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
from portable_rotary import patch_rotary_for_export

class Sam2ImageFeatures512Exporter(torch.nn.Module):
    def __init__(self, sam2_model):
        super().__init__()
        self.sam2_model = sam2_model
        
    def forward(self, img_batch: torch.Tensor):
        # img_batch: [B, 3, 512, 512]
        backbone_out = self.sam2_model.forward_image(img_batch)
        high_res_0 = backbone_out['backbone_fpn'][0]  # [B, 32, 128, 128]
        high_res_1 = backbone_out['backbone_fpn'][1]  # [B, 64, 64, 64]
        top_vision_feat = backbone_out['backbone_fpn'][2]  # [B, 256, 32, 32]
        top_vision_pos_enc = backbone_out['vision_pos_enc'][2]  # [B, 256, 32, 32]
        return top_vision_feat, top_vision_pos_enc, high_res_0, high_res_1

class Sam2InitStep512Exporter(torch.nn.Module):
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
        B = top_vision_feature.shape[0]
        device = top_vision_feature.device
        
        # 1. No mem embed
        pix_feat_with_mem = top_vision_feature.flatten(2).permute(2, 0, 1) + self.sam2_model.no_mem_embed
        pix_feat_with_mem = pix_feat_with_mem.permute(1, 2, 0).view(B, 256, 32, 32)
        
        # 2. Prompt encoder
        sparse_embeddings, dense_embeddings = self.sam2_model.sam_prompt_encoder(
            points=(point_coords, point_labels),
            boxes=None,
            masks=None
        )
        
        # Adapt dense embeddings & image_pe to 32x32
        dense_embeddings_32 = F.interpolate(dense_embeddings, size=(32, 32), mode='bilinear', align_corners=False)
        image_pe = self.sam2_model.sam_prompt_encoder.get_dense_pe()
        image_pe_32 = F.interpolate(image_pe, size=(32, 32), mode='bilinear', align_corners=False)
        high_res_features = [high_res_feature_0, high_res_feature_1]
        
        # 3. Mask decoder
        low_res_multimasks, ious, sam_output_tokens, object_score_logits = self.sam2_model.sam_mask_decoder(
            image_embeddings=pix_feat_with_mem,
            image_pe=image_pe_32,
            sparse_prompt_embeddings=sparse_embeddings,
            dense_prompt_embeddings=dense_embeddings_32,
            multimask_output=True,
            repeat_image=False,
            high_res_features=high_res_features
        )
        
        # 4. Object score & Multimask upsampling
        is_obj_appearing = object_score_logits > 0
        low_res_multimasks = torch.where(
            is_obj_appearing[:, None, None],
            low_res_multimasks,
            -1024.0
        ).float()
        
        high_res_multimasks = F.interpolate(
            low_res_multimasks,
            size=(512, 512),
            mode='bilinear',
            align_corners=False
        )
        
        best_iou_inds = torch.argmax(ious, dim=-1)
        batch_inds = torch.arange(B, device=device)
        
        low_res_masks = low_res_multimasks[batch_inds, best_iou_inds].unsqueeze(1)
        high_res_masks = high_res_multimasks[batch_inds, best_iou_inds].unsqueeze(1)
        sam_output_token = sam_output_tokens[batch_inds, best_iou_inds]
        
        # 5. Obj pointer
        obj_ptr = self.sam2_model.obj_ptr_proj(sam_output_token)
        lambda_is_obj_appearing = is_obj_appearing.float()
        if self.sam2_model.fixed_no_obj_ptr:
            obj_ptr = lambda_is_obj_appearing * obj_ptr
        obj_ptr = obj_ptr + (1.0 - lambda_is_obj_appearing) * self.sam2_model.no_obj_ptr
        
        # 6. Encode new memory
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

class Sam2TemporalStep512Exporter(torch.nn.Module):
    def __init__(self, sam2_model):
        super().__init__()
        self.sam2_model = sam2_model
        
    def forward(
        self,
        current_top_feature: torch.Tensor,
        current_top_pos: torch.Tensor,
        current_high_res_0: torch.Tensor,
        current_high_res_1: torch.Tensor,
        selected_memory_features: torch.Tensor,
        selected_memory_pos: torch.Tensor,
        memory_tpos_indices: torch.Tensor,
        selected_obj_ptrs: torch.Tensor
    ):
        B = current_top_feature.shape[0]
        C = self.sam2_model.hidden_dim  # 256
        mem_dim = self.sam2_model.mem_dim  # 64
        
        num_mem = selected_memory_features.shape[0]
        num_ptrs = selected_obj_ptrs.shape[0]
        
        # 1. Prepare memory features: [num_mem, 1, 64, 32, 32] -> [num_mem * 1024, 1, 64]
        mem_flat = selected_memory_features.flatten(3).permute(0, 3, 1, 2).reshape(-1, 1, mem_dim)
        
        # 2. Prepare memory pos
        maskmem_tpos = self.sam2_model.maskmem_tpos_enc[memory_tpos_indices]  # [num_mem, 1, 1, 64]
        pos_flat = selected_memory_pos.flatten(3).permute(0, 3, 1, 2)  # [num_mem, 1024, 1, 64]
        pos_flat = (pos_flat + maskmem_tpos).reshape(-1, 1, mem_dim)  # [num_mem * 1024, 1, 64]
        
        # 3. Prepare object pointers
        num_tokens_per_ptr = C // mem_dim  # 4
        ptrs_4d = selected_obj_ptrs.reshape(num_ptrs, 1, num_tokens_per_ptr, mem_dim)
        ptrs_flat = ptrs_4d.permute(0, 2, 1, 3).reshape(num_ptrs * num_tokens_per_ptr, 1, mem_dim)
        pos_ptrs = torch.zeros_like(ptrs_flat)
        num_obj_ptr_tokens = ptrs_flat.shape[0]
        
        # 4. Concatenate memories and pointers
        total_memory = torch.cat([mem_flat, ptrs_flat], dim=0)
        total_memory_pos = torch.cat([pos_flat, pos_ptrs], dim=0)
        
        # 5. Memory attention (1024 tokens instead of 4096!)
        curr = current_top_feature.flatten(2).permute(2, 0, 1)  # [1024, 1, 256]
        curr_pos = current_top_pos.flatten(2).permute(2, 0, 1)  # [1024, 1, 256]
        
        pix_feat_with_mem = self.sam2_model.memory_attention(
            curr=curr,
            curr_pos=curr_pos,
            memory=total_memory,
            memory_pos=total_memory_pos,
            num_obj_ptr_tokens=num_obj_ptr_tokens
        )
        pix_feat_with_mem = pix_feat_with_mem.permute(1, 2, 0).view(B, C, 32, 32)
        
        # 6. SAM Heads
        sparse_embeddings, dense_embeddings = self.sam2_model.sam_prompt_encoder(
            points=None,
            boxes=None,
            masks=None
        )
        dense_embeddings_32 = F.interpolate(dense_embeddings, size=(32, 32), mode='bilinear', align_corners=False)
        image_pe = self.sam2_model.sam_prompt_encoder.get_dense_pe()
        image_pe_32 = F.interpolate(image_pe, size=(32, 32), mode='bilinear', align_corners=False)
        high_res_features = [current_high_res_0, current_high_res_1]
        
        low_res_multimasks, ious, sam_output_tokens, object_score_logits = self.sam2_model.sam_mask_decoder(
            image_embeddings=pix_feat_with_mem,
            image_pe=image_pe_32,
            sparse_prompt_embeddings=sparse_embeddings,
            dense_prompt_embeddings=dense_embeddings_32,
            multimask_output=True,
            repeat_image=False,
            high_res_features=high_res_features
        )
        
        # 7. Selection & upsampling to 512x512
        is_obj_appearing = object_score_logits > 0
        low_res_multimasks = torch.where(
            is_obj_appearing[:, None, None],
            low_res_multimasks,
            -1024.0
        ).float()
        
        high_res_multimasks = F.interpolate(
            low_res_multimasks,
            size=(512, 512),
            mode='bilinear',
            align_corners=False
        )
        
        best_iou_inds = torch.argmax(ious, dim=-1)
        batch_inds = torch.arange(B, device=current_top_feature.device)
        
        low_res_masks = low_res_multimasks[batch_inds, best_iou_inds].unsqueeze(1)
        high_res_masks = high_res_multimasks[batch_inds, best_iou_inds].unsqueeze(1)
        sam_output_token = sam_output_tokens[batch_inds, best_iou_inds]
        
        # 8. Obj pointer projection
        obj_ptr = self.sam2_model.obj_ptr_proj(sam_output_token)
        lambda_is_obj_appearing = is_obj_appearing.float()
        if self.sam2_model.fixed_no_obj_ptr:
            obj_ptr = lambda_is_obj_appearing * obj_ptr
        obj_ptr = obj_ptr + (1.0 - lambda_is_obj_appearing) * self.sam2_model.no_obj_ptr
        
        # 9. Encode new memory
        mask_for_mem = torch.sigmoid(high_res_masks)
        mask_for_mem = mask_for_mem * self.sam2_model.sigmoid_scale_for_mem_enc + self.sam2_model.sigmoid_bias_for_mem_enc
        
        maskmem_out = self.sam2_model.memory_encoder(
            current_top_feature,
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

def export_all_512():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    out_dir = os.path.join(root_dir, 'tools/sam2_onnx/.generated_512')
    os.makedirs(out_dir, exist_ok=True)
    
    print('[512 Export] Building SAM2 model...')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    model = predictor
    model.eval()
    
    # 1. Image Features 512
    p_img = os.path.join(out_dir, 'sam2_image_features.onnx')
    print(f'[512 Export] Exporting {p_img}...')
    wrap_img = Sam2ImageFeatures512Exporter(model).eval()
    torch.onnx.export(
        wrap_img,
        torch.randn(1, 3, 512, 512),
        p_img,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=['image'],
        output_names=['top_vision_feature', 'top_vision_pos_enc', 'high_res_feature_0', 'high_res_feature_1'],
        dynamic_axes={'image': {0: 'batch_size'}},
        dynamo=False
    )
    onnx.checker.check_model(onnx.load(p_img))
    print(f'[512 Export] OK: ImageFeatures 512 ({os.path.getsize(p_img)/1024/1024:.2f} MB)')
    
    # 2. Init Step 512
    p_init = os.path.join(out_dir, 'sam2_init_step.onnx')
    print(f'[512 Export] Exporting {p_init}...')
    wrap_init = Sam2InitStep512Exporter(model).eval()
    torch.onnx.export(
        wrap_init,
        (
            torch.randn(1, 256, 32, 32),
            torch.randn(1, 32, 128, 128),
            torch.randn(1, 64, 64, 64),
            torch.tensor([[[77.0, 40.0], [181.0, 240.0]]], dtype=torch.float32),
            torch.tensor([[2, 3]], dtype=torch.int32)
        ),
        p_init,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=['top_vision_feature', 'high_res_feature_0', 'high_res_feature_1', 'point_coords', 'point_labels'],
        output_names=['low_res_mask', 'high_res_mask', 'object_score_logits', 'obj_ptr', 'memory_features', 'memory_pos_enc'],
        dynamic_axes={'point_coords': {1: 'num_points'}, 'point_labels': {1: 'num_points'}},
        dynamo=False
    )
    onnx.checker.check_model(onnx.load(p_init))
    print(f'[512 Export] OK: InitStep 512 ({os.path.getsize(p_init)/1024/1024:.2f} MB)')
    
    # 3. Temporal Step 512
    p_temp = os.path.join(out_dir, 'sam2_temporal_step.onnx')
    print(f'[512 Export] Exporting {p_temp}...')
    wrap_temp = Sam2TemporalStep512Exporter(model).eval()
    with patch_rotary_for_export(model):
        torch.onnx.export(
            wrap_temp,
            (
                torch.randn(1, 256, 32, 32),
                torch.randn(1, 256, 32, 32),
                torch.randn(1, 32, 128, 128),
                torch.randn(1, 64, 64, 64),
                torch.randn(2, 1, 64, 32, 32),
                torch.randn(2, 1, 64, 32, 32),
                torch.tensor([6, 0], dtype=torch.int64),
                torch.randn(1, 1, 256)
            ),
            p_temp,
            export_params=True,
            opset_version=17,
            do_constant_folding=True,
            input_names=[
                'current_top_feature', 'current_top_pos', 'current_high_res_0', 'current_high_res_1',
                'selected_memory_features', 'selected_memory_pos', 'memory_tpos_indices', 'selected_obj_ptrs'
            ],
            output_names=['low_res_mask', 'high_res_mask', 'object_score_logits', 'obj_ptr', 'memory_features', 'memory_pos_enc'],
            dynamic_axes={
                'selected_memory_features': {0: 'num_memories'},
                'selected_memory_pos': {0: 'num_memories'},
                'memory_tpos_indices': {0: 'num_memories'},
                'selected_obj_ptrs': {0: 'num_ptrs'}
            },
            dynamo=False
        )
    onnx.checker.check_model(onnx.load(p_temp))
    print(f'[512 Export] OK: TemporalStep 512 ({os.path.getsize(p_temp)/1024/1024:.2f} MB)')

if __name__ == '__main__':
    export_all_512()
