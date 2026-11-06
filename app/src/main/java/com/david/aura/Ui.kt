package com.david.aura

import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal val TextShadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 12f)

/** Estilo actual (con el tipo de letra elegido) + sombra para leer sobre el fondo. */
@Composable
internal fun shadowed(): TextStyle = LocalTextStyle.current.copy(shadow = TextShadow)

internal fun fontFamilyFor(name: String): FontFamily = when (name) {
    "condensed" -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

internal fun clockSp(size: String): TextUnit = when (size) { "s" -> 44.sp; "m" -> 54.sp; "xl" -> 84.sp; else -> 66.sp }
internal fun dateSp(size: String): TextUnit = when (size) { "s" -> 14.sp; "l" -> 21.sp; "xl" -> 26.sp; else -> 17.sp }
internal fun tileDims(size: String): Pair<Dp, Dp> = when (size) { "s" -> 132.dp to 74.dp; "l" -> 188.dp to 106.dp; else -> 156.dp to 88.dp }

/** Textos de la TV en espanol / ingles. */
internal class Strings(val lang: String) {
    val en = lang == "en"
    val locale: Locale = if (en) Locale.US else Locale("es", "ES")
    fun greeting(hour: Int) = when (hour) {
        in 5..11 -> if (en) "Good morning" else "Buenos días"
        in 12..19 -> if (en) "Good afternoon" else "Buenas tardes"
        else -> if (en) "Good evening" else "Buenas noches"
    }
    val datePattern = if (en) "EEEE, MMMM d" else "EEEE, d 'de' MMMM"
    val allApps = if (en) "All apps" else "Todas las apps"
    val apps = "apps"
    val remoteTitle = if (en) "Scan with your phone's camera to control this TV" else "Escanea con la cámara del móvil para controlar la TV"
    val orOpen = if (en) "Or open in a browser (same Wi-Fi):" else "O abre en el navegador (misma Wi-Fi):"
    val token = if (en) "Access token" else "Token de acceso"
    val close = if (en) "Press Menu or Back to close" else "Pulsa Menú o Atrás para cerrar"
    val moveHint = if (en) "◀ ▶ to move · OK to finish" else "◀ ▶ para mover · OK para terminar"
    val longPressHint = if (en) "Hold OK to add or remove from the main row" else "Mantén OK para añadir o quitar de la fila principal"
    fun added(label: String) = if (en) "$label added to the main row" else "$label añadida a la fila principal"
    fun removed(label: String) = if (en) "$label removed from the main row" else "$label quitada de la fila principal"

    // Ajustes
    val settings = if (en) "Settings" else "Ajustes"
    val connectPhone = if (en) "Connect your phone" else "Conecta tu móvil"
    // Actualizaciones
    val updAvailable = if (en) "New version available" else "Nueva versión disponible"
    val updDownloading = if (en) "Downloading update…" else "Descargando la actualización…"
    val updInstalling = if (en) "Installing…" else "Instalando…"
    val updFailed = if (en) "Could not update" else "No se pudo actualizar"
    val updUpToDate = if (en) "You're up to date" else "Ya tienes la última versión"
    val updNow = if (en) "Update now" else "Actualizar ahora"
    val updLater = if (en) "Later" else "Más tarde"
    val updClose = if (en) "Close" else "Cerrar"
    val updInstallHint = if (en) "Aura will restart by itself in a few seconds." else "Aura se reiniciará sola en unos segundos."
    val updTitle = if (en) "Updates" else "Actualizaciones"
    fun updSub(st: Updater.State) = when (st.phase) {
        "available" -> if (en) "Version ${st.latest} is available · press OK" else "Disponible la versión ${st.latest} · pulsa OK"
        "checking" -> if (en) "Checking…" else "Buscando…"
        "downloading" -> (if (en) "Downloading " else "Descargando ") + "${st.progress.coerceAtLeast(0)} %"
        "installing" -> updInstalling
        "failed" -> st.message.ifEmpty { updFailed }
        "uptodate" -> updUpToDate
        else -> if (en) "Press OK to check for a new version" else "Pulsa OK para buscar una versión nueva"
    }
    val pairTitle = if (en) "Connect a new device" else "Conectar un dispositivo"
    val pairFrom = if (en) "Request from" else "Lo pide"
    val pairPhone = if (en) "On a phone: scan the QR code." else "En el móvil: escanea el código QR."
    val pairPc = if (en) "On a computer: check the code matches and press Allow." else "En el ordenador: comprueba que el código coincide y pulsa Permitir."
    val pairAllow = if (en) "Allow" else "Permitir"
    val pairDeny = if (en) "Deny" else "Rechazar"
    val launcher = if (en) "Use Aura as home screen" else "Usar Aura como inicio"
    val wallpaper = if (en) "Wallpaper" else "Fondo de pantalla"
    val changeEvery = if (en) "Change wallpaper every" else "Cambiar fondo cada"
    val language = if (en) "Language" else "Idioma"
    val temperature = if (en) "Temperature" else "Temperatura"
    val clock = if (en) "Clock" else "Reloj"
    val font = if (en) "Font" else "Tipo de letra"
    val clockSize = if (en) "Clock size" else "Tamaño del reloj"
    val dateSize = if (en) "Date size" else "Tamaño de la fecha"
    val tileSize = if (en) "App size" else "Tamaño de las apps"
    val showGreeting = if (en) "Show greeting" else "Mostrar saludo"
    val showDate = if (en) "Show date" else "Mostrar fecha"
    val showWeather = if (en) "Show weather" else "Mostrar clima"
    val secondRow = if (en) "Second row of apps" else "Segunda fila de apps"
    val fireSettings = if (en) "Fire TV settings" else "Ajustes del Fire TV"
    val fireSettingsSub = if (en) "Amazon system settings: network, display, apps…" else "Ajustes del sistema de Amazon: red, pantalla, apps…"
    val organize = if (en) "Organize apps" else "Organizar apps"
    val organizeSub = if (en) "Hold OK on an app in the main row to move it. In All apps, hold OK to add or remove it."
        else "Mantén OK sobre una app de la fila principal para moverla. En Todas las apps, mantén OK para añadirla o quitarla."
    val weatherCity = if (en) "Weather city" else "Ciudad del clima"
    fun weatherCitySub(city: String?) = (city ?: if (en) "Not set" else "Sin configurar") +
        (if (en) " · change it from your phone (scan the QR)" else " · cámbiala desde el móvil (escanea el QR)")
    // Privacidad y sistema
    val privacyHeader = if (en) "Privacy & system" else "Privacidad y sistema"
    val privacySub = if (en) "What the welcome screen turned off. Press OK or ◀ ▶ to change it." else "Lo que desactivó la bienvenida. Pulsa OK o ◀ ▶ para cambiarlo."
    val privacyMissing = if (en) "Missing the system permission: run deploy.ps1 once from the PC." else "Falta el permiso del sistema: ejecuta deploy.ps1 una vez desde el PC."
    val enabled = if (en) "On" else "Activado"
    val disabled = if (en) "Off" else "Desactivado"
    val manual = if (en) "Manual" else "Manual"
    val redoWelcome = if (en) "Show the welcome screen again" else "Repetir la bienvenida"
    val redoWelcomeSub = if (en) "Turn everything off again with the animation" else "Vuelve a desactivarlo todo con la animación"
    // Codigo de seguridad
    val pinTitle = if (en) "Security code" else "Código de seguridad"
    val pinOnSub = if (en) "The web and the MCP ask for it. Press OK twice to remove it (if you forgot it)." else "La web y el MCP lo piden. Pulsa OK dos veces para quitarlo (por si lo olvidaste)."
    val pinOffSub = if (en) "Anyone who scans the QR can control the TV. Set a code from the web: Settings › Security." else "Cualquiera que escanee el QR puede controlar la TV. Ponlo desde la web: Ajustes › Seguridad."
    val pinConfirm = if (en) "OK again to remove" else "OK otra vez para quitarlo"
    // Salvapantallas
    val saverHeader = if (en) "Screensaver" else "Salvapantallas"
    val saverHeaderSub = if (en) "Time, date and weather over your wallpapers. The screen stays on unless you choose otherwise." else "Hora, fecha y clima sobre tus fondos. La pantalla no se apaga salvo que lo elijas aquí."
    val saverTitle = if (en) "Aura screensaver" else "Salvapantallas de Aura"
    val saverSub = if (en) "Replaces Amazon's screensaver" else "Sustituye al de Amazon"
    val saverInactive = if (en) "Inactive: turn off Amazon's screensaver below" else "Inactivo: desactiva el salvapantallas de Amazon más abajo"
    val saverStart = if (en) "Start after" else "Empieza tras"
    val screenOff = if (en) "Turn off the screen" else "Apagar la pantalla"
    val screenOffSub = if (en) "Time with the screensaver before the TV goes to sleep" else "Tiempo con el salvapantallas antes de que la TV se duerma"
    val saverPreview = if (en) "Preview screensaver" else "Ver el salvapantallas"
    fun offLabel(min: Int) = when {
        min == 0 -> if (en) "Never" else "Nunca"
        min % 60 == 0 -> "${min / 60} h"
        else -> "$min min"
    }
    val on = if (en) "On" else "Sí"
    val off = if (en) "Off" else "No"
    val modeApp = if (en) "Follows the focused app" else "Cambia con la app"
    val modeTime = if (en) "Time of day" else "Según la hora"
    val modeRotate = if (en) "Rotate all" else "Rotar todos"
    val modeFixed = if (en) "Fixed" else "Fijo"
    val sizeNames = mapOf("s" to (if (en) "Small" else "Pequeño"), "m" to (if (en) "Medium" else "Mediano"),
        "l" to (if (en) "Large" else "Grande"), "xl" to (if (en) "Extra large" else "Muy grande"))
    val fontNames = mapOf("sans" to "Sans", "condensed" to (if (en) "Condensed" else "Condensada"),
        "serif" to "Serif", "mono" to "Mono")
    fun mode(cfg: AuraConfig) = when (cfg.wallpaperMode) {
        "fixed" -> modeFixed
        "rotate" -> "$modeRotate · ${cfg.rotateMinutes} min"
        "time" -> modeTime
        else -> modeApp
    }
}

/**
 * Efecto cristal: dibuja detras la version desenfocada del fondo, alineada con la pantalla.
 * [outline] = borde completo; si es false solo se dibujan lineas arriba y abajo (bandas a todo el ancho).
 */
fun Modifier.glass(
    blur: ImageBitmap?,
    corner: Dp,
    tint: Color = Color.White.copy(alpha = 0.10f),
    outline: Boolean = true,
): Modifier = composed {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screen = with(density) { IntSize(configuration.screenWidthDp.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx()) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawWithCache {
            val r = corner.toPx()
            val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r))) }
            val line = Color.White.copy(alpha = 0.22f)
            onDrawBehind {
                clipPath(path) {
                    if (blur != null) {
                        translate(-origin.x, -origin.y) {
                            drawImage(
                                image = blur, srcOffset = IntOffset.Zero, srcSize = IntSize(blur.width, blur.height),
                                dstOffset = IntOffset.Zero, dstSize = screen, filterQuality = FilterQuality.Low,
                            )
                        }
                    } else {
                        drawRect(Color.Black.copy(alpha = 0.35f))
                    }
                    drawRect(tint)
                    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.09f), 0.5f to Color.Transparent))
                }
                if (outline) {
                    drawPath(path, line, style = Stroke(width = 1.dp.toPx()))
                } else {
                    drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
            }
        }
}

/** Aviso breve en cristal. */
@Composable
internal fun GlassToast(text: String, blur: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(modifier.glass(blur, 18.dp, Color.Black.copy(alpha = 0.35f)).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}
