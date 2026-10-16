package com.edencrew.shiptime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * 알림으로 무선 디버깅 페어링.
 *
 * 설정의 "페어링 코드" 창은 다른 앱으로 전환하면 닫히는 기기가 많다. 그래서 이 서비스가
 * 백그라운드에서 페어링 포트를 mDNS 로 찾고, 찾으면 알림의 입력칸(RemoteInput)으로 코드를 받는다.
 * 사용자는 설정 화면을 벗어나지 않고 알림을 내려 6자리만 입력하면 된다.
 */
class PairingService : Service() {
    @Volatile private var port: Int? = null
    @Volatile private var running = false
    @Volatile private var pairing = false
    /** 직전 실패 사유 — 포트를 다시 찾았을 때 알림 제목으로 계속 보여준다 */
    @Volatile private var hint: String? = null
    /** 실패한 포트 — 설정의 페어링 창은 실패하면 닫히고 새 포트로 다시 열리므로, mDNS 캐시에 남은 옛 포트는 건너뛴다 */
    private val badPorts = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    /** 닫힌 포트로 시도해 실패한 코드 — 새 포트를 찾으면 사용자 입력 없이 이 코드로 재시도 */
    @Volatile private var pendingCode: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            ACTION_SUBMIT -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                    ?: intent.getStringExtra(KEY_CODE) // debug 훅
                Log.i(TAG, "코드 수신: ${code?.length ?: 0}자, port=$port, pairing=$pairing")
                submit(code?.filter { it.isDigit() } ?: "")
                return START_NOT_STICKY
            }
        }
        // ACTION_START
        startInForeground(searching())
        if (!running) {
            running = true
            discoverLoop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun discoverLoop() = thread(name = "adb-pair-discover") {
        AdbPriv.init(applicationContext)
        val until = System.currentTimeMillis() + SEARCH_MS
        while (running && System.currentTimeMillis() < until) {
            // 포트를 찾았으면 코드 입력을 기다린다 (페어링 실패 시 port=null 로 되돌려 다시 탐색)
            if (pairing || port != null) {
                Thread.sleep(500)
                continue
            }
            val p = AdbPriv.discoverPairingPort(10_000)
            if (!running) break
            if (p != null && p in badPorts) {
                Thread.sleep(1000)
                continue
            }
            if (p != null) {
                Log.i(TAG, "페어링 포트 발견: $p")
                port = p
                val retry = pendingCode
                if (retry != null) {
                    pendingCode = null
                    submit(retry)
                } else {
                    notify(askCode(hint))
                }
            }
        }
        if (running && port == null) {
            notify(done("페어링 포트를 찾지 못했습니다", "무선 디버깅 > \"페어링 코드로 기기 페어링\"을 연 상태에서 다시 시도하세요."))
            stopForegroundKeep()
            running = false
            stopSelf()
        }
    }

    private fun submit(code: String) {
        val p = port
        if (p == null) {
            notify(searching())
            return
        }
        if (code.length != 6) {
            notify(askCode("6자리 숫자를 입력하세요"))
            return
        }
        if (pairing) return
        pairing = true
        notify(progress("페어링 중…"))
        thread(name = "adb-pair") {
            val app = applicationContext
            AdbPriv.init(app)
            val ok = try {
                AdbPriv.pair(p, code)
            } catch (t: Throwable) {
                Log.w(TAG, "페어링 예외", t)
                false
            }
            Log.i(TAG, "페어링 결과: ok=$ok err=${AdbPriv.lastError}")
            if (ok) {
                Prefs(app).appendLog(listOf("✓ 알림으로 페어링·연결 완료 (포트 $p)"))
                WidgetUpdater.updateAll(app)
                notify(done("페어링 완료", "이제 위젯 버튼으로 시스템 시간대가 바뀝니다."))
                stopForegroundKeep()
                running = false
                stopSelf()
            } else {
                badPorts += p
                port = null
                if (isConnRefused(AdbPriv.lastError)) {
                    // mDNS 캐시에 남은 닫힌 포트였다 → 코드는 아직 유효하므로 새 포트를 찾아 자동 재시도
                    pendingCode = code
                    notify(progress("페어링 포트 다시 찾는 중…"))
                } else {
                    // 코드가 틀렸으면 설정의 페어링 창이 닫힌다 → 새 코드 필요
                    hint = "실패: ${AdbPriv.lastError ?: "코드 확인"} — 페어링 창을 다시 열고 새 코드를 입력하세요"
                    notify(askCode(hint))
                }
            }
            pairing = false
        }
    }

    private fun isConnRefused(err: String?): Boolean =
        err != null && (err.contains("ECONNREFUSED") || err.contains("Connection refused", ignoreCase = true))

    private fun stopAll() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 결과 알림은 남기고 foreground 상태만 해제 */
    private fun stopForegroundKeep() {
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    // ── 알림 ─────────────────────────────────────────────────────────────

    private fun builder(): Notification.Builder {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ship)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
    }

    private fun cancelAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this, 2, Intent(this, PairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, "취소", pi).build()
    }

    private fun searching() = builder()
        .setContentTitle("페어링 포트 찾는 중…")
        .setContentText("무선 디버깅 > \"페어링 코드로 기기 페어링\"을 누르세요")
        .setOngoing(true)
        .setProgress(0, 0, true)
        .addAction(cancelAction())
        .build()

    private fun askCode(error: String?): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6자리 페어링 코드").build()
        // RemoteInput 결과를 채워 넣어야 하므로 MUTABLE (Android 12+ 필수 플래그)
        val pi = PendingIntent.getService(
            this, 1, Intent(this, PairingService::class.java).setAction(ACTION_SUBMIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(null, "코드 입력", pi).addRemoteInput(input).build()
        return builder()
            .setContentTitle(error ?: "페어링 코드를 입력하세요")
            .setContentText("포트 ${port ?: "?"} 발견 — 알림을 내려 \"코드 입력\"을 누르세요")
            .setOngoing(true)
            .addAction(action)
            .addAction(cancelAction())
            .build()
    }

    private fun progress(title: String) = builder()
        .setContentTitle(title)
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    private fun done(title: String, text: String) = builder()
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .build()

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_START = "com.edencrew.shiptime.pair.START"
        const val ACTION_SUBMIT = "com.edencrew.shiptime.pair.SUBMIT"
        const val ACTION_STOP = "com.edencrew.shiptime.pair.STOP"
        const val KEY_CODE = "code"
        private const val TAG = "ShipTime"
        private const val CHANNEL = "adb_pairing"
        private const val NOTIF_ID = 4212
        private const val SEARCH_MS = 5 * 60_000L

        /** 무선 디버깅 페어링은 Android 11+ 전용이므로 그 이하에서는 아무 것도 하지 않는다 */
        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            ctx.startForegroundService(Intent(ctx, PairingService::class.java).setAction(ACTION_START))
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "무선 디버깅 페어링", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "페어링 코드를 알림에서 바로 입력합니다"
                },
            )
        }
    }
}
