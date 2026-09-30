package com.wian.kidorb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Pavé 2D dégradé, même gabarit que l'ancienne barre 1D (2026-08-20, demande
 *  explicite : "j'imaginait un truc similaire... par contre il faut un
 *  retours visuel mais je veux pas agrandire le menu") — vertical : blanc en
 *  haut, arc-en-ciel complet, noir en bas (rampe existante) ; horizontal :
 *  gris (désaturé) à gauche, couleur vive à droite. Rendu via un petit bitmap
 *  précalculé (pas un Shader — la rampe verticale n'est pas un simple
 *  dégradé linéaire, elle varie de courbe par ligne selon la désaturation),
 *  redimensionné à l'affichage — coûte une passe de calcul par changement de
 *  taille, pas par frame. Le curseur (rond, pas une ligne pleine largeur
 *  comme avant) marque le point exact touché sur les 2 axes, sans agrandir
 *  la vue. */
class HueSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChange: ((Int) -> Unit)? = null
    var onPickStart: (() -> Unit)? = null
    var onPickEnd: (() -> Unit)? = null

    /** Position verticale du curseur, 0f (haut, blanc) à 1f (bas, noir). */
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Position horizontale du curseur, 0f (désaturé/gris) à 1f (couleur vive). */
    var fractionX: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Voie "Simple" (2026-08-20, demande explicite : "je veux aussi le
     *  simple sans la saturation, comme au debut") — ignore le toucher
     *  horizontal, couleur toujours pleinement saturée (fx=1), redevient la
     *  toute première version de ce widget (barre 1D pure) tout en gardant
     *  le même bitmap/rendu (juste une colonne, visuellement identique à un
     *  dégradé vertical simple). */
    var saturationLocked: Boolean = false
        set(value) {
            field = value
            buildGradientBitmap()
            invalidate()
        }

    // paliers également espacés (positions=null dans LinearGradient ci-dessous
    // répartit automatiquement) : blanc → rouge → orange → jaune → vert →
    // cyan → bleu → violet → magenta → noir, ordre spectral classique (pas de
    // retour en arrière vers le rouge en fin de dégradé, contrairement à un
    // simple balayage de teinte 0°→360°).
    private val hueStops = intArrayOf(
        Color.WHITE,
        Color.HSVToColor(floatArrayOf(0f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(30f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(60f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(120f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(180f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(240f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(280f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(320f, 1f, 1f)),
        Color.BLACK,
    )
    // résolution du bitmap précalculé — pas besoin de plus, la rampe est
    // lisse et l'affichage l'étire en filtré (BITMAP_FLAG)
    private val bmpW = 16
    private val bmpH = 96
    private var gradientBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()
    private val cursorFillPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val cursorStrokePaint = Paint().apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        isAntiAlias = true
    }
    private val cursorRadius = 6f * resources.displayMetrics.density
    private val cornerRadius = 10f * resources.displayMetrics.density
    private val bounds = RectF()

    /** Couleur de la rampe verticale seule (blanc → arc-en-ciel → noir),
     *  interpolation linéaire par canal RGB entre les 2 paliers voisins de
     *  `hueStops`, paliers également espacés. */
    private fun hueColorAt(f: Float): Int {
        val frac = f.coerceIn(0f, 1f)
        val n = hueStops.size - 1
        val segF = (frac * n).coerceIn(0f, n.toFloat())
        val i = segF.toInt().coerceIn(0, n - 1)
        val t = segF - i
        val a = hueStops[i]
        val b = hueStops[i + 1]
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
    }

    /** Version désaturée (gris de même luminance perçue) d'une couleur —
     *  extrémité gauche du dégradé horizontal, à chaque hauteur. */
    private fun luminanceGray(c: Int): Int {
        val l = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
            .toInt().coerceIn(0, 255)
        return Color.rgb(l, l, l)
    }

    /** Couleur au point (fx, fy) du pavé — même calcul que le bitmap affiché. */
    fun colorAt(fx: Float, fy: Float): Int {
        val base = hueColorAt(fy)
        val gray = luminanceGray(base)
        val t = fx.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(gray) + (Color.red(base) - Color.red(gray)) * t).toInt(),
            (Color.green(gray) + (Color.green(base) - Color.green(gray)) * t).toInt(),
            (Color.blue(gray) + (Color.blue(base) - Color.blue(gray)) * t).toInt(),
        )
    }

    private fun buildGradientBitmap() {
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        for (py in 0 until bmpH) {
            val fy = py / (bmpH - 1).toFloat()
            for (px in 0 until bmpW) {
                val fx = if (saturationLocked) 1f else px / (bmpW - 1).toFloat()
                bmp.setPixel(px, py, colorAt(fx, fy))
            }
        }
        gradientBitmap = bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW)
        buildGradientBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        gradientBitmap?.let { bmp ->
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(bmp, null, bounds, bitmapPaint)
            canvas.restore()
        }
        if (saturationLocked) {
            // voie "Simple" : la position horizontale n'a aucun effet (fx
            // toujours 1) — une ligne pleine largeur marque la hauteur
            // choisie, plus lisible qu'un rond plaqué au bord droit.
            val lineHalfHeight = cursorRadius * 0.4f
            val y = (fraction * height).coerceIn(lineHalfHeight, height - lineHalfHeight)
            canvas.drawRect(0f, y - lineHalfHeight, width.toFloat(), y + lineHalfHeight, cursorFillPaint)
            canvas.drawRect(0f, y - lineHalfHeight, width.toFloat(), y + lineHalfHeight, cursorStrokePaint)
        } else {
            val x = (fractionX * width).coerceIn(cursorRadius, width - cursorRadius)
            val y = (fraction * height).coerceIn(cursorRadius, height - cursorRadius)
            canvas.drawCircle(x, y, cursorRadius, cursorFillPaint)
            canvas.drawCircle(x, y, cursorRadius, cursorStrokePaint)
        }
    }

    private fun updateFromTouch(event: MotionEvent) {
        fractionX = if (saturationLocked) 1f else if (width > 0) event.x / width else fractionX
        fraction = if (height > 0) event.y / height else fraction
        onColorChange?.invoke(colorAt(fractionX, fraction))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onPickStart?.invoke()
                updateFromTouch(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onPickEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
