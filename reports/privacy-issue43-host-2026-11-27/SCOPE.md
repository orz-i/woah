# Issue #43 production scope

CI was green at da670362 (run 46019365514). The complete S4.2 multi-person recovery, garment and offline re-detection research is preserved on [research/framekernel-multiperson-occlusion-20261127](https://github.com/orz-i/woah/tree/research/framekernel-multiperson-occlusion-20261127), commit da670362; follow-up [#47](https://github.com/orz-i/woah/issues/47).

The #43 branch retains the S1–S4.1 compact current coverage, render-size continuity and host-only head-topology/pixel-motion experiments, with the S4.1 Clippy correction. The S4.2 algorithms, bindings and data are removed from this production PR rather than merely disabled at runtime.

Initial production integration prioritizes shared final size continuity after the existing actual coverage mask is resolved, per selected sticker owner. Host square-placement results are not equivalent to the Android mask-clipped sticker or iOS ellipse artwork. Neither compact-square placement nor the experimental pixel bridge should replace existing mobile coverage/ownership until validated with actual outputs. Existing ownership and mask protections remain in effect. Device-level acceptance of #43 remains required.
