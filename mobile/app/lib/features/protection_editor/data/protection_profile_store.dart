import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../domain/protection_profile.dart';

final protectionProfileStoreProvider = Provider<ProtectionProfileStore>((ref) {
  return SharedPreferencesProtectionProfileStore();
});

abstract interface class ProtectionProfileStore {
  Future<ProtectionProfile?> load();
  Future<void> save(ProtectionProfile profile);
}

class SharedPreferencesProtectionProfileStore
    implements ProtectionProfileStore {
  static const _profileKey = 'woah.protection_profile.v1';

  @override
  Future<ProtectionProfile?> load() async {
    try {
      final preferences = await SharedPreferences.getInstance();
      final payload = preferences.getString(_profileKey);
      if (payload == null || payload.isEmpty) return null;
      final decoded = jsonDecode(payload);
      if (decoded is! Map) return null;
      return ProtectionProfile.fromJson(decoded.cast<String, dynamic>());
    } catch (_) {
      return null;
    }
  }

  @override
  Future<void> save(ProtectionProfile profile) async {
    try {
      final preferences = await SharedPreferences.getInstance();
      await preferences.setString(_profileKey, jsonEncode(profile.toJson()));
    } catch (_) {
      // Profile persistence is convenience-only and must not block editing.
    }
  }
}
