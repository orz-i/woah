import 'package:go_router/go_router.dart';
import 'package:dance_domain/dance_domain.dart';
import '../features/import_video/presentation/import_video_screen.dart';
import '../features/person_selection/presentation/person_selection_screen.dart';

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
  ],
);
