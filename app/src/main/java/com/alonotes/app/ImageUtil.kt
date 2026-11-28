package com.alonotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/** Decodificación, reducción y guardado de imágenes (íconos de carpeta y fondos). */
internal object ImageUtil {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun sampleFor(w: Int, h: Int, maxPx: Int): Int {
        var s = 1
        while (w / (s * 2) >= maxPx || h / (s * 2) >= maxPx) s *= 2
        return s
    }

    /** El bitmap ya decodificado de [path] a [maxPx], si está en la caché (no decodifica nada). */
    fun peek(path: String, maxPx: Int): Bitmap? = cache.get("$path@$maxPx")

    /** Saca de la caché todo lo decodificado de [path] (por ejemplo, al borrar o reemplazar el archivo). */
    fun forget(path: String) {
        for (k in cache.snapshot().keys) if (k.startsWith("$path@")) cache.remove(k)
    }

    /** Lee un archivo reducido (con caché). null si falla. */
    fun decodeFile(path: String, maxPx: Int): Bitmap? {
        cache.get("$path@$maxPx")?.let { return it }
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sampleFor(o.outWidth, o.outHeight, maxPx)
            val bm = BitmapFactory.decodeFile(path, o2)
            if (bm != null) cache.put("$path@$maxPx", bm)
            bm
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Copia una imagen elegida por el usuario a [dest] (sin extensión): reducida a [maxPx],
     * respetando EXIF. JPEG de calidad [jpegQ], o PNG si tiene transparencia y [pngIfAlpha].
     * Devuelve el archivo creado o null. Llamar fuera del hilo principal.
     */
    fun saveFromUri(ctx: Context, uri: Uri, dest: File, maxPx: Int, jpegQ: Int, pngIfAlpha: Boolean): File? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sampleFor(o.outWidth, o.outHeight, maxPx)
            var bm = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
                ?: return null
            var orient = 1
            try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    orient = android.media.ExifInterface(it)
                        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
                }
            } catch (e: Exception) {
                // Sin EXIF legible: se deja tal cual.
            }
            val deg = when (orient) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
            val m = Matrix()
            if (deg != 0f) m.postRotate(deg)
            val big = maxOf(bm.width, bm.height)
            if (big > maxPx) {
                val k = maxPx.toFloat() / big
                m.postScale(k, k)
            }
            if (!m.isIdentity) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
            val png = pngIfAlpha && bm.hasAlpha()
            val f = File(dest.parentFile, dest.name + if (png) ".png" else ".jpg")
            f.parentFile?.mkdirs()
            FileOutputStream(f).use {
                bm.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, jpegQ, it)
            }
            return f
        } catch (e: Throwable) {
            return null
        }
    }

    /** Copia cruda (para videos y fuentes). Llamar fuera del hilo principal. */
    fun copy(ctx: Context, uri: Uri, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            ctx.contentResolver.openInputStream(uri)?.use { i ->
                FileOutputStream(dest).use { o -> i.copyTo(o) }
            } ?: return false
            true
        } catch (e: Throwable) {
            dest.delete()
            false
        }
    }
}
