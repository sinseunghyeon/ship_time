package com.edencrew.shiptime

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import kotlin.concurrent.thread

/**
 * 무선 디버깅 스위치 제어.
 *
 * 처음 ADB 연결에 성공하면 그 셸 권한으로 이 앱에 WRITE_SECURE_SETTINGS 를 부여해 둔다
 * (AdbPriv.onConnected). 이후로는 앱이 직접 `adb_wifi_enabled` 를 켤 수 있어서
 * 재부팅 뒤에도 사용자가 개발자 옵션에 들어갈 필요가 없다.
 *
 * 제약: 시스템은 "이 네트워크에서 항상 허용"으로 신뢰한 Wi-Fi 에서만 무선 디버깅을 실제로 켠다.
 */
object WirelessAdb {
    /** Settings.Global.ADB_WIFI_ENABLED (숨김 상수) */
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    fun hasSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    } catch (_: Throwable) {
        false
    }

    /** 자동 켜기를 써도 되는 상태인지: 한 번이라도 연결에 성공했고, 권한이 있고, 사용자가 끄지 않았다 */
    fun autoAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val p = Prefs(ctx)
        return p.autoAdb && p.adbEverConnected && hasSecureSettings(ctx)
    }

    /**
     * 꺼져 있으면 켠다.
     * @return 이번 호출로 새로 켰으면 true (adbd 가 뜨고 mDNS 광고까지 잠시 기다려야 함)
     */
    fun enableIfAllowed(ctx: Context): Boolean {
        if (!autoAllowed(ctx) || isEnabled(ctx)) return false
        return try {
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(ctx.contentResolver, ADB_WIFI_ENABLED, 1)
        } catch (_: Throwable) {
            false
        }
    }

    /** 개발자 옵션의 "무선 디버깅" 항목으로 이동 (항목 강조는 지원하는 기기에서만) */
    fun openSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", "toggle_adb_wireless")
            })
        try {
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }
}

/** 부팅 완료 → Wi-Fi 가 잡히면 무선 디버깅을 켜고 다시 연결하는 작업을 예약 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!WirelessAdb.autoAllowed(ctx)) return
        AdbReconnectJob.schedule(ctx)
    }
}

/** Wi-Fi(비종량제 네트워크) 연결을 조건으로 실행되는 재연결 작업 */
class AdbReconnectJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        thread(name = "adb-reconnect") {
            var ok = false
            val log = mutableListOf("부팅 후 자동 연결")
            try {
                if (WirelessAdb.enableIfAllowed(app)) {
                    log += "무선 디버깅 켬"
                } else if (!WirelessAdb.isEnabled(app)) {
                    log += "무선 디버깅을 켤 수 없음 (권한 없음 또는 자동 연결 꺼짐)"
                }
                AdbPriv.init(app)
                // adbd 기동 + mDNS 광고까지 수 초 걸릴 수 있어 몇 번 나눠 시도
                for (attempt in 1..4) {
                    if (AdbPriv.connect(8_000)) {
                        ok = true
                        break
                    }
                    Thread.sleep(3_000)
                }
                log += if (ok) "✓ ADB 연결됨" else "✗ 연결 실패: ${AdbPriv.lastError ?: "알 수 없음"}"
            } catch (t: Throwable) {
                log += "✗ 예외: ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                Prefs(app).appendLog(log)
                WidgetUpdater.updateAll(app)
                jobFinished(params, !ok)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4211

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AdbReconnectJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            js.schedule(job)
        }
    }
}
