import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import '../../../app/theme.dart';
import '../data/comparison_gateway.dart';
import 'comparison_controller.dart';

int parseComparisonTime(String value) {
  final match = RegExp(
    r'^(?:(\d+):)?(\d+)(?:\.(\d{1,6}))?$',
  ).firstMatch(value.trim());
  if (match == null) throw const FormatException('请输入秒或分:秒');
  final minutes = int.tryParse(match.group(1) ?? '0');
  final seconds = int.tryParse(match.group(2)!);
  if (minutes == null ||
      seconds == null ||
      minutes > 1440 ||
      seconds > 86400 ||
      (match.group(1) != null && seconds >= 60)) {
    throw const FormatException('时间无效');
  }
  final result =
      (minutes * 60 + seconds) * 1000000 +
      int.parse((match.group(3) ?? '').padRight(6, '0'));
  if (result > 86400000000) throw const FormatException('时间过大');
  return result;
}

String comparisonTime(int value) {
  final ms = value ~/ 1000;
  return '${(ms ~/ 60000).toString().padLeft(2, '0')}:${((ms ~/ 1000) % 60).toString().padLeft(2, '0')}.${(ms % 1000).toString().padLeft(3, '0')}';
}

class ComparisonScreen extends StatefulWidget {
  final ComparisonGateway? gateway;
  const ComparisonScreen({super.key, this.gateway});
  @override
  State<ComparisonScreen> createState() => _ComparisonScreenState();
}

class _ComparisonScreenState extends State<ComparisonScreen>
    with WidgetsBindingObserver {
  late final ComparisonController controller;
  bool _allowPop = false, _exiting = false;
  double? _scrub;
  @override
  void initState() {
    super.initState();
    controller = ComparisonController(
      widget.gateway ?? NativeComparisonGateway(),
    );
    WidgetsBinding.instance.addObserver(this);
    unawaited(controller.initialize());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused) {
      unawaited(controller.background());
    }
  }

  Future<void> _exit() async {
    if (_exiting) return;
    _exiting = true;
    final saved = await controller.finish();
    if (!mounted) return;
    if (!saved) {
      _exiting = false;
      return;
    }
    setState(() => _allowPop = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.pop();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    controller.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    var title = controller.project.title;
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('保存对比项目'),
        content: TextFormField(
          initialValue: title,
          maxLength: 80,
          decoration: const InputDecoration(labelText: '项目名称'),
          onChanged: (value) => title = value,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, title),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    if (result != null) await controller.save(title: result);
  }

  Future<void> _history() async {
    await controller.pause();
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            const ListTile(
              title: Text('本机对比项目'),
              subtitle: Text('视频位置和音乐校准会自动保存'),
            ),
            if (controller.projects.isEmpty)
              const ListTile(title: Text('还没有保存的对比')),
            for (final project in controller.projects)
              ListTile(
                leading: const Icon(Icons.video_library_outlined),
                title: Text(project.title),
                subtitle: Text(
                  '${project.teacher?.name ?? '待选老师'} · ${project.learner?.name ?? '待选学生'}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: () {
                  Navigator.pop(context);
                  unawaited(controller.selectProject(project));
                },
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _manual() async {
    await controller.pause();
    if (!mounted || !controller.project.hasPair) return;
    final values = await showDialog<List<int>>(
      context: context,
      builder: (_) => _MusicAnchorDialog(project: controller.project),
    );
    if (values != null) await controller.manual(values[0], values[1]);
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: _allowPop,
    onPopInvokedWithResult: (popped, _) {
      if (!popped) unawaited(_exit());
    },
    child: AnimatedBuilder(
      animation: controller,
      builder: (context, _) => Scaffold(
        resizeToAvoidBottomInset: false,
        backgroundColor: AppTheme.flowBackground,
        appBar: AppBar(
          title: const Text(
            '舞蹈对比',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          leading: BackButton(onPressed: _exit),
          actions: [
            IconButton(
              tooltip: '新建对比',
              onPressed: controller.busy ? null : controller.newProject,
              icon: const Icon(Icons.add),
            ),
            IconButton(
              tooltip: '本机项目',
              onPressed: controller.busy ? null : _history,
              icon: const Icon(Icons.history),
            ),
            IconButton(
              tooltip: '保存项目',
              onPressed: controller.saving || controller.busy ? null : _save,
              icon: const Icon(Icons.save_outlined),
            ),
          ],
        ),
        body: SafeArea(
          child: !controller.initialized
              ? const Center(child: CircularProgressIndicator())
              : LayoutBuilder(
                  builder: (context, viewport) {
                    final horizontal = viewport.maxWidth > viewport.maxHeight;
                    if (horizontal) {
                      return Row(
                        children: [
                          Expanded(child: _preview(true)),
                          SizedBox(
                            width: math.min(340, viewport.maxWidth * .43),
                            child: SingleChildScrollView(
                              padding: const EdgeInsets.all(12),
                              child: Column(
                                children: [
                                  _sources(),
                                  const SizedBox(height: 12),
                                  _controls(),
                                ],
                              ),
                            ),
                          ),
                        ],
                      );
                    }
                    return Column(
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
                          child: _sources(),
                        ),
                        Expanded(child: _preview(false)),
                        ConstrainedBox(
                          constraints: BoxConstraints(
                            maxHeight: viewport.maxHeight * .46,
                          ),
                          child: SingleChildScrollView(
                            padding: const EdgeInsets.all(12),
                            child: _controls(),
                          ),
                        ),
                      ],
                    );
                  },
                ),
        ),
      ),
    ),
  );
  Widget _sources() => Row(
    children: [
      Expanded(child: _source('teacher', '老师（参考）', controller.project.teacher)),
      IconButton(
        tooltip: '交换老师和学生',
        onPressed: controller.busy || !controller.project.hasPair
            ? null
            : controller.swap,
        icon: const Icon(Icons.swap_horiz),
      ),
      Expanded(child: _source('learner', '学生（自己）', controller.project.learner)),
    ],
  );
  Widget _source(String role, String label, ComparisonSource? source) =>
      OutlinedButton(
        key: ValueKey('comparison-pick-$role'),
        onPressed: controller.busy ? null : () => controller.pick(role),
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(label),
            const SizedBox(height: 4),
            Text(
              source?.name ?? '选择视频',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: 11,
                color: AppTheme.flowTextSecondary,
              ),
            ),
          ],
        ),
      );
  Widget _preview(bool horizontal) => LayoutBuilder(
    builder: (context, viewport) {
      final ratio = math.min(
        MediaQuery.devicePixelRatioOf(context),
        1920 / math.max(viewport.maxWidth, viewport.maxHeight),
      );
      final width = math.max(2, (viewport.maxWidth * ratio).round());
      final height = math.max(2, (viewport.maxHeight * ratio).round());
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) controller.setViewport(width, height, horizontal);
      });
      final state = controller.playback;
      return Container(
        key: const ValueKey('comparison-stage'),
        color: Colors.black,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (state != null && state.textureId >= 0)
              Texture(
                textureId: state.textureId,
                filterQuality: FilterQuality.low,
              ),
            if (!controller.project.hasPair)
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: Text(
                    '选择两段视频\n按同一段音乐观察动作差异',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppTheme.flowTextSecondary),
                  ),
                ),
              ),
            if (controller.project.hasPair &&
                (state == null || state.phase == 'opening'))
              const Center(
                child: CircularProgressIndicator(color: AppTheme.gold),
              ),
            if (controller.project.hasPair) ...[
              Positioned(top: 8, left: 8, child: _badge('老师 · 参考')),
              Positioned(
                top: horizontal ? 8 : viewport.maxHeight / 2 + 8,
                left: horizontal ? viewport.maxWidth / 2 + 8 : 8,
                child: _badge('学生 · 实际'),
              ),
            ],
          ],
        ),
      );
    },
  );
  Widget _badge(String label) => DecoratedBox(
    decoration: BoxDecoration(
      color: Colors.black54,
      borderRadius: BorderRadius.circular(5),
    ),
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      child: Text(label, style: const TextStyle(fontSize: 12)),
    ),
  );
  Widget _controls() {
    final state = controller.playback;
    final duration = state?.durationUs ?? 0;
    final position =
        (_scrub ??
                (state?.positionUs.toDouble() ??
                    controller.project.positionUs.toDouble()))
            .clamp(0.0, math.max(1, duration).toDouble());
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (controller.error != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              controller.error!,
              style: const TextStyle(color: AppTheme.error),
            ),
          ),
        Row(
          children: [
            if (controller.matching)
              const Padding(
                padding: EdgeInsets.only(right: 10),
                child: SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            Expanded(
              child: Text(
                controller.message,
                maxLines: 3,
                style: const TextStyle(
                  fontSize: 13,
                  color: AppTheme.flowTextSecondary,
                ),
              ),
            ),
            if (controller.matching)
              TextButton(
                onPressed: controller.cancelMatching,
                child: const Text('取消'),
              ),
          ],
        ),
        if (controller.project.hasPair) ...[
          const SizedBox(height: 6),
          Text(
            '音乐偏移 ${controller.project.offsetUs >= 0 ? '+' : ''}${(controller.project.offsetUs / 1e6).toStringAsFixed(3)}s · ${controller.project.alignment == 'manual'
                ? '手动校准'
                : controller.project.alignment == 'auto'
                ? '音乐匹配'
                : controller.project.alignment == 'candidate'
                ? '候选校准'
                : '待校准'}',
            style: const TextStyle(fontSize: 12),
          ),
          Slider(
            key: const ValueKey('comparison-seek'),
            min: 0,
            max: math.max(1, duration).toDouble(),
            value: position,
            onChanged: duration > 0 && !controller.busy
                ? (v) => setState(() => _scrub = v)
                : null,
            onChangeEnd: (v) {
              setState(() => _scrub = null);
              unawaited(controller.seek(v.round()));
            },
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(comparisonTime(position.round())),
              Text(comparisonTime(duration)),
            ],
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              IconButton(
                tooltip: '后退十秒',
                onPressed: controller.canPlay
                    ? () => controller.seek(
                        math.max(0, position.round() - 10000000),
                      )
                    : null,
                icon: const Icon(Icons.replay_10),
              ),
              IconButton(
                key: const ValueKey('comparison-play'),
                tooltip: controller.playing ? '暂停' : '播放',
                onPressed: controller.canPlay ? controller.toggle : null,
                iconSize: 38,
                color: AppTheme.gold,
                icon: Icon(
                  controller.playing
                      ? Icons.pause_circle_filled
                      : Icons.play_circle_fill,
                ),
              ),
              IconButton(
                tooltip: '前进十秒',
                onPressed: controller.canPlay
                    ? () => controller.seek(position.round() + 10000000)
                    : null,
                icon: const Icon(Icons.forward_10),
              ),
              const SizedBox(width: 8),
              DropdownButton<double>(
                value: controller.project.speed,
                underline: const SizedBox.shrink(),
                items: const [
                  DropdownMenuItem(value: 1, child: Text('1×')),
                  DropdownMenuItem(value: .75, child: Text('0.75×')),
                  DropdownMenuItem(value: .5, child: Text('0.5×')),
                ],
                onChanged: controller.busy
                    ? null
                    : (v) {
                        if (v != null) unawaited(controller.setSpeed(v));
                      },
              ),
            ],
          ),
          Wrap(
            alignment: WrapAlignment.center,
            spacing: 8,
            children: [
              TextButton.icon(
                key: const ValueKey('comparison-match'),
                onPressed: controller.busy ? null : controller.matchMusic,
                icon: const Icon(Icons.music_note_outlined, size: 18),
                label: const Text('匹配音乐'),
              ),
              TextButton.icon(
                key: const ValueKey('comparison-manual'),
                onPressed: controller.busy ? null : _manual,
                icon: const Icon(Icons.tune, size: 18),
                label: const Text('手动校准'),
              ),
            ],
          ),
          if (!controller.matching && controller.candidates.isNotEmpty) ...[
            const Divider(),
            const Align(alignment: Alignment.centerLeft, child: Text('可选音乐位置')),
            for (var i = 0; i < controller.candidates.length; i++)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(
                  '候选 ${i + 1} · 偏移 ${(controller.candidates[i].offsetUs / 1e6).toStringAsFixed(3)} 秒',
                ),
                subtitle: Text(
                  '${controller.candidates[i].consistentWindows}/3 段一致 · 共同段约 ${controller.candidates[i].supportedSeconds.toStringAsFixed(1)} 秒',
                ),
                trailing: TextButton(
                  onPressed: () =>
                      controller.applyCandidate(controller.candidates[i]),
                  child: const Text('试用并播放'),
                ),
              ),
          ],
        ],
      ],
    );
  }
}

class _MusicAnchorDialog extends StatefulWidget {
  final ComparisonProject project;
  const _MusicAnchorDialog({required this.project});
  @override
  State<_MusicAnchorDialog> createState() => _MusicAnchorDialogState();
}

class _MusicAnchorDialogState extends State<_MusicAnchorDialog> {
  late final TextEditingController teacher;
  late final TextEditingController learner;
  @override
  void initState() {
    super.initState();
    teacher = TextEditingController(
      text: widget.project.teacherAnchorUs == null
          ? ''
          : (widget.project.teacherAnchorUs! / 1e6).toStringAsFixed(6),
    );
    learner = TextEditingController(
      text: widget.project.learnerAnchorUs == null
          ? ''
          : (widget.project.learnerAnchorUs! / 1e6).toStringAsFixed(6),
    );
  }

  @override
  void dispose() {
    teacher.dispose();
    learner.dispose();
    super.dispose();
  }

  List<int>? _values() {
    try {
      final t = parseComparisonTime(teacher.text),
          l = parseComparisonTime(learner.text);
      if (t < widget.project.teacher!.durationUs &&
          l < widget.project.learner!.durationUs) {
        return [t, l];
      }
    } on FormatException {
      /* Incomplete input keeps the Apply action disabled. */
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final values = _values();
    return AlertDialog(
      scrollable: true,
      title: const Text('手动音乐校准'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text('分别输入同一歌词或鼓点在各自视频中的时间。秒或分:秒，最多六位小数。人物动作的早晚差异会保留。'),
          const SizedBox(height: 16),
          TextField(
            key: const ValueKey('comparison-anchor-teacher'),
            controller: teacher,
            decoration: const InputDecoration(
              labelText: '老师源时间',
              hintText: '例如 0 或 1:05.125',
            ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const ValueKey('comparison-anchor-learner'),
            controller: learner,
            decoration: const InputDecoration(
              labelText: '学生同一音乐事件源时间',
              hintText: '例如 2',
            ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 16),
          Text(
            values == null
                ? '请填写两段视频内的有效时间。'
                : '学生 − 老师 = ${((values[1] - values[0]) / 1e6).toStringAsFixed(3)} 秒',
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: values == null
              ? null
              : () {
                  final current = _values();
                  if (current != null) Navigator.pop(context, current);
                },
          child: const Text('应用校准'),
        ),
      ],
    );
  }
}
