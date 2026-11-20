package com.alonotes.app

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import kotlin.math.hypot

/**
 * Panel flotante de capas (Fase 6, solo visual). Se arrastra por su barra superior y tiene dos
 * estados: expandido (lista de capas, barra de opacidad) y minimizado (cuadro con el número de
 * capas; se arrastra igual y un toque lo expande). Vive dentro del área de hojas del editor.
 * La lista y la opacidad todavía no cambian datos: las capas reales llegan en la siguiente fase.
 */
internal class LayersPanel(private val a: Activity) : FrameLayout(a) {
    private companion object {
        const val MINI = 60
        const val FULL_W = 320
        const val FULL_H = 450
        const val EDGE = 22
    }

    var opened = false
        private set
    var minimized = false
        private set
    var onClose: (() -> Unit)? = null

    private val full = LinearLayout(a)
    private val mini = FrameLayout(a)
    private val badge = UiKit.text(a, "1", 24, ThemeUtils.accentHi(a))
    private val listBox = LinearLayout(a)
    private val pct = UiKit.text(a, "100%", 15, a.getColor(R.color.ink))
    private val slop = ViewConfiguration.get(a).scaledTouchSlop
    private var placed = false
    private var dRx = 0f
    private var dRy = 0f
    private var dragged = false

    /** Número de capas de la hoja actual (por ahora siempre 1). */
    var count: Int = 1
        set(v) {
            field = v.coerceAtLeast(1)
            badge.text = field.toString()
            fillList()
        }

    private fun dp(v: Number) = UiKit.dp(a, v)

    init {
        background = UiKit.rect(a, a.getColor(R.color.sheet), 16, a.getColor(R.color.ln2))
        clipToOutline = true
        elevation = dp(10).toFloat()
        visibility = GONE
        isClickable = true // consume los toques: la hoja de debajo no debe recibirlos
        buildFull()
        buildMini()
        addView(full, FrameLayout.LayoutParams(-1, -1))
        addView(mini, FrameLayout.LayoutParams(-1, -1))
        fillList()
        mini.visibility = GONE
    }

    // ---------- Construcción ----------

    private fun buildFull() {
        val ink = a.getColor(R.color.ink)
        val hi = ThemeUtils.accentHi(a)
        full.orientation = LinearLayout.VERTICAL

        // Barra superior: arrastrar, minimizar y cerrar
        val bar = LinearLayout(a)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(a.getColor(R.color.sf3))
        bar.setPadding(dp(14), 0, dp(4), 0)
        bar.addView(UiKit.text(a, "Capas", 15, ink), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(UiKit.iconBtn(a, Icons.GRIP, a.getColor(R.color.mut), 28, 22), UiKit.box(a, 28))
        bar.addView(UiKit.iconBtn(a, Icons.MINIMIZE, hi, 40, 22) { setMinimized(true) }, UiKit.box(a, 40))
        bar.addView(UiKit.iconBtn(a, Icons.CLOSE, hi, 40, 22) { close() }, UiKit.box(a, 40))
        attachDrag(bar, null)
        full.addView(bar, LinearLayout.LayoutParams(-1, dp(44)))

        // Lista de capas
        listBox.orientation = LinearLayout.VERTICAL
        listBox.setPadding(dp(8), dp(8), dp(8), dp(8))
        val sv = ScrollView(a)
        sv.addView(listBox, ViewGroup.LayoutParams(-1, -2))
        full.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        // Barra inferior: opacidad (solo visual por ahora)
        val line = View(a)
        line.setBackgroundColor(a.getColor(R.color.ln))
        full.addView(line, LinearLayout.LayoutParams(-1, dp(1)))
        val foot = LinearLayout(a)
        foot.gravity = Gravity.CENTER_VERTICAL
        foot.setBackgroundColor(a.getColor(R.color.sf))
        foot.setPadding(dp(14), 0, dp(14), 0)
        foot.addView(pct, LinearLayout.LayoutParams(dp(52), -2))
        val seek = SeekBar(a)
        seek.max = 100
        seek.progress = 100
        seek.progressTintList = ColorStateList.valueOf(ThemeUtils.accent(a))
        seek.thumbTintList = ColorStateList.valueOf(ThemeUtils.accent(a))
        seek.progressBackgroundTintList = ColorStateList.valueOf(a.getColor(R.color.ln2))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                pct.text = "$p%"
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        foot.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        full.addView(foot, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun buildMini() {
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        mini.addView(badge, lp)
        attachDrag(mini) { setMinimized(false) }
    }

    private fun fillList() {
        listBox.removeAllViews()
        // La capa más alta arriba.
        for (n in count downTo 1) listBox.addView(row(n, n == 1))
    }

    private fun row(n: Int, selected: Boolean): View {
        val r = LinearLayout(a)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(4), 0, dp(8), 0)
        if (selected) r.background = UiKit.rect(a, ThemeUtils.accentBg(a), 10)
        else UiKit.ripple(a, r, 10, 0)
        r.addView(UiKit.iconBtn(a, Icons.EYE, ThemeUtils.accentHi(a), 40, 22) { UiKit.soon(a) }, UiKit.box(a, 40))
        val t = UiKit.text(a, "Capa $n", 15, a.getColor(R.color.ink))
        val tp = LinearLayout.LayoutParams(0, -2, 1f)
        tp.leftMargin = dp(8)
        r.addView(t, tp)
        val lp = LinearLayout.LayoutParams(-1, dp(48))
        lp.bottomMargin = dp(4)
        r.layoutParams = lp
        return r
    }

    // ---------- Arrastre ----------

    /** Arrastra el panel desde [v]. Si no se movió (toque simple), ejecuta [tap]. */
    private fun attachDrag(v: View, tap: (() -> Unit)?) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dRx = e.rawX
                    dRy = e.rawY
                    dragged = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dRx
                    val dy = e.rawY - dRy
                    if (!dragged && hypot(dx, dy) > slop) dragged = true
                    if (dragged) {
                        moveTo(x + dx, y + dy)
                        dRx = e.rawX
                        dRy = e.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) tap?.invoke()
                }
            }
            true
        }
    }

    // ---------- Tamaño y posición ----------

    private fun pw(): Int = (parent as? View)?.width ?: 0
    private fun ph(): Int = (parent as? View)?.height ?: 0

    private fun targetW(): Int =
        if (minimized) dp(MINI) else minOf(dp(FULL_W), pw() - dp(24)).coerceAtLeast(dp(MINI))

    private fun targetH(): Int =
        if (minimized) dp(MINI) else minOf(dp(FULL_H), ph() - dp(24)).coerceAtLeast(dp(MINI))

    private fun moveTo(nx: Float, ny: Float) {
        val maxX = (pw() - targetW()).toFloat().coerceAtLeast(0f)
        val maxY = (ph() - targetH()).toFloat().coerceAtLeast(0f)
        x = nx.coerceIn(0f, maxX)
        y = ny.coerceIn(0f, maxY)
    }

    /** Aplica el estado (tamaño y vista) y mantiene el panel dentro del área. */
    private fun applyState() {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val tw = targetW()
        val th = targetH()
        // Solo se toca el layout si cambia: así no se encadenan pasadas de layout.
        if (lp.width != tw || lp.height != th) {
            lp.width = tw
            lp.height = th
            layoutParams = lp
        }
        full.visibility = if (minimized) GONE else VISIBLE
        mini.visibility = if (minimized) VISIBLE else GONE
        if (!placed && pw() > 0 && ph() > 0) {
            // Primera vez: abajo a la derecha, donde está el ícono de capas.
            x = (pw() - tw - dp(EDGE)).toFloat()
            y = (ph() - th - dp(EDGE)).toFloat()
            placed = true
        }
        moveTo(x, y)
    }

    private fun setMinimized(m: Boolean) {
        if (m == minimized) return
        val oldW = targetW()
        minimized = m
        // Se conserva el borde derecho: el botón de minimizar queda bajo el dedo.
        x = x + oldW - targetW()
        applyState()
    }

    /** Vuelve a ajustar el panel si cambió el tamaño del área (rotación, teclado). */
    fun refit() {
        if (opened) applyState()
    }

    fun open() {
        opened = true
        minimized = false
        applyState()
    }

    fun close() {
        opened = false
        onClose?.invoke()
    }

    fun save(o: Bundle) {
        o.putBoolean("lp_open", opened)
        o.putBoolean("lp_min", minimized)
        o.putBoolean("lp_placed", placed)
        o.putFloat("lp_x", x)
        o.putFloat("lp_y", y)
    }

    fun restore(b: Bundle) {
        opened = b.getBoolean("lp_open")
        minimized = b.getBoolean("lp_min")
        full.visibility = if (minimized) GONE else VISIBLE
        mini.visibility = if (minimized) VISIBLE else GONE
        if (b.getBoolean("lp_placed")) {
            x = b.getFloat("lp_x")
            y = b.getFloat("lp_y")
            placed = true
        }
    }
}
