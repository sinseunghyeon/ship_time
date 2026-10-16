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
            val ok = try {
                mgr.autoConnect(ctx, timeoutMs.coerceAtLeast(1000L))
            } catch (e: AdbPairingRequiredException) {
                needsPairing = true
                lastError = "페어링 필요"
                connected = false
                return false
            } catch (t: Throwable) {
                // autoConnect 실패 시 5555 폴백 (구형 tcpip)
                try {
                    mgr.connect(AndroidUtils.getHostIpAddress(ctx), 5555)
                } catch (t2: Throwable) {
                    lastError = t.message ?: t2.message ?: t.javaClass.simpleName
                    connected = false
                    return false
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
                try {
                    val buf = ByteArrayOutputStream()
                    stream.openInputStream().use { input ->
                        val b = ByteArray(4096)
                        while (true) {
                            val n = input.read(b)
                            if (n < 0) break
                            buf.write(b, 0, n)
                        }
                    }
                    out.set(buf.toString(StandardCharsets.UTF_8.name()))
                } catch (t: Throwable) {
                    err.set(t)
                } finally {
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
}
