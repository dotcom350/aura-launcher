package com.david.aura

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Locale

private class UiTile(val bitmap: ImageBitmap, val fill: Boolean)

private fun KeyEvent.isCenter() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
private fun KeyEvent.isBack() = key == Key.Back || key.nativeKeyCode == android.view.KeyEvent.KEYCODE_BACK

@Composable
fun AuraHome() {
    val ctx = LocalContext.current
    val cfg by Aura.config.state.collectAsState()
    val appsVersion by Aura.appsVersion.collectAsState()
    val assetsVersion by Aura.assetsVersion.collectAsState()
    val weather by Aura.weather.collectAsState()
    val s = remember(cfg.language) { Strings(cfg.language) }

    val apps by produceState(initialValue = emptyList<AppEntry>(), appsVersion) {
        value = withContext(Dispatchers.IO) { Apps.load(ctx) }
    }
    val rows = remember(apps, cfg) { Apps.rows(apps, cfg) }
    val pinned = rows.first
    val others = rows.second

    var focusedPkg by remember { mutableStateOf<String?>(null) }
    var wallpaperPkg by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    val pairReq by Pairing.shown.collectAsState()
    var pairAllow by remember { mutableStateOf(true) }
    LaunchedEffect(pairReq?.id) { pairAllow = true }
    var showAll by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var movingPkg by remember { mutableStateOf<String?>(null) }
    var skipCenterUp by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    val now by produceState(initialValue = Calendar.getInstance()) {
        while (true) {
            delay(10_000)
            value = Calendar.getInstance()
        }
    }
    val minuteKey = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    // Espera breve para no cargar fondos mientras te desplazas rapido
    LaunchedEffect(focusedPkg) {
        Aura.focusedPkg.value = focusedPkg
        delay(350)
        wallpaperPkg = focusedPkg
    }

    val wallpaperId = remember(cfg, wallpaperPkg, minuteKey, assetsVersion) {
        Wallpapers.pick(ctx, cfg, Calendar.getInstance(), wallpaperPkg)
    }
    LaunchedEffect(wallpaperId) { Aura.currentWallpaper.value = wallpaperId }
    val images by produceState<WallpaperImages?>(initialValue = null, wallpaperId, assetsVersion) {
        val id = wallpaperId ?: return@produceState
        withContext(Dispatchers.IO) { Wallpapers.load(ctx, id) }?.let { value = it }
    }

    val firstFocus = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    val resumeTick by Aura.resumeTick.collectAsState()
    // Tecla Inicio: cierra cualquier panel y vuelve al principio de la fila
    val homeTick by Aura.homeTick.collectAsState()
    LaunchedEffect(homeTick) {
        if (homeTick == 0 || !cfg.onboardingDone) return@LaunchedEffect
        showAll = false; showSettings = false; showInfo = false; movingPkg = null
        runCatching { rowState.scrollToItem(0) }
        delay(150)
        runCatching { firstFocus.requestFocus() }
    }
    // Inicio mantenido: abre los ajustes de Aura
    val pendingSettings by Aura.pendingSettings.collectAsState()
    LaunchedEffect(pendingSettings) {
        if (pendingSettings) {
            Aura.pendingSettings.value = false
            showAll = false; showInfo = false; movingPkg = null
            showSettings = true
        }
    }
    // Se espera a que carguen las apps: antes solo estan los atajos (Todas las apps, Ajustes) y el foco caeria ahi
    LaunchedEffect(apps.isNotEmpty(), resumeTick, cfg.onboardingDone) {
        if (apps.isNotEmpty() && pinned.isNotEmpty() && !showAll && !showSettings && cfg.onboardingDone) {
            runCatching { rowState.scrollToItem(0) }
            delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }

    /** Mueve una app de la fila principal (solo entre las visibles). */
    fun move(pkg: String, delta: Int) {
        val installed = apps.map { it.pkg }.toSet()
        Aura.config.update { c ->
            val visible = c.pinned.filter { it in installed && it !in c.hidden }.toMutableList()
            val i = visible.indexOf(pkg)
            val j = i + delta
            if (i < 0 || j !in visible.indices) return@update c
            Collections.swap(visible, i, j)
            c.copy(pinned = visible + c.pinned.filter { it !in visible })
        }
    }
    LaunchedEffect(pinned, movingPkg) {
        val idx = pinned.indexOfFirst { it.pkg == movingPkg }
        if (idx >= 0) rowState.animateScrollToItem((idx - 2).coerceAtLeast(0))
    }

    fun togglePinned(entry: AppEntry) {
        val wasPinned = entry.pkg in cfg.pinned
        Aura.config.update { c ->
            if (entry.pkg in c.pinned) c.copy(pinned = c.pinned - entry.pkg)
            else c.copy(pinned = c.pinned + entry.pkg, hidden = c.hidden - entry.pkg)
        }
        toast = if (wasPinned) s.removed(entry.label) else s.added(entry.label)
    }

    // Un launcher no se cierra con Atras
    BackHandler(enabled = true) { showInfo = false }

    val open: (String) -> Unit = { pkg ->
        when (pkg) {
            Aura.ALL_APPS_PKG -> showAll = true
            Aura.SETTINGS_PKG -> showSettings = true
            else -> Apps.launch(ctx, pkg)
        }
    }

    val font = remember(cfg.fontFamily) { fontFamilyFor(cfg.fontFamily) }
    val (tileW, tileH) = tileDims(cfg.tileSize)

    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = font)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { e ->
                    val moving = movingPkg
                    val pair = pairReq
                    when {
                        // Aviso de conectar un dispositivo: se maneja aqui para que el foco de la fila no se lo quede
                        pair != null -> {
                            when {
                                e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> pairAllow = true
                                e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight -> pairAllow = false
                                e.type == KeyEventType.KeyUp && e.isCenter() -> Pairing.answer(pair.id, pairAllow)
                                e.type == KeyEventType.KeyUp && e.isBack() -> Pairing.answer(pair.id, false)
                            }
                            true
                        }
                        moving != null -> when {
                            e.key == Key.DirectionLeft && e.type == KeyEventType.KeyDown -> { move(moving, -1); true }
                            e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown -> { move(moving, 1); true }
                            (e.isCenter() || e.isBack()) && e.type == KeyEventType.KeyUp -> {
                                if (skipCenterUp && e.isCenter()) skipCenterUp = false else movingPkg = null
                                true
                            }
                            else -> true // durante el modo mover no se navega
                        }
                        e.key == Key.Menu && e.type == KeyEventType.KeyUp && !showSettings && cfg.onboardingDone -> { showInfo = !showInfo; true }
                        else -> false
                    }
                }
        ) {
            Crossfade(targetState = images, animationSpec = tween(900), label = "wallpaper") { img ->
                if (img != null) {
                    Image(bitmap = img.full, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            // Fondo de video: encima de su imagen fija, que se ve mientras carga
            val video = remember(wallpaperId, cfg.videos) { Videos.byWallpaperId(wallpaperId) }
            if (video != null) key(video.id) { VideoWallpaper(video, Modifier.fillMaxSize()) }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.25f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.7f),
                    )
                )
            )

            if (!cfg.onboardingDone) {
                // Primera instalacion: la bienvenida ocupa toda la pantalla (sin filas detras que roben el foco)
                Onboarding(cfg, s, images?.blur)
            } else {

            TopBar(now, weather, cfg, s, images?.blur, Modifier.align(Alignment.TopCenter))

            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 30.dp)) {
                val label = if (movingPkg != null) s.moveHint
                    else (pinned + others).firstOrNull { it.pkg == focusedPkg }?.label.orEmpty()
                Text(
                    label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    style = shadowed(), maxLines = 1,
                    modifier = Modifier.padding(start = 64.dp, bottom = 6.dp).height(30.dp),
                )
                // Banda de cristal a todo el ancho: las apps entran y salen por los bordes de la pantalla
                Box(Modifier.fillMaxWidth().glass(images?.blur, 0.dp, outline = false)) {
                    AppRow(
                        pinned, tileW, tileH, assetsVersion, firstFocus, rowState, movingPkg,
                        onFocused = { focusedPkg = it }, onOpen = open,
                        onLongPress = { pkg ->
                            if (pkg != Aura.SETTINGS_PKG && pkg != Aura.ALL_APPS_PKG) { movingPkg = pkg; skipCenterUp = true }
                        },
                    )
                }
                if (cfg.showAllAppsRow && others.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    AppRow(
                        others, tileW * 0.72f, tileH * 0.72f, assetsVersion, null, rememberLazyListState(), null,
                        onFocused = { focusedPkg = it }, onOpen = open,
                        onLongPress = { pkg -> others.firstOrNull { it.pkg == pkg }?.let(::togglePinned) },
                    )
                }
            }

            AnimatedVisibility(visible = showAll, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                AllAppsOverlay(
                    apps, cfg, s, images?.blur, assetsVersion,
                    onClose = { showAll = false },
                    onOpen = { pkg -> showAll = false; Apps.launch(ctx, pkg) },
                    onLongPress = { pkg -> apps.firstOrNull { it.pkg == pkg }?.let(::togglePinned) },
                )
            }

            AnimatedVisibility(visible = showSettings, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                TvSettings(cfg, s, images?.blur, weather, onClose = { showSettings = false })
            }

            if (showInfo) InfoPanel(cfg, s, images?.blur, Modifier.align(Alignment.Center))

            pairReq?.let { PairPanel(it, pairAllow, cfg, s, images?.blur) }

            toast?.let { GlassToast(it, images?.blur, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)) }
            }
        }
    }
}

@Composable
private fun TopBar(now: Calendar, weather: Weather?, cfg: AuraConfig, s: Strings, blur: ImageBitmap?, modifier: Modifier) {
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val date = SimpleDateFormat(s.datePattern, s.locale).format(now.time).replaceFirstChar { it.uppercase() }
    val time = SimpleDateFormat(if (cfg.use24h) "H:mm" else "h:mm", Locale.US).format(now.time)
    val amPm = if (cfg.use24h) "" else SimpleDateFormat("a", Locale.US).format(now.time).uppercase()
    val clockSize = clockSp(cfg.clockSize)
    val sub = listOfNotNull(
        s.greeting(hour).takeIf { cfg.showGreeting },
        date.takeIf { cfg.showDate },
    ).joinToString(" · ")

    Row(modifier.fillMaxWidth().padding(start = 64.dp, end = 54.dp, top = 34.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(time, color = Color.White, fontSize = clockSize, fontWeight = FontWeight.Light, style = shadowed())
                if (amPm.isNotEmpty()) {
                    Text(
                        amPm, color = Color.White.copy(alpha = 0.85f), fontSize = clockSize * 0.3f, fontWeight = FontWeight.Medium,
                        style = shadowed(), modifier = Modifier.padding(start = 8.dp, bottom = (clockSize.value * 0.18f).dp),
                    )
                }
            }
            if (sub.isNotEmpty()) {
                Text(sub, color = Color.White.copy(alpha = 0.88f), fontSize = dateSp(cfg.dateSize), fontWeight = FontWeight.Medium, style = shadowed())
            }
        }
        if (weather != null && cfg.showWeather) {
            Row(
                Modifier.padding(top = 10.dp).glass(blur, 24.dp).padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(weather.iconRes), contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${weather.temp}°", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        if (weather.max != null && weather.min != null) {
                            Text(
                                "  ${weather.max}° / ${weather.min}°", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 5.dp),
                            )
                        }
                    }
                    Text(
                        listOf(weather.description(cfg.language), weather.place).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    items: List<AppEntry>,
    tileW: Dp,
    tileH: Dp,
    assetsVersion: Int,
    firstFocus: FocusRequester?,
    state: LazyListState,
    movingPkg: String?,
    onFocused: (String) -> Unit,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 60.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        itemsIndexed(items, key = { _, e -> e.pkg }) { index, entry ->
            AppTile(
                entry, tileW, tileH, assetsVersion, if (index == 0) firstFocus else null, onFocused, onOpen,
                onLongPress = onLongPress, moving = entry.pkg == movingPkg,
            )
        }
    }
}

@Composable
private fun AppTile(
    entry: AppEntry,
    w: Dp,
    h: Dp,
    assetsVersion: Int,
    focusRequester: FocusRequester?,
    onFocused: (String) -> Unit,
    onOpen: (String) -> Unit,
    onLongPress: ((String) -> Unit)? = null,
    moving: Boolean = false,
    showLabel: Boolean = false,
) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = when { moving -> 1.18f; focused -> 1.12f; else -> 1f },
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "scale",
    )
    val special = entry.pkg == Aura.SETTINGS_PKG || entry.pkg == Aura.ALL_APPS_PKG
    val tile by produceState<UiTile?>(initialValue = null, entry.pkg, assetsVersion) {
        if (!special) value = withContext(Dispatchers.IO) {
            Apps.tileImage(ctx, entry)?.let { UiTile(it.bitmap.asImageBitmap(), it.fill) }
        }
    }
    val shape = RoundedCornerShape(18.dp)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .zIndex(if (focused || moving) 1f else 0f)
                .size(w, h)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (focused) 30.dp.toPx() else 4.dp.toPx()
                    this.shape = shape
                    clip = true
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused(entry.pkg)
                }
                .onPreviewKeyEvent { e ->
                    if (!e.isCenter()) return@onPreviewKeyEvent false
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            val repeat = e.nativeKeyEvent.repeatCount
                            if (repeat == 0) longFired = false
                            if (repeat == 1 && onLongPress != null && !longFired) {
                                longFired = true
                                onLongPress(entry.pkg)
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            if (longFired) longFired = false else onOpen(entry.pkg)
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .background(Brush.linearGradient(listOf(Color(0xFF2C2B29), Color(0xFF161615))))
        ) {
            val t = tile
            when {
                special -> Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            if (entry.pkg == Aura.SETTINGS_PKG) listOf(Color(0xFF8E9099), Color(0xFF3F4048))
                            else listOf(Color(0xFF55504A), Color(0xFF2A2723))
                        )
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (entry.pkg == Aura.SETTINGS_PKG) R.drawable.ri_settings else R.drawable.ri_category),
                        contentDescription = entry.label, tint = Color.White, modifier = Modifier.size(h * 0.42f),
                    )
                }

                t != null && t.fill -> Image(t.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)

                t != null -> Image(t.bitmap, null, Modifier.fillMaxSize(0.58f).align(Alignment.Center), contentScale = ContentScale.Fit)

                else -> Text(
                    entry.label, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
            if (focused || moving) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.30f), 0.4f to Color.Transparent)))
                Box(Modifier.fillMaxSize().border(if (moving) 3.dp else 2.dp, if (moving) Color(0xFFF0C987) else Color.White.copy(alpha = 0.9f), shape))
            }
            if (moving) {
                Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ri_chevron_left), null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ri_chevron_right), null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (showLabel) {
            Text(
                entry.label, color = Color.White.copy(alpha = if (focused) 1f else 0.75f), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.width(w).padding(top = 10.dp), textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AllAppsOverlay(
    apps: List<AppEntry>,
    cfg: AuraConfig,
    s: Strings,
    blur: ImageBitmap?,
    assetsVersion: Int,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    BackHandler(enabled = true) { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    var focusedPkg by remember { mutableStateOf<String?>(null) }
    val visible = apps.filter { it.pkg !in cfg.hidden }
    val focusedApp = visible.firstOrNull { it.pkg == focusedPkg }

    Box(Modifier.fillMaxSize()) {
        if (blur != null) Image(blur, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Column(Modifier.fillMaxSize().padding(horizontal = 60.dp, vertical = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ri_category), null, tint = Color.White, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
                Text(s.allApps, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(14.dp))
                Text("${visible.size} ${s.apps}", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                if (focusedApp != null) {
                    if (focusedApp.pkg in cfg.pinned) {
                        Icon(painterResource(R.drawable.ri_star), null, tint = Color(0xFFF2D3A0), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(focusedApp.label, color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp)
                }
            }
            Text(s.longPressHint, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(visible, key = { _, e -> e.pkg }) { index, entry ->
                    AppTile(
                        entry, 128.dp, 72.dp, assetsVersion, if (index == 0) first else null,
                        onFocused = { focusedPkg = entry.pkg }, onOpen = onOpen, onLongPress = onLongPress, showLabel = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(cfg: AuraConfig, s: Strings, blur: ImageBitmap?, modifier: Modifier) {
    val url = remember(cfg.token) { Qr.connectUrl(cfg) }
    val qr = remember(url) { Qr.bitmap(url) }
    val ip = remember { WebService.localIp() ?: "IP" }
    Row(
        modifier.glass(blur, 30.dp, Color.Black.copy(alpha = 0.45f)).padding(34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(230.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(12.dp)) {
            Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ri_remote), null, tint = Color.White, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text(s.connectPhone, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Text(s.remoteTitle, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(s.orOpen, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            Text("http://$ip:${Aura.WEB_PORT}", color = Color(0xFFF2D3A0), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(s.token, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            Text(cfg.token.chunked(5).joinToString(" "), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(s.close, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
        }
    }
}
