
import 'package:flutter/material.dart';

import '../cat.dart';
import '../native.dart';
import '../ship_controller.dart';
import '../app_colors.dart';
import '../ui.dart';

/// 메인 탭: 고양이 선장 + 선박 시간 조절
class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.controller});
  final ShipController controller;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  int _bumpTick = 0;
  String _catLine = '안녕하냥, 선장님 🐾';

  static const _catLines = [
    '선박 시간 맞춰뒀다냥!',
    '좋은 항해 되라냥 ⛵',
    '시간대만 바꿨다냥',
    'UTC는 그대로다냥 👌',
    '또 눌러도 괜찮다냥',
  ];

  ShipController get c => widget.controller;

  void _adjust(int d) {
    setState(() {
      _bumpTick++;
      _catLine = (_catLines.toList()..shuffle()).first;
    });
    c.adjust(d);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: c,
      builder: (context, _) {
        final s = c.state;
        if (s == null) {
          return const Center(child: CircularProgressIndicator());
        }
        return RefreshIndicator(
          onRefresh: c.refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
            children: [
              _catHeader(),
              const SizedBox(height: 10),
              _clockCard(s),
              const SizedBox(height: 12),
              _syncCard(s),
            ],
          ),
        );
      },
    );
  }

  Widget _catHeader() {
    return Column(children: [
      CatMascot(size: 150, bumpTick: _bumpTick),
      SpeechBubble(c.busy ? '맞추는 중이다냥…' : _catLine),
    ]);
  }

  Widget _clockCard(ShipState s) {
    final theme = Theme.of(context).textTheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(children: [
              Text('SHIP TIME',
                  style: theme.labelMedium
                      ?.copyWith(color: AppColors.sub, letterSpacing: 1.4, fontWeight: FontWeight.w700)),
              const Spacer(),
              _offsetPicker(s),
            ]),
            const SizedBox(height: 2),
            // 시계만 1초마다 갱신 (페이지 전체 리빌드 안 함)
            ValueListenableBuilder<int>(
              valueListenable: c.clock,
              builder: (context, _, __) {
                final utc = DateTime.now().toUtc();
                final ship = utc.add(Duration(hours: s.offset));
                final device = utc.add(Duration(minutes: s.systemOffsetMinutes));
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    FittedBox(
                      alignment: Alignment.centerLeft,
                      fit: BoxFit.scaleDown,
                      child: Text(fmtTime(ship),
                          style: theme.displayLarge?.copyWith(
                              color: AppColors.cream,
                              fontWeight: FontWeight.w800,
                              fontFeatures: const [FontFeature.tabularFigures()])),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'UTC ${fmtTime(utc, sec: false)}   ·   기기 시계 ${fmtTime(device, sec: false)} (${s.systemZone})',
                      style: theme.bodySmall?.copyWith(color: AppColors.sub),
                    ),
                  ],
                );
              },
            ),
            const SizedBox(height: 16),
            Row(children: [
              Expanded(
                child: PawButton(
                  label: '−1h',
                  hint: '늦추기',
                  onTap: c.busy || s.offset <= -12 ? null : () => _adjust(-1),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: PawButton(
                  label: '+1h',
                  hint: '당기기',
                  onTap: c.busy || s.offset >= 14 ? null : () => _adjust(1),
                ),
              ),
            ]),
            const SizedBox(height: 12),
            _statusLine(s),
          ],
        ),
      ),
    );
  }

  Widget _offsetPicker(ShipState s) {
    return DropdownButton<int>(
      value: s.offset,
      underline: const SizedBox.shrink(),
      isDense: true,
      style: const TextStyle(fontFamily: 'Pretendard', fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
      items: [
        for (var o = -12; o <= 14; o++)
          DropdownMenuItem(value: o, child: Text(o >= 0 ? 'UTC+$o' : 'UTC$o')),
      ],
      onChanged: c.busy ? null : (o) => o == null ? null : c.setOffset(o),
    );
  }

  Widget _statusLine(ShipState s) {
    late final String text;
    late final Color color;
    if (c.busy) {
      text = '적용 중…';
      color = AppColors.warn;
    } else if (!s.sync) {
      text = '표시 전용 모드 — 기기 시계는 바뀌지 않습니다';
      color = Colors.white60;
    } else if (s.statusState == ShipState.stateFail) {
      text = s.statusText.isEmpty ? '적용 실패' : s.statusText;
      color = AppColors.fail;
    } else if (s.systemMatches) {
      text = '기기 시계가 선박 시간과 일치합니다';
      color = AppColors.ok;
    } else {
      text = '기기 시계(${offLabel(s.systemOffsetMinutes)})가 선박 시간과 다릅니다';
      color = AppColors.warn;
    }
    return Row(children: [
      if (c.busy)
        const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
      else
        Icon(Icons.circle, size: 10, color: color),
      const SizedBox(width: 8),
      Expanded(child: Text(text, style: TextStyle(color: color))),
    ]);
  }

  Widget _syncCard(ShipState s) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          SwitchListTile(
            title: const Text('기기 시간대도 같이 변경'),
            subtitle: const Text(
                '끄면 위젯/앱에만 선박 시간을 표시합니다. 켜면 시스템 시간대를 바꿉니다. UTC 기준 시각은 건드리지 않습니다.'),
            value: s.sync,
            onChanged: c.busy ? null : (v) => c.setSync(v),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Wrap(spacing: 8, runSpacing: 8, children: [
              OutlinedButton.icon(
                onPressed: c.busy || !s.sync ? null : c.reapply,
                icon: const Icon(Icons.sync, size: 18),
                label: const Text('지금 다시 적용'),
              ),
              OutlinedButton.icon(
                onPressed: c.busy ? null : c.adoptSystemOffset,
                icon: const Icon(Icons.download, size: 18),
                label: const Text('기기 시간대로 맞추기'),
              ),
            ]),
          ),
        ]),
      ),
    );
  }
}
