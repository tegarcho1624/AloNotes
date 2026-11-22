package com.alonotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Área de hojas del editor, con dos vistas:
 *  - Una hoja (Single Page View / Fit to Screen): la hoja actual centrada y ajustada a la pantalla.
 *    Sin herramienta: un dedo desliza para cambiar de hoja (a la izquierda = siguiente, a la derecha
 *    = anterior). Pellizco = zoom (mínimo 25 % del ajuste), dos dedos = rotar (solo visual, no se
 *    guarda), con zoom un dedo mueve la hoja, doble toque = restablecer.
 *    Con herramienta (lápiz o borrador): un dedo o el stylus dibuja/borra; dos dedos siguen
 *    haciendo zoom, rotación y desplazamiento. Si se usa stylus, los dedos sueltos (la palma) se ignoran.
 *  - Continua (Continuous Scroll / Multi-page): todas las hojas en columna; solo lectura.
 * Los trazos se guardan en milímetros de la hoja, así que no dependen del zoom ni de la rotación.
 */
internal class PageViewport(ctx: Context) : FrameLayout(ctx) {
    class Spec(val wMm: Float, val hMm: Float, val bg: Int)

    var continuous = false
        private set
    var index = 0
        private set
    var onPage: (() -> Unit)? = null

    /** 0 ninguna, 1 lápiz, 2 borrador, 3 marca texto, 4 seleccionar (solo en la vista de una hoja). */
    var tool = 0
        set(v) {
            if (v != field) {
                cancelStroke()
                endManip()
                if (v != 4) selItem = null
            }
            field = v
            updateMore()
            invalidate()
        }
    var strokes: DocStrokes? = null
    var onChanged: ((Int) -> Unit)? = null

    /** Se llama al terminar de mover, redimensionar o girar un elemento (hay que guardar las capas). */
    var onItemsChanged: (() -> Unit)? = null

    /** Elemento seleccionado (herramienta Seleccionar). */
    private var selItem: SelectableItem? = null

    /** Botón flotante de 3 puntos, anclado al lado izquierdo del recuadro de selección. */
    private var more: View? = null

    private var specs: List<Spec> = emptyList()
    private var single: PageView? = null
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

    // Dibujo.
    private var penDown = false
    private var live: FloatList? = null
    private var erasing = false
    private var erasedDirty = false
    private var lastStylusT = -10000L
    private var penType = 0
    private var penColor = 0
    private var penW = 0.8f
    private var penK = 1f
    private var eraserR = 4f
    private var sxm = 0f // posición suavizada (mm)
    private var sym = 0f
    private var lastX = 0f // último punto crudo (mm)
    private var lastY = 0f
    private var lastT = 0L
    private var lastP = 0.7f
    private var eLX = 0f
    private var eLY = 0f

    private fun dp(v: Number) = (v.toFloat() * d).roundToInt()
    private fun minZ() = if (continuous) 1f else 0.25f
    private val maxZ = 5f

    // Selección y transformación.
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var manip = 0 // 0 nada, 1 mover, 2 redimensionar, 3 girar
    private var mHandle = -1
    private var mP0x = 0f
    private var mP0y = 0f
    private var mRot0 = 0f
    private var mAng0 = 0f
    private var mMoved = false
    private val mB0 = RectF()
    private val ovStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ovFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private companion object {
        const val M_MOVE = 1
        const val M_RESIZE = 2
        const val M_ROTATE = 3

        // Tiradores: 0 sup-izq, 1 sup, 2 sup-der, 3 izq, 4 der, 5 inf-izq, 6 inf, 7 inf-der (8 = círculo de giro).
        val HX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val HY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    }

    init {
        ovStroke.style = Paint.Style.STROKE
        ovStroke.strokeWidth = 2f * d
        ovFill.style = Paint.Style.FILL
    }

    // ---------- API ----------

    fun restore(cont: Boolean, idx: Int) {
        continuous = cont
        index = idx
    }

    fun setPages(list: List<Spec>) {
        cancelStroke()
        endManip()
        selItem = null
        specs = list
        index = index.coerceIn(0, max(0, list.size - 1))
        resetTransform()
        rebuild()
    }

    fun setMode(cont: Boolean) {
        if (cont == continuous) return
        cancelStroke()
        if (continuous) index = visibleIndex()
        continuous = cont
        if (cont) tool = 0
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

    /** Vuelve a dibujar la hoja actual (por ejemplo, tras cambiar o eliminar una capa). */
    fun refreshPage() {
        val s = selItem
        if (s != null && !itemExists(s)) {
            selItem = null
            manip = 0
        }
        single?.invalidate()
        updateMore()
        invalidate()
    }

    /** Tamaño de la hoja actual (mm), o null si no hay hojas. */
    fun pageSpec(): Spec? = specs.getOrNull(index)

    /** Selecciona un elemento de la hoja actual (null = quitar la selección). */
    fun selectItem(item: SelectableItem?) {
        selItem = item
        manip = 0
        single?.invalidate()
        updateMore()
        invalidate()
    }

    fun prevPage() = goToPage(index - 1)
    fun nextPage() = goToPage(index + 1)

    fun goToPage(i: Int) {
        if (i < 0 || i >= specs.size || i == index) return
        if (continuous) {
            index = i
            scrollToIndex(i)
            onPage?.invoke()
        } else {
            goTo(i, i > index)
        }
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
        // Se reconstruye justo después del layout: añadir hojas en pleno layout las dejaba sin medir.
        if (w > 0 && h > 0) post { rebuild() }
    }

    private fun pageAt(i: Int): PageView {
        val sp = specs[i]
        return PageView(context, sp) { strokes?.page(i) }
    }

    private fun rebuild() {
        cancelStroke()
        removeAllViews()
        single = null
        column = null
        if (specs.isEmpty() || width == 0 || height == 0) return
        if (!continuous) {
            val i = index.coerceIn(0, specs.size - 1)
            val sp = specs[i]
            val k = min(width / sp.wMm, height / sp.hMm)
            val v = pageAt(i)
            addView(v, LayoutParams((sp.wMm * k).roundToInt(), (sp.hMm * k).roundToInt(), Gravity.CENTER))
            single = v
            attachMore()
        } else {
            val col = LinearLayout(context)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(0, dp(24), 0, dp(24))
            val base = min(width - dp(64), dp(720)).toFloat()
            var totalH = dp(24) * 2
            for ((i, sp) in specs.withIndex()) {
                val k = base / max(sp.wMm, sp.hMm)
                val v = pageAt(i)
                val ph = (sp.hMm * k).roundToInt()
                val lp = LinearLayout.LayoutParams((sp.wMm * k).roundToInt(), ph)
                lp.bottomMargin = dp(20)
                col.addView(v, lp)
                totalH += ph + dp(20)
            }
            // Altura exacta (hoja 1 arriba, última abajo) para poder desplazarse.
            addView(col, LayoutParams(width, totalH))
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
        updateMore() // zoom, giro o desplazamiento de la vista: el botón acompaña al recuadro
    }

    private fun notifyPage() {
        if (continuous) index = visibleIndex()
        onPage?.invoke()
    }

    /** Punto de pantalla (de este contenedor) -> milímetros de la hoja actual, deshaciendo zoom y rotación. */
    private fun toPage(px: Float, py: Float): FloatArray? {
        val v = single ?: return null
        val pts = floatArrayOf(px - v.left, py - v.top)
        val inv = Matrix()
        if (!v.matrix.invert(inv)) return null
        inv.mapPoints(pts)
        val sc = v.width / v.spec.wMm
        if (sc <= 0f) return null
        return floatArrayOf(pts[0] / sc, pts[1] / sc)
    }

    // ---------- Dibujo y borrado ----------

    private fun beginStroke(e: MotionEvent, stylus: Boolean) {
        // Palma: si hay stylus en uso, los dedos sueltos no dibujan.
        if (!stylus && e.eventTime - lastStylusT < 1500) return
        val v = single ?: return
        val pt = toPage(e.x, e.y) ?: return
        // No se dibuja ni se borra en una capa oculta (no se vería lo que pasa).
        val pl = strokes?.page(index)
        if (pl != null && !pl.layers[pl.selected].isVisible) {
            Toast.makeText(context, "La capa está oculta", Toast.LENGTH_SHORT).show()
            return
        }
        penDown = true
        lastX = pt[0]
        lastY = pt[1]
        lastT = e.eventTime
        lastP = if (stylus) e.pressure.coerceIn(0.05f, 1f) else 0.7f
        if (tool == 2) {
            erasing = true
            erasedDirty = false
            eraserR = PenStore.eraserMm(PenStore.eraser(context))
            eLX = pt[0]
            eLY = pt[1]
            eraseTo(pt)
        } else {
            if (tool == 3) {
                penType = 3
                penColor = PenStore.hlColors(context)[PenStore.hlColorIdx(context)]
                penW = PenStore.hlWidthMm(PenStore.hlWidth(context))
            } else {
                penType = PenStore.type(context)
                penColor = PenStore.colors(context)[PenStore.colorIdx(context)]
                penW = PenStore.widthMm(PenStore.width(context))
            }
            penK = 1f - (PenStore.smoothing(context) / 100f) * 0.9f
            live = null
            val sp = v.spec
            if (pt[0] in 0f..sp.wMm && pt[1] in 0f..sp.hMm) startLive(pt[0], pt[1], lastP)
            refreshLive()
        }
    }

    private fun startLive(x: Float, y: Float, p: Float) {
        val l = FloatList()
        l.add(x)
        l.add(y)
        l.add(p)
        live = l
        sxm = x
        sym = y
    }

    private fun feed(px: Float, py: Float, pr: Float, t: Long, stylus: Boolean) {
        val v = single ?: return
        val pt = toPage(px, py) ?: return
        if (erasing) {
            eraseTo(pt)
            return
        }
        val p: Float
        if (stylus) {
            p = pr.coerceIn(0.05f, 1f)
        } else {
            val dt = max(1L, t - lastT).toFloat()
            val speed = hypot(pt[0] - lastX, pt[1] - lastY) / dt // mm por ms
            val target = (0.9f - speed * 0.9f).coerceIn(0.25f, 0.9f)
            p = lastP + (target - lastP) * 0.3f
        }
        lastX = pt[0]
        lastY = pt[1]
        lastT = t
        lastP = p
        val sp = v.spec
        if (pt[0] !in 0f..sp.wMm || pt[1] !in 0f..sp.hMm) {
            commitLive() // salió de la hoja: termina el trazo; si vuelve a entrar empieza otro
            return
        }
        if (live == null) {
            startLive(pt[0], pt[1], p)
            return
        }
        sxm += (pt[0] - sxm) * penK // suavizado
        sym += (pt[1] - sym) * penK
        addDense(sxm, sym, p)
    }

    /** Añade un punto asegurando ≤ 0,6 mm entre puntos (el borrador y el trazo lo necesitan). */
    private fun addDense(x: Float, y: Float, p: Float) {
        val l = live ?: return
        if (l.isEmpty()) {
            l.add(x)
            l.add(y)
            l.add(p)
            return
        }
        val px = l[l.size - 3]
        val py = l[l.size - 2]
        val pp = l[l.size - 1]
        val dist = hypot(x - px, y - py)
        if (dist < 0.15f) return
        val steps = ceil(dist / 0.6f).toInt().coerceAtLeast(1)
        for (k in 1..steps) {
            val t = k / steps.toFloat()
            l.add(px + (x - px) * t)
            l.add(py + (y - py) * t)
            l.add(pp + (p - pp) * t)
        }
    }

    private fun refreshLive() {
        val v = single ?: return
        val l = live
        v.live = if (l != null && !l.isEmpty()) Stroke(penType, penColor, penW, l.toArray()) else null
        v.invalidate()
    }

    private fun commitLive() {
        val l = live ?: return
        live = null
        val v = single
        v?.live = null
        if (l.size >= 3) {
            strokes?.page(index)?.active?.add(Stroke(penType, penColor, penW, l.toArray()))
            onChanged?.invoke(index)
        }
        v?.invalidate()
    }

    private fun eraseTo(pt: FloatArray) {
        val list = strokes?.page(index)?.active ?: return
        val dx = pt[0] - eLX
        val dy = pt[1] - eLY
        val steps = max(1, ceil(hypot(dx, dy) / (eraserR * 0.5f)).toInt())
        for (k in 1..steps) {
            val f = k / steps.toFloat()
            if (StrokeOps.eraseAt(list, eLX + dx * f, eLY + dy * f, eraserR)) erasedDirty = true
        }
        eLX = pt[0]
        eLY = pt[1]
        single?.setCursor(pt[0], pt[1], eraserR)
        if (erasedDirty) single?.invalidate()
    }

    private fun drawMove(e: MotionEvent, stylus: Boolean) {
        for (h in 0 until e.historySize) {
            feed(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h), e.getHistoricalEventTime(h), stylus)
        }
        feed(e.x, e.y, e.pressure, e.eventTime, stylus)
        if (!erasing) refreshLive()
    }

    private fun endStroke(e: MotionEvent) {
        if (erasing) {
            erasing = false
            single?.clearCursor()
            if (erasedDirty) onChanged?.invoke(index)
            erasedDirty = false
        } else {
            // Último punto sin suavizar para que el trazo llegue hasta donde se levantó la punta.
            val pt = toPage(e.x, e.y)
            val sp = single?.spec
            if (pt != null && sp != null && live != null && pt[0] in 0f..sp.wMm && pt[1] in 0f..sp.hMm) {
                addDense(pt[0], pt[1], lastP)
            }
            commitLive()
        }
        penDown = false
    }

    private fun cancelStroke() {
        if (erasing) {
            erasing = false
            single?.clearCursor()
            if (erasedDirty) onChanged?.invoke(index)
            erasedDirty = false
        }
        if (live != null) {
            live = null
            single?.live = null
            single?.invalidate()
        }
        penDown = false
    }

    // ---------- Selección y transformación ----------

    private fun itemExists(item: SelectableItem): Boolean {
        val pl = strokes?.page(index) ?: return false
        for (ly in pl.layers) for (it in ly.items) if (it === item) return true
        return false
    }

    /** Punto de la hoja (mm) -> punto de pantalla de este contenedor, aplicando zoom y rotación de la vista. */
    private fun pageToScreen(xMm: Float, yMm: Float): FloatArray? {
        val v = single ?: return null
        val sc = v.width / v.spec.wMm
        if (sc <= 0f) return null
        val pts = floatArrayOf(xMm * sc, yMm * sc)
        v.matrix.mapPoints(pts)
        return floatArrayOf(pts[0] + v.left.toFloat(), pts[1] + v.top.toFloat())
    }

    /**
     * Posición en pantalla de los 9 tiradores (x, y por cada uno): los 8 del recuadro (ver HX/HY) y el círculo de giro,
     * que está sobre el borde superior, a 32 dp hacia "arriba" del elemento.
     */
    private fun handlePts(item: SelectableItem): FloatArray? {
        val b = item.bounds
        val w = b.width()
        val h = b.height()
        val out = FloatArray(18)
        for (i in 0 until 8) {
            val p = Xf.toPage(b.centerX(), b.centerY(), item.rotation, HX[i] * w / 2f, HY[i] * h / 2f)
            val s = pageToScreen(p[0], p[1]) ?: return null
            out[i * 2] = s[0]
            out[i * 2 + 1] = s[1]
        }
        val c = pageToScreen(b.centerX(), b.centerY()) ?: return null
        var ux = out[2] - c[0]
        var uy = out[3] - c[1]
        val n = hypot(ux, uy)
        if (n < 1e-3f) {
            ux = 0f
            uy = -1f
        } else {
            ux /= n
            uy /= n
        }
        out[16] = out[2] + ux * 32f * d
        out[17] = out[3] + uy * 32f * d
        return out
    }

    /** Índice del tirador tocado (0..7, 8 = giro) o -1. El círculo de giro tiene prioridad. */
    private fun hitHandle(pts: FloatArray, x: Float, y: Float): Int {
        if (hypot(x - pts[16], y - pts[17]) <= 26f * d) return 8
        var best = -1
        var bd = 22f * d
        for (i in 0 until 8) {
            val dd = hypot(x - pts[i * 2], y - pts[i * 2 + 1])
            if (dd <= bd) {
                bd = dd
                best = i
            }
        }
        return best
    }

    /** Elemento más arriba bajo el punto (capas visibles, de la de arriba a la de abajo). */
    private fun hitItem(xMm: Float, yMm: Float): SelectableItem? {
        val pl = strokes?.page(index) ?: return null
        for (li in pl.layers.indices.reversed()) {
            val ly = pl.layers[li]
            if (!ly.isVisible) continue
            for (k in ly.items.indices.reversed()) if (ly.items[k].hit(xMm, yMm)) return ly.items[k]
        }
        return null
    }

    private fun beginSelect(e: MotionEvent, stylus: Boolean) {
        if (!stylus && e.eventTime - lastStylusT < 1500) return // palma
        val p = toPage(e.x, e.y) ?: return
        val sel = selItem
        if (sel != null) {
            val pts = handlePts(sel)
            val h = if (pts != null) hitHandle(pts, e.x, e.y) else -1
            if (h >= 0) {
                startManip(sel, if (h == 8) M_ROTATE else M_RESIZE, h, p)
                return
            }
            if (sel.hit(p[0], p[1])) {
                startManip(sel, M_MOVE, -1, p)
                return
            }
        }
        val hit = hitItem(p[0], p[1])
        selItem = hit
        if (hit != null) startManip(hit, M_MOVE, -1, p) // se selecciona y ya se puede arrastrar
        updateMore()
        invalidate()
    }

    private fun startManip(item: SelectableItem, kind: Int, handle: Int, p: FloatArray) {
        manip = kind
        mHandle = handle
        mP0x = p[0]
        mP0y = p[1]
        mB0.set(item.bounds)
        mRot0 = item.rotation
        mAng0 = Xf.angleDeg(p[0] - mB0.centerX(), p[1] - mB0.centerY())
        mMoved = false
        invalidate()
    }

    private fun selMove(e: MotionEvent) {
        val item = selItem ?: return
        if (!mMoved && hypot(e.x - sx, e.y - sy) < touchSlop) return // un toque no mueve nada
        val p = toPage(e.x, e.y) ?: return
        mMoved = true
        when (manip) {
            M_MOVE -> {
                item.bounds.set(mB0)
                item.bounds.offset(p[0] - mP0x, p[1] - mP0y)
            }
            M_RESIZE -> Xf.resize(mB0, mRot0, HX[mHandle], HY[mHandle], p[0], p[1], item.lockAspect, item.bounds)
            M_ROTATE -> {
                // Ángulo del puntero respecto al centro (Math.atan2); se suma lo que giró desde que se agarró el círculo.
                val ang = Xf.angleDeg(p[0] - mB0.centerX(), p[1] - mB0.centerY())
                item.rotation = Xf.normalize(mRot0 + (ang - mAng0))
            }
        }
        single?.invalidate()
        updateMore() // el botón de 3 puntos sigue al recuadro
        invalidate()
    }

    private fun endManip() {
        if (manip == 0) return
        manip = 0
        if (mMoved) {
            mMoved = false
            onItemsChanged?.invoke()
        }
    }

    // ---------- Botón flotante de 3 puntos ----------

    private fun createMore(): View {
        val b = UiKit.iconBtn(context, Icons.DOTS_V, ThemeUtils.accentHi(context), 44, 22) { toggleMenu() }
        UiKit.ripple(context, b, 22, context.getColor(R.color.sf3))
        b.elevation = 6f * d
        b.visibility = INVISIBLE
        return b
    }

    /** Pone el botón sobre la hoja (rebuild() quita todas las vistas del contenedor). */
    private fun attachMore() {
        val b = more ?: createMore().also { more = it }
        if (b.parent == null) addView(b, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START))
    }

    private fun hideMore(b: View) {
        if (b.visibility != INVISIBLE) b.visibility = INVISIBLE
        dismissMenu() // sin selección no hay menú: desaparecen juntos
    }

    // ---------- Menú flotante de la imagen (PopupWindow) ----------

    private var menu: PopupWindow? = null
    private var menuClosedAt = 0L

    private fun dismissMenu() {
        menu?.dismiss()
    }

    /** Abre el menú o, si ya está abierto, lo cierra. */
    private fun toggleMenu() {
        val b = more ?: return
        val open = menu
        if (open != null) {
            open.dismiss()
            return
        }
        // Tocar el botón con el menú abierto: el toque "fuera" ya lo cerró un instante antes; no se vuelve a abrir.
        if (SystemClock.uptimeMillis() - menuClosedAt < 300) return
        showMenu(b)
    }

    /**
     * Siete botones en columna, de arriba abajo: enlace, recortar, recorte libre, voltear horizontal, voltear vertical
     * (el mismo ícono girado 90°), opacidad y bloquear. Por ahora solo cierran el menú y avisan "Próximamente".
     * Se coloca al lado del botón de 3 puntos (a su izquierda, o a su derecha si no cabe) y centrado con él en vertical.
     */
    private fun showMenu(anchor: View) {
        val ctx = context
        val ink = ctx.getColor(R.color.ink)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(4), dp(4), dp(4), dp(4))
        col.background = UiKit.rect(ctx, ctx.getColor(R.color.sf2), 16, ctx.getColor(R.color.ln2))
        val items = listOf(
            Triple(Icons.LINK, "Enlace", 0f),
            Triple(Icons.CROP, "Recortar", 0f),
            Triple(Icons.CUT, "Recorte libre", 0f),
            Triple(Icons.FLIP, "Voltear horizontal", 0f),
            Triple(Icons.FLIP, "Voltear vertical", 90f),
            Triple(Icons.OPACITY, "Opacidad", 0f),
            Triple(Icons.LOCK, "Bloquear objeto", 0f)
        )
        for ((icon, name, rot) in items) {
            val btn = UiKit.iconBtn(ctx, icon, ink, 44, 22) {
                dismissMenu()
                Toast.makeText(ctx, "$name: Próximamente", Toast.LENGTH_SHORT).show()
            }
            btn.rotation = rot
            col.addView(btn, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        col.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = col.measuredWidth
        val h = col.measuredHeight
        val pw = PopupWindow(col, w, h, false)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // No toma el foco: un toque fuera lo cierra y además llega a la hoja (por ejemplo, para deseleccionar).
        pw.isFocusable = false
        pw.isOutsideTouchable = true
        pw.setOnDismissListener {
            menu = null
            menuClosedAt = SystemClock.uptimeMillis()
        }
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val gap = dp(8)
        val bx = loc[0] + anchor.x
        val by = loc[1] + anchor.y
        var x = bx - gap - w
        if (x < loc[0]) x = bx + dp(44) + gap
        val y = (by + dp(22) - h / 2f).coerceIn(loc[1].toFloat(), max(loc[1].toFloat(), (loc[1] + height - h).toFloat()))
        pw.showAtLocation(this, Gravity.TOP or Gravity.START, x.roundToInt(), y.roundToInt())
        menu = pw
    }

    override fun onDetachedFromWindow() {
        dismissMenu()
        super.onDetachedFromWindow()
    }

    /**
     * Visible solo con un elemento seleccionado. Se coloca a la izquierda del recuadro, centrado en su borde izquierdo
     * (el tirador de la mitad izquierda), a 36 dp de él en la dirección "hacia afuera" del elemento, así que acompaña
     * el giro, el tamaño, el zoom y el desplazamiento. Se mantiene dentro del contenedor.
     */
    private fun updateMore() {
        val b = more ?: return
        val item = selItem
        if (item == null || tool != 4 || continuous || b.parent !== this) {
            hideMore(b)
            return
        }
        val pts = handlePts(item)
        val c = pageToScreen(item.bounds.centerX(), item.bounds.centerY())
        if (pts == null || c == null || width == 0 || height == 0) {
            hideMore(b)
            return
        }
        var ux = pts[6] - c[0] // del centro hacia el borde izquierdo (tirador 3)
        var uy = pts[7] - c[1]
        val n = hypot(ux, uy)
        if (n < 1e-3f) {
            ux = -1f
            uy = 0f
        } else {
            ux /= n
            uy /= n
        }
        val half = 22f * d
        val gap = half + 14f * d
        val bx = (pts[6] + ux * gap).coerceIn(half, max(half, width - half))
        val by = (pts[7] + uy * gap).coerceIn(half, max(half, height - half))
        b.x = bx - half
        b.y = by - half
        if (b.visibility != VISIBLE) b.visibility = VISIBLE
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, bt: Int) {
        super.onLayout(changed, l, t, r, bt)
        if (changed) dismissMenu() // cambió el tamaño (por ejemplo, al girar la tablet): el menú quedaría mal colocado
        updateMore()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawSelection(canvas)
    }

    /** Recuadro, 8 tiradores y círculo de giro del elemento seleccionado (se dibuja encima de la hoja, sin recorte). */
    private fun drawSelection(c: Canvas) {
        val item = selItem ?: return
        if (tool != 4 || continuous) return
        val pts = handlePts(item) ?: return
        val acc = ThemeUtils.accentHi(context)
        val ink = context.getColor(R.color.ink)
        ovStroke.color = acc
        val path = Path()
        path.moveTo(pts[0], pts[1])
        path.lineTo(pts[4], pts[5])
        path.lineTo(pts[14], pts[15])
        path.lineTo(pts[10], pts[11])
        path.close()
        c.drawPath(path, ovStroke)
        c.drawLine(pts[2], pts[3], pts[16], pts[17], ovStroke)
        for (i in 0 until 8) {
            ovFill.color = ink
            c.drawCircle(pts[i * 2], pts[i * 2 + 1], 6f * d, ovFill)
            c.drawCircle(pts[i * 2], pts[i * 2 + 1], 6f * d, ovStroke)
        }
        ovFill.color = acc
        c.drawCircle(pts[16], pts[17], 9f * d, ovFill)
        ovStroke.color = ink
        c.drawCircle(pts[16], pts[17], 9f * d, ovStroke)
    }

    // ---------- Gestos de vista ----------

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
        if (continuous || ((tool == 0 || tool == 4) && z > 1.05f)) {
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
        endManip()
        selItem = null
        resetTransform()
        rebuild()
        single?.let {
            it.alpha = 0.4f
            it.translationX = (if (forward) 0.2f else -0.2f) * width
            it.animate().alpha(1f).translationX(0f).setDuration(160).start()
        }
        onPage?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (specs.isEmpty()) return false
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val toolActive = tool != 0 && !continuous && single != null
        val drawTool = toolActive && tool != 4
        val selTool = toolActive && tool == 4
        if (stylus) lastStylusT = e.eventTime
        // Sin herramienta el stylus no mueve la vista (queda reservado para dibujar).
        if (e.pointerCount == 1 && stylus && !toolActive) return false
        if (!toolActive) gd.onTouchEvent(e)
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
                if (drawTool) beginStroke(e, stylus) else if (selTool) beginSelect(e, stylus)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    cancelStroke() // un segundo dedo = gesto de vista, no trazo
                    endManip()
                    mode = 2
                    multi = true
                    initTwo(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                vel?.addMovement(e)
                if (mode == 2 && e.pointerCount >= 2) twoFinger(e)
                else if (mode == 1) {
                    if (penDown) drawMove(e, stylus) else if (manip != 0) selMove(e) else oneFinger(e)
                }
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
                if (penDown) endStroke(e)
                endManip()
                if (!multi && !toolActive) {
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
                            // Las hojas van de izquierda a derecha: deslizar a la izquierda avanza.
                            if (dx < 0) goTo(index + 1, true) else goTo(index - 1, false)
                        }
                    }
                }
                vel?.recycle()
                vel = null
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                if (penDown) cancelStroke()
                endManip()
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
