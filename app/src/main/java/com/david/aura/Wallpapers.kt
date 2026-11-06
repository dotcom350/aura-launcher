package com.david.aura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Calendar

class WallpaperImages(val full: ImageBitmap, val blur: ImageBitmap)

object Wallpapers {
    private const val ASSET_DIR = "wallpapers"
    private val cache = LruCache<String, WallpaperImages>(3)
    private val thumbs = LruCache<String, ByteArray>(40)

    private fun userDir(ctx: Context) = File(ctx.filesDir, "wallpapers").apply { mkdirs() }

    /** Ids: "asset:nombre.jpg" (incluidos), "file:nombre.jpg" (subidos desde la web) o "video:id" (fondos de video). */
    fun list(ctx: Context): List<String> {
        val assets = ctx.assets.list(ASSET_DIR).orEmpty().sorted().map { "asset:$it" }
        val files = userDir(ctx).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.map { "file:${it.name}" }
        val videos = Aura.config.state.value.videos.map { Videos.wallpaperId(it) }
        return assets + files + videos
    }

    private fun open(ctx: Context, id: String): InputStream? = runCatching {
        when {
            id.startsWith("asset:") -> ctx.assets.open("$ASSET_DIR/${id.removePrefix("asset:")}")
            id.startsWith("file:") -> File(userDir(ctx), id.removePrefix("file:").substringAfterLast('/')).inputStream()
            // Los videos usan su imagen fija (miniatura o fotograma) para el desenfoque y como respaldo
            id.startsWith("video:") -> Videos.byWallpaperId(id)?.let { Videos.poster(ctx, it).inputStream() }
            else -> null
        }
    }.getOrNull()

    private fun decode(ctx: Context, id: String, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Con inJustDecodeBounds decodeStream devuelve null siempre: solo rellena outWidth/outHeight
        val stream = open(ctx, id) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open(ctx, id)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Recorta al centro a 16:9 y escala exactamente a w x h. */
    private fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        val target = w.toFloat() / h
        val srcRatio = src.width.toFloat() / src.height
        val crop = if (srcRatio > target) {
            val cw = (src.height * target).toInt()
            Rect((src.width - cw) / 2, 0, (src.width - cw) / 2 + cw, src.height)
        } else {
            val ch = (src.width / target).toInt()
            Rect(0, (src.height - ch) / 2, src.width, (src.height - ch) / 2 + ch)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, crop, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun load(ctx: Context, id: String): WallpaperImages? {
        cache.get(id)?.let { return it }
        val raw = decode(ctx, id, 1920, 1080) ?: return null
        val full = cover(raw, 1920, 1080)
        if (raw !== full) raw.recycle()
        val small = Bitmap.createScaledBitmap(full, 160, 90, true)
        val blurred = boxBlur(small, radius = 3, passes = 3)
        val images = WallpaperImages(full.asImageBitmap(), blurred.asImageBitmap())
        cache.put(id, images)
        return images
    }

    fun thumbnail(ctx: Context, id: String): ByteArray? {
        thumbs.get(id)?.let { return it }
        val raw = decode(ctx, id, 480, 270) ?: return null
        val t = cover(raw, 384, 216)
        val bytes = ByteArrayOutputStream().also { t.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        thumbs.put(id, bytes)
        return bytes
    }

    /** Guarda una imagen subida (cualquier tamano) como 1920x1080 JPEG. */
    fun saveUpload(ctx: Context, tmp: File, originalName: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        val raw = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = cover(raw, 1920, 1080)
        val base = originalName.substringBeforeLast('.').lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifBlank { "fondo" }.take(40)
        var name = "$base.jpg"
        var i = 2
        while (File(userDir(ctx), name).exists()) name = "${base}_${i++}.jpg"
        File(userDir(ctx), name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return "file:$name"
    }

    fun delete(ctx: Context, id: String): Boolean {
        if (id.startsWith("video:")) { cache.remove(id); thumbs.remove(id); return Videos.delete(ctx, id) }
        if (!id.startsWith("file:")) return false
        cache.remove(id); thumbs.remove(id)
        return File(userDir(ctx), id.removePrefix("file:").substringAfterLast('/')).delete()
    }

    fun slotFor(cfg: AuraConfig, hour: Int): String = when {
        hour >= cfg.dayStart && hour < 12 -> "morning"
        hour >= 12 && hour < cfg.nightStart -> "afternoon"
        hour >= cfg.nightStart && hour < 21 -> "evening"
        else -> "night"
    }

    /** Decide que fondo mostrar segun el modo configurado. */
    fun pick(ctx: Context, cfg: AuraConfig, now: Calendar, focusedPkg: String? = null): String? {
        val available = list(ctx)
        if (available.isEmpty()) return null
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val step = (now.timeInMillis / 60_000L) / cfg.rotateMinutes.coerceAtLeast(1)
        val slotList = cfg.slots[slotFor(cfg, hour)].orEmpty().filter { it in available }.ifEmpty { available }

        fun fromSlot(): String = slotList[(step % slotList.size).toInt()]

        /** Cada app tiene su propio fondo dentro de la franja horaria actual. */
        fun forApp(): String {
            val pkg = focusedPkg ?: return fromSlot()
            cfg.appWallpapers[pkg]?.takeIf { it in available }?.let { return it }
            val index = cfg.pinned.indexOf(pkg).takeIf { it >= 0 } ?: (pkg.hashCode() and 0x7fffffff)
            return slotList[index % slotList.size]
        }

        return when (cfg.wallpaperMode) {
            "app" -> forApp()
            "fixed" -> cfg.fixedWallpaper.takeIf { it in available } ?: fromSlot()
            "rotate" -> available[(step % available.size).toInt()]
            else -> fromSlot()
        }
    }

    /** Desenfoque de caja (3 pasadas ~ gaussiano) sobre un bitmap pequeno. */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(passes) {
            blurPass(px, tmp, w, h, radius, horizontal = true)
            blurPass(tmp, px, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blurPass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) line * w + c else c * w + line }
            for (i in -r..r) { val p = input[idx(i)]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
            for (i in 0 until len) {
                output[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = input[idx(i + r + 1)]; val rem = input[idx(i - r)]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}
