//! Fixed, license-free PCM topology matrix from the PR #39 investigation.
//! Do not relax expectations to fit a matcher. See docs/comparison-alignment-governance.md.
use frame_kernel::audio_alignment::align;
use frame_kernel::audio_features::{FeatureBuilder, Features, RATE};

fn music(seconds: usize, notes_per_second: f64, mut seed: u64) -> Vec<f32> {
    let notes: Vec<_> = (0..(seconds as f64 * notes_per_second).ceil() as usize + 1)
        .map(|_| {
            seed = seed
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            160.0 + ((seed >> 11) as f64 / (1u64 << 53) as f64 * 800.0).floor()
        })
        .collect();
    (0..seconds * RATE)
        .map(|i| {
            let t = i as f64 / RATE as f64;
            let beat = t * notes_per_second;
            let phase = t * notes[beat as usize] * 2.0 * std::f64::consts::PI;
            ((1.0 - beat % 1.0).powf(0.4)
                * (0.25 * phase.sin() + 0.12 * (phase * 1.5).sin() + 0.07 * (phase * 2.23).sin()))
                as f32
        })
        .collect()
}

fn features(pcm: &[f32], origin: i64) -> Features {
    let mut builder = FeatureBuilder::new(origin);
    builder.add(pcm).unwrap();
    builder.finish()
}

fn stretch(pcm: &[f32], scale: f64) -> Vec<f32> {
    if scale == 1.0 {
        return pcm.to_vec();
    }
    (0..((pcm.len() - 1) as f64 * scale) as usize)
        .map(|i| {
            let t = i as f64 / scale;
            let j = t as usize;
            let frac = (t - j as f64) as f32;
            pcm[j] * (1.0 - frac) + pcm[j + 1] * frac
        })
        .collect()
}

#[test]
fn edits_and_observation_noise_are_different_outcomes() {
    let song = music(24, 8.0, 7);
    let r = features(&song, 0);
    let short = features(&song[..20 * RATE], 0);
    let mut failures = Vec::new();
    for gap in [80, 400, 850, 1000, 1500, 3000] {
        for tail in [250, 400, 800, 3000] {
            let end = 20 * RATE;
            let cut = end - tail * 16;
            let mut target = song[..cut].to_vec();
            target.resize(cut + gap * 16, 0.0);
            target.extend_from_slice(&song[cut..end]);
            let found = align(&short, &features(&target, 0), &|| true).unwrap();
            if !matches!(found.status, 1 | 2) {
                failures.push(format!("tail={tail}ms, gap={gap}ms: {}", found.status));
            }
        }
    }
    for at in [9500, 10200] {
        for gap in [200, 400, 850, 1000, 1500] {
            for span in [400, 650, 900, 1700] {
                let cut = at * 16;
                let resume = cut + span * 16;
                let mut target = song[..cut].to_vec();
                target.resize(cut + gap * 16, 0.0);
                target.extend_from_slice(&song[cut..resume]);
                target.extend_from_slice(&song[resume + gap * 16..]);
                let found = align(&r, &features(&target, 0), &|| true).unwrap();
                if !matches!(found.status, 1 | 2) {
                    failures.push(format!(
                        "at={at}ms, gap={gap}ms, span={span}ms: {}",
                        found.status
                    ));
                }
            }
        }
    }
    for (seed, grid, span) in [(290, 4.0, 700), (7, 8.0, 1000), (18, 8.0, 700)] {
        let song = music(24, grid, seed);
        let reference = features(&song, 0);
        for lag in [150, 300, 500] {
            for (direct, echo) in [(0.55, 0.4), (0.4, 0.55)] {
                let mut target = song.clone();
                for i in 10 * RATE..10 * RATE + span * 16 {
                    target[i] = direct * song[i] + echo * song[i - lag * 16];
                }
                let result = align(&reference, &features(&target, 0), &|| true).unwrap();
                let valid = result.candidates.first().is_some_and(|c| {
                    c.offset.abs() < 35_000
                        && ((c.scale - 1.0) * 24_000_000.0 + c.offset as f64).abs() < 35_000.0
                });
                if result.status != 0 || !valid {
                    failures.push(format!(
                        "echo seed={seed}, lag={lag}ms, direct={direct}: {result:?}"
                    ));
                }
            }
        }
    }
    assert!(
        failures.is_empty(),
        "{} contract failures:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

#[test]
fn an_independently_valid_copy_at_a_different_rate_prevents_auto_apply() {
    let pcm = music(14, 8.0, 7);
    let reference = features(&pcm, 0);
    for scales in [[1.0, 0.8], [0.995, 0.995], [0.8, 1.0], [1.0, 1.0]] {
        let mut target = vec![0.0; RATE];
        let mut offsets = Vec::new();
        for scale in scales {
            offsets.push(target.len() as f64 * 1e6 / RATE as f64);
            target.extend(stretch(&pcm, scale));
            target.resize(target.len() + RATE, 0.0);
        }
        let result = align(&reference, &features(&target, 0), &|| true).unwrap();
        assert_eq!(result.status, 3, "{scales:?}: {result:?}");
        for (&offset, scale) in offsets.iter().zip(scales) {
            assert!(
                result
                    .candidates
                    .iter()
                    .any(|c| [0.0, 14_000_000.0].into_iter().all(|t| {
                        ((c.scale - scale) * t + c.offset as f64 - offset).abs() < 35_000.0
                    })),
                "no candidate for {offset}us at {scale}: {result:?}",
            );
        }
    }
}

#[test]
fn mixed_rate_copy_in_uncovered_target_time_is_also_a_review_candidate() {
    for (name, scale) in [("slower", 0.8), ("faster", 1.25)] {
        let pcm = music(14, 8.0, 7);
        let reference = features(&pcm, 0);
        let mut target = vec![0.0; RATE];
        target.extend_from_slice(&pcm); // first, normal-speed passage
        target.resize(target.len() + RATE, 0.0);
        let second_offset = target.len() as i64 * 1_000_000 / RATE as i64;
        target.extend_from_slice(&stretch(&pcm, scale));
        let result = align(&reference, &features(&target, 0), &|| true).unwrap();
        assert_eq!(result.status, 3, "{name}: {result:?}");
        for (offset, expected_scale) in [(1_000_000.0, 1.0), (second_offset as f64, scale)] {
            assert!(
                result.candidates.iter().any(|candidate| {
                    [0.0, 14_000_000.0].into_iter().all(|time| {
                        ((candidate.scale - expected_scale) * time + candidate.offset as f64
                            - offset)
                            .abs()
                            < 35_000.0
                    })
                }),
                "missing {name} copy at {offset}: {result:?}",
            );
        }
    }
}
