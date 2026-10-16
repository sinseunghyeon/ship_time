package com.edencrew.shiptime

import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 선박 시간은 정수 시간 단위 UTC 오프셋으로 다룬다. (-12 ~ +14) */
object Zones {
    const val MIN = -12
    const val MAX = 14

    fun clamp(o: Int) = o.coerceIn(MIN, MAX)

    /**
     * POSIX 규칙상 Etc/GMT 의 부호는 반대다. UTC+9 → "Etc/GMT-9"
     * tzdata 에 Etc/GMT-14 ~ Etc/GMT+12 가 모두 존재한다.
     */
    fun zoneId(offset: Int): String = when {
        offset == 0 -> "Etc/GMT"
        offset > 0 -> "Etc/GMT-$offset"
        else -> "Etc/GMT+${-offset}"
    }

    fun label(offset: Int) = if (offset >= 0) "UTC+$offset" else "UTC$offset"

    fun offsetMsOf(zoneId: String?): Int? {
        if (zoneId.isNullOrBlank()) return null
        return TimeZone.getTimeZone(zoneId).getOffset(System.currentTimeMillis())
    }

    /** 현재 시스템 오프셋을 시간 단위로 (반올림) */
    fun systemOffsetHours(): Int {
        val ms = offsetMsOf(SysTz.current()) ?: TimeZone.getDefault().getOffset(System.currentTimeMillis())
        return clamp(Math.round(ms / 3_600_000.0).toInt())
    }
}

/** 숨김 API 접근 도우미 */
object Hidden {
    @Volatile private var inited = false

    fun init() {
        if (inited) return
        inited = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (_: Throwable) {
            }
        }
    }

    /** 예: txCode("android.app.IAlarmManager", "setTimeZone") → 버전별 트랜잭션 코드 */
    fun txCode(iface: String, method: String): Int? = try {
        init()
        val f = Class.forName("$iface\$Stub").getDeclaredField("TRANSACTION_$method")
        f.isAccessible = true
        f.getInt(null)
    } catch (_: Throwable) {
        null
    }

    fun sysProp(key: String): String? = try {
        init()
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as String
    } catch (_: Throwable) {
        null
    }
}

/** 실제 시스템 시간대 읽기 (앱 프로세스의 TimeZone.getDefault() 는 캐시라 늦게 바뀔 수 있음) */
object SysTz {
    fun current(): String {
        Hidden.sysProp("persist.sys.timezone")?.takeIf { it.isNotBlank() }?.let { return it }
        try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "persist.sys.timezone"))
            val v = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (v.isNotBlank()) return v
        } catch (_: Throwable) {
        }
        return TimeZone.getDefault().id
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("ship_time", Context.MODE_PRIVATE)

    var offset: Int
        get() = if (sp.contains(K_OFFSET)) sp.getInt(K_OFFSET, 0) else Zones.systemOffsetHours()
        set(v) {
            sp.edit().putInt(K_OFFSET, Zones.clamp(v)).commit()
        }

    /** 위젯 버튼이 시스템 시간대도 같이 바꿀지 */
    var sync: Boolean
        get() = sp.getBoolean(K_SYNC, true)
        set(v) {
            sp.edit().putBoolean(K_SYNC, v).commit()
        }

    /** ADB 가 안 될 때 su 로 시도할지 */
    var useRoot: Boolean
        get() = sp.getBoolean(K_ROOT, false)
        set(v) {
            sp.edit().putBoolean(K_ROOT, v).commit()
        }

    /** 재부팅/무선 디버깅 꺼짐 시 앱이 무선 디버깅을 자동으로 켤지 */
    var autoAdb: Boolean
        get() = sp.getBoolean(K_AUTO_ADB, true)
        set(v) {
            sp.edit().putBoolean(K_AUTO_ADB, v).commit()
        }

    /** ADB 연결에 한 번이라도 성공했는지 (= 페어링 완료) — 자동 켜기의 전제 */
    var adbEverConnected: Boolean
        get() = sp.getBoolean(K_ADB_EVER, false)
        set(v) {
            sp.edit().putBoolean(K_ADB_EVER, v).commit()
        }

    var statusState: Int
        get() = sp.getInt(K_STATUS_STATE, STATE_NONE)
        private set(v) {
            sp.edit().putInt(K_STATUS_STATE, v).commit()
        }

    var statusText: String
        get() = sp.getString(K_STATUS_TEXT, "") ?: ""
        private set(v) {
            sp.edit().putString(K_STATUS_TEXT, v).commit()
        }

    fun setStatus(state: Int, text: String) {
        sp.edit().putInt(K_STATUS_STATE, state).putString(K_STATUS_TEXT, text).commit()
    }

    val log: String get() = sp.getString(K_LOG, "") ?: ""

    fun appendLog(lines: List<String>) {
        if (lines.isEmpty()) return
        // logcat 으로도 남김 (adb logcat -s ShipTime)
        lines.forEach { android.util.Log.i("ShipTime", it) }
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val merged = (log.lines().filter { it.isNotBlank() } +
            lines.mapIndexed { i, l -> if (i == 0) "[$stamp UTC] $l" else "    $l" })
            .takeLast(MAX_LOG_LINES)
        sp.edit().putString(K_LOG, merged.joinToString("\n")).commit()
    }

    fun clearLog() {
        sp.edit().remove(K_LOG).commit()
    }

    companion object {
        const val STATE_NONE = 0
        const val STATE_OK = 1
        const val STATE_FAIL = 2
        const val STATE_PENDING = 3

        private const val K_OFFSET = "offset"
        private const val K_SYNC = "sync"
        private const val K_ROOT = "use_root"
        private const val K_AUTO_ADB = "auto_adb"
        private const val K_ADB_EVER = "adb_ever_connected"
        private const val K_STATUS_STATE = "status_state"
        private const val K_STATUS_TEXT = "status_text"
        private const val K_LOG = "log"
        private const val MAX_LOG_LINES = 400
    }
}

/** 남은 시간 예산 (위젯 브로드캐스트는 goAsync 로 약 10초 제한) */
class Deadline(budgetMs: Long) {
    private val end = System.currentTimeMillis() + budgetMs
    fun remaining(): Long = (end - System.currentTimeMillis()).coerceAtLeast(0)
    val expired get() = remaining() <= 0
    fun cap(ms: Long) = minOf(ms, remaining()).coerceAtLeast(1)
}
