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
