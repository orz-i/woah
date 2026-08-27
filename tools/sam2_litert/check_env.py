import litert_torch
import ai_edge_litert
import torch
import ultralytics

print("LITERT_TORCH_VERSION:", getattr(litert_torch, "__version__", "unknown"))
print("AI_EDGE_LITERT_VERSION:", getattr(ai_edge_litert, "__version__", "unknown"))
print("TORCH_VERSION:", torch.__version__)
print("ULTRALYTICS_VERSION:", ultralytics.__version__)
print("ENV CHECK OK!")
