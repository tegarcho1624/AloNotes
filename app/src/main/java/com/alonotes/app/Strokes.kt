package com.alonotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * Un trazo. Coordenadas en milímetros de la hoja (independientes del zoom y de la pantalla):
 * pts = x, y, presión(0..1) por punto. Inmutable una vez creado (el borrador crea trazos nuevos).
 * type: 0 lápiz | 1 pluma | 2 pincel | 3 marca texto.
 */
internal class Stroke(val type: Int, val color: Int, val widthMm: Float, val pts: FloatArray) {
    val n: Int get() = pts.size / 3
    var cache: Path? = null
    var cacheScale = 0f
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
    @Transient val items: ArrayList<ImageItem> = ArrayList()
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
        images: List<List<ImageDto>> = emptyList(), imgDir: File? = null
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
            if (imgDir != null) {
                for (dto in images.getOrNull(i).orEmpty()) {
                    if (dto.r <= dto.l || dto.b <= dto.t) continue
                    l.items.add(
                        ImageItem(
                            dto.id, File(imgDir, dto.file), RectF(dto.l, dto.t, dto.r, dto.b), dto.rot, dto.bw,
                            dto.fh, dto.fv, dto.op.coerceIn(0f, 1f), dto.lt, dto.ld
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
                        im.rotation, im.baseW, im.isFlippedH, im.isFlippedV, im.opacity, im.linkType, im.linkData
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
                val im = ImageItem(isn.id, isn.file, RectF(isn.l, isn.t, isn.r, isn.b), isn.rot, isn.baseW, isn.fh, isn.fv, isn.op, isn.lt, isn.ld)
                im.bitmap = cache[isn.id + "|" + isn.file.path]
                ly.items.add(im)
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
        if (s.type == 0 || s.type == 3) {
            // 0 lápiz (casi opaco) | 3 marca texto (parcialmente transparente: se ve lo de abajo)
            paint.alpha = if (s.type == 3) 115 else 235
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = max(s.widthMm * scale, 1f)
            c.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.FILL
            c.drawPath(path, paint)
            if (s.n >= 2) {
                val e = (s.n - 1) * 3
                c.drawCircle(s.pts[0] * scale, s.pts[1] * scale, max(wMm(s, s.pts[2]) * scale / 2f, 0.4f), paint)
                c.drawCircle(s.pts[e] * scale, s.pts[e + 1] * scale, max(wMm(s, s.pts[e + 2]) * scale / 2f, 0.4f), paint)
            }
        }
    }
}

internal object StrokeOps {
    /**
     * Borra con un círculo de radio [r] mm centrado en (x, y): quita los puntos que caen dentro y
     * parte el trazo en trozos. Devuelve true si cambió algo. Los puntos van a ≤ 0,6 mm entre sí.
     */
    fun eraseAt(list: MutableList<Stroke>, x: Float, y: Float, r: Float): Boolean {
        val r2 = r * r
        var changed = false
        val out = ArrayList<Stroke>(list.size + 2)
        for (s in list) {
            var hit = false
            for (i in 0 until s.n) {
                val dx = s.pts[i * 3] - x
                val dy = s.pts[i * 3 + 1] - y
                if (dx * dx + dy * dy <= r2) {
                    hit = true
                    break
                }
            }
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
                    if (run.size >= 6) out.add(Stroke(s.type, s.color, s.widthMm, run.toArray()))
                    run = FloatList()
                } else {
                    run.add(s.pts[i * 3])
                    run.add(s.pts[i * 3 + 1])
                    run.add(s.pts[i * 3 + 2])
                }
            }
            if (run.size >= 6) out.add(Stroke(s.type, s.color, s.widthMm, run.toArray()))
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
 */
internal object StrokeStore {
    private const val MAGIC = 0x414C4E31
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
                if (i.readInt() != MAGIC) return doc
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
                        val n = i.readInt()
                        if (n < 0 || n > 2000000) throw IllegalStateException()
                        val pts = FloatArray(n * 3)
                        for (k in 0 until n) {
                            pts[k * 3] = i.readUnsignedShort() / 100f
                            pts[k * 3 + 1] = i.readUnsignedShort() / 100f
                            pts[k * 3 + 2] = i.readUnsignedByte() / 255f
                        }
                        if (n > 0) page.add(Stroke(t, col, w, pts))
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
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(pages.size)
                for (page in pages) {
                    o.writeInt(page.size)
                    for (s in page) {
                        o.writeByte(s.type)
                        o.writeInt(s.color)
                        o.writeFloat(s.widthMm)
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
                for (s in ly.canvas) StrokeRender.draw(c, s, scale, pen)
                // El trazo en curso pertenece a la capa seleccionada.
                if (i == pl.selected) live?.let { StrokeRender.draw(c, it, scale, pen) }
                if (sc >= 0) c.restoreToCount(sc)
            }
        }
        if (cr > 0f) c.drawCircle(cx * scale, cy * scale, cr * scale, cursor)
    }
}
