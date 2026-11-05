"""Require an artifact-specific visual decision for every automatic hole candidate."""
from pathlib import Path

def approved(audit, reviews, folder):
    candidates = audit.get("candidates", [])
    if audit.get("frames_with_candidates") == 0:
        return not candidates
    review = next((r for r in reviews if r.get("file") == audit.get("file") and r.get("sha256") == audit.get("sha256")), None)
    if not review or review.get("result") != "passed":
        return False
    decisions = review.get("candidates", [])
    def key(row):
        return row["frame"], tuple(row["bbox"]), row["interior_pixels"]
    if sorted(map(key, candidates)) != sorted(map(key, decisions)):
        return False
    return all(r.get("classification") in ("gap_between_distinct_faces", "unchanged_source_color", "mask_sampling_edge") and r.get("reason") and (Path(folder) / r.get("comparison_artifact", "")).is_file() for r in decisions)
