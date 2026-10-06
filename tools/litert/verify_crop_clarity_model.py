#!/usr/bin/env python3
"""Validate Woah's crop-clarity x2 LiteRT model contract and GPU-safe op graph."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

EXPECTED_INPUT_SHAPE = [1, 192, 192, 3]
EXPECTED_OUTPUT_SHAPE = [1, 384, 384, 3]
EXPECTED_DTYPE = "float32"

# Conservative intersection with the public LiteRT GPU delegate operator list.
# Keep this list deliberately small: a new operator must be reviewed before a
# model can be promoted into the release asset set.
GPU_OP_VERSION_MAX = {
    "ADD": 1,
    "CONCATENATION": 1,
    "CONV_2D": 1,
    "DEPTHWISE_CONV_2D": 2,
    "LOGISTIC": 1,
    "MUL": 1,
    "RESIZE_BILINEAR": 3,
    "SUB": 1,
    "TRANSPOSE_CONV": 1,
}
GPU_OP_ALLOWLIST = set(GPU_OP_VERSION_MAX)
FORBIDDEN_OPS = {
    "CUSTOM",
    "DELEGATE",
    "DEPTH_TO_SPACE",  # upstream SPAN PixelShuffle export
    "FLEX",
    "TRANSPOSE",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _operator_versions(raw: bytes) -> dict[str, int]:
    try:
        from ai_edge_litert import schema_py_generated as schema  # type: ignore
    except ImportError as exc:  # pragma: no cover - tooling guard
        raise SystemExit("ai-edge-litert schema module is required for op-version validation") from exc

    reverse_builtin = {
        value: name
        for name, value in vars(schema.BuiltinOperator).items()
        if name.isupper() and isinstance(value, int)
    }
    model = schema.Model.GetRootAsModel(raw, 0)
    versions: dict[str, int] = {}
    for index in range(model.OperatorCodesLength()):
        code = model.OperatorCodes(index)
        builtin = int(code.BuiltinCode())
        name = reverse_builtin.get(builtin, f"BUILTIN_{builtin}")
        if name == "CUSTOM":
            custom = code.CustomCode()
            if custom:
                name = "CUSTOM:" + custom.decode("utf-8", errors="replace")
        version = int(code.Version())
        versions[name] = max(version, versions.get(name, 0))
    return versions


def _require_litert():
    try:
        from ai_edge_litert.interpreter import Interpreter, OpResolverType  # type: ignore
    except ImportError as exc:  # pragma: no cover - tooling guard
        raise SystemExit(
            "ai-edge-litert is required to validate the model. Install it only in a tooling environment."
        ) from exc
    return Interpreter, OpResolverType


def _dtype_name(value: Any) -> str:
    return getattr(value, "__name__", str(value).split(".")[-1].replace("'>", ""))


def inspect_model(model_path: Path) -> dict[str, Any]:
    raw = model_path.read_bytes()
    if len(raw) < 8 or raw[4:8] != b"TFL3":
        raise ValueError(f"Not a LiteRT/TFLite FlatBuffer: {model_path}")

    Interpreter, OpResolverType = _require_litert()
    # Inspect the raw graph rather than a graph already rewritten by XNNPACK or
    # another default delegate. Otherwise `_get_ops_details()` may contain a
    # synthetic DELEGATE node instead of the operators we need to review.
    interpreter = Interpreter(
        model_path=str(model_path),
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError(f"Expected one input and one output, got {len(inputs)} / {len(outputs)}")

    input_shape = inputs[0]["shape"].tolist()
    output_shape = outputs[0]["shape"].tolist()
    input_dtype = _dtype_name(inputs[0]["dtype"])
    output_dtype = _dtype_name(outputs[0]["dtype"])
    if input_shape != EXPECTED_INPUT_SHAPE:
        raise ValueError(f"Unexpected input shape: {input_shape}")
    if output_shape != EXPECTED_OUTPUT_SHAPE:
        raise ValueError(f"Unexpected output shape: {output_shape}")
    if input_dtype != EXPECTED_DTYPE or output_dtype != EXPECTED_DTYPE:
        raise ValueError(f"Expected float32 IO, got input={input_dtype} output={output_dtype}")

    get_ops = getattr(interpreter, "_get_ops_details", None)
    if get_ops is None:
        raise ValueError("Installed LiteRT interpreter does not expose op inspection")
    op_details = get_ops()
    operators = [str(item.get("op_name", "UNKNOWN")).upper() for item in op_details]
    unique_ops = sorted(set(operators))
    forbidden = sorted(
        op for op in unique_ops
        if op in FORBIDDEN_OPS or op.startswith("FLEX")
    )
    unsupported = sorted(set(unique_ops) - GPU_OP_ALLOWLIST)
    if forbidden:
        raise ValueError(f"Forbidden LiteRT operators present: {forbidden}")
    if unsupported:
        raise ValueError(
            "Model is outside Woah's strict LiteRT GPU allowlist: " + ", ".join(unsupported)
        )

    operator_versions = _operator_versions(raw)
    version_errors = []
    for op, version in sorted(operator_versions.items()):
        max_version = GPU_OP_VERSION_MAX.get(op)
        if max_version is None:
            version_errors.append(f"{op} v{version}: not allowlisted")
        elif version > max_version:
            version_errors.append(f"{op} v{version}: GPU max v{max_version}")
    if version_errors:
        raise ValueError("Unsupported LiteRT GPU operator versions: " + "; ".join(version_errors))

    return {
        "file": model_path.name,
        "bytes": len(raw),
        "sha256": sha256(model_path),
        "input": {
            "shape": input_shape,
            "dtype": input_dtype,
            "layout": "NHWC",
            "range": "0..1",
        },
        "output": {
            "shape": output_shape,
            "dtype": output_dtype,
            "layout": "NHWC",
            "range": "unbounded; caller clamps 0..1",
        },
        "scale": 2,
        "tile_size": 192,
        "operators": unique_ops,
        "operator_versions": operator_versions,
        "gpu_operator_allowlist": sorted(GPU_OP_ALLOWLIST),
        "gpu_operator_version_max": GPU_OP_VERSION_MAX,
        "runtime_contract": {
            "pixelshuffle_depth_to_space_forbidden": True,
            "transpose_conv_allowed": True,
            "note": "Architecture-specific migration method is tracked by the candidate spec; this verifier only enforces the shared LiteRT runtime contract.",
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("--contract-out", type=Path)
    args = parser.parse_args()

    try:
        report = inspect_model(args.model)
    except (OSError, ValueError) as exc:
        print(f"CROP_CLARITY_MODEL=FAIL: {exc}")
        return 1

    if args.contract_out:
        args.contract_out.parent.mkdir(parents=True, exist_ok=True)
        args.contract_out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("CROP_CLARITY_MODEL=" + json.dumps(report, sort_keys=True))
    print("CROP_CLARITY_MODEL=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
