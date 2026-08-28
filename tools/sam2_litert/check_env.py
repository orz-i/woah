import importlib
import sys


def load(name):
    try:
        return importlib.import_module(name), None
    except Exception as exc:
        return None, f"{type(exc).__name__}: {exc}"


required = {
    "litert_torch": "LiteRT Torch converter required to regenerate TFLite candidates",
    "ai_edge_litert": "LiteRT interpreter required for CPU parity/static inspection",
    "torch": "PyTorch reference/export runtime",
    "ultralytics": "YOLO tooling",
}

failed = False
for module_name, purpose in required.items():
    module, error = load(module_name)
    if module is None:
        failed = True
        print(f"{module_name}: MISSING ({purpose}) -> {error}")
    else:
        print(f"{module_name}: {getattr(module, '__version__', 'unknown')}")

if failed:
    print("ENV CHECK FAILED: restore the locked export toolchain before regenerating models.")
    sys.exit(2)

print("ENV CHECK OK")
