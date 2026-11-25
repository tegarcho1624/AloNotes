package com.alonotes.app

import android.content.Context
import android.net.Uri
import java.io.File

/** Pantallas que pueden tener fondo propio (como BgScope de AloTube). */
internal enum class BgScope(val key: String, val label: String) {
    LIBRARY("library", "Mis notas"),
    EDITOR("editor", "Editor"),
    SETTINGS("settings", "Ajustes")
}

/**
 * Fondos. SharedPreferences "backgrounds": "<pantalla>" = ruta, "<pantalla>_type" = image|video,
 * "<pantalla>_scale" = Crop|FillBounds|Fit. Archivos copiados a filesDir/backgrounds/
 * (imagen ≤ 1920 px, JPEG 90; video tal cual). Ruta que ya no existe => sin fondo.
 */
internal object BackgroundStore {
    private fun sp(c: Context) = c.getSharedPreferences("backgrounds", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "backgrounds")

    fun path(c: Context, s: BgScope): String? = try {
        val p = sp(c).getString(s.key, null)
        if (p != null && File(p).exists()) p else null
    } catch (e: Exception) {
        null
    }

    fun isVideo(c: Context, s: BgScope): Boolean = sp(c).getString(s.key + "_type", "image") == "video"

    fun scale(c: Context, s: BgScope): String {
        val v = sp(c).getString(s.key + "_scale", "Crop")
        return if (v == "FillBounds" || v == "Fit") v else "Crop"
    }

    fun setScale(c: Context, s: BgScope, v: String) {
        sp(c).edit().putString(s.key + "_scale", v).apply()
    }

    private fun commit(c: Context, s: BgScope, f: File, type: String) {
        val old = sp(c).getString(s.key, null)
        sp(c).edit().putString(s.key, f.absolutePath).putString(s.key + "_type", type).apply()
        if (old != null && old != f.absolutePath) File(old).delete()
    }

    fun setImage(c: Context, s: BgScope, uri: Uri): Boolean {
        val f = ImageUtil.saveFromUri(c, uri, File(dir(c), s.key + "_" + System.currentTimeMillis()), 1920, 90, false)
            ?: return false
        commit(c, s, f, "image")
        return true
    }

    fun setVideo(c: Context, s: BgScope, uri: Uri): Boolean {
        val f = File(dir(c), s.key + "_" + System.currentTimeMillis() + ".mp4")
        if (!ImageUtil.copy(c, uri, f)) return false
        commit(c, s, f, "video")
        return true
    }

    fun remove(c: Context, s: BgScope) {
        val old = sp(c).getString(s.key, null)
        sp(c).edit().remove(s.key).remove(s.key + "_type").apply()
        if (old != null) File(old).delete()
    }
}

/** Íconos de carpeta. "folder_icons": "notes_<idCarpeta>" = ruta; imagen en filesDir/folder_icons/ (≤ 768 px). */
internal object FolderIconStore {
    private fun sp(c: Context) = c.getSharedPreferences("folder_icons", Context.MODE_PRIVATE)

    fun get(c: Context, id: String): String? = try {
        val p = sp(c).getString("notes_$id", null)
        if (p != null && File(p).exists()) p else null
    } catch (e: Exception) {
        null
    }

    /** Fuera del hilo principal. */
    fun set(c: Context, id: String, uri: Uri): Boolean {
        val f = ImageUtil.saveFromUri(
            c, uri, File(File(c.filesDir, "folder_icons"), "notes_${id}_" + System.currentTimeMillis()), 768, 92, true
        ) ?: return false
        val old = sp(c).getString("notes_$id", null)
        sp(c).edit().putString("notes_$id", f.absolutePath).apply()
        if (old != null && old != f.absolutePath) File(old).delete()
        return true
    }

    fun remove(c: Context, id: String) {
        val old = sp(c).getString("notes_$id", null)
        sp(c).edit().remove("notes_$id").apply()
        if (old != null) File(old).delete()
    }
}

/** Carpetas archivadas. "folder_archive": "archived" = conjunto de "notes_<id>". Solo una marca. */
internal object ArchiveStore {
    private fun sp(c: Context) = c.getSharedPreferences("folder_archive", Context.MODE_PRIVATE)

    fun ids(c: Context): Set<String> = try {
        (sp(c).getStringSet("archived", emptySet()) ?: emptySet())
            .filter { it.startsWith("notes_") }.map { it.removePrefix("notes_") }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun isArchived(c: Context, id: String): Boolean = ids(c).contains(id)

    fun set(c: Context, id: String, archived: Boolean) {
        val s = HashSet(sp(c).getStringSet("archived", emptySet()) ?: emptySet())
        if (archived) s.add("notes_$id") else s.remove("notes_$id")
        sp(c).edit().putStringSet("archived", s).apply()
    }
}

/** Orden manual. "local_order": "items_<idCarpeta | root>" = ids separados por comas. Ids que ya no existen se ignoran; lo nuevo va al final. */
internal object OrderStore {
    private fun sp(c: Context) = c.getSharedPreferences("local_order", Context.MODE_PRIVATE)
    private fun key(parent: String) = "items_" + (if (parent.isEmpty()) "root" else parent)

    fun get(c: Context, parent: String): List<String> = try {
        (sp(c).getString(key(parent), "") ?: "").split(",").filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    fun set(c: Context, parent: String, ids: List<String>) {
        sp(c).edit().putString(key(parent), ids.joinToString(",")).apply()
    }

    fun <T> applyOrder(list: List<T>, saved: List<String>, id: (T) -> String): List<T> {
        val pos = HashMap<String, Int>()
        saved.forEachIndexed { i, s -> pos[s] = i }
        val known = list.filter { pos.containsKey(id(it)) }.sortedBy { pos[id(it)] ?: 0 }
        val rest = list.filter { !pos.containsKey(id(it)) }
        return known + rest
    }
}

/**
 * Ajustes del lápiz. SharedPreferences "pen": type 0 lápiz | 1 pluma | 2 pincel, smoothing 0..100
 * (10 por defecto), width 0 fino | 1 normal | 2 denso, color_idx 0..3, colors = 4 × "RRGGBB",
 * saved = hasta 14 colores guardados. Valor corrupto => por defecto.
 */
internal object PenStore {
    const val MAX_SAVED = 14
    private val DEFAULT_COLORS = intArrayOf(
        0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFFFF5CB8.toInt(), 0xFF6BFFF3.toInt()
    )

    private fun sp(c: Context) = c.getSharedPreferences("pen", Context.MODE_PRIVATE)

    fun type(c: Context): Int = sp(c).getInt("type", 0).coerceIn(0, 2)
    fun setType(c: Context, v: Int) = sp(c).edit().putInt("type", v).apply()

    fun smoothing(c: Context): Int = sp(c).getInt("smoothing", 10).coerceIn(0, 100)
    fun setSmoothing(c: Context, v: Int) = sp(c).edit().putInt("smoothing", v.coerceIn(0, 100)).apply()

    fun width(c: Context): Int = sp(c).getInt("width", 1).coerceIn(0, 2)
    fun setWidth(c: Context, v: Int) = sp(c).edit().putInt("width", v).apply()

    fun eraser(c: Context): Int = sp(c).getInt("eraser", 1).coerceIn(0, 2)
    fun setEraser(c: Context, v: Int) = sp(c).edit().putInt("eraser", v).apply()

    /** Grosor del trazo (mm de la hoja) según fino / normal / denso. */
    fun widthMm(idx: Int): Float = floatArrayOf(0.35f, 0.8f, 1.8f)[idx.coerceIn(0, 2)]

    /** Radio del borrador (mm de la hoja) según fino / normal / grande. */
    fun eraserMm(idx: Int): Float = floatArrayOf(1.5f, 4f, 9f)[idx.coerceIn(0, 2)]

    fun colorIdx(c: Context): Int = sp(c).getInt("color_idx", 0).coerceIn(0, 3)
    fun setColorIdx(c: Context, v: Int) = sp(c).edit().putInt("color_idx", v).apply()

    private fun parse(raw: String?): List<Int> {
        val out = ArrayList<Int>()
        for (h in (raw ?: "").split(",")) {
            if (h.isBlank()) continue
            ThemeUtils.parseHex(h)?.let { out.add(it) }
        }
        return out
    }

    private fun csv(l: List<Int>) = l.joinToString(",") { String.format("%06X", it and 0x00FFFFFF) }

    fun colors(c: Context): IntArray {
        val l = try { parse(sp(c).getString("colors", "")) } catch (e: Exception) { emptyList() }
        return if (l.size == 4) l.toIntArray() else DEFAULT_COLORS.copyOf()
    }

    fun setColor(c: Context, idx: Int, color: Int) {
        val a = colors(c)
        a[idx.coerceIn(0, 3)] = color or 0xFF000000.toInt()
        sp(c).edit().putString("colors", csv(a.toList())).apply()
    }

    // Marca texto: su propio grosor y sus 4 colores (la paleta guardada se comparte con el lápiz).
    private val DEFAULT_HL = intArrayOf(
        0xFFFFEE58.toInt(), 0xFF69F0AE.toInt(), 0xFFFF80AB.toInt(), 0xFF6BFFF3.toInt()
    )

    fun hlWidth(c: Context): Int = sp(c).getInt("hl_width", 1).coerceIn(0, 2)
    fun setHlWidth(c: Context, v: Int) = sp(c).edit().putInt("hl_width", v).apply()

    /** Grosor del marca texto (mm de la hoja) según fino / mediano / grande. */
    fun hlWidthMm(idx: Int): Float = floatArrayOf(2.5f, 5f, 9f)[idx.coerceIn(0, 2)]

    fun hlColorIdx(c: Context): Int = sp(c).getInt("hl_color_idx", 0).coerceIn(0, 3)
    fun setHlColorIdx(c: Context, v: Int) = sp(c).edit().putInt("hl_color_idx", v).apply()

    fun hlColors(c: Context): IntArray {
        val l = try { parse(sp(c).getString("hl_colors", "")) } catch (e: Exception) { emptyList() }
        return if (l.size == 4) l.toIntArray() else DEFAULT_HL.copyOf()
    }

    fun setHlColor(c: Context, idx: Int, color: Int) {
        val a = hlColors(c)
        a[idx.coerceIn(0, 3)] = color or 0xFF000000.toInt()
        sp(c).edit().putString("hl_colors", csv(a.toList())).apply()
    }

    /**
     * Colores guardados en [MAX_SAVED] espacios FIJOS (0 = vacío), para elegir en cuál se guarda cada color.
     * Se escribe en la misma clave "saved" con "-" en los vacíos; los datos de antes (lista sin huecos) se leen igual.
     */
    fun slots(c: Context): IntArray {
        val out = IntArray(MAX_SAVED)
        try {
            val toks = (sp(c).getString("saved", "") ?: "").split(",")
            for ((i, t) in toks.withIndex()) {
                if (i >= MAX_SAVED) break
                val s = t.trim()
                if (s.isEmpty() || s == "-") continue
                ThemeUtils.parseHex(s)?.let { out[i] = it or 0xFF000000.toInt() }
            }
        } catch (e: Exception) {
            // Datos dañados: todos los espacios quedan vacíos.
        }
        return out
    }

    fun setSlots(c: Context, slots: IntArray) {
        val text = slots.take(MAX_SAVED).joinToString(",") { if (it == 0) "-" else String.format("%06X", it and 0x00FFFFFF) }
        sp(c).edit().putString("saved", text).apply()
    }

    fun saved(c: Context): List<Int> =
        try { parse(sp(c).getString("saved", "")).take(MAX_SAVED) } catch (e: Exception) { emptyList() }

    fun setSaved(c: Context, l: List<Int>) {
        sp(c).edit().putString("saved", csv(l.take(MAX_SAVED))).apply()
    }
}
