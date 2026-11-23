package com.alonotes.app

import android.content.Context
import android.graphics.RectF
import java.io.File
import java.util.UUID

/** Un objeto copiado: su archivo (copia propia en filesDir/clipboard_objects/) y su geometría relativa al centro del grupo. */
internal class ClipObj(
    val file: File,
    val dx: Float,
    val dy: Float,
    val w: Float,
    val h: Float,
    val rot: Float,
    val baseW: Float,
    val fh: Boolean,
    val fv: Boolean,
    val op: Float,
    val lt: Int,
    val ld: String
)

/**
 * Portapapeles de objetos (imágenes) de una hoja. Copiar guarda las propiedades y una copia de cada archivo; pegar crea
 * objetos NUEVOS (id nuevo y archivo nuevo en images/<idNota>/), así que no comparten nada con los originales y se
 * pueden pegar varias veces, también en otra hoja o nota. Los pegados nacen desbloqueados.
 */
internal object ObjectClipboard {
    private var objs: List<ClipObj> = emptyList()
    private var pastes = 0

    val isEmpty: Boolean get() = objs.isEmpty()

    val size: Int get() = objs.size

    private fun dir(c: Context) = File(c.filesDir, "clipboard_objects")

    /** Reemplaza el contenido del portapapeles con [items] (no modifica los originales). */
    fun copy(c: Context, items: List<ImageItem>) {
        val d = dir(c)
        try {
            d.deleteRecursively()
        } catch (ignored: Exception) {
            // Se sobrescribe lo que haga falta.
        }
        d.mkdirs()
        // Centro del grupo: el de la caja que abarca a todos (con sus giros), igual que el recuadro de selección.
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (im in items) {
            val b = im.bounds
            for (k in 0 until 4) {
                val p = Xf.toPage(
                    b.centerX(), b.centerY(), im.rotation,
                    (if (k % 2 == 0) -1 else 1) * b.width() / 2f, (if (k < 2) -1 else 1) * b.height() / 2f
                )
                if (p[0] < x0) x0 = p[0]
                if (p[0] > x1) x1 = p[0]
                if (p[1] < y0) y0 = p[1]
                if (p[1] > y1) y1 = p[1]
            }
        }
        val gx = (x0 + x1) / 2f
        val gy = (y0 + y1) / 2f
        val out = ArrayList<ClipObj>()
        var n = 0
        for (im in items) {
            if (!im.file.exists()) continue
            val dst = File(d, (n++).toString() + "_" + im.file.name)
            try {
                im.file.copyTo(dst, true)
            } catch (e: Exception) {
                continue
            }
            val b = im.bounds
            out.add(
                ClipObj(
                    dst, b.centerX() - gx, b.centerY() - gy, b.width(), b.height(), im.rotation, im.baseW,
                    im.isFlippedH, im.isFlippedV, im.opacity, im.linkType, im.linkData
                )
            )
        }
        objs = out
        pastes = 0
    }

    /**
     * Crea los objetos copiados centrados en ([cx], [cy]) (mm de la hoja). Cada pegado siguiente se corre 10 mm hacia
     * abajo y a la derecha, para que se vea que son copias. Devuelve la lista (vacía si no se pudo copiar ningún archivo).
     */
    fun paste(c: Context, docId: String, cx: Float, cy: Float): List<ImageItem> {
        val imgDir = File(c.filesDir, "images/$docId")
        imgDir.mkdirs()
        val off = 10f * pastes
        val out = ArrayList<ImageItem>()
        for (o in objs) {
            if (!o.file.exists()) continue
            val id = UUID.randomUUID().toString()
            val ext = o.file.extension
            val dst = File(imgDir, if (ext.isEmpty()) id else "$id.$ext")
            try {
                o.file.copyTo(dst, true)
            } catch (e: Exception) {
                continue
            }
            val mx = cx + o.dx + off
            val my = cy + o.dy + off
            out.add(
                ImageItem(
                    id, dst, RectF(mx - o.w / 2f, my - o.h / 2f, mx + o.w / 2f, my + o.h / 2f), o.rot, o.baseW,
                    o.fh, o.fv, o.op, o.lt, o.ld, false
                )
            )
        }
        if (out.isNotEmpty()) pastes++
        return out
    }
}
