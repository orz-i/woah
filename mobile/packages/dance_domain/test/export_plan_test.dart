import 'package:dance_domain/dance_domain.dart';
import 'package:test/test.dart';

void main() {
  DanceProject project({
    required int width,
    required int height,
    double fps = 30,
    FollowConfig follow = const FollowConfig(),
    OutputResolutionPreset resolution = OutputResolutionPreset.source,
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
    outputResolutionPreset: resolution,
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

  test('1080p landscape portrait crop restores clarity up to FHD', () {
    final plan = ExportPlan.forProject(
      project(
        width: 1920,
        height: 1080,
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 1,
          outputAspectRatio: 9 / 16,
        ),
      ),
      maxEncodeWidth: 3840,
      maxEncodeHeight: 2160,
    );

    expect((plan.width, plan.height), (1080, 1920));
    expect(plan.hasCropClarityRestoration, isTrue);
    expect(plan.cropClarityScale, closeTo(16 / 9, 0.001));
  });

  test(
    '720p landscape portrait crop never exceeds the 2x restoration bound',
    () {
      final plan = ExportPlan.forProject(
        project(
          width: 1280,
          height: 720,
          follow: const FollowConfig(
            enabled: true,
            targetPersonId: 1,
            outputAspectRatio: 9 / 16,
          ),
        ),
      );

      expect((plan.width, plan.height), (810, 1440));
      expect(plan.cropClarityScale, closeTo(2.0, 0.001));
    },
  );

  test(
    '4K portrait crop already has enough source pixels and skips restoration',
    () {
      final plan = ExportPlan.forProject(
        project(
          width: 3840,
          height: 2160,
          follow: const FollowConfig(
            enabled: true,
            targetPersonId: 1,
            outputAspectRatio: 9 / 16,
          ),
        ),
      );

      expect((plan.width, plan.height), (1206, 2144));
      expect(plan.hasCropClarityRestoration, isFalse);
      expect(plan.cropClarityScale, 1.0);
    },
  );

  test('HD portrait preference restores only to the HD envelope', () {
    final plan = ExportPlan.forProject(
      project(
        width: 1920,
        height: 1080,
        resolution: OutputResolutionPreset.hd,
        follow: const FollowConfig(
          enabled: true,
          targetPersonId: 1,
          outputAspectRatio: 9 / 16,
        ),
      ),
    );

    expect((plan.width, plan.height), (720, 1280));
    expect(plan.cropClarityScale, closeTo(32 / 27, 0.001));
  });

  test(
    'large follow zoom still respects the strict 2x restoration ceiling',
    () {
      final plan = ExportPlan.forProject(
        project(
          width: 1920,
          height: 1080,
          follow: const FollowConfig(
            enabled: true,
            targetPersonId: 1,
            outputAspectRatio: 9 / 16,
            zoom: 3,
          ),
        ),
      );

      expect((plan.width, plan.height), (396, 704));
      expect(plan.cropClarityScale, lessThanOrEqualTo(2.0));
      expect(plan.cropClarityScale, greaterThan(1.9));
    },
  );

  test(
    'encoder fallback can disable restoration when output is below crop pixels',
    () {
      final plan = ExportPlan.forProject(
        project(
          width: 1920,
          height: 1080,
          follow: const FollowConfig(
            enabled: true,
            targetPersonId: 1,
            outputAspectRatio: 9 / 16,
          ),
        ),
        maxEncodeWidth: 640,
        maxEncodeHeight: 360,
      );

      expect((plan.width, plan.height), (360, 640));
      expect(plan.hasCropClarityRestoration, isFalse);
      expect(plan.fallbackReason, ExportFallbackReason.encoderDimensionLimit);
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

  test('FHD caps 4K without upscaling smaller sources', () {
    final fourK = ExportPlan.forProject(
      project(
        width: 3840,
        height: 2160,
        resolution: OutputResolutionPreset.fhd,
      ),
    );
    expect(fourK.width, 1920);
    expect(fourK.height, 1080);
    expect(fourK.resolutionPreset, OutputResolutionPreset.fhd);

    final alreadyHd = ExportPlan.forProject(
      project(width: 1280, height: 720, resolution: OutputResolutionPreset.fhd),
    );
    expect(alreadyHd.width, 1280);
    expect(alreadyHd.height, 720);
  });

  test('HD caps 16:9 4K to 1280x720', () {
    final plan = ExportPlan.forProject(
      project(width: 3840, height: 2160, resolution: OutputResolutionPreset.hd),
    );
    expect(plan.width, 1280);
    expect(plan.height, 720);
  });

  test('FHD fits non-16:9 material inside the standard envelope', () {
    final fourThree = ExportPlan.forProject(
      project(
        width: 3840,
        height: 2880,
        resolution: OutputResolutionPreset.fhd,
      ),
    );
    final square = ExportPlan.forProject(
      project(
        width: 2160,
        height: 2160,
        resolution: OutputResolutionPreset.fhd,
      ),
    );

    expect((fourThree.width, fourThree.height), (1440, 1080));
    expect((square.width, square.height), (1080, 1080));
  });

  test('9:16 FHD and HD keep exact portrait ratios', () {
    const follow = FollowConfig(
      enabled: true,
      targetPersonId: 1,
      outputAspectRatio: 9 / 16,
    );
    final fhd = ExportPlan.forProject(
      project(
        width: 3840,
        height: 2160,
        follow: follow,
        resolution: OutputResolutionPreset.fhd,
      ),
    );
    final hd = ExportPlan.forProject(
      project(
        width: 3840,
        height: 2160,
        follow: follow,
        resolution: OutputResolutionPreset.hd,
      ),
    );

    expect((fhd.width, fhd.height), (1080, 1920));
    expect((hd.width, hd.height), (720, 1280));
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
