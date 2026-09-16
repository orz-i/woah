import 'package:dance_domain/dance_domain.dart';
import 'package:test/test.dart';

void main() {
  DanceProject project({
    required int width,
    required int height,
    double fps = 30,
    FollowConfig follow = const FollowConfig(),
  }) => DanceProject(
    id: 'export-plan',
    sourceUri: '/dance.mp4',
    videoInfo: VideoInfo(
      codedWidth: width,
      codedHeight: height,
      displayWidth: width,
      displayHeight: height,
      fps: fps,
      durationMs: 10000,
      rotation: 0,
      videoCodec: 'h264',
      hasAudio: true,
    ),
    createdAt: DateTime.utc(2026),
    updatedAt: DateTime.utc(2026),
    follow: follow,
  );

  test('preserves 4K source geometry when encoder capability allows it', () {
    final plan = ExportPlan.forProject(
      project(width: 3840, height: 2160, fps: 59.94),
      maxEncodeWidth: 3840,
      maxEncodeHeight: 2160,
    );

    expect(plan.width, 3840);
    expect(plan.height, 2160);
    expect(plan.nominalFps, 59.94);
    expect(plan.timingPolicy, ExportTimingPolicy.preserveSourcePts);
    expect(plan.fallbackReason, isNull);
    expect(plan.videoBitrate, greaterThan(8_000_000));
  });

  test(
    'applies one explicit capability fallback instead of native hidden caps',
    () {
      final plan = ExportPlan.forProject(
        project(width: 3840, height: 2160, fps: 60),
        maxEncodeWidth: 1920,
        maxEncodeHeight: 1080,
      );

      expect(plan.width, 1920);
      expect(plan.height, 1080);
      expect(plan.nominalFps, 60);
      expect(plan.fallbackReason, ExportFallbackReason.encoderDimensionLimit);
    },
  );

  test(
    'treats landscape capability pair as portrait capability when needed',
    () {
      final plan = ExportPlan.forProject(
        project(width: 2160, height: 3840, fps: 30),
        maxEncodeWidth: 3840,
        maxEncodeHeight: 2160,
      );

      expect(plan.width, 2160);
      expect(plan.height, 3840);
      expect(plan.fallbackReason, isNull);
    },
  );

  test(
    'portrait reframe keeps source-derived pixels beyond the old 1920 cap',
    () {
      final plan = ExportPlan.forProject(
        project(
          width: 3840,
          height: 2160,
          fps: 30,
          follow: const FollowConfig(
            enabled: true,
            targetPersonId: 1,
            outputAspectRatio: 9 / 16,
          ),
        ),
        maxEncodeWidth: 3840,
        maxEncodeHeight: 2160,
      );

      expect(plan.width, 1206);
      expect(plan.height, 2144);
      expect(plan.width * 16, plan.height * 9);
      expect(plan.fallbackReason, isNull);
    },
  );

  test('9:16 capability fallback keeps an exact ratio', () {
    final plan = ExportPlan.forProject(
      project(
        width: 3840,
        height: 2160,
        fps: 60,
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 1,
          outputAspectRatio: 9 / 16,
        ),
      ),
      maxEncodeWidth: 1920,
      maxEncodeHeight: 1080,
    );

    expect(plan.width, 1080);
    expect(plan.height, 1920);
    expect(plan.width * 16, plan.height * 9);
    expect(plan.fallbackReason, ExportFallbackReason.encoderDimensionLimit);
  });

  test('normalizes odd source geometry downward and never enlarges it', () {
    final plan = ExportPlan.forProject(project(width: 1921, height: 1081));
    expect(plan.width, 1920);
    expect(plan.height, 1080);
  });

  test('falls back to 30 fps only when source fps is unusable', () {
    final plan = ExportPlan.forProject(
      project(width: 1280, height: 720, fps: 0),
    );
    expect(plan.nominalFps, 30);
  });
}
