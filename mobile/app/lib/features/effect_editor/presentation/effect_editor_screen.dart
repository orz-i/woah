import 'dart:io';
import 'package:flutter/material.dart';
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

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(effectEditorControllerProvider);
    final controller = ref.read(effectEditorControllerProvider.notifier);
    final effects = state.effects;

    return Scaffold(
      appBar: AppBar(
        title: const Text('特效参数调节 (Effect Editor)'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 1. Preview Frame Card
              _buildPreviewCard(state),
              const SizedBox(height: 20),

              // 2. Effect Mode Selector
              Text(
                '特效填充模式',
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
                  '当前模式: ${state.effects.fillMode.name.toUpperCase()}',
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
      (FillMode.sticker, '趣味贴纸', Icons.emoji_emotions),
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
              Text(item.$2, style: TextStyle(color: isSelected ? Colors.black : Colors.white, fontWeight: isSelected ? FontWeight.bold : FontWeight.normal)),
            ],
          ),
          selected: isSelected,
          onSelected: (_) => controller.updateFillMode(item.$1),
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
        // Opacity slider (Common for all)
        _buildSlider(
          label: '不透明度 (Opacity)',
          value: effects.opacity,
          min: 0.1,
          max: 1.0,
          displayValue: '${(effects.opacity * 100).toInt()}%',
          onChanged: (val) => controller.updateOpacity(val),
        ),

        // Solid / Gradient Color Picker
        if (effects.fillMode == FillMode.solid || effects.fillMode == FillMode.gradient) ...[
          const SizedBox(height: 16),
          const Text('主填充颜色 (Fill Color)', style: TextStyle(fontSize: 13, color: Colors.white70)),
          const SizedBox(height: 8),
          _buildColorPalette(effects.fillColorArgb, (argb) => controller.updateFillColor(argb)),
        ],

        // Border / Outline Width
        const SizedBox(height: 16),
        _buildSlider(
          label: '边缘描边宽度 (Border Width)',
          value: effects.borderWidth,
          min: 0.0,
          max: 20.0,
          displayValue: '${effects.borderWidth.toInt()} px',
          onChanged: (val) => controller.updateBorderWidth(val),
        ),
        if (effects.borderWidth > 0) ...[
          const SizedBox(height: 12),
          const Text('描边颜色 (Border Color)', style: TextStyle(fontSize: 13, color: Colors.white70)),
          const SizedBox(height: 8),
          _buildColorPalette(effects.borderColorArgb, (argb) => controller.updateBorderColor(argb)),
        ],

        // Blur specific controls
        if (effects.fillMode == FillMode.blur || effects.fillMode == FillMode.mosaic) ...[
          const SizedBox(height: 16),
          _buildSlider(
            label: '模糊/马赛克强度 (Blur Strength)',
            value: effects.blurStrength,
            min: 1.0,
            max: 30.0,
            displayValue: '${effects.blurStrength.toInt()} px',
            onChanged: (val) => controller.updateBlurStrength(val),
          ),
        ],

        // Skin Whiten controls
        const SizedBox(height: 16),
        _buildSlider(
          label: '肤色美白强度 (Skin Whiten)',
          value: effects.skinWhiten,
          min: 0.0,
          max: 1.0,
          displayValue: '${(effects.skinWhiten * 100).toInt()}%',
          onChanged: (val) => controller.updateSkinWhiten(val),
        ),

        // Leg stretch controls
        const SizedBox(height: 16),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text('智能拉腿优化 (Leg Stretch)', style: TextStyle(fontSize: 13, color: Colors.white70)),
            Switch(
              value: effects.legStretchEnabled,
              onChanged: (val) => controller.updateLegStretch(enabled: val, stretch: 0.15),
            ),
          ],
        ),
        if (effects.legStretchEnabled) ...[
          _buildSlider(
            label: '拉伸幅度',
            value: effects.legStretch,
            min: 0.05,
            max: 0.35,
            displayValue: '+${(effects.legStretch * 100).toInt()}%',
            onChanged: (val) => controller.updateLegStretch(enabled: true, stretch: val),
          ),
        ],

        // 4. Follow Crop Controls
        const Divider(height: 32),
        Text(
          '智能运镜追踪 (Follow Crop)',
          style: Theme.of(context).textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.bold,
                color: Colors.white,
              ),
        ),
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text('锁定主角平滑运镜', style: TextStyle(fontSize: 13, color: Colors.white70)),
            Switch(
              value: state.project?.follow.enabled ?? false,
              onChanged: (val) => controller.updateFollowConfig(enabled: val),
            ),
          ],
        ),
        if (state.project?.follow.enabled == true) ...[
          _buildSlider(
            label: '特写镜头变焦倍数 (Zoom)',
            value: state.project!.follow.zoom,
            min: 1.0,
            max: 2.5,
            displayValue: '${state.project!.follow.zoom.toStringAsFixed(1)}x',
            onChanged: (val) => controller.updateFollowConfig(zoom: val),
          ),
        ],
      ],
    );
  }

  Widget _buildSlider({
    required String label,
    required double value,
    required double min,
    required double max,
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
            Text(displayValue, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold, color: Colors.white)),
          ],
        ),
        Slider(
          value: value.clamp(min, max),
          min: min,
          max: max,
          onChanged: onChanged,
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
      spacing: 10,
      runSpacing: 10,
      children: colors.map((argb) {
        final isSelected = currentArgb == argb;
        return GestureDetector(
          onTap: () => onSelect(argb),
          child: Container(
            width: 34,
            height: 34,
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
                    color: Colors.white.withAlpha(50),
                    blurRadius: 8,
                    spreadRadius: 1,
                  ),
              ],
            ),
            child: isSelected
                ? Icon(
                    Icons.check,
                    size: 18,
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
          final configured = controller.buildConfiguredProject();
          if (configured != null) {
            context.push('/export', extra: configured);
          }
        },
        icon: const Icon(Icons.movie_creation_rounded, size: 20),
        label: const Text(
          '下一步：开始硬件编码导出',
          style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.white,
          foregroundColor: Colors.black,
          padding: const EdgeInsets.symmetric(vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
    );
  }
}
