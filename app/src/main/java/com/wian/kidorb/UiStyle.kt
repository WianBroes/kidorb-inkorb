package com.wian.kidorb

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Échelle unique de texte/couleur/espacement + fabriques de boutons —
 *  remplace les valeurs éparpillées (13f ici, 0xFFBBBBBB là) par un seul
 *  système, appliqué à tout le panneau de menu. */
object UiStyle {
    const val TEXT = Color.WHITE
    val TEXT_MUTED = 0xFFACACAC.toInt()
    private val RIPPLE_COLOR = ColorStateList.valueOf(0x40FFFFFF)

    const val SP_HINT = 11f
    const val SP_BODY = 13f
    const val SP_TITLE = 14f

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** Titre de section (« Palette », « Calques »…) : seul texte en gras du panneau. */
    fun title(tv: TextView) {
        tv.setTextColor(TEXT)
        tv.textSize = SP_TITLE
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun body(tv: TextView, muted: Boolean = false) {
        tv.setTextColor(if (muted) TEXT_MUTED else TEXT)
        tv.textSize = SP_BODY
    }

    fun hint(tv: TextView) {
        tv.setTextColor(TEXT_MUTED)
        tv.textSize = SP_HINT
    }

    /** Enrobe un drawable de forme (oval/rect) dans un ripple tactile natif
     *  (framework, API 21+ — aucune dépendance ajoutée). */
    fun ripple(context: Context, shapeRes: Int): Drawable {
        val content = context.getDrawable(shapeRes)!!
        val mask = context.getDrawable(shapeRes)!!.mutate()
        return RippleDrawable(RIPPLE_COLOR, content, mask)
    }

    /** Bouton glyphe rond (✕, +) — cible tactile de taille constante,
     *  ripple inclus. Remplace les 3 variantes ad hoc du fichier. */
    fun glyphButton(context: Context, glyph: String, sizeDp: Float = 40f, muted: Boolean = false): TextView {
        val size = dp(context, sizeDp)
        return TextView(context).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(if (muted) TEXT_MUTED else TEXT)
            textSize = SP_TITLE
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = ripple(context, R.drawable.bg_round_btn)
        }
    }

    /** Ligne réglage on/off (« Bille visible », « Rester dans l'écran »…) — libellé +
     *  switch natif Android (piste + pastille qui glisse, `android.widget.Switch`,
     *  framework pur API 24+, aucune dépendance ajoutée). Remplace les anciens
     *  boutons pilule « Label : ON/OFF » (2026-08-19, demande explicite :
     *  "toggle genre ceux des smartphones en général, avec une pastille qui bouge"). */
    fun switchRow(context: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val labelView = TextView(context).apply {
            text = label
            body(this, muted = true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchView = Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }
        row.addView(labelView)
        row.addView(switchView)
        return row
    }

    /** Bouton icône + légende (« Nouveau », « Sauvegarder »…) — rangée d'accueil
     *  façon icônes classiques d'appli (2026-08-19, demande explicite :
     *  "avec des icônes classiques" pour le menu Export devenu écran d'accueil). */
    fun iconButton(context: Context, iconRes: Int, label: String, sizeDp: Float = 44f): LinearLayout {
        val size = dp(context, sizeDp)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(context, 10f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = ripple(context, R.drawable.bg_round_btn)
        }
        val caption = TextView(context).apply {
            text = label
            hint(this)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 2f) }
        }
        col.addView(icon)
        col.addView(caption)
        return col
    }

    /** Bouton pilule texte (« Annuler », « Effacer le calque »…) — padding et ripple uniformes. */
    fun pillButton(context: Context, label: String, muted: Boolean = false): TextView {
        val padH = dp(context, 14f)
        val padV = dp(context, 10f)
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (muted) TEXT_MUTED else TEXT)
            textSize = SP_BODY
            setPadding(padH, padV, padH, padV)
            background = ripple(context, R.drawable.bg_round_rect)
        }
    }
}
