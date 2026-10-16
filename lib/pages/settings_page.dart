import 'package:flutter/material.dart';

import '../native.dart';
import '../ship_controller.dart';
import '../app_colors.dart';

/// 설정 탭: ADB 연결/권한 상태 + 옵션.
/// (ADB 연결·페어링 흐름은 기존 코드를 그대로 옮긴 것이다.)
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.controller});
  final ShipController controller;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  ShipController get c => widget.controller;

  Future<void> _adbConnect() async {
    // 연결 자체만 busy 로 감싼다. 페어링 다이얼로그는 busy 해제 후 따로 띄운다
    // (runRaw 안에서 또 runRaw 를 호출하면 busy 가드에 막혀 페어링이 실행되지 않음)
    ShipState? s;
    await c.runRaw(() async {
      s = await Native.adbConnect();
    });
    if (!mounted || s == null) return;
    final r = s!;
    if (r.adbConnected) {
      c.emitToast('무선 디버깅에 연결됨');
    } else if (r.adbNeedsPairing) {
      c.emitToast('페어링이 필요합니다', error: true);
      await _showPairDialog();
    } else {
      c.emitToast(r.adbError ?? '연결 실패 — 무선 디버깅을 켜세요', error: true);
    }
  }

  Future<void> _showPairDialog() async {
    if (!mounted) return;
    // StatefulBuilder + 비동기 setState 는 다이얼로그 dispose 후
    // `_dependents.isEmpty` assertion 을 유발할 수 있어 전용 위젯으로 분리한다.
    final result = await showDialog<_PairResult>(
      context: context,
      builder: (ctx) => const _PairDialog(),
    );

    if (result == null || !mounted) return;
    if (result.port <= 0 || result.code.length < 6) {
      c.emitToast('포트와 6자리 코드를 확인하세요', error: true);
      return;
    }

    // 다이얼로그 라우트가 완전히 내려간 다음 프레임에 연결을 시작해
    // InheritedWidget 정리와 notifyListeners 가 겹치지 않게 한다.
    await Future<void>.delayed(Duration.zero);
    if (!mounted) return;

    await c.runRaw(() async {
      final s = await Native.adbPair(port: result.port, code: result.code);
      if (!mounted) return;
      if (s.adbConnected) {
        c.emitToast('페어링·연결 완료');
      } else {
        c.emitToast(s.adbError ?? '페어링 실패', error: true);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: c,
      builder: (context, _) {
        final s = c.state;
        if (s == null) return const Center(child: CircularProgressIndicator());
        return ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
          children: [
            _permissionCard(s),
            const SizedBox(height: 12),
            _optionCard(s),
          ],
        );
      },
    );
  }

  Widget _permissionCard(ShipState s) {
    Widget row(String k, String v, {Color? col}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(width: 120, child: Text(k, style: const TextStyle(color: Colors.white60))),
            Expanded(child: Text(v, style: TextStyle(color: col, height: 1.35))),
          ]),
        );

    final String adb;
    final Color adbColor;
    String? adbDetail;
    if (!s.adbSupported) {
      adb = 'Android 11 이상 필요';
      adbColor = AppColors.fail;
    } else if (s.adbConnected) {
      adb = '연결됨';
      adbColor = AppColors.ok;
    } else if (s.adbNeedsPairing) {
      adb = '페어링 필요';
      adbColor = AppColors.warn;
    } else {
      adb = '미연결';
      adbColor = AppColors.warn;
      adbDetail = _friendlyAdbError(s.adbError);
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('연결 상태', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          row('무선 디버깅(ADB)', adb, col: adbColor),
          if (adbDetail != null) ...[
            const SizedBox(height: 4),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
              decoration: BoxDecoration(
                color: Colors.black26,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                adbDetail,
                style: const TextStyle(color: AppColors.sub, fontSize: 13, height: 1.45),
              ),
            ),
          ],
          row('자동 시간대',
              switch (s.autoTimeZone) { 1 => '켜짐 (적용 시 자동으로 끔)', 0 => '꺼짐', _ => '알 수 없음' }),
          row('Device Owner', s.deviceOwner ? '예' : '아니오'),
          row('기기', '${s.device} · Android ${s.release} (SDK ${s.sdk})'),
          const SizedBox(height: 8),
          Wrap(spacing: 8, runSpacing: 8, children: [
            FilledButton.tonal(
              onPressed: c.busy || !s.adbSupported ? null : _adbConnect,
              child: Text(s.adbConnected ? '다시 연결' : 'ADB 연결'),
            ),
            FilledButton.tonal(
              onPressed: c.busy || !s.adbSupported ? null : _showPairDialog,
              child: const Text('페어링'),
            ),
            if (s.adbConnected)
              TextButton(
                onPressed: c.busy
                    ? null
                    : () => c.runRaw(() async {
                        await Native.adbDisconnect();
                      }),
                child: const Text('연결 해제'),
              ),
            TextButton(onPressed: Native.openDeveloperSettings, child: const Text('개발자 옵션')),
          ]),
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('설정 방법 (Shizuku/루트 불필요)'),
            childrenPadding: const EdgeInsets.only(bottom: 8),
            children: const [
              Text(
                '1. 설정 > 휴대전화 정보 > 소프트웨어 정보 > 빌드번호 7번 탭 → 개발자 옵션\n'
                '2. Wi-Fi 연결 상태에서 개발자 옵션 > 무선 디버깅 켜기\n'
                '3. 이 앱에서 "ADB 연결" — 처음이면 "페어링"으로 6자리 코드 입력\n'
                '4. 연결되면 ±1h / 위젯으로 시스템 시간대가 바뀝니다\n\n'
                '※ 재부팅 후에는 무선 디버깅을 다시 켜고 "ADB 연결"을 한 번 더 하세요.\n'
                '※ 구글 계정이 있어도 동작합니다 (Device Owner/루트/Shizuku 불필요).\n'
                '※ 무선 디버깅이 꺼져 있어도 위젯은 "표시 전용"으로 동작합니다.',
                style: TextStyle(height: 1.5),
              ),
            ],
          ),
        ]),
      ),
    );
  }

  Widget _optionCard(ShipState s) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          ListTile(
            title: const Text('날짜/시간 설정 열기'),
            subtitle: const Text('시간대를 직접 바꾸거나 자동 시간대를 끌 때'),
            trailing: const Icon(Icons.open_in_new, size: 18),
            onTap: Native.openDateSettings,
          ),
          SwitchListTile(
            title: const Text('루트(su) 사용'),
            subtitle: Text(s.useRoot
                ? (s.rootVerified == true ? '루트 권한으로도 시도합니다' : '루트 확인 실패 — 루팅된 기기 전용')
                : 'ADB가 안 될 때 대체 수단 (루팅 기기 전용)'),
            value: s.useRoot,
            onChanged: c.busy ? null : (v) => c.setUseRoot(v),
          ),
        ]),
      ),
    );
  }
}

/// 긴 영문 소켓 에러를 상태 줄에 넣지 않고, 읽기 쉬운 한글로 요약한다.
String? _friendlyAdbError(String? raw) {
  if (raw == null || raw.trim().isEmpty) return null;
  final e = raw.toLowerCase();
  if (e.contains('econnrefused') || e.contains('connection refused')) {
    return '연결이 거절되었습니다. 무선 디버깅이 켜져 있는지, '
        '페어링이 필요한지 확인하세요.';
  }
  if (e.contains('pair')) {
    return '페어링이 필요합니다. "페어링" 버튼으로 6자리 코드를 입력하세요.';
  }
  if (e.contains('timeout') || e.contains('timed out')) {
    return '연결 시간이 초과되었습니다. Wi-Fi와 무선 디버깅을 확인한 뒤 다시 시도하세요.';
  }
  // 원문이 너무 길면 앞부분만
  final t = raw.trim().replaceAll('\n', ' ');
  return t.length > 160 ? '${t.substring(0, 160)}…' : t;
}

class _PairResult {
  const _PairResult(this.port, this.code);
  final int port;
  final String code;
}

/// 페어링 입력 다이얼로그 — 컨트롤러/비동기 탐색 수명을 State 가 소유한다.
class _PairDialog extends StatefulWidget {
  const _PairDialog();

  @override
  State<_PairDialog> createState() => _PairDialogState();
}

class _PairDialogState extends State<_PairDialog> {
  final _portCtrl = TextEditingController();
  final _codeCtrl = TextEditingController();
  var _discovering = true;

  @override
  void initState() {
    super.initState();
    _discover();
  }

  Future<void> _discover() async {
    final p = await Native.adbDiscoverPairingPort();
    if (!mounted) return;
    setState(() {
      _discovering = false;
      if (p != null && _portCtrl.text.isEmpty) _portCtrl.text = '$p';
    });
  }

  @override
  void dispose() {
    _portCtrl.dispose();
    _codeCtrl.dispose();
    super.dispose();
  }

  void _submit() {
    final port = int.tryParse(_portCtrl.text.trim()) ?? 0;
    final code = _codeCtrl.text.trim();
    Navigator.pop(context, _PairResult(port, code));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('무선 디버깅 페어링'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              '1. 개발자 옵션 > 무선 디버깅 켜기\n'
              '2. "기기와 페어링"을 눌러 6자리 코드 화면을 연 상태\n'
              '3. 아래 포트·코드를 입력 후 페어링',
              style: TextStyle(height: 1.45),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _portCtrl,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(
                labelText: '페어링 포트',
                hintText: _discovering ? '자동 탐색 중…' : '포트 번호',
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _codeCtrl,
              keyboardType: TextInputType.number,
              maxLength: 6,
              decoration: const InputDecoration(
                labelText: '6자리 페어링 코드',
                border: OutlineInputBorder(),
                counterText: '',
              ),
              onSubmitted: (_) => _submit(),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('취소')),
        FilledButton(onPressed: _submit, child: const Text('페어링')),
      ],
    );
  }
}
