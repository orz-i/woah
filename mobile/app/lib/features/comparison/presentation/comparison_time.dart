String comparisonTime(int value) {
  final ms = value ~/ 1000;
  return '${(ms ~/ 60000).toString().padLeft(2, '0')}:${((ms ~/ 1000) % 60).toString().padLeft(2, '0')}.${(ms % 1000).toString().padLeft(3, '0')}';
}

String comparisonPreciseTime(int value) {
  if (value % 1000 == 0) return comparisonTime(value);
  return '${(value ~/ 60000000).toString().padLeft(2, '0')}:'
      '${((value ~/ 1000000) % 60).toString().padLeft(2, '0')}.'
      '${(value % 1000000).toString().padLeft(6, '0')}';
}

int parseComparisonTime(String text) {
  final match = RegExp(
    r'^(?:(\d+):)?(\d+)(?:\.(\d{1,6}))?$',
  ).firstMatch(text.trim());
  if (match == null) throw const FormatException('请输入秒或分:秒，最多六位小数');
  final minutes = int.tryParse(match[1] ?? '0');
  final seconds = int.tryParse(match[2]!);
  if (minutes == null ||
      seconds == null ||
      (match[1] != null && seconds >= 60)) {
    throw const FormatException('时间格式不正确');
  }
  final value =
      (minutes * 60 + seconds) * 1000000 +
      int.parse((match[3] ?? '').padRight(6, '0'));
  if (value < 0 || value > 86400000000) throw const FormatException('时间超出视频范围');
  return value;
}
