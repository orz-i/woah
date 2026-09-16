import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import '../features/import_video/presentation/import_video_screen.dart';
import '../features/protection_editor/presentation/protection_editor_screen.dart';
import '../features/export/presentation/export_screen.dart';
import '../features/export/presentation/result_screen.dart';
import '../features/export/domain/export_state.dart';

final rootRouteObserver = RouteObserver<ModalRoute<void>>();

final appRouter = GoRouter(
  initialLocation: '/',
  observers: [rootRouteObserver],
  routes: [
    GoRoute(
      path: '/',
      name: 'import_video',
      builder: (context, state) => const ImportVideoScreen(),
    ),
    GoRoute(
      path: '/protection_editor',
      name: 'protection_editor',
      builder: (context, state) {
        final extra = state.extra;
        if (extra is ProtectionEditorArgs) {
          return ProtectionEditorScreen(
            project: extra.project,
            fullBodyDraft: extra.fullBodyDraft,
            faceOnlyDraft: extra.faceOnlyDraft,
          );
        }
        final project = extra as DanceProject;
        return ProtectionEditorScreen(project: project);
      },
    ),
    GoRoute(
      path: '/export',
      name: 'export',
      builder: (context, state) {
        if (state.extra is ExportArgs) {
          final args = state.extra as ExportArgs;
          return ExportScreen(
            project: args.project,
            initialPreviewPath: args.initialPreviewPath,
          );
        }
        final project = state.extra as DanceProject;
        return ExportScreen(project: project);
      },
    ),

    GoRoute(
      path: '/result',
      name: 'result',
      builder: (context, state) {
        final exportState = state.extra as ExportState;
        return ResultScreen(exportState: exportState);
      },
    ),
  ],
);
