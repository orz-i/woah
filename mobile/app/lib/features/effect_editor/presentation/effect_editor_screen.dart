import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
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
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(effectEditorControllerProvider.notifier).init(widget.project);
    });
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
    final effects = state.effects;

    return Scaffold(
      appBar: AppBar(
        title: const Text('画面特效调节'),
        actions: [
          IconButton(
            icon: const Icon(Icons.restart_alt_rounded),
            tooltip: '恢复默认参数',
            onPressed: () => _resetToDefault(controller),
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 1. Preview Frame Card
              _buildPreviewCard(state),
              const SizedBox(height: 20),

              // 2. Effect Mode Selector
              Text(
                '遮挡样式选择',
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
              ),
              const SizedBox(height: 10),
              _buildModeChips(effects.fillMode, controller),
              const Divider(height: 32),

              // 3. Dynamic Controls based on selected mode
              _buildDynamicControls(context, state, controller),
            ],
          ),
        ),
      ),
      bottomNavigationBar: _buildBottomBar(context, state, controller),
    );
  }

  Widget _buildPreviewCard(EffectEditorState state) {
    final thumbPath = state.previewThumbnailPath;
    final hasThumb = thumbPath != null && thumbPath.isNotEmpty && File(thumbPath).existsSync();

    final modeName = switch (state.effects.fillMode) {
      FillMode.solid => '纯色遮挡',
      FillMode.blur => '动态模糊',
      FillMode.gradient => '纵向渐变',
      FillMode.mosaic => '像素马赛克',
      FillMode.sticker => '趣味贴纸',
    };

    return Card(
      clipBehavior: Clip.antiAlias,
      child: Container(
        height: 220,
        color: const Color(0xFF131316),
        child: Stack(
          alignment: Alignment.center,
          children: [
            if (hasThumb)
              Image.file(
                File(thumbPath),
                fit: BoxFit.contain,
                width: double.infinity,
                height: double.infinity,
              )
            else
              const Icon(Icons.auto_fix_high_rounded, size: 48, color: Colors.white30),
            Positioned(
              bottom: 12,
              right: 12,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(
                  color: Colors.black87,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.white24),
                ),
                child: Text(
                  '当前样式: $modeName',
                  style: const TextStyle(fontSize: 11, color: Colors.white, fontWeight: FontWeight.bold),
                ),
              ),
            ),
          ],
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
              Icon(item.$3, size: 16, color: isSelected ? Colors.black : Colors.white70),
              const SizedBox(width: 6),
              Text(
                item.$2,
                style: TextStyle(
                  color: isSelected ? Colors.black : Colors.white,
                  fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
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
          backgroundColor: const Color(0xFF1E1E24),
          side: BorderSide(color: isSelected ? Colors.white : Colors.white24),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        );
      }).toList(),
    );
  }

  Widget _buildDynamicControls(
    BuildContext context,
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    final effects = state.effects;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Opacity slider with step controls
        _buildStepSlider(
          label: '遮挡不透明度',
          value: effects.opacity,
          min: 0.1,
          max: 1.0,
          step: 0.05,
          displayValue: '${(effects.opacity * 100).toInt()}%',
          onChanged: (val) => controller.updateOpacity(val),
        ),

        // Solid / Gradient Color Picker
        if (effects.fillMode == FillMode.solid || effects.fillMode == FillMode.gradient) ...[
          const SizedBox(height: 18),
          const Text('遮挡填充颜色', style: TextStyle(fontSize: 13, color: Colors.white70)),
          const SizedBox(height: 10),
          _buildColorPalette(effects.fillColorArgb, (argb) => controller.updateFillColor(argb)),
        ],

        // Border Width
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

        // Blur specific controls
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

        // Skin Whiten controls
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

        // 4. Follow Crop Controls
        const Divider(height: 36),
        Text(
          '主角专属特写镜头',
          style: Theme.of(context).textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.bold,
                color: Colors.white,
              ),
        ),
        const SizedBox(height: 12),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(
            color: const Color(0xFF16161A),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.white.withAlpha(15)),
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
      ],
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
                color: Colors.white.withAlpha(12),
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
            // Minus fine-tune button
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
                onChanged: (val) {
                  onChanged(val);
                },
              ),
            ),
            // Plus fine-tune button
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

  Widget _buildBottomBar(
    BuildContext context,
    EffectEditorState state,
    EffectEditorController controller,
  ) {
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
      decoration: const BoxDecoration(
        color: Color(0xFF131316),
        border: Border(top: BorderSide(color: Color(0xFF2E2E34))),
      ),
      child: ElevatedButton.icon(
        onPressed: () {
          HapticFeedback.mediumImpact();
          final configured = controller.buildConfiguredProject();
          if (configured != null) {
            context.push('/export', extra: configured);
          }
        },
        icon: const Icon(Icons.movie_creation_rounded, size: 20),
        label: const Text(
          '下一步：生成并导出视频',
          style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.white,
          foregroundColor: Colors.black,
          padding: const EdgeInsets.symmetric(vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
        ),
      ),
    );
  }
}
