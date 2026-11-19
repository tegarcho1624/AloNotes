package com.alonotes.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Menú lateral deslizable. Se abre con el botón de tres rayas o deslizando de izquierda a derecha
 * desde la zona izquierda de la pantalla (rápido, para no chocar con el arrastre de carpetas).
 * Se cierra tocando fuera, deslizando a la izquierda o con atrás.
 */
internal class SideDrawerLayout(ctx: Context, private val panelW: Int) : FrameLayout(ctx) {
    private val scrim = View(ctx)
    private var panel: View? = null
    private var progress = 0f
    private var opened = false
    private var anim: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var dragging = false
    private var startProgress = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    val isOpen: Boolean get() = opened

    fun setup(content: View, p: View) {
        addView(content, LayoutParams(-1, -1))
        scrim.setBackgroundColor(ctx().getColor(R.color.scrim))
        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(-1, -1))
        p.elevation = 16f * resources.displayMetrics.density
        addView(p, LayoutParams(panelW, -1, Gravity.START))
        panel = p
        render()
    }

    private fun ctx(): Context = context

    private fun render() {
        panel?.translationX = -panelW * (1f - progress)
        panel?.visibility = if (progress > 0f) VISIBLE else INVISIBLE
        scrim.alpha = progress
        scrim.visibility = if (progress > 0f) VISIBLE else GONE
    }

    fun open() = settle(true)
    fun close() = settle(false)

    private fun settle(open: Boolean) {
        opened = open
        anim?.cancel()
        val a = ValueAnimator.ofFloat(progress, if (open) 1f else 0f)
        a.duration = 200
        a.addUpdateListener {
            progress = it.animatedValue as Float
            render()
        }
        anim = a
        a.start()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downT = e.eventTime
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) return true
                val dx = e.x - downX
                val dy = e.y - downY
                val horizontal = abs(dx) > 2 * abs(dy)
                val start = if (progress == 0f) {
                    downX <= width * 0.18f && dx > slop * 2 && horizontal && e.eventTime - downT < 350
                } else {
                    dx < -slop * 2 && horizontal
                }
                if (start) {
                    dragging = true
                    downX = e.x
                    startProgress = progress
                    anim?.cancel()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                progress = (startProgress + (e.x - downX) / panelW).coerceIn(0f, 1f)
                render()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val open = if (startProgress > 0.5f) progress > 0.6f else progress > 0.4f
                settle(open)
            }
        }
        return true
    }
}

/** Contenido del menú lateral. Por ahora todas las opciones muestran "Próximamente". */
internal object DrawerPanel {
    fun build(a: Activity, drawer: SideDrawerLayout): View {
        fun dp(v: Number) = UiKit.dp(a, v)
        val ink = a.getColor(R.color.ink)
        val hi = ThemeUtils.accentHi(a)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(16), 0, dp(24))

        val title = UiKit.text(a, "AloNotes", 26, ink)
        title.setPadding(dp(24), dp(14), dp(24), dp(14))
        col.addView(title)

        fun item(icon: Int, label: String, iconColor: Int = ink, selected: Boolean = false, bold: Boolean = false) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(20), 0, dp(20), 0)
            val iv = ImageView(a)
            iv.setImageDrawable(Icons(icon, iconColor, false))
            r.addView(iv, UiKit.box(a, 24))
            val t = UiKit.text(a, label, 17, ink)
            if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
            val tp = LinearLayout.LayoutParams(0, -2, 1f)
            tp.leftMargin = dp(18)
            r.addView(t, tp)
            if (selected) r.background = UiKit.rect(a, ThemeUtils.accentBg(a), 28)
            else UiKit.ripple(a, r, 28, 0)
            r.setOnClickListener {
                drawer.close()
                UiKit.soon(a)
            }
            val lp = LinearLayout.LayoutParams(-1, dp(56))
            lp.setMargins(dp(10), dp(2), dp(10), dp(2))
            col.addView(r, lp)
        }

        item(Icons.NOTES, "Todas las notas")
        item(Icons.FOLDER, "Sin categorizar")
        col.addView(View(a), LinearLayout.LayoutParams(-1, dp(12)))
        item(Icons.STAR5, "Favoritos")
        item(Icons.TRASH, "Papelera")

        val line = View(a)
        line.setBackgroundColor(a.getColor(R.color.ln2))
        val ll = LinearLayout.LayoutParams(-1, dp(1))
        ll.setMargins(dp(20), dp(18), dp(20), dp(10))
        col.addView(line, ll)

        // Encabezado "Categorías" con flecha
        val head = LinearLayout(a)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(20), 0, dp(20), 0)
        val ht = UiKit.text(a, "Categorías", 16, ink)
        ht.setTypeface(ht.typeface, Typeface.BOLD)
        head.addView(ht, LinearLayout.LayoutParams(0, -2, 1f))
        val ch = ImageView(a)
        ch.setImageDrawable(Icons(Icons.CHEV, ink, false))
        head.addView(ch, UiKit.box(a, 22))
        UiKit.ripple(a, head, 12, 0)
        head.setOnClickListener {
            drawer.close()
            UiKit.soon(a)
        }
        col.addView(head, LinearLayout.LayoutParams(-1, dp(52)))

        item(Icons.FOLDER, "Mis notas", hi, selected = true)
        item(Icons.PLUS, "Nueva categoría")

        val bg = GradientDrawable()
        bg.setColor(a.getColor(R.color.sf2))
        val r = dp(18).toFloat()
        bg.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        val sv = ScrollView(a)
        sv.background = bg
        sv.addView(col, ViewGroup.LayoutParams(-1, -2))
        return sv
    }
}
