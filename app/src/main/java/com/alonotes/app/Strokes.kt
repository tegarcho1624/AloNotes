package com.alonotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Lista de floats sin boxing (puntos de un trazo en curso). */
internal class FloatList {
    private var a = FloatArray(192)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Float = a[i]
    fun isEmpty(): Boolean = size == 0
    fun toArray(): FloatArray = a.copyOf(size)
}

/**
 * Formas básicas como puntos de trazo. Una forma NO es un tipo de objeto nuevo: se convierte en la lista de puntos de su CONTORNO
 * (x, y, presión; ≤ 0,6 mm entre puntos, igual que el lápiz, así que el borrador la parte como a cualquier trazo) y se guarda como
 * un [Stroke] normal. Coordenadas en mm de la hoja.
 */
internal object ShapeGen {
    const val LINE = 0
    const val RECT = 1
    const val CIRCLE = 2

    private const val STEP = 0.6f // mm entre puntos
    private const val PRESSURE = 0.7f // presión fija: el lápiz da grosor parejo
    private const val MIN_MM = 0.5f // más chica que esto es un toque, no una forma

    /**
     * Añade a [out] los puntos de la forma [kind] que va del punto de inicio (x0, y0) al punto actual (x1, y1). No añade nada si es
     * demasiado chica. Línea: del inicio al actual. Rectángulo: el contorno con esas dos esquinas opuestas. Círculo: centro en el
     * inicio y radio hasta el actual (limitado para que el círculo quepa en la hoja de [pageW] x [pageH]).
     */
    fun fill(out: FloatList, kind: Int, x0: Float, y0: Float, x1: Float, y1: Float, pageW: Float, pageH: Float) {
        when (kind) {
            RECT -> {
                if (abs(x1 - x0) < MIN_MM && abs(y1 - y0) < MIN_MM) return
                point(out, x0, y0)
                edge(out, x0, y0, x1, y0)
                edge(out, x1, y0, x1, y1)
                edge(out, x1, y1, x0, y1)
                edge(out, x0, y1, x0, y0) // vuelve al inicio: contorno cerrado
            }
            CIRCLE -> {
                val room = min(min(x0, pageW - x0), min(y0, pageH - y0))
                val r = min(hypot(x1 - x0, y1 - y0), room)
                if (r < MIN_MM) return
                val n = max(16, ceil(2.0 * Math.PI * r / STEP).toInt())
                for (k in 0..n) {
                    val a = 2.0 * Math.PI * k / n // con seno y coseno; k = n cierra el círculo
                    point(out, x0 + r * cos(a).toFloat(), y0 + r * sin(a).toFloat())
                }
            }
            else -> {
                if (hypot(x1 - x0, y1 - y0) < MIN_MM) return
                point(out, x0, y0)
                edge(out, x0, y0, x1, y1)
            }
        }
    }

    private fun point(out: FloatList, x: Float, y: Float) {
        out.add(x)
        out.add(y)
        out.add(PRESSURE)
    }

    /** Puntos intermedios de (xa, ya) a (xb, yb), sin repetir el de partida y llegando al final. */
    private fun edge(out: FloatList, xa: Float, ya: Float, xb: Float, yb: Float) {
        val len = hypot(xb - xa, yb - ya)
        if (len < 0.01f) return
        val n = ceil(len / STEP).toInt().coerceAtLeast(1)
        for (k in 1..n) {
            val t = k / n.toFloat()
            point(out, xa + (xb - xa) * t, ya + (yb - ya) * t)
        }
    }
}

/**
 * Un trazo. Coordenadas en milímetros de la hoja (independientes del zoom y de la pantalla):
 * pts = x, y, presión(0..1) por punto. Inmutable una vez creado (el borrador crea trazos nuevos).
 * type: 0 lápiz | 1 pluma | 2 pincel | 3 marca texto.
 */
internal class Stroke(
    val type: Int,
    val color: Int,
    val widthMm: Float,
    val pts: FloatArray,
    /** Opacidad del trazo (0..1), por encima de la propia del tipo (el marcador ya es translúcido). Se guarda en el archivo de trazos. */
    val opacity: Float = 1f,
    /** Bloqueado: no se puede mover, girar ni redimensionar. Se guarda en el archivo de trazos. */
    val locked: Boolean = false,
    uidFrom: Long = 0L,
    /** Enlace del trazo, igual que el de las imágenes: [ImageItem.LINK_NONE], LINK_WEB (linkData = URL) o LINK_INTERNAL ("idNota|hoja"). Se guarda en el archivo de trazos. */
    val linkType: Int = 0,
    val linkData: String = ""
) {
    val n: Int get() = pts.size / 3
    var cache: Path? = null
    var cacheScale = 0f

    /**
     * Identificador de esta sesión (no se guarda): sirve para volver a seleccionar un trazo después de deshacer o rehacer.
     * Un trazo que reemplaza a otro (mover, voltear, opacidad, bloqueo) conserva el identificador del anterior.
     */
    val uid: Long = if (uidFrom != 0L) uidFrom else nextUid.incrementAndGet()

    private var bb: FloatArray? = null

    /** Caja de los puntos (minX, minY, maxX, maxY), sin el grosor. Se calcula una sola vez (el trazo es inmutable). */
    fun bbox(): FloatArray {
        val c = bb
        if (c != null) return c
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = pts[i * 3]
            val y = pts[i * 3 + 1]
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        val r = floatArrayOf(x0, y0, x1, y1)
        bb = r
        return r
    }

    /**
     * El mismo trazo con otra opacidad, bloqueo, color, grosor o enlace (conserva el identificador). Comparte los puntos y, si el grosor
     * no cambia, el camino ya calculado (no se vuelve a construir).
     */
    fun copyWith(
        opacity: Float = this.opacity,
        locked: Boolean = this.locked,
        color: Int = this.color,
        widthMm: Float = this.widthMm,
        linkType: Int = this.linkType,
        linkData: String = this.linkData
    ): Stroke {
        val s = Stroke(type, color, widthMm, pts, opacity, locked, uid, linkType, linkData)
        if (widthMm == this.widthMm) {
            s.cache = cache
            s.cacheScale = cacheScale
        }
        s.bb = bb
        return s
    }

    private companion object {
        val nextUid = java.util.concurrent.atomic.AtomicLong()
    }
}

/** Una capa de una hoja: sus trazos (canvas), visibilidad y opacidad. No usar equals/hashCode: se identifica por id. */
@Serializable
internal data class Layer(
    val id: Long,
    var name: String,
    var isVisible: Boolean = true,
    var opacity: Float = 1f,
    // Los trazos no se serializan aquí: van en strokes/<id>.bin.
    @Transient val canvas: ArrayList<Stroke> = ArrayList(),
    // Imágenes de la capa (se guardan aparte: archivos en images/<idNota>/ y sus datos en layers/<idNota>.json).
    @Transient val items: ArrayList<ImageItem> = ArrayList(),
    // Textos de la capa: se dibujan encima de las imágenes y debajo de los trazos (datos en layers/<idNota>.json).
    @Transient val texts: ArrayList<TextItem> = ArrayList()
)

/** Capas de una hoja (la primera es la de abajo) y cuál está seleccionada. Siempre hay al menos una. */
internal class PageLayers(first: List<Stroke>? = null) {
    companion object {
        const val MAX = 30
    }

    val layers = ArrayList<Layer>()
    private var seq = 1

    var selected = 0
        set(v) {
            field = v.coerceIn(0, layers.size - 1)
        }

    init {
        val l = Layer(1L, "Capa 1")
        if (first != null) l.canvas.addAll(first)
        layers.add(l)
    }

    /** Trazos de la capa seleccionada: ahí se dibuja y se borra. */
    val active: ArrayList<Stroke> get() = layers[selected].canvas

    val canAdd: Boolean get() = layers.size < MAX

    /** Agrega una capa vacía justo encima de la seleccionada y la selecciona. */
    fun add(): Layer {
        seq++
        val l = Layer(seq.toLong(), "Capa $seq")
        layers.add(selected + 1, l)
        selected += 1
        return l
    }

    /** Reemplaza todas las capas (por ejemplo, al pegar una hoja copiada). */
    fun replaceWith(newLayers: List<Layer>, sel: Int) {
        if (newLayers.isEmpty()) return
        layers.clear()
        layers.addAll(newLayers)
        var top = layers.size
        for (l in layers) if (l.id > top) top = l.id.toInt()
        seq = top
        selected = sel
    }

    /** Mueve una capa de la posición [from] a [to] (0 = la de abajo). La capa seleccionada sigue siendo la misma. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in layers.indices || to !in layers.indices) return
        val sel = layers[selected]
        layers.add(to, layers.removeAt(from))
        selected = layers.indexOfFirst { it === sel }
    }

    /** Elimina la capa seleccionada. La última capa no se puede eliminar. */
    fun removeSelected(): Boolean {
        if (layers.size <= 1) return false
        layers.removeAt(selected)
        selected = min(selected, layers.size - 1)
        return true
    }

    /**
     * Restaura las capas guardadas (sin trazos) y reparte entre ellas los trazos cargados, usando cuántos
     * tenía cada una. Si los números no cuadran con los trazos cargados, todos quedan en la primera capa.
     */
    fun restore(
        saved: List<Layer>, sel: Int, counts: List<Int>,
        images: List<List<ImageDto>> = emptyList(), imgDir: File? = null,
        texts: List<List<TextDto>> = emptyList()
    ) {
        if (saved.isEmpty() || saved.size > MAX) return
        val flat = flatten()
        val ok = counts.size == saved.size && counts.all { it >= 0 } && counts.sum() == flat.size
        var pos = 0
        for ((i, l) in saved.withIndex()) {
            l.canvas.clear()
            val n = if (ok) counts[i] else if (i == 0) flat.size else 0
            for (k in 0 until n) l.canvas.add(flat[pos + k])
            pos += n
            for (dto in texts.getOrNull(i).orEmpty()) {
                if (dto.r <= dto.l || dto.b <= dto.t) continue
                val ti = TextItem(
                    dto.id, dto.text, dto.size.coerceIn(TextItem.MIN_SIZE, TextItem.MAX_SIZE), dto.family, dto.italic, dto.bold,
                    dto.underline, dto.color, RectF(dto.l, dto.t, dto.r, dto.b), dto.rot, dto.op.coerceIn(0f, 1f), dto.lk
                )
                ti.refit() // el recuadro vuelve al tamaño natural (por si la fuente cambió)
                l.texts.add(ti)
            }
            if (imgDir != null) {
                for (dto in images.getOrNull(i).orEmpty()) {
                    if (dto.r <= dto.l || dto.b <= dto.t) continue
                    l.items.add(
                        ImageItem(
                            dto.id, File(imgDir, dto.file), RectF(dto.l, dto.t, dto.r, dto.b), dto.rot, dto.bw,
                            dto.fh, dto.fv, dto.op.coerceIn(0f, 1f), dto.lt, dto.ld, dto.lk
                        )
                    )
                }
            }
        }
        layers.clear()
        layers.addAll(saved)
        var top = layers.size
        for (l in layers) if (l.id > top) top = l.id.toInt()
        seq = top
        selected = sel
    }

    /** Foto del estado lógico de la hoja para el historial (sin bitmaps ni copias de puntos). */
    fun snapshot(): PageSnap = PageSnap(
        layers.map { ly ->
            LayerSnap(
                ly.id, ly.name, ly.isVisible, ly.opacity, ArrayList(ly.canvas),
                ly.items.map { im ->
                    ItemSnap(
                        im.id, im.file, im.bounds.left, im.bounds.top, im.bounds.right, im.bounds.bottom,
                        im.rotation, im.baseW, im.isFlippedH, im.isFlippedV, im.opacity, im.linkType, im.linkData, im.isLocked
                    )
                },
                ly.texts.map { t ->
                    TextSnap(
                        t.id, t.text, t.fontSize, t.fontFamily, t.isItalic, t.isBold, t.isUnderline, t.color,
                        t.bounds.left, t.bounds.top, t.bounds.right, t.bounds.bottom, t.rotation, t.opacity, t.isLocked
                    )
                }
            )
        },
        selected, seq
    )

    /** Vuelve al estado de [s]. Las imágenes con el mismo id y archivo conservan el bitmap ya cargado (no se vuelve a leer). */
    fun restoreSnapshot(s: PageSnap) {
        if (s.layers.isEmpty()) return
        val cache = HashMap<String, Bitmap?>()
        for (ly in layers) for (im in ly.items) cache[im.id + "|" + im.file.path] = im.bitmap
        val out = ArrayList<Layer>()
        for (ls in s.layers) {
            val ly = Layer(ls.id, ls.name, ls.visible, ls.opacity)
            ly.canvas.addAll(ls.strokes)
            for (isn in ls.items) {
                val im = ImageItem(isn.id, isn.file, RectF(isn.l, isn.t, isn.r, isn.b), isn.rot, isn.baseW, isn.fh, isn.fv, isn.op, isn.lt, isn.ld, isn.lk)
                im.bitmap = cache[isn.id + "|" + isn.file.path]
                ly.items.add(im)
            }
            for (ts in ls.texts) {
                ly.texts.add(
                    TextItem(
                        ts.id, ts.text, ts.size, ts.family, ts.italic, ts.bold, ts.underline, ts.color,
                        RectF(ts.l, ts.t, ts.r, ts.b), ts.rot, ts.op, ts.lk
                    )
                )
            }
            out.add(ly)
        }
        layers.clear()
        layers.addAll(out)
        seq = s.seq
        selected = s.selected
    }

    /** Todos los trazos en orden (de abajo hacia arriba): así se guarda hasta que las capas se guarden por separado. */
    fun flatten(): ArrayList<Stroke> {
        val out = ArrayList<Stroke>()
        for (l in layers) out.addAll(l.canvas)
        return out
    }
}

/** Trazos de una nota: capas por hoja. */
internal class DocStrokes {
    val pages = ArrayList<PageLayers>()

    fun page(i: Int): PageLayers {
        while (pages.size <= i) pages.add(PageLayers())
        return pages[i]
    }
}

internal object StrokeRender {
    private fun wMm(s: Stroke, p: Float): Float = when (s.type) {
        1 -> s.widthMm * (0.35f + 0.9f * p) // pluma: varía con la presión
        2 -> s.widthMm * (0.25f + 1.5f * p) // pincel: varía mucho
        else -> s.widthMm
    }

    private fun polyline(s: Stroke, scale: Float): Path {
        val path = Path()
        path.moveTo(s.pts[0] * scale, s.pts[1] * scale)
        if (s.n == 1) path.lineTo(s.pts[0] * scale + 0.01f, s.pts[1] * scale)
        for (i in 1 until s.n) path.lineTo(s.pts[i * 3] * scale, s.pts[i * 3 + 1] * scale)
        return path
    }

    private fun outline(s: Stroke, scale: Float): Path {
        val n = s.n
        val path = Path()
        val x = FloatArray(n) { s.pts[it * 3] * scale }
        val y = FloatArray(n) { s.pts[it * 3 + 1] * scale }
        val w = FloatArray(n) { wMm(s, s.pts[it * 3 + 2]) * scale }
        if (n == 1) {
            path.addCircle(x[0], y[0], max(w[0] / 2f, 0.5f), Path.Direction.CW)
            return path
        }
        val lx = FloatArray(n)
        val ly = FloatArray(n)
        val rx = FloatArray(n)
        val ry = FloatArray(n)
        for (i in 0 until n) {
            val a = max(0, i - 1)
            val b = min(n - 1, i + 1)
            var tx = x[b] - x[a]
            var ty = y[b] - y[a]
            val l = hypot(tx, ty)
            if (l < 1e-4f) {
                tx = 1f
                ty = 0f
            } else {
                tx /= l
                ty /= l
            }
            val hw = max(w[i] / 2f, 0.4f)
            lx[i] = x[i] - ty * hw
            ly[i] = y[i] + tx * hw
            rx[i] = x[i] + ty * hw
            ry[i] = y[i] - tx * hw
        }
        path.moveTo(lx[0], ly[0])
        for (i in 1 until n) path.lineTo(lx[i], ly[i])
        for (i in n - 1 downTo 0) path.lineTo(rx[i], ry[i])
        path.close()
        return path
    }

    fun draw(c: Canvas, s: Stroke, scale: Float, paint: Paint) {
        if (s.n == 0) return
        var path = s.cache
        if (path == null || s.cacheScale != scale) {
            path = if (s.type == 0 || s.type == 3) polyline(s, scale) else outline(s, scale)
            s.cache = path
            s.cacheScale = scale
        }
        paint.color = s.color
        val op = s.opacity.coerceIn(0f, 1f)
        if (s.type == 0 || s.type == 3) {
            // 0 lápiz (casi opaco) | 3 marca texto (parcialmente transparente: se ve lo de abajo); ambos por la opacidad del trazo
            paint.alpha = ((if (s.type == 3) 115 else 235) * op).roundToInt()
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = max(s.widthMm * scale, 1f)
            c.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.FILL
            if (op < 1f) {
                // Pluma y pincel se dibujan con varias formas que se tocan: en una capa con alpha para que no se mezclen dos veces.
                val b = RectF()
                path.computeBounds(b, true)
                val pad = s.widthMm * scale * 2f + 2f
                val sc = c.saveLayerAlpha(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad, (255f * op).roundToInt())
                fill(c, s, scale, paint, path)
                c.restoreToCount(sc)
            } else {
                fill(c, s, scale, paint, path)
            }
        }
    }

    /** Pluma y pincel: el contorno relleno y un círculo en cada punta. */
    private fun fill(c: Canvas, s: Stroke, scale: Float, paint: Paint, path: Path) {
        c.drawPath(path, paint)
        if (s.n >= 2) {
            val e = (s.n - 1) * 3
            c.drawCircle(s.pts[0] * scale, s.pts[1] * scale, max(wMm(s, s.pts[2]) * scale / 2f, 0.4f), paint)
            c.drawCircle(s.pts[e] * scale, s.pts[e + 1] * scale, max(wMm(s, s.pts[e + 2]) * scale / 2f, 0.4f), paint)
        }
    }
}

internal object StrokeOps {
    /** ¿Algún punto del trazo cae dentro del círculo? Descarta de inmediato los trazos cuya caja queda lejos. */
    private fun touches(s: Stroke, x: Float, y: Float, r: Float, r2: Float): Boolean {
        val bb = s.bbox()
        if (x < bb[0] - r || x > bb[2] + r || y < bb[1] - r || y > bb[3] + r) return false
        for (i in 0 until s.n) {
            val dx = s.pts[i * 3] - x
            val dy = s.pts[i * 3 + 1] - y
            if (dx * dx + dy * dy <= r2) return true
        }
        return false
    }

    /**
     * ¿Se tocó el trazo? Distancia del punto (x, y) a la línea del trazo ≤ mitad del grosor + [tol] mm. Primero se descarta
     * por la caja (con muchos trazos solo se miden los pocos cercanos).
     */
    fun hit(s: Stroke, x: Float, y: Float, tol: Float): Boolean {
        val r = s.widthMm / 2f + tol
        val bb = s.bbox()
        if (x < bb[0] - r || x > bb[2] + r || y < bb[1] - r || y > bb[3] + r) return false
        val r2 = r * r
        val p = s.pts
        for (i in 0 until s.n - 1) {
            val ax = p[i * 3]
            val ay = p[i * 3 + 1]
            val bx = p[i * 3 + 3]
            val by = p[i * 3 + 4]
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            var t = if (len2 > 1e-9f) ((x - ax) * vx + (y - ay) * vy) / len2 else 0f
            t = t.coerceIn(0f, 1f)
            val dx = ax + t * vx - x
            val dy = ay + t * vy - y
            if (dx * dx + dy * dy <= r2) return true
        }
        return false
    }

    /**
     * Borra con un círculo de radio [r] mm centrado en (x, y): quita los puntos que caen dentro y
     * parte el trazo en trozos. Devuelve true si cambió algo. Los puntos van a ≤ 0,6 mm entre sí.
     */
    fun eraseAt(list: MutableList<Stroke>, x: Float, y: Float, r: Float): Boolean {
        val r2 = r * r
        // Rendimiento: primero se ve si ALGÚN trazo toca el círculo (con un descarte rápido por su caja) sin crear nada.
        var any = false
        for (s in list) {
            if (touches(s, x, y, r, r2)) {
                any = true
                break
            }
        }
        if (!any) return false
        var changed = false
        val out = ArrayList<Stroke>(list.size + 2)
        for (s in list) {
            val hit = touches(s, x, y, r, r2)
            if (!hit) {
                out.add(s)
                continue
            }
            changed = true
            var run = FloatList()
            for (i in 0 until s.n) {
                val dx = s.pts[i * 3] - x
                val dy = s.pts[i * 3 + 1] - y
                if (dx * dx + dy * dy <= r2) {
                    if (run.size >= 6) out.add(Stroke(s.type, s.color, s.widthMm, run.toArray(), s.opacity, s.locked, 0L, s.linkType, s.linkData))
                    run = FloatList()
                } else {
                    run.add(s.pts[i * 3])
                    run.add(s.pts[i * 3 + 1])
                    run.add(s.pts[i * 3 + 2])
                }
            }
            if (run.size >= 6) out.add(Stroke(s.type, s.color, s.widthMm, run.toArray(), s.opacity, s.locked, 0L, s.linkType, s.linkData))
        }
        if (changed) {
            list.clear()
            list.addAll(out)
        }
        return changed
    }
}

/**
 * Guardado de trazos: filesDir/strokes/<idNota>.bin (binario compacto: x, y en 0,01 mm y presión
 * en un byte). Se escribe en segundo plano y por archivo temporal. Archivo dañado => sin trazos.
 * Dos versiones: "ALN1" (la de siempre) y "ALN2", que añade por trazo un byte de banderas (bit 0 = bloqueado, bit 1 = tiene
 * enlace) y otro de opacidad (0..255); si tiene enlace, a continuación un byte de tipo y el texto (UTF). Se lee cualquiera
 * (los ALN2 sin enlaces, de la versión anterior, se leen igual); se escribe ALN2 solo si algún trazo tiene opacidad, bloqueo
 * o enlace (si no, ALN1, así las notas sin esas funciones siguen siendo iguales que antes).
 */
internal object StrokeStore {
    private const val MAGIC = 0x414C4E31
    private const val MAGIC2 = 0x414C4E32

    /** Un enlace se guarda solo si tiene tipo y un texto de tamaño razonable (writeUTF admite hasta 65535 bytes). */
    private fun hasLink(s: Stroke) = s.linkType != 0 && s.linkData.isNotEmpty() && s.linkData.length <= 16000
    private val exec = Executors.newSingleThreadExecutor()

    private fun file(c: Context, id: String) = File(File(c.filesDir, "strokes"), "$id.bin")

    /** Espera a que terminen las escrituras pendientes (para leer justo después, desde otra pantalla). */
    fun flush() {
        try {
            exec.submit(Runnable { }).get()
        } catch (ignored: Exception) {
            // Si se interrumpe, se sigue igual.
        }
    }

    fun load(c: Context, id: String): DocStrokes {
        val doc = DocStrokes()
        val f = file(c, id)
        if (!f.exists()) return doc
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { i ->
                val magic = i.readInt()
                val v2 = magic == MAGIC2
                if (magic != MAGIC && !v2) return doc
                val np = i.readInt()
                if (np < 0 || np > 5000) throw IllegalStateException()
                for (pi in 0 until np) {
                    val page = ArrayList<Stroke>()
                    val ns = i.readInt()
                    if (ns < 0 || ns > 500000) throw IllegalStateException()
                    for (si in 0 until ns) {
                        val t = i.readUnsignedByte()
                        val col = i.readInt()
                        val w = i.readFloat()
                        var flags = 0
                        var opb = 255
                        var lt = 0
                        var ld = ""
                        if (v2) {
                            flags = i.readUnsignedByte()
                            opb = i.readUnsignedByte()
                            if ((flags and 2) != 0) {
                                lt = i.readUnsignedByte()
                                ld = i.readUTF()
                            }
                        }
                        val n = i.readInt()
                        if (n < 0 || n > 2000000) throw IllegalStateException()
                        val pts = FloatArray(n * 3)
                        for (k in 0 until n) {
                            pts[k * 3] = i.readUnsignedShort() / 100f
                            pts[k * 3 + 1] = i.readUnsignedShort() / 100f
                            pts[k * 3 + 2] = i.readUnsignedByte() / 255f
                        }
                        if (n > 0) page.add(Stroke(t, col, w, pts, opb / 255f, (flags and 1) != 0, 0L, lt, ld))
                    }
                    doc.pages.add(PageLayers(page))
                }
            }
        } catch (e: Exception) {
            doc.pages.clear() // archivo dañado: se empieza sin trazos
        }
        return doc
    }

    fun saveAsync(c: Context, id: String, doc: DocStrokes) {
        val snap = doc.pages.map { it.flatten() } // copia superficial: Stroke es inmutable
        val app = c.applicationContext
        exec.execute { write(app, id, snap) }
    }

    private fun write(c: Context, id: String, pages: List<List<Stroke>>) {
        try {
            val f = file(c, id)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$id.tmp")
            var v2 = false
            for (page in pages) for (s in page) if (s.opacity < 1f || s.locked || hasLink(s)) v2 = true
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
                o.writeInt(if (v2) MAGIC2 else MAGIC)
                o.writeInt(pages.size)
                for (page in pages) {
                    o.writeInt(page.size)
                    for (s in page) {
                        o.writeByte(s.type)
                        o.writeInt(s.color)
                        o.writeFloat(s.widthMm)
                        if (v2) {
                            val link = hasLink(s)
                            o.writeByte((if (s.locked) 1 else 0) or (if (link) 2 else 0))
                            o.writeByte((s.opacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255))
                            if (link) {
                                o.writeByte(s.linkType)
                                o.writeUTF(s.linkData)
                            }
                        }
                        o.writeInt(s.n)
                        for (k in 0 until s.n) {
                            o.writeShort((s.pts[k * 3] * 100f).roundToInt().coerceIn(0, 65535))
                            o.writeShort((s.pts[k * 3 + 1] * 100f).roundToInt().coerceIn(0, 65535))
                            o.writeByte((s.pts[k * 3 + 2] * 255f).roundToInt().coerceIn(0, 255))
                        }
                    }
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // Si no se puede escribir, queda el archivo anterior.
        }
    }

    fun delete(c: Context, id: String) {
        try {
            file(c, id).delete()
        } catch (e: Exception) {
            // Nada que borrar.
        }
    }
}

/** Una hoja: dibuja su fondo, sus trazos guardados, el trazo en curso y el cursor del borrador. */
internal class PageView(ctx: Context, val spec: PageViewport.Spec, private val page: () -> PageLayers?) : View(ctx) {
    var live: Stroke? = null

    /** Trazo seleccionado que se está transformando: se dibuja él (movido, girado...) en lugar del original. */
    var lifted: StrokeItem? = null

    /**
     * Trazos de un grupo que se está moviendo, redimensionando o girando: se dibujan con [liftMatrix] (mm de la hoja -> mm de la
     * hoja, una sola matriz para todos), sin recalcular sus puntos mientras dura el gesto.
     */
    var liftedSet: Set<Stroke>? = null
    var liftMatrix: Matrix? = null

    /** Texto que se está editando (lo muestra el cuadro de edición): no se dibuja aquí. */
    var hiddenText: TextItem? = null
    private var cx = 0f
    private var cy = 0f
    private var cr = -1f
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        outlineProvider = ViewOutlineProvider.BOUNDS
        elevation = 4 * d
        cursor.style = Paint.Style.STROKE
        cursor.strokeWidth = 1.5f * d
        cursor.color = 0xFF888888.toInt()
    }

    fun setCursor(xMm: Float, yMm: Float, rMm: Float) {
        cx = xMm
        cy = yMm
        cr = rMm
        invalidate()
    }

    fun clearCursor() {
        cr = -1f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val scale = width / spec.wMm
        bg.color = spec.bg
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRect(rect, bg)
        val pl = page()
        val ls = liftedSet
        val gm = liftMatrix
        var pm: Matrix? = null
        if (ls != null && gm != null) {
            // La matriz va en mm; el lienzo está en píxeles: píxeles -> mm, matriz del grupo, mm -> píxeles.
            pm = Matrix()
            pm.setScale(1f / scale, 1f / scale)
            pm.postConcat(gm)
            pm.postScale(scale, scale)
        }
        if (pl != null) {
            for ((i, ly) in pl.layers.withIndex()) {
                if (!ly.isVisible) continue
                val a = (ly.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
                val sc = if (a < 255) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), a) else -1
                if (ly.items.isNotEmpty()) {
                    // Las imágenes solo se ven dentro de la hoja (lo que se sale queda recortado), debajo de los trazos.
                    val n = c.save()
                    c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                    for (im in ly.items) im.draw(c, scale) { postInvalidate() }
                    c.restoreToCount(n)
                }
                if (ly.texts.isNotEmpty()) {
                    // Los textos van encima de las imágenes y debajo de los trazos (así el marcador se ve sobre el texto).
                    val n = c.save()
                    c.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                    val hid = hiddenText
                    for (t in ly.texts) if (t !== hid) t.draw(c, scale) { postInvalidate() }
                    c.restoreToCount(n)
                }
                val lf = lifted
                for (s in ly.canvas) {
                    if (lf != null && s === lf.src) {
                        StrokeRender.draw(c, lf.transformed(), scale, pen)
                    } else if (pm != null && ls != null && ls.contains(s)) {
                        val n = c.save()
                        c.concat(pm)
                        StrokeRender.draw(c, s, scale, pen)
                        c.restoreToCount(n)
                    } else {
                        StrokeRender.draw(c, s, scale, pen)
                    }
                }
                // El trazo en curso pertenece a la capa seleccionada.
                if (i == pl.selected) live?.let { StrokeRender.draw(c, it, scale, pen) }
                if (sc >= 0) c.restoreToCount(sc)
            }
        }
        if (cr > 0f) c.drawCircle(cx * scale, cy * scale, cr * scale, cursor)
    }
}
