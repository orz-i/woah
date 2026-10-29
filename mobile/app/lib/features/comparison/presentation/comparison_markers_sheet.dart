import 'package:flutter/material.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../app/theme.dart';
import 'comparison_controller.dart';
import 'comparison_time.dart';

class ComparisonMarkersSheet extends StatelessWidget {
  const ComparisonMarkersSheet({super.key, required this.controller});
  final ComparisonController controller;
  Future<void> _edit(BuildContext context, ComparisonMarker? marker) =>
      showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) => _MarkerEditor(controller: controller, marker: marker),
      );
  @override
  Widget build(BuildContext context) => SafeArea(
    child: AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final markers = List<ComparisonMarker>.of(controller.project.markers)
          ..sort(
            (a, b) => (controller.markerRange(a)?.startUs ?? 86400000001)
                .compareTo(controller.markerRange(b)?.startUs ?? 86400000001),
          );
        return Column(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      '问题片段',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  TextButton(
                    key: const ValueKey('comparison-marker-add'),
                    onPressed: controller.canPlay
                        ? () => _edit(context, null)
                        : null,
                    child: const Text('添加'),
                  ),
                  IconButton(
                    tooltip: '关闭问题片段',
                    onPressed: () => Navigator.pop(context),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
            ),
            if (controller.error != null)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Text(
                  controller.error!,
                  style: TextStyle(color: AppTheme.of(context).error),
                ),
              ),
            Expanded(
              child: ListView(
                children: [
                  if (markers.isEmpty)
                    const Padding(
                      padding: EdgeInsets.all(24),
                      child: Text('还没有问题片段。添加时可使用 A/B 区间，或调整起止时间。'),
                    ),
                  for (final marker in markers)
                    Builder(
                      builder: (context) {
                        final range = controller.markerRange(marker);
                        Future<void> open(bool repeat) async {
                          if (await controller.openMarker(
                                marker,
                                repeat: repeat,
                              ) &&
                              context.mounted) {
                            Navigator.pop(context);
                          }
                        }

                        return ListTile(
                          key: ValueKey('comparison-marker-${marker.id}'),
                          title: Text(
                            marker.note.isEmpty ? '问题片段' : marker.note,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Text(
                            range == null
                                ? '原视频未选中，或不在当前共同区间内'
                                : '${comparisonTime(range.startUs)} – ${comparisonTime(range.endUs)}${range.clipped ? ' · 部分可用' : ''}',
                          ),
                          onTap: range == null || controller.busy
                              ? null
                              : () => open(false),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                tooltip: '将片段设为 A/B 循环',
                                onPressed: range == null || controller.busy
                                    ? null
                                    : () => open(true),
                                icon: const Icon(Icons.repeat_rounded),
                              ),
                              PopupMenuButton<String>(
                                tooltip: '片段操作',
                                enabled: !controller.busy,
                                onSelected: (action) async {
                                  if (action == 'edit') {
                                    await _edit(context, marker);
                                  } else {
                                    await controller.deleteMarker(marker.id);
                                  }
                                },
                                itemBuilder: (_) => const [
                                  PopupMenuItem(
                                    value: 'edit',
                                    child: Text('编辑'),
                                  ),
                                  PopupMenuItem(
                                    value: 'delete',
                                    child: Text('删除'),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        );
                      },
                    ),
                ],
              ),
            ),
          ],
        );
      },
    ),
  );
}

class _MarkerEditor extends StatefulWidget {
  const _MarkerEditor({required this.controller, this.marker});
  final ComparisonController controller;
  final ComparisonMarker? marker;
  @override
  State<_MarkerEditor> createState() => _MarkerEditorState();
}

class _MarkerEditorState extends State<_MarkerEditor> {
  final form = GlobalKey<FormState>();
  late final TextEditingController start, end, note;
  late final bool editableRange;
  bool saving = false;
  String? error;
  @override
  void initState() {
    super.initState();
    final controller = widget.controller;
    final marker = widget.marker;
    final range = marker == null ? null : controller.markerRange(marker);
    editableRange = marker == null || (range != null && !range.clipped);
    final duration = controller.playback?.durationUs ?? 0;
    final project = controller.project;
    int a, b;
    if (marker != null) {
      a = editableRange ? range!.startUs : marker.startUs;
      b = editableRange ? range!.endUs : marker.endUs;
    } else if (project.loopStartUs != null &&
        project.loopEndUs != null &&
        project.loopEndUs! <= duration) {
      a = project.loopStartUs!;
      b = project.loopEndUs!;
    } else {
      a = project.positionUs.clamp(0, duration);
      if (duration - a < 1000000) a = (duration - 2000000).clamp(0, duration);
      b = (a + 2000000).clamp(0, duration);
    }
    start = TextEditingController(text: comparisonPreciseTime(a));
    end = TextEditingController(text: comparisonPreciseTime(b));
    note = TextEditingController(text: marker?.note ?? '');
  }

  @override
  void dispose() {
    start.dispose();
    end.dispose();
    note.dispose();
    super.dispose();
  }

  Future<void> save() async {
    if (saving || !form.currentState!.validate()) return;
    setState(() {
      saving = true;
      error = null;
    });
    try {
      final result = await widget.controller.saveMarker(
        existing: widget.marker,
        startUs: parseComparisonTime(start.text),
        endUs: parseComparisonTime(end.text),
        note: note.text,
      );
      if (!mounted) return;
      if (result) {
        Navigator.pop(context);
      } else {
        setState(() => error = widget.controller.error ?? '片段未保存，请重试。');
      }
    } on FormatException catch (failure) {
      if (mounted) setState(() => error = failure.message);
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  String? timeError(String? value, bool isEnd) {
    if (!editableRange) return null;
    try {
      final time = parseComparisonTime(value ?? '');
      if (time > (widget.controller.playback?.durationUs ?? 0)) {
        return '时间必须位于共同区间内';
      }
      if (isEnd && time <= parseComparisonTime(start.text)) {
        return '结束时间需晚于开始时间';
      }
    } on FormatException catch (failure) {
      return failure.message;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !saving,
    child: AlertDialog(
      title: Text(widget.marker == null ? '标记问题片段' : '编辑问题片段'),
      content: SingleChildScrollView(
        child: Form(
          key: form,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (!editableRange) const Text('原始时间保留，当前仅可修改备注。定位和循环使用当前可用部分。'),
              TextFormField(
                key: const ValueKey('comparison-marker-start'),
                controller: start,
                enabled: editableRange && !saving,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                decoration: InputDecoration(
                  labelText: editableRange ? '开始时间' : '原视频起点',
                  helperText: '秒，或分:秒',
                ),
                validator: (value) => timeError(value, false),
              ),
              const SizedBox(height: 12),
              TextFormField(
                key: const ValueKey('comparison-marker-end'),
                controller: end,
                enabled: editableRange && !saving,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                decoration: InputDecoration(
                  labelText: editableRange ? '结束时间' : '原视频终点',
                ),
                validator: (value) => timeError(value, true),
              ),
              const SizedBox(height: 12),
              TextFormField(
                key: const ValueKey('comparison-marker-note'),
                controller: note,
                enabled: !saving,
                maxLines: 3,
                maxLength: 160,
                decoration: const InputDecoration(labelText: '问题备注（可选）'),
              ),
              if (error != null)
                Text(
                  error!,
                  style: TextStyle(color: AppTheme.of(context).error),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: saving ? null : () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          key: const ValueKey('comparison-marker-save'),
          onPressed: saving ? null : save,
          child: Text(saving ? '正在保存…' : '保存'),
        ),
      ],
    ),
  );
}
