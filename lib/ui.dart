/// 공통 포맷 유틸. (색상은 app_colors.dart 의 AppColors 에서 관리)

String fmtTime(DateTime t, {bool sec = true}) {
  String two(int n) => n.toString().padLeft(2, '0');
  return sec
      ? '${two(t.hour)}:${two(t.minute)}:${two(t.second)}'
      : '${two(t.hour)}:${two(t.minute)}';
}

/// 분 단위 오프셋 → "UTC+9" / "UTC+9:30"
String offLabel(int minutes) {
  final sign = minutes >= 0 ? '+' : '-';
  final m = minutes.abs();
  return m % 60 == 0
      ? 'UTC$sign${m ~/ 60}'
      : 'UTC$sign${m ~/ 60}:${(m % 60).toString().padLeft(2, '0')}';
}
