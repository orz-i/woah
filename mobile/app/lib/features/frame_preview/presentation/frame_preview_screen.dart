import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../core/widgets/stage_viewport.dart';
import '../../../core/widgets/bottom_control_drawer.dart';
import '../../export/presentation/export_screen.dart';
import '../../../repositories/native_processing_repository.dart';

class FramePreviewArgs {
  final DanceProject project;
  final String? initialPreviewPath;

  const FramePreviewArgs({
    required this.project,
    this.initialPreviewPath,
  });
}

class FramePreviewScreen extends ConsumerStatefulWidget {
  final DanceProject project;
  final String? initialPreviewPath;

  const FramePreviewScreen({
    super.key,
    required this.project,
    this.initialPreviewPath,
  });

  @override
  ConsumerState<FramePreviewScreen> createState() => _FramePreviewScreenState();
}

class _FramePreviewScreenState extends ConsumerState<FramePreviewScreen> {
  String? _previewPath;
  bool _isLoading = false;
  String? _errorMessage;
  String _selectedProfile = 'quality';
  bool _sam2Available = false;
  final TransformationController _transformationController = TransformationController();
  final DraggableScrollableController _drawerController = DraggableScrollableController();

  @override
  void initState() {
    super.initState();
    _previewPath = widget.initialPreviewPath;
    if (_previewPath == null || !File(_previewPath!).existsSync()) {
      _loadPreview();
    }
    _checkCapabilities();
  }

  Future<void> _checkCapabilities() async {
    try {
      final repo = ref.read(nativeRepositoryProvider);
      final caps = await repo.getCapabilities();
      final sam2Available = caps.supportedProfiles.contains('sam2');
      if (mounted) {
        setState(() {
          _sam2Available = sam2Available;
          if (!_sam2Available && _selectedProfile == 'sam2') {
            _selectedProfile = 'quality';
          }
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _sam2Available = false;
          if (_selectedProfile == 'sam2') {
            _selectedProfile = 'quality';
          }
        });
      }
    }
  }

  @override
  void dispose() {
    _transformationController.dispose();
    _drawerController.dispose();
    super.dispose();
  }

  Future<void> _loadPreview() async {
    final cacheId = widget.project.analysisCacheId;
    if (cacheId == null || cacheId.isEmpty) {
      setState(() {
        _errorMessage = '缺少分析缓存，无法渲染首帧预览';
      });
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final repo = ref.read(nativeRepositoryProvider);
      final result = await repo.getPreviewFrame(
        analysisCacheId: cacheId,
        timestampMs: 0,
        selectedPersonIds: widget.project.selectedPersonIds.toList(),
        effects: widget.project.effects,
        follow: widget.project.follow,
      );

      if (mounted) {
        setState(() {
          _previewPath = result.thumbnailPath;
          _isLoading = false;
          _errorMessage = null;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isLoading = false;
          _errorMessage = e.toString();
        });
      }
    }
  }

  Future<void> _exportDiagnostics() async {
    try {
      final repo = ref.read(nativeRepositoryProvider);
      final bundle = await repo.createDiagnosticBundle();
      final fileName = bundle?['fileName'] as String? ?? 'diagnostic_bundle.zip';
      final filePath = bundle?['filePath'] as String?;
      final publicUri = bundle?['publicUri'] as String?;

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('诊断包已生成: $fileName'),
            backgroundColor: const Color(0xFF10B981),
            behavior: SnackBarBehavior.floating,
          ),
        );
      }

      await repo.shareDiagnosticBundle(
        filePath: filePath,
        publicUri: publicUri,
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('导出诊断包失败: $e'),
            backgroundColor: Colors.redAccent,
            behavior: SnackBarBehavior.floating,
          ),
        );
      }
    }
  }

  void _resetZoom() {
    _transformationController.value = Matrix4.identity();
  }

  @override
  Widget build(BuildContext context) {
    final project = widget.project;
    final effects = project.effects;
    final videoInfo = project.videoInfo;

    final modeName = switch (effects.fillMode) {
      FillMode.solid => '纯色遮挡',
      FillMode.blur => '动态模糊',
      FillMode.gradient => '纵向渐变',
      FillMode.mosaic => '像素马赛克',
      FillMode.sticker => '趣味贴纸',
    };

    final hasValidPreview = _previewPath != null &&
        _previewPath!.isNotEmpty &&
        File(_previewPath!).existsSync();

    return Scaffold(
      backgroundColor: const Color(0xFF0A0A0C),
      resizeToAvoidBottomInset: false,
      body: Stack(
        children: [
          // Layer 0: 全屏多媒体主舞台 (Full-screen Stage Viewport)
          Positioned.fill(
            child: StageViewport(
              transformationController: _transformationController,
              child: _buildStageContent(hasValidPreview, modeName),
            ),
          ),

          // Layer 1: 顶部悬浮毛玻璃操作栏
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: SafeArea(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: _buildTopFloatingBar(),
              ),
            ),
          ),

          // Layer 2: 底部从下至上控制抽屉 (可完全收起至边缘)
          Positioned.fill(
            child: BottomControlDrawer(
              controller: _drawerController,
              minChildSize: 0.045,
              initialChildSize: 0.36,
              maxChildSize: 0.72,
              snapSizes: const [0.045, 0.36, 0.72],
              peekHeader: _buildDrawerPeekHeader(project, modeName),
              bottomActionBar: _buildDrawerBottomBar(project),
              child: _buildDrawerContent(effects, videoInfo, modeName),
            ),
          ),
        ],
      ),
    );
  }

  /// 全屏主舞台内容
  Widget _buildStageContent(bool hasValidPreview, String modeName) {
    return Stack(
      alignment: Alignment.center,
      children: [
        if (hasValidPreview)
          Image.file(
            File(_previewPath!),
            key: ValueKey(_previewPath),
            fit: BoxFit.contain,
            gaplessPlayback: true,
          )
        else if (!_isLoading)
          Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.broken_image_rounded, size: 56, color: Colors.white24),
                const SizedBox(height: 12),
                Text(
                  _errorMessage ?? '尚未生成首帧预览',
                  style: const TextStyle(color: Colors.white54, fontSize: 13),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 12),
                ElevatedButton.icon(
                  onPressed: _loadPreview,
                  icon: const Icon(Icons.refresh, size: 16),
                  label: const Text('重试生成'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFF2E2E34),
                    foregroundColor: Colors.white,
                  ),
                ),
              ],
            ),
          ),

        // 加载中浮层
        if (_isLoading)
          Positioned.fill(
            child: Container(
              color: Colors.black54,
              child: const Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    CircularProgressIndicator(color: Colors.white),
                    SizedBox(height: 14),
                    Text(
                      '正在渲染全分辨率脱敏首帧...',
                      style: TextStyle(color: Colors.white70, fontSize: 13),
                    ),
                  ],
                ),
              ),
            ),
          ),

        // 悬浮提示胶囊
        Positioned(
          top: 80,
          left: 16,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
            decoration: BoxDecoration(
              color: Colors.black.withAlpha(180),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: Colors.white12),
            ),
            child: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.pinch_rounded, size: 14, color: Colors.white70),
                SizedBox(width: 6),
                Text(
                  '支持双指放大查看打码边缘',
                  style: TextStyle(fontSize: 11, color: Colors.white70),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  /// 顶部一体化极简导航栏 (自适应屏幕宽度，绝不溢出)
  Widget _buildTopFloatingBar() {
    return ClipRRect(
      borderRadius: BorderRadius.circular(24),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
        child: Container(
          height: 48,
          padding: const EdgeInsets.symmetric(horizontal: 6),
          decoration: BoxDecoration(
            color: const Color(0xFF141418).withAlpha(180),
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: Colors.white.withAlpha(25)),
          ),
          child: Row(
            children: [
              // 返回
              IconButton(
                icon: const Icon(Icons.arrow_back_rounded, color: Colors.white, size: 20),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                tooltip: '返回',
                onPressed: () {
                  HapticFeedback.lightImpact();
                  context.pop();
                },
              ),

              const SizedBox(width: 4),

              // 标题
              const Expanded(
                child: Text(
                  '首帧效果确认',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: Colors.white,
                    letterSpacing: 0.2,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),

              // 重新渲染
              IconButton(
                icon: const Icon(Icons.refresh_rounded, color: Colors.white, size: 20),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                tooltip: '重新渲染预览',
                onPressed: _isLoading
                    ? null
                    : () {
                        HapticFeedback.lightImpact();
                        _loadPreview();
                      },
              ),

              // 更多功能菜单 (重置缩放、导出诊断、抽屉开关)
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_horiz_rounded, color: Colors.white70, size: 20),
                color: const Color(0xFF1E1E24),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                onSelected: (value) {
                  HapticFeedback.selectionClick();
                  if (value == 'reset_zoom') {
                    _resetZoom();
                  } else if (value == 'diagnostics') {
                    _exportDiagnostics();
                  } else if (value == 'toggle_drawer') {
                    final current = _drawerController.size;
                    if (current < 0.1) {
                      _drawerController.animateTo(0.36, duration: const Duration(milliseconds: 260), curve: Curves.easeOutCubic);
                    } else {
                      _drawerController.animateTo(0.045, duration: const Duration(milliseconds: 260), curve: Curves.easeOutCubic);
                    }
                  }
                },
                itemBuilder: (context) => [
                  const PopupMenuItem(
                    value: 'reset_zoom',
                    child: Row(
                      children: [
                        Icon(Icons.fit_screen_rounded, size: 18, color: Colors.white70),
                        SizedBox(width: 10),
                        Text('重置画面缩放', style: TextStyle(color: Colors.white, fontSize: 13)),
                      ],
                    ),
                  ),
                  const PopupMenuItem(
                    value: 'diagnostics',
                    child: Row(
                      children: [
                        Icon(Icons.bug_report_outlined, size: 18, color: Colors.white70),
                        SizedBox(width: 10),
                        Text('导出诊断日志', style: TextStyle(color: Colors.white, fontSize: 13)),
                      ],
                    ),
                  ),
                  const PopupMenuItem(
                    value: 'toggle_drawer',
                    child: Row(
                      children: [
                        Icon(Icons.tune_rounded, size: 18, color: Colors.white70),
                        SizedBox(width: 10),
                        Text('展开/收起参数抽屉', style: TextStyle(color: Colors.white, fontSize: 13)),
                      ],
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 抽屉 Peek Header
  Widget _buildDrawerPeekHeader(DanceProject project, String modeName) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 2, 16, 6),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            children: [
              const Icon(Icons.verified_user_rounded, size: 16, color: Colors.greenAccent),
              const SizedBox(width: 6),
              Text(
                '选中保护目标: ${project.selectedPersonIds.length} 人',
                style: const TextStyle(
                  fontSize: 13,
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(width: 8),
              GestureDetector(
                onTap: () {
                  HapticFeedback.lightImpact();
                  _drawerController.animateTo(
                    0.045,
                    duration: const Duration(milliseconds: 260),
                    curve: Curves.easeOutCubic,
                  );
                },
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: Colors.white.withAlpha(15),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.keyboard_arrow_down_rounded, size: 14, color: Colors.white70),
                      SizedBox(width: 2),
                      Text('收起', style: TextStyle(fontSize: 10, color: Colors.white70)),
                    ],
                  ),
                ),
              ),
            ],
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: Colors.white.withAlpha(15),
              borderRadius: BorderRadius.circular(6),
              border: Border.all(color: Colors.white24),
            ),
            child: Text(
              modeName,
              style: const TextStyle(fontSize: 11, color: Colors.white, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  /// 抽屉滚动内容
  Widget _buildDrawerContent(
    EffectConfig effects,
    VideoInfo videoInfo,
    String modeName,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 6),
        Wrap(
          spacing: 8,
          runSpacing: 6,
          children: [
            _buildChip('样式: $modeName'),
            _buildChip('透明度: ${(effects.opacity * 100).toInt()}%'),
            if (effects.borderWidth > 0)
              _buildChip('描边: ${effects.borderWidth.toInt()}px'),
            if (effects.skinWhiten > 0)
              _buildChip('美白: ${(effects.skinWhiten * 100).toInt()}%'),
            if (effects.legStretchEnabled)
              _buildChip('拉腿: ${(effects.legStretch * 100).toInt()}%'),
            _buildChip('规格: ${videoInfo.displayWidth}×${videoInfo.displayHeight}'),
          ],
        ),

        const SizedBox(height: 16),
        const Divider(color: Colors.white10, height: 1),
        const SizedBox(height: 14),

        const Text(
          '导出处理引擎与性能配置',
          style: TextStyle(fontSize: 13, color: Colors.white, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 10),
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          physics: const BouncingScrollPhysics(),
          child: Row(
            children: [
              _buildProfileChip('quality', '🌟 质量模式 (默认)'),
              if (_sam2Available) ...[
                const SizedBox(width: 8),
                _buildProfileChip('sam2', '⚡ SAM2 时序增强'),
              ],
              const SizedBox(width: 8),
              _buildProfileChip('balanced', '⚖️ 均衡模式'),
              const SizedBox(width: 8),
              _buildProfileChip('speed', '🚀 极速模式'),
            ],
          ),
        ),
        const SizedBox(height: 16),
      ],
    );
  }

  /// 抽屉底部操作栏
  Widget _buildDrawerBottomBar(DanceProject project) {
    return Row(
      children: [
        OutlinedButton.icon(
          onPressed: () {
            HapticFeedback.lightImpact();
            context.pop();
          },
          icon: const Icon(Icons.arrow_back, size: 18),
          label: const Text('返回调整'),
          style: OutlinedButton.styleFrom(
            foregroundColor: Colors.white70,
            side: const BorderSide(color: Colors.white24),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
            ),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: ElevatedButton.icon(
            onPressed: () {
              HapticFeedback.mediumImpact();
              final effectiveProfile = (_selectedProfile == 'sam2' && !_sam2Available) ? 'quality' : _selectedProfile;
              context.push(
                '/export',
                extra: ExportArgs(
                  project: project,
                  processingProfile: effectiveProfile,
                ),
              );
            },
            icon: const Icon(Icons.movie_creation_rounded, size: 20),
            label: const Text(
              '确认效果并开始导出',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
            ),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.white,
              foregroundColor: Colors.black,
              padding: const EdgeInsets.symmetric(vertical: 15),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildProfileChip(String profileKey, String label) {
    final isSelected = _selectedProfile == profileKey;
    return GestureDetector(
      onTap: () {
        HapticFeedback.selectionClick();
        setState(() {
          _selectedProfile = profileKey;
        });
      },
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: isSelected ? Colors.white : const Color(0xFF22222A),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: isSelected ? Colors.white : Colors.white12,
            width: isSelected ? 1.5 : 1.0,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 12,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
            color: isSelected ? Colors.black : Colors.white70,
          ),
        ),
      ),
    );
  }

  Widget _buildChip(String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: const Color(0xFF23232A),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.white12),
      ),
      child: Text(
        text,
        style: const TextStyle(fontSize: 11, color: Colors.white70),
      ),
    );
  }
}
