package com.david.aura

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object Screensaver {
    /** Cada cuanto cambia la foto del salvapantallas. */
    private const val PHOTO_MS = 60_000L

    /** Fondo fijo (puede ser un video) o las fotos de la franja horaria actual, rotando cada minuto. */
    fun pick(ctx: Context, cfg: AuraConfig, step: Long): String? {
        val available = Wallpapers.list(ctx)
        if (available.isEmpty()) return null
        if (cfg.wallpaperMode == "fixed" && cfg.fixedWallpaper in available) return cfg.fixedWallpaper
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val list = cfg.slots[Wallpapers.slotFor(cfg, hour)].orEmpty().filter { it in available }.ifEmpty { available }
        return list[(step % list.size).toInt()]
    }

    fun preview(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(ctx, ScreensaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    internal fun stepNow() = System.currentTimeMillis() / PHOTO_MS
}

/** Pantalla del salvapantallas: tus fondos con zoom lento, hora grande, fecha y clima. */
@Composable
fun ScreensaverContent() {
    val ctx = LocalContext.current
    val cfg by Aura.config.state.collectAsState()
    val weather by Aura.weather.collectAsState()
    val s = remember(cfg.language) { Strings(cfg.language) }
    val now by produceState(Calendar.getInstance()) {
        while (true) { value = Calendar.getInstance(); delay(5_000) }
    }
    val step = Screensaver.stepNow()
    val wpId = remember(cfg, step) { Screensaver.pick(ctx, cfg, step) }
    val images by produceState<WallpaperImages?>(null, wpId) {
        val id = wpId ?: return@produceState
        withContext(Dispatchers.IO) { Wallpapers.load(ctx, id) }?.let { value = it }
    }
    val video = remember(wpId, cfg.videos) { Videos.byWallpaperId(wpId) }

    // Zoom muy lento ("Ken Burns") para que la foto no este quieta
    val zoom by rememberInfiniteTransition(label = "kb").animateFloat(
        1f, 1.08f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom",
    )
    // El bloque de la hora se desplaza un poco cada minuto: evita marcas en pantallas OLED
    val minute = now.get(Calendar.MINUTE)
    val dx by animateDpAsState(((minute * 37) % 120).dp, tween(4000), label = "dx")
    val dy by animateDpAsState(-((minute * 23) % 60).dp, tween(4000), label = "dy")

    val font = remember(cfg.fontFamily) { fontFamilyFor(cfg.fontFamily) }
    val time = SimpleDateFormat(if (cfg.use24h) "H:mm" else "h:mm", Locale.US).format(now.time)
    val amPm = if (cfg.use24h) "" else SimpleDateFormat("a", Locale.US).format(now.time).uppercase()
    val date = SimpleDateFormat(s.datePattern, s.locale).format(now.time).replaceFirstChar { it.uppercase() }

    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = font)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Crossfade(images, animationSpec = tween(2500), label = "saver") { img ->
                if (img != null) {
                    Image(
                        img.full, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                    )
                }
            }
            if (video != null) VideoWallpaper(video, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 70.dp, bottom = 60.dp).offset(dx, dy)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(time, color = Color.White, fontSize = 112.sp, fontWeight = FontWeight.Thin, style = shadowed())
                    if (amPm.isNotEmpty()) {
                        Text(
                            amPm, color = Color.White.copy(alpha = 0.85f), fontSize = 30.sp, fontWeight = FontWeight.Light,
                            style = shadowed(), modifier = Modifier.padding(start = 10.dp, bottom = 22.dp),
                        )
                    }
                }
                Text(date, color = Color.White.copy(alpha = 0.92f), fontSize = 26.sp, fontWeight = FontWeight.Medium, style = shadowed())
                val w = weather
                if (w != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(w.iconRes), null, tint = Color.White, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("${w.temp}°", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, style = shadowed())
                        Spacer(Modifier.width(14.dp))
                        Text(
                            listOfNotNull(
                                w.description(cfg.language),
                                if (w.max != null && w.min != null) "${w.max}° / ${w.min}°" else null,
                                w.place.ifEmpty { null },
                            ).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, style = shadowed(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Salvapantallas del sistema (Android "Daydream"). Fire OS lo inicia tras los minutos de inactividad
 * configurados; cualquier tecla del mando lo cierra. La pantalla solo se apaga si se configura en Ajustes.
 */
class AuraDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        Aura.init(this)
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AuraDream)
            setViewTreeSavedStateRegistryOwner(this@AuraDream)
            setContent { ScreensaverContent() }
        }
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        registry.currentState = Lifecycle.State.RESUMED
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // Seguridad extra: si hay audio o video sonando (una app que no avisa de que esta reproduciendo),
        // el salvapantallas se quita solo y no interrumpe lo que estas viendo
        val audio = getSystemService(android.media.AudioManager::class.java)
        if (audio?.isMusicActive == true) {
            if (android.os.Build.VERSION.SDK_INT >= 29) wakeUp() else finish()
            return
        }
        registry.currentState = Lifecycle.State.RESUMED
        Aura.scope.launch { Aura.refreshWeather() }
    }

    override fun onDreamingStopped() {
        registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
}

/** Vista previa (y salvapantallas manual desde Ajustes o la web). Cualquier tecla lo cierra. */
class ScreensaverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Aura.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { ScreensaverContent() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }
}
