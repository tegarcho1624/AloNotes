package com.alonotes.app

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Estado guardado de las capas de una hoja: capas (sin trazos), la seleccionada y cuántos trazos tiene cada una. */
@Serializable
internal class PageState(
    val selected: Int = 0,
    val layers: List<Layer> = emptyList(),
    val counts: List<Int> = emptyList()
)

/** Archivo de capas de una nota (una entrada por hoja). */
@Serializable
internal class DocState(
    val version: Int = 1,
    val pages: List<PageState> = emptyList()
)

/**
 * Guardado de la estructura de capas: filesDir/layers/<idNota>.json (nombre, orden, visibilidad, opacidad
 * y capa seleccionada de cada hoja). Los trazos siguen en strokes/<idNota>.bin; aquí solo se guarda cuántos
 * trazos tiene cada capa (el .bin los guarda juntos, de abajo hacia arriba) para devolverlos a su capa al abrir.
 * Se escribe en segundo plano y por archivo temporal. Archivo dañado o inexistente => una sola capa por hoja.
 */
internal object LayerRepository {
    private val exec = Executors.newSingleThreadExecutor()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(c: Context, id: String) = File(File(c.filesDir, "layers"), "$id.json")

    /** Lee el archivo. Si no existe o está dañado devuelve lista vacía (cada hoja queda con su capa por defecto). */
    fun loadLayers(c: Context, id: String): List<PageState> {
        val f = file(c, id)
        if (!f.exists()) return emptyList()
        return try {
            json.decodeFromString(DocState.serializer(), f.readText()).pages
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Aplica lo guardado a las hojas ya cargadas (llamar justo después de StrokeStore.load). */
    fun applyTo(c: Context, id: String, doc: DocStrokes) {
        for ((i, ps) in loadLayers(c, id).withIndex()) {
            if (i >= 5000) break
            doc.page(i).restore(ps.layers, ps.selected, ps.counts)
        }
    }

    /** Guarda la estructura de capas de toda la nota. El JSON se arma al instante y se escribe en segundo plano. */
    fun saveLayers(c: Context, id: String, doc: DocStrokes) {
        val text = try {
            val pages = doc.pages.map { pl ->
                PageState(pl.selected, ArrayList(pl.layers), pl.layers.map { it.canvas.size })
            }
            json.encodeToString(DocState.serializer(), DocState(1, pages))
        } catch (e: Exception) {
            return
        }
        val app = c.applicationContext
        exec.execute { write(app, id, text) }
    }

    private fun write(c: Context, id: String, text: String) {
        try {
            val f = file(c, id)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$id.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // Si no se puede escribir, queda el archivo anterior.
        }
    }

    fun delete(c: Context, id: String) {
        try {
            file(c, id).delete()
        } catch (e: Exception) {
            // Nada que borrar.
        }
    }
}
