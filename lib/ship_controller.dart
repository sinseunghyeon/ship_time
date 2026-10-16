import 'dart:async';

import 'package:flutter/foundation.dart';

import 'native.dart';

class ToastMsg {
  const ToastMsg(this.msg, {this.error = false});
  final String msg;
  final bool error;
}

/// 앱 전역 상태/동작의 단일 소유자. 페이지들은 이 컨트롤러를 구독한다.
/// (ADB 연결/페어링 같은 권한 획득 로직은 여기 두지 않고 설정 페이지에서 직접 호출한다.)
class ShipController extends ChangeNotifier {
  ShipState? state;
  bool busy = false;
  String log = '';

  /// 스낵바용 — 셸이 구독한다.
  final ValueNotifier<ToastMsg?> toast = ValueNotifier(null);

  /// 1초 시계 틱 — 시계 표시 위젯만 구독한다 (상태 변경과 분리).
  final ValueNotifier<int> clock = ValueNotifier(0);

  Timer? _tick;
  Timer? _poll;
  bool _disposed = false;

  void start() {
    refresh();
    // 1초마다 시계 틱 — 전체 리빌드가 아니라 clock 구독자(시계 Text)만 갱신
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!_disposed) clock.value++;
    });
    // 5초마다 시스템 상태 폴링
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => refresh(quiet: true));
  }

  @override
  void dispose() {
    _disposed = true;
    _tick?.cancel();
    _poll?.cancel();
    toast.dispose();
    clock.dispose();
    super.dispose();
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  void emitToast(String msg, {bool error = false}) {
    if (!_disposed) toast.value = ToastMsg(msg, error: error);
  }

  Future<void> refresh({bool quiet = false}) async {
    if (busy && quiet) return;
    try {
      final s = await Native.state();
      final l = await Native.log();
      if (_disposed) return;
      state = s;
      log = l;
      _notify();
    } catch (e) {
      if (!quiet) emitToast('상태 조회 실패: $e', error: true);
    }
  }

  /// ShipState 를 돌려주는 동작(오프셋 변경 등)을 busy 처리와 함께 실행
  Future<void> run(Future<ShipState> Function() op) async {
    if (busy) return;
    busy = true;
    _notify();
    try {
      final s = await op();
      final l = await Native.log();
      if (!_disposed) {
        state = s;
        log = l;
        if (s.lastMessage != null) emitToast(s.lastMessage!, error: s.lastOk == false);
      }
    } catch (e) {
      emitToast('오류: $e', error: true);
    } finally {
      busy = false;
      _notify();
    }
  }

  /// ShipState 를 돌려주지 않는 임의 동작(연결 등)을 busy 처리와 함께 실행.
  /// 끝나면 상태를 다시 읽어온다.
  Future<void> runRaw(Future<void> Function() op) async {
    if (busy) return;
    busy = true;
    _notify();
    try {
      await op();
    } catch (e) {
      emitToast('오류: $e', error: true);
    } finally {
      busy = false;
      _notify();
      await refresh();
    }
  }

  Future<void> adjust(int d) => run(() => Native.adjust(d));
  Future<void> setOffset(int o) => run(() => Native.setOffset(o));
  Future<void> setSync(bool v) => run(() => Native.setSync(v));
  Future<void> setUseRoot(bool v) => run(() => Native.setUseRoot(v));
  Future<void> setAutoAdb(bool v) => run(() => Native.setAutoAdb(v));
  Future<void> reapply() => run(Native.reapply);
  Future<void> adoptSystemOffset() => run(Native.adoptSystemOffset);

  Future<void> clearLog() async {
    await Native.clearLog();
    await refresh();
  }
}
