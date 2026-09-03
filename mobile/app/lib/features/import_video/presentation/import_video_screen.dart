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
  static const _workspaceSystemUiStyle = SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.dark,
    statusBarBrightness: Brightness.light,
    systemNavigationBarColor: AppTheme.warmBackground,
    systemNavigationBarIconBrightness: Brightness.dark,
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
    SystemChrome.setSystemUIOverlayStyle(_workspaceSystemUiStyle);
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
      backgroundColor: AppTheme.warmSurface,
      surfaceTintColor: Colors.transparent,
      width: (MediaQuery.sizeOf(context).width * 0.88)
          .clamp(280.0, 420.0)
          .toDouble(),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.horizontal(left: Radius.circular(30)),
      ),
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 46,
                    height: 46,
                    decoration: BoxDecoration(
                      color: AppTheme.coralPale,
                      borderRadius: BorderRadius.circular(15),
                      border: Border.all(color: AppTheme.warmBorder),
                    ),
                    alignment: Alignment.center,
                    child: const Icon(
                      Icons.developer_board_rounded,
                      size: 22,
                      color: AppTheme.coral,
                    ),
                  ),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '设备与诊断',
                          style: TextStyle(
                            color: AppTheme.warmTextPrimary,
                            fontSize: 18,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        SizedBox(height: 2),
                        Text(
                          '技术信息仅用于排查问题',
                          style: TextStyle(
                            color: AppTheme.warmTextSecondary,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Builder(
                    builder: (drawerContext) => IconButton(
                      tooltip: '关闭',
                      onPressed: () => Navigator.of(drawerContext).pop(),
                      icon: const Icon(
                        Icons.close_rounded,
                        color: AppTheme.warmTextPrimary,
                        size: 25,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 20),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(
                  horizontal: 13,
                  vertical: 11,
                ),
                decoration: BoxDecoration(
                  color: AppTheme.warmSurfaceSoft,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: AppTheme.warmBorder),
                ),
                child: const Row(
                  children: [
                    Icon(
                      Icons.lock_outline_rounded,
                      color: AppTheme.coral,
                      size: 18,
                    ),
                    SizedBox(width: 9),
                    Expanded(
                      child: Text(
                        '视频分析与处理均在本机完成',
                        style: TextStyle(
                          color: AppTheme.warmTextPrimary,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 18),
              Expanded(
                child: capsAsync.when(
                  data: (caps) => ListView(
                    physics: const BouncingScrollPhysics(),
                    children: [
                      _infoSection(
                        icon: Icons.phone_android_rounded,
                        title: '设备',
                        children: [
                          _infoRow(
                            '系统',
                            '${caps.platform.toUpperCase()} ${caps.osVersion}',
                          ),
                          _infoRow('CPU', '${caps.cpuCores} 核'),
                          _infoRow(
                            '推荐档位',
                            caps.recommendedProfile.toUpperCase(),
                          ),
                        ],
                      ),
                      const SizedBox(height: 14),
                      _infoSection(
                        icon: Icons.speed_rounded,
                        title: '加速能力',
                        children: [
                          _infoRow(
                            '图形渲染',
                            caps.gpuSupported ? 'OpenGL ES' : '软件渲染',
                          ),
                          _infoRow(
                            'H.264 编码',
                            caps.h264Encoder ? '硬件支持' : '不可用',
                          ),
                        ],
                      ),
                      const SizedBox(height: 14),
                      _infoSection(
                        icon: Icons.shield_outlined,
                        title: '隐私',
                        children: [
                          _infoRow('视频处理', '仅本机'),
                          _infoRow('云端上传', '关闭'),
                        ],
                      ),
                      const SizedBox(height: 18),
                      const Center(
                        child: Text(
                          '开发者面板 · 长按首页 Woah 打开',
                          style: TextStyle(
                            color: AppTheme.warmTextMuted,
                            fontSize: 11,
                          ),
                        ),
                      ),
                    ],
                  ),
                  loading: () => const Center(
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: AppTheme.coral,
                    ),
                  ),
                  error: (error, _) => const Center(
                    child: Text(
                      '暂时无法读取设备信息',
                      style: TextStyle(
                        color: AppTheme.warmTextSecondary,
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

  Widget _infoSection({
    required IconData icon,
    required String title,
    required List<Widget> children,
  }) {
    return Container(
      padding: const EdgeInsets.fromLTRB(15, 14, 15, 4),
      decoration: BoxDecoration(
        color: AppTheme.warmSurface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppTheme.warmBorder),
        boxShadow: const [
          BoxShadow(
            color: Color(0x0C000000),
            blurRadius: 16,
            offset: Offset(0, 5),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 18, color: AppTheme.coral),
              const SizedBox(width: 8),
              Text(
                title,
                style: const TextStyle(
                  color: AppTheme.warmTextPrimary,
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          const SizedBox(height: 7),
          ...children,
        ],
      ),
    );
  }

  Widget _infoRow(String label, String value) {
    return Container(
      constraints: const BoxConstraints(minHeight: AppTheme.minTouchTarget),
      padding: const EdgeInsets.symmetric(vertical: 10),
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: AppTheme.warmBorder)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: const TextStyle(
                color: AppTheme.warmTextSecondary,
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
                color: AppTheme.warmTextPrimary,
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
