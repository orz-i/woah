//! Integer panes and screen-space picture transforms; no GPU ownership.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Viewport {
    pub x: i32,
    pub top: i32,
    pub width: i32,
    pub height: i32,
}
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct Quad {
    pub scale_x: f32,
    pub scale_y: f32,
    pub shift_x: f32,
    pub shift_y: f32,
    pub valid: u32,
}

impl Viewport {
    fn valid(self) -> bool {
        self.width > 0
            && self.height > 0
            && self.width <= 65535
            && self.height <= 65535
            && self.x >= 0
            && self.top >= 0
            && self.x <= 65535
            && self.top <= 65535
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_pane(
    width: i32,
    height: i32,
    gap: i32,
    reference: u32,
    side_by_side: u32,
) -> Viewport {
    if !(2..=65535).contains(&width)
        || !(2..=65535).contains(&height)
        || gap < 0
        || gap
            >= if side_by_side != 0 {
                width - 1
            } else {
                height - 1
            }
    {
        return Viewport::default();
    }
    if side_by_side != 0 {
        let first = (width - gap) / 2;
        let left = if reference != 0 { 0 } else { first + gap };
        Viewport {
            x: left,
            top: 0,
            width: if reference != 0 { first } else { width - left },
            height,
        }
    } else {
        let first = (height - gap) / 2;
        let top = if reference != 0 { 0 } else { first + gap };
        Viewport {
            x: 0,
            top,
            width,
            height: if reference != 0 { first } else { height - top },
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_fit(pane: Viewport, aspect: f64) -> Viewport {
    if !pane.valid() || !aspect.is_finite() || aspect <= 0.0 {
        return Viewport::default();
    }
    let width = (f64::from(pane.width).min(f64::from(pane.height) * aspect) as i32).max(1);
    let height = (f64::from(pane.height).min(f64::from(pane.width) / aspect) as i32).max(1);
    Viewport {
        x: pane.x + (pane.width - width) / 2,
        top: pane.top + (pane.height - height) / 2,
        width,
        height,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_quad(
    pane: Viewport,
    fit: Viewport,
    zoom: f64,
    pan_x: f64,
    pan_y: f64,
) -> Quad {
    if !pane.valid()
        || !fit.valid()
        || !zoom.is_finite()
        || !(1.0..=4.0).contains(&zoom)
        || !(-1.0..=1.0).contains(&pan_x)
        || !(-1.0..=1.0).contains(&pan_y)
    {
        return Quad::default();
    }
    let sx = f64::from(fit.width) / f64::from(pane.width) * zoom;
    let sy = f64::from(fit.height) / f64::from(pane.height) * zoom;
    let cx = (2.0 * (f64::from(fit.x - pane.x) + f64::from(fit.width) / 2.0)
        / f64::from(pane.width)
        - 1.0)
        * zoom;
    let cy = (1.0
        - 2.0 * (f64::from(fit.top - pane.top) + f64::from(fit.height) / 2.0)
            / f64::from(pane.height))
        * zoom;
    let tx = (sx - 1.0).abs();
    let ty = (sy - 1.0).abs();
    Quad {
        scale_x: sx as f32,
        scale_y: sy as f32,
        shift_x: if zoom == 1.0 {
            cx
        } else {
            (cx + pan_x * tx).clamp(-tx, tx)
        } as f32,
        shift_y: if zoom == 1.0 {
            cy
        } else {
            (cy - pan_y * ty).clamp(-ty, ty)
        } as f32,
        valid: 1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn odd_panes_keep_all_pixels_and_clip_pan() {
        let a = frame_kernel_pane(1381, 715, 8, 1, 1);
        let b = frame_kernel_pane(1381, 715, 8, 0, 1);
        assert_eq!(a.width, 686);
        assert_eq!(b.width, 687);
        assert_eq!(b.x - a.x - a.width, 8);
        assert_eq!(b.x + b.width, 1381);
        let fit = frame_kernel_fit(a, 16.0 / 9.0);
        let identity = frame_kernel_quad(a, fit, 1.0, 1.0, -1.0);
        let zero = frame_kernel_quad(a, fit, 1.0, 0.0, 0.0);
        assert_eq!(identity.shift_x, zero.shift_x);
        assert_eq!(identity.shift_y, zero.shift_y);
        assert_eq!(frame_kernel_quad(a, fit, 2.0, f64::NAN, 0.0).valid, 0);
        assert_eq!(frame_kernel_pane(2, 2, 1, 1, 0).width, 0);
    }
}

// Cutout Canvas uses display-oriented, unmirrored source coordinates. The
// selection anchor and placement are immutable across frames; never normalize
// each moving detection box, which would erase dance motion.
use crate::geometry::{Rect, Transform};

fn mask_sample(mask: &[u8], t: Transform, x: f32, y: f32) -> f32 {
    if !(0.0..1.0).contains(&x) || !(0.0..1.0).contains(&y) {
        return 0.0;
    }
    let n = t.proto_size as usize;
    let u =
        (x * t.source_width as f32 * t.scale + t.pad_left) * n as f32 / t.input_size as f32 - 0.5;
    let v =
        (y * t.source_height as f32 * t.scale + t.pad_top) * n as f32 / t.input_size as f32 - 0.5;
    if u < -0.5 || v < -0.5 || u > n as f32 - 0.5 || v > n as f32 - 0.5 {
        return 0.0;
    }
    let u = u.clamp(0.0, (n - 1) as f32);
    let v = v.clamp(0.0, (n - 1) as f32);
    let (i, j) = (u.floor() as usize, v.floor() as usize);
    let (dx, dy) = (u - i as f32, v - j as f32);
    let a = mask[j * n + i] as f32;
    let b = mask[j * n + (i + 1).min(n - 1)] as f32;
    let c = mask[(j + 1).min(n - 1) * n + i] as f32;
    let d = mask[(j + 1).min(n - 1) * n + (i + 1).min(n - 1)] as f32;
    (a + (b - a) * dx) * (1.0 - dy) + (c + (d - c) * dx) * dy
}

fn mask_area(t: Transform) -> Option<usize> {
    t.valid().then(|| (t.proto_size as usize).pow(2))
}

/// -1: background, -2: competing masks, -3: invalid input. Never select
/// by bounding box alone, or by detector ordering when two masks overlap.
pub fn cutout_select(masks: &[u8], t: Transform, x: f32, y: f32) -> i32 {
    let Some(area) = mask_area(t) else { return -3 };
    if !masks.len().is_multiple_of(area)
        || masks.len() / area > 256
        || !x.is_finite()
        || !y.is_finite()
    {
        return -3;
    }
    let (mut best, mut runner, mut index) = (0.0_f32, 0.0_f32, -1);
    for (i, mask) in masks.chunks_exact(area).enumerate() {
        let score = mask_sample(mask, t, x, y);
        if score > best {
            runner = best;
            best = score;
            index = i as i32;
        } else {
            runner = runner.max(score);
        }
    }
    if best < 128.0 {
        -1
    } else if runner >= 128.0 && best - runner < 24.0 {
        -2
    } else {
        index
    }
}

/// Convert model confidence to an initial soft alpha, not to a learned matte.
/// No dilation, privacy fallback region or retained mask is applied.
pub fn cutout_alpha(mask: &[u8], t: Transform, width: u32, height: u32, output: &mut [u8]) -> bool {
    if mask_area(t) != Some(mask.len())
        || width == 0
        || height == 0
        || width > 4096
        || height > 4096
        || output.len() != width as usize * height as usize
    {
        return false;
    }
    for y in 0..height {
        for x in 0..width {
            let score = mask_sample(
                mask,
                t,
                (x as f32 + 0.5) / width as f32,
                (y as f32 + 0.5) / height as f32,
            );
            let a = ((score - 96.0) / 96.0).clamp(0.0, 1.0);
            output[(y * width + x) as usize] = (a * a * (3.0 - 2.0 * a) * 255.0).round() as u8;
        }
    }
    true
}

/// Place the full source image based on a fixed, normalized selection bbox.
/// The anchor bottom center maps to (x,y), and its height to canvasHeight*height.
/// Negative or off-canvas coordinates are allowed only for the resulting quad.
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_cutout_quad(
    width: u32,
    height: u32,
    aspect: f64,
    anchor: Rect,
    x: f64,
    y: f64,
    subject_height: f64,
    mirror: u32,
) -> Quad {
    if !(2..=65535).contains(&width)
        || !(2..=65535).contains(&height)
        || !anchor.valid()
        || anchor.left < 0.0
        || anchor.top < 0.0
        || anchor.right > 1.0
        || anchor.bottom > 1.0
        || !aspect.is_finite()
        || !(0.01..=100.0).contains(&aspect)
        || !x.is_finite()
        || !y.is_finite()
        || !(0.0..=1.0).contains(&x)
        || !(0.0..=1.0).contains(&y)
        || !(0.05..=2.0).contains(&subject_height)
        || anchor.bottom - anchor.top < 0.01
        || mirror > 1
    {
        return Quad::default();
    }
    let sy = subject_height / f64::from(anchor.bottom - anchor.top);
    let sx = sy * f64::from(height) * aspect / f64::from(width);
    let ax = (f64::from(anchor.left) + f64::from(anchor.right)) / 2.0;
    let ax = if mirror == 1 { 1.0 - ax } else { ax };
    Quad {
        scale_x: sx as f32,
        scale_y: sy as f32,
        shift_x: (2.0 * x - 1.0 - (2.0 * ax - 1.0) * sx) as f32,
        shift_y: (1.0 - 2.0 * y - (1.0 - 2.0 * f64::from(anchor.bottom)) * sy) as f32,
        valid: 1,
    }
}

/// # Safety
/// Non-null buffers must contain the specified number of readable bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_cutout_select(
    masks: *const u8,
    len: usize,
    t: Transform,
    x: f32,
    y: f32,
) -> i32 {
    let Some(area) = mask_area(t) else { return -3 };
    if len > area * 256 || (len != 0 && masks.is_null()) {
        return -3;
    }
    let bytes = if len == 0 {
        &[]
    } else {
        // SAFETY: caller supplies len readable bytes; size and null checked above.
        unsafe { std::slice::from_raw_parts(masks, len) }
    };
    cutout_select(bytes, t, x, y)
}

/// # Safety
/// Input/output must be valid, non-overlapping buffers of the stated sizes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_cutout_alpha(
    mask: *const u8,
    len: usize,
    t: Transform,
    width: u32,
    height: u32,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    if mask_area(t) != Some(len)
        || mask.is_null()
        || out.is_null()
        || width == 0
        || height == 0
        || width > 4096
        || height > 4096
        || out_len != width as usize * height as usize
    {
        return -1;
    }
    // SAFETY: caller guarantees valid, non-overlapping buffers; lengths checked above.
    let input = unsafe { std::slice::from_raw_parts(mask, len) };
    // SAFETY: output is writable for out_len bytes and does not alias input.
    let output = unsafe { std::slice::from_raw_parts_mut(out, out_len) };
    if cutout_alpha(input, t, width, height, output) {
        0
    } else {
        -1
    }
}

#[cfg(test)]
mod cutout_tests {
    use super::*;
    fn transform() -> Transform {
        Transform {
            scale: 1.0,
            pad_left: 0.0,
            pad_top: 0.0,
            source_width: 4,
            source_height: 4,
            input_size: 4,
            proto_size: 4,
        }
    }
    #[test]
    fn hit_uses_masks_and_rejects_overlap_and_background() {
        let t = transform();
        let a = [
            255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0,
        ];
        let b = [
            0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255,
        ];
        let masks = [a, b].concat();
        assert_eq!(cutout_select(&masks, t, 0.125, 0.125), 0);
        assert_eq!(cutout_select(&masks, t, 0.875, 0.125), 1);
        assert_eq!(cutout_select(&[a, a].concat(), t, 0.125, 0.125), -2);
        assert_eq!(cutout_select(&masks, t, 1.0, 0.5), -1);
        assert_eq!(cutout_select(&[0; 16], t, 0.5, 0.5), -1);
        assert_eq!(cutout_select(&[1], t, 0.5, 0.5), -3);
    }
    #[test]
    fn alpha_projects_letterbox_and_soft_edges() {
        let t = Transform {
            source_width: 4,
            source_height: 2,
            pad_top: 1.0,
            ..transform()
        };
        let m = [0, 0, 0, 0, 0, 128, 255, 255, 0, 128, 255, 255, 0, 0, 0, 0];
        let mut a = [9; 8];
        assert!(cutout_alpha(&m, t, 4, 2, &mut a));
        assert_eq!(a, [0, 66, 255, 255, 0, 66, 255, 255]);
        assert!(!cutout_alpha(&m, t, 4, 3, &mut a));
    }
    #[test]
    fn fixed_anchor_and_mirror_preserve_translation_and_aspect() {
        let anchor = Rect {
            left: 0.1,
            top: 0.2,
            right: 0.5,
            bottom: 0.8,
        };
        for mirror in [0, 1] {
            let q = frame_kernel_cutout_quad(1000, 500, 1.5, anchor, 0.25, 0.85, 0.6, mirror);
            let ax = if mirror == 1 { 0.7 } else { 0.3 };
            assert_eq!(q.valid, 1);
            assert!((q.scale_x - 0.75).abs() < 1e-6);
            assert!(((2.0 * ax - 1.0) * q.scale_x + q.shift_x + 0.5).abs() < 1e-6);
            assert!(((1.0 - 1.6) * q.scale_y + q.shift_y + 0.7).abs() < 1e-6);
            assert!((q.scale_y * 0.1 - 0.1).abs() < 1e-6);
        }
    }
}

/// Render an unmirrored, transparent still from the *same* letterboxed input
/// that produced the selected instance. Pixels are RGBA straight-alpha;
/// output is top-down in model coordinates. The returned crop excludes
/// transparent padding/background. No predicted or unselected mask is used.
pub fn cutout_thumbnail(
    mask: &[u8],
    t: Transform,
    rgba: &[u8],
    bottom_up: bool,
    output: &mut [u8],
) -> Viewport {
    let size = t.input_size as usize;
    if mask_area(t) != Some(mask.len())
        || !(1..=1024).contains(&size)
        || rgba.len() != size * size * 4
        || output.len() != rgba.len()
    {
        return Viewport::default();
    }
    output.fill(0);
    let (mut left, mut top, mut right, mut bottom) = (size, size, 0, 0);
    for y in 0..size {
        for x in 0..size {
            let sx = (x as f32 + 0.5 - t.pad_left) / (t.scale * t.source_width as f32);
            let sy = (y as f32 + 0.5 - t.pad_top) / (t.scale * t.source_height as f32);
            let score = mask_sample(mask, t, sx, sy);
            let a = ((score - 96.0) / 96.0).clamp(0.0, 1.0);
            let alpha = (a * a * (3.0 - 2.0 * a) * 255.0).round() as u8;
            if alpha == 0 {
                continue;
            }
            let row = if bottom_up { size - y - 1 } else { y };
            let from = (row * size + x) * 4;
            let to = (y * size + x) * 4;
            output[to..to + 3].copy_from_slice(&rgba[from..from + 3]);
            output[to + 3] = alpha;
            left = left.min(x);
            top = top.min(y);
            right = right.max(x + 1);
            bottom = bottom.max(y + 1);
        }
    }
    if right <= left || bottom <= top {
        return Viewport::default();
    }
    Viewport {
        x: left as i32,
        top: top as i32,
        width: (right - left) as i32,
        height: (bottom - top) as i32,
    }
}

/// # Safety
/// Each buffer must be valid for its declared length; output must not overlap inputs.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_cutout_thumbnail(
    mask: *const u8,
    mask_len: usize,
    t: Transform,
    rgba: *const u8,
    rgba_len: usize,
    bottom_up: u32,
    output: *mut u8,
    output_len: usize,
) -> Viewport {
    let n = t.input_size as usize;
    if !(1..=1024).contains(&n)
        || mask_area(t) != Some(mask_len)
        || rgba_len != n * n * 4
        || output_len != rgba_len
        || bottom_up > 1
        || mask.is_null()
        || rgba.is_null()
        || output.is_null()
    {
        return Viewport::default();
    }
    // SAFETY: caller owns valid nonoverlapping buffers of the checked lengths.
    let mask = unsafe { std::slice::from_raw_parts(mask, mask_len) };
    // SAFETY: input is readable for the checked length.
    let rgba = unsafe { std::slice::from_raw_parts(rgba, rgba_len) };
    // SAFETY: output is writable and does not alias either input.
    let output = unsafe { std::slice::from_raw_parts_mut(output, output_len) };
    cutout_thumbnail(mask, t, rgba, bottom_up != 0, output)
}

#[cfg(test)]
mod thumbnail_tests {
    use super::*;
    #[test]
    fn thumbnail_keeps_selected_rgb_and_rejects_padding_and_background() {
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 1.,
            source_width: 4,
            source_height: 2,
            input_size: 4,
            proto_size: 4,
        };
        let m = [
            255, 255, 255, 255, 255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255,
        ];
        let mut rgba = [0u8; 64];
        for i in 0..16 {
            rgba[4 * i] = i as u8;
            rgba[4 * i + 3] = 255;
        }
        let mut out = [99; 64];
        assert_eq!(
            cutout_thumbnail(&m, t, &rgba, false, &mut out),
            Viewport {
                x: 0,
                top: 1,
                width: 1,
                height: 2
            }
        );
        assert_eq!(&out[16..20], &[4, 0, 0, 255]);
        assert_eq!(&out[32..36], &[8, 0, 0, 255]);
        assert!(out[..16].iter().all(|&x| x == 0));
        assert_eq!(&out[20..24], &[0; 4]);
        assert_eq!(
            cutout_thumbnail(&m, t, &rgba, true, &mut out),
            Viewport {
                x: 0,
                top: 1,
                width: 1,
                height: 2
            }
        );
        assert_eq!(&out[16..20], &[8, 0, 0, 255]);
        assert_eq!(&out[32..36], &[4, 0, 0, 255]);
        assert_eq!(
            cutout_thumbnail(&[0; 16], t, &rgba, false, &mut out),
            Viewport::default()
        );
    }
}
