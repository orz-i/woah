//! Planar or interleaved 4:2:0 sampling with explicit matrix/range/crop metadata.
use crate::pixels::letterbox;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct YuvSpec {
    pub width: u32,
    pub height: u32,
    pub crop_left: u32,
    pub crop_top: u32,
    pub crop_right: u32,
    pub crop_bottom: u32,
    pub display_width: u32,
    pub display_height: u32,
    pub rotation: u32,
    pub output_width: u32,
    pub output_height: u32,
    pub flip_rows: u32,
    pub matrix: u32,
    pub full_range: u32,
    pub fill: u32,
}

pub struct Plane<'a> {
    pub bytes: &'a [u8],
    pub row_stride: u32,
    pub pixel_stride: u32,
}
#[derive(Clone, Copy)]
struct Sample {
    a: usize,
    b: usize,
    weight: u32,
}

fn axis(value: f64, dimension: u32) -> Sample {
    let c = value.clamp(0.0, (dimension - 1) as f64);
    let a = c.floor() as usize;
    Sample {
        a,
        b: (a + 1).min(dimension as usize - 1),
        weight: ((c - a as f64) * 256.0).round() as u32,
    }
}
fn valid_plane(p: &Plane<'_>, width: u32, height: u32) -> bool {
    let row = (width as usize - 1) * p.pixel_stride as usize + 1;
    p.pixel_stride > 0
        && p.row_stride as usize >= row
        && p.bytes.len() >= (height as usize - 1) * p.row_stride as usize + row
}
fn sample(p: &Plane<'_>, x: Sample, y: Sample) -> i32 {
    let at = |a: usize, b: usize| {
        p.bytes[b * p.row_stride as usize + a * p.pixel_stride as usize] as u32
    };
    let top = at(x.a, y.a) * (256 - x.weight) + at(x.b, y.a) * x.weight;
    let bottom = at(x.a, y.b) * (256 - x.weight) + at(x.b, y.b) * x.weight;
    ((top * (256 - y.weight) + bottom * y.weight + 32768) >> 16) as i32
}
pub fn rgb(y: i32, u: i32, v: i32, matrix: u32, full: bool) -> [u8; 4] {
    let (rv, gu, gv, bu) = match (matrix, full) {
        (0, false) => (409, -100, -208, 516),
        (0, true) => (359, -88, -183, 454),
        (2, false) => (430, -48, -167, 548),
        (2, true) => (377, -42, -146, 482),
        (_, false) => (459, -55, -136, 541),
        (_, true) => (403, -48, -120, 475),
    };
    let yt = if full { y * 256 } else { 298 * (y - 16).max(0) };
    let u = u - 128;
    let v = v - 128;
    [
        ((yt + rv * v + 128) >> 8).clamp(0, 255) as u8,
        ((yt + gu * u + gv * v + if full { 127 } else { 128 }) >> 8).clamp(0, 255) as u8,
        ((yt + bu * u + 128) >> 8).clamp(0, 255) as u8,
        255,
    ]
}

pub fn convert(planes: [&Plane<'_>; 3], s: YuvSpec, output: &mut [u8]) -> Option<()> {
    if s.width == 0
        || s.height == 0
        || s.width > 65535
        || s.height > 65535
        || s.crop_left >= s.crop_right
        || s.crop_top >= s.crop_bottom
        || s.crop_right > s.width
        || s.crop_bottom > s.height
        || ![0, 90, 180, 270].contains(&s.rotation)
        || s.flip_rows > 1
        || s.matrix > 2
        || s.full_range > 1
        || s.fill > 255
    {
        return None;
    }
    let p = letterbox(
        s.display_width,
        s.display_height,
        s.output_width,
        s.output_height,
    )?;
    let count = s.output_width as usize * s.output_height as usize;
    if count > 16_777_216
        || output.len() < count * 4
        || !valid_plane(planes[0], s.width, s.height)
        || planes[1..]
            .iter()
            .any(|a| !valid_plane(a, s.width.div_ceil(2), s.height.div_ceil(2)))
    {
        return None;
    }
    let swap = s.rotation == 90 || s.rotation == 270;
    let cw = s.crop_right - s.crop_left;
    let ch = s.crop_bottom - s.crop_top;
    let dw = if swap { ch } else { cw };
    let dh = if swap { cw } else { ch };
    let dx: Vec<f64> = (0..s.output_width)
        .map(|i| {
            (((i as f64 + 0.5 - p.left as f64) / p.width as f64).clamp(0.0, 1.0) * dw as f64) - 0.5
        })
        .collect();
    let dy: Vec<f64> = (0..s.output_height)
        .map(|i| {
            (((i as f64 + 0.5 - p.top as f64) / p.height as f64).clamp(0.0, 1.0) * dh as f64) - 0.5
        })
        .collect();
    let sx: Vec<f64> = (if swap { &dy } else { &dx })
        .iter()
        .map(|&v| {
            (if s.rotation == 180 || s.rotation == 270 {
                s.crop_right as f64 - 1.0 - v
            } else {
                v + s.crop_left as f64
            })
            .clamp(s.crop_left as f64, (s.crop_right - 1) as f64)
        })
        .collect();
    let sy: Vec<f64> = (if swap { &dx } else { &dy })
        .iter()
        .map(|&v| {
            (if s.rotation == 90 || s.rotation == 180 {
                s.crop_bottom as f64 - 1.0 - v
            } else {
                v + s.crop_top as f64
            })
            .clamp(s.crop_top as f64, (s.crop_bottom - 1) as f64)
        })
        .collect();
    let lx: Vec<_> = sx.iter().map(|&v| axis(v, s.width)).collect();
    let ly: Vec<_> = sy.iter().map(|&v| axis(v, s.height)).collect();
    let ux: Vec<_> = sx
        .iter()
        .map(|&v| axis(v * 0.5, s.width.div_ceil(2)))
        .collect();
    let uy: Vec<_> = sy
        .iter()
        .map(|&v| axis(v * 0.5, s.height.div_ceil(2)))
        .collect();
    for y in 0..s.output_height as usize {
        for x in 0..s.output_width as usize {
            let row = if s.flip_rows == 1 {
                s.output_height as usize - 1 - y
            } else {
                y
            };
            let out = (row * s.output_width as usize + x) * 4;
            if (x as f64 + 0.5) < p.left as f64
                || (x as f64 + 0.5) >= (p.left + p.width) as f64
                || (y as f64 + 0.5) < p.top as f64
                || (y as f64 + 0.5) >= (p.top + p.height) as f64
            {
                output[out..out + 4].copy_from_slice(&[
                    s.fill as u8,
                    s.fill as u8,
                    s.fill as u8,
                    255,
                ]);
                continue;
            }
            let (a, b) = if swap { (y, x) } else { (x, y) };
            let y8 = sample(planes[0], lx[a], ly[b]);
            let u8 = sample(planes[1], ux[a], uy[b]);
            let v8 = sample(planes[2], ux[a], uy[b]);
            output[out..out + 4].copy_from_slice(&rgb(y8, u8, v8, s.matrix, s.full_range == 1));
        }
    }
    Some(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn full_and_limited_neutral_endpoints_are_stable() {
        for matrix in 0..3 {
            assert_eq!(rgb(16, 128, 128, matrix, false), [0, 0, 0, 255]);
            assert_eq!(rgb(235, 128, 128, matrix, false), [255, 255, 255, 255]);
            assert_eq!(rgb(73, 128, 128, matrix, true), [73, 73, 73, 255]);
        }
    }
}
