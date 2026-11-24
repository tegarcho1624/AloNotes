package com.alonotes.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Un sticker: su categoría y el archivo (nombre dentro de filesDir/stickers/). */
internal data class Sticker(val id: String, val categoryId: String, val path: String)

internal data class StickerCategory(val id: String, val name: String)

/**
 * Stickers y sus categorías. SharedPreferences "stickers": "items" (JSON [{"i","c","p"}], su orden es el orden en el menú),
 * "cats" (JSON [{"i","n"}]) y "cur" (categoría actual del menú). Los archivos están en filesDir/stickers/.
 * Siempre existe la categoría "default" ("Mis stickers"), que no se puede eliminar. Un JSON dañado no hace crash.
 */
internal object StickerStore {
    const val DEFAULT_ID = "default"
    private const val DEFAULT_NAME = "Mis stickers"

    private fun sp(c: Context) = c.getSharedPreferences("stickers", Context.MODE_PRIVATE)

    fun dir(c: Context): File {
        val d = File(c.filesDir, "stickers")
        d.mkdirs()
        return d
    }

    fun fileOf(c: Context, s: Sticker): File = File(dir(c), s.path)

    // ---------- Lectura y escritura ----------

    private fun loadCats(c: Context): ArrayList<StickerCategory> {
        val out = ArrayList<StickerCategory>()
        try {
            val a = JSONArray(sp(c).getString("cats", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("i")
                if (id.isEmpty() || out.any { it.id == id }) continue
                val nm = o.optString("n").trim().ifEmpty { if (id == DEFAULT_ID) DEFAULT_NAME else "Categoría" }
                out.add(StickerCategory(id, nm))
            }
        } catch (ignored: Exception) {
            // JSON dañado: se parte de lo leído.
        }
        val di = out.indexOfFirst { it.id == DEFAULT_ID }
        if (di < 0) out.add(0, StickerCategory(DEFAULT_ID, DEFAULT_NAME)) else if (di > 0) out.add(0, out.removeAt(di))
        return out
    }

    private fun saveCats(c: Context, l: List<StickerCategory>) {
        try {
            val a = JSONArray()
            for (x in l) {
                val o = JSONObject()
                o.put("i", x.id)
                o.put("n", x.name)
                a.put(o)
            }
            sp(c).edit().putString("cats", a.toString()).apply()
        } catch (ignored: Exception) {
            // Se conserva lo anterior.
        }
    }

    private fun loadItems(c: Context): ArrayList<Sticker> {
        val out = ArrayList<Sticker>()
        try {
            val a = JSONArray(sp(c).getString("items", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("i")
                val p = o.optString("p")
                if (id.isEmpty() || p.isEmpty()) continue
                out.add(Sticker(id, o.optString("c", DEFAULT_ID).ifEmpty { DEFAULT_ID }, p))
            }
        } catch (ignored: Exception) {
            // JSON dañado: lista vacía.
        }
        return out
    }

    private fun saveItems(c: Context, l: List<Sticker>) {
        try {
            val a = JSONArray()
            for (x in l) {
                val o = JSONObject()
                o.put("i", x.id)
                o.put("c", x.categoryId)
                o.put("p", x.path)
                a.put(o)
            }
            sp(c).edit().putString("items", a.toString()).apply()
        } catch (ignored: Exception) {
            // Se conserva lo anterior.
        }
    }

    // ---------- Categorías ----------

    @Synchronized
    fun categories(c: Context): List<StickerCategory> = loadCats(c)

    @Synchronized
    fun addCategory(c: Context, name: String?): StickerCategory {
        val l = loadCats(c)
        var nm = name?.trim().orEmpty()
        if (nm.isEmpty()) nm = "Categoría " + l.size
        val cat = StickerCategory(UUID.randomUUID().toString(), nm)
        l.add(cat)
        saveCats(c, l)
        return cat
    }

    /** Elimina una categoría y sus stickers (también sus archivos). La categoría por defecto no se puede eliminar. */
    @Synchronized
    fun removeCategory(c: Context, id: String): Boolean {
        if (id == DEFAULT_ID) return false
        val items = loadItems(c)
        val keep = ArrayList<Sticker>()
        for (s in items) {
            if (s.categoryId == id) {
                try {
                    fileOf(c, s).delete()
                } catch (ignored: Exception) {
                    // Queda suelto.
                }
            } else {
                keep.add(s)
            }
        }
        saveItems(c, keep)
        saveCats(c, loadCats(c).filter { it.id != id })
        if (currentCategory(c) == id) setCurrent(c, DEFAULT_ID)
        return true
    }

    /** Categoría que muestra el menú de stickers (y donde se guardan los nuevos stickers). */
    fun currentCategory(c: Context): String {
        val cur = sp(c).getString("cur", DEFAULT_ID) ?: DEFAULT_ID
        return if (loadCats(c).any { it.id == cur }) cur else DEFAULT_ID
    }

    fun setCurrent(c: Context, id: String) {
        sp(c).edit().putString("cur", id).apply()
    }

    // ---------- Stickers ----------

    @Synchronized
    fun stickers(c: Context, categoryId: String): List<Sticker> = loadItems(c).filter { it.categoryId == categoryId }

    /** Registra un sticker cuyo archivo ya está en filesDir/stickers/. */
    @Synchronized
    fun add(c: Context, categoryId: String, file: File): Sticker {
        val items = loadItems(c)
        val s = Sticker(UUID.randomUUID().toString(), categoryId, file.name)
        items.add(s)
        saveItems(c, items)
        return s
    }

    /** Borra el sticker y su archivo. */
    @Synchronized
    fun remove(c: Context, id: String) {
        val items = loadItems(c)
        val s = items.firstOrNull { it.id == id } ?: return
        try {
            fileOf(c, s).delete()
        } catch (ignored: Exception) {
            // Queda suelto.
        }
        saveItems(c, items.filter { it.id != id })
    }

    /** Guarda el nuevo orden de los stickers de una categoría (los demás no cambian de lugar). */
    @Synchronized
    fun reorder(c: Context, categoryId: String, ids: List<String>) {
        val items = loadItems(c)
        val mine = items.filter { it.categoryId == categoryId }.associateBy { it.id }
        val ordered = ArrayList<Sticker>()
        for (id in ids) mine[id]?.let { ordered.add(it) }
        for (s in mine.values) if (ordered.none { it.id == s.id }) ordered.add(s)
        val out = ArrayList<Sticker>()
        out.addAll(items.filter { it.categoryId != categoryId })
        out.addAll(ordered)
        saveItems(c, out)
    }
}
