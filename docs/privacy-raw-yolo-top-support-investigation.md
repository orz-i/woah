# FULL_BODY: raw YOLO mask support vs detector-box truncation

Historical implementation note: the Debug-only counterfactual was implemented at `3b4ba5a8259e6fae94ff140e27b5277c3b5079a5` and exercised by subsequent paired Android exports. **It has now been retired from Android inference/DTO/privacy telemetry**, because the captured 39.733–39.900s model response above the contracted protagonist bbox was too weak and ambiguous to justify a safety-preserving pixel correction. Production rendering was never changed by this probe; the historical anonymous ZIP parser remains read-only and compatible. The implementation details below describe that historical experiment, **not currently enabled runtime functionality**. FULL_BODY visual acceptance remains **NOT_ACCEPTED**. Baseline before the experiment: `111b65e1a7ce3763406f1475252ee9dd3f1cc03e`. Contains no source video, screenshot, per-pixel coordinates, raw RGB or binary masks.

## What the code audit establishes

- Production `YoloLiteRtSegmenter.segmentGlReadbackRgbaSync` calls `YoloLiteRtTensorAdapter.parseDetections` on model output arrays. The decoder reconstructs an instance mask from 32 prototype channels and 32 coefficients **only inside the detection box plus a one-proto-pixel margin**. Once the detected protagonist box top moves down, its current decoded Mask has zeros above that rectangle whether or not the raw prototype coefficients still activate there. The same bounded-support operation is used in the fast NCHW array and compatibility paths.
- `YoloMaskDecoder.calculateMaskIoU` and its bounded-support equivalent currently count every **nonzero** byte as active for NMS. Sigmoid values below 128 can therefore participate in duplicate suppression. This is a separate plausible suppression artifact, **not** an established explanation of the 39.8s lost protagonist silhouette. Do not switch NMS thresholds on the existing output pipeline without an actual model-output and protected-output A/B regression.
- `KalmanFilter` maintains XYAH/velocity/covariance for box association and temporary geometric continuity. `TrackManager` may warp historical masks for tracking or fallback; neither Kalman continuity, predicted boxes, matching scores nor footY can establish that a currently absent upper-arm/hair pixel is unprotected.
- `PrivacyOcclusionResolver` resolves per protected target, unions effective protection, and creates its GL occluder only where the **global** effective protection is zero. Maintain the older `framesSinceLastObservation` fallback expiry: the 46s ceiling ghost must not return.

## Implemented probe (no privacy behavior changes)

`YoloRawTopSupportProbe.summarize` evaluates the original model coefficients against the original prototype just **above** the box-supported mask. The band is up to 12 prototype rows high, in the candidate's supported horizontal extent. Only kept detections with an intersecting second detection in the current frame are sampled, only for a Debug job with a diagnostic ID. Release and ordinary inference skip the extra work.

The existing `PRIVACY_UNKNOWN_FOREGROUND_PIXEL_AUDIT` event (one relevant overlap per frame) gains two optional **aggregate integer maps**: `target_yolo_raw_top_support` for the conservative-unknown SELECTED target, and `candidate_yolo_raw_top_support` for its fresh UNSELECTED foreground candidate. Each reports:

- `outside_sampled`: number of prototype cells directly above the decoded candidate mask's supported top;
- `outside_ge128`, `outside_ge192`, `outside_ge224`: raw sigmoid counts in that band (nested thresholds, not ground truth);
- `inside_sampled`, `inside_decoded_ge128`: comparison with the first up-to-12 **decoded** rows inside the supported box;
- `band_height_proto`: fixed upper sampling budget of 12 rows.

No source pixels, images, Mask arrays, coordinates, RGB hashes, appearance descriptors, or additional per-frame events are emitted. The optional fields are also validated and compared across landscape/portrait exports by `analyze_privacy_overlap_evidence.py`; older ZIPs remain compatible and missing values are **unknown**, not zero.

**Interpretation:** For candidate detection index 3, positive `outside_ge224` during the 39.8s protagonist upper-body loss means the model's raw prototype has positive responses which the bbox decoder discarded. It does **not** say those cells belong to the protagonist: selected ID1 or the unknown candidate could also activate them. Values close to zero mean a box-only widening has no corroborating raw-model signal, shifting investigation toward independent source-frame motion/appearance or a separate segmentation source. No result authorizes unmasking.

## Separate motion/appearance route: guarded design, NOT integrated

A future local-only bounded motion probe should reuse an **actual source-frame** RGBA/luma pair before crop, with independently observed protagonist interior seeds. Candidates must pass bidirectional patch/flow correspondence (forward/backward consistency), an appearance reconstruction check, reliable source coordinate/PTS contract, and a strict short continuity window. Moving foreground is not equivalent to protagonist identity; white-shirt textureless regions and crossing dancers can be ambiguous. Every candidate in a current protected-instance mask is vetoed; an independently observed protected root/unknown region must not be overridden by a matched protagonist trajectory. Missing/conflicting evidence defaults to protection.

Minimum adversarial tests before any compositor use: (1) constant raw prototype but contracted bbox top, (2) genuine model signal disappearance, (3) two crossing people with similar apparel/motion, (4) selected person moving into a formerly protagonist-owned pixel, (5) occlusion/disocclusion and camera pan, (6) source geometry mismatch or time gap, (7) 46.333s stale protected ID1 still excluded. A possible flow implementation can be evaluated offline with numerical true/false recovery counts and protected-person false-clear counts before a device-side shadow implementation. **Zero newly exposed selected pixels** is a release gate; numerical or synthetic success still requires final-video privacy review.

Motion algorithm background (not a dependency choice): OpenCV documents forward/backward rejection for optical flow and the additional computational cost, https://docs.opencv.org/4.12.0/d2/d84/group__optflow.html ; DIS optical flow offers multiple speed/accuracy presets, https://docs.opencv.org/4.10.0/javadoc/org/opencv/video/DISOpticalFlow.html . Memory-based segmentation such as XMem is a larger separate model/runtime investment, https://arxiv.org/abs/2207.07115 . Do not introduce OpenCV, XMem, SAM2, ONNX or image retention simply for a speculative carve.

## Validation and next decision

Python analyzer and source boundary tests can run on Mac. Kotlin JUnit tests for this probe cover real clipping despite positive raw logits, negative logits, a temporal top-box contraction, synthetic overlapping-person ambiguity, source top edge, and synthetic-mask rejection; Android toolchain unavailable locally means native compilation/APK and actual-image privacy validation must still be done on the Windows/device environment when justified.

Run the existing ordinary Debug export in **both** orientations when a device verification is next needed, then compare aggregate reports on 39.733–39.900s. Do **not** supply source crops or screenshots to analyze these scalar fields. If the model has raw response above the protagonist box, evaluate whether it remains uniquely attributable to the protagonist with independent pixel correspondence and a protected veto *before* any Mask-expansion or carve experiment. If not, stop that route; no arbitrary bbox extension or delayed past-mask clearing.

The 39.733s ~79.27% coverage warning is still outstanding; the 46s mask-expiry repair is unchanged. **Visual privacy acceptance = NOT_PROVEN.**
