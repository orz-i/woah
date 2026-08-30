import 'package:dance_domain/dance_domain.dart';
import 'package:test/test.dart';

void main() {
  group('DanceDomain Model Tests', () {
    test('NormalizedRect calculations', () {
      const rect = NormalizedRect(left: 0.1, top: 0.2, right: 0.5, bottom: 0.8);
      expect(rect.width, closeTo(0.4, 1e-5));
      expect(rect.height, closeTo(0.6, 1e-5));
      expect(rect.centerX, closeTo(0.3, 1e-5));
      expect(rect.centerY, closeTo(0.5, 1e-5));
    });

    test('VideoInfo display resolution with rotation', () {
      const info = VideoInfo(
        codedWidth: 1920,
        codedHeight: 1080,
        displayWidth: 1080,
        displayHeight: 1920,
        fps: 30.0,
        durationMs: 5000,
        rotation: 90,
        videoCodec: 'h264',
        audioCodec: 'aac',
        hasAudio: true,
      );

      expect(info.width, equals(1080));
      expect(info.height, equals(1920));
      expect(info.aspectRatio, closeTo(1080 / 1920, 1e-5));
    });

    test('DanceProject serialization and deserialization', () {
      final now = DateTime.now().toUtc();
      final project = DanceProject(
        id: 'proj-123',
        sourceUri: '/path/to/video.mp4',
        videoInfo: const VideoInfo(
          codedWidth: 1920,
          codedHeight: 1080,
          displayWidth: 1920,
          displayHeight: 1080,
          fps: 30.0,
          durationMs: 10000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: true,
        ),
        persons: const [
          PersonTrack(
            id: 0,
            normalizedInitialBox: NormalizedRect(left: 0.1, top: 0.1, right: 0.4, bottom: 0.9),
            thumbnailPath: '/path/thumb0.webp',
            confidence: 0.92,
            selected: true,
          ),
          PersonTrack(
            id: 1,
            normalizedInitialBox: NormalizedRect(left: 0.5, top: 0.1, right: 0.8, bottom: 0.9),
            thumbnailPath: '/path/thumb1.webp',
            confidence: 0.88,
            selected: false,
          ),
        ],
        selectedPersonIds: {0},
        faceOnlyPersonIds: {1},
        effects: const EffectConfig(
          fillMode: FillMode.blur,
          blurStrength: 20.0,
          borderColorArgb: 0xFF00FF00,
          borderWidth: 4.0,
        ),
        createdAt: now,
        updatedAt: now,
      );

      final json = project.toJson();
      final reconstructed = DanceProject.fromJson(json);

      expect(reconstructed.id, equals(project.id));
      expect(reconstructed.persons.length, equals(2));
      expect(reconstructed.selectedPersonIds, contains(0));
      expect(reconstructed.faceOnlyPersonIds, contains(1));
      expect(
        reconstructed.privacyModeForPerson(0),
        equals(PersonPrivacyMode.fullBody),
      );
      expect(
        reconstructed.privacyModeForPerson(1),
        equals(PersonPrivacyMode.faceOnly),
      );
      expect(reconstructed.privacyTargetIds, equals({0, 1}));
      expect(reconstructed.effects.fillMode, equals(FillMode.blur));
    });

    test('DanceProject legacy JSON defaults face-only to empty', () {
      final now = DateTime.now().toUtc();
      final json = DanceProject(
        id: 'legacy',
        sourceUri: '/legacy.mp4',
        videoInfo: const VideoInfo(
          codedWidth: 640,
          codedHeight: 480,
          displayWidth: 640,
          displayHeight: 480,
          fps: 30.0,
          durationMs: 1000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: false,
        ),
        selectedPersonIds: const {3},
        createdAt: now,
        updatedAt: now,
      ).toJson()
        ..remove('faceOnlyPersonIds');

      final reconstructed = DanceProject.fromJson(json);
      expect(reconstructed.faceOnlyPersonIds, isEmpty);
      expect(reconstructed.privacyModeForPerson(3), PersonPrivacyMode.fullBody);
    });

    test('DanceProject full body wins face-only conflict', () {
      final now = DateTime.now().toUtc();
      final project = DanceProject(
        id: 'conflict',
        sourceUri: '/conflict.mp4',
        videoInfo: const VideoInfo(
          codedWidth: 640,
          codedHeight: 480,
          displayWidth: 640,
          displayHeight: 480,
          fps: 30.0,
          durationMs: 1000,
          rotation: 0,
          videoCodec: 'h264',
          hasAudio: false,
        ),
        selectedPersonIds: const {4},
        faceOnlyPersonIds: const {4, 5},
        createdAt: now,
        updatedAt: now,
      );

      expect(project.privacyModeForPerson(4), PersonPrivacyMode.fullBody);
      expect(project.privacyModeForPerson(5), PersonPrivacyMode.faceOnly);
      expect(project.privacyTargetIds, equals({4, 5}));
    });
  });
}
