import 'package:flutter/material.dart';

class AppTheme {
  static ThemeData get darkTheme {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xFF6750A4),
        brightness: Brightness.dark,
        surface: const Color(0xFF141218),
      ),
      scaffoldBackgroundColor: const Color(0xFF141218),
      cardTheme: const CardThemeData(
        color: Color(0xFF1D1B20),
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(16)),
        ),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Color(0xFF141218),
        elevation: 0,
        centerTitle: true,
      ),
    );
  }
}
