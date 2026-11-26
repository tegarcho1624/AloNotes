package com.alonotes.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Barra central de la herramienta de texto (la "T"): tamaño ("Size 40", abre un control deslizante con DONE), fuente (la misma lista
 * de FontManager que usan los Ajustes), itálica, negrita, subrayado y los cuatro colores del lápiz. Lo que se cambia aquí queda en
 * [TextStore] y, si hay un texto abierto para escribir, se aplica al instante ([PageViewport.textStyleChanged]).
 */
internal object TextOptions {
    fun build(a: Activity, bar: LinearLayout, vp: PageViewport, rebuild: () -> Unit) {
        fun dp(v: Number) = UiKit.dp(a, v)
        val hi = ThemeUtils.accentHi(a)
        val on = ThemeUtils.accentBg(a)

        // 1. Tamaño
        val size = UiKit.text(a, "Size " + TextStore.size(a).roundToInt(), 15, hi)
        size.gravity = Gravity.CENTER
        UiKit.ripple(a, size, 12, 0)
        size.setOnClickListener { sizePopup(a, size, vp) }
        bar.addView(size, LinearLayout.LayoutParams(dp(84), dp(44)))

        // 2. Fuente (cada botón "Aa" se ve en la fuente elegida)
        val font = UiKit.text(a, "Aa", 18, hi)
        font.gravity = Gravity.CENTER
        font.typeface = TextItem.typeface(TextStore.family(a), false, false)
        UiKit.ripple(a, font, 12, 0)
        font.setOnClickListener { fontSheet(a, vp, rebuild) }
        bar.addView(font, LinearLayout.LayoutParams(dp(44), dp(44)))

        // 3, 4 y 5. Itálica, negrita y subrayado (resaltados si están activos)
        styleBtn(a, bar, "I", hi, on, TextStore.italic(a)) { v ->
            v.setTypeface(v.typeface, Typeface.ITALIC)
        }.setOnClickListener {
            TextStore.setItalic(a, !TextStore.italic(a))
            vp.textStyleChanged(false)
            rebuild()
        }
        styleBtn(a, bar, "B", hi, on, TextStore.bold(a)) { v ->
            v.setTypeface(v.typeface, Typeface.BOLD)
        }.setOnClickListener {
            TextStore.setBold(a, !TextStore.bold(a))
            vp.textStyleChanged(false)
            rebuild()
        }
        styleBtn(a, bar, "U", hi, on, TextStore.underline(a)) { v ->
            v.paintFlags = v.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }.setOnClickListener {
            TextStore.setUnderline(a, !TextStore.underline(a))
            vp.textStyleChanged(false)
            rebuild()
        }

        // 6. Colores (los mismos del lápiz)
        bar.addView(PenOptions.divider(a))
        PenOptions.colorsOnly(a, bar) {
            vp.textStyleChanged(true)
            rebuild()
        }
    }

    private fun styleBtn(a: Activity, bar: LinearLayout, label: String, hi: Int, on: Int, active: Boolean, style: (TextView) -> Unit): TextView {
        val t = UiKit.text(a, label, 18, hi)
        t.gravity = Gravity.CENTER
        style(t)
        UiKit.ripple(a, t, 12, if (active) on else 0)
        bar.addView(t, LinearLayout.LayoutParams(UiKit.dp(a, 44), UiKit.dp(a, 44)))
        return t
    }

    /** Control deslizante del tamaño: el número de la barra se actualiza mientras se mueve; DONE lo cierra. */
    private fun sizePopup(a: Activity, anchor: TextView, vp: PageViewport) {
        fun dp(v: Number) = UiKit.dp(a, v)
        val hi = ThemeUtils.accentHi(a)
        val col = UiKit.vbox(a, 16)
        col.background = UiKit.rect(a, a.getColor(R.color.sf2), 16, a.getColor(R.color.ln2))
        val seek = SeekBar(a)
        val min = TextItem.MIN_SIZE.toInt()
        seek.max = TextItem.MAX_SIZE.toInt() - min
        seek.progress = TextStore.size(a).roundToInt() - min
        seek.progressTintList = ColorStateList.valueOf(hi)
        seek.thumbTintList = ColorStateList.valueOf(hi)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                TextStore.setSize(a, (min + p).toFloat())
                anchor.text = "Size " + (min + p)
                vp.textStyleChanged(false)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        col.addView(seek, LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.WRAP_CONTENT))
        val done = UiKit.btn(a, "Done", hi)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END
        lp.topMargin = dp(10)
        col.addView(done, lp)
        // No toma el foco: así, si se está escribiendo, el teclado se queda abierto.
        val pw = PopupWindow(col, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(8).toFloat()
        done.setOnClickListener { pw.dismiss() }
        // Si la barra se rehace (otra herramienta, otro texto), el panel no se queda flotando sin su botón.
        anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                pw.dismiss()
            }
        })
        pw.showAsDropDown(anchor, 0, dp(6))
    }

    /** Hoja con las fuentes de la app (la misma lista que Ajustes), cada una en su tipografía. Elegir una solo cambia el texto. */
    private fun fontSheet(a: Activity, vp: PageViewport, rebuild: () -> Unit) {
        fun dp(v: Number) = UiKit.dp(a, v)
        val l = UiKit.vbox(a, 12)
        l.addView(UiKit.text(a, "Fuente del texto", 17, a.getColor(R.color.ink)))
        val opts = FontManager.list(a)
        val sel = TextStore.family(a)
        val rows = LinearLayout(a)
        rows.orientation = LinearLayout.VERTICAL
        var dlg: android.app.Dialog? = null
        for (o in opts) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(8), dp(12), dp(8), dp(12))
            val t = UiKit.text(a, o.label, 20, a.getColor(R.color.ink))
            t.typeface = o.tf
            r.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (o.id == sel) {
                val ck = ImageView(a)
                ck.setImageDrawable(Icons(Icons.CHECK, ThemeUtils.accentHi(a), false))
                r.addView(ck, UiKit.box(a, 24))
            }
            UiKit.ripple(a, r, 0, 0)
            r.setOnClickListener {
                TextStore.setFamily(a, o.id)
                dlg?.dismiss()
                vp.textStyleChanged(false)
                rebuild()
            }
            rows.addView(r)
        }
        val scroll = ScrollView(a)
        scroll.addView(rows)
        val h = minOf(opts.size * dp(48) + dp(8), (a.resources.displayMetrics.heightPixels * 0.55f).toInt())
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
        lp.topMargin = dp(8)
        l.addView(scroll, lp)
        dlg = UiKit.show(a, l, true)
    }
}
