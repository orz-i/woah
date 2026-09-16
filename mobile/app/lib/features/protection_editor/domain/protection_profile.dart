import 'package:dance_domain/dance_domain.dart';

import '../../person_selection/domain/person_selection_state.dart';

/// Reusable editor defaults that are safe to carry across unrelated videos.
///
/// Person identities and follow targets are intentionally excluded because they
/// belong to one analyzed video. A saved profile only carries project-wide
/// privacy/effect/output preferences.
class ProtectionProfile {
  final ProjectPrivacyMode privacyMode;
  final EffectConfig fullBodyEffects;
  final EffectConfig faceOnlyEffects;
  final OutputResolutionPreset outputResolutionPreset;
  final bool portraitReframe;

  const ProtectionProfile({
    required this.privacyMode,
    required this.fullBodyEffects,
    required this.faceOnlyEffects,
    required this.outputResolutionPreset,
    this.portraitReframe = false,
  });

  Map<String, dynamic> toJson() => {
    'privacyMode': privacyMode.name,
    'fullBodyEffects': fullBodyEffects.toJson(),
    'faceOnlyEffects': faceOnlyEffects.toJson(),
    'outputResolutionPreset': outputResolutionPreset.name,
    'portraitReframe': portraitReframe,
  };

  factory ProtectionProfile.fromJson(Map<String, dynamic> json) {
    final privacyModeName = json['privacyMode'] as String?;
    final outputResolutionName = json['outputResolutionPreset'] as String?;
    return ProtectionProfile(
      privacyMode: privacyModeName == ProjectPrivacyMode.faceOnly.name
          ? ProjectPrivacyMode.faceOnly
          : ProjectPrivacyMode.fullBody,
      fullBodyEffects: EffectConfig.fromJson(
        (json['fullBodyEffects'] as Map?)?.cast<String, dynamic>() ?? const {},
      ),
      faceOnlyEffects: EffectConfig.fromJson(
        (json['faceOnlyEffects'] as Map?)?.cast<String, dynamic>() ?? const {},
      ),
      outputResolutionPreset: OutputResolutionPreset.values.firstWhere(
        (value) => value.name == outputResolutionName,
        orElse: () => OutputResolutionPreset.source,
      ),
      portraitReframe: json['portraitReframe'] as bool? ?? false,
    );
  }
}
