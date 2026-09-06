import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';
import 'woah_easter_egg_screen.dart';

class ImportVideoScreen extends ConsumerStatefulWidget {
  const ImportVideoScreen({super.key});

  @override
  ConsumerState<ImportVideoScreen> createState() => _ImportVideoScreenState();
}

class _ImportVideoScreenState extends ConsumerState<ImportVideoScreen> {
  void _closeApp() {
    HapticFeedback.lightImpact();
    SystemNavigator.pop();
  }

  Future<void> _pickVideoAndContinue() async {
    HapticFeedback.mediumImpact();
    final controller = ref.read(importVideoControllerProvider.notifier);
    await controller.pickAndProbeVideo();
    if (!mounted) return;

    final project = controller.createProject();
    if (project == null) return;

    await context.push('/trim_video', extra: project);
    if (!mounted) return;
    controller.reset();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(importVideoControllerProvider);
    final isBusy =
        state.status == VideoImportStatus.picking ||
        state.status == VideoImportStatus.probing;

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.dark,
        statusBarBrightness: Brightness.light,
        systemNavigationBarColor: AppTheme.warmBackground,
        systemNavigationBarIconBrightness: Brightness.dark,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: AppTheme.warmBackground,
        body: SafeArea(
          child: Stack(
            children: [
              Positioned(
                top: 10,
                left: 18,
                child: _CloseButton(onPressed: _closeApp),
              ),
              Positioned.fill(
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    final cardWidth = (constraints.maxWidth * 0.60).clamp(
                      210.0,
                      340.0,
                    );

                    return Stack(
                      children: [
                        Align(
                          alignment: const Alignment(0, -0.02),
                          child: _DanceClipImportCard(
                            width: cardWidth,
                            isBusy: isBusy,
                            status: state.status,
                            onTap: isBusy ? null : _pickVideoAndContinue,
                          ),
                        ),
                        if (state.errorMessage != null)
                          Positioned(
                            left: 36,
                            right: 36,
                            bottom: 138,
                            child: _LightErrorNotice(
                              message: state.errorMessage!,
                            ),
                          ),
                        Positioned(
                          left: 0,
                          right: 0,
                          bottom: 28,
                          child: _ImportBrandSignature(
                            onLongPress: () {
                              HapticFeedback.lightImpact();
                              Navigator.of(context).push(
                                MaterialPageRoute<void>(
                                  fullscreenDialog: true,
                                  builder: (_) => const WoahEasterEggScreen(),
                                ),
                              );
                            },
                          ),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DanceClipImportCard extends StatelessWidget {
  final double width;
  final bool isBusy;
  final VideoImportStatus status;
  final VoidCallback? onTap;

  const _DanceClipImportCard({
    required this.width,
    required this.isBusy,
    required this.status,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final label = switch (status) {
      VideoImportStatus.picking => '正在导入舞段…',
      VideoImportStatus.probing => '正在读取舞段…',
      _ => '导入舞段',
    };

    return Semantics(
      button: !isBusy,
      enabled: !isBusy,
      label: label,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 180),
          opacity: isBusy ? 0.92 : 1,
          child: Container(
            width: width,
            height: width / 0.75,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(22),
              boxShadow: const [
                BoxShadow(
                  color: Color(0x16000000),
                  blurRadius: 28,
                  offset: Offset(0, 14),
                ),
              ],
            ),
            clipBehavior: Clip.antiAlias,
            child: Stack(
              fit: StackFit.expand,
              children: [
                const DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: AppTheme.importCardGradient,
                  ),
                ),
                const DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: RadialGradient(
                      center: Alignment(-0.86, 0.98),
                      radius: 0.88,
                      colors: [Color(0xAA780524), Color(0x00780524)],
                      stops: [0.0, 1.0],
                    ),
                  ),
                ),
                const DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: RadialGradient(
                      center: Alignment(0.82, 0.72),
                      radius: 0.82,
                      colors: [Color(0x55FF726B), Color(0x00FF726B)],
                      stops: [0.0, 1.0],
                    ),
                  ),
                ),
                Align(
                  alignment: const Alignment(0, -0.10),
                  child: isBusy
                      ? const SizedBox(
                          width: 28,
                          height: 28,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(
                          Icons.add_rounded,
                          color: Colors.white,
                          size: 36,
                          shadows: [
                            Shadow(
                              color: Color(0x28000000),
                              blurRadius: 5,
                              offset: Offset(0, 1),
                            ),
                          ],
                        ),
                ),
                Positioned(
                  left: 16,
                  right: 16,
                  bottom: 20,
                  child: Text(
                    label,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 14,
                      height: 1.2,
                      fontWeight: FontWeight.w400,
                      letterSpacing: 0.6,
                      shadows: [
                        Shadow(
                          color: Color(0x22000000),
                          blurRadius: 4,
                          offset: Offset(0, 1),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _CloseButton extends StatelessWidget {
  final VoidCallback onPressed;

  const _CloseButton({required this.onPressed});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 48,
      height: 48,
      child: IconButton(
        tooltip: '关闭',
        onPressed: onPressed,
        padding: EdgeInsets.zero,
        icon: const Icon(
          Icons.close_rounded,
          size: 28,
          color: Color(0xFF161616),
        ),
      ),
    );
  }
}

class _ImportBrandSignature extends StatelessWidget {
  final VoidCallback onLongPress;

  const _ImportBrandSignature({required this.onLongPress});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onLongPress: onLongPress,
      child: const Padding(
        padding: EdgeInsets.symmetric(horizontal: 24, vertical: 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Woah',
              style: TextStyle(
                color: Color(0xFFB0ACAA),
                fontSize: 15,
                fontWeight: FontWeight.w500,
                letterSpacing: 5.2,
              ),
            ),
            SizedBox(height: 8),
            Text(
              '记录每一个舞动瞬间',
              style: TextStyle(
                color: Color(0xFFBDB9B7),
                fontSize: 11,
                fontWeight: FontWeight.w400,
                letterSpacing: 3.0,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LightErrorNotice extends StatelessWidget {
  final String message;

  const _LightErrorNotice({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF5F3),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: const Color(0x33B93438)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.error_outline_rounded,
            color: Color(0xFFB93438),
            size: 18,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(
                color: Color(0xFF7A4648),
                fontSize: 12,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
