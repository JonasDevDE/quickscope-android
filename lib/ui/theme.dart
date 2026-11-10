import 'package:flutter/material.dart';

abstract final class AppColors {
  static const Color black = Color(0xFF000000);
  static const Color card = Color(0xFF1E1E1E);
  static const Color cardHigh = Color(0xFF2B2B2B);
  static const Color sheet = Color(0xFF141414);
  static const Color surfaceRaised = cardHigh;
  static const Color outline = Color(0xFF454545);
  static const Color pressed = Color(0xFF3A3A3A);
  static const Color dangerOutline = Color(0xFF8A3B3B);
  static const Color accent = Color(0xFFD4FF3A);
  static const Color onAccent = Color(0xFF000000);
  static const Color muted = Color(0xFF9E9EA4);
  static const Color danger = Color(0xFFFF6B6B);
  static const Color warn = Color(0xFFFFC857);
  static const Color soft = Color(0xFFFF9F5A);
}

final ThemeData quickscopeTheme = ThemeData(
  useMaterial3: true,
  brightness: Brightness.dark,
  scaffoldBackgroundColor: AppColors.black,
  canvasColor: AppColors.black,
  colorScheme: const ColorScheme.dark(
    primary: AppColors.accent,
    onPrimary: AppColors.onAccent,
    secondary: AppColors.accent,
    onSecondary: AppColors.onAccent,
    surface: AppColors.black,
    onSurface: Colors.white,
    surfaceContainerLowest: AppColors.black,
    surfaceContainerLow: AppColors.sheet,
    surfaceContainer: AppColors.card,
    surfaceContainerHigh: AppColors.cardHigh,
    surfaceContainerHighest: AppColors.cardHigh,
    onSurfaceVariant: AppColors.muted,
    outline: Color(0xFF2A2A2A),
    outlineVariant: Color(0xFF1F1F1F),
    error: AppColors.danger,
    onError: Colors.black,
    primaryContainer: AppColors.cardHigh,
    onPrimaryContainer: Colors.white,
    secondaryContainer: AppColors.cardHigh,
    onSecondaryContainer: Colors.white,
    tertiary: AppColors.accent,
    onTertiary: AppColors.onAccent,
    tertiaryContainer: AppColors.cardHigh,
    onTertiaryContainer: Colors.white,
    errorContainer: AppColors.cardHigh,
    onErrorContainer: AppColors.danger,
    surfaceTint: Colors.transparent,
    scrim: Colors.black,
  ),
  textTheme: const TextTheme(
    displaySmall: TextStyle(
      fontSize: 36,
      height: 42 / 36,
      fontWeight: FontWeight.bold,
      letterSpacing: -1.0,
      color: Colors.white,
    ),
    headlineMedium: TextStyle(
      fontSize: 28,
      height: 34 / 28,
      fontWeight: FontWeight.w600,
      letterSpacing: -0.5,
      color: Colors.white,
    ),
    headlineSmall: TextStyle(
      fontSize: 24,
      height: 30 / 24,
      fontWeight: FontWeight.w600,
      letterSpacing: -0.3,
      color: Colors.white,
    ),
    titleLarge: TextStyle(
      fontSize: 20,
      height: 26 / 20,
      fontWeight: FontWeight.w600,
      color: Colors.white,
    ),
    titleMedium: TextStyle(
      fontSize: 17,
      height: 22 / 17,
      fontWeight: FontWeight.w500,
      color: Colors.white,
    ),
    titleSmall: TextStyle(
      fontSize: 15,
      height: 20 / 15,
      fontWeight: FontWeight.w500,
      color: Colors.white,
    ),
    bodyLarge: TextStyle(
      fontSize: 16,
      height: 22 / 16,
      color: Colors.white,
    ),
    bodyMedium: TextStyle(
      fontSize: 14,
      height: 20 / 14,
      color: Colors.white,
    ),
    bodySmall: TextStyle(
      fontSize: 12,
      height: 16 / 12,
      color: AppColors.muted,
    ),
    labelLarge: TextStyle(
      fontSize: 16,
      height: 20 / 16,
      fontWeight: FontWeight.w600,
      color: Colors.white,
    ),
    labelMedium: TextStyle(
      fontSize: 13,
      height: 16 / 13,
      fontWeight: FontWeight.w500,
      color: Colors.white,
    ),
  ),
  bottomSheetTheme: const BottomSheetThemeData(
    backgroundColor: AppColors.sheet,
    modalBackgroundColor: AppColors.sheet,
    surfaceTintColor: Colors.transparent,
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
    ),
  ),
  dialogTheme: DialogThemeData(
    backgroundColor: AppColors.sheet,
    surfaceTintColor: Colors.transparent,
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
  ),
  snackBarTheme: SnackBarThemeData(
    backgroundColor: AppColors.cardHigh,
    contentTextStyle: const TextStyle(color: Colors.white, fontSize: 14),
    behavior: SnackBarBehavior.floating,
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
  ),
);
