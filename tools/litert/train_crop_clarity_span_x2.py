#!/usr/bin/env python3
"""Train the SPAN-derived crop-clarity x2 model with real-video-style degradation.

Production training should point --data-dir at a diverse licensed HR image/video
frame corpus. The repository test fixtures can be used only with --smoke-fixtures
to validate the training/export plumbing; a fixture-trained checkpoint must never
be promoted as a release model.
"""
from __future__ import annotations

import argparse
from io import BytesIO
from pathlib import Path
import random

import numpy as np
from PIL import Image, ImageFilter

from crop_clarity_span_x2 import OUTPUT_SIZE, TILE_SIZE, build_model


def _require_tensorflow():
    try:
        import tensorflow as tf  # type: ignore
    except ImportError as exc:
        raise SystemExit("TensorFlow is required for training") from exc
    return tf


def image_paths(data_dir: Path) -> list[Path]:
    extensions = {".png", ".jpg", ".jpeg", ".webp"}
    return sorted(p for p in data_dir.rglob("*") if p.suffix.lower() in extensions)


def _ensure_patch_source(image: Image.Image, size: int) -> Image.Image:
    width, height = image.size
    if width >= size and height >= size:
        return image
    scale = max(size / max(1, width), size / max(1, height))
    return image.resize(
        (max(size, round(width * scale)), max(size, round(height * scale))),
        Image.Resampling.LANCZOS,
    )


def _random_hr_patch(path: Path, rng: random.Random) -> Image.Image:
    image = Image.open(path).convert("RGB")
    image = _ensure_patch_source(image, OUTPUT_SIZE)
    width, height = image.size
    left = rng.randint(0, max(0, width - OUTPUT_SIZE))
    top = rng.randint(0, max(0, height - OUTPUT_SIZE))
    patch = image.crop((left, top, left + OUTPUT_SIZE, top + OUTPUT_SIZE))
    if rng.random() < 0.5:
        patch = patch.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    return patch


def _jpeg_roundtrip(image: Image.Image, quality: int) -> Image.Image:
    stream = BytesIO()
    image.save(stream, format="JPEG", quality=quality, subsampling=2)
    stream.seek(0)
    with Image.open(stream) as decoded:
        return decoded.convert("RGB")


def _degrade(hr: Image.Image, rng: random.Random) -> np.ndarray:
    """Real-ESRGAN-inspired lightweight degradation for phone/video sources."""
    working = hr
    if rng.random() < 0.85:
        working = working.filter(ImageFilter.GaussianBlur(radius=rng.uniform(0.0, 1.6)))

    # Variable resize before the final x2 sampling simulates mixed resize chains
    # from social media, camera ISP scaling and editor transcodes.
    intermediate = rng.randint(round(TILE_SIZE * 0.68), round(TILE_SIZE * 1.15))
    interpolation = rng.choice(
        [Image.Resampling.BILINEAR, Image.Resampling.BICUBIC, Image.Resampling.LANCZOS]
    )
    working = working.resize((intermediate, intermediate), interpolation)
    working = working.resize((TILE_SIZE, TILE_SIZE), rng.choice([
        Image.Resampling.BILINEAR,
        Image.Resampling.BICUBIC,
    ]))

    if rng.random() < 0.9:
        working = _jpeg_roundtrip(working, rng.randint(55, 96))

    lr = np.asarray(working, dtype=np.float32) / 255.0
    if rng.random() < 0.65:
        sigma = rng.uniform(0.0, 3.5) / 255.0
        noise_rng = np.random.default_rng(rng.randrange(1 << 31))
        lr = lr + noise_rng.normal(0.0, sigma, lr.shape).astype(np.float32)
    return np.clip(lr, 0.0, 1.0)


def sample_pair(paths: list[Path], rng: random.Random) -> tuple[np.ndarray, np.ndarray]:
    hr_image = _random_hr_patch(rng.choice(paths), rng)
    hr = np.asarray(hr_image, dtype=np.float32) / 255.0
    lr = _degrade(hr_image, rng)
    return lr[None, ...], hr[None, ...]


def train(
    paths: list[Path],
    *,
    output_weights: Path,
    steps: int,
    learning_rate: float,
    seed: int,
) -> None:
    tf = _require_tensorflow()
    if not paths:
        raise ValueError("No training images found")
    rng = random.Random(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)

    model = build_model()
    optimizer = tf.keras.optimizers.Adam(learning_rate=learning_rate)

    @tf.function
    def train_step(lr, hr):
        with tf.GradientTape() as tape:
            sr = model(lr, training=True)
            delta = sr - hr
            charbonnier = tf.reduce_mean(tf.sqrt(tf.square(delta) + 1e-6))
            sr_edges = tf.image.sobel_edges(sr)
            hr_edges = tf.image.sobel_edges(hr)
            edge_loss = tf.reduce_mean(tf.abs(sr_edges - hr_edges))
            loss = charbonnier + 0.05 * edge_loss
        gradients = tape.gradient(loss, model.trainable_variables)
        optimizer.apply_gradients(zip(gradients, model.trainable_variables))
        return loss, charbonnier, edge_loss

    for step in range(1, steps + 1):
        lr_np, hr_np = sample_pair(paths, rng)
        loss, pixel, edge = train_step(lr_np, hr_np)
        if step == 1 or step % 25 == 0 or step == steps:
            print(
                f"step={step}/{steps} loss={float(loss):.6f} "
                f"pixel={float(pixel):.6f} edge={float(edge):.6f}"
            )

    output_weights.parent.mkdir(parents=True, exist_ok=True)
    model.save_weights(str(output_weights))
    print(f"CROP_CLARITY_WEIGHTS={output_weights}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--smoke-fixtures", action="store_true")
    parser.add_argument("--output-weights", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=1000)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--seed", type=int, default=20261005)
    args = parser.parse_args()

    if args.smoke_fixtures:
        root = Path(__file__).resolve().parents[2]
        paths = image_paths(root / "testdata")
        print("WARNING: using repository fixtures for plumbing validation only; do not ship these weights")
    elif args.data_dir is not None:
        paths = image_paths(args.data_dir)
    else:
        parser.error("provide --data-dir or explicitly use --smoke-fixtures")

    train(
        paths,
        output_weights=args.output_weights,
        steps=max(1, args.steps),
        learning_rate=args.learning_rate,
        seed=args.seed,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
