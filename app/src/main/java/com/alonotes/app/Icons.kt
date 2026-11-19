package com.alonotes.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/** Íconos de trazo dibujados en una rejilla de 24x24 (sin recursos vectoriales). */
internal class Icons(private val type: Int, color: Int, private val fill: Boolean) : Drawable() {
    companion object {
        const val MENU = 0
        const val SORT = 1
        const val SEARCH = 2
        const val MAIL = 3
        const val HELP = 4
        const val GEAR = 5
        const val PLUS = 6
        const val CLOSE = 7
        const val DOC = 8
        const val PDF = 9
        const val FOLDER = 10
        const val BACK = 11
        const val CHEV = 12
        const val GRID = 13
        const val PEN = 14
        const val MARKER = 15
        const val ERASER = 16
        const val TEXT = 17
        const val SHAPES = 18
        const val IMAGE = 19
        const val STAR = 20
        const val SELECT = 21
        const val UNDO = 22
        const val REDO = 23
        const val LOCK = 24
        const val ROT_L = 25
        const val ROT_R = 26
        const val PENCIL = 27
        const val CHECK = 28
        const val ARCHIVE = 29
        const val PALETTE = 30
        const val VIDEO = 31
        const val FOUNTAIN = 32
        const val BRUSH = 33
        const val SMOOTH = 34
        const val EYEDROPPER = 35
        const val NOTES = 36
        const val STAR5 = 37
        const val TRASH = 38
        const val EYE = 39
        const val EYE_ALT = 40
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val q = Path()
    private val r = RectF()

    init {
        p.color = color
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    private fun ln(c: Canvas, a: Number, b: Number, x: Number, y: Number) =
        c.drawLine(a.toFloat(), b.toFloat(), x.toFloat(), y.toFloat(), p)

    private fun rr(c: Canvas, l: Number, t: Number, rt: Number, bt: Number, rad: Number) {
        r.set(l.toFloat(), t.toFloat(), rt.toFloat(), bt.toFloat())
        c.drawRoundRect(r, rad.toFloat(), rad.toFloat(), p)
    }

    private fun circ(c: Canvas, x: Number, y: Number, rad: Number) =
        c.drawCircle(x.toFloat(), y.toFloat(), rad.toFloat(), p)

    private fun poly(c: Canvas, close: Boolean, vararg v: Number) {
        q.reset()
        q.moveTo(v[0].toFloat(), v[1].toFloat())
        var i = 2
        while (i < v.size) {
            q.lineTo(v[i].toFloat(), v[i + 1].toFloat())
            i += 2
        }
        if (close) q.close()
        c.drawPath(q, p)
    }

    private fun undo(c: Canvas) {
        poly(c, false, 8, 5, 4, 9, 8, 13)
        q.reset()
        q.moveTo(4.5f, 9f)
        q.lineTo(14f, 9f)
        q.cubicTo(18f, 9f, 20f, 11.5f, 20f, 15f)
        q.cubicTo(20f, 18f, 18f, 19.5f, 15f, 19.5f)
        c.drawPath(q, p)
    }

    private fun almond(c: Canvas) {
        q.reset()
        q.moveTo(2f, 12f)
        q.cubicTo(5f, 6.5f, 8.5f, 5f, 12f, 5f)
        q.cubicTo(15.5f, 5f, 19f, 6.5f, 22f, 12f)
        q.cubicTo(19f, 17.5f, 15.5f, 19f, 12f, 19f)
        q.cubicTo(8.5f, 19f, 5f, 17.5f, 2f, 12f)
        q.close()
        c.drawPath(q, p)
    }

    private fun rot(c: Canvas) {
        r.set(5f, 5f, 19f, 19f)
        c.drawArc(r, 200f, 280f, false, p)
        poly(c, false, 5.4f, 5.5f, 5.42f, 9.61f, 9.5f, 10.2f)
    }

    private fun label(c: Canvas, s: String, size: Float, x: Float, y: Float) {
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.textSize = size
        p.isFakeBoldText = true
        c.drawText(s, x, y, p)
        p.isFakeBoldText = false
        p.style = Paint.Style.STROKE
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        val s = b.width() / 24f
        c.scale(s, s)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        p.pathEffect = null
        when (type) {
            MENU -> { ln(c, 4, 6, 20, 6); ln(c, 4, 12, 20, 12); ln(c, 4, 18, 20, 18) }
            SORT -> {
                ln(c, 4, 6, 14, 6); ln(c, 4, 12, 11, 12); ln(c, 4, 18, 8, 18)
                ln(c, 18, 5, 18, 19); poly(c, false, 15, 16, 18, 19, 21, 16)
            }
            SEARCH -> { circ(c, 11, 11, 6.5f); ln(c, 16, 16, 21, 21) }
            MAIL -> { rr(c, 3, 5, 21, 19, 2); poly(c, false, 3.5f, 6.5f, 12, 13, 20.5f, 6.5f) }
            HELP -> { circ(c, 12, 12, 9); label(c, "?", 12f, 12f, 16.2f) }
            GEAR -> {
                circ(c, 12, 12, 3)
                circ(c, 12, 12, 6.5f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(45.0 * i)
                    val cs = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    ln(c, 12 + 6.5f * cs, 12 + 6.5f * sn, 12 + 9f * cs, 12 + 9f * sn)
                }
            }
            PLUS -> { ln(c, 12, 5, 12, 19); ln(c, 5, 12, 19, 12) }
            CLOSE -> { ln(c, 6, 6, 18, 18); ln(c, 18, 6, 6, 18) }
            DOC -> { poly(c, true, 6, 3, 14, 3, 19, 8, 19, 21, 6, 21); poly(c, false, 14, 3, 14, 8, 19, 8) }
            PDF -> {
                poly(c, true, 6, 3, 14, 3, 19, 8, 19, 21, 6, 21)
                poly(c, false, 14, 3, 14, 8, 19, 8)
                label(c, "PDF", 5.5f, 12.5f, 17f)
            }
            FOLDER -> {
                if (fill) p.style = Paint.Style.FILL_AND_STROKE
                poly(c, true, 3, 7, 3, 19, 21, 19, 21, 9, 12, 9, 10, 7)
            }
            BACK -> poly(c, false, 15, 5, 8, 12, 15, 19)
            CHEV -> poly(c, false, 6, 9, 12, 15, 18, 9)
            GRID -> {
                rr(c, 4, 4, 10, 10, 1.5f); rr(c, 14, 4, 20, 10, 1.5f)
                rr(c, 4, 14, 10, 20, 1.5f); rr(c, 14, 14, 20, 20, 1.5f)
            }
            PEN, PENCIL -> {
                poly(c, true, 4, 20, 5.5f, 14.5f, 15.5f, 4.5f, 19.5f, 8.5f, 9.5f, 18.5f)
                ln(c, 13, 7, 17, 11)
            }
            MARKER -> { poly(c, true, 5, 19, 5, 15, 15, 5, 19, 9, 9, 19); ln(c, 4, 21.5f, 11, 21.5f) }
            ERASER -> {
                poly(c, true, 3.5f, 14.5f, 13, 5, 19.5f, 11.5f, 12, 19, 8, 19)
                ln(c, 12, 19, 21, 19)
            }
            TEXT -> { ln(c, 5, 6, 19, 6); ln(c, 12, 6, 12, 19); ln(c, 9, 19, 15, 19) }
            SHAPES -> {
                poly(c, true, 8, 3, 13, 11, 3, 11); rr(c, 3, 14, 10, 21, 1)
                circ(c, 16.5f, 16.5f, 4.5f)
            }
            IMAGE -> {
                rr(c, 3, 4, 21, 20, 2); circ(c, 9, 10, 1.8f)
                poly(c, false, 3, 17, 9, 12, 13, 16, 16, 13, 21, 18)
            }
            STAR -> {
                rr(c, 3, 3, 21, 21, 5)
                q.reset()
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) 5f else 2.2f
                    val a = Math.toRadians(-90.0 + 36 * i)
                    val x = 12f + rad * cos(a).toFloat()
                    val y = 12.5f + rad * sin(a).toFloat()
                    if (i == 0) q.moveTo(x, y) else q.lineTo(x, y)
                }
                q.close()
                c.drawPath(q, p)
            }
            SELECT -> {
                p.pathEffect = DashPathEffect(floatArrayOf(2.5f, 2.5f), 0f)
                rr(c, 3, 3, 15, 15, 1.5f)
                p.pathEffect = null
                poly(c, true, 12, 12, 21, 15.5f, 16.5f, 17, 15, 21.5f)
            }
            UNDO -> undo(c)
            REDO -> { c.save(); c.scale(-1f, 1f, 12f, 12f); undo(c); c.restore() }
            LOCK -> {
                rr(c, 5, 11, 19, 21, 2)
                q.reset()
                q.moveTo(8f, 11f)
                q.lineTo(8f, 8f)
                r.set(8f, 4f, 16f, 12f)
                q.arcTo(r, 180f, 180f)
                q.lineTo(16f, 11f)
                c.drawPath(q, p)
            }
            ROT_L -> rot(c)
            ROT_R -> { c.save(); c.scale(-1f, 1f, 12f, 12f); rot(c); c.restore() }
            CHECK -> poly(c, false, 5, 12.5f, 10, 17.5f, 19, 7)
            ARCHIVE -> {
                rr(c, 3, 4, 21, 9, 1.5f)
                poly(c, false, 5, 9, 5, 20, 19, 20, 19, 9)
                ln(c, 10, 13, 14, 13)
            }
            PALETTE -> {
                circ(c, 12, 12, 9)
                circ(c, 8, 10.5f, 1); circ(c, 12, 7.5f, 1); circ(c, 16, 10.5f, 1); circ(c, 8.5f, 15.5f, 1)
            }
            VIDEO -> { rr(c, 3, 6, 15, 18, 2); poly(c, true, 15, 10, 21, 7, 21, 17, 15, 14) }
            FOUNTAIN -> {
                poly(c, true, 4.5f, 19.5f, 6, 13, 14, 5, 19, 10, 11, 18)
                ln(c, 4.5f, 19.5f, 10, 14); circ(c, 10.8f, 13.2f, 1.1f)
            }
            BRUSH -> {
                ln(c, 19.5f, 4.5f, 11.5f, 12.5f)
                q.reset()
                q.moveTo(11f, 11f)
                q.cubicTo(8.5f, 10.5f, 5.5f, 13f, 5.5f, 16f)
                q.cubicTo(5.5f, 18f, 4.5f, 19f, 3.5f, 19.5f)
                q.cubicTo(7f, 21f, 11.5f, 19.5f, 13f, 15.5f)
                q.close()
                c.drawPath(q, p)
            }
            SMOOTH -> {
                q.reset()
                q.moveTo(3f, 12f)
                q.cubicTo(6f, 4f, 9f, 4f, 12f, 12f)
                q.cubicTo(15f, 20f, 18f, 20f, 21f, 12f)
                c.drawPath(q, p)
            }
            EYEDROPPER -> { poly(c, true, 4, 20, 5.5f, 15.5f, 14, 7, 17, 10, 8.5f, 18.5f); ln(c, 13, 5, 19, 11) }
            NOTES -> { rr(c, 3, 5, 21, 19, 2); ln(c, 7, 10, 17, 10); ln(c, 7, 14, 13, 14) }
            STAR5 -> {
                q.reset()
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) 9f else 4f
                    val a = Math.toRadians(-90.0 + 36 * i)
                    val x = 12f + rad * cos(a).toFloat()
                    val y = 12.8f + rad * sin(a).toFloat()
                    if (i == 0) q.moveTo(x, y) else q.lineTo(x, y)
                }
                q.close()
                c.drawPath(q, p)
            }
            TRASH -> {
                ln(c, 4, 7, 20, 7); poly(c, false, 9, 7, 9, 4, 15, 4, 15, 7)
                poly(c, false, 6, 7, 7, 20, 17, 20, 18, 7); ln(c, 10, 11, 10, 16); ln(c, 14, 11, 14, 16)
            }
            EYE -> {
                // Ojo relleno con la pupila vaciada: vista de una sola hoja.
                p.style = Paint.Style.FILL
                q.reset()
                q.fillType = Path.FillType.EVEN_ODD
                q.moveTo(2f, 12f)
                q.cubicTo(5f, 6.5f, 8.5f, 5f, 12f, 5f)
                q.cubicTo(15.5f, 5f, 19f, 6.5f, 22f, 12f)
                q.cubicTo(19f, 17.5f, 15.5f, 19f, 12f, 19f)
                q.cubicTo(8.5f, 19f, 5f, 17.5f, 2f, 12f)
                q.close()
                q.addCircle(12f, 12f, 3.6f, Path.Direction.CW)
                c.drawPath(q, p)
                q.fillType = Path.FillType.WINDING
                circ(c, 12, 12, 1.5f)
            }
            EYE_ALT -> {
                // Ojo de contorno con iris y pupila: vista continua.
                almond(c)
                circ(c, 12, 12, 3.6f)
                p.style = Paint.Style.FILL
                circ(c, 12, 12, 1.2f)
            }
            else -> {}
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
