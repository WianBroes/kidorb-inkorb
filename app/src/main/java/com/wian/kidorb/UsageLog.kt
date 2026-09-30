package com.wian.kidorb

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal double canal : logcat (réservé à un futur adb) + fichier lisible
 * dans le dossier privé de l'app (`/sdcard/Android/data/com.wian.kidorb/files/`).
 *
 * Pourquoi : depuis le conteneur proot (uid app, pas shell/root), logcat ne
 * montre que les logs de son propre uid — ceux de l'app sont invisibles
 * (filtrage framework Android). Le fichier est lisible depuis le conteneur
 * via ce dossier.
 *
 * Écrit via `getExternalFilesDir` (dossier privé spécifique à l'app), pas
 * `MediaStore.Downloads` (dossier public partagé) : `MediaStore.Downloads`
 * n'existe qu'à partir d'Android 10 (API 29) et a fait planter l'app au
 * lancement sur tout appareil plus ancien (`NoClassDefFoundError`, non
 * rattrapé par le `catch Exception` existant car c'est un `Error`, pas une
 * `Exception` — trouvé via logcat réel sur un Redmi le 29/08). Le dossier
 * privé fonctionne identiquement sur toutes les versions d'Android depuis
 * l'API 24 (minSdk du projet) sans aucune permission — contrairement au
 * dossier public Téléchargements sur les Android < 10, qui aurait demandé
 * `WRITE_EXTERNAL_STORAGE` et cassé la déclaration "zéro permission" déjà
 * publiée (politique de confidentialité + Sécurité des données Play Store).
 *
 * Horodatage avec millisecondes : permet de mesurer les durées de contact
 * (utile pour le diagnostic du chargement de couleur).
 */
object UsageLog {

    private const val FILE_NAME = "usage.log"
    private const val TAG = "UsageLog"

    private lateinit var context: Context
    private var file: File? = null
    private val lock = Any()

    /** État du journal, affiché dans le HUD de diagnostic : "OK (n écritures)"
     *  ou le dernier message d'erreur. */
    var lastStatus: String = "init"
    private var writeCount = 0

    // 2026-08-25, demande explicite : "je dois pouvoir désactiver dans le
    // menu dev les différents logs" — app donnée à quelqu'un d'autre, plus
    // de raison d'accumuler un fichier de diagnostic ni de bruiter son
    // logcat. Coupe les deux canaux d'un coup.
    // 2026-09-02, demande explicite : "il faut bien se dire que c'est
    // destiné au grand public" — false par défaut, réactivable depuis Mode
    // développeur (MainActivity synchronise ce champ sur usageLogEnabled).
    var enabled: Boolean = false

    fun init(context: Context) {
        this.context = context.applicationContext
        try {
            val dir = this.context.getExternalFilesDir(null) ?: return
            // Retrouve le plus récent fichier horodaté existant (app
            // redémarrée entre deux sessions) — sinon créé à la 1re écriture.
            file = dir.listFiles { f -> f.name.startsWith(FILE_NAME) }
                ?.maxByOrNull { it.lastModified() }
        } catch (_: Throwable) {
        }
    }

    fun d(msg: String) = log("D", msg)
    fun w(msg: String) = log("W", msg)
    fun e(msg: String) = log("E", msg)

    private fun log(level: String, msg: String) {
        if (!enabled) return
        Log.d(TAG, "$level $msg") // canal logcat
        if (!::context.isInitialized) return
        synchronized(lock) {
            try {
                var f = file
                if (f == null) f = createFile()
                if (f == null) {
                    lastStatus = "createFile = null"
                    return
                }
                FileOutputStream(f, true).use { fos ->
                    val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE).format(Date())
                    fos.write("[$ts] $level $msg\n".toByteArray())
                }
                writeCount++
                lastStatus = "OK ($writeCount)"
            } catch (e: Throwable) {
                lastStatus = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "UsageLog: échec écriture fichier", e)
            }
        }
    }

    private fun createFile(): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date())
        val f = File(dir, "$FILE_NAME-$ts.txt")
        file = f
        return f
    }
}
