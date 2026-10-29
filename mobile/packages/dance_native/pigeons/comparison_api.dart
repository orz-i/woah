import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/src/bridge/comparison_api.g.dart',
  dartPackageName: 'dance_native',
  kotlinOut: 'android/src/main/kotlin/art/gaoge/dance/engine/bridge/comparison/ComparisonApi.g.kt',
  kotlinOptions: KotlinOptions(package: 'art.gaoge.dance.engine.bridge.comparison'),
  swiftOut: 'ios/dance_native/Sources/dance_native/ComparisonApi.g.swift',
  swiftOptions: SwiftOptions(fileSpecificClassNameComponent: 'Comparison', includeErrorClass: false),
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
  int? teacherFrameUs;
  int? learnerFrameUs;
  double? teacherAspect;
  double? learnerAspect;
  double? timeScale;
  ComparisonPlaybackDto({required this.textureId, required this.phase,
    required this.positionUs, required this.durationUs, required this.teacherStartUs,
    required this.offsetUs, required this.speed, this.error,
    this.teacherFrameUs, this.learnerFrameUs, this.teacherAspect, this.learnerAspect, this.timeScale});
}

class ComparisonPictureDto {
  double zoom;
  double panX;
  double panY;
  ComparisonPictureDto({required this.zoom, required this.panX, required this.panY});
}

class ComparisonPracticeDto {
  bool mirrorTeacher;
  bool mirrorLearner;
  int? loopStartUs;
  int? loopEndUs;
  bool loopEnabled;
  ComparisonPictureDto? teacherPicture;
  ComparisonPictureDto? learnerPicture;
  ComparisonPracticeDto({required this.mirrorTeacher, required this.mirrorLearner,
    this.loopStartUs, this.loopEndUs, required this.loopEnabled,
    this.teacherPicture, this.learnerPicture});
}

class MusicCandidateDto {
  int offsetUs;
  double score;
  int consistentWindows;
  double supportedSeconds;
  double residualMs;
  double? timeScale;
  MusicCandidateDto({required this.offsetUs, required this.score,
    required this.consistentWindows, required this.supportedSeconds,
    required this.residualMs, this.timeScale});
}

class MusicAlignmentDto {
  String status;
  String message;
  List<MusicCandidateDto> candidates;
  MusicAlignmentDto({required this.status, required this.message,
    required this.candidates});
}

enum ComparisonCommand { play, pause, seek, setOffset, setSpeed }

class ComparisonExportDto {
  String jobId;
  String projectId;
  String state;
  String stage;
  double progress;
  int startUs;
  int endUs;
  int width;
  int height;
  String? outputPath;
  String? savedUri;
  String? error;
  ComparisonExportDto({required this.jobId, required this.projectId, required this.state,
    required this.stage, required this.progress, required this.startUs, required this.endUs,
    required this.width, required this.height, this.outputPath, this.savedUri, this.error});
}

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
    int positionUs, double speed, int width, int height, bool sideBySide,
    ComparisonPracticeDto practice, double timeScale);
  @async
  ComparisonPlaybackDto control(ComparisonCommand command, int valueUs, double speed);
  @async
  ComparisonPlaybackDto setAlignment(int offsetUs, double timeScale);
  @async
  ComparisonPlaybackDto resize(int width, int height, bool sideBySide);
  @async
  ComparisonPlaybackDto setPractice(ComparisonPracticeDto practice);
  @async
  ComparisonPlaybackDto stepFrame(bool forward);
  @async
  ComparisonPlaybackDto getState();
  @async
  MusicAlignmentDto alignMusic(String teacherUri, String learnerUri);
  @async
  void cancelAlignment();
  @async
  ComparisonExportDto startExport(String projectJson, int startUs, int endUs,
    int width, int height, int gap, bool sideBySide);
  @async
  ComparisonExportDto? getExport();
  @async
  ComparisonExportDto cancelExport(String jobId);
  @async
  ComparisonExportDto retryExport(String jobId);
  @async
  ComparisonExportDto saveExport(String jobId);
  @async
  void openExport(String jobId);
  @async
  void close();
}
