package com.alonotes.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Categoría de la biblioteca. La categoría por defecto ("Mis notas", id "default") no se puede eliminar. */
internal data class Category(val id: String, val nombre: String, val esPorDefecto: Boolean = false)

/**
 * Lista de categorías. SharedPreferences "notes" (la misma de las notas), clave "cats" (JSON):
 * [{"i":id,"n":nombre,"d":esPorDefecto}]. Si no existe o está dañada, se crea "Mis notas" (id "default");
 * las notas y carpetas sin categoría (datos de versiones anteriores) cuentan como "default".
 */
internal object CategoryStore {
    const val DEFAULT_ID = "default"
    const val DEFAULT_NAME = "Mis notas"
    private const val KEY = "cats"

    private fun sp(c: Context) = c.getSharedPreferences("notes", Context.MODE_PRIVATE)

    /** Todas las categorías, con la por defecto siempre primera. Nunca devuelve una lista vacía. */
    @Synchronized
    fun all(c: Context): List<Category> {
        val out = ArrayList<Category>()
        try {
            val a = JSONArray(sp(c).getString(KEY, "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("i")
                if (id.isEmpty() || out.any { it.id == id }) continue
                val isDef = id == DEFAULT_ID
                val nm = o.optString("n").trim().ifEmpty { if (isDef) DEFAULT_NAME else "Categoría" }
                out.add(Category(id, nm, isDef))
            }
        } catch (ignored: Exception) {
            // JSON dañado: se parte de lo leído y se crea la categoría por defecto.
        }
        val di = out.indexOfFirst { it.id == DEFAULT_ID }
        if (di < 0) {
            out.add(0, Category(DEFAULT_ID, DEFAULT_NAME, true))
            write(c, out)
        } else if (di > 0) {
            out.add(0, out.removeAt(di))
        }
        return out
    }

    fun ids(c: Context): Set<String> = all(c).map { it.id }.toSet()

    fun get(c: Context, id: String): Category? = all(c).firstOrNull { it.id == id }

    @Synchronized
    fun add(c: Context, name: String?): Category {
        val l = ArrayList(all(c))
        var nm = name?.trim().orEmpty()
        if (nm.isEmpty()) nm = "Categoría " + l.size
        val cat = Category(UUID.randomUUID().toString(), nm, false)
        l.add(cat)
        write(c, l)
        return cat
    }

    @Synchronized
    fun rename(c: Context, id: String, name: String) {
        val nm = name.trim()
        if (nm.isEmpty()) return
        write(c, all(c).map { if (it.id == id) it.copy(nombre = nm) else it })
    }

    /** Guarda el orden de las categorías. La por defecto queda siempre primera, aunque se la renombre. */
    @Synchronized
    fun reorder(c: Context, ids: List<String>) {
        val cur = all(c)
        val byId = cur.associateBy { it.id }
        val out = ArrayList<Category>()
        byId[DEFAULT_ID]?.let { out.add(it) }
        for (id in ids) {
            val x = byId[id]
            if (x != null && out.none { it.id == id }) out.add(x)
        }
        for (x in cur) if (out.none { it.id == x.id }) out.add(x)
        write(c, out)
    }

    /** Elimina una categoría (nunca la por defecto). Mover antes sus notas con NoteStore.reassignCategory. */
    @Synchronized
    fun remove(c: Context, id: String): Boolean {
        if (id == DEFAULT_ID) return false
        write(c, all(c).filter { it.id != id })
        return true
    }

    private fun write(c: Context, l: List<Category>) {
        try {
            val a = JSONArray()
            for (x in l) {
                val o = JSONObject()
                o.put("i", x.id)
                o.put("n", x.nombre)
                o.put("d", x.esPorDefecto)
                a.put(o)
            }
            sp(c).edit().putString(KEY, a.toString()).apply()
        } catch (ignored: Exception) {
            // Si no se puede guardar, se conserva el estado anterior.
        }
    }
}
