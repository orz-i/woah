import os
import sys
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

def export_and_verify_image_features():
    import litert_torch
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

    sample_input = (torch.randn(1, 3, 1024, 1024, dtype=torch.float32),)

    print(f'[SAM2 ImageFeatures] Running PyTorch reference forward pass...')
    with torch.no_grad():
        pt_outs = wrapper(*sample_input)

    print(f'[SAM2 ImageFeatures] Converting via litert_torch.convert()...')
    edge_model = litert_torch.convert(wrapper, sample_input)

    print(f'[SAM2 ImageFeatures] Exporting to {tflite_path}...')
    edge_model.export(tflite_path)
    file_size_mb = os.path.getsize(tflite_path) / (1024 * 1024)
    print(f'[SAM2 ImageFeatures] SUCCESS: Saved {tflite_path} ({file_size_mb:.2f} MB)')

    # Verify LiteRT execution and numerical parity
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
    if not export_and_verify_image_features():
        sys.exit(1)
