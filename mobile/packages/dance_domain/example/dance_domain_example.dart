import 'package:dance_domain/dance_domain.dart';

void main() {
  final now = DateTime.now();
  final project = DanceProject(
    id: 'sample',
    sourceUri: 'file:///sample.mp4',
    videoInfo: const VideoInfo(
      codedWidth: 1920,
      codedHeight: 1080,
      displayWidth: 1920,
      displayHeight: 1080,
      fps: 30.0,
      durationMs: 3000,
      rotation: 0,
      videoCodec: 'h264',
      hasAudio: true,
    ),
    createdAt: now,
    updatedAt: now,
  );
  print('Created project: ${project.id} with ${project.videoInfo}');
}
