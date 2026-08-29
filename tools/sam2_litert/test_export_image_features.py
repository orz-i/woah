import os
import sys
import argparse
import hashlib
import json
import platform
from importlib import metadata
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
from tools.sam2_litert.analyze_model_graph import analyze_tflite_model

def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def _static_gpu_gate(model_path: str):
    """Reject known LiteRT GPU blockers before a candidate can replace the stable asset."""
    report = analyze_tflite_model(model_path, allocate_tensors=False)
    parser_available = bool(report.get('operator_parser_available'))
    blockers = report.get('gpu_blocker_candidates', []) if parser_available else []
    blocker_counts = {}
    for blocker in blockers:
        opcode = blocker.get('opcode', 'UNKNOWN')
        blocker_counts[opcode] = blocker_counts.get(opcode, 0) + 1

    result = {
        'parser_available': parser_available,
        'interpreter_backend': report.get('interpreter_backend'),
        'total_operators': report.get('total_operators'),
        'gpu_blocker_candidate_count': len(blockers),
        'gpu_blocker_counts': blocker_counts,
        'analysis_note': report.get('tf_lite_interpreter_note') or report.get('ops_details_note'),
    }
    return parser_available and not blockers, result


def _write_manifest(path: str, manifest: dict) -> None:
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2)


def _package_version(distribution_name: str) -> str:
    try:
        return metadata.version(distribution_name)
    except metadata.PackageNotFoundError:
        return 'not-installed'


def export_and_verify_image_features(
    run_reference: bool = False,
    run_litert_verify: bool = False,
    promote: bool = False,
):
    if promote and not run_litert_verify:
        print(
            '[SAM2 ImageFeatures] REFUSING promotion: --promote requires '
            '--verify-litert so the candidate passes static GPU checks and CPU parity first.'
        )
        return False

    import litert_torch
    litert_interp = None
    if run_litert_verify:
        import ai_edge_litert.interpreter as litert_interp

    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    out_dir = os.path.join(root_dir, 'models/litert')
    os.makedirs(out_dir, exist_ok=True)
    stable_path = os.path.join(out_dir, 'sam2_image_features.tflite')
    candidate_path = os.path.join(out_dir, 'sam2_image_features.candidate.tflite')
    candidate_manifest_path = candidate_path + '.manifest.json'
    stable_manifest_path = stable_path + '.manifest.json'

    # Never let a failed conversion look like a fresh candidate from this run.
    for stale_candidate in (candidate_path, candidate_manifest_path):
        if os.path.exists(stale_candidate):
            os.remove(stale_candidate)

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

    print(f'[SAM2 ImageFeatures] Exporting candidate to {candidate_path}...')
    edge_model.export(candidate_path)
    file_size_mb = os.path.getsize(candidate_path) / (1024 * 1024)
    model_sha256 = _sha256(candidate_path)
    print(
        f'[SAM2 ImageFeatures] Candidate saved: {candidate_path} '
        f'({file_size_mb:.2f} MB) sha256={model_sha256}'
    )

    print('[SAM2 ImageFeatures] Running static LiteRT GPU blocker gate...')
    static_gpu_clean, static_gpu_gate = _static_gpu_gate(candidate_path)
    print(
        '[SAM2 ImageFeatures] Static GPU gate: '
        f'parser={static_gpu_gate["parser_available"]}, '
        f'blockers={static_gpu_gate["gpu_blocker_candidate_count"]}, '
        f'counts={static_gpu_gate["gpu_blocker_counts"]}'
    )

    manifest = {
        'model': os.path.basename(candidate_path),
        'sha256': model_sha256,
        'bytes': os.path.getsize(candidate_path),
        'exported_at_utc': datetime.now(timezone.utc).isoformat(),
        'source_rewrites': [
            'MultiScaleAttention 4D qkv/chunk',
            'PositionEmbeddingSine rank<=4 parity-mask sin/cos',
            'Hiera fixed-resolution positional embedding export constant',
            'Hiera rank<=4 window partition/unpartition',
            'MultiScaleAttention 2D projection linear',
        ],
        'toolchain': {
            'python': platform.python_version(),
            'platform': f'{platform.system()} {platform.machine()}',
            'torch': torch.__version__,
            'litert_torch': _package_version('litert-torch'),
            'ai_edge_litert': _package_version('ai-edge-litert'),
        },
        'reference_forward_run': bool(run_reference or run_litert_verify),
        'litert_cpu_verify_run': run_litert_verify,
        'static_gpu_gate': static_gpu_gate,
        'candidate_status': (
            'STATIC_GPU_CLEAN_AWAITING_CPU_PARITY'
            if static_gpu_clean
            else 'STATIC_GPU_GATE_FAILED'
        ),
        'device_gpu_verification': 'NOT_VERIFIED_ON_DEVICE',
        'promoted_to_stable_asset': False,
    }
    _write_manifest(candidate_manifest_path, manifest)
    print(f'[SAM2 ImageFeatures] Wrote provenance manifest: {candidate_manifest_path}')

    if not static_gpu_clean:
        print(
            '[SAM2 ImageFeatures] REJECTED candidate: static GPU blocker gate failed. '
            f'Stable asset remains untouched: {stable_path}'
        )
        return False

    # Verify LiteRT execution and numerical parity
    if not run_litert_verify:
        print('[SAM2 ImageFeatures] Skipping LiteRT CPU execution/parity verification.')
        manifest['candidate_status'] = 'STATIC_GPU_CLEAN_UNVERIFIED_CANDIDATE'
        _write_manifest(candidate_manifest_path, manifest)
        print('[SAM2 ImageFeatures] Candidate status: STATIC_GPU_CLEAN_UNVERIFIED_CANDIDATE')
        print(f'[SAM2 ImageFeatures] Stable asset remains untouched: {stable_path}')
        return True

    if pt_outs is None:
        print('[SAM2 ImageFeatures] LiteRT parity requires a PyTorch reference; running it now...')
        with torch.no_grad():
            pt_outs = wrapper(*sample_input)

    print(f'[SAM2 ImageFeatures] Verifying LiteRT interpreter and numerical parity...')
    interp = litert_interp.Interpreter(model_path=candidate_path)
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
    manifest['litert_cpu_parity'] = {
        'overall_max_abs_diff': float(overall_max_diff),
        'threshold': 0.01,
        'passed': bool(pass_gate),
    }
    if not pass_gate:
        manifest['candidate_status'] = 'LITERT_CPU_PARITY_FAILED'
        _write_manifest(candidate_manifest_path, manifest)
        print(
            '[SAM2 ImageFeatures] REJECTED candidate: LiteRT CPU parity failed. '
            f'Stable asset remains untouched: {stable_path}'
        )
        return False

    manifest['candidate_status'] = 'STATIC_GPU_CLEAN_CPU_PARITY_OK'
    _write_manifest(candidate_manifest_path, manifest)

    if not promote:
        print('[SAM2 ImageFeatures] Candidate passed static GPU gate and LiteRT CPU parity.')
        print(
            '[SAM2 ImageFeatures] Not promoted because --promote was not requested. '
            f'Stable asset remains untouched: {stable_path}'
        )
        return True

    manifest['model'] = os.path.basename(stable_path)
    manifest['candidate_status'] = 'PROMOTED_AWAITING_DEVICE_GPU_VERIFICATION'
    manifest['promoted_to_stable_asset'] = True
    _write_manifest(candidate_manifest_path, manifest)
    os.replace(candidate_path, stable_path)
    os.replace(candidate_manifest_path, stable_manifest_path)
    print(f'[SAM2 ImageFeatures] PROMOTED candidate atomically to {stable_path}')
    print(
        '[SAM2 ImageFeatures] Device GPU verification is still REQUIRED; '
        'SAM2 must remain hidden unless Android compile/run/readback succeeds.'
    )
    return True

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
    parser.add_argument(
        '--promote',
        action='store_true',
        help=(
            'Atomically replace the stable sam2_image_features.tflite only after '
            'the candidate passes the static GPU blocker gate and LiteRT CPU parity. '
            'Requires --verify-litert; device GPU verification remains mandatory.'
        ),
    )
    args = parser.parse_args()

    if not export_and_verify_image_features(
        run_reference=args.reference,
        run_litert_verify=args.verify_litert,
        promote=args.promote,
    ):
        sys.exit(1)
