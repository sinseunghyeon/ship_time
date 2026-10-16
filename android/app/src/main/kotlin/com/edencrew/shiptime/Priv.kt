package com.edencrew.shiptime

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

data class ExecResult(val code: Int?, val out: String) {
    fun summary(): String {
        val o = out.replace('\n', ' ').trim().let { if (it.length > 160) it.take(160) + "…" else it }
        return "exit=${code ?: "?"}" + if (o.isNotEmpty()) " | $o" else ""
    }
}

/** 셸/루트 권한으로 명령을 실행할 수 있는 주체 */
interface Priv {
    val name: String
    fun exec(cmd: String, timeoutMs: Long): ExecResult
}

internal fun drain(proc: Process, timeoutMs: Long): ExecResult {
    val sb = StringBuffer()
    try {
        proc.outputStream.close()
    } catch (_: Throwable) {
    }
    val r1 = thread(isDaemon = true) {
        try {
            proc.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
        } catch (_: Throwable) {
        }
    }
    val r2 = thread(isDaemon = true) {
        try {
            proc.errorStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
        } catch (_: Throwable) {
        }
    }
    val code = AtomicReference<Int?>(null)
    val w = thread(isDaemon = true) {
        try {
            code.set(proc.waitFor())
        } catch (_: Throwable) {
        }
    }
    w.join(timeoutMs)
    if (w.isAlive) {
        try {
            proc.destroy()
        } catch (_: Throwable) {
        }
        sb.append("[timeout ${timeoutMs}ms]")
    }
    r1.join(300)
    r2.join(300)
    return ExecResult(code.get(), sb.toString().trim())
}

object ShizukuPriv : Priv {
    override val name = "Shizuku"
    const val PKG = "moe.shizuku.privileged.api"

    /** Shizuku API 13 에서 newProcess 가 private 이 되어 리플렉션으로 호출 */
    private val newProcess: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isPreV11(): Boolean = try {
        Shizuku.isPreV11()
    } catch (_: Throwable) {
        true
    }

    fun hasPermission(): Boolean = try {
        isRunning() && !isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun version(): Int? = try {
        if (isRunning()) Shizuku.getVersion() else null
    } catch (_: Throwable) {
        null
    }

    fun uid(): Int? = try {
        if (isRunning()) Shizuku.getUid() else null
    } catch (_: Throwable) {
        null
    }

    /**
     * 위젯 탭으로 앱 프로세스가 새로 뜬 직후에는 ShizukuProvider 가 바인더를 아직 못 받았을 수 있다.
     * 최대 timeoutMs 동안 기다린다.
     */
    fun waitBinder(timeoutMs: Long): Boolean {
        if (isRunning()) return true
        val latch = CountDownLatch(1)
        val l = Shizuku.OnBinderReceivedListener { latch.countDown() }
        return try {
            Shizuku.addBinderReceivedListenerSticky(l)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            isRunning()
        } catch (_: Throwable) {
            false
        } finally {
            try {
                Shizuku.removeBinderReceivedListener(l)
            } catch (_: Throwable) {
            }
        }
    }

    val canExec: Boolean get() = newProcess != null

    override fun exec(cmd: String, timeoutMs: Long): ExecResult {
        val m = newProcess ?: return ExecResult(null, "Shizuku.newProcess 사용 불가(API 변경)")
        val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        return drain(proc, timeoutMs)
    }

    /** 시스템 서비스 바인더를 Shizuku(셸 uid) 경유로 감싼다 */
    fun systemBinder(service: String): IBinder? {
        Hidden.init()
        val raw = SystemServiceHelper.getSystemService(service) ?: return null
        return ShizukuBinderWrapper(raw)
    }

    /** IAlarmManager.setTimeZone(String) 를 직접 트랜잭션 */
    fun alarmSetTimeZone(zone: String): String {
        val code = Hidden.txCode("android.app.IAlarmManager", "setTimeZone")
            ?: throw IllegalStateException("트랜잭션 코드 조회 실패")
        val b = systemBinder("alarm") ?: throw IllegalStateException("alarm 서비스 없음")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("android.app.IAlarmManager")
            data.writeString(zone)
            b.transact(code, data, reply, 0)
            reply.readException() // SecurityException 등은 여기서 throw
            return "code=$code 호출 성공"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

object RootPriv : Priv {
    override val name = "Root(su)"

    @Volatile var verified: Boolean? = null
        private set

    fun check(timeoutMs: Long = 4000): Boolean {
        if (verified == true) return true
        val ok = try {
            exec("id", timeoutMs).out.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
        verified = ok
        return ok
    }

    override fun exec(cmd: String, timeoutMs: Long): ExecResult {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        return drain(proc, timeoutMs)
    }
}
