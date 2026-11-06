package com.david.aura

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Sand = Color(0xFFF0C987)
private val Light = Color(0xFFF6F3EE)
private val Ink = Color(0xFF151311)
private val Amber = Color(0xFFF2A65A)

private enum class Phase { Pending, Working, Done, Failed, Removed }

/**
 * Bienvenida de la primera instalacion: idioma, lista de ajustes de Amazon a desactivar y
 * animacion "desactivando y eliminando". Al terminar (o al omitirla) no vuelve a salir;
 * se puede repetir desde Ajustes o desde la web.
 */
@Composable
internal fun Onboarding(cfg: AuraConfig, s: Strings, blur: ImageBitmap?) {
    val ctx = LocalContext.current
    val en = s.en
    var step by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateMapOf<String, Boolean>().apply { SystemTweaks.ALL.filter { !it.manual }.forEach { put(it.id, true) } } }
    val phases = remember { mutableStateMapOf<String, Phase>() }
    var done by remember { mutableIntStateOf(0) }
    var failed by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<String?>(null) }
    val missing = remember { SystemTweaks.missingPermissions(ctx) }

    fun finish() = Aura.config.update { it.copy(onboardingDone = true) }

    BackHandler(enabled = true) {
        when (step) {
            1 -> step = 0
            3 -> finish()
        }
    }

    // Aplicar uno a uno, con tiempo suficiente para ver cada animacion
    val queue = remember(step) { SystemTweaks.ALL.filter { selected[it.id] == true } }
    LaunchedEffect(step) {
        if (step != 2) return@LaunchedEffect
        queue.forEach { phases[it.id] = Phase.Pending }
        delay(700)
        for (t in queue) {
            current = t.id
            phases[t.id] = Phase.Working
            val start = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) { SystemTweaks.apply(ctx, t, true) }
            delay((1100 - (System.currentTimeMillis() - start)).coerceAtLeast(0))
            phases[t.id] = if (ok) Phase.Done else Phase.Failed
            done++
            if (!ok) failed++
            delay(700)
            if (ok) phases[t.id] = Phase.Removed
            delay(260)
        }
        current = null
        delay(600)
        step = 3
    }

    Box(Modifier.fillMaxSize()) {
        if (blur != null) Image(blur, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))
        Glow()
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(250)) },
            label = "onboarding",
        ) { st ->
            when (st) {
                0 -> Welcome(cfg, en, onNext = { step = 1 })
                1 -> Choose(cfg, en, selected, missing, onApply = { done = 0; failed = 0; step = 2 }, onSkip = { finish() })
                2 -> Running(cfg, en, queue, phases, done, current)
                else -> Finished(en, done - failed, failed, onClose = { finish() })
            }
        }
    }
}

/** Resplandor calido que se mueve despacio por detras. */
@Composable
private fun Glow() {
    val t by rememberInfiniteTransition(label = "glow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "g",
    )
    Box(
        Modifier.fillMaxSize().drawBehind {
            val c = Offset(size.width * (0.2f + 0.6f * t), size.height * (0.85f - 0.5f * t))
            drawCircle(Brush.radialGradient(listOf(Sand.copy(alpha = 0.16f), Color.Transparent), c, size.minDimension * 0.75f), size.minDimension * 0.75f, c)
        }
    )
}

@Composable
private fun Welcome(cfg: AuraConfig, en: Boolean, onNext: () -> Unit) {
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(350); runCatching { start.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ic_aura), null, Modifier.size(92.dp).clip(RoundedCornerShape(26.dp)))
        Spacer(Modifier.height(22.dp))
        Text(if (en) "Welcome to Aura" else "Bienvenido a Aura", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            if (en) "Your Fire TV, clean and your way. In a minute we'll turn off Amazon's ads, tracking and distractions."
            else "Tu Fire TV, limpio y a tu manera. En un minuto desactivamos la publicidad, el rastreo y las distracciones de Amazon.",
            color = Color.White.copy(alpha = 0.78f), fontSize = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(34.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Español", selected = cfg.language == "es") { Aura.config.update { it.copy(language = "es") } }
            Pill("English", selected = cfg.language == "en") { Aura.config.update { it.copy(language = "en") } }
        }
        Spacer(Modifier.height(18.dp))
        Pill(if (en) "Get started" else "Empezar", primary = true, focusRequester = start, icon = R.drawable.ri_chevron_right, onClick = onNext)
    }
}

@Composable
private fun Choose(
    cfg: AuraConfig,
    en: Boolean,
    selected: MutableMap<String, Boolean>,
    missing: Boolean,
    onApply: () -> Unit,
    onSkip: () -> Unit,
) {
    val apply = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(350); runCatching { apply.requestFocus() } }
    val count = selected.count { it.value }
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 44.dp)) {
        Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ri_shield_check), null, tint = Sand, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(16.dp))
            Text(if (en) "Clean up your Fire TV" else "Limpia tu Fire TV", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (en) "Aura will turn off these Amazon options. Press OK on one to leave it as it is."
                else "Aura va a desactivar estas opciones de Amazon. Pulsa OK sobre una para dejarla como está.",
                color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp,
            )
            Spacer(Modifier.height(26.dp))
            Pill(
                if (en) "Turn off and remove ($count)" else "Desactivar y eliminar ($count)",
                primary = true, focusRequester = apply, icon = R.drawable.ri_sparkles, enabled = count > 0, onClick = onApply,
            )
            Spacer(Modifier.height(12.dp))
            Pill(if (en) "Not now" else "Ahora no", onClick = onSkip)
            Spacer(Modifier.height(20.dp))
            Text(
                if (en) "You can turn anything back on in Settings › Privacy & system, or from the web panel."
                else "Podrás volver a activar cualquier cosa en Ajustes › Privacidad y sistema, o desde la web.",
                color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
            )
            if (missing) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (en) "Aura still needs the system permission. From the PC run deploy.ps1 once (it grants it over ADB)."
                    else "A Aura le falta el permiso del sistema. Desde el PC ejecuta deploy.ps1 una vez (lo concede por ADB).",
                    color = Amber, fontSize = 13.sp,
                    modifier = Modifier.background(Amber.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
        }
        Spacer(Modifier.width(48.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SystemTweaks.GROUPS.keys.forEach { group ->
                val tweaks = SystemTweaks.ALL.filter { it.group == group }
                item(key = "h_$group") {
                    Text(
                        SystemTweaks.groupTitle(group, cfg.language).uppercase(), color = Sand, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                items(tweaks, key = { it.id }) { t ->
                    ChoiceRow(t, cfg.language, selected[t.id] == true) {
                        if (!t.manual) selected[t.id] = selected[t.id] != true
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(t: SystemTweaks.Tweak, lang: String, on: Boolean, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val en = lang == "en"
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e -> if (e.isOk() && e.type == KeyEventType.KeyUp) { onToggle(); true } else false }
            .focusable()
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f), shape)
            .border(1.dp, if (focused) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(t.icon), null, tint = if (on) Sand else Color.White.copy(alpha = 0.55f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title(lang), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(t.sub(lang), color = Color.White.copy(alpha = 0.6f), fontSize = 12.5.sp, maxLines = 2)
        }
        Spacer(Modifier.width(14.dp))
        if (t.manual) {
            Text(
                if (en) "Manual" else "Manual", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        } else {
            Box(
                Modifier.size(28.dp)
                    .background(if (on) Sand else Color.Transparent, CircleShape)
                    .border(2.dp, if (on) Sand else Color.White.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Icon(painterResource(R.drawable.ri_check), null, tint = Ink, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Running(
    cfg: AuraConfig,
    en: Boolean,
    queue: List<SystemTweaks.Tweak>,
    phases: Map<String, Phase>,
    done: Int,
    current: String?,
) {
    val total = queue.size.coerceAtLeast(1)
    val progress by animateFloatAsState(done.toFloat() / total, tween(700), label = "progress")
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(380.dp), horizontalAlignment = Alignment.Start) {
            Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                ProgressRing(progress, Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Light)
                    Text(if (en) "$done of ${queue.size}" else "$done de ${queue.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(30.dp))
            Text(
                if (en) "Turning off and removing…" else "Desactivando y eliminando…",
                color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            val name = queue.firstOrNull { it.id == current }?.title(cfg.language) ?: (if (en) "Getting ready…" else "Preparando…")
            AnimatedContent(name, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "cur") { n ->
                Text(n, color = Sand, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            queue.forEach { t ->
                val phase = phases[t.id] ?: Phase.Pending
                AnimatedVisibility(
                    visible = phase != Phase.Removed,
                    exit = slideOutHorizontally(tween(420)) { it / 3 } + fadeOut(tween(380)) + shrinkVertically(tween(420, delayMillis = 120)),
                ) {
                    RunRow(t, cfg.language, phase)
                }
            }
        }
    }
}

@Composable
private fun RunRow(t: SystemTweaks.Tweak, lang: String, phase: Phase) {
    val en = lang == "en"
    val shape = RoundedCornerShape(14.dp)
    val working = phase == Phase.Working
    val shimmer by rememberInfiniteTransition(label = "sh").animateFloat(
        -0.4f, 1.4f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x",
    )
    val strike by animateFloatAsState(if (phase == Phase.Done) 1f else 0f, tween(450), label = "strike")
    val dim = phase == Phase.Pending
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(Color.White.copy(alpha = if (working) 0.12f else 0.05f), shape)
            .border(1.dp, if (working) Sand.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.07f), shape)
            .drawBehind {
                if (working) {
                    val x = size.width * shimmer
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Sand.copy(alpha = 0.16f), Color.Transparent), Offset(x - 160f, 0f), Offset(x + 160f, size.height)))
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(t.icon), null, tint = if (dim) Color.White.copy(alpha = 0.35f) else Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            t.title(lang), color = Color.White.copy(alpha = if (dim) 0.45f else 1f), fontSize = 16.sp, maxLines = 1,
            modifier = Modifier.weight(1f, fill = false).drawWithContent {
                drawContent()
                if (strike > 0f) drawLine(Color.White.copy(alpha = 0.8f), Offset(0f, size.height / 2), Offset(size.width * strike, size.height / 2), 2.dp.toPx())
            },
        )
        Spacer(Modifier.weight(1f))
        val status = when (phase) {
            Phase.Working -> if (t.inverted) (if (en) "Turning on…" else "Activando…") else (if (en) "Turning off…" else "Desactivando…")
            Phase.Done, Phase.Removed -> if (t.inverted) (if (en) "On" else "Activado") else (if (en) "Removed" else "Eliminado")
            Phase.Failed -> if (en) "No permission" else "Sin permiso"
            Phase.Pending -> ""
        }
        if (status.isNotEmpty()) {
            Text(status, color = when (phase) { Phase.Failed -> Amber; Phase.Working -> Sand; else -> Color.White.copy(alpha = 0.75f) }, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                Phase.Working -> Spinner(22.dp)
                Phase.Done, Phase.Removed -> Box(Modifier.size(24.dp).background(Sand, CircleShape)) { AnimatedCheck(Modifier.fillMaxSize(), Ink, 2.5.dp) }
                Phase.Failed -> Text("!", color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Phase.Pending -> Box(Modifier.size(8.dp).background(Color.White.copy(alpha = 0.25f), CircleShape))
            }
        }
    }
}

@Composable
private fun Finished(en: Boolean, ok: Int, failed: Int, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(900); runCatching { close.requestFocus() } }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 220f)) }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(2200)), label = "p")
    Column(
        Modifier.fillMaxSize().padding(horizontal = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Sand.copy(alpha = 0.35f * (1f - pulse)), radius = size.minDimension / 2 * (0.55f + 0.45f * pulse), style = Stroke(2.dp.toPx()))
            }
            Box(Modifier.size(104.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.background(Sand, CircleShape)) {
                AnimatedCheck(Modifier.fillMaxSize(), Ink, 6.dp, delayMs = 250)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(if (en) "All set" else "Listo", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(if (en) "Your Fire TV is clean" else "Tu Fire TV está limpio", color = Color.White.copy(alpha = 0.85f), fontSize = 21.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            buildString {
                append(if (en) "$ok turned off" else "$ok desactivados")
                if (failed > 0) append(if (en) " · $failed need the PC permission (deploy.ps1)" else " · $failed necesitan el permiso del PC (deploy.ps1)")
            },
            color = if (failed > 0) Amber else Color.White.copy(alpha = 0.6f), fontSize = 15.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (en) "You can turn anything back on in Settings › Privacy & system, or from the web panel."
            else "Puedes reactivar cualquier opción en Ajustes › Privacidad y sistema, o desde la web.",
            color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(30.dp))
        Pill(if (en) "Go to home" else "Ir al inicio", primary = true, focusRequester = close, icon = R.drawable.ri_home, onClick = onClose)
    }
}

// ---------- piezas ----------

private fun androidx.compose.ui.input.key.KeyEvent.isOk() =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

@Composable
internal fun Pill(
    text: String,
    primary: Boolean = false,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    icon: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "pill")
    val shape = RoundedCornerShape(999.dp)
    val bg = when {
        primary && focused -> Color.White
        primary -> Light.copy(alpha = if (enabled) 0.92f else 0.4f)
        selected -> Sand.copy(alpha = if (focused) 1f else 0.85f)
        focused -> Color.White.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val fg = if (primary || selected) Ink else Color.White
    Row(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e -> if (e.isOk() && e.type == KeyEventType.KeyUp) { if (enabled) onClick(); true } else false }
            .focusable()
            .background(bg, shape)
            .border(2.dp, if (focused) Sand else Color.Transparent, shape)
            .padding(horizontal = 26.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (icon != null) {
            Spacer(Modifier.width(10.dp))
            Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = 10.dp.toPx()
        val inset = w / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        drawArc(Color.White.copy(alpha = 0.1f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        drawArc(
            Brush.sweepGradient(listOf(Sand.copy(alpha = 0.6f), Sand, Light, Sand.copy(alpha = 0.6f))),
            -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun Spinner(size: Dp) {
    val rot by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(850, easing = LinearEasing)), label = "r",
    )
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = rot }) {
        drawArc(Sand, 0f, 270f, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Marca de verificacion que se dibuja sola. */
@Composable
private fun AnimatedCheck(modifier: Modifier, color: Color, stroke: Dp, delayMs: Int = 0) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, tween(420)) }
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.27f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.68f)
            lineTo(size.width * 0.74f, size.height * 0.35f)
        }
        val pm = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        pm.getSegment(0f, pm.length * p.value, seg, true)
        drawPath(seg, color, style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
