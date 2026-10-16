package com.edencrew.shiptime

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
