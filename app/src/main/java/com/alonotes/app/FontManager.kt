package com.alonotes.app

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Estilos de letra (como FontsScreen de AloTube). SharedPreferences "settings", clave "font":
 * "sys:<familia>" (sistema), "asset:Nombre.ttf" (assets/fonts/) o "file:Nombre.ttf" (importada
 * en filesDir/fonts/). Sin elección: la primera de assets/fonts o la del sistema.
 * Fuente que falta o es inválida => la predeterminada, sin error.
 */
internal object FontManager {
    class Opt(val id: String, val label: String, val tf: Typeface)

    private val cache = HashMap<String, Typeface>()
    private val SYSTEM = listOf(
        "sys:sans-serif" to "Sans Serif",
        "sys:serif" to "Serif",
        "sys:monospace" to "Monoespaciada",
        "sys:cursive" to "Cursiva",
        "sys:casual" to "Casual"
    )

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "fonts")
    private fun isFont(n: String) = n.endsWith(".ttf", true) || n.endsWith(".otf", true)

    private fun assetNames(c: Context): List<String> = try {
        (c.assets.list("fonts") ?: emptyArray()).filter { isFont(it) }.sorted()
    } catch (e: Exception) {
        emptyList()
    }

    private fun fileNames(c: Context): List<String> =
        (dir(c).listFiles() ?: emptyArray()).map { it.name }.filter { isFont(it) }.sorted()

    private fun resolve(c: Context, id: String): Typeface? {
        cache[id]?.let { return it }
        val tf: Typeface? = try {
            when {
                id.startsWith("sys:") -> Typeface.create(id.removePrefix("sys:"), Typeface.NORMAL)
                id.startsWith("asset:") -> Typeface.createFromAsset(c.assets, "fonts/" + id.removePrefix("asset:"))
                id.startsWith("file:") -> {
                    val f = File(dir(c), id.removePrefix("file:"))
                    if (f.exists()) Typeface.createFromFile(f) else null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        if (tf != null) cache[id] = tf
        return tf
    }

    fun selectedId(c: Context): String {
        val v = sp(c).getString("font", null)
        if (v != null && resolve(c, v) != null) return v
        return assetNames(c).firstOrNull()?.let { "asset:$it" } ?: "sys:sans-serif"
    }

    fun current(c: Context): Typeface = resolve(c, selectedId(c)) ?: Typeface.DEFAULT

    fun list(c: Context): List<Opt> {
        val out = ArrayList<Opt>()
        for (n in assetNames(c)) resolve(c, "asset:$n")?.let { out.add(Opt("asset:$n", n.substringBeforeLast('.'), it)) }
        for (n in fileNames(c)) resolve(c, "file:$n")?.let { out.add(Opt("file:$n", n.substringBeforeLast('.'), it)) }
        for ((id, label) in SYSTEM) resolve(c, id)?.let { out.add(Opt(id, label, it)) }
        return out
    }

    fun set(c: Context, id: String) {
        sp(c).edit().putString("font", id).apply()
        ThemeUtils.bump()
    }

    /** Importa un .ttf/.otf elegido por el usuario. Fuera del hilo principal. */
    fun import(c: Context, uri: Uri): Boolean {
        var name: String? = null
        try {
            c.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = it.getString(i)
                }
            }
        } catch (e: Exception) {
            // Sin nombre: se rechaza abajo.
        }
        val n = name ?: return false
        if (!isFont(n)) return false
        val f = File(dir(c), n.replace("/", "_"))
        if (!ImageUtil.copy(c, uri, f)) return false
        return try {
            Typeface.createFromFile(f)
            true
        } catch (e: Exception) {
            f.delete()
            false
        }
    }
}
