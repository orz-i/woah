#!/usr/bin/env python3
"""Verify a native QA replay ledger against reviewed detector producers.

Inputs stay local. The output contains only counts, run IDs and evidence hashes.
"""
from __future__ import annotations

import argparse
from collections import Counter
import datetime as dt
import json
from pathlib import Path

from observation_replay import HASH, contract, sha


def read(path):
    return json.loads(path.read_bytes())


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def events(path, results):
    start, end = (
        dt.datetime.fromisoformat(results[key].replace("Z", "+00:00")).timestamp() * 1000
        for key in ("started_at_utc", "finished_at_utc")
    )
    pid = int(results["process_id"])
    require(start <= end, "Invalid native run time window")
    require(all(int(row["pid"]) == pid for row in results["cases"]), "Native PID changed")
    allowed = {"inference", "replay", "conflict", "rejected", "record_failed"}
    with path.open(encoding="utf-8") as ledger:
        for line in ledger:
            stamp, event_pid, key, operation, digest = line.rstrip("\r\n").split("\t")
            if int(event_pid) != pid or not start <= int(stamp) <= end:
                continue
            require(operation in allowed and HASH.fullmatch(key), "Invalid observation ledger row")
            expected = operation in {"inference", "replay", "conflict"}
            require(bool(HASH.fullmatch(digest)) if expected else digest == "", "Invalid payload hash")
            yield key, operation, digest


def checked_run(path, contract_doc, expected_contract):
    result = read(path / "results.json")
    manifest = read(path / "build-manifest.json")
    meta = read(path / "run-metadata.json")
    require(result["status"] == "finished" and result["cases"] and meta["finished_utc"],
            "Run did not finish")
    require(result["compiled_source_commit"] == manifest["source_commit"]
            and result["compiled_source_fingerprint"] == manifest["source_fingerprint"]
            and meta["binary_sha256"] == manifest["binary_sha256"], "Native artifact binding changed")
    digest, computed = contract(manifest, contract_doc["environment"])
    require(digest == expected_contract
            and all(contract_doc.get(k) == v for k, v in computed.items()),
            "Observation contract changed")
    return result, manifest, meta["config"]["run_id"]


def qualify(approved_run, expected_contract, contract_doc):
    run, snapshot, review_file, approval_file = approved_run
    result, _, _ = checked_run(run, contract_doc, expected_contract)
    review, approval = read(review_file), read(approval_file)
    require(review["decision"] == "approved_observation_baseline"
            and approval["schema"] == "qa-observation-approval-v1", "Producer lacks approval")
    require(approval["baseline_results_sha256"] == review["baseline_results_sha256"]
            == sha(run / "results.json"), "Producer results changed")
    require(approval["review_receipt_sha256"] == sha(review_file), "Producer review changed")
    require(approval["events_sha256"] == review["events_sha256"] == sha(snapshot),
            "Producer ledger changed")
    require(approval["compiled_source_commit"] == review["compiled_source_commit"]
            == result["compiled_source_commit"], "Producer source changed")
    require(set(review["reviewed_case_ids"]) == {row["case"] for row in result["cases"]}
            and all(row["state"] == "completed" and row["result"] == "output_created"
                    for row in result["cases"]), "Producer case review does not match")
    observed, conflict = {}, set()
    for key, operation, digest in events(snapshot, result):
        if operation == "conflict":
            conflict.add(key)
        if operation == "inference":
            if key in observed and observed[key] != digest:
                conflict.add(key)
            observed[key] = digest
    qualified = {k: v for k, v in observed.items() if k not in conflict}
    require(len(qualified) == approval["approved"] > 0 and len(conflict) == approval["conflicted"],
            "Producer ledger does not reproduce its approval counts")
    return qualified, {"compiled_source_commit": result["compiled_source_commit"],
                       "approved_entries": len(qualified), "approval_sha256": sha(approval_file)}


def audit(run, ledger, control, contract_file, approved_runs):
    schema, binary, namespace = control.read_text(encoding="utf-8").splitlines()
    require(schema == "qa-observation-v1", "Invalid observation control")
    contract_doc = read(contract_file)
    require(contract_doc.get("contract_sha256", namespace) == namespace
            and contract_doc.get("artifact_sha256", binary) == binary, "Control receipt changed")
    result, manifest, run_id = checked_run(run, contract_doc, namespace)
    require(manifest["binary_sha256"] == binary, "Observation control targets another binary")
    qualified, receipts = {}, []
    for producer in approved_runs:
        entries, receipt = qualify(producer, namespace, contract_doc)
        require(all(qualified.get(k, v) == v for k, v in entries.items()),
                "Approved producers disagree on a payload")
        qualified.update(entries)
        receipts.append(receipt)
    counts, conflicts, hits = Counter(), set(), set()
    for key, operation, digest in events(ledger, result):
        if operation == "conflict":
            conflicts.add(key)
        if operation == "replay":
            require(qualified.get(key) == digest and key not in conflicts,
                    "Replay has no compatible qualified producer")
            hits.add(key)
        counts[operation] += 1
    require(counts["replay"] > 0, "No native replay evidence")
    require(counts["record_failed"] == 0, "Some inference measurements were not recorded")
    return {
        "schema": "qa-observation-audit-v1",
        "platform": result["platform"],
        "run_id": run_id,
        "compiled_source_commit": result["compiled_source_commit"],
        "binary_sha256": binary,
        "contract_sha256": namespace,
        "results_sha256": sha(run / "results.json"),
        "event_snapshot_sha256": sha(ledger),
        "approved_producers": receipts,
        "approved_entries": len(qualified),
        "replayed_calls": counts["replay"],
        "unique_replayed_entries": len(hits),
        "fresh_calls_recorded": counts["inference"] + counts["conflict"],
        "conflict_events": counts["conflict"],
        "quarantined_entries": len(conflicts),
        "rejected_reads": counts["rejected"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("run", "ledger", "control", "contract_file", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--approved-run", nargs=4, action="append", type=Path, required=True,
                        metavar=("RUN", "LEDGER", "REVIEW", "APPROVAL"))
    args = parser.parse_args()
    report = audit(args.run, args.ledger, args.control, args.contract_file, args.approved_run)
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
