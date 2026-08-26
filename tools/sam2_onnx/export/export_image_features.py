import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

import torch
import onnx

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor

class Sam2ImageFeaturesExporter(torch.nn.Module):
    def __init__(self, sam2_model):
        super().__init__()
        self.sam2_model = sam2_model
        
    def forward(self, img_batch: torch.Tensor):
        # img_batch: [B, 3, 1024, 1024]
        backbone_out = self.sam2_model.forward_image(img_batch)
        
        # backbone_fpn: [0: stride 4 (32x256x256), 1: stride 8 (64x128x128), 2: stride 16 (256x64x64)]
        high_res_0 = backbone_out['backbone_fpn'][0]
        high_res_1 = backbone_out['backbone_fpn'][1]
        top_vision_feat = backbone_out['backbone_fpn'][2]
        top_vision_pos_enc = backbone_out['vision_pos_enc'][2]
        
        return top_vision_feat, top_vision_pos_enc, high_res_0, high_res_1

def export_image_features():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    out_dir = os.path.join(root_dir, 'tools/sam2_onnx/.generated')
    os.makedirs(out_dir, exist_ok=True)
    onnx_path = os.path.join(out_dir, 'sam2_image_features.onnx')
    
    print(f'[Export] Loading SAM2 model for ImageFeatures export...')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    model = predictor
    model.eval()
    
    wrapper = Sam2ImageFeaturesExporter(model)
    wrapper.eval()
    
    dummy_input = torch.randn(1, 3, 1024, 1024, dtype=torch.float32)
    
    print(f'[Export] Exporting to {onnx_path}...')
    torch.onnx.export(
        wrapper,
        dummy_input,
        onnx_path,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=['image'],
        output_names=['top_vision_feature', 'top_vision_pos_enc', 'high_res_feature_0', 'high_res_feature_1'],
        dynamic_axes={
            'image': {0: 'batch_size'},
            'top_vision_feature': {0: 'batch_size'},
            'top_vision_pos_enc': {0: 'batch_size'},
            'high_res_feature_0': {0: 'batch_size'},
            'high_res_feature_1': {0: 'batch_size'}
        },
        dynamo=False
    )
    
    # Check ONNX model
    onnx_model = onnx.load(onnx_path)
    onnx.checker.check_model(onnx_model)
    file_size_mb = os.path.getsize(onnx_path) / (1024 * 1024)
    print(f'[Export] SUCCESS: sam2_image_features.onnx validated! Size: {file_size_mb:.2f} MB')

if __name__ == '__main__':
    export_image_features()
