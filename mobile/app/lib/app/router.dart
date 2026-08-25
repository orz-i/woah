import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import '../features/import_video/presentation/import_video_screen.dart';
import '../features/person_selection/presentation/person_selection_screen.dart';
import '../features/effect_editor/presentation/effect_editor_screen.dart';
import '../features/export/presentation/export_screen.dart';
import '../features/export/presentation/result_screen.dart';
import '../features/export/domain/export_state.dart';

final appRouter = GoRouter(
  initialLocation: '/',
  routes: [
    GoRoute(
      path: '/',
      name: 'import_video',
      builder: (context, state) => const ImportVideoScreen(),
    ),
    GoRoute(
      path: '/person_selection',
      name: 'person_selection',
      builder: (context, state) {
        final project = state.extra as DanceProject;
        return PersonSelectionScreen(project: project);
      },
    ),
    GoRoute(
      path: '/effect_editor',
      name: 'effect_editor',
      builder: (context, state) {
        final project = state.extra as DanceProject;
        return EffectEditorScreen(project: project);
      },
    ),
    GoRoute(
      path: '/export',
      name: 'export',
      builder: (context, state) {
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
