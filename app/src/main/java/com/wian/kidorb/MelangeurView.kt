package com.wian.kidorb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Sélecteur de couleur 2D : carte perceptuellement uniforme (OKLCh) —
 * X = teinte (0..360°), Y = clarté (blanc → couleur → noir), chroma réglable
 * (par défaut, mais ajustable en complément via un slider Saturation externe —
 * la carte se régénère alors avec la nouvelle chroma).
 * Le doigt déplace un point ; la couleur sous le point est renvoyée en
 * direct (onColorPicked) et confirmée au relâchement (onColorCommitted).
 * teinte/clarte/chroma sont la source de vérité (pas une position pixel) —
 * un slider externe (setHue/setChroma) édite le même échantillon.
 */
class MelangeurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorPicked: ((Int) -> Unit)? = null
    var onColorCommitted: ((Int) -> Unit)? = null
    // déclenché seulement par un toucher DIRECT sur la carte — jamais par
    // setHue/setChroma — pour que les sliders externes puissent se
    // synchroniser sans jamais réassigner leur propre progress en plein
    // glissé (ça fait ramer/lutter avec le doigt sur la même SeekBar)
    var onDirectTouch: (() -> Unit)? = null

    var hue: Double = 180.0
        private set
    var clarte: Double = 0.5
        private set
    var chroma: Double = 0.28
        private set
    private var color: Int = 0

    private var bmp: Bitmap = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val apercuPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // rebuildBitmap() régénère 360×100 pixels — le gamut mapping de
    // PigmentMix fait une bissection par pixel hors-gamut (souvent la
    // majorité de la carte), assez coûteux pour saccader le thread UI même
    // débounce : calculé sur un thread à part, puis échangé sur le thread
    // principal une fois prêt (rebuildGen ignore un résultat devenu obsolète).
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rebuildGen = 0
    private val rebuildTrigger = Runnable { rebuildBitmapAsync() }

    // 2026-08-25, rapporté : "le demarage est un peu lent, ya comme un freez
    // au depart" — génération SYNCHRONE de la carte 360×100 au premier
    // affichage (gamut mapping par bissection, cf. PigmentMix.gamutMap),
    // répétée pour 3 instances construites d'un coup dans onCreate (palette,
    // bille, pinceau). Passe par le chemin asynchrone déjà utilisé pour les
    // changements de chroma (rebuildBitmapAsync) : la vue démarre avec un
    // bitmap vide (transparent, un instant à peine) plutôt que de bloquer le
    // thread UI pendant la construction du menu.
    init {
        recomputeColor()
        rebuildBitmapAsync()
    }

    /** Régénère la carte en arrière-plan (chroma changée, ou premier
     *  affichage) — non bloquant. */
    private fun rebuildBitmapAsync() {
        val myGen = ++rebuildGen
        val targetChroma = chroma
        Thread {
            val fresh = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888)
            fillBitmap(fresh, targetChroma)
            mainHandler.post {
                if (myGen == rebuildGen) { // sinon une chroma plus récente a déjà pris le relais
                    bmp = fresh
                    invalidate()
                } else {
                    fresh.recycle()
                }
            }
        }.start()
    }

    private fun fillBitmap(target: Bitmap, chromaValue: Double) {
        // dégradé perceptuellement uniforme : X = teinte OKLCh, Y = clarté,
        // chroma donnée — le gamut mapping de PigmentMix ramène les
        // couleurs hors-gamut dans le sRGB.
        for (y in 0 until target.height) {
            val L = 0.95 - y / (target.height - 1).toDouble() * 0.90 // 0.95 → 0.05
            for (x in 0 until target.width) {
                val h = x / target.width.toDouble() * 360.0
                target.setPixel(x, y, PigmentMix.oklchToArgb(L, chromaValue, h))
            }
        }
    }

    private fun recomputeColor() {
        color = PigmentMix.oklchToArgb(clarte, chroma, hue)
    }

    /** Change la teinte depuis un contrôle externe (slider) — même échantillon
     *  que le mélangeur : déplace le point affiché, pas de régénération. */
    fun setHue(h: Double) {
        hue = h.coerceIn(0.0, 360.0)
        recomputeColor()
        invalidate()
        onColorPicked?.invoke(color)
    }

    /** Change la saturation (chroma OKLCh) depuis un slider externe — la carte
     *  entière se régénère avec la nouvelle chroma (plus ou moins vive). */
    fun setChroma(c: Double) {
        chroma = c.coerceIn(0.0, 0.4)
        // retour immédiat (point + bandeau d'aperçu) — la carte de fond,
        // recalculée en arrière-plan (coûteuse, cf. rebuildBitmapAsync),
        // s'actualise dès qu'elle est prête, sans jamais bloquer le geste
        recomputeColor()
        invalidate()
        onColorPicked?.invoke(color)
        // débounce le LANCEMENT du calcul (pas le calcul lui-même, déjà hors
        // thread UI) — évite d'empiler des dizaines de threads par seconde
        // pendant un glissé continu
        mainHandler.removeCallbacks(rebuildTrigger)
        mainHandler.postDelayed(rebuildTrigger, 40)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
        // point de sélection : ombre + anneau blanc contrasté + cœur couleur
        val px = (hue / 360.0 * width).toFloat()
        val py = ((0.95 - clarte) / 0.90 * height).toFloat()
        pointPaint.color = 0x66000000.toInt()
        canvas.drawCircle(px, py, 12f, pointPaint)
        pointPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(px, py, 10f, pointPaint)
        dotPaint.color = color
        canvas.drawCircle(px, py, 6f, dotPaint)
        // bandeau d'aperçu : la couleur choisie, visible en bas
        val bh = (16 * resources.displayMetrics.density).toInt()
        apercuPaint.color = color
        canvas.drawRect(0f, (height - bh).toFloat(), width.toFloat(), height.toFloat(), apercuPaint)
        // liseré autour du bandeau
        apercuPaint.style = Paint.Style.STROKE
        apercuPaint.strokeWidth = 2f
        apercuPaint.color = 0xFF666666.toInt()
        canvas.drawRect(0f, (height - bh).toFloat(), width.toFloat(), height.toFloat(), apercuPaint)
        apercuPaint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x.coerceIn(0f, width.toFloat())
                val y = event.y.coerceIn(0f, height.toFloat())
                hue = (x / width * 360.0)
                clarte = 0.95 - (y / height) * 0.90
                recomputeColor()
                onColorPicked?.invoke(color)
                onDirectTouch?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onColorCommitted?.invoke(color)
            }
        }
        return true
    }
}
