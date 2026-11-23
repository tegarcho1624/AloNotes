package com.alonotes.app

import android.content.Context
import android.graphics.RectF
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Imagen copiada: su archivo está en filesDir/clipboard/ (copia propia, no depende de la hoja de origen). */
internal class ClipImage(
    val file: File,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val rot: Float,
    val bw: Float,
    val fh: Boolean,
    val fv: Boolean,
    val op: Float,
    val lt: Int,
    val ld: String,
    val lk: Boolean
)

internal class ClipLayer(
    val name: String,
    val isVisible: Boolean,
    val opacity: Float,
    val strokes: List<Stroke>,
    val images: List<ClipImage>
)

/** Una hoja copiada: sus datos de página (JSON), sus capas con trazos e imágenes, y la capa seleccionada. */
internal class ClipPage(val json: String, val layers: List<ClipLayer>, val selected: Int)

/**
 * Portapapeles de hojas (en memoria mientras la app esté abierta). Guarda COPIAS PROFUNDAS: los trazos se
 * duplican (arreglo nuevo de puntos) y las imágenes se copian a filesDir/clipboard/. Así, borrar o editar la hoja
 * original no afecta a lo copiado, y al pegar cada hoja nueva recibe trazos e imágenes propios (archivos con
 * nombre nuevo): nada se comparte entre hojas.
 */
internal object PageClipboard {
    var pages: List<ClipPage> = emptyList()
        private set

    val isEmpty: Boolean get() = pages.isEmpty()

    private fun dir(c: Context) = File(c.filesDir, "clipboard")

    /** Reemplaza el contenido del portapapeles con las hojas dadas (en ese orden). No modifica las originales. */
    fun copy(c: Context, items: List<Pair<JSONObject, PageLayers>>) {
        val d = dir(c)
        try {
            d.deleteRecursively()
        } catch (ignored: Exception) {
            // Si no se puede limpiar, se sobrescribe lo que haga falta.
        }
        d.mkdirs()
        var n = 0
        val out = ArrayList<ClipPage>()
        for ((js, pl) in items) {
            val layers = ArrayList<ClipLayer>()
            for (ly in pl.layers) {
                val imgs = ArrayList<ClipImage>()
                for (im in ly.items) {
                    if (!im.file.exists()) continue
                    val dst = File(d, (n++).toString() + "_" + im.file.name)
                    try {
                        im.file.copyTo(dst, true)
                    } catch (e: Exception) {
                        continue
                    }
                    val b = im.bounds
                    imgs.add(ClipImage(dst, b.left, b.top, b.right, b.bottom, im.rotation, im.baseW, im.isFlippedH, im.isFlippedV, im.opacity, im.linkType, im.linkData, im.isLocked))
                }
                val strokes = ly.canvas.map { s -> Stroke(s.type, s.color, s.widthMm, s.pts.copyOf()) }
                layers.add(ClipLayer(ly.name, ly.isVisible, ly.opacity, strokes, imgs))
            }
            out.add(ClipPage(js.toString(), layers, pl.selected))
        }
        pages = out
    }

    /**
     * Crea hojas NUEVAS a partir del portapapeles, para la nota [docId]: datos de página, capas, trazos duplicados
     * e imágenes copiadas a images/<docId>/ con nombre nuevo. El portapapeles queda intacto (se puede pegar varias veces).
     */
    fun paste(c: Context, docId: String): List<Pair<JSONObject, PageLayers>> {
        val imgDir = File(c.filesDir, "images/$docId")
        imgDir.mkdirs()
        val out = ArrayList<Pair<JSONObject, PageLayers>>()
        for (cp in pages) {
            val js = try {
                JSONObject(cp.json)
            } catch (e: Exception) {
                continue
            }
            val ls = ArrayList<Layer>()
            for ((i, cl) in cp.layers.withIndex()) {
                val ly = Layer((i + 1).toLong(), cl.name, cl.isVisible, cl.opacity)
                for (s in cl.strokes) ly.canvas.add(Stroke(s.type, s.color, s.widthMm, s.pts.copyOf()))
                for (im in cl.images) {
                    if (!im.file.exists()) continue
                    val id = UUID.randomUUID().toString()
                    val ext = im.file.extension
                    val dst = File(imgDir, if (ext.isEmpty()) id else "$id.$ext")
                    try {
                        im.file.copyTo(dst, true)
                    } catch (e: Exception) {
                        continue
                    }
                    ly.items.add(ImageItem(id, dst, RectF(im.l, im.t, im.r, im.b), im.rot, im.bw, im.fh, im.fv, im.op, im.lt, im.ld, im.lk))
                }
                ls.add(ly)
            }
            if (ls.isEmpty()) ls.add(Layer(1L, "Capa 1"))
            val pl = PageLayers()
            pl.replaceWith(ls, cp.selected)
            out.add(Pair(js, pl))
        }
        return out
    }
}
