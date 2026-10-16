import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../ship_controller.dart';

/// 기록 탭: 진단 로그
class LogPage extends StatelessWidget {
  const LogPage({super.key, required this.controller});
  final ShipController controller;

  @override
  Widget build(BuildContext context) {
    final c = controller;
    return AnimatedBuilder(
      animation: c,
      builder: (context, _) {
        final lines = c.log.split('\n').where((l) => l.trim().isNotEmpty).toList();
        final shown = lines.length > 200 ? lines.sublist(lines.length - 200) : lines;
        return ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
          children: [
            Row(children: [
              Text('진단 로그', style: Theme.of(context).textTheme.titleMedium),
              const Spacer(),
              Text(lines.isEmpty ? '기록 없음' : '${lines.length}줄',
                  style: const TextStyle(color: Colors.white60)),
            ]),
            const SizedBox(height: 4),
            const Text('문제가 생기면 복사해서 보내주세요',
                style: TextStyle(color: Colors.white54, fontSize: 12)),
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration:
                  BoxDecoration(color: Colors.black26, borderRadius: BorderRadius.circular(10)),
              child: SelectableText(
                shown.isEmpty ? '아직 기록이 없습니다.' : shown.join('\n'),
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11.5, height: 1.5),
              ),
            ),
            const SizedBox(height: 10),
            Row(mainAxisAlignment: MainAxisAlignment.end, children: [
              TextButton.icon(
                onPressed: c.clearLog,
                icon: const Icon(Icons.delete_outline, size: 18),
                label: const Text('지우기'),
              ),
              FilledButton.tonalIcon(
                onPressed: () async {
                  final s = c.state;
                  final header = s == null
                      ? ''
                      : '[${s.device} / Android ${s.release} SDK ${s.sdk} / ship ${s.label} / sys ${s.systemZone}]\n';
                  await Clipboard.setData(ClipboardData(text: header + c.log));
                  c.emitToast('로그를 복사했습니다');
                },
                icon: const Icon(Icons.copy, size: 18),
                label: const Text('복사'),
              ),
            ]),
          ],
        );
      },
    );
  }
}
