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
    val counts: List<Int> = emptyList(),
    /** Imágenes de cada capa (misma posición que [layers]). */
    val images: List<List<ImageDto>> = emptyList(),
    /** Textos de cada capa (misma posición que [layers]); los archivos viejos no lo traen. */
    val texts: List<List<TextDto>> = emptyList(),
    /** Imagen de fondo de la hoja: nombre de su archivo en images/<idNota>/ (null = ninguna). Los archivos viejos no lo traen. */
    val backgroundImagePath: String? = null
)

/** Texto guardado: contenido, estilo (fuente = id de FontManager) y recuadro sin rotar en mm de la hoja con su rotación. */
@Serializable
internal class TextDto(
    val id: String,
    val text: String,
    val size: Float = 40f,
    val family: String = "",
    val italic: Boolean = false,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val rot: Float = 0f,
    val op: Float = 1f,
    val lk: Boolean = false,
    val fh: Boolean = false,
    val fv: Boolean = false,
    val lt: Int = 0,
    val ld: String = "",
    /** Ancho fijo, espaciado de letras (0 a 0,5), alineado (0 izquierda, 1 centro, 2 derecha) y altura de línea (0,2 a 2). */
    val fw: Boolean = false,
    val ls: Float = 0f,
    val ta: Int = 0,
    val lh: Float = 1f
)

/** Imagen guardada: archivo (en images/<idNota>/), rectángulo sin rotar en mm de la hoja, rotación y ancho original. */
@Serializable
internal class ImageDto(
    val id: String,
    val file: String,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val rot: Float = 0f,
    val bw: Float = 0f,
    /** Espejo horizontal / vertical y opacidad (0..1). */
    val fh: Boolean = false,
    val fv: Boolean = false,
    val op: Float = 1f,
    /** Enlace: tipo (0 ninguno, 1 web, 2 interno) y destino. */
    val lt: Int = 0,
    val ld: String = "",
    /** Bloqueada. */
    val lk: Boolean = false
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

    /** Espera a que terminen las escrituras pendientes (para leer justo después, desde otra pantalla). */
    fun flush() {
        try {
            exec.submit(Runnable { }).get()
        } catch (ignored: Exception) {
            // Si se interrumpe, se sigue igual.
        }
    }

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
        FontManager.current(c) // los textos miden con su fuente al cargarse: FontManager necesita ya su contexto
        for ((i, ps) in loadLayers(c, id).withIndex()) {
            if (i >= 5000) break
            val imgDir = File(c.filesDir, "images/" + id)
            val pg = doc.page(i)
            pg.restore(ps.layers, ps.selected, ps.counts, ps.images, imgDir, ps.texts)
            pg.backgroundImagePath = ps.backgroundImagePath
            pg.bgDir = imgDir
        }
    }

    /** Guarda la estructura de capas de toda la nota. El JSON se arma al instante y se escribe en segundo plano. */
    fun saveLayers(c: Context, id: String, doc: DocStrokes) {
        val text = try {
            val pages = doc.pages.map { pl ->
                PageState(
                    pl.selected, ArrayList(pl.layers), pl.layers.map { it.canvas.size },
                    pl.layers.map { ly ->
                        ly.items.map { ImageDto(
                            it.id, it.file.name, it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom,
                            it.rotation, it.baseW, it.isFlippedH, it.isFlippedV, it.opacity, it.linkType, it.linkData, it.isLocked
                        ) }
                    },
                    pl.layers.map { ly ->
                        ly.texts.map { TextDto(
                            it.id, it.text, it.fontSize, it.fontFamily, it.isItalic, it.isBold, it.isUnderline, it.color,
                            it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom, it.rotation, it.opacity, it.isLocked,
                            it.isFlippedH, it.isFlippedV, it.linkType, it.linkData,
                            it.isFixedWidth, it.letterSpacing, it.textAlign, it.lineHeight
                        ) }
                    },
                    pl.backgroundImagePath
                )
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
