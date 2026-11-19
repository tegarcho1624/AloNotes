package com.alonotes.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import android.graphics.Outline
import android.view.ViewOutlineProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ajustes (la tuerca). Navegación propia por pilas de rutas, como el NavHost de AloTube:
 * MENU › APPEARANCE › (BACKGROUNDS | FONTS | color de acento y HEX) y ARCHIVED.
 * Cambiar acento o fuente recrea la Activity; la pila se conserva. Fondo propio (BgScope.SETTINGS).
 */
class SettingsActivity : Activity() {
    private companion object {
        const val REQ_BG_IMG = 31
        const val REQ_BG_VID = 32
        const val REQ_FONT = 33
        const val REQ_ICON = 34
        val SCALES = listOf("Crop" to "Rellenar", "FillBounds" to "Estirar", "Fit" to "Ajustar")
    }

    private var stack = ArrayList<String>()
    private val archivedPath = ArrayList<String>()
    private lateinit var host: FrameLayout
    private lateinit var bgLayer: BackgroundLayer
    private var themeVer = 0
    private var pendingScope: BgScope? = null
    private var pendingFolder: String? = null
    private val fmt = SimpleDateFormat("EEE dd MMM, HH:mm", Locale("es"))

    private fun dp(v: Number) = UiKit.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemeUtils.applyTo(this)
        themeVer = ThemeUtils.version()
        stack = b?.getStringArrayList("stack") ?: arrayListOf("MENU")
        if (stack.isEmpty()) stack.add("MENU")
        b?.getStringArrayList("apath")?.let { archivedPath.addAll(it) }
        val root = FrameLayout(this)
        bgLayer = BackgroundLayer(this, BgScope.SETTINGS)
        root.addView(bgLayer, FrameLayout.LayoutParams(-1, -1))
        host = FrameLayout(this)
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        show()
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putStringArrayList("stack", stack)
        o.putStringArrayList("apath", ArrayList(archivedPath))
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
        bgLayer.pause()
        super.onPause()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = back()

    private fun back() {
        if (stack.last() == "ARCHIVED" && archivedPath.isNotEmpty()) {
            archivedPath.removeAt(archivedPath.size - 1)
        } else if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
        } else {
            finish()
            return
        }
        show()
    }

    private fun go(route: String) {
        stack.add(route)
        show()
    }

    private fun show() {
        host.removeAllViews()
        val v = when (stack.last()) {
            "APPEARANCE" -> appearance()
            "FONTS" -> fonts()
            "BACKGROUNDS" -> backgrounds()
            "ARCHIVED" -> archived()
            else -> menu()
        }
        host.addView(v, FrameLayout.LayoutParams(-1, -1))
    }

    // ---------- Componentes (SettingsTopBar / SettingsRow) ----------

    private fun page(title: String, action: View? = null, content: LinearLayout.() -> Unit): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(8), 0, dp(8), 0)
        top.addView(UiKit.iconBtn(this, Icons.BACK, getColor(R.color.mut), 48, 24) { back() }, UiKit.box(this, 48))
        val t = UiKit.text(this, title, 22, getColor(R.color.ink))
        t.setSingleLine(true)
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        t.setPadding(dp(4), 0, 0, 0)
        top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) top.addView(action, UiKit.box(this, 48))
        col.addView(top, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = ScrollView(this)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, 0, 0, dp(24))
        body.content()
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    private fun row(icon: Int, title: String, sub: String? = null, onClick: () -> Unit): View {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(20), dp(14), dp(20), dp(14))
        val iv = ImageView(this)
        iv.setImageDrawable(Icons(icon, getColor(R.color.ink), false))
        r.addView(iv, UiKit.box(this, 24))
        val tx = LinearLayout(this)
        tx.orientation = LinearLayout.VERTICAL
        tx.addView(UiKit.text(this, title, 16, getColor(R.color.ink)))
        if (sub != null) tx.addView(UiKit.text(this, sub, 13, getColor(R.color.mut)))
        val tp = LinearLayout.LayoutParams(0, -2, 1f)
        tp.leftMargin = dp(18)
        r.addView(tx, tp)
        val ch = ImageView(this)
        ch.setImageDrawable(Icons(Icons.CHEV, getColor(R.color.mut), false))
        ch.rotation = -90f
        r.addView(ch, UiKit.box(this, 20))
        UiKit.ripple(this, r, 0, 0)
        r.setOnClickListener { onClick() }
        return r
    }

    private fun section(title: String): View {
        val t = UiKit.text(this, title, 13, ThemeUtils.accentHi(this))
        t.setPadding(dp(20), dp(20), dp(20), dp(6))
        return t
    }

    private fun note(s: String): View {
        val t = UiKit.text(this, s, 13, getColor(R.color.mut))
        t.setPadding(dp(20), dp(12), dp(20), dp(6))
        return t
    }

    // ---------- Pantallas ----------

    private fun menu(): View = page("Ajustes") {
        addView(row(Icons.PALETTE, "Apariencia") { go("APPEARANCE") })
        addView(row(Icons.ARCHIVE, "Archivados") {
            archivedPath.clear()
            go("ARCHIVED")
        })
    }

    private fun appearance(): View = page("Apariencia") {
        addView(row(Icons.IMAGE, "Fondo") { go("BACKGROUNDS") })
        addView(section("Estilo de Escritura"))
        addView(row(Icons.TEXT, "Estilos") { go("FONTS") })
        addView(section("Color de acento"))
        addView(accentGrid())
        addView(note("Mantén pulsado un color propio para quitarlo."))
    }

    private fun accentGrid(): View {
        val cur = ThemeUtils.accent(this)
        val ink = getColor(R.color.ink)
        val basics = ThemeUtils.ACCENT_COLORS.size
        val all = ArrayList<Int?>()
        for (c in ThemeUtils.ACCENT_COLORS) all.add(c)
        for (c in ThemeUtils.customs(this)) all.add(c)
        all.add(null) // "+" HEX
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(14), 0, dp(14), 0)
        for (chunk in all.withIndex().chunked(6)) {
            val rowL = LinearLayout(this)
            for ((idx, color) in chunk) {
                val sp = UiKit.box(this, 44)
                sp.setMargins(dp(5), dp(5), dp(5), dp(5))
                if (color == null) {
                    val plus = UiKit.iconBtn(this, Icons.PLUS, getColor(R.color.mut), 44, 22) { hexDialog() }
                    plus.background = UiKit.oval(this, 0, getColor(R.color.ln2), 1.5f)
                    rowL.addView(plus, sp)
                } else {
                    val sw = View(this)
                    sw.background = UiKit.oval(this, color, ink, if (color == cur) 2.5f else 0f)
                    sw.setOnClickListener {
                        ThemeUtils.setAccent(this, color)
                        recreate()
                    }
                    if (idx >= basics) {
                        sw.setOnLongClickListener {
                            UiKit.confirm(this, "¿Quitar este color?", "Quitar") {
                                ThemeUtils.removeCustom(this, color)
                                recreate()
                            }
                            true
                        }
                    }
                    rowL.addView(sw, sp)
                }
            }
            wrap.addView(rowL)
        }
        return wrap
    }

    /** Añade un color propio por código HEX (#RRGGBB). */
    private fun hexDialog() {
        val l = UiKit.vbox(this, 22)
        l.addView(UiKit.text(this, "Color HEX", 18, getColor(R.color.ink)))
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        val prev = View(this)
        prev.background = UiKit.oval(this, ThemeUtils.accent(this), getColor(R.color.ln2), 1)
        val pp = UiKit.box(this, 40)
        pp.rightMargin = dp(14)
        r.addView(prev, pp)
        val e = EditText(this)
        e.setSingleLine(true)
        e.hint = "#RRGGBB"
        e.setHintTextColor(getColor(R.color.mut))
        e.setTextColor(getColor(R.color.ink))
        e.typeface = FontManager.current(this)
        e.filters = arrayOf(InputFilter.LengthFilter(7))
        e.backgroundTintList = ColorStateList.valueOf(ThemeUtils.accent(this))
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                ThemeUtils.parseHex(s?.toString() ?: "")?.let {
                    prev.background = UiKit.oval(this@SettingsActivity, it, getColor(R.color.ln2), 1)
                }
            }
        })
        r.addView(e, LinearLayout.LayoutParams(0, -2, 1f))
        val rp = LinearLayout.LayoutParams(-1, -2)
        rp.topMargin = dp(14)
        rp.bottomMargin = dp(18)
        l.addView(r, rp)
        val ref = DlgRef()
        val no = UiKit.btn(this, "Cancelar", getColor(R.color.mut))
        val yes = UiKit.btn(this, "Añadir", ThemeUtils.accent(this))
        no.setOnClickListener { ref.dismiss() }
        yes.setOnClickListener {
            val c = ThemeUtils.parseHex(e.text.toString())
            if (c == null) {
                toast("Código HEX no válido")
            } else {
                ref.dismiss()
                ThemeUtils.addCustom(this, c)
                recreate()
            }
        }
        l.addView(UiKit.buttons(this, no, yes))
        ref.d = UiKit.show(this, l, false)
        e.requestFocus()
    }

    private fun fonts(): View {
        val plus = UiKit.iconBtn(this, Icons.PLUS, ThemeUtils.accentHi(this), 48, 24) { pickFont() }
        return page("Estilos", plus) {
            val sel = FontManager.selectedId(this@SettingsActivity)
            for (o in FontManager.list(this@SettingsActivity)) {
                val r = LinearLayout(this@SettingsActivity)
                r.gravity = Gravity.CENTER_VERTICAL
                r.setPadding(dp(20), dp(14), dp(20), dp(14))
                val t = UiKit.text(this@SettingsActivity, o.label, 20, getColor(R.color.ink))
                t.typeface = o.tf // cada nombre en su propia tipografía
                r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                if (o.id == sel) {
                    val ck = ImageView(this@SettingsActivity)
                    ck.setImageDrawable(Icons(Icons.CHECK, ThemeUtils.accentHi(this@SettingsActivity), false))
                    r.addView(ck, UiKit.box(this@SettingsActivity, 24))
                }
                UiKit.ripple(this@SettingsActivity, r, 0, 0)
                r.setOnClickListener {
                    FontManager.set(this@SettingsActivity, o.id)
                    recreate()
                }
                addView(r)
            }
            addView(note("Toca + para importar una fuente .ttf u .otf."))
        }
    }

    private fun backgrounds(): View = page("Fondo") {
        for (s in BgScope.values()) {
            val has = BackgroundStore.path(this@SettingsActivity, s) != null
            val sub = if (!has) "Sin fondo" else
                (if (BackgroundStore.isVideo(this@SettingsActivity, s)) "Video" else "Imagen") + " · " +
                    (SCALES.firstOrNull { it.first == BackgroundStore.scale(this@SettingsActivity, s) }?.second ?: "")
            addView(row(if (has && BackgroundStore.isVideo(this@SettingsActivity, s)) Icons.VIDEO else Icons.IMAGE, s.label, sub) {
                bgSheet(s)
            })
        }
        addView(note("El fondo se oscurece un 60 % para que se lea el contenido."))
    }

    private fun bgSheet(s: BgScope) {
        val ink = getColor(R.color.ink)
        val l = UiKit.vbox(this, 12)
        val title = UiKit.text(this, s.label, 18, ink)
        title.setPadding(dp(14), dp(8), dp(14), dp(8))
        l.addView(title)
        val ref = DlgRef()
        l.addView(UiKit.sheetRow(this, Icons.IMAGE, "Establecer imagen", ink, ref) { pickBg(s, false) })
        l.addView(UiKit.sheetRow(this, Icons.VIDEO, "Establecer video", ink, ref) { pickBg(s, true) })
        if (BackgroundStore.path(this, s) != null) {
            val lab = UiKit.text(this, "Ajuste", 13, getColor(R.color.mut))
            lab.setPadding(dp(14), dp(10), dp(14), dp(6))
            l.addView(lab)
            val chips = LinearLayout(this)
            chips.setPadding(dp(8), 0, 0, dp(8))
            val views = ArrayList<Pair<String, TextView>>()
            fun paint() {
                val cur = BackgroundStore.scale(this, s)
                for ((k, tv) in views) {
                    val on = k == cur
                    tv.setTextColor(if (on) ThemeUtils.accentHi(this) else getColor(R.color.mut))
                    tv.background = UiKit.rect(this, if (on) ThemeUtils.accentBg(this) else getColor(R.color.sf3), 10)
                }
            }
            for ((k, label) in SCALES) {
                val tv = UiKit.text(this, label, 14, ink)
                tv.setPadding(dp(16), dp(10), dp(16), dp(10))
                tv.setOnClickListener {
                    BackgroundStore.setScale(this, s, k)
                    bgLayer.refresh()
                    paint()
                    show()
                }
                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.setMargins(dp(6), 0, dp(6), 0)
                chips.addView(tv, lp)
                views.add(k to tv)
            }
            paint()
            l.addView(chips)
            l.addView(UiKit.sheetRow(this, Icons.CLOSE, "Quitar fondo", getColor(R.color.danger), ref) {
                BackgroundStore.remove(this, s)
                toast("Quitado")
                bgLayer.refresh()
                show()
            })
        }
        ref.d = UiKit.show(this, l, true)
    }

    private fun archived(): View {
        val openId = archivedPath.lastOrNull()
        val title = if (openId == null) "Archivados" else (NoteStore.get(this, openId)?.name ?: "Archivados")
        return page(title) {
            val list = if (openId == null) NoteStore.archivedFolders(this@SettingsActivity)
            else NoteStore.contentsOf(this@SettingsActivity, openId)
            if (list.isEmpty()) {
                addView(note(if (openId == null) "No hay carpetas archivadas." else "Carpeta vacía."))
            }
            for (it in list) addView(archivedRow(it, openId == null))
        }
    }

    private fun archivedRow(it: NoteStore.Item, top: Boolean): View {
        val r = LinearLayout(this)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(20), dp(10), dp(12), dp(10))
        val th = FrameLayout(this)
        val iconPath = if (it.folder) FolderIconStore.get(this, it.id) else null
        if (iconPath != null) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            iv.setImageBitmap(ImageUtil.decodeFile(iconPath, 256))
            iv.clipToOutline = true
            iv.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) {
                    o.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
            th.addView(iv, FrameLayout.LayoutParams(-1, -1))
        } else {
            val iv = ImageView(this)
            iv.setImageDrawable(
                if (it.folder) Icons(Icons.FOLDER, ThemeUtils.accent(this), true)
                else Icons(Icons.DOC, getColor(R.color.mut), false)
            )
            th.addView(iv, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        r.addView(th, UiKit.box(this, 56))
        val tx = LinearLayout(this)
        tx.orientation = LinearLayout.VERTICAL
        tx.addView(UiKit.text(this, it.name, 16, getColor(R.color.ink)))
        tx.addView(UiKit.text(this, fmt.format(Date(it.t)), 12, getColor(R.color.mut)))
        val tp = LinearLayout.LayoutParams(0, -2, 1f)
        tp.leftMargin = dp(16)
        r.addView(tx, tp)
        if (top) {
            r.addView(UiKit.iconBtn(this, Icons.CHEV, getColor(R.color.mut), 40, 20) {
                FolderMenu.show(this, it, {
                    pendingFolder = it.id
                    pickImage(REQ_ICON)
                }) { show() }
            }, UiKit.box(this, 40))
        }
        UiKit.ripple(this, r, 0, 0)
        r.setOnClickListener { _ ->
            if (it.folder) {
                archivedPath.add(it.id)
                show()
            } else {
                val i = Intent(this, EditorActivity::class.java)
                i.putExtra("id", it.id)
                startActivity(i)
            }
        }
        return r
    }

    // ---------- Selectores de archivos ----------

    private fun pickImage(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, req)
    }

    private fun pickBg(s: BgScope, video: Boolean) {
        pendingScope = s
        if (!video) {
            pickImage(REQ_BG_IMG)
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "video/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_BG_VID)
    }

    private fun pickFont() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")
        )
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_FONT)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        when (req) {
            REQ_BG_IMG, REQ_BG_VID -> {
                val s = pendingScope ?: return
                toast("Aplicando…")
                Thread {
                    val done = if (req == REQ_BG_VID) BackgroundStore.setVideo(this, s, uri)
                    else BackgroundStore.setImage(this, s, uri)
                    runOnUiThread {
                        toast(if (done) "Aplicado" else "No se pudo aplicar")
                        bgLayer.refresh()
                        show()
                    }
                }.start()
            }
            REQ_FONT -> Thread {
                val done = FontManager.import(this, uri)
                runOnUiThread {
                    toast(if (done) "Fuente importada" else "No se pudo importar")
                    show()
                }
            }.start()
            REQ_ICON -> {
                val id = pendingFolder ?: return
                FolderMenu.applyIcon(this, id, uri) { show() }
            }
        }
    }
}
