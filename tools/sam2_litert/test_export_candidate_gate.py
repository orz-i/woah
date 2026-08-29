import os
import sys
from unittest.mock import patch


ROOT_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
if ROOT_PATH not in sys.path:
    sys.path.insert(0, ROOT_PATH)

from tools.sam2_litert import test_export_image_features as export_module


def test_static_gpu_gate_accepts_clean_parsed_graph():
    fake_report = {
        "operator_parser_available": True,
        "interpreter_backend": "fake",
        "total_operators": 42,
        "gpu_blocker_candidates": [],
    }
    with patch.object(export_module, "analyze_tflite_model", return_value=fake_report):
        passed, result = export_module._static_gpu_gate("candidate.tflite")

    assert passed
    assert result["parser_available"] is True
    assert result["gpu_blocker_candidate_count"] == 0
    assert result["gpu_blocker_counts"] == {}


def test_static_gpu_gate_rejects_known_blockers():
    fake_report = {
        "operator_parser_available": True,
        "interpreter_backend": "fake",
        "total_operators": 99,
        "gpu_blocker_candidates": [
            {"opcode": "BROADCAST_TO"},
            {"opcode": "GATHER_ND"},
            {"opcode": "RESHAPE"},
            {"opcode": "RESHAPE"},
        ],
    }
    with patch.object(export_module, "analyze_tflite_model", return_value=fake_report):
        passed, result = export_module._static_gpu_gate("candidate.tflite")

    assert not passed
    assert result["gpu_blocker_candidate_count"] == 4
    assert result["gpu_blocker_counts"] == {
        "BROADCAST_TO": 1,
        "GATHER_ND": 1,
        "RESHAPE": 2,
    }


def test_static_gpu_gate_rejects_unparsed_graph():
    fake_report = {
        "operator_parser_available": False,
        "total_operators": 0,
        "gpu_blocker_candidates": [],
        "tf_lite_interpreter_note": "parser unavailable",
    }
    with patch.object(export_module, "analyze_tflite_model", return_value=fake_report):
        passed, result = export_module._static_gpu_gate("candidate.tflite")

    assert not passed
    assert result["parser_available"] is False
    assert result["analysis_note"] == "parser unavailable"


def test_promotion_requires_litert_cpu_parity():
    assert export_module.export_and_verify_image_features(
        run_litert_verify=False,
        promote=True,
    ) is False


if __name__ == "__main__":
    test_static_gpu_gate_accepts_clean_parsed_graph()
    test_static_gpu_gate_rejects_known_blockers()
    test_static_gpu_gate_rejects_unparsed_graph()
    test_promotion_requires_litert_cpu_parity()
    print("PASS: SAM2 candidate export gate tests")
