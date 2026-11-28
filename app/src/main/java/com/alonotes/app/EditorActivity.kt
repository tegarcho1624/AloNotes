package com.alonotes.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.UUID

/**
 * Editor de una nota. Dos vistas de hojas (ojo de la barra): una hoja centrada y ajustada a la
 * pantalla, o continua (solo lectura). Zoom y rotación son solo visuales (ver PageViewport).
 * Lápiz (lápiz/pluma/pincel) y borrador dibujan y borran sobre la hoja; los trazos se guardan por nota.
 * No hay botón de lápiz flotante: el lápiz del borde es el panel edge de la tablet.
 */
class EditorActivity : Activity() {
    private companion object {
        const val REQ_IMAGE = 41
        const val REQ_PAGES = 42
        const val REQ_STICKER = 43
        const val REQ_BACKGROUND = 44
        val DIMS = arrayOf("A5", "A4", "A3", "A2")
        val DIM_LABELS = arrayOf("A5 (148 x 210 mm)", "A4 (210 x 297 mm)", "A3 (297 x 420 mm)", "A2 (420 x 594 mm)")
        val MM = arrayOf(
            floatArrayOf(148f, 210f), floatArrayOf(210f, 297f),
            floatArrayOf(297f, 420f), floatArrayOf(420f, 594f)
        )
        val PAGE_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFF5EBD7.toInt(), 0xFFFFF6C9.toInt(), 0xFFDDF3E4.toInt(),
            0xFFDCEBFA.toInt(), 0xFFFADCE6.toInt(), 0xFFEDEDED.toInt(), 0xFF1A1A1A.toInt()
        )
    }

    private var docId = ""
    private lateinit var viewport: PageViewport
    private lateinit var emptyView: LinearLayout
    private lateinit var layers: LayersView
    private lateinit var layersPanel: LayersPanel
    private val history = PageHistory(30) // deshacer/rehacer de la hoja actual (máx. 30 pasos)
    private lateinit var counter: TextView
    private lateinit var bar: LinearLayout
    private lateinit var bgLayer: BackgroundLayer
    private var stickerMenu: StickerMenu? = null
    private var hasSel = false // ¿hay algo seleccionado en la hoja? (lo avisa el visor)
    private var pendingPage = -1 // hoja a mostrar al recargar el editor tras el gestor de páginas
    private var tool = 0 // 0 ninguna, 1 lápiz, 2 borrador, 3 marca texto, 4 seleccionar
    private lateinit var strokes: DocStrokes
    private var total = 0
    private var themeVer = 0

    private fun dp(v: Number) = UiKit.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemeUtils.applyTo(this)
        val id = intent.getStringExtra("id")
        if (id == null || NoteStore.get(this, id) == null) {
            finish()
            return
        }
        docId = id
        themeVer = ThemeUtils.version()

        val root = FrameLayout(this)
        bgLayer = BackgroundLayer(this, BgScope.EDITOR)
        root.addView(bgLayer, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Barra de herramientas (se reconstruye al cambiar de vista, lápiz u opciones)
        bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), 0, dp(6), 0)
        counter = UiKit.text(this, "0 / 0", 17, getColor(R.color.ink))
        counter.gravity = Gravity.CENTER

        // Área de hojas
        val area = FrameLayout(this)
        viewport = PageViewport(this)
        viewport.restore(b?.getBoolean("cont") ?: false, b?.getInt("page") ?: intent.getIntExtra("page", 0))
        viewport.onPage = {
            history.clear() // al cambiar de hoja el historial empieza de cero
            updateCounter()
        }
        viewport.onRecord = { history.record(it) }
        // Gotero del selector de color: el menú de color le pide al visor el color de un punto de la hoja.
        PenOptions.eyedropperStart = { onColor, onCancel -> viewport.startEyedropper(onColor, onCancel) }
        PenOptions.eyedropperStop = { viewport.stopEyedropper() }
        viewport.onSelectionChanged = {
            hasSel = it
            if (::bar.isInitialized) buildBar() // los íconos de selección solo se ven si hay algo seleccionado
        }
        viewport.onTextStyle = { if (::bar.isInitialized) buildBar() } // se abrió un texto existente: la barra muestra su estilo
        viewport.onEditLink = { showLinkMenu(it) }
        viewport.onOpenLink = { openLink(it) }
        history.onChange = { if (::bar.isInitialized) buildBar() } // activa o apaga deshacer/rehacer
        strokes = StrokeStore.load(this, docId)
        LayerRepository.applyTo(this, docId, strokes)
        cleanOrphanImages()
        viewport.strokes = strokes
        viewport.onChanged = {
            persistStrokes()
            persistLayers()
        }
        // Mover, redimensionar o girar una imagen: solo cambian los datos de las capas.
        viewport.onItemsChanged = { persistLayers() }
        area.addView(viewport, FrameLayout.LayoutParams(-1, -1))

        emptyView = LinearLayout(this)
        emptyView.orientation = LinearLayout.VERTICAL
        emptyView.gravity = Gravity.CENTER_HORIZONTAL
        emptyView.addView(UiKit.text(this, "Sin páginas todavía", 16, getColor(R.color.mut)))
        val bt = UiKit.btn(this, "Crear páginas", ThemeUtils.accent(this))
        bt.setOnClickListener { createPages() }
        val bp = LinearLayout.LayoutParams(-2, -2)
        bp.topMargin = dp(16)
        emptyView.addView(bt, bp)
        area.addView(emptyView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Capas: ícono con número (por ahora solo hay una capa)
        layers = LayersView(this)
        layers.setOnClickListener {
            layersPanel.open()
            updateLayersUi()
        }
        val lp = FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END)
        lp.setMargins(0, 0, dp(22), dp(22))
        area.addView(layers, lp)

        // Panel flotante de capas (arrastrable, expandido o minimizado)
        layersPanel = LayersPanel(this)
        layersPanel.onClose = { updateLayersUi() }
        layersPanel.onRecord = { history.record(it) }
        layersPanel.onVisibilityChanged = {
            viewport.refreshPage()
            persistLayers()
        }
        layersPanel.docId = docId
        layersPanel.onMoveMode = { viewport.layerMoveMode = it }
        layersPanel.onPickBackground = { editOnly { openBackgroundPicker() } }
        layersPanel.onRemoveBackground = { removeBackground() }
        layersPanel.onOpacityLive = { viewport.refreshPage() } // la opacidad de la capa se ve mientras se mueve la barra
        layersPanel.onLayersChanged = { dirty ->
            layers.count = layersPanel.layerCount
            if (dirty) {
                viewport.refreshPage()
                persistStrokes()
            }
            persistLayers()
        }
        if (b != null) layersPanel.restore(b)
        area.addView(layersPanel, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.TOP or Gravity.START))
        area.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layersPanel.refit() }

        buildBar()
        val hsv = HorizontalScrollView(this)
        hsv.isFillViewport = true
        hsv.isHorizontalScrollBarEnabled = false
        hsv.setBackgroundColor(getColor(R.color.sf))
        hsv.addView(bar, ViewGroup.LayoutParams(-1, -1))
        col.addView(hsv, LinearLayout.LayoutParams(-1, dp(52)))
        val line = View(this)
        line.setBackgroundColor(getColor(R.color.ln))
        col.addView(line, LinearLayout.LayoutParams(-1, dp(1)))
        col.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        renderPages()
        if (b == null && intent.getBooleanExtra("new", false) && total == 0) createPages()
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        if (::viewport.isInitialized) {
            o.putBoolean("cont", viewport.continuous)
            o.putInt("page", if (pendingPage >= 0) pendingPage else viewport.index)
        }
        if (::layersPanel.isInitialized) layersPanel.save(o)
    }

    override fun onResume() {
        super.onResume()
        if (themeVer != ThemeUtils.version()) {
            recreate()
            return
        }
        bgLayer.refresh()
    }

    override fun onPause() {
        viewport.finishText() // un texto a medio escribir se guarda
        if (::bgLayer.isInitialized) bgLayer.pause()
        super.onPause()
    }

    // ---------- Barra ----------

    private fun buildBar() {
        val ink = ThemeUtils.accentHi(this)
        val mut = getColor(R.color.mut)
        val cont = viewport.continuous
        bar.removeAllViews()
        bar.addView(tb(Icons.BACK, ink) { finish() }, UiKit.box(this, 44))
        bar.addView(tb(Icons.GRID, ink) { openPages() }, UiKit.box(this, 44))
        bar.addView(tb(Icons.PLUS, ink) { createPages() }, UiKit.box(this, 44))
        bar.addView(View(this), LinearLayout.LayoutParams(dp(22), 1))
        // Lápiz (1), borrador (2), marca texto (3), seleccionar (4) y formas (5): se resaltan y muestran sus opciones; el resto, próximamente
        val penBtn = tb(PenOptions.typeIcon(PenStore.type(this)), ink) { pickTool(1) }
        if (tool == 1 && !cont) UiKit.ripple(this, penBtn, 12, ThemeUtils.accentBg(this))
        bar.addView(penBtn, UiKit.box(this, 44))
        val markerBtn = tb(Icons.MARKER, ink) { pickTool(3) }
        if (tool == 3 && !cont) UiKit.ripple(this, markerBtn, 12, ThemeUtils.accentBg(this))
        bar.addView(markerBtn, UiKit.box(this, 44))
        val eraserBtn = tb(Icons.ERASER, ink) { pickTool(2) }
        if (tool == 2 && !cont) UiKit.ripple(this, eraserBtn, 12, ThemeUtils.accentBg(this))
        bar.addView(eraserBtn, UiKit.box(this, 44))
        val others = intArrayOf(Icons.TEXT, Icons.SHAPES, Icons.IMAGE, Icons.STAR, Icons.SELECT)
        for (t in others) {
            val btn = when (t) {
                Icons.IMAGE -> tb(t, ink) { editOnly { openGallery() } }
                Icons.SELECT -> tb(t, ink) { pickTool(4) }
                Icons.TEXT -> tb(t, ink) { pickTool(6) }
                Icons.SHAPES -> tb(t, ink) { pickTool(5) }
                Icons.STAR -> tb(t, ink) { editOnly { openStickers() } }
                else -> tb(t, ink) { editOnly { UiKit.soon(this) } }
            }
            if (t == Icons.SELECT && tool == 4 && !cont) UiKit.ripple(this, btn, 12, ThemeUtils.accentBg(this))
            if (t == Icons.SHAPES && tool == 5 && !cont) UiKit.ripple(this, btn, 12, ThemeUtils.accentBg(this))
            if (t == Icons.TEXT && tool == 6 && !cont) UiKit.ripple(this, btn, 12, ThemeUtils.accentBg(this))
            bar.addView(btn, UiKit.box(this, 44))
        }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (tool == 1 && !cont) {
            PenOptions.build(this, bar) { buildBar() }
        } else if (tool == 2 && !cont) {
            PenOptions.buildEraser(this, bar) { buildBar() }
        } else if (tool == 3 && !cont) {
            PenOptions.buildHighlighter(this, bar) { buildBar() }
        } else if (tool == 6 && !cont) {
            // Texto: tamaño, fuente, itálica, negrita, subrayado y color
            TextOptions.build(this, bar, viewport) { buildBar() }
        } else if (tool == 5 && !cont) {
            // Formas: línea, cuadrado y círculo; a la derecha, los mismos grosores y colores del lápiz
            PenOptions.buildShapes(this, bar, viewport.shape, { viewport.shape = it }) { buildBar() }
        } else if (tool == 4 && !cont && hasSel) {
            // Herramienta Seleccionar CON algo seleccionado: en vez de las flechas de página, los 7 íconos de selección (en este orden)
            val defs = listOf(
                Pair(Icons.SELECT, SelAct.ALL), Pair(Icons.STAR, SelAct.STICKER), Pair(Icons.COPY, SelAct.COPY),
                Pair(Icons.PASTE, SelAct.PASTE), Pair(Icons.BRING_FRONT, SelAct.FRONT), Pair(Icons.SEND_BACK, SelAct.BACK),
                Pair(Icons.TRASH, SelAct.DELETE)
            )
            for ((icon, act) in defs) bar.addView(tb(icon, ink) { onSelAction(act) }, UiKit.box(this, 44))
        } else {
            // Flechas de página: anterior / siguiente
            bar.addView(tb(Icons.UNDO, ink) { viewport.prevPage() }, UiKit.box(this, 44))
            bar.addView(counter, LinearLayout.LayoutParams(dp(90), -2))
            bar.addView(tb(Icons.REDO, ink) { viewport.nextPage() }, UiKit.box(this, 44))
        }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // Ojo: alterna entre una hoja y vista continua
        val eye = UiKit.iconBtn(this, if (cont) Icons.EYE_ALT else Icons.EYE, getColor(R.color.ink), 44, 28) {
            toggleView()
        }
        bar.addView(eye, UiKit.box(this, 44))
        // Antes aquí estaba el candado (sin función). Se deja un hueco invisible del mismo ancho para que el ojo y el resto de la barra
        // queden exactamente donde estaban (el espaciador de la izquierda absorbería ese ancho y movería todo lo demás).
        bar.addView(View(this), UiKit.box(this, 44))
        // Deshacer / rehacer: en gris cuando no hay nada que deshacer o rehacer (y en la vista continua)
        val canU = history.canUndo && !cont
        val canR = history.canRedo && !cont
        bar.addView(tb(Icons.ROT_L, if (canU) ink else mut) { if (canU) undo() }, UiKit.box(this, 44))
        bar.addView(tb(Icons.ROT_R, if (canR) ink else mut) { if (canR) redo() }, UiKit.box(this, 44))
        val div = View(this)
        div.setBackgroundColor(getColor(R.color.ln2))
        val dv = LinearLayout.LayoutParams(dp(1), dp(24))
        dv.setMargins(dp(8), 0, dp(8), 0)
        bar.addView(div, dv)
        bar.addView(tb(Icons.GEAR, ink) { UiKit.soon(this) }, UiKit.box(this, 44))
    }

    // ---------- Stickers ----------

    private fun openStickers() {
        val m = StickerMenu(this, { insertSticker(it) }, { pickStickerImage() })
        stickerMenu = m
        m.show()
    }

    /** "+" del menú: abre la galería para elegir la imagen del nuevo sticker. */
    private fun pickStickerImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_STICKER)
    }

    /** Copia reducida de la imagen elegida (máx. 1024 px) a filesDir/stickers/ y la agrega a la categoría actual del menú. */
    private fun addStickerFromGallery(uri: Uri) {
        toast("Agregando sticker…")
        Thread {
            val dest = File(StickerStore.dir(this), UUID.randomUUID().toString())
            val f = ImageUtil.saveFromUri(this, uri, dest, 1024, 90, true)
            if (f != null) StickerStore.add(this, StickerStore.currentCategory(this), f)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (f == null) {
                    toast("No se pudo abrir la imagen")
                } else {
                    stickerMenu?.refresh()
                    toast("Sticker agregado")
                }
            }
        }.start()
    }

    /** Inserta el sticker en el centro de la hoja como una imagen normal (se puede seleccionar, girar, recortar...). */
    private fun insertSticker(s: Sticker) {
        if (total == 0) {
            toast("Primero crea una página")
            return
        }
        val pl = strokes.page(viewport.index)
        if (!pl.layers[pl.selected].isVisible) {
            toast("La capa está oculta")
            return
        }
        if (pl.layers[pl.selected].isLocked) {
            toast("La capa está bloqueada")
            return
        }
        val src = StickerStore.fileOf(this, s)
        Thread {
            val id = UUID.randomUUID().toString()
            val ext = src.extension
            val dst = File(File(filesDir, "images/$docId"), if (ext.isEmpty()) id else "$id.$ext")
            val ok = try {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, true)
                true
            } catch (e: Exception) {
                false
            }
            val bm = if (ok) ImageUtil.decodeFile(dst.path, 2048) else null
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bm == null) toast("No se pudo abrir el sticker") else insertImage(id, dst, bm, 0.4f)
            }
        }.start()
    }

    /**
     * "Guardar como sticker" (botón de la barra de selección): cada imagen seleccionada se dibuja tal como se ve (su recorte,
     * su giro y su espejo; sin la opacidad) en un PNG con fondo transparente y se guarda en la categoría actual del menú.
     * Los trazos seleccionados se dibujan juntos en un solo PNG transparente.
     */
    private fun saveAsSticker() {
        val sel = selectedImages()
        // Los trazos seleccionados se rasterizan JUNTOS en un solo sticker (en el orden en que se dibujan). Se copian sin su camino
        // calculado ni su opacidad: así el hilo de fondo no toca nada que la hoja esté usando.
        // Cada texto seleccionado es un sticker aparte (como las imágenes): se dibuja tal como se ve (giro y espejo; sin opacidad).
        val texts = viewport.currentSelection().filterIsInstance<TextItem>().map { it.plainCopy() }
        val picked = selectedStrokes().toSet()
        val drawn = ArrayList<Stroke>()
        if (picked.isNotEmpty()) {
            for (ly in strokes.page(viewport.index).layers) {
                for (s in ly.canvas) if (picked.contains(s)) drawn.add(Stroke(s.type, s.color, s.widthMm, s.pts, 1f, false))
            }
        }
        if (sel.isEmpty() && drawn.isEmpty() && texts.isEmpty()) {
            toast("Selecciona un objeto primero")
            return
        }
        val dir = StickerStore.dir(this)
        val cat = StickerStore.currentCategory(this)
        Thread {
            var saved = 0
            for (im in sel) {
                val bm = im.bitmap ?: continue
                val out = renderForSticker(im, bm) ?: continue
                val f = ImageFiles.write(out, dir, UUID.randomUUID().toString()) ?: continue
                StickerStore.add(this, cat, f)
                saved++
            }
            for (t in texts) {
                val out = renderTextForSticker(t) ?: continue
                val f = ImageFiles.write(out, dir, UUID.randomUUID().toString()) ?: continue
                StickerStore.add(this, cat, f)
                saved++
            }
            if (drawn.isNotEmpty()) {
                val out = renderStrokesForSticker(drawn)
                val f = if (out != null) ImageFiles.write(out, dir, UUID.randomUUID().toString()) else null
                if (f != null) {
                    StickerStore.add(this, cat, f)
                    saved++
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (saved == 0) {
                    toast("No se pudo guardar el sticker")
                } else {
                    stickerMenu?.refresh()
                    toast("Guardado en stickers")
                }
            }
        }.start()
    }

    /**
     * Rasteriza trazos (un PNG transparente del tamaño de su caja más un margen del grosor). Densidad: la que quepa en 1024 px,
     * entre 1 y 12 píxeles por mm.
     */
    private fun renderStrokesForSticker(list: List<Stroke>): android.graphics.Bitmap? {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        var pad = 0f
        for (s in list) {
            val bb = s.bbox()
            if (bb[0] < x0) x0 = bb[0]
            if (bb[1] < y0) y0 = bb[1]
            if (bb[2] > x1) x1 = bb[2]
            if (bb[3] > y1) y1 = bb[3]
            if (s.widthMm > pad) pad = s.widthMm
        }
        pad += 0.5f
        val wMm = x1 - x0 + 2f * pad
        val hMm = y1 - y0 + 2f * pad
        if (list.isEmpty() || wMm <= 0f || hMm <= 0f) return null
        val k = (1024f / maxOf(wMm, hMm)).coerceIn(1f, 12f)
        val pw = Math.ceil((wMm * k).toDouble()).toInt().coerceAtLeast(1)
        val ph = Math.ceil((hMm * k).toDouble()).toInt().coerceAtLeast(1)
        return try {
            val out = android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.translate(-(x0 - pad) * k, -(y0 - pad) * k)
            val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            for (s in list) StrokeRender.draw(c, s, k, pen)
            out
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Rasteriza un texto (una copia suya, sin opacidad): un PNG transparente del tamaño de su caja girada más un margen, con la misma
     * densidad que los trazos (la que quepa en 1024 px, entre 1 y 12 píxeles por mm).
     */
    private fun renderTextForSticker(t: TextItem): android.graphics.Bitmap? {
        val b = t.bounds
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (k in 0 until 4) {
            val p = Xf.toPage(
                b.centerX(), b.centerY(), t.rotation,
                (if (k % 2 == 0) -1 else 1) * b.width() / 2f, (if (k < 2) -1 else 1) * b.height() / 2f
            )
            if (p[0] < x0) x0 = p[0]
            if (p[0] > x1) x1 = p[0]
            if (p[1] < y0) y0 = p[1]
            if (p[1] > y1) y1 = p[1]
        }
        val pad = 0.5f
        val wMm = x1 - x0 + 2f * pad
        val hMm = y1 - y0 + 2f * pad
        if (t.text.isEmpty() || wMm <= 0f || hMm <= 0f) return null
        val k = (1024f / maxOf(wMm, hMm)).coerceIn(1f, 12f)
        val pw = Math.ceil((wMm * k).toDouble()).toInt().coerceAtLeast(1)
        val ph = Math.ceil((hMm * k).toDouble()).toInt().coerceAtLeast(1)
        return try {
            val out = android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.translate(-(x0 - pad) * k, -(y0 - pad) * k)
            t.draw(c, k) { }
            out
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Dibuja [im] en un bitmap del tamaño de su caja girada: el rectángulo (con su giro θ) ocupa W·|cosθ| + H·|sinθ| de ancho y
     * W·|sinθ| + H·|cosθ| de alto. Se usa la misma densidad (píxeles por mm) que la imagen y un máximo de 2048 px.
     */
    private fun renderForSticker(im: ImageItem, bm: android.graphics.Bitmap): android.graphics.Bitmap? {
        val w = im.bounds.width()
        val h = im.bounds.height()
        if (w <= 0f || h <= 0f) return null
        val th = Math.toRadians(im.rotation.toDouble())
        val cs = Math.abs(Math.cos(th)).toFloat()
        val sn = Math.abs(Math.sin(th)).toFloat()
        val bwMm = w * cs + h * sn
        val bhMm = w * sn + h * cs
        var k = bm.width / w
        val big = maxOf(bwMm, bhMm) * k
        if (big > 2048f) k *= 2048f / big
        val pw = Math.ceil((bwMm * k).toDouble()).toInt().coerceAtLeast(1)
        val ph = Math.ceil((bhMm * k).toDouble()).toInt().coerceAtLeast(1)
        return try {
            val out = android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
            val tmp = ImageItem(
                "tmp", im.file, RectF(bwMm / 2f - w / 2f, bhMm / 2f - h / 2f, bwMm / 2f + w / 2f, bhMm / 2f + h / 2f),
                im.rotation, w, im.isFlippedH, im.isFlippedV, 1f
            )
            tmp.drawBitmap(android.graphics.Canvas(out), k, bm)
            out
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- Barra de selección: copiar, pegar, orden y eliminar ----------

    private fun onSelAction(a: Int) {
        if (total == 0) return
        if (viewport.inCropMode) {
            toast("Termina o cancela el recorte primero")
            return
        }
        when (a) {
            SelAct.ALL -> if (viewport.selectAll() == 0) toast("No hay objetos que seleccionar en esta capa (vacía o bloqueada)")
            SelAct.STICKER -> saveAsSticker()
            SelAct.COPY -> copySelection()
            SelAct.PASTE -> pasteObjects()
            SelAct.FRONT -> reorderSelection(true)
            SelAct.BACK -> reorderSelection(false)
            SelAct.DELETE -> deleteSelection()
        }
    }

    private fun selectedImages(): List<ImageItem> = viewport.currentSelection().filterIsInstance<ImageItem>()

    /** Los trazos (los de la capa, no los elementos de selección) que están seleccionados. */
    private fun selectedStrokes(): List<Stroke> = viewport.currentSelection().filterIsInstance<StrokeItem>().map { it.src }

    private fun copySelection() {
        val sel = viewport.currentSelection()
        if (sel.isEmpty()) {
            toast("Selecciona uno o varios objetos")
            return
        }
        ObjectClipboard.copy(this, sel)
        toast(if (sel.size == 1) "1 objeto copiado" else sel.size.toString() + " objetos copiados")
    }

    /** Pega en la capa seleccionada, al centro de la hoja (cada pegado se corre un poco), y deja lo pegado seleccionado. */
    private fun pasteObjects() {
        if (ObjectClipboard.isEmpty) {
            toast("No hay objetos copiados")
            return
        }
        val sp = viewport.pageSpec() ?: return
        val pl = strokes.page(viewport.index)
        val layer = pl.layers[pl.selected]
        if (!layer.isVisible) {
            toast("La capa está oculta")
            return
        }
        if (layer.isLocked) {
            toast("La capa está bloqueada")
            return
        }
        val made = ObjectClipboard.paste(this, docId, sp.wMm / 2f, sp.hMm / 2f)
        if (made.isEmpty) {
            toast("No se pudo pegar")
            return
        }
        val pre = pl.snapshot()
        layer.items.addAll(made.images)
        layer.texts.addAll(made.texts)
        layer.canvas.addAll(made.strokes)
        history.record(pre)
        val now = ArrayList<SelectableItem>()
        now.addAll(made.images)
        now.addAll(made.texts)
        for (s in made.strokes) now.add(StrokeItem(s))
        viewport.setSelection(now)
        viewport.refreshPage()
        persistStrokes()
        persistLayers()
        toast(if (made.size == 1) "1 objeto pegado" else made.size.toString() + " objetos pegados")
    }

    /** Lleva lo seleccionado al final de la lista de su capa (al frente) o al inicio (al fondo); el orden de la lista es el orden de dibujo. */
    private fun reorderSelection(front: Boolean) {
        val sel = selectedImages()
        val selStrokes = selectedStrokes()
        val selTexts = viewport.currentSelection().filterIsInstance<TextItem>()
        if (sel.isEmpty() && selStrokes.isEmpty() && selTexts.isEmpty()) {
            toast("Selecciona uno o varios objetos")
            return
        }
        val pl = strokes.page(viewport.index)
        val pre = pl.snapshot()
        val set = HashSet<ImageItem>(sel)
        val strokeSet = HashSet<Stroke>(selStrokes)
        val textSet = HashSet<TextItem>(selTexts)
        var changed = false
        var strokesChanged = false
        for (ly in pl.layers) {
            val mine = ly.items.filter { set.contains(it) }
            if (mine.isNotEmpty()) {
                val before = ArrayList(ly.items)
                ly.items.removeAll(mine.toSet())
                if (front) ly.items.addAll(mine) else ly.items.addAll(0, mine)
                if (before != ly.items) changed = true
            }
            // Los textos se reordenan entre los textos de su capa (se dibujan encima de las imágenes y debajo de los trazos).
            val mineT = ly.texts.filter { textSet.contains(it) }
            if (mineT.isNotEmpty()) {
                val beforeT = ArrayList(ly.texts)
                ly.texts.removeAll(mineT.toSet())
                if (front) ly.texts.addAll(mineT) else ly.texts.addAll(0, mineT)
                if (beforeT != ly.texts) changed = true
            }
            // Los trazos se reordenan entre los trazos de su capa (las imágenes de la capa siempre se dibujan debajo de ellos).
            val mineS = ly.canvas.filter { strokeSet.contains(it) }
            if (mineS.isNotEmpty()) {
                val beforeS = ArrayList(ly.canvas)
                ly.canvas.removeAll(mineS.toSet())
                if (front) ly.canvas.addAll(mineS) else ly.canvas.addAll(0, mineS)
                if (beforeS != ly.canvas) {
                    changed = true
                    strokesChanged = true
                }
            }
        }
        if (!changed) {
            toast(if (front) "Ya está al frente" else "Ya está al fondo")
            return
        }
        history.record(pre)
        viewport.refreshPage()
        if (strokesChanged) persistStrokes() // el orden de los trazos va en el archivo de trazos
        persistLayers()
    }

    private fun deleteSelection() {
        val sel = selectedImages()
        val selStrokes = selectedStrokes()
        val selTexts = viewport.currentSelection().filterIsInstance<TextItem>()
        if (sel.isEmpty() && selStrokes.isEmpty() && selTexts.isEmpty()) {
            toast("Selecciona uno o varios objetos")
            return
        }
        val pl = strokes.page(viewport.index)
        val pre = pl.snapshot()
        val set = HashSet<ImageItem>(sel)
        val strokeSet = HashSet<Stroke>(selStrokes)
        val textSet = HashSet<TextItem>(selTexts)
        for (ly in pl.layers) {
            ly.items.removeAll(set)
            if (strokeSet.isNotEmpty()) ly.canvas.removeAll(strokeSet)
            if (textSet.isNotEmpty()) ly.texts.removeAll(textSet)
        }
        history.record(pre)
        viewport.setSelection(emptyList())
        viewport.refreshPage()
        if (strokeSet.isNotEmpty()) persistStrokes()
        persistLayers() // también cambia cuántos trazos tiene cada capa
        val n = sel.size + selStrokes.size + selTexts.size
        toast(if (n == 1) "1 objeto eliminado" else n.toString() + " objetos eliminados")
    }

    // ---------- Enlaces de imagen ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Texto de a dónde apunta el enlace de la imagen (para mostrarlo en el menú). */
    private fun linkLabel(item: Linkable): String {
        if (item.linkType == ImageItem.LINK_WEB) return item.linkData
        val k = item.linkData.lastIndexOf('|')
        if (k <= 0) return "?"
        val note = NoteStore.get(this, item.linkData.substring(0, k)) ?: return "nota eliminada"
        val page = (item.linkData.substring(k + 1).toIntOrNull() ?: 0) + 1
        return note.name + " · Page " + page
    }

    /** Menú de enlace: Enlace Web, Enlace Interno y (si ya tiene) Quitar enlace. */
    private fun showLinkMenu(item: Linkable) {
        val ink = getColor(R.color.ink)
        val l = UiKit.vbox(this, 12)
        val t = UiKit.text(
            this,
            when (item) {
                is StrokeItem -> "Enlace del trazo"
                is TextItem -> "Enlace del texto"
                else -> "Enlace de la imagen"
            },
            17, ink
        )
        t.setPadding(dp(14), dp(8), dp(14), dp(4))
        l.addView(t)
        if (item.linkType != ImageItem.LINK_NONE) {
            val cur = UiKit.text(this, "Actual: " + linkLabel(item), 13, getColor(R.color.mut))
            cur.setSingleLine(true)
            cur.ellipsize = android.text.TextUtils.TruncateAt.END
            cur.setPadding(dp(14), 0, dp(14), dp(8))
            l.addView(cur)
        }
        val ref = DlgRef()
        l.addView(UiKit.sheetRow(this, Icons.LINK, "Enlace Web", ink, ref) { askWebLink(item) })
        l.addView(UiKit.sheetRow(this, Icons.NOTES, "Enlace Interno", ink, ref) { pickInternalLink(item) })
        if (item.linkType != ImageItem.LINK_NONE) {
            l.addView(UiKit.sheetRow(this, Icons.CLOSE, "Quitar enlace", getColor(R.color.danger), ref) {
                setLink(item, ImageItem.LINK_NONE, "")
            })
        }
        ref.d = UiKit.show(this, l, true)
    }

    /** Guarda el enlace en la imagen o el trazo (un paso del historial), en las capas y, si es un trazo, en el archivo de trazos. */
    private fun setLink(item: Linkable, type: Int, data: String) {
        if (total == 0) return
        val pre = strokes.page(viewport.index).snapshot()
        if (item is StrokeItem) {
            // Un trazo no guarda el enlace en las capas: el trazo con el enlace nuevo reemplaza al anterior y va en el archivo de trazos.
            viewport.setStrokeLink(item, type, data)
            persistStrokes()
        } else {
            item.linkType = type
            item.linkData = data
        }
        history.record(pre)
        persistLayers()
        viewport.refreshPage()
        toast(if (type == ImageItem.LINK_NONE) "Enlace quitado" else "Enlace guardado")
    }

    /** Solo http y https (con o sin el prefijo escrito). Devuelve la URL limpia o null si no es válida. */
    private fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty() || s == "https://") return null
        if (!s.contains("://")) s = "https://$s"
        val u = try {
            Uri.parse(s)
        } catch (e: Exception) {
            return null
        }
        val sch = u.scheme?.lowercase()
        if ((sch != "http" && sch != "https") || u.host.isNullOrEmpty()) return null
        return s
    }

    private fun askWebLink(item: Linkable) {
        val start = if (item.linkType == ImageItem.LINK_WEB) item.linkData else "https://"
        UiKit.askName(this, "Enlace web", start) { raw ->
            val url = normalizeUrl(raw)
            if (url == null) toast("Escribe una dirección http o https válida") else setLink(item, ImageItem.LINK_WEB, url)
        }
    }

    /** Selector de destino: primero las hojas de esta nota y luego las otras notas (de una nota con varias hojas se elige la hoja). */
    private fun pickInternalLink(item: Linkable) {
        val here = ArrayList<Pair<String, () -> Unit>>()
        for (i in 0 until total) {
            here.add(Pair("Page " + (i + 1), { setLink(item, ImageItem.LINK_INTERNAL, "$docId|$i") }))
        }
        val others = ArrayList<Pair<String, () -> Unit>>()
        for (n in NoteStore.notesForLinks(this)) {
            if (n.id == docId) continue
            others.add(Pair(n.name.ifEmpty { "Nota sin título" }, { pickPageOf(item, n) }))
        }
        showPicker("Enlace interno", listOf(Pair("Esta nota", here), Pair("Otras notas", others)))
    }

    private fun pickPageOf(item: Linkable, n: NoteStore.Item) {
        val count = n.pages.length()
        if (count <= 1) {
            setLink(item, ImageItem.LINK_INTERNAL, n.id + "|0")
            return
        }
        val rows = ArrayList<Pair<String, () -> Unit>>()
        for (i in 0 until count) rows.add(Pair("Page " + (i + 1), { setLink(item, ImageItem.LINK_INTERNAL, n.id + "|" + i) }))
        showPicker(n.name, listOf(Pair("Hojas", rows)))
    }

    /** Lista con scroll de opciones por secciones (título de sección y filas que ejecutan su acción al tocarlas). */
    private fun showPicker(title: String, sections: List<Pair<String, List<Pair<String, () -> Unit>>>>) {
        val ink = getColor(R.color.ink)
        val mut = getColor(R.color.mut)
        val box = UiKit.vbox(this, 12)
        val tt = UiKit.text(this, title, 17, ink)
        tt.setPadding(dp(14), dp(8), dp(14), dp(8))
        box.addView(tt)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val ref = DlgRef()
        var rows = 0
        for ((name, items) in sections) {
            if (items.isEmpty()) continue
            val h = UiKit.text(this, name, 13, mut)
            h.setPadding(dp(14), dp(10), dp(14), dp(4))
            list.addView(h)
            rows++
            for ((label, act) in items) {
                list.addView(UiKit.sheetRow(this, Icons.NOTES, label, ink, ref) { act() })
                rows++
            }
        }
        if (rows == 0) {
            toast("No hay destinos")
            return
        }
        val sv = android.widget.ScrollView(this)
        sv.addView(list)
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        box.addView(sv, LinearLayout.LayoutParams(-1, minOf(maxH, rows * dp(52))))
        ref.d = UiKit.show(this, box, true)
    }

    /** Abre el enlace de una imagen o un trazo tocado: web en el navegador; interno, la hoja o la nota de destino. */
    private fun openLink(item: Linkable) {
        when (item.linkType) {
            ImageItem.LINK_WEB -> {
                val url = normalizeUrl(item.linkData)
                if (url == null) {
                    toast("El enlace web no es válido")
                    return
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    toast("No se pudo abrir el enlace")
                }
            }
            ImageItem.LINK_INTERNAL -> openInternal(item.linkData)
        }
    }

    private fun openInternal(data: String) {
        val k = data.lastIndexOf('|')
        if (k <= 0) return
        val id = data.substring(0, k)
        val page = data.substring(k + 1).toIntOrNull() ?: 0
        if (id == docId) {
            if (page in 0 until total) viewport.goToPage(page) else toast("Esa hoja ya no existe")
            return
        }
        if (NoteStore.get(this, id) == null) {
            toast("La nota de destino ya no existe")
            return
        }
        val i = Intent(this, EditorActivity::class.java)
        i.putExtra("id", id)
        i.putExtra("page", page)
        startActivity(i)
    }

    // ---------- Deshacer y rehacer ----------

    override fun onDestroy() {
        PenOptions.eyedropperStart = null // para no retener esta pantalla
        PenOptions.eyedropperStop = null
        super.onDestroy()
    }

    private fun undo() = stepHistory(true)

    private fun redo() = stepHistory(false)

    /** Aplica el estado anterior (deshacer) o el siguiente (rehacer) a la hoja actual y guarda. */
    private fun stepHistory(undo: Boolean) {
        if (total == 0 || viewport.continuous) return
        val pl = strokes.page(viewport.index)
        val selIds = viewport.selectedIds()
        viewport.cancelModes()
        val snap = (if (undo) history.undo(pl.snapshot()) else history.redo(pl.snapshot())) ?: return
        pl.restoreSnapshot(snap)
        syncLayers() // panel de capas e ícono
        viewport.reselect(selIds)
        viewport.refreshPage()
        persistStrokes()
        persistLayers()
    }

    /** Borra los archivos de imagen de la nota que ninguna hoja usa (quedan al recortar, para poder deshacer). */
    private fun cleanOrphanImages() {
        val keep = HashSet<String>()
        for (pl in strokes.pages) for (ly in pl.layers) for (im in ly.items) keep.add(im.file.name)
        for (pl in strokes.pages) pl.backgroundImagePath?.let { keep.add(it) } // las imágenes de fondo de las hojas también
        val dir = File(filesDir, "images/$docId")
        Thread {
            try {
                val now = System.currentTimeMillis()
                dir.listFiles()?.forEach { f ->
                    // Solo archivos de más de un minuto: nunca se toca lo que se acaba de guardar.
                    if (!keep.contains(f.name) && now - f.lastModified() > 60000L) f.delete()
                }
            } catch (ignored: Exception) {
                // Se limpiará la próxima vez.
            }
        }.start()
    }

    // ---------- Gestor de páginas ----------

    private fun openPages() {
        if (total == 0) {
            Toast.makeText(this, "Primero crea una página", Toast.LENGTH_SHORT).show()
            return
        }
        // Lo último que se dibujó o se movió ya se está guardando; el gestor espera a que termine antes de leer.
        val i = Intent(this, PageManagerActivity::class.java)
        i.putExtra("id", docId)
        i.putExtra("page", viewport.index)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PAGES)
    }

    /** Vuelve del gestor: si se reordenaron hojas, se recarga el editor desde los archivos; si no, solo se cambia de hoja. */
    private fun onPagesResult(changed: Boolean, page: Int) {
        if (changed) {
            StrokeStore.flush()
            LayerRepository.flush()
            pendingPage = if (page >= 0) page else viewport.index
            recreate()
        } else if (page >= 0) {
            viewport.goToPage(page)
        }
    }

    // ---------- Imágenes ----------

    /** Abre la galería (mismo mecanismo que Ajustes: el proyecto usa Activity simple, sin androidx.activity). */
    private fun openGallery() {
        if (total == 0) {
            Toast.makeText(this, "Primero crea una página", Toast.LENGTH_SHORT).show()
            return
        }
        val pl = strokes.page(viewport.index)
        if (!pl.layers[pl.selected].isVisible) {
            Toast.makeText(this, "La capa está oculta", Toast.LENGTH_SHORT).show()
            return
        }
        if (pl.layers[pl.selected].isLocked) {
            Toast.makeText(this, "La capa está bloqueada", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMAGE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_STICKER) {
            val u = data?.data
            if (res == RESULT_OK && u != null) addStickerFromGallery(u)
            return
        }
        if (req == REQ_BACKGROUND) {
            val u = data?.data
            if (res == RESULT_OK && u != null) setBackgroundFromUri(u)
            return
        }
        if (req == REQ_PAGES) {
            if (res == RESULT_OK && data != null) {
                onPagesResult(data.getBooleanExtra("changed", false), data.getIntExtra("page", -1))
            }
            return
        }
        val uri = data?.data
        if (req != REQ_IMAGE || res != RESULT_OK || uri == null) return
        Toast.makeText(this, "Insertando imagen…", Toast.LENGTH_SHORT).show()
        Thread {
            // Se copia reducida (máx. 2048 px, respetando EXIF) a images/<idNota>/ y se decodifica.
            val id = UUID.randomUUID().toString()
            val f = ImageUtil.saveFromUri(this, uri, File(File(filesDir, "images/$docId"), id), 2048, 90, true)
            val bm = if (f != null) ImageUtil.decodeFile(f.path, 2048) else null
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (f == null || bm == null) {
                    Toast.makeText(this, "No se pudo abrir la imagen", Toast.LENGTH_SHORT).show()
                } else {
                    insertImage(id, f, bm)
                }
            }
        }.start()
    }

    // ---------- Imagen de fondo de la hoja ----------

    private var bgPage = -1 // hoja para la que se abrió la galería

    /** Menú ⋮ del panel de capas (capa 1): abre la galería para elegir la imagen de fondo de la hoja actual. */
    private fun openBackgroundPicker() {
        if (total == 0) {
            toast("Primero crea una página")
            return
        }
        bgPage = viewport.index
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_BACKGROUND)
    }

    /**
     * Guarda la imagen elegida en el almacenamiento de la app (images/<idNota>/, reducida a 2048 px y respetando EXIF) y la deja como fondo
     * de la hoja: la imagen se escala para cubrir toda la hoja sin deformarse (centerCrop, ver PageView.onDraw). Si la hoja ya tenía un
     * fondo, su archivo se borra. Se guarda con las capas (JSON).
     */
    private fun setBackgroundFromUri(uri: Uri) {
        val idx = bgPage
        toast("Insertando imagen de fondo…")
        Thread {
            val id = UUID.randomUUID().toString()
            val f = ImageUtil.saveFromUri(this, uri, File(File(filesDir, "images/$docId"), id), 2048, 90, true)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (f == null || idx < 0 || idx >= total) {
                    toast("No se pudo abrir la imagen")
                } else {
                    val pg = strokes.page(idx)
                    val old = pg.backgroundFile
                    pg.backgroundImagePath = f.name
                    pg.bgDir = f.parentFile
                    if (old != null && old.name != f.name) deleteBackgroundFile(old)
                    persistLayers()
                    viewport.refreshPage()
                    toast("Imagen de fondo insertada")
                }
            }
        }.start()
    }

    /** Quita la imagen de fondo de la hoja actual y borra su archivo. */
    private fun removeBackground() {
        if (total == 0) return
        val pg = strokes.page(viewport.index)
        val old = pg.backgroundFile
        pg.backgroundImagePath = null
        if (old != null) deleteBackgroundFile(old)
        persistLayers()
        viewport.refreshPage()
        toast("Imagen de fondo quitada")
    }

    /** Borra el archivo de un fondo que ya no se usa (y lo saca de la caché) para no acumular basura. */
    private fun deleteBackgroundFile(f: File) {
        PageBackground.forget(f)
        Thread {
            try {
                f.delete()
            } catch (ignored: Exception) {
                // La limpieza al abrir la nota lo borra si queda.
            }
        }.start()
    }

    /** Coloca la imagen en el centro de la hoja actual, dentro de la capa seleccionada, y activa la herramienta Seleccionar. */
    private fun insertImage(id: String, f: File, bm: android.graphics.Bitmap, frac: Float = 0.6f) {
        val sp = viewport.pageSpec()
        if (total == 0 || sp == null) return
        val pl = strokes.page(viewport.index)
        val layer = pl.layers[pl.selected]
        if (layer.isLocked) { // por si se bloqueó la capa mientras se cargaba la imagen
            toast("La capa está bloqueada")
            return
        }
        // Cabe en [frac] de la hoja (60 % las fotos, 40 % los stickers), conservando la proporción.
        val k = minOf(sp.wMm * frac / bm.width, sp.hMm * frac / bm.height)
        val w = bm.width * k
        val h = bm.height * k
        val cx = sp.wMm / 2f
        val cy = sp.hMm / 2f
        val item = ImageItem(id, f, RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), 0f, w)
        item.bitmap = bm
        val pre = pl.snapshot() // la hoja antes de insertar la imagen
        layer.items.add(item)
        history.record(pre)
        tool = 4
        viewport.tool = 4
        viewport.selectItem(item)
        viewport.refreshPage()
        buildBar()
        persistLayers()
    }

    private fun pickTool(t: Int) {
        if (::layersPanel.isInitialized) layersPanel.setMoveMode(false) // elegir una herramienta sale del modo "Mover capa"
        editOnly {
            tool = if (tool == t) 0 else t
            viewport.tool = tool
            buildBar()
        }
    }

    private fun tb(type: Int, color: Int, l: () -> Unit): View =
        UiKit.iconBtn(this, type, color, 44, 24, l)

    /** Las herramientas de edición no se usan en la vista continua (solo visual). */
    private fun editOnly(run: () -> Unit) {
        if (viewport.continuous) {
            Toast.makeText(this, "Vista continua: solo visual", Toast.LENGTH_SHORT).show()
        } else {
            run()
        }
    }

    private fun toggleView() {
        viewport.setMode(!viewport.continuous)
        if (viewport.continuous) {
            tool = 0
            viewport.tool = 0
        }
        updateLayersUi()
        buildBar()
        updateCounter()
    }

    /** Ícono de capas o panel flotante, según el estado (ambos solo en la vista de una hoja). */
    private fun updateLayersUi() {
        val ok = total > 0 && !viewport.continuous
        layersPanel.visibility = if (ok && layersPanel.opened) View.VISIBLE else View.GONE
        layers.visibility = if (ok && !layersPanel.opened) View.VISIBLE else View.GONE
    }

    // ---------- Páginas ----------

    private fun renderPages() {
        val doc = NoteStore.get(this, docId)
        val specs = ArrayList<PageViewport.Spec>()
        if (doc != null) {
            for (i in 0 until doc.pages.length()) {
                val pg = doc.pages.optJSONObject(i) ?: continue
                var di = 1
                for (k in DIMS.indices) if (DIMS[k] == pg.optString("d")) di = k
                val a = MM[di][0]
                val b = MM[di][1]
                val land = pg.optString("o") != "P"
                val bg = pg.optInt("bg", 0xFFFFFFFF.toInt())
                specs.add(if (land) PageViewport.Spec(maxOf(a, b), minOf(a, b), bg) else PageViewport.Spec(minOf(a, b), maxOf(a, b), bg))
            }
        }
        total = specs.size
        viewport.setPages(specs)
        emptyView.visibility = if (total == 0) View.VISIBLE else View.GONE
        updateLayersUi()
        updateCounter()
    }

    private fun updateCounter() {
        counter.text = if (total == 0) "0 / 0" else (viewport.index + 1).toString() + " / " + total
        syncLayers()
    }

    private fun persistStrokes() = StrokeStore.saveAsync(this, docId, strokes)

    private fun persistLayers() = LayerRepository.saveLayers(this, docId, strokes)

    /** Muestra en el panel (y en el ícono) las capas de la hoja actual. */
    private fun syncLayers() {
        if (!::layersPanel.isInitialized) return
        val m = if (total > 0) strokes.page(viewport.index) else null
        layersPanel.bind(m)
        layers.count = layersPanel.layerCount
    }

    // ---------- Diálogo "Crear páginas nuevas" ----------

    private fun selector(initial: String, opts: Array<String>, sel: IntArray, softBlockOthers: Boolean): View {
        val s = LinearLayout(this)
        s.gravity = Gravity.CENTER_VERTICAL
        s.setPadding(dp(10), dp(8), dp(6), dp(8))
        val t = UiKit.text(this, initial, 16, getColor(R.color.ink))
        s.addView(t)
        val ch = ImageView(this)
        ch.setImageDrawable(Icons(Icons.CHEV, getColor(R.color.ink), false))
        val cp = UiKit.box(this, 20)
        cp.leftMargin = dp(6)
        s.addView(ch, cp)
        UiKit.ripple(this, s, 8, 0)
        s.setOnClickListener {
            UiKit.dropdown(this, s, opts) { i ->
                if (softBlockOthers && i != 0) {
                    UiKit.soon(this) // Imagen y Patrón llegarán más adelante
                } else {
                    sel[0] = i
                    t.text = opts[i]
                }
            }
        }
        return s
    }

    private fun formRow(label: String, right: View, rightWidth: Int = -2): View {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(UiKit.text(this, label, 16, getColor(R.color.ink)), LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(right, LinearLayout.LayoutParams(rightWidth, -2))
        r.setPadding(0, dp(6), 0, dp(6))
        return r
    }

    private fun createPages() {
        val dim = intArrayOf(1)
        val ori = intArrayOf(0)
        val bgSel = intArrayOf(0)
        val color = intArrayOf(PAGE_COLORS[0])
        val l = UiKit.vbox(this, 22)
        val title = UiKit.text(this, "Crear páginas nuevas", 18, getColor(R.color.ink))
        title.setPadding(0, 0, 0, dp(8))
        l.addView(title)
        l.addView(formRow("Dimensiones", selector(DIM_LABELS[1], DIM_LABELS, dim, false)))
        l.addView(formRow("Orientación", selector("Horizontal", arrayOf("Horizontal", "Vertical"), ori, false)))
        l.addView(formRow("Fondo", selector("Color", arrayOf("Color", "Imagen", "Patrón"), bgSel, true)))
        val sw = View(this)
        sw.background = UiKit.oval(this, color[0], getColor(R.color.ln2), 1)
        val colorBox = LinearLayout(this)
        colorBox.gravity = Gravity.CENTER_VERTICAL
        colorBox.addView(UiKit.text(this, "Color:", 15, getColor(R.color.mut)))
        val swp = UiKit.box(this, 36)
        swp.leftMargin = dp(10)
        colorBox.addView(sw, swp)
        sw.setOnClickListener {
            pickColor { c ->
                color[0] = c
                sw.background = UiKit.oval(this, c, getColor(R.color.ln2), 1)
            }
        }
        val cr = LinearLayout(this)
        cr.gravity = Gravity.END
        cr.addView(colorBox)
        l.addView(cr)
        val num = EditText(this)
        num.inputType = InputType.TYPE_CLASS_NUMBER
        num.setText("1")
        num.gravity = Gravity.END
        num.setSingleLine(true)
        num.setTextColor(getColor(R.color.ink))
        num.typeface = FontManager.current(this)
        num.backgroundTintList = ColorStateList.valueOf(ThemeUtils.accent(this))
        l.addView(formRow("Número de páginas", num, dp(90)))
        val ref = DlgRef()
        val no = UiKit.btn(this, "Cancelar", getColor(R.color.mut))
        val ok = UiKit.btn(this, "Crear", ThemeUtils.accent(this))
        no.setOnClickListener { ref.dismiss() }
        ok.setOnClickListener {
            val n = (num.text.toString().trim().toIntOrNull() ?: 1).coerceIn(1, 50)
            NoteStore.addPages(this, docId, n, DIMS[dim[0]], ori[0] == 0, color[0])
            ref.dismiss()
            renderPages()
        }
        val br = UiKit.buttons(this, no, ok)
        br.setPadding(0, dp(14), 0, 0)
        l.addView(br)
        ref.d = UiKit.show(this, l, false)
    }

    private fun pickColor(got: (Int) -> Unit) {
        val l = UiKit.vbox(this, 18)
        val t = UiKit.text(this, "Color de la página", 17, getColor(R.color.ink))
        t.setPadding(dp(4), 0, 0, dp(10))
        l.addView(t)
        val ref = DlgRef()
        var row: LinearLayout? = null
        for ((i, c) in PAGE_COLORS.withIndex()) {
            if (i % 4 == 0) {
                val nr = LinearLayout(this)
                l.addView(nr)
                row = nr
            }
            val s = View(this)
            s.background = UiKit.oval(this, c, getColor(R.color.ln2), 1)
            s.setOnClickListener {
                ref.dismiss()
                got(c)
            }
            val sp = UiKit.box(this, 52)
            sp.setMargins(dp(8), dp(8), dp(8), dp(8))
            row!!.addView(s, sp)
        }
        ref.d = UiKit.show(this, l, false)
    }
}
