package com.wian.kidorb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Disque de couleur radial — 3e voie du sélecteur mode enfant, revue
 *  2026-08-20 (spec par capture d'écran fournie : modèle HSB standard,
 *  "regarde le screen et base toi la dessu" — lightcolourvision.org/diagram,
 *  "Hues are arranged around the edge... saturation reduces in equal steps
 *  towards the centre") — angle = teinte (tour complet), rayon =
 *  SATURATION (centre = blanc/désaturé, bord = pleinement saturé),
 *  luminosité fixe au maximum partout (pas d'assombrissement vers le bord,
 *  contrairement à la version précédente qui allait jusqu'au noir). Un seul
 *  geste de glissé, n'importe où dans le disque, choisit teinte+saturation
 *  d'un coup. Même technique de rendu que HueSliderView (bitmap précalculé,
 *  recalculé seulement au redimensionnement). */
class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChange: ((Int) -> Unit)? = null
    var onPickStart: (() -> Unit)? = null
    var onPickEnd: (() -> Unit)? = null

    /** Teinte actuelle (degrés, 0-360). */
    private var hueDeg: Float = 0f
    /** Saturation actuelle, 0 (centre, blanc) à 1 (bord, pleinement saturé). */
    private var saturation: Float = 0.8f

    private fun colorAt(deg: Float, sat: Float): Int =
        Color.HSVToColor(floatArrayOf(((deg % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), 1f))

    private val bmpSize = 120
    private var gradientBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cursorFillPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    private val cursorStrokePaint = Paint().apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        isAntiAlias = true
    }
    private val cursorRadius = 5f * resources.displayMetrics.density

    private var centerPx = bmpSize / 2f
    private var outerR = bmpSize / 2f - 2f

    private fun buildBitmap() {
        val bmp = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888)
        for (py in 0 until bmpSize) {
            for (px in 0 until bmpSize) {
                val dx = px + 0.5f - centerPx
                val dy = py + 0.5f - centerPx
                val r = hypot(dx, dy)
                if (r <= outerR) {
                    val angleDeg = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
                    val sat = (r / outerR).coerceIn(0f, 1f) // centre=0 (blanc), bord=1 (saturé)
                    bmp.setPixel(px, py, colorAt(angleDeg, sat))
                } else {
                    bmp.setPixel(px, py, Color.TRANSPARENT)
                }
            }
        }
        gradientBitmap = bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = width / bmpSize.toFloat()
        gradientBitmap?.let { bmp ->
            canvas.save()
            canvas.scale(scale, scale)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            canvas.restore()
        }
        val hueRad = Math.toRadians(hueDeg.toDouble())
        val r = saturation * outerR
        val cx = (centerPx + r * cos(hueRad).toFloat()) * scale
        val cy = (centerPx + r * sin(hueRad).toFloat()) * scale
        canvas.drawCircle(cx, cy, cursorRadius, cursorFillPaint)
        canvas.drawCircle(cx, cy, cursorRadius, cursorStrokePaint)
    }

    private fun handleTouch(x: Float, y: Float) {
        val scale = width / bmpSize.toFloat()
        val bx = x / scale
        val by = y / scale
        val dx = bx - centerPx
        val dy = by - centerPx
        val r = hypot(dx, dy).coerceAtMost(outerR)
        hueDeg = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
        saturation = r / outerR
        invalidate()
        onColorChange?.invoke(colorAt(hueDeg, saturation))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onPickStart?.invoke()
                handleTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTouch(event.x, event.y)
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
