package com.alonotes.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils

/**
 * Opciones de la herramienta lápiz en la barra del editor (se muestran a la derecha de las
 * herramientas mientras el lápiz está seleccionado): tipo (lápiz/pluma/pincel), suavizado,
 * 3 grosores y 4 colores (toque largo = selector de color). Todo se guarda en PenStore.
 * El trazo en sí llega en la siguiente fase; aquí solo se configuran las opciones.
 */
internal object PenOptions {
    fun typeIcon(type: Int): Int = when (type) {
        1 -> Icons.FOUNTAIN
        2 -> Icons.BRUSH
        else -> Icons.PEN
    }

    fun build(a: Activity, bar: LinearLayout, rebuild: () -> Unit) {
        fun dp(v: Number) = UiKit.dp(a, v)
        val hi = ThemeUtils.accentHi(a)
        val ink = a.getColor(R.color.ink)
        val ln2 = a.getColor(R.color.ln2)

        val typeBtn = UiKit.iconBtn(a, typeIcon(PenStore.type(a)), hi, 44, 24)
        typeBtn.setOnClickListener { typePopup(a, typeBtn, rebuild) }
        bar.addView(typeBtn, UiKit.box(a, 44))
        val smBtn = UiKit.iconBtn(a, Icons.SMOOTH, hi, 44, 24)
        smBtn.setOnClickListener { smoothPopup(a, smBtn) }
        bar.addView(smBtn, UiKit.box(a, 44))
        bar.addView(divider(a))

        val colors = PenStore.colors(a)
        val sel = PenStore.colorIdx(a)
        val lineCol = if (ColorUtils.calculateLuminance(colors[sel]) < 0.12) 0xFFBDBDBD.toInt() else colors[sel]
        val thick = intArrayOf(2, 5, 9)
        for (i in 0..2) {
            val f = FrameLayout(a)
            val line = View(a)
            line.background = UiKit.rect(a, lineCol, 6)
            f.addView(line, FrameLayout.LayoutParams(dp(26), dp(thick[i]), Gravity.CENTER))
            UiKit.ripple(a, f, 12, if (i == PenStore.width(a)) ThemeUtils.accentBg(a) else 0)
            f.setOnClickListener {
                PenStore.setWidth(a, i)
                rebuild()
            }
            bar.addView(f, LinearLayout.LayoutParams(dp(46), dp(44)))
        }
        bar.addView(divider(a))

        val group = LinearLayout(a)
        group.gravity = Gravity.CENTER_VERTICAL
        for (i in 0..3) {
            val f = FrameLayout(a)
            val sw = View(a)
            val size = if (i == sel) 34 else 30
            sw.background = UiKit.oval(a, colors[i], if (i == sel) ink else ln2, if (i == sel) 2.5f else 1f)
            f.addView(sw, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
            f.setOnClickListener {
                PenStore.setColorIdx(a, i)
                rebuild()
            }
            f.setOnLongClickListener {
                PenStore.setColorIdx(a, i)
                ColorPicker.show(a, group, colors[i], { c ->
                    PenStore.setColor(a, i, c)
                    sw.background = UiKit.oval(a, c, ink, 2.5f)
                }, { rebuild() })
                true
            }
            group.addView(f, LinearLayout.LayoutParams(dp(46), dp(44)))
        }
        bar.addView(group)
    }

    /** Opciones del borrador: tres tamaños (fino, normal, grande). */
    fun buildEraser(a: Activity, bar: LinearLayout, rebuild: () -> Unit) {
        fun dp(v: Number) = UiKit.dp(a, v)
        val ink = a.getColor(R.color.ink)
        val sel = PenStore.eraser(a)
        val sizes = intArrayOf(12, 22, 32)
        for (i in 0..2) {
            val f = FrameLayout(a)
            val c = View(a)
            c.background = UiKit.oval(a, ink, ThemeUtils.accentHi(a), if (i == sel) 2.5f else 0f)
            f.addView(c, FrameLayout.LayoutParams(dp(sizes[i]), dp(sizes[i]), Gravity.CENTER))
            UiKit.ripple(a, f, 14, 0)
            f.setOnClickListener {
                PenStore.setEraser(a, i)
                rebuild()
            }
            bar.addView(f, LinearLayout.LayoutParams(dp(54), dp(44)))
        }
    }

    private fun divider(a: Activity): View {
        val d = View(a)
        d.setBackgroundColor(a.getColor(R.color.ln2))
        val lp = LinearLayout.LayoutParams(UiKit.dp(a, 1), UiKit.dp(a, 24))
        lp.setMargins(UiKit.dp(a, 8), 0, UiKit.dp(a, 8), 0)
        d.layoutParams = lp
        return d
    }

    private fun popup(a: Activity, content: View, w: Int): PopupWindow {
        content.background = UiKit.rect(a, a.getColor(R.color.sheet), 20, a.getColor(R.color.ln))
        val pw = PopupWindow(content, w, -2, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = UiKit.dp(a, 8).toFloat()
        return pw
    }

    /** Lápiz / pluma / pincel. */
    private fun typePopup(a: Activity, anchor: View, rebuild: () -> Unit) {
        val l = LinearLayout(a)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(UiKit.dp(a, 8), UiKit.dp(a, 8), UiKit.dp(a, 8), UiKit.dp(a, 8))
        val pw = popup(a, l, -2)
        val cur = PenStore.type(a)
        for ((i, icon) in intArrayOf(Icons.PEN, Icons.FOUNTAIN, Icons.BRUSH).withIndex()) {
            val b = UiKit.iconBtn(a, icon, if (i == cur) ThemeUtils.accentHi(a) else a.getColor(R.color.mut), 52, 28)
            if (i == cur) UiKit.ripple(a, b, 14, ThemeUtils.accentBg(a))
            b.setOnClickListener {
                PenStore.setType(a, i)
                pw.dismiss()
                rebuild()
            }
            l.addView(b, UiKit.box(a, 52))
        }
        pw.showAsDropDown(anchor, 0, UiKit.dp(a, 6))
    }

    /** Suavizado: valor editable + control deslizante. */
    private fun smoothPopup(a: Activity, anchor: View) {
        fun dp(v: Number) = UiKit.dp(a, v)
        val l = UiKit.vbox(a, 20)
        val pw = popup(a, l, dp(320))
        val row = LinearLayout(a)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(UiKit.text(a, "Suavizado:", 17, a.getColor(R.color.ink)))
        val edit = EditText(a)
        edit.inputType = InputType.TYPE_CLASS_NUMBER
        edit.setSingleLine(true)
        edit.gravity = Gravity.END
        edit.setText(PenStore.smoothing(a).toString())
        edit.setTextColor(a.getColor(R.color.ink))
        edit.typeface = FontManager.current(a)
        edit.backgroundTintList = ColorStateList.valueOf(ThemeUtils.accent(a))
        val ep = LinearLayout.LayoutParams(dp(80), -2)
        ep.leftMargin = dp(12)
        row.addView(edit, ep)
        l.addView(row)

        val seek = SeekBar(a)
        seek.max = 100
        seek.progress = PenStore.smoothing(a)
        seek.progressTintList = ColorStateList.valueOf(ThemeUtils.accent(a))
        seek.thumbTintList = ColorStateList.valueOf(ThemeUtils.accent(a))
        seek.progressBackgroundTintList = ColorStateList.valueOf(a.getColor(R.color.ln2))
        val sp = LinearLayout.LayoutParams(-1, -2)
        sp.topMargin = dp(14)
        sp.bottomMargin = dp(6)
        l.addView(seek, sp)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                edit.setText(p.toString())
                PenStore.setSmoothing(a, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = s?.toString()?.toIntOrNull()?.coerceIn(0, 100) ?: return
                if (seek.progress != n) seek.progress = n
                PenStore.setSmoothing(a, n)
            }
        })

        val done = UiKit.btn(a, "Listo", ThemeUtils.accent(a))
        done.setOnClickListener { pw.dismiss() }
        l.addView(UiKit.buttons(a, done))
        pw.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        pw.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        pw.showAsDropDown(anchor, 0, dp(6))
    }
}
