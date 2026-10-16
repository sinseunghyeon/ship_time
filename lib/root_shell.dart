import 'package:flutter/material.dart';

import 'app_colors.dart';

import 'pages/home_page.dart';
import 'pages/log_page.dart';
import 'pages/settings_page.dart';
import 'ship_controller.dart';

/// 하단 탭 셸 — 컨트롤러를 소유하고 스낵바/생명주기를 처리한다.
class RootShell extends StatefulWidget {
  const RootShell({super.key});

  @override
  State<RootShell> createState() => _RootShellState();
}

class _RootShellState extends State<RootShell> with WidgetsBindingObserver {
  final ShipController _c = ShipController();
  int _index = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _c.toast.addListener(_onToast);
    _c.start();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _c.toast.removeListener(_onToast);
    // 자식(AnimatedBuilder/ValueListenableBuilder)이 먼저 unmount 된 뒤에
    // 컨트롤러를 버려야 InheritedWidget/_dependents assertion 을 피한다.
    final c = _c;
    WidgetsBinding.instance.addPostFrameCallback((_) => c.dispose());
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _c.refresh();
  }

  void _onToast() {
    final t = _c.toast.value;
    if (t == null) return;
    // 빌드/라우트 전환 도중 스낵바를 띄우면 프레임워크 assertion 이 날 수 있어 다음 프레임으로 미룬다
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final messenger = ScaffoldMessenger.maybeOf(context);
      if (messenger == null) return;
      messenger
        ..clearSnackBars()
        ..showSnackBar(SnackBar(
          content: Text(t.msg),
          backgroundColor: t.error ? AppColors.toastError : null,
          behavior: SnackBarBehavior.floating,
        ));
    });
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      HomePage(controller: _c),
      SettingsPage(controller: _c),
      LogPage(controller: _c),
    ];
    return Scaffold(
      // 상단 헤더는 시간 탭에만 — 다른 탭은 내용이 곧 제목이라 헤더 없이 깔끔하게
      appBar: _index == 0
          ? AppBar(
              toolbarHeight: 72,
              title: _header(),
              actions: [
                IconButton(
                  onPressed: _c.refresh,
                  icon: const Icon(Icons.refresh, color: AppColors.sub),
                  tooltip: '새로고침',
                ),
                const SizedBox(width: 4),
              ],
            )
          : null,
      // AppBar 가 없는 탭에서는 상단 상태바 영역만 SafeArea 로 확보
      body: SafeArea(
        top: _index != 0,
        bottom: false,
        child: IndexedStack(index: _index, children: pages),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.schedule_outlined), label: '시간'),
          NavigationDestination(icon: Icon(Icons.tune_outlined), label: '설정'),
          NavigationDestination(icon: Icon(Icons.article_outlined), label: '기록'),
        ],
      ),
    );
  }

  Widget _header() {
    const titles = ['Helm', '설정', '기록'];
    final subtitle = switch (_index) {
      0 => _utcDateLine(),
      1 => '무선 디버깅 연결 · 권한',
      _ => '진단 로그',
    };
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(mainAxisSize: MainAxisSize.min, children: [
          Text(
            titles[_index],
            style: const TextStyle(
              fontFamily: 'Pretendard',
              fontSize: 26,
              fontWeight: FontWeight.w800,
              color: AppColors.cream,
              letterSpacing: -0.2,
            ),
          ),
          if (_index == 0) ...[
            const SizedBox(width: 8),
            const Icon(Icons.anchor, size: 18, color: AppColors.gold),
          ],
        ]),
        const SizedBox(height: 2),
        Text(
          subtitle,
          style: const TextStyle(
            fontFamily: 'Pretendard',
            fontSize: 12,
            fontWeight: FontWeight.w500,
            color: AppColors.sub,
          ),
        ),
      ],
    );
  }

  String _utcDateLine() {
    const wd = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    const mo = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    final u = DateTime.now().toUtc();
    return 'UTC · ${wd[u.weekday - 1]}, ${mo[u.month - 1]} ${u.day}';
  }

}
