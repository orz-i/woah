import 'dart:convert';
import 'dart:io';
import 'package:dance_domain/dance_domain.dart';
import 'package:test/test.dart';

void main() {
  test('Dart consumes the frozen C ABI source-time contract', () {
    var root = Directory.current.absolute;
    while (!File(
      '${root.path}/testdata/comparison/timeline-v1.json',
    ).existsSync()) {
      if (root.parent.path == root.path) fail('Cannot locate timeline fixture');
      root = root.parent;
    }
    final fixture =
        jsonDecode(
              File(
                '${root.path}/testdata/comparison/timeline-v1.json',
              ).readAsStringSync(),
            )
            as Map;
    for (final c in fixture['map'] as List) {
      final m = ComparisonMusicMapping(
        c['offset'] as int,
        (c['scale'] as num).toDouble(),
      );
      expect(m.target(c['reference'] as int), c['target']);
    }
    for (final c in fixture['overlap'] as List) {
      final m = ComparisonMusicMapping(
        c['offset'] as int,
        (c['scale'] as num).toDouble(),
      );
      final r = m.overlap(c['reference'][1] as int, c['target'][1] as int);
      // Domain sources start at zero; native interval-start cases use the ABI gate.
      if (c['reference'][0] == 0 && c['target'][0] == 0) {
        expect(r == null ? null : [r.$1, r.$2], c['expected']);
      }
    }
  });
}
