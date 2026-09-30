package com.wian.kidorb

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Joystick de jeu d'action couplé à la palette (2026-08-20, demande
 *  explicite : "cherche zn ligne comment faire un joystick comme pour les
 *  jzux daction, et couple ca avec la palette") — base fixe + bouton (knob)
 *  qu'on glisse dedans, clampé à un rayon max comme un vrai joystick. Les
 *  couleurs de la palette sont réparties également autour du cercle ;
 *  l'angle du knob choisit une couleur par fondu continu entre les 2
 *  couleurs voisines (pas de secteurs figés, demande explicite : "angle
 *  continu, mélange entre 2 couleurs voisines"). Au relâchement, le knob
 *  revient au centre en glissant (vrai comportement joystick, demande
 *  explicite) — la couleur choisie reste appliquée côté bille, seul le
 *  widget visuel se réinitialise. */
class JoystickPaletteView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChange: ((Int) -> Unit)? = null
    var onPickStart: (() -> Unit)? = null
    var onPickEnd: (() -> Unit)? = null

    /** Couleurs de la palette, réparties également autour du cercle —
     *  rafraîchi par l'appelant (MainActivity) à l'activation de ce type
     *  de sélecteur. */
    var colors: List<Int> = emptyList()
        set(value) { field = value; invalidate() }

    private var knobX = 0f
    private var knobY = 0f
    private var dragging = false
    private var springAnim: ValueAnimator? = null

    // 2026-08-20, demande explicite : "je veux voire la palette, pas des
    // pastiles" — pastilles agrandies (34dp, taille d'une vraie pastille de
    // palette plutôt qu'un point décoratif) + même cadre que bg_swatch_frame
    // (2dp, 40% blanc) pour lire comme LA palette, pas comme un ornement.
    // Base aussi rendue plus visible (0x33 → 0x55) — trop transparente pour
    // qu'on comprenne ce qu'on regarde.
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val swatchStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val swatchFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private var centerPx = 0f
    private var baseRadius = 0f
    private var swatchRadius = 0f
    private var knobRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        centerPx = min(w, h) / 2f
        swatchRadius = 17f * density // ≈ taille d'une vraie pastille de palette (34dp), pas un point
        baseRadius = centerPx - swatchRadius - 6f * density
        knobRadius = 14f * density
        knobX = centerPx
        knobY = centerPx
    }

    /** Couleur au point (dx,dy) relatif au centre — l'angle seul compte,
     *  fondu continu entre les 2 couleurs de palette voisines. */
    private fun colorAtAngle(dx: Float, dy: Float): Int {
        if (colors.isEmpty()) return Color.WHITE
        if (colors.size == 1) return colors[0]
        var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (deg < 0) deg += 360f
        val step = 360f / colors.size
        val segF = deg / step
        val i = segF.toInt().coerceIn(0, colors.size - 1)
        val t = segF - i
        val a = colors[i]
        val b = colors[(i + 1) % colors.size]
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerPx, centerPx, baseRadius, basePaint)
        // couleurs de la palette réparties autour du cercle
        if (colors.isNotEmpty()) {
            val step = 360.0 / colors.size
            colors.forEachIndexed { i, c ->
                val rad = Math.toRadians(i * step)
                val sx = centerPx + baseRadius * cos(rad).toFloat()
                val sy = centerPx + baseRadius * sin(rad).toFloat()
                swatchFillPaint.color = c
                canvas.drawCircle(sx, sy, swatchRadius, swatchFillPaint)
                canvas.drawCircle(sx, sy, swatchRadius, swatchStrokePaint)
            }
        }
        canvas.drawLine(centerPx, centerPx, knobX, knobY, stickPaint)
        knobFillPaint.color = colorAtAngle(knobX - centerPx, knobY - centerPx)
        canvas.drawCircle(knobX, knobY, knobRadius, knobFillPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobStrokePaint)
    }

    private fun updateKnob(x: Float, y: Float) {
        val dx = x - centerPx
        val dy = y - centerPx
        val dist = hypot(dx, dy)
        if (dist <= baseRadius || dist == 0f) {
            knobX = x; knobY = y
        } else {
            // clamp au rayon max de la base — comportement joystick classique
            knobX = centerPx + dx / dist * baseRadius
            knobY = centerPx + dy / dist * baseRadius
        }
        invalidate()
        onColorChange?.invoke(colorAtAngle(knobX - centerPx, knobY - centerPx))
    }

    private fun springBackToCenter() {
        springAnim?.cancel()
        val fromX = knobX
        val fromY = knobY
        springAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                val t = it.animatedValue as Float
                knobX = fromX + (centerPx - fromX) * t
                knobY = fromY + (centerPx - fromY) * t
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                springAnim?.cancel()
                dragging = true
                onPickStart?.invoke()
                updateKnob(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) updateKnob(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                springBackToCenter()
                onPickEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
