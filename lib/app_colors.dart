import 'package:flutter/material.dart';

/// 앱 전체 색상의 단일 관리 지점. 새 색이 필요하면 여기에만 추가한다.
class AppColors {
  AppColors._();

  // 배경 / 표면
  static const bg = Color(0xFF0A1824); // 스캐폴드 배경
  static const card = Color(0xFF12304D); // 카드
  static const navbar = Color(0xFF0E2135); // 하단 탭바 배경

  // 브랜드
  static const seed = Color(0xFF2A6F97); // 시드(블루)
  static const gold = Color(0xFFFFC24B); // 포인트(골드)
  static const cream = Color(0xFFFFF6E9); // 밝은 글자
  static const sub = Color(0xFF9FB9D4); // 보조 글자
  static const ink = Color(0xFF0B1E33); // 밝은 배경 위 어두운 글자

  // 상태
  static const ok = Color(0xFF5FD38D);
  static const warn = gold;
  static const fail = Color(0xFFFF7A6B);

  // 버튼 / 기타
  static const pawTop = Color(0xFF1F4A6E); // 발바닥 버튼 그라데이션 위
  static const pawBottom = Color(0xFF163A58); // 발바닥 버튼 그라데이션 아래
  static const toastError = Color(0xFF7A2E26); // 에러 스낵바 배경
}
