package com.alonotes.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Carga de las imágenes de fondo de las hojas, sin frenar el dibujo: [get] devuelve el bitmap si ya está en la caché de [ImageUtil]; si no,
 * lo decodifica (reducido a [maxSide] px) en un hilo aparte y avisa con [onReady] para que la hoja se vuelva a dibujar. Un archivo que no
 * se pudo leer no se reintenta en cada cuadro.
 */
internal object PageBackground {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()

    /** El fondo ya cargado o null (y entonces se está cargando, o falló). Llamar desde el hilo principal. */
    fun get(file: File, maxSide: Int, onReady: () -> Unit): Bitmap? {
        ImageUtil.peek(file.path, maxSide)?.let { return it }
        val key = file.path + "@" + maxSide
        if (failed.contains(key) || !loading.add(key)) return null
        exec.execute {
            val bm = ImageUtil.decodeFile(file.path, maxSide)
            main.post {
                loading.remove(key)
                if (bm == null) failed.add(key)
                onReady()
            }
        }
        return null
    }

    /** Olvida lo decodificado y lo fallido de un archivo (al borrarlo o reemplazarlo). */
    fun forget(file: File) {
        ImageUtil.forget(file.path)
        failed.removeAll { it.startsWith(file.path + "@") }
    }
}
