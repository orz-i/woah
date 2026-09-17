import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../protection_editor/presentation/protection_editor_screen.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';
import 'woah_easter_egg_screen.dart';

class ImportVideoScreen extends ConsumerStatefulWidget {
  const ImportVideoScreen({super.key});

  @override
  ConsumerState<ImportVideoScreen> createState() => _ImportVideoScreenState();
}

class _ImportVideoScreenState extends ConsumerState<ImportVideoScreen> {
  Future<void> _pickVideoAndContinue() async {
    HapticFeedback.mediumImpact();
    final controller = ref.read(importVideoControllerProvider.notifier);
    await controller.pickAndProbeVideo();
    if (!mounted) return;

    final project = controller.createProject();
    if (project == null) return;

    await context.push(
      '/protection_editor',
      extra: ProtectionEditorArgs(project: project),
    );
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
        statusBarIconBrightness: Brightness.light,
        statusBarBrightness: Brightness.dark,
        systemNavigationBarColor: AppTheme.flowBackground,
        systemNavigationBarIconBrightness: Brightness.light,
        systemNavigationBarDividerColor: Colors.transparent,
        systemStatusBarContrastEnforced: false,
        systemNavigationBarContrastEnforced: false,
      ),
      child: Scaffold(
        backgroundColor: AppTheme.flowBackground,
        body: SafeArea(
          child: Stack(
            children: [
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
                            child: _ErrorNotice(message: state.errorMessage!),
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
            key: const ValueKey('media-picker-card'),
            width: width,
            height: width / 0.75,
            decoration: BoxDecoration(
              gradient: AppTheme.mediaPickerGradient,
              borderRadius: BorderRadius.circular(22),
              border: Border.all(color: AppTheme.flowBorder),
              boxShadow: const [
                BoxShadow(
                  color: Color(0x33000000),
                  blurRadius: 24,
                  offset: Offset(0, 12),
                ),
              ],
            ),
            clipBehavior: Clip.antiAlias,
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 54,
                    height: 54,
                    decoration: BoxDecoration(
                      color: AppTheme.flowSurfaceSoft,
                      shape: BoxShape.circle,
                      border: Border.all(color: AppTheme.flowBorder),
                    ),
                    child: Center(
                      child: isBusy
                          ? const SizedBox(
                              width: 22,
                              height: 22,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: AppTheme.coral,
                              ),
                            )
                          : const Icon(
                              Icons.add_rounded,
                              color: AppTheme.coral,
                              size: 30,
                            ),
                    ),
                  ),
                  const SizedBox(height: 14),
                  Text(
                    label,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: AppTheme.flowTextPrimary,
                      fontSize: 14,
                      height: 1.2,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0.2,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    isBusy ? '请稍候' : '从设备中选择一个视频',
                    style: const TextStyle(
                      color: AppTheme.flowTextMuted,
                      fontSize: 11.5,
                      fontWeight: FontWeight.w400,
                    ),
                  ),
                ],
              ),
            ),
          ),
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
                color: AppTheme.flowTextSecondary,
                fontSize: 15,
                fontWeight: FontWeight.w500,
                letterSpacing: 5.2,
              ),
            ),
            SizedBox(height: 8),
            Text(
              '记录每一个舞动瞬间',
              style: TextStyle(
                color: AppTheme.flowTextMuted,
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

class _ErrorNotice extends StatelessWidget {
  final String message;

  const _ErrorNotice({required this.message});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: AppTheme.flowSurfaceSoft,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.coralStrong.withAlpha(88)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.error_outline_rounded,
            color: AppTheme.coralStrong,
            size: 18,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(
                color: AppTheme.flowTextSecondary,
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
