import 'package:go_router/go_router.dart';
import '../features/import_video/presentation/import_video_screen.dart';

final appRouter = GoRouter(
  initialLocation: '/',
  routes: [
    GoRoute(
      path: '/',
      name: 'import_video',
      builder: (context, state) => const ImportVideoScreen(),
    ),
  ],
);
