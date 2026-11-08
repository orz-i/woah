// Shared Android-device/iOS-simulator media QA. Never used by production main.dart.
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _QaApp());
}

class _QaApp extends StatefulWidget {
  const _QaApp();
  @override
  State<_QaApp> createState() => _QaAppState();
}

class _QaAppState extends State<_QaApp> {
  String state = 'Preparing real-media privacy regression';
  final List<String> lines = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      try {
        await _QA(log: _write).run();
      } catch (e, stack) {
        _write('QA_FATAL=$e\n$stack');
      }
    });
  }

  void _write(String message) {
    // Logs are printed and also written to the simulator's Documents directory.
    stdout.writeln(message);
    if (!mounted) return;
    setState(() {
      state = message;
      lines.add(message);
      if (lines.length > 13) lines.removeAt(0);
    });
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    home: Scaffold(
      appBar: AppBar(title: const Text('Woah Privacy QA • real media')),
      body: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Process $pid', style: const TextStyle(fontSize: 16)),
            const SizedBox(height: 18),
            Text(
              state,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 18),
            Expanded(
              child: SingleChildScrollView(
                child: SelectableText(lines.join('\n\n')),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

const _cases = <Map<String, Object>>[
  {
    'case': 'group_all_faces',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'endMs': 3040,
    'width': 960,
    'height': 540,
  },
  {
    'case': 'group_face_sticker_follow',
    'input': 'group-hd-8956295.mp4',
    'fill': 'sticker',
    'hero': 6,
    'endMs': 3040,
    'width': 720,
    'height': 1280,
  },
  {
    'case': 'group_face_solid_follow',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'hero': 6,
    'endMs': 3040,
    'width': 720,
    'height': 1280,
  },
  {
    'case': 'group_full_solid',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'faceOnly': false,
    'width': 960,
    'height': 540,
  },
  {
    'case': 'group_full_mosaic',
    'input': 'group-hd-8956295.mp4',
    'fill': 'mosaic',
    'faceOnly': false,
    'width': 960,
    'height': 540,
  },
  {
    'case': 'group_full_blur',
    'input': 'group-hd-8956295.mp4',
    'fill': 'blur',
    'faceOnly': false,
    'width': 960,
    'height': 540,
  },
  {
    'case': 'group_full_gradient',
    'input': 'group-hd-8956295.mp4',
    'fill': 'gradient',
    'faceOnly': false,
    'width': 960,
    'height': 540,
  },
  {
    'case': 'group_first_solid',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'faceOnly': false,
    'select': 'first',
    'width': 960,
    'height': 540,
  },
  {
    'case': 'group_4k_full',
    'input': 'group-mirror-8956295.mp4',
    'fill': 'solid',
    'faceOnly': false,
    'width': 960,
    'height': 540,
  },
  {
    'case': 'duo_full_solid',
    'input': 'duo-dance-6940536.mp4',
    'fill': 'solid',
    'faceOnly': false,
    'width': 960,
    'height': 540,
  },
  {'case': 'solo_face_sticker', 'fill': 'sticker'},
  {'case': 'solo_face_mosaic', 'fill': 'mosaic'},
  {'case': 'solo_face_blur', 'fill': 'blur'},
  {'case': 'solo_face_solid', 'fill': 'solid'},
  {'case': 'solo_face_gradient', 'fill': 'gradient'},
  {'case': 'solo_face_balanced', 'fill': 'sticker', 'profile': 'balanced'},
  {'case': 'solo_face_speed', 'fill': 'sticker', 'profile': 'speed'},
  {
    'case': 'solo_nonzero_trim',
    'fill': 'sticker',
    'startMs': 1000,
    'endMs': 2600,
  },
  {
    'case': 'solo_4k_sticker',
    'input': 'solo-portrait-7570258.mp4',
    'fill': 'sticker',
  },
  {
    'case': 'group_full_balanced',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'faceOnly': false,
    'profile': 'balanced',
    'width': 960,
    'height': 540,
  },
  {
    'case': 'group_full_speed',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'faceOnly': false,
    'profile': 'speed',
    'width': 960,
    'height': 540,
  },
  {
    'case': 'group_full_length_follow',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'hero': 6,
    'faceOnly': true,
    'endMs': 23240,
    'width': 720,
    'height': 1280,
  },
];

const _supplementCases = <Map<String, Object>>[
  {
    'case': 'group_face_sticker_follow_ios0',
    'input': 'group-hd-8956295.mp4',
    'fill': 'sticker',
    'hero': 0,
    'endMs': 3040,
    'width': 720,
    'height': 1280,
  },
  {
    'case': 'group_face_solid_follow_ios0',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'hero': 0,
    'endMs': 3040,
    'width': 720,
    'height': 1280,
  },
  {
    'case': 'solo_face_sticker_ios1000',
    'input': 'solo-hd-7570258.mp4',
    'fill': 'sticker',
    'startMs': 1000,
    'endMs': 2600,
  },
  {
    'case': 'solo_4k_sticker_ios1000',
    'input': 'solo-portrait-7570258.mp4',
    'fill': 'sticker',
    'startMs': 1000,
    'endMs': 2600,
  },
];

class _QA {
  _QA({required this.log});
  final void Function(String) log;
  late final DanceNativeClient client = DanceNativeClient();
  late final Directory data;
  late final Directory docs;
  String? activeCase;
  DateTime lastProgressWrite = DateTime.fromMillisecondsSinceEpoch(0);
  bool verifyPreviews = false;
  late final Directory sources = Directory('${docs.path}/qa_sources');
  late Directory outputs = Directory('${docs.path}/qa_outputs');
  final Map<String, AnalyzeResultDto> analysis = {};
  final List<Map<String, Object?>> rows = [];

  Future<void> run() async {
    const qaDirectory = String.fromEnvironment('WOAH_PRIVACY_QA_DIRECTORY');
    if (qaDirectory.isNotEmpty) {
      docs = Directory(qaDirectory);
    } else if (Platform.isIOS) {
      docs = Directory('${Directory.systemTemp.parent.path}/Documents');
    } else {
      throw StateError('Build Android QA with WOAH_PRIVACY_QA_DIRECTORY');
    }
    data = docs.parent;
    log('QA_BOOT data=${data.path} pid=$pid');
    await outputs.create(recursive: true);
    final modeFile = File('${docs.path}/qa_mode.txt');
    final mode = await modeFile.exists()
        ? (await modeFile.readAsString()).trim()
        : 'matrix';
    log('QA_MODE=$mode');
    final capabilities = await client.getCapabilities();
    log('QA_CAPABILITIES=$capabilities');
    if (mode == 'configured') {
      final config =
          jsonDecode(
                await File('${docs.path}/qa_ios_cases.json').readAsString(),
              )
              as Map<String, dynamic>;
      final runId = config['run_id'] as String;
      if (!RegExp(r'^[a-zA-Z0-9_-]+$').hasMatch(runId)) {
        throw StateError('Invalid QA run id');
      }
      outputs = Directory('${docs.path}/qa_ios_runs/$runId/outputs');
      if (await outputs.parent.exists()) {
        throw StateError(
          'Preserve existing QA run; choose a new run id: $runId',
        );
      }
      await outputs.create(recursive: true);
      verifyPreviews = config['verify_previews'] == true;
      const compiledFingerprint = String.fromEnvironment(
        'WOAH_QA_SOURCE_FINGERPRINT',
      );
      if (config['build_source_fingerprint'] != null &&
          config['build_source_fingerprint'] != compiledFingerprint) {
        throw StateError(
          'Installed QA binary does not match the requested source manifest',
        );
      }
      if (config['core_smokes'] == true && Platform.isIOS) {
        final checks = <String, Object?>{};
        try {
          for (final method in const [
            'runIOSMetalPhase3Smoke',
            'runIOSExportPhase4Smoke',
            'runIOSGoldenTracePhase5Smoke',
            'runIOSPrivacyClassPhase6Smoke',
            'runIOSCacheLifecycleSmoke',
            'runIOSDiagnosticPrivacySmoke',
          ]) {
            final report = await const MethodChannel(
              'dance_native',
            ).invokeMapMethod<String, dynamic>(method);
            if (report == null) {
              throw StateError('$method returned no evidence');
            }
            checks[method] = report;
            log('QA_CORE_SMOKE_PASS $method');
          }
          await persist('qa_ios_runs/$runId/core-smokes.json', {
            'status': 'passed',
            'checks': checks,
          });
        } catch (error, stack) {
          await persist('qa_ios_runs/$runId/core-smokes.json', {
            'status': 'failed',
            'checks': checks,
            'error': '$error',
            'stack': '$stack',
          });
          await persist('qa_ios_runs/$runId/results.json', {
            'status': 'finished',
            'cases': [],
            'core_smoke_failure': '$error',
          });
          rethrow;
        }
      }
      await matrix(
        capabilities.toString(),
        cases: config['legacy_22'] == true
            ? _cases
            : (config['cases'] as List)
                  .map((c) => Map<String, Object>.from(c as Map))
                  .toList(),
        resultFile: 'qa_ios_runs/$runId/results.json',
        runLifecycle: config['lifecycle'] == true,
      );
    } else if (mode == 'matrix') {
      await matrix(capabilities.toString());
    } else if (mode == 'audio_lifecycle') {
      await lifecycle(resultFile: 'qa_audio_lifecycle_results.json');
    } else if (mode == 'long_follow_diagnostic') {
      final row = await runCase({
        'case': 'group_full_length_follow_diagnostic_first_5s',
        'input': 'group-hd-8956295.mp4',
        'fill': 'solid',
        'hero': 6,
        'faceOnly': false,
        'startMs': 0,
        'endMs': 5000,
        'width': 720,
        'height': 1280,
      });
      await persist('qa_long_follow_diagnostic.json', {
        'case': row,
        'finished': true,
      });
      log('QA_LONG_FOLLOW_DIAGNOSTIC_DONE result=${row['result']}');
    } else if (mode == 'mask_orientation_probe') {
      final face = await runCase({
        'case': 'group_all_faces_upright_mask',
        'input': 'group-hd-8956295.mp4',
        'fill': 'solid',
        'endMs': 3040,
        'width': 960,
        'height': 540,
      });
      await persist('qa_mask_orientation_probe.json', {
        'pid': pid,
        'face': face,
      });
    } else if (mode == 'analysis_probe') {
      await analysisProbe();
    } else if (mode == 'supplement') {
      await supplement();
    } else if (mode == 'interrupt_start') {
      await startInterrupted();
    } else if (mode == 'interrupt_check') {
      await checkInterrupted();
    } else {
      log('QA_INVALID_MODE=$mode');
    }
  }

  Future<void> persist(String name, Map<String, Object?> data) async {
    final file = File('${docs.path}/$name');
    final temp = File('${file.path}.tmp');
    await temp.writeAsString(const JsonEncoder.withIndent('  ').convert(data));
    await temp.rename(file.path);
  }

  Map<String, Object?> statusRecord(JobStatusDto s) => {
    'state': s.state,
    'job_id': s.jobId,
    'current_frame': s.currentFrame,
    'total_frames': s.totalFrames,
    'progress': s.progress,
    'fps': s.fps,
    'output_uri': s.outputUri,
    'error_code': s.errorCode,
    'error_message': s.errorMessage,
  };

  Future<AnalyzeResultDto> analyze(String input, {int startMs = 0}) async {
    final key = '$input-$startMs';
    if (analysis.containsKey(key)) return analysis[key]!;
    final file = File('${sources.path}/$input');
    if (!await file.exists()) {
      throw StateError('Missing QA media input ${file.path}');
    }
    final info = await client.probeVideo(file.path);
    log(
      'QA_PROBE $input ${info.displayWidth}x${info.displayHeight} fps=${info.fps} duration=${info.durationMs}',
    );
    final result = await client.analyzeVideo(
      videoUri: file.path,
      modelProfile: 'quality',
      trimStartMs: startMs,
    );
    log(
      'QA_ANALYZE $input start=$startMs ids=${result.persons.map((p) => p.id).toList()}',
    );
    analysis[key] = result;
    return result;
  }

  Future<JobStatusDto> terminal(
    String job, {
    Duration timeout = const Duration(minutes: 5),
  }) async {
    final start = DateTime.now();
    var lastAdvance = start;
    var previousFrame = -1;
    while (DateTime.now().difference(start) < timeout) {
      final status = await client.getJobStatus(job);
      if (status.currentFrame != previousFrame) {
        previousFrame = status.currentFrame;
        lastAdvance = DateTime.now();
      }
      await recordProgress(status);
      if (DateTime.now().difference(lastAdvance) > const Duration(minutes: 8)) {
        await client.cancelJob(job);
        throw TimeoutException(
          'Export made no frame progress for eight minutes: $job',
        );
      }
      if (status.state == 'completed' ||
          status.state == 'failed' ||
          status.state == 'cancelled' ||
          status.state == 'unknown') {
        await Future<void>.delayed(const Duration(milliseconds: 200));
        return status;
      }
      await Future<void>.delayed(const Duration(milliseconds: 220));
    }
    await client.cancelJob(job);
    final status = await client.getJobStatus(job);
    throw TimeoutException(
      'Export job timed out: $job ${statusRecord(status)}',
      timeout,
    );
  }

  Future<void> recordProgress(JobStatusDto status) async {
    final now = DateTime.now();
    if (now.difference(lastProgressWrite) < const Duration(seconds: 2)) return;
    lastProgressWrite = now;
    await persist('qa_current_progress.json', {
      'case': activeCase,
      'process_id': pid,
      'updated_at_utc': now.toUtc().toIso8601String(),
      ...statusRecord(status),
    });
  }

  EffectConfig effect(String fill, {String? stickerAssetId}) => EffectConfig(
    fillMode: FillMode.fromString(fill),
    fillColorArgb: 0xFFFF0000,
    borderColorArgb: 0xFFFFFFFF,
    opacity: 1.0,
    borderWidth: 0,
    blurStrength: 18,
    faceStickerEnabled: fill == 'sticker',
    stickerAssetId:
        stickerAssetId ?? (fill == 'sticker' ? 'builtin:sunglasses' : null),
  );

  Future<Map<String, Object?>> runCase(Map<String, Object> c) async {
    final name = c['case'] as String;
    final input = (c['input'] as String?) ?? 'solo-hd-7570258.mp4';
    final fill = (c['fill'] as String?) ?? 'sticker';
    final profile = (c['profile'] as String?) ?? 'quality';
    final startMs = (c['startMs'] as int?) ?? 0;
    final endMs = (c['endMs'] as int?) ?? 1600;
    final faceOnly = (c['faceOnly'] as bool?) ?? true;
    final hero = c['hero'] as int?;
    final width = (c['width'] as int?) ?? 540;
    final height = (c['height'] as int?) ?? 1024;
    final startedAt = DateTime.now();
    activeCase = name;
    log(
      'QA_CASE_START $name input=$input fill=$fill trim=[$startMs,$endMs) profile=$profile',
    );
    final row = <String, Object?>{
      ...c,
      'case': name,
      'input': input,
      'fill': fill,
      'start_ms': startMs,
      'end_ms': endMs,
      'face_only': faceOnly,
      'profile': profile,
      'output_width': width,
      'output_height': height,
      'result': 'not_executed',
      'pid': pid,
    };
    try {
      final a = await analyze(input, startMs: startMs);
      final ids = a.persons.map((p) => p.id).toList();
      row['detected_ids'] = ids;
      if (ids.isEmpty) throw StateError('NO_DETECTED_PERSONS');
      if (hero != null && !ids.contains(hero)) {
        throw StateError('HERO_ID_NOT_FOUND:$hero in $ids');
      }
      final stickerAsset = c['customSticker'] == true
          ? await client.persistStickerAsset(
              await File('${sources.path}/custom-sticker.png').readAsBytes(),
            )
          : null;
      if (stickerAsset != null) {
        row['custom_sticker_bytes'] = await File(stickerAsset).length();
      }
      final selected = (c['select'] == 'first' ? ids.take(1).toList() : ids)
          .where((id) => id != hero)
          .toList();
      row['selected_ids'] = selected;
      row['hero_id'] = hero;
      final qaEffects = effect(fill, stickerAssetId: stickerAsset);
      final qaFollow = FollowConfig(
        enabled: hero != null,
        targetPersonId: hero,
        outputAspectRatio: hero != null ? width / height : null,
      );
      if (verifyPreviews) {
        final directory = Directory('${outputs.parent.path}/previews');
        await directory.create(recursive: true);
        final frameMs = (1000 / a.videoInfo.fps).ceil();
        final lastMs = (endMs - frameMs).clamp(startMs, endMs - 1).toInt();
        final previewRows = <Map<String, Object?>>[];
        final previewTimes = {startMs, (startMs + lastMs) ~/ 2, lastMs};
        for (final regressionMs in [6600, 11800]) {
          if (hero == 6 && startMs <= regressionMs && lastMs >= regressionMs) {
            previewTimes.add(regressionMs);
          }
        }
        // Full4K clipped faces must also be checked in the native preview path.
        if (name == 'group_4k_face_solid' && startMs == 0 && endMs == 23240) {
          previewTimes.addAll([7400, 8120]);
        }
        for (final at in previewTimes) {
          final preview = await client.getPreviewFrame(
            analysisCacheId: a.analysisCacheId,
            timestampMs: at,
            selectedPersonIds: faceOnly ? [] : selected,
            faceOnlyPersonIds: faceOnly ? selected : [],
            effects: qaEffects,
            follow: qaFollow,
          );
          final bytes = await File(preview.thumbnailPath).readAsBytes();
          final codec = await ui.instantiateImageCodec(bytes);
          final image = (await codec.getNextFrame()).image;
          if (image.width <= 0 || image.height <= 0) {
            throw StateError('Empty preview image');
          }
          final target = File('${directory.path}/$name-$at.png');
          await target.writeAsBytes(bytes);
          final previewWidth = image.width;
          final previewHeight = image.height;
          image.dispose();
          codec.dispose();
          // A full-frame, unprotected control verifies native decode time
          // independently of the privacy effect and follow crop under test.
          final control = await client.getPreviewFrame(
            analysisCacheId: a.analysisCacheId,
            timestampMs: at,
            selectedPersonIds: const [],
            faceOnlyPersonIds: const [],
            effects: effect('solid'),
            follow: const FollowConfig(),
          );
          final controlBytes = await File(control.thumbnailPath).readAsBytes();
          final controlCodec = await ui.instantiateImageCodec(controlBytes);
          final controlImage = (await controlCodec.getNextFrame()).image;
          final controlFile = File('${directory.path}/$name-$at-source.png');
          await controlFile.writeAsBytes(controlBytes);
          previewRows.add({
            'requested_ms': at,
            'dto_timestamp_ms': preview.timestampMs,
            'control_file': controlFile.uri.pathSegments.last,
            'control_bytes': controlBytes.length,
            'control_width': controlImage.width,
            'control_height': controlImage.height,
            'width': previewWidth,
            'height': previewHeight,
            'bytes': bytes.length,
            'file': target.uri.pathSegments.last,
          });
          controlImage.dispose();
          controlCodec.dispose();
        }
        row['previews'] = previewRows;
      }
      final out = File('${outputs.path}/$name.mp4');
      if (await out.exists()) await out.delete();
      final job = await client.startExport(
        sourceUri: File('${sources.path}/$input').path,
        analysisCacheId: a.analysisCacheId,
        outputFilePath: out.path,
        selectedPersonIds: faceOnly ? [] : selected,
        faceOnlyPersonIds: faceOnly ? selected : [],
        effects: qaEffects,
        follow: qaFollow,
        targetWidth: width,
        targetHeight: height,
        targetFps: a.videoInfo.fps,
        videoBitrate: (c['bitrate'] as int?) ?? 4000000,
        processingProfile: profile,
        trimStartMs: startMs,
        trimEndMs: endMs,
      );
      row['job_id'] = job;
      final cancelAt = c['cancelAtFrame'] as int?;
      if (cancelAt != null) {
        final deadline = DateTime.now().add(const Duration(minutes: 10));
        while (true) {
          final progress = await client.getJobStatus(job);
          await recordProgress(progress);
          if (progress.state == 'completed' ||
              progress.state == 'failed' ||
              progress.state == 'cancelled' ||
              progress.state == 'unknown') {
            break;
          }
          if (progress.currentFrame >= cancelAt) {
            row['before_cancel'] = statusRecord(progress);
            await client.cancelJob(job);
            break;
          }
          if (DateTime.now().isAfter(deadline)) {
            throw TimeoutException('Cancellation progress deadline');
          }
          await Future<void>.delayed(const Duration(milliseconds: 220));
        }
      }
      final s = await terminal(job, timeout: const Duration(minutes: 90));
      row.addAll(statusRecord(s));
      row['file_bytes'] = await out.exists() ? await out.length() : 0;
      row['result'] = cancelAt != null
          ? (s.state == 'cancelled' &&
                    row['before_cancel'] != null &&
                    !await out.exists()
                ? 'cancelled_cleanly'
                : 'failed')
          : (s.state == 'completed' && (row['file_bytes'] as int) > 1024
                ? 'output_created'
                : 'failed');
      row['partial_files'] = (await outputs.list().toList())
          .whereType<File>()
          .where(
            (f) =>
                (f.path.contains('.$name.') &&
                    f.path.endsWith('.partial.mp4')) ||
                f.path.endsWith('/$name.tmp.mp4'),
          )
          .map((f) => f.path)
          .toList();
      if ((row['partial_files'] as List).isNotEmpty) row['result'] = 'failed';
      if (c['saveToGallery'] == true && row['result'] == 'output_created') {
        row['gallery_saved_uri'] = await client.saveVideoToGallery(out.path);
        row['gallery_save_completed'] = row['gallery_saved_uri'] != null;
        if (row['gallery_save_completed'] != true) row['result'] = 'failed';
        if (Platform.isAndroid && row['gallery_save_completed'] == true) {
          final original = await const MethodChannel('dance_native')
              .invokeMapMethod<String, dynamic>(
                'copyPrivacyQaGalleryOriginal',
                {'uri': row['gallery_saved_uri']},
              );
          if (original == null || (original['bytes'] as int? ?? 0) <= 0) {
            throw StateError('Saved gallery original was not readable');
          }
          row['gallery_original'] = original;
        }
      }
    } catch (error, stack) {
      row['result'] = 'failed';
      row['exception'] = error.toString();
      row['exception_stack'] = stack.toString().split('\n').take(8).join('\n');
    }
    row['elapsed_seconds'] =
        DateTime.now().difference(startedAt).inMilliseconds / 1000;
    log(
      'QA_CASE_END $name result=${row['result']} bytes=${row['file_bytes'] ?? 0} error=${row['error_code'] ?? row['exception'] ?? '-'} elapsed=${row['elapsed_seconds']}',
    );
    return row;
  }

  Future<void> analysisProbe() async {
    final entries = <Map<String, Object?>>[];
    final specs = [
      ('group-hd-8956295.mp4', 0),
      ('solo-hd-7570258.mp4', 0),
      ('solo-portrait-7570258.mp4', 0),
      ('solo-hd-7570258.mp4', 1000),
      ('no-person-flower.mp4', 0),
    ];
    for (final (name, anchor) in specs) {
      try {
        final result = await analyze(name, startMs: anchor);
        entries.add({
          'input': name,
          'trim_start_ms': anchor,
          'persons': result.persons
              .map(
                (person) => {
                  'id': person.id,
                  'confidence': person.confidence,
                  'bbox': [person.x1, person.y1, person.x2, person.y2],
                },
              )
              .toList(),
          'count': result.persons.length,
          'status': 'passed_api_call',
        });
      } catch (error) {
        entries.add({
          'input': name,
          'trim_start_ms': anchor,
          'status': 'failed',
          'error': error.toString(),
        });
      }
      await persist('qa_analysis_probe.json', {
        'pid': pid,
        'mode': 'analysis_probe',
        'cases': entries,
        'finished': entries.length == specs.length,
      });
    }
    log(
      'QA_ANALYSIS_PROBE_DONE group=${entries.first['count']} cases=${entries.length}',
    );
  }

  Future<void> supplement() async {
    final result = <String, Object?>{
      'status': 'running',
      'process_id': pid,
      'note':
          'Non-equivalent diagnostic follow-ups: use iOS-detected hero 0 and frame 1s, do not replace the original 22-case Android parity matrix.',
      'cases': <Map<String, Object?>>[],
    };
    final supplementRows = result['cases']! as List<Map<String, Object?>>;
    Future<void> persistProgress() =>
        persist('qa_supplement_results.json', result);
    await persistProgress();
    for (final testCase in _supplementCases) {
      supplementRows.add(await runCase(testCase));
      await persistProgress();
    }
    result['status'] = 'cases_finished';
    await persistProgress();
    await lifecycle(
      analysisStartMs: 1000,
      cancelStartMs: 1000,
      resultFile: 'qa_lifecycle_fallback_results.json',
    );
    result['status'] = 'finished';
    await persistProgress();
    log('QA_SUPPLEMENT_DONE cases=${supplementRows.length}');
  }

  Future<void> matrix(
    String capabilities, {
    List<Map<String, Object>> cases = _cases,
    String resultFile = 'qa_matrix_results.json',
    bool runLifecycle = true,
  }) async {
    final started = DateTime.now().toUtc().toIso8601String();
    final content = <String, Object?>{
      'started_at_utc': started,
      'process_id': pid,
      'mode': 'matrix',
      'platform': Platform.operatingSystem,
      'verify_previews': verifyPreviews,
      'compiled_source_fingerprint': const String.fromEnvironment(
        'WOAH_QA_SOURCE_FINGERPRINT',
      ),
      'compiled_source_commit': const String.fromEnvironment(
        'WOAH_QA_SOURCE_COMMIT',
      ),
      'capabilities': capabilities,
      'cases': rows,
      'status': 'running',
    };
    Future<void> snapshot() => persist(resultFile, content);
    await snapshot();
    for (final c in cases) {
      final result = await runCase(c);
      rows.add(result);
      await snapshot();
    }
    content['status'] = 'matrix_finished';
    await snapshot();
    if (runLifecycle) {
      await lifecycle();
      await negatives();
    }
    content['status'] = 'finished';
    content['finished_at_utc'] = DateTime.now().toUtc().toIso8601String();
    await snapshot();
    log(
      'QA_SUITE_DONE matrix=${rows.length} output_created=${rows.where((r) => r['result'] == 'output_created').length}',
    );
  }

  Future<void> lifecycle({
    int analysisStartMs = 0,
    int cancelStartMs = 0,
    String resultFile = 'qa_lifecycle_results.json',
  }) async {
    log(
      'QA_LIFECYCLE_START complete->cancel->retry+custom_sticker anchor=$analysisStartMs',
    );
    final result = <String, Object?>{
      'process_id': pid,
      'status': 'running',
      'analysis_start_ms': analysisStartMs,
      'long_trim_start_ms': cancelStartMs,
    };
    Future<void> snapshot() => persist(resultFile, result);
    await snapshot();
    try {
      final source = 'solo-portrait-7570258.mp4';
      final a = await analyze(source, startMs: analysisStartMs);
      final ids = a.persons.map((p) => p.id).toList();
      result.addAll({
        'input': source,
        'target_width': 674,
        'target_height': 1280,
        'retry_start_ms': 1000,
        'retry_end_ms': 2920,
        'face_only': true,
        'fill': 'sticker',
        'selected_ids': ids,
      });
      if (ids.isEmpty) {
        throw StateError('No person detected for 4K cancellation input');
      }
      final sticker = await client.persistStickerAsset(
        await File('${sources.path}/custom-sticker.png').readAsBytes(),
      );
      result['custom_sticker_asset'] = sticker;
      result['custom_sticker_exists'] = await File(sticker).exists();
      await snapshot();
      final live = File('${outputs.path}/cancel_long_4k.mp4');
      if (await live.exists()) await live.delete();
      final jobId = await client.startExport(
        sourceUri: '${sources.path}/$source',
        analysisCacheId: a.analysisCacheId,
        outputFilePath: live.path,
        selectedPersonIds: [],
        faceOnlyPersonIds: ids,
        effects: effect('sticker', stickerAssetId: sticker),
        targetWidth: 674,
        targetHeight: 1280,
        targetFps: a.videoInfo.fps,
        videoBitrate: 4000000,
        trimStartMs: cancelStartMs,
        trimEndMs: 26480,
      );
      result['cancel_job_id'] = jobId;
      final start = DateTime.now();
      JobStatusDto? last;
      while (DateTime.now().difference(start) < const Duration(minutes: 3)) {
        last = await client.getJobStatus(jobId);
        if (last.currentFrame >= 8 &&
            (last.state == 'exporting' || last.state == 'processing')) {
          break;
        }
        if (last.state == 'failed' || last.state == 'completed') break;
        await Future<void>.delayed(const Duration(milliseconds: 250));
      }
      result['before_cancel'] = last == null ? null : statusRecord(last);
      await client.cancelJob(jobId);
      final after = await client.getJobStatus(jobId);
      result['after_cancel'] = statusRecord(after);
      await Future<void>.delayed(const Duration(seconds: 2));
      result['cancelled_output_exists'] = await live.exists();
      final partialFiles = (await outputs.list().toList())
          .whereType<File>()
          .where(
            (f) =>
                f.path.endsWith('/cancel_long_4k.tmp.mp4') ||
                f.path.contains('/.cancel_long_4k.'),
          )
          .map((f) => f.path)
          .toList();
      result['cancelled_partial_files'] = partialFiles;
      result['cancel_passed'] =
          after.state == 'cancelled' &&
          after.outputUri == null &&
          !await live.exists() &&
          partialFiles.isEmpty;
      await snapshot();
      log('QA_LIFECYCLE_CANCEL ${result['cancel_passed']}');
      // Keep the original analysis and process; a prior cancellation must not
      // block the next export. Same custom PNG is rendered into final output.
      final retry = File('${outputs.path}/retry_custom_sticker.mp4');
      if (await retry.exists()) await retry.delete();
      final retryId = await client.startExport(
        sourceUri: '${sources.path}/$source',
        analysisCacheId: a.analysisCacheId,
        outputFilePath: retry.path,
        selectedPersonIds: [],
        faceOnlyPersonIds: ids,
        effects: effect('sticker', stickerAssetId: sticker),
        targetWidth: 674,
        targetHeight: 1280,
        targetFps: a.videoInfo.fps,
        videoBitrate: 4000000,
        trimStartMs: 1000,
        trimEndMs: 2920,
      );
      final retryStatus = await terminal(
        retryId,
        timeout: const Duration(minutes: 6),
      );
      result['retry_status'] = statusRecord(retryStatus);
      result['retry_bytes'] = await retry.exists() ? await retry.length() : 0;
      result['retry_passed'] =
          retryStatus.state == 'completed' &&
          (result['retry_bytes'] as int) > 1024;
      result['status'] =
          (result['cancel_passed'] == true && result['retry_passed'] == true)
          ? 'passed_api_chain'
          : 'failed';
    } catch (error, stack) {
      result['status'] = 'failed';
      result['exception'] = error.toString();
      result['stack'] = stack.toString().split('\n').take(12).join('\n');
    }
    await snapshot();
    log('QA_LIFECYCLE_END ${result['status']}');
  }

  Future<void> negatives() async {
    log('QA_NEGATIVES_START');
    final result = <String, Object?>{'process_id': pid, 'status': 'running'};
    try {
      final flower = await analyze('no-person-flower.mp4');
      result['flower_ids'] = flower.persons.map((e) => e.id).toList();
      result['flower_no_person'] = flower.persons.isEmpty;
    } catch (e) {
      result['flower_error'] = e.toString();
    }
    try {
      await client.probeVideo('${sources.path}/corrupt-video-1024bytes.mp4');
      result['corrupt_rejected'] = false;
      result['corrupt_error'] = 'Probe accepted truncated 1024-byte video';
    } catch (e) {
      result['corrupt_rejected'] = true;
      result['corrupt_error'] = e.toString();
    }
    try {
      final normal = await analyze('duo-dance-6940536.mp4');
      result['valid_reimport_person_ids'] = normal.persons
          .map((e) => e.id)
          .toList();
      result['valid_reimport'] = normal.persons.isNotEmpty;
    } catch (e) {
      result['valid_reimport_error'] = e.toString();
    }
    result['status'] =
        result['flower_no_person'] == true &&
            result['corrupt_rejected'] == true &&
            result['valid_reimport'] == true
        ? 'passed_api_checks'
        : 'failed';
    await persist('qa_negative_results.json', result);
    log('QA_NEGATIVES_END ${result['status']}');
  }

  Future<void> startInterrupted() async {
    log('QA_INTERRUPT_START');
    final a = await analyze('solo-portrait-7570258.mp4', startMs: 1000);
    final ids = a.persons.map((p) => p.id).toList();
    if (ids.isEmpty) {
      throw StateError('Cannot interrupt without a 4K selected person');
    }
    final out = File('${outputs.path}/interrupted_4k.mp4');
    if (await out.exists()) await out.delete();
    final job = await client.startExport(
      sourceUri: '${sources.path}/solo-portrait-7570258.mp4',
      analysisCacheId: a.analysisCacheId,
      outputFilePath: out.path,
      selectedPersonIds: [],
      faceOnlyPersonIds: ids,
      effects: effect('sticker'),
      targetWidth: 674,
      targetHeight: 1280,
      targetFps: a.videoInfo.fps,
      videoBitrate: 4000000,
      trimStartMs: 1000,
      trimEndMs: 26480,
    );
    final started = DateTime.now();
    while (DateTime.now().difference(started) < const Duration(minutes: 3)) {
      final s = await client.getJobStatus(job);
      if (s.currentFrame >= 8 && s.state == 'exporting') {
        await persist('qa_interrupt_start.json', {
          'pid': pid,
          'job_id': job,
          'state_before_termination': statusRecord(s),
          'output_path': out.path,
          'at_utc': DateTime.now().toUtc().toIso8601String(),
        });
        log('QA_INTERRUPT_READY job=$job frame=${s.currentFrame}');
        // Explicit termination must come from host, not from cooperative cancel.
        await Completer<void>().future;
        return;
      }
      if (s.state == 'completed' || s.state == 'failed') {
        log('QA_INTERRUPT_EARLY_TERMINAL=${statusRecord(s)}');
        await persist('qa_interrupt_start.json', {
          'pid': pid,
          'job_id': job,
          'error': statusRecord(s),
        });
        return;
      }
      await Future<void>.delayed(const Duration(milliseconds: 250));
    }
    log('QA_INTERRUPT_WAIT_TIMEOUT');
  }

  Future<void> checkInterrupted() async {
    log('QA_INTERRUPT_CHECK');
    final before =
        jsonDecode(
              await File('${docs.path}/qa_interrupt_start.json').readAsString(),
            )
            as Map<String, dynamic>;
    final status = await client.getJobStatus(before['job_id'] as String);
    final out = File('${outputs.path}/interrupted_4k.mp4');
    final partials = outputs
        .listSync()
        .whereType<File>()
        .where(
          (e) =>
              e.path.contains('interrupted_4k') &&
              e.path.contains('.partial.mp4'),
        )
        .map((e) => e.path)
        .toList();
    final result = <String, Object?>{
      'new_pid': pid,
      'previous_pid': before['pid'],
      'job_id': before['job_id'],
      'after_relaunch': statusRecord(status),
      'committed_output_exists': await out.exists(),
      'partial_paths': partials,
      'interruption_recovered':
          status.state == 'interrupted' &&
          status.errorCode == 'EXPORT_INTERRUPTED' &&
          status.outputUri == null &&
          !await out.exists(),
      'prior_completed_case_preserved': await File(
        '${outputs.path}/group_full_solid.mp4',
      ).exists(),
    };
    // Independent recovery dimension: even when an interrupted job ID is no
    // longer queryable after process death, test that a fresh import/export
    // succeeds and no interrupted FINAL output was mistakenly published.
    try {
      final source = 'solo-hd-7570258.mp4';
      final analyzed = await analyze(source, startMs: 1000);
      final ids = analyzed.persons.map((e) => e.id).toList();
      if (ids.isEmpty) {
        throw StateError('No detected person for recovery export');
      }
      final recovered = File(
        '${outputs.path}/fresh_export_after_termination.mp4',
      );
      if (await recovered.exists()) await recovered.delete();
      final newJob = await client.startExport(
        sourceUri: '${sources.path}/$source',
        analysisCacheId: analyzed.analysisCacheId,
        outputFilePath: recovered.path,
        selectedPersonIds: [],
        faceOnlyPersonIds: ids,
        effects: effect('solid'),
        targetWidth: 540,
        targetHeight: 1024,
        targetFps: analyzed.videoInfo.fps,
        videoBitrate: 4000000,
        trimStartMs: 1000,
        trimEndMs: 2600,
      );
      final state = await terminal(newJob, timeout: const Duration(minutes: 4));
      final size = await recovered.exists() ? await recovered.length() : 0;
      result['fresh_export_after_termination'] = {
        'job_id': newJob,
        'status': statusRecord(state),
        'file_bytes': size,
        'passed': state.state == 'completed' && size > 1024,
      };
    } catch (error) {
      result['fresh_export_after_termination'] = {
        'passed': false,
        'error': error.toString(),
      };
    }
    result['result'] =
        result['interruption_recovered'] == true &&
            (result['fresh_export_after_termination']
                    as Map<String, Object?>)['passed'] ==
                true
        ? 'passed'
        : 'failed';
    await persist('qa_interrupt_check.json', result);
    log(
      'QA_INTERRUPT_RESULT=${result['result']} state=${status.state} code=${status.errorCode} partials=${partials.length}',
    );
  }
}
