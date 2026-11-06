package com.david.aura

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AuraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Aura.init(this)
        WebService.start(this)
    }
}

/** Estado global compartido entre la UI, el servidor web y los servicios. */
object Aura {
    const val WEB_PORT = 8765
    const val SETTINGS_PKG = "aura.settings"
    const val ALL_APPS_PKG = "aura.allapps"

    lateinit var app: Context
        private set
    lateinit var config: ConfigStore
        private set

    val weather = MutableStateFlow<Weather?>(null)
    /** Se incrementa cuando cambian las apps instaladas. */
    val appsVersion = MutableStateFlow(0)
    /** Se incrementa cuando se suben/borran fondos o iconos. */
    val assetsVersion = MutableStateFlow(0)
    /** Se incrementa cada vez que Aura vuelve a primer plano. */
    val resumeTick = MutableStateFlow(0)
    /** Se incrementa con cada pulsacion corta de Inicio: cierra paneles y vuelve a la primera app. */
    val homeTick = MutableStateFlow(0)
    /** Pulsacion larga de Inicio: abrir los ajustes de Aura (se consume al abrirlos). */
    val pendingSettings = MutableStateFlow(false)
    /** App enfocada ahora mismo en el launcher (para la web/MCP). */
    val focusedPkg = MutableStateFlow<String?>(null)
    val currentWallpaper = MutableStateFlow<String?>(null)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        config = ConfigStore(app)
        runCatching { Installer.register(app) }
        // Reaplica los ajustes del sistema que Fire OS revierte (al arrancar y cada 10 minutos)
        runCatching { SystemTweaks.watch(app) }
        scope.launch {
            // Nada mas arrancar y otra vez durante el arranque (Fire OS restaura cosas al iniciar)
            for (d in listOf(0L, 5_000L, 15_000L, 40_000L)) { delay(d); runCatching { SystemTweaks.enforce(app) } }
            while (true) {
                delay(10 * 60_000L)
                runCatching { SystemTweaks.enforce(app) }
                runCatching { SystemTweaks.ensureHomeService(app) }
            }
        }
        // Al volver a activar Aura como launcher, se enciende tambien el servicio del boton Inicio
        scope.launch {
            config.state.map { it.launcherEnabled }.distinctUntilChanged().collect { if (it) runCatching { SystemTweaks.ensureHomeService(app) } }
        }
        startWeatherLoop()
        // Salvapantallas / apagado: se reaplica al arrancar y cada vez que cambian sus ajustes
        scope.launch {
            config.state.map { listOf(it.manageScreensaver, it.screensaverEnabled, it.screensaverMinutes, it.screenOffMinutes) }
                .distinctUntilChanged().collect { SystemTweaks.applyPower(app, config.state.value) }
        }
    }

    private fun startWeatherLoop() {
        // Refresca al cambiar la ubicacion y cada 20 minutos
        scope.launch {
            config.state.map { it.weather to it.tempUnit }.distinctUntilChanged().collect { refreshWeather() }
        }
        scope.launch {
            while (true) {
                delay(20 * 60_000L)
                refreshWeather()
            }
        }
    }

    suspend fun refreshWeather() {
        val cfg = config.state.value
        val loc = cfg.weather ?: run { weather.value = null; return }
        Weather.fetch(loc, cfg.tempUnit)?.let { weather.value = it }
    }
}
