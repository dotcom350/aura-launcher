package com.david.aura

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

private class SettingItem(
    val icon: Int,
    val title: String,
    val value: String? = null,
    val sub: String? = null,
    /** -1 / +1 con las flechas; los interruptores ignoran la direccion. */
    val onStep: ((Int) -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    /** Titulo de seccion (no se puede enfocar). */
    val header: Boolean = false,
)

private fun <T> cycle(list: List<T>, current: T, delta: Int): T {
    val i = list.indexOf(current).coerceAtLeast(0)
    return list[(i + delta + list.size) % list.size]
}

/** Todos los ajustes de Aura, manejables con el mando. */
@Composable
internal fun TvSettings(cfg: AuraConfig, s: Strings, blur: ImageBitmap?, weather: Weather?, onClose: () -> Unit) {
    BackHandler(enabled = true) { onClose() }
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    fun set(transform: (AuraConfig) -> AuraConfig) = Aura.config.update(transform)
    val upd by Updater.state.collectAsState()
    fun onOff(v: Boolean) = if (v) s.on else s.off

    val modes = listOf("app", "time", "rotate", "fixed")
    val modeNames = mapOf("app" to s.modeApp, "time" to s.modeTime, "rotate" to s.modeRotate, "fixed" to s.modeFixed)
    val minutes = listOf(5, 10, 15, 20, 30, 60, 120)
    val sizes = listOf("s", "m", "l", "xl")
    val tiles = listOf("s", "m", "l")
    val fonts = listOf("sans", "condensed", "serif", "mono")

    // Estado real de los ajustes del sistema (se vuelve a leer al cambiar uno)
    var tweakTick by remember { mutableStateOf(0) }
    val blocked = remember(tweakTick, cfg.manageScreensaver) { SystemTweaks.ALL.associate { it.id to SystemTweaks.isBlocked(ctx, it) } }
    val missing = remember(tweakTick) { SystemTweaks.missingPermissions(ctx) }
    fun tweakValue(t: SystemTweaks.Tweak): String = when {
        t.manual -> s.manual
        blocked[t.id] == null -> "—"
        (blocked[t.id] == true) != t.inverted -> s.disabled
        else -> s.enabled
    }
    val tweakItems = SystemTweaks.ALL.map { t ->
        if (t.manual) {
            SettingItem(t.icon, t.title(cfg.language), s.manual, sub = t.sub(cfg.language), onClick = { Apps.openFireSettings(ctx, "all") })
        } else {
            SettingItem(t.icon, t.title(cfg.language), tweakValue(t), sub = t.sub(cfg.language), onStep = {
                SystemTweaks.apply(ctx, t, blocked[t.id] != true)
                tweakTick++
            })
        }
    }
    // Codigo de seguridad: se pone desde la web; en la TV se puede quitar (por si se olvida) pulsando OK dos veces
    var confirmPin by remember { mutableStateOf(false) }
    val pinOn = remember(cfg.pinHash) { cfg.pinHash.isNotEmpty() }
    val securityItem = SettingItem(
        R.drawable.ri_shield_check, s.pinTitle,
        when { confirmPin -> s.pinConfirm; pinOn -> s.enabled; else -> s.disabled },
        sub = if (pinOn) s.pinOnSub else s.pinOffSub,
        onClick = {
            if (pinOn) {
                if (confirmPin) { Security.clearPin(); confirmPin = false } else confirmPin = true
            }
        },
    )
    val saverItems = listOf(
        SettingItem(R.drawable.ri_moon_sleep, s.saverTitle, onOff(cfg.screensaverEnabled),
            sub = if (cfg.manageScreensaver) s.saverSub else s.saverInactive,
            onStep = { set { it.copy(screensaverEnabled = !it.screensaverEnabled) } }),
        SettingItem(R.drawable.ri_clock, s.saverStart, "${cfg.screensaverMinutes} min", onStep = { d ->
            set { it.copy(screensaverMinutes = cycle(AuraConfig.SAVER_MINUTES, it.screensaverMinutes, d)) }
        }),
        SettingItem(R.drawable.ri_power, s.screenOff, s.offLabel(cfg.screenOffMinutes), sub = s.screenOffSub, onStep = { d ->
            set { it.copy(screenOffMinutes = cycle(AuraConfig.OFF_MINUTES, it.screenOffMinutes, d)) }
        }),
        SettingItem(R.drawable.ri_eye, s.saverPreview, onClick = { Screensaver.preview(ctx) }),
    )

    val items = listOf(
        SettingItem(R.drawable.ri_home, s.launcher, onOff(cfg.launcherEnabled), onStep = { set { it.copy(launcherEnabled = !it.launcherEnabled) } }),
        SettingItem(R.drawable.ri_wallpaper, s.wallpaper, modeNames[cfg.wallpaperMode], onStep = { d -> set { it.copy(wallpaperMode = cycle(modes, it.wallpaperMode, d)) } }),
        SettingItem(R.drawable.ri_refresh, s.changeEvery, "${cfg.rotateMinutes} min", onStep = { d ->
            set { c ->
                val nearest = minutes.minByOrNull { abs(it - c.rotateMinutes) } ?: 20
                c.copy(rotateMinutes = cycle(minutes, nearest, d))
            }
        }),
        SettingItem(R.drawable.ri_globe, s.language, if (cfg.language == "en") "English" else "Español", onStep = {
            set { it.copy(language = if (it.language == "en") "es" else "en") }
        }),
        SettingItem(R.drawable.ri_thermometer, s.temperature, "°${cfg.tempUnit}", onStep = {
            set { it.copy(tempUnit = if (it.tempUnit == "F") "C" else "F") }
        }),
        SettingItem(R.drawable.ri_clock, s.clock, if (cfg.use24h) "24 h" else "12 h · AM/PM", onStep = { set { it.copy(use24h = !it.use24h) } }),
        SettingItem(R.drawable.ri_text, s.font, s.fontNames[cfg.fontFamily], onStep = { d -> set { it.copy(fontFamily = cycle(fonts, it.fontFamily, d)) } }),
        SettingItem(R.drawable.ri_clock, s.clockSize, s.sizeNames[cfg.clockSize], onStep = { d -> set { it.copy(clockSize = cycle(sizes, it.clockSize, d)) } }),
        SettingItem(R.drawable.ri_text, s.dateSize, s.sizeNames[cfg.dateSize], onStep = { d -> set { it.copy(dateSize = cycle(sizes, it.dateSize, d)) } }),
        SettingItem(R.drawable.ri_layout, s.tileSize, s.sizeNames[cfg.tileSize], onStep = { d -> set { it.copy(tileSize = cycle(tiles, it.tileSize, d)) } }),
        SettingItem(R.drawable.ri_eye, s.showGreeting, onOff(cfg.showGreeting), onStep = { set { it.copy(showGreeting = !it.showGreeting) } }),
        SettingItem(R.drawable.ri_eye, s.showDate, onOff(cfg.showDate), onStep = { set { it.copy(showDate = !it.showDate) } }),
        SettingItem(R.drawable.ri_eye, s.showWeather, onOff(cfg.showWeather), onStep = { set { it.copy(showWeather = !it.showWeather) } }),
        SettingItem(R.drawable.ri_grid, s.secondRow, onOff(cfg.showAllAppsRow), onStep = { set { it.copy(showAllAppsRow = !it.showAllAppsRow) } }),
        SettingItem(R.drawable.ri_cloud_sun, s.weatherCity, sub = s.weatherCitySub(cfg.weather?.name)),
        SettingItem(R.drawable.ri_star, s.organize, sub = s.organizeSub),
        SettingItem(R.drawable.ri_moon_sleep, s.saverHeader, sub = s.saverHeaderSub, header = true),
    ) + saverItems + listOf(
        SettingItem(R.drawable.ri_shield_check, s.privacyHeader, sub = if (missing) s.privacyMissing else s.privacySub, header = true),
        securityItem,
    ) + tweakItems + listOf(
        SettingItem(R.drawable.ri_refresh, s.updTitle, "v${BuildConfig.VERSION_NAME}", sub = s.updSub(upd), onClick = {
            if (upd.phase == "available") Updater.state.value = upd.copy(prompt = true) else Updater.checkNow()
        }),
        SettingItem(R.drawable.ri_sparkles, s.redoWelcome, sub = s.redoWelcomeSub, onClick = {
            set { it.copy(onboardingDone = false) }
            onClose()
        }),
        SettingItem(R.drawable.ri_tv, s.fireSettings, sub = s.fireSettingsSub, header = true),
    ) + FireSettings.PAGES.map { p ->
        SettingItem(p.icon, p.title(cfg.language), sub = p.sub(cfg.language), onClick = { Apps.openFireSettings(ctx, p.id) })
    }

    val url = remember(cfg.token) { Qr.connectUrl(cfg) }
    val qr = remember(url) { Qr.bitmap(url) }

    Box(Modifier.fillMaxSize()) {
        if (blur != null) Image(blur, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 44.dp)) {
            // Columna izquierda: titulo + QR
            Column(Modifier.width(330.dp).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ri_settings), null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(s.settings, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text("v${BuildConfig.VERSION_NAME}", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
                }
                Spacer(Modifier.height(28.dp))
                Column(Modifier.glass(blur, 26.dp, Color.Black.copy(alpha = 0.3f)).padding(22.dp)) {
                    Text(s.connectPhone, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(s.remoteTitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.size(200.dp).background(Color.White, RoundedCornerShape(18.dp)).padding(10.dp)) {
                        Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "http://${WebService.localIp() ?: "IP"}:${Aura.WEB_PORT}",
                        color = Color(0xFFF2D3A0), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                }
                if (weather != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(weather.iconRes), null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${weather.temp}°${weather.unit} · ${weather.place}", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.width(40.dp))
            // Lista de ajustes
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    SettingRow(item, if (index == 0) first else null)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(item: SettingItem, focusRequester: FocusRequester?) {
    if (item.header) {
        Column(Modifier.fillMaxWidth().padding(start = 6.dp, top = 22.dp, bottom = 4.dp)) {
            Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (item.sub != null) Text(item.sub, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        return
    }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                when {
                    e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown && item.onStep != null -> { item.onStep.invoke(-1); true }
                    e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown && item.onStep != null -> { item.onStep.invoke(1); true }
                    (e.key == Key.DirectionCenter || e.key == Key.Enter) && e.type == KeyEventType.KeyUp -> {
                        item.onClick?.invoke() ?: item.onStep?.invoke(1)
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f), shape)
            .border(1.dp, if (focused) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(item.icon), null, tint = if (focused) Color.White else Color.White.copy(alpha = 0.75f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (item.sub != null) Text(item.sub, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        if (item.value != null) {
            if (item.onStep != null && focused) {
                Icon(painterResource(R.drawable.ri_chevron_left), null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(item.value, color = if (focused) Color.White else Color.White.copy(alpha = 0.8f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (item.onStep != null && focused) {
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(R.drawable.ri_chevron_right), null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
            }
        }
    }
}
