"""Bind native integer-microsecond media PTS to the frozen source scan."""
def bind_microsecond_frame(frames, timestamp_us):
    if timestamp_us in frames:return frames[timestamp_us],timestamp_us
    # AVFoundation source scans round rational CMTime to microseconds;
    # Android MediaExtractor floors it. At most one microsecond is lost.
    nearby=[t for t in (timestamp_us-1,timestamp_us+1) if t in frames]
    if len(nearby)!=1:raise ValueError(f'Source PTS binding missing or ambiguous {timestamp_us}')
    matched=nearby[0]
    return frames[matched],matched
