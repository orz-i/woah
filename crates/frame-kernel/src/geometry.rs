#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Rect {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl Rect {
    pub fn valid(self) -> bool {
        [self.left, self.top, self.right, self.bottom]
            .iter()
            .all(|x| x.is_finite())
            && self.right > self.left
            && self.bottom > self.top
    }
}

/// Ordinary bounding-box IoU, including subpixel areas. Detail-tile merging's
/// historical denominator clamp is intentionally a separate operation.
pub fn overlap_iou(a: Rect, b: Rect) -> f32 {
    let area = (a.right.min(b.right) - a.left.max(b.left)).max(0.0)
        * (a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.0);
    let union = (a.right - a.left).max(0.0) * (a.bottom - a.top).max(0.0)
        + (b.right - b.left).max(0.0) * (b.bottom - b.top).max(0.0)
        - area;
    if union > 0.0 { area / union } else { 0.0 }
}

/// Explicit preprocessing metadata. Adapters must describe the transform that
/// actually produced their tensor; never reconstruct a subtly different one.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Transform {
    pub scale: f32,
    pub pad_left: f32,
    pub pad_top: f32,
    pub source_width: u32,
    pub source_height: u32,
    pub input_size: u32,
    pub proto_size: u32,
}

impl Transform {
    pub fn valid(self) -> bool {
        self.scale.is_finite()
            && self.scale > 0.0
            && self.pad_left.is_finite()
            && self.pad_top.is_finite()
            && self.source_width > 0
            && self.source_height > 0
            && self.input_size > 0
            && (1..=1024).contains(&self.proto_size)
    }

    fn source_x(self, x: u32) -> f32 {
        ((x as f32 + 0.5) * self.input_size as f32 / self.proto_size as f32 - self.pad_left)
            / self.scale
    }

    fn source_y(self, y: u32) -> f32 {
        ((y as f32 + 0.5) * self.input_size as f32 / self.proto_size as f32 - self.pad_top)
            / self.scale
    }

    fn proto_x(self, x: f32) -> i32 {
        ((x * self.scale + self.pad_left) / self.input_size as f32 * self.proto_size as f32).floor()
            as i32
    }

    fn proto_y(self, y: f32) -> i32 {
        ((y * self.scale + self.pad_top) / self.input_size as f32 * self.proto_size as f32).floor()
            as i32
    }
}

pub fn detail_views(width: u32, height: u32) -> Option<[Rect; 4]> {
    if width < 2 || height < 2 || width > 65535 || height > 65535 {
        return None;
    }
    let w = width * 3 / 5;
    let h = height * 3 / 5;
    Some(
        [
            (0, 0),
            (width - w, 0),
            (0, height - h),
            (width - w, height - h),
        ]
        .map(|(x, y)| Rect {
            left: x as f32,
            top: y as f32,
            right: (x + w) as f32,
            bottom: (y + h) as f32,
        }),
    )
}

pub fn detail_complete(box_: Rect, view: Rect, width: u32, height: u32) -> bool {
    if !box_.valid() || !view.valid() || width == 0 || height == 0 {
        return false;
    }
    let w = view.right - view.left;
    let h = view.bottom - view.top;
    (view.left == 0.0 || box_.left > w * 0.02)
        && (view.top == 0.0 || box_.top > h * 0.02)
        && (view.right == width as f32 || box_.right < w - w * 0.02)
        && (view.bottom == height as f32 || box_.bottom < h - h * 0.02)
}

pub fn iou(a: Rect, b: Rect) -> f32 {
    if !a.valid() || !b.valid() {
        return 0.0;
    }
    let intersection = (a.right.min(b.right) - a.left.max(b.left)).max(0.0)
        * (a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.0);
    intersection
        / ((a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top)
            - intersection)
            .max(1.0)
}

pub fn reproject_mask(
    mask: &[u8],
    local: Transform,
    scene: Transform,
    view: Rect,
    output: &mut [u8],
) -> bool {
    if !local.valid()
        || !scene.valid()
        || !view.valid()
        || mask.len() != (local.proto_size * local.proto_size) as usize
        || output.len() != (scene.proto_size * scene.proto_size) as usize
    {
        return false;
    }
    output.fill(0);
    for y in 0..scene.proto_size {
        let sy = scene.source_y(y);
        let ly = sy - view.top;
        if sy < 0.0 || sy >= scene.source_height as f32 || ly < 0.0 || ly >= view.bottom - view.top
        {
            continue;
        }
        let py = local.proto_y(ly).clamp(0, local.proto_size as i32 - 1) as usize;
        for x in 0..scene.proto_size {
            let sx = scene.source_x(x);
            let lx = sx - view.left;
            if sx < 0.0
                || sx >= scene.source_width as f32
                || lx < 0.0
                || lx >= view.right - view.left
            {
                continue;
            }
            let px = local.proto_x(lx).clamp(0, local.proto_size as i32 - 1) as usize;
            output[(y * scene.proto_size + x) as usize] = mask[py * local.proto_size as usize + px];
        }
    }
    true
}

/// Nearest-neighbour output projection. Keep arithmetic order stable: encoded
/// privacy edges must not change when a platform changes its row-cache strategy.
#[allow(clippy::too_many_arguments)]
pub fn project_mask(
    mask: &[u8],
    transform: Transform,
    source_width: u32,
    source_height: u32,
    width: u32,
    height: u32,
    crop: Rect,
    output: &mut [u8],
) -> bool {
    if !transform.valid()
        || !crop.valid()
        || source_width == 0
        || source_height == 0
        || width == 0
        || height == 0
        || width > 16384
        || height > 16384
        || u64::from(width) * u64::from(height) > 64 * 1024 * 1024
        || mask.len() != (transform.proto_size * transform.proto_size) as usize
        || output.len() != width as usize * height as usize
    {
        return false;
    }
    let xs: Vec<_> = (0..width)
        .map(|x| {
            let sx = (x as f32 + 0.5) * source_width as f32 / width as f32
                * (crop.right - crop.left)
                + crop.left * source_width as f32;
            transform.proto_x(sx)
        })
        .collect();
    let size = transform.proto_size as usize;
    let mut rows: Vec<Option<Vec<u8>>> = (0..size).map(|_| None).collect();
    output.fill(0);
    for y in 0..height {
        let sy = (y as f32 + 0.5) * source_height as f32 / height as f32 * (crop.bottom - crop.top)
            + crop.top * source_height as f32;
        let py = transform.proto_y(sy);
        if py < 0 || py >= size as i32 {
            continue;
        }
        let row = rows[py as usize].get_or_insert_with(|| {
            xs.iter()
                .map(|&px| {
                    if px >= 0 && px < size as i32 {
                        mask[py as usize * size + px as usize]
                    } else {
                        0
                    }
                })
                .collect()
        });
        output[y as usize * width as usize..(y as usize + 1) * width as usize].copy_from_slice(row);
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn transform(w: u32, h: u32) -> Transform {
        let scale = (640.0 / w as f32).min(640.0 / h as f32);
        Transform {
            scale,
            pad_left: (640.0 - w as f32 * scale) / 2.0,
            pad_top: (640.0 - h as f32 * scale) / 2.0,
            source_width: w,
            source_height: h,
            input_size: 640,
            proto_size: 160,
        }
    }

    #[test]
    fn four_overlapping_views_keep_original_4k_geometry() {
        let views = detail_views(3840, 2160).unwrap();
        assert_eq!(
            views[3],
            Rect {
                left: 1536.0,
                top: 864.0,
                right: 3840.0,
                bottom: 2160.0
            }
        );
        let portrait = detail_views(2160, 4096).unwrap();
        assert_eq!(portrait[3].bottom, 4096.0);
        assert_eq!(portrait[3].top, 1639.0);
        assert!(detail_views(u32::MAX, 2160).is_none());
    }

    #[test]
    fn clipped_head_at_internal_tile_edge_is_rejected() {
        let view = detail_views(3840, 2160).unwrap()[3];
        let box_ = Rect {
            left: 100.0,
            top: 0.0,
            right: 500.0,
            bottom: 1000.0,
        };
        assert!(!detail_complete(box_, view, 3840, 2160));
        assert!(detail_complete(
            Rect { top: 100.0, ..box_ },
            view,
            3840,
            2160
        ));
        assert!(!detail_complete(
            Rect {
                top: f32::NAN,
                ..box_
            },
            view,
            3840,
            2160
        ));
    }

    #[test]
    fn bottom_tile_mask_does_not_leak_into_padding_or_other_people() {
        let scene = transform(3840, 2160);
        let view = detail_views(3840, 2160).unwrap()[2];
        let local = transform(2304, 1296);
        let mut result = vec![0; 160 * 160];
        assert!(reproject_mask(
            &vec![255; 160 * 160],
            local,
            scene,
            view,
            &mut result
        ));
        let at = |x, y| result[scene.proto_y(y) as usize * 160 + scene.proto_x(x) as usize];
        assert_eq!(at(900.0, 1400.0), 255);
        assert_eq!(at(3000.0, 1400.0), 0);
        assert_eq!(at(900.0, 300.0), 0);
        assert_eq!(result[0], 0);
        assert!(!reproject_mask(&[255], local, scene, view, &mut result));
    }
}
