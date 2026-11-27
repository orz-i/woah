//! Explicit user-touch recovery, separate from ongoing identity association.
//! A successful primary touch is never replaced. The same current source
//! sample supplies local observations; no prediction or guessed seed.
use crate::{
    appearance::{APPEARANCE_BINS, feature, present},
    composition::{Viewport, cutout_select},
    dance_cutout::{CutoutObservation, STRONG_CONF},
    dance_detail::remap,
    geometry::{Rect, Transform, detail_complete},
    instances::InstanceDetection,
};

pub(crate) fn view(t: Transform, x: f32, y: f32, mode: u32) -> Option<Viewport> {
    if !t.valid()
        || !(0. ..1.).contains(&x)
        || !(0. ..1.).contains(&y)
        || !(2..=65535).contains(&t.source_width)
        || !(2..=65535).contains(&t.source_height)
    {
        return None;
    }
    let fraction = match mode {
        0 => 0.32,
        1 => 0.20,
        2 => 0.50,
        _ => return None,
    };
    let (sw, sh) = (t.source_width as f64, t.source_height as f64);
    let side = (sw.max(sh) * fraction).round().max(2.);
    let (w, h) = (side.min(sw), side.min(sh));
    Some(Viewport {
        x: (f64::from(x) * sw - w * 0.5).round().clamp(0., sw - w) as i32,
        top: (f64::from(y) * sh - h * 0.5).round().clamp(0., sh - h) as i32,
        width: w as i32,
        height: h as i32,
    })
}

fn valid(o: CutoutObservation<'_>) -> bool {
    let t = o.transform;
    t.valid()
        && t.input_size <= 1024
        && o.detections.len() <= 256
        && o.masks.len() == o.detections.len() * (t.proto_size as usize).pow(2)
        && o.features.len() == o.detections.len() * APPEARANCE_BINS
        && o.detections
            .iter()
            .all(|d| d.bounds.valid() && (0. ..=1.).contains(&d.confidence))
        && o.features
            .chunks_exact(APPEARANCE_BINS)
            .all(|f| feature(f).is_some())
}

/// Unlike the canonical selection, never pick one of two threshold-level
/// hits even when its alpha is higher. Used exclusively for local rescue.
fn unique(masks: &[u8], t: Transform, x: f32, y: f32) -> Result<usize, i32> {
    let area = (t.proto_size as usize).pow(2);
    let mut hit = None;
    for (i, mask) in masks.chunks_exact(area).enumerate() {
        if cutout_select(mask, t, x, y) >= 0 && hit.replace(i).is_some() {
            return Err(-2);
        }
    }
    hit.ok_or(-1)
}

pub(crate) struct TouchSeed {
    pub selected: usize,
    pub rows: Vec<InstanceDetection>,
    pub masks: Vec<u8>,
    pub features: Vec<f32>,
}

/// -1 no local touch, -2 ambiguous, -3 invalid, -4 artificial-edge
/// truncation (may expand once), -5 cannot safely remap/seed. Never
/// replace the full-frame selection, and keep remappable competitors.
pub(crate) fn merge(
    full: CutoutObservation<'_>,
    local: CutoutObservation<'_>,
    roi: Viewport,
    x: f32,
    y: f32,
) -> Result<TouchSeed, i32> {
    let t = full.transform;
    let lt = local.transform;
    if !valid(full)
        || !valid(local)
        || !(0. ..1.).contains(&x)
        || !(0. ..1.).contains(&y)
        || ![0, 1, 2]
            .into_iter()
            .any(|mode| view(t, x, y, mode) == Some(roi))
        || lt.source_width != roi.width as u32
        || lt.source_height != roi.height as u32
    {
        return Err(-3);
    }
    if cutout_select(full.masks, t, x, y) != -1 {
        return Err(-5);
    }
    let (tx, ty) = (
        (x * t.source_width as f32 - roi.x as f32) / roi.width as f32,
        (y * t.source_height as f32 - roi.top as f32) / roi.height as f32,
    );
    if !(0. ..1.).contains(&tx) || !(0. ..1.).contains(&ty) {
        return Err(-3);
    }
    let selected = unique(local.masks, lt, tx, ty)?;
    if local.detections[selected].confidence < STRONG_CONF {
        return Err(-5);
    }
    let crop = Rect {
        left: roi.x as f32,
        top: roi.top as f32,
        right: (roi.x + roi.width) as f32,
        bottom: (roi.top + roi.height) as f32,
    };
    if !detail_complete(
        local.detections[selected].bounds,
        crop,
        t.source_width,
        t.source_height,
    ) {
        return Err(-4);
    }
    if full.detections.len() + local.detections.len() > 256 {
        return Err(-5);
    }
    let area = (t.proto_size as usize).pow(2);
    let mut rows = full.detections.to_vec();
    let mut masks = full.masks.to_vec();
    let mut features = full.features.to_vec();
    let mut mapped = None;
    let mut output = vec![0; area];
    let local_area = (lt.proto_size as usize).pow(2);
    for (i, (d, raw)) in local
        .detections
        .iter()
        .zip(local.masks.chunks_exact(local_area))
        .enumerate()
    {
        if let Some(row) = remap(*d, raw, lt, t, roi, &rows, &masks, &mut output) {
            if i == selected {
                mapped = Some(rows.len());
            }
            rows.push(row);
            masks.extend_from_slice(&output);
            features
                .extend_from_slice(&local.features[i * APPEARANCE_BINS..(i + 1) * APPEARANCE_BINS]);
        }
    }
    let chosen = mapped.ok_or(-5)?;
    let scene = unique(&masks, t, x, y).map_err(|e| if e == -2 { -2 } else { -5 })?;
    if scene != chosen
        || !present(
            &feature(&features[chosen * APPEARANCE_BINS..(chosen + 1) * APPEARANCE_BINS])
                .ok_or(-3)?,
        )
    {
        return Err(-5);
    }
    Ok(TouchSeed {
        selected: chosen,
        rows,
        masks,
        features,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn transform(w: u32, h: u32) -> Transform {
        Transform {
            scale: 160. / w.max(h) as f32,
            pad_left: if w < h {
                (160. - 160. * w as f32 / h as f32) / 2.
            } else {
                0.
            },
            pad_top: if h < w {
                (160. - 160. * h as f32 / w as f32) / 2.
            } else {
                0.
            },
            source_width: w,
            source_height: h,
            input_size: 160,
            proto_size: 16,
        }
    }
    #[test]
    fn view_clamps_edges_and_validates_touch() {
        let t = transform(160, 90);
        assert_eq!(
            view(t, 0., 0., 0),
            Some(Viewport {
                x: 0,
                top: 0,
                width: 51,
                height: 51
            })
        );
        assert_eq!(
            view(t, 0.999, 0.999, 2),
            Some(Viewport {
                x: 80,
                top: 10,
                width: 80,
                height: 80
            })
        );
        for mode in [3, 4, u32::MAX] {
            assert!(view(t, 0.5, 0.5, mode).is_none());
        }
        assert!(view(t, 1., 0.5, 0).is_none());
        assert!(view(t, f32::NAN, 0.5, 0).is_none());
    }
    #[test]
    fn merge_requires_background_unique_complete_and_appearance() {
        let t = transform(160, 160);
        let v = view(t, 0.5, 0.5, 0).unwrap();
        let lt = transform(v.width as u32, v.height as u32);
        let mut f = [0.; APPEARANCE_BINS];
        f[1] = 1.;
        let mut mask = [0u8; 256];
        for y in 4..12 {
            for x in 5..11 {
                mask[y * 16 + x] = 255;
            }
        }
        let d = InstanceDetection {
            bounds: Rect {
                left: 15.,
                top: 12.,
                right: 35.,
                bottom: 39.,
            },
            confidence: 0.9,
        };
        let rows = [d];
        let empty = CutoutObservation {
            detections: &[],
            masks: &[],
            features: &[],
            transform: t,
        };
        let local = CutoutObservation {
            detections: &rows,
            masks: &mask,
            features: &f,
            transform: lt,
        };
        let result = merge(empty, local, v, 0.5, 0.5).unwrap();
        assert_eq!(result.selected, 0);
        assert_eq!(result.rows.len(), 1);
        assert_eq!(unique(&result.masks, t, 0.5, 0.5), Ok(0));
        let bg = [0u8; 256];
        let full = CutoutObservation {
            detections: &rows,
            masks: &bg,
            features: &f,
            transform: t,
        };
        let result = merge(full, local, v, 0.5, 0.5).unwrap();
        assert_eq!(result.selected, 1);
        assert_eq!(&result.masks[..256], &bg);
        assert_eq!(result.rows[0], d);
        assert_eq!(&result.features[..APPEARANCE_BINS], &f);
        let hit = [255u8; 256];
        let primary = CutoutObservation {
            masks: &hit,
            ..full
        };
        assert_eq!(merge(primary, local, v, 0.5, 0.5).err(), Some(-5));
        let pair = [d, d];
        let both = [mask, mask].concat();
        let ff = [f, f].concat();
        let ambiguous = CutoutObservation {
            detections: &pair,
            masks: &both,
            features: &ff,
            transform: lt,
        };
        assert_eq!(merge(empty, ambiguous, v, 0.5, 0.5).err(), Some(-2));
        let clipped = [InstanceDetection {
            bounds: Rect {
                left: 0.,
                ..d.bounds
            },
            ..d
        }];
        assert_eq!(
            merge(
                empty,
                CutoutObservation {
                    detections: &clipped,
                    ..local
                },
                v,
                0.5,
                0.5
            )
            .err(),
            Some(-4)
        );
        let absent = [0.; APPEARANCE_BINS];
        assert_eq!(
            merge(
                empty,
                CutoutObservation {
                    features: &absent,
                    ..local
                },
                v,
                0.5,
                0.5
            )
            .err(),
            Some(-5)
        );
        assert_eq!(
            merge(
                empty,
                CutoutObservation {
                    masks: &bg,
                    ..local
                },
                v,
                0.5,
                0.5
            )
            .err(),
            Some(-1)
        );
        assert_eq!(
            merge(empty, local, Viewport { x: 0, ..v }, 0.5, 0.5).err(),
            Some(-3)
        );
    }
}
