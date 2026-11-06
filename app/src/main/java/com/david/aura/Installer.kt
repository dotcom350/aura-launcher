package com.david.aura

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom

/**
 * Instalar y desinstalar apps desde la web.
 *
 * Aura no puede instalar en silencio (eso solo lo pueden hacer apps del sistema): prepara el APK y
 * Fire OS muestra su ventana de confirmacion en la TV, que se acepta con el mando (o el mando web).
 * Permisos por ADB (deploy.ps1): appops REQUEST_INSTALL_PACKAGES allow.
 */
object Installer {
    const val MAX_APK = 300L * 1024 * 1024
    private const val ACTION_RESULT = "com.david.aura.INSTALL_RESULT"

    /**
     * phase: idle | downloading | installing | confirm | done | failed | uninstall_confirm | uninstalled
     * progress: 0-100 o -1 si no se sabe.
     */
    @Serializable
    data class Status(
        val phase: String = "idle",
        val label: String = "",
        val pkg: String? = null,
        val progress: Int = -1,
        val message: String = "",
        val ts: Long = System.currentTimeMillis(),
    )

    val status = MutableStateFlow(Status())
    private var uninstalling: String? = null

    private fun set(s: Status) { status.value = s.copy(ts = System.currentTimeMillis()) }

    /**
     * Estado para la web. Si se esta esperando confirmacion y la ventana del instalador ya no esta delante
     * (se cancelo con Atras), se marca como cancelada para que la web no se quede esperando.
     */
    fun current(): Status {
        val st = status.value
        if (st.phase in setOf("confirm", "uninstall_confirm") && System.currentTimeMillis() - st.ts > 3000) {
            val svcUp = HomeWatcherService.instance != null
            if (svcUp && AutoConfirm.installerRoot() == null) {
                set(st.copy(phase = "failed", message = "Cancelada"))
            }
        }
        return status.value
    }

    fun busy() = status.value.phase in setOf("downloading", "installing")

    private fun apkDir(ctx: Context) = File(ctx.cacheDir, "apks").apply { mkdirs() }
    private fun newApk(ctx: Context) =
        File(apkDir(ctx), ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } + ".apk")

    // ---------- instalar ----------

    /** APK subido desde la web (archivo temporal de NanoHTTPD). */
    fun installUpload(ctx: Context, tmp: File, name: String): Boolean {
        if (busy()) return false
        val apk = newApk(ctx)
        if (!tmp.renameTo(apk)) tmp.copyTo(apk, overwrite = true)
        set(Status("installing", name.substringBeforeLast('.')))
        Aura.scope.launch { install(ctx, apk) }
        return true
    }

    /** APK ya descargado en el dispositivo (lo usa [Updater]). El estado pasa a "installing" al momento. */
    fun installFile(ctx: Context, apk: File): Boolean {
        if (busy()) return false
        set(Status("installing", "Aura"))
        Aura.scope.launch { install(ctx, apk) }
        return true
    }

    /** Descarga un APK de un enlace http/https y lo instala. */
    fun installUrl(ctx: Context, url: String): Boolean {
        if (busy()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
        val label = uri.path.orEmpty().substringAfterLast('/').substringBeforeLast('.').ifBlank { uri.host }
        set(Status("downloading", label, progress = 0))
        Aura.scope.launch {
            val apk = newApk(ctx)
            val ok = runCatching { download(url.trim(), apk, label) }.getOrElse { e ->
                set(Status("failed", label, message = "Descarga fallida: ${e.message ?: e.javaClass.simpleName}")); false
            }
            if (ok) install(ctx, apk) else apk.delete()
        }
        return true
    }

    private fun download(url: String, dest: File, label: String): Boolean {
        var current = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Aura-FireTV")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                val next = conn.getHeaderField("Location") ?: break
                current = URL(current, next)
                if (current.protocol !in setOf("http", "https")) error("redireccion no valida")
                conn.disconnect(); redirects++; continue
            }
            if (code != 200) error("HTTP $code")
            break
        }
        val total = conn.contentLengthLong
        if (total > MAX_APK) error("el archivo pasa de 300 MB")
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (read > MAX_APK) error("el archivo pasa de 300 MB")
                    val pct = if (total > 0) (read * 100 / total).toInt() else -1
                    if (pct != lastPct) { lastPct = pct; set(Status("downloading", label, progress = pct)) }
                }
            }
        }
        conn.disconnect()
        return true
    }

    private fun install(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0)
        if (info == null) {
            apk.delete()
            set(Status("failed", status.value.label, message = "No es un APK válido"))
            return
        }
        val app = info.applicationInfo
        val label = app?.let { it.sourceDir = apk.path; it.publicSourceDir = apk.path; runCatching { it.loadLabel(pm).toString() }.getOrNull() }
            ?: info.packageName
        set(Status("installing", label, info.packageName, 100))
        runCatching {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(info.packageName); setSize(apk.length()) }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val intent = Intent(ACTION_RESULT).setPackage(ctx.packageName)
                    .putExtra("label", label).putExtra("pkg", info.packageName).putExtra("apk", apk.path)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
        }.onFailure {
            apk.delete()
            set(Status("failed", label, info.packageName, message = it.message ?: it.javaClass.simpleName))
        }
    }

    /** Resultado de la sesion de instalacion (registrado en [Aura.init]). */
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val label = intent.getStringExtra("label").orEmpty()
            val pkg = intent.getStringExtra("pkg")
            when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    set(Status("confirm", label, pkg, 100))
                    AutoConfirm.arm()
                    runCatching { ctx.startActivity(confirm!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { set(Status("failed", label, pkg, message = "No se pudo mostrar la confirmación en la TV")) }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    intent.getStringExtra("apk")?.let { File(it).delete() }
                    set(Status("done", label, pkg, 100))
                    Aura.appsVersion.value++
                }
                else -> {
                    intent.getStringExtra("apk")?.let { File(it).delete() }
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    set(Status("failed", label, pkg, message = if (code == PackageInstaller.STATUS_FAILURE_ABORTED) "Cancelada" else msg ?: "Error $code"))
                }
            }
        }
    }

    /** Avisa de altas/bajas de paquetes (para refrescar la lista y saber si se desinstalo). */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            Aura.appsVersion.value++
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && pkg != null && pkg == uninstalling &&
                !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                uninstalling = null
                set(Status("uninstalled", status.value.label, pkg))
            }
        }
    }

    fun register(ctx: Context) {
        // No exportado: solo el propio sistema (via el PendingIntent de Aura) puede mandar el resultado.
        // Si fuera publico, otra app podria pedir a Aura que abriera cualquier pantalla.
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, resultReceiver, IntentFilter(ACTION_RESULT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ctx.registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        })
        apkDir(ctx).listFiles()?.forEach { it.delete() }
    }

    // ---------- desinstalar ----------

    /** Solo apps instaladas por el usuario (las del sistema solo se pueden desactivar por ADB). */
    fun removable(ctx: Context, pkg: String): Boolean = runCatching {
        if (pkg == ctx.packageName) return false
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ai.flags and ApplicationInfo.FLAG_SYSTEM == 0
    }.getOrDefault(false)

    fun uninstall(ctx: Context, pkg: String, label: String): Boolean {
        if (!removable(ctx, pkg)) return false
        uninstalling = pkg
        set(Status("uninstall_confirm", label, pkg))
        AutoConfirm.arm()
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent); true }.getOrElse {
            set(Status("failed", label, pkg, message = "No se pudo abrir la desinstalación")); false
        }
    }

    /** Abre la búsqueda de la Appstore de Amazon en la TV. */
    fun appstoreSearch(ctx: Context, query: String): Boolean = runCatching {
        val q = query.trim().take(80)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?s=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/**
 * Pulsa el boton de aceptar de la ventana de instalar/desinstalar de Fire OS.
 * Solo se arma justo despues de que el usuario lo confirme en la web (ventana + codigo de seguridad),
 * durante unos segundos y solo en ventanas del instalador del sistema.
 */
object AutoConfirm {
    private const val TAG = "AuraInstall"
    private val handler = Handler(Looper.getMainLooper())
    private var until = 0L
    private var clicks = 0
    private var dumped = false
    private val IDS = setOf("android:id/button1", "com.android.packageinstaller:id/ok_button", "com.google.android.packageinstaller:id/ok_button")
    private val WORDS = setOf(
        "ok", "aceptar", "instalar", "install", "desinstalar", "uninstall", "actualizar", "update",
        "sí", "si", "yes", "confirmar", "confirm",
    )

    fun arm() {
        handler.post {
            until = SystemClock.elapsedRealtime() + 25_000
            clicks = 0; dumped = false
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 600)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() > until || clicks >= 3) return
            var next = 400L
            runCatching {
                val root = installerRoot()
                if (root != null) {
                    if (!dumped) { dumped = true; dump(root, 0) }
                    val btn = find(root)
                    if (btn != null && btn.isEnabled) {
                        val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        android.util.Log.i(TAG, "Confirmado en la TV: '${btn.text}' (${btn.viewIdResourceName}) ok=$ok")
                        if (ok) { clicks++; next = 1500L }
                    } else if (btn != null) {
                        // Algunos instaladores no activan el boton hasta llegar al final de la lista de permisos
                        scrollAll(root)
                    }
                }
            }
            handler.postDelayed(this, next)
        }
    }

    /** Ventana del instalador del sistema, aunque no sea la "activa" (los dialogos a veces no lo son). */
    fun installerRoot(): AccessibilityNodeInfo? {
        val svc = HomeWatcherService.instance ?: return null
        fun isInst(n: AccessibilityNodeInfo?) = n?.packageName?.toString()?.contains("installer", ignoreCase = true) == true
        svc.rootInActiveWindow?.takeIf(::isInst)?.let { return it }
        return runCatching { svc.windows.mapNotNull { it.root }.firstOrNull(::isInst) }.getOrNull()
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()

    private fun find(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var byText: AccessibilityNodeInfo? = null
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.viewIdResourceName in IDS) return n
            if (byText == null && label(n) in WORDS) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                byText = c
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return byText
    }

    private fun scrollAll(n: AccessibilityNodeInfo) {
        if (n.isScrollable) n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        for (i in 0 until n.childCount) n.getChild(i)?.let(::scrollAll)
    }

    private fun dump(n: AccessibilityNodeInfo, depth: Int) {
        if (depth > 12) return
        android.util.Log.d(TAG, "  ".repeat(depth) + "${n.className} id=${n.viewIdResourceName} t='${n.text ?: ""}' click=${n.isClickable} en=${n.isEnabled}")
        for (i in 0 until n.childCount) n.getChild(i)?.let { dump(it, depth + 1) }
    }
}
