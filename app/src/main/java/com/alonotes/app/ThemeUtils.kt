package com.alonotes.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.graphics.ColorUtils

/**
 * Tema y acento de AloNotes (mismo sistema que AloTube). SharedPreferences "settings":
 * "accent" = ARGB int; "accent_custom" = "RRGGBB,RRGGBB,..." (el más nuevo primero, máx. 12).
 * Por defecto dorado (#C9A24B, hi #E2BD67). Nunca uses colores de marca fijos: lee de aquí.
 */
internal object ThemeUtils {
    val ACCENT_COLORS = intArrayOf(
        0xFFC9A24B.toInt(), 0xFFE57373.toInt(), 0xFFFF8A65.toInt(), 0xFFFFD54F.toInt(),
        0xFF81C784.toInt(), 0xFF4DB6AC.toInt(), 0xFF4FC3F7.toInt(), 0xFF5C8DDB.toInt(),
        0xFF9575CD.toInt(), 0xFFBA68C8.toInt(), 0xFFF06292.toInt(), 0xFFB0BEC5.toInt()
    )
    const val MAX_CUSTOM = 12

    private var ver = 0

    /** Sube cuando cambia acento o fuente: las Activity se recrean al verlo distinto. */
    fun version(): Int = ver
    fun bump() { ver++ }

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun accent(c: Context): Int = sp(c).getInt("accent", ACCENT_COLORS[0])

    /** Dorado => #E2BD67; el resto, +10 % de luminosidad (tope 0.92). */
    fun hiFor(color: Int): Int {
        if (color == ACCENT_COLORS[0]) return 0xFFE2BD67.toInt()
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = minOf(0.92f, hsl[2] * 1.1f)
        return ColorUtils.HSLToColor(hsl)
    }

    fun accentHi(c: Context): Int = hiFor(accent(c))

    fun accentBg(c: Context): Int = (accent(c) and 0x00FFFFFF) or 0x1F000000

    fun setAccent(c: Context, color: Int) {
        sp(c).edit().putInt("accent", color).apply()
        ver++
    }

    /** Valida "#RRGGBB" (se añade "#" si falta). null si no es válido. */
    fun parseHex(s: String): Int? {
        var t = s.trim()
        if (!t.startsWith("#")) t = "#$t"
        if (!Regex("#[0-9a-fA-F]{6}").matches(t)) return null
        return try { Color.parseColor(t) } catch (e: Exception) { null }
    }

    fun customs(c: Context): List<Int> {
        val out = ArrayList<Int>()
        try {
            val raw = sp(c).getString("accent_custom", "") ?: ""
            for (h in raw.split(",")) {
                if (h.isBlank()) continue
                parseHex(h)?.let { out.add(it) }
            }
        } catch (e: Exception) {
            // Valor corrupto: sin colores propios.
        }
        return out
    }

    private fun saveCustoms(c: Context, l: List<Int>) {
        val s = l.joinToString(",") { String.format("%06X", it and 0x00FFFFFF) }
        sp(c).edit().putString("accent_custom", s).apply()
    }

    /** Añade un color propio (el más nuevo primero, máx. 12) y lo activa. */
    fun addCustom(c: Context, color: Int) {
        val l = ArrayList<Int>()
        l.add(color or 0xFF000000.toInt())
        for (x in customs(c)) if ((x and 0x00FFFFFF) != (color and 0x00FFFFFF)) l.add(x)
        saveCustoms(c, l.take(MAX_CUSTOM))
        setAccent(c, color or 0xFF000000.toInt())
    }

    /** Quita un color propio; si estaba activo vuelve al dorado. */
    fun removeCustom(c: Context, color: Int) {
        saveCustoms(c, customs(c).filter { (it and 0x00FFFFFF) != (color and 0x00FFFFFF) })
        if (accent(c) == color) setAccent(c, ACCENT_COLORS[0]) else ver++
    }

    /** Llamar en onCreate de toda Activity, justo después de super.onCreate. */
    fun applyTo(a: Activity) {
        a.window.statusBarColor = a.getColor(R.color.bg)
        a.window.navigationBarColor = a.getColor(R.color.bg)
    }
}
