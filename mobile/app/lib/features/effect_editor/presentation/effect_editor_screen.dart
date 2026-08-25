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
                    ),
              ),
              const SizedBox(height: 10),
              _buildModeChips(effects.fillMode, controller),
              const Divider(height: 32),

              // 3. Dynamic Controls based on selected mode
              _buildDynamicControls(context, effects, controller),
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
        height: 200,
        color: const Color(0xFF1E1C24),
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
              const Icon(Icons.auto_fix_high_rounded, size: 48, color: Colors.white38),
            Positioned(
              bottom: 12,
              right: 12,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: Colors.black87,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  '当前模式: ${state.effects.fillMode.name}',
                  style: const TextStyle(fontSize: 11, color: Colors.white70),
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
              Icon(item.$3, size: 16),
              const SizedBox(width: 6),
              Text(item.$2),
            ],
          ),
          selected: isSelected,
          onSelected: (_) => controller.updateFillMode(item.$1),
          selectedColor: Colors.deepPurpleAccent.withAlpha(80),
        );
      }).toList(),
    );
  }

  Widget _buildDynamicControls(
    BuildContext context,
    EffectConfig effects,
    EffectEditorController controller,
  ) {
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
              activeThumbColor: Colors.deepPurpleAccent,
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
            Text(displayValue, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
          ],
        ),
        Slider(
          value: value.clamp(min, max),
          min: min,
          max: max,
          activeColor: Colors.deepPurpleAccent,
          onChanged: onChanged,
        ),
      ],
    );
  }

  Widget _buildColorPalette(int currentArgb, ValueChanged<int> onSelect) {
    final colors = [
      (0xFF000000, '经典黑'),
      (0xFF7C4DFF, '潮流紫'),
      (0xFF00E5FF, '霓虹蓝'),
      (0xFFFF4081, '樱花粉'),
      (0xFF00E676, '荧光绿'),
      (0xFFFFFFFF, '纯白'),
    ];

    return Row(
      children: colors.map((c) {
        final isSelected = currentArgb == c.$1;
        return Padding(
          padding: const EdgeInsets.only(right: 10),
          child: GestureDetector(
            onTap: () => onSelect(c.$1),
            child: Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                color: Color(c.$1),
                shape: BoxShape.circle,
                border: Border.all(
                  color: isSelected ? Colors.white : Colors.white24,
                  width: isSelected ? 3.0 : 1.0,
                ),
              ),
              child: isSelected
                  ? Icon(
                      Icons.check,
                      size: 18,
                      color: c.$1 == 0xFFFFFFFF ? Colors.black : Colors.white,
                    )
                  : null,
            ),
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
      padding: const EdgeInsets.all(16),
      decoration: const BoxDecoration(
        color: Color(0xFF1D1B20),
        border: Border(top: BorderSide(color: Colors.white10)),
      ),
      child: ElevatedButton.icon(
        onPressed: () {
          final configured = controller.buildConfiguredProject();
          if (configured != null) {
            context.push('/export', extra: configured);
          }
        },
        icon: const Icon(Icons.video_call_rounded),
        label: const Text(
          '下一步：开始导出视频',
          style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor: Colors.deepPurpleAccent,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
    );
  }
}
