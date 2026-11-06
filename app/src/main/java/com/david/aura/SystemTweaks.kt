package com.david.aura

import android.Manifest
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.launch

/**
 * Ajustes del sistema que el onboarding desactiva (privacidad, anuncios, notificaciones, contenido destacado,
 * salvapantallas y sonidos). Lista CERRADA: la web solo puede pedir un id de esta lista y "bloqueado" si/no.
 *
 * "blocked = true" significa que la funcion de Amazon queda desactivada (lo recomendado por Aura).
 *
 * Permisos (se conceden una vez desde el PC, deploy.ps1 lo hace):
 *   pm grant com.david.aura android.permission.WRITE_SECURE_SETTINGS
 *   appops set com.david.aura WRITE_SETTINGS allow
 *   cmd notification allow_dnd com.david.aura
 */
object SystemTweaks {
    const val AURA_DREAM = "com.david.aura/com.david.aura.AuraDream"
    const val AMAZON_DREAM = "com.amazon.ftv.screensaver/.app.services.ScreensaverService"
    private const val ADGUARD = "dns.adguard-dns.com"
    private const val VIDEO_KEY = "com.amazon.tv.settings.featured_item_video_enabled"
    private const val AUDIO_KEY = "com.amazon.tv.settings.featured_item_video_volume"

    enum class Need { SECURE, SYSTEM, DND, MANUAL }

    class Tweak(
        val id: String,
        val group: String,
        val icon: Int,
        private val es: String,
        private val en: String,
        private val subEs: String,
        private val subEn: String,
        val need: Need,
        /** true si "bloqueado" se muestra como "Activado" (No interrumpir). */
        val inverted: Boolean = false,
    ) {
        fun title(lang: String) = if (lang == "en") en else es
        fun sub(lang: String) = if (lang == "en") subEn else subEs
        val manual get() = need == Need.MANUAL
    }

    val GROUPS = linkedMapOf(
        "privacy" to ("Privacidad y datos" to "Privacy & data"),
        "data" to ("Uso de datos" to "Data usage"),
        "notif" to ("Notificaciones" to "Notifications"),
        "home" to ("Pantalla de inicio y contenido" to "Home screen & content"),
        "screen" to ("Pantalla y sonido" to "Display & sound"),
    )

    fun groupTitle(group: String, lang: String) = GROUPS[group]?.let { if (lang == "en") it.second else it.first } ?: group

    val ALL = listOf(
        Tweak("device_usage", "privacy", R.drawable.ri_chart, "Datos de uso del dispositivo", "Device usage data",
            "El sistema deja de procesar información en segundo plano", "The system stops processing data in the background", Need.SECURE),
        Tweak("app_usage", "privacy", R.drawable.ri_eye_off, "Recopilar uso de las apps", "Collect app usage data",
            "Libera recursos de procesamiento", "Frees processing resources", Need.SECURE),
        Tweak("interest_ads", "privacy", R.drawable.ri_ban, "Anuncios basados en intereses", "Interest-based ads",
            "Sin publicidad segmentada en el dispositivo", "No targeted advertising on the device", Need.SECURE),
        Tweak("ad_dns", "privacy", R.drawable.ri_shield_check, "Bloqueo de anuncios y rastreadores", "Ad and tracker blocking",
            "DNS privado de AdGuard para todo el Fire TV", "AdGuard private DNS for the whole Fire TV", Need.SECURE, inverted = true),
        Tweak("data_monitoring", "data", R.drawable.ri_data, "Control del uso de datos", "Data monitoring",
            "Amazon no deja que otras apps lo cambien. Viene apagado de fábrica; puedes revisarlo en Preferencias.",
            "Amazon doesn't let other apps change it. It's off by default; you can check it in Preferences.", Need.MANUAL),
        Tweak("dnd", "notif", R.drawable.ri_bell_off, "No interrumpir", "Do not disturb",
            "Sin avisos del sistema ni de actualizaciones mientras ves algo", "No system or update pop-ups while you watch", Need.DND, inverted = true),
        Tweak("video_autoplay", "home", R.drawable.ri_video, "Reproducción automática de vídeo", "Video autoplay",
            "Los tráileres del contenido destacado no se reproducen solos", "Featured content trailers don't play on their own", Need.SECURE),
        Tweak("audio_autoplay", "home", R.drawable.ri_volume_cross, "Reproducción automática de audio", "Audio autoplay",
            "Los banners promocionales no suenan solos", "Promotional banners don't play sound", Need.SECURE),
        Tweak("screensaver", "screen", R.drawable.ri_moon_sleep, "Salvapantallas de Amazon", "Amazon screensaver",
            "Se sustituye por el de Aura: hora, fecha y clima sobre tus fondos", "Replaced by Aura's: time, date and weather over your wallpapers", Need.SECURE),
        Tweak("nav_sounds", "screen", R.drawable.ri_soundwave, "Sonidos de navegación", "Navigation sounds",
            "Sin sonidos al moverte por los menús", "No sounds when moving through menus", Need.SYSTEM),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    // ---------- permisos ----------
    fun canSecure(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun canSystem(ctx: Context) = Settings.System.canWrite(ctx)
    fun canDnd(ctx: Context) = runCatching { ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }.getOrDefault(false)

    fun allowed(ctx: Context, t: Tweak) = when (t.need) {
        Need.SECURE -> canSecure(ctx)
        Need.SYSTEM -> canSystem(ctx) || canSecure(ctx)
        Need.DND -> canDnd(ctx)
        Need.MANUAL -> false
    }

    /** Faltan permisos que solo se pueden dar desde el PC. */
    fun missingPermissions(ctx: Context) = !canSecure(ctx) || !canSystem(ctx) || !canDnd(ctx)

    // ---------- lectura ----------
    private fun secure(cr: ContentResolver, k: String) = runCatching { Settings.Secure.getString(cr, k) }.getOrNull()
    private fun global(cr: ContentResolver, k: String) = runCatching { Settings.Global.getString(cr, k) }.getOrNull()
    private fun system(cr: ContentResolver, k: String) = runCatching { Settings.System.getString(cr, k) }.getOrNull()

    /** true = funcion de Amazon desactivada; null = no se puede saber. */
    fun isBlocked(ctx: Context, t: Tweak): Boolean? {
        val cr = ctx.contentResolver
        return when (t.id) {
            "device_usage" -> secure(cr, "settingsApp:deviceUsageData:value") == "0"
            "app_usage" -> secure(cr, "settingsApp:appUsageData:value") == "0"
            "interest_ads" -> secure(cr, "limit_ad_tracking") == "1"
            "ad_dns" -> global(cr, "private_dns_mode") == "hostname" && global(cr, "private_dns_specifier") == ADGUARD
            "dnd" -> runCatching {
                ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            }.getOrNull()
            "video_autoplay" -> (global(cr, VIDEO_KEY) ?: secure(cr, VIDEO_KEY)) == "0"
            "audio_autoplay" -> (global(cr, AUDIO_KEY) ?: secure(cr, AUDIO_KEY)) == "0"
            "screensaver" -> Aura.config.state.value.manageScreensaver
            "nav_sounds" -> system(cr, Settings.System.SOUND_EFFECTS_ENABLED) == "0"
            else -> null
        }
    }

    // ---------- escritura ----------
    private fun putSecure(cr: ContentResolver, k: String, v: String?) = Settings.Secure.putString(cr, k, v)
    private fun putGlobal(cr: ContentResolver, k: String, v: String?) = Settings.Global.putString(cr, k, v)

    /** Aplica un ajuste y recuerda la eleccion para reaplicarla si Fire OS la revierte. */
    fun apply(ctx: Context, t: Tweak, blocked: Boolean): Boolean {
        val ok = applyRaw(ctx, t, blocked)
        if (ok && !t.manual) Aura.config.update { c ->
            val cur = c.enforcedTweaks.orEmpty()
            c.copy(enforcedTweaks = if (blocked) (cur + t.id).distinct() else cur - t.id)
        }
        return ok
    }

    /**
     * Fire OS restaura algunos ajustes al reiniciar (p. ej. los sonidos de navegacion).
     * Se vuelven a aplicar los que el usuario dejo desactivados.
     */
    fun enforce(ctx: Context) {
        val cfg = Aura.config.state.value
        if (!cfg.onboardingDone) return
        val ids = cfg.enforcedTweaks ?: ALL.filter { !it.manual }.map { it.id }.also { all ->
            // Instalaciones anteriores a esta version: la bienvenida ya lo desactivo todo
            Aura.config.update { it.copy(enforcedTweaks = all) }
        }
        ALL.filter { it.id in ids && !it.manual }.forEach { t ->
            if (isBlocked(ctx, t) == false) {
                android.util.Log.i("AuraTweaks", "Fire OS revirtio ${t.id}: se vuelve a desactivar")
                applyRaw(ctx, t, true)
            }
        }
    }

    /**
     * Vigila al instante los ajustes que Fire OS cambia por su cuenta (p. ej. los sonidos de navegacion al
     * reiniciar, via RestoreNavigationSoundsReceiver de Amazon, que no se puede desactivar). En cuanto cambian,
     * se vuelve a aplicar lo que el usuario eligio en Aura.
     */
    fun watch(ctx: Context) {
        val app = ctx.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val run = Runnable {
            Aura.scope.launch {
                runCatching { enforce(app) }
                runCatching { applyPower(app, Aura.config.state.value) }
            }
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(run); handler.postDelayed(run, 400) }
        }
        val cr = app.contentResolver
        val uris = listOf(
            Settings.System.getUriFor(Settings.System.SOUND_EFFECTS_ENABLED),
            Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT),
            Settings.Global.getUriFor(VIDEO_KEY), Settings.Secure.getUriFor(VIDEO_KEY),
            Settings.Global.getUriFor(AUDIO_KEY), Settings.Secure.getUriFor(AUDIO_KEY),
            Settings.Global.getUriFor("private_dns_mode"),
            Settings.Secure.getUriFor("settingsApp:deviceUsageData:value"),
            Settings.Secure.getUriFor("settingsApp:appUsageData:value"),
            Settings.Secure.getUriFor("limit_ad_tracking"),
            Settings.Secure.getUriFor("screensaver_components"),
            Settings.Secure.getUriFor("sleep_timeout"),
        )
        uris.forEach { runCatching { cr.registerContentObserver(it, false, observer) } }
        // El boton Inicio: si alguien apaga el servicio de accesibilidad, se enciende al momento
        val acc = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.postDelayed({ runCatching { ensureHomeService(app) } }, 300) }
        }
        listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED).forEach {
            runCatching { cr.registerContentObserver(Settings.Secure.getUriFor(it), false, acc) }
        }
        runCatching { ensureHomeService(app) }
    }

    private const val HOME_SERVICE = "com.david.aura/com.david.aura.HomeWatcherService"

    /**
     * El servicio de accesibilidad es lo que captura el boton Inicio del mando. Fire OS lo apaga a veces
     * (al actualizar Aura y otras veces sin avisar): si Aura esta activada como launcher, se vuelve a encender.
     */
    fun ensureHomeService(ctx: Context) {
        if (!Aura.config.state.value.launcherEnabled || !canSecure(ctx)) return
        val cr = ctx.contentResolver
        val cur = secure(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val list = cur.split(':').filter { it.isNotBlank() }
        val on = list.any { it.equals(HOME_SERVICE, true) || it.equals("com.david.aura/.HomeWatcherService", true) }
        if (on && secure(cr, Settings.Secure.ACCESSIBILITY_ENABLED) == "1") return
        android.util.Log.w("AuraHome", "Servicio de accesibilidad apagado (valor='$cur'): se vuelve a encender")
        runCatching {
            if (!on) putSecure(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + HOME_SERVICE).joinToString(":"))
            putSecure(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        }
    }

    private fun applyRaw(ctx: Context, t: Tweak, blocked: Boolean): Boolean = runCatching {
        if (!allowed(ctx, t)) return false
        val cr = ctx.contentResolver
        val off = if (blocked) "0" else "1"
        val on = if (blocked) "1" else "0"
        when (t.id) {
            "device_usage" -> {
                putSecure(cr, "settingsApp:deviceUsageData:value", off)
                putSecure(cr, "settingsApp:deviceUsageData:syncPending", "true")
                putSecure(cr, "USAGE_METRICS_UPLOAD_ENABLED", off)
            }
            "app_usage" -> {
                putSecure(cr, "settingsApp:appUsageData:value", off)
                putSecure(cr, "settingsApp:appUsageData:syncPending", "true")
                putSecure(cr, "pact_usage_collection_enabled", off)
            }
            "interest_ads" -> {
                putSecure(cr, "limit_ad_tracking", on)
                putSecure(cr, "amz_limit_ad_tracking", on)
                putSecure(cr, "non_amz_limit_ad_tracking", on)
                putSecure(cr, "advertisingIdApp:interestBasedAds:value", off)
                putSecure(cr, "advertisingIdApp:interestBasedAds:syncPending", "true")
                putSecure(cr, "usage_metrics_marketing_enabled", off)
            }
            "ad_dns" -> {
                if (blocked) {
                    putGlobal(cr, "private_dns_specifier", ADGUARD)
                    putGlobal(cr, "private_dns_mode", "hostname")
                } else {
                    putGlobal(cr, "private_dns_mode", "off")
                }
            }
            "dnd" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (blocked) {
                    // Prioridad: solo alarmas y multimedia (nunca silencia las apps de video)
                    if (Build.VERSION.SDK_INT >= 28) {
                        nm.notificationPolicy = NotificationManager.Policy(
                            NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA,
                            0, 0,
                            NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT,
                        )
                    }
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                } else {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
                if (canSecure(ctx)) putGlobal(cr, "heads_up_notifications_enabled", if (blocked) "0" else "1")
            }
            // Sin valor = lo que Amazon trae por defecto (activado)
            "video_autoplay" -> { putGlobal(cr, VIDEO_KEY, if (blocked) "0" else null); putSecure(cr, VIDEO_KEY, if (blocked) "0" else null) }
            "audio_autoplay" -> { putGlobal(cr, AUDIO_KEY, if (blocked) "0" else null); putSecure(cr, AUDIO_KEY, if (blocked) "0" else null) }
            "screensaver" -> {
                Aura.config.update { it.copy(manageScreensaver = blocked) }
                if (blocked) applyPower(ctx, Aura.config.state.value) else restoreAmazonPower(ctx)
            }
            "nav_sounds" -> {
                Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, if (blocked) 0 else 1)
                val am = ctx.getSystemService(AudioManager::class.java)
                if (blocked) am.unloadSoundEffects() else am.loadSoundEffects()
            }
            else -> return false
        }
        true
    }.getOrDefault(false)

    /**
     * Salvapantallas y apagado de pantalla gestionados por Aura.
     *  - screen_off_timeout: inactividad hasta que empieza el salvapantallas (o se apaga si no hay salvapantallas)
     *  - sleep_timeout: inactividad hasta que el Fire TV se duerme (-1 = nunca)
     */
    fun applyPower(ctx: Context, cfg: AuraConfig) {
        if (!cfg.manageScreensaver || !canSecure(ctx)) return
        runCatching {
            val cr = ctx.contentResolver
            val start = cfg.screensaverMinutes * 60_000L
            val off = cfg.screenOffMinutes * 60_000L
            if (cfg.screensaverEnabled) {
                putSecure(cr, "screensaver_components", AURA_DREAM)
                putSecure(cr, "screensaver_enabled", "1")
                putSecure(cr, "screensaver_activate_on_sleep", "1")
                putSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT, start.toString())
            } else {
                putSecure(cr, "screensaver_enabled", "0")
                putSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT, (if (off == 0L) Int.MAX_VALUE.toLong() else off).toString())
            }
            val sleep = if (off == 0L) -1L else off + (if (cfg.screensaverEnabled) start else 0L)
            putSecure(cr, "sleep_timeout", sleep.toString())
        }
    }

    /** Vuelve al salvapantallas de Amazon con sus tiempos de fabrica (5 min / dormir a los 20). */
    private fun restoreAmazonPower(ctx: Context) {
        val cr = ctx.contentResolver
        putSecure(cr, "screensaver_components", AMAZON_DREAM)
        putSecure(cr, "screensaver_enabled", "1")
        putSystem(ctx, Settings.System.SCREEN_OFF_TIMEOUT, "300000")
        putSecure(cr, "sleep_timeout", "1200000")
    }

    private fun putSystem(ctx: Context, k: String, v: String) {
        runCatching { Settings.System.putString(ctx.contentResolver, k, v) }
    }

    /** Desactiva todo lo que se pueda (onboarding / "Aplicar todo"). */
    fun applyAll(ctx: Context, ids: Collection<String> = ALL.map { it.id }): Map<String, Boolean> =
        ALL.filter { it.id in ids && !it.manual }.associate { it.id to apply(ctx, it, true) }
}
