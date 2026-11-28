#!/usr/bin/env python3
"""M6 host-only unified FrameKernel research candidate; one scene analysis per video.

The canonical tracker remains the identity authority. Geometry may use a
current two-anchor fragment proof or a confirmed offline tracklet without
mutating that tracker. The square sticker, not the legacy face ellipse, is
the proposed output. Source labels are used only by separate auditors.
"""
import argparse
from collections import OrderedDict
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import time

from PIL import Image

import evaluate_dense_tracklets as dense
import evaluate_head_instances as head_instances
import anatomical_face_policy
import evaluate_fragment_heads as fragment
import evaluate_tracklet_recovery as recovery
from evaluate_body_reid import PERSON_MODEL, FACE_MODEL, sha
from evaluate_pose_parts import POSE_FILE, POSE_SHA, load_0287
from replay_face_geometry import load, model, detect_instances
from replay_face_tracking import replay


class CountedModel:
    """Reuse exact single-input inference, never approximate matching.

    Cache raw model outputs only in this video process; bounded LRU memory.
    FrameKernel still decodes/validates ownership for each request and crop.
    """
    def __init__(self, net, budget=0):
        self.net = net
        self.budget = budget
        self.cache = OrderedDict()
        self.bytes = self.calls = self.requests = self.hits = 0
        self.key = None
        self.result = None
        self.inputs = net.get_input_details()
        self.outputs = net.get_output_details()
        if len(self.inputs) != 1:
            raise ValueError("single-input model required")

    def get_input_details(self):
        return self.inputs

    def get_output_details(self):
        return self.outputs

    def set_tensor(self, index, value):
        if index != self.inputs[0]["index"]:
            raise ValueError("unexpected model input")
        self.result = None
        self.key = (value.shape, value.dtype.str, hashlib.sha256(value.tobytes()).digest()) if self.budget else None
        if self.key not in self.cache:
            self.net.set_tensor(index, value)

    def invoke(self):
        self.requests += 1
        if self.key in self.cache:
            self.hits += 1
            self.cache.move_to_end(self.key)
            self.result = self.cache[self.key]
            return
        self.calls += 1
        self.net.invoke()
        if self.key is None:
            return
        self.result = {o["index"]: self.net.get_tensor(o["index"]) for o in self.outputs}
        size = sum(v.nbytes for v in self.result.values())
        if size > self.budget:
            return
        while self.bytes + size > self.budget:
            _, old = self.cache.popitem(last=False)
            self.bytes -= sum(v.nbytes for v in old.values())
        self.cache[self.key] = self.result
        self.bytes += size

    def get_tensor(self, index):
        return self.net.get_tensor(index) if self.result is None else self.result[index].copy()

    def stats(self):
        return {"calls": self.calls, "requests": self.requests, "cache_hits": self.hits}


class CountedReid:
    def __init__(self, net):
        self.net = net
        self.calls = 0

    def __call__(self, values):
        self.calls += 1
        return self.net(values)


def write(path, value):
    path.write_text(json.dumps(value, separators=(",", ":")) + "\n")


def coverage_stats(rows):
    # Missing intervals and discontinuities are placement statistics, not
    # assertions about true target visibility or dense identity correctness.
    gaps = []
    for i, row in enumerate(rows):
        if row["render"] is None:
            if gaps and gaps[-1][1] == i - 1:
                gaps[-1][1] = i
            else:
                gaps.append([i, i])
    return {"frames": len(rows), "regions": sum(r["render"] is not None for r in rows),
            "observed": sum(bool(r.get("track") and r["track"]["observed"]) for r in rows),
            "missing_runs": len(gaps), "longest_missing_run": max((b-a+1 for a,b in gaps), default=0),
            "missing_intervals": gaps}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    for n in ("library", "fixture", "model-dir", "output-dir"):
        ap.add_argument("--" + n, type=Path, required=True)
    ap.add_argument("--target-id", type=int, required=True)
    ap.add_argument("--without-memo", action="store_true", help="Exact inference-cache ablation")
    ap.add_argument("--head-instance-model", type=Path, required=True, help="Pinned nano head model; local research only")
    ap.add_argument("--shared-head-stream", type=Path, help="Reuse same-video dual-view head detections; recompute ownership and links")
    ap.add_argument("--shared-dense", type=Path)
    ap.add_argument("--shared-reference", type=Path)
    a = ap.parse_args()
    if (a.shared_dense is None) != (a.shared_reference is None):
        ap.error("shared dense evidence and its canonical reference must be supplied together")
    start = time.perf_counter()
    if sha(a.model_dir / POSE_FILE) != POSE_SHA:
        raise ValueError("pose weight mismatch")
    a.output_dir.mkdir(parents=True, exist_ok=True)
    canonical_path, profile_path, dense_path, recovery_path = [a.output_dir / (n + ".json") for n in ("canonical", "geometry", "dense", "recovery")]
    fixture = json.loads(a.fixture.read_text())
    lib = load(a.library)
    person = CountedModel(model(PERSON_MODEL))
    face = CountedModel(model(FACE_MODEL), 0 if a.without_memo else 384 * 1024**2)
    pose = CountedModel(model(a.model_dir / POSE_FILE), 0 if a.without_memo else 16 * 1024**2)
    models = (person, face, pose)
    evidence = []
    stages = {}
    mark = start
    def checkpoint(name):
        nonlocal mark
        now = time.perf_counter()
        stages[name] = {"elapsed_s": now-mark, **{n: m.stats() for n,m in zip(("person", "face", "pose"), models)}}
        mark = now
        print(name, stages[name], flush=True)
    fps = fixture.get("fps", fixture.get("source_fps", 30))
    for i, f in enumerate(fixture["frames"]):
        path = a.fixture.parent / f["asset"]
        if sha(path) != f["sha256"]:
            raise ValueError("fixture frame mismatch")
        im = Image.open(path).convert("RGB")
        evidence.append((dict(f, index=i, time_us=round(i*1e6/fps)), im, *detect_instances(lib, person, im)))
    if not evidence:
        raise ValueError("empty fixture")
    def scene(i):
        return evidence[i][1:]
    roots = sorted({i for i,d in enumerate(evidence[0][3]) if d.confidence >= .60} | {a.target_id})
    checkpoint("scene")
    tracked = replay(lib, face, evidence, a.target_id, set(), protected_roots=roots,
                     multiperson_recovery=True, pair_continuity=True, pair_head=True,
                     merged_edge_yolo=person, crowded_recovery=True, crowded_consensus=True,
                     crowd_clothing=True, adaptive_clothing=True, motion_clothing=True, part_continuity=True,
                     anatomical_policy=anatomical_face_policy.AnatomicalPolicy(lib,pose,face))
    canonical = {"scope": "M5.1 canonical association, no source-label input", "fixture_sha256": sha(a.fixture),
                 "library_sha256": sha(a.library), "target_id": a.target_id, "identity_protected_roots": roots,
                 "model_sha256": {"person": sha(PERSON_MODEL), "face": sha(FACE_MODEL)}, "candidate": tracked}
    write(canonical_path, canonical)
    # All downstream stages use JSON-normalized rows (tuples become lists).
    # Compare against the same serialized canonical representation.
    canonical = json.loads(canonical_path.read_text())
    tracked = canonical["candidate"]
    checkpoint("canonical")
    common = dict(library=a.library, fixture=a.fixture, reference_replay=canonical_path, model_dir=a.model_dir)
    fragment.run(SimpleNamespace(**common, output=profile_path), scene=scene, models=models)
    geo = json.loads(profile_path.read_text())
    current = geo["candidate"]["frames"]
    checkpoint("current_geometry")
    # The offline lane cannot repair a current observed body or an existing
    # placement. Avoid the expensive dense model pass if no eligible gap exists.
    needs_dense = any(r["render"] is None and r.get("track") is not None and not r["track"]["observed"] for r in current)
    reid_calls = 0
    spans = []
    attempts = []
    if needs_dense:
        # Reuse the previously validated all-root dense trace across targets
        # of the same fixture; the existing reuse path checks canonical raw
        # ownership, library, models, and every source-frame hash.
        reid = None if a.shared_dense is not None else CountedReid(load_0287(a.model_dir))
        dense.run(SimpleNamespace(**common, output=dense_path, shared_dense=a.shared_dense, shared_reference=a.shared_reference),
                  scene=scene, models=(*models,reid))
        reid_calls = 0 if reid is None else reid.calls
        checkpoint("dense_identity")
        recovery.run(SimpleNamespace(**common, dense=dense_path, output=recovery_path, ear_cap=True, detail_cap=True),
                     scene=scene, models=models, geometry=current)
        offline = json.loads(recovery_path.read_text())
        current = offline["candidate"]["frames"]
        spans, attempts = offline["spans"], offline["attempts"]
        checkpoint("offline_geometry")
    head_stream_path=a.output_dir/"head_stream.json"
    head_reference_path=a.output_dir/"head_reference.json"
    head_result_path=a.output_dir/"head_instances.json"
    head_stats=None
    # Independent geometry uses the canonical raw body observations,
    # not the existing sticker or any audit annotations for identity.
    # Reuse the same decoded scene instead of repeating segmentation.
    write(head_reference_path,{**canonical,"candidate":{"frames":current}})
    args=dict(library=a.library,fixture=a.fixture,reference_replay=head_reference_path)
    stream=head_instances.produce(SimpleNamespace(**args,output=head_stream_path,
        head_model=a.head_instance_model,threads=2,shared_head_stream=a.shared_head_stream),
        scene=scene,person=person)
    result=head_instances.associate(SimpleNamespace(**args,stream=head_stream_path,
        output=head_result_path,
        dense=dense_path if needs_dense else None,dense_reference=canonical_path if needs_dense else None))
    current=result["candidate"]["frames"]
    head_stats={"head_model":stream["head_model"],"head_model_sha256":stream["head_model_sha256"],
        "head_calls":stream["head_calls"],"head_inference_s":stream["head_inference_s"],
        "additional_person_calls":stream["person_calls"],"shared_head_sha256":stream["shared_head_sha256"],"policy":"arbitrate","counts":result["counts"],
        "stream_sha256":sha(head_stream_path),"result_sha256":sha(head_result_path)}
    checkpoint("head_instance_geometry")
    prior = tracked["frames"]
    identity_keys = ("track", "tracks", "raw_boxes", "tracking_snapshot_sha256", "class_evidence", "motion_evidence")
    if len(current) != len(prior) or any(any(x[k] != y[k] for k in identity_keys) for x,y in zip(prior,current)):
        raise RuntimeError("placement path mutated canonical identity")
    by_index = {q["index"]: q["raw"] for q in attempts if q["status"] == 0}
    placements = []
    for i, row in enumerate(current):
        if row["render"] is None:
            placements.append(None)
            continue
        if row.get("independent_head_instance"):
            lane, raw = "independent_head_instance", fragment.canonical_raw(row)
        elif row.get("offline_tracklet_reentry"):
            lane, raw = "offline_tracklet", by_index[i]
        elif row.get("fragment_head_repair"):
            lane, raw = "fragment_window", row["fragment_head_repair"]["raw"]
        elif row.get("anatomical_clock_placement"):
            lane, raw = "anatomical_clock", fragment.canonical_raw(row)
        else:
            lane, raw = "canonical", fragment.canonical_raw(row)
        sticker = row["sticker"] if row.get("sticker") is not None else row["render"]
        x, y, rx, ry = sticker
        radius = max(rx, ry)
        placements.append({"source": lane, "raw_index": raw, "square": [x, y, radius, radius],
                           "render_bounds": fragment.square(sticker), "required_bounds": row.get("sticker_required_bounds"),
                           **({"head_index":row["independent_head_index"]} if row.get("independent_head_instance") else {})})
    out = {**{k:v for k,v in canonical.items() if k != "candidate"}, "scope": __doc__,
           "canonical_sha256": sha(canonical_path), "geometry_sha256": sha(profile_path),
           "dense_sha256": sha(dense_path) if needs_dense else None,
           "recovery_sha256": sha(recovery_path) if needs_dense else None,
           "head_instance_experiment": head_stats, "candidate_config": "m6-unified-nano-clock-reconnect", "dense_scheduled": needs_dense, "dense_reused": needs_dense and a.shared_dense is not None, "baseline": tracked,
           "candidate": {"frames": current, "summary": coverage_stats(current)}, "final_placements": placements,
           "canonical_placement_stats": coverage_stats(prior), "offline_spans": spans,
           "offline_attempts": attempts, "fragment_changed": geo["fragment_changed"],
           "stages_cumulative_model_counts": stages, "reid_calls": reid_calls,
           "elapsed_s": time.perf_counter()-start, "model_calls": {n:m.stats() for n,m in zip(("person", "face", "pose"),models)}}
    write(a.output_dir / "unified.json", out)
    print("UNIFIED", json.dumps({"summary": out["candidate"]["summary"], "model_calls":out["model_calls"], "reid_calls":reid_calls, "elapsed_s":out["elapsed_s"]}), flush=True)


if __name__ == "__main__":
    main()
