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

/** Un trazo copiado: sus datos y sus puntos (x, y, presión) con x e y relativos al centro del grupo. */
internal class ClipStroke(
    val type: Int,
    val color: Int,
    val widthMm: Float,
    val pts: FloatArray,
    val opacity: Float,
    val linkType: Int,
    val linkData: String
)

/** Lo que crea un pegado: las imágenes y los trazos nuevos (para ponerlos en la capa). */
internal class Pasted(val images: List<ImageItem>, val strokes: List<Stroke>) {
    val isEmpty: Boolean get() = images.isEmpty() && strokes.isEmpty()
    val size: Int get() = images.size + strokes.size
}

/**
 * Portapapeles de objetos (imágenes y trazos) de una hoja. Copiar guarda las propiedades y una copia de cada archivo; pegar crea
 * objetos NUEVOS (id nuevo y archivo nuevo en images/<idNota>/), así que no comparten nada con los originales y se
 * pueden pegar varias veces, también en otra hoja o nota. Los pegados nacen desbloqueados. Los trazos se copian con sus
 * puntos (arreglo propio) y su opacidad; al pegarlos son trazos nuevos.
 */
internal object ObjectClipboard {
    private var objs: List<ClipObj> = emptyList()
    private var strokeObjs: List<ClipStroke> = emptyList()
    private var pastes = 0

    val isEmpty: Boolean get() = objs.isEmpty() && strokeObjs.isEmpty()

    val size: Int get() = objs.size + strokeObjs.size

    private fun dir(c: Context) = File(c.filesDir, "clipboard_objects")

    /** Reemplaza el contenido del portapapeles con [items] (imágenes y trazos; no modifica los originales). */
    fun copy(c: Context, items: List<SelectableItem>) {
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
        val outStrokes = ArrayList<ClipStroke>()
        var n = 0
        for (it in items) {
            if (it is StrokeItem) {
                val sp = it.src.pts
                val rel = FloatArray(sp.size)
                var k = 0
                while (k < sp.size) {
                    rel[k] = sp[k] - gx
                    rel[k + 1] = sp[k + 1] - gy
                    rel[k + 2] = sp[k + 2]
                    k += 3
                }
                outStrokes.add(ClipStroke(it.src.type, it.src.color, it.src.widthMm, rel, it.src.opacity, it.src.linkType, it.src.linkData))
            }
        }
        for (im in items.filterIsInstance<ImageItem>()) {
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
        strokeObjs = outStrokes
        pastes = 0
    }

    /**
     * Crea los objetos copiados centrados en ([cx], [cy]) (mm de la hoja). Cada pegado siguiente se corre 10 mm hacia
     * abajo y a la derecha, para que se vea que son copias. Devuelve la lista (vacía si no se pudo copiar ningún archivo).
     */
    fun paste(c: Context, docId: String, cx: Float, cy: Float): Pasted {
        val imgDir = File(c.filesDir, "images/$docId")
        imgDir.mkdirs()
        val off = 10f * pastes
        // Los trazos se guardan con coordenadas de 0 a 655 mm: si el grupo se saldría de la hoja, todo el pegado se corre lo justo.
        var fx = 0f
        var fy = 0f
        if (strokeObjs.isNotEmpty()) {
            var x0 = Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            for (so in strokeObjs) {
                var k = 0
                while (k < so.pts.size) {
                    val x = cx + off + so.pts[k]
                    val y = cy + off + so.pts[k + 1]
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                    k += 3
                }
            }
            val pw = cx * 2f
            val ph = cy * 2f
            if (x1 > pw) fx = pw - x1
            if (y1 > ph) fy = ph - y1
            if (x0 + fx < 0f) fx = -x0
            if (y0 + fy < 0f) fy = -y0
        }
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
            val mx = cx + o.dx + off + fx
            val my = cy + o.dy + off + fy
            out.add(
                ImageItem(
                    id, dst, RectF(mx - o.w / 2f, my - o.h / 2f, mx + o.w / 2f, my + o.h / 2f), o.rot, o.baseW,
                    o.fh, o.fv, o.op, o.lt, o.ld, false
                )
            )
        }
        val outStrokes = ArrayList<Stroke>()
        for (so in strokeObjs) {
            val pts = FloatArray(so.pts.size)
            var k = 0
            while (k < pts.size) {
                pts[k] = cx + off + fx + so.pts[k]
                pts[k + 1] = cy + off + fy + so.pts[k + 1]
                pts[k + 2] = so.pts[k + 2]
                k += 3
            }
            outStrokes.add(Stroke(so.type, so.color, so.widthMm, pts, so.opacity, false, 0L, so.linkType, so.linkData))
        }
        if (out.isNotEmpty() || outStrokes.isNotEmpty()) pastes++
        return Pasted(out, outStrokes)
    }
}
