import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/src/bridge/comparison_api.g.dart',
  dartPackageName: 'dance_native',
  kotlinOut: 'android/src/main/kotlin/art/gaoge/dance/engine/bridge/comparison/ComparisonApi.g.kt',
  kotlinOptions: KotlinOptions(package: 'art.gaoge.dance.engine.bridge.comparison'),
))
class ComparisonSourceDto {
  String uri;
  String name;
  int durationUs;
  bool hasAudio;
  ComparisonSourceDto({required this.uri, required this.name,
    required this.durationUs, required this.hasAudio});
}

class ComparisonPlaybackDto {
  int textureId;
  String phase;
  int positionUs;
  int durationUs;
  int teacherStartUs;
  int offsetUs;
  double speed;
  String? error;
  ComparisonPlaybackDto({required this.textureId, required this.phase,
    required this.positionUs, required this.durationUs, required this.teacherStartUs,
    required this.offsetUs, required this.speed, this.error});
}

class MusicCandidateDto {
  int offsetUs;
  double score;
  int consistentWindows;
  double supportedSeconds;
  double residualMs;
  MusicCandidateDto({required this.offsetUs, required this.score,
    required this.consistentWindows, required this.supportedSeconds,
    required this.residualMs});
}

class MusicAlignmentDto {
  String status;
  String message;
  List<MusicCandidateDto> candidates;
  MusicAlignmentDto({required this.status, required this.message,
    required this.candidates});
}

enum ComparisonCommand { play, pause, seek, setOffset, setSpeed }

@HostApi()
abstract class DanceComparisonApi {
  @async
  ComparisonSourceDto? pickVideo(String role);
  @async
  List<String> loadProjects();
  @async
  void saveProject(String projectJson);
  @async
  ComparisonPlaybackDto open(String teacherUri, String learnerUri, int offsetUs,
    int positionUs, double speed, int width, int height, bool sideBySide);
  @async
  ComparisonPlaybackDto control(ComparisonCommand command, int valueUs, double speed);
  @async
  ComparisonPlaybackDto resize(int width, int height, bool sideBySide);
  @async
  ComparisonPlaybackDto getState();
  @async
  MusicAlignmentDto alignMusic(String teacherUri, String learnerUri);
  @async
  void cancelAlignment();
  @async
  void close();
}
