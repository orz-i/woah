#!/usr/bin/env python3
"""Local-only QA observation cache controls and explicit reviewed-baseline approval.

No media, masks, observations, or approvals are uploaded by this tool.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re

HASH = re.compile(r"[0-9a-f]{64}")
def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def contract(manifest, environment):
    # Conservative dependency boundary. Only the isolated rendering-geometry
    # implementation is excluded: all detector input bytes are also keyed at runtime.
    excluded = {"face_geometry.rs", "ffi_face_geometry.rs"}
    def owns(name):
        if name.startswith("crates/frame-kernel/"):
            return name.rsplit("/", 1)[-1] not in excluded and "/include/" not in name
        return any(part in name for part in [
            "/inference/", "/litert/", "/geometry/", "/cpp/", "/assets/models/",
            "/Resources/", "GridFaceLocator", "QaObservation",
            "IOSYolo", "IOSFrameKernel.swift", "build.gradle", "Podfile.lock",
            "pubspec.lock", ".podspec"])
    files = {name: digest for name, digest in manifest["source_files"].items() if owns(name)}
    assert files and all(HASH.fullmatch(x) for x in files.values())
    assert environment["platform"] == manifest["platform"]
    assert environment.get("device") and environment.get("os") and environment.get("runtime")
    value = {"schema": "qa-observation-v1", "platform": manifest["platform"],
             "environment": environment, "observation_sources": files}
    digest = hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return digest, value

def approve(directory, results_path, receipt_path):
    results = json.loads(results_path.read_bytes())
    receipt = json.loads(receipt_path.read_bytes())
    assert results["status"] == "finished"
    assert receipt["decision"] == "approved_observation_baseline"
    assert receipt["baseline_results_sha256"] == sha(results_path)
    assert receipt["compiled_source_commit"] == results["compiled_source_commit"]
    assert set(receipt["reviewed_case_ids"]) == {r["case"] for r in results["cases"]}
    assert results["cases"] and all(r["state"] == "completed" and r["result"] == "output_created"
                                    for r in results["cases"])
    events = directory / "events.tsv"
    assert receipt["events_sha256"] == sha(events)
    snapshot = receipt_path.with_name(receipt_path.stem + ".events.tsv")
    if snapshot.exists():
        assert sha(snapshot) == receipt["events_sha256"]
    else:
        snapshot.write_bytes(events.read_bytes())
        assert sha(snapshot) == receipt["events_sha256"]
    start = dt.datetime.fromisoformat(results["started_at_utc"].replace("Z", "+00:00")).timestamp()*1000
    end = dt.datetime.fromisoformat(results["finished_at_utc"].replace("Z", "+00:00")).timestamp()*1000
    pids = {int(results["process_id"])} | {int(r.get("pid", results["process_id"])) for r in results["cases"]}
    selected = {}
    conflicts = set()
    for line in events.read_text().splitlines():
        stamp, pid, key, operation, digest = line.split("\t")
        if not (start <= int(stamp) <= end and int(pid) in pids): continue
        assert HASH.fullmatch(key)
        if operation == "conflict": conflicts.add(key)
        if operation == "inference":
            assert HASH.fullmatch(digest)
            if key in selected and selected[key] != digest: conflicts.add(key)
            selected[key] = digest
    count = 0
    for key, digest in selected.items():
        if key in conflicts or (directory / (key+".conflict")).exists(): continue
        payload = directory / (key+".bin")
        assert payload.is_file() and sha(payload) == digest
        temporary = directory / (key+".approved.tmp")
        temporary.write_text(digest+"\n", encoding="utf-8")
        temporary.replace(directory / (key+".approved"))
        count += 1
    assert count, "No real detector observations were qualified"
    return {"schema":"qa-observation-approval-v1", "approved": count, "conflicted":len(conflicts),
            "baseline_results_sha256":sha(results_path), "review_receipt_sha256":sha(receipt_path),
            "events_sha256":sha(events), "compiled_source_commit":results["compiled_source_commit"]}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    control = sub.add_parser("control")
    control.add_argument("manifest", type=Path)
    control.add_argument("environment", type=Path)
    control.add_argument("output", type=Path)
    approval = sub.add_parser("approve")
    approval.add_argument("directory", type=Path)
    approval.add_argument("results", type=Path)
    approval.add_argument("review_receipt", type=Path)
    approval.add_argument("output_receipt", type=Path)
    stats = sub.add_parser("stats")
    stats.add_argument("directory", type=Path)
    args = parser.parse_args()
    if args.operation == "control":
        manifest = json.loads(args.manifest.read_bytes())
        digest, value = contract(manifest, json.loads(args.environment.read_bytes()))
        artifact = manifest["binary_sha256"]
        assert HASH.fullmatch(artifact)
        assert not args.output.exists(), "Preserve the previous control"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text("qa-observation-v1\n"+artifact+"\n"+digest+"\n", encoding="utf-8")
        args.output.with_suffix(".contract.json").write_text(json.dumps(value,indent=2)+"\n")
        print(json.dumps({"contract_sha256":digest,"artifact_sha256":artifact,"sources":len(value["observation_sources"])}))
    elif args.operation == "approve":
        assert not args.output_receipt.exists()
        result = approve(args.directory,args.results,args.review_receipt)
        args.output_receipt.write_text(json.dumps(result,indent=2)+"\n")
        print(json.dumps(result))
    else:
        counts = {}
        for line in (args.directory/"events.tsv").read_text().splitlines():
            operation=line.split("\t")[3]; counts[operation]=counts.get(operation,0)+1
        print(json.dumps({"operations":counts,"approved_entries":len(list(args.directory.glob("*.approved"))),
                          "pending_entries":len(list(args.directory.glob("*.bin")))}))
if __name__ == "__main__": main()
