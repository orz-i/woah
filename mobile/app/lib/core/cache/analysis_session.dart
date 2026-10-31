import 'dart:async';

import '../logging/app_logger.dart';

/// Explicit owner of one editing flow, not the shared controller's current state.
/// The editor remains below export/result and closes only its own session.
class AnalysisSession {
  AnalysisSession({required this.releaseCache});

  final Future<void> Function(String) releaseCache;
  final Set<String> _owned = {};
  final Set<String> _released = {};
  final Map<String, Future<void>> _pending = {};
  bool _closed = false;
  bool get isClosed => _closed;

  void track(String cacheId) {
    if (cacheId.isEmpty) return;
    _owned.add(cacheId);
    if (_closed) unawaited(_release(cacheId));
  }

  Future<void> releaseUnadopted(String cacheId) async {
    if (cacheId.isEmpty || (!_closed && _owned.contains(cacheId))) return;
    _owned.add(cacheId);
    await _release(cacheId);
  }

  Future<void> close() async {
    _closed = true;
    await Future.wait(_owned.toList().map(_release));
  }

  Future<void> _release(String cacheId) {
    if (_released.contains(cacheId)) return Future.value();
    return _pending.putIfAbsent(cacheId, () async {
      // Start after insertion, including repositories that synchronously throw.
      await Future<void>.value();
      try {
        await releaseCache(cacheId);
        _released.add(cacheId);
      } catch (_) {
        // Native detach and orphan maintenance are independent safety nets.
        // A failed request is not marked released; a later close may retry it.
        AppLogger.d('AnalysisSession', 'CACHE_RELEASE_DEFERRED');
      } finally {
        _pending.remove(cacheId);
      }
    });
  }
}
