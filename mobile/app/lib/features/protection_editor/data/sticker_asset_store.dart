import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../repositories/native_processing_repository.dart';

final stickerAssetStoreProvider = Provider<StickerAssetStore>((ref) {
  return StickerAssetStore(ref.watch(nativeRepositoryProvider));
});

class StickerAssetStore {
  static const _outputSize = 512;
  static const _maxDecodeDimension = 2048;
  static const _maxInputBytes = 25 * 1024 * 1024;

  final NativeProcessingRepository _repository;

  const StickerAssetStore(this._repository);

  Future<String?> pickAndImport() async {
    final files = await FilePickerPlatform.instance.pickFiles(
      type: FileType.image,
    );
    if (files.isEmpty) return null;

    final picked = files.first;
    final inputLength = await picked.length();
    if (inputLength > _maxInputBytes) {
      throw const FormatException('图片过大，请选择 25 MB 以内的图片');
    }
    final bytes = await picked.readAsBytes();
    if (bytes.isEmpty) {
      throw const FormatException('所选图片为空');
    }

    final normalized = await _normalizeSquarePng(bytes);
    return _repository.persistStickerAsset(normalized);
  }

  Future<Uint8List> _normalizeSquarePng(Uint8List bytes) async {
    ui.ImmutableBuffer? buffer;
    ui.ImageDescriptor? descriptor;
    ui.Codec? codec;
    ui.Image? decoded;
    ui.Picture? picture;
    ui.Image? output;

    try {
      buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
      descriptor = await ui.ImageDescriptor.encoded(buffer);
      if (descriptor.width <= 0 || descriptor.height <= 0) {
        throw const FormatException('无法识别图片尺寸');
      }

      final shortest = math.min(descriptor.width, descriptor.height);
      final longest = math.max(descriptor.width, descriptor.height);
      final targetScale = _outputSize / shortest;
      final boundedScale = _maxDecodeDimension / longest;
      final scale = math.min(targetScale, boundedScale);
      final decodeWidth = math.max(1, (descriptor.width * scale).round());
      final decodeHeight = math.max(1, (descriptor.height * scale).round());

      codec = await descriptor.instantiateCodec(
        targetWidth: decodeWidth,
        targetHeight: decodeHeight,
      );
      final frame = await codec.getNextFrame();
      decoded = frame.image;

      final cropSize = math.min(decoded.width, decoded.height).toDouble();
      final sourceRect = ui.Rect.fromLTWH(
        (decoded.width - cropSize) / 2,
        (decoded.height - cropSize) / 2,
        cropSize,
        cropSize,
      );
      final destinationRect = ui.Rect.fromLTWH(
        0,
        0,
        _outputSize.toDouble(),
        _outputSize.toDouble(),
      );

      final recorder = ui.PictureRecorder();
      final canvas = ui.Canvas(recorder);
      canvas.drawImageRect(
        decoded,
        sourceRect,
        destinationRect,
        ui.Paint()..filterQuality = ui.FilterQuality.high,
      );
      picture = recorder.endRecording();
      output = await picture.toImage(_outputSize, _outputSize);
      final png = await output.toByteData(format: ui.ImageByteFormat.png);
      if (png == null) {
        throw const FormatException('无法转换所选图片');
      }
      return png.buffer.asUint8List();
    } on FormatException {
      rethrow;
    } catch (_) {
      throw const FormatException('图片格式不受支持或文件已损坏');
    } finally {
      output?.dispose();
      picture?.dispose();
      decoded?.dispose();
      codec?.dispose();
      descriptor?.dispose();
      buffer?.dispose();
    }
  }
}
