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
from tools.sam2_onnx.export.export_init_step import Sam2InitStepExporter

def export_and_verify_init_step():
    import litert_torch
    import ai_edge_litert.interpreter as litert_interp

    root_dir = ROOT_PATH
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    out_dir = os.path.join(root_dir, 'models/litert')
    os.makedirs(out_dir, exist_ok=True)
    tflite_path = os.path.join(out_dir, 'sam2_init_step.tflite')

    print(f'[SAM2 InitStep] Loading PyTorch model from {ckpt_path}...')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    model = predictor
    model.eval()

    wrapper = Sam2InitStepExporter(model)
    wrapper.eval()

    dummy_top = torch.randn(1, 256, 64, 64, dtype=torch.float32)
    dummy_high0 = torch.randn(1, 32, 256, 256, dtype=torch.float32)
    dummy_high1 = torch.randn(1, 64, 128, 128, dtype=torch.float32)
    dummy_pts = torch.tensor([[[154.0, 80.0], [362.0, 479.0]]], dtype=torch.float32)
    dummy_labels = torch.tensor([[2, 3]], dtype=torch.int32)

    sample_inputs = (dummy_top, dummy_high0, dummy_high1, dummy_pts, dummy_labels)

    print(f'[SAM2 InitStep] Running PyTorch reference forward pass...')
    with torch.no_grad():
        pt_outs = wrapper(*sample_inputs)

    print(f'[SAM2 InitStep] Converting via litert_torch.convert()...')
    edge_model = litert_torch.convert(wrapper, sample_inputs)

    print(f'[SAM2 InitStep] Exporting to {tflite_path}...')
    edge_model.export(tflite_path)
    file_size_mb = os.path.getsize(tflite_path) / (1024 * 1024)
    print(f'[SAM2 InitStep] SUCCESS: Saved {tflite_path} ({file_size_mb:.2f} MB)')

    # Verify LiteRT interpreter and numerical parity
    print(f'[SAM2 InitStep] Verifying LiteRT interpreter and numerical parity...')
    interp = litert_interp.Interpreter(model_path=tflite_path)
    interp.allocate_tensors()

    in_details = interp.get_input_details()
    out_details = interp.get_output_details()

    for idx, inp in enumerate(sample_inputs):
        interp.set_tensor(in_details[idx]['index'], inp.numpy())
    
    interp.invoke()

    print(f'LiteRT Inputs: {[d["shape"].tolist() for d in in_details]}')
    print(f'LiteRT Outputs: {[d["shape"].tolist() for d in out_details]}')

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
    print(f'[Gate S2: init_step] overall_max_diff={overall_max_diff:.6f}, PASS={pass_gate}')
    return pass_gate

if __name__ == '__main__':
    if not export_and_verify_init_step():
        sys.exit(1)
