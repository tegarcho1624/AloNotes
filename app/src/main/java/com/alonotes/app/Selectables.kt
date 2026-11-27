package com.alonotes.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Bitmap.CompressFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Algo que se puede seleccionar y transformar en una hoja (hoy imágenes; más adelante textos y trazos).
 * Todo está en milímetros de la hoja, igual que los trazos, así no depende del zoom ni de la vista.
 */
internal interface SelectableItem {
    val id: String

    /** Rectángulo SIN rotar. Su centro es el pivote de la rotación. */
    val bounds: RectF

    /** Grados, sentido horario (como Canvas.rotate; el eje Y apunta hacia abajo). */
    var rotation: Float

    /** Tamaño actual respecto al original (informativo; el tamaño real es [bounds]). */
    val scale: Float

    /** Si es true, los tiradores de las esquinas conservan la proporción. */
    val lockAspect: Boolean get() = true

    /** Espejo en el eje X (izquierda-derecha), en los ejes propios del elemento (sigue valiendo si está girado). */
    var isFlippedH: Boolean

    /** Espejo en el eje Y (arriba-abajo), en los ejes propios del elemento. */
    var isFlippedV: Boolean

    /** 0 = transparente, 1 = opaco. */
    var opacity: Float

    /** Bloqueado: no se puede mover, girar ni redimensionar hasta desbloquearlo. */
    var isLocked: Boolean

    fun draw(c: Canvas, mmToPx: Float, invalidate: () -> Unit)

    /** ¿Está el punto (mm de la hoja) dentro del elemento, ya con su rotación? */
    fun hit(xMm: Float, yMm: Float): Boolean {
        val l = Xf.toLocal(bounds.centerX(), bounds.centerY(), rotation, xMm, yMm)
        return abs(l[0]) <= bounds.width() / 2f && abs(l[1]) <= bounds.height() / 2f
    }
}

/** Algo que puede llevar un enlace (imagen o trazo): web ([ImageItem.LINK_WEB]) o interno ([ImageItem.LINK_INTERNAL]). */
internal interface Linkable {
    var linkType: Int
    var linkData: String
}

/** Geometría de la transformación (funciones puras). Ángulos en grados, sentido horario, Y hacia abajo. */
internal object Xf {
    const val MIN_MM = 6f
    private const val MAX_MM = 3000f

    /** Gira el vector (x, y) [deg] grados: x' = x·cos − y·sin, y' = x·sin + y·cos. */
    fun rotate(x: Float, y: Float, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(x * c - y * s, x * s + y * c)
    }

    /** Punto local (relativo al centro, sin rotar) -> punto de la hoja. */
    fun toPage(cx: Float, cy: Float, deg: Float, lx: Float, ly: Float): FloatArray {
        val r = rotate(lx, ly, deg)
        return floatArrayOf(cx + r[0], cy + r[1])
    }

    /** Punto de la hoja -> punto local (relativo al centro, deshaciendo la rotación). */
    fun toLocal(cx: Float, cy: Float, deg: Float, px: Float, py: Float): FloatArray =
        rotate(px - cx, py - cy, -deg)

    /** Ángulo del vector (dx, dy) en grados, con Math.atan2. */
    fun angleDeg(dx: Float, dy: Float): Float = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

    /** Lleva un ángulo al intervalo (−180, 180]. */
    fun normalize(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * Redimensiona arrastrando el tirador (sx, sy) hasta el punto (px, py) de la hoja. sx y sy valen −1, 0 o 1:
     * (−1,−1) es la esquina superior izquierda, (0,−1) el borde superior, (1,0) el borde derecho, etc.
     * El punto OPUESTO al tirador (el ancla) no se mueve.
     *  - Esquina con proporción fija: f = (v·d) / (d·d), la proyección del puntero (relativo al ancla) sobre la
     *    diagonal original; el tamaño nuevo es el original × f.
     *  - Borde (o esquina libre): solo cambia el ancho (sx) y/o el alto (sy), medidos desde el ancla en el sistema local.
     *  - El centro nuevo es: ancla + R(rot)·(sx·w/2, sy·h/2).
     * [b0] y [rot] son el estado al empezar el gesto; el resultado se escribe en [out].
     */
    fun resize(
        b0: RectF, rot: Float, sx: Int, sy: Int, px: Float, py: Float, lockAspect: Boolean, out: RectF
    ) {
        val w0 = b0.width()
        val h0 = b0.height()
        val a = toPage(b0.centerX(), b0.centerY(), rot, -sx * w0 / 2f, -sy * h0 / 2f)
        val v = rotate(px - a[0], py - a[1], -rot)
        var w = w0
        var h = h0
        if (sx != 0 && sy != 0 && lockAspect) {
            val dx = sx * w0
            val dy = sy * h0
            val f = ((v[0] * dx + v[1] * dy) / (dx * dx + dy * dy))
                .coerceIn(max(MIN_MM / w0, MIN_MM / h0), MAX_MM / max(w0, h0))
            w = w0 * f
            h = h0 * f
        } else {
            if (sx != 0) w = (sx * v[0]).coerceIn(MIN_MM, MAX_MM)
            if (sy != 0) h = (sy * v[1]).coerceIn(MIN_MM, MAX_MM)
        }
        val c = toPage(a[0], a[1], rot, sx * w / 2f, sy * h / 2f)
        out.set(c[0] - w / 2f, c[1] - h / 2f, c[0] + w / 2f, c[1] + h / 2f)
    }
}

/** Imagen colocada en una hoja. El archivo está en filesDir/images/<idNota>/; el bitmap se carga al dibujarla. */
internal class ImageItem(
    override val id: String,
    /** Archivo de la imagen; cambia cuando se recorta (se guarda uno nuevo). */
    var file: File,
    override val bounds: RectF,
    override var rotation: Float,
    /** Ancho (mm) con el que se insertó (o se recortó), para calcular [scale]. */
    var baseW: Float,
    override var isFlippedH: Boolean = false,
    override var isFlippedV: Boolean = false,
    override var opacity: Float = 1f,
    /** Enlace de la imagen: [LINK_NONE], [LINK_WEB] (linkData = URL) o [LINK_INTERNAL] (linkData = "idNota|númeroDeHoja"). */
    override var linkType: Int = LINK_NONE,
    override var linkData: String = "",
    override var isLocked: Boolean = false
) : SelectableItem, Linkable {
    companion object {
        const val LINK_NONE = 0
        const val LINK_WEB = 1
        const val LINK_INTERNAL = 2
    }

    var bitmap: Bitmap? = null
    private var loading = false
    private var failed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    override val scale: Float get() = if (baseW > 0f) bounds.width() / baseW else 1f

    override fun draw(c: Canvas, mmToPx: Float, invalidate: () -> Unit) {
        val bm = bitmap
        if (bm != null) {
            drawBitmap(c, mmToPx, bm)
            return
        }
        // Mientras se carga: un recuadro gris.
        dst.set(bounds.left * mmToPx, bounds.top * mmToPx, bounds.right * mmToPx, bounds.bottom * mmToPx)
        val n = c.save()
        c.rotate(rotation, dst.centerX(), dst.centerY())
        paint.color = 0x33808080
        c.drawRect(dst, paint)
        c.restoreToCount(n)
        if (!loading && !failed) load(invalidate)
    }

    /**
     * Dibuja [bm] en su rectángulo con la rotación, el espejo (sobre el centro, en los ejes del propio elemento: primero
     * se gira y luego se refleja, así el espejo no depende del ángulo) y la opacidad. También lo usan las miniaturas.
     */
    fun drawBitmap(c: Canvas, mmToPx: Float, bm: Bitmap) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        val d = RectF(bounds.left * mmToPx, bounds.top * mmToPx, bounds.right * mmToPx, bounds.bottom * mmToPx)
        val n = c.save()
        c.rotate(rotation, d.centerX(), d.centerY())
        c.scale(if (isFlippedH) -1f else 1f, if (isFlippedV) -1f else 1f, d.centerX(), d.centerY())
        c.drawBitmap(bm, null, d, p)
        c.restoreToCount(n)
    }

    private fun load(invalidate: () -> Unit) {
        loading = true
        Thread {
            val b = ImageUtil.decodeFile(file.path, 2048)
            Handler(Looper.getMainLooper()).post {
                bitmap = b
                failed = b == null
                loading = false
                invalidate()
            }
        }.start()
    }
}

/**
 * Texto colocado en una hoja. Todo en mm de la hoja. El tamaño ([fontSize]) está en "unidades de texto": 1 unidad = [MM_PER_UNIT] mm
 * (tamaño 40 = letra de 8 mm). Se dibuja con un StaticLayout (salto de línea, alineado, espaciado de letras y altura de línea).
 *
 * El recuadro [bounds] (sin rotar; su centro es el pivote del giro) sigue al texto:
 *  - [isFixedWidth] = false: una sola línea; el recuadro crece o se encoge solo para adaptarse al texto.
 *  - [isFixedWidth] = true: el ancho del recuadro es el que se le dio y el texto salta de línea al llegar al borde derecho; el alto es
 *    el del texto, o uno mayor si se arrastró un tirador de arriba o de abajo (el texto queda arriba).
 * [letterSpacing] (0 a 0,5, en "em"), [textAlign] (0 izquierda, 1 centro, 2 derecha) y [lineHeight] (0,2 a 2, multiplicador).
 * [x] e [y] son el centro del recuadro. [fontFamily] es un id de FontManager ("sys:...", "asset:...", "file:...").
 *
 * Tiradores: las esquinas escalan el texto (cambian el tamaño de la letra y, si el ancho es fijo, el ancho en la misma proporción);
 * los de izquierda y derecha cambian el ancho (el texto pasa a ancho fijo y se reacomoda); los de arriba y abajo cambian el alto.
 * Mientras dura el arrastre ([beginGesture]) el dibujo sigue al dedo y al soltar ([endGesture]) se pasa a los datos del texto.
 */
internal class TextItem(
    override val id: String,
    var text: String,
    var fontSize: Float,
    var fontFamily: String,
    var isItalic: Boolean,
    var isBold: Boolean,
    var isUnderline: Boolean,
    var color: Int,
    override val bounds: RectF,
    override var rotation: Float,
    override var opacity: Float = 1f,
    override var isLocked: Boolean = false,
    override var isFlippedH: Boolean = false,
    override var isFlippedV: Boolean = false,
    override var linkType: Int = ImageItem.LINK_NONE,
    override var linkData: String = "",
    var isFixedWidth: Boolean = false,
    var letterSpacing: Float = 0f,
    var textAlign: Int = 0,
    var lineHeight: Float = 1f
) : SelectableItem, Linkable {
    companion object {
        const val MM_PER_UNIT = 0.2f
        const val MIN_SIZE = 8f
        const val MAX_SIZE = 200f
        const val MIN_SPACING = 0f
        const val MAX_SPACING = 0.5f
        const val MIN_LINE = 0.2f
        const val MAX_LINE = 2f
        private const val MIN_WIDTH_MM = 6f // ancho mínimo de un recuadro de ancho fijo
        private const val K = 50f // el texto se mide y se arma a 50 px por mm (se dibuja reducido) para no perder precisión

        // Qué se está arrastrando (ver beginGesture)
        const val G_NONE = 0
        const val G_CORNER = 1
        const val G_SIDE = 2
        const val G_VERT = 3

        private val styled = HashMap<String, Typeface>()

        /** La fuente con negrita e itálica aplicadas (con caché). */
        @Synchronized
        fun typeface(family: String, bold: Boolean, italic: Boolean): Typeface {
            val key = "$family|$bold|$italic"
            styled[key]?.let { return it }
            val base = FontManager.typefaceFor(family)
            val st = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            val tf = if (st == Typeface.NORMAL) base else Typeface.create(base, st)
            if (FontManager.isReady()) styled[key] = tf
            return tf
        }

        /** Un texto nuevo cuyo punto izquierdo-centro está en (ax, ay) de la hoja. */
        fun create(
            id: String, text: String, size: Float, family: String, italic: Boolean, bold: Boolean, underline: Boolean,
            color: Int, ax: Float, ay: Float
        ): TextItem {
            val t = TextItem(id, text, size, family, italic, bold, underline, color, RectF(), 0f)
            t.placeLeftCenter(ax, ay)
            return t
        }
    }

    // Alto del recuadro cuando es mayor que el del texto (ancho fijo y se arrastró un tirador de arriba o de abajo); 0 = el del texto.
    private var frameH = 0f

    // Arrastre en curso: qué es y, si son las esquinas, el recuadro al empezar (el texto se dibuja escalado respecto a él).
    private var gMode = G_NONE
    private var scaleRef: RectF? = null

    // Último StaticLayout armado (se reutiliza mientras no cambie nada: se dibuja en cada cuadro).
    private var cacheKey: String? = null
    private var cacheLayout: StaticLayout? = null

    init {
        if (isFixedWidth && bounds.width() > 0f && bounds.height() > 0f) {
            val h = measure(bounds.width())[1]
            if (bounds.height() > h + 0.05f) frameH = bounds.height()
        }
    }

    /** Centro del recuadro (mm de la hoja). */
    var x: Float
        get() = bounds.centerX()
        set(v) {
            bounds.offset(v - bounds.centerX(), 0f)
        }
    var y: Float
        get() = bounds.centerY()
        set(v) {
            bounds.offset(0f, v - bounds.centerY())
        }

    override val scale: Float get() = fontSize / TextStore.DEFAULT_SIZE

    /** Una copia sin opacidad ni bloqueo y con su recuadro propio (para dibujarla aparte, por ejemplo en un sticker). */
    fun plainCopy(): TextItem = TextItem(
        id, text, fontSize, fontFamily, isItalic, isBold, isUnderline, color, RectF(bounds), rotation, 1f, false,
        isFlippedH, isFlippedV, ImageItem.LINK_NONE, "", isFixedWidth, letterSpacing, textAlign, lineHeight
    )

    // ---------- Maquetación ----------

    private fun textPaint(sizePx: Float): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = typeface(fontFamily, isBold, isItalic)
        p.textSize = sizePx
        p.letterSpacing = letterSpacing.coerceIn(MIN_SPACING, MAX_SPACING)
        p.color = color
        p.isUnderlineText = isUnderline
        return p
    }

    private fun alignment(): Layout.Alignment = when (textAlign) {
        1 -> Layout.Alignment.ALIGN_CENTER
        2 -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    /** El texto armado a [K] px por mm. [widthMm] = null: sin saltos de línea (una sola línea, ancho natural). */
    @Synchronized
    private fun layoutFor(widthMm: Float?): StaticLayout {
        val key = "$text|$fontSize|$fontFamily|$isBold|$isItalic|$isUnderline|$color|$letterSpacing|$textAlign|$lineHeight|${widthMm ?: -1f}"
        val c = cacheLayout
        if (c != null && key == cacheKey) return c
        val tp = textPaint(fontSize * MM_PER_UNIT * K)
        val txt: CharSequence = if (text.isEmpty()) " " else text
        val wPx = if (widthMm == null) ceil(Layout.getDesiredWidth(txt, tp)).toInt() + 2 else max(1, (widthMm * K).roundToInt())
        val l = StaticLayout.Builder.obtain(txt, 0, txt.length, tp, wPx)
            .setAlignment(alignment())
            .setLineSpacing(0f, lineHeight.coerceIn(MIN_LINE, MAX_LINE))
            .setIncludePad(false)
            .build()
        cacheKey = key
        cacheLayout = l
        return l
    }

    /**
     * Tamaño del texto en mm: [ancho, alto]. [widthMm] = null: una línea (ancho = el de la línea más larga); si no, ese ancho.
     * El alto va del borde de arriba a lo más bajo de la última línea (con una altura de línea menor que 1 las líneas se pegan, pero
     * el recuadro sigue cubriendo las letras).
     */
    @Synchronized
    fun measure(widthMm: Float?): FloatArray {
        val l = layoutFor(widthMm)
        var w = 0f
        for (i in 0 until l.lineCount) w = max(w, l.getLineWidth(i))
        val h = l.getLineBaseline(l.lineCount - 1) + l.paint.fontMetrics.descent
        return floatArrayOf(if (widthMm == null) max(w, 1f) / K else widthMm, h / K)
    }

    /** Ancho y alto que debe tener el recuadro con el estado actual (libre: una línea; fijo: su ancho y el alto del texto o el mayor dado). */
    private fun boxSize(): FloatArray {
        if (!isFixedWidth) {
            frameH = 0f
            return measure(null)
        }
        val w = max(bounds.width(), MIN_WIDTH_MM)
        val h = measure(w)[1]
        return floatArrayOf(w, if (frameH > h) frameH else h)
    }

    private fun topLeft(): FloatArray =
        Xf.toPage(bounds.centerX(), bounds.centerY(), rotation, -bounds.width() / 2f, -bounds.height() / 2f)

    private fun setFromTopLeft(tx: Float, ty: Float, w: Float, h: Float) {
        val c = Xf.toPage(tx, ty, rotation, w / 2f, h / 2f)
        bounds.set(c[0] - w / 2f, c[1] - h / 2f, c[0] + w / 2f, c[1] + h / 2f)
    }

    /** Ajusta el recuadro al texto manteniendo fija su esquina superior izquierda (con el giro actual). */
    fun refit() {
        val tl = topLeft()
        val s = boxSize()
        setFromTopLeft(tl[0], tl[1], s[0], s[1])
    }

    /** Como [refit], pero con la esquina superior izquierda en (tx, ty) de la hoja (al terminar de editar el texto). */
    fun placeTopLeft(tx: Float, ty: Float) {
        val s = boxSize()
        setFromTopLeft(tx, ty, s[0], s[1])
    }

    /** Recuadro de un texto NUEVO (una línea) con su punto izquierdo-centro en (ax, ay) de la hoja. */
    fun placeLeftCenter(ax: Float, ay: Float) {
        val s = boxSize()
        val c = Xf.toPage(ax, ay, rotation, s[0] / 2f, 0f)
        bounds.set(c[0] - s[0] / 2f, c[1] - s[1] / 2f, c[0] + s[0] / 2f, c[1] + s[1] / 2f)
    }

    // ---------- Arrastre de tiradores ----------

    /** Empieza un arrastre: [G_CORNER] (esquinas o grupo: escala), [G_SIDE] (izquierda o derecha: ancho), [G_VERT] (arriba o abajo: alto) o [G_NONE]. */
    fun beginGesture(mode: Int) {
        gMode = mode
        scaleRef = if (mode == G_CORNER) RectF(bounds) else null
    }

    /** Abandona un arrastre sin aplicar nada (el estado del texto no cambió). */
    fun cancelGesture() {
        gMode = G_NONE
        scaleRef = null
    }

    /** Termina el arrastre y pasa lo hecho a los datos del texto: tamaño de letra, ancho fijo o alto del recuadro. */
    fun endGesture() {
        val mode = gMode
        val ref = scaleRef
        cancelGesture()
        when (mode) {
            G_CORNER -> {
                val k = if (ref != null && ref.width() > 0f) bounds.width() / ref.width() else 1f
                if (abs(k - 1f) > 0.002f) {
                    fontSize = (fontSize * k).coerceIn(MIN_SIZE, MAX_SIZE)
                    if (frameH > 0f) frameH *= k
                }
                refit()
            }
            G_SIDE -> {
                isFixedWidth = true
                refit()
            }
            G_VERT -> {
                isFixedWidth = true
                val h = measure(max(bounds.width(), MIN_WIDTH_MM))[1]
                frameH = if (bounds.height() > h + 0.05f) bounds.height() else 0f
                refit()
            }
        }
    }

    // ---------- Dibujo ----------

    override fun draw(c: Canvas, mmToPx: Float, invalidate: () -> Unit) {
        if (text.isEmpty()) return
        val ref = scaleRef
        val live = ref != null && ref.width() > 0f // se arrastra una esquina: el texto de siempre, escalado al recuadro
        val s = if (live) bounds.width() / ref!!.width() else 1f
        val widthMm: Float? = when {
            live -> if (isFixedWidth) ref!!.width() else null
            isFixedWidth || gMode == G_SIDE || gMode == G_VERT -> bounds.width() // el texto se reacomoda al ancho del recuadro
            else -> null
        }
        synchronized(this) {
            val lay = layoutFor(widthMm)
            lay.paint.alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
            val cx = bounds.centerX() * mmToPx
            val cy = bounds.centerY() * mmToPx
            val n = c.save()
            c.rotate(rotation, cx, cy)
            c.translate(cx, cy)
            c.scale(if (isFlippedH) -1f else 1f, if (isFlippedV) -1f else 1f) // espejo sobre el centro del recuadro
            c.translate(-bounds.width() * mmToPx / 2f, -bounds.height() * mmToPx / 2f) // esquina superior izquierda
            c.scale(s * mmToPx / K, s * mmToPx / K)
            lay.draw(c)
            c.restoreToCount(n)
        }
    }
}

/** Escritura de bitmaps de imágenes (PNG si tienen transparencia, JPEG si no). */
internal object ImageFiles {
    /** Guarda [bm] como <id>.png o <id>.jpg dentro de [dir]. Devuelve el archivo o null si no se pudo escribir. */
    fun write(bm: Bitmap, dir: File, id: String): File? {
        val png = bm.hasAlpha()
        val f = File(dir, id + if (png) ".png" else ".jpg")
        return try {
            dir.mkdirs()
            val ok = FileOutputStream(f).use { bm.compress(if (png) CompressFormat.PNG else CompressFormat.JPEG, 92, it) }
            if (ok) f else null
        } catch (e: Exception) {
            null
        }
    }
}

/** Acciones de la barra de selección (de izquierda a derecha). */
internal object SelAct {
    const val ALL = 0
    const val STICKER = 1
    const val COPY = 2
    const val PASTE = 3
    const val FRONT = 4
    const val BACK = 5
    const val DELETE = 6
}

/**
 * Un trazo (lápiz, pluma, pincel o marcador) como objeto seleccionable. La caja es el rectángulo de los puntos más la mitad
 * del grosor (para que el recuadro no corte la línea); se mueve, redimensiona y gira con los mismos gestos que una imagen.
 * Mientras dura el gesto el trazo se calcula con [transformed] (una matriz sobre los puntos: de la caja del inicio a la caja
 * actual); al terminar, [bake] deja esos puntos como un Stroke nuevo (el trazo no se guarda con transformación aparte, así el
 * archivo de trazos y todo lo demás siguen igual). El orden de dibujo no cambia: el nuevo trazo ocupa el lugar del anterior.
 */
internal class StrokeItem(var src: Stroke) : SelectableItem, Linkable {
    override val id: String get() = "s" + src.uid
    override val bounds = RectF()
    override var rotation: Float = 0f
    override val scale: Float get() = 1f
    override var isFlippedH: Boolean = false
    override var isFlippedV: Boolean = false
    // Opacidad y bloqueo viajan con el trazo (se guardan en el archivo de trazos al confirmarse, ver PageViewport.commitStroke).
    override var opacity: Float = src.opacity
    override var isLocked: Boolean = src.locked

    // Enlace del trazo (el mismo que el de las imágenes). Se copia del trazo y se actualiza en adopt(); al cambiarlo se reemplaza
    // el trazo de la capa (PageViewport.setStrokeLink) y así se guarda con el archivo de trazos.
    override var linkType: Int = src.linkType
    override var linkData: String = src.linkData

    // Estado al empezar el gesto (la transformación va de b0/rot0 a bounds/rotation).
    private val b0 = RectF()
    private var rot0 = 0f
    private var cur: Stroke? = null
    private val curKey = FloatArray(5)

    init {
        val bb = src.bbox()
        val pad = src.widthMm * 0.75f
        bounds.set(bb[0] - pad, bb[1] - pad, bb[2] + pad, bb[3] + pad)
        b0.set(bounds)
    }

    fun beginGesture() {
        b0.set(bounds)
        rot0 = rotation
        cur = null
    }

    override fun draw(c: Canvas, mmToPx: Float, invalidate: () -> Unit) {
        StrokeRender.draw(c, transformed(), mmToPx, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    /**
     * El trazo con la transformación actual. Para cada punto: se lleva al sistema de la caja inicial (deshaciendo su giro),
     * se escala (ancho nuevo / ancho inicial, alto nuevo / alto inicial) y se coloca en la caja actual con su giro.
     * El grosor se escala con la media geométrica de los dos factores.
     */
    fun transformed(): Stroke {
        val same = bounds.left == b0.left && bounds.top == b0.top && bounds.right == b0.right &&
            bounds.bottom == b0.bottom && rotation == rot0
        if (same && src.opacity == opacity && src.locked == isLocked) return src
        val c = cur
        if (c != null && curKey[0] == bounds.left && curKey[1] == bounds.top && curKey[2] == bounds.right &&
            curKey[3] == bounds.bottom && curKey[4] == rotation && c.opacity == opacity && c.locked == isLocked
        ) return c
        if (same) {
            // Solo cambió la opacidad o el bloqueo: mismos puntos y mismo camino calculado.
            val k = src.copyWith(opacity, isLocked)
            cur = k
            curKey[0] = bounds.left
            curKey[1] = bounds.top
            curKey[2] = bounds.right
            curKey[3] = bounds.bottom
            curKey[4] = rotation
            return k
        }
        val w0 = max(b0.width(), 1e-3f)
        val h0 = max(b0.height(), 1e-3f)
        val sx = bounds.width() / w0
        val sy = bounds.height() / h0
        val c0x = b0.centerX()
        val c0y = b0.centerY()
        val c1x = bounds.centerX()
        val c1y = bounds.centerY()
        val a0 = Math.toRadians(rot0.toDouble())
        val cos0 = cos(a0).toFloat()
        val sin0 = sin(a0).toFloat()
        val a1 = Math.toRadians(rotation.toDouble())
        val cos1 = cos(a1).toFloat()
        val sin1 = sin(a1).toFloat()
        val p = src.pts
        val out = FloatArray(p.size)
        var i = 0
        while (i < p.size) {
            val dx = p[i] - c0x
            val dy = p[i + 1] - c0y
            val lx = (dx * cos0 + dy * sin0) * sx // rotar -rot0 y escalar
            val ly = (-dx * sin0 + dy * cos0) * sy
            out[i] = c1x + lx * cos1 - ly * sin1 // rotar +rotation y colocar
            out[i + 1] = c1y + lx * sin1 + ly * cos1
            out[i + 2] = p[i + 2]
            i += 3
        }
        val f = kotlin.math.sqrt(kotlin.math.abs(sx * sy)).coerceIn(0.05f, 20f)
        val res = Stroke(src.type, src.color, src.widthMm * f, out, opacity, isLocked, src.uid, src.linkType, src.linkData)
        cur = res
        curKey[0] = bounds.left
        curKey[1] = bounds.top
        curKey[2] = bounds.right
        curKey[3] = bounds.bottom
        curKey[4] = rotation
        return res
    }

    /** Termina el gesto: devuelve el trazo transformado (para ponerlo en la capa) y vuelve a ajustar la caja a él, conservando el giro. */
    fun bake(): Stroke = adopt(transformed())

    /**
     * El espejo del trazo (izquierda-derecha o arriba-abajo) sobre el centro de su caja y en los ejes de la caja (así sigue valiendo
     * si está girado). La caja es simétrica respecto a su centro, así que no cambia. Devuelve el trazo nuevo (el actual no se toca).
     */
    fun flipped(horizontal: Boolean): Stroke {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val a = Math.toRadians(rotation.toDouble())
        val cs = cos(a).toFloat()
        val sn = sin(a).toFloat()
        val p = src.pts
        val out = FloatArray(p.size)
        var i = 0
        while (i < p.size) {
            val dx = p[i] - cx
            val dy = p[i + 1] - cy
            var lx = dx * cs + dy * sn // al sistema de la caja (deshacer el giro)
            var ly = -dx * sn + dy * cs
            if (horizontal) lx = -lx else ly = -ly
            out[i] = cx + lx * cs - ly * sn // de vuelta a la hoja
            out[i + 1] = cy + lx * sn + ly * cs
            out[i + 2] = p[i + 2]
            i += 3
        }
        return Stroke(src.type, src.color, src.widthMm, out, opacity, isLocked, src.uid, src.linkType, src.linkData)
    }

    /** Hace de [out] el trazo de este elemento y reajusta la caja a sus puntos, conservando el giro. */
    fun adopt(out: Stroke): Stroke {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val a = Math.toRadians(rotation.toDouble())
        val cs = cos(a).toFloat()
        val sn = sin(a).toFloat()
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        var i = 0
        while (i < out.pts.size) {
            val dx = out.pts[i] - cx
            val dy = out.pts[i + 1] - cy
            val lx = dx * cs + dy * sn
            val ly = -dx * sn + dy * cs
            if (lx < x0) x0 = lx
            if (lx > x1) x1 = lx
            if (ly < y0) y0 = ly
            if (ly > y1) y1 = ly
            i += 3
        }
        val pad = out.widthMm * 0.75f
        val lcx = (x0 + x1) / 2f
        val lcy = (y0 + y1) / 2f
        val w = x1 - x0 + 2f * pad
        val h = y1 - y0 + 2f * pad
        val ncx = cx + lcx * cs - lcy * sn
        val ncy = cy + lcx * sn + lcy * cs
        bounds.set(ncx - w / 2f, ncy - h / 2f, ncx + w / 2f, ncy + h / 2f)
        src = out
        linkType = out.linkType
        linkData = out.linkData
        cur = null
        b0.set(bounds)
        rot0 = rotation
        return out
    }
}
