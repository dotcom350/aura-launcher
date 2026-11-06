package com.david.aura

import android.content.Context
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Panel web + API REST de Aura.
 *
 * Seguridad:
 *  - Solo responde a clientes de la red local (IPs privadas).
 *  - Toda ruta /api/ (salvo /api/ping) exige el token largo (cabecera X-Token o ?token=).
 *  - Nunca ejecuta comandos con texto recibido: las teclas salen de una lista fija,
 *    las apps se validan contra las instaladas y el texto pasa por una lista blanca de caracteres.
 */
class WebServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    private val main = Handler(Looper.getMainLooper())

    override fun serve(session: IHTTPSession): Response {
        if (!isLanClient(session.remoteIpAddress)) return text("Solo red local", Response.Status.FORBIDDEN)
        return try {
            handle(session)
        } catch (e: Exception) {
            json(buildJsonObject { put("ok", false); put("error", e.javaClass.simpleName) }, Response.Status.INTERNAL_ERROR)
        }
    }

    private fun isLanClient(ip: String?): Boolean = runCatching {
        val addr = InetAddress.getByName(ip ?: return false)
        addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
    }.getOrDefault(false)

    private fun tokenOk(given: String?): Boolean {
        val expected = Aura.config.state.value.token
        if (given == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
    }

    private fun handle(s: IHTTPSession): Response {
        val uri = s.uri.trimEnd('/').ifEmpty { "/" }
        if (s.method == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        STATIC[if (uri == "/") "/index.html" else uri]?.let { (file, mime) -> return asset(file, mime) }
        if (!uri.startsWith("/api/")) return text("No encontrado", Response.Status.NOT_FOUND)
        if (uri == "/api/ping") return json(buildJsonObject { put("ok", true); put("name", "Aura"); put("pin", Security.enabled()) })

        // Entrar sin token: la TV muestra el QR y un codigo, y se aprueba con el mando (ver Pairing)
        if (uri == "/api/pair") {
            if (s.method != Method.POST) return bad("Usa POST")
            val r = Pairing.start(Pairing.deviceName(s.headers["user-agent"]))
                ?: return json(buildJsonObject { put("ok", false); put("error", "wait") }, Response.Status.TOO_MANY_REQUESTS)
            return json(buildJsonObject { put("ok", true); put("id", r.id); put("code", r.code); put("ttl", Pairing.TTL) })
        }
        if (uri == "/api/pair/status") {
            val id = s.parameters["id"]?.firstOrNull()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return bad("id")
            val (state, token) = Pairing.status(id)
            return json(buildJsonObject { put("ok", true); put("state", state); token?.let { put("token", it) }; put("pin", Security.enabled()) })
        }

        // El token se comprueba ANTES de leer el cuerpo (cabecera X-Token o ?token= en la URL)
        if (!tokenOk(s.headers["x-token"] ?: s.parameters["token"]?.firstOrNull())) {
            return json(buildJsonObject { put("ok", false); put("error", "Token incorrecto") }, Response.Status.UNAUTHORIZED)
        }
        // Con codigo de seguridad activado hace falta ademas una sesion (se consigue en /api/login con el PIN)
        if (uri != "/api/login" && Security.enabled() &&
            !Security.sessionOk(s.headers["x-session"] ?: s.parameters["session"]?.firstOrNull())
        ) {
            return json(buildJsonObject { put("ok", false); put("error", "pin_required") }, Response.Status.UNAUTHORIZED)
        }
        // Instalar y desinstalar piden el codigo otra vez (cabecera X-Pin), antes de recibir el APK
        if (s.method == Method.POST && uri in setOf("/api/install", "/api/uninstall")) pinConfirm(s)?.let { return it }
        val length = s.headers["content-length"]?.toLongOrNull() ?: 0L
        val limit = when (uri) { "/api/video" -> Videos.MAX_UPLOAD; "/api/install" -> Installer.MAX_APK; else -> 30L * 1024 * 1024 }
        if (length > limit) return json(buildJsonObject { put("ok", false); put("error", "Archivo demasiado grande") }, Response.Status.PAYLOAD_TOO_LARGE)
        val files = HashMap<String, String>()
        if (s.method == Method.POST) s.parseBody(files)
        val params = s.parameters.mapValues { it.value.firstOrNull().orEmpty() }

        val body: JsonObject = files["postData"]
            ?.let { runCatching { AuraJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        fun arg(name: String): String? = params[name]?.takeIf { it.isNotEmpty() } ?: body[name]?.jsonPrimitive?.contentOrNull

        return when (uri) {
            "/api/state" -> json(state())

            "/api/login" -> {
                if (!Security.enabled()) return json(buildJsonObject { put("ok", true) })
                when (Security.check(arg("pin"))) {
                    Security.Check.OK -> json(buildJsonObject { put("ok", true); put("session", Security.newSession()) })
                    Security.Check.LOCKED -> json(
                        buildJsonObject { put("ok", false); put("error", "locked"); put("seconds", Security.lockSeconds()) },
                        Response.Status.TOO_MANY_REQUESTS,
                    )
                    Security.Check.BAD -> json(
                        buildJsonObject { put("ok", false); put("error", "bad_pin"); put("left", Security.attemptsLeft()) },
                        Response.Status.FORBIDDEN,
                    )
                }
            }

            "/api/pin" -> {
                // Activar/cambiar o quitar el codigo. Si ya hay codigo, siempre se pide el actual.
                if (s.method != Method.POST) return bad("Usa POST")
                if (Security.enabled()) {
                    when (Security.check(arg("current"))) {
                        Security.Check.OK -> Unit
                        Security.Check.LOCKED -> return json(
                            buildJsonObject { put("ok", false); put("error", "locked"); put("seconds", Security.lockSeconds()) },
                            Response.Status.TOO_MANY_REQUESTS,
                        )
                        Security.Check.BAD -> return json(
                            buildJsonObject { put("ok", false); put("error", "bad_pin"); put("left", Security.attemptsLeft()) },
                            Response.Status.FORBIDDEN,
                        )
                    }
                }
                when (arg("action")) {
                    "set" -> {
                        val pin = arg("pin")
                        if (!Security.validFormat(pin)) return bad("bad_format")
                        json(buildJsonObject { put("ok", true); put("session", Security.setPin(pin!!)) })
                    }
                    "disable" -> { Security.clearPin(); ok() }
                    else -> bad("action debe ser set o disable")
                }
            }

            "/api/launch" -> {
                val pkg = installedPackage(arg("pkg")) ?: return bad("App no instalada")
                json(buildJsonObject { put("ok", launchOnMain(pkg)) })
            }

            "/api/fire-settings" -> {
                // Solo las pantallas de la lista cerrada FireSettings.PAGES
                val page = FireSettings.byId(arg("page"))?.id ?: return bad("Pantalla desconocida")
                json(buildJsonObject { put("ok", onMain { Apps.openFireSettings(ctx, page) }) })
            }

            "/api/key" -> {
                // Solo teclas de la lista fija
                val name = arg("key")?.lowercase()?.takeIf { it in Remote.KEYS } ?: return bad("Tecla desconocida")
                val times = (arg("times")?.toIntOrNull() ?: 1).coerceIn(1, 20)
                var done = true
                repeat(times) { done = Remote.key(ctx, name) && done }
                json(buildJsonObject { put("ok", done) })
            }

            "/api/text" -> {
                val value = arg("text").orEmpty().take(200)
                if (value.isBlank()) return bad("Falta text")
                json(buildJsonObject { put("ok", Remote.text(value)) })
            }

            "/api/power" -> {
                val done = when (arg("action")) {
                    "on" -> Remote.wake(ctx)
                    "off" -> Remote.sleep()
                    else -> return bad("action debe ser on u off")
                }
                json(buildJsonObject { put("ok", done) })
            }

            "/api/config" -> {
                if (s.method == Method.POST) {
                    // Desactivar Aura como launcher tambien pide el codigo
                    val turningOff = body["launcherEnabled"]?.jsonPrimitive?.contentOrNull == "false" && Aura.config.state.value.launcherEnabled
                    if (turningOff) pinConfirm(s)?.let { return it }
                    Aura.config.patch(body)
                    Aura.scope.launch { Aura.refreshWeather() }
                }
                json(publicConfig())
            }

            "/api/app-icon" -> {
                val pkg = installedPackage(arg("pkg")) ?: return text("No existe", Response.Status.NOT_FOUND)
                val entry = Apps.load(ctx).first { it.pkg == pkg }
                bytes(Apps.iconPng(ctx, entry) ?: return text("Sin icono", Response.Status.NOT_FOUND), "image/png")
            }

            "/api/icon" -> {
                val pkg = installedPackage(arg("pkg")) ?: return bad("App no instalada")
                if (arg("reset") == "1") {
                    Apps.customIconFile(ctx, pkg).delete()
                } else {
                    val tmp = files["file"] ?: return bad("Falta el archivo")
                    if (!Apps.saveCustomIcon(ctx, pkg, File(tmp))) return bad("Imagen no valida")
                }
                Aura.assetsVersion.value++
                ok()
            }

            "/api/wallpaper-thumb" -> {
                val id = knownWallpaper(arg("id")) ?: return text("No existe", Response.Status.NOT_FOUND)
                bytes(Wallpapers.thumbnail(ctx, id) ?: return text("No existe", Response.Status.NOT_FOUND), "image/jpeg")
            }

            "/api/wallpaper" -> {
                if (arg("delete") == "1") {
                    val id = knownWallpaper(arg("id")) ?: return bad("Fondo desconocido")
                    Wallpapers.delete(ctx, id)
                    Aura.assetsVersion.value++
                    ok()
                } else {
                    val tmp = files["file"] ?: return bad("Falta el archivo")
                    val id = Wallpapers.saveUpload(ctx, File(tmp), params["file"] ?: "fondo.jpg") ?: return bad("Imagen no valida")
                    Aura.assetsVersion.value++
                    json(buildJsonObject { put("ok", true); put("id", id) })
                }
            }

            "/api/tweaks" -> {
                // Lista cerrada de SystemTweaks: solo id + bloqueado si/no
                if (s.method == Method.POST) {
                    if (arg("all") == "1" || arg("all") == "true") {
                        SystemTweaks.applyAll(ctx)
                    } else {
                        val t = SystemTweaks.byId(arg("id")) ?: return bad("Ajuste desconocido")
                        val blocked = arg("blocked") == "true" || arg("blocked") == "1"
                        if (!SystemTweaks.apply(ctx, t, blocked)) return bad("Sin permiso para cambiarlo")
                    }
                }
                json(tweaksJson())
            }

            "/api/update" -> {
                // Actualizaciones de Aura desde las Releases de GitHub (solo el repositorio oficial)
                if (s.method == Method.POST) {
                    when (arg("action")) {
                        "check" -> Updater.checkNow()
                        "install" -> if (!Updater.install(ctx)) return bad("No hay ninguna version nueva lista para instalar")
                        "later" -> Updater.later()
                        else -> return bad("action debe ser check, install o later")
                    }
                }
                json(AuraJson.encodeToJsonElement(Updater.State.serializer(), Updater.state.value).jsonObject)
            }

            "/api/onboarding" -> {
                // Vuelve a mostrar la bienvenida en la TV
                Aura.config.update { it.copy(onboardingDone = false) }
                json(buildJsonObject { put("ok", onMain { Apps.bringAuraToFront(ctx) }) })
            }

            "/api/screensaver" -> json(buildJsonObject { put("ok", onMain { Screensaver.preview(ctx) }) })

            "/api/video" -> {
                when {
                    arg("delete") == "1" -> {
                        val id = knownWallpaper(arg("id"))?.takeIf { it.startsWith("video:") } ?: return bad("Video desconocido")
                        Wallpapers.delete(ctx, id)
                        Aura.assetsVersion.value++
                        ok()
                    }
                    files["file"] != null -> {
                        val v = Videos.saveUpload(ctx, File(files.getValue("file")), params["file"] ?: "video.mp4")
                            ?: return bad("Video no valido (mp4, webm, mkv o mov)")
                        Aura.assetsVersion.value++
                        json(buildJsonObject { put("ok", true); put("id", Videos.wallpaperId(v)) })
                    }
                    else -> {
                        val v = Videos.addUrl(arg("url").orEmpty(), arg("name")) ?: return bad("Enlace no valido")
                        // La miniatura se prepara en segundo plano
                        Aura.scope.launch { runCatching { Videos.poster(ctx, v) }; Aura.assetsVersion.value++ }
                        json(buildJsonObject { put("ok", true); put("id", Videos.wallpaperId(v)) })
                    }
                }
            }

            "/api/install" -> {
                // Sin cuerpo: estado actual. Con archivo: APK subido. Con url: descarga e instala.
                if (s.method == Method.POST) {
                    val tmp = files["file"]
                    val started = when {
                        tmp != null -> {
                            val name = params["file"] ?: "app.apk"
                            if (!name.lowercase().endsWith(".apk")) return bad("Solo archivos .apk")
                            Installer.installUpload(ctx, File(tmp), name)
                        }
                        !arg("url").isNullOrBlank() -> Installer.installUrl(ctx, arg("url")!!)
                        else -> return bad("Falta el archivo o el enlace")
                    }
                    if (!started) return bad(if (Installer.busy()) "Ya hay una instalación en curso" else "Enlace no válido")
                }
                json(AuraJson.encodeToJsonElement(Installer.Status.serializer(), Installer.current()).jsonObject)
            }

            // La web comprueba el codigo antes de empezar a subir un APK grande
            "/api/verify-pin" -> pinConfirm(s) ?: ok()

            "/api/uninstall" -> {
                val pkg = installedPackage(arg("pkg"))?.takeIf { Installer.removable(ctx, it) } ?: return bad("Esta app no se puede desinstalar")
                val label = Apps.load(ctx).firstOrNull { it.pkg == pkg }?.label ?: pkg
                json(buildJsonObject { put("ok", onMain { Installer.uninstall(ctx, pkg, label) }) })
            }

            "/api/appstore" -> {
                val query = arg("q")?.trim().orEmpty()
                if (query.isEmpty()) return bad("Falta q")
                json(buildJsonObject { put("ok", onMain { Installer.appstoreSearch(ctx, query) }) })
            }

            "/api/screenshot" -> bytes(Remote.screenshot() ?: return bad("Captura no disponible"), "image/png")

            else -> text("No encontrado", Response.Status.NOT_FOUND)
        }
    }

    /**
     * Confirmacion con el codigo para acciones delicadas. Sin codigo activado no hace nada (null = seguir).
     * Sin cabecera X-Pin devuelve pin_confirm (la web abre la ventana para pedirlo) y no cuenta como fallo.
     */
    private fun pinConfirm(s: IHTTPSession): Response? {
        if (!Security.enabled()) return null
        val pin = s.headers["x-pin"]?.trim()
        if (pin.isNullOrEmpty()) return json(buildJsonObject { put("ok", false); put("error", "pin_confirm") }, Response.Status.FORBIDDEN)
        return when (Security.check(pin)) {
            Security.Check.OK -> null
            Security.Check.LOCKED -> json(
                buildJsonObject { put("ok", false); put("error", "locked"); put("seconds", Security.lockSeconds()) },
                Response.Status.TOO_MANY_REQUESTS,
            )
            Security.Check.BAD -> json(
                buildJsonObject { put("ok", false); put("error", "bad_pin"); put("left", Security.attemptsLeft()) },
                Response.Status.FORBIDDEN,
            )
        }
    }

    /** Devuelve el paquete solo si es una app instalada (o el atajo de Ajustes). */
    private fun installedPackage(pkg: String?): String? {
        if (pkg == null) return null
        if (pkg == Aura.SETTINGS_PKG) return pkg
        return Apps.load(ctx).firstOrNull { it.pkg == pkg }?.pkg
    }

    private fun knownWallpaper(id: String?): String? = id?.takeIf { it in Wallpapers.list(ctx) }

    private fun launchOnMain(pkg: String): Boolean = onMain { Apps.launch(ctx, pkg) }

    private fun onMain(block: () -> Boolean): Boolean {
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { result = runCatching(block).getOrDefault(false); latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    private fun publicConfig(): JsonObject =
        JsonObject(AuraJson.encodeToJsonElement(AuraConfig.serializer(), Aura.config.state.value).jsonObject.filterKeys { it != "token" && it != "pinHash" })

    private fun state(): JsonObject {
        val cfg = Aura.config.state.value
        val apps = Apps.load(ctx)
        val (pinned, others) = Apps.rows(apps, cfg)
        val w = Aura.weather.value
        return buildJsonObject {
            put("ip", WebService.localIp())
            put("port", Aura.WEB_PORT)
            put("focused", Aura.focusedPkg.value)
            put("screenOn", Remote.isScreenOn(ctx))
            put("remoteReady", HomeWatcherService.instance != null)
            put("currentWallpaper", Aura.currentWallpaper.value)
            put("config", publicConfig())
            putJsonArray("pinned") { pinned.forEach { add(it.pkg) } }
            putJsonArray("others") { others.forEach { add(it.pkg) } }
            putJsonArray("apps") {
                apps.forEach { a ->
                    addJsonObject {
                        put("pkg", a.pkg)
                        put("label", a.label)
                        put("pinned", a.pkg in cfg.pinned)
                        put("hidden", a.pkg in cfg.hidden)
                        put("customIcon", Apps.customIconFile(ctx, a.pkg).exists())
                        put("removable", Installer.removable(ctx, a.pkg))
                    }
                }
            }
            putJsonArray("wallpapers") { Wallpapers.list(ctx).forEach { add(it) } }
            put("tweaks", tweaksJson())
            put("pinEnabled", Security.enabled())
            put("version", BuildConfig.VERSION_NAME)
            put("update", AuraJson.encodeToJsonElement(Updater.State.serializer(), Updater.state.value))
            putJsonArray("fireSettings") {
                FireSettings.PAGES.forEach { p ->
                    addJsonObject { put("id", p.id); put("title", p.title(cfg.language)); put("sub", p.sub(cfg.language)) }
                }
            }
            if (w != null) putJsonObject("weather") {
                put("temp", w.temp); put("icon", w.iconName); put("description", w.description(cfg.language))
                put("max", JsonPrimitive(w.max)); put("min", JsonPrimitive(w.min)); put("place", w.place); put("unit", w.unit)
            }
        }
    }

    private fun tweaksJson(): JsonObject {
        val lang = Aura.config.state.value.language
        return buildJsonObject {
            put("canSecure", SystemTweaks.canSecure(ctx))
            put("canSystem", SystemTweaks.canSystem(ctx))
            put("canDnd", SystemTweaks.canDnd(ctx))
            putJsonArray("items") {
                SystemTweaks.ALL.forEach { t ->
                    addJsonObject {
                        put("id", t.id)
                        put("group", SystemTweaks.groupTitle(t.group, lang))
                        put("title", t.title(lang))
                        put("sub", t.sub(lang))
                        put("manual", t.manual)
                        put("inverted", t.inverted)
                        put("allowed", SystemTweaks.allowed(ctx, t))
                        put("blocked", SystemTweaks.isBlocked(ctx, t))
                    }
                }
            }
        }
    }

    // ---------- helpers ----------
    private fun cors(r: Response): Response = r.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Content-Type, X-Token")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Cache-Control", "no-store")
    }

    private fun json(obj: JsonObject, status: Response.Status = Response.Status.OK) =
        cors(newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString()))

    private fun ok() = json(buildJsonObject { put("ok", true) })
    private fun bad(msg: String) = json(buildJsonObject { put("ok", false); put("error", msg) }, Response.Status.BAD_REQUEST)
    private fun text(msg: String, status: Response.Status) = cors(newFixedLengthResponse(status, MIME_PLAINTEXT, msg))
    private fun bytes(data: ByteArray, mime: String) =
        cors(newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(data), data.size.toLong()))

    private fun asset(path: String, mime: String): Response = bytes(ctx.assets.open(path).use { it.readBytes() }, mime)

    companion object {
        /** Archivos publicos del panel (PWA). Lista cerrada: no se sirve nada mas del APK. */
        private val STATIC = mapOf(
            "/index.html" to ("web/index.html" to "text/html; charset=utf-8"),
            "/icons.js" to ("web/icons.js" to "application/javascript; charset=utf-8"),
            "/manifest.webmanifest" to ("web/manifest.webmanifest" to "application/manifest+json"),
            "/sw.js" to ("web/sw.js" to "application/javascript; charset=utf-8"),
            "/icon-192.png" to ("web/icon-192.png" to "image/png"),
            "/icon-512.png" to ("web/icon-512.png" to "image/png"),
            "/apple-touch-icon.png" to ("web/icon-192.png" to "image/png"),
            "/credits.json" to ("credits.json" to "application/json; charset=utf-8"),
        )
    }
}
