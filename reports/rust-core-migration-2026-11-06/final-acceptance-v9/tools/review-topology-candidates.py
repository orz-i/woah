"""Create local topology review images aligned with the native source crop and PTS."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import sys

import cv2
import numpy as np


def read(path):
    return json.loads(path.read_bytes())


def jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def sha(path):
    with path.open("rb") as f:
        h = hashlib.sha256()
        for b in iter(lambda: f.read(1048576), b""):
            h.update(b)
        return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repository", "run", "media_audit", "sources", "source_faces", "destination"):
        parser.add_argument(name, type=Path)
    a = parser.parse_args()
    sys.path.insert(0, str(a.repository.resolve()))
    from tools.privacy.lifecycle_evidence import retry_case
    from tools.privacy.source_time import bind_microsecond_frame

    api = read(a.run / "results.json")
    rows = list(api["cases"])
    if retry := retry_case(a.run):
        rows.append(retry)
    by_case = {v["case"]: v for v in rows}
    meta = read(a.run / "run-metadata.json")
    traces = {}
    for name in set(meta.get("crop_traces", []) + list(meta.get("additional_crop_traces", {}).values())):
        file = a.run / "crop-traces" / name
        entries = jsonl(file)
        movie = entries[0]["output_basename"]
        assert movie not in traces
        traces[movie] = (file, entries)
    audit = read(a.media_audit)
    a.destination.mkdir(parents=True)
    found = []
    for media in audit["cases"]:
        candidates = media.get("topology_candidates", [])
        if not candidates:
            continue
        name = media["case"]
        row = by_case[name]
        movie = a.run / "outputs" / (name + ".mp4")
        assert sha(movie) == media["sha256"]
        trace_file, (header, *crops) = traces[movie.name]
        assert header["source_basename"] == row["input"]
        source = a.sources / row["input"]
        source_hash = meta["sources"][row["input"]]["sha256"]
        scan = jsonl(a.source_faces / (source.stem + ".jsonl"))
        assert sha(source) == scan[0]["sha256"] == source_hash
        assert scan[-1]["kind"] == "finished"
        pts = {round(v["pts"] * 1e6): v for v in scan if v["kind"] == "frame"}
        n = src_n = -1
        output = cv2.VideoCapture(str(movie))
        original = cv2.VideoCapture(str(source))
        items = {}
        for i, raw in sorted(enumerate(candidates), key=lambda v: v[1]["frame"]):
            frame = raw["frame"]
            crop = crops[frame]
            assert crop["frame_index"] == frame
            src_row, _ = bind_microsecond_frame(pts, crop["t_us"])
            while n < frame:
                ok, rendered = output.read()
                assert ok
                n += 1
            while src_n < src_row["frame"]:
                ok, source_frame = original.read()
                assert ok
                src_n += 1
            assert src_n == src_row["frame"]
            oh, ow = rendered.shape[:2]
            sh, sw = source_frame.shape[:2]
            assert (ow, oh) == (header["output_width"], header["output_height"])
            l, t, r, b = crop["source_crop_normalized"]
            sx, sy = (r - l) * sw / ow, (b - t) * sh / oh
            assert sx > 0 and sy > 0
            x, y, w, h = raw["bbox"]
            assert 0 <= x < x + w <= ow and 0 <= y < y + h <= oh
            images = {}
            for pad, label, size in [(110, "context", 520), (16, "detail", 350)]:
                left, top = max(0, x - pad), max(0, y - pad)
                right, bottom = min(ow, x + w + pad), min(oh, y + h + pad)
                transform = np.array([
                    [sx, 0, l * sw + (left + .5) * sx - .5],
                    [0, sy, t * sh + (top + .5) * sy - .5],
                ])
                aligned = cv2.warpAffine(
                    source_frame, transform, (right - left, bottom - top),
                    flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP)
                actual = rendered[top:bottom, left:right].copy()
                for tile in (aligned, actual):
                    cv2.rectangle(tile, (x-left, y-top), (x+w-left, y+h-top), (0, 255, 255), 1)
                pair = np.hstack([cv2.resize(v, (size, size), interpolation=cv2.INTER_NEAREST)
                                  for v in (aligned, actual)])
                title = np.zeros((32, size * 2, 3), np.uint8)
                cv2.putText(title, f"output {frame} / source {src_n}: aligned source / actual output",
                            (5, 22), 0, .48, (0, 255, 255), 1)
                file = a.destination / f"topology-{len(found) + i:02d}-{label}.jpg"
                assert cv2.imwrite(str(file), np.vstack([title, pair]), [cv2.IMWRITE_JPEG_QUALITY, 96])
                images[file.name] = sha(file)
            items[i] = {"index": len(found) + i, "case": name, "candidate": raw, "source_frame": src_n,
                        "source_sha256": source_hash, "movie_sha256": media["sha256"],
                        "trace_sha256": sha(trace_file), "images": images}
        output.release()
        original.release()
        found.extend(items[i] for i in range(len(items)))
    (a.destination / "index.json").write_text(json.dumps({
        "scope": "Local crop-aligned source and actual movie comparisons. Requires visual judgment.",
        "raw_media_audit_sha256": sha(a.media_audit), "items": found,
    }, indent=2) + "\n", encoding="utf-8")
    print("TOPOLOGY_CROPS_REQUIRE_REVIEW", len(found))


if __name__ == "__main__":
    main()
