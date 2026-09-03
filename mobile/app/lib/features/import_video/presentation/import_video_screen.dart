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
  Future<void> _pickVideoAndContinue() async {
    HapticFeedback.mediumImpact();
    final controller = ref.read(importVideoControllerProvider.notifier);
    await controller.pickAndProbeVideo();
    if (!mounted) return;

    final project = controller.createProject();
    if (project == null) return;

    await context.push('/person_selection', extra: project);
    if (mounted) controller.reset();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(importVideoControllerProvider);
    final isBusy =
        state.status == VideoImportStatus.picking ||
        state.status == VideoImportStatus.probing;

    return Scaffold(
      backgroundColor: AppTheme.background,
      endDrawer: _buildSystemInfoDrawer(),
      body: Builder(
        builder: (scaffoldContext) => SafeArea(
          child: Stack(
            children: [
              Positioned(
                top: 8,
                right: 14,
                child: _ChromeIconButton(
                  icon: Icons.more_horiz_rounded,
                  tooltip: '设备与诊断',
                  onPressed: () {
                    HapticFeedback.lightImpact();
                    Scaffold.of(scaffoldContext).openEndDrawer();
                  },
                ),
              ),
              Center(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(28, 72, 28, 36),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 420),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        ShaderMask(
                          blendMode: BlendMode.srcIn,
                          shaderCallback: AppTheme.metalGradient.createShader,
                          child: const Text(
                            'Woah',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 54,
                              height: 1,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -2.2,
                            ),
                          ),
                        ),
                        const SizedBox(height: 16),
                        const Text(
                          '隐私保护 · 本机处理',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: AppTheme.textPrimary,
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                            letterSpacing: 0.4,
                          ),
                        ),
                        const SizedBox(height: 28),
                        const _PrivacyPromise(),
                        const SizedBox(height: 58),
                        if (state.errorMessage != null) ...[
                          _ErrorNotice(message: state.errorMessage!),
                          const SizedBox(height: 18),
                        ],
                        if (isBusy)
                          _LoadingPanel(status: state.status)
                        else
                          _MetalActionButton(
                            icon: Icons.folder_open_rounded,
                            label: '选择视频',
                            onTap: _pickVideoAndContinue,
                          ),
                        const SizedBox(height: 14),
                        const Text(
                          '支持 MP4 · MOV · H.264 · HEVC',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: AppTheme.textMuted,
                            fontSize: 12,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ],
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

class _PrivacyPromise extends StatelessWidget {
  const _PrivacyPromise();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 15),
      decoration: BoxDecoration(
        color: AppTheme.surface.withAlpha(210),
        borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
        border: Border.all(color: AppTheme.surfaceBorder),
      ),
      child: const Column(
        children: [
          _PromiseRow(icon: Icons.lock_outline_rounded, text: '视频仅在本机处理'),
          SizedBox(height: 10),
          _PromiseRow(icon: Icons.cloud_off_outlined, text: '不会上传任何内容'),
        ],
      ),
    );
  }
}

class _PromiseRow extends StatelessWidget {
  final IconData icon;
  final String text;

  const _PromiseRow({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 18, color: AppTheme.metalMid),
        const SizedBox(width: 10),
        Text(
          text,
          style: const TextStyle(
            color: AppTheme.textSecondary,
            fontSize: 13,
            fontWeight: FontWeight.w500,
          ),
        ),
      ],
    );
  }
}

class _ChromeIconButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;

  const _ChromeIconButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: AppTheme.minTouchTarget,
      height: AppTheme.minTouchTarget,
      decoration: AppTheme.panelDecoration(radius: 14),
      child: IconButton(
        tooltip: tooltip,
        onPressed: onPressed,
        icon: Icon(icon, size: 22),
      ),
    );
  }
}

class _MetalActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  const _MetalActionButton({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
        child: Ink(
          width: double.infinity,
          height: 58,
          decoration: AppTheme.metalButtonDecoration(),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: AppTheme.canvas, size: 21),
              const SizedBox(width: 10),
              Text(
                label,
                style: const TextStyle(
                  color: AppTheme.canvas,
                  fontSize: 16,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _LoadingPanel extends StatelessWidget {
  final VideoImportStatus status;

  const _LoadingPanel({required this.status});

  @override
  Widget build(BuildContext context) {
    final label = status == VideoImportStatus.picking ? '正在选择视频…' : '正在读取视频信息…';
    return Container(
      width: double.infinity,
      constraints: const BoxConstraints(minHeight: 58),
      decoration: AppTheme.panelDecoration(),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
          const SizedBox(width: 12),
          Text(
            label,
            style: const TextStyle(color: AppTheme.textSecondary, fontSize: 14),
          ),
        ],
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
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppTheme.surfaceElevated,
        borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
        border: Border.all(color: AppTheme.error.withAlpha(110)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.error_outline_rounded,
            color: AppTheme.error,
            size: 20,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(
                color: AppTheme.textSecondary,
                fontSize: 13,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
