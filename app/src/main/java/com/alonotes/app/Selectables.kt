package com.alonotes.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Algo que se puede seleccionar y transformar en una hoja (hoy imágenes; más adelante textos y trazos).
 * Todo está en milímetros de la hoja, igual que los trazos, así no depende del zoom ni de la vista.
 */
internal interface SelectableItem {
    val id: String

    /** Rectángulo SIN rotar. Su centro es el pivote de la rotación. */
    val bounds: RectF

    /** Grados, sentido horario (como Canvas.rotate; el eje Y apunta hacia abajo). */
    var rotation: Float

    /** Tamaño actual respecto al original (informativo; el tamaño real es [bounds]). */
    val scale: Float

    /** Si es true, los tiradores de las esquinas conservan la proporción. */
    val lockAspect: Boolean get() = true

    /** Espejo en el eje X (izquierda-derecha), en los ejes propios del elemento (sigue valiendo si está girado). */
    var isFlippedH: Boolean

    /** Espejo en el eje Y (arriba-abajo), en los ejes propios del elemento. */
    var isFlippedV: Boolean

    /** 0 = transparente, 1 = opaco. */
    var opacity: Float

    fun draw(c: Canvas, mmToPx: Float, invalidate: () -> Unit)

    /** ¿Está el punto (mm de la hoja) dentro del elemento, ya con su rotación? */
    fun hit(xMm: Float, yMm: Float): Boolean {
        val l = Xf.toLocal(bounds.centerX(), bounds.centerY(), rotation, xMm, yMm)
        return abs(l[0]) <= bounds.width() / 2f && abs(l[1]) <= bounds.height() / 2f
    }
}

/** Geometría de la transformación (funciones puras). Ángulos en grados, sentido horario, Y hacia abajo. */
internal object Xf {
    const val MIN_MM = 6f
    private const val MAX_MM = 3000f

    /** Gira el vector (x, y) [deg] grados: x' = x·cos − y·sin, y' = x·sin + y·cos. */
    fun rotate(x: Float, y: Float, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(x * c - y * s, x * s + y * c)
    }

    /** Punto local (relativo al centro, sin rotar) -> punto de la hoja. */
    fun toPage(cx: Float, cy: Float, deg: Float, lx: Float, ly: Float): FloatArray {
        val r = rotate(lx, ly, deg)
        return floatArrayOf(cx + r[0], cy + r[1])
    }

    /** Punto de la hoja -> punto local (relativo al centro, deshaciendo la rotación). */
    fun toLocal(cx: Float, cy: Float, deg: Float, px: Float, py: Float): FloatArray =
        rotate(px - cx, py - cy, -deg)

    /** Ángulo del vector (dx, dy) en grados, con Math.atan2. */
    fun angleDeg(dx: Float, dy: Float): Float = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

    /** Lleva un ángulo al intervalo (−180, 180]. */
    fun normalize(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * Redimensiona arrastrando el tirador (sx, sy) hasta el punto (px, py) de la hoja. sx y sy valen −1, 0 o 1:
     * (−1,−1) es la esquina superior izquierda, (0,−1) el borde superior, (1,0) el borde derecho, etc.
     * El punto OPUESTO al tirador (el ancla) no se mueve.
     *  - Esquina con proporción fija: f = (v·d) / (d·d), la proyección del puntero (relativo al ancla) sobre la
     *    diagonal original; el tamaño nuevo es el original × f.
     *  - Borde (o esquina libre): solo cambia el ancho (sx) y/o el alto (sy), medidos desde el ancla en el sistema local.
     *  - El centro nuevo es: ancla + R(rot)·(sx·w/2, sy·h/2).
     * [b0] y [rot] son el estado al empezar el gesto; el resultado se escribe en [out].
     */
    fun resize(
        b0: RectF, rot: Float, sx: Int, sy: Int, px: Float, py: Float, lockAspect: Boolean, out: RectF
    ) {
        val w0 = b0.width()
        val h0 = b0.height()
        val a = toPage(b0.centerX(), b0.centerY(), rot, -sx * w0 / 2f, -sy * h0 / 2f)
        val v = rotate(px - a[0], py - a[1], -rot)
        var w = w0
        var h = h0
        if (sx != 0 && sy != 0 && lockAspect) {
            val dx = sx * w0
            val dy = sy * h0
            val f = ((v[0] * dx + v[1] * dy) / (dx * dx + dy * dy))
                .coerceIn(max(MIN_MM / w0, MIN_MM / h0), MAX_MM / max(w0, h0))
            w = w0 * f
            h = h0 * f
        } else {
            if (sx != 0) w = (sx * v[0]).coerceIn(MIN_MM, MAX_MM)
            if (sy != 0) h = (sy * v[1]).coerceIn(MIN_MM, MAX_MM)
        }
        val c = toPage(a[0], a[1], rot, sx * w / 2f, sy * h / 2f)
        out.set(c[0] - w / 2f, c[1] - h / 2f, c[0] + w / 2f, c[1] + h / 2f)
    }
}

/** Imagen colocada en una hoja. El archivo está en filesDir/images/<idNota>/; el bitmap se carga al dibujarla. */
internal class ImageItem(
    override val id: String,
    val file: File,
    override val bounds: RectF,
    override var rotation: Float,
    /** Ancho (mm) con el que se insertó, para calcular [scale]. */
    val baseW: Float,
    override var isFlippedH: Boolean = false,
    override var isFlippedV: Boolean = false,
    override var opacity: Float = 1f
) : SelectableItem {
    var bitmap: Bitmap? = null
    private var loading = false
    private var failed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    override val scale: Float get() = if (baseW > 0f) bounds.width() / baseW else 1f

    override fun draw(c: Canvas, mmToPx: Float, invalidate: () -> Unit) {
        val bm = bitmap
        if (bm != null) {
            drawBitmap(c, mmToPx, bm)
            return
        }
        // Mientras se carga: un recuadro gris.
        dst.set(bounds.left * mmToPx, bounds.top * mmToPx, bounds.right * mmToPx, bounds.bottom * mmToPx)
        val n = c.save()
        c.rotate(rotation, dst.centerX(), dst.centerY())
        paint.color = 0x33808080
        c.drawRect(dst, paint)
        c.restoreToCount(n)
        if (!loading && !failed) load(invalidate)
    }

    /**
     * Dibuja [bm] en su rectángulo con la rotación, el espejo (sobre el centro, en los ejes del propio elemento: primero
     * se gira y luego se refleja, así el espejo no depende del ángulo) y la opacidad. También lo usan las miniaturas.
     */
    fun drawBitmap(c: Canvas, mmToPx: Float, bm: Bitmap) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        val d = RectF(bounds.left * mmToPx, bounds.top * mmToPx, bounds.right * mmToPx, bounds.bottom * mmToPx)
        val n = c.save()
        c.rotate(rotation, d.centerX(), d.centerY())
        c.scale(if (isFlippedH) -1f else 1f, if (isFlippedV) -1f else 1f, d.centerX(), d.centerY())
        c.drawBitmap(bm, null, d, p)
        c.restoreToCount(n)
    }

    private fun load(invalidate: () -> Unit) {
        loading = true
        Thread {
            val b = ImageUtil.decodeFile(file.path, 2048)
            Handler(Looper.getMainLooper()).post {
                bitmap = b
                failed = b == null
                loading = false
                invalidate()
            }
        }.start()
    }
}
