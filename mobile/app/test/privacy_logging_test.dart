import 'dart:io';

import 'package:app/core/logging/app_logger.dart';
import 'package:app/features/import_video/presentation/import_video_controller.dart';
import 'package:app/repositories/native_processing_repository.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _NoStringError {
  @override
  String toString() => throw StateError('must not stringify');
}

class _ClientSpy implements DanceNativeClient {
  final seen = <String>[];
  @override
  Future<VideoInfo> probeVideo(String uri) async {
    seen.add(uri);
    throw PlatformException(
      code: 'VIDEO_OPEN_FAILED',
      message: uri,
      details: {
        'nested': [uri],
      },
    );
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('logger vocabulary rejects nested Unicode and encoded paths', () {
    final lines = <String>[];
    final previous = debugPrint;
    debugPrint = (String? value, {int? wrapWidth}) {
      if (value != null) lines.add(value);
    };
    addTearDown(() => debugPrint = previous);
    const secrets = [
      '/Users/测试 用户/私密 舞蹈.mp4',
      'file:///tmp/私密%20舞蹈.mp4',
      'content://vendor/tree/primary%3ADCIM%2F私密%20舞蹈.mp4',
      r'C:\Users\测试 用户\私密.mp4',
      r'\\server\share\私密.mp4',
      '私密 舞蹈.mp4',
    ];
    for (final path in secrets) {
      AppLogger.e(
        'ExportController',
        'EXPORT_START_FAILED',
        PlatformException(
          code: 'EXPORT_BUSY',
          message: path,
          details: {
            path: [
              FileSystemException(path, path),
              {'cause': path},
            ],
          },
        ),
        StackTrace.fromString(path),
      );
      AppLogger.e(path, path, PlatformException(code: path, message: path));
      AppLogger.d(path, path);
      AppLogger.w(path, path);
    }
    AppLogger.e('ExportController', 'EXPORT_START_FAILED', _NoStringError());
    final output = lines.join('\n');
    for (final path in secrets) {
      expect(output, isNot(contains(path)));
    }
    expect(output, isNot(contains('私密')));
    expect(output, contains('EXPORT_START_FAILED code=EXPORT_BUSY'));
    expect(output, contains('[App] UNCLASSIFIED_EVENT code=UNKNOWN_ERROR'));
    expect(
      AppLogger.exportErrorDetails(secrets.first),
      'stage=export; code=UNKNOWN_ERROR',
    );
  });

  test(
    'real repository preserves source URI but import error is fixed',
    () async {
      final spy = _ClientSpy();
      final controller = ImportVideoController(NativeProcessingRepository(spy));
      addTearDown(controller.dispose);
      const source = 'content://vendor/tree/primary%3ADCIM%2F私密%20舞蹈.mp4';
      await controller.probeVideoPath(source, name: '私密 舞蹈.mp4');
      expect(spy.seen, [source]);
      expect(controller.state.videoPath, source);
      expect(
        controller.state.errorMessage,
        '解析视频规格失败，请重试（VIDEO_PROBE_FAILED）。',
      );
    },
  );
}
