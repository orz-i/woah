import os
import sys
sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

import torch
import torch.nn.functional as F
import onnx

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor

class Sam2InitStepExporter(torch.nn.Module):
    def __init__(self, sam2_model):
        super().__init__()
        self.sam2_model = sam2_model
        
    def forward(
        self,
        top_vision_feature: torch.Tensor,
        high_res_feature_0: torch.Tensor,
        high_res_feature_1: torch.Tensor,
        point_coords: torch.Tensor,
        point_labels: torch.Tensor
    ):
        # top_vision_feature: [B, 256, 64, 64]
        # high_res_feature_0: [B, 32, 256, 256]
        # high_res_feature_1: [B, 64, 128, 128]
        # point_coords: [B, N, 2]
        # point_labels: [B, N]
        B = top_vision_feature.shape[0]
        device = top_vision_feature.device
        
        # 1. Directly add no mem embed
        pix_feat_with_mem = top_vision_feature.flatten(2).permute(2, 0, 1) + self.sam2_model.no_mem_embed
        pix_feat_with_mem = pix_feat_with_mem.permute(1, 2, 0).view(B, 256, 64, 64)
        
        # 2. Prompt encoder
        sparse_embeddings, dense_embeddings = self.sam2_model.sam_prompt_encoder(
            points=(point_coords, point_labels),
            boxes=None,
            masks=None
        )
        
        # 3. Mask decoder
        image_pe = self.sam2_model.sam_prompt_encoder.get_dense_pe()
        high_res_features = [high_res_feature_0, high_res_feature_1]
        
        low_res_multimasks, ious, sam_output_tokens, object_score_logits = self.sam2_model.sam_mask_decoder(
            image_embeddings=pix_feat_with_mem,
            image_pe=image_pe,
            sparse_prompt_embeddings=sparse_embeddings,
            dense_prompt_embeddings=dense_embeddings,
            multimask_output=True,
            repeat_image=False,
            high_res_features=high_res_features
        )
        
        # 4. Object score handling
        is_obj_appearing = object_score_logits > 0
        low_res_multimasks = torch.where(
            is_obj_appearing[:, None, None],
            low_res_multimasks,
            -1024.0
        )
        low_res_multimasks = low_res_multimasks.float()
        
        # Upsample multimasks
        high_res_multimasks = F.interpolate(
            low_res_multimasks,
            size=(self.sam2_model.image_size, self.sam2_model.image_size),
            mode='bilinear',
            align_corners=False
        )
        
        # Multimask selection (best iou index)
        best_iou_inds = torch.argmax(ious, dim=-1)
        batch_inds = torch.arange(B, device=device)
        
        low_res_masks = low_res_multimasks[batch_inds, best_iou_inds].unsqueeze(1)
        high_res_masks = high_res_multimasks[batch_inds, best_iou_inds].unsqueeze(1)
        sam_output_token = sam_output_tokens[batch_inds, best_iou_inds]
        
        # 5. Extract obj_ptr
        obj_ptr = self.sam2_model.obj_ptr_proj(sam_output_token)
        lambda_is_obj_appearing = is_obj_appearing.float()
        if self.sam2_model.fixed_no_obj_ptr:
            obj_ptr = lambda_is_obj_appearing * obj_ptr
        obj_ptr = obj_ptr + (1.0 - lambda_is_obj_appearing) * self.sam2_model.no_obj_ptr
        
        # 6. Encode new memory
        # Frame 0 is from points/box -> binarize_mask_from_pts_for_mem_enc = True
        mask_for_mem = (high_res_masks > 0).float()
        mask_for_mem = mask_for_mem * self.sam2_model.sigmoid_scale_for_mem_enc + self.sam2_model.sigmoid_bias_for_mem_enc
        
        maskmem_out = self.sam2_model.memory_encoder(
            top_vision_feature,
            mask_for_mem,
            skip_mask_sigmoid=True
        )
        memory_features = maskmem_out['vision_features']
        memory_pos_enc = maskmem_out['vision_pos_enc'][-1]
        
        return (
            low_res_masks,
            high_res_masks,
            object_score_logits,
            obj_ptr,
            memory_features,
            memory_pos_enc
        )

def export_init_step():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    out_dir = os.path.join(root_dir, 'tools/sam2_onnx/.generated')
    os.makedirs(out_dir, exist_ok=True)
    onnx_path = os.path.join(out_dir, 'sam2_init_step.onnx')
    
    print(f'[Export] Loading SAM2 model for InitStep export...')
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
    
    print(f'[Export] Exporting to {onnx_path}...')
    torch.onnx.export(
        wrapper,
        (dummy_top, dummy_high0, dummy_high1, dummy_pts, dummy_labels),
        onnx_path,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=[
            'top_vision_feature',
            'high_res_feature_0',
            'high_res_feature_1',
            'point_coords',
            'point_labels'
        ],
        output_names=[
            'low_res_mask',
            'high_res_mask',
            'object_score_logits',
            'obj_ptr',
            'memory_features',
            'memory_pos_enc'
        ],
        dynamic_axes={
            'top_vision_feature': {0: 'batch_size'},
            'high_res_feature_0': {0: 'batch_size'},
            'high_res_feature_1': {0: 'batch_size'},
            'point_coords': {0: 'batch_size', 1: 'num_points'},
            'point_labels': {0: 'batch_size', 1: 'num_points'},
            'low_res_mask': {0: 'batch_size'},
            'high_res_mask': {0: 'batch_size'},
            'object_score_logits': {0: 'batch_size'},
            'obj_ptr': {0: 'batch_size'},
            'memory_features': {0: 'batch_size'},
            'memory_pos_enc': {0: 'batch_size'}
        },
        dynamo=False
    )
    
    # Check ONNX model
    onnx_model = onnx.load(onnx_path)
    onnx.checker.check_model(onnx_model)
    file_size_mb = os.path.getsize(onnx_path) / (1024 * 1024)
    print(f'[Export] SUCCESS: sam2_init_step.onnx validated! Size: {file_size_mb:.2f} MB')

if __name__ == '__main__':
    export_init_step()
