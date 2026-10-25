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
  Future<ComparisonPlaybackDto> resize(int width, int height, bool horizontal);
  Future<ComparisonPlaybackDto> state();
  Future<MusicAlignmentDto> align(ComparisonProject project);
  Future<void> cancelAlignment();
  Future<void> close();
}

class NativeComparisonGateway implements ComparisonGateway {
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
  );
  @override
  Future<ComparisonPlaybackDto> control(
    ComparisonCommand command,
    int valueUs,
    double speed,
  ) => _api.control(command, valueUs, speed);
  @override
  Future<ComparisonPlaybackDto> resize(
    int width,
    int height,
    bool horizontal,
  ) => _api.resize(width, height, horizontal);
  @override
  Future<ComparisonPlaybackDto> state() => _api.getState();
  @override
  Future<MusicAlignmentDto> align(ComparisonProject p) =>
      _api.alignMusic(p.teacher!.uri, p.learner!.uri);
  @override
  Future<void> cancelAlignment() => _api.cancelAlignment();
  @override
  Future<void> close() => _api.close();
}
