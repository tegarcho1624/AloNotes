package com.alonotes.app

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Capa de fondo de una pantalla (como BackgroundLayer de AloTube, con video añadido).
 * Dibuja Bg y, si hay fondo para el ámbito, imagen o video (mudo, en bucle) con una capa
 * negra al 60 %. La pantalla encima debe ser transparente. Llamar refresh() en onResume
 * y pause() en onPause. Cualquier fallo de video => se queda el color de fondo.
 */
internal class BackgroundLayer(ctx: Context, private val scope: BgScope) :
    FrameLayout(ctx), TextureView.SurfaceTextureListener {

    private companion object {
        const val DIM_ALPHA = 0.6f
    }

    private val img = ImageView(ctx)
    private val dim = View(ctx)
    private var tv: TextureView? = null
    private var tex: SurfaceTexture? = null
    private var surface: Surface? = null
    private var mp: MediaPlayer? = null
    private var vw = 0
    private var vh = 0
    private var shown: String? = null

    init {
        setBackgroundColor(ctx.getColor(R.color.bg))
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.visibility = GONE
        addView(img, LayoutParams(-1, -1))
        dim.setBackgroundColor(Color.argb((255 * DIM_ALPHA).toInt(), 0, 0, 0))
        dim.visibility = GONE
        addView(dim, LayoutParams(-1, -1))
    }

    fun refresh() {
        val path = BackgroundStore.path(context, scope)
        if (path == null) {
            clearAll()
            return
        }
        dim.visibility = VISIBLE
        val mode = BackgroundStore.scale(context, scope)
        if (BackgroundStore.isVideo(context, scope)) {
            img.visibility = GONE
            img.setImageDrawable(null)
            ensureTexture()
            applyTransform()
            if (shown != path || mp == null) {
                shown = path
                startVideo()
            }
        } else {
            removeTexture()
            img.visibility = VISIBLE
            img.scaleType = when (mode) {
                "FillBounds" -> ImageView.ScaleType.FIT_XY
                "Fit" -> ImageView.ScaleType.FIT_CENTER
                else -> ImageView.ScaleType.CENTER_CROP
            }
            if (shown != path) {
                shown = path
                img.setImageBitmap(ImageUtil.decodeFile(path, 1920))
            }
        }
    }

    fun pause() {
        stopVideo()
    }

    private fun clearAll() {
        removeTexture()
        img.setImageDrawable(null)
        img.visibility = GONE
        dim.visibility = GONE
        shown = null
    }

    private fun ensureTexture() {
        if (tv != null) return
        val t = TextureView(context)
        t.surfaceTextureListener = this
        addView(t, 1, LayoutParams(-1, -1))
        tv = t
        if (t.isAvailable) tex = t.surfaceTexture
    }

    private fun removeTexture() {
        stopVideo()
        val t = tv ?: return
        t.surfaceTextureListener = null
        removeView(t)
        tv = null
        tex = null
    }

    private fun startVideo() {
        val st = tex ?: return
        val path = BackgroundStore.path(context, scope) ?: return
        stopVideo()
        try {
            val m = MediaPlayer()
            mp = m
            val s = Surface(st)
            surface = s
            m.setSurface(s)
            m.setDataSource(path)
            m.isLooping = true
            m.setVolume(0f, 0f)
            m.setOnVideoSizeChangedListener { _, w, h ->
                vw = w
                vh = h
                applyTransform()
            }
            m.setOnPreparedListener { it.start() }
            m.setOnErrorListener { _, _, _ ->
                stopVideo()
                true
            }
            m.prepareAsync()
        } catch (e: Exception) {
            stopVideo()
        }
    }

    private fun stopVideo() {
        val m = mp
        mp = null
        try {
            m?.setOnPreparedListener(null)
            m?.setOnErrorListener(null)
            m?.release()
        } catch (e: Exception) {
            // Ya liberado.
        }
        try {
            surface?.release()
        } catch (e: Exception) {
            // Ya liberada.
        }
        surface = null
    }

    private fun applyTransform() {
        val t = tv ?: return
        val w = t.width.toFloat()
        val h = t.height.toFloat()
        if (w == 0f || h == 0f || vw == 0 || vh == 0) return
        val mode = BackgroundStore.scale(context, scope)
        val m = Matrix()
        if (mode != "FillBounds") {
            val s = if (mode == "Fit") minOf(w / vw, h / vh) else maxOf(w / vw, h / vh)
            m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f)
        }
        t.setTransform(m)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        tex = st
        if (BackgroundStore.path(context, scope) != null && BackgroundStore.isVideo(context, scope)) startVideo()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        applyTransform()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopVideo()
        tex = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onDetachedFromWindow() {
        stopVideo()
        super.onDetachedFromWindow()
    }
}
