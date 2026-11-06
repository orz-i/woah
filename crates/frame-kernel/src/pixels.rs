//! Portable tensor transport, layout and pixel-centre letterbox sampling.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct Letterbox {
    pub scale: f32,
    pub width: f32,
    pub height: f32,
    pub left: f32,
    pub top: f32,
}

pub fn letterbox(
    width: u32,
    height: u32,
    output_width: u32,
    output_height: u32,
) -> Option<Letterbox> {
    if [width, height, output_width, output_height].contains(&0)
        || [width, height, output_width, output_height]
            .iter()
            .any(|&x| x > 65535)
    {
        return None;
    }
    let scale = (output_width as f32 / width as f32).min(output_height as f32 / height as f32);
    let w = width as f32 * scale;
    let h = height as f32 * scale;
    Some(Letterbox {
        scale,
        width: w,
        height: h,
        left: (output_width as f32 - w) / 2.0,
        top: (output_height as f32 - h) / 2.0,
    })
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct PixelSpec {
    pub width: u32,
    pub height: u32,
    pub row_stride: u32,
    pub channels: u32,
    pub red: u32,
    pub green: u32,
    pub blue: u32,
    pub flip_rows: u32,
    pub flip_columns: u32,
    pub interleaved: u32,
    pub scale: f32,
    pub bias: f32,
}

pub fn normalize(input: &[u8], spec: PixelSpec, output: &mut [f32]) -> Option<()> {
    let s = spec;
    let pixels = (s.width as usize).checked_mul(s.height as usize)?;
    let row_bytes = (s.width as usize).checked_mul(s.channels as usize)?;
    let needed = (s.height as usize)
        .checked_sub(1)?
        .checked_mul(s.row_stride as usize)?
        .checked_add(row_bytes)?;
    if pixels == 0
        || pixels > 16_777_216
        || !(3..=4).contains(&s.channels)
        || row_bytes > s.row_stride as usize
        || input.len() < needed
        || output.len() < pixels * 3
        || [s.red, s.green, s.blue].iter().any(|&x| x >= s.channels)
        || s.flip_rows > 1
        || s.flip_columns > 1
        || s.interleaved > 1
        || !s.scale.is_finite()
        || !s.bias.is_finite()
    {
        return None;
    }
    for y in 0..s.height as usize {
        let sy = if s.flip_rows == 1 {
            s.height as usize - 1 - y
        } else {
            y
        };
        for x in 0..s.width as usize {
            let sx = if s.flip_columns == 1 {
                s.width as usize - 1 - x
            } else {
                x
            };
            let base = sy * s.row_stride as usize + sx * s.channels as usize;
            let pixel = y * s.width as usize + x;
            for (channel, offset) in [s.red, s.green, s.blue].iter().enumerate() {
                let index = if s.interleaved == 1 {
                    pixel * 3 + channel
                } else {
                    channel * pixels + pixel
                };
                output[index] = input[base + *offset as usize] as f32 * s.scale + s.bias;
            }
        }
    }
    Some(())
}

/// RGBA byte-domain bilinear sampling. Source/output rows are top-to-bottom;
/// padding is determined at pixel centres using continuous Float32 geometry.
#[allow(clippy::too_many_arguments)] // Mirrors the explicit caller-owned C buffers.
pub fn resize_rgba(
    input: &[u8],
    width: u32,
    height: u32,
    stride: u32,
    output: &mut [u8],
    ow: u32,
    oh: u32,
    fill: u8,
) -> Option<Letterbox> {
    let plan = letterbox(width, height, ow, oh)?;
    let count = (ow as usize).checked_mul(oh as usize)?.checked_mul(4)?;
    let needed = (height as usize - 1)
        .checked_mul(stride as usize)?
        .checked_add(width as usize * 4)?;
    if count > 67_108_864 || stride < width * 4 || input.len() < needed || output.len() < count {
        return None;
    }
    fn axis(v: f64, n: u32) -> (usize, usize, u32) {
        let v = v.clamp(0.0, (n - 1) as f64);
        let a = v.floor() as usize;
        (
            a,
            (a + 1).min(n as usize - 1),
            ((v - a as f64) * 256.0).round() as u32,
        )
    }
    for y in 0..oh as usize {
        let cy = y as f64 + 0.5;
        for x in 0..ow as usize {
            let cx = x as f64 + 0.5;
            let out = (y * ow as usize + x) * 4;
            if cx < plan.left as f64
                || cx >= (plan.left + plan.width) as f64
                || cy < plan.top as f64
                || cy >= (plan.top + plan.height) as f64
            {
                output[out..out + 4].copy_from_slice(&[fill, fill, fill, 255]);
                continue;
            }
            let (x0, x1, wx) = axis(
                (cx - plan.left as f64) / plan.width as f64 * width as f64 - 0.5,
                width,
            );
            let (y0, y1, wy) = axis(
                (cy - plan.top as f64) / plan.height as f64 * height as f64 - 0.5,
                height,
            );
            for c in 0..3 {
                let a = input[y0 * stride as usize + x0 * 4 + c] as u32 * (256 - wx)
                    + input[y0 * stride as usize + x1 * 4 + c] as u32 * wx;
                let b = input[y1 * stride as usize + x0 * 4 + c] as u32 * (256 - wx)
                    + input[y1 * stride as usize + x1 * 4 + c] as u32 * wx;
                output[out + c] = ((a * (256 - wy) + b * wy + 32768) >> 16) as u8;
            }
            output[out + 3] = 255;
        }
    }
    Some(plan)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fractional_letterbox_and_half_pixel_sampling_are_explicit() {
        let p = letterbox(2160, 4096, 640, 640).unwrap();
        assert_eq!((p.width, p.left), (337.5, 151.25));
        let input = [255, 0, 0, 255, 0, 0, 255, 255];
        let mut out = [0; 16];
        resize_rgba(&input, 2, 1, 8, &mut out, 2, 2, 114).unwrap();
        assert_eq!(&out[..8], &input);
        assert_eq!(&out[8..], &[114, 114, 114, 255, 114, 114, 114, 255]);
        let before = out;
        assert!(resize_rgba(&input[..3], 2, 1, 8, &mut out, 2, 2, 114).is_none());
        assert_eq!(out, before);
    }
    #[test]
    fn normalization_rejects_short_buffers_before_writing() {
        let spec = PixelSpec {
            width: 1,
            height: 1,
            row_stride: 4,
            channels: 4,
            red: 0,
            green: 1,
            blue: 2,
            flip_rows: 0,
            flip_columns: 0,
            interleaved: 0,
            scale: 1.0 / 255.0,
            bias: 0.0,
        };
        let mut out = [42.0; 3];
        assert!(normalize(&[1, 2], spec, &mut out).is_none());
        assert_eq!(out, [42.0; 3]);
        normalize(&[255, 0, 128, 255], spec, &mut out).unwrap();
        assert_eq!(out, [1.0, 0.0, 128.0 * (1.0 / 255.0)]);
    }
}
