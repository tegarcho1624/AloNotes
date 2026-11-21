package com.alonotes.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.RectF
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
    private lateinit var counter: TextView
    private lateinit var bar: LinearLayout
    private lateinit var bgLayer: BackgroundLayer
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
        viewport.restore(b?.getBoolean("cont") ?: false, b?.getInt("page") ?: 0)
        viewport.onPage = { updateCounter() }
        strokes = StrokeStore.load(this, docId)
        LayerRepository.applyTo(this, docId, strokes)
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
        layersPanel.onVisibilityChanged = {
            viewport.refreshPage()
            persistLayers()
        }
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
            o.putInt("page", viewport.index)
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
        bar.addView(tb(Icons.GRID, ink) { UiKit.soon(this) }, UiKit.box(this, 44))
        bar.addView(tb(Icons.PLUS, ink) { createPages() }, UiKit.box(this, 44))
        bar.addView(View(this), LinearLayout.LayoutParams(dp(22), 1))
        // Lápiz (1), borrador (2) y marca texto (3): se resaltan y muestran sus opciones; el resto, próximamente
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
                else -> tb(t, ink) { editOnly { UiKit.soon(this) } }
            }
            if (t == Icons.SELECT && tool == 4 && !cont) UiKit.ripple(this, btn, 12, ThemeUtils.accentBg(this))
            bar.addView(btn, UiKit.box(this, 44))
        }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (tool == 1 && !cont) {
            PenOptions.build(this, bar) { buildBar() }
        } else if (tool == 2 && !cont) {
            PenOptions.buildEraser(this, bar) { buildBar() }
        } else if (tool == 3 && !cont) {
            PenOptions.buildHighlighter(this, bar) { buildBar() }
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
        bar.addView(tb(Icons.LOCK, ink) { UiKit.soon(this) }, UiKit.box(this, 44))
        bar.addView(tb(Icons.ROT_L, mut) { UiKit.soon(this) }, UiKit.box(this, 44))
        bar.addView(tb(Icons.ROT_R, mut) { UiKit.soon(this) }, UiKit.box(this, 44))
        val div = View(this)
        div.setBackgroundColor(getColor(R.color.ln2))
        val dv = LinearLayout.LayoutParams(dp(1), dp(24))
        dv.setMargins(dp(8), 0, dp(8), 0)
        bar.addView(div, dv)
        bar.addView(tb(Icons.GEAR, ink) { UiKit.soon(this) }, UiKit.box(this, 44))
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
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMAGE)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
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

    /** Coloca la imagen en el centro de la hoja actual, dentro de la capa seleccionada, y activa la herramienta Seleccionar. */
    private fun insertImage(id: String, f: File, bm: android.graphics.Bitmap) {
        val sp = viewport.pageSpec()
        if (total == 0 || sp == null) return
        val pl = strokes.page(viewport.index)
        val layer = pl.layers[pl.selected]
        // Cabe en el 60 % de la hoja, conservando la proporción.
        val k = minOf(sp.wMm * 0.6f / bm.width, sp.hMm * 0.6f / bm.height)
        val w = bm.width * k
        val h = bm.height * k
        val cx = sp.wMm / 2f
        val cy = sp.hMm / 2f
        val item = ImageItem(id, f, RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), 0f, w)
        item.bitmap = bm
        layer.items.add(item)
        tool = 4
        viewport.tool = 4
        viewport.selectItem(item)
        viewport.refreshPage()
        buildBar()
        persistLayers()
    }

    private fun pickTool(t: Int) {
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
