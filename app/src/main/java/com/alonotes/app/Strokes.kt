package com.alonotes.app

import android.content.Context
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
 * type: 0 lápiz | 1 pluma | 2 pincel.
 */
internal class Stroke(val type: Int, val color: Int, val widthMm: Float, val pts: FloatArray) {
    val n: Int get() = pts.size / 3
    var cache: Path? = null
    var cacheScale = 0f
}

/** Trazos de una nota: una lista por hoja. */
internal class DocStrokes {
    val pages = ArrayList<ArrayList<Stroke>>()

    fun page(i: Int): ArrayList<Stroke> {
        while (pages.size <= i) pages.add(ArrayList())
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
            path = if (s.type == 0) polyline(s, scale) else outline(s, scale)
            s.cache = path
            s.cacheScale = scale
        }
        paint.color = s.color
        if (s.type == 0) {
            paint.alpha = 235
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
                    doc.pages.add(page)
                }
            }
        } catch (e: Exception) {
            doc.pages.clear() // archivo dañado: se empieza sin trazos
        }
        return doc
    }

    fun saveAsync(c: Context, id: String, doc: DocStrokes) {
        val snap = doc.pages.map { ArrayList(it) } // copia superficial: Stroke es inmutable
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
internal class PageView(ctx: Context, val spec: PageViewport.Spec, private val list: () -> List<Stroke>) : View(ctx) {
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
        for (s in list()) StrokeRender.draw(c, s, scale, pen)
        live?.let { StrokeRender.draw(c, it, scale, pen) }
        if (cr > 0f) c.drawCircle(cx * scale, cy * scale, cr * scale, cursor)
    }
}
