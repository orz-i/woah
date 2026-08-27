import os
import sys
import torch
import torch.nn.functional as F
import numpy as np

ROOT_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
if ROOT_PATH not in sys.path:
    sys.path.insert(0, ROOT_PATH)

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../desktop/vendor'))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2_video_predictor
from tools.sam2_onnx.export.portable_rotary import patch_rotary_for_export

NUM_MASKMEM = 7
MAX_OBJ_PTRS = 16
HIDDEN_DIM = 256
MEM_DIM = 64
TOTAL_MEM_TOKENS = NUM_MASKMEM * 4096 + MAX_OBJ_PTRS * 4  # 28736

class Sam2TemporalStepLiteRtExporter(torch.nn.Module):
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
        selected_obj_ptrs: torch.Tensor,
        attn_mask: torch.Tensor
    ):
        # current_top_feature: [1, 256, 64, 64]
        # current_top_pos: [1, 256, 64, 64]
        # current_high_res_0: [1, 32, 256, 256]
        # current_high_res_1: [1, 64, 128, 128]
        # selected_memory_features: [7, 1, 64, 64, 64]
        # selected_memory_pos: [7, 1, 64, 64, 64]
        # memory_tpos_indices: [7]
        # selected_obj_ptrs: [16, 1, 256]
        # attn_mask: [1, 1, 1, 28736] (additive float mask, 0.0 for valid, -10000.0 for padding)

        B = current_top_feature.shape[0]
        C = HIDDEN_DIM  # 256
        mem_dim = MEM_DIM  # 64

        num_mem = NUM_MASKMEM
        num_ptrs = MAX_OBJ_PTRS

        # 1. Prepare memory features: [7, 1, 64, 64, 64] -> [7 * 4096, 1, 64]
        mem_flat = selected_memory_features.flatten(3).permute(0, 3, 1, 2).reshape(-1, 1, mem_dim)

        # 2. Prepare memory pos: add maskmem_tpos_enc
        maskmem_tpos = self.sam2_model.maskmem_tpos_enc[memory_tpos_indices]  # [7, 1, 1, 64]
        pos_flat = selected_memory_pos.flatten(3).permute(0, 3, 1, 2)  # [7, 4096, 1, 64]
        pos_flat = (pos_flat + maskmem_tpos).reshape(-1, 1, mem_dim)  # [7 * 4096, 1, 64]

        # 3. Prepare object pointers: [16, 1, 256] -> [16 * 4, 1, 64]
        num_tokens_per_ptr = C // mem_dim  # 4
        ptrs_4d = selected_obj_ptrs.reshape(num_ptrs, 1, num_tokens_per_ptr, mem_dim)
        ptrs_flat = ptrs_4d.permute(0, 2, 1, 3).reshape(num_ptrs * num_tokens_per_ptr, 1, mem_dim)
        pos_ptrs = torch.zeros_like(ptrs_flat)
        num_obj_ptr_tokens = num_ptrs * num_tokens_per_ptr  # 64

        # 4. Concatenate memories and pointers
        total_memory = torch.cat([mem_flat, ptrs_flat], dim=0)  # [28736, 1, 64]
        total_memory_pos = torch.cat([pos_flat, pos_ptrs], dim=0)  # [28736, 1, 64]

        # 5. Memory attention
        curr = current_top_feature.flatten(2).permute(2, 0, 1)  # [4096, 1, 256]
        curr_pos = current_top_pos.flatten(2).permute(2, 0, 1)  # [4096, 1, 256]

        pix_feat_with_mem = self.sam2_model.memory_attention(
            curr=curr,
            curr_pos=curr_pos,
            memory=total_memory,
            memory_pos=total_memory_pos,
            num_obj_ptr_tokens=num_obj_ptr_tokens,
            attn_mask=attn_mask
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

def export_and_verify_temporal_step():
    import litert_torch
    import ai_edge_litert.interpreter as litert_interp

    root_dir = ROOT_PATH
    ckpt_path = os.path.join(root_dir, 'models/pytorch/sam2_hiera_tiny.pt')
    out_dir = os.path.join(root_dir, 'models/litert')
    os.makedirs(out_dir, exist_ok=True)
    tflite_path = os.path.join(out_dir, 'sam2_temporal_step.tflite')

    print(f'[SAM2 TemporalStep] Loading PyTorch model from {ckpt_path}...')
    predictor = build_sam2_video_predictor('sam2_hiera_t.yaml', ckpt_path=ckpt_path, device='cpu')
    model = predictor
    model.eval()

    wrapper = Sam2TemporalStepLiteRtExporter(model)
    wrapper.eval()

    dummy_top = torch.randn(1, 256, 64, 64, dtype=torch.float32)
    dummy_top_pos = torch.randn(1, 256, 64, 64, dtype=torch.float32)
    dummy_high0 = torch.randn(1, 32, 256, 256, dtype=torch.float32)
    dummy_high1 = torch.randn(1, 64, 128, 128, dtype=torch.float32)
    dummy_mem_feats = torch.randn(NUM_MASKMEM, 1, 64, 64, 64, dtype=torch.float32)
    dummy_mem_pos = torch.randn(NUM_MASKMEM, 1, 64, 64, 64, dtype=torch.float32)
    dummy_tpos_inds = torch.tensor([6, 5, 4, 3, 2, 1, 0], dtype=torch.int64)
    dummy_ptrs = torch.randn(MAX_OBJ_PTRS, 1, 256, dtype=torch.float32)
    dummy_mask = torch.zeros(1, 1, 1, TOTAL_MEM_TOKENS, dtype=torch.float32)

    sample_inputs = (
        dummy_top,
        dummy_top_pos,
        dummy_high0,
        dummy_high1,
        dummy_mem_feats,
        dummy_mem_pos,
        dummy_tpos_inds,
        dummy_ptrs,
        dummy_mask
    )

    print(f'[SAM2 TemporalStep] Running PyTorch reference forward pass...')
    with patch_rotary_for_export(model):
        with torch.no_grad():
            pt_outs = wrapper(*sample_inputs)

    print(f'[SAM2 TemporalStep] Converting via litert_torch.convert()...')
    with patch_rotary_for_export(model):
        edge_model = litert_torch.convert(wrapper, sample_inputs)

    print(f'[SAM2 TemporalStep] Exporting to {tflite_path}...')
    edge_model.export(tflite_path)
    file_size_mb = os.path.getsize(tflite_path) / (1024 * 1024)
    print(f'[SAM2 TemporalStep] SUCCESS: Saved {tflite_path} ({file_size_mb:.2f} MB)')

    # Verify LiteRT interpreter and numerical parity
    print(f'[SAM2 TemporalStep] Verifying LiteRT interpreter and numerical parity...')
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
    print(f'[Gate S3: temporal_step] overall_max_diff={overall_max_diff:.6f}, PASS={pass_gate}')
    return pass_gate

if __name__ == '__main__':
    if not export_and_verify_temporal_step():
        sys.exit(1)
