package com.david.aura

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones desde las Releases de GitHub.
 *
 * Cada 6 horas (y al pulsar "Buscar actualizaciones") consulta la ultima Release. Si su version es mayor que
 * la instalada, la TV muestra un aviso; al aceptarlo se descarga Aura.apk con su progreso, se comprueba que es
 * Aura y que es mas nueva, y se instala con [Installer] (Fire OS pide confirmar; Aura la acepta sola).
 * Tras instalarse, Aura se reinicia sola (MY_PACKAGE_REPLACED).
 */
object Updater {
    const val REPO = "dotcom350/aura-launcher"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val EVERY_MS = 6 * 3600_000L

    /** phase: idle | checking | uptodate | available | downloading | installing | failed */
    @Serializable
    data class State(
        val phase: String = "idle",
        val current: String = BuildConfig.VERSION_NAME,
        val latest: String? = null,
        val notes: List<String> = emptyList(),
        val sizeMb: Double = 0.0,
        val progress: Int = -1,
        val downloadedMb: Double = 0.0,
        val message: String = "",
        /** Mostrar el aviso en la TV (se oculta con "Mas tarde"). */
        val prompt: Boolean = false,
        val checkedAt: Long = 0,
    )

    val state = MutableStateFlow(State())
    private var apkUrl: String? = null
    private var dismissed: String? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        Aura.scope.launch {
            delay(30_000) // deja arrancar la TV antes de la primera consulta
            while (true) {
                check(manual = false)
                delay(EVERY_MS)
            }
        }
        // Al volver a la TV se muestra otra vez el aviso si sigue habiendo version nueva
        Aura.scope.launch {
            Aura.resumeTick.collect {
                val s = state.value
                if (s.phase == "available" && !s.prompt && s.latest != dismissed) state.value = s.copy(prompt = true)
            }
        }
    }

    private fun busy() = state.value.phase in setOf("checking", "downloading", "installing")

    /** Compara versiones tipo 1.8 / 1.10.2 numero a numero. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Consulta la ultima Release. [manual] = el usuario lo pidio (siempre muestra el resultado). */
    suspend fun check(manual: Boolean) {
        if (busy()) return
        state.value = state.value.copy(phase = "checking", message = "")
        val result = runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Aura-FireTV")
            }
            val body = try {
                if (conn.responseCode != 200) error("GitHub respondio ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally { conn.disconnect() }
            AuraJson.parseToJsonElement(body).jsonObject
        }
        val rel = result.getOrElse {
            state.value = state.value.copy(phase = "failed", message = it.message ?: "Sin conexion", prompt = manual, checkedAt = now())
            return
        }
        val tag = rel["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty().removePrefix("v")
        val asset = rel["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "Aura.apk" }
        val notes = rel["body"]?.jsonPrimitive?.contentOrNull.orEmpty().lines()
            .filter { it.trimStart().startsWith("- ") }.map { it.trimStart().removePrefix("- ").replace("**", "").replace("`", "") }
        if (tag.isEmpty() || asset == null || !newer(tag, BuildConfig.VERSION_NAME)) {
            state.value = State(phase = "uptodate", latest = tag.ifEmpty { null }, prompt = manual, checkedAt = now())
            return
        }
        apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
        val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
        state.value = State(
            phase = "available", latest = tag, notes = notes.take(6), sizeMb = mb(size),
            prompt = manual || tag != dismissed, checkedAt = now(),
        )
    }

    fun checkNow() { Aura.scope.launch { check(manual = true) } }

    /** "Mas tarde": no vuelve a avisar de esta version hasta que se vuelva a Aura o se pida a mano. */
    fun later() {
        dismissed = state.value.latest
        state.value = state.value.copy(prompt = false)
    }

    fun closeMessage() { state.value = state.value.copy(prompt = false) }

    /** Descarga e instala la version nueva. */
    fun install(ctx: Context): Boolean {
        val url = apkUrl ?: return false
        if (busy() || state.value.phase != "available") return false
        val latest = state.value.latest
        state.value = state.value.copy(phase = "downloading", progress = 0, downloadedMb = 0.0, prompt = true, message = "")
        Aura.scope.launch {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val apk = File(dir, "Aura-$latest.apk")
            val ok = runCatching { download(url, apk) }.getOrElse { e ->
                fail("Descarga fallida: ${e.message ?: e.javaClass.simpleName}"); false
            }
            if (!ok) { apk.delete(); return@launch }
            // Debe ser Aura y mas nueva que la instalada (la firma la comprueba Android al instalar)
            val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode ?: 0 else info?.versionCode?.toLong() ?: 0
            if (info?.packageName != ctx.packageName || code <= BuildConfig.VERSION_CODE) {
                apk.delete(); fail("El archivo descargado no es una version nueva de Aura"); return@launch
            }
            state.value = state.value.copy(phase = "installing", progress = 100)
            if (!Installer.installFile(ctx, apk)) { fail("Ya hay otra instalacion en curso"); return@launch }
            // Si la instalacion falla o se cancela, Installer lo indica; si va bien, Aura se reinicia sola
            val end = withTimeoutOrNull(10 * 60_000L) { Installer.status.first { it.phase == "failed" || it.phase == "done" } }
            if (state.value.phase == "installing" && end?.phase != "done") fail(end?.message?.ifEmpty { null } ?: "No se pudo instalar")
        }
        return true
    }

    private fun fail(msg: String) {
        state.value = state.value.copy(phase = "failed", message = msg, prompt = true)
    }

    private fun download(url: String, dest: File): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Aura-FireTV")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (read > Installer.MAX_APK) error("archivo demasiado grande")
                        val pct = if (total > 0) (read * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            state.value = state.value.copy(progress = pct, downloadedMb = mb(read), sizeMb = if (total > 0) mb(total) else state.value.sizeMb)
                        }
                    }
                }
            }
        } finally { conn.disconnect() }
        return true
    }

    private fun mb(bytes: Long) = Math.round(bytes / 1024.0 / 1024.0 * 10) / 10.0
    private fun now() = System.currentTimeMillis()
}

/** Aviso en la TV: version nueva, progreso de descarga, instalando o error. Las teclas las maneja HomeScreen. */
@Composable
internal fun UpdatePanel(st: Updater.State, updateSelected: Boolean, s: Strings, blur: ImageBitmap?) {
    val sand = Color(0xFFF0C987)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(620.dp).glass(blur, 30.dp, Color.Black.copy(alpha = 0.5f)).padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ri_sparkles), null, tint = sand, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    when (st.phase) {
                        "downloading" -> s.updDownloading
                        "installing" -> s.updInstalling
                        "failed" -> s.updFailed
                        "uptodate" -> s.updUpToDate
                        else -> s.updAvailable
                    },
                    color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold,
                )
            }
            Text(
                if (st.phase == "uptodate") "Aura ${st.current}" else "Aura ${st.current}  →  ${st.latest ?: "?"}",
                color = sand, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            when (st.phase) {
                "available" -> {
                    st.notes.take(4).forEach {
                        Text("•  $it", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (st.sizeMb > 0) Text("${st.sizeMb} MB", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Pill(s.updNow, selected = updateSelected) {}
                        Pill(s.updLater, selected = !updateSelected) {}
                    }
                }
                "downloading", "installing" -> {
                    val target = if (st.phase == "installing") 1f else (st.progress.coerceAtLeast(0) / 100f)
                    val p by animateFloatAsState(target, tween(400), label = "upd")
                    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(99.dp)).background(Color.White.copy(alpha = 0.12f))) {
                        Box(
                            Modifier.fillMaxWidth(p).height(12.dp).clip(RoundedCornerShape(99.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFFE3A458), sand)))
                        )
                    }
                    Text(
                        if (st.phase == "installing") s.updInstallHint
                        else if (st.progress >= 0) "${st.progress} %  ·  ${st.downloadedMb} / ${st.sizeMb} MB"
                        else "${st.downloadedMb} MB",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp,
                    )
                }
                else -> {
                    if (st.message.isNotEmpty()) Text(st.message, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Pill(s.updClose, selected = true) {}
                }
            }
        }
    }
}
