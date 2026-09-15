import 'dart:convert';
import 'package:dance_domain/dance_domain.dart';
import 'package:test/test.dart';

void main() {
  DanceProject project(int width, int height) => DanceProject(
    id: 'reframe',
    sourceUri: '/dance.mp4',
    videoInfo: VideoInfo(
      codedWidth: width,
      codedHeight: height,
      displayWidth: width,
      displayHeight: height,
      fps: 30,
      durationMs: 10000,
      rotation: 0,
      videoCodec: 'h264',
      hasAudio: true,
    ),
    createdAt: DateTime.utc(2026),
    updatedAt: DateTime.utc(2026),
    follow: const FollowConfig(
      enabled: true,
      targetPersonId: 1,
      outputAspectRatio: 9 / 16,
    ),
  );

  test('old follow JSON retains legacy source-aspect semantics', () {
    final follow = FollowConfig.fromJson({
      'enabled': true,
      'targetPersonId': 1,
    });
    expect(follow.outputAspectRatio, isNull);
    expect(follow.zoom, 1);
  });

  test('portrait follow survives project JSON round trip', () {
    final restored = DanceProject.fromJson(
      jsonDecode(jsonEncode(project(1920, 1080).toJson()))
          as Map<String, dynamic>,
    );
    expect(restored.follow.targetPersonId, 1);
    expect(restored.outputAspectRatio, 9 / 16);
    expect(restored.outputSize, (width: 594, height: 1056));
    expect(restored.videoInfo.width, 1920);
  });

  test(
    'portrait dimensions are even, exact 9:16, bounded and not enlarged',
    () {
      for (final size in [
        (1920, 1080),
        (3840, 2160),
        (720, 1280),
        (1080, 1920),
        (1080, 1080),
        (480, 1280),
      ]) {
        final output = project(size.$1, size.$2).outputSize;
        expect(output.width % 2, 0);
        expect(output.height % 2, 0);
        expect(output.width * 16, output.height * 9);
        expect(output.width, lessThanOrEqualTo(size.$1));
        expect(output.height, lessThanOrEqualTo(size.$2));
        expect(output.height, lessThanOrEqualTo(1920));
      }
    },
  );

  test(
    'disabling follow restores the original output and does not change privacy',
    () {
      final initial = project(
        1920,
        1080,
      ).copyWith(selectedPersonIds: {2}, faceOnlyPersonIds: {3});
      final disabled = initial.copyWith(
        follow: initial.follow.copyWith(enabled: false),
      );
      expect(disabled.outputSize, (width: 1920, height: 1080));
      expect(disabled.outputAspectRatio, 16 / 9);
      expect(disabled.selectedPersonIds, {2});
      expect(disabled.faceOnlyPersonIds, {3});
    },
  );

  test('unknown follow identity is not a valid export target', () {
    expect(project(1920, 1080).hasFollowTarget, isFalse);
  });
}
