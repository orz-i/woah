import 'package:app/core/widgets/bottom_control_drawer.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('drawer handle supports vertical drag between snap states', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final controller = DraggableScrollableController();
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Stack(
            children: [
              Positioned.fill(
                child: BottomControlDrawer(
                  controller: controller,
                  minChildSize: 0.10,
                  initialChildSize: 0.30,
                  maxChildSize: 0.80,
                  snapSizes: const [0.10, 0.30, 0.80],
                  child: const Text('Controls'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(controller.size, closeTo(0.30, 0.01));

    final handle = find.byKey(const ValueKey('bottom_control_drawer_handle'));
    expect(handle, findsOneWidget);

    await tester.drag(handle, const Offset(0, -260));
    await tester.pumpAndSettle();
    expect(controller.size, closeTo(0.80, 0.02));

    await tester.drag(handle, const Offset(0, 520));
    await tester.pumpAndSettle();
    expect(controller.size, closeTo(0.10, 0.02));
  });
}
