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
