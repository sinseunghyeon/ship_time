package com.edencrew.shiptime

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 무선 디버깅(ADB)으로 셸 명령을 실행한다. Shizuku/루트/Device Owner 없이
 * 구글 계정이 있는 기기에서도 시스템 시간대를 바꿀 수 있는 권한 경로.
 *
 * 전제: 개발자 옵션 > 무선 디버깅 ON (Android 11+). 재부팅 후 무선 디버깅을 다시 켜야 한다.
 */
object AdbPriv : Priv {
    override val name = "ADB(무선 디버깅)"

    @Volatile private var appCtx: Context? = null
    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var needsPairing: Boolean = false
        private set

    private val lock = Any()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun ensureConnected(timeoutMs: Long): Boolean = synchronized(lock) {
        val ctx = appCtx ?: return false.also { lastError = "Context 없음" }
        if (!supported()) {
            lastError = "Android 11 이상 필요"
            return false
        }
        if (connected) {
            try {
                // 살아 있는지 가벼운 명령으로 확인
                val r = execUnlocked("echo ok", minOf(1500, timeoutMs))
                if (r.out.contains("ok")) return true
            } catch (_: Throwable) {
            }
            connected = false
        }
        return connectInternal(ctx, timeoutMs)
    }

    fun connect(timeoutMs: Long = 8000): Boolean = synchronized(lock) {
        val ctx = appCtx ?: return false.also { lastError = "Context 없음" }
        return connectInternal(ctx, timeoutMs)
    }

    fun disconnect() = synchronized(lock) {
        try {
            appCtx?.let { ShipAdbManager.get(it).disconnect() }
        } catch (_: Throwable) {
        }
        connected = false
    }

    /**
     * 무선 디버깅 "기기 페어링" 화면이 열린 뒤 mDNS 로 페어링 포트를 찾는다.
     * @return 포트 또는 null
     */
    fun discoverPairingPort(timeoutMs: Long = 15_000): Int? {
        val ctx = appCtx ?: return null
        if (!supported()) return null
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, p ->
            if (p > 0) {
                port.set(p)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } finally {
            mdns.stop()
        }
        val p = port.get()
        return if (p > 0) p else null
    }

    fun pair(port: Int, pairingCode: String): Boolean = synchronized(lock) {
        val ctx = appCtx ?: return false.also { lastError = "Context 없음" }
        if (!supported()) {
            lastError = "Android 11 이상 필요"
            return false
        }
        return try {
            val mgr = ShipAdbManager.get(ctx)
            val host = AndroidUtils.getHostIpAddress(ctx)
            val ok = mgr.pair(host, port, pairingCode.trim())
            if (!ok) {
                lastError = "페어링 실패 (코드/포트 확인)"
                needsPairing = true
                return false
            }
            needsPairing = false
            lastError = null
            connectInternal(ctx, 8000)
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            needsPairing = true
            false
        }
    }

    override fun exec(cmd: String, timeoutMs: Long): ExecResult = synchronized(lock) {
        val ctx = appCtx ?: return ExecResult(null, "Context 없음")
        if (!connected && !connectInternal(ctx, minOf(4000, timeoutMs))) {
            return ExecResult(null, lastError ?: "ADB 미연결")
        }
        return execUnlocked(cmd, timeoutMs)
    }

    private fun connectInternal(ctx: Context, timeoutMs: Long): Boolean {
        return try {
            val mgr = ShipAdbManager.get(ctx)
            val dl = Deadline(timeoutMs.coerceAtLeast(1000L))
            val ok = try {
                mgr.autoConnect(ctx, dl.remaining().coerceAtLeast(1000L))
            } catch (e: AdbPairingRequiredException) {
                needsPairing = true
                lastError = "페어링 필요"
                connected = false
                return false
            } catch (t: Throwable) {
                try {
                    // autoConnect 는 _adb._tcp(구형 tcpip)와 _adb-tls-connect(무선 디버깅) 중 먼저
                    // 발견된 쪽만 시도한다. 구형 광고가 먼저 잡혀 실패하면 무선 디버깅을 명시적으로 다시 시도.
                    // (InterruptedException = 아무것도 발견 못 함 → TLS 재탐색은 시간 낭비)
                    if (t is InterruptedException || dl.remaining() < 500) throw t
                    mgr.connectTls(ctx, dl.remaining())
                } catch (e: AdbPairingRequiredException) {
                    needsPairing = true
                    lastError = "페어링 필요"
                    connected = false
                    return false
                } catch (t2: Throwable) {
                    // 마지막으로 5555 폴백 (구형 tcpip / 에뮬레이터).
                    // 같은 기기의 adbd 이므로 루프백을 먼저 — getHostIpAddress 는 에뮬레이터에서
                    // 10.0.2.2(호스트 PC)를 돌려줘 엉뚱한 adbd 에 붙을 수 있다.
                    try {
                        try {
                            mgr.connect("127.0.0.1", 5555)
                        } catch (_: java.io.IOException) {
                            mgr.connect(AndroidUtils.getHostIpAddress(ctx), 5555)
                        }
                    } catch (t3: Throwable) {
                        android.util.Log.w("ShipTime", "ADB 연결 실패 (5555)", t3)
                        lastError = listOfNotNull("auto" to t, ("tls" to t2).takeIf { t2 !== t }, "5555" to t3)
                            .joinToString(" / ") { (k, e) -> "$k: ${e.message ?: e.javaClass.simpleName}" }
                        connected = false
                        return false
                    }
                }
            }
            connected = ok
            if (ok) {
                needsPairing = false
                lastError = null
            } else {
                lastError = "무선 디버깅에 연결할 수 없음 (켜져 있는지 확인)"
            }
            ok
        } catch (t: Throwable) {
            connected = false
            lastError = t.message ?: t.javaClass.simpleName
            if (t is AdbPairingRequiredException || (t.message?.contains("pair", true) == true)) {
                needsPairing = true
            }
            false
        }
    }

    private fun execUnlocked(cmd: String, timeoutMs: Long): ExecResult {
        val ctx = appCtx ?: return ExecResult(null, "Context 없음")
        return try {
            val mgr = ShipAdbManager.get(ctx)
            // 한 방 실행: shell:cmd … 가 끝나면 스트림이 닫힌다
            val wrapped = "($cmd); echo __SHIP_EXIT__:\$?"
            val stream = mgr.openStream("shell:$wrapped")
            val out = AtomicReference("")
            val err = AtomicReference<Throwable?>(null)
            val latch = CountDownLatch(1)
            thread(isDaemon = true, name = "adb-shell-read") {
                val buf = ByteArrayOutputStream()
                try {
                    stream.openInputStream().use { input ->
                        val b = ByteArray(4096)
                        while (true) {
                            val n = input.read(b)
                            if (n < 0) break
                            buf.write(b, 0, n)
                            // 종료 마커 줄까지 받았으면 스트림 닫힘 신호를 기다리지 않는다
                            if (EXIT_LINE.containsMatchIn(buf.toString(StandardCharsets.UTF_8.name()))) break
                        }
                    }
                } catch (e: java.io.IOException) {
                    // libadb 는 원격이 스트림을 닫는 순간과 read 가 겹치면 -1 대신
                    // IOException("Stream closed.") 를 던진다 → 정상 종료(EOF)로 취급
                    if (!stream.isClosed) err.set(e)
                } catch (t: Throwable) {
                    err.set(t)
                } finally {
                    out.set(buf.toString(StandardCharsets.UTF_8.name()))
                    try {
                        stream.close()
                    } catch (_: Throwable) {
                    }
                    latch.countDown()
                }
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                try {
                    stream.close()
                } catch (_: Throwable) {
                }
                return ExecResult(null, "[timeout ${timeoutMs}ms] ${out.get()}".trim())
            }
            err.get()?.let {
                connected = false
                return ExecResult(null, it.message ?: it.javaClass.simpleName)
            }
            val text = out.get().trim()
            val marker = "__SHIP_EXIT__:"
            val idx = text.lastIndexOf(marker)
            if (idx >= 0) {
                val code = text.substring(idx + marker.length).trim().lines().firstOrNull()?.toIntOrNull()
                val body = text.substring(0, idx).trim()
                return ExecResult(code, body)
            }
            ExecResult(0, text)
        } catch (t: Throwable) {
            connected = false
            lastError = t.message ?: t.javaClass.simpleName
            ExecResult(null, lastError ?: "exec 실패")
        }
    }

    /** execUnlocked 가 명령 뒤에 붙이는 종료 코드 줄 (숫자 + 줄바꿈까지 와야 완결) */
    private val EXIT_LINE = Regex("__SHIP_EXIT__:-?\\d+\\s*\\n")
}
