package com.david.aura

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.SecureRandom

@Serializable
data class WeatherLoc(val name: String, val lat: Double, val lon: Double)

/** Fondo de video: kind = file (subido), url (enlace directo mp4/m3u8/webm) o youtube (src = id del video). */
@Serializable
data class VideoWp(val id: String, val kind: String, val src: String, val name: String)

@Serializable
data class AuraConfig(
    /** Las configuraciones antiguas no tienen este campo: por defecto 1 para que se migren. */
    val configVersion: Int = 1,
    /** Apps de la fila principal, en orden. */
    val pinned: List<String> = DEFAULT_PINNED,
    /** Apps que no aparecen en ninguna fila. */
    val hidden: List<String> = DEFAULT_HIDDEN,
    /** app (cambia con la app enfocada, dentro de la franja horaria) | time | rotate | fixed */
    val wallpaperMode: String = "app",
    /** Fondo elegido a mano para una app concreta (opcional). */
    val appWallpapers: Map<String, String> = emptyMap(),
    val fixedWallpaper: String = "asset:nat_lago_via_lactea.jpg",
    val rotateMinutes: Int = 20,
    /** Franjas: morning, afternoon, evening, night -> fondos que rotan dentro de la franja */
    val slots: Map<String, List<String>> = DEFAULT_SLOTS,
    val dayStart: Int = 7,
    val nightStart: Int = 19,
    val weather: WeatherLoc? = null,
    /** C | F */
    val tempUnit: String = "C",
    /** es | en */
    val language: String = "es",
    val use24h: Boolean = false,
    val showAllAppsRow: Boolean = false,
    /** Si es false, Aura deja de sustituir el inicio de Amazon. */
    val launcherEnabled: Boolean = true,
    /** sans | condensed | serif | mono */
    val fontFamily: String = "sans",
    /** s | m | l | xl */
    val clockSize: String = "l",
    val dateSize: String = "m",
    /** s | m | l */
    val tileSize: String = "m",
    val showGreeting: Boolean = true,
    val showDate: Boolean = true,
    val showWeather: Boolean = true,
    /** false hasta completar (u omitir) la bienvenida de la primera instalacion. */
    val onboardingDone: Boolean = false,
    /** Aura gestiona el salvapantallas y el apagado (se activa al desactivar el salvapantallas de Amazon). */
    val manageScreensaver: Boolean = false,
    val screensaverEnabled: Boolean = true,
    /** Minutos sin usar el mando hasta que empieza el salvapantallas. */
    val screensaverMinutes: Int = 5,
    /** Minutos de salvapantallas hasta apagar la pantalla. 0 = nunca. */
    val screenOffMinutes: Int = 0,
    /** Fondos de video (YouTube, enlace o subidos). Se usan con el id "video:<id>". */
    val videos: List<VideoWp> = emptyList(),
    /** Ajustes del sistema que el usuario quiere desactivados; Aura los reaplica si Fire OS los revierte. null = sin iniciar. */
    val enforcedTweaks: List<String>? = null,
    /** PIN de la web/MCP: "sal:hash" PBKDF2 en hex. Vacio = sin codigo. Nunca sale por la API. */
    val pinHash: String = "",
    /** Token para la web y el MCP. */
    val token: String = "",
) {
    companion object {
        const val CURRENT_VERSION = 4
        val MODES = setOf("app", "time", "rotate", "fixed")
        val FONTS = setOf("sans", "condensed", "serif", "mono")
        val SIZES = setOf("s", "m", "l", "xl")
        val TILE_SIZES = setOf("s", "m", "l")
        val SAVER_MINUTES = listOf(1, 2, 5, 10, 15, 30, 60)
        val OFF_MINUTES = listOf(0, 30, 60, 120, 240, 480)
        /** Campos que la web no puede cambiar con /api/config (tienen su propia ruta). */
        private val PROTECTED = setOf("token", "configVersion", "videos", "manageScreensaver", "pinHash", "enforcedTweaks")
        fun isProtected(key: String) = key in PROTECTED
        private const val JW = "com.amazon.rialto.cordova.webapp.webapp77de0d87e08947b7a1c2a37cbd056e1b"

        val DEFAULT_PINNED = listOf(
            "com.android.fvzkj", "ch.protonvpn.android", "com.disney.disneyplus", "com.amazon.firetv.youtube",
            "com.netflix.ninja", "org.smarttube.stable", "com.esaba.downloader", "dev.app.cloosi", JW,
        )

        val DEFAULT_HIDDEN = listOf(
            "com.spocky.projengmenu", "com.amazon.tv.launcher", "com.amazon.ftv.profilepicker",
            "com.amazon.ftv.screensaver", "com.amazon.ssm", "tv.projectivy.plugin.wallpaperprovider.macos",
        )

        private fun a(name: String) = "asset:$name.jpg"

        val DEFAULT_SLOTS = mapOf(
            "morning" to listOf(a("nat_valle_niebla"), a("nat_bosque_niebla"), a("nat_colinas_bruma")),
            "afternoon" to listOf(
                a("nat_arroyo_bosque"), a("nat_lago_bosque"),
                a("nat_lago_nubes"), a("nat_montanas_nieve"),
            ),
            "evening" to listOf(a("nat_rio_ocaso"), a("nat_costa_ocaso"), a("nat_lago_hora_azul"), a("nat_arboles_aurora")),
            "night" to listOf(
                a("nat_lago_via_lactea"), a("nat_montanas_estrellas"), a("nat_arboles_via_lactea"),
                a("nat_aurora_lago"), a("nat_aurora_costa"), a("nat_aurora_monte"), a("nat_cumbre_via_lactea"),
            ),
        )
    }
}

val AuraJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

class ConfigStore(private val context: Context) {
    private val file = File(context.filesDir, "config.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AuraConfig> = _state

    private fun load(): AuraConfig {
        var cfg = runCatching { AuraJson.decodeFromString<AuraConfig>(file.readText()) }.getOrNull() ?: AuraConfig()
        var dirty = false
        if (cfg.configVersion < 2) {
            // v2: solo fondos de naturaleza segun el horario. Se conservan apps, ciudad y token.
            cfg = cfg.copy(
                configVersion = 2,
                wallpaperMode = "time",
                slots = AuraConfig.DEFAULT_SLOTS,
                fixedWallpaper = AuraConfig().fixedWallpaper,
                use24h = false,
                showAllAppsRow = false,
            )
            dirty = true
        }
        if (cfg.configVersion < 3) {
            // v3: el fondo vuelve a cambiar con la app enfocada (siempre fondos de la franja horaria)
            cfg = cfg.copy(configVersion = 3, wallpaperMode = "app")
            dirty = true
        }
        if (cfg.configVersion < 4) {
            // v4: se quitan los fondos de macOS (no se pueden publicar). Las franjas vacias usan los de serie.
            fun clean(id: String) = !id.startsWith("asset:wp_")
            cfg = cfg.copy(
                configVersion = 4,
                slots = AuraConfig.DEFAULT_SLOTS.mapValues { (k, def) -> cfg.slots[k].orEmpty().filter(::clean).ifEmpty { def } },
                appWallpapers = cfg.appWallpapers.filterValues(::clean),
                fixedWallpaper = cfg.fixedWallpaper.takeIf(::clean) ?: AuraConfig().fixedWallpaper,
            )
            dirty = true
        }
        if (cfg.token.length < 20) {
            val bytes = ByteArray(15).also { SecureRandom().nextBytes(it) }
            cfg = cfg.copy(token = bytes.joinToString("") { "%02x".format(it) })
            dirty = true
        }
        if (dirty) save(cfg)
        return cfg
    }

    private fun save(cfg: AuraConfig) {
        val tmp = File(file.parentFile, "config.json.tmp")
        tmp.writeText(AuraJson.encodeToString(AuraConfig.serializer(), cfg))
        tmp.renameTo(file)
    }

    @Synchronized
    fun update(transform: (AuraConfig) -> AuraConfig) {
        val next = transform(_state.value)
        save(next)
        _state.value = next
    }

    /** Aplica un JSON parcial (desde la web) encima de la configuracion actual. El token no se puede cambiar asi. */
    fun patch(partial: JsonObject) {
        update { current ->
            val base = AuraJson.encodeToJsonElement(AuraConfig.serializer(), current).jsonObject
            val merged = buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                partial.forEach { (k, v) -> if (!AuraConfig.isProtected(k)) put(k, v) }
            }
            val next = AuraJson.decodeFromJsonElement(AuraConfig.serializer(), merged)
            next.copy(
                language = if (next.language == "en") "en" else "es",
                tempUnit = if (next.tempUnit == "F") "F" else "C",
                wallpaperMode = next.wallpaperMode.takeIf { it in AuraConfig.MODES } ?: "app",
                fontFamily = next.fontFamily.takeIf { it in AuraConfig.FONTS } ?: "sans",
                clockSize = next.clockSize.takeIf { it in AuraConfig.SIZES } ?: "l",
                dateSize = next.dateSize.takeIf { it in AuraConfig.SIZES } ?: "m",
                tileSize = next.tileSize.takeIf { it in AuraConfig.TILE_SIZES } ?: "m",
                rotateMinutes = next.rotateMinutes.coerceIn(1, 1440),
                dayStart = next.dayStart.coerceIn(0, 23),
                nightStart = next.nightStart.coerceIn(0, 23),
                screensaverMinutes = next.screensaverMinutes.takeIf { it in AuraConfig.SAVER_MINUTES } ?: 5,
                screenOffMinutes = next.screenOffMinutes.takeIf { it in AuraConfig.OFF_MINUTES } ?: 0,
            )
        }
    }
}
