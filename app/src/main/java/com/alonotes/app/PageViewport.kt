package com.alonotes.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import android.widget.SeekBar
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
                if (v != 4) {
                    cancelCrop()
                    selItem = null
                    multiSel.clear()
                }
            }
            field = v
            updateMore()
            invalidate()
        }
    var strokes: DocStrokes? = null
    var onChanged: ((Int) -> Unit)? = null

    /** Pide al editor mostrar el menú de enlace de la imagen seleccionada. */
    var onEditLink: ((ImageItem) -> Unit)? = null

    /** Se tocó una imagen con enlace (herramienta lápiz, marcador o ninguna): el editor lo abre. */
    var onOpenLink: ((ImageItem) -> Unit)? = null

    private var downTime = 0L
    private var linkCand: ImageItem? = null // imagen con enlace bajo el dedo
    private var linkHint: ImageItem? = null // ... ya mantenida un momento: se muestra el ícono de cadena
    private val linkHintRun = Runnable {
        linkHint = linkCand
        invalidate()
    }

    /** Se llama al completarse una acción, con la foto de la hoja de ANTES (para el historial de deshacer). */
    var onRecord: ((PageSnap) -> Unit)? = null

    private fun snapNow(): PageSnap? = strokes?.page(index)?.snapshot()

    /** Se llama al terminar de mover, redimensionar o girar un elemento (hay que guardar las capas). */
    var onItemsChanged: (() -> Unit)? = null

    /** Elemento seleccionado (herramienta Seleccionar). Con varios seleccionados es null y se usa [multiSel]. */
    private var selItem: SelectableItem? = null

    /** Selección múltiple (2 o más): su caja de grupo es [grpB] con el giro [grpRot]. */
    private val multiSel = ArrayList<SelectableItem>()
    private val grpB = RectF()
    private var grpRot = 0f
    private val gB0 = RectF()
    private var gRot0 = 0f
    private val gStart = ArrayList<FloatArray>() // por objeto: l, t, r, b, giro al empezar el gesto

    /** Barra de herramientas de selección (con la herramienta Seleccionar). */
    private var selBar: View? = null

    /** El usuario tocó un botón de la barra de selección (ver [SelAct]); lo atiende el editor. */
    var onSelAction: ((Int) -> Unit)? = null

    // Modo recorte: el recuadro pasa a ser la ventana de recorte; la imagen se mueve por debajo.
    private var cropMode = false
    private var cropImg: ImageItem? = null
    private val cropB = RectF() // ventana de recorte (mm de la hoja, con el giro de la imagen)
    private val cropOrig = RectF() // posición de la imagen al entrar (para cancelar)
    private var cropBar: View? = null

    // Modo recorte libre (lazo): el trazo vive en mm de la hoja, así no depende del zoom ni de la rotación de la vista.
    private var lassoMode = false
    private var lassoImg: ImageItem? = null
    private val lassoPts = ArrayList<Float>() // x, y, x, y...
    private var lassoClosed = false

    /** Botón flotante de 3 puntos, anclado al lado izquierdo del recuadro de selección. */
    private var more: View? = null

    /** Botón flotante de candado: ocupa el lugar del de 3 puntos cuando la imagen seleccionada está bloqueada. */
    private var lockBtn: View? = null

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
    private var erasePre: PageSnap? = null // la hoja antes de empezar a borrar (se registra si algo cambió)
    private var manipPre: PageSnap? = null // la hoja antes de mover/redimensionar/girar
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
    private val ovText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ovDash = DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)

    private companion object {
        const val M_MOVE = 1
        const val M_RESIZE = 2
        const val M_ROTATE = 3
        const val M_CROP_RESIZE = 4
        const val M_CROP_MOVE = 5
        const val M_LASSO = 6
        const val M_GMOVE = 7
        const val M_GRESIZE = 8
        const val M_GROT = 9

        // Tiradores: 0 sup-izq, 1 sup, 2 sup-der, 3 izq, 4 der, 5 inf-izq, 6 inf, 7 inf-der (8 = círculo de giro).
        val HX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val HY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
    }

    init {
        ovStroke.style = Paint.Style.STROKE
        ovStroke.strokeWidth = 2f * d
        ovFill.style = Paint.Style.FILL
        ovText.textSize = 16f * d
        ovText.textAlign = Paint.Align.CENTER
        ovText.setShadowLayer(3f * d, 0f, 0f, 0xFF000000.toInt())
    }

    // ---------- API ----------

    fun restore(cont: Boolean, idx: Int) {
        continuous = cont
        index = idx
    }

    fun setPages(list: List<Spec>) {
        cancelStroke()
        endManip()
        cancelCrop()
        selItem = null
        multiSel.clear()
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
            cancelCrop()
            selItem = null
            manip = 0
        }
        if (multiSel.isNotEmpty()) {
            multiSel.retainAll { itemExists(it) }
            if (multiSel.size == 1) {
                selItem = multiSel[0]
                multiSel.clear()
            } else if (multiSel.isNotEmpty()) {
                computeGroup()
            }
        }
        single?.invalidate()
        updateMore()
        invalidate()
    }

    /** Objetos seleccionados (uno o varios). */
    fun currentSelection(): List<SelectableItem> {
        if (multiSel.isNotEmpty()) return ArrayList(multiSel)
        val one = selItem
        return if (one != null) listOf(one) else emptyList()
    }

    /** Selecciona [items] (de la hoja actual): ninguno, uno (selección normal) o varios (recuadro de grupo). */
    fun setSelection(items: List<SelectableItem>) {
        cancelCrop()
        manip = 0
        multiSel.clear()
        selItem = null
        if (items.size == 1) {
            selItem = items[0]
        } else if (items.size > 1) {
            multiSel.addAll(items)
            computeGroup()
        }
        updateMore()
        single?.invalidate()
        invalidate()
    }

    /** Selecciona todos los objetos (sin bloquear) de la capa actual. Devuelve cuántos son. */
    fun selectAll(): Int {
        val pl = strokes?.page(index) ?: return 0
        val items = pl.layers[pl.selected].items.filter { !it.isLocked }
        setSelection(items)
        return items.size
    }

    /** Ids de lo seleccionado (para volver a seleccionarlo después de deshacer o rehacer). */
    fun selectedIds(): List<String> = currentSelection().map { it.id }

    /** Tras restaurar la hoja los objetos son nuevos: vuelve a seleccionar los de esos ids (si siguen y la herramienta es Seleccionar). */
    fun reselect(ids: List<String>) {
        val found = ArrayList<SelectableItem>()
        val pl = strokes?.page(index)
        if (tool == 4 && pl != null && ids.isNotEmpty()) {
            for (ly in pl.layers) for (im in ly.items) if (ids.contains(im.id)) found.add(im)
        }
        setSelection(found)
    }

    /** Caja que abarca a todos los objetos de la selección múltiple (sin giro al empezar). */
    private fun computeGroup() {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (im in multiSel) {
            val b = im.bounds
            for (k in 0 until 4) {
                val p = Xf.toPage(
                    b.centerX(), b.centerY(), im.rotation,
                    (if (k % 2 == 0) -1 else 1) * b.width() / 2f, (if (k < 2) -1 else 1) * b.height() / 2f
                )
                if (p[0] < x0) x0 = p[0]
                if (p[0] > x1) x1 = p[0]
                if (p[1] < y0) y0 = p[1]
                if (p[1] > y1) y1 = p[1]
            }
        }
        grpB.set(x0, y0, x1, y1)
        grpRot = 0f
    }

    /** Sale de los modos de recorte y suelta cualquier gesto, sin cambios (antes de deshacer o rehacer). */
    fun cancelModes() {
        cancelCrop()
        manip = 0
        mMoved = false
        manipPre = null
    }

    /** Tamaño de la hoja actual (mm), o null si no hay hojas. */
    fun pageSpec(): Spec? = specs.getOrNull(index)

    /** Selecciona un elemento de la hoja actual (null = quitar la selección). */
    fun selectItem(item: SelectableItem?) {
        cancelCrop()
        multiSel.clear()
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
            attachCropBar()
            attachSelBar()
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
            erasePre = snapNow()
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
            val pre = snapNow()
            strokes?.page(index)?.active?.add(Stroke(penType, penColor, penW, l.toArray()))
            onChanged?.invoke(index)
            if (pre != null) onRecord?.invoke(pre)
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
            if (erasedDirty) {
                onChanged?.invoke(index)
                recordErase()
            }
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

    /** Tira el trazo en curso sin guardarlo (cuando el toque era para abrir un enlace). */
    private fun discardStroke() {
        live = null
        single?.live = null
        single?.invalidate()
        penDown = false
    }

    // ---------- Enlaces de imagen ----------

    /** Imagen con enlace bajo el punto tocado (capas visibles, de arriba abajo), o null. Las imágenes sin enlace no cuentan. */
    private fun linkAt(e: MotionEvent): ImageItem? {
        val p = toPage(e.x, e.y) ?: return null
        val pl = strokes?.page(index) ?: return null
        for (li in pl.layers.indices.reversed()) {
            val ly = pl.layers[li]
            if (!ly.isVisible) continue
            for (k in ly.items.indices.reversed()) {
                val im = ly.items[k]
                if (im.linkType != ImageItem.LINK_NONE && im.hit(p[0], p[1])) return im
            }
        }
        return null
    }

    private fun startLinkHint(e: MotionEvent) {
        stopLinkHint()
        if (continuous || e.pointerCount != 1 || !(tool == 0 || tool == 1 || tool == 3)) return
        val im = linkAt(e) ?: return
        linkCand = im
        postDelayed(linkHintRun, 220) // solo si se mantiene presionada: al dibujar encima no parpadea
    }

    private fun stopLinkHint() {
        removeCallbacks(linkHintRun)
        linkCand = null
        if (linkHint != null) {
            linkHint = null
            invalidate()
        }
    }

    /** Ícono de cadena en la esquina superior derecha de la imagen con enlace que se mantiene presionada. */
    private fun drawLinkHint(c: Canvas) {
        val im = linkHint ?: return
        val pts = handlePts(im) ?: return // tirador 2 = esquina superior derecha
        val x = pts[4]
        val y = pts[5]
        val acc = ThemeUtils.accentHi(context)
        ovFill.color = context.getColor(R.color.sf3)
        c.drawCircle(x, y, 16f * d, ovFill)
        ovStroke.color = acc
        c.drawCircle(x, y, 16f * d, ovStroke)
        val ic = Icons(Icons.LINK, acc, false)
        ic.setBounds((x - 10f * d).toInt(), (y - 10f * d).toInt(), (x + 10f * d).toInt(), (y + 10f * d).toInt())
        ic.draw(c)
    }

    /** Registra en el historial el borrado que acaba de terminar (una sola vez por gesto). */
    private fun recordErase() {
        val pre = erasePre
        erasePre = null
        if (pre != null) onRecord?.invoke(pre)
    }

    private fun cancelStroke() {
        if (erasing) {
            erasing = false
            single?.clearCursor()
            if (erasedDirty) {
                onChanged?.invoke(index)
                recordErase()
            }
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
    private fun handlePts(item: SelectableItem): FloatArray? = handlePtsFor(item.bounds, item.rotation)

    private fun handlePtsFor(b: RectF, rot: Float): FloatArray? {
        val w = b.width()
        val h = b.height()
        val out = FloatArray(18)
        for (i in 0 until 8) {
            val p = Xf.toPage(b.centerX(), b.centerY(), rot, HX[i] * w / 2f, HY[i] * h / 2f)
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
    private fun hitHandle(pts: FloatArray, x: Float, y: Float, rotate: Boolean = true): Int {
        if (rotate && hypot(x - pts[16], y - pts[17]) <= 26f * d) return 8
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
        if (lassoMode) {
            startLasso(p)
            return
        }
        if (cropMode) {
            beginCrop(e, p)
            return
        }
        if (multiSel.isNotEmpty()) {
            // Selección múltiple: el recuadro de grupo se mueve, se redimensiona y gira como el de un solo objeto.
            val gpts = handlePtsFor(grpB, grpRot)
            val gh = if (gpts != null) hitHandle(gpts, e.x, e.y) else -1
            if (gh >= 0) {
                startGroupManip(if (gh == 8) M_GROT else M_GRESIZE, gh, p)
                return
            }
            val gl = Xf.toLocal(grpB.centerX(), grpB.centerY(), grpRot, p[0], p[1])
            if (kotlin.math.abs(gl[0]) <= grpB.width() / 2f && kotlin.math.abs(gl[1]) <= grpB.height() / 2f) {
                startGroupManip(M_GMOVE, -1, p)
                return
            }
            multiSel.clear() // un toque fuera: se sigue como una selección normal (otra imagen o ninguna)
        }
        val sel = selItem
        if (sel != null) {
            if (sel.isLocked) {
                // Bloqueada: no hay tiradores ni se mueve. Un toque encima la deja seleccionada (para poder desbloquearla).
                if (sel.hit(p[0], p[1])) return
            } else {
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
        }
        val hit = hitItem(p[0], p[1])
        multiSel.clear()
        selItem = hit
        // Se selecciona y ya se puede arrastrar (salvo que esté bloqueada).
        if (hit != null && !hit.isLocked) startManip(hit, M_MOVE, -1, p)
        updateMore()
        invalidate()
    }

    private fun startManip(item: SelectableItem, kind: Int, handle: Int, p: FloatArray) {
        manipPre = snapNow()
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
        if (manip == M_GMOVE || manip == M_GRESIZE || manip == M_GROT) {
            groupMove(e)
            return
        }
        val item = selItem ?: return
        // El lazo registra desde el primer movimiento; lo demás espera a superar el umbral (un toque no mueve nada).
        if (manip != M_LASSO && !mMoved && hypot(e.x - sx, e.y - sy) < touchSlop) return
        val p = toPage(e.x, e.y) ?: return
        mMoved = true
        if (manip == M_LASSO) {
            addLassoPoint(p)
            invalidate()
            return
        }
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
            M_CROP_RESIZE -> {
                // Se redimensiona la ventana, no la imagen. La ventana no puede salirse de la imagen.
                Xf.resize(mB0, mRot0, HX[mHandle], HY[mHandle], p[0], p[1], false, cropB)
                cropImg?.let { clampWindowToImage(it) }
            }
            M_CROP_MOVE -> {
                // La ventana queda quieta y la imagen se desliza por debajo (sin dejar la ventana fuera de la imagen).
                item.bounds.set(mB0)
                item.bounds.offset(p[0] - mP0x, p[1] - mP0y)
                cropImg?.let { clampImageOverWindow(it) }
            }
        }
        single?.invalidate()
        updateMore() // el botón de 3 puntos sigue al recuadro
        invalidate()
    }

    private fun endManip() {
        if (manip == 0) return
        val kind = manip
        if (kind == M_LASSO) closeLasso() // al levantar el dedo el trazo se cierra solo
        manip = 0
        if (mMoved) {
            mMoved = false
            // En los modos de recorte no se guarda nada hasta confirmar.
            if (!cropMode && !lassoMode) {
                if (kind == M_MOVE || kind == M_RESIZE || kind == M_ROTATE || kind == M_GMOVE || kind == M_GRESIZE || kind == M_GROT) {
                    val pre = manipPre
                    if (pre != null) onRecord?.invoke(pre)
                }
                manipPre = null
                onItemsChanged?.invoke()
            }
        }
    }

    // ---------- Selección múltiple: mover, redimensionar y girar el grupo ----------

    private fun startGroupManip(kind: Int, handle: Int, p: FloatArray) {
        manipPre = snapNow()
        manip = kind
        mHandle = handle
        mP0x = p[0]
        mP0y = p[1]
        gB0.set(grpB)
        gRot0 = grpRot
        gStart.clear()
        for (im in multiSel) gStart.add(floatArrayOf(im.bounds.left, im.bounds.top, im.bounds.right, im.bounds.bottom, im.rotation))
        mAng0 = Xf.angleDeg(p[0] - gB0.centerX(), p[1] - gB0.centerY())
        mMoved = false
        invalidate()
    }

    /**
     * Cada objeto se calcula SIEMPRE desde su estado al empezar el gesto (no se acumula error):
     *  - Mover: todos y la caja se desplazan lo mismo.
     *  - Redimensionar: escala UNIFORME f respecto al punto opuesto al tirador (el ancla A): centro' = A + f·(centro − A),
     *    ancho' = f·ancho, alto' = f·alto. f sale de la proyección del puntero sobre la diagonal (esquinas) o del eje (bordes).
     *    Así los objetos girados no se deforman.
     *  - Girar: todos giran φ alrededor del centro de la caja: centro' = C + R(φ)·(centro − C), giro' = giro + φ.
     */
    private fun groupMove(e: MotionEvent) {
        if (!mMoved && hypot(e.x - sx, e.y - sy) < touchSlop) return
        val p = toPage(e.x, e.y) ?: return
        mMoved = true
        when (manip) {
            M_GMOVE -> {
                val dx = p[0] - mP0x
                val dy = p[1] - mP0y
                for ((i, im) in multiSel.withIndex()) {
                    val s0 = gStart[i]
                    im.bounds.set(s0[0] + dx, s0[1] + dy, s0[2] + dx, s0[3] + dy)
                }
                grpB.set(gB0)
                grpB.offset(dx, dy)
            }
            M_GRESIZE -> {
                val hx = HX[mHandle]
                val hy = HY[mHandle]
                val gw = gB0.width()
                val gh = gB0.height()
                val a = Xf.toPage(gB0.centerX(), gB0.centerY(), gRot0, -hx * gw / 2f, -hy * gh / 2f)
                val v = Xf.rotate(p[0] - a[0], p[1] - a[1], -gRot0)
                var f = if (hx != 0 && hy != 0) {
                    val dx = hx * gw
                    val dy = hy * gh
                    (v[0] * dx + v[1] * dy) / (dx * dx + dy * dy)
                } else if (hx != 0) {
                    hx * v[0] / gw
                } else {
                    hy * v[1] / gh
                }
                f = f.coerceIn(Xf.MIN_MM / minOf(gw, gh), 20f)
                for ((i, im) in multiSel.withIndex()) {
                    val s0 = gStart[i]
                    val cx = (s0[0] + s0[2]) / 2f
                    val cy = (s0[1] + s0[3]) / 2f
                    val nx = a[0] + f * (cx - a[0])
                    val ny = a[1] + f * (cy - a[1])
                    val hw = f * (s0[2] - s0[0]) / 2f
                    val hh = f * (s0[3] - s0[1]) / 2f
                    im.bounds.set(nx - hw, ny - hh, nx + hw, ny + hh)
                }
                val gx = a[0] + f * (gB0.centerX() - a[0])
                val gy = a[1] + f * (gB0.centerY() - a[1])
                grpB.set(gx - f * gw / 2f, gy - f * gh / 2f, gx + f * gw / 2f, gy + f * gh / 2f)
            }
            M_GROT -> {
                val cx = gB0.centerX()
                val cy = gB0.centerY()
                val phi = Xf.angleDeg(p[0] - cx, p[1] - cy) - mAng0
                for ((i, im) in multiSel.withIndex()) {
                    val s0 = gStart[i]
                    val r = Xf.rotate((s0[0] + s0[2]) / 2f - cx, (s0[1] + s0[3]) / 2f - cy, phi)
                    val nx = cx + r[0]
                    val ny = cy + r[1]
                    val hw = (s0[2] - s0[0]) / 2f
                    val hh = (s0[3] - s0[1]) / 2f
                    im.bounds.set(nx - hw, ny - hh, nx + hw, ny + hh)
                    im.rotation = Xf.normalize(s0[4] + phi)
                }
                grpRot = Xf.normalize(gRot0 + phi)
            }
        }
        single?.invalidate()
        invalidate()
    }

    // ---------- Modo recorte ----------

    private fun beginCrop(e: MotionEvent, p: FloatArray) {
        val img = cropImg ?: return
        val pts = handlePtsFor(cropB, img.rotation)
        val h = if (pts != null) hitHandle(pts, e.x, e.y, false) else -1
        if (h >= 0) {
            startCropManip(M_CROP_RESIZE, h, p, cropB)
            return
        }
        val l = Xf.toLocal(cropB.centerX(), cropB.centerY(), img.rotation, p[0], p[1])
        if (kotlin.math.abs(l[0]) <= cropB.width() / 2f && kotlin.math.abs(l[1]) <= cropB.height() / 2f) {
            startCropManip(M_CROP_MOVE, -1, p, img.bounds)
        }
    }

    private fun startCropManip(kind: Int, handle: Int, p: FloatArray, from: RectF) {
        val img = cropImg ?: return
        manip = kind
        mHandle = handle
        mP0x = p[0]
        mP0y = p[1]
        mB0.set(from)
        mRot0 = img.rotation
        mMoved = false
        invalidate()
    }

    /** Mantiene la ventana dentro del rectángulo de la imagen (en los ejes de la imagen). */
    private fun clampWindowToImage(img: ImageItem) {
        val b = img.bounds
        val th = img.rotation
        val bw = b.width()
        val bh = b.height()
        val l = Xf.toLocal(b.centerX(), b.centerY(), th, cropB.centerX(), cropB.centerY())
        var left = l[0] - cropB.width() / 2f
        var right = l[0] + cropB.width() / 2f
        var top = l[1] - cropB.height() / 2f
        var bottom = l[1] + cropB.height() / 2f
        left = max(left, -bw / 2f)
        right = min(right, bw / 2f)
        top = max(top, -bh / 2f)
        bottom = min(bottom, bh / 2f)
        if (right - left < Xf.MIN_MM) right = min(bw / 2f, left + Xf.MIN_MM)
        if (bottom - top < Xf.MIN_MM) bottom = min(bh / 2f, top + Xf.MIN_MM)
        val c = Xf.toPage(b.centerX(), b.centerY(), th, (left + right) / 2f, (top + bottom) / 2f)
        val hw = (right - left) / 2f
        val hh = (bottom - top) / 2f
        cropB.set(c[0] - hw, c[1] - hh, c[0] + hw, c[1] + hh)
    }

    /** Después de mover la imagen: la imagen debe seguir cubriendo toda la ventana. */
    private fun clampImageOverWindow(img: ImageItem) {
        val b = img.bounds
        val bw = b.width()
        val bh = b.height()
        val th = img.rotation
        val l = Xf.toLocal(b.centerX(), b.centerY(), th, cropB.centerX(), cropB.centerY())
        val mx = max(0f, bw / 2f - cropB.width() / 2f)
        val my = max(0f, bh / 2f - cropB.height() / 2f)
        val lx = l[0].coerceIn(-mx, mx)
        val ly = l[1].coerceIn(-my, my)
        // centro de la imagen = centro de la ventana − R(θ)·(lx, ly)
        val r = Xf.rotate(lx, ly, th)
        val cx = cropB.centerX() - r[0]
        val cy = cropB.centerY() - r[1]
        b.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
    }

    private fun enterCrop() {
        val img = selItem as? ImageItem ?: return
        if (img.bitmap == null) {
            Toast.makeText(context, "La imagen aún se está cargando", Toast.LENGTH_SHORT).show()
            return
        }
        cropMode = true
        cropImg = img
        cropOrig.set(img.bounds)
        cropB.set(img.bounds)
        manip = 0
        updateCropBar()
        updateMore()
        invalidate()
    }

    private fun leaveCrop() {
        cropMode = false
        cropImg = null
        manip = 0
        mMoved = false
        updateCropBar()
        updateMore()
        single?.invalidate()
        invalidate()
    }

    /** Sale del modo recorte SIN cambios: la imagen vuelve a donde estaba. */
    private fun cancelCrop() {
        if (lassoMode) leaveLasso() // la X sirve para los dos modos: se borra el trazo y la imagen no cambia
        if (!cropMode) return
        cropImg?.bounds?.set(cropOrig)
        leaveCrop()
    }

    /**
     * Confirma: extrae del bitmap exactamente lo que hay dentro de la ventana. Matemática:
     *  1. La ventana se pasa al sistema LOCAL de la imagen (relativo a su centro, deshaciendo la rotación): toLocal(θ).
     *  2. Con el tamaño de la imagen (W×H mm) eso da fracciones u0..u1, v0..v1 de 0 a 1 sobre lo que se ve.
     *  3. Si está volteada, las fracciones se reflejan (u → 1−u) para apuntar a los píxeles del archivo.
     *  4. Se redondea a píxeles y se corta con Bitmap.createBitmap(src, x, y, w, h).
     *  5. El recuadro nuevo se calcula desde esos píxeles (no desde la ventana), para que coincida exacto con la imagen cortada.
     */
    private fun confirmCrop() {
        val img = cropImg ?: return
        val bm = img.bitmap ?: return
        val b = img.bounds
        val bw = b.width()
        val bh = b.height()
        val th = img.rotation
        val l = Xf.toLocal(b.centerX(), b.centerY(), th, cropB.centerX(), cropB.centerY())
        val hw = cropB.width() / 2f
        val hh = cropB.height() / 2f
        var u0 = ((l[0] - hw + bw / 2f) / bw).coerceIn(0f, 1f)
        var u1 = ((l[0] + hw + bw / 2f) / bw).coerceIn(0f, 1f)
        var v0 = ((l[1] - hh + bh / 2f) / bh).coerceIn(0f, 1f)
        var v1 = ((l[1] + hh + bh / 2f) / bh).coerceIn(0f, 1f)
        if (img.isFlippedH) {
            val a = 1f - u1
            u1 = 1f - u0
            u0 = a
        }
        if (img.isFlippedV) {
            val a = 1f - v1
            v1 = 1f - v0
            v0 = a
        }
        val x0 = (u0 * bm.width).roundToInt().coerceIn(0, bm.width - 1)
        val x1 = (u1 * bm.width).roundToInt().coerceIn(x0 + 1, bm.width)
        val y0 = (v0 * bm.height).roundToInt().coerceIn(0, bm.height - 1)
        val y1 = (v1 * bm.height).roundToInt().coerceIn(y0 + 1, bm.height)
        if (x0 == 0 && y0 == 0 && x1 == bm.width && y1 == bm.height) {
            cancelCrop() // la ventana abarca toda la imagen: no hay nada que recortar
            return
        }
        val out = try {
            Bitmap.createBitmap(bm, x0, y0, x1 - x0, y1 - y0)
        } catch (e: Throwable) {
            null
        }
        if (out == null) {
            Toast.makeText(context, "No se pudo recortar la imagen", Toast.LENGTH_SHORT).show()
            return
        }
        val pre = snapNow()
        if (commitCrop(img, bm, out, x0, y0, x1, y1) == null) return
        leaveCrop()
        onItemsChanged?.invoke() // guarda el archivo nuevo y el recuadro en las capas
        if (pre != null) onRecord?.invoke(pre)
    }

    /**
     * Parte común de los dos recortes (rectangular y libre): guarda [out] como archivo NUEVO y deja la imagen con ese bitmap
     * y un recuadro que cubre exactamente la región (x0,y0)-(x1,y1) del bitmap original [bm], con su giro y su espejo.
     * Devuelve el archivo anterior o null si no se pudo guardar (en ese caso la imagen no cambia). El archivo anterior NO se
     * borra: el historial lo necesita para deshacer; los archivos sin uso se limpian al abrir la nota (EditorActivity).
     */
    private fun commitCrop(img: ImageItem, bm: Bitmap, out: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): java.io.File? {
        val dir = img.file.parentFile
        val saved = if (dir != null) ImageFiles.write(out, dir, java.util.UUID.randomUUID().toString()) else null
        if (saved == null) {
            Toast.makeText(context, "No se pudo guardar la imagen recortada", Toast.LENGTH_SHORT).show()
            return null
        }
        val b = img.bounds
        val bw = b.width()
        val bh = b.height()
        // Región recortada, vista otra vez en pantalla (con el espejo): fracciones -> mm locales -> hoja.
        var du0 = x0.toFloat() / bm.width
        var du1 = x1.toFloat() / bm.width
        var dv0 = y0.toFloat() / bm.height
        var dv1 = y1.toFloat() / bm.height
        if (img.isFlippedH) {
            val a = 1f - du1
            du1 = 1f - du0
            du0 = a
        }
        if (img.isFlippedV) {
            val a = 1f - dv1
            dv1 = 1f - dv0
            dv0 = a
        }
        val lx0 = du0 * bw - bw / 2f
        val lx1 = du1 * bw - bw / 2f
        val ly0 = dv0 * bh - bh / 2f
        val ly1 = dv1 * bh - bh / 2f
        val c = Xf.toPage(b.centerX(), b.centerY(), img.rotation, (lx0 + lx1) / 2f, (ly0 + ly1) / 2f)
        val nw = lx1 - lx0
        val nh = ly1 - ly0
        val old = img.file
        b.set(c[0] - nw / 2f, c[1] - nh / 2f, c[0] + nw / 2f, c[1] + nh / 2f)
        img.bitmap = out
        img.file = saved
        img.baseW = nw
        return old
    }

    // ---------- Recorte libre (lazo) ----------

    private fun enterLasso() {
        val img = selItem as? ImageItem ?: return
        if (img.bitmap == null) {
            Toast.makeText(context, "La imagen aún se está cargando", Toast.LENGTH_SHORT).show()
            return
        }
        lassoMode = true
        lassoImg = img
        lassoPts.clear()
        lassoClosed = false
        manip = 0
        updateCropBar()
        updateMore()
        invalidate()
    }

    private fun leaveLasso() {
        lassoMode = false
        lassoImg = null
        lassoPts.clear()
        lassoClosed = false
        manip = 0
        mMoved = false
        updateCropBar()
        updateMore()
        invalidate()
    }

    private fun startLasso(p: FloatArray) {
        lassoPts.clear()
        lassoClosed = false
        lassoPts.add(p[0])
        lassoPts.add(p[1])
        manip = M_LASSO
        mMoved = false
        invalidate()
    }

    private fun addLassoPoint(p: FloatArray) {
        val n = lassoPts.size
        if (n < 2) return
        if (hypot(p[0] - lassoPts[n - 2], p[1] - lassoPts[n - 1]) < 0.4f) return // ~0,4 mm: sin puntos de más
        lassoPts.add(p[0])
        lassoPts.add(p[1])
    }

    /** Al levantar el dedo el trazo se cierra (el último punto se une con el primero al dibujarlo y al recortar). */
    private fun closeLasso() {
        if (lassoPts.size < 6) {
            lassoPts.clear()
            lassoClosed = false
        } else {
            lassoClosed = true
        }
        invalidate()
    }

    /** El trazo en pantalla (aplica zoom, giro y desplazamiento de la vista). Cerrado si ya se levantó el dedo. */
    private fun lassoScreenPath(): Path? {
        val v = single ?: return null
        if (lassoPts.size < 4) return null
        val sc = v.width / v.spec.wMm
        if (sc <= 0f) return null
        val arr = FloatArray(lassoPts.size)
        for (i in arr.indices) arr[i] = lassoPts[i] * sc
        v.matrix.mapPoints(arr)
        val path = Path()
        path.moveTo(arr[0] + v.left, arr[1] + v.top)
        var i = 2
        while (i < arr.size) {
            path.lineTo(arr[i] + v.left, arr[i + 1] + v.top)
            i += 2
        }
        if (lassoClosed) path.close()
        return path
    }

    /** Capa oscura sobre la hoja (más fuerte fuera del lazo cuando ya está cerrado), línea dorada punteada y la instrucción. */
    private fun drawLasso(c: Canvas) {
        val v = single ?: return
        val page = Path()
        val pg = floatArrayOf(0f, 0f, v.spec.wMm, 0f, v.spec.wMm, v.spec.hMm, 0f, v.spec.hMm)
        for (i in 0 until 4) {
            val s = pageToScreen(pg[i * 2], pg[i * 2 + 1]) ?: return
            if (i == 0) page.moveTo(s[0], s[1]) else page.lineTo(s[0], s[1])
        }
        page.close()
        val lasso = lassoScreenPath()
        val n = c.save()
        c.clipPath(page)
        if (lassoClosed && lasso != null) {
            val mask = Path()
            mask.fillType = Path.FillType.EVEN_ODD
            mask.addPath(page)
            mask.addPath(lasso)
            ovFill.color = 0x99000000.toInt()
            c.drawPath(mask, ovFill)
        } else {
            ovFill.color = 0x55000000
            c.drawPath(page, ovFill)
        }
        c.restoreToCount(n)
        if (lasso != null) {
            ovStroke.color = ThemeUtils.accentHi(context)
            ovStroke.pathEffect = ovDash
            c.drawPath(lasso, ovStroke)
            ovStroke.pathEffect = null
        }
        if (!lassoClosed) {
            ovText.color = context.getColor(R.color.ink)
            c.drawText("Dibuja el contorno a recortar", width / 2f, 96f * d, ovText)
        }
    }

    /**
     * Confirma el recorte libre. En vez de dibujar con la Matrix de la imagen, se hace el camino inverso (equivalente y sin
     * volver a muestrear píxeles): cada punto del lazo (hoja, mm) se lleva al sistema de la imagen deshaciendo su giro
     * (toLocal con θ), se convierte en fracción 0..1, se refleja si está volteada y se multiplica por el ancho/alto del archivo.
     * Con el lazo ya en píxeles del archivo: computeBounds da el rectángulo mínimo; se crea un Bitmap de ese tamaño y se
     * dibuja la imagen con clipPath(lazo). Lo que queda fuera del lazo es transparente (PNG).
     */
    private fun confirmLasso() {
        val img = lassoImg ?: return
        val bm = img.bitmap ?: return
        if (!lassoClosed || lassoPts.size < 6) {
            Toast.makeText(context, "Dibuja el contorno a recortar", Toast.LENGTH_SHORT).show()
            return
        }
        val b = img.bounds
        val w = b.width()
        val h = b.height()
        val th = img.rotation
        val path = Path()
        var i = 0
        while (i < lassoPts.size) {
            val l = Xf.toLocal(b.centerX(), b.centerY(), th, lassoPts[i], lassoPts[i + 1])
            var u = (l[0] + w / 2f) / w
            var vv = (l[1] + h / 2f) / h
            if (img.isFlippedH) u = 1f - u
            if (img.isFlippedV) vv = 1f - vv
            val px = u * bm.width
            val py = vv * bm.height
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            i += 2
        }
        path.close() // el último punto se une con el primero
        val rf = RectF()
        path.computeBounds(rf, true)
        if (rf.right <= 0f || rf.bottom <= 0f || rf.left >= bm.width || rf.top >= bm.height) {
            Toast.makeText(context, "El trazo quedó fuera de la imagen", Toast.LENGTH_SHORT).show()
            return
        }
        val x0 = floor(rf.left).toInt().coerceIn(0, bm.width - 1)
        val y0 = floor(rf.top).toInt().coerceIn(0, bm.height - 1)
        val x1 = ceil(rf.right).toInt().coerceIn(x0 + 1, bm.width)
        val y1 = ceil(rf.bottom).toInt().coerceIn(y0 + 1, bm.height)
        val out = try {
            Bitmap.createBitmap(x1 - x0, y1 - y0, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            null
        }
        if (out == null) {
            Toast.makeText(context, "No se pudo recortar la imagen", Toast.LENGTH_SHORT).show()
            return
        }
        val cv = Canvas(out)
        cv.translate(-x0.toFloat(), -y0.toFloat())
        cv.clipPath(path)
        cv.drawBitmap(bm, 0f, 0f, null)
        val pre = snapNow()
        if (commitCrop(img, bm, out, x0, y0, x1, y1) == null) return
        leaveLasso()
        single?.invalidate()
        onItemsChanged?.invoke()
        if (pre != null) onRecord?.invoke(pre)
    }

    // ---------- Barra de herramientas de selección ----------

    private fun createSelBar(): View {
        val ctx = context
        val ink = ctx.getColor(R.color.ink)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(4), dp(4), dp(4), dp(4))
        row.background = UiKit.rect(ctx, ctx.getColor(R.color.sf2), 24, ctx.getColor(R.color.ln2))
        row.elevation = 6f * d
        // De izquierda a derecha: seleccionar todo, guardar como sticker, copiar, pegar, al frente, al fondo, eliminar.
        val defs = listOf(
            Pair(Icons.SELECT, SelAct.ALL), Pair(Icons.STAR, SelAct.STICKER), Pair(Icons.COPY, SelAct.COPY),
            Pair(Icons.PASTE, SelAct.PASTE), Pair(Icons.BRING_FRONT, SelAct.FRONT), Pair(Icons.SEND_BACK, SelAct.BACK),
            Pair(Icons.TRASH, SelAct.DELETE)
        )
        for ((icon, act) in defs) {
            row.addView(
                UiKit.iconBtn(ctx, icon, ink, 42, 22) { onSelAction?.invoke(act) },
                LinearLayout.LayoutParams(dp(42), dp(42))
            )
        }
        row.visibility = INVISIBLE
        return row
    }

    private fun attachSelBar() {
        val bar = selBar ?: createSelBar().also { selBar = it }
        if (bar.parent == null) {
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            lp.topMargin = dp(12)
            addView(bar, lp)
        }
        updateSelBar()
    }

    /** Solo con la herramienta Seleccionar (y fuera de los modos de recorte, que usan ese mismo lugar). */
    private fun updateSelBar() {
        val bar = selBar ?: return
        val v = if (tool == 4 && !continuous && !cropMode && !lassoMode && bar.parent === this) VISIBLE else INVISIBLE
        if (bar.visibility != v) bar.visibility = v
    }

    private fun createCropBar(): View {
        val ctx = context
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(6), dp(6), dp(6), dp(6))
        row.background = UiKit.rect(ctx, ctx.getColor(R.color.sf2), 28, ctx.getColor(R.color.ln2))
        row.elevation = 6f * d
        row.addView(
            UiKit.iconBtn(ctx, Icons.CLOSE, ctx.getColor(R.color.ink), 48, 24) { cancelCrop() },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
        row.addView(
            UiKit.iconBtn(ctx, Icons.CHECK, ThemeUtils.accentHi(ctx), 48, 24) { if (lassoMode) confirmLasso() else confirmCrop() },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
        row.visibility = INVISIBLE
        return row
    }

    private fun attachCropBar() {
        val bar = cropBar ?: createCropBar().also { cropBar = it }
        if (bar.parent == null) {
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            lp.topMargin = dp(12)
            addView(bar, lp)
        }
        updateCropBar()
    }

    private fun updateCropBar() {
        val bar = cropBar ?: return
        val v = if ((cropMode || lassoMode) && bar.parent === this) VISIBLE else INVISIBLE
        if (bar.visibility != v) bar.visibility = v
    }

    // ---------- Botón flotante de 3 puntos ----------

    private fun createMore(): View {
        val b = UiKit.iconBtn(context, Icons.DOTS_V, ThemeUtils.accentHi(context), 44, 22) { toggleMenu() }
        UiKit.ripple(context, b, 22, context.getColor(R.color.sf3))
        b.elevation = 6f * d
        b.visibility = INVISIBLE
        return b
    }

    private fun createLock(): View {
        val b = UiKit.iconBtn(context, Icons.LOCK, ThemeUtils.accentHi(context), 44, 22) { setLocked(false) }
        UiKit.ripple(context, b, 22, context.getColor(R.color.sf3))
        b.elevation = 6f * d
        b.visibility = INVISIBLE
        return b
    }

    /** Pone los botones flotantes sobre la hoja (rebuild() quita todas las vistas del contenedor). */
    private fun attachMore() {
        val b = more ?: createMore().also { more = it }
        if (b.parent == null) addView(b, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START))
        val k = lockBtn ?: createLock().also { lockBtn = it }
        if (k.parent == null) addView(k, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START))
    }

    /** Bloquea o desbloquea la imagen seleccionada (un paso del historial) y guarda. */
    private fun setLocked(value: Boolean) {
        val item = selItem ?: return
        if (item.isLocked == value) return
        val pre = snapNow()
        item.isLocked = value
        manip = 0
        mMoved = false
        onItemsChanged?.invoke()
        if (pre != null) onRecord?.invoke(pre)
        updateMore()
        single?.invalidate()
        invalidate()
    }

    private fun hideMore(b: View) {
        lockBtn?.let { if (it.visibility != INVISIBLE) it.visibility = INVISIBLE }
        if (b.visibility != INVISIBLE) b.visibility = INVISIBLE
        dismissMenu() // sin selección no hay menú: desaparecen juntos
    }

    // ---------- Menú flotante de la imagen (PopupWindow) ----------

    /** El popup abierto (el menú o el panel de opacidad); solo hay uno a la vez. */
    private var menu: PopupWindow? = null
    private var menuClosedAt = 0L

    private class MenuAct(val icon: Int, val name: String, val rot: Float, val run: () -> Unit)

    private fun dismissMenu() {
        menu?.dismiss()
    }

    /** Abre el menú o, si ya hay un popup abierto, lo cierra. */
    private fun toggleMenu() {
        val b = more ?: return
        val open = menu
        if (open != null) {
            open.dismiss()
            return
        }
        // Tocar el botón con el popup abierto: el toque "fuera" ya lo cerró un instante antes; no se vuelve a abrir.
        if (SystemClock.uptimeMillis() - menuClosedAt < 300) return
        showMenu(b)
    }

    private fun soonAct(name: String): () -> Unit = {
        Toast.makeText(context, "$name: Próximamente", Toast.LENGTH_SHORT).show()
    }

    /** Voltea el elemento seleccionado (espejo) y guarda. El recuadro es simétrico: no cambia. */
    private fun flipSelected(horizontal: Boolean) {
        val item = selItem ?: return
        val pre = snapNow()
        if (horizontal) item.isFlippedH = !item.isFlippedH else item.isFlippedV = !item.isFlippedV
        single?.invalidate()
        invalidate()
        onItemsChanged?.invoke()
        if (pre != null) onRecord?.invoke(pre)
    }

    /** Muestra el popup [pw] al lado del botón (a su izquierda, o a su derecha si no cabe), centrado con él en vertical. */
    private fun placeBeside(pw: PopupWindow, anchor: View, w: Int, h: Int) {
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

    /** Ventana flotante sin foco: un toque fuera la cierra y además llega a la hoja. [onGone] se llama al cerrarse. */
    private fun makePopup(content: View, onGone: () -> Unit): PopupWindow {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pw = PopupWindow(content, content.measuredWidth, content.measuredHeight, false)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isFocusable = false
        pw.isOutsideTouchable = true
        pw.setOnDismissListener {
            if (menu === pw) menu = null
            menuClosedAt = SystemClock.uptimeMillis()
            onGone()
        }
        return pw
    }

    /**
     * Siete botones en columna, de arriba abajo: enlace, recortar, recorte libre, voltear horizontal, voltear vertical
     * (el mismo ícono girado 90°), opacidad y bloquear. Recortar, voltear y opacidad ya funcionan; el resto avisa "Próximamente".
     */
    private fun showMenu(anchor: View) {
        val ctx = context
        val ink = ctx.getColor(R.color.ink)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(4), dp(4), dp(4), dp(4))
        col.background = UiKit.rect(ctx, ctx.getColor(R.color.sf2), 16, ctx.getColor(R.color.ln2))
        val acts = listOf(
            MenuAct(Icons.LINK, "Enlace", 0f) { (selItem as? ImageItem)?.let { onEditLink?.invoke(it) } },
            MenuAct(Icons.CROP, "Recortar", 0f) { enterCrop() },
            MenuAct(Icons.CUT, "Recorte libre", 0f) { enterLasso() },
            MenuAct(Icons.FLIP, "Voltear horizontal", 0f) { flipSelected(true) },
            MenuAct(Icons.FLIP, "Voltear vertical", 90f) { flipSelected(false) },
            MenuAct(Icons.OPACITY, "Opacidad", 0f) { showOpacity(anchor) },
            MenuAct(Icons.LOCK, "Bloquear objeto", 0f) { setLocked(true) }
        )
        for (a in acts) {
            val btn = UiKit.iconBtn(ctx, a.icon, ink, 44, 22) {
                dismissMenu()
                a.run()
            }
            btn.rotation = a.rot
            col.addView(btn, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        val pw = makePopup(col) {}
        placeBeside(pw, anchor, col.measuredWidth, col.measuredHeight)
    }

    /**
     * Panel de opacidad: porcentaje y un SeekBar de 0 a 100. Cambia la opacidad de la imagen en tiempo real (el alpha con el
     * que se dibuja) y la guarda al soltar el control o al cerrar el panel.
     */
    private fun showOpacity(anchor: View) {
        val item = selItem ?: return
        val ctx = context
        val ink = ctx.getColor(R.color.ink)
        val acc = ThemeUtils.accent(ctx)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(12), dp(16), dp(12))
        col.background = UiKit.rect(ctx, ctx.getColor(R.color.sf2), 16, ctx.getColor(R.color.ln2))
        val start = (item.opacity.coerceIn(0f, 1f) * 100f).roundToInt()
        val label = UiKit.text(ctx, "Opacidad: $start%", 15, ink)
        col.addView(label)
        val seek = SeekBar(ctx)
        seek.max = 100
        seek.progress = start
        seek.progressTintList = ColorStateList.valueOf(acc)
        seek.thumbTintList = ColorStateList.valueOf(acc)
        seek.progressBackgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.ln2))
        val sp = LinearLayout.LayoutParams(dp(220), -2)
        sp.topMargin = dp(6)
        col.addView(seek, sp)
        var dirty = false
        val pre = snapNow() // la hoja antes de tocar la opacidad
        var recorded = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                label.text = "Opacidad: $p%"
                if (fromUser) {
                    if (!recorded && pre != null) {
                        recorded = true
                        onRecord?.invoke(pre) // una sola entrada en el historial por cada vez que se abre el panel
                    }
                    item.opacity = p / 100f
                    dirty = true
                    single?.invalidate() // se ve al instante
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {
                if (dirty) {
                    dirty = false
                    onItemsChanged?.invoke()
                }
            }
        })
        val pw = makePopup(col) {
            if (dirty) {
                dirty = false
                onItemsChanged?.invoke()
            }
        }
        placeBeside(pw, anchor, col.measuredWidth, col.measuredHeight)
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
        updateSelBar()
        val b = more ?: return
        val item = selItem
        if (item == null || tool != 4 || continuous || cropMode || lassoMode || b.parent !== this) {
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
        // Bloqueada: el candado ocupa el lugar del botón de 3 puntos (y el menú, si estaba abierto, se cierra).
        val lb = lockBtn
        val shown = if (item.isLocked && lb != null) lb else b
        val other = if (shown === b) lb else b
        if (other != null && other.visibility != INVISIBLE) {
            other.visibility = INVISIBLE
            if (other === b) dismissMenu()
        }
        shown.x = bx - half
        shown.y = by - half
        if (shown.visibility != VISIBLE) shown.visibility = VISIBLE
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, bt: Int) {
        super.onLayout(changed, l, t, r, bt)
        if (changed) dismissMenu() // cambió el tamaño (por ejemplo, al girar la tablet): el menú quedaría mal colocado
        updateMore()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawSelection(canvas)
        drawLinkHint(canvas)
    }

    /** Recuadro, 8 tiradores y círculo de giro del elemento seleccionado (se dibuja encima de la hoja, sin recorte). */
    private fun drawSelection(c: Canvas) {
        if (tool != 4 || continuous) return
        if (multiSel.isNotEmpty() && !cropMode && !lassoMode) {
            drawMulti(c)
            return
        }
        val item = selItem ?: return
        if (lassoMode) {
            drawLasso(c)
            return
        }
        if (cropMode) {
            drawCrop(c, item)
            return
        }
        val pts = handlePts(item) ?: return
        val acc = ThemeUtils.accentHi(context)
        val ink = context.getColor(R.color.ink)
        val locked = item.isLocked
        ovStroke.color = if (locked) context.getColor(R.color.mut) else acc
        val path = Path()
        path.moveTo(pts[0], pts[1])
        path.lineTo(pts[4], pts[5])
        path.lineTo(pts[14], pts[15])
        path.lineTo(pts[10], pts[11])
        path.close()
        c.drawPath(path, ovStroke)
        if (locked) {
            // Bloqueada: contorno gris y tiradores pequeños y apagados (no se usan); sin círculo de giro.
            for (i in 0 until 8) {
                ovFill.color = context.getColor(R.color.ln2)
                c.drawCircle(pts[i * 2], pts[i * 2 + 1], 4f * d, ovFill)
                c.drawCircle(pts[i * 2], pts[i * 2 + 1], 4f * d, ovStroke)
            }
            return
        }
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

    /** Selección múltiple: un contorno fino por objeto y la caja del grupo con sus 8 tiradores y el círculo de giro. */
    private fun drawMulti(c: Canvas) {
        val pts = handlePtsFor(grpB, grpRot) ?: return
        val acc = ThemeUtils.accentHi(context)
        val ink = context.getColor(R.color.ink)
        ovStroke.color = acc
        val w0 = ovStroke.strokeWidth
        ovStroke.strokeWidth = 1f * d
        ovStroke.alpha = 0x99
        for (im in multiSel) {
            val q = pageToScreenQuad(im.bounds, im.rotation) ?: continue
            c.drawPath(q, ovStroke)
        }
        ovStroke.strokeWidth = w0
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

    /** Modo recorte: capa negra al 50 % sobre la imagen (menos la ventana), contorno de la ventana y sus 8 tiradores. */
    private fun drawCrop(c: Canvas, item: SelectableItem) {
        val img = pageToScreenQuad(item.bounds, item.rotation) ?: return
        val win = pageToScreenQuad(cropB, item.rotation) ?: return
        val v = single ?: return
        val pg = floatArrayOf(0f, 0f, v.spec.wMm, 0f, v.spec.wMm, v.spec.hMm, 0f, v.spec.hMm)
        val page = Path()
        for (i in 0 until 4) {
            val s = pageToScreen(pg[i * 2], pg[i * 2 + 1]) ?: return
            if (i == 0) page.moveTo(s[0], s[1]) else page.lineTo(s[0], s[1])
        }
        page.close()
        val mask = Path()
        mask.fillType = Path.FillType.EVEN_ODD
        mask.addPath(img)
        mask.addPath(win)
        val n = c.save()
        c.clipPath(page) // lo que se sale de la hoja no se ve, tampoco su sombra
        ovFill.color = 0x80000000.toInt()
        c.drawPath(mask, ovFill)
        c.restoreToCount(n)
        val acc = ThemeUtils.accentHi(context)
        val ink = context.getColor(R.color.ink)
        ovStroke.color = acc
        c.drawPath(win, ovStroke)
        val pts = handlePtsFor(cropB, item.rotation) ?: return
        for (i in 0 until 8) {
            ovFill.color = ink
            c.drawCircle(pts[i * 2], pts[i * 2 + 1], 6f * d, ovFill)
            c.drawCircle(pts[i * 2], pts[i * 2 + 1], 6f * d, ovStroke)
        }
    }

    /** Cuadrilátero (en pantalla) de un rectángulo de la hoja con su giro. */
    private fun pageToScreenQuad(b: RectF, rot: Float): Path? {
        val path = Path()
        val xs = intArrayOf(-1, 1, 1, -1)
        val ys = intArrayOf(-1, -1, 1, 1)
        for (i in 0 until 4) {
            val p = Xf.toPage(b.centerX(), b.centerY(), rot, xs[i] * b.width() / 2f, ys[i] * b.height() / 2f)
            val s = pageToScreen(p[0], p[1]) ?: return null
            if (i == 0) path.moveTo(s[0], s[1]) else path.lineTo(s[0], s[1])
        }
        path.close()
        return path
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
        cancelCrop()
        selItem = null
        multiSel.clear()
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
                downTime = e.eventTime
                startLinkHint(e)
                if (drawTool) beginStroke(e, stylus) else if (selTool) beginSelect(e, stylus)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    cancelStroke() // un segundo dedo = gesto de vista, no trazo
                    stopLinkHint()
                    endManip()
                    mode = 2
                    multi = true
                    initTwo(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                vel?.addMovement(e)
                if ((linkCand != null || linkHint != null) && hypot(e.x - sx, e.y - sy) > touchSlop) stopLinkHint()
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
                // Un toque corto y quieto sobre una imagen con enlace lo abre en vez de dibujar o mover la vista.
                val tapLink = if (!multi && !continuous && (tool == 0 || tool == 1 || tool == 3) &&
                    e.eventTime - downTime < 450 && hypot(e.x - sx, e.y - sy) < touchSlop
                ) linkAt(e) else null
                if (penDown && tapLink != null) discardStroke() else if (penDown) endStroke(e)
                endManip()
                stopLinkHint()
                if (tapLink != null) onOpenLink?.invoke(tapLink)
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
                stopLinkHint()
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
