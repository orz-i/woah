import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
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

  void _resetZoom() {
    _transformationController.value = Matrix4.identity();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
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
      backgroundColor: const Color(0xFF0D0D10),
      appBar: AppBar(
        title: const Text(
          '首帧效果确认',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 17),
        ),
        centerTitle: true,
        backgroundColor: const Color(0xFF131316),
        elevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            tooltip: '重新渲染预览',
            onPressed: _isLoading ? null : _loadPreview,
          ),
          IconButton(
            icon: const Icon(Icons.fit_screen_rounded),
            tooltip: '重置画面缩放',
            onPressed: _resetZoom,
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            // 1. Zoomable Image Preview Area
            Expanded(
              child: Container(
                color: Colors.black,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    if (hasValidPreview)
                      InteractiveViewer(
                        transformationController: _transformationController,
                        minScale: 1.0,
                        maxScale: 4.0,
                        child: Center(
                          child: Image.file(
                            File(_previewPath!),
                            key: ValueKey(_previewPath),
                            fit: BoxFit.contain,
                            gaplessPlayback: true,
                          ),

                        ),
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

                    // Loading overlay
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

                    // Floating prompt badge
                    Positioned(
                      top: 14,
                      left: 14,
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

                    // Floating status badge
                    Positioned(
                      bottom: 14,
                      right: 14,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                        decoration: BoxDecoration(
                          color: Colors.black.withAlpha(200),
                          borderRadius: BorderRadius.circular(20),
                          border: Border.all(color: Colors.white24),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(Icons.verified_user_rounded, size: 13, color: Colors.greenAccent),
                            const SizedBox(width: 5),
                            Text(
                              '打码样式: $modeName',
                              style: const TextStyle(fontSize: 11, color: Colors.white, fontWeight: FontWeight.w600),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),

            // 2. Summary Info Section
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              color: const Color(0xFF131316),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.info_outline_rounded, size: 16, color: Colors.white70),
                      const SizedBox(width: 8),
                      Text(
                        '导出效果确认',
                        style: theme.textTheme.labelLarge?.copyWith(
                          color: Colors.white,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const Spacer(),
                      Text(
                        '选中保护目标: ${project.selectedPersonIds.length} 人',
                        style: const TextStyle(color: Colors.greenAccent, fontSize: 12, fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
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

                  const SizedBox(height: 12),
                  const Divider(color: Colors.white10, height: 1),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      const Text(
                        '导出处理引擎: ',
                        style: TextStyle(fontSize: 12, color: Colors.white70, fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: SingleChildScrollView(
                          scrollDirection: Axis.horizontal,
                          physics: const BouncingScrollPhysics(),
                          child: Row(
                            children: [
                              _buildProfileChip('quality', '🌟 质量 (默认)'),
                              if (_sam2Available) ...[
                                const SizedBox(width: 6),
                                _buildProfileChip('sam2', '⚡ SAM2 时序'),
                              ],
                              const SizedBox(width: 6),
                              _buildProfileChip('balanced', '⚖️ 均衡'),
                              const SizedBox(width: 6),
                              _buildProfileChip('speed', '🚀 极速'),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),

            // 3. Bottom Actions Bar
            Container(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 14),
              decoration: const BoxDecoration(
                color: Color(0xFF131316),
                border: Border(top: BorderSide(color: Color(0xFF2E2E34))),
              ),
              child: Row(
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
              ),
            ),
          ],
        ),
      ),
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
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: isSelected ? Colors.white : const Color(0xFF23232A),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: isSelected ? Colors.white : Colors.white12,
            width: isSelected ? 1.5 : 1.0,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 11,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
            color: isSelected ? Colors.black : Colors.white70,
          ),
        ),
      ),
    );
  }

  Widget _buildChip(String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: const Color(0xFF23232A),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.white10),
      ),
      child: Text(
        text,
        style: const TextStyle(fontSize: 11, color: Colors.white70),
      ),
    );
  }
}

