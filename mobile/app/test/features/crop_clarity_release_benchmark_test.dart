import 'package:flutter_test/flutter_test.dart';
import 'package:app/features/export/presentation/export_controller.dart';

void main() {
  test('normal builds preserve auto crop-clarity scale on all platforms', () {
    expect(
      effectiveExportCropClarityScale(
        plannedScale: 2.0,
        isAndroid: true,
        benchmarkOff: false,
      ),
      2.0,
    );
    expect(
      effectiveExportCropClarityScale(
        plannedScale: 1.78,
        isAndroid: false,
        benchmarkOff: false,
      ),
      1.78,
    );
  });

  test('benchmark off only affects Android native export request', () {
    expect(
      effectiveExportCropClarityScale(
        plannedScale: 2.0,
        isAndroid: true,
        benchmarkOff: true,
      ),
      1.0,
    );
    expect(
      effectiveExportCropClarityScale(
        plannedScale: 2.0,
        isAndroid: false,
        benchmarkOff: true,
      ),
      2.0,
    );
  });
}
