// Opt-in device/simulator mirror regression. Never used by production main.dart.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _MirrorQaApp());
}

class _MirrorQaApp extends StatefulWidget {
  const _MirrorQaApp();
  @override
  State<_MirrorQaApp> createState() => _MirrorQaAppState();
}

class _MirrorQaAppState extends State<_MirrorQaApp> {
  String _status = 'Mirror QA';
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final runner = _MirrorQa((message) {
        stdout.writeln(message);
        if (mounted) setState(() => _status = message);
      });
      await runner.run();
    });
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    home: Scaffold(body: Center(child: Text(_status))),
  );
}

class _MirrorQa {
  _MirrorQa(this.log);
  final void Function(String) log;
  final client = DanceNativeClient();
  late final Directory root;
  final rows = <Map<String, Object?>>[];
  final result = <String, Object?>{};
  static const _endMs = 720;

  Future<void> _persist() async {
    final target = File('${root.path}/results.json');
    final temporary = File('${target.path}.tmp');
    await temporary.writeAsString(
      const JsonEncoder.withIndent('  ').convert(result),
      flush: true,
    );
    await temporary.rename(target.path);
  }

  Future<JobStatusDto> _terminal(String job) async {
    final deadline = DateTime.now().add(const Duration(minutes: 3));
    while (DateTime.now().isBefore(deadline)) {
      final status = await client.getJobStatus(job);
      if ([
        'completed',
        'failed',
        'cancelled',
        'unknown',
      ].contains(status.state)) {
        // Wait for the native coordinator to finish terminal bookkeeping.
        await Future<void>.delayed(const Duration(milliseconds: 350));
        return status;
      }
      await Future<void>.delayed(const Duration(milliseconds: 180));
    }
    await client.cancelJob(job);
    throw TimeoutException('Mirror QA export timed out');
  }

  Future<void> run() async {
    const external = String.fromEnvironment('WOAH_MIRROR_QA_DIRECTORY');
    root = Directory(
      Platform.isIOS
          ? '${Directory.systemTemp.parent.path}/Documents/mirror_qa'
          : external,
    );
    if (!Platform.isIOS && external.isEmpty) {
      log('MIRROR_QA_FAIL missing isolated Android directory');
      return;
    }
    await root.create(recursive: true);
    result.addAll({
      'platform': Platform.operatingSystem,
      'status': 'running',
      'cases': rows,
    });
    await _persist();
    String? cache;
    try {
      final input = File('${root.path}/input.mp4');
      if (!await input.exists()) throw StateError('Missing mirror QA input');
      final info = await client.probeVideo(input.path);
      final a = await client.analyzeVideo(
        videoUri: input.path,
        modelProfile: 'quality',
      );
      cache = a.analysisCacheId;
      final ids = a.persons.map((p) => p.id).toList();
      if (ids.length < 2) {
        throw StateError('Expected at least two detected people');
      }
      result['source'] = {
        'width': info.displayWidth,
        'height': info.displayHeight,
        'fps': info.fps,
        'duration_ms': info.durationMs,
        'has_audio': info.hasAudio,
      };
      result['detected_ids'] = ids;
      final sorted = [...a.persons]..sort((a, b) => a.x1.compareTo(b.x1));
      final protected = sorted.first.id;
      final hero = sorted.last.id;
      result['protected_id'] = protected;
      result['follow_id'] = hero;
      final followFaces = ids.where((id) => id != hero).toList();
      result['follow_protected_ids'] = followFaces;
      await _persist();

      final solid = EffectConfig(
        fillMode: FillMode.solid,
        fillColorArgb: 0xFFFF0000,
        opacity: 1.0,
        borderWidth: 0,
      );
      final sticker = solid.copyWith(
        fillMode: FillMode.sticker,
        faceStickerEnabled: true,
        stickerAssetId: 'builtin:sunglasses',
      );
      final follow = FollowConfig(
        enabled: true,
        targetPersonId: hero,
        outputAspectRatio: 9 / 16,
      );
      final cases = [
        (
          'source',
          <int>[],
          <int>[],
          solid,
          const FollowConfig(),
          640,
          360,
          false,
        ),
        (
          'body',
          [protected],
          <int>[],
          solid,
          const FollowConfig(),
          640,
          360,
          true,
        ),
        (
          'face_sticker',
          <int>[],
          [protected],
          sticker,
          const FollowConfig(),
          640,
          360,
          true,
        ),
        ('follow_source', <int>[], <int>[], solid, follow, 288, 512, false),
        ('follow', <int>[], followFaces, sticker, follow, 288, 512, true),
      ];
      for (final c in cases) {
        for (final mirror in [false, true]) {
          final name = '${c.$1}-${mirror ? 'on' : 'off'}';
          final row = <String, Object?>{
            'name': name,
            'mirror': mirror,
            'body_ids': c.$2,
            'face_ids': c.$3,
            'follow': c.$5.enabled,
          };
          rows.add(row);
          try {
            log('MIRROR_QA_PREVIEW $name');
            final preview = await client.getPreviewFrame(
              analysisCacheId: a.analysisCacheId,
              timestampMs: 0,
              selectedPersonIds: c.$2,
              faceOnlyPersonIds: c.$3,
              effects: c.$4,
              follow: c.$5,
              tightMaskPreview: true,
              mirrorHorizontally: mirror,
            );
            final previewFile = File('${root.path}/$name.jpg');
            await File(preview.thumbnailPath).copy(previewFile.path);
            row['preview_bytes'] = await previewFile.length();
            if (c.$8) {
              log('MIRROR_QA_EXPORT $name');
              final out = File('${root.path}/$name.mp4');
              final job = await client.startExport(
                sourceUri: input.path,
                analysisCacheId: a.analysisCacheId,
                outputFilePath: out.path,
                selectedPersonIds: c.$2,
                faceOnlyPersonIds: c.$3,
                effects: c.$4,
                follow: c.$5,
                targetWidth: c.$6,
                targetHeight: c.$7,
                targetFps: info.fps,
                videoBitrate: 4000000,
                processingProfile: 'quality',
                enableLivePreview: true,
                trimEndMs: _endMs,
                mirrorHorizontally: mirror,
              );
              final status = await _terminal(job);
              row['job_state'] = status.state;
              row['frames'] = status.currentFrame;
              row['error_code'] = status.errorCode;
              row['output_bytes'] = await out.exists() ? await out.length() : 0;
              if (status.state != 'completed' ||
                  (row['output_bytes'] as int) < 1024) {
                throw StateError(
                  'Export failed: ${status.state} ${status.errorCode}',
                );
              }
            }
            row['status'] = 'pass';
            log('MIRROR_QA_CASE_PASS $name');
          } catch (e) {
            row['status'] = 'fail';
            row['error'] = e.toString();
            log('MIRROR_QA_CASE_FAIL $name $e');
          }
          await _persist();
        }
      }
      result['status'] = rows.every((r) => r['status'] == 'pass')
          ? 'pass'
          : 'fail';
    } catch (e, stack) {
      result['status'] = 'fail';
      result['error'] = e.toString();
      log('MIRROR_QA_FATAL $e\n$stack');
    } finally {
      if (cache != null) {
        try {
          await client.releaseProject(cache);
        } catch (_) {}
      }
      client.dispose();
      await _persist();
      log('MIRROR_QA_DONE ${result['status']}');
    }
  }
}
