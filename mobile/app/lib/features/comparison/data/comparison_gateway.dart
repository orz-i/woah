import 'dart:convert';
import 'package:dance_domain/dance_domain.dart';
import 'package:dance_native/dance_native.dart';

class ComparisonLibrary {
  final List<ComparisonProject> projects;
  final int unreadableCount;
  const ComparisonLibrary(this.projects, this.unreadableCount);
}

abstract interface class ComparisonGateway {
  Future<ComparisonSource?> pick(String role);
  Future<ComparisonLibrary> load();
  Future<void> save(ComparisonProject project);
  Future<ComparisonPlaybackDto> open(
    ComparisonProject project,
    int width,
    int height,
    bool horizontal,
  );
  Future<ComparisonPlaybackDto> control(
    ComparisonCommand command,
    int valueUs,
    double speed,
  );
  Future<ComparisonPlaybackDto> setAlignment(int offsetUs, double timeScale);
  Future<ComparisonPlaybackDto> resize(int width, int height, bool horizontal);
  Future<ComparisonPlaybackDto> setPractice(ComparisonProject project);
  Future<ComparisonPlaybackDto> stepFrame(bool forward);
  Future<ComparisonPlaybackDto> state();
  Future<MusicAlignmentDto> align(ComparisonProject project);
  Future<void> cancelAlignment();
  Future<ComparisonExportDto> startExport(
    ComparisonExportPlan plan, {
    bool replacePreviousUnsaved = false,
  });
  Future<ComparisonExportDto?> getExport();
  Future<ComparisonExportDto> cancelExport(String id);
  Future<ComparisonExportDto> retryExport(String id);
  Future<ComparisonExportDto> saveExport(String id);
  Future<void> openExport(String id);
  Future<void> close();
}

abstract interface class ComparisonCutoutGateway {
  Future<ComparisonCutoutPreviewDto> inspectCutout(
    bool reference,
    ComparisonCutoutSeedDto seed,
  );
  Future<ComparisonCutoutResultDto> prepareCutout(
    ComparisonCutoutSeedDto reference,
    ComparisonCutoutSeedDto target,
    ComparisonCanvasLayoutDto? layout,
  );
  Future<ComparisonPlaybackDto> setCutoutLayout(
    ComparisonCanvasLayoutDto layout,
  );
  Future<ComparisonPlaybackDto> clearCutout();
  Future<ComparisonPlaybackDto> resumeCutoutPreview();
}

abstract interface class ComparisonProgressiveCutoutGateway {
  Future<ComparisonCutoutResultDto?> cutoutStatus();
}

abstract interface class ComparisonSavedCutoutGateway {
  Future<bool> saveCutout(String projectId);
  Future<ComparisonCutoutResultDto?> restoreCutout(
    String projectId,
    ComparisonCanvasLayoutDto? layout,
  );
  Future<void> forgetCutout(String projectId);
}

class NativeComparisonGateway
    implements
        ComparisonGateway,
        ComparisonCutoutGateway,
        ComparisonProgressiveCutoutGateway,
        ComparisonSavedCutoutGateway {
  final DanceComparisonApi _api;
  NativeComparisonGateway({DanceComparisonApi? api})
    : _api = api ?? DanceComparisonApi();
  @override
  Future<ComparisonSource?> pick(String role) async {
    final value = await _api.pickVideo(role);
    return value == null
        ? null
        : ComparisonSource(
            uri: value.uri,
            name: value.name,
            durationUs: value.durationUs,
            hasAudio: value.hasAudio,
          );
  }

  @override
  Future<ComparisonLibrary> load() async {
    final projects = <ComparisonProject>[];
    var unreadable = 0;
    for (final json in await _api.loadProjects()) {
      try {
        projects.add(
          ComparisonProject.fromJson(
            Map<String, dynamic>.from(jsonDecode(json) as Map),
          ),
        );
      } catch (_) {
        unreadable++;
      }
    }
    projects.sort((a, b) => b.updatedAtMs.compareTo(a.updatedAtMs));
    return ComparisonLibrary(projects, unreadable);
  }

  @override
  Future<void> save(ComparisonProject project) =>
      _api.saveProject(jsonEncode(project.toJson()));
  @override
  Future<ComparisonPlaybackDto> open(
    ComparisonProject p,
    int width,
    int height,
    bool horizontal,
  ) => _api.open(
    p.teacher!.uri,
    p.learner!.uri,
    p.offsetUs,
    p.positionUs,
    p.speed,
    width,
    height,
    horizontal,
    _practice(p),
    p.timeScale,
  );
  static ComparisonPracticeDto _practice(ComparisonProject p) =>
      ComparisonPracticeDto(
        mirrorTeacher: p.mirrorTeacher,
        mirrorLearner: p.mirrorLearner,
        loopStartUs: p.loopStartUs,
        loopEndUs: p.loopEndUs,
        loopEnabled: p.loopEnabled,
        teacherPicture: ComparisonPictureDto(
          zoom: p.teacherPicture.zoom,
          panX: p.teacherPicture.panX,
          panY: p.teacherPicture.panY,
        ),
        learnerPicture: ComparisonPictureDto(
          zoom: p.learnerPicture.zoom,
          panX: p.learnerPicture.panX,
          panY: p.learnerPicture.panY,
        ),
      );
  @override
  Future<ComparisonPlaybackDto> setPractice(ComparisonProject p) =>
      _api.setPractice(_practice(p));
  @override
  Future<ComparisonPlaybackDto> stepFrame(bool forward) =>
      _api.stepFrame(forward);
  @override
  Future<ComparisonPlaybackDto> control(
    ComparisonCommand command,
    int valueUs,
    double speed,
  ) => _api.control(command, valueUs, speed);
  @override
  Future<ComparisonPlaybackDto> setAlignment(int offsetUs, double timeScale) =>
      _api.setAlignment(offsetUs, timeScale);
  @override
  Future<ComparisonPlaybackDto> resize(
    int width,
    int height,
    bool horizontal,
  ) => _api.resize(width, height, horizontal);
  @override
  Future<ComparisonCutoutPreviewDto> inspectCutout(
    bool reference,
    ComparisonCutoutSeedDto seed,
  ) => _api.inspectCutout(reference, seed);
  @override
  Future<ComparisonCutoutResultDto> prepareCutout(
    ComparisonCutoutSeedDto reference,
    ComparisonCutoutSeedDto target,
    ComparisonCanvasLayoutDto? layout,
  ) => _api.prepareCutout(reference, target, layout);
  @override
  Future<ComparisonCutoutResultDto?> cutoutStatus() => _api.getCutoutStatus();
  @override
  Future<bool> saveCutout(String projectId) => _api.saveCutout(projectId);
  @override
  Future<ComparisonCutoutResultDto?> restoreCutout(
    String projectId,
    ComparisonCanvasLayoutDto? layout,
  ) => _api.restoreCutout(projectId, layout);
  @override
  Future<void> forgetCutout(String projectId) => _api.forgetCutout(projectId);
  @override
  Future<ComparisonPlaybackDto> setCutoutLayout(
    ComparisonCanvasLayoutDto layout,
  ) => _api.setCutoutLayout(layout);
  @override
  Future<ComparisonPlaybackDto> resumeCutoutPreview() =>
      _api.resumeCutoutPreview();
  @override
  Future<ComparisonPlaybackDto> clearCutout() => _api.clearCutout();
  @override
  Future<ComparisonPlaybackDto> state() => _api.getState();
  @override
  Future<MusicAlignmentDto> align(ComparisonProject p) =>
      _api.alignMusic(p.teacher!.uri, p.learner!.uri);
  @override
  Future<void> cancelAlignment() => _api.cancelAlignment();
  @override
  Future<ComparisonExportDto> startExport(
    ComparisonExportPlan p, {
    bool replacePreviousUnsaved = false,
  }) => (p.cutout ? _api.startCutoutExport : _api.startExport)(
    jsonEncode(p.project.toJson()),
    p.startUs,
    p.endUs,
    p.width,
    p.height,
    p.gap,
    p.sideBySide,
    replacePreviousUnsaved,
  );
  @override
  Future<ComparisonExportDto?> getExport() => _api.getExport();
  @override
  Future<ComparisonExportDto> cancelExport(String id) => _api.cancelExport(id);
  @override
  Future<ComparisonExportDto> retryExport(String id) => _api.retryExport(id);
  @override
  Future<ComparisonExportDto> saveExport(String id) => _api.saveExport(id);
  @override
  Future<void> openExport(String id) => _api.openExport(id);
  @override
  Future<void> close() => _api.close();
}

ComparisonCanvasLayoutDto canvasLayoutDto(ComparisonCanvasLayout layout) {
  ComparisonCanvasLayerDto layer(ComparisonCanvasLayer value) =>
      ComparisonCanvasLayerDto(
        x: value.x,
        y: value.y,
        height: value.height,
        opacity: value.opacity,
        mirror: value.mirror,
      );
  return ComparisonCanvasLayoutDto(
    reference: layer(layout.reference),
    target: layer(layout.target),
    targetOnTop: layout.targetOnTop,
    background: layout.background,
  );
}
