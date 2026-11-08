# Current face rendering coverage

A current face detector can localize only the rear of a side-facing head. Switching
from a larger head fallback to that smaller detected ellipse can expose the nose
and mouth even though the current segmentation still covers those visible pixels.
Ownership alone does not establish sufficient render coverage.

`frame_kernel_current_face_region` is the shared render-only operation. Android
and iOS call it after temporal/size processing and immediately before emitting a
detected face region. They pass the current person's current mask; retained masks
and inferred identity are not inputs to this operation.

The core searches the inferred face-height band for coherent head-sized mask runs
near the existing ellipse. Broad body runs, distant support and isolated rows do
not establish a head envelope. It adds one prototype cell around supported bounds
to cover raster quantization, then constructs an ellipse containing both the
complete original ellipse and the visible-support rectangle. After that envelope,
one source-space prototype cell is added to both radii, including when a supplied
current mask cannot establish a head run. This sampling margin protects a chin
at the geometric edge and retains the entire previous envelope. No current mask
retains the original region; malformed supplied evidence fails without writing.

A current face can also be visible without a reliable person match. iOS's
existing protect-unassigned policy emits such a face independently. This
rendering path now calls `frame_kernel_standalone_face_region` for the same
source-space sampling margin. It does not guess which body owns the face or
borrow a person's mask. The detector ellipse and ownership scores are unchanged;
only the standalone region submitted for rendering grows.

This is additive render coverage, not an ownership score, confidence threshold,
identity assignment or exemption. The detector geometry ABI and frozen legacy
geometry remain available unchanged. Existing current-face ownership checks and
150/800 ms lifetimes retain their contracts. The native adapters only transport
data and preserve the original region's source classification.

Public regressions use synthetic rectangles and mirrored profiles. They verify
coverage of independent feature probes, retention of the complete input ellipse,
noise/body rejection, invalid-input atomicity, JNI transport and the Swift
production resolver. Actual diagnostic masks and observations remain local to
their originating host; a successful local replay does not replace actual
export/previews or the two complete 62-case regressions.

The envelope can be conservative and larger than the detector's face. Privacy
coverage does not establish minimum patch area or identical platform pixels.
