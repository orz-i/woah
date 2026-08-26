import os
import sys
import json
import torch

# Ensure sam2 is in sys.path
VENDOR_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../desktop/vendor"))
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

from sam2.build_sam import build_sam2

from hydra import compose, initialize
from hydra.core.global_hydra import GlobalHydra

def inspect_model():
    config_dir = os.path.join(VENDOR_PATH, "sam2")
    config_name = "sam2_hiera_t.yaml"

    ckpt_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../models/pytorch/sam2_hiera_tiny.pt"))

    print(f"[Inspect] Loading SAM2 model from: {ckpt_path}")
    print(f"[Inspect] Config: {os.path.join(config_dir, config_name)}")

    model = build_sam2(config_file=config_name, ckpt_path=ckpt_path, device="cpu", mode="eval")
    model.eval()

    image_size = model.image_size
    num_maskmem = model.num_maskmem
    hidden_dim = model.hidden_dim
    mem_dim = model.mem_dim

    print(f"[Inspect] Image Size: {image_size}x{image_size}")
    print(f"[Inspect] Num MaskMem: {num_maskmem}")
    print(f"[Inspect] Hidden Dim: {hidden_dim}, Mem Dim: {mem_dim}")

    # Submodule 1: Image Encoder
    # Input: [1, 3, image_size, image_size]
    # Output: Multi-scale vision features and pos encodings
    dummy_image = torch.zeros(1, 3, image_size, image_size, dtype=torch.float32)
    with torch.no_grad():
        backbone_out = model.forward_image(dummy_image)
    
    vision_features = backbone_out["vision_features"] # [1, 256, 64, 64]
    vision_pos_enc = backbone_out["vision_pos_enc"]   # list of pos encodings [1, 256, 64, 64], etc.
    backbone_fpn = backbone_out["backbone_fpn"]       # list of 3 feature maps: [1, 32, 256, 256], [1, 64, 128, 128], [1, 256, 64, 64]

    contract = {
        "model_variant": "sam2_hiera_tiny",
        "image_size": image_size,
        "num_maskmem": num_maskmem,
        "hidden_dim": hidden_dim,
        "mem_dim": mem_dim,
        "components": {
            "image_encoder": {
                "inputs": {
                    "image": {"shape": list(dummy_image.shape), "dtype": "float32"}
                },
                "outputs": {
                    "vision_features": {"shape": list(vision_features.shape), "dtype": "float32"},
                    "vision_pos_enc_0": {"shape": list(vision_pos_enc[0].shape), "dtype": "float32"},
                    "vision_pos_enc_1": {"shape": list(vision_pos_enc[1].shape), "dtype": "float32"},
                    "vision_pos_enc_2": {"shape": list(vision_pos_enc[2].shape), "dtype": "float32"},
                    "fpn_feat_0": {"shape": list(backbone_fpn[0].shape), "dtype": "float32"},
                    "fpn_feat_1": {"shape": list(backbone_fpn[1].shape), "dtype": "float32"},
                    "fpn_feat_2": {"shape": list(backbone_fpn[2].shape), "dtype": "float32"}
                }
            },
            "memory_encoder": {
                "inputs": {
                    "pix_feat": {"shape": [1, 256, 64, 64], "dtype": "float32"},
                    "masks": {"shape": [1, 1, image_size, image_size], "dtype": "float32"}
                },
                "outputs": {
                    "vision_features": {"shape": [1, 64, 64, 64], "dtype": "float32"},
                    "vision_pos_enc": {"shape": [1, 64, 64, 64], "dtype": "float32"}
                }
            },
            "memory_attention": {
                "inputs": {
                    "curr_vision_features": {"shape": [1, 256, 64, 64], "dtype": "float32"},
                    "curr_vision_pos_enc": {"shape": [1, 256, 64, 64], "dtype": "float32"},
                    "memory_features": {"shape": [1, num_maskmem, 64, 64, 64], "dtype": "float32"},
                    "memory_pos_enc": {"shape": [1, num_maskmem, 64, 64, 64], "dtype": "float32"}
                },
                "outputs": {
                    "conditioned_features": {"shape": [1, 256, 64, 64], "dtype": "float32"}
                }
            },
            "sam_mask_decoder": {
                "inputs": {
                    "image_embeddings": {"shape": [1, 256, 64, 64], "dtype": "float32"},
                    "image_pe": {"shape": [1, 256, 64, 64], "dtype": "float32"},
                    "point_coords": {"shape": [1, 2, 2], "dtype": "float32"},
                    "point_labels": {"shape": [1, 2], "dtype": "int32"},
                    "high_res_features_0": {"shape": [1, 32, 256, 256], "dtype": "float32"},
                    "high_res_features_1": {"shape": [1, 64, 128, 128], "dtype": "float32"}
                },
                "outputs": {
                    "low_res_masks": {"shape": [1, 3, 256, 256], "dtype": "float32"},
                    "iou_predictions": {"shape": [1, 3], "dtype": "float32"},
                    "object_score_logits": {"shape": [1, 1], "dtype": "float32"}
                }
            }
        }
    }

    out_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "../sam2_tensor_contract.json"))
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(contract, f, indent=2)

    print(f"[Inspect] Wrote contract to {out_path}")

if __name__ == "__main__":
    inspect_model()
