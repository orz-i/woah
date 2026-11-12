import 'dart:ffi';
import 'dart:io';

/// Synchronous numerical calls only. Decoding, rendering and resources stay native.
/// Tests use the same host cdylib built by tools/rust/run.py, without a Dart oracle.
final class _TimeRange extends Struct {
  @Int64()
  external int start;
  @Int64()
  external int end;
  @Uint32()
  external int valid;
  @Uint32()
  external int reserved;
}

final class _TimeMap extends Struct {
  @Double()
  external double scale;
  @Int64()
  external int offset;
  @Uint32()
  external int valid;
  @Uint32()
  external int reserved;
}

DynamicLibrary _library() {
  final explicit = Platform.environment['FRAME_KERNEL_LIBRARY'];
  if (explicit != null) return DynamicLibrary.open(explicit);
  if (Platform.isIOS) return DynamicLibrary.process();
  if (Platform.isAndroid) {
    return DynamicLibrary.open('libframe_kernel_jni.so');
  }
  final name = Platform.isWindows
      ? 'frame_kernel.dll'
      : Platform.isMacOS
      ? 'libframe_kernel.dylib'
      : 'libframe_kernel.so';
  var directory = Directory.current.absolute;
  while (true) {
    final file = File(
      '${directory.path}/crates/frame-kernel/target/release/$name',
    );
    if (file.existsSync()) return DynamicLibrary.open(file.path);
    if (directory.parent.path == directory.path) break;
    directory = directory.parent;
  }
  throw StateError('Build FrameKernel with python tools/rust/run.py build');
}

final _core = _library();
final _value = _core
    .lookupFunction<
      Int64 Function(Uint32, Int64, Uint32, Double, Int64),
      int Function(int, int, int, double, int)
    >('frame_kernel_time_value');
final _inverse = _core
    .lookupFunction<
      _TimeMap Function(Double, Int64),
      _TimeMap Function(double, int)
    >('frame_kernel_time_inverse');
final _anchors = _core
    .lookupFunction<
      _TimeMap Function(Int64, Int64, Int64, Int64),
      _TimeMap Function(int, int, int, int)
    >('frame_kernel_time_anchors');
final _overlap = _core
    .lookupFunction<
      _TimeRange Function(Int64, Int64, Int64, Int64, Double, Int64),
      _TimeRange Function(int, int, int, int, double, int)
    >('frame_kernel_time_overlap');

abstract final class TimeKernel {
  static int map(int value, double scale, int offset, {bool inverse = false}) {
    final result = _value(inverse ? 1 : 0, value, 1000000, scale, offset);
    if (result == -9223372036854775808) {
      throw const FormatException('Invalid source time mapping');
    }
    return result;
  }

  static (int, double) inverse(double scale, int offset) {
    final result = _inverse(scale, offset);
    if (result.valid == 0) {
      throw const FormatException('Invalid inverse mapping');
    }
    return (result.offset, result.scale);
  }

  static (int, double) anchors(int r1, int t1, int r2, int t2) {
    final result = _anchors(r1, t1, r2, t2);
    if (result.valid == 0) throw const FormatException('Invalid music anchors');
    return (result.offset, result.scale);
  }

  static (int, int)? overlap(int re, int te, double scale, int offset) {
    final result = _overlap(0, re, 0, te, scale, offset);
    return result.valid == 0 ? null : (result.start, result.end);
  }
}
