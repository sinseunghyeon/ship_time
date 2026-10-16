package com.edencrew.shiptime

import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlin.concurrent.thread

open class ShipTimeWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.updateAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLUS, ACTION_MINUS -> {
                val delta = if (intent.action == ACTION_PLUS) 1 else -1
                // 브로드캐스트는 goAsync 로 약 10초까지 연장 가능
                val pending = goAsync()
                thread(name = "ship-time-adjust") {
                    try {
                        ShipTimeController.adjust(ctx, delta, budgetMs = 8000)
                    } catch (_: Throwable) {
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> WidgetUpdater.updateAll(ctx)
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_PLUS = "com.edencrew.shiptime.PLUS"
        const val ACTION_MINUS = "com.edencrew.shiptime.MINUS"
    }
}

object WidgetUpdater {
    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        updateProvider(ctx, mgr, ShipTimeWidget::class.java, R.layout.widget_ship_time)
        updateProvider(ctx, mgr, ShipTimeWidgetLarge::class.java, R.layout.widget_ship_time_large)
    }

    private fun updateProvider(ctx: Context, mgr: AppWidgetManager, cls: Class<*>, layout: Int) {
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
        if (ids.isEmpty()) return
        val views = build(ctx, layout)
        for (id in ids) mgr.updateAppWidget(id, views)
    }

    private fun build(ctx: Context, layout: Int): RemoteViews {
        val p = Prefs(ctx)
        val o = p.offset
        val v = RemoteViews(ctx.packageName, layout)
        v.setTextViewText(R.id.offset_label, Zones.label(o))
        v.setString(R.id.ship_clock, "setTimeZone", Zones.zoneId(o))
        v.setString(R.id.utc_clock, "setTimeZone", "Etc/UTC")

        val (text, color) = statusOf(ctx, p, o)
        v.setTextViewText(R.id.status, text)
        v.setTextColor(R.id.status, ctx.getColor(color))

        v.setOnClickPendingIntent(R.id.btn_minus, actionIntent(ctx, ShipTimeWidget.ACTION_MINUS, 1))
        v.setOnClickPendingIntent(R.id.btn_plus, actionIntent(ctx, ShipTimeWidget.ACTION_PLUS, 2))
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        v.setOnClickPendingIntent(
            R.id.clock_area,
            PendingIntent.getActivity(ctx, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        return v
    }

    private fun statusOf(ctx: Context, p: Prefs, o: Int): Pair<String, Int> {
        if (!p.sync) return "표시 전용" to R.color.widget_sub
        return when (p.statusState) {
            Prefs.STATE_PENDING -> "적용 중…" to R.color.status_warn
            Prefs.STATE_FAIL -> "적용 실패" to R.color.status_fail
            else -> {
                val sysMs = Zones.offsetMsOf(SysTz.current())
                if (sysMs == o * 3_600_000) "시스템 연동" to R.color.status_ok
                else "기기 시계와 다름" to R.color.status_warn
            }
        }
    }

    private fun actionIntent(ctx: Context, action: String, req: Int): PendingIntent {
        val i = Intent(ctx, ShipTimeWidget::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** 큰 고양이 위젯 — 동작은 작은 위젯과 동일, 레이아웃만 다르다. */
class ShipTimeWidgetLarge : ShipTimeWidget()

/** adb shell dpm set-device-owner com.edencrew.shiptime/.ShipAdminReceiver 로 등록 시 사용 (선택) */
class ShipAdminReceiver : DeviceAdminReceiver()
