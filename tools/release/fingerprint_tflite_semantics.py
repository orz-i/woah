#!/usr/bin/env python3
"""Compute a serialization-order-resistant semantic fingerprint for a TFLite graph."""

from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import sys
import types
from pathlib import Path

# The generated TFLite schema bindings expose optional ``*AsNumpy`` helpers,
# but this fingerprint only needs scalar FlatBuffer access. Avoid importing the
# host NumPy ABI entirely so the diagnostic remains portable across the Windows
# developer host and the isolated Linux exporter environment.
# ``flatbuffers.__init__`` imports modules that call ``import_numpy()`` before
# we can patch that helper, so provide a harmless module stub first. No NumPy
# API is used anywhere below.
if "numpy" not in sys.modules:
    sys.modules["numpy"] = types.ModuleType("numpy")

import flatbuffers.compat  # type: ignore

flatbuffers.compat.import_numpy = lambda: None

import tflite  # type: ignore


def text(value):
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return value


def digest_json(value) -> str:
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def vector(obj, base: str) -> list:
    length = int(getattr(obj, base + "Length")())
    getter = getattr(obj, base)
    return [text(getter(i)) for i in range(length)]


def generated_fields(obj) -> dict:
    result: dict[str, object] = {}
    methods = vars(type(obj))
    for name in sorted(methods):
        if not name.endswith("Length"):
            continue
        base = name[:-6]
        if not hasattr(obj, base):
            continue
        try:
            result[base] = vector(obj, base)
        except Exception:
            pass
    for name in sorted(methods):
        if name.startswith("_") or name in {"Init"}:
            continue
        if name.endswith(("Length", "AsNumpy", "IsNone")):
            continue
        if name in result:
            continue
        fn = getattr(obj, name, None)
        if not callable(fn):
            continue
        try:
            if len(inspect.signature(fn).parameters) != 0:
                continue
            value = fn()
        except Exception:
            continue
        value = text(value)
        if value is None or isinstance(value, (bool, int, float, str)):
            result[name] = value
    return result


BUILTIN_OPTIONS = {
    value: name
    for name, value in vars(tflite.BuiltinOptions).items()
    if not name.startswith("_") and isinstance(value, int)
}


def opcode_descriptor(code) -> dict:
    out = {
        "builtin_code": int(code.BuiltinCode()),
        "version": int(code.Version()),
    }
    if hasattr(code, "DeprecatedBuiltinCode"):
        out["deprecated_builtin_code"] = int(code.DeprecatedBuiltinCode())
    custom = code.CustomCode()
    if custom:
        out["custom_code"] = text(custom)
    return out


def builtin_options(op) -> dict | None:
    option_type = int(op.BuiltinOptionsType())
    if option_type == 0:
        return None
    option_name = BUILTIN_OPTIONS.get(option_type, f"UNKNOWN_{option_type}")
    raw = op.BuiltinOptions()
    cls = getattr(tflite, option_name, None)
    if raw is None or cls is None:
        return {"type": option_name}
    typed = cls()
    typed.Init(raw.Bytes, raw.Pos)
    return {"type": option_name, "fields": generated_fields(typed)}


def quantization(tensor) -> dict | None:
    q = tensor.Quantization()
    if q is None:
        return None
    fields = generated_fields(q)
    meaningful = {
        key: value
        for key, value in fields.items()
        if value not in (None, False, 0, 0.0, [], "")
    }
    return meaningful or None


def model_fingerprint(path: Path) -> tuple[dict, list[dict]]:
    data = path.read_bytes()
    zip_offset = data.find(b"PK\x03\x04", max(8, len(data) - 65536))
    core = data[:zip_offset] if zip_offset >= 0 else data
    model = tflite.Model.GetRootAsModel(core, 0)

    opcodes = [opcode_descriptor(model.OperatorCodes(i)) for i in range(model.OperatorCodesLength())]
    subgraphs: list[dict] = []
    constant_records: list[dict] = []

    for sg_index in range(model.SubgraphsLength()):
        sg = model.Subgraphs(sg_index)
        tensors_by_index: dict[int, dict] = {}
        tensor_name_by_index: dict[int, str] = {}
        for i in range(sg.TensorsLength()):
            tensor = sg.Tensors(i)
            name = text(tensor.Name()) or f"<tensor:{i}>"
            buffer_index = int(tensor.Buffer())
            buffer = model.Buffers(buffer_index)
            buffer_size = int(buffer.DataLength())
            buffer_hash = None
            if buffer_size:
                raw = bytes(int(buffer.Data(j)) for j in range(buffer_size))
                buffer_hash = hashlib.sha256(raw).hexdigest()
                constant_records.append(
                    {"tensor": name, "bytes": buffer_size, "sha256": buffer_hash}
                )
            descriptor = {
                "name": name,
                "shape": [int(tensor.Shape(j)) for j in range(tensor.ShapeLength())],
                "shape_signature": [
                    int(tensor.ShapeSignature(j)) for j in range(tensor.ShapeSignatureLength())
                ],
                "type": int(tensor.Type()),
                "is_variable": bool(tensor.IsVariable()),
                "constant_bytes": buffer_size,
                "constant_sha256": buffer_hash,
                "quantization": quantization(tensor),
            }
            tensors_by_index[i] = descriptor
            tensor_name_by_index[i] = name

        def names(indices: list[int]) -> list[str | None]:
            return [None if index < 0 else tensor_name_by_index[index] for index in indices]

        operators: list[dict] = []
        for i in range(sg.OperatorsLength()):
            op = sg.Operators(i)
            opcode = opcodes[int(op.OpcodeIndex())]
            inputs = [int(op.Inputs(j)) for j in range(op.InputsLength())]
            outputs = [int(op.Outputs(j)) for j in range(op.OutputsLength())]
            intermediates = [int(op.Intermediates(j)) for j in range(op.IntermediatesLength())]
            custom = (
                bytes(int(op.CustomOptions(j)) for j in range(op.CustomOptionsLength()))
                if op.CustomOptionsLength()
                else b""
            )
            operators.append(
                {
                    "opcode": opcode,
                    "inputs": names(inputs),
                    "outputs": names(outputs),
                    "intermediates": names(intermediates),
                    "builtin_options": builtin_options(op),
                    "custom_options_sha256": hashlib.sha256(custom).hexdigest() if custom else None,
                    "custom_options_format": int(op.CustomOptionsFormat()),
                }
            )

        tensor_descriptors = sorted(tensors_by_index.values(), key=lambda item: item["name"])
        operators = sorted(
            operators,
            key=lambda item: (
                tuple("" if value is None else value for value in item["outputs"]),
                json.dumps(item["opcode"], sort_keys=True),
            ),
        )
        subgraphs.append(
            {
                "name": text(sg.Name()),
                "inputs": names([int(sg.Inputs(j)) for j in range(sg.InputsLength())]),
                "outputs": names([int(sg.Outputs(j)) for j in range(sg.OutputsLength())]),
                "tensors": tensor_descriptors,
                "operators": operators,
            }
        )

    constant_records.sort(key=lambda item: item["tensor"])
    semantic = {
        "schema": 1,
        "model_version": int(model.Version()),
        "description": text(model.Description()),
        "subgraphs": sorted(subgraphs, key=lambda item: item["name"] or ""),
    }
    structure = json.loads(json.dumps(semantic))
    for sg in structure["subgraphs"]:
        for tensor in sg["tensors"]:
            tensor["constant_sha256"] = None

    fingerprint = {
        "core_size_bytes": len(core),
        "core_sha256": hashlib.sha256(core).hexdigest(),
        "tensor_count": sum(len(sg["tensors"]) for sg in semantic["subgraphs"]),
        "operator_count": sum(len(sg["operators"]) for sg in semantic["subgraphs"]),
        "constant_tensor_count": len(constant_records),
        "constant_bytes": sum(record["bytes"] for record in constant_records),
        "structure_sha256": digest_json(structure),
        "constants_sha256": digest_json(constant_records),
        "semantic_sha256": digest_json(semantic),
    }
    return fingerprint, constant_records


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    parser.add_argument("--constants-output", type=Path, default=None)
    args = parser.parse_args()
    fingerprint, constants = model_fingerprint(args.model)
    if args.constants_output is not None:
        args.constants_output.parent.mkdir(parents=True, exist_ok=True)
        args.constants_output.write_text(
            json.dumps({"schema": 1, "constants": constants}, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    print("PHASE7_TFLITE_SEMANTIC_FINGERPRINT=" + json.dumps(fingerprint, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
