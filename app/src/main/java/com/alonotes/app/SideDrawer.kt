package com.alonotes.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Menú lateral deslizable. Se abre con el botón de tres rayas o deslizando de izquierda a derecha
 * desde la zona izquierda de la pantalla (rápido, para no chocar con el arrastre de carpetas).
 * Se cierra tocando fuera, deslizando a la izquierda o con atrás.
 */
internal class SideDrawerLayout(ctx: Context, private val panelW: Int) : FrameLayout(ctx) {
    private val scrim = View(ctx)
    private var panel: View? = null
    private var progress = 0f
    private var opened = false
    private var anim: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var dragging = false
    private var startProgress = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    val isOpen: Boolean get() = opened

    fun setup(content: View, p: View) {
        addView(content, LayoutParams(-1, -1))
        scrim.setBackgroundColor(ctx().getColor(R.color.scrim))
        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(-1, -1))
        p.elevation = 16f * resources.displayMetrics.density
        addView(p, LayoutParams(panelW, -1, Gravity.START))
        panel = p
        render()
    }

    private fun ctx(): Context = context

    private fun render() {
        panel?.translationX = -panelW * (1f - progress)
        panel?.visibility = if (progress > 0f) VISIBLE else INVISIBLE
        scrim.alpha = progress
        scrim.visibility = if (progress > 0f) VISIBLE else GONE
    }

    fun open() = settle(true)
    fun close() = settle(false)

    private fun settle(open: Boolean) {
        opened = open
        anim?.cancel()
        val a = ValueAnimator.ofFloat(progress, if (open) 1f else 0f)
        a.duration = 200
        a.addUpdateListener {
            progress = it.animatedValue as Float
            render()
        }
        anim = a
        a.start()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                downT = e.eventTime
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) return true
                val dx = e.x - downX
                val dy = e.y - downY
                val horizontal = abs(dx) > 2 * abs(dy)
                val start = if (progress == 0f) {
                    downX <= width * 0.18f && dx > slop * 2 && horizontal && e.eventTime - downT < 350
                } else {
                    dx < -slop * 2 && horizontal
                }
                if (start) {
                    dragging = true
                    downX = e.x
                    startProgress = progress
                    anim?.cancel()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                progress = (startProgress + (e.x - downX) / panelW).coerceIn(0f, 1f)
                render()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val open = if (startProgress > 0.5f) progress > 0.6f else progress > 0.4f
                settle(open)
            }
        }
        return true
    }
}

/** Lo que el menú lateral necesita de la pantalla principal. */
internal interface DrawerHost {
    val currentCategoryId: String
    val mode: ViewMode
    fun onSelectCategory(id: String)
    fun onSelectAll()
    fun onSelectFavorites()

    /** Se renombró o eliminó una categoría: hay que recargar la pantalla (y el título). */
    fun onCategoriesChanged()
}

/**
 * Contenido del menú lateral, en un solo RecyclerView: encabezado (Todas las notas, Favoritos, Papelera, título de
 * Categorías), la lista dinámica de categorías y el pie "Nueva categoría".
 * Toque largo en una categoría: Renombrar y Eliminar (la de por defecto no se elimina).
 * Reordenar: toque largo + arrastrar (como en la biblioteca); si se suelta sin mover, abre el menú de la categoría.
 * "Mis notas" (la categoría por defecto) siempre es la primera: no se arrastra ni se puede soltar sobre ella.
 */
internal class DrawerPanel(
    private val a: Activity,
    private val drawer: SideDrawerLayout,
    private val host: DrawerHost
) {
    private companion object {
        const val T_HEADER = 0
        const val T_CAT = 1
        const val T_FOOTER = 2
    }

    private val cats = ArrayList<Category>()
    private val rv = RecyclerView(a)
    private val adapter = DrawerAdapter()
    private lateinit var touch: ItemTouchHelper
    private var moved = false
    private var dragging = false
    private var dragged: Category? = null
    private var dragBg: Drawable? = null

    val view: View get() = rv

    private fun dp(v: Number) = UiKit.dp(a, v)

    init {
        val bg = GradientDrawable()
        bg.setColor(a.getColor(R.color.sf2))
        val r = dp(18).toFloat()
        bg.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        rv.background = bg
        rv.layoutManager = LinearLayoutManager(a)
        rv.adapter = adapter
        rv.setPadding(0, dp(16), 0, dp(24))
        rv.clipToPadding = false
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        touch = ItemTouchHelper(dragCallback())
        touch.attachToRecyclerView(rv)
        refresh()
    }

    /** Vuelve a construir la lista (categorías, selección actual). */
    fun refresh() {
        cats.clear()
        cats.addAll(CategoryStore.all(a))
        adapter.notifyDataSetChanged()
    }

    // ---------- Lista ----------

    private inner class DrawerAdapter : RecyclerView.Adapter<DrawerVH>() {
        override fun getItemCount(): Int = cats.size + 2

        override fun getItemViewType(position: Int): Int = when (position) {
            0 -> T_HEADER
            cats.size + 1 -> T_FOOTER
            else -> T_CAT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DrawerVH {
            val f = FrameLayout(a)
            f.clipChildren = false
            f.clipToPadding = false
            f.layoutParams = RecyclerView.LayoutParams(-1, -2)
            return DrawerVH(f)
        }

        override fun onBindViewHolder(h: DrawerVH, position: Int) {
            val f = h.itemView as FrameLayout
            f.removeAllViews()
            when (getItemViewType(position)) {
                T_HEADER -> f.addView(buildHeader(), FrameLayout.LayoutParams(-1, -2))
                T_FOOTER -> {
                    f.setPadding(dp(10), dp(2), dp(10), dp(2))
                    f.addView(
                        plainItem(Icons.PLUS, "Nueva categoría", a.getColor(R.color.ink), false) { newCategory() },
                        FrameLayout.LayoutParams(-1, dp(56))
                    )
                }
                else -> {
                    f.setPadding(dp(10), dp(2), dp(10), dp(2))
                    f.addView(buildCategoryRow(h, cats[position - 1]), FrameLayout.LayoutParams(-1, dp(56)))
                }
            }
        }
    }

    private fun buildHeader(): View {
        val ink = a.getColor(R.color.ink)
        val hi = ThemeUtils.accentHi(a)
        val mode = host.mode
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL

        fun add(v: View) {
            val lp = LinearLayout.LayoutParams(-1, dp(56))
            lp.setMargins(dp(10), dp(2), dp(10), dp(2))
            col.addView(v, lp)
        }

        val title = UiKit.text(a, "AloNotes", 26, ink)
        title.setPadding(dp(24), dp(14), dp(24), dp(14))
        col.addView(title)

        val allSel = mode == ViewMode.MODO_TODAS_LAS_NOTAS
        add(plainItem(Icons.NOTES, "Todas las notas", if (allSel) hi else ink, allSel) { host.onSelectAll() })
        col.addView(View(a), LinearLayout.LayoutParams(-1, dp(12)))
        val favSel = mode == ViewMode.MODO_FAVORITOS
        add(plainItem(Icons.STAR5, "Favoritos", if (favSel) hi else ink, favSel) { host.onSelectFavorites() })
        add(plainItem(Icons.TRASH, "Papelera", ink, false) { UiKit.soon(a) })

        val line = View(a)
        line.setBackgroundColor(a.getColor(R.color.ln2))
        val ll = LinearLayout.LayoutParams(-1, dp(1))
        ll.setMargins(dp(20), dp(18), dp(20), dp(10))
        col.addView(line, ll)

        // Encabezado "Categorías" con flecha
        val head = LinearLayout(a)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(20), 0, dp(20), 0)
        val ht = UiKit.text(a, "Categorías", 16, ink)
        ht.setTypeface(ht.typeface, Typeface.BOLD)
        head.addView(ht, LinearLayout.LayoutParams(0, -2, 1f))
        val ch = ImageView(a)
        ch.setImageDrawable(Icons(Icons.CHEV, ink, false))
        head.addView(ch, UiKit.box(a, 22))
        UiKit.ripple(a, head, 12, 0)
        head.setOnClickListener {
            drawer.close()
            UiKit.soon(a)
        }
        col.addView(head, LinearLayout.LayoutParams(-1, dp(52)))
        return col
    }

    /** Fila simple del menú (ícono, texto). Al tocarla se cierra el menú y se ejecuta [onClick]. */
    private fun plainItem(icon: Int, label: String, iconColor: Int, selected: Boolean, onClick: () -> Unit): LinearLayout {
        val r = LinearLayout(a)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(20), 0, dp(20), 0)
        val iv = ImageView(a)
        iv.setImageDrawable(Icons(icon, iconColor, false))
        r.addView(iv, UiKit.box(a, 24))
        val t = UiKit.text(a, label, 17, a.getColor(R.color.ink))
        t.setSingleLine(true)
        t.ellipsize = TextUtils.TruncateAt.END
        val tp = LinearLayout.LayoutParams(0, -2, 1f)
        tp.leftMargin = dp(18)
        r.addView(t, tp)
        if (selected) r.background = UiKit.rect(a, ThemeUtils.accentBg(a), 28)
        else UiKit.ripple(a, r, 28, 0)
        r.setOnClickListener {
            drawer.close()
            onClick()
        }
        return r
    }

    /** Fila de una categoría: tocar la abre; toque largo la arrastra (salvo la por defecto, que abre su menú). */
    private fun buildCategoryRow(h: DrawerVH, cat: Category): View {
        val ink = a.getColor(R.color.ink)
        val hi = ThemeUtils.accentHi(a)
        val sel = host.mode == ViewMode.MODO_CARPETAS && cat.id == host.currentCategoryId
        val r = LinearLayout(a)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, 0, dp(20), 0)
        val zone = FrameLayout(a)
        val iv = ImageView(a)
        iv.setImageDrawable(Icons(Icons.FOLDER, if (sel) hi else ink, false))
        val ip = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL or Gravity.START)
        ip.leftMargin = dp(20)
        zone.addView(iv, ip)
        r.addView(zone, LinearLayout.LayoutParams(dp(52), -1))
        val t = UiKit.text(a, cat.nombre, 17, ink)
        t.setSingleLine(true)
        t.ellipsize = TextUtils.TruncateAt.END
        val tp = LinearLayout.LayoutParams(0, -2, 1f)
        tp.leftMargin = dp(10)
        r.addView(t, tp)
        if (sel) r.background = UiKit.rect(a, ThemeUtils.accentBg(a), 28)
        else UiKit.ripple(a, r, 28, 0)
        r.setOnClickListener {
            drawer.close()
            host.onSelectCategory(cat.id)
        }
        r.setOnLongClickListener {
            if (cat.esPorDefecto) {
                categoryOptions(cat)
            } else {
                // Como en la biblioteca: empieza el arrastre; si se suelta sin mover, se abre el menú (ver clearView).
                moved = false
                dragging = true
                dragged = cat
                touch.startDrag(h)
            }
            true
        }
        return r
    }

    // ---------- Reordenar (ItemTouchHelper) ----------

    /** Posición de la categoría en la lista de datos (0 = "Mis notas"), o -1 si la fila no es una categoría. */
    private fun catIndex(vh: RecyclerView.ViewHolder): Int {
        if (vh.itemViewType != T_CAT) return -1
        val p = vh.bindingAdapterPosition
        return if (p == RecyclerView.NO_POSITION) -1 else p - 1
    }

    private fun dragCallback() = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled(): Boolean = false
        override fun isItemViewSwipeEnabled(): Boolean = false

        // Solo se pueden arrastrar las categorías que no son la primera ("Mis notas", por defecto).
        override fun getDragDirs(r: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (catIndex(vh) > 0) super.getDragDirs(r, vh) else 0

        override fun canDropOver(r: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
            catIndex(target) > 0

        override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = catIndex(vh)
            val to = catIndex(target)
            // La posición 0 es de "Mis notas": ni sale de ahí ni nada se coloca encima.
            if (from <= 0 || to <= 0) return false
            cats.add(to, cats.removeAt(from))
            adapter.notifyItemMoved(vh.bindingAdapterPosition, target.bindingAdapterPosition)
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state != ItemTouchHelper.ACTION_STATE_DRAG) return
            // Fila "levantada": fondo propio y sombra.
            val row = (vh?.itemView as? FrameLayout)?.getChildAt(0) ?: return
            dragBg = row.background
            row.background = UiKit.rect(a, a.getColor(R.color.sf3), 28)
            row.elevation = dp(12).toFloat()
        }

        override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(r, vh)
            val row = (vh.itemView as? FrameLayout)?.getChildAt(0)
            if (row != null) {
                row.elevation = 0f
                val bg = dragBg
                if (bg != null) row.background = bg
            }
            dragBg = null
            if (!dragging) return
            dragging = false
            val c = dragged
            dragged = null
            if (moved) {
                // Al soltar: se guarda el nuevo orden.
                moved = false
                CategoryStore.reorder(a, cats.map { it.id })
            } else if (c != null) {
                // Toque largo sin mover: opciones de la categoría.
                categoryOptions(c)
            }
        }
    }

    // ---------- Acciones ----------

    private fun newCategory() {
        UiKit.askName(a, "Nueva categoría", "") { n ->
            val cat = CategoryStore.add(a, n)
            host.onSelectCategory(cat.id)
        }
    }

    private fun categoryOptions(cat: Category) {
        val ink = a.getColor(R.color.ink)
        val l = UiKit.vbox(a, 12)
        val title = UiKit.text(a, cat.nombre, 17, ink)
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        title.setPadding(dp(14), dp(8), dp(14), dp(8))
        l.addView(title)
        val ref = DlgRef()
        l.addView(UiKit.sheetRow(a, Icons.PENCIL, "Renombrar", ink, ref) {
            UiKit.askName(a, "Renombrar categoría", cat.nombre) { n ->
                CategoryStore.rename(a, cat.id, n)
                host.onCategoriesChanged()
            }
        })
        if (!cat.esPorDefecto) {
            l.addView(UiKit.sheetRow(a, Icons.CLOSE, "Eliminar", a.getColor(R.color.danger), ref) {
                val defName = CategoryStore.get(a, CategoryStore.DEFAULT_ID)?.nombre ?: CategoryStore.DEFAULT_NAME
                val msg = "¿Eliminar la categoría «" + cat.nombre + "»? Sus carpetas y notas pasarán a «" + defName + "»."
                UiKit.confirm(a, msg, "Eliminar") {
                    NoteStore.reassignCategory(a, cat.id, CategoryStore.DEFAULT_ID)
                    CategoryStore.remove(a, cat.id)
                    host.onCategoriesChanged()
                }
            })
        }
        ref.d = UiKit.show(a, l, true)
    }
}

private class DrawerVH(v: View) : RecyclerView.ViewHolder(v)
