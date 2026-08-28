import os
import sys
import argparse
import hashlib
import json
from datetime import datetime, timezone
import torch
import numpy as np

ROOT_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
if ROOT_PATH not in sys.path:
    sys.path.insert(0, ROOT_PATH)

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor
from tools.sam2_onnx.export.export_image_features import Sam2ImageFeaturesExporter

def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def export_and_verify_image_features(
    run_reference: bool = False,
    run_litert_verify: bool = False,
):
    import litert_torch
    litert_interp = None
    if run_litert_verify:
        import ai_edge_litert.interpreter as litert_interp

    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    out_dir = os.path.join(root_dir, 'models/litert')
    os.makedirs(out_dir, exist_ok=True)
    tflite_path = os.path.join(out_dir, 'sam2_image_features.tflite')

    print(f'[SAM2 ImageFeatures] Loading PyTorch model from {ckpt_path}...')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    model = predictor
    model.eval()

    wrapper = Sam2ImageFeaturesExporter(model)
    wrapper.eval()

    # The LiteRT image-feature contract is fixed at 1024x1024 input, yielding a
    # 256x256 Hiera trunk grid. Precompute the learned positional embedding after
    # checkpoint loading so the converter sees an exact constant instead of
    # lowering bicubic resize/repeat into GPU-unsupported GATHER_ND/BROADCAST_TO.
    trunk = getattr(getattr(model, 'image_encoder', None), 'trunk', None)
    if trunk is not None and hasattr(trunk, 'prepare_litert_export_pos_embed'):
        trunk.prepare_litert_export_pos_embed((256, 256))
        print('[SAM2 ImageFeatures] Prepared exact 256x256 positional-embedding export constant.')

    sample_input = (torch.randn(1, 3, 1024, 1024, dtype=torch.float32),)

    pt_outs = None
    if run_reference:
        print('[SAM2 ImageFeatures] Running PyTorch reference forward pass...')
        with torch.no_grad():
            pt_outs = wrapper(*sample_input)
    else:
        print('[SAM2 ImageFeatures] Skipping PyTorch reference forward pass.')

    print(f'[SAM2 ImageFeatures] Converting via litert_torch.convert()...')
    edge_model = litert_torch.convert(wrapper, sample_input)

    print(f'[SAM2 ImageFeatures] Exporting to {tflite_path}...')
    edge_model.export(tflite_path)
    file_size_mb = os.path.getsize(tflite_path) / (1024 * 1024)
    model_sha256 = _sha256(tflite_path)
    print(f'[SAM2 ImageFeatures] SUCCESS: Saved {tflite_path} ({file_size_mb:.2f} MB) sha256={model_sha256}')

    manifest_path = tflite_path + '.manifest.json'
    manifest = {
        'model': os.path.basename(tflite_path),
        'sha256': model_sha256,
        'bytes': os.path.getsize(tflite_path),
        'exported_at_utc': datetime.now(timezone.utc).isoformat(),
        'source_rewrites': [
            'MultiScaleAttention 4D qkv/chunk',
            'PositionEmbeddingSine rank<=4 parity-mask sin/cos',
            'Hiera fixed-resolution positional embedding export constant',
            'Hiera rank<=4 window partition/unpartition',
            'MultiScaleAttention 2D projection linear',
        ],
        'reference_forward_run': run_reference,
        'litert_cpu_verify_run': run_litert_verify,
        'candidate_status': 'EXPORTED_UNVERIFIED_CANDIDATE',
        'device_gpu_verification': 'NOT_VERIFIED_ON_DEVICE',
    }
    with open(manifest_path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2)
    print(f'[SAM2 ImageFeatures] Wrote provenance manifest: {manifest_path}')

    # Verify LiteRT execution and numerical parity
    if not run_litert_verify:
        print('[SAM2 ImageFeatures] Skipping LiteRT CPU execution/parity verification.')
        print('[SAM2 ImageFeatures] Candidate status: EXPORTED_UNVERIFIED_CANDIDATE')
        return True

    if pt_outs is None:
        print('[SAM2 ImageFeatures] LiteRT parity requires a PyTorch reference; running it now...')
        with torch.no_grad():
            pt_outs = wrapper(*sample_input)

    print(f'[SAM2 ImageFeatures] Verifying LiteRT interpreter and numerical parity...')
    interp = litert_interp.Interpreter(model_path=tflite_path)
    interp.allocate_tensors()

    in_details = interp.get_input_details()
    out_details = interp.get_output_details()

    interp.set_tensor(in_details[0]['index'], sample_input[0].numpy())
    interp.invoke()

    print(f'LiteRT Inputs: {[d["shape"].tolist() for d in in_details]}')
    print(f'LiteRT Outputs: {[d["shape"].tolist() for d in out_details]}')

    # Compare outputs
    max_diffs = []
    for i, pt_out in enumerate(pt_outs):
        tf_out = interp.get_tensor(out_details[i]['index'])
        pt_np = pt_out.numpy()
        diff = np.max(np.abs(pt_np - tf_out))
        mean_diff = np.mean(np.abs(pt_np - tf_out))
        max_diffs.append(diff)
        print(f'Output[{i}] shape={tf_out.shape} max_abs_diff={diff:.6f} mean_abs_diff={mean_diff:.6f}')

    overall_max_diff = max(max_diffs)
    pass_gate = overall_max_diff < 0.01
    print(f'[Gate S1: image_features] overall_max_diff={overall_max_diff:.6f}, PASS={pass_gate}')
    return pass_gate

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Export SAM2 image_features LiteRT candidate with optional heavyweight verification stages.'
    )
    parser.add_argument(
        '--reference',
        action='store_true',
        help='Run the full PyTorch image encoder reference forward pass before export.',
    )
    parser.add_argument(
        '--verify-litert',
        action='store_true',
        help='Run LiteRT CPU inference and numerical parity after export (implies reference output).',
    )
    args = parser.parse_args()

    if not export_and_verify_image_features(
        run_reference=args.reference,
        run_litert_verify=args.verify_litert,
    ):
        sys.exit(1)
