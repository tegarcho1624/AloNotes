package com.alonotes.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Área de hojas del editor, con dos vistas (solo visuales: nada de esto se guarda en la hoja):
 *  - Una hoja (Single Page View / Fit to Screen): la hoja actual centrada y ajustada a la pantalla,
 *    con su orientación. Un dedo desliza para cambiar de hoja (izquierda = anterior, derecha =
 *    siguiente); pellizco = zoom (mínimo 25 % del ajuste, la hoja nunca desaparece); dos dedos =
 *    rotar; con zoom, un dedo mueve la hoja; doble toque = restablecer.
 *  - Continua (Continuous Scroll / Multi-page): todas las hojas en columna con un pequeño espacio;
 *    solo lectura. Pellizco = zoom (nunca más pequeño que el tamaño base), un dedo = desplazar.
 * El lápiz (stylus) se ignora aquí: queda reservado para dibujar.
 */
internal class PageViewport(ctx: Context) : FrameLayout(ctx) {
    class Spec(val wMm: Float, val hMm: Float, val bg: Int)

    var continuous = false
        private set
    var index = 0
        private set
    var onPage: (() -> Unit)? = null

    private var specs: List<Spec> = emptyList()
    private var single: View? = null
    private var column: LinearLayout? = null

    // Transformación visual actual.
    private var z = 1f
    private var rot = 0f
    private var tx = 0f
    private var ty = 0f

    private val d = resources.displayMetrics.density
    private val scroller = OverScroller(ctx)
    private var vel: VelocityTracker? = null
    private var mode = 0 // 0 nada, 1 un dedo, 2 dos dedos
    private var multi = false
    private var lx = 0f
    private var ly = 0f
    private var sx = 0f
    private var sy = 0f
    private var pDist = 0f
    private var pAng = 0f
    private var pMx = 0f
    private var pMy = 0f
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetView()
            return true
        }
    })

    private fun dp(v: Number) = (v.toFloat() * d).roundToInt()
    private fun minZ() = if (continuous) 1f else 0.25f
    private val maxZ = 5f

    // ---------- API ----------

    fun restore(cont: Boolean, idx: Int) {
        continuous = cont
        index = idx
    }

    fun setPages(list: List<Spec>) {
        specs = list
        index = index.coerceIn(0, max(0, list.size - 1))
        resetTransform()
        rebuild()
    }

    fun setMode(cont: Boolean) {
        if (cont == continuous) return
        if (continuous) index = visibleIndex()
        continuous = cont
        resetTransform()
        rebuild()
        onPage?.invoke()
    }

    fun resetView() {
        z = 1f
        rot = 0f
        tx = 0f
        ty = 0f
        scroller.forceFinished(true)
        if (continuous) scrollToIndex(index) else applyTransform()
    }

    // ---------- Construcción ----------

    private fun resetTransform() {
        z = 1f
        rot = 0f
        tx = 0f
        ty = 0f
        scroller.forceFinished(true)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) rebuild()
    }

    private fun pageBg(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = 2 * d
        return g
    }

    private fun rebuild() {
        removeAllViews()
        single = null
        column = null
        if (specs.isEmpty() || width == 0 || height == 0) return
        if (!continuous) {
            val sp = specs[index.coerceIn(0, specs.size - 1)]
            val k = min(width / sp.wMm, height / sp.hMm)
            val v = View(context)
            v.background = pageBg(sp.bg)
            v.elevation = 4 * d
            addView(v, LayoutParams((sp.wMm * k).roundToInt(), (sp.hMm * k).roundToInt(), Gravity.CENTER))
            single = v
        } else {
            val col = LinearLayout(context)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(0, dp(24), 0, dp(24))
            val base = min(width - dp(64), dp(720)).toFloat()
            for (sp in specs) {
                val k = base / max(sp.wMm, sp.hMm)
                val v = View(context)
                v.background = pageBg(sp.bg)
                v.elevation = 4 * d
                val lp = LinearLayout.LayoutParams((sp.wMm * k).roundToInt(), (sp.hMm * k).roundToInt())
                lp.bottomMargin = dp(20)
                col.addView(v, lp)
            }
            addView(col, LayoutParams(width, -2))
            column = col
            col.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int
                ) {
                    col.removeOnLayoutChangeListener(this)
                    scrollToIndex(index)
                }
            })
        }
        applyTransform()
    }

    // ---------- Geometría ----------

    private fun visibleIndex(): Int {
        val col = column ?: return index
        val cy = (height / 2f - ty) / z
        var best = 0
        for (i in 0 until col.childCount) {
            best = i
            if (col.getChildAt(i).bottom >= cy) break
        }
        return best
    }

    private fun scrollToIndex(i: Int) {
        val col = column ?: return
        if (i < 0 || i >= col.childCount) return
        val c = col.getChildAt(i)
        ty = height / 2f - (c.top + c.height / 2f) * z
        clamp()
        applyTransform()
    }

    /** Límites de desplazamiento de la vista continua: [minX, maxX, minY, maxY]. */
    private fun contBounds(): FloatArray {
        val col = column ?: return floatArrayOf(0f, 0f, 0f, 0f)
        val cw = col.width * z
        val ch = col.height * z
        val minX = if (cw > width) width - cw else 0f
        val minY = if (ch > height) height - ch else 0f
        return floatArrayOf(minX, 0f, minY, 0f)
    }

    private fun clamp() {
        if (continuous) {
            val b = contBounds()
            tx = tx.coerceIn(b[0], b[1])
            ty = ty.coerceIn(b[2], b[3])
        } else {
            val v = single ?: return
            val a = Math.toRadians(rot.toDouble())
            val c = abs(cos(a)).toFloat()
            val s = abs(sin(a)).toFloat()
            val rw = (c * v.width + s * v.height) * z
            val rh = (s * v.width + c * v.height) * z
            val mx = max(0f, (rw - width) / 2f)
            val my = max(0f, (rh - height) / 2f)
            tx = tx.coerceIn(-mx, mx)
            ty = ty.coerceIn(-my, my)
        }
    }

    private fun applyTransform() {
        single?.let {
            it.scaleX = z
            it.scaleY = z
            it.rotation = rot
            it.translationX = tx
            it.translationY = ty
        }
        column?.let {
            it.pivotX = 0f
            it.pivotY = 0f
            it.scaleX = z
            it.scaleY = z
            it.translationX = tx
            it.translationY = ty
        }
    }

    private fun notifyPage() {
        if (continuous) index = visibleIndex()
        onPage?.invoke()
    }

    // ---------- Gestos ----------

    private fun initTwo(e: MotionEvent) {
        val x0 = e.getX(0)
        val y0 = e.getY(0)
        val x1 = e.getX(1)
        val y1 = e.getY(1)
        pMx = (x0 + x1) / 2f
        pMy = (y0 + y1) / 2f
        pDist = hypot(x1 - x0, y1 - y0)
        pAng = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
    }

    private fun twoFinger(e: MotionEvent) {
        val x0 = e.getX(0)
        val y0 = e.getY(0)
        val x1 = e.getX(1)
        val y1 = e.getY(1)
        val mx = (x0 + x1) / 2f
        val my = (y0 + y1) / 2f
        val dist = hypot(x1 - x0, y1 - y0)
        val ang = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        if (pDist > 0f && dist > 0f) {
            val nz = (z * dist / pDist).coerceIn(minZ(), maxZ)
            val f = nz / z
            if (continuous) {
                tx = mx - f * (pMx - tx)
                ty = my - f * (pMy - ty)
            } else {
                var da = ang - pAng
                if (da > 180f) da -= 360f
                if (da < -180f) da += 360f
                val cx = width / 2f
                val cy = height / 2f
                val vx = pMx - cx - tx
                val vy = pMy - cy - ty
                val rad = Math.toRadians(da.toDouble())
                val cs = cos(rad).toFloat()
                val sn = sin(rad).toFloat()
                tx = mx - cx - f * (vx * cs - vy * sn)
                ty = my - cy - f * (vx * sn + vy * cs)
                rot += da
            }
            z = nz
            clamp()
            applyTransform()
            notifyPage()
        }
        pMx = mx
        pMy = my
        pDist = dist
        pAng = ang
    }

    private fun oneFinger(e: MotionEvent) {
        val dx = e.x - lx
        val dy = e.y - ly
        lx = e.x
        ly = e.y
        if (continuous || z > 1.05f) {
            tx += dx
            ty += dy
            clamp()
            applyTransform()
            notifyPage()
        }
    }

    private fun goTo(n: Int, forward: Boolean) {
        if (n < 0 || n >= specs.size || n == index) return
        index = n
        resetTransform()
        rebuild()
        single?.let {
            it.alpha = 0.4f
            it.translationX = (if (forward) -0.2f else 0.2f) * width
            it.animate().alpha(1f).translationX(0f).setDuration(160).start()
        }
        onPage?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (specs.isEmpty()) return false
        // El stylus no mueve la vista: queda reservado para dibujar.
        if (e.pointerCount == 1 && e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return false
        gd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                mode = 1
                multi = false
                sx = e.x
                sy = e.y
                lx = e.x
                ly = e.y
                vel?.recycle()
                vel = VelocityTracker.obtain()
                vel?.addMovement(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    mode = 2
                    multi = true
                    initTwo(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                vel?.addMovement(e)
                if (mode == 2 && e.pointerCount >= 2) twoFinger(e)
                else if (mode == 1) oneFinger(e)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount == 2) {
                    val rest = if (e.actionIndex == 0) 1 else 0
                    mode = 1
                    lx = e.getX(rest)
                    ly = e.getY(rest)
                    pDist = 0f
                }
            }
            MotionEvent.ACTION_UP -> {
                vel?.addMovement(e)
                vel?.computeCurrentVelocity(1000)
                if (!multi) {
                    if (continuous) {
                        val b = contBounds()
                        scroller.fling(
                            tx.toInt(), ty.toInt(), (vel?.xVelocity ?: 0f).toInt(), (vel?.yVelocity ?: 0f).toInt(),
                            b[0].toInt(), b[1].toInt(), b[2].toInt(), b[3].toInt()
                        )
                        postInvalidateOnAnimation()
                    } else if (z <= 1.05f) {
                        val dx = e.x - sx
                        val dy = e.y - sy
                        if (abs(dx) > max(dp(64), (width * 0.12f).toInt()) && abs(dx) > 2 * abs(dy)) {
                            // Izquierda = anterior, derecha = siguiente.
                            if (dx < 0) goTo(index - 1, false) else goTo(index + 1, true)
                        }
                    }
                }
                vel?.recycle()
                vel = null
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                vel?.recycle()
                vel = null
                mode = 0
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = scroller.currX.toFloat()
            ty = scroller.currY.toFloat()
            clamp()
            applyTransform()
            notifyPage()
            postInvalidateOnAnimation()
        }
    }
}
