import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'dance_native_platform_interface.dart';

/// An implementation of [DanceNativePlatform] that uses method channels.
class MethodChannelDanceNative extends DanceNativePlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('dance_native');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>(
      'getPlatformVersion',
    );
    return version;
  }
}
