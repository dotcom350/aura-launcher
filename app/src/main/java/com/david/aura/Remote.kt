package com.david.aura

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Mando a distancia sin ADB ni shell (Fire OS bloquea el ADB desde la propia TV):
 *  - Navegacion, OK, Atras, Inicio, texto, apagar y captura -> servicio de accesibilidad de Aura.
 *  - Multimedia y volumen -> AudioManager.
 *  - Encender -> wake lock que enciende la pantalla (y la tele por HDMI-CEC).
 * Dentro de Aura las teclas se inyectan en su propia ventana.
 */
object Remote {
    val KEYS = setOf(
        "up", "down", "left", "right", "ok", "back", "home",
        "playpause", "play", "pause", "rewind", "forward", "next", "previous",
        "volup", "voldown", "mute",
    )

    @Volatile var auraInFront = false

    private val acc: HomeWatcherService? get() = HomeWatcherService.instance

    fun key(ctx: Context, name: String): Boolean = when (name) {
        "up", "down", "left", "right", "ok" ->
            if (auraInFront) injectIntoAura(name) else navigate(name)
        "back" -> acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
        "home" -> {
            val a = acc
            when {
                a == null -> false
                runCatching { Aura.config.state.value.launcherEnabled }.getOrDefault(true) -> { a.goHome(); true }
                else -> a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }
        "playpause" -> media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        "play" -> media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
        "pause" -> media(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
        "rewind" -> media(ctx, KeyEvent.KEYCODE_MEDIA_REWIND)
        "forward" -> media(ctx, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        "next" -> media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
        "previous" -> media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        "volup" -> volume(ctx, AudioManager.ADJUST_RAISE)
        "voldown" -> volume(ctx, AudioManager.ADJUST_LOWER)
        "mute" -> volume(ctx, AudioManager.ADJUST_TOGGLE_MUTE)
        else -> false
    }

    // ---------------- navegacion
    private fun keyCode(name: String) = when (name) {
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> KeyEvent.KEYCODE_DPAD_CENTER
    }

    /** Instrumentation solo puede inyectar en ventanas de la propia app: perfecto para Aura. */
    private fun injectIntoAura(name: String): Boolean = runCatching {
        Instrumentation().sendKeyDownUpSync(keyCode(name)); true
    }.getOrDefault(false)

    private fun navigate(name: String): Boolean {
        val service = acc ?: return false
        val root = service.rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (name == "ok") {
            var node = focused
            while (node != null && !node.isClickable) node = node.parent
            return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }
        if (focused == null) return firstFocusable(root)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
        val direction = when (name) {
            "up" -> View.FOCUS_UP
            "down" -> View.FOCUS_DOWN
            "left" -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        val next = focused.focusSearch(direction) ?: return false
        return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun firstFocusable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocusable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { c -> firstFocusable(c)?.let { return it } }
        return null
    }

    // ---------------- texto
    fun text(value: String): Boolean {
        val root = acc?.rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { c -> findEditable(c)?.let { return it } }
        return null
    }

    // ---------------- multimedia y volumen
    private fun media(ctx: Context, code: Int): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return true
    }

    private fun volume(ctx: Context, direction: Int): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustSuggestedStreamVolume(direction, AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI)
        return true
    }

    // ---------------- encendido
    fun wake(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val lock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "aura:wake",
        )
        lock.acquire(5_000)
        return true
    }

    fun sleep(): Boolean =
        if (Build.VERSION.SDK_INT >= 28) acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) ?: false else false

    fun isScreenOn(ctx: Context): Boolean = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    // ---------------- captura
    fun screenshot(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        val service = acc ?: return null
        var png: ByteArray? = null
        val latch = CountDownLatch(1)
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    png = soft?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        return png
    }
}
