package com.david.aura

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom

/** Fondos de video: YouTube, enlace directo (mp4, webm, m3u8…) o archivo subido desde la web. */
object Videos {
    /** NanoHTTPD mapea la subida en memoria: limite prudente para el Fire TV. */
    const val MAX_UPLOAD = 300L * 1024 * 1024
    private val EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov")
    private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun dir(ctx: Context) = File(ctx.filesDir, "videos").apply { mkdirs() }

    fun wallpaperId(v: VideoWp) = "video:${v.id}"

    fun byWallpaperId(id: String?): VideoWp? {
        if (id == null || !id.startsWith("video:")) return null
        val vid = id.removePrefix("video:")
        return Aura.config.state.value.videos.firstOrNull { it.id == vid }
    }

    private fun newId(): String = ByteArray(5).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Extrae el id de un enlace de YouTube (watch, youtu.be, shorts, embed, live) o null. */
    fun youtubeId(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path.orEmpty()
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            host == "youtube.com" || host == "music.youtube.com" || host == "youtube-nocookie.com" -> when {
                path == "/watch" -> uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/") || path.startsWith("/v/") ->
                    path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { YT_ID.matches(it) }
    }

    private fun add(v: VideoWp): VideoWp {
        Aura.config.update { it.copy(videos = it.videos + v) }
        return v
    }

    /** Añade un enlace (YouTube o video directo http/https). */
    fun addUrl(url: String, name: String?): VideoWp? {
        val u = url.trim()
        if (u.isEmpty() || u.length > 2000) return null
        val label = name?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
        youtubeId(u)?.let { return add(VideoWp(newId(), "youtube", it, label ?: "YouTube · $it")) }
        val uri = runCatching { URI(u) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        val fallback = uri.path.orEmpty().substringAfterLast('/').substringBeforeLast('.').ifBlank { uri.host }
        return add(VideoWp(newId(), "url", u, label ?: fallback.take(60)))
    }

    /** Guarda un video subido. Solo se acepta si Android reconoce una pista de video. */
    fun saveUpload(ctx: Context, tmp: File, originalName: String): VideoWp? {
        val ext = originalName.substringAfterLast('.', "mp4").lowercase()
        if (ext !in EXTENSIONS) return null
        val hasVideo = runCatching {
            MediaMetadataRetriever().run {
                try { setDataSource(tmp.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" } finally { release() }
            }
        }.getOrDefault(false)
        if (!hasVideo) return null
        val id = newId()
        val dest = File(dir(ctx), "$id.$ext")
        if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
        val label = originalName.substringBeforeLast('.').take(60).ifBlank { "Video" }
        return add(VideoWp(id, "file", dest.name, label))
    }

    fun delete(ctx: Context, wallpaperId: String): Boolean {
        val v = byWallpaperId(wallpaperId) ?: return false
        if (v.kind == "file") File(dir(ctx), v.src.substringAfterLast('/')).delete()
        posterFile(ctx, v).delete()
        Aura.config.update { c ->
            c.copy(
                videos = c.videos.filter { it.id != v.id },
                slots = c.slots.mapValues { (_, l) -> l - wallpaperId },
                appWallpapers = c.appWallpapers.filterValues { it != wallpaperId },
                fixedWallpaper = if (c.fixedWallpaper == wallpaperId) AuraConfig().fixedWallpaper else c.fixedWallpaper,
            )
        }
        return true
    }

    fun playUri(ctx: Context, v: VideoWp): Uri = when (v.kind) {
        "file" -> Uri.fromFile(File(dir(ctx), v.src.substringAfterLast('/')))
        else -> Uri.parse(v.src)
    }

    private fun posterFile(ctx: Context, v: VideoWp) = File(dir(ctx), "${v.id}.poster.jpg")

    /** Imagen fija del video (miniatura de YouTube o un fotograma). Se genera una vez y se guarda. */
    fun poster(ctx: Context, v: VideoWp): File {
        val f = posterFile(ctx, v)
        if (f.exists() && f.length() > 0) return f
        val bmp = runCatching {
            when (v.kind) {
                "youtube" -> listOf("maxresdefault", "hqdefault").firstNotNullOfOrNull { download("https://i.ytimg.com/vi/${v.src}/$it.jpg") }
                else -> MediaMetadataRetriever().run {
                    try {
                        if (v.kind == "file") setDataSource(File(dir(ctx), v.src).path) else setDataSource(v.src, HashMap())
                        getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally { release() }
                }
            }
        }.getOrNull() ?: placeholder()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return f
    }

    private fun download(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally { c.disconnect() }
    }

    /** Degradado oscuro calido cuando no hay fotograma (enlace caido, formato raro…). */
    private fun placeholder(): Bitmap {
        val b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 640f, 360f, 0xFF2C2B29.toInt(), 0xFF141312.toInt(), Shader.TileMode.CLAMP) }
        Canvas(b).drawRect(0f, 0f, 640f, 360f, p)
        return b
    }
}

/**
 * Video de fondo silenciado y en bucle. Solo aparece (con fundido) cuando ya se esta reproduciendo,
 * asi debajo se ve la imagen fija mientras carga. Se pausa cuando Aura pasa a segundo plano.
 */
@Composable
fun VideoWallpaper(v: VideoWp, modifier: Modifier = Modifier) {
    var playing by remember(v.id) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (playing) 1f else 0f, tween(900), label = "video")
    // El fundido se aplica a la vista Android (una capa de Compose rompe el video del WebView)
    if (v.kind == "youtube") YoutubeBackground(v.src, modifier, alpha) { playing = true }
    else ExoBackground(v, modifier, alpha) { playing = true }
}

@Composable
private fun ExoBackground(v: VideoWp, modifier: Modifier, alpha: Float, onPlaying: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(v.id) {
        ExoPlayer.Builder(ctx).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Videos.playUri(ctx, v)))
            prepare()
            playWhenReady = true
        }
    }
    var frame by remember { mutableStateOf<FrameLayout?>(null) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = onPlaying()
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("AuraVideo", "No se pudo reproducir ${v.name}: ${error.errorCodeName}", error)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val f = frame ?: return
                val t = texture ?: return
                coverScale(f, t, videoSize.width * videoSize.pixelWidthHeightRatio, videoSize.height.toFloat())
            }
        }
        player.addListener(listener)
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { c ->
            FrameLayout(c).apply {
                clipChildren = true
                val tv = TextureView(c)
                addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                player.setVideoTextureView(tv)
                frame = this; texture = tv
                // Al cambiar de tamaño se recalcula el recorte
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    val s = player.videoSize
                    if (s.width > 0) coverScale(this, tv, s.width * s.pixelWidthHeightRatio, s.height.toFloat())
                }
            }
        },
        update = { it.alpha = alpha },
        modifier = modifier,
    )
}

/** Escala el TextureView para cubrir toda la pantalla recortando los bordes (como "cover"). */
private fun coverScale(frame: FrameLayout, view: TextureView, videoW: Float, videoH: Float) {
    if (frame.width == 0 || frame.height == 0 || videoW <= 0f || videoH <= 0f) return
    val va = videoW / videoH
    val fa = frame.width.toFloat() / frame.height
    view.scaleX = if (va > fa) va / fa else 1f
    view.scaleY = if (va > fa) 1f else fa / va
}

private class YtBridge(private val onPlaying: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun playing() { main.postDelayed({ onPlaying() }, 1200) }
}

/** Reproductor oficial de YouTube (IFrame API) a pantalla completa, sin controles ni sonido. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubeBackground(videoId: String, modifier: Modifier, alpha: Float, onPlaying: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var web by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(web, lifecycle) {
        val w = web
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> { w?.onResume(); w?.evaluateJavascript("window.p&&p.playVideo&&p.playVideo()", null) }
                Lifecycle.Event.ON_STOP -> { w?.evaluateJavascript("window.p&&p.pauseVideo&&p.pauseVideo()", null); w?.onPause() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // El WebView se hace mas grande que la pantalla (16:9 + 12 %) y se recorta centrado: asi cubre todo
    // sin depender del tamaño que calcule la pagina
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
    val w = maxOf(maxWidth, maxHeight * 16f / 9f) * 1.12f
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(0)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                addJavascriptInterface(YtBridge(onPlaying), "Aura")
                loadDataWithBaseURL("https://aura.local/", youtubeHtml(videoId), "text/html", "utf-8", null)
                web = this
            }
        },
        update = { it.alpha = alpha },
        onRelease = { it.destroy() },
        modifier = Modifier.requiredSize(w, w * 9f / 16f),
    )
    }
}

private fun youtubeHtml(id: String) = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;overflow:hidden;background:transparent}
#w{position:fixed;top:0;left:0;right:0;bottom:0;pointer-events:none}
#p{width:100%;height:100%;display:block}</style></head><body><div id="w"><div id="p"></div></div>
<script>
var p;
function onYouTubeIframeAPIReady(){
  p=new YT.Player('p',{videoId:'$id',playerVars:{autoplay:1,mute:1,controls:0,loop:1,playlist:'$id',playsinline:1,rel:0,iv_load_policy:3,disablekb:1,fs:0,origin:'https://aura.local'},
    events:{onReady:function(e){e.target.mute();e.target.playVideo()},
      onStateChange:function(e){if(e.data===1)Aura.playing();if(e.data===0)e.target.playVideo()}}});
}
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>"""
