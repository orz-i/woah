#!/usr/bin/env python3
"""No-training migration helpers for official pretrained SPAN x2 checkpoints.

The official SPAN x2 head is a 3x3 Conv2D with ``3 * scale^2`` outputs followed
by PyTorch PixelShuffle. LiteRT GPU does not list DEPTH_TO_SPACE, but does list
TRANSPOSE_CONV v1. For scale=2 the head can be rewritten exactly as:

  TRANSPOSE_CONV(main feature weights, stride=2, kernel=6)
  + TRANSPOSE_CONV(periodic phase bias, stride=2, kernel=2)

The second branch is necessary because PixelShuffle's source Conv2D owns a
separate bias for every output phase, while one ordinary transposed convolution
has only one bias per output channel. A zero-kernel 1x1 Conv2D with bias=1 can
produce the constant LR feature map needed by the periodic-bias branch using
only GPU-allowlisted operators.

This module intentionally contains no model training. It is the mathematical
bridge used by the preferred pretrained-weight route.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

SCALE = 2
KERNEL_SIZE = 3


@dataclass(frozen=True)
class PixelShuffleHead:
    """PyTorch layout head weights: OIHW + phase-specific bias."""

    weight: np.ndarray  # [out_channels * scale^2, in_channels, k, k]
    bias: np.ndarray  # [out_channels * scale^2]
    scale: int = SCALE

    @property
    def output_channels(self) -> int:
        phases = self.scale * self.scale
        if self.weight.shape[0] % phases != 0:
            raise ValueError("head output channels are not divisible by scale^2")
        return self.weight.shape[0] // phases

    @property
    def input_channels(self) -> int:
        return self.weight.shape[1]

    @property
    def kernel_size(self) -> int:
        if self.weight.ndim != 4 or self.weight.shape[2] != self.weight.shape[3]:
            raise ValueError("expected square PyTorch OIHW Conv2D weights")
        return self.weight.shape[2]

    def validate(self) -> None:
        if self.scale != 2:
            raise ValueError("Woah pretrained migration is intentionally x2-only")
        if self.kernel_size % 2 != 1:
            raise ValueError("exact SAME-padding mapping requires an odd source kernel")
        if self.bias.shape != (self.weight.shape[0],):
            raise ValueError(
                f"bias shape {self.bias.shape} does not match weight outputs {self.weight.shape[0]}"
            )
        if not np.isfinite(self.weight).all() or not np.isfinite(self.bias).all():
            raise ValueError("head weights/bias must be finite")


@dataclass(frozen=True)
class TransposeHead:
    """TensorFlow/LiteRT Conv2DTranspose HWOC-style filter tensors."""

    main_kernel: np.ndarray  # [k*scale, k*scale, out_channels, in_channels]
    phase_bias_kernel: np.ndarray  # [scale, scale, out_channels, 1]
    scale: int


def convert_pixelshuffle_head(head: PixelShuffleHead) -> TransposeHead:
    """Convert PyTorch Conv+PixelShuffle weights to an exact x2 transpose head.

    PyTorch PixelShuffle channel order is:
      q = output_channel * scale^2 + phase_y * scale + phase_x

    TensorFlow/LiteRT Conv2DTranspose with SAME padding needs the source spatial
    kernel reversed and each shuffle phase inserted at its HR sub-pixel offset.
    """
    head.validate()
    scale = head.scale
    kernel = head.kernel_size
    out_channels = head.output_channels
    in_channels = head.input_channels

    main = np.zeros(
        (kernel * scale, kernel * scale, out_channels, in_channels),
        dtype=np.float32,
    )
    source = np.asarray(head.weight, dtype=np.float32)
    for dy in range(kernel):
        for dx in range(kernel):
            for phase_y in range(scale):
                for phase_x in range(scale):
                    ky = (kernel - 1 - dy) * scale + phase_y
                    kx = (kernel - 1 - dx) * scale + phase_x
                    for out_channel in range(out_channels):
                        q = (
                            out_channel * scale * scale
                            + phase_y * scale
                            + phase_x
                        )
                        main[ky, kx, out_channel, :] = source[q, :, dy, dx]

    phase_bias = np.zeros((scale, scale, out_channels, 1), dtype=np.float32)
    source_bias = np.asarray(head.bias, dtype=np.float32)
    for phase_y in range(scale):
        for phase_x in range(scale):
            for out_channel in range(out_channels):
                q = out_channel * scale * scale + phase_y * scale + phase_x
                phase_bias[phase_y, phase_x, out_channel, 0] = source_bias[q]

    return TransposeHead(main_kernel=main, phase_bias_kernel=phase_bias, scale=scale)


def pytorch_pixelshuffle_reference(
    feature: np.ndarray,
    head: PixelShuffleHead,
) -> np.ndarray:
    """Small NumPy reference for tests; NHWC input, PyTorch phase ordering."""
    head.validate()
    if feature.ndim != 4 or feature.shape[0] != 1:
        raise ValueError("reference expects NHWC batch=1 input")
    if feature.shape[-1] != head.input_channels:
        raise ValueError("feature channel mismatch")
    batch, height, width, _ = feature.shape
    kernel = head.kernel_size
    pad = kernel // 2
    padded = np.pad(feature, ((0, 0), (pad, pad), (pad, pad), (0, 0)))
    conv = np.empty(
        (batch, height, width, head.weight.shape[0]),
        dtype=np.float32,
    )
    # Slow by design: this is a tiny deterministic equivalence oracle only.
    for y in range(height):
        for x in range(width):
            patch = padded[0, y : y + kernel, x : x + kernel, :]
            for q in range(head.weight.shape[0]):
                kernel_hwio = np.transpose(head.weight[q], (1, 2, 0))
                conv[0, y, x, q] = np.sum(patch * kernel_hwio) + head.bias[q]

    scale = head.scale
    output = np.empty(
        (batch, height * scale, width * scale, head.output_channels),
        dtype=np.float32,
    )
    for y in range(height):
        for x in range(width):
            for out_channel in range(head.output_channels):
                for phase_y in range(scale):
                    for phase_x in range(scale):
                        q = (
                            out_channel * scale * scale
                            + phase_y * scale
                            + phase_x
                        )
                        output[
                            0,
                            y * scale + phase_y,
                            x * scale + phase_x,
                            out_channel,
                        ] = conv[0, y, x, q]
    return output


def _require_tensorflow():
    try:
        import tensorflow as tf  # type: ignore
    except ImportError as exc:  # pragma: no cover - tooling guard
        raise SystemExit(
            "TensorFlow is required only for migration equivalence/export checks. "
            "Install tools/litert/crop_clarity_requirements.txt in an isolated environment."
        ) from exc
    return tf


def transpose_head_reference_numpy(
    feature: np.ndarray,
    converted: TransposeHead,
) -> np.ndarray:
    """Pure NumPy ConvTranspose reference matching SAME padding for x2."""
    if feature.ndim != 4 or feature.shape[0] != 1:
        raise ValueError("reference expects NHWC batch=1 input")
    batch, height, width, in_channels = feature.shape
    if in_channels != converted.main_kernel.shape[3]:
        raise ValueError("feature channel mismatch")
    scale = converted.scale
    out_channels = converted.main_kernel.shape[2]
    output = np.zeros((batch, height * scale, width * scale, out_channels), np.float32)
    kernel = converted.main_kernel.shape[0]
    # SAME ConvTranspose output=source*stride. For kernel=source_k*scale,
    # the symmetric crop is source_padding*scale.
    padding = (kernel - scale) // 2
    for y in range(height):
        for x in range(width):
            source = feature[0, y, x, :]
            for ky in range(kernel):
                oy = y * scale + ky - padding
                if oy < 0 or oy >= height * scale:
                    continue
                for kx in range(kernel):
                    ox = x * scale + kx - padding
                    if ox < 0 or ox >= width * scale:
                        continue
                    output[0, oy, ox, :] += converted.main_kernel[ky, kx] @ source

    # Periodic bias branch: a constant-one LR feature map, kernel=scale,
    # stride=scale, SAME => every HR phase is written exactly once.
    for y in range(height):
        for x in range(width):
            for phase_y in range(scale):
                for phase_x in range(scale):
                    output[0, y * scale + phase_y, x * scale + phase_x, :] += (
                        converted.phase_bias_kernel[phase_y, phase_x, :, 0]
                    )
    return output


def tensorflow_transpose_head(
    feature: np.ndarray,
    converted: TransposeHead,
) -> np.ndarray:
    """Execute the converted head with the exact LiteRT-target primitives."""
    tf = _require_tensorflow()
    tensor = tf.convert_to_tensor(feature, dtype=tf.float32)
    batch, height, width, _ = feature.shape
    scale = converted.scale
    output_shape = [batch, height * scale, width * scale, converted.main_kernel.shape[2]]
    main = tf.nn.conv2d_transpose(
        tensor,
        converted.main_kernel,
        output_shape=output_shape,
        strides=[1, scale, scale, 1],
        padding="SAME",
    )
    ones = tf.ones([batch, height, width, 1], dtype=tf.float32)
    bias = tf.nn.conv2d_transpose(
        ones,
        converted.phase_bias_kernel,
        output_shape=output_shape,
        strides=[1, scale, scale, 1],
        padding="SAME",
    )
    return (main + bias).numpy()


def verify_equivalence(
    *,
    seed: int = 20261006,
    height: int = 7,
    width: int = 9,
    input_channels: int = 5,
    output_channels: int = 3,
) -> dict[str, float | int]:
    rng = np.random.default_rng(seed)
    phases = SCALE * SCALE
    head = PixelShuffleHead(
        weight=rng.normal(
            0.0,
            0.1,
            (output_channels * phases, input_channels, KERNEL_SIZE, KERNEL_SIZE),
        ).astype(np.float32),
        bias=rng.normal(0.0, 0.1, (output_channels * phases,)).astype(np.float32),
    )
    feature = rng.normal(0.0, 1.0, (1, height, width, input_channels)).astype(np.float32)
    reference = pytorch_pixelshuffle_reference(feature, head)
    converted = convert_pixelshuffle_head(head)
    numpy_actual = transpose_head_reference_numpy(feature, converted)
    numpy_delta = np.abs(reference - numpy_actual)
    if float(numpy_delta.max()) > 1e-4:
        return {
            "max_abs": float(numpy_delta.max()),
            "mean_abs": float(numpy_delta.mean()),
            "elements": int(numpy_delta.size),
        }
    actual = tensorflow_transpose_head(feature, converted)
    delta = np.abs(reference - actual)
    return {
        "max_abs": float(delta.max()),
        "mean_abs": float(delta.mean()),
        "elements": int(delta.size),
    }


def export_equivalent_head_tflite(
    output: Path,
    *,
    input_channels: int = 48,
    output_channels: int = 3,
    tile_size: int = 192,
    seed: int = 20261006,
) -> Path:
    """Export a random-weight proof graph using only the target head primitives."""
    tf = _require_tensorflow()
    rng = np.random.default_rng(seed)
    phases = SCALE * SCALE
    source_head = PixelShuffleHead(
        weight=rng.normal(
            0.0,
            0.02,
            (output_channels * phases, input_channels, KERNEL_SIZE, KERNEL_SIZE),
        ).astype(np.float32),
        bias=rng.normal(0.0, 0.02, (output_channels * phases,)).astype(np.float32),
    )
    converted = convert_pixelshuffle_head(source_head)

    inputs = tf.keras.Input(
        shape=(tile_size, tile_size, input_channels),
        batch_size=1,
        dtype=tf.float32,
        name="span_features",
    )
    main = tf.keras.layers.Conv2DTranspose(
        output_channels,
        kernel_size=KERNEL_SIZE * SCALE,
        strides=SCALE,
        padding="same",
        use_bias=False,
        trainable=False,
        name="pixelshuffle_equivalent_main",
    )(inputs)
    # Produce a constant-one LR map without shape/tile ops: zero kernel + bias 1.
    ones = tf.keras.layers.Conv2D(
        1,
        kernel_size=1,
        padding="same",
        use_bias=True,
        trainable=False,
        kernel_initializer="zeros",
        bias_initializer="ones",
        name="phase_bias_constant_one",
    )(inputs)
    phase_bias = tf.keras.layers.Conv2DTranspose(
        output_channels,
        kernel_size=SCALE,
        strides=SCALE,
        padding="same",
        use_bias=False,
        trainable=False,
        name="pixelshuffle_equivalent_phase_bias",
    )(ones)
    outputs = tf.keras.layers.Add(name="pixelshuffle_equivalent_output")([main, phase_bias])
    model = tf.keras.Model(inputs=inputs, outputs=outputs)

    model.get_layer("pixelshuffle_equivalent_main").set_weights([converted.main_kernel])
    model.get_layer("phase_bias_constant_one").set_weights(
        [np.zeros((1, 1, input_channels, 1), np.float32), np.ones((1,), np.float32)]
    )
    model.get_layer("pixelshuffle_equivalent_phase_bias").set_weights(
        [converted.phase_bias_kernel]
    )

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converter.allow_custom_ops = False
    payload = converter.convert()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(payload)
    return output


RGB_MEAN = (0.4488, 0.4371, 0.4040)
IMG_RANGE = 255.0
BLOCK_COUNT = 6


def load_reparameterized_archive(path: Path) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as payload:
        return {key: np.asarray(payload[key]) for key in payload.files}


def random_reparameterized_archive(
    *,
    feature_channels: int = 48,
    seed: int = 20261006,
) -> dict[str, np.ndarray]:
    """Synthetic full-SPAN inference weights for graph/performance proof only."""
    rng = np.random.default_rng(seed)

    def conv(out_channels: int, in_channels: int, kernel: int) -> tuple[np.ndarray, np.ndarray]:
        scale = 0.02 / max(1.0, np.sqrt(in_channels * kernel * kernel))
        return (
            rng.normal(0, scale, (out_channels, in_channels, kernel, kernel)).astype(np.float32),
            rng.normal(0, scale, (out_channels,)).astype(np.float32),
        )

    archive: dict[str, np.ndarray] = {}
    archive["conv_1.weight"], archive["conv_1.bias"] = conv(feature_channels, 3, 3)
    for block in range(1, BLOCK_COUNT + 1):
        for conv_name in ("c1", "c2", "c3"):
            archive[f"block_{block}.{conv_name}.weight"], archive[f"block_{block}.{conv_name}.bias"] = conv(
                feature_channels, feature_channels, 3
            )
    archive["conv_2.weight"], archive["conv_2.bias"] = conv(feature_channels, feature_channels, 3)
    archive["conv_cat.weight"], archive["conv_cat.bias"] = conv(feature_channels, feature_channels * 4, 1)
    archive["upsampler.weight"], archive["upsampler.bias"] = conv(12, feature_channels, 3)
    archive["__feature_channels"] = np.asarray([feature_channels], np.int32)
    archive["__scale"] = np.asarray([2], np.int32)
    archive["__input_channels"] = np.asarray([3], np.int32)
    archive["__output_channels"] = np.asarray([3], np.int32)
    return archive


def _archive_scalar(archive: dict[str, np.ndarray], key: str) -> int:
    if key not in archive or np.asarray(archive[key]).size != 1:
        raise ValueError(f"archive is missing scalar metadata {key}")
    return int(np.asarray(archive[key]).reshape(-1)[0])


def validate_reparameterized_archive(archive: dict[str, np.ndarray]) -> None:
    feature_channels = _archive_scalar(archive, "__feature_channels")
    if _archive_scalar(archive, "__scale") != 2:
        raise ValueError("Woah pretrained route accepts x2 archives only")
    if _archive_scalar(archive, "__input_channels") != 3 or _archive_scalar(archive, "__output_channels") != 3:
        raise ValueError("Woah pretrained route accepts RGB->RGB SPAN only")

    expected: dict[str, tuple[int, ...]] = {
        "conv_1.weight": (feature_channels, 3, 3, 3),
        "conv_1.bias": (feature_channels,),
        "conv_2.weight": (feature_channels, feature_channels, 3, 3),
        "conv_2.bias": (feature_channels,),
        "conv_cat.weight": (feature_channels, feature_channels * 4, 1, 1),
        "conv_cat.bias": (feature_channels,),
        "upsampler.weight": (12, feature_channels, 3, 3),
        "upsampler.bias": (12,),
    }
    for block in range(1, BLOCK_COUNT + 1):
        for conv_name in ("c1", "c2", "c3"):
            expected[f"block_{block}.{conv_name}.weight"] = (
                feature_channels, feature_channels, 3, 3
            )
            expected[f"block_{block}.{conv_name}.bias"] = (feature_channels,)
    for key, shape in expected.items():
        if key not in archive:
            raise ValueError(f"reparameterized archive is missing {key}")
        value = np.asarray(archive[key])
        if value.shape != shape:
            raise ValueError(f"archive tensor {key} has shape {value.shape}, expected {shape}")
        if not np.isfinite(value).all():
            raise ValueError(f"archive tensor {key} contains non-finite values")


def _fixed_conv_oihw(tf: Any, x: Any, weight: np.ndarray, bias: np.ndarray, name: str) -> Any:
    weight = np.asarray(weight, np.float32)
    bias = np.asarray(bias, np.float32)
    layer = tf.keras.layers.Conv2D(
        filters=weight.shape[0],
        kernel_size=(weight.shape[2], weight.shape[3]),
        padding="same",
        use_bias=True,
        trainable=False,
        name=name,
    )
    output = layer(x)
    layer.set_weights([np.transpose(weight, (2, 3, 1, 0)), bias])
    return output


def _span_block(tf: Any, x: Any, archive: dict[str, np.ndarray], block: int) -> tuple[Any, Any]:
    prefix = f"block_{block}"
    out1 = _fixed_conv_oihw(
        tf, x, archive[f"{prefix}.c1.weight"], archive[f"{prefix}.c1.bias"], f"{prefix}_c1"
    )
    out1_act = tf.keras.layers.Activation("swish", name=f"{prefix}_swish1")(out1)
    out2 = _fixed_conv_oihw(
        tf,
        out1_act,
        archive[f"{prefix}.c2.weight"],
        archive[f"{prefix}.c2.bias"],
        f"{prefix}_c2",
    )
    out2_act = tf.keras.layers.Activation("swish", name=f"{prefix}_swish2")(out2)
    out3 = _fixed_conv_oihw(
        tf,
        out2_act,
        archive[f"{prefix}.c3.weight"],
        archive[f"{prefix}.c3.bias"],
        f"{prefix}_c3",
    )
    attention = tf.keras.layers.Activation("sigmoid", name=f"{prefix}_sigmoid")(out3)
    attention = tf.keras.layers.Lambda(lambda value: value - 0.5, name=f"{prefix}_center_attention") (
        attention
    )
    residual = tf.keras.layers.Add(name=f"{prefix}_residual")([out3, x])
    output = tf.keras.layers.Multiply(name=f"{prefix}_attention")([residual, attention])
    return output, out1


def build_pretrained_span_x2_model(
    archive: dict[str, np.ndarray],
    *,
    tile_size: int = 192,
):
    """Build the full official SPAN inference topology from fused checkpoint weights.

    Input/output use Woah's [0,1] RGB contract. Internally the input is normalized
    exactly like upstream SPAN: (x - rgb_mean) * 255. The final upstream output
    is divided by 255 so the mobile runtime retains its existing [0,1] contract.
    """
    tf = _require_tensorflow()
    validate_reparameterized_archive(archive)
    feature_channels = _archive_scalar(archive, "__feature_channels")

    inputs = tf.keras.Input(
        shape=(tile_size, tile_size, 3),
        batch_size=1,
        dtype=tf.float32,
        name="lr_rgb",
    )
    norm = tf.keras.layers.Conv2D(
        3,
        1,
        padding="same",
        use_bias=True,
        trainable=False,
        name="upstream_input_normalization",
    )
    x = norm(inputs)
    norm_kernel = np.zeros((1, 1, 3, 3), np.float32)
    for channel in range(3):
        norm_kernel[0, 0, channel, channel] = IMG_RANGE
    norm_bias = -np.asarray(RGB_MEAN, np.float32) * IMG_RANGE
    norm.set_weights([norm_kernel, norm_bias])

    out_feature = _fixed_conv_oihw(
        tf, x, archive["conv_1.weight"], archive["conv_1.bias"], "conv_1"
    )
    block_outputs: list[Any] = []
    block_first: list[Any] = []
    current = out_feature
    for block in range(1, BLOCK_COUNT + 1):
        current, first = _span_block(tf, current, archive, block)
        block_outputs.append(current)
        block_first.append(first)

    out_b6 = _fixed_conv_oihw(
        tf,
        block_outputs[-1],
        archive["conv_2.weight"],
        archive["conv_2.bias"],
        "conv_2",
    )
    # Upstream concatenation is [out_feature, out_b6, out_b1, out_b5_2], where
    # out_b5_2 is actually block_6's first Conv3XC output returned as out1.
    fused = tf.keras.layers.Concatenate(axis=-1, name="feature_concat")(
        [out_feature, out_b6, block_outputs[0], block_first[-1]]
    )
    fused = _fixed_conv_oihw(
        tf, fused, archive["conv_cat.weight"], archive["conv_cat.bias"], "conv_cat"
    )

    head = PixelShuffleHead(
        weight=np.asarray(archive["upsampler.weight"], np.float32),
        bias=np.asarray(archive["upsampler.bias"], np.float32),
    )
    converted = convert_pixelshuffle_head(head)
    main_layer = tf.keras.layers.Conv2DTranspose(
        3,
        kernel_size=6,
        strides=2,
        padding="same",
        use_bias=False,
        trainable=False,
        name="upsampler_transpose_main",
    )
    main = main_layer(fused)
    main_layer.set_weights([converted.main_kernel])

    ones_layer = tf.keras.layers.Conv2D(
        1,
        1,
        padding="same",
        use_bias=True,
        trainable=False,
        name="upsampler_phase_bias_constant_one",
    )
    ones = ones_layer(fused)
    ones_layer.set_weights(
        [np.zeros((1, 1, feature_channels, 1), np.float32), np.ones((1,), np.float32)]
    )
    phase_layer = tf.keras.layers.Conv2DTranspose(
        3,
        kernel_size=2,
        strides=2,
        padding="same",
        use_bias=False,
        trainable=False,
        name="upsampler_transpose_phase_bias",
    )
    phase_bias = phase_layer(ones)
    phase_layer.set_weights([converted.phase_bias_kernel])
    upstream_output = tf.keras.layers.Add(name="upstream_span_output")([main, phase_bias])

    scale_layer = tf.keras.layers.Conv2D(
        3,
        1,
        padding="same",
        use_bias=True,
        trainable=False,
        name="woah_output_scale_0_1",
    )
    outputs = scale_layer(upstream_output)
    scale_kernel = np.zeros((1, 1, 3, 3), np.float32)
    for channel in range(3):
        scale_kernel[0, 0, channel, channel] = 1.0 / IMG_RANGE
    scale_layer.set_weights([scale_kernel, np.zeros((3,), np.float32)])
    return tf.keras.Model(inputs=inputs, outputs=outputs, name="woah_pretrained_span_x2")


def export_pretrained_span_x2_tflite(
    output: Path,
    *,
    archive: dict[str, np.ndarray],
    tile_size: int = 192,
) -> Path:
    tf = _require_tensorflow()
    model = build_pretrained_span_x2_model(archive, tile_size=tile_size)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converter.allow_custom_ops = False
    payload = converter.convert()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(payload)
    return output


def main() -> int:
    import argparse
    import json

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-equivalence", action="store_true")
    parser.add_argument("--export-head-proof", type=Path)
    parser.add_argument("--archive", type=Path, help="Reparameterized NPZ from extract_pretrained_span_x2.py")
    parser.add_argument("--export-full", type=Path, help="Export full pretrained-migrated x2 LiteRT model")
    parser.add_argument("--export-full-random-proof", type=Path, help="Export full official topology with synthetic weights")
    args = parser.parse_args()
    if (
        not args.verify_equivalence
        and args.export_head_proof is None
        and args.export_full is None
        and args.export_full_random_proof is None
    ):
        parser.error("request an equivalence check or export action")

    if args.verify_equivalence:
        report = verify_equivalence()
        print("SPAN_PRETRAINED_HEAD_EQUIVALENCE=" + json.dumps(report, sort_keys=True))
        if report["max_abs"] > 1e-4:
            raise SystemExit("SPAN_PRETRAINED_HEAD_EQUIVALENCE=FAIL")
        print("SPAN_PRETRAINED_HEAD_EQUIVALENCE=PASS")
    if args.export_head_proof is not None:
        path = export_equivalent_head_tflite(args.export_head_proof)
        print(f"SPAN_PRETRAINED_HEAD_TFLITE={path}")
    if args.export_full is not None:
        if args.archive is None:
            parser.error("--export-full requires --archive")
        archive = load_reparameterized_archive(args.archive)
        path = export_pretrained_span_x2_tflite(args.export_full, archive=archive)
        print(f"SPAN_PRETRAINED_FULL_TFLITE={path}")
    if args.export_full_random_proof is not None:
        path = export_pretrained_span_x2_tflite(
            args.export_full_random_proof,
            archive=random_reparameterized_archive(),
        )
        print(f"SPAN_PRETRAINED_FULL_RANDOM_PROOF={path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
