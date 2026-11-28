# M6 — Independent head model deployment review

Base `34205742`. Bounded candidate assessment, no change to the unified M6 algorithm, thresholds, model weights or native application.

## Candidate and provenance

Google's [Open Images model release](https://storage.googleapis.com/openimages/web/extras.html) explicitly licenses the SSD MobileNet V2 V4 model under Apache 2.0. Downloaded the official `ssd_mobilenet_v2_oid_v4_2018_12_12` archive, pinned archive/graph/label-map hashes and read the frozen graph in OpenVINO 2025.4. The original graph's generic multiclass postprocessing took approximately 4.1 s/view in the initial host probe; the complete probe was stopped because of cost. Cutting the graph at its decoded boxes and sigmoid scores exposes 1,917 anchors and 602 score channels. Feed source-mapped xywh/score rows into the existing FrameKernel decoder, NMS and dual-view fusion. No new tracking or geometry code.

There is a historical class-map inconsistency: pipeline.config names the 545-class legacy label map (Head=39), whereas the actual graph has 601 foreground channels plus background, and native TensorFlow outputs for person, clothing and face match the 601-class V4 map (Human head=292). Tested both; V4 is the primary comparison. Five independent TensorFlow 2.20 native-graph controls confirm low head confidence on the selected full source frames. The incorrect legacy-channel experiment is retained only as a diagnostic, not used as the main result.

## Measured comparison

Use all matching existing sparse labeled head/neighbor envelopes, deduplicated by video, frame, role and rectangle: nine videos, 157 source frames, 168 target and 114 neighboring head envelopes. Evaluate box recall at IoU >= 0.5; not dense ground truth, identity accuracy or precision.

| Head observation | Target recall | Neighbor recall |
|---|---:|---:|
| M6 Nano | 135/168 | 78/114 |
| SSD, full frame | 0/168 | 0/114 |
| SSD, four fixed overlapping views per direction | 0/168 | 0/114 |
| SSD, full frame, fixed score multiplier 10 | 41/168 | 12/114 |
| SSD, full frame, fixed score multiplier 20 | 79/168 | 28/114 |

All SSD cases use the same FrameKernel decode and dual-view fusion. Score multipliers are diagnostics, not calibrated model probabilities or proposed thresholds. Even multiplier 20 gets 0/49 Baganda target envelopes and only 1/9 Swing; no exhaustive negative annotations support a precision claim. Fixed four-tile inference costs approximately four times the single-view raw graph. Raw graph averaged about 17 ms/view on Mac CPU, excluding preprocessing, transfer and decoding; no device benchmark. Stop this candidate; do not add runtime, weight or algorithm option to M6.

## Independent head stage necessity and decision

Re-evaluate the exact cached pre-head and final M6 rows across 13 targets, rather than running an unsupported replacement through the expensive full pipeline. Removing independent head arbitration/reconnect reduces proposed regions from 2,093 to 1,981 of 2,665: Baganda 189 to 91, Basque 102 to 92, Swing ID7 269 to 265. Baganda's 17/17 and 20/20 newly covered audited head envelopes become 0/17 and 0/20. Some existing geometric arbitration regressions also disappear, but the loss of proven recovery is unacceptable as an unconditional replacement.

Other candidates: [IoT-Edge Head Person Detection](https://www.modelscope.cn/models/IoT-Edge/Head_Person_Detection) is tagged Apache 2.0; original revision file list shows YOLOv5 source/checkpoint and no explicit training provenance. Do not run its pickle checkpoint or assume entitlement. [CrowdHuman-derived weights](https://huggingface.co/wesjos/YOLO26n-Crowdhuman-Head-Detection) with explicit non-commercial terms and SHWD-derived datasets with [SCUT-HEAD lineage](https://github.com/njvisionpower/Safety-Helmet-Wearing-Dataset) do not resolve the original deployment issue. [Ultralytics licensing](https://www.ultralytics.com/license) remains relevant to existing YOLO11n-seg as well as Nano. MoveNet's [official card](https://storage.googleapis.com/movenet/MoveNet.SinglePose%20Model%20Card.pdf) states Apache 2.0; [OMZ 0287 model metadata](https://github.com/openvinotoolkit/open_model_zoo/blob/master/models/intel/person-reidentification-retail-0287/model.yml) links the OMZ license. Do not infer a whole-app license conclusion from a model card; existing agreements and attribution requirements must be checked.

Next choose rights-cleared existing head weights or one independently trained, small, single-class detector with a permissive implementation and documented training-source rights (for example verified CC BY images and appropriately licensed annotations, without restricted SCUT/CrowdHuman weights or pseudo-labels). Do not spend additional rounds threshold-tuning this SSD or adding model-specific head/geometry fallbacks. Existing M6 local baseline stays frozen. No mobile packaging, license purchase or project license change. The original model distribution and residual quality blockers remain.

Local detailed scripts and raw measurements are under `issue47-local/m6-deploy`; the machine summary pins hashes, per-source counts and ablation. No model bytes, source media or full traces are committed. No new Rust checks are claimed because the Rust/host candidate code is unchanged.
