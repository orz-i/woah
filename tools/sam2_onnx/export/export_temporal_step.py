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

class Sam2TemporalStepExporter(torch.nn.Module):
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
        # current_top_feature: [1, 256, 64, 64]
        # current_top_pos: [1, 256, 64, 64]
        # current_high_res_0: [1, 32, 256, 256]
        # current_high_res_1: [1, 64, 128, 128]
        # selected_memory_features: [num_mem, 1, 64, 64, 64]
        # selected_memory_pos: [num_mem, 1, 64, 64, 64]
        # memory_tpos_indices: [num_mem] (indices into maskmem_tpos_enc)
        # selected_obj_ptrs: [num_ptrs, 1, 256]
        
        B = current_top_feature.shape[0]
        C = self.sam2_model.hidden_dim  # 256
        mem_dim = self.sam2_model.mem_dim  # 64
        
        num_mem = selected_memory_features.shape[0]
        num_ptrs = selected_obj_ptrs.shape[0]
        
        # 1. Prepare memory features: [num_mem, 1, 64, 64, 64] -> [num_mem * 4096, 1, 64]
        mem_flat = selected_memory_features.flatten(3).permute(0, 3, 1, 2).reshape(-1, 1, mem_dim)
        
        # 2. Prepare memory pos: add maskmem_tpos_enc
        maskmem_tpos = self.sam2_model.maskmem_tpos_enc[memory_tpos_indices]  # [num_mem, 1, 1, 64]
        pos_flat = selected_memory_pos.flatten(3).permute(0, 3, 1, 2)  # [num_mem, 4096, 1, 64]
        pos_flat = (pos_flat + maskmem_tpos).reshape(-1, 1, mem_dim)  # [num_mem * 4096, 1, 64]
        
        # 3. Prepare object pointers: [num_ptrs, 1, 256] -> [num_ptrs * 4, 1, 64]
        num_tokens_per_ptr = C // mem_dim  # 4
        ptrs_4d = selected_obj_ptrs.reshape(num_ptrs, 1, num_tokens_per_ptr, mem_dim)
        ptrs_flat = ptrs_4d.permute(0, 2, 1, 3).reshape(num_ptrs * num_tokens_per_ptr, 1, mem_dim)
        pos_ptrs = torch.zeros_like(ptrs_flat)
        num_obj_ptr_tokens = ptrs_flat.shape[0]
        
        # 4. Concatenate memories and pointers
        total_memory = torch.cat([mem_flat, ptrs_flat], dim=0)
        total_memory_pos = torch.cat([pos_flat, pos_ptrs], dim=0)
        
        # 5. Memory attention
        curr = current_top_feature.flatten(2).permute(2, 0, 1)  # [4096, 1, 256]
        curr_pos = current_top_pos.flatten(2).permute(2, 0, 1)  # [4096, 1, 256]
        
        pix_feat_with_mem = self.sam2_model.memory_attention(
            curr=curr,
            curr_pos=curr_pos,
            memory=total_memory,
            memory_pos=total_memory_pos,
            num_obj_ptr_tokens=num_obj_ptr_tokens
        )
        pix_feat_with_mem = pix_feat_with_mem.permute(1, 2, 0).view(B, C, 64, 64)
        
        # 6. SAM Heads (no prompt input on tracking step)
        sparse_embeddings, dense_embeddings = self.sam2_model.sam_prompt_encoder(
            points=None,
            boxes=None,
            masks=None
        )
        image_pe = self.sam2_model.sam_prompt_encoder.get_dense_pe()
        high_res_features = [current_high_res_0, current_high_res_1]
        
        low_res_multimasks, ious, sam_output_tokens, object_score_logits = self.sam2_model.sam_mask_decoder(
            image_embeddings=pix_feat_with_mem,
            image_pe=image_pe,
            sparse_prompt_embeddings=sparse_embeddings,
            dense_prompt_embeddings=dense_embeddings,
            multimask_output=True,
            repeat_image=False,
            high_res_features=high_res_features
        )
        
        # 7. Object score and multimask selection
        is_obj_appearing = object_score_logits > 0
        low_res_multimasks = torch.where(
            is_obj_appearing[:, None, None],
            low_res_multimasks,
            -1024.0
        )
        low_res_multimasks = low_res_multimasks.float()
        
        high_res_multimasks = F.interpolate(
            low_res_multimasks,
            size=(self.sam2_model.image_size, self.sam2_model.image_size),
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
        
        # 9. Encode new memory (tracking step: is_mask_from_pts = False)
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

def export_temporal_step():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    out_dir = os.path.join(root_dir, 'tools/sam2_onnx/.generated')
    os.makedirs(out_dir, exist_ok=True)
    onnx_path = os.path.join(out_dir, 'sam2_temporal_step.onnx')
    
    print(f'[Export] Loading SAM2 model for TemporalStep export...')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    model = predictor
    model.eval()
    
    wrapper = Sam2TemporalStepExporter(model)
    wrapper.eval()
    
    dummy_top = torch.randn(1, 256, 64, 64, dtype=torch.float32)
    dummy_top_pos = torch.randn(1, 256, 64, 64, dtype=torch.float32)
    dummy_high0 = torch.randn(1, 32, 256, 256, dtype=torch.float32)
    dummy_high1 = torch.randn(1, 64, 128, 128, dtype=torch.float32)
    dummy_mem_feats = torch.randn(2, 1, 64, 64, 64, dtype=torch.float32)
    dummy_mem_pos = torch.randn(2, 1, 64, 64, 64, dtype=torch.float32)
    dummy_tpos_inds = torch.tensor([6, 0], dtype=torch.int64)
    dummy_ptrs = torch.randn(1, 1, 256, dtype=torch.float32)
    
    print(f'[Export] Exporting to {onnx_path} with patched RoPE and patched freqs_cis...')
    with patch_rotary_for_export(model):
        torch.onnx.export(
            wrapper,
            (
                dummy_top,
                dummy_top_pos,
                dummy_high0,
                dummy_high1,
                dummy_mem_feats,
                dummy_mem_pos,
                dummy_tpos_inds,
                dummy_ptrs
            ),
            onnx_path,
            export_params=True,
            opset_version=17,
            do_constant_folding=True,
            input_names=[
                'current_top_feature',
                'current_top_pos',
                'current_high_res_0',
                'current_high_res_1',
                'selected_memory_features',
                'selected_memory_pos',
                'memory_tpos_indices',
                'selected_obj_ptrs'
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
                'current_top_feature': {0: 'batch_size'},
                'current_top_pos': {0: 'batch_size'},
                'current_high_res_0': {0: 'batch_size'},
                'current_high_res_1': {0: 'batch_size'},
                'selected_memory_features': {0: 'num_memories', 1: 'batch_size'},
                'selected_memory_pos': {0: 'num_memories', 1: 'batch_size'},
                'memory_tpos_indices': {0: 'num_memories'},
                'selected_obj_ptrs': {0: 'num_ptrs', 1: 'batch_size'},
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
    print(f'[Export] SUCCESS: sam2_temporal_step.onnx validated! Size: {file_size_mb:.2f} MB')

if __name__ == '__main__':
    export_temporal_step()
