import 'package:flutter_test/flutter_test.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('DanceNative DTO Mappers', () {
    test('VideoInfoDto toDomain mapper', () {
      final dto = VideoInfoDto(
        codedWidth: 1920,
        codedHeight: 1080,
        displayWidth: 1080,
        displayHeight: 1920,
        fps: 60.0,
        durationMs: 4000,
        rotation: 90,
        videoCodec: 'video/avc',
        audioCodec: 'audio/mp4a-latm',
        hasAudio: true,
      );

      final domain = dto.toDomain();
      expect(domain.codedWidth, 1920);
      expect(domain.codedHeight, 1080);
      expect(domain.displayWidth, 1080);
      expect(domain.displayHeight, 1920);
      expect(domain.width, 1080);
      expect(domain.height, 1920);
      expect(domain.fps, 60.0);
      expect(domain.rotation, 90);
      expect(domain.hasAudio, isTrue);
    });

    test('DetectedPersonDto toDomain mapper', () {
      final dto = DetectedPersonDto(
        id: 0,
        x1: 0.1,
        y1: 0.2,
        x2: 0.4,
        y2: 0.8,
        thumbnailPath: '/tmp/thumb_0.webp',
        confidence: 0.95,
      );

      final person = dto.toDomain(selected: true);
      expect(person.id, 0);
      expect(person.normalizedInitialBox.left, 0.1);
      expect(person.normalizedInitialBox.top, 0.2);
      expect(person.normalizedInitialBox.right, 0.4);
      expect(person.normalizedInitialBox.bottom, 0.8);
      expect(person.confidence, 0.95);
      expect(person.selected, isTrue);
    });

    test('EffectConfig toDto mapper', () {
      const config = EffectConfig(
        fillMode: FillMode.solid,
        fillColorArgb: 0xFF000000,
        borderColorArgb: 0xFF00FF00,
        opacity: 0.8,
        borderWidth: 2.5,
        blurStrength: 10.0,
      );

      final dto = config.toDto();
      expect(dto.fillMode, 'solid');
      expect(dto.fillColorArgb, 0xFF000000);
      expect(dto.borderColorArgb, 0xFF00FF00);
      expect(dto.opacity, 0.8);
      expect(dto.borderWidth, 2.5);
    });
  });
}
