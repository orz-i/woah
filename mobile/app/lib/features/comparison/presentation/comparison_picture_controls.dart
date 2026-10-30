import 'package:flutter/material.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../app/theme.dart';

class ComparisonPictureControls extends StatelessWidget {
  const ComparisonPictureControls({
    super.key,
    required this.project,
    required this.reference,
    required this.onSelect,
    required this.onChange,
    required this.onApply,
    required this.onCancel,
    this.saving = false,
    this.error,
  });
  final ComparisonProject project;
  final bool reference, saving;
  final ValueChanged<bool> onSelect;
  final ValueChanged<ComparisonPicture> onChange;
  final VoidCallback onApply, onCancel;
  final String? error;
  @override
  Widget build(BuildContext context) {
    final picture = reference ? project.teacherPicture : project.learnerPicture;
    final label = reference ? '参考' : '目标';
    void zoom(double value) =>
        onChange(ComparisonPicture.clamped(value, picture.panX, picture.panY));
    Widget pan(String direction, IconData icon, double dx, double dy) =>
        IconButton(
          tooltip: '$label画面$direction',
          onPressed: saving || picture.zoom == 1
              ? null
              : () => onChange(
                  ComparisonPicture.clamped(
                    picture.zoom,
                    picture.panX + dx,
                    picture.panY + dy,
                  ),
                ),
          icon: Icon(icon),
        );
    final apply = FilledButton(
      onPressed: saving ? null : onApply,
      key: const ValueKey('comparison-picture-apply'),
      style: FilledButton.styleFrom(
        backgroundColor: AppTheme.of(context).brandAction,
        foregroundColor: AppTheme.of(context).textOnAccent,
        minimumSize: const Size(48, 48),
      ),
      child: Text(saving ? '正在保存…' : '完成调整'),
    );
    final cancel = TextButton(
      onPressed: saving ? null : onCancel,
      key: const ValueKey('comparison-picture-cancel'),
      child: const Text('取消'),
    );
    final selector = SegmentedButton<bool>(
      showSelectedIcon: false,
      style: ButtonStyle(
        side: const WidgetStatePropertyAll(BorderSide.none),
        backgroundColor: const WidgetStatePropertyAll(Colors.transparent),
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected)
              ? AppTheme.of(context).brandAction
              : AppTheme.of(context).flowTextSecondary,
        ),
        minimumSize: const WidgetStatePropertyAll(Size(48, 48)),
      ),
      segments: const [
        ButtonSegment(value: true, label: Text('参考')),
        ButtonSegment(value: false, label: Text('目标')),
      ],
      selected: {reference},
      onSelectionChanged: saving ? null : (value) => onSelect(value.first),
    );
    final reset = IconButton(
      tooltip: '重置$label画面',
      onPressed: saving ? null : () => onChange(const ComparisonPicture()),
      icon: const Icon(Icons.restart_alt_rounded),
    );
    return Padding(
      padding: const EdgeInsets.all(12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (error != null)
            Text(error!, style: TextStyle(color: AppTheme.of(context).error)),
          LayoutBuilder(
            builder: (context, bounds) => bounds.maxWidth < 280
                ? Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      selector,
                      Align(alignment: Alignment.centerRight, child: reset),
                    ],
                  )
                : Row(
                    children: [
                      Expanded(child: selector),
                      reset,
                    ],
                  ),
          ),
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Flexible(child: Text('$label缩放', maxLines: 2)),
              Text('${picture.zoom.toStringAsFixed(2)}×', maxLines: 1),
            ],
          ),
          Row(
            children: [
              IconButton(
                tooltip: '缩小$label',
                onPressed: saving ? null : () => zoom(picture.zoom - .25),
                icon: const Icon(Icons.remove_rounded),
              ),
              Expanded(
                child: Semantics(
                  label: '$label缩放比例',
                  child: Slider(
                    key: const ValueKey('comparison-picture-zoom'),
                    min: 1,
                    max: 4,
                    value: picture.zoom,
                    semanticFormatterCallback: (value) =>
                        '${value.toStringAsFixed(2)}倍',
                    onChanged: saving ? null : zoom,
                  ),
                ),
              ),
              IconButton(
                tooltip: '放大$label',
                onPressed: saving ? null : () => zoom(picture.zoom + .25),
                icon: const Icon(Icons.add_rounded),
              ),
            ],
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              pan('左移', Icons.arrow_back_rounded, -.1, 0),
              pan('右移', Icons.arrow_forward_rounded, .1, 0),
              pan('上移', Icons.arrow_upward_rounded, 0, -.1),
              pan('下移', Icons.arrow_downward_rounded, 0, .1),
            ],
          ),
          LayoutBuilder(
            builder: (context, bounds) =>
                bounds.maxWidth < 280 ||
                    MediaQuery.textScalerOf(context).scale(14) > 19
                ? Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [apply, cancel],
                  )
                : Row(
                    children: [
                      cancel,
                      const SizedBox(width: 12),
                      Expanded(child: apply),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}
