import 'package:flutter/material.dart';

import 'app_colors.dart';
import 'root_shell.dart';

class ShipTimeApp extends StatelessWidget {
  const ShipTimeApp({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(
      seedColor: AppColors.seed,
      brightness: Brightness.dark,
    ).copyWith(secondary: AppColors.gold);
    return MaterialApp(
      title: 'Helm',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: scheme,
        scaffoldBackgroundColor: AppColors.bg,
        cardTheme: CardThemeData(
          color: AppColors.card,
          margin: EdgeInsets.zero,
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
        ),
        useMaterial3: true,
        fontFamily: 'Pretendard',
        appBarTheme: const AppBarTheme(
          backgroundColor: Colors.transparent,
          surfaceTintColor: Colors.transparent,
          scrolledUnderElevation: 0,
          elevation: 0,
          centerTitle: false,
          titleSpacing: 20,
        ),
        navigationBarTheme: NavigationBarThemeData(
          backgroundColor: AppColors.navbar,
          // 선택 시 알약/아이콘 변화 없이 "글씨 색"만 바뀌도록:
          indicatorColor: Colors.transparent,
          surfaceTintColor: Colors.transparent,
          elevation: 0,
          height: 66,
          labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
          // 아이콘·라벨 색을 함께 (선택 골드 / 비선택 서브)
          iconTheme: WidgetStateProperty.resolveWith((states) {
            final selected = states.contains(WidgetState.selected);
            return IconThemeData(
              size: 24,
              color: selected ? AppColors.gold : AppColors.sub,
            );
          }),
          labelTextStyle: WidgetStateProperty.resolveWith((states) {
            final selected = states.contains(WidgetState.selected);
            return TextStyle(
              fontFamily: 'Pretendard',
              fontSize: 12,
              fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
              color: selected ? AppColors.gold : AppColors.sub,
            );
          }),
        ),
      ),
      home: const RootShell(),
    );
  }
}
