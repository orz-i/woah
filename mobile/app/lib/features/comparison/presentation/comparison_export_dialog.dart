import 'dart:async';
import 'package:flutter/material.dart';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';
import '../data/comparison_gateway.dart';
import '../../../app/theme.dart';
import 'comparison_time.dart';

class ComparisonExportDialog extends StatefulWidget {
  const ComparisonExportDialog({
    super.key,
    required this.gateway,
    required this.full,
    required this.selection,
    this.existingJob,
  }) : assert(full != null || existingJob != null);
  final ComparisonGateway gateway;
  final ComparisonExportPlan? full;
  final ComparisonExportDto? existingJob;
  final ComparisonExportPlan? selection;
  @override
  State<ComparisonExportDialog> createState() => _ComparisonExportDialogState();
}

class _ComparisonExportDialogState extends State<ComparisonExportDialog> {
  ComparisonExportDto? _job, _previous;
  Timer? _timer;
  bool _loading = true, _working = false, _polling = false, _selection = false;
  bool _closeAfterCancel = false;
  bool _closing = false, _confirmingReplacement = false;
  String? _error;
  bool _active(ComparisonExportDto? value) =>
      value != null &&
      const [
        'queued',
        'encoding',
        'publishing',
        'cancelling',
      ].contains(value.state);
  @override
  void initState() {
    super.initState();
    _job = widget.existingJob;
    unawaited(_load());
    _timer = Timer.periodic(const Duration(milliseconds: 400), (_) => _poll());
  }

  Future<void> _load() async {
    try {
      final previous = await widget.gateway.getExport();
      if (!mounted || _closing) return;
      if (widget.existingJob != null) {
        _job = previous ?? widget.existingJob;
      } else if (_active(previous)) {
        _job = previous;
      } else if (previous != null) {
        // Keep the preceding result visible even when it belongs to a different
        // project: an unsaved local movie must not be silently replaced.
        _previous = previous;
      }
    } catch (_) {
      if (!_closing) _error = '上次导出记录暂时无法读取，可开始新的导出。';
    }
    if (mounted && !_closing) setState(() => _loading = false);
  }

  Future<void> _poll() async {
    if (!_active(_job) || _polling || !mounted || _closing) return;
    _polling = true;
    try {
      final current = await widget.gateway.getExport();
      if (mounted && !_closing && current?.jobId == _job?.jobId) {
        setState(() => _job = current);
        if (_closeAfterCancel && !_active(current)) {
          _close();
        }
      }
    } catch (_) {
      if (mounted && !_closing) {
        setState(() => _error = '进度暂时无法更新，返回应用后可继续查看。');
      }
    } finally {
      _polling = false;
    }
  }

  Future<void> _run(Future<ComparisonExportDto> Function() operation) async {
    if (_working || _closing || !mounted) return;
    setState(() {
      _working = true;
      _error = null;
    });
    try {
      final result = await operation();
      if (mounted && !_closing) setState(() => _job = result);
    } catch (_) {
      if (mounted && !_closing) setState(() => _error = '操作未完成，请重试。');
    } finally {
      if (mounted && !_closing) setState(() => _working = false);
    }
  }

  Future<void> _startExport(ComparisonExportPlan plan) async {
    if (_working || _closing || _confirmingReplacement || !mounted) return;
    final previous = _previous;
    var replacePreviousUnsaved = false;
    if (previous != null &&
        previous.state == 'completed' &&
        previous.outputPath != null &&
        previous.savedUri == null) {
      setState(() => _confirmingReplacement = true);
      bool? confirmed;
      try {
        confirmed = await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (context) => AlertDialog(
            title: const Text('替换未保存的导出？'),
            content: const Text(
              '上次导出的本地视频尚未保存到相册或文件。开始新导出后，旧成片将从应用中删除，且无法恢复。建议先保存上次导出。',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('返回保存'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('替换旧成片'),
              ),
            ],
          ),
        );
      } finally {
        if (mounted) setState(() => _confirmingReplacement = false);
      }
      if (!mounted || _closing || confirmed != true) return;
      replacePreviousUnsaved = true;
    }
    await _run(
      () => widget.gateway.startExport(
        plan,
        replacePreviousUnsaved: replacePreviousUnsaved,
      ),
    );
  }

  Future<void> _cancel({bool close = false}) async {
    if (_closing || !mounted) return;
    if (close) _closeAfterCancel = true;
    if (!_active(_job)) {
      if (_closeAfterCancel) _close();
      return;
    }
    if (_working) return;
    await _run(() => widget.gateway.cancelExport(_job!.jobId));
    if (_closeAfterCancel && !_active(_job)) _close();
  }

  void _close() {
    if (!mounted || _closing || ModalRoute.of(context)?.isCurrent != true) {
      return;
    }
    _closing = true;
    _timer?.cancel();
    Navigator.pop(context);
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final job = _job;
    final active = _active(job);
    final plan = _selection ? widget.selection : widget.full;
    final isIOS = Theme.of(context).platform == TargetPlatform.iOS;
    return PopScope(
      canPop: !active && !_working && !_loading,
      onPopInvokedWithResult: (popped, _) {
        if (popped) {
          _closing = true;
          _timer?.cancel();
        } else if (active && !_closing) {
          unawaited(_cancel(close: true));
        }
      },
      child: AlertDialog(
        title: const Text('导出对比视频'),
        content: SizedBox(
          width: 360,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (_loading)
                  const Center(child: CircularProgressIndicator())
                else if (job == null) ...[
                  RadioGroup<bool>(
                    groupValue: _selection,
                    onChanged: (v) {
                      if (!_working) setState(() => _selection = v!);
                    },
                    child: Column(
                      children: [
                        RadioListTile<bool>(
                          value: false,
                          title: const Text('全段'),
                          subtitle: Text(
                            comparisonTime(widget.full!.durationUs),
                          ),
                          contentPadding: EdgeInsets.zero,
                        ),
                        RadioListTile<bool>(
                          value: true,
                          title: const Text('A/B 选段'),
                          enabled: widget.selection != null,
                          subtitle: Text(
                            widget.selection == null
                                ? '请先在播放条上选定起止点'
                                : comparisonTime(widget.selection!.durationUs),
                          ),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ],
                    ),
                  ),
                  Text(
                    '${plan!.width} × ${plan.height} · 1× · H.264/AAC',
                    style: const TextStyle(fontSize: 13),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    widget.full!.project.teacher!.hasAudio
                        ? '使用参考音频，画面与当前预览一致'
                        : '参考视频无音轨，导出静音视频',
                    style: TextStyle(
                      fontSize: 12,
                      color: AppTheme.of(context).flowTextSecondary,
                    ),
                  ),
                  if (_previous != null) ...[
                    const SizedBox(height: 12),
                    if (_previous!.state == 'completed' &&
                        _previous!.savedUri == null &&
                        _previous!.outputPath != null)
                      Padding(
                        padding: EdgeInsets.only(bottom: 8),
                        child: Text(
                          '上次导出尚未保存，开始新导出需要确认替换。',
                          style: TextStyle(
                            fontSize: 12,
                            color: AppTheme.of(context).flowTextSecondary,
                          ),
                        ),
                      ),
                    if (_previous!.state == 'completed')
                      TextButton(
                        onPressed: _working
                            ? null
                            : () => setState(() => _job = _previous),
                        child: const Text('查看上次导出'),
                      )
                    else
                      TextButton(
                        onPressed: _working
                            ? null
                            : () => _run(
                                () => widget.gateway.retryExport(
                                  _previous!.jobId,
                                ),
                              ),
                        child: const Text('重试上次中断或失败的导出'),
                      ),
                  ],
                ] else ...[
                  Text(
                    job.stage,
                    key: const ValueKey('comparison-export-stage'),
                  ),
                  const SizedBox(height: 12),
                  if (active) ...[
                    LinearProgressIndicator(value: job.progress.clamp(0, 1)),
                    const SizedBox(height: 8),
                    Text('${(job.progress * 100).floor()}%'),
                    const SizedBox(height: 12),
                    Text(
                      isIOS
                          ? 'iOS 后台运行时间由系统限制，建议保持应用在前台；中断后可返回重试。'
                          : '切到后台后会继续导出',
                      style: TextStyle(
                        fontSize: 12,
                        color: AppTheme.of(context).flowTextSecondary,
                      ),
                    ),
                  ] else
                    Text(
                      '${job.width} × ${job.height} · 1× · ${comparisonTime(job.endUs - job.startUs)}',
                      style: const TextStyle(fontSize: 13),
                    ),
                  if (job.error != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(
                        job.error!,
                        style: TextStyle(color: AppTheme.of(context).error),
                      ),
                    ),
                ],
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      _error!,
                      style: TextStyle(color: AppTheme.of(context).error),
                    ),
                  ),
              ],
            ),
          ),
        ),
        actions: [
          if (!active)
            TextButton(
              onPressed: _working || _loading ? null : _close,
              child: Text(job == null ? '取消' : '完成'),
            ),
          if (job == null)
            FilledButton(
              onPressed: _working || _loading || _confirmingReplacement
                  ? null
                  : () => _startExport(plan!),
              child: const Text('开始导出'),
            )
          else if (active)
            TextButton(
              onPressed: _working || job.state == 'cancelling'
                  ? null
                  : () => _cancel(),
              child: const Text('取消导出'),
            )
          else if (job.state == 'completed') ...[
            if (widget.full != null && !(isIOS && job.savedUri == null))
              TextButton(
                onPressed: _working
                    ? null
                    : () => setState(() {
                        _previous = job;
                        _job = null;
                        _error = null;
                      }),
                child: const Text('重新导出'),
              ),
            if (isIOS && job.savedUri == null)
              TextButton(
                onPressed: _working
                    ? null
                    : () => _run(() => widget.gateway.saveExport(job.jobId)),
                child: const Text('保存到照片'),
              ),
            FilledButton(
              onPressed: _working
                  ? null
                  : () async {
                      if (!isIOS && job.savedUri == null) {
                        await _run(() => widget.gateway.saveExport(job.jobId));
                      } else {
                        try {
                          await widget.gateway.openExport(job.jobId);
                        } catch (_) {
                          if (mounted) {
                            setState(
                              () => _error = isIOS
                                  ? '暂时无法打开视频，请重试。'
                                  : '暂时无法打开系统视频查看器，请从相册查看。',
                            );
                          }
                        }
                      }
                    },
              child: Text(!isIOS && job.savedUri == null ? '保存视频' : '查看视频'),
            ),
          ] else
            FilledButton(
              onPressed: _working
                  ? null
                  : () => _run(() => widget.gateway.retryExport(job.jobId)),
              child: const Text('重试导出'),
            ),
        ],
      ),
    );
  }
}
