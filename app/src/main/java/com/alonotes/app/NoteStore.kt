package com.alonotes.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.io.File
import org.json.JSONObject
import java.util.UUID

/**
 * Biblioteca de notas y carpetas. SharedPreferences "notes", clave "items" (JSON):
 * [{"i":id,"p":idCarpetaPadre ("" = raíz),"n":nombre,"f":esCarpeta,"t":fecha ms,
 *   "c":idCategoria ("default" si falta),"fav":esFavorita,
 *   "pg":[{"d":"A4","o":"L"|"P","bg":colorARGB}]}]
 * Las categorías están en CategoryStore. JSON corrupto => lista vacía, sin crash.
 */
internal object NoteStore {
    class Item {
        var id = ""
        var parent = ""
        var name = ""
        var folder = false
        var t = 0L
        var pages = JSONArray()
        var categoryId = CategoryStore.DEFAULT_ID
        var isFavorite = false
    }

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("notes", Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): MutableList<Item> {
        val out = ArrayList<Item>()
        try {
            val a = JSONArray(sp(c).getString("items", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val it = Item()
                it.id = o.optString("i")
                it.parent = o.optString("p", "")
                it.name = o.optString("n")
                it.folder = o.optBoolean("f")
                it.t = o.optLong("t")
                it.categoryId = o.optString("c", CategoryStore.DEFAULT_ID).ifEmpty { CategoryStore.DEFAULT_ID }
                it.isFavorite = o.optBoolean("fav", false)
                o.optJSONArray("pg")?.let { pg -> it.pages = pg }
                out.add(it)
            }
        } catch (ignored: Exception) {
            // JSON dañado: se parte de una lista vacía y la app sigue funcionando.
        }
        return out
    }

    @Synchronized
    private fun save(c: Context, l: List<Item>) {
        try {
            val a = JSONArray()
            for (it in l) {
                val o = JSONObject()
                o.put("i", it.id)
                o.put("p", it.parent)
                o.put("n", it.name)
                o.put("f", it.folder)
                o.put("t", it.t)
                o.put("c", it.categoryId)
                o.put("fav", it.isFavorite)
                o.put("pg", it.pages)
                a.put(o)
            }
            sp(c).edit().putString("items", a.toString()).apply()
        } catch (ignored: Exception) {
            // Si no se puede guardar, se conserva el estado anterior.
        }
    }

    /**
     * Primera vez con categorías: crea "Mis notas" y escribe la categoría "default" en todo lo existente
     * (así los datos viejos no se rompen). Se ejecuta una sola vez.
     */
    @Synchronized
    fun migrate(c: Context) {
        CategoryStore.all(c)
        if (sp(c).getBoolean("cat_migrated", false)) return
        val l = load(c)
        if (l.isNotEmpty()) save(c, l)
        sp(c).edit().putBoolean("cat_migrated", true).apply()
    }

    /** Categoría efectiva: si la guardada ya no existe, cuenta como "default". */
    private fun catOf(x: Item, known: Set<String>): String =
        if (known.contains(x.categoryId)) x.categoryId else CategoryStore.DEFAULT_ID

    /** Clave del orden manual: la carpeta padre o, en la raíz, la categoría ("" para "default", como antes). */
    fun orderKey(parent: String, category: String): String =
        if (parent.isNotEmpty()) parent else if (category == CategoryStore.DEFAULT_ID) "" else "@" + category

    /** ¿Está x dentro de una carpeta archivada (a cualquier nivel)? */
    private fun underArchived(x: Item, byId: Map<String, Item>, arch: Collection<String>): Boolean {
        var p = x.parent
        var guard = 0
        while (p.isNotEmpty() && guard++ < 64) {
            if (arch.contains(p)) return true
            p = byId[p]?.parent ?: return false
        }
        return false
    }

    /** Todas las notas (sin carpetas, a cualquier nivel) de una categoría, las más recientes primero. */
    fun allNotes(c: Context, category: String): List<Item> {
        val known = CategoryStore.ids(c)
        val items = load(c)
        val byId = items.associateBy { it.id }
        val arch = ArchiveStore.ids(c)
        return items.filter { !it.folder && catOf(it, known) == category && !underArchived(it, byId, arch) }
            .sortedByDescending { it.t }
    }

    /** Notas favoritas de todas las categorías, las más recientes primero. */
    fun favorites(c: Context): List<Item> {
        val items = load(c)
        val byId = items.associateBy { it.id }
        val arch = ArchiveStore.ids(c)
        return items.filter { !it.folder && it.isFavorite && !underArchived(it, byId, arch) }
            .sortedByDescending { it.t }
    }

    @Synchronized
    fun setFavorite(c: Context, id: String, value: Boolean) {
        val all = load(c)
        var changed = false
        for (x in all) if (x.id == id && !x.folder && x.isFavorite != value) {
            x.isFavorite = value
            changed = true
        }
        if (changed) save(c, all)
    }

    /** Pasa todo lo de la categoría [from] a [to] (al eliminar una categoría). */
    @Synchronized
    fun reassignCategory(c: Context, from: String, to: String) {
        val all = load(c)
        var changed = false
        for (x in all) if (x.categoryId == from) {
            x.categoryId = to
            changed = true
        }
        if (changed) save(c, all)
    }

    /** Carpetas primero (sin las archivadas), luego notas; orden manual guardado y lo nuevo al final. En la raíz solo la categoría dada. */
    fun children(c: Context, parent: String, category: String = CategoryStore.DEFAULT_ID): List<Item> {
        val known = CategoryStore.ids(c)
        val all = load(c).filter { it.parent == parent && (parent.isNotEmpty() || catOf(it, known) == category) }
        val arch = ArchiveStore.ids(c)
        val saved = OrderStore.get(c, orderKey(parent, category))
        val folders = all.filter { it.folder && !arch.contains(it.id) }.sortedByDescending { it.t }
        val docs = all.filter { !it.folder }.sortedByDescending { it.t }
        return OrderStore.applyOrder(folders, saved) { it.id } + OrderStore.applyOrder(docs, saved) { it.id }
    }

    /** Carpetas archivadas (de cualquier nivel). */
    fun archivedFolders(c: Context): List<Item> {
        val arch = ArchiveStore.ids(c)
        return load(c).filter { it.folder && arch.contains(it.id) }.sortedBy { it.name.lowercase() }
    }

    /** Contenido de una carpeta sin filtrar archivadas (para Archivados). */
    fun contentsOf(c: Context, parent: String): List<Item> =
        load(c).filter { it.parent == parent }.sortedWith(Comparator { x, y ->
            if (x.folder != y.folder) (if (x.folder) -1 else 1) else y.t.compareTo(x.t)
        })

    fun get(c: Context, id: String): Item? = load(c).firstOrNull { it.id == id }

    @Synchronized
    fun add(
        c: Context, parent: String, folder: Boolean, name: String?,
        category: String = CategoryStore.DEFAULT_ID, favorite: Boolean = false
    ): Item {
        val all = load(c)
        var nm = name?.trim().orEmpty()
        if (nm.isEmpty()) {
            val n = all.count { it.folder == folder } + 1
            nm = (if (folder) "Carpeta " else "Nota ") + n
        }
        val it = Item()
        it.id = UUID.randomUUID().toString()
        it.parent = parent
        it.name = nm
        it.folder = folder
        it.t = System.currentTimeMillis()
        it.categoryId = category
        it.isFavorite = favorite && !folder
        all.add(it)
        save(c, all)
        return it
    }

    @Synchronized
    fun rename(c: Context, id: String, name: String) {
        val all = load(c)
        for (it in all) if (it.id == id) {
            it.name = name.trim()
            it.t = System.currentTimeMillis()
        }
        save(c, all)
    }

    /** Borra el elemento y, si es carpeta, todo su contenido. */
    @Synchronized
    fun delete(c: Context, id: String) {
        val all = load(c)
        val gone = HashSet<String>()
        gone.add(id)
        var grew = true
        while (grew) {
            grew = false
            for (it in all) if (gone.contains(it.parent) && gone.add(it.id)) grew = true
        }
        for (g in gone) {
            FolderIconStore.remove(c, g)
            StrokeStore.delete(c, g)
            LayerRepository.delete(c, g)
            try {
                File(c.filesDir, "images/" + g).deleteRecursively()
            } catch (ignored: Exception) {
                // Nada que borrar.
            }
            ArchiveStore.set(c, g, false)
        }
        save(c, all.filter { !gone.contains(it.id) })
    }

    @Synchronized
    /** Mueve una hoja de la posición [from] a [to] (0 = la primera). Los trazos y capas se reordenan aparte. */
    @Synchronized
    fun movePage(c: Context, id: String, from: Int, to: Int) {
        val all = load(c)
        val doc = all.firstOrNull { it.id == id } ?: return
        val n = doc.pages.length()
        if (from !in 0 until n || to !in 0 until n || from == to) return
        val l = ArrayList<Any?>()
        for (i in 0 until n) l.add(doc.pages.opt(i))
        l.add(to, l.removeAt(from))
        val arr = JSONArray()
        for (x in l) arr.put(x)
        doc.pages = arr
        doc.t = System.currentTimeMillis()
        save(c, all)
    }

    fun addPages(c: Context, id: String, n: Int, dim: String, landscape: Boolean, bg: Int) {
        val all = load(c)
        for (item in all) {
            if (item.id != id) continue
            try {
                for (k in 0 until n) {
                    val o = JSONObject()
                    o.put("d", dim)
                    o.put("o", if (landscape) "L" else "P")
                    o.put("bg", bg)
                    item.pages.put(o)
                }
            } catch (ignored: Exception) {
                // No debería ocurrir: son tipos simples.
            }
            item.t = System.currentTimeMillis()
        }
        save(c, all)
    }
}
