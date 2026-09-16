import 'package:app/features/person_selection/domain/person_selection_state.dart';
import 'package:app/features/protection_editor/data/protection_profile_store.dart';
import 'package:app/features/protection_editor/domain/protection_profile.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  test('local Profile round-trips reusable editor preferences', () async {
    final store = SharedPreferencesProtectionProfileStore();
    const profile = ProtectionProfile(
      privacyMode: ProjectPrivacyMode.faceOnly,
      fullBodyEffects: EffectConfig(
        fillMode: FillMode.mosaic,
        blurStrength: 8,
        opacity: .72,
      ),
      faceOnlyEffects: EffectConfig(
        fillMode: FillMode.sticker,
        faceStickerEnabled: true,
        stickerAssetId: 'builtin:panda',
        stickerScale: 1.4,
      ),
      outputResolutionPreset: OutputResolutionPreset.fhd,
      portraitReframe: true,
    );

    await store.save(profile);
    final restored = await store.load();

    expect(restored, isNotNull);
    expect(restored!.privacyMode, ProjectPrivacyMode.faceOnly);
    expect(restored.fullBodyEffects.fillMode, FillMode.mosaic);
    expect(restored.fullBodyEffects.opacity, .72);
    expect(restored.faceOnlyEffects.fillMode, FillMode.sticker);
    expect(restored.faceOnlyEffects.stickerAssetId, 'builtin:panda');
    expect(restored.faceOnlyEffects.stickerScale, 1.4);
    expect(restored.outputResolutionPreset, OutputResolutionPreset.fhd);
    expect(restored.portraitReframe, isTrue);
  });
}
