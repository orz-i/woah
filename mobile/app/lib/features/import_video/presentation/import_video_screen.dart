import 'package:dance_native/dance_native.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/theme.dart';
import '../../../repositories/native_processing_repository.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';

final capabilitiesProvider = FutureProvider<NativeCapabilitiesDto>((ref) async {
  final repo = ref.watch(nativeRepositoryProvider);
  return repo.getCapabilities();
});

class ImportVideoScreen extends ConsumerStatefulWidget {
  const ImportVideoScreen({super.key});

  @override
  ConsumerState<ImportVideoScreen> createState() => _ImportVideoScreenState();
}

class _ImportVideoScreenState extends ConsumerState<ImportVideoScreen> {
  static const _darkWorkspaceSystemUiStyle = SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    statusBarBrightness: Brightness.dark,
    systemNavigationBarColor: AppTheme.background,
    systemNavigationBarIconBrightness: Brightness.light,
    systemNavigationBarDividerColor: Colors.transparent,
    systemStatusBarContrastEnforced: false,
    systemNavigationBarContrastEnforced: false,
  );

  @override
  void initState() {
    super.initState();
    _enterImportImmersiveMode();
  }

  @override
  void dispose() {
    _restoreWorkspaceSystemUi();
    super.dispose();
  }

  Future<void> _enterImportImmersiveMode() async {
    await SystemChrome.setEnabledSystemUIMode(
      SystemUiMode.manual,
      overlays: const [SystemUiOverlay.bottom],
    );
  }

  Future<void> _restoreWorkspaceSystemUi() async {
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    SystemChrome.setSystemUIOverlayStyle(_darkWorkspaceSystemUiStyle);
  }

  Future<void> _closeApp() async {
    HapticFeedback.lightImpact();
    await _restoreWorkspaceSystemUi();
    SystemNavigator.pop();
  }

  Future<void> _pickVideoAndContinue() async {
    HapticFeedback.mediumImpact();
    final controller = ref.read(importVideoControllerProvider.notifier);
    await controller.pickAndProbeVideo();
    if (!mounted) return;

    final project = controller.createProject();
    if (project == null) return;

    await _restoreWorkspaceSystemUi();
    if (!mounted) return;
    await context.push('/trim_video', extra: project);
    if (!mounted) return;
    controller.reset();
    await _enterImportImmersiveMode();
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
        endDrawer: _buildSystemInfoDrawer(),
        body: Builder(
          builder: (scaffoldContext) => SafeArea(
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
                                Scaffold.of(scaffoldContext).openEndDrawer();
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
      ),
    );
  }

  Widget _buildSystemInfoDrawer() {
    final capsAsync = ref.watch(capabilitiesProvider);
    return Drawer(
      backgroundColor: AppTheme.surface,
      width: MediaQuery.sizeOf(context).width * 0.84,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 20, 22, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 44,
                    height: 44,
                    decoration: AppTheme.panelDecoration(radius: 14),
                    alignment: Alignment.center,
                    child: const Icon(Icons.memory_rounded, size: 21),
                  ),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '设备与诊断',
                          style: TextStyle(
                            color: AppTheme.textPrimary,
                            fontSize: 17,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        SizedBox(height: 2),
                        Text(
                          '技术信息仅用于排查问题',
                          style: TextStyle(
                            color: AppTheme.textMuted,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 22),
              const Divider(height: 1),
              const SizedBox(height: 18),
              Expanded(
                child: capsAsync.when(
                  data: (caps) => ListView(
                    physics: const BouncingScrollPhysics(),
                    children: [
                      _sectionTitle('设备'),
                      _infoRow(
                        '系统',
                        '${caps.platform.toUpperCase()} ${caps.osVersion}',
                      ),
                      _infoRow('CPU', '${caps.cpuCores} 核'),
                      _infoRow('推荐档位', caps.recommendedProfile.toUpperCase()),
                      const SizedBox(height: 22),
                      _sectionTitle('加速能力'),
                      _infoRow(
                        '图形渲染',
                        caps.gpuSupported ? 'OpenGL ES' : '软件渲染',
                      ),
                      _infoRow('H.264 编码', caps.h264Encoder ? '硬件支持' : '不可用'),
                      const SizedBox(height: 22),
                      _sectionTitle('隐私'),
                      _infoRow('视频处理', '仅本机'),
                      _infoRow('云端上传', '关闭'),
                    ],
                  ),
                  loading: () => const Center(
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  error: (error, _) => const Center(
                    child: Text(
                      '暂时无法读取设备信息',
                      style: TextStyle(
                        color: AppTheme.textSecondary,
                        fontSize: 13,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _sectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(
        title,
        style: const TextStyle(
          color: AppTheme.textMuted,
          fontSize: 12,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.5,
        ),
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Container(
      constraints: const BoxConstraints(minHeight: AppTheme.minTouchTarget),
      padding: const EdgeInsets.symmetric(vertical: 10),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppTheme.surfaceBorder)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: const TextStyle(
                color: AppTheme.textSecondary,
                fontSize: 13,
              ),
            ),
          ),
          const SizedBox(width: 16),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.right,
              style: const TextStyle(
                color: AppTheme.textPrimary,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
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
