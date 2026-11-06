package com.david.aura

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Entrar en la web sin escribir el token.
 *
 * La web (sin sesion) pide emparejarse: la TV muestra el QR de conexion y un codigo de 4 cifras.
 *  - Con el movil: se escanea el QR y entra directamente.
 *  - En el ordenador: se comprueba que el codigo coincide y se pulsa "Permitir" con el mando.
 * Solo quien tiene el mando delante puede aprobarlo. Despues, si hay codigo de seguridad, la web lo pide.
 */
object Pairing {
    const val TTL = 120_000L

    class Req(val id: String, val code: String, val device: String, val created: Long) {
        @Volatile var state: String = "pending" // pending | approved | denied
    }

    /** Peticion que se esta mostrando en la TV. */
    val shown = MutableStateFlow<Req?>(null)
    private val reqs = ConcurrentHashMap<String, Req>()
    private var lastStart = 0L
    private val rnd = SecureRandom()

    @Synchronized
    fun start(device: String): Req? {
        val now = System.currentTimeMillis()
        if (now - lastStart < 4_000) return null // evita llenar la TV de avisos
        lastStart = now
        reqs.values.removeIf { now - it.created > TTL }
        if (reqs.size > 20) return null
        val id = ByteArray(16).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        val code = "%04d".format(rnd.nextInt(10_000))
        return Req(id, code, device, now).also {
            reqs[id] = it
            shown.value = it
            Apps.bringAuraToFront(Aura.app)
        }
    }

    fun answer(id: String, allow: Boolean) {
        reqs[id]?.state = if (allow) "approved" else "denied"
        if (shown.value?.id == id) shown.value = null
    }

    /** Estado para la web. El token solo se entrega una vez, cuando se ha aprobado en la TV. */
    fun status(id: String): Pair<String, String?> {
        val r = reqs[id] ?: return "expired" to null
        if (System.currentTimeMillis() - r.created > TTL) {
            reqs.remove(id); if (shown.value?.id == id) shown.value = null
            return "expired" to null
        }
        if (r.state == "approved") { reqs.remove(id); return "approved" to Aura.config.state.value.token }
        if (r.state == "denied") reqs.remove(id)
        return r.state to null
    }

    /** Nombre del equipo a partir del User-Agent, solo con palabras conocidas (nunca el texto tal cual). */
    fun deviceName(ua: String?): String {
        val u = ua.orEmpty()
        val os = when {
            "iPhone" in u -> "iPhone"; "iPad" in u -> "iPad"; "Android" in u -> "Android"
            "Windows" in u -> "Windows"; "Mac OS" in u || "Macintosh" in u -> "Mac"; "Linux" in u -> "Linux"
            else -> ""
        }
        val br = when {
            "Edg/" in u -> "Edge"; "OPR/" in u -> "Opera"; "Firefox/" in u -> "Firefox"
            "Chrome/" in u -> "Chrome"; "Safari/" in u -> "Safari"; else -> ""
        }
        return listOf(br, os).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Navegador" }
    }
}

/** Aviso en la TV: QR para el movil + codigo y Permitir / Rechazar para el ordenador. */
@Composable
internal fun PairPanel(req: Pairing.Req, allowSelected: Boolean, cfg: AuraConfig, s: Strings, blur: ImageBitmap?) {
    val url = remember(cfg.token) { Qr.connectUrl(cfg) }
    val qr = remember(url) { Qr.bitmap(url) }
    // Las teclas (izquierda/derecha, OK, Atras) las maneja HomeScreen mientras el aviso esta abierto
    LaunchedEffect(req.id) {
        delay(Pairing.TTL)
        Pairing.answer(req.id, false)
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Row(
            Modifier.glass(blur, 30.dp, Color.Black.copy(alpha = 0.5f)).padding(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(250.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(12.dp)) {
                Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.width(470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ri_shield_check), null, tint = Color(0xFFF0C987), modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s.pairTitle, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
                Text("${s.pairFrom} ${req.device}", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(s.pairPhone, color = Color.White.copy(alpha = 0.88f), fontSize = 16.sp)
                Text(s.pairPc, color = Color.White.copy(alpha = 0.88f), fontSize = 16.sp)
                Text(
                    req.code.toCharArray().joinToString("  "),
                    color = Color(0xFFF2D3A0), fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Pill(s.pairAllow, selected = allowSelected) { Pairing.answer(req.id, true) }
                    Pill(s.pairDeny, selected = !allowSelected) { Pairing.answer(req.id, false) }
                }
            }
        }
    }
}
