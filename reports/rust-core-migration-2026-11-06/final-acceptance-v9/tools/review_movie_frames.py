"""Select actual movie frames, checking seeks against independent full-decode samples."""
import hashlib
import cv2


def movie_sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def review_movie_frames(movie, count, dimensions, media_row):
    indices = [0, count // 2, count - 1]
    digest = movie_sha256(movie)
    width, height = dimensions
    if (media_row.get('status') in {'passed_media', 'needs_topology_review'}
            and media_row.get('sha256') == digest
            and media_row.get('decoded_frames') == count
            and media_row.get('dimensions') == [width, height]):
        selected = {}
        sample_dir = movie.parent.parent / 'review-frames'
        capture = cv2.VideoCapture(str(movie))
        try:
            for n in dict.fromkeys(indices):
                sample = sample_dir / f'{movie.stem}-frame-{n}.jpg'
                if not sample.is_file() or not capture.set(cv2.CAP_PROP_POS_FRAMES, n):
                    break
                ok, frame = capture.read()
                if not ok or int(capture.get(cv2.CAP_PROP_POS_FRAMES)) != n + 1:
                    break
                thumb_width = min(960, width)
                thumb = cv2.resize(frame, (thumb_width, round(height * thumb_width / width)))
                ok, encoded = cv2.imencode('.jpg', thumb)
                if not ok or encoded.tobytes() != sample.read_bytes():
                    break
                selected[n] = frame
        finally:
            capture.release()
        if set(selected) == set(indices):
            return indices, selected, digest, 'seek_matches_independent_decode_samples'

    capture = cv2.VideoCapture(str(movie))
    selected = {}
    decoded = 0
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            if decoded in indices:
                selected[decoded] = frame.copy()
            decoded += 1
    finally:
        capture.release()
    if decoded != count:
        raise ValueError(f'{movie.name}: expected {count} decoded frames, got {decoded}')
    return indices, selected, digest, 'full_sequential_decode'
