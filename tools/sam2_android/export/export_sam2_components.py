import os
import sys
import json
import torch
import torch.nn as nn
import torch.nn.functional as F

VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../desktop/vendor"))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2
from executorch.exir import to_edge, EdgeCompileConfig
import executorch.exir as exir

class ImageEncoderWrapper(nn.Module):
    def __init__(self, sam2_model):
        super().__init__()
        self.image_encoder = sam2_model.image_encoder

    def forward(self, image: torch.Tensor):
        # image: [1, 3, 1024, 1024]
        out = self.image_encoder(image)
        # out["vision_features"]: [1, 256, 64, 64]
        # out["vision_pos_enc"]: list of 3 [1, 256, 256, 256], [1, 256, 128, 128], [1, 256, 64, 64]
        # out["backbone_fpn"]: list of 3 [1, 32, 256, 256], [1, 64, 128, 128], [1, 256, 64, 64]
        vf = out["vision_features"]
        pe0 = out["vision_pos_enc"][0]
        pe1 = out["vision_pos_enc"][1]
        pe2 = out["vision_pos_enc"][2]
        fpn0 = out["backbone_fpn"][0]
        fpn1 = out["backbone_fpn"][1]
        fpn2 = out["backbone_fpn"][2]
        return vf, pe0, pe1, pe2, fpn0, fpn1, fpn2

class MemoryEncoderWrapper(nn.Module):
    def __init__(self, sam2_model):
        super().__init__()
        self.memory_encoder = sam2_model.memory_encoder
        self.sigmoid_scale_for_mem_enc = sam2_model.sigmoid_scale_for_mem_enc
        self.sigmoid_bias_for_mem_enc = sam2_model.sigmoid_bias_for_mem_enc

    def forward(self, pix_feat: torch.Tensor, mask_logits: torch.Tensor):
        # pix_feat: [1, 256, 64, 64]
        # mask_logits: [1, 1, 1024, 1024]
        # SAM2 memory encoder expects sigmoid(logits * scale + bias)
        mask_for_mem = torch.sigmoid(mask_logits * self.sigmoid_scale_for_mem_enc + self.sigmoid_bias_for_mem_enc)
        out = self.memory_encoder(pix_feat, mask_for_mem, skip_mask_sigmoid=True)
        # vision_features: [1, 64, 64, 64], vision_pos_enc: [1, 64, 64, 64]
        return out["vision_features"], out["vision_pos_enc"]

class MemoryAttentionWrapper(nn.Module):
    def __init__(self, sam2_model):
        super().__init__()
        self.memory_attention = sam2_model.memory_attention

    def forward(self, curr_vision_feat: torch.Tensor, curr_vision_pos_enc: torch.Tensor,
                memory_features: torch.Tensor, memory_pos_enc: torch.Tensor):
        # curr_vision_feat: [1, 256, 64, 64]
        # curr_vision_pos_enc: [1, 256, 64, 64]
        # memory_features: [N, 64, 64, 64] (where N is total memory frames flattened or 7)
        # memory_pos_enc: [N, 64, 64, 64]
        B, C, H, W = curr_vision_feat.shape
        # Flatten spatial dimensions for attention
        curr_feat_flat = curr_vision_feat.flatten(2).permute(2, 0, 1) # [H*W, B, C]
        curr_pos_flat = curr_vision_pos_enc.flatten(2).permute(2, 0, 1)
        
        # memory: [M, 64, H, W] -> [M*H*W, 1, 64]
        M = memory_features.shape[0]
        mem_feat_flat = memory_features.flatten(2).permute(2, 0, 1) # [H*W, M, 64]
        mem_feat_flat = mem_feat_flat.reshape(-1, 1, 64) # [M*H*W, 1, 64]

        mem_pos_flat = memory_pos_enc.flatten(2).permute(2, 0, 1)
        mem_pos_flat = mem_pos_flat.reshape(-1, 1, 64)

        out = self.memory_attention(
            curr=curr_feat_flat,
            curr_pos=curr_pos_flat,
            memory=mem_feat_flat,
            memory_pos=mem_pos_flat,
            num_obj_ptr_tokens=0
        )
        # out: [H*W, B, C] -> [B, C, H, W]
        out = out.permute(1, 2, 0).reshape(B, C, H, W)
        return out

class SamMaskDecoderWrapper(nn.Module):
    def __init__(self, sam2_model):
        super().__init__()
        self.sam_mask_decoder = sam2_model.sam_mask_decoder
        self.sam_prompt_encoder = sam2_model.sam_prompt_encoder

    def forward(self, image_embeddings: torch.Tensor, image_pe: torch.Tensor,
                point_coords: torch.Tensor, point_labels: torch.Tensor,
                high_res_0: torch.Tensor, high_res_1: torch.Tensor):
        # point_coords: [1, 2, 2] (box corners)
        # point_labels: [1, 2] ([2, 3] for box top-left and bottom-right)
        points = (point_coords, point_labels)
        sparse_embeddings, dense_embeddings = self.sam_prompt_encoder(
            points=points,
            boxes=None,
            masks=None
        )
        high_res_features = [high_res_0, high_res_1]
        low_res_masks, iou_predictions, sam_output_tokens, object_score_logits = self.sam_mask_decoder(
            image_embeddings=image_embeddings,
            image_pe=image_pe,
            sparse_prompt_embeddings=sparse_embeddings,
            dense_prompt_embeddings=dense_embeddings,
            multimask_output=True,
            repeat_image=False,
            high_res_features=high_res_features
        )
        # low_res_masks: [1, 3, 256, 256]
        # iou_predictions: [1, 3]
        # object_score_logits: [1, 1]
        # sam_output_tokens: [1, 3, 256]
        return low_res_masks, iou_predictions, object_score_logits, sam_output_tokens

def run_export_and_validation():
    ckpt_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../models/pytorch/sam2_hiera_tiny.pt"))
    config_name = "sam2_hiera_t.yaml"
    out_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.generated"))
    os.makedirs(out_dir, exist_ok=True)

    print(f"[Export] Loading SAM2 base model...")
    model = build_sam2(config_file=config_name, ckpt_path=ckpt_path, device="cpu", mode="eval")
    model.eval()

    validation_results = {}

    # 1. Export Image Encoder
    print("[Export] 1/4 Exporting ImageEncoder...")
    img_enc = ImageEncoderWrapper(model)
    img_enc.eval()
    dummy_img = torch.randn(1, 3, 1024, 1024, dtype=torch.float32)

    with torch.no_grad():
        py_vf, py_pe0, py_pe1, py_pe2, py_fpn0, py_fpn1, py_fpn2 = img_enc(dummy_img)

    exported_img_enc = torch.export.export(img_enc, (dummy_img,))
    edge_img_enc = to_edge(exported_img_enc, compile_config=EdgeCompileConfig(_check_ir_validity=False))
    exec_img_enc = edge_img_enc.to_executorch()

    img_enc_pte = os.path.join(out_dir, "sam2_image_encoder.pte")
    with open(img_enc_pte, "wb") as f:
        f.write(exec_img_enc.buffer)
    print(f"[Export] Saved {img_enc_pte} ({len(exec_img_enc.buffer) / 1024 / 1024:.2f} MB)")

    validation_results["image_encoder"] = {
        "export_status": "PASS",
        "pte_bytes": len(exec_img_enc.buffer),
        "input_shape": list(dummy_img.shape),
        "output_vf_shape": list(py_vf.shape)
    }

    # 2. Export Memory Encoder
    print("[Export] 2/4 Exporting MemoryEncoder...")
    mem_enc = MemoryEncoderWrapper(model)
    mem_enc.eval()
    dummy_pix = torch.randn(1, 256, 64, 64, dtype=torch.float32)
    dummy_mask = torch.randn(1, 1, 1024, 1024, dtype=torch.float32)

    with torch.no_grad():
        py_mem_vf, py_mem_pe = mem_enc(dummy_pix, dummy_mask)

    exported_mem_enc = torch.export.export(mem_enc, (dummy_pix, dummy_mask))
    edge_mem_enc = to_edge(exported_mem_enc, compile_config=EdgeCompileConfig(_check_ir_validity=False))
    exec_mem_enc = edge_mem_enc.to_executorch()

    mem_enc_pte = os.path.join(out_dir, "sam2_memory_encoder.pte")
    with open(mem_enc_pte, "wb") as f:
        f.write(exec_mem_enc.buffer)
    print(f"[Export] Saved {mem_enc_pte} ({len(exec_mem_enc.buffer) / 1024 / 1024:.2f} MB)")

    validation_results["memory_encoder"] = {
        "export_status": "PASS",
        "pte_bytes": len(exec_mem_enc.buffer),
        "output_mem_vf_shape": list(py_mem_vf.shape)
    }

    # 3. Export SAM Mask Decoder
    print("[Export] 3/4 Exporting SamMaskDecoder...")
    mask_dec = SamMaskDecoderWrapper(model)
    mask_dec.eval()
    dummy_emb = torch.randn(1, 256, 64, 64, dtype=torch.float32)
    dummy_pe = py_pe2
    dummy_pts = torch.tensor([[[150.0, 80.0], [360.0, 480.0]]], dtype=torch.float32)
    dummy_labels = torch.tensor([[2, 3]], dtype=torch.int32)
    dummy_hr0 = torch.randn(1, 32, 256, 256, dtype=torch.float32)
    dummy_hr1 = torch.randn(1, 64, 128, 128, dtype=torch.float32)

    with torch.no_grad():
        py_masks, py_ious, py_scores, py_tokens = mask_dec(dummy_emb, dummy_pe, dummy_pts, dummy_labels, dummy_hr0, dummy_hr1)

    exported_mask_dec = torch.export.export(mask_dec, (dummy_emb, dummy_pe, dummy_pts, dummy_labels, dummy_hr0, dummy_hr1))
    edge_mask_dec = to_edge(exported_mask_dec, compile_config=EdgeCompileConfig(_check_ir_validity=False))
    exec_mask_dec = edge_mask_dec.to_executorch()

    mask_dec_pte = os.path.join(out_dir, "sam2_mask_decoder.pte")
    with open(mask_dec_pte, "wb") as f:
        f.write(exec_mask_dec.buffer)
    print(f"[Export] Saved {mask_dec_pte} ({len(exec_mask_dec.buffer) / 1024 / 1024:.2f} MB)")

    validation_results["mask_decoder"] = {
        "export_status": "PASS",
        "pte_bytes": len(exec_mask_dec.buffer),
        "output_masks_shape": list(py_masks.shape)
    }

    # 4. Export Memory Attention
    print("[Export] 4/4 Exporting MemoryAttention...")
    mem_attn = MemoryAttentionWrapper(model)
    mem_attn.eval()
    dummy_curr_vf = torch.randn(1, 256, 64, 64, dtype=torch.float32)
    dummy_curr_pe = py_pe2
    dummy_mem_feats = torch.randn(7, 64, 64, 64, dtype=torch.float32)
    dummy_mem_pos = torch.randn(7, 64, 64, 64, dtype=torch.float32)

    with torch.no_grad():
        py_cond_vf = mem_attn(dummy_curr_vf, dummy_curr_pe, dummy_mem_feats, dummy_mem_pos)

    exported_mem_attn = torch.export.export(mem_attn, (dummy_curr_vf, dummy_curr_pe, dummy_mem_feats, dummy_mem_pos))
    edge_mem_attn = to_edge(exported_mem_attn, compile_config=EdgeCompileConfig(_check_ir_validity=False))
    exec_mem_attn = edge_mem_attn.to_executorch()

    mem_attn_pte = os.path.join(out_dir, "sam2_memory_attention.pte")
    with open(mem_attn_pte, "wb") as f:
        f.write(exec_mem_attn.buffer)
    print(f"[Export] Saved {mem_attn_pte} ({len(exec_mem_attn.buffer) / 1024 / 1024:.2f} MB)")

    validation_results["memory_attention"] = {
        "export_status": "PASS",
        "pte_bytes": len(exec_mem_attn.buffer),
        "output_cond_shape": list(py_cond_vf.shape)
    }

    val_json_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "../export_validation.json"))
    with open(val_json_path, "w", encoding="utf-8") as f:
        json.dump(validation_results, f, indent=2)

    print(f"[Export] All 4 SAM2 submodules exported to ExecuTorch successfully! Validation report written to {val_json_path}")

if __name__ == "__main__":
    run_export_and_validation()
