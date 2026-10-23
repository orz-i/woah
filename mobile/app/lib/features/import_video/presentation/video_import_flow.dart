import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../protection_editor/presentation/protection_editor_screen.dart';
import '../domain/video_import_state.dart';
import 'import_video_controller.dart';

/// Shared launcher for any real, independently operable video-protection entry.
///
/// Keeps the existing picker, probe, editor route, and post-editor reset
/// consistent across Home and the tool library.
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
  await controller.pickAndProbeVideo();
  if (!context.mounted) return;

  final project = controller.createProject();
  if (project == null) return;

  await context.push(
    '/protection_editor',
    extra: ProtectionEditorArgs(project: project),
  );
  if (!context.mounted) return;
  controller.reset();
}
