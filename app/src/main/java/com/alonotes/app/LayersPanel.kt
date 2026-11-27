package com.alonotes.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Toast
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * Panel flotante de capas (Fase 6, solo visual). Se arrastra por su barra superior y tiene dos
 * estados: expandido (lista de capas, barra de opacidad) y minimizado (cuadro con el número de
 * capas; se arrastra igual y un toque lo expande). Vive dentro del área de hojas del editor.
 * La lista y la opacidad todavía no cambian datos: las capas reales llegan en la siguiente fase.
 */
internal class LayersPanel(private val a: Activity) : FrameLayout(a) {
    private companion object {
        const val MINI = 60
        const val FULL_W = 320
        const val FULL_H = 450
        const val EDGE = 22
    }

    var opened = false
        private set
    var minimized = false
        private set
    var onClose: (() -> Unit)? = null

    /** Se llama al agregar o eliminar una capa; el valor indica si cambiaron trazos (hay que redibujar y guardar). */
    var onLayersChanged: ((Boolean) -> Unit)? = null
    private var model: PageLayers? = null

    /** Se llama al completarse una acción sobre las capas, con la foto de la hoja de ANTES (historial de deshacer). */
    var onRecord: ((PageSnap) -> Unit)? = null
    private var dragPre: PageSnap? = null

    /** Se llama al mostrar u ocultar una capa, o al cambiar su modo de fusión o su bloqueo (hay que redibujar la hoja y guardar las capas). */
    var onVisibilityChanged: (() -> Unit)? = null

    /** Id de la nota abierta: las imágenes de una capa pegada se copian a su carpeta de imágenes. */
    var docId = ""

    /** Se activa o desactiva el modo "Mover capa" (el visor mueve toda la capa seleccionada al arrastrar). */
    var onMoveMode: ((Boolean) -> Unit)? = null
    private var moveMode = false
    private var moveBtn: View? = null

    private val full = LinearLayout(a)
    private val mini = FrameLayout(a)
    private val badge = UiKit.text(a, "1", 24, ThemeUtils.accentHi(a))
    private val rv = RecyclerView(a)
    private val adapter = LayerAdapter()
    private lateinit var touch: ItemTouchHelper
    private var moved = false
    private val pct = UiKit.text(a, "100%", 15, a.getColor(R.color.ink))
    private val slop = ViewConfiguration.get(a).scaledTouchSlop
    private var placed = false
    private var dRx = 0f
    private var dRy = 0f
    private var dragged = false

    /** Número de capas de la hoja actual. */
    val layerCount: Int get() = model?.layers?.size ?: 1

    /** Muestra las capas de la hoja actual (null si no hay hojas). */
    fun bind(m: PageLayers?) {
        model = m
        fillList()
    }

    private fun dp(v: Number) = UiKit.dp(a, v)

    init {
        background = UiKit.rect(a, a.getColor(R.color.sheet), 16, a.getColor(R.color.ln2))
        clipToOutline = true
        elevation = dp(10).toFloat()
        visibility = GONE
        isClickable = true // consume los toques: la hoja de debajo no debe recibirlos
        buildFull()
        buildMini()
        addView(full, FrameLayout.LayoutParams(-1, -1))
        addView(mini, FrameLayout.LayoutParams(-1, -1))
        fillList()
        mini.visibility = GONE
    }

    // ---------- Construcción ----------

    private fun buildFull() {
        val ink = a.getColor(R.color.ink)
        val hi = ThemeUtils.accentHi(a)
        full.orientation = LinearLayout.VERTICAL

        // Barra superior: arrastrar, minimizar y cerrar
        val bar = LinearLayout(a)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(a.getColor(R.color.sf3))
        bar.setPadding(dp(14), 0, dp(4), 0)
        bar.addView(UiKit.text(a, "Capas", 15, ink), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(UiKit.iconBtn(a, Icons.GRIP, a.getColor(R.color.mut), 28, 22), UiKit.box(a, 28))
        bar.addView(UiKit.iconBtn(a, Icons.MINIMIZE, hi, 40, 22) { setMinimized(true) }, UiKit.box(a, 40))
        bar.addView(UiKit.iconBtn(a, Icons.CLOSE, hi, 40, 22) { close() }, UiKit.box(a, 40))
        attachDrag(bar, null)
        full.addView(bar, LinearLayout.LayoutParams(-1, dp(44)))

        // Cuerpo: barra izquierda | columna central | barra derecha
        val body = LinearLayout(a)
        body.orientation = LinearLayout.HORIZONTAL
        body.addView(sideBar(intArrayOf(Icons.PLUS, Icons.COPY_PLUS, Icons.PASTE, Icons.ARROW_DOWN)), LinearLayout.LayoutParams(dp(40), -1))
        body.addView(vline(), LinearLayout.LayoutParams(dp(1), -1))
        body.addView(buildCenter(), LinearLayout.LayoutParams(0, -1, 1f))
        body.addView(vline(), LinearLayout.LayoutParams(dp(1), -1))
        body.addView(
            sideBar(intArrayOf(Icons.CHECKER, Icons.MOVE, Icons.TRASH, Icons.DOTS_V)),
            LinearLayout.LayoutParams(dp(40), -1)
        )
        full.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun vline(): View {
        val v = View(a)
        v.setBackgroundColor(a.getColor(R.color.ln))
        return v
    }

    /** Barra vertical de 40 dp con botones de herramienta (todos "Próximamente"). */
    private fun sideBar(icons: IntArray): View {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val ink = a.getColor(R.color.ink)
        for (t in icons) {
            val b = UiKit.iconBtn(a, t, ink, 40, 22)
            b.setOnClickListener {
                when (t) {
                    Icons.PLUS -> addLayer() // agregar capa
                    Icons.COPY_PLUS -> copyLayer() // copiar capa
                    Icons.PASTE -> pasteLayer() // pegar capa
                    Icons.ARROW_DOWN -> mergeDown() // incorporar la capa a la de abajo
                    Icons.CHECKER -> clearLayer() // limpiar capa
                    Icons.MOVE -> setMoveMode(!moveMode) // modo mover capa
                    Icons.TRASH -> deleteLayer() // eliminar capa
                    Icons.DOTS_V -> showMenu(b) // modos de fusión y bloqueo
                    else -> soon()
                }
            }
            if (t == Icons.MOVE) moveBtn = b
            col.addView(b, UiKit.box(a, 40))
        }
        val sv = ScrollView(a)
        sv.isVerticalScrollBarEnabled = false
        sv.setBackgroundColor(a.getColor(R.color.sf))
        sv.addView(col, ViewGroup.LayoutParams(-1, -2))
        return sv
    }

    /** Columna central: lista de capas, modo de fusión y opacidad. */
    private fun buildCenter(): View {
        val center = LinearLayout(a)
        center.orientation = LinearLayout.VERTICAL

        // Lista de capas
        rv.layoutManager = LinearLayoutManager(a)
        rv.adapter = adapter
        rv.setPadding(dp(6), dp(8), dp(6), dp(8))
        rv.clipToPadding = false
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        touch = ItemTouchHelper(dragCallback())
        touch.attachToRecyclerView(rv)
        center.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))

        // Opacidad (solo visual por ahora). Encima de ella estaban los íconos Recorte y Bloqueo alfa: se quitaron con su franja.
        val line2 = View(a)
        line2.setBackgroundColor(a.getColor(R.color.ln))
        center.addView(line2, LinearLayout.LayoutParams(-1, dp(1)))
        val foot = LinearLayout(a)
        foot.gravity = Gravity.CENTER_VERTICAL
        foot.setBackgroundColor(a.getColor(R.color.sf))
        foot.setPadding(dp(10), 0, dp(10), 0)
        foot.addView(pct, LinearLayout.LayoutParams(dp(46), -2))
        val seek = SeekBar(a)
        seek.max = 100
        seek.progress = 100
        seek.progressTintList = ColorStateList.valueOf(ThemeUtils.accent(a))
        seek.thumbTintList = ColorStateList.valueOf(ThemeUtils.accent(a))
        seek.progressBackgroundTintList = ColorStateList.valueOf(a.getColor(R.color.ln2))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                pct.text = "$p%"
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        foot.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        center.addView(foot, LinearLayout.LayoutParams(-1, dp(52)))
        return center
    }

    private fun buildMini() {
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        mini.addView(badge, lp)
        attachDrag(mini) { setMinimized(false) }
    }

    private fun soon() = UiKit.soon(a)

    private fun fillList() {
        badge.text = layerCount.toString()
        adapter.notifyDataSetChanged()
    }

    private fun select(idx: Int) {
        val m = model ?: return
        if (idx !in m.layers.indices) return
        m.selected = idx
        fillList()
        onLayersChanged?.invoke(false)
    }

    // ---------- Lista (RecyclerView) y reordenar con el ícono ≡ ----------

    /**
     * La lista se muestra al revés: la capa más alta del modelo (la que se dibuja encima) va arriba. El nombre que se ve es solo el número
     * de su posición: la de más abajo (índice 0, la primera en dibujarse) es la 1 y la de arriba la más alta. Se calcula siempre a partir
     * del índice en el modelo, así que al crear, borrar o reordenar capas la numeración queda consecutiva sin saltos.
     */
    private inner class LayerAdapter : RecyclerView.Adapter<LayerVH>() {
        override fun getItemCount(): Int = model?.layers?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayerVH {
            val f = FrameLayout(a)
            f.layoutParams = RecyclerView.LayoutParams(-1, dp(56))
            return LayerVH(f)
        }

        override fun onBindViewHolder(h: LayerVH, position: Int) {
            val m = model ?: return
            val idx = m.layers.size - 1 - position
            val ly = m.layers[idx]
            val f = h.itemView as FrameLayout
            f.removeAllViews()
            f.alpha = 1f
            f.addView(
                row(ly, idx + 1, idx == m.selected, { idxOf(h).let { i -> if (i >= 0) select(i) } }, {
                    dragPre = model?.snapshot() // la hoja antes de reordenar
                    touch.startDrag(h)
                }),
                FrameLayout.LayoutParams(-1, dp(52))
            )
        }
    }

    /** Índice de capa (en el modelo) de la fila [h], o -1 si ya no está en la lista. */
    private fun idxOf(h: LayerVH): Int {
        val m = model ?: return -1
        val p = h.bindingAdapterPosition
        return if (p == RecyclerView.NO_POSITION) -1 else m.layers.size - 1 - p
    }

    /** Arrastrar arriba/abajo. Solo empieza al tocar el ≡ (startDrag manual), no con pulsación larga en la fila. */
    private fun dragCallback() = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun onMove(r: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val m = model ?: return false
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            m.move(m.layers.size - 1 - from, m.layers.size - 1 - to)
            adapter.notifyItemMoved(from, to)
            refreshNumbers() // los números siguen al orden mientras se arrastra
            moved = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.alpha = 0.85f
        }

        override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(r, vh)
            vh.itemView.alpha = 1f
            // Al soltar: redibuja (orden de apilado) y guarda el nuevo orden.
            if (moved) {
                moved = false
                val pre = dragPre
                if (pre != null) onRecord?.invoke(pre)
                onLayersChanged?.invoke(true)
            }
            dragPre = null
        }
    }

    private fun addLayer() {
        val m = model ?: return
        if (!m.canAdd) {
            Toast.makeText(a, "Máximo " + PageLayers.MAX + " capas", Toast.LENGTH_SHORT).show()
            return
        }
        val pre = m.snapshot()
        m.add()
        onRecord?.invoke(pre)
        fillList()
        onLayersChanged?.invoke(false)
    }

    private fun deleteLayer() {
        val m = model ?: return
        if (m.layers.size <= 1) {
            Toast.makeText(a, "Debe quedar al menos una capa", Toast.LENGTH_SHORT).show()
            return
        }
        val ly = m.layers[m.selected]
        if (ly.isLocked) {
            Toast.makeText(a, "Desbloquea la capa para eliminarla", Toast.LENGTH_SHORT).show()
            return
        }
        fun go() {
            val pre = m.snapshot()
            m.removeSelected()
            onRecord?.invoke(pre)
            fillList()
            onLayersChanged?.invoke(true)
        }
        if (ly.canvas.isEmpty()) go()
        else UiKit.confirm(a, "¿Eliminar la capa " + (m.selected + 1) + " y sus trazos?", "Eliminar") { go() }
    }

    /** Copia la capa seleccionada (con todos sus objetos) al portapapeles de capas. */
    private fun copyLayer() {
        val m = model ?: return
        LayerClipboard.copy(a, m.layers[m.selected])
        Toast.makeText(a, "Capa copiada", Toast.LENGTH_SHORT).show()
    }

    /** Pega la capa copiada como una capa nueva justo encima de la seleccionada, con objetos nuevos (ids nuevos). */
    private fun pasteLayer() {
        val m = model ?: return
        if (LayerClipboard.isEmpty) {
            Toast.makeText(a, "No hay una capa copiada", Toast.LENGTH_SHORT).show()
            return
        }
        if (!m.canAdd) {
            Toast.makeText(a, "Máximo " + PageLayers.MAX + " capas", Toast.LENGTH_SHORT).show()
            return
        }
        val pre = m.snapshot()
        val l = m.add()
        if (!LayerClipboard.paste(a, docId, l)) {
            m.removeSelected()
            return
        }
        onRecord?.invoke(pre)
        fillList()
        onLayersChanged?.invoke(true)
    }

    /** Incorporar la siguiente capa: todo lo de la capa seleccionada pasa a la de abajo y la seleccionada se elimina. */
    private fun mergeDown() {
        val m = model ?: return
        if (m.selected <= 0) {
            Toast.makeText(a, "No hay una capa debajo", Toast.LENGTH_SHORT).show()
            return
        }
        if (m.layers[m.selected].isLocked || m.layers[m.selected - 1].isLocked) {
            Toast.makeText(a, "Hay una capa bloqueada", Toast.LENGTH_SHORT).show()
            return
        }
        val pre = m.snapshot()
        m.mergeDown()
        onRecord?.invoke(pre)
        fillList()
        onLayersChanged?.invoke(true)
    }

    /** Limpiar la capa seleccionada: se borran sus trazos, imágenes y textos y la capa queda vacía. */
    private fun clearLayer() {
        val m = model ?: return
        val ly = m.layers[m.selected]
        if (ly.isLocked) {
            Toast.makeText(a, "La capa está bloqueada", Toast.LENGTH_SHORT).show()
            return
        }
        if (ly.canvas.isEmpty() && ly.items.isEmpty() && ly.texts.isEmpty()) {
            Toast.makeText(a, "La capa ya está vacía", Toast.LENGTH_SHORT).show()
            return
        }
        UiKit.confirm(a, "¿Limpiar la capa " + (m.selected + 1) + "? Se borrarán sus trazos, imágenes y textos.", "Limpiar") {
            val pre = m.snapshot()
            m.clearSelected()
            onRecord?.invoke(pre)
            fillList()
            onLayersChanged?.invoke(true)
        }
    }

    /** Activa o desactiva el modo "Mover capa" (el botón queda resaltado mientras está activo). */
    fun setMoveMode(on: Boolean) {
        if (moveMode == on) return
        moveMode = on
        moveBtn?.let { UiKit.ripple(a, it, 12, if (on) ThemeUtils.accentBg(a) else 0) }
        onMoveMode?.invoke(on)
        if (on) Toast.makeText(a, "Arrastra sobre la hoja para mover toda la capa", Toast.LENGTH_SHORT).show()
    }

    /** Menú ⋮: modo de fusión de la capa seleccionada (Normal, Multiplicar, Superponer, Trama) y un interruptor para bloquearla. */
    private fun showMenu(anchor: View) {
        val m = model ?: return
        val ly = m.layers[m.selected]
        val ink = a.getColor(R.color.ink)
        val mut = a.getColor(R.color.mut)
        val acc = ThemeUtils.accentHi(a)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(6), dp(8), dp(6), dp(8))
        col.background = UiKit.rect(a, a.getColor(R.color.sf2), 16, a.getColor(R.color.ln2))
        var pw: PopupWindow? = null
        val head = UiKit.text(a, "Modo de fusión", 13, mut)
        head.setPadding(dp(12), 0, dp(12), dp(4))
        col.addView(head)
        for (i in LayerBlend.names.indices) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(12), 0, dp(10), 0)
            r.addView(UiKit.text(a, LayerBlend.names[i], 16, ink), LinearLayout.LayoutParams(0, -2, 1f))
            if (ly.blendMode == i) {
                val ck = ImageView(a)
                ck.setImageDrawable(Icons(Icons.CHECK, acc, false))
                r.addView(ck, UiKit.box(a, 24))
            }
            UiKit.ripple(a, r, 10, if (ly.blendMode == i) ThemeUtils.accentBg(a) else 0)
            r.setOnClickListener {
                pw?.dismiss()
                setBlend(ly, i)
            }
            col.addView(r, LinearLayout.LayoutParams(dp(210), dp(44)))
        }
        val line = View(a)
        line.setBackgroundColor(a.getColor(R.color.ln))
        val lpLine = LinearLayout.LayoutParams(-1, dp(1))
        lpLine.topMargin = dp(6)
        lpLine.bottomMargin = dp(6)
        col.addView(line, lpLine)
        val lr = LinearLayout(a)
        lr.gravity = Gravity.CENTER_VERTICAL
        lr.setPadding(dp(12), 0, dp(10), 0)
        lr.addView(UiKit.text(a, "Bloquear capa", 16, ink), LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(a)
        sw.isChecked = ly.isLocked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(acc, mut))
        sw.trackTintList = ColorStateList(states, intArrayOf(ThemeUtils.accentBg(a), a.getColor(R.color.ln2)))
        sw.setOnCheckedChangeListener { _, on -> setLocked(ly, on) }
        lr.addView(sw)
        UiKit.ripple(a, lr, 10, 0)
        lr.setOnClickListener { sw.isChecked = !sw.isChecked }
        col.addView(lr, LinearLayout.LayoutParams(dp(210), dp(48)))
        val p = PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        p.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        p.elevation = dp(8).toFloat()
        pw = p
        p.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    /** Cambia el modo de fusión de la capa (un paso del historial); se redibuja la hoja y se guardan las capas. */
    private fun setBlend(ly: Layer, mode: Int) {
        if (ly.blendMode == mode) return
        val pre = model?.snapshot()
        ly.blendMode = mode
        if (pre != null) onRecord?.invoke(pre)
        fillList()
        onVisibilityChanged?.invoke()
    }

    /** Bloquea o desbloquea la capa (un paso del historial): en una capa bloqueada no se dibuja, borra ni selecciona. */
    private fun setLocked(ly: Layer, on: Boolean) {
        if (ly.isLocked == on) return
        val pre = model?.snapshot()
        ly.isLocked = on
        if (pre != null) onRecord?.invoke(pre)
        fillList()
        onVisibilityChanged?.invoke() // refreshPage suelta la selección de objetos de esa capa
    }

    private fun toggleVisible(ly: Layer) {
        val pre = model?.snapshot()
        ly.isVisible = !ly.isVisible
        if (pre != null) onRecord?.invoke(pre)
        fillList()
        onVisibilityChanged?.invoke()
    }

    /** Número que se muestra de la capa [ly]: su posición en el modelo + 1 (la de abajo es la 1). */
    private fun numberOf(ly: Layer): Int {
        val m = model ?: return 0
        for (i in m.layers.indices) if (m.layers[i] === ly) return i + 1
        return 0
    }

    /** Vuelve a poner el número de cada fila visible según el orden actual del modelo (sin rehacer las filas). */
    private fun refreshNumbers() {
        for (i in 0 until rv.childCount) {
            val f = rv.getChildAt(i) as? FrameLayout ?: continue
            val tv = f.getChildAt(0)?.tag as? TextView ?: continue
            val ly = tv.tag as? Layer ?: continue
            tv.text = numberOf(ly).toString()
        }
    }

    private fun row(ly: Layer, number: Int, selected: Boolean, onSelect: () -> Unit, onHandle: () -> Unit): View {
        val ink = a.getColor(R.color.ink)
        val r = LinearLayout(a)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(6), 0, dp(2), 0)
        if (selected) r.background = UiKit.rect(a, ThemeUtils.accentBg(a), 10)
        else UiKit.ripple(a, r, 10, 0)
        // Miniatura con fondo de tablero (transparencia)
        r.addView(Checker(a), UiKit.box(a, 32))
        // Ojo: alterna la visibilidad de la capa (apagado y gris cuando está oculta).
        val eye = if (ly.isVisible) UiKit.iconBtn(a, Icons.EYE, ThemeUtils.accentHi(a), 36, 22) { toggleVisible(ly) }
        else UiKit.iconBtn(a, Icons.EYE_OFF, a.getColor(R.color.mut), 36, 22) { toggleVisible(ly) }
        val ep = UiKit.box(a, 36)
        ep.leftMargin = dp(4)
        r.addView(eye, ep)
        val tp = LinearLayout.LayoutParams(0, -2, 1f)
        tp.leftMargin = dp(4)
        val nameTv = UiKit.text(a, number.toString(), 15, if (ly.isVisible) ink else a.getColor(R.color.mut))
        nameTv.tag = ly // para refreshNumbers
        r.tag = nameTv
        r.addView(nameTv, tp)
        // Candado: la capa está bloqueada
        if (ly.isLocked) {
            val lk = ImageView(a)
            lk.setImageDrawable(Icons(Icons.LOCK, a.getColor(R.color.mut), false))
            r.addView(lk, LinearLayout.LayoutParams(dp(20), dp(20)))
        }
        // Asa de arrastre (≡): al tocarla empieza el arrastre de esta fila.
        val handle = UiKit.iconBtn(a, Icons.MENU, ink, 36, 20)
        handle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onHandle()
            false
        }
        r.addView(handle, UiKit.box(a, 36))
        r.setOnClickListener { onSelect() }
        return r
    }

    // ---------- Arrastre ----------

    /** Arrastra el panel desde [v]. Si no se movió (toque simple), ejecuta [tap]. */
    private fun attachDrag(v: View, tap: (() -> Unit)?) {
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dRx = e.rawX
                    dRy = e.rawY
                    dragged = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dRx
                    val dy = e.rawY - dRy
                    if (!dragged && hypot(dx, dy) > slop) dragged = true
                    if (dragged) {
                        moveTo(x + dx, y + dy)
                        dRx = e.rawX
                        dRy = e.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) tap?.invoke()
                }
            }
            true
        }
    }

    // ---------- Tamaño y posición ----------

    private fun pw(): Int = (parent as? View)?.width ?: 0
    private fun ph(): Int = (parent as? View)?.height ?: 0

    private fun targetW(): Int =
        if (minimized) dp(MINI) else minOf(dp(FULL_W), pw() - dp(24)).coerceAtLeast(dp(MINI))

    private fun targetH(): Int =
        if (minimized) dp(MINI) else minOf(dp(FULL_H), ph() - dp(24)).coerceAtLeast(dp(MINI))

    private fun moveTo(nx: Float, ny: Float) {
        val maxX = (pw() - targetW()).toFloat().coerceAtLeast(0f)
        val maxY = (ph() - targetH()).toFloat().coerceAtLeast(0f)
        x = nx.coerceIn(0f, maxX)
        y = ny.coerceIn(0f, maxY)
    }

    /** Aplica el estado (tamaño y vista) y mantiene el panel dentro del área. */
    private fun applyState() {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val tw = targetW()
        val th = targetH()
        // Solo se toca el layout si cambia: así no se encadenan pasadas de layout.
        if (lp.width != tw || lp.height != th) {
            lp.width = tw
            lp.height = th
            layoutParams = lp
        }
        full.visibility = if (minimized) GONE else VISIBLE
        mini.visibility = if (minimized) VISIBLE else GONE
        if (!placed && pw() > 0 && ph() > 0) {
            // Primera vez: abajo a la derecha, donde está el ícono de capas.
            x = (pw() - tw - dp(EDGE)).toFloat()
            y = (ph() - th - dp(EDGE)).toFloat()
            placed = true
        }
        moveTo(x, y)
    }

    private fun setMinimized(m: Boolean) {
        if (m == minimized) return
        val oldW = targetW()
        minimized = m
        // Se conserva el borde derecho: el botón de minimizar queda bajo el dedo.
        x = x + oldW - targetW()
        applyState()
    }

    /** Vuelve a ajustar el panel si cambió el tamaño del área (rotación, teclado). */
    fun refit() {
        if (opened) applyState()
    }

    fun open() {
        opened = true
        minimized = false
        applyState()
    }

    fun close() {
        setMoveMode(false)
        opened = false
        onClose?.invoke()
    }

    fun save(o: Bundle) {
        o.putBoolean("lp_open", opened)
        o.putBoolean("lp_min", minimized)
        o.putBoolean("lp_placed", placed)
        o.putFloat("lp_x", x)
        o.putFloat("lp_y", y)
    }

    fun restore(b: Bundle) {
        opened = b.getBoolean("lp_open")
        minimized = b.getBoolean("lp_min")
        full.visibility = if (minimized) GONE else VISIBLE
        mini.visibility = if (minimized) VISIBLE else GONE
        if (b.getBoolean("lp_placed")) {
            x = b.getFloat("lp_x")
            y = b.getFloat("lp_y")
            placed = true
        }
    }
}

/** Cuadro pequeño con fondo de tablero de ajedrez, para simular transparencia. */
private class Checker(private val ctx: Context) : View(ctx) {
    private val paint = Paint()
    private val c1 = ctx.getColor(R.color.mut)
    private val c2 = ctx.getColor(R.color.ln2)

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, UiKit.dp(ctx, 6).toFloat())
            }
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val sw = width / n.toFloat()
        val sh = height / n.toFloat()
        for (i in 0 until n) {
            for (j in 0 until n) {
                paint.color = if ((i + j) % 2 == 0) c1 else c2
                canvas.drawRect(i * sw, j * sh, (i + 1) * sw, (j + 1) * sh, paint)
            }
        }
    }
}

private class LayerVH(v: View) : RecyclerView.ViewHolder(v)
