import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../core/widgets/stage_viewport.dart';
import '../../../core/widgets/bottom_control_drawer.dart';
import '../../frame_preview/presentation/frame_preview_screen.dart';
import 'effect_editor_controller.dart';
import '../domain/effect_editor_state.dart';

class EffectEditorScreen extends ConsumerStatefulWidget {
  final DanceProject project;

  const EffectEditorScreen({
    super.key,
    required this.project,
  });

  @override
  ConsumerState<EffectEditorScreen> createState() => _EffectEditorScreenState();
}

class _EffectEditorScreenState extends ConsumerState<EffectEditorScreen> {
  final TransformationController _stageTransformationController = TransformationController();
  final DraggableScrollableController _drawerController = DraggableScrollableController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(effectEditorControllerProvider.notifier).init(widget.project);
    });
  }

  @override
  void dispose() {
    _stageTransformationController.dispose();
    _drawerController.dispose();
    super.dispose();
  }

  void _resetToDefault(EffectEditorController controller) {
    HapticFeedback.mediumImpact();
    controller.updateFillMode(FillMode.solid);
    controller.updateOpacity(1.0);
    controller.updateFillColor(0xFF000000);
    controller.updateBorderWidth(0.0);
    controller.updateBorderColor(0xFFFFFFFF);
    controller.updateBlurStrength(15.0);
    controller.updateSkinWhiten(0.0);
    controller.updateLegStretch(enabled: false, stretch: 0.15);
    controller.updateFollowConfig(enabled: false);
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        duration: Duration(seconds: 2),
        backgroundColor: Color(0xFF222226),
        content: Text('已恢复默认特效参数', style: TextStyle(color: Colors.white)),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(effectEditorControllerProvider);
    final controller = ref.read(effectEditorControllerProvider.notifier);

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        final configured = controller.buildConfiguredProject();
        context.pop(configured);
      },
      child: Scaffold(
        backgroundColor: const Color(0xFF0A0A0C),
        resizeToAvoidBottomInset: false,
        body: Stack(
          children: [
            // Layer 0: 全屏多媒体主舞台 (Multimedia Stage Viewport)
            Positioned.fill(
              child: StageViewport(
                transformationController: _stageTransformationController,
                child: _buildStagePreview(state),
              ),
            ),

            // Layer 1: 顶部悬浮毛玻璃操作栏 (Floating Glassmorphism Bar)
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: _buildTopFloatingBar(context, controller, state),
                ),
              ),
            ),

            // Layer 2: 底部从下至上控制抽屉 (可完全收起至边缘，内容完全可滑动)
            Positioned.fill(
              child: BottomControlDrawer(
                controller: _drawerController,
                minChildSize: 0.045,
                initialChildSize: 0.44,
                maxChildSize: 0.85,
                snapSizes: const [0.045, 0.44, 0.85],
                peekHeader: _buildDrawerPeekHeader(state, controller),
                bottomActionBar: _buildDrawerBottomBar(context, state, controller),
                child: _buildDrawerContent(context, state, controller),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 全屏主舞台内容
  Widget _buildStagePreview(EffectEditorState state) {
    final displayPath = state.previewPath ?? state.previewThumbnailPath;
    final hasImage = displayPath != null && displayPath.isNotEmpty && File(displayPath).existsSync();

    return Stack(
      alignment: Alignment.center,
      children: [
        if (hasImage)
          Image.file(
            File(displayPath),
            key: ValueKey('${displayPath}_${state.previewRequestId}'),
            fit: BoxFit.contain,
            gaplessPlayback: true,
          )
        else
          const Center(
            child: Icon(
              Icons.auto_fix_high_rounded,
              size: 64,
              color: Colors.white24,
            ),
          ),

        // 加载中悬浮指示器
        if (state.previewLoading)
          Positioned(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(16),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                  color: Colors.black54,
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
                      ),
                      SizedBox(width: 14),
                      Text(
                        '实时特效渲染中...',
                        style: TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w500),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),

        // 错误提示
        if (state.previewError != null)
          Positioned(
            top: 80,
            left: 20,
            right: 20,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                color: Colors.redAccent.withAlpha(220),
                child: Row(
                  children: [
                    const Icon(Icons.error_outline, size: 16, color: Colors.white),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '预览生成失败: ${state.previewError}',
                        style: const TextStyle(fontSize: 12, color: Colors.white),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }

  /// 顶部一体化极简导航栏 (自适应屏幕宽度，绝不溢出)
  Widget _buildTopFloatingBar(
    BuildContext context,
    EffectEditorController controller,
    EffectEditorState state,
  ) {
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
                  final configured = controller.buildConfiguredProject();
                  context.pop(configured);
                },
              ),

              const SizedBox(width: 4),

              // 标题
              const Expanded(
                child: Text(
                  '画面特效调节',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: Colors.white,
                    letterSpacing: 0.2,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),

              // 展开/收起抽屉
              IconButton(
                icon: const Icon(Icons.tune_rounded, color: Colors.white, size: 20),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                tooltip: '展开/收起设置抽屉',
                onPressed: () {
                  HapticFeedback.lightImpact();
                  final current = _drawerController.size;
                  if (current < 0.1) {
                    _drawerController.animateTo(0.44, duration: const Duration(milliseconds: 260), curve: Curves.easeOutCubic);
                  } else {
                    _drawerController.animateTo(0.045, duration: const Duration(milliseconds: 260), curve: Curves.easeOutCubic);
                  }
                },
              ),

              // 恢复默认
              IconButton(
                icon: const Icon(Icons.restart_alt_rounded, color: Colors.white70, size: 20),
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                tooltip: '恢复默认参数',
                onPressed: () => _resetToDefault(controller),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 抽屉 Peek Header（精简单行标题，绝不阻挡滚动内容）
  Widget _buildDrawerPeekHeader(EffectEditorState state, EffectEditorController controller) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 2, 16, 6),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Row(
            children: [
              const Icon(Icons.tune_rounded, size: 15, color: Colors.white),
              const SizedBox(width: 6),
              const Text(
                '特效参数调节',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
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
          if (state.previewPath != null)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: Colors.greenAccent.withAlpha(30),
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: Colors.greenAccent.withAlpha(80)),
              ),
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.check_circle_rounded, size: 11, color: Colors.greenAccent),
                  SizedBox(width: 4),
                  Text(
                    'Live Preview',
                    style: TextStyle(fontSize: 10, color: Colors.greenAccent, fontWeight: FontWeight.bold),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  /// 抽屉滚动内容主体 (所有控制选项完整无缝滚动)
  Widget _buildDrawerContent(
    BuildContext context,
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final effects = state.effects;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 1. 遮挡样式选择
        const Text(
          '遮挡样式选择',
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.bold,
            color: Colors.white70,
          ),
        ),
        const SizedBox(height: 8),
        _buildModeChips(effects.fillMode, controller),
        const Divider(height: 22, color: Colors.white12),

        // 2. Opacity slider with step controls
        _buildStepSlider(
          label: '遮挡不透明度',
          value: effects.opacity,
          min: 0.1,
          max: 1.0,
          step: 0.05,
          displayValue: '${(effects.opacity * 100).toInt()}%',
          onChanged: (val) => controller.updateOpacity(val),
        ),

        // 3. Solid / Gradient Color Picker
        if (effects.fillMode == FillMode.solid || effects.fillMode == FillMode.gradient) ...[
          const SizedBox(height: 18),
          const Text('遮挡填充颜色', style: TextStyle(fontSize: 13, color: Colors.white70)),
          const SizedBox(height: 10),
          _buildColorPalette(effects.fillColorArgb, (argb) => controller.updateFillColor(argb)),
        ],

        // 4. Border Width & Border Color
        const SizedBox(height: 18),
        _buildStepSlider(
          label: '边缘描边粗细',
          value: effects.borderWidth,
          min: 0.0,
          max: 20.0,
          step: 1.0,
          displayValue: '${effects.borderWidth.toInt()} 像素',
          onChanged: (val) => controller.updateBorderWidth(val),
        ),
        if (effects.borderWidth > 0) ...[
          const SizedBox(height: 14),
          const Text('描边发光颜色', style: TextStyle(fontSize: 13, color: Colors.white70)),
          const SizedBox(height: 10),
          _buildColorPalette(effects.borderColorArgb, (argb) => controller.updateBorderColor(argb)),
        ],

        // 5. Blur / Mosaic specific controls
        if (effects.fillMode == FillMode.blur || effects.fillMode == FillMode.mosaic) ...[
          const SizedBox(height: 18),
          _buildStepSlider(
            label: '模糊 / 马赛克强度',
            value: effects.blurStrength,
            min: 1.0,
            max: 30.0,
            step: 1.0,
            displayValue: '${effects.blurStrength.toInt()}',
            onChanged: (val) => controller.updateBlurStrength(val),
          ),
        ],

        // 6. Skin Whiten controls
        const SizedBox(height: 18),
        _buildStepSlider(
          label: '人像美白提亮',
          value: effects.skinWhiten,
          min: 0.0,
          max: 1.0,
          step: 0.05,
          displayValue: '${(effects.skinWhiten * 100).toInt()}%',
          onChanged: (val) => controller.updateSkinWhiten(val),
        ),

        // 7. AI 特效区域
        const Divider(height: 28, color: Colors.white12),
        const Text(
          '主角专属特写镜头',
          style: TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.bold,
            color: Colors.white,
          ),
        ),
        const SizedBox(height: 10),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(
            color: const Color(0xFF1B1B22),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.white.withAlpha(20)),
          ),
          child: Column(
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('自动跟随主角平滑运镜', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Colors.white)),
                  Switch(
                    value: state.project?.follow.enabled ?? false,
                    onChanged: (val) {
                      HapticFeedback.lightImpact();
                      controller.updateFollowConfig(enabled: val);
                    },
                  ),
                ],
              ),
              AnimatedSize(
                duration: const Duration(milliseconds: 250),
                curve: Curves.easeInOut,
                child: (state.project?.follow.enabled == true)
                    ? Padding(
                        padding: const EdgeInsets.only(top: 8.0, bottom: 4.0),
                        child: _buildStepSlider(
                          label: '特写放大倍数',
                          value: state.project!.follow.zoom,
                          min: 1.0,
                          max: 2.5,
                          step: 0.1,
                          displayValue: '${state.project!.follow.zoom.toStringAsFixed(1)}倍',
                          onChanged: (val) => controller.updateFollowConfig(zoom: val),
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
      ],
    );
  }

  /// 抽屉底部主操作按钮
  Widget _buildDrawerBottomBar(
    BuildContext context,
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    return SizedBox(
      height: 48,
      width: double.infinity,
      child: ElevatedButton.icon(
        onPressed: () {
          HapticFeedback.mediumImpact();
          final configured = controller.buildConfiguredProject();
          if (configured != null) {
            context.push(
              '/frame_preview',
              extra: FramePreviewArgs(
                project: configured,
                initialPreviewPath: state.previewPath,
              ),
            );
          }
        },
        icon: const Icon(Icons.preview_rounded, size: 18),
        label: const Text(
          '下一步：首帧效果确认',
          style: TextStyle(fontSize: 14, fontWeight: FontWeight.bold),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.white,
          foregroundColor: Colors.black,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
    );
  }

  Widget _buildModeChips(FillMode current, EffectEditorController controller) {
    final modes = [
      (FillMode.solid, '纯色遮挡', Icons.format_color_fill),
      (FillMode.blur, '动态模糊', Icons.blur_on),
      (FillMode.gradient, '纵向渐变', Icons.gradient),
      (FillMode.mosaic, '像素马赛克', Icons.grid_4x4),
    ];

    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: modes.map((item) {
        final isSelected = current == item.$1;
        return ChoiceChip(
          label: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(item.$3, size: 15, color: isSelected ? Colors.black : Colors.white70),
              const SizedBox(width: 5),
              Text(
                item.$2,
                style: TextStyle(
                  color: isSelected ? Colors.black : Colors.white,
                  fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                  fontSize: 12,
                ),
              ),
            ],
          ),
          selected: isSelected,
          onSelected: (_) {
            HapticFeedback.selectionClick();
            controller.updateFillMode(item.$1);
          },
          selectedColor: Colors.white,
          backgroundColor: const Color(0xFF22222A),
          side: BorderSide(color: isSelected ? Colors.white : Colors.white24),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        );
      }).toList(),
    );
  }

  Widget _buildStepSlider({
    required String label,
    required double value,
    required double min,
    required double max,
    required double step,
    required String displayValue,
    required ValueChanged<double> onChanged,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: const TextStyle(fontSize: 13, color: Colors.white70)),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.white.withAlpha(15),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                displayValue,
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: Colors.white),
              ),
            ),
          ],
        ),
        const SizedBox(height: 2),
        Row(
          children: [
            IconButton(
              icon: const Icon(Icons.remove_circle_outline_rounded, size: 20, color: Colors.white54),
              visualDensity: VisualDensity.compact,
              padding: EdgeInsets.zero,
              onPressed: value > min
                  ? () {
                      HapticFeedback.selectionClick();
                      onChanged((value - step).clamp(min, max));
                    }
                  : null,
            ),
            Expanded(
              child: Slider(
                value: value.clamp(min, max),
                min: min,
                max: max,
                onChanged: (val) => onChanged(val),
              ),
            ),
            IconButton(
              icon: const Icon(Icons.add_circle_outline_rounded, size: 20, color: Colors.white54),
              visualDensity: VisualDensity.compact,
              padding: EdgeInsets.zero,
              onPressed: value < max
                  ? () {
                      HapticFeedback.selectionClick();
                      onChanged((value + step).clamp(min, max));
                    }
                  : null,
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildColorPalette(int currentArgb, ValueChanged<int> onSelect) {
    final colors = [
      0xFF000000, // Pure Black
      0xFF202020, // Dark Grey
      0xFF808080, // Medium Grey
      0xFFFFFFFF, // Pure White
      0xFF7C4DFF, // Deep Purple
      0xFF2979FF, // Blue
      0xFF00E676, // Green
      0xFFFF5252, // Red
      0xFFFFD700, // Gold
    ];

    return Wrap(
      spacing: 12,
      runSpacing: 10,
      children: colors.map((argb) {
        final isSelected = currentArgb == argb;
        return GestureDetector(
          onTap: () {
            HapticFeedback.selectionClick();
            onSelect(argb);
          },
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            width: isSelected ? 38 : 34,
            height: isSelected ? 38 : 34,
            decoration: BoxDecoration(
              color: Color(argb),
              shape: BoxShape.circle,
              border: Border.all(
                color: isSelected ? Colors.white : Colors.white24,
                width: isSelected ? 2.5 : 1.0,
              ),
              boxShadow: [
                if (isSelected)
                  BoxShadow(
                    color: Colors.white.withAlpha(60),
                    blurRadius: 10,
                    spreadRadius: 1,
                  ),
              ],
            ),
            child: isSelected
                ? Icon(
                    Icons.check,
                    size: 20,
                    color: argb == 0xFFFFFFFF ? Colors.black : Colors.white,
                  )
                : null,
          ),
        );
      }).toList(),
    );
  }
}
