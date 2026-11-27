package com.alonotes.app

import android.content.Context
import android.graphics.RectF
import java.io.File
import java.util.UUID

/**
 * Portapapeles de UNA capa (en memoria mientras la app esté abierta). Al copiar guarda una copia profunda de la capa y de todo lo que
 * tiene: los trazos se duplican (arreglo nuevo de puntos) y las imágenes se copian a filesDir/layerclip/. Así, borrar o editar la capa
 * original no afecta a lo copiado. Al pegar, todo recibe IDENTIFICADORES NUEVOS (trazos nuevos, ids nuevos de imágenes y textos, archivos de
 * imagen con nombre nuevo en images/<idNota>/), de modo que nada se comparte con otra capa; se puede pegar varias veces y en otra hoja o nota.
 * Usa las mismas clases de copia que el portapapeles de hojas ([ClipLayer], [ClipImage], [ClipText]).
 */
internal object LayerClipboard {
    var layer: ClipLayer? = null
        private set

    val isEmpty: Boolean get() = layer == null

    private fun dir(c: Context) = File(c.filesDir, "layerclip")

    /** Reemplaza el contenido del portapapeles con la capa [ly] (no la modifica). */
    fun copy(c: Context, ly: Layer) {
        val d = dir(c)
        try {
            d.deleteRecursively()
        } catch (ignored: Exception) {
            // Si no se puede limpiar, se sobrescribe lo que haga falta.
        }
        d.mkdirs()
        var n = 0
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
            imgs.add(
                ClipImage(
                    dst, b.left, b.top, b.right, b.bottom, im.rotation, im.baseW, im.isFlippedH, im.isFlippedV, im.opacity,
                    im.linkType, im.linkData, im.isLocked
                )
            )
        }
        val strokes = ly.canvas.map { s ->
            Stroke(s.type, s.color, s.widthMm, s.pts.copyOf(), s.opacity, s.locked, 0L, s.linkType, s.linkData)
        }
        val texts = ly.texts.map { t ->
            ClipText(
                t.text, t.fontSize, t.fontFamily, t.isItalic, t.isBold, t.isUnderline, t.color,
                t.bounds.left, t.bounds.top, t.bounds.right, t.bounds.bottom, t.rotation, t.opacity, t.isLocked,
                t.isFlippedH, t.isFlippedV, t.linkType, t.linkData,
                t.isFixedWidth, t.letterSpacing, t.textAlign, t.lineHeight
            )
        }
        layer = ClipLayer(ly.name, ly.isVisible, ly.opacity, strokes, imgs, texts, ly.blendMode, ly.isLocked)
    }

    /**
     * Llena la capa [target] (nueva y vacía) con una copia de lo copiado, para la nota [docId]: sus propiedades (visibilidad, opacidad, modo
     * de fusión y bloqueo) y todos sus objetos con identificadores nuevos. El portapapeles queda intacto. Devuelve false si no hay nada copiado.
     */
    fun paste(c: Context, docId: String, target: Layer): Boolean {
        val cl = layer ?: return false
        val imgDir = File(c.filesDir, "images/$docId")
        imgDir.mkdirs()
        target.isVisible = cl.isVisible
        target.opacity = cl.opacity
        target.blendMode = cl.blend
        target.isLocked = cl.locked
        for (s in cl.strokes) {
            target.canvas.add(Stroke(s.type, s.color, s.widthMm, s.pts.copyOf(), s.opacity, s.locked, 0L, s.linkType, s.linkData))
        }
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
            target.items.add(ImageItem(id, dst, RectF(im.l, im.t, im.r, im.b), im.rot, im.bw, im.fh, im.fv, im.op, im.lt, im.ld, im.lk))
        }
        for (ct in cl.texts) {
            target.texts.add(
                TextItem(
                    UUID.randomUUID().toString(), ct.text, ct.size, ct.family, ct.italic, ct.bold, ct.underline, ct.color,
                    RectF(ct.l, ct.t, ct.r, ct.b), ct.rot, ct.op, ct.lk, ct.fh, ct.fv, ct.lt, ct.ld,
                    ct.fw, ct.ls, ct.ta, ct.lh
                )
            )
        }
        return true
    }
}
