package com.alonotes.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Selector de color del lápiz: círculo cromático + barra de valor + código HEX (misma validación
 * que el HEX del acento, ThemeUtils.parseHex) + paleta de hasta 14 colores guardados (PenStore).
 * Solo se usa para los colores del lápiz; no toca el acento de la app.
 */
internal object ColorPicker {
    fun show(a: Activity, anchor: View, initial: Int, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
        fun dp(v: Number) = UiKit.dp(a, v)
        val ln2 = a.getColor(R.color.ln2)
        val mut = a.getColor(R.color.mut)
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)

        val screenW = a.resources.displayMetrics.widthPixels
        val wheelSize = maxOf(dp(160), min(dp(300), screenW - dp(32 + 32 + 120)))
        val wheel = WheelView(a)
        val vbar = ValueBar(a)
        val preview = View(a)
        val hex = EditText(a)
        var updating = false

        fun render(updateHex: Boolean) {
            val c = Color.HSVToColor(hsv)
            wheel.hue = hsv[0]; wheel.sat = hsv[1]; wheel.invalidate()
            vbar.hue = hsv[0]; vbar.sat = hsv[1]; vbar.v = hsv[2]; vbar.invalidate()
            preview.background = UiKit.oval(a, c, ln2, 1)
            if (updateHex) {
                updating = true
                hex.setText(String.format("#%06X", c and 0x00FFFFFF))
                updating = false
            }
        }

        fun commit(updateHex: Boolean) {
            render(updateHex)
            onChange(Color.HSVToColor(hsv))
        }

        val root = LinearLayout(a)
        root.setPadding(dp(16), dp(16), dp(16), dp(16))
        root.background = UiKit.rect(a, a.getColor(R.color.sheet), 20, a.getColor(R.color.ln))

        // Columna izquierda: rueda, valor y HEX
        val left = LinearLayout(a)
        left.orientation = LinearLayout.VERTICAL
        left.addView(wheel, LinearLayout.LayoutParams(wheelSize, wheelSize))
        val bp = LinearLayout.LayoutParams(wheelSize, dp(34))
        bp.topMargin = dp(12)
        left.addView(vbar, bp)
        val hr = LinearLayout(a)
        hr.gravity = Gravity.CENTER_VERTICAL
        val pp = UiKit.box(a, 26)
        pp.rightMargin = dp(12)
        hr.addView(preview, pp)
        hex.setSingleLine(true)
        hex.hint = "#RRGGBB"
        hex.setHintTextColor(mut)
        hex.setTextColor(a.getColor(R.color.ink))
        hex.textSize = 18f
        hex.typeface = FontManager.current(a)
        hex.filters = arrayOf(InputFilter.LengthFilter(7))
        hex.backgroundTintList = ColorStateList.valueOf(ThemeUtils.accent(a))
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val c = ThemeUtils.parseHex(s?.toString() ?: "") ?: return
                Color.colorToHSV(c, hsv)
                commit(false)
            }
        })
        hr.addView(hex, LinearLayout.LayoutParams(0, -2, 1f))
        hr.addView(UiKit.iconBtn(a, Icons.EYEDROPPER, mut, 40, 24) { UiKit.soon(a) }, UiKit.box(a, 40))
        val hp = LinearLayout.LayoutParams(wheelSize, -2)
        hp.topMargin = dp(10)
        left.addView(hr, hp)
        root.addView(left)

        // Columna derecha: colores guardados (2 × 7)
        val tiles = LinearLayout(a)
        tiles.orientation = LinearLayout.VERTICAL
        val tp = LinearLayout.LayoutParams(-2, -2)
        tp.leftMargin = dp(14)
        root.addView(tiles, tp)

        fun buildTiles() {
            tiles.removeAllViews()
            val saved = PenStore.saved(a)
            for (r in 0 until PenStore.MAX_SAVED / 2) {
                val row = LinearLayout(a)
                for (k in 0 until 2) {
                    val i = r * 2 + k
                    val t = FrameLayout(a)
                    if (i < saved.size) {
                        val col = saved[i]
                        t.background = UiKit.oval(a, col, ln2, 1)
                        t.setOnClickListener {
                            Color.colorToHSV(col, hsv)
                            commit(true)
                        }
                        t.setOnLongClickListener {
                            PenStore.setSaved(a, PenStore.saved(a).filterIndexed { j, _ -> j != i })
                            buildTiles()
                            true
                        }
                    } else {
                        t.background = UiKit.oval(a, a.getColor(R.color.sf3), ln2, 1)
                        val iv = ImageView(a)
                        iv.setImageDrawable(Icons(Icons.PLUS, mut, false))
                        t.addView(iv, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                        t.setOnClickListener {
                            val c = Color.HSVToColor(hsv)
                            val l = PenStore.saved(a).toMutableList()
                            if (!l.contains(c)) l.add(c)
                            PenStore.setSaved(a, l)
                            buildTiles()
                        }
                    }
                    val lp = LinearLayout.LayoutParams(dp(44), dp(44))
                    lp.setMargins(dp(6), dp(5), dp(6), dp(5))
                    row.addView(t, lp)
                }
                tiles.addView(row)
            }
        }

        wheel.hue = hsv[0]; wheel.sat = hsv[1]
        wheel.onPick = { h, s ->
            hsv[0] = h
            hsv[1] = s
            commit(true)
        }
        vbar.onPick = { v ->
            hsv[2] = v
            commit(true)
        }
        render(true)
        buildTiles()

        val pw = PopupWindow(root, -2, -2, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(8).toFloat()
        pw.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        pw.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        pw.setOnDismissListener { onDismiss() }
        pw.showAsDropDown(anchor, 0, dp(6), Gravity.END)
    }

    /** Círculo cromático: ángulo = tono (antihorario, rojo a la derecha), radio = saturación. */
    private class WheelView(a: Activity) : View(a) {
        var hue = 0f
        var sat = 0f
        var onPick: ((Float, Float) -> Unit)? = null
        private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rad = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mk = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cx = 0f
        private var cy = 0f
        private var r = 0f
        private val d = resources.displayMetrics.density

        init {
            mk.style = Paint.Style.STROKE
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            cx = w / 2f
            cy = h / 2f
            r = min(w, h) / 2f - 10 * d
            sweep.shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    0xFFFF0000.toInt(), 0xFFFF00FF.toInt(), 0xFF0000FF.toInt(), 0xFF00FFFF.toInt(),
                    0xFF00FF00.toInt(), 0xFFFFFF00.toInt(), 0xFFFF0000.toInt()
                ), null
            )
            rad.shader = RadialGradient(cx, cy, r, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            c.drawCircle(cx, cy, r, sweep)
            c.drawCircle(cx, cy, r, rad)
            val ang = Math.toRadians(-hue.toDouble())
            val px = cx + sat * r * cos(ang).toFloat()
            val py = cy + sat * r * sin(ang).toFloat()
            mk.color = Color.WHITE
            mk.strokeWidth = 3.5f * d
            c.drawCircle(px, py, 9 * d, mk)
            mk.color = Color.BLACK
            mk.strokeWidth = 1.5f * d
            c.drawCircle(px, py, 9 * d, mk)
            c.drawLine(px - 5 * d, py, px + 5 * d, py, mk)
            c.drawLine(px, py - 5 * d, px, py + 5 * d, mk)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = e.x - cx
                val dy = e.y - cy
                val s = min(1f, hypot(dx, dy) / r)
                var h = (-Math.toDegrees(atan2(dy, dx).toDouble())).toFloat()
                if (h < 0) h += 360f
                hue = h % 360f
                sat = s
                invalidate()
                onPick?.invoke(hue, sat)
                return true
            }
            return true
        }
    }

    /** Barra de valor (brillo): de negro al color del círculo, con marcador triangular. */
    private class ValueBar(a: Activity) : View(a) {
        var hue = 0f
        var sat = 0f
        var v = 1f
        var onPick: ((Float) -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val d = resources.displayMetrics.density
        private val border = a.getColor(R.color.ln2)
        private val ink = a.getColor(R.color.ink)

        override fun onDraw(c: Canvas) {
            val l = 2 * d
            val rt = width - 2 * d
            val top = 12 * d
            p.shader = LinearGradient(l, 0f, rt, 0f, Color.BLACK, Color.HSVToColor(floatArrayOf(hue, sat, 1f)), Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL
            c.drawRect(l, top, rt, height.toFloat() - 2 * d, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = border
            c.drawRect(l, top, rt, height.toFloat() - 2 * d, p)
            val x = l + v * (rt - l)
            path.reset()
            path.moveTo(x - 8 * d, 0f)
            path.lineTo(x + 8 * d, 0f)
            path.lineTo(x, 11 * d)
            path.close()
            p.style = Paint.Style.FILL
            p.color = ink
            c.drawPath(path, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                parent?.requestDisallowInterceptTouchEvent(true)
                val l = 2 * d
                val rt = width - 2 * d
                v = ((e.x - l) / (rt - l)).coerceIn(0f, 1f)
                invalidate()
                onPick?.invoke(v)
            }
            return true
        }
    }
}
