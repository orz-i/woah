#!/usr/bin/env python3
"""SPAN-derived x2 crop-clarity model for LiteRT-friendly NHWC execution.

This is intentionally *not* a verbatim copy of the upstream SPAN implementation.
It keeps the parameter-free attention idea while constraining the graph to the
small LiteRT GPU op set Woah relies on: Conv2D, sigmoid, add/sub/mul,
concatenation, and bilinear resize. The original SPAN PixelShuffle head is not
used because LiteRT GPU does not currently list DEPTH_TO_SPACE as a supported
operator.

Upstream inspiration:
  Swift Parameter-free Attention Network for Efficient Super-Resolution
  https://github.com/hongyuanyu/SPAN (Apache-2.0)

The model consumes float32 NHWC RGB in [0, 1] with a static 192x192 tile and
produces float32 NHWC RGB at 384x384. Clipping to [0, 1] belongs to the caller so
no extra graph operators are introduced.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

TILE_SIZE = 192
UPSCALE = 2
OUTPUT_SIZE = TILE_SIZE * UPSCALE
FEATURE_CHANNELS = 12
BLOCK_COUNT = 3


@dataclass(frozen=True)
class ModelSpec:
    tile_size: int = TILE_SIZE
    upscale: int = UPSCALE
    feature_channels: int = FEATURE_CHANNELS
    block_count: int = BLOCK_COUNT
    input_layout: str = "NHWC"
    input_dtype: str = "float32"
    input_range: str = "0..1"


def _require_tensorflow():
    try:
        import tensorflow as tf  # type: ignore
    except ImportError as exc:  # pragma: no cover - tooling guard
        raise SystemExit(
            "TensorFlow is required for crop-clarity model authoring/export. "
            "Install it in an isolated tool environment; it is not a Woah runtime dependency."
        ) from exc
    return tf


def _conv(tf: Any, x: Any, channels: int, kernel: int, name: str) -> Any:
    return tf.keras.layers.Conv2D(
        channels,
        kernel,
        padding="same",
        use_bias=True,
        kernel_initializer="he_normal",
        name=name,
    )(x)


def _separable_conv(tf: Any, x: Any, channels: int, name: str) -> Any:
    """GPU-friendly depthwise 3x3 + pointwise 1x1 convolution."""
    x = tf.keras.layers.DepthwiseConv2D(
        3,
        padding="same",
        use_bias=True,
        depthwise_initializer="he_normal",
        name=f"{name}_dw",
    )(x)
    return tf.keras.layers.Conv2D(
        channels,
        1,
        padding="same",
        use_bias=True,
        kernel_initializer="he_normal",
        name=f"{name}_pw",
    )(x)


def _spab(tf: Any, x: Any, channels: int, index: int) -> Any:
    """LiteRT-safe parameter-free attention block inspired by SPAN's SPAB."""
    residual = x
    x = _separable_conv(tf, x, channels, f"spab{index}_conv1")
    x = tf.keras.layers.Activation("swish", name=f"spab{index}_swish1")(x)
    x = _separable_conv(tf, x, channels, f"spab{index}_conv2")
    x = tf.keras.layers.Activation("swish", name=f"spab{index}_swish2")(x)
    x = _separable_conv(tf, x, channels, f"spab{index}_conv3")
    attention = tf.keras.layers.Activation("sigmoid", name=f"spab{index}_sigmoid")(x)
    attention = tf.keras.layers.Lambda(
        lambda value: value - 0.5,
        name=f"spab{index}_center_attention",
    )(attention)
    x = tf.keras.layers.Add(name=f"spab{index}_residual_add")([x, residual])
    return tf.keras.layers.Multiply(name=f"spab{index}_attention_mul")([x, attention])


def build_model(spec: ModelSpec = ModelSpec()):
    """Build the trainable NHWC x2 model with only GPU-friendly primitives."""
    tf = _require_tensorflow()
    if spec.upscale != 2:
        raise ValueError("Woah V1 crop clarity model is intentionally x2-only")

    inputs = tf.keras.Input(
        shape=(spec.tile_size, spec.tile_size, 3),
        batch_size=1,
        dtype=tf.float32,
        name="lr_rgb",
    )
    stem = _conv(tf, inputs, spec.feature_channels, 3, "stem")

    blocks = []
    x = stem
    for index in range(1, spec.block_count + 1):
        x = _spab(tf, x, spec.feature_channels, index)
        blocks.append(x)

    taps = [stem]
    if blocks:
        taps.append(blocks[0])
    if len(blocks) >= 3:
        taps.append(blocks[-2])
    if blocks:
        taps.append(blocks[-1])
    fused = tf.keras.layers.Concatenate(axis=-1, name="feature_concat")(taps)
    fused = _conv(tf, fused, spec.feature_channels, 1, "feature_fuse")
    fused = _separable_conv(tf, fused, spec.feature_channels, "feature_refine")

    # LiteRT GPU explicitly supports RESIZE_BILINEAR. This replaces the
    # PixelShuffle / DEPTH_TO_SPACE head used by upstream SPAN.
    target_size = spec.tile_size * spec.upscale
    up_features = tf.keras.layers.Resizing(
        target_size,
        target_size,
        interpolation="bilinear",
        crop_to_aspect_ratio=False,
        name="feature_resize_x2",
    )(fused)
    residual_rgb = _conv(tf, up_features, 3, 3, "reconstruction")
    base_rgb = tf.keras.layers.Resizing(
        target_size,
        target_size,
        interpolation="bilinear",
        crop_to_aspect_ratio=False,
        name="base_resize_x2",
    )(inputs)
    outputs = tf.keras.layers.Add(name="sr_rgb")([base_rgb, residual_rgb])
    return tf.keras.Model(inputs=inputs, outputs=outputs, name="woah_span_lite_x2")


def export_tflite(
    output_path: Path,
    *,
    weights_path: Path | None = None,
    spec: ModelSpec = ModelSpec(),
) -> Path:
    """Export a float32 TFLite FlatBuffer without Select-TF/Flex ops."""
    tf = _require_tensorflow()
    model = build_model(spec)
    if weights_path is not None:
        model.load_weights(str(weights_path))

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converter.allow_custom_ops = False
    tflite = converter.convert()

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(tflite)
    return output_path


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--weights", type=Path)
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()

    if args.summary:
        build_model().summary()
    exported = export_tflite(args.output, weights_path=args.weights)
    print(f"CROP_CLARITY_TFLITE={exported}")
