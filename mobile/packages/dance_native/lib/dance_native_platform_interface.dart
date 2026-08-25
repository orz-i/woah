import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'dance_native_method_channel.dart';

abstract class DanceNativePlatform extends PlatformInterface {
  /// Constructs a DanceNativePlatform.
  DanceNativePlatform() : super(token: _token);

  static final Object _token = Object();

  static DanceNativePlatform _instance = MethodChannelDanceNative();

  /// The default instance of [DanceNativePlatform] to use.
  ///
  /// Defaults to [MethodChannelDanceNative].
  static DanceNativePlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [DanceNativePlatform] when
  /// they register themselves.
  static set instance(DanceNativePlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
