package com.wian.kidorb

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Langue de l'app indépendante de la langue système — "system" suit le
 *  téléphone (comportement par défaut, zéro configuration), "en"/"fr" la
 *  fige. Pas d'AndroidX (le projet est zéro-dépendance) : on enveloppe la
 *  Configuration/Locale au niveau framework via attachBaseContext, ce qui
 *  fonctionne identiquement sur toutes les API (pas besoin de l'API 33+). */
object LocaleHelper {

    private const val PREFS = "encrebille_prefs"
    private const val KEY_LANGUAGE = "language"
    const val SYSTEM = "system"

    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM

    fun setLanguage(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, code).apply()
    }

    /** À appeler depuis attachBaseContext(). */
    fun wrap(context: Context): Context {
        val code = getLanguage(context)
        if (code == SYSTEM) return context
        val locale = Locale.Builder().setLanguage(code).build()
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
