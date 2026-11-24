package com.alonotes.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * Menú flotante de stickers (una hoja inferior, no pantalla completa).
 *  - Barra: + (agregar desde la galería), selector de categoría (lista desplegable con crear y eliminar) y basura
 *    (borra el sticker seleccionado: el último que se mantuvo presionado).
 *  - Cuadrícula de los stickers de la categoría actual. Toque simple: se inserta en la hoja ([onPick]) y se cierra.
 *    Toque largo + arrastrar: reordena (se guarda). Toque largo sin mover: lo selecciona y ofrece "Borrar sticker".
 */
internal class StickerMenu(
    private val a: Activity,
    private val onPick: (Sticker) -> Unit,
    private val onAdd: () -> Unit
) {
    private val stickers = ArrayList<Sticker>()
    private var selectedId: String? = null
    private val ref = DlgRef()
    private val rv = RecyclerView(a)
    private val adapter = StickerAdapter()
    private lateinit var touch: ItemTouchHelper
    private val title = UiKit.text(a, "", 16, a.getColor(R.color.ink))
    private val empty = UiKit.text(a, "Aquí no hay stickers todavía.\nToca + para agregar uno de la galería.", 14, a.getColor(R.color.mut))
    private var dragging = false
    private var moved = false
    private var dragKey: String? = null

    private fun dp(v: Number) = UiKit.dp(a, v)

    private fun width(): Int = minOf(dp(560), (a.resources.displayMetrics.widthPixels * 0.92f).toInt())

    private fun span(): Int = max(3, width() / dp(110))

    fun show() {
        val w = width()
        val box = UiKit.vbox(a, 12)
        box.addView(buildBar(), LinearLayout.LayoutParams(w, dp(52)))
        val h = minOf(dp(360), (a.resources.displayMetrics.heightPixels * 0.5f).toInt())
        val body = FrameLayout(a)
        rv.layoutManager = GridLayoutManager(a, span())
        rv.adapter = adapter
        rv.setPadding(dp(4), dp(4), dp(4), dp(8))
        rv.clipToPadding = false
        touch = ItemTouchHelper(dragCallback())
        touch.attachToRecyclerView(rv)
        body.addView(rv, FrameLayout.LayoutParams(-1, -1))
        empty.gravity = Gravity.CENTER
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        box.addView(body, LinearLayout.LayoutParams(w, h))
        refresh()
        ref.d = UiKit.show(a, box, true)
    }

    fun dismiss() = ref.dismiss()

    /** Vuelve a leer la categoría actual y sus stickers (por ejemplo, después de agregar uno). */
    fun refresh() {
        val cur = StickerStore.currentCategory(a)
        val name = StickerStore.categories(a).firstOrNull { it.id == cur }?.name ?: ""
        title.text = name
        stickers.clear()
        stickers.addAll(StickerStore.stickers(a, cur))
        if (stickers.none { it.id == selectedId }) selectedId = null
        adapter.notifyDataSetChanged()
        empty.visibility = if (stickers.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- Barra ----------

    private fun buildBar(): View {
        val ink = a.getColor(R.color.ink)
        val hi = ThemeUtils.accentHi(a)
        val bar = LinearLayout(a)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(UiKit.iconBtn(a, Icons.PLUS, hi, 44, 24) { onAdd() }, UiKit.box(a, 44))
        // Selector de categoría: nombre y flecha; abre la lista desplegable
        val sel = LinearLayout(a)
        sel.gravity = Gravity.CENTER
        title.setSingleLine(true)
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        sel.addView(title, LinearLayout.LayoutParams(-2, -2))
        val ch = ImageView(a)
        ch.setImageDrawable(Icons(Icons.CHEV, ink, false))
        val cp = UiKit.box(a, 20)
        cp.leftMargin = dp(6)
        sel.addView(ch, cp)
        UiKit.ripple(a, sel, 14, 0)
        sel.setOnClickListener { showCategories(sel) }
        bar.addView(sel, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(UiKit.iconBtn(a, Icons.TRASH, ink, 44, 24) { deleteSelected() }, UiKit.box(a, 44))
        return bar
    }

    private fun row(text: String, color: Int, selected: Boolean, onClick: () -> Unit): View {
        val t = UiKit.text(a, text, 15, color)
        t.setPadding(dp(16), dp(12), dp(16), dp(12))
        t.setSingleLine(true)
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        if (selected) t.background = UiKit.rect(a, ThemeUtils.accentBg(a), 10) else UiKit.ripple(a, t, 10, 0)
        t.setOnClickListener { onClick() }
        return t
    }

    /** Lista desplegable: categorías, "Nueva categoría" y (si no es la por defecto) "Eliminar categoría". */
    private fun showCategories(anchor: View) {
        val ink = a.getColor(R.color.ink)
        val cur = StickerStore.currentCategory(a)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(6), dp(6), dp(6), dp(6))
        col.background = UiKit.rect(a, a.getColor(R.color.sf2), 14, a.getColor(R.color.ln2))
        val pw = PopupWindow(col, max(anchor.width, dp(240)), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        for (c in StickerStore.categories(a)) {
            col.addView(row(c.name, ink, c.id == cur) {
                pw.dismiss()
                StickerStore.setCurrent(a, c.id)
                refresh()
            })
        }
        col.addView(row("+  Nueva categoría", ThemeUtils.accentHi(a), false) {
            pw.dismiss()
            UiKit.askName(a, "Nueva categoría de stickers", "") { n ->
                val cat = StickerStore.addCategory(a, n)
                StickerStore.setCurrent(a, cat.id)
                refresh()
            }
        })
        if (cur != StickerStore.DEFAULT_ID) {
            col.addView(row("Eliminar categoría", a.getColor(R.color.danger), false) {
                pw.dismiss()
                val name = StickerStore.categories(a).firstOrNull { it.id == cur }?.name ?: ""
                UiKit.confirm(a, "¿Eliminar «" + name + "» y todos sus stickers?", "Eliminar") {
                    StickerStore.removeCategory(a, cur)
                    refresh()
                }
            })
        }
        pw.showAsDropDown(anchor)
    }

    // ---------- Cuadrícula ----------

    private inner class StickerAdapter : RecyclerView.Adapter<StickerVH>() {
        override fun getItemCount(): Int = stickers.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StickerVH {
            val f = FrameLayout(a)
            f.layoutParams = RecyclerView.LayoutParams(-1, dp(100))
            return StickerVH(f)
        }

        override fun onBindViewHolder(h: StickerVH, position: Int) {
            val f = h.itemView as FrameLayout
            f.removeAllViews()
            f.addView(cell(h, stickers[position]), FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun cell(h: StickerVH, s: Sticker): View {
        val sel = s.id == selectedId
        val acc = ThemeUtils.accentHi(a)
        val wrap = FrameLayout(a)
        wrap.setPadding(dp(4), dp(4), dp(4), dp(4))
        val box = FrameLayout(a)
        val g = GradientDrawable()
        g.setColor(a.getColor(R.color.sf))
        g.cornerRadius = dp(12).toFloat()
        g.setStroke(dp(if (sel) 3 else 1), if (sel) acc else a.getColor(R.color.ln))
        box.background = g
        val iv = ImageView(a)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(8), dp(8), dp(8), dp(8))
        iv.setImageBitmap(ImageUtil.decodeFile(StickerStore.fileOf(a, s).path, 256))
        box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        wrap.addView(box, FrameLayout.LayoutParams(-1, -1))
        wrap.setOnClickListener {
            // Toque simple: se inserta en la hoja y el menú se cierra.
            ref.dismiss()
            onPick(s)
        }
        wrap.setOnLongClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) {
                // Toque largo: empieza el arrastre; si se suelta sin mover, se selecciona y se ofrece borrar (ver clearView).
                dragging = true
                moved = false
                dragKey = stickers[i].id
                touch.startDrag(h)
            }
            true
        }
        return wrap
    }

    // ---------- Reordenar y borrar ----------

    private fun dragCallback() = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            stickers.add(to, stickers.removeAt(from))
            adapter.notifyItemMoved(from, to)
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                vh?.itemView?.alpha = 0.85f
                vh?.itemView?.scaleX = 1.06f
                vh?.itemView?.scaleY = 1.06f
            }
        }

        override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(r, vh)
            vh.itemView.alpha = 1f
            vh.itemView.scaleX = 1f
            vh.itemView.scaleY = 1f
            if (!dragging) return
            dragging = false
            val key = dragKey
            if (moved) {
                moved = false
                StickerStore.reorder(a, StickerStore.currentCategory(a), stickers.map { it.id })
            } else if (key != null) {
                selectedId = key
                adapter.notifyDataSetChanged()
                showContext(key)
            }
        }
    }

    private fun showContext(id: String) {
        val l = UiKit.vbox(a, 12)
        val t = UiKit.text(a, "Sticker", 17, a.getColor(R.color.ink))
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        l.addView(t)
        val r = DlgRef()
        l.addView(UiKit.sheetRow(a, Icons.TRASH, "Borrar sticker", a.getColor(R.color.danger), r) { confirmDelete(id) })
        r.d = UiKit.show(a, l, true)
    }

    private fun confirmDelete(id: String) {
        UiKit.confirm(a, "¿Borrar este sticker?", "Borrar") {
            StickerStore.remove(a, id)
            if (selectedId == id) selectedId = null
            refresh()
        }
    }

    /** Botón de la basura: borra el sticker seleccionado (el último que se mantuvo presionado). */
    private fun deleteSelected() {
        val id = selectedId
        if (id == null) {
            Toast.makeText(a, "Mantén presionado un sticker para seleccionarlo", Toast.LENGTH_SHORT).show()
            return
        }
        confirmDelete(id)
    }
}

private class StickerVH(v: View) : RecyclerView.ViewHolder(v)
