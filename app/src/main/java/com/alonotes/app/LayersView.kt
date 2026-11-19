package com.alonotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Ícono de capas: pila de tres hojas con el número de capas en la hoja de enfrente.
 * El dibujo es independiente del número: basta cambiar [count] para actualizarlo.
 */
internal class LayersView(ctx: Context) : View(ctx) {
    var count: Int = 1
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val back = ctx.getColor(R.color.bg)

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val f = s * 0.72f
        val step = s * 0.14f
        val rad = 3f * d
        // Hojas de atrás (solo contorno), de la más lejana a la más cercana.
        for (i in 2 downTo 1) {
            r.set(step * i, step * i, step * i + f, step * i + f)
            p.style = Paint.Style.FILL
            p.color = back
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(r, rad, rad, p)
        }
        // Hoja de enfrente con el número.
        r.set(0f, 0f, f, f)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(r, rad, rad, p)
        p.color = 0xFF1B1B1F.toInt()
        p.textAlign = Paint.Align.CENTER
        p.textSize = f * 0.58f
        val y = f / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(count.toString(), f / 2f, y, p)
    }
}
