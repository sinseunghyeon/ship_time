package com.edencrew.shiptime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * debug 빌드 전용. 위젯 버튼과 똑같은 경로(ShipTimeController, 8초 예산)로 명령을 실행하고
 * 결과를 logcat 한 줄로 남긴다. tool/emulator_test.sh 가 이 줄을 파싱한다.
 *
 * cmd: plus | minus | set(--ei offset N) | reapply | sync_on | sync_off | state
 * 출력: RESULT id=.. cmd=.. ok=.. offset=.. target=.. sys=.. shizuku=running/permission uid=.. msg=..
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val cmd = intent.getStringExtra("cmd") ?: "state"
        val id = intent.getStringExtra("id") ?: "-"
        val pending = goAsync()
        thread(name = "ship-time-debug") {
            try {
                val p = Prefs(app)
                val out: ShipTimeController.Outcome? = when (cmd) {
                    "plus" -> ShipTimeController.adjust(app, 1, 8000)
                    "minus" -> ShipTimeController.adjust(app, -1, 8000)
                    "set" -> ShipTimeController.setOffset(app, intent.getIntExtra("offset", 0), 8000)
                    "reapply" -> ShipTimeController.reapply(app, 8000)
                    "sync_on" -> { p.sync = true; null }
                    "sync_off" -> { p.sync = false; null }
                    else -> null
                }
                Log.i(
                    TAG,
                    "RESULT id=$id cmd=$cmd ok=${out?.ok} offset=${p.offset} target=${Zones.zoneId(p.offset)} " +
                        "sys=${SysTz.current()} shizuku=${ShizukuPriv.isRunning()}/${ShizukuPriv.hasPermission()} " +
                        "uid=${ShizukuPriv.uid()} sdk=${android.os.Build.VERSION.SDK_INT} msg=${out?.message}",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "RESULT id=$id cmd=$cmd ok=false error=$t")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ShipTime"
    }
}
