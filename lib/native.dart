import 'package:flutter/services.dart';

/// 네이티브(Kotlin) 쪽 ShipTimeController 와 통신.
/// 위젯 버튼은 앱이 꺼져 있어도 동작해야 하므로 실제 로직은 Kotlin 에 있고,
/// Flutter 는 같은 로직을 MethodChannel 로 호출한다.
class Native {
  static const _ch = MethodChannel('shiptime/native');

  static Future<ShipState> state() async => ShipState(await _map('getState'));

  static Future<ShipState> adjust(int delta) async => ShipState(await _map('adjust', {'delta': delta}));

  static Future<ShipState> setOffset(int offset) async => ShipState(await _map('setOffset', {'offset': offset}));

  static Future<ShipState> reapply() async => ShipState(await _map('reapply'));

  static Future<ShipState> adoptSystemOffset() async => ShipState(await _map('adoptSystemOffset'));

  static Future<ShipState> setSync(bool v) async => ShipState(await _map('setSync', {'value': v}));

  static Future<ShipState> setUseRoot(bool v) async => ShipState(await _map('setUseRoot', {'value': v}));

  static Future<ShipState> adbConnect() async => ShipState(await _map('adbConnect'));

  static Future<ShipState> adbDisconnect() async => ShipState(await _map('adbDisconnect'));

  static Future<int?> adbDiscoverPairingPort() async {
    final r = await _map('adbDiscoverPairingPort');
    final p = r['port'];
    if (p is int && p > 0) return p;
    return null;
  }

  static Future<ShipState> adbPair({required int port, required String code}) async =>
      ShipState(await _map('adbPair', {'port': port, 'code': code}));

  static Future<void> openDateSettings() => _ch.invokeMethod('openDateSettings');
  static Future<void> openDeveloperSettings() => _ch.invokeMethod('openDeveloperSettings');

  static Future<String> log() async => (await _ch.invokeMethod<String>('getLog')) ?? '';
  static Future<void> clearLog() => _ch.invokeMethod('clearLog');

  static Future<Map<String, dynamic>> _map(String m, [Map<String, dynamic>? args]) async {
    final r = await _ch.invokeMethod<Map<Object?, Object?>>(m, args);
    return (r ?? {}).map((k, v) => MapEntry(k.toString(), v));
  }
}

class ShipState {
  ShipState(this.raw);
  final Map<String, dynamic> raw;

  static const stateNone = 0, stateOk = 1, stateFail = 2, statePending = 3;

  int get offset => raw['offset'] as int? ?? 0;
  String get label => raw['label'] as String? ?? '';
  String get zoneId => raw['zoneId'] as String? ?? '';
  bool get sync => raw['sync'] as bool? ?? true;
  bool get useRoot => raw['useRoot'] as bool? ?? false;
  bool? get rootVerified => raw['rootVerified'] as bool?;
  int get statusState => raw['statusState'] as int? ?? stateNone;
  String get statusText => raw['statusText'] as String? ?? '';
  String get systemZone => raw['systemZone'] as String? ?? '';
  int get systemOffsetMinutes => raw['systemOffsetMinutes'] as int? ?? 0;

  /// 1 = 자동, 0 = 수동, -1 = 알 수 없음
  int get autoTimeZone => raw['autoTimeZone'] as int? ?? -1;

  bool get adbSupported => raw['adbSupported'] as bool? ?? false;
  bool get adbConnected => raw['adbConnected'] as bool? ?? false;
  bool get adbNeedsPairing => raw['adbNeedsPairing'] as bool? ?? false;
  String? get adbError => raw['adbError'] as String?;

  bool get deviceOwner => raw['deviceOwner'] as bool? ?? false;
  int get sdk => raw['sdk'] as int? ?? 0;
  String get release => raw['release'] as String? ?? '';
  String get device => raw['device'] as String? ?? '';

  bool? get lastOk => raw['lastOk'] as bool?;
  String? get lastMessage => raw['lastMessage'] as String?;

  bool get systemMatches => systemOffsetMinutes == offset * 60;
}
