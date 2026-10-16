import 'dart:math' as math;
import 'package:flutter/material.dart';

import 'app_colors.dart';

/// 고양이 선장 마스코트 — 패키지 없이 CustomPainter 로 직접 그린 오리지널 캐릭터.
/// [bumpTick] 값이 바뀌면 폴짝 반응하고, 주기적으로 눈을 깜빡인다.
class CatMascot extends StatefulWidget {
  const CatMascot({super.key, this.size = 150, this.bumpTick = 0, this.furBase});
  final double size;
  final int bumpTick;
  final Color? furBase;

  @override
  State<CatMascot> createState() => _CatMascotState();
}

class _CatMascotState extends State<CatMascot> with TickerProviderStateMixin {
  late final AnimationController _blink =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 180));
  late final AnimationController _bump =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 440));
  // build 마다 merge 를 새로 만들면 AnimatedBuilder 리스너가 꼬일 수 있어 고정한다.
  late final Listenable _anim = Listenable.merge([_blink, _bump]);

  @override
  void initState() {
    super.initState();
    _loopBlink();
  }

  Future<void> _loopBlink() async {
    while (mounted) {
      await Future.delayed(const Duration(milliseconds: 3200));
      if (!mounted) return;
      await _blink.forward();
      await _blink.reverse();
    }
  }

  @override
  void didUpdateWidget(covariant CatMascot old) {
    super.didUpdateWidget(old);
    if (old.bumpTick != widget.bumpTick) {
      _bump.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _blink.dispose();
    _bump.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _anim,
      builder: (_, __) {
        final wiggle = math.sin(_bump.value * math.pi * 2) * 0.10 * (1 - _bump.value);
        final hop = -math.sin(_bump.value * math.pi) * 8;
        return Transform.translate(
          offset: Offset(0, hop),
          child: Transform.rotate(
            angle: wiggle,
            child: CustomPaint(
              size: Size.square(widget.size),
              painter: _CatPainter(blink: _blink.value, fur: widget.furBase ?? const Color(0xFFF6CF9B)),
            ),
          ),
        );
      },
    );
  }
}

class _CatPainter extends CustomPainter {
  _CatPainter({required this.blink, required this.fur});
  final double blink;
  final Color fur;

  @override
  void paint(Canvas c, Size size) {
    final s = size.width / 200.0;
    Offset p(double x, double y) => Offset(x * s, y * s);
    final fill = Paint()..isAntiAlias = true..style = PaintingStyle.fill;
    final stroke = Paint()
      ..isAntiAlias = true
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round;

    final furDark = Color.lerp(fur, Colors.black, 0.12)!;
    final pink = const Color(0xFFFF9F8F);

    // ears
    fill.color = fur;
    c.drawPath(_tri(p(58, 70), p(48, 34), p(86, 56)), fill);
    c.drawPath(_tri(p(142, 70), p(152, 34), p(114, 56)), fill);
    fill.color = pink;
    c.drawPath(_tri(p(62, 64), p(56, 44), p(80, 56)), fill);
    c.drawPath(_tri(p(138, 64), p(144, 44), p(120, 56)), fill);

    // head
    fill.shader = RadialGradient(
      center: const Alignment(0, -0.2),
      radius: 0.9,
      colors: [Color.lerp(fur, Colors.white, .35)!, fur],
    ).createShader(Rect.fromCircle(center: p(100, 104), radius: 58 * s));
    c.drawOval(Rect.fromCenter(center: p(100, 104), width: 112 * s, height: 100 * s), fill);
    fill.shader = null;

    // stripes
    stroke..color = furDark..strokeWidth = 6 * s;
    c.drawPath(_q(p(100, 56), p(106, 73), p(100, 90)), stroke);
    stroke.strokeWidth = 5 * s;
    c.drawPath(_q(p(78, 60), p(80, 74), p(76, 84)), stroke);
    c.drawPath(_q(p(122, 60), p(120, 74), p(124, 84)), stroke);

    // sailor hat
    fill.color = const Color(0xFFEEF4FB);
    c.drawOval(Rect.fromCenter(center: p(100, 52), width: 96 * s, height: 24 * s), fill);
    fill.color = const Color(0xFFF7FBFF);
    final cap = Path()
      ..moveTo(p(66, 52).dx, p(66, 52).dy)
      ..quadraticBezierTo(p(100, 18).dx, p(100, 18).dy, p(134, 52).dx, p(134, 52).dy)
      ..close();
    c.drawPath(cap, fill);
    fill.color = AppColors.seed;
    c.drawRRect(RRect.fromRectAndRadius(
        Rect.fromLTWH(p(92, 30).dx, p(92, 30).dy, 16 * s, 10 * s), Radius.circular(3 * s)), fill);
    stroke..color = AppColors.seed..strokeWidth = 4 * s;
    c.drawLine(p(88, 44), p(112, 44), stroke);
    fill.color = AppColors.gold;
    c.drawCircle(p(100, 40), 3.4 * s, fill);

    // eyes (blink: ry shrinks)
    final eyeH = (10 - blink * 8.5) * s;
    fill.color = const Color(0xFF243B53);
    c.drawOval(Rect.fromCenter(center: p(80, 104), width: 17 * s, height: eyeH * 2), fill);
    c.drawOval(Rect.fromCenter(center: p(120, 104), width: 17 * s, height: eyeH * 2), fill);
    if (blink < 0.5) {
      fill.color = Colors.white;
      c.drawCircle(p(83, 100), 2.6 * s, fill);
      c.drawCircle(p(123, 100), 2.6 * s, fill);
    }

    // nose + mouth
    fill.color = pink;
    c.drawPath(_tri(p(96, 118), p(104, 118), p(100, 123)), fill);
    stroke..color = const Color(0xFFC98B58)..strokeWidth = 2.4 * s;
    c.drawPath(_q(p(100, 123), p(93, 130), p(86, 125)), stroke);
    c.drawPath(_q(p(100, 123), p(107, 130), p(114, 125)), stroke);

    // whiskers
    stroke..color = Color.lerp(fur, Colors.white, .2)!..strokeWidth = 2 * s;
    c.drawLine(p(66, 112), p(46, 108), stroke);
    c.drawLine(p(66, 120), p(46, 124), stroke);
    c.drawLine(p(134, 112), p(154, 108), stroke);
    c.drawLine(p(134, 120), p(154, 124), stroke);

    // cheeks
    fill.color = const Color(0xFFFFB3A0).withValues(alpha: .55);
    c.drawCircle(p(70, 120), 6 * s, fill);
    c.drawCircle(p(130, 120), 6 * s, fill);
  }

  Path _tri(Offset a, Offset b, Offset d) => Path()
    ..moveTo(a.dx, a.dy)
    ..lineTo(b.dx, b.dy)
    ..lineTo(d.dx, d.dy)
    ..close();

  Path _q(Offset a, Offset ctrl, Offset b) => Path()
    ..moveTo(a.dx, a.dy)
    ..quadraticBezierTo(ctrl.dx, ctrl.dy, b.dx, b.dy);

  @override
  bool shouldRepaint(_CatPainter old) => old.blink != blink || old.fur != fur;
}

/// 말풍선
class SpeechBubble extends StatelessWidget {
  const SpeechBubble(this.text, {super.key});
  final String text;

  @override
  Widget build(BuildContext context) {
    return Column(mainAxisSize: MainAxisSize.min, children: [
      CustomPaint(size: const Size(14, 7), painter: _BubbleTail()),
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        decoration: BoxDecoration(
          color: AppColors.cream,
          borderRadius: BorderRadius.circular(14),
          boxShadow: const [BoxShadow(color: Color(0x33000000), blurRadius: 10, offset: Offset(0, 4))],
        ),
        child: Text(text,
            style: const TextStyle(
            fontFamily: 'Pretendard', color: AppColors.ink, fontWeight: FontWeight.w700, fontSize: 12.5)),
      ),
    ]);
  }
}

class _BubbleTail extends CustomPainter {
  @override
  void paint(Canvas c, Size s) {
    final path = Path()
      ..moveTo(s.width / 2, 0)
      ..lineTo(0, s.height)
      ..lineTo(s.width, s.height)
      ..close();
    c.drawPath(path, Paint()..color = AppColors.cream);
  }

  @override
  bool shouldRepaint(_) => false;
}

/// 발바닥 버튼
class PawButton extends StatelessWidget {
  const PawButton({super.key, required this.label, required this.hint, required this.onTap});
  final String label;
  final String hint;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return Opacity(
      opacity: enabled ? 1 : .5,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(20),
          onTap: onTap,
          child: Ink(
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [AppColors.pawTop, AppColors.pawBottom],
              ),
              borderRadius: BorderRadius.circular(20),
              boxShadow: const [BoxShadow(color: Color(0x4D000000), blurRadius: 16, offset: Offset(0, 8))],
            ),
            padding: const EdgeInsets.symmetric(vertical: 14),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              CustomPaint(size: const Size(20, 16), painter: _Paw()),
              const SizedBox(height: 3),
              Text(label,
                  style: const TextStyle(
                      fontFamily: 'Pretendard', color: AppColors.cream, fontWeight: FontWeight.w800, fontSize: 19)),
              Text(hint,
                  style: const TextStyle(fontFamily: 'Pretendard', color: AppColors.sub, fontSize: 11)),
            ]),
          ),
        ),
      ),
    );
  }
}

class _Paw extends CustomPainter {
  @override
  void paint(Canvas c, Size s) {
    final f = Paint()
      ..color = AppColors.cream.withValues(alpha: .9)
      ..isAntiAlias = true;
    final u = s.width / 20;
    c.drawOval(Rect.fromCenter(center: Offset(10 * u, 11 * u), width: 11 * u, height: 9 * u), f);
    c.drawCircle(Offset(4.5 * u, 5 * u), 2.1 * u, f);
    c.drawCircle(Offset(8.5 * u, 3 * u), 2.1 * u, f);
    c.drawCircle(Offset(12.5 * u, 3 * u), 2.1 * u, f);
    c.drawCircle(Offset(16 * u, 5 * u), 2.1 * u, f);
  }

  @override
  bool shouldRepaint(_) => false;
}
