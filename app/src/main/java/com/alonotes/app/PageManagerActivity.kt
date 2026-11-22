package com.alonotes.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Gestor de páginas: cuadrícula con la miniatura de cada hoja de una nota.
 *  - Normal: tocar una hoja vuelve al editor en esa hoja. Barra: Atrás y EDIT.
 *  - Edición (EDIT): tocar marca/desmarca (borde y capa de acento). Deshacer, rehacer, seleccionar todo, copiar y basura
 *    aún muestran "Próximamente".
 *  - Toque largo + arrastrar: reordena las hojas (se guarda: hojas, trazos y capas). Si se suelta sin mover, abre el menú
 *    de la hoja o de las hojas seleccionadas (por ahora solo avisos).
 * Copiar, pegar y borrar son reales (ver commit()). Devuelve a EditorActivity: "changed" (se cambió algo) y "page" (hoja elegida o -1).
 */
class PageManagerActivity : Activity() {
    private companion object {
        // Mismas medidas que EditorActivity.
        val DIMS = arrayOf("A5", "A4", "A3", "A2")
        val MM = arrayOf(
            floatArrayOf(148f, 210f), floatArrayOf(210f, 297f),
            floatArrayOf(297f, 420f), floatArrayOf(420f, 594f)
        )
    }

    /** Una hoja. [key] no cambia al reordenar (sirve para la selección y las miniaturas). */
    private class Page(val key: Int, val spec: PageViewport.Spec, val layers: PageLayers, val json: JSONObject)

    private val pages = ArrayList<Page>()
    private val selected = HashSet<Int>()
    private val thumbs = HashMap<Int, Bitmap>()
    private val pending = HashSet<Int>()
    private val renderExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val adapter = PageAdapter()

    private var docId = ""
    private var openAt = 0
    private var nextKey = 0
    private var editing = false
    private var changed = false
    private lateinit var doc: DocStrokes
    private lateinit var bar: LinearLayout
    private lateinit var rv: RecyclerView
    private lateinit var glm: GridLayoutManager
    private lateinit var touch: ItemTouchHelper

    // Arrastre
    private var dragging = false
    private var moved = false
    private var dragKey = -1
    private var dragFrom = -1

    private fun dp(v: Number) = UiKit.dp(this, v)

    private fun span(): Int = max(2, resources.displayMetrics.widthPixels / dp(190))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        ThemeUtils.applyTo(this)
        val id = intent.getStringExtra("id")
        if (id == null || NoteStore.get(this, id) == null) {
            finish()
            return
        }
        docId = id
        openAt = intent.getIntExtra("page", 0)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(getColor(R.color.bg))
        bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), 0, dp(6), 0)
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))

        glm = GridLayoutManager(this, span())
        rv = RecyclerView(this)
        rv.layoutManager = glm
        rv.adapter = adapter
        rv.setPadding(dp(10), dp(6), dp(10), dp(24))
        rv.clipToPadding = false
        touch = ItemTouchHelper(dragCallback())
        touch.attachToRecyclerView(rv)
        root.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        buildBar()
        load()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        glm.spanCount = span()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderExec.shutdown()
    }

    /** Carga trazos y capas (después de que terminen de guardarse los del editor) y arma la lista de hojas. */
    private fun load() {
        Thread {
            StrokeStore.flush()
            LayerRepository.flush()
            val d = StrokeStore.load(this, docId)
            LayerRepository.applyTo(this, docId, d)
            val specs = specsOf()
            while (d.pages.size < specs.size) d.page(d.pages.size)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                doc = d
                pages.clear()
                for ((i, s) in specs.withIndex()) pages.add(Page(i, s.second, d.pages[i], s.first))
                nextKey = pages.size
                adapter.notifyDataSetChanged()
                if (openAt in pages.indices) rv.scrollToPosition(openAt)
            }
        }.start()
    }

    /** Datos de cada hoja de la nota (JSON) con su tamaño. Una entrada por hoja, en orden (así no se desalinean con los trazos). */
    private fun specsOf(): List<Pair<JSONObject, PageViewport.Spec>> {
        val out = ArrayList<Pair<JSONObject, PageViewport.Spec>>()
        val item = NoteStore.get(this, docId) ?: return out
        for (i in 0 until item.pages.length()) {
            val pg = item.pages.optJSONObject(i) ?: JSONObject()
            out.add(Pair(pg, specOf(pg)))
        }
        return out
    }

    private fun specOf(pg: JSONObject): PageViewport.Spec {
        var di = 1
        for (k in DIMS.indices) if (DIMS[k] == pg.optString("d")) di = k
        val a = MM[di][0]
        val b = MM[di][1]
        val land = pg.optString("o") != "P"
        val bg = pg.optInt("bg", 0xFFFFFFFF.toInt())
        return if (land) PageViewport.Spec(max(a, b), minOf(a, b), bg) else PageViewport.Spec(minOf(a, b), max(a, b), bg)
    }

    // ---------- Barra superior ----------

    private fun buildBar() {
        bar.removeAllViews()
        val ic = ThemeUtils.accentHi(this)
        fun btn(type: Int, l: () -> Unit): View = UiKit.iconBtn(this, type, ic, 44, 24, l)
        if (!editing) {
            bar.addView(btn(Icons.BACK) { finishWith(-1) }, UiKit.box(this, 44))
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            val e = UiKit.text(this, "EDIT", 16, ic)
            e.setTypeface(e.typeface, Typeface.BOLD)
            e.gravity = Gravity.CENTER
            e.setPadding(dp(14), 0, dp(14), 0)
            UiKit.ripple(this, e, 12, 0)
            e.setOnClickListener {
                editing = true
                selected.clear()
                buildBar()
                adapter.notifyDataSetChanged()
            }
            bar.addView(e, LinearLayout.LayoutParams(-2, dp(44)))
        } else {
            bar.addView(btn(Icons.BACK) { exitEdit() }, UiKit.box(this, 44))
            bar.addView(btn(Icons.UNDO) { UiKit.soon(this) }, UiKit.box(this, 44))
            bar.addView(btn(Icons.REDO) { UiKit.soon(this) }, UiKit.box(this, 44))
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            bar.addView(btn(Icons.SELECT) { UiKit.soon(this) }, UiKit.box(this, 44))
            bar.addView(btn(Icons.COPY) { doCopy(selectedPages()) }, UiKit.box(this, 44))
            bar.addView(btn(Icons.TRASH) { doDelete(selectedPages()) }, UiKit.box(this, 44))
        }
    }

    private fun exitEdit() {
        editing = false
        selected.clear()
        buildBar()
        adapter.notifyDataSetChanged()
    }

    private fun finishWith(page: Int) {
        // Si hubo cambios, se espera a que terminen de escribirse antes de volver al editor.
        if (changed) {
            StrokeStore.flush()
            LayerRepository.flush()
        }
        val i = Intent()
        i.putExtra("changed", changed)
        i.putExtra("page", page)
        setResult(RESULT_OK, i)
        finish()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (editing) exitEdit() else finishWith(-1)
    }

    // ---------- Lista ----------

    private inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val f = FrameLayout(this@PageManagerActivity)
            f.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return PageVH(f)
        }

        override fun onBindViewHolder(h: PageVH, position: Int) {
            val f = h.itemView as FrameLayout
            f.removeAllViews()
            val p = pages[position]
            f.addView(buildCell(h, p, position), FrameLayout.LayoutParams(-1, -2))
            if (thumbs[p.key] == null) requestThumb(p)
        }
    }

    private fun buildCell(h: PageVH, p: Page, pos: Int): View {
        val sel = editing && selected.contains(p.key)
        val acc = ThemeUtils.accentHi(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(6), dp(6), dp(6), dp(6))

        // Recuadro de la miniatura: borde de acento y capa translúcida si está marcada
        val box = FrameLayout(this)
        val g = GradientDrawable()
        g.setColor(getColor(R.color.sf))
        g.cornerRadius = dp(12).toFloat()
        g.setStroke(dp(if (sel) 3 else 1), if (sel) acc else getColor(R.color.ln))
        box.background = g
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(10), dp(10), dp(10), dp(10))
        iv.setImageBitmap(thumbs[p.key])
        box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        if (sel) {
            val ov = View(this)
            ov.background = UiKit.rect(this, ThemeUtils.accentBg(this), 12)
            box.addView(ov, FrameLayout.LayoutParams(-1, -1))
            val chk = ImageView(this)
            chk.setImageDrawable(Icons(Icons.CHECK, getColor(R.color.bg), false))
            chk.background = UiKit.rect(this, acc, 11)
            chk.setPadding(dp(3), dp(3), dp(3), dp(3))
            val cp = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END)
            cp.setMargins(0, dp(8), dp(8), 0)
            box.addView(chk, cp)
        }
        col.addView(box, LinearLayout.LayoutParams(-1, dp(170)))

        val t = UiKit.text(this, "Page " + (pos + 1), 14, if (sel) acc else getColor(R.color.ink))
        t.gravity = Gravity.CENTER
        val tp = LinearLayout.LayoutParams(-1, -2)
        tp.topMargin = dp(6)
        col.addView(t, tp)

        col.setOnClickListener { onTap(h) }
        col.setOnLongClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) {
                // Toque largo: empieza el arrastre; si se suelta sin mover se abre el menú (ver clearView).
                dragging = true
                moved = false
                dragKey = pages[i].key
                dragFrom = i
                touch.startDrag(h)
            }
            true
        }
        return col
    }

    private fun onTap(h: PageVH) {
        val pos = h.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val p = pages[pos]
        if (editing) {
            if (!selected.remove(p.key)) selected.add(p.key)
            adapter.notifyItemChanged(pos)
        } else {
            finishWith(pos)
        }
    }

    // ---------- Miniaturas ----------

    private fun requestThumb(p: Page) {
        if (!pending.add(p.key)) return
        renderExec.execute {
            val bm = try {
                renderThumb(p.spec, p.layers)
            } catch (e: Throwable) {
                null
            }
            main.post {
                pending.remove(p.key)
                if (bm != null && !isFinishing) {
                    thumbs[p.key] = bm
                    val pos = pages.indexOfFirst { it.key == p.key }
                    if (pos >= 0) adapter.notifyItemChanged(pos)
                }
            }
        }
    }

    /** Dibuja la hoja (fondo, y por capa visible: imágenes y trazos) en un bitmap pequeño, igual que PageView. */
    private fun renderThumb(spec: PageViewport.Spec, pl: PageLayers): Bitmap {
        val k = 420f / max(spec.wMm, spec.hMm)
        val w = (spec.wMm * k).roundToInt().coerceAtLeast(1)
        val h = (spec.hMm * k).roundToInt().coerceAtLeast(1)
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = spec.bg
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val dst = RectF()
        for (ly in pl.layers) {
            if (!ly.isVisible) continue
            val a = (ly.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
            val sc = if (a < 255) c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), a) else -1
            val n = c.save()
            c.clipRect(0f, 0f, w.toFloat(), h.toFloat())
            for (im in ly.items) {
                val src = ImageUtil.decodeFile(im.file.path, 512) ?: continue
                val b = im.bounds
                dst.set(b.left * k, b.top * k, b.right * k, b.bottom * k)
                val n2 = c.save()
                c.rotate(im.rotation, dst.centerX(), dst.centerY())
                c.drawBitmap(src, null, dst, paint)
                c.restoreToCount(n2)
            }
            c.restoreToCount(n)
            for (s in ly.canvas) StrokeRender.draw(c, s, k, pen)
            if (sc >= 0) c.restoreToCount(sc)
        }
        return bm
    }

    // ---------- Arrastrar y soltar ----------

    private fun dragCallback() = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            pages.add(to, pages.removeAt(from))
            adapter.notifyItemMoved(from, to)
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                vh?.itemView?.alpha = 0.85f
                vh?.itemView?.scaleX = 1.05f
                vh?.itemView?.scaleY = 1.05f
            }
        }

        override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(r, vh)
            vh.itemView.alpha = 1f
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
            if (!dragging) return
            dragging = false
            if (moved) {
                moved = false
                finishMove()
            } else {
                showMenu(dragKey)
            }
        }
    }

    /** Aplica el movimiento al archivo de la nota, a los trazos y a las capas, y renumera "Page N". */
    private fun finishMove() {
        val to = pages.indexOfFirst { it.key == dragKey }
        if (to >= 0 && to != dragFrom) commit()
        rv.post { adapter.notifyItemRangeChanged(0, pages.size) }
    }

    // ---------- Operaciones: copiar, pegar, borrar ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun selectedPages(): List<Page> = pages.filter { selected.contains(it.key) }

    /** Hojas sobre las que actúa el menú: las marcadas (si son varias y esta es una de ellas) o solo la tocada. */
    private fun groupOf(key: Int): List<Page> {
        if (editing && selected.size > 1 && selected.contains(key)) return selectedPages()
        val p = pages.firstOrNull { it.key == key } ?: return emptyList()
        return listOf(p)
    }

    /**
     * Escribe la nota completa desde UNA sola lista ordenada (`pages`). Cada hoja lleva consigo su JSON de página y su
     * PageLayers (capas, trazos, imágenes), así que las tres cosas se reconstruyen en el mismo orden y no hay forma de
     * que los trazos de una hoja acaben en otra: no se usan números de posición guardados, solo el orden de esta lista.
     * Hojas, trazos y capas se escriben desde la misma foto del estado.
     */
    private fun commit() {
        doc.pages.clear()
        for (p in pages) doc.pages.add(p.layers)
        NoteStore.setPages(this, docId, pages.map { it.json })
        StrokeStore.saveAsync(this, docId, doc)
        LayerRepository.saveLayers(this, docId, doc)
        changed = true
    }

    private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    private fun doCopy(list: List<Page>) {
        if (list.isEmpty()) {
            toast("Selecciona al menos una hoja")
            return
        }
        // Copia profunda: no se borra ni se cambia nada de las originales.
        PageClipboard.copy(this, list.map { Pair(it.json, it.layers) })
        toast(count(list.size, "hoja copiada", "hojas copiadas"))
    }

    /** [after] = true: justo después de la última hoja del grupo (Pegar adelante); false: justo antes de la primera (Pegar atrás). */
    private fun doPaste(target: List<Page>, after: Boolean) {
        if (PageClipboard.isEmpty) {
            toast("No hay hojas copiadas")
            return
        }
        if (target.isEmpty()) return
        val idx = if (after) target.maxOf { pages.indexOf(it) } + 1 else target.minOf { pages.indexOf(it) }
        val made = PageClipboard.paste(this, docId)
        if (made.isEmpty()) {
            toast("No se pudo pegar")
            return
        }
        val news = made.map { (js, pl) -> Page(nextKey++, specOf(js), pl, js) }
        pages.addAll(idx.coerceIn(0, pages.size), news)
        commit()
        adapter.notifyDataSetChanged()
        toast(if (news.size == 1) "Hoja pegada" else "Hojas pegadas")
    }

    private fun doDelete(list: List<Page>) {
        if (list.isEmpty()) {
            toast("Selecciona al menos una hoja")
            return
        }
        // Regla de oro: la nota siempre conserva al menos una hoja.
        if (list.size >= pages.size) {
            toast("La nota debe tener al menos una hoja")
            return
        }
        val n = list.size
        UiKit.confirm(this, "¿Borrar " + count(n, "hoja", "hojas") + "? No se puede deshacer.", "Borrar") {
            val keys = list.map { it.key }.toSet()
            pages.removeAll { keys.contains(it.key) }
            for (k in keys) {
                selected.remove(k)
                thumbs.remove(k)
            }
            commit()
            cleanupImages()
            adapter.notifyDataSetChanged()
            toast(count(n, "hoja borrada", "hojas borradas"))
        }
    }

    /** Borra los archivos de imagen de la nota que ya no usa ninguna hoja (las copias del portapapeles tienen los suyos). */
    private fun cleanupImages() {
        try {
            val keep = HashSet<String>()
            for (p in pages) for (ly in p.layers.layers) for (im in ly.items) keep.add(im.file.name)
            File(filesDir, "images/$docId").listFiles()?.forEach { if (!keep.contains(it.name)) it.delete() }
        } catch (ignored: Exception) {
            // Los archivos sueltos se borran con la nota.
        }
    }

    // ---------- Menú contextual ----------

    private fun showMenu(key: Int) {
        val group = groupOf(key)
        if (group.isEmpty()) return
        val n = group.size
        val ink = getColor(R.color.ink)
        val l = UiKit.vbox(this, 12)
        val pos = pages.indexOfFirst { it.key == key }
        val title = UiKit.text(this, if (n == 1) "Page " + (pos + 1) else "$n hojas seleccionadas", 17, ink)
        title.setPadding(dp(14), dp(8), dp(14), dp(8))
        l.addView(title)
        val ref = DlgRef()
        l.addView(UiKit.sheetRow(this, Icons.COPY, if (n == 1) "Copiar hoja" else "Copiar seleccionadas", ink, ref) {
            doCopy(group)
        })
        l.addView(UiKit.sheetRow(this, Icons.TRASH, if (n == 1) "Borrar hoja" else "Borrar seleccionadas", getColor(R.color.danger), ref) {
            doDelete(group)
        })
        l.addView(UiKit.sheetRow(this, Icons.ARROW_UP, "Pegar adelante", ink, ref) { doPaste(group, true) })
        l.addView(UiKit.sheetRow(this, Icons.ARROW_DOWN, "Pegar atrás", ink, ref) { doPaste(group, false) })
        ref.d = UiKit.show(this, l, true)
    }
}

private class PageVH(v: View) : RecyclerView.ViewHolder(v)
