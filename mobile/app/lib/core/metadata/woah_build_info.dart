import 'package:flutter/services.dart';

class WoahBuildInfo {
  static const _channel = MethodChannel('com.danceanon.app/build_info');
  static const authorName = 'CJ';
  static const _fallbackVersionName = String.fromEnvironment(
    'WOAH_APP_VERSION',
    defaultValue: '1.0.0',
  );
  static const _fallbackBuildNumber = String.fromEnvironment(
    'WOAH_BUILD_NUMBER',
    defaultValue: '1',
  );
  static const _fallbackCommit = String.fromEnvironment(
    'WOAH_GIT_COMMIT',
    defaultValue: 'development',
  );
  static const _fallbackBuildType = String.fromEnvironment(
    'WOAH_BUILD_TYPE',
    defaultValue: 'flutter',
  );

  final String versionName;
  final String buildNumber;
  final String gitCommit;
  final String buildType;

  const WoahBuildInfo({
    required this.versionName,
    required this.buildNumber,
    required this.gitCommit,
    required this.buildType,
  });

  String get shortCommit {
    if (gitCommit.length <= 12) return gitCommit;
    return gitCommit.substring(0, 12);
  }

  static Future<WoahBuildInfo> load() async {
    var versionName = _fallbackVersionName;
    var buildNumber = _fallbackBuildNumber;
    var gitCommit = _fallbackCommit;
    var buildType = _fallbackBuildType;

    try {
      final raw = await _channel.invokeMapMethod<String, dynamic>(
        'getBuildInfo',
      );
      if (raw != null) {
        versionName = _nonEmpty(raw['versionName']) ?? versionName;
        buildNumber = _nonEmpty(raw['versionCode']?.toString()) ?? buildNumber;
        gitCommit = _nonEmpty(raw['gitCommit']) ?? gitCommit;
        buildType = _nonEmpty(raw['buildType']) ?? buildType;
      }
    } on MissingPluginException {
      // Non-Android/test environments use compile-time fallbacks.
    } on PlatformException {
      // Build metadata is decorative; keep the easter egg available even when
      // the native bridge is unavailable.
    }

    return WoahBuildInfo(
      versionName: versionName,
      buildNumber: buildNumber,
      gitCommit: gitCommit,
      buildType: buildType,
    );
  }

  static String? _nonEmpty(dynamic value) {
    if (value is! String) return null;
    final normalized = value.trim();
    return normalized.isEmpty ? null : normalized;
  }
}
