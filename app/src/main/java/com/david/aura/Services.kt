package com.david.aura

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

/** Servicio en primer plano que mantiene vivo el servidor web de control. */
class WebService : Service() {
    private var server: WebServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        Aura.init(this)
        startForeground(1, notification())
        server = WebServer(this, Aura.WEB_PORT).also {
            runCatching { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        }
        // Mantiene el Wi-Fi activo para poder controlar/encender la TV en remoto
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "aura:web").apply {
                setReferenceCounted(false); acquire()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        server?.stop()
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("aura", "Aura", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "aura")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setContentTitle("Aura")
            .setContentText("Control web activo en el puerto ${Aura.WEB_PORT}")
            .setSmallIcon(R.drawable.ic_aura)
            .build()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, WebService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }

        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}

/**
 * Servicio de accesibilidad de Aura:
 *  - Fire OS no deja cambiar el launcher: cuando aparece el inicio de Amazon, abrimos Aura encima.
 *  - Ademas da al mando web la navegacion, el texto, el apagado y las capturas (ver [Remote]).
 */
class HomeWatcherService : AccessibilityService() {
    private var last = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Aura.init(this)
        instance = this
        // Tras reiniciar el Fire TV el inicio de Amazon ya esta abierto antes de que este servicio arranque:
        // se comprueba varias veces durante el arranque y, si Aura esta activada, se abre Aura.
        listOf(1_500L, 6_000L, 15_000L).forEach { handler.postDelayed(::openAuraIfAmazonHome, it) }
    }

    private fun openAuraIfAmazonHome() {
        if (!launcherOn()) return
        val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val booting = SystemClock.elapsedRealtime() < 5 * 60_000L
        if (pkg == "com.amazon.tv.launcher" || (pkg == null && booting)) {
            android.util.Log.i("AuraHome", "Arranque: abriendo Aura (ventana=$pkg)")
            goHome()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: HomeWatcherService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var longFired = false
    private val longPress = Runnable { longFired = true; goHome(openSettings = true) }

    private fun launcherOn() = runCatching { Aura.config.state.value.launcherEnabled }.getOrDefault(true)

    /** Lleva a Aura al frente (lo usan la tecla Inicio, el mando web y el vigilante del inicio de Amazon). */
    fun goHome(openSettings: Boolean = false) {
        if (openSettings) Aura.pendingSettings.value = true else Aura.homeTick.value++
        last = SystemClock.elapsedRealtime()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    /**
     * Captura la tecla Inicio antes que Fire OS: asi nunca se ve el inicio de Amazon.
     * Pulsacion corta -> Aura. Pulsacion larga -> ajustes de Aura (Fire OS no deja abrir su menu rapido).
     * Si Aura esta desactivada como launcher, la tecla sigue su camino normal.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !launcherOn()) return false
        if (event.action == KeyEvent.ACTION_UP) android.util.Log.i("AuraHome", "Inicio capturado (mantenida=$longFired)")
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                longFired = false
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, 700)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (!longFired) goHome()
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != "com.amazon.tv.launcher") return
        // Solo la pantalla de inicio de Amazon: sus Ajustes, buscador, etc. viven en el mismo paquete
        if (event.className?.toString()?.contains("HomeActivity") != true) return
        // El usuario puede desactivar Aura como launcher desde la TV o la web
        if (runCatching { !Aura.config.state.value.launcherEnabled }.getOrDefault(false)) return
        // Respaldo: si algo abre el inicio de Amazon (Atras desde una app, otra via), volvemos a Aura
        if (SystemClock.elapsedRealtime() - last < 700) return
        goHome()
    }

    override fun onInterrupt() {}
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Aura.init(context)
            WebService.start(context)
            // Fire OS desactiva la accesibilidad al actualizar una app: se reactiva (necesita WRITE_SECURE_SETTINGS)
            runCatching { SystemTweaks.ensureHomeService(context) }
            // Abre Aura al encender (si esta activada). El servicio de accesibilidad lo reintenta por si Fire OS lo bloquea.
            if (Aura.config.state.value.launcherEnabled) {
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
        }
    }
}
