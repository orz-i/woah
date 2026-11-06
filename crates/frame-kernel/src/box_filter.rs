//! Eight-state XYAH constant-velocity filter with full covariance propagation.
#![allow(clippy::needless_range_loop)] // Matrix indices keep the reference accumulation order explicit.
use crate::geometry::Rect;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct BoxFilter {
    pub mean: [f32; 8],
    pub covariance: [[f32; 8]; 8],
    pub timestamp_us: i64,
}
impl Default for BoxFilter {
    fn default() -> Self {
        Self {
            mean: [0.0; 8],
            covariance: [[0.0; 8]; 8],
            timestamp_us: -1,
        }
    }
}
fn measurement(r: Rect) -> [f32; 4] {
    let w = (r.right - r.left).max(0.0);
    let raw_h = (r.bottom - r.top).max(0.0);
    let h = raw_h.max(1e-4);
    [r.left + w / 2.0, r.top + raw_h / 2.0, w / h, h]
}
pub fn invert4(matrix: [[f32; 4]; 4]) -> Option<[[f32; 4]; 4]> {
    let mut a = [[0.0; 8]; 4];
    for r in 0..4 {
        for c in 0..8 {
            a[r][c] = if c < 4 {
                matrix[r][c]
            } else if c - 4 == r {
                1.0
            } else {
                0.0
            };
        }
    }
    for i in 0..4 {
        let mut row = i;
        for k in i + 1..4 {
            if a[k][i].abs() > a[row][i].abs() {
                row = k;
            }
        }
        a.swap(i, row);
        let pivot = a[i][i];
        if pivot.abs() < 1e-9 {
            return None;
        }
        for j in 0..8 {
            a[i][j] /= pivot;
        }
        for k in 0..4 {
            if k != i {
                let factor = a[k][i];
                for j in 0..8 {
                    a[k][j] -= factor * a[i][j];
                }
            }
        }
    }
    Some(std::array::from_fn(|r| {
        std::array::from_fn(|c| a[r][c + 4])
    }))
}
impl BoxFilter {
    pub fn valid(&self) -> bool {
        self.mean
            .iter()
            .chain(self.covariance.iter().flatten())
            .all(|x| x.is_finite())
    }
    pub fn init(&mut self, bounds: Rect, timestamp_us: i64) {
        let z = measurement(bounds);
        self.mean = [0.0; 8];
        self.mean[..4].copy_from_slice(&z);
        self.timestamp_us = timestamp_us;
        self.covariance = [[0.0; 8]; 8];
        let h = z[3];
        let p = 1.0 / 20.0;
        let v = 1.0 / 10.0;
        let std = [
            2.0 * p * h,
            2.0 * p * h,
            1e-2,
            2.0 * p * h,
            10.0 * v * h,
            10.0 * v * h,
            1e-2,
            10.0 * v * h,
        ];
        for (i, s) in std.iter().enumerate() {
            self.covariance[i][i] = s * s;
        }
    }
    pub fn bounds(&self) -> Rect {
        let [cx, cy, a, h, ..] = self.mean;
        let a = a.max(0.01);
        let h = h.max(0.01);
        let w = a * h;
        Rect {
            left: cx - w / 2.0,
            top: cy - h / 2.0,
            right: cx + w / 2.0,
            bottom: cy + h / 2.0,
        }
    }
    pub fn dampen(&mut self, factor: f32) {
        for v in &mut self.mean[4..] {
            *v *= factor.clamp(0.0, 1.0);
        }
    }
    fn innovation(&self, bounds: Rect) -> ([f32; 4], [[f32; 4]; 4]) {
        let z = measurement(bounds);
        let h = z[3];
        let p = 1.0 / 20.0;
        let std = [p * h, p * h, 1e-1, p * h];
        let mut s = [[0.0; 4]; 4];
        for i in 0..4 {
            s[i].copy_from_slice(&self.covariance[i][..4]);
            s[i][i] += std[i] * std[i];
        }
        (std::array::from_fn(|i| z[i] - self.mean[i]), s)
    }
    pub fn gating_distance(&self, bounds: Rect) -> f32 {
        let (y, s) = self.innovation(bounds);
        let Some(inv) = invert4(s) else {
            return f32::MAX;
        };
        let mut d = 0.0;
        for i in 0..4 {
            let mut value = 0.0;
            for (j, yj) in y.iter().enumerate() {
                value += inv[i][j] * yj;
            }
            d += y[i] * value;
        }
        if d < 0.0 { f32::MAX } else { d }
    }
    pub fn predict(&mut self, timestamp_us: i64) -> Rect {
        let dt = if timestamp_us > 0 && self.timestamp_us > 0 && timestamp_us > self.timestamp_us {
            ((timestamp_us - self.timestamp_us) as f32 / 1_000_000.0).clamp(0.001, 0.2)
        } else {
            1.0 / 30.0
        };
        if timestamp_us > 0 {
            self.timestamp_us = timestamp_us;
        }
        for i in 0..4 {
            self.mean[i] += dt * self.mean[i + 4];
        }
        let h = self.mean[3].max(1e-4);
        let pos = (1.0 / 20.0) * h;
        let vel = (1.0 / 10.0) * h;
        let std = [
            pos * dt,
            pos * dt,
            1e-2 * dt,
            pos * dt,
            vel * dt,
            vel * dt,
            1e-3 * dt,
            vel * dt,
        ];
        let mut next = [[0.0; 8]; 8];
        for i in 0..4 {
            for j in 0..4 {
                let p11 = self.covariance[i][j];
                let p12 = self.covariance[i][j + 4];
                let p21 = self.covariance[i + 4][j];
                let p22 = self.covariance[i + 4][j + 4];
                next[i][j] = p11 + dt * (p21 + p12) + dt * dt * p22;
                next[i][j + 4] = p12 + dt * p22;
                next[i + 4][j] = p21 + dt * p22;
                next[i + 4][j + 4] = p22;
            }
        }
        for i in 0..8 {
            next[i][i] += std[i] * std[i];
        }
        self.covariance = next;
        self.bounds()
    }
    pub fn update(&mut self, bounds: Rect, timestamp_us: i64) {
        if timestamp_us > 0 {
            self.timestamp_us = timestamp_us;
        }
        let (innovation, s) = self.innovation(bounds);
        let Some(inv) = invert4(s) else {
            return;
        };
        let mut gain = [[0.0; 4]; 8];
        for (i, row) in gain.iter_mut().enumerate() {
            for (j, value) in row.iter_mut().enumerate() {
                for (k, inv_row) in inv.iter().enumerate() {
                    *value += self.covariance[i][k] * inv_row[j];
                }
            }
        }
        for (i, row) in gain.iter().enumerate() {
            let mut delta = 0.0;
            for j in 0..4 {
                delta += row[j] * innovation[j];
            }
            self.mean[i] += delta;
        }
        let mut khp = [[0.0; 8]; 8];
        for i in 0..8 {
            for j in 0..8 {
                for k in 0..4 {
                    khp[i][j] += gain[i][k] * self.covariance[k][j];
                }
            }
        }
        for (i, row) in khp.iter().enumerate() {
            for (j, value) in row.iter().enumerate() {
                self.covariance[i][j] -= value;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn filter_predicts_motion_and_gates_remote_observations() {
        let mut f = BoxFilter::default();
        let a = Rect {
            left: 10.0,
            top: 20.0,
            right: 110.0,
            bottom: 220.0,
        };
        f.init(a, 1);
        f.predict(40001);
        f.update(
            Rect {
                left: 20.0,
                right: 120.0,
                ..a
            },
            40001,
        );
        let previous = f.bounds().left;
        assert!(f.predict(80001).left > previous);
        assert!(
            f.gating_distance(Rect {
                left: 1000.0,
                right: 1100.0,
                ..a
            }) > f.gating_distance(a)
        );
        assert!(f.valid());
        assert!(invert4([[0.0; 4]; 4]).is_none());
    }
}
