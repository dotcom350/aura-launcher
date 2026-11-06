package com.david.aura

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import java.io.File

data class AppEntry(
    val pkg: String,
    val label: String,
    val component: ComponentName?,
    val banner: Drawable?,
    val icon: Drawable?,
)

/** Imagen de una baldosa: si es "banner" ocupa toda la tarjeta; si no, se centra el icono. */
class TileImage(val bitmap: Bitmap, val fill: Boolean)

object Apps {
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName || found.containsKey(ai.packageName)) continue
                val banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
                found[ai.packageName] = AppEntry(
                    pkg = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    component = ComponentName(ai.packageName, ai.name),
                    banner = banner,
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                )
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun settingsEntry(lang: String) = AppEntry(Aura.SETTINGS_PKG, if (lang == "en") "Settings" else "Ajustes", null, null, null)
    fun allAppsEntry(lang: String) = AppEntry(Aura.ALL_APPS_PKG, if (lang == "en") "All apps" else "Todas las apps", null, null, null)

    /** Fila principal (orden configurado + Todas las apps + Ajustes) y resto de apps visibles. */
    fun rows(apps: List<AppEntry>, cfg: AuraConfig): Pair<List<AppEntry>, List<AppEntry>> {
        val byPkg = apps.associateBy { it.pkg }
        val pinned = cfg.pinned.mapNotNull { byPkg[it] }.filter { it.pkg !in cfg.hidden } +
            allAppsEntry(cfg.language) + settingsEntry(cfg.language)
        val pinnedSet = pinned.map { it.pkg }.toSet()
        val others = apps.filter { it.pkg !in pinnedSet && it.pkg !in cfg.hidden }
        return pinned to others
    }

    fun customIconFile(ctx: Context, pkg: String) =
        File(File(ctx.filesDir, "icons").apply { mkdirs() }, "${pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png")

    fun tileImage(ctx: Context, entry: AppEntry): TileImage? {
        val custom = customIconFile(ctx, entry.pkg)
        if (custom.exists()) BitmapFactory.decodeFile(custom.path)?.let { return TileImage(it, fill = true) }
        entry.banner?.let { b -> return TileImage(b.toBitmap(320, 180), fill = true) }
        entry.icon?.let { i -> return TileImage(i.toBitmap(192, 192), fill = false) }
        return null
    }

    fun iconPng(ctx: Context, entry: AppEntry): ByteArray? {
        val img = tileImage(ctx, entry) ?: return null
        return ByteArrayOutputStream().also { img.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    fun saveCustomIcon(ctx: Context, pkg: String, tmp: File): Boolean {
        val bmp = BitmapFactory.decodeFile(tmp.path) ?: return false
        val scale = minOf(1f, 640f / bmp.width)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        customIconFile(ctx, pkg).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }

    /** Abre una pantalla de los ajustes del Fire TV (lista cerrada, ver [FireSettings]). */
    fun openFireSettings(ctx: Context, page: String): Boolean {
        val p = FireSettings.byId(page) ?: return false
        val intent = Intent(p.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent); true }.getOrElse {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
    }

    fun bringAuraToFront(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        true
    }.getOrDefault(false)

    fun launch(ctx: Context, pkg: String): Boolean {
        val intent = if (pkg == Aura.SETTINGS_PKG) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            val pm = ctx.packageManager
            pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent); true }.getOrElse { false }
    }
}
