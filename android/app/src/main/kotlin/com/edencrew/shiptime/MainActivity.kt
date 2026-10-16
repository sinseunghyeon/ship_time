package com.edencrew.shiptime

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingPermResult: MethodChannel.Result? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        val r = pendingPermResult
        pendingPermResult = null
        main.post { r?.success(mapOf("granted" to (grant == PackageManager.PERMISSION_GRANTED))) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdbPriv.init(applicationContext)
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            val ctx = applicationContext
            val p = Prefs(ctx)
            when (call.method) {
                "getState" -> background(result) { state(ctx) }
                "adjust" -> background(result) {
                    val o = ShipTimeController.adjust(ctx, call.argument<Int>("delta") ?: 0, 15000)
                    state(ctx) + mapOf("lastOk" to o.ok, "lastMessage" to o.message)
                }
                "setOffset" -> background(result) {
                    val o = ShipTimeController.setOffset(ctx, call.argument<Int>("offset") ?: 0, 15000)
                    state(ctx) + mapOf("lastOk" to o.ok, "lastMessage" to o.message)
                }
                "reapply" -> background(result) {
                    val o = ShipTimeController.reapply(ctx)
                    state(ctx) + mapOf("lastOk" to o.ok, "lastMessage" to o.message)
                }
                "adoptSystemOffset" -> background(result) {
                    p.offset = Zones.systemOffsetHours()
                    p.setStatus(Prefs.STATE_NONE, "")
                    WidgetUpdater.updateAll(ctx)
                    state(ctx)
                }
                "setSync" -> background(result) {
                    p.sync = call.argument<Boolean>("value") ?: true
                    p.setStatus(Prefs.STATE_NONE, "")
                    WidgetUpdater.updateAll(ctx)
                    state(ctx)
                }
                "setUseRoot" -> background(result) {
                    val v = call.argument<Boolean>("value") ?: false
                    p.useRoot = v
                    if (v) RootPriv.check()
                    state(ctx)
                }
                "adbConnect" -> background(result) {
                    AdbPriv.init(ctx)
                    val ok = AdbPriv.connect(10_000)
                    state(ctx) + mapOf("adbConnectOk" to ok, "adbError" to AdbPriv.lastError)
                }
                "adbDisconnect" -> background(result) {
                    AdbPriv.disconnect()
                    state(ctx)
                }
                "adbDiscoverPairingPort" -> background(result) {
                    AdbPriv.init(ctx)
                    mapOf("port" to AdbPriv.discoverPairingPort(20_000))
                }
                "adbPair" -> background(result) {
                    AdbPriv.init(ctx)
                    val port = call.argument<Int>("port") ?: 0
                    val code = call.argument<String>("code") ?: ""
                    val ok = AdbPriv.pair(port, code)
                    state(ctx) + mapOf("adbPairOk" to ok, "adbError" to AdbPriv.lastError)
                }
                "requestShizukuPermission" -> requestShizukuPermission(result)
                "openShizuku" -> {
                    val launch = packageManager.getLaunchIntentForPackage(ShizukuPriv.PKG)
                    val i = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL))
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.success(launch != null)
                }
                "openDateSettings" -> {
                    startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.success(true)
                }
                "openDeveloperSettings" -> {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.success(true)
                    } catch (_: Throwable) {
                        result.success(false)
                    }
                }
                "getLog" -> result.success(p.log)
                "clearLog" -> {
                    p.clearLog()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun requestShizukuPermission(result: MethodChannel.Result) {
        try {
            when {
                !ShizukuPriv.isRunning() -> result.success(mapOf("granted" to false, "reason" to "not_running"))
                ShizukuPriv.isPreV11() -> result.success(mapOf("granted" to false, "reason" to "too_old"))
                ShizukuPriv.hasPermission() -> result.success(mapOf("granted" to true))
                Shizuku.shouldShowRequestPermissionRationale() ->
                    result.success(mapOf("granted" to false, "reason" to "denied_permanently"))
                else -> {
                    pendingPermResult?.success(mapOf("granted" to false, "reason" to "superseded"))
                    pendingPermResult = result
                    Shizuku.requestPermission(REQ_SHIZUKU)
                }
            }
        } catch (t: Throwable) {
            result.success(mapOf("granted" to false, "reason" to (t.message ?: t.javaClass.simpleName)))
        }
    }

    private fun background(result: MethodChannel.Result, block: () -> Any?) {
        io.execute {
            try {
                val v = block()
                main.post { result.success(v) }
            } catch (t: Throwable) {
                main.post { result.error("native", t.message ?: t.javaClass.simpleName, null) }
            }
        }
    }

    private fun state(ctx: Context): Map<String, Any?> {
        AdbPriv.init(ctx)
        val p = Prefs(ctx)
        val sysZone = SysTz.current()
        val installed = try {
            ctx.packageManager.getPackageInfo(ShizukuPriv.PKG, 0)
            true
        } catch (_: Throwable) {
            false
        }
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val autoTz = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME_ZONE, -1)
        } catch (_: Throwable) {
            -1
        }
        return mapOf(
            "offset" to p.offset,
            "label" to Zones.label(p.offset),
            "zoneId" to Zones.zoneId(p.offset),
            "sync" to p.sync,
            "useRoot" to p.useRoot,
            "rootVerified" to RootPriv.verified,
            "statusState" to p.statusState,
            "statusText" to p.statusText,
            "systemZone" to sysZone,
            "systemOffsetMinutes" to ((Zones.offsetMsOf(sysZone) ?: 0) / 60000),
            "autoTimeZone" to autoTz,
            "adbSupported" to AdbPriv.supported(),
            "adbConnected" to AdbPriv.connected,
            "adbNeedsPairing" to AdbPriv.needsPairing,
            "adbError" to AdbPriv.lastError,
            "shizukuInstalled" to installed,
            "shizukuRunning" to ShizukuPriv.isRunning(),
            "shizukuPermission" to ShizukuPriv.hasPermission(),
            "shizukuVersion" to ShizukuPriv.version(),
            "shizukuUid" to ShizukuPriv.uid(),
            "deviceOwner" to dpm.isDeviceOwnerApp(ctx.packageName),
            "sdk" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    companion object {
        private const val CHANNEL = "shiptime/native"
        private const val REQ_SHIZUKU = 4210
        private const val SHIZUKU_URL = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
    }
}
