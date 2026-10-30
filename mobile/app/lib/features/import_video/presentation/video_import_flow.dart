import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../protection_editor/presentation/protection_editor_screen.dart';
import '../domain/video_import_state.dart';
import 'home_tool_catalog.dart';
import 'import_video_controller.dart';

/// Standalone Home actions only. Editor-internal effects have no dispatch key.
Future<void> launchHomeTool(
  BuildContext context,
  WidgetRef ref,
  HomeTool tool,
) async {
  switch (tool.launch) {
    case HomeToolLaunch.videoProtection:
      await startVideoProtectionFlow(context, ref);
    case HomeToolLaunch.danceComparison:
      await context.push('/comparison');
  }
}

/// Preserve picker, media probing, unified editor, and post-editor reset.
Future<void> startVideoProtectionFlow(
  BuildContext context,
  WidgetRef ref,
) async {
  final state = ref.read(importVideoControllerProvider);
  if (state.status == VideoImportStatus.picking ||
      state.status == VideoImportStatus.probing) {
    return;
  }

  HapticFeedback.mediumImpact();
  final controller = ref.read(importVideoControllerProvider.notifier);
  final selected = await controller.pickAndProbeVideo();
  if (!context.mounted) return;

  // An error or canceled selection must not open the editor.
  if (!selected ||
      ref.read(importVideoControllerProvider).status !=
          VideoImportStatus.ready) {
    return;
  }
  final project = controller.createProject();
  if (project == null) return;

  await context.push(
    '/protection_editor',
    extra: ProtectionEditorArgs(project: project),
  );
  if (!context.mounted) return;
  controller.reset();
}
