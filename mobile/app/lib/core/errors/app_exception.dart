/// Base application exception
class AppException implements Exception {
  final String message;
  final String? code;
  final dynamic details;

  const AppException(this.message, {this.code, this.details});

  @override
  String toString() =>
      'AppException(code: $code, message: $message, details: $details)';
}

class NativeException extends AppException {
  const NativeException(super.message, {super.code, super.details});
}
