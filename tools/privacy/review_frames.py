"""Select real decoded frames without relying on codec random-seek metadata."""
def sequential_selected(capture, indices):
    pending=set(indices)
    if any(not isinstance(i,int) or i<0 for i in pending):
        raise ValueError('Review frame indices must be nonnegative integers')
    index=0
    while pending:
        ok,frame=capture.read()
        if not ok:raise ValueError('Review source/output frame unavailable')
        if index in pending:
            pending.remove(index)
            yield index,frame
        index+=1
