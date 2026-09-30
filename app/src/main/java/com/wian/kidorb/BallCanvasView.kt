package com.wian.kidorb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Canvas de jeu : des BILLES, une par CALQUE, poussées par la gravité projetée
 * sur l'écran (TYPE_GRAVITY) + coups de poignet (TYPE_GYROSCOPE) + poussée au
 * doigt — comportement billard (inertie, rebonds).
 *
 * Peinture v9 (multi-calques) :
 * - chaque calque = une bille + sa couche de peinture + ses spots + une
 *   opacité de rendu (0 = invisible, 1 = opaque) ;
 * - une bille ne prend que les couleurs présentes sur SON calque (elle
 *   échantillonne sa couche, pas celle des autres) ;
 * - le calque est transparent par défaut : la peinture d'un calque se
 *   superpose aux autres selon son opacité ;
 * - les billes ont chacune une COULEUR DE BASE (réglable) : la couleur de
 *   la bille (halo, retour après un contact). La bille ne peint QUE quand
 *   elle porte une couleur chargée (contact + sillage) — pas de trace en
 *   roulant « à vide » ; elle ne « touche » que des couleurs qui diffèrent
 *   de ce qu'elle porte (échantillonnage dense sur tout le disque, bords
 *   semi-transparents ignorés) ;
 * - traits semi-transparents et à largeur variable (texture irrégulière) ;
 * - taches posées DANS la couche (ordre temporel) : une tache est au-dessus
 *   de tout ce qui existe au moment où on la pose, et la peinture posée
 *   après (traînée de la bille, lignes) la recouvre — comme de la peinture
 *   réelle.
 * - la peinture « sèche » : seuls les traits frais (moins de 8 s) rechargent
 *   la bille — les anciens restent visibles mais ne l'alimentent plus.
 *
 * Gestes : boutons en haut pour ouvrir les menus (plus de swipe qui
 * déclenche des traits accidentels) ; pour fermer un panneau, le repousser
 * vers son origine (← palette, → calques, ↓ config) ; tap = rond,
 * appuyer et bouger = trait continu ; doigt sur une bille = la soulever
 * puis la lancer (elle écrit au lancer). Undo via le bouton « Annuler ».
 */
class BallCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    /** Mode de mélange — UN SEUL : pigments réels (Kubelka-Munk, Spectral.js). */
    companion object {
        // Valeurs par défaut — réglables en direct via les menus
        private const val DEF_GRAVITY_TO_PX = 300f // m/s² de gravité projetée → px/s²
        private const val DEF_SMOOTHING = 0.5f     // filtre passe-bas du capteur (1 = brut)
        private const val DEF_TRAIL_WIDTH_DP = 26f // épaisseur du trait / traînée — indépendante de la bille
        private const val DEF_BALL_RADIUS_DP = 34f // rayon de la bille
        private const val DEF_GOMME_RADIUS_DP = 35f // rayon des gommes (~70px à densité 2x, valeur fixe d'avant)
        private const val DEF_RESTITUTION = 0.85f  // rebond billard sur les bords (0 = aucun, 1 = élastique)

        private const val DEF_FRICTION_RATE = 0.6f    // décélération par seconde (exp) — inertie billard
        // 2026-08-13 : diagnostic logs — un lancer moyen dépasse déjà largement 3000
        // (VelocityTracker donne couramment 8000-80000 px/s selon le zoom, cf.
        // référence Android elle-même ~4000 px/s pour un fling "maximum"), et
        // l'ancien clamp DUR (if speed > MAX_SPEED) écrasait tout lancer moyen ou
        // fort à la MÊME vitesse plafond dès la frame suivante — d'où l'impression
        // de non-proportionnalité signalée. Plafond relevé à 6000 ET rendu SOUPLE
        // (tanh, cf. recherche « soft clamping ») : au lieu d'un mur, la vitesse
        // sature progressivement — un lancer 2× plus fort reste visiblement plus
        // rapide au lieu d'être identique.
        private const val MAX_SPEED = 6000f      // plafond de vitesse (px/s), désormais souple (tanh)
        private const val SPEED_COLOR_SATURATION = 0.85f // saturation HSV pleine (comète/arc-en-ciel), avant fondu vers le blanc
        private const val SPEED_COLOR_EDGE_BLACK = 0.25f // largeur (fraction de MAX_SPEED) de la bande noire de speedColor, élargie le 2026-08-30 (demande explicite)
        private const val SPEED_COLOR_EDGE_WHITE = 0.1f // largeur (fraction de MAX_SPEED) de la bande blanche de speedColor
        private const val MAX_FLING_SCREEN_PX_S = 8000f // borne le VelocityTracker à la source (px/s écran, avant /camScale) — élimine les pointes d'extrapolation aberrantes
        private const val THROW_ACCEL_WINDOW_MS = 220L // fenêtre récente examinée pour juger si le geste accélère juste avant le lâcher (ni trop courte = bruit, ni trop longue = capte tout le hold immobile)
        private const val THROW_ACCEL_BOOST_MAX = 1.6f    // ratio vitesse fin-de-geste / début-de-geste au-delà duquel on plafonne le bonus
        private const val THROW_ACCEL_BOOST_WEIGHT = 0.6f // poids du bonus d'accélération (0 = ignoré, 1 = bonus plein) — modéré car le plafond souple sature vite si on cumule trop de multiplicateurs
        private const val GYRO_TO_VEL = 150f     // rotation gyroscope (rad/s) → impulsion (px/s²)
        private const val GYRO_DEADZONE = 0.25f  // rotation minimale pour compter (bruit du capteur)
        private const val SAMPLE_SNAPSHOT_MS = 250L // fraîcheur de la photo d'échantillonnage (cf. commentaire de sampleSnapshot) — assez récent pour exclure le tracé de la frame en cours, assez ancien pour ne jamais rester figé sur un contact continu
        private const val INK_AMOUNT = 3000f     // réserve d'encre en px de traînée (600 → 1500 → 3000, 2026-08-19 : "le trait s'arrête net sur un trajet long" — trajets sans repasser sur de la peinture existante s'interrompaient trop tôt)
        private const val RECHARGE_RATE = 2000f  // px de réserve remplis par seconde au contact (progressive)
        private const val MIX_RATE = 0.4f        // vitesse du fondu pigmentaire par frame (~5 frames pour converger : visible mais réactif)
        private const val MELANGE_PROTECTION_MS = 600L   // mode expérimental 1/3 : durée pendant laquelle la couleur quittée est ignorée/ralentie
        private const val MELANGE_RAYON_FACTOR = 0.45f   // mode expérimental 2 : rayon d'échantillonnage couleur (pas le rayon de contact/sillage, inchangé)
        private const val MELANGE_RETOUR_LENT_FACTOR = 0.25f // mode expérimental 3 : multiplicateur de fondu SEULEMENT vers la couleur quittée
        private const val MIN_MOVE = 0.15f       // distance minimale pour dessiner un trait (px)
        // 2026-08-30, simplification après recherche externe ("ça va pas,
        // simplifierait pas le système ?") : le trait de bille passe d'un
        // segment de ligne (bouts ronds) par frame à un TAMPONNAGE de
        // cercles pleins régulièrement espacés — technique standard des
        // moteurs de pinceau (Krita/Clip Studio/Photoshop), cf. commentaire
        // de stampBallTrail(). Remplace l'accumulation de segment (v446,
        // MIN_SEGMENT_WIDTH_RATIO) ET le dégradé par LinearGradient (v445,
        // lastTrailColor/drawBallTrailSegment/joinPrevBallX-Y) d'un coup —
        // un seul mécanisme au lieu de deux rustines empilées.
        // 2026-08-30, retour direct sur screenshot réel : "ya tjs des
        // cercles" — 20% (v447) laissait chaque tampon individuellement
        // visible, contours ronds distincts au lieu d'un tube lisse.
        // Resserré à 8% (repères de moteurs de pinceau réels : 5-10% du
        // diamètre pour un rendu lisse), plafond de tampons/frame relevé en
        // conséquence pour ne pas retrouver des trous à vitesse élevée.
        private const val TRAIL_STAMP_SPACING_RATIO = 0.08f // écart entre 2 tampons, fraction de la largeur du trait — plus petit = plus lisse/coûteux
        // plafond de tampons dessinés en UN appel (protège le coût CPU/frame à
        // vitesse extrême) — la distance non peinte au-delà de ce plafond
        // n'est PAS perdue, elle se rattrape à l'appel suivant (cf.
        // stampBallTrail, correctif du 2026-08-31 sur les pointillés).
        // 2026-08-31, suite : log réel (usage.log, capture "c'est la
        // vitesse") a montré des rattrapages allant jusqu'à ~1665px en une
        // seule rafale (soit >300 tampons), donc étalés sur PLUSIEURS
        // frames avec l'ancien plafond de 60 — largeur du trait restée
        // constante entre-temps (pas un problème de largeur), mais chaque
        // frame de rattrapage vise la position ACTUELLE de la bille (qui a
        // continué d'avancer), pas la vraie trajectoire parcourue : le
        // tracé de rattrapage "coupe" vers la bille plutôt que de rejouer
        // fidèlement le chemin. Relevé à 400 (couvre large les rafales
        // observées, ~2000px en un seul appel) pour que le rattrapage tienne
        // quasiment toujours en une frame — dessiner quelques centaines de
        // cercles plats de plus en une frame reste bon marché.
        private const val MAX_STAMPS_PER_FRAME = 400
        // 2026-08-31, diagnostic temporaire sur les pointillés à vitesse —
        // désactivé une fois les données du log récupérées (cf. commentaire
        // ci-dessus) ; laissé en place (mettre à true) si le symptôme
        // revient malgré le relevage du plafond.
        private const val STAMP_DEBUG_LOG = false
        // 2026-08-31, trailEdgeMode==2 ("net + doux") : part de rattrapage de
        // `smoothedStampColor` vers la couleur cible à CHAQUE tampon (EMA) —
        // 0.25 converge en ~10 tampons (cf. commentaire de stampBallTrail),
        // grosso modo la largeur du trait parcourue avant transition complète.
        private const val STAMP_COLOR_SMOOTH_FACTOR = 0.25f
        // 2026-09-04, même principe pour le pinceau (demande explicite :
        // "mettre cette option [Net + doux] sur les pinceaux") — le pinceau
        // dessine par segments de ligne, pas par tampons, mais le même EMA
        // par appel (un appel = un segment = un ACTION_MOVE) donne le même
        // effet : contour du trait net (une ligne pleine, pas de dégradé de
        // bord), couleur qui fond progressivement sur quelques segments au
        // lieu de sauter net quand on change le mélangeur en plein trait.
        private const val PINCEAU_COLOR_SMOOTH_FACTOR = 0.25f
        // re-échantillonnage du trait pinceau (dessinerSegment) — même ratio
        // que TRAIL_STAMP_SPACING_RATIO côté bille, pour que le fondu EMA
        // avance sur le même rythme (~10 pas par largeur de trait parcourue)
        // des deux côtés.
        private const val PINCEAU_STEP_RATIO = 0.08f
        // garde-fou seulement (même rôle que MAX_STAMPS_PER_FRAME côté
        // bille) : un ACTION_MOVE réel entre 2 doigts humains ne parcourt
        // jamais des centaines de px, cette limite ne devrait donc jamais
        // s'atteindre en usage normal. Contrairement à la bille (état
        // persistant entre frames), un `dessinerSegment` non fini au
        // plafond laisserait un vrai trou (pas de reliquat repris plus
        // tard) — plafond volontairement large pour rester purement
        // défensif (entrée aberrante/simulée), pas un plafond de confort.
        private const val PINCEAU_MAX_SUBSTEPS = 600

        private const val FLING_MAX_MS = 350L    // un geste plus long n'est pas un swipe (tracé)

        private const val INK_ALPHA = 0xFF        // traits opaques (le fond ne transparaît plus)
        private const val COLOR_EPS = 40          // seuil ΔRGB pour « toucher » une couleur
        private const val TEXTURE_SCALE = 0.02f   // fréquence spatiale de la texture (par px)

        private const val DEF_BACKGROUND = 0xFFF7F3EC.toInt() // papier (fond par défaut)
        private const val BALL_COLOR = 0xFF202124.toInt()

        private const val HANDLE_RADIUS = 26f // rayon visuel + tactile des poignées d'édition (Ligne, Triangle, Bouchon) une fois sélectionnés
        private const val DELETE_MARKER_RADIUS = 28f // rayon visuel + tactile de la petite croix de suppression (2026-08-11, agrandie sur retour d'usage : "un peu plus grande")
        // 2026-08-13, demande explicite : "tout les outils... puissent avoir les
        // mêmes tailles minimum ou maximum, histoire que ce soit homogène" —
        // avant, chaque type avait ses propres bornes, ET certains types avaient
        // des bornes DIFFÉRENTES entre le pincement, la poignée dédiée et le
        // redimensionnement à la création (Accélérateur : 15 vs 20 ; Ellipse :
        // 5 vs 10 ; Portail plafonné à 300 quand Bouchon allait jusqu'à 3000).
        // Bornes communes à tous les obstacles circulaires (Bouchon, Portail,
        // Planète [noyau], Accélérateur, Ellipse) désormais partagées partout.
        private const val OBSTACLE_R_MIN = 10f
        private const val OBSTACLE_R_MAX = 3000f
        // 2026-08-13, suite : "le cercle déformable et l'accélérateur ne sont
        // pas homogénéisés" — les BORNES de resize l'étaient déjà (ci-dessus),
        // mais chaque type avait sa propre taille PAR DÉFAUT à la création
        // (Bouchon 30, Portail 45, Planète/Ellipse 60, Accélérateur 95 — 3×
        // Bouchon) : visible immédiatement au placement, avant même de
        // toucher une poignée. Une seule taille de départ pour tous.
        private const val OBSTACLE_DEFAULT_R = 60f
        private const val PORTAIL_DEFAULT_R = OBSTACLE_DEFAULT_R // rayon par défaut d'un disque de portail à la création
        private const val RESUME_PEINDRE_APRES_PINCH_MS = 220L // délai avant que le doigt restant après un pinch/pan puisse repeindre (2026-08-11, demande explicite : "faudrait un petit délai avant que ça repeigne")
        private const val TOUCH_PUSH_SCALE = 0.4f    // fraction de la vitesse du doigt transmise à la bille au contact en pinceau libre (ballGrabInPinceau, 2026-08-20) — à ajuster sur pièce
        private const val DEF_SHAKE_TO_VEL = 800f    // m/s² d'accélération linéaire → impulsion (px/s²)
        private const val DEF_SHAKE_THRESHOLD = 3.5f  // seuil d'accélération pour détecter une secousse (m/s²)
        private const val DEF_GRAVITY_DEADZONE = 4f   // zone morte inclinaison (°) — en dessous, la bille ne bouge pas
        private const val PLANETE_DEFAULT_R = OBSTACLE_DEFAULT_R // rayon du noyau solide d'une planète
        private const val PLANETE_DEFAULT_INFLUENCE = 300f // rayon d'influence gravitationnelle
        private const val ACCELERATEUR_DEFAULT_R = OBSTACLE_DEFAULT_R // rayon de la zone à la création (2026-08-12 : 70 trop petit puis 130 trop grand ; 2026-08-13 : uniformisé à OBSTACLE_DEFAULT_R — resize possible après coup)
        private const val ACCELERATEUR_RATE = 4000f      // px/s² à jauge pleine (±1) (2026-08-12 : 900 quasi imperceptible, la bille traverse trop vite)
        private const val PLANETE_FALLOFF_START = 0.75f  // fraction de influenceRadius où le fondu de sortie commence (avant : coupure nette au bord)
        private const val TRAMPOLINE_RESTITUTION = 1.3f  // fixe, ignore restitution de la bille (>1 = regagne un peu d'énergie, sensation "trampoline" même pour une bille peu rebondissante)
        // 2026-08-29, rapporté après le premier essai du slider log : "la
        // valeur est trop élevée besoin de partir de 0 et vers bcp plus" —
        // min réellement à 0 (aucune gravité, pas 1M comme avant) et
        // plafond relevé ×3,3 (300M → 1G) pour laisser de la marge en haut.
        // Le slider correspondant (addSliderPow) n'est plus logarithmique
        // (incompatible avec un minimum à 0) mais en courbe puissance —
        // 0 exact au curseur tout en bas, plus fin en bas de plage qu'en haut.
        const val PLANETE_MASS_MIN = 0f
        const val PLANETE_MASS_MAX = 1000000000f
        const val PLANETE_MASS_DEFAULT = 12000000f  // masse (force de l'attraction) — ×60 pour être comparable à gravityToPx
        private const val ELLIPSE_DEFAULT_R = OBSTACLE_DEFAULT_R // rx/ry par défaut à la création

    }

    /** Un calque = une bille + sa couche de peinture + son opacité. */
    private class UndoSnapshot(
        val calque: Calque,
        val paint: Bitmap?, // null = geste d'OBSTACLES uniquement (peinture inchangée — snapshot léger)
        val wet: Bitmap?,
        val obstacles: List<Obstacle> // les obstacles de construction (annulables aussi)
    )
    private class Calque(
        var baseColor: Int,
        var opacity: Float,          // opacité de rendu du calque (0..1)
        var ballX: Float,
        var ballY: Float,
        var velX: Float = 0f,
        var velY: Float = 0f,
        var prevPosX: Float = 0f,
        var prevPosY: Float = 0f,
        var carriedColor: Int? = null, // couleur portée par la bille (prise directe au contact)
        var inkReserve: Float = 0f,    // réserve d'encre en px de traînée (épuisement type v1)
        var wasInContact: Boolean = false,
        // largeur du trait EFFECTIVEMENT utilisée par stampBallTrail — plus
        // lissée elle-même depuis le 2026-08-31 (cf. smoothedWidthSpeed
        // ci-dessous) : juste la dernière `cibleTrailWidth()` calculée à
        // partir d'une vitesse déjà lissée. Un 2e étage de lissage ICI (en
        // plus de celui sur la vitesse) empilait 2 lissages successifs —
        // retardait doublement un saut de vitesse légitime (lancer),
        // produisant une transition non monotone plutôt qu'un simple délai.
        var smoothedTrailWidthBall: Float = -1f,
        var smoothedColorSpeed: Float = -1f, // lisse la vitesse utilisée par speedColor() — évite les sauts de teinte brusques sur un pic de vitesse d'une seule frame (rebond, collision)
        // 2026-08-31, demande explicite ("encore des pointillés... c'est la
        // vitesse de la bille" — capture réelle : silhouette en bosses/
        // étranglements, pas des trous) : `cibleTrailWidth()` (cometTrail/
        // vitesseEpaisseur) recevait la vitesse BRUTE (hypot(velX,velY)),
        // seule la largeur RÉSULTANTE était lissée ensuite
        // (smoothedTrailWidthBall) — mais cometTrail transforme la vitesse
        // en largeur par une courbe très raide (jusqu'à 88% de variation),
        // donc le moindre bruit de vitesse (rebonds, bruit capteur
        // d'inclinaison) ressort amplifié en à-coups de largeur malgré le
        // lissage en aval. Même leçon déjà tirée pour speedColor() le
        // 2026-08-29 ("certains switch de couleur sont un peu brusque",
        // cf. smoothedColorSpeed ci-dessus) : lisser la vitesse EN AMONT de
        // la courbe non linéaire, pas seulement son résultat.
        var smoothedWidthSpeed: Float = -1f,
        // 2026-08-30, remplace joinPrevBallX/Y + lastTrailColor (2 rustines
        // successives, cf. CHANGELOG) — dernier point où un TAMPON a
        // vraiment été posé (cf. stampBallTrail), distinct de prevPosX/Y
        // (position brute frame par frame, utilisée ailleurs pour la
        // physique/collision) : le trajet entre l'ancien et le nouveau
        // point est rempli de tampons régulièrement espacés.
        var lastPaintX: Float = Float.NaN,
        var lastPaintY: Float = Float.NaN,
        var lastSample: Int? = null,
        var paintLayer: Bitmap? = null,   // couche visible (tout y reste)
        val paintCanvas: Canvas = Canvas(),
        var wetLayer: Bitmap? = null,     // couche « humide » : seule détectée par la bille
        val wetCanvas: Canvas = Canvas(),
        var spotCount: Int = 0,
        // Photo RAFRAÎCHIE PÉRIODIQUEMENT de la couche humide (2026-08-10,
        // bug réel : mélanges de couleur peu convaincants avec les enfants,
        // log confirmé — 13s de contact continu avec une zone rouge pure
        // #EE0000, `carried` restait bloqué en brun sombre et `sample`
        // sautait entre rouge pur et brun boueux frame à frame). Cause : le
        // tracé de la bille (1.9×ballRadius de large) couvre ~95% du
        // diamètre du disque d'échantillonnage (2×ballRadius) — dès qu'elle
        // a roulé une fois sur une zone, le disque est presque entièrement
        // sa PROPRE traînée récente, et le filtre « distinct de
        // carriedColor » ne l'exclut qu'imparfaitement (carriedColor dérive
        // à chaque frame, donc la traînée d'il y a quelques frames ne
        // matche déjà plus exactement la référence courante). Même leçon
        // que Carnet (CONCEPT.md) : ne jamais échantillonner ce qu'on vient
        // tout juste de déposer soi-même.
        // v1 de ce fix (figée au DÉBUT de chaque contact) s'est révélée
        // PIRE à l'usage (log confirmé, 2026-08-10) : le tracé de la bille
        // couvrant ~95% de son propre disque, `touching` reste quasiment
        // toujours vrai en roulant en continu — un seul CONTACT+ et ZÉRO
        // CONTACT- observés sur 2min30 de session réelle, donc la photo
        // n'était plus jamais reprise après le tout premier contact : la
        // bille devenait aveugle à toute nouvelle couleur peinte ensuite
        // (pire que le bug d'origine). Remplacé par un rafraîchissement
        // périodique (`SAMPLE_SNAPSHOT_MS`, indépendant des transitions de
        // contact) : assez récent pour exclure le tracé de LA frame en
        // cours (auto-contamination immédiate), assez ancien pour ne
        // jamais rester figé indéfiniment sur une session de contact
        // continu.
        var sampleSnapshot: Bitmap? = null,
        var sampleSnapshotAt: Long = 0L,
        // Modes expérimentaux "mélange couleur" (2026-08-29, cf. melangeExperiment) :
        // couleur qu'on vient de quitter (à éviter/ralentir un temps) + dernière
        // zone de couleur détectée (sert à repérer QUAND on vient de changer de zone).
        var avoidColor: Int? = null,
        var avoidUntil: Long = 0L,
        var prevZoneColor: Int? = null,
        // 2026-08-31, demande explicite : "un tracé aux contours nets mais
        // qui se comporte comme un pinceau doux à l'intérieur". 1re tentative
        // (dégradé radial opaque par tampon, couleur du tampon → couleur du
        // tampon précédent) écartée après 2 retours réels négatifs (capture
        // à l'appui) : un dégradé RADIAL à l'intérieur d'un tampon crée son
        // propre motif en rosace/bosse (comme une bille 3D ombrée), quel que
        // soit son rayon d'action — ce n'est pas ainsi qu'un pinceau doux
        // fond ses couleurs. Remplacé par un lissage exponentiel (EMA) de la
        // couleur du tampon lui-même (cf. STAMP_COLOR_SMOOTH_FACTOR) : chaque
        // tampon reste PLAT (comme le mode dur, donc le contour extérieur
        // reste net), mais sa couleur ne saute plus instantanément vers
        // `carriedColor` — elle le rattrape progressivement sur plusieurs
        // tampons, ce qui fond visuellement la transition sans jamais créer
        // de motif interne. null = pas encore de tampon sur ce contact.
        var smoothedStampColor: Int? = null
    )

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // Fusion des capteurs : TYPE_GRAVITY (repli ACCELEROMETER) pour
    // l'inclinaison (plan incliné), TYPE_GYROSCOPE pour les coups de poignet
    // (impulsions — frapper la bille comme au billard), TYPE_LINEAR_ACCELERATION
    // pour les secousses (secouer le téléphone = valdinguer la bille).
    private val gravitySensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // Paramètres réglables (globaux — s'appliquent à toutes les billes)
    var gravityToPx: Float = DEF_GRAVITY_TO_PX
    var shakeToVel: Float = DEF_SHAKE_TO_VEL
    var shakeThreshold: Float = DEF_SHAKE_THRESHOLD
    var gravityDeadZone: Float = DEF_GRAVITY_DEADZONE
    var smoothing: Float = DEF_SMOOTHING
    var trailWidthDp: Float = DEF_TRAIL_WIDTH_DP
        set(value) {
            field = value
            trailWidth = value * resources.displayMetrics.density
            invalidate() // sinon le changement ne se voit pas en pause (écran figé)
        }
    var ballRadiusDp: Float = DEF_BALL_RADIUS_DP
        set(value) {
            field = value
            ballRadius = value * resources.displayMetrics.density
            ballTrailWidthPx = ballRadius * 1.9f // tracé = 0.95 × diamètre (un chouïa moins)
            invalidate()
        }
    var ballColor: Int = BALL_COLOR // couleur du disque de la bille (2026-08-20, demande explicite : "j'ai besoin de pouvoir editer le visuel de la bille") — réglable par bille, cf. BilleProfile
    // 2026-08-25, demande explicite : "est-ce que tu pourrais utiliser ça
    // [le dégradé de volume des icônes de bille] pour la bille dans l'app ?
    // ajoute un toggle" — même principe que billeSphereDrawable
    // (MainActivity) : dégradé radial clair→sombre calculé depuis ballColor,
    // au lieu du disque plat + point de brillance fixe existant.
    var volumeMode: Boolean = false
    var restitution: Float = DEF_RESTITUTION
    var poids: Float = 1f // masse relative de la bille : lourde = inertielle (roule plus longtemps, réagit moins aux impulsions)
    var frictionRate: Float = DEF_FRICTION_RATE // décélération par seconde — 0 = roule indéfiniment, fort = s'arrête vite (2026-08-12, demande explicite : réglable, avant fixe)
    var fonduRate: Float = MIX_RATE // vitesse du fondu pigmentaire par frame — 3 préréglages au choix (Net/Doux/Très fondu), cf. onglet Réglages
    // 2026-08-29, en test avec Wian : sur un écran majoritairement couvert
    // d'une seule couleur, la bille retombe systématiquement dedans dès
    // qu'elle la retouche — le fondu (ci-dessus) est symétrique et sans
    // mémoire, donc "revenir" et "changer" vont à la même vitesse ; ce qui
    // change, c'est le temps de contact (la zone dominante est partout,
    // une couleur neuve n'est qu'une petite tache traversée en quelques
    // frames). Le séchage réglait ça en purgeant l'historique après 60s,
    // jugé trop brutal et retiré (cf. CHANGELOG 28/08). 3 pistes comparées
    // en usage réel — 0 = comportement d'origine (avant ce mécanisme) :
    // 1 = protection anti-retour (ignore un temps la couleur quittée),
    // 2 = rayon d'échantillonnage resserré (moins "aimantée" par le pourtour),
    // 3 = retour ralenti (fondu plus lent SEULEMENT vers la couleur quittée).
    // 2026-09-01, demande explicite : Protection anti-retour (1) confirmée
    // en usage réel (cf. CHANGELOG 31/08, combinée à "Net + doux") et
    // activée par défaut.
    var melangeExperiment: Int = 1
    // 2026-08-30, demande explicite : "les couleurs on de nouveau des
    // tendances a faire des melanges moche a tendance brun, caca d'oie" —
    // cf. PigmentMix.mix pour le détail des 2 pistes (0 = K-M réaliste
    // inchangé, 1 = chroma repoussée, 2 = fondu de teinte HSV).
    var mixVividMode: Int = 0
    // 2026-08-30, demande explicite : "le bord doux... pourrait changer la
    // donne car c'est moche, tous les demi-cercles en transition de
    // couleur" — cf. stampBallTrail() pour le détail. 0 = bord dur (défaut,
    // inchangé) ; 1 = dégradé radial opaque→transparent par tampon (adoucit
    // AUSSI le contour extérieur du trait entier, effet de bord signalé le
    // même jour) ; 2 = "net" (2026-08-31, demande explicite : "tracé aux
    // contours nets mais qui se comporte comme un pinceau doux à
    // l'intérieur") — dégradé opaque de bout en bout, couleur du tampon
    // courant vers celle du tampon précédent (jamais vers transparent) :
    // fond les transitions de couleur SANS jamais adoucir le contour, qui
    // reste net (alpha plein partout, comme le mode 0).
    var trailEdgeMode: Int = 0
    // 2026-08-29, demande explicite : "je peux choisir des valeurs par
    // défaut [pour planète/planète inverse/accélérateur] et que l'outil ne
    // sera modifiable que par leur taille" — les poignées dédiées (gravité
    // de la planète, jauge de l'accélérateur) réglables objet par objet sont
    // retirées (moins lisible qu'un réglage global, cf. Paramètres →
    // Développeur → Outils), remplacées par ces 3 valeurs par défaut
    // appliquées à la CRÉATION de chaque nouvel obstacle. La taille reste la
    // seule chose réglable après coup (poignée existante, inchangée).
    var planeteMassDefault: Float = PLANETE_MASS_DEFAULT
    var planeteInverseMassDefault: Float = PLANETE_MASS_DEFAULT
    var accelerateurGaugeDefault: Float = 0.6f // 0 = neutre (aucun effet) — évite un accélérateur inerte à la pose
    // 2026-08-29, demande explicite : "quand la balle passe dans la portée
    // de la planète, ça annulerait la gravité du téléphone dans la zone...
    // ça résoudrait pas les problèmes de fun avec ?" — sans ça, la gravité
    // d'inclinaison (accX/accY) continue de tirer la bille pendant qu'elle
    // est censée orbiter, ce qui brouille l'effet. Toggle plutôt que
    // comportement forcé ("il faut un toggle aussi") — réutilise nearPlanete
    // (déjà calculé pour couper la friction en orbite, cf. step()).
    var planeteCancelTilt: Boolean = false
    var textureAmount: Float = 0f // 0 = tracé parfaitement net (défaut — 0.2 laissait encore des bosses visibles en usage réel, vérifié sur pièce), 1 = texture ondulée max, réglable au curseur "Texture du trait"

    // Mode texture de bille : essayé puis abandonné (2026-08-22, "ça marche
    // pas, ça semble pas possible avec le moteur présent, efface la
    // fonction") — plusieurs mécanismes tentés (tampon du disque tourné,
    // puis point unique centré sur le trait), aucun satisfaisant à l'usage
    // réel. Retiré intégralement (BallCanvasView, MainActivity,
    // TextureDiskView.kt, BilleProfile, strings) plutôt que laissé inerte.
    var vitesseEpaisseur: Boolean = false // si vrai, la vitesse de la bille rétrécit son tracé
    // 2026-08-22, IDEAS.md "traînée qui rétrécit avec la vitesse" — effet
    // comète bien plus prononcé que vitesseEpaisseur ci-dessus (jusqu'à 88%
    // de réduction contre 45%) : trait presque net à vitesse max, épais à
    // l'arrêt. Priorité sur vitesseEpaisseur si les deux sont actifs (cas
    // improbable, pas de UI pour les combiner), cf. cibleTrailWidth().
    var cometTrail: Boolean = false
    // 2026-08-22, IDEAS.md "bille arc-en-ciel" — couleur qui boucle dans le
    // temps (teinte HSV, cycle de 3s) au lieu de rester fixe sur
    // couleurBille ; remplace la couleur au moment du RENDU seulement (ball
    // + trait), ne touche pas au mélange pigmentaire sous-jacent.
    var rainbowMode: Boolean = false

    /** Largeur cible du trait pour une vitesse donnée — comète (si active,
     *  prioritaire) > vitesse=épaisseur > largeur pleine. Partagé entre le
     *  tracé normal et les segments de sortie/entrée de téléportation. */
    private fun cibleTrailWidth(speed: Float): Float {
        val f = when {
            cometTrail -> (1f - 0.88f * (speed / MAX_SPEED).coerceIn(0f, 1f)).coerceIn(0.12f, 1f)
            vitesseEpaisseur -> (1f - 0.45f * (speed / MAX_SPEED).coerceIn(0f, 1f)).coerceIn(0.55f, 1f)
            else -> 1f
        }
        return textureWidth(ballTrailWidthPx * f)
    }

    // 2026-08-30, demande explicite : "la taille de la bille peut changer en
    // fonction de la vitesse aussi, car la elle est grosse et change pas en
    // fonction, alors que le tracé oui" — même principe que cibleTrailWidth
    // (tracé qui s'amincit avec la vitesse) appliqué au DISQUE de la bille
    // lui-même, purement visuel : ne touche pas `ballRadius` (rayon de
    // collision/physique, inchangé partout ailleurs). Rétrécit vers un point
    // à haute vitesse comme le fait déjà le tracé (tête de comète contractée,
    // traîne allongée).
    // 2026-09-01, demande explicite : "quand le tracé change de taille, il
    // faut que la bille change de taille en rapport avec" — ne couvrait que
    // cometTrail, alors que vitesseEpaisseur amincit aussi le tracé sans
    // jamais faire varier la bille (incohérence visuelle). Suit désormais
    // EXACTEMENT les mêmes options que cibleTrailWidth (comète prioritaire
    // sur vitesse=épaisseur, cf. son commentaire), avec la même règle
    // qu'avant pour l'intensité : la bille rétrécit deux fois moins que le
    // tracé sous la même option (comète : 45% vs 88% du tracé, floor 55% ;
    // vitesse=épaisseur : 20% vs 45% du tracé, floor 80%) — jamais aussi
    // radical que le tracé, pour rester visible/pilotable à MAX_SPEED.
    private fun cibleBallRadiusFactor(speed: Float): Float = when {
        cometTrail -> (1f - 0.45f * (speed / MAX_SPEED).coerceIn(0f, 1f)).coerceIn(0.55f, 1f)
        vitesseEpaisseur -> (1f - 0.20f * (speed / MAX_SPEED).coerceIn(0f, 1f)).coerceIn(0.80f, 1f)
        else -> 1f
    }

    /** Teinte HSV pleinement saturée/lumineuse — mêmes paramètres pour
     *  rainbowColor (arc-en-ciel) et speedColor (comète), cf. leur
     *  commentaire respectif : demande explicite du 2026-08-30 de faire
     *  matcher les couleurs de la comète sur celles de l'arc-en-ciel. */
    private fun hueColor(hueDeg: Float): Int = Color.HSVToColor(floatArrayOf(hueDeg, SPEED_COLOR_SATURATION, 1f))

    /** Couleur en boucle (teinte HSV, cycle de 3s) pour le mode arc-en-ciel. */
    private fun rainbowColor(): Int {
        val hue = (SystemClock.uptimeMillis() % 3000L) / 3000f * 360f
        return hueColor(hue)
    }

    // 2026-08-22, demande explicite : "un toggle pour que la vitesse influe
    // la couleur... vers le bleu la lenteur et vers le jaune rouge la
    // vitesse, comme la rentrée dans l'atmosphère" — 1er jet en RGB brut
    // donnait un dégradé qui vire au terne/marron en transition (capture à
    // l'appui). Reconstruit sur le même espace perceptuel que le mélangeur
    // de couleur de l'app (MelangeurView, OKLCh via PigmentMix.oklchToArgb,
    // mêmes L/chroma par défaut que la carte du mélangeur) : un simple
    // balayage de teinte reste vif sur tout le trajet, pas de zone grise au
    // milieu. Priorité sur rainbowMode si les deux toggles sont actifs en
    // même temps (cas improbable, pas de UI pour les combiner).
    var speedColorMode: Boolean = false

    /** Vitesse lissée (même principe que `smoothedTrailWidthBall`, facteur
     *  proche) pour `speedColor()` — 2026-08-29, retour réel sur v433 : "certain
     *  switch de couleur sont un peu brusque". La vitesse brute (`hypot(velX,
     *  velY)`) peut sauter fortement d'une frame à l'autre (rebond, collision)
     *  et avec 3 tours de teinte (v433) un petit saut de vitesse suffit à
     *  produire un grand saut de teinte perceptible. Lisser la vitesse plutôt
     *  que la couleur directement — la couleur elle-même reste calculée d'un
     *  coup à partir d'une seule vitesse cohérente, pas de fondu séparé entre
     *  2 couleurs OKLCh (qui traverserait des teintes intermédiaires non
     *  voulues). */
    private fun smoothedColorSpeed(c: Calque, raw: Float): Float {
        val s = if (c.smoothedColorSpeed < 0f) raw else c.smoothedColorSpeed + (raw - c.smoothedColorSpeed) * 0.18f
        c.smoothedColorSpeed = s
        return s
    }

    /** Même principe que `smoothedColorSpeed` ci-dessus, dédié à la largeur
     *  du trait (`cibleTrailWidth`, cometTrail/vitesseEpaisseur) — champ
     *  séparé (`smoothedWidthSpeed`) pour ne pas mélanger 2 usages distincts
     *  de la vitesse lissée dans le même état. 2026-08-31, cf. commentaire
     *  de `Calque.smoothedWidthSpeed`. */
    private fun smoothedWidthSpeed(c: Calque, raw: Float): Float {
        val s = if (c.smoothedWidthSpeed < 0f) raw else c.smoothedWidthSpeed + (raw - c.smoothedWidthSpeed) * 0.18f
        c.smoothedWidthSpeed = s
        return s
    }

    // 2026-08-30, demande explicite : "les couleurs de la comète sont en
    // partie moche, polluée, même ordre que l'arc-en-ciel, les couleurs
    // sont plus belles" — remplace l'ancien balayage OKLCh (noir à l'arrêt →
    // teintes → blanc au plus rapide, chroma quasi nulle près des extrêmes,
    // cf. historique des 22-29/08 ci-dessous conservé pour mémoire) par le
    // même espace HSV que rainbowColor — piloté par la vitesse plutôt que
    // le temps, même ORDRE de teinte (0→360°, un seul tour) plutôt que le
    // balayage à 3 tours décalé de 270°.
    // Complété le même jour ("ya moyen d'ajouter le blanc et le noir dans ce
    // schéma ?") : noir à l'arrêt et blanc au plus rapide réintroduits comme
    // demandé le 22/08, mais confinés à une bande ÉTROITE aux deux extrémités
    // au lieu d'un dégradé de chroma sur tout le trajet — c'est ce dégradé
    // large qui rendait les couleurs ternes/"polluées" dans l'ancienne
    // version OKLCh. Le cœur du trajet reste teintes pleinement vives,
    // comme l'arc-en-ciel.
    // 2e retour, même jour : "faut la plage noir plus grande, et j'aimais
    // bien que le noir soit proche du violet, rouge" — bande noire élargie
    // (SPEED_COLOR_EDGE_BLACK, la blanche inchangée à SPEED_COLOR_EDGE_WHITE)
    // et décalage de teinte remis à 270° (comme l'ancien balayage OKLCh,
    // cf. son commentaire ci-dessus "bleu→violet→rouge→orange→jaune") au
    // lieu de 0° (rouge) : juste après le noir, la teinte traverse
    // bleu-violet(270°)→magenta(300°)→rouge(360°) avant le reste du tour.
    private fun speedColor(speed: Float): Int {
        // MAX_SPEED=6000 vérifié sur les logs d'usage réel (p75 des
        // vitesses observées déjà à 6000, pas un plafond théorique jamais
        // atteint) — dénominateur inchangé depuis la version OKLCh.
        val ratio = (speed / MAX_SPEED).coerceIn(0f, 1f)
        val hue = (270f + ratio * 360f) % 360f
        val value = (ratio / SPEED_COLOR_EDGE_BLACK).coerceIn(0f, 1f) // 0→1 sur la bande basse (noir → vif)
        val sat = (1f - (ratio - (1f - SPEED_COLOR_EDGE_WHITE)) / SPEED_COLOR_EDGE_WHITE).coerceIn(0f, 1f) * SPEED_COLOR_SATURATION // pleine→0 sur la bande haute (vif → blanc)
        return Color.HSVToColor(floatArrayOf(hue, sat, value))
    }

    // 2026-08-22, demande explicite : "augmenter les effets de l'inclinaison,
    // si c'est peu incliner la bille va lentement et plus ça s'incline la
    // bille accélère" — la réponse actuelle est déjà linéaire (gravityX ×
    // gravityToPx), donc plate/peu marquée. Courbe au carré (signe préservé,
    // ré-échelonnée pour matcher le même maximum qu'en linéaire à 90°) :
    // douce aux petites inclinaisons, nettement amplifiée aux fortes.
    // Toggle séparé, pas de changement sur la constante gravityToPx
    // existante — comportement par défaut strictement inchangé si off.
    var tiltCurveMode: Boolean = false

    // Mode expérimental "réponse à l'inclinaison" (2026-08-30, retour d'un
    // testeur externe transmis par Wian : "la bille est lente, met du temps
    // à se mettre en mouvement, sensation de lourdeur" — la courbe au carré
    // ci-dessus (tiltCurveMode=true sur la bille par défaut à
    // l'installation) écrase la réponse à une inclinaison normale/modérée :
    // à ~20-30° elle ne délivre qu'environ 10-15% de l'accélération
    // linéaire équivalente. Global (comme melangeExperiment ci-dessus),
    // PAR-DESSUS le tiltCurveMode de chaque bille : 0 = respecte
    // tiltCurveMode de la bille active (exposant 2) ; 1 = force la réponse
    // LINÉAIRE sur toutes les billes, ignore tiltCurveMode ; 2 = courbe
    // adoucie (exposant 1.4 au lieu de 2) sur les billes qui ont
    // tiltCurveMode actif, les autres restent linéaires comme aujourd'hui.
    // 2026-09-01, demande explicite : linéaire (1) devient le défaut —
    // corrige la lourdeur ressentie par un testeur externe (cf. CHANGELOG
    // 30/08).
    var tiltResponseMode: Int = 1

    private fun curvedTilt(g: Float): Float {
        if (!tiltCurveMode || tiltResponseMode == 1) return g
        val n = (g / 9.81f).coerceIn(-1f, 1f)
        val exposant = if (tiltResponseMode == 2) 1.4f else 2f
        return sign(n) * abs(n).pow(exposant) * 9.81f
    }
    var rechargeProgressive: Boolean = true // si vrai, la jauge se remplit graduellement au contact
    var showJauge: Boolean = true // afficher la jauge d'encre en bas
        set(value) {
            field = value
            invalidate() // sinon pas de rafraîchissement visible en pause
        }
    var selectedColor = 0xFFE53935.toInt() // couleur sélectionnée (rond / trait)
    // 2026-08-22, demande explicite : "un éditeur de pinceaux à la suite des
    // billes" — texture et fondu du PINCEAU, séparés des champs équivalents
    // de la BILLE (textureAmount/fonduRate ci-dessous, partagés jusqu'ici
    // par erreur avec dessinerSegment — désormais chacun les siens, cf.
    // pinceauTextureWidth()/pinceauMixedColor()). couleur/largeur du trait
    // restaient déjà indépendantes (selectedColor/trailWidthDp).
    var pinceauTextureAmount: Float = 0f
    var pinceauFonduRate: Float = 0.4f
    // 2026-08-22, demande explicite : "j'ai besoin des 2 options" — mélange
    // actif = comportement ci-dessus (couleur figée au début du trait,
    // fondu au contact d'une autre couleur) ; inactif = comportement
    // d'avant l'ajout du fondu, couleur du mélangeur prise EN DIRECT à
    // chaque point (change immédiatement si on retouche le mélangeur en
    // plein trait, jamais de mélange avec ce qui est dessous).
    var pinceauMelangeActif: Boolean = true
    private var pinceauCarriedColor: Int? = null
    // 2026-09-04, demande explicite : "mettre cette option [fondu Net +
    // doux, cf. trailEdgeMode de la bille] sur les pinceaux" — lissage EMA
    // de la couleur RÉELLEMENT posée (après mélange pigmentaire éventuel),
    // sur un axe indépendant de pinceauMelangeActif (qui mélange avec ce
    // qu'il y a DESSOUS) : sans ça, changer de couleur sur le mélangeur en
    // plein trait saute net d'un segment au suivant. Le contour de la ligne
    // reste net par nature (pas de dégradé de bord comme le mode "Doux" de
    // la bille, pas transposable à une ligne) — seule la couleur fond.
    // Remis à null aux mêmes points que pinceauCarriedColor (nouveau trait).
    private var pinceauSmoothedColor: Int? = null

    private fun pinceauTextureWidth(base: Float): Float {
        if (pinceauTextureAmount <= 0.01f) return base
        val wobble = 0.5f + 0.5f * sin(inkPhase * 0.9f) * sin(inkPhase * 2.3f + 1.7f)
        val amp = 0.28f * pinceauTextureAmount
        return (base * (1f - amp + 2f * amp * wobble)).coerceAtLeast(base * (1f - amp))
    }

    /** Mélange pigmentaire du pinceau — bien plus simple que celui de la
     *  bille (pas de rayon, pas de moyenne pondérée, pas de snapshot) : un
     *  point de contact, pas un disque qui roule, donc un seul pixel
     *  échantillonné suffit. Couleur "portée" tant que le trait continue,
     *  remise à zéro à chaque nouveau trait (cf. pinceauCarriedColor = null
     *  aux points de départ de geste). Le résultat (mélangé ou EN DIRECT
     *  selon pinceauMelangeActif) passe ensuite par un lissage EMA
     *  (pinceauSmoothedColor, cf. commentaire plus haut) avant d'être posé. */
    private fun pinceauMixedColor(cc: Calque, x: Float, y: Float, dist: Float): Int {
        val target = pinceauTargetColor(cc, x, y, dist)
        val prev = pinceauSmoothedColor ?: target
        val smoothed = lerpColor(prev, target, PINCEAU_COLOR_SMOOTH_FACTOR)
        pinceauSmoothedColor = smoothed
        return inkColor(smoothed)
    }

    private fun pinceauTargetColor(cc: Calque, x: Float, y: Float, dist: Float): Int {
        if (!pinceauMelangeActif) return selectedColor // couleur du mélangeur EN DIRECT, jamais figée ni mélangée (à part le fondu de pinceauMixedColor)
        val base = pinceauCarriedColor ?: selectedColor
        val layer = cc.paintLayer
        if (layer == null || pinceauFonduRate <= 0.01f) {
            pinceauCarriedColor = base
            return base
        }
        val px = x.toInt().coerceIn(0, layer.width - 1)
        val py = y.toInt().coerceIn(0, layer.height - 1)
        val sample = layer.getPixel(px, py)
        if (Color.alpha(sample) <= 140 || colorDist(sample, base) <= COLOR_EPS) {
            pinceauCarriedColor = base
            return base
        }
        // BUG TROUVÉ (2026-08-22, "le pinceau écrit au début mais... ça se
        // dilue" + "le mélange en mode net marche pas") : copié tel quel du
        // calcul de la bille, `fonduRate * dist` fait converger le plus
        // VITE au préréglage "Net" (0.9) — cohérent pour une bille qui roule
        // (bascule en ~1 frame), mais désastreux pour un trait à la main :
        // "Net" diluait tout le trait dès les premiers pixels dès qu'on
        // repassait sur une autre couleur, l'exact inverse de "j'ai besoin
        // que le pinceau écrive par-dessus". Inversé : (1-fonduRate) — Net
        // résiste maintenant à la dilution (reste opaque sur un trait
        // normal), Extrême dilue vite, Doux/Très fondu entre les deux.
        val t = ((1f - pinceauFonduRate) * dist * 0.007f).coerceIn(0f, 1f)
        val mixed = lerpColor(base, sample, t)
        pinceauCarriedColor = mixed
        return mixed
    }
    var bgColor: Int = DEF_BACKGROUND // couleur de fond (réglable)
        set(value) {
            field = value
            bgPaint.color = value
            invalidate()
        }

    // ------------------------------------------------------------- page & caméra
    // La « page » fait 3× l'écran : on peut zoomer/dézoomer (pinch) et
    // naviguer pour faire des détails SANS baisser la résolution du dessin.
    private var pageW = 0
    private var pageH = 0
    private var camScale = 1f // 1 = taille écran, min 0.33 (page 3×), max 2 (détails)
    private var camX = 0f // coin haut-gauche du monde visible
    private var camY = 0f
    private var camMode = false // deux doigts : navigation (zoom/pan), pas de dessin
    // 2026-08-21, demande explicite mode kid : "bloquer le zoom dezoom a la
    // taille de lecran pour que le canva soit remplis" — un enfant qui
    // pince accidentellement dézoome et voit son dessin minuscule au milieu
    // de la page (2× l'écran) ; verrouillé, le 2e doigt n'active jamais le
    // mode caméra (ni zoom ni pan), la vue reste figée à l'échelle écran.
    var zoomLocked: Boolean = false
        set(value) {
            field = value
            if (value) {
                camScale = 1f
                camX = (pageW - width) / 2f
                camY = (pageH - height) / 2f
                invalidate()
            }
        }
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    // Rejet de paume (2026-08-11, retour d'usage : "ya encore des soucis
    // avec le pinch" — log : ~75% des gestes de dessin coupés par un 2e
    // contact ~40ms après le 1er doigt, bien trop systématique pour être un
    // vrai pincement volontaire à chaque fois — cohérent avec une paume/un
    // doigt qui frôle le bord de l'écran en tenant un grand téléphone d'une
    // main). Un 2e doigt qui touche près d'un bord de l'écran est ignoré
    // pour tout ce qui est zoom/pan/annulation de trait — le dessin
    // continue normalement avec le doigt d'origine (pointeur 0).
    private var ignorePinchGesture = false
    private val edgeMarginPx get() = 40f * resources.displayMetrics.density
    // Après un pinch/pan à 2 doigts, le doigt restant peut redessiner mais
    // pas peindre AVANT ce délai (2026-08-11, demande explicite : "le
    // dernier [doigt] sur l'écran dessine, faudrait un petit délai avant
    // que ça repeigne") — laisse le temps de lever franchement le doigt
    // plutôt que de peindre un trait non voulu s'il reste juste posé une
    // fraction de seconde après le geste de navigation.
    private var drawResumeAtMs = 0L

    private val bgPaint = Paint().apply { color = DEF_BACKGROUND }
    private val gaugePaint = Paint(Paint.ANTI_ALIAS_FLAG) // jauge d'encre de la bille
    private val limitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC00E676.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Pinch en cours sur un obstacle sélectionné (ne push undo qu'au début). */
    private var resizingObstacle = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                // rejet de paume (cf. ignorePinchGesture) : ce 2e doigt a été
                // détecté près d'un bord au down — on ignore tout le geste de
                // pincement, retourner false l'arrête net (plus d'onScale
                // ensuite pour ce geste, cf. doc ScaleGestureDetector).
                if (ignorePinchGesture) return false
                // début du pinch : si un obstacle est sélectionné, on commence
                // le resize — SAUF si un point précis est déjà en cours
                // d'édition (editingEndpoint >= 0, ex : un disque de Portail
                // qu'on fait glisser seul). Bug réel 2026-08-11 : "quand je
                // les bouge parfois il bouge 2, il ne doit bouger un par un"
                // — un 2e doigt qui touche l'écron PAR ACCIDENT pendant ce
                // glissé (scaleDetector.onTouchEvent tourne AVANT toute la
                // logique de geste, sans savoir qu'un glissé mono-point est
                // déjà en cours) détournait le geste en pinch, qui bouge/
                // redimensionne les 2 disques ensemble au lieu d'un seul.
                if (editingEndpoint < 0 && selectedObstacleIndex >= 0 && selectedObstacleIndex < obstacles.size) {
                    pushUndoObstacles()
                    resizingObstacle = true
                    return true
                }
                resizingObstacle = false
                return super.onScaleBegin(d)
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                // ScaleGestureDetector tourne indépendamment de camMode
                // (scaleDetector.onTouchEvent en tête d'onTouchEvent) —
                // on bloque le zoom si le geste est rejeté (bord d'écran)
                // ou si on n'est pas en mode navigation.
                if (ignorePinchGesture || !camMode) return true
                if (resizingObstacle && selectedObstacleIndex >= 0 && selectedObstacleIndex < obstacles.size) {
                    val o = obstacles[selectedObstacleIndex]
                    val cx: Float
                    val cy: Float
                    when (o) {
                        is Obstacle.Mur -> {
                            var sx = 0f; var sy = 0f
                            for (p in o.pts) { sx += p.first; sy += p.second }
                            cx = sx / o.pts.size; cy = sy / o.pts.size
                        }
                        is Obstacle.Bouchon -> { cx = o.cx; cy = o.cy }
                        is Obstacle.Triangle -> { cx = (o.x1 + o.x2 + o.x3) / 3f; cy = (o.y1 + o.y2 + o.y3) / 3f }
                        is Obstacle.Ligne -> { cx = (o.x1 + o.x2) / 2f; cy = (o.y1 + o.y2) / 2f }
                        is Obstacle.Rectangle -> { cx = (o.x1 + o.x2 + o.x3 + o.x4) / 4f; cy = (o.y1 + o.y2 + o.y3 + o.y4) / 4f }
                        is Obstacle.Portail -> { cx = (o.x1 + o.x2) / 2f; cy = (o.y1 + o.y2) / 2f }
                        is Obstacle.Planete -> { cx = o.cx; cy = o.cy }
                        is Obstacle.Accelerateur -> { cx = o.cx; cy = o.cy }
                        is Obstacle.Ellipse -> { cx = o.cx; cy = o.cy }
                    }
                    val s = d.scaleFactor
                    obstacles[selectedObstacleIndex] = when (o) {
                        is Obstacle.Mur -> o.copy(pts = o.pts.map { (px, py) ->
                            Pair(cx + (px - cx) * s, cy + (py - cy) * s)
                        })
                        is Obstacle.Bouchon -> o.copy(r = (o.r * s).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                        is Obstacle.Triangle -> o.copy(
                            x1 = cx + (o.x1 - cx) * s, y1 = cy + (o.y1 - cy) * s,
                            x2 = cx + (o.x2 - cx) * s, y2 = cy + (o.y2 - cy) * s,
                            x3 = cx + (o.x3 - cx) * s, y3 = cy + (o.y3 - cy) * s)
                        is Obstacle.Ligne -> o.copy(
                            x1 = cx + (o.x1 - cx) * s, y1 = cy + (o.y1 - cy) * s,
                            x2 = cx + (o.x2 - cx) * s, y2 = cy + (o.y2 - cy) * s,
                            cx = cx + (o.cx - cx) * s, cy = cy + (o.cy - cy) * s)
                        is Obstacle.Rectangle -> o.copy(
                            x1 = cx + (o.x1 - cx) * s, y1 = cy + (o.y1 - cy) * s,
                            x2 = cx + (o.x2 - cx) * s, y2 = cy + (o.y2 - cy) * s,
                            x3 = cx + (o.x3 - cx) * s, y3 = cy + (o.y3 - cy) * s,
                            x4 = cx + (o.x4 - cx) * s, y4 = cy + (o.y4 - cy) * s)
                        is Obstacle.Portail -> o.copy(
                            x1 = cx + (o.x1 - cx) * s, y1 = cy + (o.y1 - cy) * s,
                            x2 = cx + (o.x2 - cx) * s, y2 = cy + (o.y2 - cy) * s,
                            r = (o.r * s).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                        is Obstacle.Planete -> o.copy(
                            r = (o.r * s).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX),
                            mass = o.mass * s * s,
                            influenceRadius = (o.influenceRadius * s).coerceIn(50f, 800f))
                        is Obstacle.Accelerateur -> o.copy(r = (o.r * s).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                        is Obstacle.Ellipse -> o.copy(rx = (o.rx * s).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX), ry = (o.ry * s).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                    }
                    invalidate()
                    return true
                }
                // zoom normal du canvas (pas d'obstacle sélectionné)
                val fx = d.focusX
                val fy = d.focusY
                val wxf = fx / camScale + camX
                val wyf = fy / camScale + camY
                // min 0.33 = la page entière (3× écran) tient exactement
                // dans l'écran — pas plus loin, sinon on voit du vide.
                // max 8 (2026-08-12, demande explicite : "je dois pouvoir zoomer
                // plus pour agrandir la vision de la boule" — 2 pas assez).
                camScale = (camScale * d.scaleFactor).coerceIn(1f/3f, 8f)
                camX = wxf - fx / camScale
                camY = wyf - fy / camScale
                clampCamera()
                invalidate()
                return true
            }
        }
    )

    private fun toWorldX(sx: Float): Float = sx / camScale + camX
    private fun toWorldY(sy: Float): Float = sy / camScale + camY
    private fun focusX(e: MotionEvent): Float = if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.getX(0)
    private fun focusY(e: MotionEvent): Float = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.getY(0)

    /** La fenêtre visible ne sort jamais de la page : jamais de vide à l'écran. */
    private fun clampCamera() {
        val vw = width / camScale
        val vh = height / camScale
        // le coin haut-gauche de la fenêtre reste dans [0, pageW - vw] quand la
        // fenêtre est plus petite que la page ; sinon la page est centrée
        camX = if (vw >= pageW) (pageW - vw) / 2f else camX.coerceIn(0f, pageW - vw)
        camY = if (vh >= pageH) (pageH - vh) / 2f else camY.coerceIn(0f, pageH - vh)
    }

    /** Obstacles de construction : murs, bouchons (billard), triangles (flipper),
     *  lignes (segment à 2 points, extrémités éditables via l'outil sélection).
     *  Placés par l'utilisateur pour créer un parcours — la bille rebondit
     *  dessus. Affichés et actifs seulement quand constructionVisible = true. */
    sealed class Obstacle {
        /** Mur : polyligne (tous les points d'un même geste de tracé libre
         *  dans UN SEUL objet) — pour pouvoir sélectionner et déplacer toute
         *  la ligne tracée d'un coup, pas juste le petit segment sous le doigt. */
        data class Mur(val pts: List<Pair<Float, Float>>) : Obstacle()
        data class Bouchon(val cx: Float, val cy: Float, val r: Float) : Obstacle()
        /** Triangle libre : 3 sommets posés par l'utilisateur — n'importe quelle
         *  forme (angles obtus, aigus, droits). */
        data class Triangle(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                            val x3: Float, val y3: Float) : Obstacle()
        /** Segment à 2 points + un point de contrôle (cx,cy) pour une courbe
         *  de Bézier quadratique — cx,cy = milieu de x1y1/x2y2 par défaut
         *  (mathématiquement équivalent à une ligne droite). Sélectionné
         *  (outil flèche) : poignées agrandies aux extrémités (1, 2 —
         *  déplace ce point, la courbe suit) + poignée de centre distincte
         *  (3 : bouger cx,cy directement = courber la ligne, x1y1/x2y2 fixes)
         *  — invisibles/inactives tant que la ligne n'est pas sélectionnée. */
        // trampoline (2026-08-29, demande explicite : "un outil trampoline
        // qui fonctionnerait sur la ligne... même avec une bille non
        // rebondissante on peut jouer avec") — même forme/mêmes poignées que
        // la Ligne normale, juste posée avec l'outil dédié (outilObstacle
        // == 11) plutôt que 4 ; à la collision, ignore canvas.restitution et
        // impose TRAMPOLINE_RESTITUTION (cf. collidePolyline), donc rebondit
        // fort même sur un profil de bille "non rebondissante".
        data class Ligne(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                         val cx: Float, val cy: Float, val trampoline: Boolean = false) : Obstacle()
        /** Rectangle libre : 4 sommets, même principe que Triangle (2026-08-11,
         *  demande explicite : "pouvoir faire des rectangles sur le même
         *  principe que les autres formes") — poignée unique au sommet 1,
         *  tourne+redimensionne autour du centre. */
        data class Rectangle(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                             val x3: Float, val y3: Float, val x4: Float, val y4: Float) : Obstacle()
        /** Portail façon Portal (2026-08-11, demande explicite) : 2 disques
         *  liés, rouge (x1,y1) et bleu (x2,y2), même rayon r — la bille qui
         *  touche l'un ressort de l'autre avec la même vitesse. Pas de
         *  collision physique (traverse la bille comme une zone de
         *  déclenchement, pas un mur) et pas gommable localement (paire
         *  indivisible : se supprime entière via la croix de sélection). */
        data class Portail(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val r: Float) : Obstacle()
        /** Planète (2026-08-11) : noyau solide (cx,cy,r) + champ gravitationnel
         *  (influenceRadius). La bille rebondit sur le noyau et orbite dans
         *  la zone d'influence — F = mass / dist². `repel` (2026-08-13,
         *  Anti-Planète) inverse le sens de la force : repousse au lieu
         *  d'attirer, même design/mêmes réglages sinon. `mass` fixée à la
         *  création depuis planeteMassDefault/planeteInverseMassDefault
         *  (2026-08-29 : plus de poignée par objet), seule la taille reste
         *  éditable après coup — influenceRadius suit la taille proportionnellement. */
        data class Planete(val cx: Float, val cy: Float, val r: Float, val mass: Float, val influenceRadius: Float, val repel: Boolean = false) : Obstacle()
        /** Accélérateur (2026-08-12, demande explicite) : zone non solide
         *  (cx,cy,r) — la bille la traverse librement, pas de rebond. `gauge`
         *  (-1..1) règle l'effet : positif accélère (vert), négatif ralentit
         *  (rouge) — fixé à la création depuis accelerateurGaugeDefault
         *  (2026-08-29 : plus de poignée par objet, cf. Paramètres →
         *  Développeur → Outils), seule la taille reste éditable après coup. */
        data class Accelerateur(val cx: Float, val cy: Float, val r: Float, val gauge: Float) : Obstacle()
        /** Ellipse (2026-08-12, demande explicite) : comme Bouchon (anneau,
         *  même comportement de rebond) mais rx/ry indépendants — 2 poignées
         *  distinctes (bord droit = rx, bord bas = ry) pour la déformer
         *  librement, pas juste un cercle redimensionné uniformément. */
        data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : Obstacle()
    }

    /** Mode de l'outil obstacle : 0 = pinceau (dessin normal),
     *  1 = mur (segments), 2 = bouchon (rond), 3 = triangle (flipper),
     *  4 = ligne (2 points, extrémités éditables une fois sélectionnée),
     *  5 = rectangle, 6 = portail (2 disques liés, téléporte la bille),
     *  7 = planète (noyau + gravité), 8 = accélérateur (jauge de vitesse),
     *  9 = ellipse (comme bouchon, rx/ry indépendants). */
    var outilObstacle: Int = 0
        set(value) {
            field = value
            obstacleDessin = false
            trianglePhase = 0
            lignePhase = 0
            portailPhase = 0
            gommeObstacles = false
            gommePeinture = false
        }
    /** Gomme obstacles — TOUJOURS locale (2026-08-11, demande explicite :
     *  "la gomme efface uniquement la taille de la gomme sur l'endroit où
     *  elle est passée") : Mur/Bouchon/Triangle/Ligne tous gommés
     *  localement (coupe la portion sous la gomme, garde le reste), plus
     *  jamais supprimés en entier par un simple tap — supprimer une forme
     *  entière se fait maintenant via la petite croix qui apparaît quand on
     *  la sélectionne avec l'outil flèche (cf. obstacleDeleteMarkerPos).
     *  Fusionne l'ancien mode "gommeObstaclesLocale" (retiré, devenu
     *  redondant : les deux faisaient la même chose une fois le tap-
     *  suppression retiré). */
    var gommeObstacles: Boolean = false
        set(value) {
            field = value
            obstacleDessin = false
            if (value) gommePeinture = false
        }
    /** Gomme peinture : efface les pixels du calque actif sous le doigt —
     *  outil séparé de gommeObstacles, même sous-menu (bouton gomme). */
    var gommePeinture: Boolean = false
        set(value) {
            field = value
            obstacleDessin = false
            if (value) gommeObstacles = false
        }
    /** Rayon des gommes, indépendants l'un de l'autre (2026-08-11, demande
     *  explicite : "on doit pouvoir régler la taille des gommes") — même
     *  principe que ballRadiusDp/trailWidthDp (dp réglé par glissé, converti
     *  en px via la densité d'écran). */
    var gommePeintureRadiusDp: Float = DEF_GOMME_RADIUS_DP
        set(value) { field = value; gommePeintureRadiusPx = value * resources.displayMetrics.density }
    var gommeObstaclesRadiusDp: Float = DEF_GOMME_RADIUS_DP
        set(value) { field = value; gommeObstaclesRadiusPx = value * resources.displayMetrics.density }
    private var gommePeintureRadiusPx: Float = DEF_GOMME_RADIUS_DP
    private var gommeObstaclesRadiusPx: Float = DEF_GOMME_RADIUS_DP
    /** Outil sélection : quand actif, un tap sélectionne un obstacle pour
     *  le déplacer (ou tap sur une poignée — extrémités d'une Ligne,
     *  point unique d'un Triangle/Bouchon — pour l'éditer, ou la petite
     *  croix pour la supprimer, cf. obstacleDeleteMarkerPos). Désactive les
     *  autres outils. */
    var outilSelection: Boolean = false
        set(value) {
            field = value
            if (value) { outilObstacle = 0; gommeObstacles = false; gommePeinture = false }
            selectedObstacleIndex = -1
            editingEndpoint = -1
            editingOriginal = null
            obstacleDessin = false
        }
    /** Index de l'obstacle sélectionné (-1 = aucun). */
    var selectedObstacleIndex: Int = -1
    /** Quand true : les obstacles sont affichés ET la physique agit.
     *  Quand false : ils disparaissent visuellement et n'ont plus d'effet
     *  (la bille les traverse). Le dessin généré reste. */
    var constructionVisible: Boolean = true
        set(value) {
            field = value
            invalidate() // sinon pas de rafraîchissement visible en pause (pas de boucle d'animation)
        }
    /** Cache la bille à l'écran (2026-08-13, demande explicite) — la physique
     *  continue normalement (elle peint toujours), seul son rendu disparaît. */
    var ballVisible: Boolean = true
    // toucher la bille l'attrape même en pinceau libre, pas seulement à la
    // flèche (2026-08-20, demande explicite) — off par défaut, cf. usage
    // dans onTouchEvent/ACTION_DOWN
    var ballGrabInPinceau: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    /** Coupe la contrainte qui garde la bille dans l'écran visible/la page
     *  (2026-08-13, demande explicite) : à false, la bille peut sortir du
     *  viewport librement (utile pour tester le lancer/la caméra sans le
     *  rebond automatique sur les bords). */
    var boundsActive: Boolean = true
    /** Comportement au bord (dessin ou écran selon [boundsActive]) : au lieu
     *  de rebondir (défaut) ou de rester bloquée, la bille réapparaît de
     *  l'autre côté — comme dans Univers (2026-08-13, demande explicite,
     *  "comme dans l'app Univers"). */
    var wrapActive: Boolean = false

    private val obstacles = mutableListOf<Obstacle>()
    private var obstacleDessin = false
    private var obstacleX1 = 0f
    private var obstacleY1 = 0f
    private var obstacleX2 = 0f
    private var obstacleY2 = 0f
    // dernier point de la gomme peinture (2026-08-20, rapporté "tracés pas
    // net") — relie les points de glissé par un trait plutôt que de
    // tamponner un cercle isolé à chaque MOVE, cf. bloc gommePeinture
    private var lastGommeX = 0f
    private var lastGommeY = 0f
    // index du Mur (polyligne) en cours de tracé libre pendant le geste
    // courant, -1 = aucun (créé au 1er point ajouté, pas au down, pour ne
    // pas laisser un mur dégénéré derrière un simple tap)
    private var currentMurIndex = -1
    // placement triangle : 0 = pas en cours, 1 = 1ᵉʳ sommet posé, 2 = 2ᵉ posé
    private var trianglePhase = 0
    // placement ligne : 0 = pas en cours, 1 = geste continu en cours (point A
    // = down, point B suit le doigt jusqu'au relâchement)
    private var lignePhase = 0
    // placement portail : même principe que lignePhase (point A = down,
    // point B = disque bleu suivant le doigt jusqu'au relâchement) — MAIS
    // 2 modes possibles (2026-08-11, demande explicite : "en mode un tap et
    // puis le tap d'après pour le suivant, les 2 modes doivent être
    // possibles") : glissé continu (down→move→up en un seul geste, complète
    // toujours) OU tap-puis-tap (2 gestes séparés — le 1er tap SANS glissé
    // fixe seulement le disque rouge et laisse portailPhase à 1 sans créer
    // l'obstacle ; le 2e tap, glissé ou non, fixe le disque bleu et
    // complète). `portailGestureStartedArmed` distingue les deux cas : vrai
    // si portailPhase valait déjà 1 AVANT le down du geste courant (donc ce
    // geste est le 2e tap) ; `portailDragMoved` détecte un glissé PENDANT ce
    // geste précis (indépendant de la distance point A → doigt, qui peut
    // être grande même sans glissé si c'est le 2e tap d'un couple éloigné).
    private var portailPhase = 0
    private var portailGestureStartedArmed = false
    private var portailDownScreenX = 0f
    private var portailDownScreenY = 0f
    private var portailDragMoved = false
    // Points du portail en cours de placement — DÉDIÉS, séparés de
    // obstacleX1/Y1/X2/Y2 (bug réel 2026-08-11 : "le premier tap ne semble
    // pas marcher" — le point A survivait à portailGestureStartedArmed mais
    // se faisait écraser quand même, car le tout début du bloc ACTION_DOWN
    // partagé réassigne obstacleX1/Y1 = position du doigt POUR TOUS LES
    // OUTILS avant même d'atteindre la branche spécifique au portail — le 2e
    // tap effaçait le point A du 1er tap avant que la garde ne puisse agir).
    private var portailX1 = 0f
    private var portailY1 = 0f
    private var portailX2 = 0f
    private var portailY2 = 0f
    // édition d'un obstacle sélectionné via sa poignée : -1 = aucune poignée
    // tenue, 0 = poignée unique (Triangle : tourne+redimensionne autour du
    // centre ; Bouchon : redimensionne) ou extrémité 1 d'une Ligne, 1 = extrémité 2
    private var editingEndpoint = -1
    // point de contact au moment où une poignée de SEGMENT (Rectangle,
    // editingEndpoint 10..13) est saisie — sert de référence pour le delta
    // appliqué aux 2 sommets de ce côté (cf. bloc d'édition de poignée)
    private var edgeGrabWx = 0f
    private var edgeGrabWy = 0f
    // snapshot de l'obstacle au début du glissé de poignée — le nouvel état
    // se recalcule toujours à partir de cet original (jamais de la valeur
    // mutée à la frame précédente, pour éviter toute dérive cumulative)
    private var editingOriginal: Obstacle? = null

    /** Supprime tous les obstacles de construction. */
    fun clearObstacles() {
        pushUndoObstacles() // annulable (léger)
        obstacles.clear()
        invalidate()
    }

    /** Distance d'un point à un segment (utilisé pour mur, triangle, et gomme). */
    private fun distPointSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val ax = x2 - x1
        val ay = y2 - y1
        val len2 = ax * ax + ay * ay
        if (len2 < 1f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * ax + (py - y1) * ay) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * ax), py - (y1 + t * ay))
    }

    /** Évalue un point sur la courbe de Bézier quadratique d'une Ligne
     *  (t dans [0,1]) — avec cx,cy = milieu de x1y1/x2y2, ça reste une
     *  droite exacte ; sinon la ligne se courbe autour du point de contrôle. */
    private fun quadPoint(t: Float, x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float): Pair<Float, Float> {
        val u = 1f - t
        val x = u * u * x1 + 2 * u * t * cx + t * t * x2
        val y = u * u * y1 + 2 * u * t * cy + t * t * y2
        return Pair(x, y)
    }

    /** Approxime la courbe d'une Ligne en N segments (collision, distance). */
    private fun ligneSegments(o: Obstacle.Ligne, n: Int = 16): List<Pair<Pair<Float, Float>, Pair<Float, Float>>> {
        val segs = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        var prev = Pair(o.x1, o.y1)
        for (i in 1..n) {
            val pt = quadPoint(i.toFloat() / n, o.x1, o.y1, o.cx, o.cy, o.x2, o.y2)
            segs.add(Pair(prev, pt))
            prev = pt
        }
        return segs
    }

    /** Distance d'un point au plus proche d'un obstacle (pour la gomme et la sélection). */
    private fun distPointObstacle(px: Float, py: Float, o: Obstacle): Float = when (o) {
        is Obstacle.Mur -> {
            if (o.pts.size < 2) {
                if (o.pts.isEmpty()) Float.MAX_VALUE else hypot(px - o.pts[0].first, py - o.pts[0].second)
            } else {
                var best = Float.MAX_VALUE
                for (i in 0 until o.pts.size - 1) {
                    val (ax, ay) = o.pts[i]; val (bx, by) = o.pts[i + 1]
                    val d = distPointSegment(px, py, ax, ay, bx, by)
                    if (d < best) best = d
                }
                best
            }
        }
        // circleSelectScore (pas juste "d - r", qui devient très négatif à
        // l'intérieur, ni abs(d - r), qui empêche de sélectionner en cliquant
        // loin du bord dans une grande forme) : tout point à l'intérieur est
        // TOUJOURS un hit valide (retour d'usage explicite, 2026-08-12 :
        // "la planète doit être sélectionnable quand on clique aussi dans la
        // planète"), classé par taille pour qu'une petite forme imbriquée
        // dans une grande gagne quand même la sélection à sa place (autre
        // retour du même jour : l'accélérateur dans le cercle).
        is Obstacle.Bouchon -> circleSelectScore(hypot(px - o.cx, py - o.cy), o.r)
        is Obstacle.Triangle -> minOf(
            distPointSegment(px, py, o.x1, o.y1, o.x2, o.y2),
            distPointSegment(px, py, o.x2, o.y2, o.x3, o.y3),
            distPointSegment(px, py, o.x3, o.y3, o.x1, o.y1)
        )
        is Obstacle.Ligne -> {
            var best = Float.MAX_VALUE
            for ((a, b) in ligneSegments(o)) {
                val d = distPointSegment(px, py, a.first, a.second, b.first, b.second)
                if (d < best) best = d
            }
            best
        }
        is Obstacle.Rectangle -> minOf(
            distPointSegment(px, py, o.x1, o.y1, o.x2, o.y2),
            distPointSegment(px, py, o.x2, o.y2, o.x3, o.y3),
            distPointSegment(px, py, o.x3, o.y3, o.x4, o.y4),
            distPointSegment(px, py, o.x4, o.y4, o.x1, o.y1)
        )
        is Obstacle.Portail -> minOf(
            circleSelectScore(hypot(px - o.x1, py - o.y1), o.r),
            circleSelectScore(hypot(px - o.x2, py - o.y2), o.r)
        )
        // le noyau (o.r) uniquement, PAS le halo d'influence — retour
        // d'usage explicite 2026-08-12 : "la planète doit être sélectionnable
        // uniquement par la planète [le noyau], pas dans sa zone d'influence"
        // (annule un essai précédent avec influenceRadius, trop large).
        is Obstacle.Planete -> circleSelectScore(hypot(px - o.cx, py - o.cy), o.r)
        is Obstacle.Accelerateur -> circleSelectScore(hypot(px - o.cx, py - o.cy), o.r)
        is Obstacle.Ellipse -> {
            // approximation : distance normalisée en espace ellipse (1 = sur
            // le bord), remise à l'échelle par le plus grand rayon — assez
            // précis pour la sélection, pas besoin d'exact
            val e = hypot((px - o.cx) / o.rx, (py - o.cy) / o.ry)
            circleSelectScore(e * max(o.rx, o.ry), max(o.rx, o.ry))
        }
    }

    // score de sélection pour une forme "cercle" : tout point à l'intérieur
    // (d <= r) est un hit valide (classé par r, plus petit = prioritaire) ;
    // à l'extérieur, distance normale au bord (tolérance de clic proche).
    private fun circleSelectScore(d: Float, r: Float): Float =
        if (d <= r) r - 1_000_000f else d - r

    /** Cœur de la gomme locale : retire les points sous le rayon de la
     *  gomme d'une liste de points, découpe le reste en morceaux (« runs »)
     *  consécutifs — n'efface plus jamais toute la forme d'un coup.
     *  `closed` = vrai pour une forme dont le dernier point rejoint le
     *  premier (Bouchon, Triangle) : les deux bouts de la liste linéaire
     *  sont alors fusionnés s'ils survivent tous les deux — sinon un coup
     *  de gomme au milieu d'un anneau le casserait en 2 tronçons séparés
     *  par une couture artificielle au lieu d'1 seul tronçon continu.
     *  Utilisé pour les 4 types d'obstacles (2026-08-11 : Mur/Bouchon/
     *  Triangle/Ligne, plus de distinction — cf. bouchonPoints/
     *  trianglePoints/lignePoints et le point d'appel dans le geste de
     *  gomme). */
    private fun erasePointsPortion(
        pts: List<Pair<Float, Float>>, ex: Float, ey: Float, radius: Float, closed: Boolean,
    ): List<List<Pair<Float, Float>>> {
        val runs = mutableListOf<MutableList<Pair<Float, Float>>>()
        var current: MutableList<Pair<Float, Float>>? = null
        for (p in pts) {
            if (hypot(p.first - ex, p.second - ey) >= radius) {
                val c = current ?: mutableListOf<Pair<Float, Float>>().also { current = it; runs.add(it) }
                c.add(p)
            } else {
                current = null
            }
        }
        if (closed && runs.size >= 2 && pts.isNotEmpty()) {
            val firstAlive = hypot(pts.first().first - ex, pts.first().second - ey) >= radius
            val lastAlive = hypot(pts.last().first - ex, pts.last().second - ey) >= radius
            if (firstAlive && lastAlive) {
                // Le premier et le dernier morceau sont géométriquement
                // adjacents sur la forme fermée (la couture de la
                // discrétisation) — les fusionner en un seul tronçon continu.
                val last = runs.removeAt(runs.size - 1)
                runs[0].addAll(0, last)
            }
        }
        return runs.filter { it.size >= 2 }
    }

    /** Discrétise le cercle d'un Bouchon en points denses — pour la gomme
     *  locale (2026-08-10) : une fois entamé, un Bouchon devient un Mur
     *  (arc ouvert), il perd ses poignées de redimensionnement propres au
     *  Bouchon (comportement assumé, cohérent avec Mur/Ligne érodés). */
    private fun bouchonPoints(o: Obstacle.Bouchon, n: Int = 48): List<Pair<Float, Float>> =
        (0 until n).map { i ->
            val a = 2f * PI.toFloat() * i / n
            (o.cx + o.r * cos(a)) to (o.cy + o.r * sin(a))
        }

    /** Discrétise le contour d'une Ellipse en points denses — même principe
     *  que `bouchonPoints`, pour la gomme locale (2026-08-13, bug réel
     *  signalé : "la gomme verte n'efface pas les cercles déformables" —
     *  l'Ellipse était exclue de la gomme locale par erreur, groupée avec
     *  Portail/Planète/Accélérateur alors que rien ne l'empêche de suivre
     *  le même principe que Bouchon). */
    private fun ellipsePoints(o: Obstacle.Ellipse, n: Int = 48): List<Pair<Float, Float>> =
        (0 until n).map { i ->
            val a = 2f * PI.toFloat() * i / n
            (o.cx + o.rx * cos(a)) to (o.cy + o.ry * sin(a))
        }

    /** Discrétise les 3 côtés d'un Triangle en points denses — même
     *  principe que `bouchonPoints`, pour la gomme locale. */
    private fun trianglePoints(o: Obstacle.Triangle, perEdge: Int = 10): List<Pair<Float, Float>> {
        val corners = listOf(o.x1 to o.y1, o.x2 to o.y2, o.x3 to o.y3)
        val pts = mutableListOf<Pair<Float, Float>>()
        for (i in corners.indices) {
            val (ax, ay) = corners[i]
            val (bx, by) = corners[(i + 1) % corners.size]
            for (j in 0 until perEdge) {
                val t = j / perEdge.toFloat()
                pts.add((ax + (bx - ax) * t) to (ay + (by - ay) * t))
            }
        }
        return pts
    }

    /** Position de la petite croix de suppression (2026-08-11) : juste
     *  au-dessus du point le plus haut de la forme pour Mur/Bouchon/Ligne.
     *  Triangle/Rectangle ont une poignée d'édition sur le sommet 1 — la
     *  croix utilise volontairement un AUTRE sommet (2, poussé vers
     *  l'extérieur depuis le centre) pour ne jamais se superposer avec elle
     *  (retour d'usage explicite : "sur le triangle j'aimerais que ça soit
     *  sur un autre sommet que celui avec la poignée"). */
    private fun obstacleDeleteMarkerPos(o: Obstacle): Pair<Float, Float> = when (o) {
        is Obstacle.Mur -> {
            // 2026-08-12, demande explicite : à gauche plutôt qu'en haut
            val left = o.pts.minByOrNull { it.first } ?: (0f to 0f)
            (left.first - HANDLE_RADIUS * 2f) to left.second
        }
        is Obstacle.Bouchon -> (o.cx - o.r - HANDLE_RADIUS * 2f) to o.cy
        is Obstacle.Ligne -> {
            val left = if (o.x1 <= o.x2) o.x1 to o.y1 else o.x2 to o.y2
            (left.first - HANDLE_RADIUS * 2f) to left.second
        }
        is Obstacle.Triangle -> {
            val cx = (o.x1 + o.x2 + o.x3) / 3f
            val cy = (o.y1 + o.y2 + o.y3) / 3f
            outwardFrom(cx, cy, o.x2, o.y2)
        }
        is Obstacle.Rectangle -> {
            val cx = (o.x1 + o.x2 + o.x3 + o.x4) / 4f
            val cy = (o.y1 + o.y2 + o.y3 + o.y4) / 4f
            outwardFrom(cx, cy, o.x2, o.y2)
        }
        is Obstacle.Portail -> (o.x1 - o.r - HANDLE_RADIUS * 2f) to o.y1
        is Obstacle.Planete -> (o.cx - o.r - HANDLE_RADIUS * 2f) to o.cy
        is Obstacle.Accelerateur -> (o.cx - o.r - HANDLE_RADIUS * 2f) to o.cy
        is Obstacle.Ellipse -> (o.cx - o.rx - HANDLE_RADIUS * 2f) to o.cy
    }

    /** Position de la poignée de zone d'influence d'une Planète (attire ou
     *  repousse) — sur le halo (bord BAS à l'origine, distincte de la
     *  poignée de taille du noyau à droite et de la croix de suppression à
     *  gauche). 2026-08-29, demande explicite : "j'ai changé la variable de
     *  gravité, j'aimerais éditer la planète tout en gardant la variable en
     *  question... il faudrait une 2e poignée sur l'orbite pour régler la
     *  zone d'influence" — indépendante à la fois du noyau (poignée jaune)
     *  ET de la gravité (réglée globalement, cf. planeteMassDefault/Inverse).
     *
     *  Suite, même jour : "possible d'avoir la poignée qui suit le doigt sur
     *  la zone d'effet histoire de pas avoir les poignées en dehors de
     *  l'écran ?" — une planète à grande influence peut avoir sa poignée
     *  fixe (bord bas) hors du cadre visible après zoom/pan. Projetée
     *  maintenant vers le centre de l'écran VISIBLE (pas le bord bas fixe) :
     *  elle glisse le long du cercle à mesure qu'on déplace la caméra, donc
     *  reste atteignable tant qu'une partie du halo est à l'écran. Combinée
     *  au hit-test qui accepte tout le pourtour du cercle (pas seulement ce
     *  point précis, cf. onTouchEvent) — on peut attraper n'importe où sur
     *  le halo visible, pas seulement ce marqueur. */
    private fun planeteInfluenceHandlePos(o: Obstacle.Planete): Pair<Float, Float> {
        val viewCx = camX + (width / camScale) / 2f
        val viewCy = camY + (height / camScale) / 2f
        val dx = viewCx - o.cx
        val dy = viewCy - o.cy
        val len = hypot(dx, dy).coerceAtLeast(0.01f)
        return (o.cx + dx / len * o.influenceRadius) to (o.cy + dy / len * o.influenceRadius)
    }

    /** Pousse le point (px,py) vers l'extérieur en partant du centre
     *  (cx,cy), d'une distance fixe — utilisé pour ancrer la croix de
     *  suppression sur un sommet donné sans jamais empiéter dessus. */
    private fun outwardFrom(cx: Float, cy: Float, px: Float, py: Float): Pair<Float, Float> {
        val vx = px - cx
        val vy = py - cy
        val len = hypot(vx, vy).coerceAtLeast(0.01f)
        return (px + vx / len * HANDLE_RADIUS * 1.6f) to (py + vy / len * HANDLE_RADIUS * 1.6f)
    }

    /** Points denses le long de la courbe d'une Ligne — même principe que
     *  bouchonPoints/trianglePoints (2026-08-11, demande explicite : "la
     *  gomme efface uniquement la taille de la gomme sur l'endroit où elle
     *  est passée" — la Ligne, ouverte, n'était pas gommée localement
     *  avant, toujours supprimée en entier ; plus de distinction main-
     *  tenant, tout est local). Ouverte (closed=false à l'appel), pas de
     *  fusion de couture nécessaire. */
    private fun lignePoints(o: Obstacle.Ligne): List<Pair<Float, Float>> {
        val segs = ligneSegments(o)
        val pts = mutableListOf(segs.first().first)
        for ((_, b) in segs) pts.add(b)
        return pts
    }

    /** Discrétise les 4 côtés d'un Rectangle en points denses — même
     *  principe que trianglePoints, pour la gomme locale (2026-08-11). */
    private fun rectanglePoints(o: Obstacle.Rectangle, perEdge: Int = 10): List<Pair<Float, Float>> {
        val corners = listOf(o.x1 to o.y1, o.x2 to o.y2, o.x3 to o.y3, o.x4 to o.y4)
        val pts = mutableListOf<Pair<Float, Float>>()
        for (i in corners.indices) {
            val (ax, ay) = corners[i]
            val (bx, by) = corners[(i + 1) % corners.size]
            for (j in 0 until perEdge) {
                val t = j / perEdge.toFloat()
                pts.add((ax + (bx - ax) * t) to (ay + (by - ay) * t))
            }
        }
        return pts
    }

    /** Collision bille ↔ polyligne (Mur, Triangle, ou segments d'une Ligne
     *  courbée) : ne résout que sur le segment le plus proche, pas sur
     *  chacun — sinon une bille qui chevauche plusieurs mini-segments à la
     *  fois se fait appliquer le rebond (× restitution) plusieurs fois dans
     *  la même frame et s'écrase quasi à l'arrêt (bug « ultra ralentie »).
     *
     *  Vérifie le TRAJET de cette frame (prevPos → position actuelle), pas
     *  seulement le point d'arrivée (2026-08-10, bug réel : "de temps en
     *  temps les billes passent à travers les obstacles, notamment les
     *  lignes dessinées") — à MAX_SPEED=3000px/s et dt plafonné à 33ms, la
     *  bille peut parcourir jusqu'à ~99px en une frame, souvent plus que la
     *  longueur d'une ligne dessinée à la main ; vérifier seulement la
     *  position finale laisse passer les cas où le trajet a traversé le
     *  segment sans que l'arrivée soit à moins de ballRadius de celui-ci
     *  (tunneling classique en collision discrète). Sous-échantillonne le
     *  trajet en pas d'environ la moitié du rayon de la bille — dès qu'un
     *  pas est en collision, la bille est ramenée à CE point avant de
     *  résoudre (elle ne doit jamais rester à sa position finale déjà
     *  passée à travers l'obstacle). */
    private fun collidePolyline(c: Calque, segs: List<Pair<Pair<Float, Float>, Pair<Float, Float>>>, restitutionOverride: Float? = null) {
        val travelX = c.ballX - c.prevPosX
        val travelY = c.ballY - c.prevPosY
        val travelDist = hypot(travelX, travelY)
        val subSteps = (travelDist / (ballRadius * 0.5f)).toInt().coerceIn(1, 12)
        for (i in 1..subSteps) {
            val t = i / subSteps.toFloat()
            val px = c.prevPosX + travelX * t
            val py = c.prevPosY + travelY * t
            var bestD = Float.MAX_VALUE
            var bestSeg: Pair<Pair<Float, Float>, Pair<Float, Float>>? = null
            for (seg in segs) {
                val (a, b) = seg
                val d = distPointSegment(px, py, a.first, a.second, b.first, b.second)
                if (d < bestD) { bestD = d; bestSeg = seg }
            }
            if (bestD < ballRadius) {
                bestSeg?.let { (a, b) ->
                    c.ballX = px
                    c.ballY = py
                    collideSegment(c, a.first, a.second, b.first, b.second, restitutionOverride)
                }
                return
            }
        }
    }

    /** Collision bille ↔ segment : repousse la bille et réfléchit sa vitesse.
     *  `restitutionOverride` (trampoline) ignore le réglage de bille — rebondit
     *  fort même sur un profil "non rebondissante" (restitution proche de 0). */
    private fun collideSegment(c: Calque, x1: Float, y1: Float, x2: Float, y2: Float, restitutionOverride: Float? = null) {
        val ax = x2 - x1
        val ay = y2 - y1
        val len2 = ax * ax + ay * ay
        if (len2 < 1f) return
        val t = (((c.ballX - x1) * ax + (c.ballY - y1) * ay) / len2).coerceIn(0f, 1f)
        val qx = x1 + t * ax
        val qy = y1 + t * ay
        val dx = c.ballX - qx
        val dy = c.ballY - qy
        val d = hypot(dx, dy)
        if (d < ballRadius && d > 0.001f) {
            val nx = dx / d
            val ny = dy / d
            c.ballX = qx + nx * ballRadius
            c.ballY = qy + ny * ballRadius
            val dot = c.velX * nx + c.velY * ny
            if (dot < 0f) {
                // seule la composante normale (celle qui rentre dans
                // l'obstacle) est amortie par restitution — la composante
                // tangentielle (rouler le long de la surface) doit rester
                // intacte, sinon une pression continue contre l'obstacle
                // (gravité) écrase aussi le roulement à chaque frame
                val tx = c.velX - dot * nx
                val ty = c.velY - dot * ny
                val r = restitutionOverride ?: restitution
                c.velX = tx - dot * nx * r
                c.velY = ty - dot * ny * r
            }
        }
    }

    /** Portail (2026-08-11, demande explicite) : téléporte la bille d'un
     *  disque à l'autre en conservant sa vitesse — pas de rebond, c'est un
     *  déclencheur, pas un mur. `prevPosX/Y` remis au CENTRE du disque de
     *  sortie (pas à la position finale post-offset) : les obstacles
     *  suivants testés CETTE MÊME frame (cf. collidePolyline, trajet
     *  prevPos → position) voient ainsi un trajet court et réel — juste
     *  disque→sortie — au lieu soit d'un faux trajet traversant toute la
     *  page depuis l'ancienne position (danger d'origine), soit d'un trajet
     *  nul qui ne détecte plus RIEN (bug réel 2026-08-11 : "en sortant du
     *  portail la bille est passée à travers un mur" — un Rectangle posé
     *  juste devant la sortie n'était jamais testé sur le trajet du
     *  téléport, seulement à partir de la frame suivante). Pas de cooldown
     *  (2026-08-11, demande explicite : "j'aime bien que y ait pas de
     *  cooldown") — seul l'offset de sortie (exitDist) protège d'un
     *  re-déclenchement immédiat, ce qui laisse la bille rebondir en boucle
     *  d'un portail à l'autre si sa vitesse la ramène dessus. */
    private fun handlePortail(c: Calque, o: Obstacle.Portail) {
        val d1 = hypot(c.ballX - o.x1, c.ballY - o.y1)
        val d2 = hypot(c.ballX - o.x2, c.ballY - o.y2)
        val fromX: Float; val fromY: Float; val toX: Float; val toY: Float
        if (d1 < o.r + ballRadius) {
            fromX = o.x1; fromY = o.y1; toX = o.x2; toY = o.y2
        } else if (d2 < o.r + ballRadius) {
            fromX = o.x2; fromY = o.y2; toX = o.x1; toY = o.y1
        } else return
        val speed = hypot(c.velX, c.velY)
        val dirX: Float; val dirY: Float
        if (speed > 1f) {
            dirX = c.velX / speed; dirY = c.velY / speed
        } else {
            // bille quasi immobile : sort dans l'axe d'entrée (bille → disque)
            val ex = c.ballX - fromX
            val ey = c.ballY - fromY
            val el = hypot(ex, ey).coerceAtLeast(0.01f)
            dirX = ex / el; dirY = ey / el
        }
        val exitDist = o.r + ballRadius + 4f
        val enteredX = c.ballX; val enteredY = c.ballY
        c.prevPosX = toX
        c.prevPosY = toY
        c.ballX = toX + dirX * exitDist
        c.ballY = toY + dirY * exitDist
        // BUG CORRIGÉ (2026-08-18, retour d'usage : "un tracé qui se dessine
        // comme si la balle s'était déplacée normalement... c'est une
        // téléportation, devrait pas y avoir de trace") : `prevPosX/Y`
        // est bien remis au centre du disque de sortie ci-dessus, mais
        // `joinPrevBallX/Y` (l'ancre SÉPARÉE utilisée pour la jonction
        // arrondie entre deux frames de roulement, cf. le bloc de peinture
        // ~L2315) ne l'était pas — elle gardait la position de la bille
        // AVANT le téléport (côté entrée). Le prochain trait peint reliait
        // donc cette vieille position à la sortie via un `Path` à 3 points,
        // dessinant un faux trajet traversant tout l'écran. Même reset que
        // celui déjà fait au début d'un contact (CONTACT+, juste au-dessus) :
        // sans ancre, le prochain segment retombe sur un simple `drawLine`
        // court (sortie → juste après), pas de couture avec l'avant-téléport.
        c.lastPaintX = Float.NaN
        c.lastPaintY = Float.NaN
        // Logging temporaire (2026-08-11, retour d'usage vague : "des bugs
        // sur les trajectoires des balles sortantes du portail" — sans
        // détail exploitable, aucune instrumentation existante sur ce
        // chemin) : à retirer une fois la trajectoire de sortie confirmée
        // correcte en usage réel.
        UsageLog.d("portail: entrée(%.0f,%.0f) vel(%.0f,%.0f) speed=%.0f dir(%.2f,%.2f) sortie(%.0f,%.0f)".format(
            enteredX, enteredY, c.velX, c.velY, speed, dirX, dirY, c.ballX, c.ballY))
    }

    /** Panneau ouvert : 0 = aucun, 1 = gauche, 2 = droite, 3 = bas. */
    var openDirection: Int = 0

    // Calques
    private val calques = mutableListOf<Calque>()
    private var actif = 0
    val activeIndex: Int get() = actif
    var onCalquesChanged: (() -> Unit)? = null

    // Capteurs
    private var gravityX = 0f   // gravité filtrée (m/s²) — inclinaison
    private var gravityY = 0f
    /** Force de l'effet de TOUT mouvement du téléphone (2026-08-12, demande
     *  explicite — remplace une calibration essayée puis rejetée à l'usage,
     *  puis étendue à la secousse+coup de poignet, pas juste l'inclinaison :
     *  "étrangement, j'ai essayé de tout baisser et quand je secoue mon
     *  téléphone ça influe" — un seul réglage maître) : 0 = inclinaison,
     *  secousse et coup de poignet n'ont plus aucun effet, 1 = effet plein. */
    var tiltEffect: Float = 1f
    private var gyroX = 0f      // vitesse angulaire gyroscope (rad/s)
    private var gyroY = 0f
    private var accelX = 0f     // accélération linéaire (m/s²) — secousses
    private var accelY = 0f
    private var accelZ = 0f

    // Peinture
    private val trailPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 2026-08-30 : trait de la BILLE peint par tampons (cercles pleins) au
    // lieu de segments de ligne — cf. stampBallTrail(). Paint séparé de
    // trailPaint (toujours utilisé tel quel par le pinceau/dessin au doigt).
    private val stampPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    // gomme unifiée (peinture + obstacles, même bouton) : efface les pixels
    // peints sous le doigt, en plus des obstacles (cf. gommeObstacles)
    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private var inkPhase = 0f // phase spatiale de la texture des traits
    // largeur lissée du trait libre : évite le saut brut de largeur d'un
    // segment à l'autre (bosses visibles aux jonctions, capuchons ronds
    // désaccordés) — -1 = pas encore initialisée (premier segment du geste)
    private var smoothedTrailWidth = -1f

    private var paused = false // démarre en lecture (2026-08-20, demande explicite : "par defaut quand on ouvre lapp cest sur play pas en pause") — avant : démarrait en pause pour choisir une couleur et déposer d'abord
    private var lastFrameTime = 0L

    // Poussée de la bille au doigt (inertie)
    private var dragIndex = -1          // calque dont la bille est tenue par le doigt (-1 = aucune)
    // "pousser/éjecter" la bille au contact du doigt en pinceau libre
    // (2026-08-20, demande explicite : "je veux pas que ... on attrape la
    // balle, je veux que ca la pousse, ou jejecte selon la vitesse du
    // toucher" — pas d'attrape/portage, un impact ponctuel proportionnel à
    // la vitesse du doigt à l'instant du contact, cf. ACTION_MOVE) — point
    // et heure du dernier échantillon de glissé, pour calculer la vitesse
    // instantanée du doigt entre 2 MOVE.
    private var lastPushX = 0f
    private var lastPushY = 0f
    private var lastPushTimeMs = 0L
    // le doigt comme mur mobile, vérifié CHAQUE frame physique dans step()
    // plutôt qu'aux évènements tactiles (2026-08-20, rapporté : "la balle
    // passe souvent a travers de mon doigts" — un doigt immobile ne
    // déclenche aucun MOVE, donc une bille rapide le traversait sans jamais
    // être testée). touchWallActive = un doigt est posé en pinceau libre
    // (entre DOWN et UP) ; X/Y/VelX/VelY = sa position et sa vitesse
    // instantanée, mis à jour à chaque MOVE, réutilisés tels quels par
    // step() tant que le doigt reste immobile.
    private var touchWallActive = false
    private var touchWallX = 0f
    private var touchWallY = 0f
    private var touchWallVelX = 0f
    private var touchWallVelY = 0f
    private var velocityTracker: VelocityTracker? = null
    // fenêtre récente (t, x, y) pendant qu'on tient la bille — indépendante du lissage
    // de VelocityTracker, sert uniquement à détecter une accélération du geste juste
    // avant le lâcher (2026-08-13, demande explicite : "l'accélération dans mon tracé
    // dois impacter" le lancer, pas seulement la vitesse instantanée finale)
    private val throwSamples = ArrayDeque<Triple<Long, Float, Float>>()

    // Undo (ratures) : pile de snapshots des couches du calque au début de chaque geste
    private val undoStack = mutableListOf<UndoSnapshot>()
    private val redoStack = mutableListOf<UndoSnapshot>()
    // Diagnostic (journal fichier, cf. UsageLog)
    private var frameCount = 0
    private var drawSegments = 0

    private var ballRadius = 60f
    private var trailWidth = 40f
    private var ballTrailWidthPx = 0f // tracé de la bille = 0.95 × son diamètre (un chouïa moins)

    // Gestes
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var moved = false
    private var ignoreTouches = false // vrai quand un geste a fermé le menu : tout le geste est ignoré (pas de dessin)
    // vrai dès le down d'un trait libre (pushUndo déjà fait) jusqu'au up —
    // si un 2e doigt arrive pendant cette fenêtre (début de pinch), le très
    // court trait à un seul doigt déjà tracé est annulé (coup de pinceau parasite)
    private var traceEnCours = false
    private var lastDrawX = 0f
    private var lastDrawY = 0f
    // point d'ancrage pour joindre le segment en cours au précédent par une
    // vraie jonction arrondie (Join.ROUND) plutôt que deux bouts (Cap.ROUND)
    // indépendants qui débordent visiblement à l'intérieur d'une courbe
    // serrée — NaN = pas de segment précédent (tout début du trait)
    private var joinPrevDrawX = Float.NaN
    private var joinPrevDrawY = Float.NaN
    private val strokePath = Path()

    /** 1 = swipe bord gauche → (palette), -1 = swipe bord droit ← (calques),
     *  2 = swipe ↑ (config de la balle). */
    var onSwipeOpen: ((Int) -> Unit)? = null
    var onPauseChanged: ((Boolean) -> Unit)? = null
    // 2026-08-22, demande explicite : "quand je peins le menu reste ouvert"
    // — aucun sous-menu (taille pinceau, choix de bille, etc.) ne se
    // fermait jamais au début d'un nouveau trait, faille pré-existante
    // remarquée seulement maintenant avec le sélecteur de pinceau. Appelé
    // au tout début d'un geste de dessin (ACTION_DOWN), MainActivity ferme
    // tous les sous-menus ouverts dessus.
    var onDrawStart: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            // Un geste lent est un tracé de ligne, pas un swipe ; pendant qu'on
            // pousse une bille, pas de menu non plus
            if (SystemClock.uptimeMillis() - downTimeMs > FLING_MAX_MS) return false
            if (dragIndex >= 0) return false
            val dx = e2.x - (e1?.x ?: e2.x)
            val dy = e2.y - (e1?.y ?: e2.y)
            // FERMETURE : repousser le panneau ouvert vers son origine
            // (l'ouverture se fait par les boutons — plus de swipe accidentel)
            if (openDirection == 1 && dx < -160f) { onSwipeOpen?.invoke(0); return true }
            if (openDirection == 2 && dx > 160f) { onSwipeOpen?.invoke(0); return true }
            if (openDirection == 3 && dy > 160f) { onSwipeOpen?.invoke(0); return true }
            return false
        }
    })

    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val volumePaint = Paint(Paint.ANTI_ALIAS_FLAG) // dégradé de volume, cf. volumeMode
    // 2026-08-25, optimisation (Galaxy A13, ça ramait) : le RadialGradient de
    // volumeMode était reconstruit à CHAQUE frame (allocation native coûteuse)
    // alors que sa couleur/rayon ne changent quasi jamais frame à frame (sauf
    // rainbowMode/speedColorMode) — reconstruit seulement si couleur/rayon
    // changent, repositionné sur la bille via setLocalMatrix (juste une
    // multiplication de matrice, pas de réallocation).
    private var volumeGradient: RadialGradient? = null
    private var volumeGradientLight = 0
    private var volumeGradientDark = 0
    private var volumeGradientRadius = -1f
    private val volumeGradientMatrix = Matrix()
    // 2026-08-30 : bord doux du tampon de trait de bille (cf. trailEdgeMode/
    // stampBallTrail) — même principe de cache que volumeGradient ci-dessus.
    private var stampGradient: RadialGradient? = null
    private var stampGradientColor = 0
    private var stampGradientRadius = -1f
    private val stampGradientMatrix = Matrix()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val layerPaint = Paint() // alpha d'opacité appliqué au rendu d'un calque
    private val layerZoneRect = Rect() // réutilisé à chaque frame (cf. onDraw), pas de réallocation
    // 2026-08-25, optimisation (Galaxy A13, ça ramait) : drawObstacles()
    // allouait un nouveau Path() à chaque forme à chaque frame tant que le
    // mode construction est visible — un seul Path réutilisé (rewind()
    // avant chaque forme, dessinée immédiatement après construction).
    private val obstaclePath = Path()
    private val tachePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        calques.add(Calque(baseColor = 0xFF1A1A1A.toInt(), opacity = 1f, ballX = 0f, ballY = 0f))
        UsageLog.d("écran ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}px d=${resources.displayMetrics.density}")
        if (gravitySensor == null) {
            UsageLog.w("init capteur = AUCUN — les billes ne bougeront pas")
        } else {
            val kind = if (gravitySensor.type == Sensor.TYPE_GRAVITY) "GRAVITY" else "ACCELEROMETER"
            val gyro = if (gyroSensor != null) " + GYROSCOPE" else " (pas de gyroscope)"
            val shake = if (linearAccelSensor != null) " + SHAKE" else ""
            UsageLog.d("init capteur = $kind$gyro$shake")
            sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
            gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            linearAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    // ---------------------------------------------------------------- API calques

    fun addCalque() {
        val c = Calque(
            baseColor = selectedColor, // la nouvelle bille prend la couleur sélectionnée
            opacity = 1f,
            ballX = if (width > 0) width / 2f else 0f,
            ballY = if (height > 0) height / 2f else 0f
        )
        calques.add(c)
        actif = calques.size - 1
        if (width > 0 && height > 0) initPaintLayer(c)
        onCalquesChanged?.invoke()
        UsageLog.d("calque ajouté (total ${calques.size}), actif=${actif + 1}")
        invalidate()
    }

    fun removeCalque(index: Int) {
        if (calques.size <= 1) return // toujours au moins un calque
        val c = calques.removeAt(index)
        c.paintLayer?.recycle()
        c.wetLayer?.recycle()
        // les snapshots undo/redo de ce calque sont périmés
        val it = undoStack.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.calque === c) {
                s.paint?.recycle()
                s.wet?.recycle()
                it.remove()
            }
        }
        val it2 = redoStack.iterator()
        while (it2.hasNext()) {
            val s = it2.next()
            if (s.calque === c) {
                s.paint?.recycle()
                s.wet?.recycle()
                it2.remove()
            }
        }
        if (index < actif) actif--
        if (actif >= calques.size) actif = calques.size - 1
        onCalquesChanged?.invoke()
        UsageLog.d("calque supprimé (total ${calques.size}), actif=${actif + 1}")
        invalidate()
    }

    fun setCalqueActif(index: Int) {
        if (index in calques.indices && index != actif) {
            actif = index
            onCalquesChanged?.invoke()
            UsageLog.d("calque actif = ${actif + 1}")
        }
    }

    fun setCalqueOpacity(index: Int, opacity: Float) {
        calques[index].opacity = opacity.coerceIn(0f, 1f)
    }

    fun calqueCount(): Int = calques.size
    fun calqueBaseColor(index: Int): Int = calques[index].baseColor
    fun calqueOpacity(index: Int): Float = calques[index].opacity
    fun setCalqueBaseColor(index: Int, color: Int) {
        calques[index].baseColor = color
        invalidate()
    }
    fun setActiveBaseColor(color: Int) {
        calques[actif].baseColor = color
    }
    fun activeBaseColor(): Int = calques[actif].baseColor
    /** Choix manuel d'une couleur (mélangeur/palette) : oublie toute couleur
     *  "portée" par mélange pigmentaire précédent sur la bille active, sinon
     *  `paintColor()` continue de préférer `carriedColor` à `selectedColor`
     *  fraîchement choisi — le nouveau choix resterait invisible tant qu'un
     *  contact avec de la peinture ne l'écrase pas (2026-09-04, régression
     *  liée : "même quand la bille roule pas, ça marche pas"). */
    fun clearActiveCarriedColor() {
        calques.getOrNull(actif)?.carriedColor = null
    }

    /** Rendu de l'œuvre seule (fond + calques, sans la bille/halo) pour
     *  l'export — `scale` multiplie la résolution (1 = taille écran). */
    /** Copie de la couche de peinture d'un calque (sauvegarde d'œuvre). */
    fun calqueBitmapCopy(index: Int): Bitmap? =
        calques[index].paintLayer?.let { Bitmap.createBitmap(it) }

    /** Restaure la couche d'un calque depuis une bitmap (chargement d'œuvre) —
     *  la couche humide reçoit une copie : l'œuvre chargée est détectable par
     *  la bille (elle peut continuer à peindre dessus). */
    fun setCalqueBitmap(index: Int, bmp: Bitmap, base: Int, opacity: Float) {
        val c = calques[index]
        c.baseColor = base
        c.opacity = opacity
        c.paintLayer?.recycle()
        c.paintLayer = bmp
        c.paintCanvas.setBitmap(bmp)
        c.wetLayer?.recycle()
        val wet = Bitmap.createBitmap(bmp)
        c.wetCanvas.setBitmap(wet)
        c.wetLayer = wet
        c.carriedColor = null
        c.inkReserve = 0f
        invalidate()
    }

    /** Remplace TOUTES les couches de peinture (chargement complet d'œuvre). */
    fun resetCalques(count: Int, base: Int, opacity: Float) {
        while (calques.size > 1) removeCalque(calques.size - 1)
        while (calques.size < count) addCalque()
        setCalqueBaseColor(0, base)
        calques[0].opacity = opacity
        actif = 0
        onCalquesChanged?.invoke()
    }

    /** Sérialise tous les obstacles (sauvegarde d'œuvre) — format texte
     *  maison ligne par ligne, cohérent avec le config.txt existant (pas de
     *  dépendance JSON, zéro dépendance du projet). 2026-08-10, bug réel
     *  signalé : "quand on sauve et réouvre les obstacles ne sont pas
     *  sauvegardés" — confirmé, `saveOeuvre`/`loadOeuvre` ne touchaient
     *  jamais `obstacles`. */
    fun serializeObstacles(): String = obstacles.joinToString("\n") { o ->
        when (o) {
            is Obstacle.Mur -> "MUR;" + o.pts.joinToString(";") { "${it.first},${it.second}" }
            is Obstacle.Bouchon -> "BOUCHON;${o.cx},${o.cy},${o.r}"
            is Obstacle.Triangle -> "TRIANGLE;${o.x1},${o.y1},${o.x2},${o.y2},${o.x3},${o.y3}"
            is Obstacle.Ligne -> "LIGNE;${o.x1},${o.y1},${o.x2},${o.y2},${o.cx},${o.cy},${if (o.trampoline) 1 else 0}"
            is Obstacle.Rectangle -> "RECTANGLE;${o.x1},${o.y1},${o.x2},${o.y2},${o.x3},${o.y3},${o.x4},${o.y4}"
            is Obstacle.Portail -> "PORTAIL;${o.x1},${o.y1},${o.x2},${o.y2},${o.r}"
            is Obstacle.Planete -> "PLANETE;${o.cx},${o.cy},${o.r},${o.mass},${o.influenceRadius},${if (o.repel) 1 else 0}"
            is Obstacle.Accelerateur -> "ACCELERATEUR;${o.cx},${o.cy},${o.r},${o.gauge}"
            is Obstacle.Ellipse -> "ELLIPSE;${o.cx},${o.cy},${o.rx},${o.ry}"
        }
    }

    /** Restaure les obstacles depuis le format de `serializeObstacles` —
     *  remplace tout ce qui existait (chargement complet d'œuvre). Une
     *  chaîne vide (anciennes œuvres sauvegardées avant ce fix, sans
     *  fichier obstacles.txt) vide simplement les obstacles, sans planter —
     *  compatible avec les sauvegardes existantes. */
    fun deserializeObstacles(text: String) {
        obstacles.clear()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val parts = line.split(";")
            try {
                when (parts.getOrNull(0)) {
                    "MUR" -> {
                        val pts = parts.drop(1).map { p ->
                            val (x, y) = p.split(",")
                            x.toFloat() to y.toFloat()
                        }
                        if (pts.size >= 2) obstacles.add(Obstacle.Mur(pts))
                    }
                    "BOUCHON" -> {
                        val v = parts[1].split(",").map { it.toFloat() }
                        obstacles.add(Obstacle.Bouchon(v[0], v[1], v[2]))
                    }
                    "TRIANGLE" -> {
                        val v = parts[1].split(",").map { it.toFloat() }
                        obstacles.add(Obstacle.Triangle(v[0], v[1], v[2], v[3], v[4], v[5]))
                    }
                    "LIGNE" -> {
                        val v = parts[1].split(",").map { it.toFloat() }
                        // trampoline (7e valeur, 2026-08-29) absent des sauvegardes
                        // antérieures → false par défaut, même principe que repel
                        val trampoline = v.getOrNull(6) == 1f
                        obstacles.add(Obstacle.Ligne(v[0], v[1], v[2], v[3], v[4], v[5], trampoline))
                    }
                    "RECTANGLE" -> {
                        val v = parts[1].split(",").map { it.toFloat() }
                        obstacles.add(Obstacle.Rectangle(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]))
                    }
                    "PORTAIL" -> {
                        val v = parts[1].split(",").map { it.toFloat() }
                        obstacles.add(Obstacle.Portail(v[0], v[1], v[2], v[3], v[4]))
                    }
                    "PLANETE" -> {
                        val v = parts[1].split(",").map { it.toFloat() }
                        // repel (6e valeur) absent des sauvegardes antérieures à
                        // l'Anti-Planète (2026-08-13) → false par défaut
                        val repel = v.getOrNull(5) == 1f
                        obstacles.add(Obstacle.Planete(v[0], v[1], v[2], v[3], v[4], repel))
                    }
                    "ACCELERATEUR" -> {
                        val v = parts[1].split(",").map { it.toFloat() }
                        obstacles.add(Obstacle.Accelerateur(v[0], v[1], v[2], v[3]))
                    }
                    "ELLIPSE" -> {
                        val v = parts[1].split(",").map { it.toFloat() }
                        obstacles.add(Obstacle.Ellipse(v[0], v[1], v[2], v[3]))
                    }
                }
            } catch (_: Exception) {
                // ligne corrompue/format inattendu : ignorée, ne bloque pas
                // le reste du chargement
            }
        }
        // Un seul portail à la fois (cf. règle à la création) — une œuvre
        // sauvegardée avant cette règle pourrait en contenir plusieurs, on
        // ne garde que le dernier au chargement.
        val lastPortail = obstacles.lastOrNull { it is Obstacle.Portail }
        if (lastPortail != null) {
            obstacles.removeAll { it is Obstacle.Portail && it !== lastPortail }
        }
        selectedObstacleIndex = -1
        invalidate()
    }

    /** Rendu de l'œuvre seule (fond + calques, sans la bille/halo) pour
     *  l'export/sauvegarde — `scale` multiplie la résolution (1 = taille écran). */
    /** [includeBall]/[includeObstacles] (2026-08-13, demande explicite : "dans
     *  les exports aussi avec ou sans bille, avec ou sans obstacle") —
     *  réutilise exactement le même rendu que l'écran (drawBalls/drawObstacles)
     *  pour ne rien dupliquer ; la sélection est temporairement masquée pour
     *  un rendu "propre" (pas de poignées jaunes ni de croix de suppression
     *  dans l'image exportée). */
    fun renderArtwork(scale: Float, includeBall: Boolean = true, includeObstacles: Boolean = true): Bitmap {
        // exporte la PAGE entière (2× l'écran) : rien ne manque, la résolution
        // du dessin n'est jamais réduite par le zoom de la caméra —
        // SAUF en mode kid zoom verrouillé (2026-08-21, demande explicite :
        // "que quand on exporte l'oeuvre il soit rempli pour le mode kid") :
        // la page fait 2× l'écran, donc exporter la page entière laisse une
        // grosse marge vide autour du dessin que l'enfant n'a jamais vue ni
        // pu atteindre (caméra figée). On exporte alors exactement la
        // fenêtre visible (camX/camY, taille écran) — toujours rempli.
        val originX = if (zoomLocked) camX else 0f
        val originY = if (zoomLocked) camY else 0f
        val regionW = if (zoomLocked) width.toFloat() else pageW.toFloat()
        val regionH = if (zoomLocked) height.toFloat() else pageH.toFloat()
        val w = (regionW * scale).toInt().coerceAtLeast(1)
        val h = (regionH * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        c.translate(-originX, -originY)
        c.drawRect(originX, originY, originX + regionW, originY + regionH, bgPaint)
        val paint = Paint()
        for (calque in calques) {
            calque.paintLayer?.let { layer ->
                paint.alpha = (calque.opacity * 255).toInt()
                c.drawBitmap(layer, 0f, 0f, paint)
            }
        }
        if (includeObstacles && obstacles.isNotEmpty()) {
            val savedSelection = selectedObstacleIndex
            selectedObstacleIndex = -1 // pas de poignées/croix dans l'export
            drawObstacles(c)
            selectedObstacleIndex = savedSelection
        }
        if (includeBall) drawBalls(c)
        return bmp
    }

    // ---------------------------------------------------------------- capteur

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                // Filtre passe-bas exponentiel : lisse le bruit du capteur
                gravityX = smoothing * event.values[0] + (1f - smoothing) * gravityX
                gravityY = smoothing * event.values[1] + (1f - smoothing) * gravityY
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Vitesse angulaire instantanée (rad/s) — pas d'intégration,
                // pas de dérive : chaque coup de poignet est une impulsion
                gyroX = event.values[0]
                gyroY = event.values[1]
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Accélération linéaire sans gravité (m/s²) — détection de
                // secousses : on garde les dernières valeurs, le pic est
                // détecté dans updatePhysics (appliqué en impulsion)
                accelX = event.values[0]
                accelY = event.values[1]
                accelZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------------------------------------------------------- cycle

    private fun initPaintLayer(c: Calque) {
        // la « page » fait 3× l'écran : on peut zoomer pour les détails
        // sans jamais baisser la résolution du dessin
        val pw = max(1, (width * 3).coerceAtLeast(1))
        val ph = max(1, (height * 3).coerceAtLeast(1))
        if (pageW != pw || pageH != ph) {
            pageW = pw
            pageH = ph
            camX = (pageW - width / camScale) / 2f
            camY = (pageH - height / camScale) / 2f
        }
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        c.paintCanvas.setBitmap(bmp)
        c.paintLayer = bmp
        val wet = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        c.wetCanvas.setBitmap(wet)
        c.wetLayer = wet
        c.prevPosX = c.ballX
        c.prevPosY = c.ballY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // BUG CORRIGÉ (2026-08-19, retour d'usage : "aux extrémités de l'écran
        // ça dessine pas", confirmé sur bords SANS aucune UI dessus — gauche/
        // haut/bas) : sur téléphone en navigation gestuelle (Android 10+), une
        // fine bande sur les bords de l'écran est réservée par le SYSTÈME pour
        // ses propres gestes (retour arrière au bord gauche/droit, tiroir de
        // notifications en haut, geste home en bas) — un toucher qui commence
        // dans cette bande n'atteint jamais la vue de l'appli, quel que soit
        // son code. `setSystemGestureExclusionRects` (API 29+) réclame toute
        // la surface du canvas pour qu'Android ne vole plus ces touchers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
        val density = resources.displayMetrics.density
        ballRadius = ballRadiusDp * density
        trailWidth = trailWidthDp * density
        ballTrailWidthPx = ballRadius * 1.9f

        applyPageSize(w, h)
    }

    // 2026-08-25, demande explicite : "avoir une option pour réduire la
    // taille ou la résolution [de la page]" (Galaxy A13, ça ramait) — la
    // page est un bitmap ARGB_8888 par calque (peinture + couche humide),
    // pageOversizeFactor² fois la surface de l'écran ; le clone périodique
    // de la couche humide pour l'échantillonnage de couleur (cf.
    // sampleSnapshot) copie ce bitmap EN ENTIER toutes les 250ms — réduire
    // le facteur réduit mémoire ET coût de cette copie, sans toucher à la
    // logique d'échantillonnage elle-même (aucun risque sur la précision
    // des couleurs mélangées, contrairement à un recadrage de la copie).
    // Reconstruit les bitmaps à la taille courante — mêmes garde-fous que
    // onSizeChanged (contenu conservé, juste rogné si la page rétrécit).
    var pageOversizeFactor: Float = 2f
        set(value) {
            if (field == value) return
            field = value
            if (width > 0 && height > 0) applyPageSize(width, height)
        }

    private fun applyPageSize(w: Int, h: Int) {
        // page pageOversizeFactor× l'écran + caméra centrée sur la page
        pageW = max(1, (w * pageOversizeFactor).toInt())
        pageH = max(1, (h * pageOversizeFactor).toInt())
        camX = (pageW - w / camScale) / 2f
        camY = (pageH - h / camScale) / 2f

        // Redimensionne la couche de peinture de chaque calque (contenu
        // conservé si la taille n'a pas changé)
        for (c in calques) {
            val old = c.paintLayer
            if (old != null && old.width == pageW && old.height == pageH) continue
            val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
            c.paintCanvas.setBitmap(bmp)
            if (old != null) {
                c.paintCanvas.drawBitmap(old, 0f, 0f, null)
                old.recycle()
            }
            c.paintLayer = bmp
            // couche humide : recréée vide (volatile, elle se remplit au dessin)
            c.wetLayer?.recycle()
            val wet = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
            c.wetCanvas.setBitmap(wet)
            c.wetLayer = wet
            if (c.ballX == 0f && c.ballY == 0f) {
                c.ballX = pageW / 2f
                c.ballY = pageH / 2f
                c.prevPosX = c.ballX
                c.prevPosY = c.ballY
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameTime == 0L) 0f else ((now - lastFrameTime) / 1e9f).coerceIn(0f, 0.033f)
        lastFrameTime = now

        if (!paused) {
            step(dt)
        }

        // fond « bureau » (au-delà de la page quand on dézoome beaucoup)
        canvas.drawColor(0xFF2B2B2B.toInt())

        // vue caméra : tout le monde est transformé (zoom/pan)
        canvas.save()
        canvas.scale(camScale, camScale)
        canvas.translate(-camX, -camY)

        // fond de la PAGE entière (couleur réglable)
        canvas.drawRect(0f, 0f, pageW.toFloat(), pageH.toFloat(), bgPaint)

        // calques empilés : la couche de peinture contient TOUT (traînées,
        // lignes, taches posées) dans l'ordre temporel — une tache posée est
        // au-dessus de la peinture passée, et la peinture suivante la recouvre.
        // Rendu OPTIMISÉ : seule la zone visible du calque est copiée (la page
        // fait 2× l'écran — dessiner le calque entier à chaque frame ramait).
        val vx = camX.coerceIn(0f, pageW.toFloat())
        val vy = camY.coerceIn(0f, pageH.toFloat())
        val vw = (width / camScale).coerceAtMost(pageW - vx)
        val vh = (height / camScale).coerceAtMost(pageH - vy)
        for (c in calques) {
            c.paintLayer?.let { layer ->
                layerPaint.alpha = (c.opacity * 255).toInt()
                if (vw > 0f && vh > 0f) {
                    layerZoneRect.set(vx.toInt(), vy.toInt(), (vx + vw).toInt(), (vy + vh).toInt())
                    canvas.drawBitmap(layer, layerZoneRect, layerZoneRect, layerPaint)
                }
            }
        }

        // billes : ombre + corps + reflet — extrait en fonction (2026-08-13,
        // demande explicite : cacher la bille + l'inclure/l'exclure des
        // exports) pour être appelable aussi bien depuis onDraw que depuis
        // renderArtwork, sans dupliquer le rendu.
        if (ballVisible) drawBalls(canvas)

        // obstacles de construction — DANS la transformation caméra,
        // par-dessus, hors des calques. Affichés seulement quand
        // constructionVisible = true. Extrait en fonction pour la même
        // raison que drawBalls ci-dessus (réutilisable pour l'export).
        if (constructionVisible && obstacles.isNotEmpty()) drawObstacles(canvas)

        // preview ligne pendant le geste continu de placement : point A fixe
        // (down) + segment en direct jusqu'à la position actuelle du doigt
        if (lignePhase == 1) {
            limitePaint.style = Paint.Style.FILL
            limitePaint.color = 0x88FFEB3B.toInt()
            limitePaint.strokeWidth = 0f
            canvas.drawCircle(obstacleX1, obstacleY1, 8f, limitePaint)
            limitePaint.style = Paint.Style.STROKE
            limitePaint.strokeWidth = 6f
            limitePaint.color = 0x88FFEB3B.toInt()
            canvas.drawLine(obstacleX1, obstacleY1, obstacleX2, obstacleY2, limitePaint)
        }

        // preview portail : disque rouge fixe (1er point, tap ou down) +
        // disque bleu qui suit le doigt (2e point, glissé en cours) — pas de
        // disque bleu tant qu'aucun 2e point n'a encore bougé (mode
        // tap-puis-tap en attente : sinon les 2 cercles superposés au même
        // endroit masqueraient le rouge sous un bleu trompeur).
        if (portailPhase == 1) {
            limitePaint.style = Paint.Style.FILL
            limitePaint.color = 0x88E53935.toInt()
            canvas.drawCircle(portailX1, portailY1, PORTAIL_DEFAULT_R, limitePaint)
            if (hypot(portailX2 - portailX1, portailY2 - portailY1) > 1f) {
                limitePaint.color = 0x882196F3.toInt()
                canvas.drawCircle(portailX2, portailY2, PORTAIL_DEFAULT_R, limitePaint)
            }
        }

        canvas.restore()

        // jauge d'encre de la bille active (bas, centrée) : niveau de réserve
        // en direct — 0 = vide (la bille ne peint plus), pleine = max
        if (showJauge && calques.isNotEmpty()) {
            val c = calques[actif]
            val frac = (c.inkReserve / INK_AMOUNT).coerceIn(0f, 1f)
            val d = resources.displayMetrics.density
            val jw = 150f * d
            val jh = 8f * d
            val jx = (width - jw) / 2f
            val jy = height - 34f * d
            gaugePaint.color = 0x55000000.toInt()
            canvas.drawRoundRect(jx, jy, jx + jw, jy + jh, jh / 2f, jh / 2f, gaugePaint)
            val portee = c.carriedColor ?: c.baseColor
            gaugePaint.color = (portee and 0x00FFFFFF) or 0xCC000000.toInt()
            if (frac > 0.01f) {
                canvas.drawRoundRect(jx, jy, jx + jw * frac, jy + jh, jh / 2f, jh / 2f, gaugePaint)
            }
        }

        // boucle de rendu : continue seulement en mode play
        if (!paused) postInvalidateOnAnimation()
    }

    /** Mélange linéaire vers une couleur cible (blanc/noir) — utilisé par le
     *  dégradé de volume (cf. volumeMode), même technique que
     *  billeSphereDrawable côté MainActivity. */
    private fun mixToward(c: Int, target: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c) + (Color.red(target) - Color.red(c)) * f).toInt().coerceIn(0, 255),
            (Color.green(c) + (Color.green(target) - Color.green(c)) * f).toInt().coerceIn(0, 255),
            (Color.blue(c) + (Color.blue(target) - Color.blue(c)) * f).toInt().coerceIn(0, 255),
        )
    }

    private fun drawBalls(canvas: Canvas) {
        // la bille tenue est « soulevée » (ombre portée étalée), elle ne
        // touche pas la feuille
        for (ci in calques.indices) {
            val c = calques[ci]
            // rayon purement visuel (cf. cibleBallRadiusFactor) — le rayon
            // de collision `ballRadius` reste inchangé partout ailleurs.
            val visualRadius = ballRadius * cibleBallRadiusFactor(hypot(c.velX, c.velY))
            if (ci == dragIndex) {
                shadowPaint.color = 0x40000000.toInt()
                canvas.drawCircle(c.ballX + 8f, c.ballY + 14f, ballRadius * 1.4f, shadowPaint)
            } else {
                shadowPaint.color = 0x30000000.toInt()
                canvas.drawCircle(c.ballX + 4f, c.ballY + 5f, visualRadius, shadowPaint)
            }
            // Halo de vitesse de libération (2026-08-13, demande explicite —
            // concept éducatif réel : v_libération = √(2×masse/distance).
            // Rouge = la bille n'a pas assez de vitesse pour s'échapper de la
            // planète la plus proche à cette distance, vert = elle s'échappe.
            // Seulement si une planète est à portée (sinon rien à indiquer).
            // Anti-Planète exclue : pas de notion d'« échapper » à une
            // répulsion (2026-08-13, ajout Anti-Planète).
            if (constructionVisible) {
                var bestP: Obstacle.Planete? = null
                var bestD = Float.MAX_VALUE
                for (o in obstacles) {
                    if (o is Obstacle.Planete && !o.repel) {
                        val d = hypot(c.ballX - o.cx, c.ballY - o.cy)
                        if (d < o.influenceRadius && d < bestD) { bestD = d; bestP = o }
                    }
                }
                bestP?.let { p ->
                    val vEscape = sqrt(2f * p.mass / bestD.coerceAtLeast(1f))
                    val speed = hypot(c.velX, c.velY)
                    val ratio = (speed / vEscape).coerceIn(0f, 1f)
                    haloPaint.color = Color.rgb((255 * (1f - ratio)).toInt(), (255 * ratio).toInt(), 60)
                    canvas.drawCircle(c.ballX, c.ballY, ballRadius * 1.7f, haloPaint)
                }
            }
            // 2026-09-01, demande explicite : "la bille doit refléter la
            // couleur qu'elle écrit" — même principe que la taille du tracé
            // (cibleBallRadiusFactor) ci-dessus : la bille reflétait
            // seulement sa couleur statique (ballColor, réglage de la
            // bille), jamais la couleur RÉELLEMENT en train d'être peinte
            // (c.carriedColor, mélange pigmentaire au contact) — déjà
            // utilisée par paintColor() pour le tracé lui-même, jamais
            // reportée sur le disque de la bille. Retombe sur ballColor
            // tant qu'aucune couleur n'est portée (pas encore touché de
            // peinture).
            val currentBallColor = when {
                speedColorMode -> speedColor(smoothedColorSpeed(c, hypot(c.velX, c.velY)))
                rainbowMode -> rainbowColor()
                else -> c.carriedColor ?: ballColor
            }
            if (volumeMode) {
                // 2026-08-25, demande explicite : même dégradé que les icônes
                // de bille (billeSphereDrawable côté MainActivity) — clair
                // mélangé vers le blanc en haut-gauche, sombre mélangé vers
                // le noir en bas-droite, calculé depuis la couleur réelle
                // (fonctionne même sur une couleur déjà très sombre/claire,
                // contrairement à une simple superposition à l'alpha).
                val light = mixToward(currentBallColor, Color.WHITE, 0.55f)
                val dark = mixToward(currentBallColor, Color.BLACK, 0.45f)
                if (volumeGradient == null || light != volumeGradientLight ||
                    dark != volumeGradientDark || visualRadius != volumeGradientRadius
                ) {
                    volumeGradient = RadialGradient(
                        -visualRadius * 0.35f, -visualRadius * 0.35f, visualRadius * 1.8f,
                        light, dark, Shader.TileMode.CLAMP
                    )
                    volumeGradientLight = light
                    volumeGradientDark = dark
                    volumeGradientRadius = visualRadius
                }
                volumeGradientMatrix.setTranslate(c.ballX, c.ballY)
                volumeGradient!!.setLocalMatrix(volumeGradientMatrix)
                volumePaint.shader = volumeGradient
                canvas.drawCircle(c.ballX, c.ballY, visualRadius, volumePaint)
            } else {
                ballPaint.color = currentBallColor
                canvas.drawCircle(c.ballX, c.ballY, visualRadius, ballPaint)
                ballPaint.color = 0x66FFFFFF.toInt()
                canvas.drawCircle(c.ballX - visualRadius * 0.32f, c.ballY - visualRadius * 0.36f,
                    visualRadius * 0.26f, ballPaint)
            }
        }
    }

    /** Formes des obstacles + poignées d'édition/croix de suppression sur la
     *  forme sélectionnée. Pour un rendu "propre" (export), appeler avec
     *  [selectedObstacleIndex] = -1 (aucune poignée ne s'affiche alors). */
    private fun drawObstacles(canvas: Canvas) {
            limitePaint.style = Paint.Style.STROKE
            limitePaint.strokeWidth = 8f
            // liseré du Portail : noir par défaut (se détache du fond clair
            // habituel), blanc si le fond est lui-même sombre/noir (sinon
            // invisible) — cf. Obstacle.Portail plus bas dans cette boucle.
            val bgIsDark = (Color.red(bgColor) * 0.299 + Color.green(bgColor) * 0.587 + Color.blue(bgColor) * 0.114) < 60
            for (oi in obstacles.indices) {
                val o = obstacles[oi]
                val selected = oi == selectedObstacleIndex
                limitePaint.color = if (selected) 0xCCFF9800.toInt() else 0xCC00E676.toInt()
                limitePaint.strokeWidth = if (selected) 10f else 8f
                when (o) {
                    is Obstacle.Mur -> {
                        if (o.pts.size >= 2) {
                            limitePaint.style = Paint.Style.STROKE
                            obstaclePath.rewind()
                            obstaclePath.moveTo(o.pts[0].first, o.pts[0].second)
                            for (i in 1 until o.pts.size) obstaclePath.lineTo(o.pts[i].first, o.pts[i].second)
                            canvas.drawPath(obstaclePath, limitePaint)
                        }
                        if (selected && o.pts.isNotEmpty()) {
                            // poignée unique (1er point) — tourne et
                            // redimensionne tout le tracé autour de son centre
                            limitePaint.style = Paint.Style.FILL
                            limitePaint.color = 0xFFFFEB3B.toInt()
                            canvas.drawCircle(o.pts[0].first, o.pts[0].second, HANDLE_RADIUS, limitePaint)
                        }
                    }
                    is Obstacle.Bouchon -> {
                        limitePaint.style = Paint.Style.STROKE
                        canvas.drawCircle(o.cx, o.cy, o.r, limitePaint)
                        if (selected) {
                            // poignée unique (bord droit) — agrandir en
                            // l'éloignant du centre ; visible seulement
                            // sélectionné
                            limitePaint.style = Paint.Style.FILL
                            limitePaint.color = 0xFFFFEB3B.toInt()
                            canvas.drawCircle(o.cx + o.r, o.cy, HANDLE_RADIUS, limitePaint)
                        }
                    }
                    is Obstacle.Triangle -> {
                        obstaclePath.rewind()
                        obstaclePath.moveTo(o.x1, o.y1)
                        obstaclePath.lineTo(o.x2, o.y2)
                        obstaclePath.lineTo(o.x3, o.y3)
                        obstaclePath.close()
                        limitePaint.style = Paint.Style.STROKE
                        canvas.drawPath(obstaclePath, limitePaint)
                        if (selected) {
                            // poignée unique (sommet 1, la pointe) — tourne
                            // et redimensionne le triangle autour de son
                            // centre ; visible seulement sélectionné
                            limitePaint.style = Paint.Style.FILL
                            limitePaint.color = 0xFFFFEB3B.toInt()
                            canvas.drawCircle(o.x1, o.y1, HANDLE_RADIUS, limitePaint)
                            // poignée base (milieu du côté x2-x3, couleur
                            // distincte) — tourne juste ce côté autour de son
                            // propre milieu, longueur et pointe inchangées
                            // (2026-08-14, demande explicite)
                            limitePaint.color = 0xFF29B6F6.toInt()
                            canvas.drawCircle((o.x2 + o.x3) / 2f, (o.y2 + o.y3) / 2f, HANDLE_RADIUS, limitePaint)
                        }
                    }
                    is Obstacle.Rectangle -> {
                        obstaclePath.rewind()
                        obstaclePath.moveTo(o.x1, o.y1)
                        obstaclePath.lineTo(o.x2, o.y2)
                        obstaclePath.lineTo(o.x3, o.y3)
                        obstaclePath.lineTo(o.x4, o.y4)
                        obstaclePath.close()
                        limitePaint.style = Paint.Style.STROKE
                        canvas.drawPath(obstaclePath, limitePaint)
                        if (selected) {
                            // même principe que Triangle : poignée unique
                            // (sommet 1), tourne+redimensionne autour du centre
                            limitePaint.style = Paint.Style.FILL
                            limitePaint.color = 0xFFFFEB3B.toInt()
                            canvas.drawCircle(o.x1, o.y1, HANDLE_RADIUS, limitePaint)
                            // poignées de segment (milieu de chaque côté,
                            // couleur distincte, un peu plus petites) — signalent
                            // que chaque côté peut être tiré pour élargir/allonger
                            // ce côté seul (déjà actif au toucher, juste rendu
                            // visible ici — 2026-08-14, demande explicite)
                            limitePaint.color = 0xFF29B6F6.toInt()
                            val milieux = listOf(
                                (o.x1 + o.x2) / 2f to (o.y1 + o.y2) / 2f,
                                (o.x2 + o.x3) / 2f to (o.y2 + o.y3) / 2f,
                                (o.x3 + o.x4) / 2f to (o.y3 + o.y4) / 2f,
                                (o.x4 + o.x1) / 2f to (o.y4 + o.y1) / 2f
                            )
                            for ((mx, my) in milieux) canvas.drawCircle(mx, my, HANDLE_RADIUS * 0.7f, limitePaint)
                        }
                    }
                    is Obstacle.Ligne -> {
                        // quadTo avec cx,cy = milieu de x1y1/x2y2 rend une
                        // droite exacte — pas besoin de cas séparé
                        limitePaint.style = Paint.Style.STROKE
                        // trampoline (2026-08-29) : couleur distincte (rose vif)
                        // pour signaler au coup d'œil que ça rebondit fort,
                        // même style sinon — même logique que repel/Planète.
                        if (o.trampoline) {
                            limitePaint.color = if (selected) 0xFFFF4081.toInt() else 0xFFE91E63.toInt()
                            limitePaint.strokeWidth = if (selected) 12f else 10f
                        }
                        obstaclePath.rewind()
                        obstaclePath.moveTo(o.x1, o.y1)
                        obstaclePath.quadTo(o.cx, o.cy, o.x2, o.y2)
                        canvas.drawPath(obstaclePath, limitePaint)
                        if (selected) {
                            // poignées d'extrémité — seulement tant que la
                            // ligne est sélectionnée, pour l'édition
                            // (inclinaison/taille) ; disparaissent sinon
                            limitePaint.style = Paint.Style.FILL
                            limitePaint.color = 0xFFFFEB3B.toInt()
                            canvas.drawCircle(o.x1, o.y1, HANDLE_RADIUS, limitePaint)
                            canvas.drawCircle(o.x2, o.y2, HANDLE_RADIUS, limitePaint)
                            // poignée de centre (couleur distincte) — la
                            // bouger courbe la ligne (point de contrôle),
                            // x1y1/x2y2 restent fixes
                            limitePaint.color = 0xFF29B6F6.toInt()
                            canvas.drawCircle(o.cx, o.cy, HANDLE_RADIUS, limitePaint)
                        }
                    }
                    is Obstacle.Portail -> {
                        // 2 disques pleins (rouge/bleu, cf. doc de
                        // Obstacle.Portail) — pas de silhouette verte comme
                        // les autres obstacles, la couleur EST la forme ;
                        // liseré NOIR (2026-08-11, demande explicite : "le
                        // portail doit être entouré d'un cercle noir sauf si
                        // le fond est noir") pour le détacher du fond clair
                        // habituel — bascule en blanc si le fond lui-même est
                        // sombre (cf. bgIsDark plus haut), sinon invisible.
                        limitePaint.style = Paint.Style.FILL
                        limitePaint.color = 0xFFE53935.toInt()
                        canvas.drawCircle(o.x1, o.y1, o.r, limitePaint)
                        limitePaint.color = 0xFF2196F3.toInt()
                        canvas.drawCircle(o.x2, o.y2, o.r, limitePaint)
                        limitePaint.style = Paint.Style.STROKE
                        limitePaint.strokeWidth = if (selected) 6f else 4f
                        limitePaint.color = if (selected) 0xFFFF9800.toInt()
                            else if (bgIsDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                        canvas.drawCircle(o.x1, o.y1, o.r, limitePaint)
                        canvas.drawCircle(o.x2, o.y2, o.r, limitePaint)
                    }
                    is Obstacle.Planete -> {
                        // Noyau solide (comme Bouchon) + halo d'influence
                        // (cercle pointillé discret autour, zone où la gravité agit).
                        // Anti-Planète (repel) : même design, palette orange au
                        // lieu de violette pour signaler l'inversion (2026-08-13).
                        limitePaint.style = Paint.Style.STROKE
                        limitePaint.strokeWidth = 4f
                        limitePaint.color = if (o.repel) {
                            if (selected) 0xFFFF6D00.toInt() else 0xFFE65100.toInt()
                        } else {
                            if (selected) 0xFF9C27B0.toInt() else 0xFF7B1FA2.toInt()
                        }
                        canvas.drawCircle(o.cx, o.cy, o.influenceRadius, limitePaint)
                        limitePaint.style = Paint.Style.FILL
                        limitePaint.color = if (o.repel) 0xFFBF360C.toInt() else 0xFF4A148C.toInt()
                        canvas.drawCircle(o.cx, o.cy, o.r, limitePaint)
                        if (selected) {
                            // poignée de taille (bord droit, noyau) — la gravité vient
                            // de la valeur par défaut (Paramètres → Développeur →
                            // Outils, cf. planeteMassDefault/Inverse), pas éditable ici.
                            limitePaint.style = Paint.Style.FILL
                            limitePaint.color = 0xFFFFEB3B.toInt()
                            canvas.drawCircle(o.cx + o.r, o.cy, HANDLE_RADIUS, limitePaint)
                            // poignée de zone d'influence (bord bas, sur le halo) —
                            // indépendante du noyau ET de la gravité (2026-08-29).
                            limitePaint.color = 0xFF29B6F6.toInt()
                            val (ix, iy) = planeteInfluenceHandlePos(o)
                            canvas.drawCircle(ix, iy, HANDLE_RADIUS, limitePaint)
                        }
                    }
                    is Obstacle.Accelerateur -> {
                        // zone non solide : couleur = sens de la jauge (vert accélère,
                        // rouge ralentit, gris neutre à 0) — pas de remplissage plein,
                        // juste un anneau + chevrons pour ne pas masquer le dessin dessous
                        val g = o.gauge.coerceIn(-1f, 1f)
                        val zoneColor = when {
                            g > 0.02f -> Color.rgb((76 + (255-76)*(1-g)).toInt(), 175, 80)
                            g < -0.02f -> Color.rgb(244, (67 + (200-67)*(1-(-g))).toInt(), 54)
                            else -> 0xFF9E9E9E.toInt()
                        }
                        limitePaint.style = Paint.Style.STROKE
                        limitePaint.strokeWidth = if (selected) 6f else 4f
                        limitePaint.color = zoneColor
                        canvas.drawCircle(o.cx, o.cy, o.r, limitePaint)
                        // double chevron au centre, dans le sens de l'effet (vers le
                        // haut si accélère, vers le bas si ralentit)
                        limitePaint.strokeWidth = 5f
                        limitePaint.strokeCap = Paint.Cap.ROUND
                        limitePaint.strokeJoin = Paint.Join.ROUND
                        val cs = o.r * 0.35f
                        val dir = if (g < 0f) -1f else 1f
                        for (k in 0..1) {
                            val oy = o.cy - dir * (cs * 0.5f) + dir * k * cs * 0.55f
                            obstaclePath.rewind()
                            obstaclePath.moveTo(o.cx - cs, oy + dir * cs * 0.5f)
                            obstaclePath.lineTo(o.cx, oy - dir * cs * 0.5f)
                            obstaclePath.lineTo(o.cx + cs, oy + dir * cs * 0.5f)
                            canvas.drawPath(obstaclePath, limitePaint)
                        }
                        limitePaint.strokeCap = Paint.Cap.BUTT
                        limitePaint.strokeJoin = Paint.Join.MITER
                        if (selected) {
                            // poignée de taille (bord droit) — seule chose éditable après
                            // coup, la jauge vient de la valeur par défaut (Paramètres →
                            // Développeur → Outils, cf. accelerateurGaugeDefault)
                            limitePaint.style = Paint.Style.FILL
                            limitePaint.color = 0xFFFFEB3B.toInt()
                            canvas.drawCircle(o.cx + o.r, o.cy, HANDLE_RADIUS, limitePaint)
                        }
                    }
                    is Obstacle.Ellipse -> {
                        limitePaint.style = Paint.Style.STROKE
                        val oval = android.graphics.RectF(o.cx - o.rx, o.cy - o.ry, o.cx + o.rx, o.cy + o.ry)
                        canvas.drawOval(oval, limitePaint)
                        if (selected) {
                            // 2 poignées indépendantes : bord droit = rx, bord bas = ry
                            limitePaint.style = Paint.Style.FILL
                            limitePaint.color = 0xFFFFEB3B.toInt()
                            canvas.drawCircle(o.cx + o.rx, o.cy, HANDLE_RADIUS, limitePaint)
                            limitePaint.color = 0xFF29B6F6.toInt()
                            canvas.drawCircle(o.cx, o.cy + o.ry, HANDLE_RADIUS, limitePaint)
                        }
                    }
                }
                // Petite croix de suppression (2026-08-11, demande explicite :
                // "quand on sélectionne une forme avec la flèche j'aimerais
                // qu'il y ait une petite croix pour la supprimer" — remplace
                // l'ancienne suppression par tap de la gomme, retirée).
                // Visible seulement sur la forme sélectionnée, décalée au-
                // dessus de son point le plus haut — cf. obstacleDeleteMarkerPos.
                if (selected) {
                    val (dx, dy) = obstacleDeleteMarkerPos(o)
                    limitePaint.style = Paint.Style.FILL
                    limitePaint.color = 0xFFE53935.toInt()
                    canvas.drawCircle(dx, dy, DELETE_MARKER_RADIUS, limitePaint)
                    limitePaint.style = Paint.Style.STROKE
                    limitePaint.strokeWidth = 4f
                    limitePaint.color = 0xFFFFFFFF.toInt()
                    val arm = DELETE_MARKER_RADIUS * 0.5f
                    canvas.drawLine(dx - arm, dy - arm, dx + arm, dy + arm, limitePaint)
                    canvas.drawLine(dx - arm, dy + arm, dx + arm, dy - arm, limitePaint)
                }
            }
        }

    /** Une frame de physique : accélération → vitesse → position → collisions → peinture. */
    private fun step(dt: Float) {
        val nowMs = SystemClock.uptimeMillis()
        // Signe : penché bord droit vers le bas → gx < 0 → la bille roule à
        // droite (pos.x += -gx). Penché haut vers le bas → gy > 0 → elle
        // descend (pos.y += gy). À valider en usage réel.
        // Zone morte inclinaison : en dessous de gravityDeadZone degrés,
        // la bille ne bouge pas — évite la dérive quand le phone est à plat.
        val gravMag = hypot(gravityX, gravityY) // m/s², ~9.8 quand vertical
        val deadZoneMs2 = 9.81f * sin(gravityDeadZone * PI.toFloat() / 180f)
        val gravActive = gravMag > deadZoneMs2
        val accX = if (gravActive) -curvedTilt(gravityX) * gravityToPx * tiltEffect else 0f
        val accY = if (gravActive) curvedTilt(gravityY) * gravityToPx * tiltEffect else 0f
        // Coup de poignet (gyroscope) : une rotation rapide du téléphone
        // frappe la bille comme au billard (impulsion, signes à valider)
        // × tiltEffect aussi : coup de poignet = mouvement du téléphone, même
        // réglage maître que l'inclinaison et la secousse
        val impX = if (abs(gyroY) > GYRO_DEADZONE) -gyroY * GYRO_TO_VEL * tiltEffect else 0f
        val impY = if (abs(gyroX) > GYRO_DEADZONE) gyroX * GYRO_TO_VEL * tiltEffect else 0f
        // Secousse (accéléromètre linéaire) : un pic d'accélération secoue
        // la bille — impulsion opposée à la direction du mouvement du phone.
        // × tiltEffect (2026-08-12, demande explicite) : un seul réglage
        // maître pour TOUT mouvement du téléphone (inclinaison ET secousse),
        // pas deux réglages séparés à penser à baisser tous les deux.
        val shakeMag = hypot(accelX, accelY)
        val shakeImpX = if (shakeMag > shakeThreshold) -accelX * shakeToVel * tiltEffect else 0f
        val shakeImpY = if (shakeMag > shakeThreshold) -accelY * shakeToVel * tiltEffect else 0f
        val tMix = (fonduRate * dt * 60f).coerceIn(0f, 1f) // pas du fondu pigmentaire (par frame)
        // ON : la bille reste dans l'intersection de l'écran visible ET de
        // la page (comportement d'origine). OFF (2026-08-13, demande
        // explicite) : elle peut sortir du CADRE AFFICHÉ, mais reste dans
        // la PAGE (le "monde") — sans ce garde-fou elle part à l'infini et
        // devient introuvable ("elle disparaît du monde", signalé après
        // un premier essai sans aucune limite). Ne dépend d'aucun calque
        // (juste caméra/page) — sorti de la boucle ci-dessous (2026-08-13,
        // même valeur recalculée à l'identique pour chaque calque jusqu'ici,
        // et réutilisée telle quelle pour les billes supplémentaires).
        val visL: Float; val visT: Float; val visR: Float; val visB: Float
        if (boundsActive) {
            visL = camX.coerceAtLeast(0f)
            visT = camY.coerceAtLeast(0f)
            visR = (camX + width / camScale).coerceAtMost(pageW.toFloat())
            visB = (camY + height / camScale).coerceAtMost(pageH.toFloat())
        } else {
            visL = 0f; visT = 0f; visR = pageW.toFloat(); visB = pageH.toFloat()
        }

        for (ci in calques.indices) {
            val c = calques[ci]

            // bille tenue par le doigt : pas de gravité ni de contact, mais la
            // trace suit le doigt (en play, la bille peint là où on la pousse)
            if (ci == dragIndex) {
                // bille soulevée par le doigt : elle ne peint pas (pas en
                // contact avec la feuille) — elle écrira au lancer
                c.prevPosX = c.ballX
                c.prevPosY = c.ballY
                // BUG TROUVÉ (2026-08-31, "quand je la lâche, ya un tracé qui
                // se fait avec l'endroit où je la lâche et la peinture sur
                // laquelle elle était passée") — `lastPaintX/Y` (dernier point
                // où un TAMPON a vraiment été posé, cf. stampBallTrail)
                // n'était jamais touché pendant que la bille est tenue : elle
                // garde la position d'AVANT la saisie. Au lancer, la peinture
                // reprend et relie ce vieux point au point de lâcher via une
                // ligne fantôme qui traverse tout ce qui se trouve entre les
                // deux — même bug déjà corrigé pour le portail et le
                // rebouclage d'écran (cf. leurs resets de lastPaintX/Y), pas
                // encore fait ici. Mis à jour à CHAQUE frame de saisie
                // (comme prevPosX/Y juste au-dessus) : `remaining` calculé au
                // prochain stampBallTrail() partira de la position de lâcher
                // elle-même, sans rien à relier.
                c.lastPaintX = Float.NaN
                c.lastPaintY = Float.NaN
                continue
            }

            // physique — décélération exponentielle indépendante du framerate ;
            // le POIDS (masse) : n'affecte PAS l'inclinaison (a = g pour toutes
            // les masses) mais divise l'effet des coups de poignet (billard) et
            // réduit la friction → une balle lourde roule plus longtemps.
            // Près d'une planète : friction quasi nulle (espace) pour permettre
            // les orbites. Sinon la friction normale du billard.
            val nearPlanete = constructionVisible && obstacles.any { o ->
                o is Obstacle.Planete && hypot(c.ballX - o.cx, c.ballY - o.cy) < o.influenceRadius
            }
            val effectiveFriction = if (nearPlanete) 0.02f else frictionRate
            val damp = exp(-effectiveFriction * dt / poids)
            val tiltCancelled = planeteCancelTilt && nearPlanete
            val accXEff = if (tiltCancelled) 0f else accX
            val accYEff = if (tiltCancelled) 0f else accY
            c.velX = (c.velX + accXEff * dt + (impX / poids) * dt + (shakeImpX / poids) * dt) * damp
            c.velY = (c.velY + accYEff * dt + (impY / poids) * dt + (shakeImpY / poids) * dt) * damp
            val speed = hypot(c.velX, c.velY)
            // 2026-08-13, bug réel trouvé par relecture : un clamp tanh appliqué
            // ICI, à CHAQUE frame, réduisait la vitesse même quand elle était déjà
            // sous le plafond (tanh(x) < x pour tout x > 0) — une friction fantôme
            // indépendante du réglage Friction (signalé : "0 friction, la bille
            // devrait continuer à la vitesse"). Le clamp souple ne doit s'appliquer
            // qu'UNE FOIS, au lancer (cf. bloc ACTION_UP) — ici on ne fait plus que
            // protéger contre un DÉPASSEMENT du plafond (gravité/accélérateur/
            // inclinaison qui pousseraient la vitesse au-delà avec le temps), donc
            // ne touche jamais une bille qui roule déjà sous MAX_SPEED.
            if (speed > MAX_SPEED) {
                c.velX *= MAX_SPEED / speed
                c.velY *= MAX_SPEED / speed
            }
            c.ballX += c.velX * dt
            c.ballY += c.velY * dt
            if (wrapActive) {
                // téléportation à l'opposé (comme Univers) au lieu de rebondir —
                // fonctionne aussi bien sur les bords du dessin (page) que de
                // l'écran (viewport), selon ce que visL/visT/visR/visB désignent
                // déjà via boundsActive ci-dessus.
                // Seuil décalé du rayon (2026-08-14, "est-ce que c'est logique
                // la façon dont la balle réapparaît ?" — recherche en ligne :
                // téléporter dès que le CENTRE franchit le bord fait apparaître
                // la bille à moitié coupée d'un côté puis à moitié coupée de
                // l'autre au même instant (effet de pop visible). Pratique
                // recommandée : attendre qu'elle soit ENTIÈREMENT sortie
                // (centre à + d'un rayon du bord) avant de téléporter — elle
                // disparaît complètement puis réapparaît intacte, juste en
                // retrait du bord opposé, au lieu d'être coupée en deux.
                val pw = visR - visL
                val ph = visB - visT
                // Position de sortie AVANT téléportation — sert à peindre le
                // dernier bout de trait jusqu'au bord (cf. plus bas).
                val exitX = c.ballX
                val exitY = c.ballY
                var wrapped = false
                var enteredRight = false // sortie par la gauche → rentre côté droit
                var enteredLeft = false  // sortie par la droite → rentre côté gauche
                var enteredBottom = false // sortie par le haut → rentre en bas
                var enteredTop = false    // sortie par le bas → rentre en haut
                if (c.ballX < visL - ballRadius) { c.ballX += pw; wrapped = true; enteredRight = true }
                if (c.ballX > visR + ballRadius) { c.ballX -= pw; wrapped = true; enteredLeft = true }
                if (c.ballY < visT - ballRadius) { c.ballY += ph; wrapped = true; enteredBottom = true }
                if (c.ballY > visB + ballRadius) { c.ballY -= ph; wrapped = true; enteredTop = true }
                if (wrapped) {
                    // 2026-08-19 : COMPORTEMENT VOULU, précisé après 2 essais
                    // (bridge traversant l'écran essayé puis rejeté — demande
                    // explicite : "je veux juste qu'elle réapparaisse en haut de
                    // l'écran et dessine tout du long", PAS de trait qui saute
                    // d'un bord à l'autre) : peindre le dernier segment réel
                    // jusqu'au bord de sortie AVANT de téléporter (sinon ce bout
                    // de trajet, entre le début de la frame et le bord, n'est
                    // jamais dessiné — cf. pdist≈0 au bloc de peinture principal
                    // une fois prevPosX/Y resetés ci-dessous), PUIS repartir
                    // intact à l'entrée SANS relier les deux bords entre eux.
                    if (c.carriedColor != null || rainbowMode || speedColorMode || cometTrail) {
                        val exitDist = hypot(exitX - c.prevPosX, exitY - c.prevPosY)
                        if (exitDist > MIN_MOVE) {
                            // un seul étage de lissage (vitesse, pas la largeur
                            // résultante) — cf. commentaire détaillé au point
                            // d'appel principal plus bas dans ce fichier.
                            c.smoothedTrailWidthBall = cibleTrailWidth(smoothedWidthSpeed(c, hypot(c.velX, c.velY)))
                            stampBallTrail(c, exitX, exitY)
                        }
                    }
                    // 2026-08-13, bug réel signalé ("l'angle d'entrée et de sortie
                    // ne correspond pas") : sans ce reset, prevPosX/Y gardent
                    // l'ancienne position d'avant le saut — collidePolyline plus
                    // bas dans cette même frame calcule alors un trajet énorme
                    // (l'ancienne position → l'autre bout de la page/écran) et
                    // peut percuter un obstacle sur cette ligne fantôme, faussant
                    // la vitesse de sortie. Même principe que handlePortail
                    // (téléportation Portail) juste au-dessus dans ce fichier.
                    c.prevPosX = c.ballX
                    c.prevPosY = c.ballY
                    // Idem pour `joinPrevBallX/Y` (l'ancre séparée de la jonction
                    // arrondie entre frames, cf. bloc de peinture ~L2350) : sans
                    // ce reset, le prochain trait peint relierait le bord de
                    // sortie au bord d'entrée via un `Path` à 3 points — le
                    // bridge qui traverse tout l'écran, explicitement pas voulu.
                    c.lastPaintX = Float.NaN
                    c.lastPaintY = Float.NaN
                    // BUG CORRIGÉ (2026-08-19, précisé par l'utilisateur après
                    // plusieurs allers-retours : "ya un blanc entre la limite de
                    // l'écran et le début du trait") : le point d'ENTRÉE (calculé
                    // par le += pw/ph ci-dessus) est TOUJOURS à au moins un rayon
                    // de bille à l'intérieur du bord visible, par construction —
                    // le seuil de sortie exige que le CENTRE ait déjà dépassé le
                    // bord opposé d'un rayon plein (2026-08-14, pour éviter que la
                    // bille apparaisse visuellement coupée en deux à l'écran), et
                    // ce même écart se retrouve tel quel du côté entrée. La bille
                    // elle-même ne doit pas être redessinée plus près du bord
                    // (même raison), mais rien n'empêche d'étendre le TRAIT : un
                    // court segment du bord réel jusqu'au point d'entrée comble
                    // ce blanc, sans jamais afficher la bille à moitié coupée.
                    if (c.carriedColor != null || rainbowMode || speedColorMode || cometTrail) {
                        // BUG CORRIGÉ (2026-08-19, retour d'usage : "ça a créé des
                        // fausses lignes") : la 1ère version de ce bouchon traçait
                        // un segment horizontal/vertical pur (bord → point d'entrée
                        // à X ou Y fixe) — sur une trajectoire diagonale, ça
                        // dessinait un coude à angle droit qui ne correspond à rien
                        // de réel. Fix : extrapoler EN ARRIÈRE le long de la vraie
                        // direction de déplacement (velX/velY, inchangée par le
                        // wrap) jusqu'au bord — le bouchon suit alors exactement
                        // l'angle de la trajectoire, pas un axe arbitraire.
                        if (c.smoothedTrailWidthBall < 0f) c.smoothedTrailWidthBall = textureWidth(ballTrailWidthPx)
                        fun stub(edgeX: Float, edgeY: Float) {
                            // ancre explicite : lastPaintX/Y vient d'être remis à
                            // NaN par le reset de téléportation ci-dessus, sinon
                            // stampBallTrail n'aurait rien à peindre (distance 0
                            // à lui-même au 1er appel)
                            c.lastPaintX = edgeX
                            c.lastPaintY = edgeY
                            stampBallTrail(c, c.ballX, c.ballY)
                        }
                        if (enteredRight && kotlin.math.abs(c.velX) > 0.01f) {
                            val t = (visR - c.ballX) / c.velX
                            stub(visR, (c.ballY + c.velY * t).coerceIn(visT, visB))
                        }
                        if (enteredLeft && kotlin.math.abs(c.velX) > 0.01f) {
                            val t = (visL - c.ballX) / c.velX
                            stub(visL, (c.ballY + c.velY * t).coerceIn(visT, visB))
                        }
                        if (enteredBottom && kotlin.math.abs(c.velY) > 0.01f) {
                            val t = (visB - c.ballY) / c.velY
                            stub((c.ballX + c.velX * t).coerceIn(visL, visR), visB)
                        }
                        if (enteredTop && kotlin.math.abs(c.velY) > 0.01f) {
                            val t = (visT - c.ballY) / c.velY
                            stub((c.ballX + c.velX * t).coerceIn(visL, visR), visT)
                        }
                    }
                    UsageLog.d("wrap calque=${ci + 1}: sortie visL/T/R/B=(%.0f,%.0f,%.0f,%.0f) entrée(%.0f,%.0f) carried=%s ink=%.0f".format(
                        visL, visT, visR, visB, c.ballX, c.ballY, c.carriedColor?.let { "#%06X".format(it and 0xFFFFFF) } ?: "none", c.inkReserve))
                }
            } else {
                if (c.ballX < visL + ballRadius) { c.ballX = visL + ballRadius; c.velX = -c.velX * restitution }
                if (c.ballX > visR - ballRadius) { c.ballX = visR - ballRadius; c.velX = -c.velX * restitution }
                if (c.ballY < visT + ballRadius) { c.ballY = visT + ballRadius; c.velY = -c.velY * restitution }
                if (c.ballY > visB - ballRadius) { c.ballY = visB - ballRadius; c.velY = -c.velY * restitution }
            }

            // Planètes : gravité APRÈS le damping (sinon la friction bouffe
            // l'attraction et rend les orbites impossibles). Une seule planète
            // active à la fois (la plus proche).
            if (constructionVisible) {
                var bestDist = Float.MAX_VALUE
                var bestPlanete: Obstacle.Planete? = null
                for (o in obstacles) {
                    if (o is Obstacle.Planete) {
                        val d = hypot(c.ballX - o.cx, c.ballY - o.cy)
                        if (d < o.influenceRadius && d < bestDist) {
                            bestDist = d
                            bestPlanete = o
                        }
                    }
                }
                bestPlanete?.let { p ->
                    val dxP = c.ballX - p.cx
                    val dyP = c.ballY - p.cy
                    val dist = hypot(dxP, dyP)
                    if (dist > p.r && dist > 1f) {
                        // distance adoucie pour le calcul de force (2026-08-12,
                        // demande explicite : "la gravité éjecte carrément la
                        // balle") : juste à la surface du noyau, dist≈r, la
                        // force 1/d² explose (surtout avec les masses élevées
                        // permises depuis le dernier réglage) — la bille rebondit
                        // sur le noyau puis se prend un fouet. Plancher à 1.5×r,
                        // n'affecte pas la distance réelle (collision/rendu),
                        // seulement l'intensité de la force près de la surface.
                        val distForce = dist.coerceAtLeast(p.r * 1.5f)
                        // 2026-08-29, demande explicite : "plus elle est grosse plus
                        // elle est forte, mais que ça soit utilisable et fun" — la
                        // taille du noyau (poignée jaune) multiplie maintenant la
                        // masse effective par (r/R0)², R0 = taille par défaut. Choix
                        // du carré (pas juste r, pas cube) : au ras de la surface, où
                        // le plancher ci-dessus vaut 1.5×r, la force max devient
                        // mass/(1.5×R0)² — CONSTANTE, indépendante de r (jamais
                        // "explosive" même en agrandissant beaucoup) — mais à distance
                        // d'orbite normale (d >> r, hors du plancher), la force réelle
                        // scale bien en r² : une planète 2× plus grosse tire ~4× plus
                        // fort à distance égale, ressenti net sans casser le contrôle
                        // près du noyau.
                        val sizeFactor = (p.r / OBSTACLE_DEFAULT_R).let { it * it }
                        // 2026-08-29, demande explicite : "ça serait mieux" (fondu en
                        // sortant de la zone plutôt qu'une coupure nette à d==influenceRadius
                        // — la bille perdait toute attraction d'un coup). Fondu SEULEMENT sur
                        // le dernier quart de la zone (PLANETE_FALLOFF_START=0.75) — la
                        // portée (poignée bleue) et la puissance restent des réglages
                        // indépendants, ce fondu ne change que le bord, pas le ressenti au
                        // centre de l'orbite. Smoothstep (3t²-2t³) plutôt que linéaire : pas
                        // de cassure de pente au début du fondu.
                        val fadeStart = p.influenceRadius * PLANETE_FALLOFF_START
                        val falloff = if (dist > fadeStart) {
                            val t = ((dist - fadeStart) / (p.influenceRadius - fadeStart).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            1f - (t * t * (3f - 2f * t))
                        } else 1f
                        val force = p.mass * sizeFactor * falloff / (distForce * distForce)
                        val sign = if (p.repel) 1f else -1f
                        c.velX += sign * (dxP / dist) * force * dt
                        c.velY += sign * (dyP / dist) * force * dt
                    }
                }
            }

            // obstacles de construction — la bille rebondit dessus seulement
            // quand constructionVisible = true ; sinon elle les traverse
            if (constructionVisible && obstacles.isNotEmpty()) {
                for (o in obstacles) {
                    when (o) {
                        is Obstacle.Mur -> collidePolyline(c,
                            (0 until o.pts.size - 1).map { i -> o.pts[i] to o.pts[i + 1] })
                        is Obstacle.Bouchon -> {
                            // anneau, pas disque plein : la bille rebondit sur le
                            // bord des deux côtés — dehors elle ne peut pas entrer,
                            // dedans elle reste coincée (symétrique par rapport au
                            // rayon o.r, pas juste repoussée vers l'extérieur)
                            val dx = c.ballX - o.cx
                            val dy = c.ballY - o.cy
                            val d = hypot(dx, dy)
                            if (d > 0.001f) {
                                val nx = dx / d
                                val ny = dy / d
                                val inside = d < o.r
                                val target = if (inside) (o.r - ballRadius).coerceAtLeast(0f) else o.r + ballRadius
                                val penetrating = if (inside) d > target else d < target
                                if (penetrating) {
                                    c.ballX = o.cx + nx * target
                                    c.ballY = o.cy + ny * target
                                    val dot = c.velX * nx + c.velY * ny
                                    // dehors : repousse si elle avance vers le centre (dot<0)
                                    // dedans : repousse si elle avance vers le bord (dot>0)
                                    val approaching = if (inside) dot > 0f else dot < 0f
                                    if (approaching) {
                                        // même correction que collideSegment : seule la
                                        // composante normale est amortie, pas le roulement
                                        val tx = c.velX - dot * nx
                                        val ty = c.velY - dot * ny
                                        c.velX = tx - dot * nx * restitution
                                        c.velY = ty - dot * ny * restitution
                                    }
                                }
                            }
                        }
                        // Passe par collidePolyline (2026-08-10) plutôt que 3
                        // collideSegment directs — récupère la protection anti-
                        // traversée (trajet de la frame, pas juste position
                        // finale, cf. commentaire de collidePolyline) sans dupliquer
                        // la logique. Un seul côté résolu par frame, comme avant.
                        is Obstacle.Triangle -> collidePolyline(c, listOf(
                            (o.x1 to o.y1) to (o.x2 to o.y2),
                            (o.x2 to o.y2) to (o.x3 to o.y3),
                            (o.x3 to o.y3) to (o.x1 to o.y1)
                        ))
                        is Obstacle.Rectangle -> collidePolyline(c, listOf(
                            (o.x1 to o.y1) to (o.x2 to o.y2),
                            (o.x2 to o.y2) to (o.x3 to o.y3),
                            (o.x3 to o.y3) to (o.x4 to o.y4),
                            (o.x4 to o.y4) to (o.x1 to o.y1)
                        ))
                        is Obstacle.Ligne -> collidePolyline(c, ligneSegments(o), if (o.trampoline) TRAMPOLINE_RESTITUTION else null)
                        is Obstacle.Portail -> handlePortail(c, o)
                        is Obstacle.Planete -> {
                            // Noyau solide : la bille rebondit comme sur un Bouchon.
                            // 2026-08-13, bug réel signalé ("une bille qui est passée
                            // à travers une planète") : ce test ne regardait que la
                            // position FINALE de la frame — exactement le bug
                            // tunneling déjà corrigé pour les murs le 2026-08-10 (cf.
                            // collidePolyline ci-dessus), jamais appliqué au noyau des
                            // planètes. À vitesse orbitale élevée, la bille peut
                            // traverser tout le noyau entre 2 frames sans qu'aucune
                            // des deux positions (avant/après) ne soit à moins de
                            // r+ballRadius du centre. Sous-échantillonne le trajet,
                            // même principe que collidePolyline.
                            val travelX = c.ballX - c.prevPosX
                            val travelY = c.ballY - c.prevPosY
                            val travelDist = hypot(travelX, travelY)
                            val subSteps = (travelDist / (ballRadius * 0.5f)).toInt().coerceIn(1, 12)
                            var hitX = Float.NaN
                            var hitY = Float.NaN
                            for (i in 1..subSteps) {
                                val t = i / subSteps.toFloat()
                                val px = c.prevPosX + travelX * t
                                val py = c.prevPosY + travelY * t
                                if (hypot(px - o.cx, py - o.cy) < o.r + ballRadius) {
                                    hitX = px; hitY = py
                                    break
                                }
                            }
                            if (!hitX.isNaN()) {
                                val dxP = hitX - o.cx
                                val dyP = hitY - o.cy
                                val d = hypot(dxP, dyP).coerceAtLeast(0.001f)
                                val nx = dxP / d
                                val ny = dyP / d
                                c.ballX = o.cx + nx * (o.r + ballRadius)
                                c.ballY = o.cy + ny * (o.r + ballRadius)
                                val dot = c.velX * nx + c.velY * ny
                                if (dot < 0) {
                                    c.velX -= 2 * dot * nx * restitution
                                    c.velY -= 2 * dot * ny * restitution
                                }
                            }
                        }
                        is Obstacle.Accelerateur -> {
                            // zone non solide : pas de rebond, juste un boost/frein
                            // continu de la vitesse (direction inchangée) tant que
                            // la bille est dedans
                            val d = hypot(c.ballX - o.cx, c.ballY - o.cy)
                            if (d < o.r) {
                                val speed = hypot(c.velX, c.velY)
                                if (speed > 1f) {
                                    val newSpeed = (speed + o.gauge * ACCELERATEUR_RATE * dt).coerceIn(0f, MAX_SPEED)
                                    val scale = newSpeed / speed
                                    c.velX *= scale
                                    c.velY *= scale
                                }
                            }
                        }
                        is Obstacle.Ellipse -> {
                            // anneau comme Bouchon, en espace ellipse normalisé
                            // (approximation : normale = gradient de l'implicite,
                            // exacte seulement pour rx=ry, suffisante ici)
                            val nex = (c.ballX - o.cx) / o.rx
                            val ney = (c.ballY - o.cy) / o.ry
                            val ed = hypot(nex, ney)
                            if (ed > 0.001f) {
                                val ux = nex / ed
                                val uy = ney / ed
                                val edgeX = o.cx + ux * o.rx
                                val edgeY = o.cy + uy * o.ry
                                var gx = ux / o.rx
                                var gy = uy / o.ry
                                val gl = hypot(gx, gy).coerceAtLeast(0.0001f)
                                gx /= gl; gy /= gl
                                val ddx = c.ballX - edgeX
                                val ddy = c.ballY - edgeY
                                val dEdge = hypot(ddx, ddy)
                                val inside = ed < 1f
                                if (dEdge < ballRadius) {
                                    val sign = if (inside) -1f else 1f
                                    c.ballX = edgeX + gx * ballRadius * sign
                                    c.ballY = edgeY + gy * ballRadius * sign
                                    val dot = c.velX * gx + c.velY * gy
                                    val approaching = if (inside) dot > 0f else dot < 0f
                                    if (approaching) {
                                        val tx = c.velX - dot * gx
                                        val ty = c.velY - dot * gy
                                        c.velX = tx - dot * gx * restitution
                                        c.velY = ty - dot * gy * restitution
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // le doigt agit comme un mur mobile (2026-08-20, rapporté : "la
            // balle passe souvent a travers de mon doigts" — le contact
            // n'était testé qu'aux évènements MOVE du doigt, jamais à
            // chaque frame physique : une bille rapide traverse un doigt
            // IMMOBILE sans qu'aucun MOVE ne se déclenche pour la détecter.
            // Déplacé ici, dans la boucle physique par frame (même principe
            // que les autres obstacles ci-dessus), donc vérifié à chaque
            // tick indépendamment des évènements tactiles.
            if (ballGrabInPinceau && touchWallActive) {
                // 2026-08-21, rapporté : "les coup de pinceau sur la balle
                // magique qui est petite rate souvent la balle, ca passe a
                // travers" — vérifié dans les logs : la bille "magique" a un
                // rayon réglé à ~10dp (~27px à d=2.7), donc reach = 27*1.3 ≈
                // 35px ≈ 4,6mm — bien en dessous de la précision réelle d'un
                // doigt sur écran (Android recommande ≥48dp comme cible
                // tactile minimale). Un plancher absolu garantit une zone de
                // contact utilisable même pour les toutes petites billes,
                // sans changer le comportement des grosses billes (déjà
                // au-dessus du plancher).
                val reachMinPx = 24f * resources.displayMetrics.density
                val reach = (ballRadius * 1.3f).coerceAtLeast(reachMinPx)
                // sous-échantillonne le trajet parcouru CETTE frame
                // (2026-08-20, rapporté "elle reagi souvent pas" avec la
                // bille pingpong, très rapide/rebondissante) — même bug de
                // tunneling déjà corrigé pour Planète/Mur (2026-08-10/13)
                // mais jamais appliqué ici : un seul point testé à la
                // position FINALE de la frame, une bille rapide peut sauter
                // par-dessus le doigt entre 2 frames sans qu'aucun point
                // testé ne tombe dans la zone de contact.
                val travelX = c.ballX - c.prevPosX
                val travelY = c.ballY - c.prevPosY
                val travelDist = hypot(travelX, travelY)
                val subStepsWall = (travelDist / (ballRadius * 0.5f)).toInt().coerceIn(1, 12)
                var hitWallX = Float.NaN
                var hitWallY = Float.NaN
                for (i in 1..subStepsWall) {
                    val t = i / subStepsWall.toFloat()
                    val px = c.prevPosX + travelX * t
                    val py = c.prevPosY + travelY * t
                    if (hypot(px - touchWallX, py - touchWallY) < reach) {
                        hitWallX = px; hitWallY = py
                        break
                    }
                }
                if (!hitWallX.isNaN()) {
                    val dxT = hitWallX - touchWallX
                    val dyT = hitWallY - touchWallY
                    val dT = hypot(dxT, dyT).coerceAtLeast(0.001f)
                    val nx = dxT / dT
                    val ny = dyT / dT
                    c.ballX = touchWallX + nx * reach
                    c.ballY = touchWallY + ny * reach
                    val relVelX = c.velX - touchWallVelX
                    val relVelY = c.velY - touchWallVelY
                    val dot = relVelX * nx + relVelY * ny
                    if (dot < 0f) {
                        val tvx = relVelX - dot * nx
                        val tvy = relVelY - dot * ny
                        c.velX = tvx - dot * nx * restitution + touchWallVelX
                        c.velY = tvy - dot * ny * restitution + touchWallVelY
                    } else {
                        c.velX = touchWallVelX
                        c.velY = touchWallVelY
                    }
                }
            }

            // Rafraîchit la photo d'échantillonnage toutes les
            // SAMPLE_SNAPSHOT_MS (2026-08-10, PAS liée aux transitions de
            // contact — cf. commentaire de `sampleSnapshot` : une version
            // "figée au début du contact" s'est révélée pire à l'usage, la
            // bille devenant aveugle à toute nouvelle couleur dès que le
            // contact ne se coupait jamais, ce qui arrive presque toujours
            // en roulant en continu). Immuable, jamais utilisée comme
            // cible d'un Canvas, donc pas de risque du crash déjà rencontré
            // côté Carnet sur ce point précis.
            if (c.wetLayer != null && nowMs - c.sampleSnapshotAt >= SAMPLE_SNAPSHOT_MS) {
                c.sampleSnapshot = c.wetLayer!!.copy(Bitmap.Config.ARGB_8888, false)
                c.sampleSnapshotAt = nowMs
            }
            // contact bille ↔ SON calque (la couche contient tout, taches comprises) :
            // la bille ne prend que les couleurs qui diffèrent de ce qu'elle porte,
            // mais le contact « je suis sur de la peinture » (pour le minuteur de
            // sillage) ne dépend pas de cette distinctivité — cf. SampleResult
            val result = c.wetLayer?.let { samplePaintColor(it, c, nowMs) }
            val sampled = result?.distinctColor
            c.lastSample = sampled
            val nowInContact = result?.touching == true
            // portée AVANT ce frame — capturée ici (pas seulement dans le bloc
            // sampled!=null ci-dessous) pour rester disponible au reset de
            // CONTACT+ plus bas, qui doit savoir si la bille portait déjà une
            // couleur avant CE contact précis, que ce frame ait rafraîchi
            // l'échantillon ou non.
            val before = c.carriedColor
            if (sampled != null) {
                // Modes expérimentaux 1 (protection) et 3 (retour ralenti) : dès
                // que l'échantillon change de zone de façon significative, la
                // couleur PRÉCÉDEMMENT portée devient "à éviter/ralentir" pour
                // une fenêtre courte — sans ça la bille retombe instantanément
                // dans la couleur dominante dès qu'elle la retouche (même taux
                // de fondu dans les deux sens, cf. commentaire de melangeExperiment).
                if (melangeExperiment == 1 || melangeExperiment == 3) {
                    val prevZone = c.prevZoneColor
                    if (before != null && (prevZone == null || colorDist(sampled, prevZone) > COLOR_EPS)) {
                        c.avoidColor = before
                        c.avoidUntil = nowMs + MELANGE_PROTECTION_MS
                        UsageLog.d("AVOID+ calque=${ci + 1} avoid=${fmt(before)} nouvelle_zone=${fmt(sampled)} pendant=${MELANGE_PROTECTION_MS}ms")
                    }
                    c.prevZoneColor = sampled
                }
                var tMixEffective = tMix
                val avoidColorNow = c.avoidColor
                val avoidActiveNow = (melangeExperiment == 1 || melangeExperiment == 3) &&
                    avoidColorNow != null && nowMs < c.avoidUntil && colorDist(sampled, avoidColorNow) <= COLOR_EPS
                if (melangeExperiment == 3 && avoidActiveNow) {
                    tMixEffective = tMix * MELANGE_RETOUR_LENT_FACTOR
                }
                if (avoidActiveNow) {
                    // Mode 1 : ce message prouve que samplePaintColor() a bien filtré
                    // avoidColor (sinon "sampled" collerait à avoidColor, cf. plus haut) —
                    // mode 3 : prouve que tMixEffective est bien réduit ce frame-ci.
                    UsageLog.d("AVOID actif calque=${ci + 1} evite=${fmt(avoidColorNow)} sample=${fmt(sampled)} tMix=${"%.3f".format(tMix)}→${"%.3f".format(tMixEffective)}")
                }
                // Fondu pigmentaire continu : la couleur portée évolue vers celle de
                // la zone pendant tout le contact (pas un saut ponctuel) — le résultat
                // reste un vrai mélange de pigments (jaune+bleu → vert, cf. PigmentMix).
                c.carriedColor = if (before == null) sampled
                else lerpColor(before, sampled, tMixEffective)
                if (c.carriedColor != before) logCarried(before, c.carriedColor)
            }
            if (nowInContact && !c.wasInContact) {
                // BUG TROUVÉ (2026-08-31, "c'était la balle normale, le trait
                // noir" — pointillés confirmés indépendants de la bille testée,
                // cf. log réel : rafales de dizaines de CONTACT+/CONTACT-
                // en moins de 150ms) : `nowInContact` = la bille touche de la
                // PEINTURE EXISTANTE (samplePaintColor), pas le doigt qui
                // touche l'écran — un nouveau geste utilisateur n'a RIEN à
                // voir avec ce signal. Ce reset datait d'avant cometTrail/
                // rainbowMode/speedColorMode (quand peindre exigeait d'être
                // en contact), donc "nouveau contact = nouveau trait" avait
                // un sens. Mais avec un de ces 3 modes actif, la bille peint
                // EN CONTINU quel que soit `touching` — en roulant vite, la
                // bille quitte/retouche sa PROPRE traînée toute fraîche des
                // dizaines de fois par seconde (bruit de détection, pas un
                // vrai nouveau trait), et chaque reset coupait la continuité
                // de `lastPaintX/Y` : le trait suivant repartait de zéro à
                // cet endroit précis au lieu de se relier au précédent — un
                // vrai trou à chaque fois, illustré par ma capture "pointillés
                // en mode saucisson"/tirets nets.
                // SUITE (2026-08-31, "regarde l'ancien code avant le
                // changement des couleurs" — Wian a eu raison de douter :
                // le correctif ci-dessus ne couvrait QUE rainbowMode/
                // speedColorMode/cometTrail, alors que la vraie condition
                // n'a jamais été liée à ces 3 modes précis. Depuis le
                // 19/07 ("encre illimitée tant qu'elle porte une couleur"),
                // peindre ne dépend QUE de `carriedColor != null` (ou de
                // ces 3 modes) — jamais de `touching` en tant que tel, pour
                // AUCUNE bille. "Rebondissante" (aucun des 3 modes) gardait
                // donc le VIEUX bug intact : en rebondissant vite elle
                // retouche sa PROPRE traînée (déjà porteuse d'une couleur)
                // des dizaines de fois par seconde, et chaque reset
                // fragmentait le trait en petits segments à bouts ronds —
                // le "boudin" est la MÊME cause que les pointillés,
                // seulement plus dense (fragments collés plutôt qu'espacés).
                // Vraie condition : ne réinitialiser QUE si la bille ne
                // portait PAS encore de couleur avant ce contact (`before
                // == null`, cf. plus haut) — un vrai premier contact, pas
                // un re-contact sur un trait déjà en cours.
                if (!(rainbowMode || speedColorMode || cometTrail) && before == null) {
                    c.smoothedTrailWidthBall = -1f
                    c.lastPaintX = Float.NaN
                    c.lastPaintY = Float.NaN
                    // nouveau contact = nouveau trait, pas de raison de fondre sa
                    // toute première couleur vers celle d'un trait précédent sans
                    // rapport (cf. trailEdgeMode==2, stampBallTrail).
                    c.smoothedStampColor = null
                }
                UsageLog.d("CONTACT+ calque=${ci + 1} sample=${fmt(sampled)} carried=${fmt(c.carriedColor)}")
            } else if (!nowInContact && c.wasInContact) {
                UsageLog.d("CONTACT- calque=${ci + 1} carried=${fmt(c.carriedColor)} ink=${c.inkReserve.toInt()}")
            }
            // rouler sur de la PEINTURE recharge la réserve (même sa propre
            // couleur) — la bille dessine en continu sur une zone peinte, et
            // s'épuise seulement quand elle roule sur le vide. Recharge
            // progressive (jauge visible) ou instantanée selon le réglage.
            if (nowInContact) {
                c.inkReserve = if (rechargeProgressive) {
                    (c.inkReserve + RECHARGE_RATE * dt).coerceAtMost(INK_AMOUNT)
                } else INK_AMOUNT
            }
            c.wasInContact = nowInContact

            // heartbeat : état complet 2×/s — pour corréler avec ce que l'utilisateur voit
            frameCount++
            if (frameCount % 30 == 0) {
                UsageLog.d(
                    "hb calque=${ci + 1} pos=(${c.ballX.toInt()},${c.ballY.toInt()}) speed=${hypot(c.velX, c.velY).toInt()}" +
                        " sample=${fmt(c.lastSample)} carried=${fmt(c.carriedColor)}" +
                        " ink=${c.inkReserve.toInt()} contact=$nowInContact spots=${c.spotCount} opa=${c.opacity}"
                )
            }

            // peinture sur SON calque : couleur portée (contact + sillage) ou
            // couleur de base — la bille laisse une trace dès qu'elle roule
            val pdx = c.ballX - c.prevPosX
            val pdy = c.ballY - c.prevPosY
            val pdist = hypot(pdx, pdy)
            // Option C : la bille ne peint QUE quand elle porte une couleur
            // (contact + sillage) — pas de trace de base en roulant « à vide ».
            // 2026-08-19, demande explicite ("encre illimitée tant qu'elle
            // porte une couleur") : la réserve (inkReserve) ne bloque plus le
            // tracé — à vitesse max (6000px/s) elle vidait 3000px en 0.5s et,
            // si la trajectoire ne recroisait pas de peinture existante entre
            // temps, le trait restait coupé indéfiniment (cf. captures
            // d'écran, segments isolés à bouts arrondis). inkReserve continue
            // d'exister pour la jauge visuelle (recharge au contact), mais
            // ne conditionne plus le tracé.
            // BUG TROUVÉ (2026-08-25, rapporté : "quand je prends la bille
            // arc-en-ciel, sur écran vide, sans peindre, elle ne commence
            // pas seule à écrire... vu que c'est elle qui génère la couleur")
            // — ce garde-fou ignorait totalement rainbowMode/speedColorMode :
            // paintColor() résout pourtant déjà une vraie couleur dans ces
            // 2 cas (indépendante de carriedColor, cf. plus bas), mais le
            // tracé ne démarrait jamais faute de contact préalable avec de
            // la peinture existante. Même chose pour cometTrail (rapporté
            // "ne tient pas compte des couleurs déjà présentes" — l'effet
            // comète perd tout son sens s'il faut d'abord toucher une
            // couleur existante pour laisser SA traînée) : ces 3 réglages
            // portent chacun leur propre raison de peindre dès le départ,
            // sans dépendre d'un contact préalable.
            if (c.carriedColor != null || rainbowMode || speedColorMode || cometTrail) {
                if (pdist > MIN_MOVE) {
                    inkPhase += pdist * TEXTURE_SCALE
                    // tracé = taille de la bille − un chouïa ; cometTrail/vitesseEpaisseur
                    // rétrécissent le trait à vitesse — cf. cibleTrailWidth().
                    // BUG TROUVÉ (2026-08-31, "j'ai jeté la balle et ça fait un
                    // truc étrange" — confirmé par Wian : transition épais→fin
                    // →bourgeon juste après un lancer, pas un rétrécissement
                    // propre) : lisser la vitesse (smoothedWidthSpeed) PUIS
                    // lisser À NOUVEAU la largeur qui en résulte (ligne
                    // retirée ci-dessous) empilait 2 étages de lissage l'un
                    // sur l'autre — un saut de vitesse légitime (lancer) se
                    // retrouvait doublement retardé, produisant une courbe de
                    // transition non monotone plutôt qu'un simple délai. Même
                    // principe que speedColor() (cf. smoothedColorSpeed) : UN
                    // SEUL étage de lissage, sur la vitesse, avant la courbe
                    // non linéaire — jamais un 2e sur le résultat.
                    c.smoothedTrailWidthBall = cibleTrailWidth(smoothedWidthSpeed(c, speed))
                    stampBallTrail(c, c.ballX, c.ballY)
                    c.inkReserve -= pdist // la goutte s'étale… jusqu'à épuisement
                }
            }
            // Reset "encre épuisée → couleur de base" retiré (2026-08-19,
            // même demande ci-dessus) : la couleur portée ne se perd plus
            // quand inkReserve touche 0, cohérent avec le tracé qui ne
            // s'arrête plus non plus dans ce cas.
            c.prevPosX = c.ballX
            c.prevPosY = c.ballY
        }
    }

    /** Couleur de peinture effective : portée (mélange pigmentaire au
     *  contact), sinon la couleur sélectionnée EN DIRECT — jamais figée à
     *  `c.baseColor` (2026-09-04, régression trouvée : "juste avant je
     *  pouvais dessiner et changer la couleur en meme temps... ca permetais
     *  de faire des tracés arc en ciel" — vérifié par test réel : un flick
     *  de bille suivi d'un changement de couleur sur le mélangeur PENDANT
     *  qu'elle roulait encore ne changeait pas le tracé, resté figé à la
     *  couleur de départ. Même principe déjà appliqué au pinceau, cf.
     *  `pinceauMixedColor()` ci-dessus : "couleur du mélangeur EN DIRECT,
     *  jamais figée ni mélangée" quand rien à mélanger). `c.baseColor`
     *  reste la couleur de départ de la bille (icône, jauge d'encre) —
     *  seul le TRACÉ suit désormais `selectedColor` en direct. */
    private fun paintColor(c: Calque): Int = inkColor(when {
        speedColorMode -> speedColor(smoothedColorSpeed(c, hypot(c.velX, c.velY)))
        rainbowMode -> rainbowColor()
        else -> c.carriedColor ?: selectedColor
    })

    private fun inkColor(c: Int): Int = (c and 0x00FFFFFF) or (INK_ALPHA shl 24)

    /** Peint le trait de la bille par TAMPONS (cercles pleins) régulièrement
     *  espacés le long du trajet, plutôt qu'un segment de ligne à bouts
     *  ronds par frame — technique standard des moteurs de pinceau
     *  (Krita/Clip Studio/Photoshop : réglage "spacing" entre tampons,
     *  cf. recherche externe du 2026-08-30, demande explicite : "on
     *  simplifierait pas le système ?"). Remplace d'un coup les 2 rustines
     *  empilées précédentes (dégradé par LinearGradient, accumulation de
     *  segment) :
     *  - un cercle n'a pas de direction → aucune couture/bosse possible
     *    dans un virage serré, plus besoin de joindre 2 segments par un
     *    Path à 3 points ;
     *  - chaque tampon prend sa propre couleur courante indépendamment des
     *    autres (même principe que le dégradé de teinte d'un vrai moteur de
     *    pinceau) → dégradé "gratuit" par simple chevauchement dense, plus
     *    besoin de shader.
     *  [x,y] = point d'arrivée du déplacement de cette frame ; avance par
     *  pas de `TRAIL_STAMP_SPACING_RATIO × largeur du trait` depuis
     *  `c.lastPaintX/Y` (dernier tampon posé) jusqu'à [x,y], plafonné à
     *  MAX_STAMPS_PER_FRAME (protège des micro-trous à vitesse extrême sans
     *  faire ramer l'app). `c.smoothedTrailWidthBall` doit déjà être réglé
     *  par l'appelant. */
    private fun stampBallTrail(c: Calque, x: Float, y: Float) {
        if (c.lastPaintX.isNaN()) { c.lastPaintX = x; c.lastPaintY = y }
        var remaining = hypot(x - c.lastPaintX, y - c.lastPaintY)
        val spacing = (c.smoothedTrailWidthBall * TRAIL_STAMP_SPACING_RATIO).coerceAtLeast(MIN_MOVE)
        // 2026-08-31, instrumentation temporaire ("c'est la vitesse de la
        // bille" — vrais pointillés/tirets réguliers observés sur capture
        // réelle, pas juste des bosses) : capturer chaque appel pour voir
        // sur pièce ce qui se passe au moment du trou, plutôt qu'une 3e
        // théorie non vérifiée. À retirer une fois la cause confirmée.
        if (STAMP_DEBUG_LOG) {
            // 2026-08-31, suite : "boudin" confirmé PAS dû à la largeur (fixe,
            // vérifié) ni au plafond ni à un mur — position x,y ajoutée pour
            // voir directement si le TRAJET peint zigzague réellement (vrai
            // rebond fin, pas un bug de rendu) ou si les points sont
            // parfaitement alignés (bug de rendu/composition, pas la bille).
            UsageLog.d("stamp x=${x.toInt()} y=${y.toInt()} last=(${c.lastPaintX.toInt()},${c.lastPaintY.toInt()}) remaining=${remaining.toInt()} spacing=${spacing.toInt()} widthR=${(c.smoothedTrailWidthBall / 2f).toInt()} skip=${remaining < spacing}")
        }
        if (remaining < spacing) return
        val dirX = (x - c.lastPaintX) / remaining
        val dirY = (y - c.lastPaintY) / remaining
        val color = paintColor(c)
        val r = c.smoothedTrailWidthBall / 2f
        // 2026-08-30, demande explicite : "le bord doux... pourrait changer
        // la donne car c'est moche, tous les demi-cercles en transition de
        // couleur" — les tampons à bord DUR (couleur plate jusqu'au bord)
        // montrent un arc visible partout où 2 tampons voisins ont une
        // couleur différente, même très rapprochés. Bord doux (mode 1) =
        // dégradé radial opaque au centre → transparent au bord
        // (RadialGradient, même principe de cache/repositionnement que
        // volumeGradient ci-dessus) : les transitions de couleur se fondent,
        // mais le contour EXTÉRIEUR du trait entier en hérite aussi (l'alpha
        // tombe même sur les tampons de bord, pas seulement aux jonctions
        // internes) — effet de bord signalé le 30/08.
        // Mode 2 ("net + doux", 2026-08-31) — 2 tentatives précédentes
        // écartées par retour réel négatif (captures à l'appui, cf. Git) :
        // un dégradé RADIAL à l'intérieur d'un tampon (couleur du tampon →
        // couleur du tampon précédent) crée TOUJOURS son propre motif en
        // rosace/bosse, qu'il soit répété à chaque tampon (1er bug) ou limité
        // au 1er tampon de chaque appel (2e bug — mais à vitesse normale, un
        // seul tampon par frame = "1er tampon" presque à chaque fois, donc le
        // motif restait visible). Un dégradé RADIAL n'est simplement pas la
        // bonne technique : la couleur d'un vrai pinceau doux ne varie pas
        // À L'INTÉRIEUR d'un coup de pinceau ponctuel, elle varie
        // PROGRESSIVEMENT D'UN COUP AU SUIVANT. Remplacé par un lissage
        // exponentiel (EMA, cf. STAMP_COLOR_SMOOTH_FACTOR) de la couleur du
        // tampon lui-même : chaque tampon reste PLAT (comme le mode dur, donc
        // aucun motif interne, contour extérieur net), mais sa couleur
        // rattrape `color` progressivement sur plusieurs tampons au lieu d'y
        // sauter instantanément — la fonte se voit dans la SUCCESSION des
        // tampons, jamais dans un seul.
        if (trailEdgeMode == 1) {
            if (stampGradient == null || color != stampGradientColor || r != stampGradientRadius) {
                stampGradient = RadialGradient(0f, 0f, r.coerceAtLeast(0.01f), color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
                stampGradientColor = color
                stampGradientRadius = r
            }
            stampPaint.shader = stampGradient
        } else {
            stampPaint.shader = null
            if (trailEdgeMode != 2) stampPaint.color = color // mode 2 : couleur posée par tampon, dans la boucle
        }
        var n = 0
        while (remaining >= spacing && n < MAX_STAMPS_PER_FRAME) {
            c.lastPaintX += dirX * spacing
            c.lastPaintY += dirY * spacing
            if (trailEdgeMode == 1) {
                stampGradientMatrix.setTranslate(c.lastPaintX, c.lastPaintY)
                stampGradient!!.setLocalMatrix(stampGradientMatrix)
            } else if (trailEdgeMode == 2) {
                val prev = c.smoothedStampColor ?: color
                val blended = lerpColor(prev, color, STAMP_COLOR_SMOOTH_FACTOR)
                c.smoothedStampColor = blended
                stampPaint.color = blended
            }
            c.paintCanvas.drawCircle(c.lastPaintX, c.lastPaintY, r, stampPaint)
            c.wetCanvas.drawCircle(c.lastPaintX, c.lastPaintY, r, stampPaint)
            remaining -= spacing
            n++
        }
        // BUG TROUVÉ (2026-08-31, "quand la bille va vite j'ai de la
        // discontinuité, ça fait des pointillés") : ce snap forçait
        // `lastPaintX/Y` sur [x,y] dès que le plafond de 60 tampons était
        // atteint DANS UN SEUL appel — la distance restante (`remaining`,
        // potentiellement des centaines de px pour une bille au trait
        // resserré par la vitesse, cf. cometTrail) n'était alors JAMAIS
        // peinte : un vrai trou permanent, pas juste un ralentissement.
        // Le commentaire d'origine ("protège des micro-trous") décrivait
        // l'intention inverse de ce que le code faisait réellement.
        // Corrigé en ne touchant plus lastPaintX/Y ici : la distance non
        // peinte reste due, l'appel suivant (frame suivante) reprend
        // exactement où le tampon précédent s'est arrêté et continue de
        // peindre — le plafond limite toujours le coût par frame (jamais
        // plus de MAX_STAMPS_PER_FRAME tampons d'un coup), mais ne fait
        // plus jamais disparaître de trajet.
        // 2026-08-31, suite : "encore des pointillés, en mode saucisson" —
        // le correctif ci-dessus n'a pas suffi (ou révèle un 2e mécanisme).
        // Hypothèse non confirmée : le rattrapage de retard peut étaler un
        // même lot de tampons sur plusieurs frames, chacune avec son propre
        // `smoothedTrailWidthBall` (lissé à 12%/frame) — si la vitesse (donc
        // la largeur cible, cf. cibleTrailWidth) varie vite pendant ce
        // rattrapage, des lots de largeurs différentes se suivent =
        // silhouette en bosses/étranglements ("saucisson"). Instrumenté
        // plutôt que re-deviné (règle : capturer les traces avant théorie) —
        // log seulement quand le plafond est atteint, pour ne pas noyer le
        // fichier en usage normal.
        if (n >= MAX_STAMPS_PER_FRAME) {
            UsageLog.d("stampBallTrail plafond atteint: reste=${remaining.toInt()}px largeur=${r.toInt()}px")
        }
    }

    /** Mélange pigmentaire réaliste — portage de Spectral.js (MIT), cf. PigmentMix.kt.
     *  Kubelka-Munk : bleu+jaune → vert, jaune+rouge → orange, rouge+bleu → violet sombre. */
    private fun lerpColor(a: Int, b: Int, t: Float): Int = PigmentMix.mix(a, b, t.toDouble(), mixVividMode)

    /** Distance perceptive simple entre deux couleurs (somme des ΔRGB). */
    private fun colorDist(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))

    /** Largeur texturée : variation lisse pseudo-aléatoire autour de la largeur de
     *  base — réduite par textureAmount (0 = net, aucun ondulation). */
    private fun textureWidth(base: Float): Float {
        if (textureAmount <= 0.01f) return base
        val wobble = 0.5f + 0.5f * sin(inkPhase * 0.9f) * sin(inkPhase * 2.3f + 1.7f)
        val amp = 0.28f * textureAmount
        return (base * (1f - amp + 2f * amp * wobble)).coerceAtLeast(base * (1f - amp))
    }

    private fun fmt(c: Int?): String = if (c == null) "none" else "#%06X".format(c and 0xFFFFFF)

    private fun logCarried(before: Int?, after: Int?) {
        UsageLog.d("carried ${fmt(before)} → ${fmt(after)}")
    }

    /**
     * Échantillonne la couche HUMIDE du calque (peinture fraîche — les traits
     * séchés ne rechargeant plus la bille) sur TOUT le disque de la bille
     * (grille dense) : une ligne fine sous la bille est détectée même
     * décalée du centre. Ne renvoie que des couleurs qui DIFFÈRENT de ce que
     * la bille porte déjà — sinon elle « toucherait » sa propre traînée en
     * permanence. Les pixels trop transparents (bords anti-aliasés des
     * traits, alpha ≤ 140) sont ignorés : ce sont eux qui créaient le
     * rechargement en boucle sur sa propre trace. null = la bille ne roule
     * que sur sa propre couleur (ou du vide).
     */
    /** @param touching y a-t-il de la peinture (opaque) sous la bille, peu
     *  importe sa couleur — pilote le minuteur de sillage.
     *  @param distinctColor la couleur la plus proche du centre qui DIFFÈRE
     *  de ce que la bille porte déjà (> COLOR_EPS) — pilote le mélange. Les
     *  deux étaient confondus dans un seul retour avant : la bille perdait le
     *  signal « contact » dès que sa couleur portée convergeait vers celle du
     *  dessous (EPS franchi), alors qu'elle roulait toujours dessus — le
     *  minuteur se rechargeait en boucle sur la texture (ré-détections toutes
     *  les ~80ms observées en usage réel) et ne retombait jamais à zéro. */
    private class SampleResult(val touching: Boolean, val distinctColor: Int?)

    private fun samplePaintColor(layer: Bitmap, c: Calque, nowMs: Long): SampleResult {
        val cx = c.ballX.toInt()
        val cy = c.ballY.toInt()
        val w = layer.width - 1
        val h = layer.height - 1
        // Source de la couleur "distincte" : la photo figée en début de CE
        // contact si elle existe, jamais la couche en direct (cf.
        // commentaire de `sampleSnapshot`) — `touching`, lui, reste basé sur
        // la couche en direct (pas de bug là-dessus, la bille doit bien
        // sentir sa propre trace en direct).
        val snap = c.sampleSnapshot
        val sw = (snap?.width ?: layer.width) - 1
        val sh = (snap?.height ?: layer.height) - 1
        val r = ballRadius.toInt()
        // 2026-09-04 : essayé un temps aligné sur paintColor() (`carriedColor
        // ?: selectedColor`) pour empêcher un faux "contact" dès qu'on
        // change le mélangeur sans rien peindre dessous — mais ça cassait un
        // AUTRE comportement, plus ancien et voulu : toucher une zone qu'on
        // vient de peindre AU DOIGT (même couleur SÉLECTIONNÉE, mais
        // DIFFÉRENTE de la couleur D'ORIGINE de la bille) ne se voyait plus
        // jamais "distinct" puisque ref == la couleur qu'on vient tout juste
        // de peindre avec cette même sélection. Retour direct : "je trace
        // une couleur avec le doigt et la balle ne prend pas la couleur" —
        // confirmé : "une [zone] que je peint avec le doigt" est bien ce
        // qu'elle doit absorber. Revenu à `c.baseColor` (couleur D'ORIGINE
        // de LA BILLE, jamais modifiée après création) : tout ce qui n'est
        // pas cette couleur d'origine reste détectable comme distinct, peu
        // importe la sélection actuelle — restaure "elle absorbe ce
        // qu'elle touche" sans revenir au bug initial (paintColor(), lui,
        // continue de lire `selectedColor` en direct, inchangé).
        val ref = c.carriedColor ?: c.baseColor
        val r2 = r * r
        // Mode expérimental 2 (rayon resserré) : ne réduit QUE le rayon pris
        // en compte pour le mélange de couleur — le contact/sillage (touching,
        // plus bas) garde le rayon complet, sans quoi la recharge d'encre et
        // le minuteur de sillage changeraient aussi (pas le but de ce mode).
        val sampleR = if (melangeExperiment == 2) (r * MELANGE_RAYON_FACTOR).toInt().coerceAtLeast(1) else r
        val sampleR2 = sampleR * sampleR
        val step = max(3, (r / 5).toInt())
        var touching = false
        // moyenne pondérée des pixels distincts (poids 1/(d+1), d = distance au
        // centre) : la bille mélange vers la couleur MOYENNE de la zone sous elle
        // au lieu d'un pixel isolé → évolution stable et lissée, pas de sautillement.
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumW = 0.0
        var bestDist = Int.MAX_VALUE
        var bestColor: Int? = null
        // Mode expérimental 1 (protection anti-retour) : pendant la fenêtre,
        // les pixels proches de la couleur qu'on vient de quitter sont
        // ignorés — comme s'ils ne différaient pas de ce qui est déjà porté.
        val avoidColor = c.avoidColor
        val avoiding = melangeExperiment == 1 && avoidColor != null && nowMs < c.avoidUntil
        var y = cy - r
        while (y <= cy + r) {
            var x = cx - r
            while (x <= cx + r) {
                val dx = x - cx
                val dy = y - cy
                val d = dx * dx + dy * dy
                if (d <= r2) {
                    if (Color.alpha(layer.getPixel(x.coerceIn(0, w), y.coerceIn(0, h))) > 140) touching = true
                }
                if (d <= sampleR2) {
                    val src = snap ?: layer
                    val px = src.getPixel(x.coerceIn(0, sw), y.coerceIn(0, sh))
                    if (Color.alpha(px) > 140 && colorDist(px, ref) > COLOR_EPS &&
                        !(avoiding && colorDist(px, avoidColor!!) <= COLOR_EPS)
                    ) {
                        val wgt = 1.0 / (d + 1.0)
                        sumR += Color.red(px) * wgt
                        sumG += Color.green(px) * wgt
                        sumB += Color.blue(px) * wgt
                        sumW += wgt
                        if (d < bestDist) {
                            bestDist = d
                            bestColor = px
                        }
                    }
                }
                x += step
            }
            y += step
        }
        val avg = if (sumW > 0.0) Color.rgb(
            (sumR / sumW).toInt().coerceIn(0, 255),
            (sumG / sumW).toInt().coerceIn(0, 255),
            (sumB / sumW).toInt().coerceIn(0, 255)
        ) else bestColor
        return SampleResult(touching, avg)
    }

    // ---------------------------------------------------------------- gestes

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Décidé AVANT scaleDetector.onTouchEvent(event) juste en dessous :
        // le détecteur peut déclencher onScaleBegin dès CET événement — trop
        // tard pour lui faire voir ignorePinchGesture à jour si on le
        // calcule après (2026-08-11, bug réel trouvé lors de ce fix : la
        // marge de bord de v178 ne s'appliquait en pratique qu'À PARTIR du
        // prochain événement, jamais celui qui déclenche onScaleBegin).
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Rejet de paume : un 2e doigt qui touche en bord d'écran est
            // probablement un grip accidentel, pas un vrai pincement.
            // Standard des apps de dessin pro (Procreate, Sketchbook…).
            val px = event.getX(event.actionIndex)
            val py = event.getY(event.actionIndex)
            ignorePinchGesture = zoomLocked || px < edgeMarginPx || px > width - edgeMarginPx || py < edgeMarginPx || py > height - edgeMarginPx
            if (!ignorePinchGesture) {
                // camMode activé AVANT scaleDetector pour que onScaleBegin/
                // onScale le voient dès cet événement (ils tournent avant le
                // when{}). lastFocus initialisé ici aussi pour le pan.
                camMode = true
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
            }
        }
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // un menu est ouvert : un clic hors du menu le ferme (on ne dessine pas)
                if (openDirection != 0) {
                    UsageLog.d("down: ferme menu (openDir=$openDirection)")
                    openDirection = 0
                    onSwipeOpen?.invoke(0)
                    ignoreTouches = true // tout le geste est ignoré (jusqu'au UP)
                    return true
                }
                ignoreTouches = false
                if (event.pointerCount >= 2 && !zoomLocked) {
                    UsageLog.d("down: 2 doigts → caméra")
                    camMode = true
                    lastFocusX = focusX(event)
                    lastFocusY = focusY(event)
                    return true
                }
                camMode = false
                // outil sélection : toucher un obstacle → le sélectionner,
                // ou toucher le vide → désélectionner
                if (outilSelection) {
                    val wx = toWorldX(event.x)
                    val wy = toWorldY(event.y)
                    // seul l'outil flèche peut attraper la bille — ainsi on ne
                    // risque jamais de dessiner par erreur en visant la bille
                    // en mode pinceau (elle est prioritaire sur la sélection
                    // d'obstacle si le doigt touche pile la bille)
                    dragIndex = hitBall(wx, wy)
                    if (dragIndex >= 0) {
                        downX = event.x
                        downY = event.y
                        downTimeMs = SystemClock.uptimeMillis()
                        moved = false
                        velocityTracker = VelocityTracker.obtain()
                        velocityTracker?.addMovement(event)
                        throwSamples.clear()
                        throwSamples.add(Triple(downTimeMs, event.x, event.y))
                        pushUndo() // snapshot avant le lancer (cf. undo bille)
                        UsageLog.d("down (flèche): bille touchée calque=${dragIndex + 1}")
                        return true
                    }
                    // obstacle déjà sélectionné : tap sur la petite croix →
                    // supprime cet obstacle précis (2026-08-11, demande
                    // explicite — remplace l'ancienne suppression par tap de
                    // la gomme, retirée). Vérifié AVANT les poignées d'édition
                    // (même priorité, la croix est un point à part).
                    if (selectedObstacleIndex >= 0 && selectedObstacleIndex < obstacles.size) {
                        val sel = obstacles[selectedObstacleIndex]
                        val (dx, dy) = obstacleDeleteMarkerPos(sel)
                        if (hypot(wx - dx, wy - dy) < DELETE_MARKER_RADIUS * 1.3f / camScale) {
                            pushUndoObstacles()
                            obstacles.removeAt(selectedObstacleIndex)
                            selectedObstacleIndex = -1
                            UsageLog.d("obstacle supprimé (croix de sélection)")
                            invalidate()
                            return true
                        }
                    }
                    // obstacle déjà sélectionné : tap sur sa poignée d'édition
                    // → édite (sans re-sélectionner) — Ligne : 2 extrémités
                    // (0/1) + centre (2, courbe la ligne) ; Triangle/Mur :
                    // poignée unique (pointe / 1er point) tourne+redimensionne
                    // autour du centre ; Bouchon : poignée unique redimensionne
                    if (selectedObstacleIndex >= 0 && selectedObstacleIndex < obstacles.size) {
                        val sel = obstacles[selectedObstacleIndex]
                        val handleSeuil = HANDLE_RADIUS * 1.3f / camScale
                        var hit = -1
                        when (sel) {
                            is Obstacle.Ligne -> {
                                val d1 = hypot(wx - sel.x1, wy - sel.y1)
                                val d2 = hypot(wx - sel.x2, wy - sel.y2)
                                val d3 = hypot(wx - sel.cx, wy - sel.cy)
                                val best = minOf(d1, d2, d3)
                                if (best < handleSeuil) hit = if (d1 == best) 0 else if (d2 == best) 1 else 2
                            }
                            is Obstacle.Triangle -> {
                                if (hypot(wx - sel.x1, wy - sel.y1) < handleSeuil) hit = 0
                                else {
                                    val mx = (sel.x2 + sel.x3) / 2f
                                    val my = (sel.y2 + sel.y3) / 2f
                                    if (hypot(wx - mx, wy - my) < handleSeuil) hit = 1
                                }
                            }
                            is Obstacle.Rectangle -> {
                                // poignée 0 (sommet 1) : tourne+redimensionne comme avant.
                                // Sinon, un côté (segment) sous le doigt (2026-08-12, demande
                                // explicite : "sélectionner le segment du rectangle pour
                                // élargir/allonger en tirant dessus, la forme reste fermée")
                                // → 10..13, déplace SEULEMENT les 2 sommets de ce côté,
                                // les 2 autres restent fixes (toujours un quadrilatère fermé).
                                if (hypot(wx - sel.x1, wy - sel.y1) < handleSeuil) hit = 0
                                else {
                                    val edges = listOf(
                                        distPointSegment(wx, wy, sel.x1, sel.y1, sel.x2, sel.y2),
                                        distPointSegment(wx, wy, sel.x2, sel.y2, sel.x3, sel.y3),
                                        distPointSegment(wx, wy, sel.x3, sel.y3, sel.x4, sel.y4),
                                        distPointSegment(wx, wy, sel.x4, sel.y4, sel.x1, sel.y1)
                                    )
                                    val bestI = edges.indices.minByOrNull { edges[it] } ?: -1
                                    if (bestI >= 0 && edges[bestI] < handleSeuil) {
                                        hit = 10 + bestI
                                        edgeGrabWx = wx; edgeGrabWy = wy
                                    }
                                }
                            }
                            is Obstacle.Bouchon -> {
                                if (hypot(wx - (sel.cx + sel.r), wy - sel.cy) < handleSeuil) hit = 0
                            }
                            is Obstacle.Planete -> {
                                // 0 = taille du noyau (bord droit), 1 = zone d'influence
                                // — gravité fixée à la création depuis planeteMassDefault/
                                // planeteInverseMassDefault, pas de poignée pour ça (2026-08-29).
                                // La poignée d'influence s'attrape n'importe où sur le
                                // POURTOUR du halo (pas juste le marqueur visuel projeté
                                // vers l'écran) — "poignée qui suit le doigt sur la zone
                                // d'effet", pour rester utilisable même si le marqueur
                                // lui-même est proche du bord de l'écran.
                                if (hypot(wx - (sel.cx + sel.r), wy - sel.cy) < handleSeuil) hit = 0
                                else if (abs(hypot(wx - sel.cx, wy - sel.cy) - sel.influenceRadius) < handleSeuil) hit = 1
                            }
                            is Obstacle.Mur -> {
                                if (sel.pts.isNotEmpty() && hypot(wx - sel.pts[0].first, wy - sel.pts[0].second) < handleSeuil) hit = 0
                            }
                            is Obstacle.Portail -> {
                                // "poignée" = le disque lui-même (déjà grand) :
                                // retoucher un disque précis (déjà sélectionné)
                                // le déplace seul, sans l'autre
                                val d1 = hypot(wx - sel.x1, wy - sel.y1)
                                val d2 = hypot(wx - sel.x2, wy - sel.y2)
                                if (d1 < sel.r + handleSeuil || d2 < sel.r + handleSeuil) hit = if (d1 <= d2) 0 else 1
                            }
                            is Obstacle.Accelerateur -> {
                                // seule poignée : taille (bord droit) — jauge fixée à la
                                // création depuis accelerateurGaugeDefault
                                if (hypot(wx - (sel.cx + sel.r), wy - sel.cy) < handleSeuil) hit = 0
                            }
                            is Obstacle.Ellipse -> {
                                // 0 = rx (bord droit), 1 = ry (bord bas) — indépendantes
                                if (hypot(wx - (sel.cx + sel.rx), wy - sel.cy) < handleSeuil) hit = 0
                                else if (hypot(wx - sel.cx, wy - (sel.cy + sel.ry)) < handleSeuil) hit = 1
                            }
                        }
                        if (hit >= 0) {
                            pushUndoObstacles()
                            editingEndpoint = hit
                            editingOriginal = sel
                            obstacleDessin = true
                            invalidate()
                            return true
                        }
                    }
                    val seuil = 60f / camScale
                    var bestIdx = -1
                    var bestDist = seuil
                    for (oi in obstacles.indices) {
                        val d = distPointObstacle(wx, wy, obstacles[oi])
                        if (d < bestDist) { bestDist = d; bestIdx = oi }
                    }
                    selectedObstacleIndex = bestIdx
                    // BUG CORRIGÉ (2026-08-18, retour d'usage : "quand je pose
                    // les deux [disques] et que je sélectionne pour en faire
                    // bouger un, par défaut ça sélectionne les 2... ils
                    // doivent toujours se sélectionner individuellement") :
                    // editingEndpoint restait à -1 (obstacle ENTIER
                    // sélectionné) tant qu'un 2e tap ne venait pas préciser
                    // quel disque — un premier drag déplaçait donc les 2
                    // disques ensemble. Portail est le seul obstacle à 2
                    // "poignées" qui sont aussi grosses que l'obstacle
                    // lui-même (les disques), donc le tout premier tap sait
                    // déjà, sans ambiguïté, lequel des deux a été touché —
                    // pas besoin d'un 2e tap comme pour Ligne/Rectangle où la
                    // poignée est un petit point distinct du corps de la
                    // forme. Même calcul que le hit-test de la poignée
                    // ci-dessus (d1 vs d2), appliqué dès la sélection initiale.
                    editingEndpoint = (obstacles.getOrNull(bestIdx) as? Obstacle.Portail)?.let { p ->
                        val d1 = hypot(wx - p.x1, wy - p.y1)
                        val d2 = hypot(wx - p.x2, wy - p.y2)
                        if (d1 <= d2) 0 else 1
                    } ?: -1
                    editingOriginal = obstacles.getOrNull(bestIdx)
                    if (bestIdx >= 0) {
                        // début du drag : on mémorise le point de départ
                        // pour calculer le déplacement relatif
                        obstacleX1 = wx
                        obstacleY1 = wy
                        obstacleDessin = true // flag « en train de déplacer »
                    }
                    invalidate()
                    return true
                }
                if (outilObstacle != 0 || gommeObstacles || gommePeinture) {
                    // mode obstacle — snapshot complet (peinture incluse) pour
                    // la gomme peinture, léger (obstacles seuls) pour les autres
                    if (gommePeinture) pushUndo() else pushUndoObstacles()
                    val wx = toWorldX(event.x)
                    val wy = toWorldY(event.y)
                    obstacleX1 = wx
                    obstacleY1 = wy
                    obstacleX2 = wx
                    obstacleY2 = wy
                    lastGommeX = wx
                    lastGommeY = wy
                    // bouchon : placé immédiatement au down, taille par défaut —
                    // si le doigt reste posé (move), ça redimensionne direct
                    // (comme la poignée de l'outil flèche) ; simple tap = taille
                    // par défaut (cf. editingOriginal, snapshot pour le move)
                    if (outilObstacle == 2) {
                        obstacleDessin = true
                        obstacles.add(Obstacle.Bouchon(obstacleX1, obstacleY1, OBSTACLE_DEFAULT_R))
                        editingOriginal = obstacles.last()
                    }
                    // triangle flipper : placé immédiatement au down, même
                    // principe que le bouchon (redimensionnement direct au move)
                    else if (outilObstacle == 3) {
                        obstacleDessin = true
                        // 2026-08-13, suite demande "homogène" : même empreinte
                        // (boîte englobante 2×OBSTACLE_DEFAULT_R) que le cercle
                        // et le rectangle, plutôt qu'une taille propre au triangle
                        val d = OBSTACLE_DEFAULT_R * 2f
                        val hw = OBSTACLE_DEFAULT_R
                        obstacles.add(Obstacle.Triangle(
                            obstacleX1, obstacleY1,
                            obstacleX1 - hw, obstacleY1 + d,
                            obstacleX1 + hw, obstacleY1 + d))
                        editingOriginal = obstacles.last()
                    }
                    // ligne (et trampoline, 2026-08-29, même geste — outil 11,
                    // cf. Obstacle.Ligne.trampoline) : geste continu unique —
                    // le point A est le down, le point B suit le doigt en
                    // direct (preview), le segment est créé au relâchement
                    // (ACTION_UP)
                    else if (outilObstacle == 4 || outilObstacle == 11) {
                        obstacleDessin = true
                        lignePhase = 1
                        obstacleX1 = wx
                        obstacleY1 = wy
                        obstacleX2 = wx
                        obstacleY2 = wy
                    }
                    // rectangle : placé immédiatement au down, même principe
                    // que Bouchon/Triangle (2026-08-11, demande explicite :
                    // "pouvoir faire des rectangles sur le même principe que
                    // les autres formes") — sommet 1 = coin haut-gauche,
                    // devient la poignée tourner+redimensionner
                    else if (outilObstacle == 5) {
                        obstacleDessin = true
                        // 2026-08-13 : même empreinte que les autres formes (cf.
                        // Triangle ci-dessus) — carré 2×OBSTACLE_DEFAULT_R
                        val hw = OBSTACLE_DEFAULT_R; val hh = OBSTACLE_DEFAULT_R
                        obstacles.add(Obstacle.Rectangle(
                            obstacleX1 - hw, obstacleY1 - hh,
                            obstacleX1 + hw, obstacleY1 - hh,
                            obstacleX1 + hw, obstacleY1 + hh,
                            obstacleX1 - hw, obstacleY1 + hh))
                        editingOriginal = obstacles.last()
                    }
                    // portail : geste continu OU tap-puis-tap (cf. commentaire
                    // de portailPhase). Points dédiés (portailX1/Y1/X2/Y2),
                    // PAS obstacleX1/Y1/X2/Y2 — ces derniers sont réassignés
                    // sans condition juste au-dessus (lignes 1906-1909) pour
                    // TOUS les outils, donc utiliser les mêmes ici effaçait
                    // le point A du 1er tap dès le down du 2e (bug réel :
                    // "le premier tap ne semble pas marcher").
                    else if (outilObstacle == 6) {
                        obstacleDessin = true
                        portailGestureStartedArmed = portailPhase == 1
                        portailDownScreenX = event.x
                        portailDownScreenY = event.y
                        portailDragMoved = false
                        if (!portailGestureStartedArmed) {
                            portailPhase = 1
                            portailX1 = wx
                            portailY1 = wy
                        }
                        portailX2 = wx
                        portailY2 = wy
                    }
                    // planète : placée immédiatement au down, taille par défaut ;
                    // le move redimensionne direct (via editingOriginal, comme Bouchon)
                    else if (outilObstacle == 7) {
                        obstacleDessin = true
                        obstacles.add(Obstacle.Planete(obstacleX1, obstacleY1, PLANETE_DEFAULT_R, planeteMassDefault, PLANETE_DEFAULT_INFLUENCE))
                        editingOriginal = obstacles.last()
                    }
                    // anti-planète : même principe, repel = true (2026-08-13)
                    else if (outilObstacle == 10) {
                        obstacleDessin = true
                        obstacles.add(Obstacle.Planete(obstacleX1, obstacleY1, PLANETE_DEFAULT_R, planeteInverseMassDefault, PLANETE_DEFAULT_INFLUENCE, repel = true))
                        editingOriginal = obstacles.last()
                    }
                    // accélérateur : même principe que Bouchon/Planète — placé
                    // immédiatement avec la jauge par défaut, le move redimensionne le rayon
                    else if (outilObstacle == 8) {
                        obstacleDessin = true
                        obstacles.add(Obstacle.Accelerateur(obstacleX1, obstacleY1, ACCELERATEUR_DEFAULT_R, accelerateurGaugeDefault))
                        editingOriginal = obstacles.last()
                    }
                    // ellipse : placée immédiatement, rx/ry par défaut — le move
                    // déforme les 2 axes indépendamment (dx→rx, dy→ry), pour
                    // pouvoir directement lui donner la forme voulue à la création
                    else if (outilObstacle == 9) {
                        obstacleDessin = true
                        obstacles.add(Obstacle.Ellipse(obstacleX1, obstacleY1, ELLIPSE_DEFAULT_R, ELLIPSE_DEFAULT_R))
                        editingOriginal = obstacles.last()
                    }
                    // mur / gomme (outilObstacle == 1, gommeObstacles ou gommePeinture)
                    else {
                        obstacleDessin = true
                    }
                    invalidate()
                    return true
                }
                downX = event.x
                downY = event.y
                downTimeMs = SystemClock.uptimeMillis()
                moved = false
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                // dessin libre : on ne teste plus la bille ici — seul l'outil
                // flèche peut l'attraper (cf. branche outilSelection
                // ci-dessus), pour ne jamais dessiner par erreur en la visant
                // mal. Le "pousser/éjecter au contact" (ballGrabInPinceau,
                // 2026-08-20) se fait au fil du glissé, cf. ACTION_MOVE —
                // pas ici, pas d'attrape.
                dragIndex = -1
                pushUndo() // snapshot avant le geste de dessin (rond ou trait)
                lastDrawX = toWorldX(event.x)
                lastDrawY = toWorldY(event.y)
                lastPushX = lastDrawX
                lastPushY = lastDrawY
                lastPushTimeMs = downTimeMs
                if (ballGrabInPinceau) {
                    touchWallX = lastDrawX
                    touchWallY = lastDrawY
                    touchWallVelX = 0f
                    touchWallVelY = 0f
                    touchWallActive = true
                }
                drawSegments = 0
                smoothedTrailWidth = -1f
                joinPrevDrawX = Float.NaN
                joinPrevDrawY = Float.NaN
                pinceauCarriedColor = null
                pinceauSmoothedColor = null
                traceEnCours = true
                ignorePinchGesture = false
                drawResumeAtMs = 0L
                onDrawStart?.invoke()
                UsageLog.d("geste start calque=${actif + 1}")
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ignorePinchGesture) {
                    UsageLog.d("2e doigt ignoré (bord) @(%.0f,%.0f) — continue dessin".format(event.getX(event.actionIndex), event.getY(event.actionIndex)))
                    return true
                }
                // camMode déjà activé dans le pré-bloc (avant scaleDetector).
                // On finalise : annuler la trace en cours si le doigt 1 avait
                // commencé à dessiner.
                if (traceEnCours) {
                    UsageLog.d("pinch: annule trace en cours (${drawSegments} segments déjà peints)")
                    annulerTraceEnCours()
                    traceEnCours = false
                }
                dragIndex = -1
                touchWallActive = false // 2e doigt = pan/zoom, plus de mur (2026-08-20)
                UsageLog.d("pinch: camMode ON (pré-bloc)")
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (camMode) {
                    // pan : le monde suit le centre des deux doigts (le zoom est
                    // déjà appliqué par le scaleDetector) — protégé si un doigt
                    // s'est levé (plus que 1 doigt → on quitte la navigation)
                    if (event.pointerCount < 2) {
                        UsageLog.d("pinch: camMode OFF (plus qu'1 doigt)")
                        camMode = false
                        // 2026-08-13, bug réel trouvé dans les logs ("zoom dézoom
                        // bug et refait des tracés") : tant que camMode est actif,
                        // ce bloc `return true`ait à chaque ACTION_MOVE SANS
                        // jamais mettre à jour `moved` (calculé plus bas, hors de
                        // camMode) — au relâchement, `!moved` restait vrai (valeur
                        // d'avant le pincement) même après tout un geste de zoom/
                        // pan, donc le code croyait à un simple tap et déposait un
                        // rond de peinture parasite à l'endroit où le pincement
                        // s'est terminé. Un pincement n'est jamais un tap.
                        moved = true
                        // Le doigt restant repart depuis SA position actuelle
                        // (pas l'ancien point d'avant le pinch, figé — sinon
                        // trait parasite traversant l'écran) mais ne peint
                        // qu'après un court délai (RESUME_PEINDRE_APRES_PINCH_MS,
                        // 2026-08-11, demande explicite : "faudrait un petit
                        // délai avant que ça repeigne").
                        lastDrawX = toWorldX(event.x)
                        lastDrawY = toWorldY(event.y)
                        drawSegments = 0
                        smoothedTrailWidth = -1f
                        joinPrevDrawX = Float.NaN
                        joinPrevDrawY = Float.NaN
                        pinceauCarriedColor = null
                        pinceauSmoothedColor = null
                        drawResumeAtMs = SystemClock.uptimeMillis() + RESUME_PEINDRE_APRES_PINCH_MS
                        return true
                    }
                    val fx = focusX(event)
                    val fy = focusY(event)
                    camX -= (fx - lastFocusX) / camScale
                    camY -= (fy - lastFocusY) / camScale
                    lastFocusX = fx
                    lastFocusY = fy
                    clampCamera() // on ne sort jamais de la page
                    invalidate()
                    return true
                }
                // outil sélection actif mais rien touché au down (tap dans le
                // vide, obstacleDessin resté faux) : le geste doit rester
                // absorbé, pas retomber sur le dessin libre (tracés parasites) —
                // sauf si c'est la bille qui a été attrapée (dragIndex >= 0),
                // qui doit suivre le doigt plus bas dans cette même fonction
                if (outilSelection && !obstacleDessin && dragIndex < 0) return true
                if (obstacleDessin) {
                    val wx = toWorldX(event.x)
                    val wy = toWorldY(event.y)
                    // édition par poignée : toujours recalculée à partir du
                    // snapshot pris au début du geste (editingOriginal), pas
                    // de la valeur mutée à la frame précédente
                    if (outilSelection && editingEndpoint >= 0 && selectedObstacleIndex >= 0 && selectedObstacleIndex < obstacles.size) {
                        val orig = editingOriginal
                        if (orig != null) {
                            obstacles[selectedObstacleIndex] = when (orig) {
                                is Obstacle.Ligne -> when (editingEndpoint) {
                                    0 -> orig.copy(x1 = wx, y1 = wy)
                                    1 -> orig.copy(x2 = wx, y2 = wy)
                                    // poignée de centre : déplace directement le
                                    // point de contrôle → courbe la ligne,
                                    // x1y1/x2y2 (les extrémités) ne bougent pas
                                    else -> orig.copy(cx = wx, cy = wy)
                                }
                                is Obstacle.Bouchon ->
                                    orig.copy(r = hypot(wx - orig.cx, wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                                is Obstacle.Planete -> if (editingEndpoint == 0) {
                                    // taille du noyau seule — n'entraîne plus la zone
                                    // d'influence (poignée dédiée ci-dessous, 2026-08-29)
                                    orig.copy(r = hypot(wx - orig.cx, wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                                } else {
                                    // zone d'influence seule — indépendante du noyau ET
                                    // de la gravité (réglée globalement)
                                    orig.copy(influenceRadius = hypot(wx - orig.cx, wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                                }
                                is Obstacle.Triangle -> if (editingEndpoint == 0) {
                                    val cx = (orig.x1 + orig.x2 + orig.x3) / 3f
                                    val cy = (orig.y1 + orig.y2 + orig.y3) / 3f
                                    val r0 = hypot(orig.x1 - cx, orig.y1 - cy)
                                    val r1 = hypot(wx - cx, wy - cy)
                                    if (r0 < 1f || r1 < 1f) orig else {
                                        val scale = r1 / r0
                                        val da = atan2(wy - cy, wx - cx) - atan2(orig.y1 - cy, orig.x1 - cx)
                                        val cosA = cos(da)
                                        val sinA = sin(da)
                                        fun tourne(px: Float, py: Float): Pair<Float, Float> {
                                            val sx = (px - cx) * scale
                                            val sy = (py - cy) * scale
                                            return Pair(cx + sx * cosA - sy * sinA, cy + sx * sinA + sy * cosA)
                                        }
                                        val (nx1, ny1) = tourne(orig.x1, orig.y1)
                                        val (nx2, ny2) = tourne(orig.x2, orig.y2)
                                        val (nx3, ny3) = tourne(orig.x3, orig.y3)
                                        Obstacle.Triangle(nx1, ny1, nx2, ny2, nx3, ny3)
                                    }
                                } else {
                                    // poignée base (1) : glisse UNIQUEMENT le long
                                    // de l'axe pointe→milieu de base d'origine —
                                    // monter/descendre éloigne/rapproche la base
                                    // de la pointe (règle la hauteur du triangle).
                                    // Axe et demi-largeur de base figés → x1-x2 et
                                    // x1-x3 restent strictement égaux (isocèle
                                    // garanti par construction, pas par calcul a
                                    // posteriori). (2026-08-14, 2e correction :
                                    // "monter/descendre pour éloigner/rapprocher
                                    // la base du sommet, toujours isocèle")
                                    val ax = orig.x1
                                    val ay = orig.y1
                                    val mx0 = (orig.x2 + orig.x3) / 2f
                                    val my0 = (orig.y2 + orig.y3) / 2f
                                    val axisLen = hypot(mx0 - ax, my0 - ay)
                                    if (axisLen < 1f) orig else {
                                        val ux = (mx0 - ax) / axisLen
                                        val uy = (my0 - ay) / axisLen
                                        // perpendiculaire à l'axe = direction de la base, fixe
                                        val vx = -uy
                                        val vy = ux
                                        val hb = hypot(orig.x2 - mx0, orig.y2 - my0)
                                        // projection du doigt sur l'axe = nouvelle
                                        // hauteur (distance pointe→base) ; plancher
                                        // pour éviter un triangle dégénéré
                                        val t = ((wx - ax) * ux + (wy - ay) * uy).coerceAtLeast(OBSTACLE_R_MIN)
                                        val nmx = ax + ux * t
                                        val nmy = ay + uy * t
                                        val nx2 = nmx + vx * hb
                                        val ny2 = nmy + vy * hb
                                        val nx3 = nmx - vx * hb
                                        val ny3 = nmy - vy * hb
                                        orig.copy(x2 = nx2, y2 = ny2, x3 = nx3, y3 = ny3)
                                    }
                                }
                                is Obstacle.Mur -> {
                                    if (orig.pts.isEmpty()) orig else {
                                        var sx = 0f; var sy = 0f
                                        for (p in orig.pts) { sx += p.first; sy += p.second }
                                        val cx = sx / orig.pts.size; val cy = sy / orig.pts.size
                                        val p0 = orig.pts[0]
                                        val r0 = hypot(p0.first - cx, p0.second - cy)
                                        val r1 = hypot(wx - cx, wy - cy)
                                        if (r0 < 1f || r1 < 1f) orig else {
                                            val scale = r1 / r0
                                            val da = atan2(wy - cy, wx - cx) - atan2(p0.second - cy, p0.first - cx)
                                            val cosA = cos(da)
                                            val sinA = sin(da)
                                            orig.copy(pts = orig.pts.map { (px, py) ->
                                                val sx2 = (px - cx) * scale
                                                val sy2 = (py - cy) * scale
                                                Pair(cx + sx2 * cosA - sy2 * sinA, cy + sx2 * sinA + sy2 * cosA)
                                            })
                                        }
                                    }
                                }
                                is Obstacle.Rectangle -> if (editingEndpoint == 0) {
                                    val cx = (orig.x1 + orig.x2 + orig.x3 + orig.x4) / 4f
                                    val cy = (orig.y1 + orig.y2 + orig.y3 + orig.y4) / 4f
                                    val r0 = hypot(orig.x1 - cx, orig.y1 - cy)
                                    val r1 = hypot(wx - cx, wy - cy)
                                    if (r0 < 1f || r1 < 1f) orig else {
                                        val scale = r1 / r0
                                        val da = atan2(wy - cy, wx - cx) - atan2(orig.y1 - cy, orig.x1 - cx)
                                        val cosA = cos(da)
                                        val sinA = sin(da)
                                        fun tourne(px: Float, py: Float): Pair<Float, Float> {
                                            val sx = (px - cx) * scale
                                            val sy = (py - cy) * scale
                                            return Pair(cx + sx * cosA - sy * sinA, cy + sx * sinA + sy * cosA)
                                        }
                                        val (nx1, ny1) = tourne(orig.x1, orig.y1)
                                        val (nx2, ny2) = tourne(orig.x2, orig.y2)
                                        val (nx3, ny3) = tourne(orig.x3, orig.y3)
                                        val (nx4, ny4) = tourne(orig.x4, orig.y4)
                                        Obstacle.Rectangle(nx1, ny1, nx2, ny2, nx3, ny3, nx4, ny4)
                                    }
                                } else {
                                    // poignée de segment (10..13) : glisse UNIQUEMENT les 2
                                    // sommets de ce côté (delta depuis le point de contact au
                                    // grab), les 2 autres restent fixes — élargit/allonge en
                                    // gardant un quadrilatère fermé
                                    val dx = wx - edgeGrabWx
                                    val dy = wy - edgeGrabWy
                                    when (editingEndpoint) {
                                        10 -> orig.copy(x1 = orig.x1 + dx, y1 = orig.y1 + dy, x2 = orig.x2 + dx, y2 = orig.y2 + dy)
                                        11 -> orig.copy(x2 = orig.x2 + dx, y2 = orig.y2 + dy, x3 = orig.x3 + dx, y3 = orig.y3 + dy)
                                        12 -> orig.copy(x3 = orig.x3 + dx, y3 = orig.y3 + dy, x4 = orig.x4 + dx, y4 = orig.y4 + dy)
                                        else -> orig.copy(x4 = orig.x4 + dx, y4 = orig.y4 + dy, x1 = orig.x1 + dx, y1 = orig.y1 + dy)
                                    }
                                }
                                is Obstacle.Portail -> when (editingEndpoint) {
                                    0 -> orig.copy(x1 = wx, y1 = wy)
                                    else -> orig.copy(x2 = wx, y2 = wy)
                                }
                                // seule poignée : taille (bord droit), comme Bouchon — la
                                // jauge ne se règle plus par objet, cf. accelerateurGaugeDefault
                                is Obstacle.Accelerateur ->
                                    orig.copy(r = hypot(wx - orig.cx, wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                                is Obstacle.Ellipse -> when (editingEndpoint) {
                                    0 -> orig.copy(rx = abs(wx - orig.cx).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                                    else -> orig.copy(ry = abs(wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX))
                                }
                            }
                        }
                        invalidate()
                        return true
                    }
                    // outil sélection : déplacer l'obstacle entier (corps, pas une poignée)
                    if (outilSelection && selectedObstacleIndex >= 0 && selectedObstacleIndex < obstacles.size) {
                        val dx = wx - obstacleX1
                        val dy = wy - obstacleY1
                        obstacleX1 = wx
                        obstacleY1 = wy
                        val o = obstacles[selectedObstacleIndex]
                        obstacles[selectedObstacleIndex] = when (o) {
                            is Obstacle.Mur -> o.copy(pts = o.pts.map { (px, py) -> Pair(px + dx, py + dy) })
                            is Obstacle.Bouchon -> o.copy(cx = o.cx + dx, cy = o.cy + dy)
                            is Obstacle.Planete -> o.copy(cx = o.cx + dx, cy = o.cy + dy)
                            is Obstacle.Triangle -> o.copy(
                            x1 = o.x1 + dx, y1 = o.y1 + dy,
                            x2 = o.x2 + dx, y2 = o.y2 + dy,
                            x3 = o.x3 + dx, y3 = o.y3 + dy)
                            is Obstacle.Rectangle -> o.copy(
                            x1 = o.x1 + dx, y1 = o.y1 + dy,
                            x2 = o.x2 + dx, y2 = o.y2 + dy,
                            x3 = o.x3 + dx, y3 = o.y3 + dy,
                            x4 = o.x4 + dx, y4 = o.y4 + dy)
                            is Obstacle.Ligne -> o.copy(x1 = o.x1 + dx, y1 = o.y1 + dy, x2 = o.x2 + dx, y2 = o.y2 + dy,
                                cx = o.cx + dx, cy = o.cy + dy)
                            is Obstacle.Portail -> o.copy(x1 = o.x1 + dx, y1 = o.y1 + dy, x2 = o.x2 + dx, y2 = o.y2 + dy)
                            is Obstacle.Accelerateur -> o.copy(cx = o.cx + dx, cy = o.cy + dy)
                            is Obstacle.Ellipse -> o.copy(cx = o.cx + dx, cy = o.cy + dy)
                        }
                        invalidate()
                        return true
                    }
                    if (gommePeinture) {
                        // gomme peinture : efface les pixels du calque actif
                        // sous le doigt, ne touche pas aux obstacles — taille
                        // réglable (2026-08-11, demande explicite).
                        // 2026-08-20, rapporté "la gomme fait des tracés pas
                        // net" : un simple drawCircle au point courant, sans
                        // relier au point précédent, laisse des trous entre
                        // 2 évènements MOVE dès que le doigt bouge un peu
                        // vite (même bug que le trait libre AVANT le lissage
                        // par segments) — repoussé par un trait (drawLine,
                        // capuchon rond) du dernier point au point courant,
                        // qui couvre tout le trajet parcouru.
                        val seuil = gommePeintureRadiusPx / camScale
                        val c = calques[actif]
                        eraserPaint.style = Paint.Style.STROKE
                        eraserPaint.strokeCap = Paint.Cap.ROUND
                        eraserPaint.strokeWidth = seuil * 2f
                        c.paintCanvas.drawLine(lastGommeX, lastGommeY, wx, wy, eraserPaint)
                        c.wetCanvas.drawLine(lastGommeX, lastGommeY, wx, wy, eraserPaint)
                        lastGommeX = wx
                        lastGommeY = wy
                    } else if (gommeObstacles) {
                        // Gomme obstacles — TOUJOURS locale, quel que soit le
                        // type (2026-08-11, demande explicite : "enlever le
                        // tap avec la gomme pour enlever l'élément... la
                        // gomme efface uniquement la taille de la gomme sur
                        // l'endroit où elle est passée") : plus de
                        // suppression d'une forme entière par simple tap —
                        // supprimer une forme entière se fait maintenant via
                        // la petite croix sur la sélection (outil flèche).
                        // Taille réglable comme la gomme peinture.
                        val seuil = gommeObstaclesRadiusPx / camScale
                        var changed = false
                        val nouveaux = mutableListOf<Obstacle>()
                        for (ob in obstacles) {
                            // Portail : paire indivisible, pas de gomme locale
                            // (cf. doc de Obstacle.Portail) — se supprime
                            // entier via la croix de sélection uniquement.
                            if (ob is Obstacle.Portail) { nouveaux.add(ob); continue }
                            if (ob is Obstacle.Planete) { nouveaux.add(ob); continue } // pas de gomme locale, comme Portail
                            if (ob is Obstacle.Accelerateur) { nouveaux.add(ob); continue } // idem
                            val pts = when (ob) {
                                is Obstacle.Mur -> ob.pts
                                is Obstacle.Bouchon -> bouchonPoints(ob)
                                is Obstacle.Triangle -> trianglePoints(ob)
                                is Obstacle.Rectangle -> rectanglePoints(ob)
                                is Obstacle.Ligne -> lignePoints(ob)
                                is Obstacle.Ellipse -> ellipsePoints(ob)
                                is Obstacle.Portail -> emptyList() // inatteignable (continue ci-dessus)
                                is Obstacle.Planete -> emptyList() // inatteignable (continue ci-dessus)
                                is Obstacle.Accelerateur -> emptyList() // inatteignable (continue ci-dessus)
                            }
                            val closed = ob is Obstacle.Bouchon || ob is Obstacle.Triangle || ob is Obstacle.Rectangle || ob is Obstacle.Ellipse
                            val morceaux = erasePointsPortion(pts, wx, wy, seuil, closed)
                            if (morceaux.size == 1 && morceaux[0].size == pts.size) {
                                nouveaux.add(ob) // rien retiré
                            } else {
                                changed = true
                                nouveaux.addAll(morceaux.map { Obstacle.Mur(it) })
                                // morceaux vide (tout gommé) : rien ajouté, la forme disparaît complètement
                            }
                        }
                        if (changed) {
                            obstacles.clear()
                            obstacles.addAll(nouveaux)
                            selectedObstacleIndex = -1 // les index ont pu bouger
                        }
                    } else if (outilObstacle == 1) {
                        // mur : tracé libre — un seul Mur (polyligne) pour tout
                        // le geste, créé au 1er point ajouté (pas au down, pour
                        // qu'un simple tap ne laisse rien) puis complété point
                        // par point, afin de pouvoir sélectionner/déplacer toute
                        // la ligne d'un coup ensuite
                        if (hypot(wx - obstacleX2, wy - obstacleY2) > 5f) {
                            val idx = currentMurIndex
                            val existing = if (idx in obstacles.indices) obstacles[idx] as? Obstacle.Mur else null
                            if (existing != null) {
                                obstacles[idx] = existing.copy(pts = existing.pts + Pair(wx, wy))
                            } else {
                                obstacles.add(Obstacle.Mur(listOf(Pair(obstacleX2, obstacleY2), Pair(wx, wy))))
                                currentMurIndex = obstacles.size - 1
                            }
                            obstacleX2 = wx
                            obstacleY2 = wy
                        }
                    } else if (outilObstacle == 2) {
                        // bouchon : le doigt reste posé après le tap → redimensionne
                        // direct, comme si on venait de saisir sa poignée (même
                        // calcul, à partir du snapshot pris au placement).
                        // 2026-08-13, bug réel signalé ("pas une dimension similaire
                        // quand on les fait apparaître par un simple tap") : ce
                        // recalcul se faisait sur CHAQUE move, y compris le micro-
                        // tremblement inévitable d'un tap "immobile" (quelques px) —
                        // le rayon fini par tap était donc quasi aléatoire (souvent
                        // écrasé près du minimum) au lieu de rester à
                        // OBSTACLE_DEFAULT_R. Sous le seuil de touch-slop, on ne
                        // touche plus à la taille — elle ne bouge qu'au-delà d'un
                        // vrai geste de redimensionnement intentionnel.
                        val orig = editingOriginal as? Obstacle.Bouchon
                        if (orig != null && obstacles.isNotEmpty() && hypot(wx - orig.cx, wy - orig.cy) > touchSlop / camScale) {
                            obstacles[obstacles.size - 1] = orig.copy(
                                r = hypot(wx - orig.cx, wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX)
                            )
                        }
                    } else if (outilObstacle == 7 || outilObstacle == 10) {
                        // planète (+ anti-planète, 2026-08-14 : "un repousseur a
                        // perdu son aura" — cette branche ne testait que l'outil
                        // 7, donc le glissé de redimensionnement juste après la
                        // pose d'un Anti-Planète ne faisait rien, contrairement à
                        // la poignée de sélection qui gère les deux via le type)
                        // même principe que le bouchon — redimensionne
                        // le noyau ; la masse et l'influence suivent (masse ∝ r²).
                        // Même garde-fou touch-slop que Bouchon ci-dessus.
                        val orig = editingOriginal as? Obstacle.Planete
                        if (orig != null && obstacles.isNotEmpty() && hypot(wx - orig.cx, wy - orig.cy) > touchSlop / camScale) {
                            val newR = hypot(wx - orig.cx, wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX)
                            val ratio = (newR / orig.r).let { it * it }
                            obstacles[obstacles.size - 1] = orig.copy(
                                r = newR,
                                mass = orig.mass * ratio,
                                influenceRadius = orig.influenceRadius * (newR / orig.r)
                            )
                        }
                    } else if (outilObstacle == 8) {
                        // accélérateur : même principe que le bouchon — le rayon de
                        // la zone suit le doigt ; la jauge se règle plus tard, à la
                        // sélection avec l'outil flèche. Même garde-fou touch-slop.
                        val orig = editingOriginal as? Obstacle.Accelerateur
                        if (orig != null && obstacles.isNotEmpty() && hypot(wx - orig.cx, wy - orig.cy) > touchSlop / camScale) {
                            obstacles[obstacles.size - 1] = orig.copy(
                                r = hypot(wx - orig.cx, wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX)
                            )
                        }
                    } else if (outilObstacle == 9) {
                        // ellipse : dx (écart horizontal au centre) → rx, dy → ry,
                        // indépendamment — se déforme directement à la création.
                        // Même garde-fou touch-slop (sur la distance totale, pas
                        // par axe, pour rester cohérent avec les autres formes).
                        val orig = editingOriginal as? Obstacle.Ellipse
                        if (orig != null && obstacles.isNotEmpty() && hypot(wx - orig.cx, wy - orig.cy) > touchSlop / camScale) {
                            obstacles[obstacles.size - 1] = orig.copy(
                                rx = abs(wx - orig.cx).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX),
                                ry = abs(wy - orig.cy).coerceIn(OBSTACLE_R_MIN, OBSTACLE_R_MAX)
                            )
                        }
                    } else if (outilObstacle == 3) {
                        // triangle : même principe que le bouchon — rotation +
                        // redimensionnement autour du centre, depuis le snapshot
                        val orig = editingOriginal as? Obstacle.Triangle
                        if (orig != null && obstacles.isNotEmpty()) {
                            val cx = (orig.x1 + orig.x2 + orig.x3) / 3f
                            val cy = (orig.y1 + orig.y2 + orig.y3) / 3f
                            val r0 = hypot(orig.x1 - cx, orig.y1 - cy)
                            val r1 = hypot(wx - cx, wy - cy)
                            if (r0 >= 1f && r1 >= 1f) {
                                val scale = r1 / r0
                                val da = atan2(wy - cy, wx - cx) - atan2(orig.y1 - cy, orig.x1 - cx)
                                val cosA = cos(da)
                                val sinA = sin(da)
                                fun tourne(px: Float, py: Float): Pair<Float, Float> {
                                    val sx = (px - cx) * scale
                                    val sy = (py - cy) * scale
                                    return Pair(cx + sx * cosA - sy * sinA, cy + sx * sinA + sy * cosA)
                                }
                                val (nx1, ny1) = tourne(orig.x1, orig.y1)
                                val (nx2, ny2) = tourne(orig.x2, orig.y2)
                                val (nx3, ny3) = tourne(orig.x3, orig.y3)
                                obstacles[obstacles.size - 1] = Obstacle.Triangle(nx1, ny1, nx2, ny2, nx3, ny3)
                            }
                        }
                    } else if (outilObstacle == 5) {
                        // rectangle : même principe que le triangle — rotation +
                        // redimensionnement autour du centre, depuis le snapshot
                        val orig = editingOriginal as? Obstacle.Rectangle
                        if (orig != null && obstacles.isNotEmpty()) {
                            val cx = (orig.x1 + orig.x2 + orig.x3 + orig.x4) / 4f
                            val cy = (orig.y1 + orig.y2 + orig.y3 + orig.y4) / 4f
                            val r0 = hypot(orig.x1 - cx, orig.y1 - cy)
                            val r1 = hypot(wx - cx, wy - cy)
                            if (r0 >= 1f && r1 >= 1f) {
                                val scale = r1 / r0
                                val da = atan2(wy - cy, wx - cx) - atan2(orig.y1 - cy, orig.x1 - cx)
                                val cosA = cos(da)
                                val sinA = sin(da)
                                fun tourne(px: Float, py: Float): Pair<Float, Float> {
                                    val sx = (px - cx) * scale
                                    val sy = (py - cy) * scale
                                    return Pair(cx + sx * cosA - sy * sinA, cy + sx * sinA + sy * cosA)
                                }
                                val (nx1, ny1) = tourne(orig.x1, orig.y1)
                                val (nx2, ny2) = tourne(orig.x2, orig.y2)
                                val (nx3, ny3) = tourne(orig.x3, orig.y3)
                                val (nx4, ny4) = tourne(orig.x4, orig.y4)
                                obstacles[obstacles.size - 1] = Obstacle.Rectangle(nx1, ny1, nx2, ny2, nx3, ny3, nx4, ny4)
                            }
                        }
                    } else if ((outilObstacle == 4 || outilObstacle == 11) && lignePhase == 1) {
                        // preview : le point B suit le doigt en direct
                        obstacleX2 = wx
                        obstacleY2 = wy
                    } else if (outilObstacle == 6 && portailPhase == 1) {
                        // preview : le disque bleu suit le doigt en direct
                        portailX2 = wx
                        portailY2 = wy
                        if (hypot(event.x - portailDownScreenX, event.y - portailDownScreenY) > touchSlop) {
                            portailDragMoved = true
                        }
                    }
                    invalidate()
                    return true
                }
                if (ignoreTouches) return true
                velocityTracker?.addMovement(event)
                if (hypot(event.x - downX, event.y - downY) > touchSlop) moved = true
                if (dragIndex >= 0) {
                    // la bille suit le doigt ; sa vélocité sera fixée au relâchement
                    val c = calques[dragIndex]
                    c.ballX = toWorldX(event.x)
                    c.ballY = toWorldY(event.y)
                    val now = SystemClock.uptimeMillis()
                    throwSamples.add(Triple(now, event.x, event.y))
                    while (throwSamples.isNotEmpty() && now - throwSamples.first().first > THROW_ACCEL_WINDOW_MS) {
                        throwSamples.removeFirst()
                    }
                    invalidate()
                } else {
                    val wx = toWorldX(event.x)
                    val wy = toWorldY(event.y)
                    if (SystemClock.uptimeMillis() < drawResumeAtMs) {
                        // délai après un pinch/pan (cf. drawResumeAtMs) : le
                        // doigt bouge mais ne peint pas encore — le suivre
                        // sans peindre évite qu'un trait démarre pile au
                        // relâchement du 2e doigt si le 1er reste juste posé.
                        lastDrawX = wx
                        lastDrawY = wy
                        invalidate()
                    } else {
                        // Un seul doigt = dessin. Si un 2e doigt est présent
                        // (ne devrait pas arriver ici, mais ceinture +
                        // bretelles), on ne dessine pas — on met juste à jour
                        // la position pour ne pas faire un trait parasite au
                        // retour à 1 doigt.
                        if (event.pointerCount >= 2) {
                            UsageLog.d("MOVE: ${event.pointerCount} doigts, camMode=$camMode → skip dessin")
                            lastDrawX = wx
                            lastDrawY = wy
                        } else {
                            // trait continu : le doigt appuyé trace la couleur
                            // sélectionnée, en direct (2026-08-11, demande
                            // explicite : "pas grave si on a des flash de
                            // peinture, ça laissait pas de trace" — le rattrapage
                            // différé a été retiré, direct = plus simple et plus
                            // fiable).
                            dessinerSegment(lastDrawX, lastDrawY, wx, wy)
                            // le doigt agit comme un mur mobile — position/
                            // vitesse mises à jour ici, mais la collision
                            // elle-même se fait à CHAQUE frame physique dans
                            // step() (2026-08-20, rapporté : "la balle passe
                            // souvent a travers de mon doigts" — un doigt
                            // immobile ne déclenche aucun MOVE, une bille
                            // rapide le traversait sans jamais être testée
                            // tant que ce test vivait seulement ici).
                            if (ballGrabInPinceau) {
                                val nowPush = SystemClock.uptimeMillis()
                                val dtMs = (nowPush - lastPushTimeMs).coerceAtLeast(1L)
                                touchWallVelX = (wx - lastPushX) / dtMs * 1000f
                                touchWallVelY = (wy - lastPushY) / dtMs * 1000f
                                touchWallX = wx
                                touchWallY = wy
                                touchWallActive = true
                                lastPushX = wx
                                lastPushY = wy
                                lastPushTimeMs = nowPush
                            }
                        }
                        lastDrawX = wx
                        lastDrawY = wy
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // un doigt se lève. Le bloc ci-dessous (délai de reprise +
                // reset drawSegments) ne s'applique QUE si un vrai pan/zoom
                // était en cours (camMode) — sinon (2e doigt ignoré ou jamais
                // confirmé comme un vrai pincement) rien ne doit être
                // perturbé (2026-08-11, bug réel trouvé en cherchant "regarde
                // le log" : ce bloc tournait pour CHAQUE 2e doigt qui se
                // lève, y compris un contact fantôme qu'on avait déjà décidé
                // d'ignorer — remettait drawSegments à 0 en plein trait
                // réel ET bloquait la peinture 220ms de plus, expliquant des
                // gestes visiblement perdus sans même une "trace annulée").
                if (camMode) {
                    for (i in 0 until event.pointerCount) {
                        if (i != event.actionIndex) {
                            lastDrawX = toWorldX(event.getX(i))
                            lastDrawY = toWorldY(event.getY(i))
                            drawSegments = 0
                            drawResumeAtMs = SystemClock.uptimeMillis() + RESUME_PEINDRE_APRES_PINCH_MS
                            break
                        }
                    }
                }
                camMode = false
                dragIndex = -1
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                traceEnCours = false
                touchWallActive = false // le doigt se lève : plus de mur (2026-08-20)
                if (obstacleDessin) {
                    // ligne : relâchement du geste continu → crée le segment
                    // (point B = position actuelle du doigt)
                    if ((outilObstacle == 4 || outilObstacle == 11) && lignePhase == 1) {
                        // point de contrôle = milieu par défaut → droite exacte
                        // tant que la poignée de centre n'a pas été bougée
                        obstacles.add(Obstacle.Ligne(obstacleX1, obstacleY1, obstacleX2, obstacleY2,
                            (obstacleX1 + obstacleX2) / 2f, (obstacleY1 + obstacleY2) / 2f,
                            trampoline = outilObstacle == 11))
                        lignePhase = 0
                    }
                    // portail : relâchement → crée la paire SI ce geste
                    // complète un placement (glissé, ou 2e tap d'un couple de
                    // taps) — cf. commentaire de portailPhase pour les 2
                    // modes. Un simple 1er tap sans glissé fixe le disque
                    // rouge et laisse la main tendue (portailPhase reste à 1,
                    // rien créé) pour le 2e tap. Un seul portail à la fois
                    // (2026-08-11, demande explicite : "il ne doit pouvoir
                    // exister qu'un portail de chaque" — comme le vrai jeu,
                    // poser une nouvelle paire remplace l'ancienne).
                    if (outilObstacle == 6 && portailPhase == 1) {
                        if (portailGestureStartedArmed || portailDragMoved) {
                            obstacles.removeAll { it is Obstacle.Portail }
                            obstacles.add(Obstacle.Portail(portailX1, portailY1, portailX2, portailY2, PORTAIL_DEFAULT_R))
                            portailPhase = 0
                        }
                        // sinon : 1er tap sans glissé, disque rouge seul fixé
                        // — reste en attente du 2e tap (portailPhase inchangé)
                    }
                    editingEndpoint = -1
                    editingOriginal = null
                    currentMurIndex = -1
                    obstacleDessin = false
                    return true
                }
                // outil sélection actif mais rien touché (tap dans le vide) :
                // ne pas laisser retomber sur le dépôt d'un rond de peinture —
                // sauf si la bille a été attrapée, qui doit se lancer plus bas
                if (outilSelection && dragIndex < 0) {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    return true
                }
                if (ignoreTouches) {
                    ignoreTouches = false
                    return true
                }
                if (camMode && event.pointerCount <= 1) {
                    // Revert (2026-08-11, demande explicite : "la 2e itération
                    // marchait bien, reprends ça" — la tentative de laisser
                    // tomber ce `return true` créait pire que le problème
                    // qu'elle visait à corriger, probablement un rond déposé
                    // à tort). Retour au comportement de la version qui
                    // marchait : reset simple, sans traiter la suite.
                    camMode = false
                    return true
                }
                if (dragIndex >= 0) {
                    // lancer : la bille part à la vitesse du doigt et « retombe »
                    // sur la feuille — elle écrit sa couleur portée (sillage)
                    // addMovement(event) manquait ici : sans le point du UP lui-même,
                    // le tracker calculait la vitesse jusqu'au dernier MOVE seulement —
                    // le tout dernier instant du geste (souvent le plus rapide d'un
                    // vrai coup de poignet) était systématiquement perdu.
                    velocityTracker?.addMovement(event)
                    // 2026-08-13 : les logs montraient des pointes de 40000-80000 px/s
                    // (VelocityTracker lui-même, avant tout multiplicateur maison) —
                    // bien au-delà de ce qu'un vrai doigt produit (référence Android :
                    // ~4000 px/s pour un fling "maximum"). Défaut connu de l'algorithme
                    // (extrapolation instable quand le dernier échantillon — ici le UP
                    // qu'on vient d'ajouter — est très proche dans le temps du
                    // précédent). Fix standard : borner DIRECTEMENT à la source via le
                    // paramètre maxVelocity, plutôt que de compenser en aval — ce sont
                    // ces pointes parasites qui écrasaient tout à la même vitesse et
                    // donnaient l'impression de non-proportionnalité.
                    velocityTracker?.computeCurrentVelocity(1000, MAX_FLING_SCREEN_PX_S)
                    throwSamples.add(Triple(SystemClock.uptimeMillis(), event.x, event.y))
                    val accelBoost = throwAccelBoost()
                    val c = calques[dragIndex]
                    val rawVX = (velocityTracker?.xVelocity ?: 0f) / camScale * accelBoost
                    val rawVY = (velocityTracker?.yVelocity ?: 0f) / camScale * accelBoost
                    val rawSpeed = hypot(rawVX, rawVY)
                    if (rawSpeed > MAX_SPEED) {
                        // compression souple UNE SEULE FOIS ici, au lancer — un lancer
                        // très fort reste visiblement plus fort qu'un lancer moyen au
                        // lieu d'être écrasé à la même valeur, mais une fois fixée,
                        // cette vitesse initiale n'est plus jamais retouchée par ce
                        // calcul (cf. step() : le clamp par frame ne fait que protéger
                        // contre un dépassement, il ne rogne pas une bille qui roule
                        // déjà sous le plafond)
                        val softSpeed = MAX_SPEED * tanh(rawSpeed / MAX_SPEED)
                        val k = softSpeed / rawSpeed
                        c.velX = rawVX * k
                        c.velY = rawVY * k
                    } else {
                        c.velX = rawVX
                        c.velY = rawVY
                    }
                    throwSamples.clear()
                    UsageLog.d("bille lancée calque=${dragIndex + 1} v=(${c.velX.toInt()},${c.velY.toInt()}) accel×${"%.2f".format(accelBoost)}")
                    dragIndex = -1
                    // lancer la bille en pause = relancer le jeu (gravité + mouvement)
                    if (paused) setPaused(false)
                } else if (drawSegments > 0) {
                    UsageLog.d("tracé fin, $drawSegments segments (calque ${actif + 1})")
                }
                // tap (sans bouger, hors bille) = rond de la taille du spot
                if (!moved && dragIndex < 0 && event.actionMasked == MotionEvent.ACTION_UP) {
                    val c = calques[actif]
                    c.spotCount++
                    // le rond du tap a la même taille que le trait (épaisseur)
                    tachePaint.color = inkColor(selectedColor)
                    val wx = toWorldX(event.x)
                    val wy = toWorldY(event.y)
                    c.paintCanvas.drawCircle(wx, wy, trailWidth / 2f, tachePaint)
                    c.wetCanvas.drawCircle(wx, wy, trailWidth / 2f, tachePaint)
                    UsageLog.d("rond déposé #%06X r=%.0f @(%.0f,%.0f) calque=%d — total %d"
                        .format(selectedColor and 0xFFFFFF, trailWidth / 2f, wx, wy, actif + 1, c.spotCount))
                    invalidate()
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    /** Index du calque dont la bille est touchée par le doigt (-1 si aucune). */
    private fun hitBall(x: Float, y: Float): Int {
        val reach = ballRadius * 1.3f
        val r2 = reach * reach
        for (i in calques.indices) {
            val c = calques[i]
            val dx = x - c.ballX
            val dy = y - c.ballY
            if (dx * dx + dy * dy <= r2) return i
        }
        return -1
    }


    /** Compare la vitesse en début vs fin de la fenêtre récente [throwSamples] :
     *  un geste qui accélère jusqu'au lâcher (vrai « coup de poignet ») lance
     *  plus fort qu'un geste à vitesse constante qui finit à la même vitesse
     *  instantanée. Décélérer ou rester à vitesse constante ne pénalise pas
     *  (plancher à 1×, pas de malus). */
    private fun throwAccelBoost(): Float {
        if (throwSamples.size < 4) return 1f
        val mid = throwSamples.size / 2
        val early = throwSamples.take(mid)
        val late = throwSamples.drop(mid)
        val earlySpeed = throwWindowSpeed(early)
        val lateSpeed = throwWindowSpeed(late)
        if (earlySpeed < 40f) return 1f // début de fenêtre quasi immobile : pas assez de signal pour juger d'une accélération
        val ratio = (lateSpeed / earlySpeed).coerceIn(1f, THROW_ACCEL_BOOST_MAX)
        return 1f + (ratio - 1f) * THROW_ACCEL_BOOST_WEIGHT
    }

    private fun throwWindowSpeed(pts: List<Triple<Long, Float, Float>>): Float {
        if (pts.size < 2) return 0f
        val (t0, x0, y0) = pts.first()
        val (t1, x1, y1) = pts.last()
        val dt = ((t1 - t0).coerceAtLeast(1)) / 1000f
        return hypot(x1 - x0, y1 - y0) / dt
    }

    // ---------------------------------------------------------------- undo

    /** Snapshot des couches du calque actif (avant un geste de dessin). */
    private fun pushUndo() {
        val c = calques[actif]
        val paint = c.paintLayer ?: return
        val wet = c.wetLayer ?: return
        val snap = UndoSnapshot(
            c,
            paint.copy(Bitmap.Config.ARGB_8888, false),
            wet.copy(Bitmap.Config.ARGB_8888, false),
            obstacles.toList()
        )
        undoStack.add(snap)
        while (undoStack.size > 5) {
            undoStack.removeAt(0).let { it.paint?.recycle(); it.wet?.recycle() }
        }
        // un nouveau geste invalide les redo (on repart de l'état actuel)
        redoStack.forEach { it.paint?.recycle(); it.wet?.recycle() }
        redoStack.clear()
    }

    /** Peint un segment du trait libre (couche + humide) et avance
     *  inkPhase/drawSegments. Largeur lissée UNE FOIS pour tout le segment
     *  brut (comportement inchangé) ; la couleur/le tracé eux, sont
     *  subdivisés en petits pas (cf. dessinerSousSegment) — 2026-09-04,
     *  retour direct : "je vois toujours les morceaux de disque successifs"
     *  pendant un changement de couleur en direct sur le mélangeur. Cause :
     *  un ACTION_MOVE = un seul aplat de couleur, potentiellement large et
     *  visible, si le doigt saute loin d'un événement tactile au suivant
     *  (typiquement en dessinant d'une main pendant qu'on glisse sur le
     *  mélangeur de l'autre). Même principe que TRAIL_STAMP_SPACING_RATIO
     *  côté bille : re-échantillonner en pas fixes fait avancer le fondu
     *  EMA de pinceauMixedColor bien plus finement, indépendamment de la
     *  granularité brute du toucher. */
    private fun dessinerSegment(x1: Float, y1: Float, x2: Float, y2: Float) {
        val d = hypot(x2 - x1, y2 - y1)
        inkPhase += d * TEXTURE_SCALE
        // lissage exponentiel vers la largeur cible : un segment isolé ne peut
        // plus sauter d'un coup à une largeur très différente (c'était ça, la
        // bosse à chaque jonction de capuchon rond) — l'ondulation reste
        // perceptible sur la longueur du trait, juste plus jamais brutale.
        // Une seule fois par segment BRUT (pas par sous-pas) : la largeur ne
        // pose pas le même problème de granularité que la couleur.
        val target = pinceauTextureWidth(trailWidth)
        smoothedTrailWidth = if (smoothedTrailWidth < 0f) target
            else smoothedTrailWidth + (target - smoothedTrailWidth) * 0.12f
        trailPaint.strokeWidth = smoothedTrailWidth
        if (d <= MIN_MOVE) {
            dessinerSousSegment(x1, y1, x2, y2, d)
            return
        }
        val step = (smoothedTrailWidth * PINCEAU_STEP_RATIO).coerceAtLeast(MIN_MOVE)
        val dirX = (x2 - x1) / d
        val dirY = (y2 - y1) / d
        var px = x1
        var py = y1
        var traveled = 0f
        var n = 0
        while (traveled < d && n < PINCEAU_MAX_SUBSTEPS) {
            val segLen = minOf(step, d - traveled)
            val nx = px + dirX * segLen
            val ny = py + dirY * segLen
            dessinerSousSegment(px, py, nx, ny, segLen)
            px = nx; py = ny
            traveled += segLen
            n++
        }
    }

    /** Un sous-pas de `dessinerSegment` : couleur (fondu EMA) + tracé
     *  (jonction arrondie avec le point précédent, même mécanisme qu'avant
     *  la subdivision — fonctionne à l'identique entre 2 appels bruts
     *  qu'entre 2 sous-pas d'un même appel, `joinPrevDrawX/Y` ne distingue
     *  pas les deux). */
    private fun dessinerSousSegment(x1: Float, y1: Float, x2: Float, y2: Float, dist: Float) {
        val cc = calques[actif]
        trailPaint.color = pinceauMixedColor(cc, x2, y2, dist)
        // jonction arrondie plutôt que deux bouts ronds indépendants : sur une
        // courbe serrée, deux Cap.ROUND consécutifs débordent visiblement à
        // l'intérieur du virage (bosses) — un Path à 3 points avec Join.ROUND
        // fond proprement le coude. Le 1er segment du trait n'a rien à joindre.
        if (joinPrevDrawX.isNaN()) {
            cc.paintCanvas.drawLine(x1, y1, x2, y2, trailPaint)
            cc.wetCanvas.drawLine(x1, y1, x2, y2, trailPaint)
        } else {
            strokePath.reset()
            strokePath.moveTo(joinPrevDrawX, joinPrevDrawY)
            strokePath.lineTo(x1, y1)
            strokePath.lineTo(x2, y2)
            cc.paintCanvas.drawPath(strokePath, trailPaint)
            cc.wetCanvas.drawPath(strokePath, trailPaint)
        }
        joinPrevDrawX = x1
        joinPrevDrawY = y1
        drawSegments++
    }

    /** Annule silencieusement (ni undo, ni redo) le trait en train d'être tracé
     *  à un seul doigt quand un 2e doigt arrive juste après — restaure le
     *  snapshot pris au down de ce même geste, pour effacer le coup de
     *  pinceau parasite avant de basculer en navigation caméra. Le trait
     *  étant peint en direct (pas de buffer), ça peut effacer un bref flash
     *  de couleur déjà affiché — accepté (2026-08-11, demande explicite). */
    private fun annulerTraceEnCours() {
        if (undoStack.isEmpty()) return
        val snap = undoStack.removeAt(undoStack.size - 1)
        if (snap.calque in calques && snap.paint != null) {
            val c = snap.calque
            c.paintCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.wetCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            snap.paint?.let { c.paintCanvas.drawBitmap(it, 0f, 0f, null) }
            snap.wet?.let { c.wetCanvas.drawBitmap(it, 0f, 0f, null) }
            invalidate()
        }
        snap.paint?.recycle()
        snap.wet?.recycle()
        UsageLog.d("trace annulée (pinch pendant un trait à 1 doigt)")
    }

    /** Snapshot LÉGER pour un geste d'obstacles (la peinture n'a pas changé) :
     *  ne capture que les obstacles — évite de copier 88 Mo par geste. */
    private fun pushUndoObstacles() {
        val c = calques[actif]
        undoStack.add(UndoSnapshot(c, null, null, obstacles.toList()))
        while (undoStack.size > 5) {
            undoStack.removeAt(0).let { it.paint?.recycle(); it.wet?.recycle() }
        }
        redoStack.forEach { it.paint?.recycle(); it.wet?.recycle() }
        redoStack.clear()
    }

    /** État courant du calque, poussé dans la pile de redo/undo. */
    private fun pushCurrent(c: Calque, avecPeinture: Boolean): UndoSnapshot? {
        if (avecPeinture) {
            val paint = c.paintLayer?.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val wet = c.wetLayer?.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            return UndoSnapshot(c, paint, wet, obstacles.toList())
        }
        return UndoSnapshot(c, null, null, obstacles.toList())
    }

    /** Annule le dernier geste de dessin (rond ou trait) sur son calque. */
    fun undo() {
        if (undoStack.isEmpty()) {
            UsageLog.d("undo : rien à annuler")
            return
        }
        val snap = undoStack.removeAt(undoStack.size - 1)
        if (snap.calque in calques) {
            val c = snap.calque
            // l'état courant part dans la pile de redo (léger si le geste était léger)
            pushCurrent(c, avecPeinture = snap.paint != null)?.let { cur ->
                redoStack.add(cur)
                while (redoStack.size > 5) redoStack.removeAt(0).let { it.paint?.recycle(); it.wet?.recycle() }
            }
            if (snap.paint != null) {
                // effacer D'ABORD, puis dessiner le snapshot par-dessus —
                // dans l'autre ordre, le clear qui suit efface aussitôt ce
                // qu'on vient de restaurer (undo vidait quasi toute la couche)
                c.paintCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                c.wetCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                snap.paint?.let { c.paintCanvas.drawBitmap(it, 0f, 0f, null) }
                snap.wet?.let { c.wetCanvas.drawBitmap(it, 0f, 0f, null) }
                // 2026-08-13, bug réel trouvé dans les logs : sampleSnapshot
                // (photo utilisée pour l'échantillonnage de couleur, cf.
                // samplePaintColor) n'était pas invalidée ici — elle restait
                // périmée (jusqu'à SAMPLE_SNAPSHOT_MS) et pointait encore sur
                // l'ÉTAT D'AVANT l'undo. La bille, en contact continu,
                // resamplait alors depuis cette photo obsolète pendant ~250ms
                // avant de rattraper le bon état — d'où la cascade de couleurs
                // "carried" fausses observée juste après un undo/redo
                // ("carried none → #A6276A → #A62769 → ..."). Forcer le
                // rafraîchissement immédiat de la photo règle le glitch.
                c.sampleSnapshotAt = 0L
            }
            c.carriedColor = null
            c.inkReserve = 0f
            // restaure les obstacles de construction (undo)
            obstacles.clear()
            obstacles.addAll(snap.obstacles)
            invalidate()
        }
        snap.paint?.recycle()
        snap.wet?.recycle()
        UsageLog.d("undo (reste ${undoStack.size}, redo ${redoStack.size})")
    }

    /** Refait le dernier geste annulé. */
    fun redo() {
        if (redoStack.isEmpty()) {
            UsageLog.d("redo : rien à refaire")
            return
        }
        val snap = redoStack.removeAt(redoStack.size - 1)
        if (snap.calque in calques) {
            val c = snap.calque
            pushCurrent(c, avecPeinture = snap.paint != null)?.let { cur ->
                undoStack.add(cur)
                while (undoStack.size > 5) undoStack.removeAt(0).let { it.paint?.recycle(); it.wet?.recycle() }
            }
            if (snap.paint != null) {
                c.paintCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                c.wetCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                snap.paint?.let { c.paintCanvas.drawBitmap(it, 0f, 0f, null) }
                snap.wet?.let { c.wetCanvas.drawBitmap(it, 0f, 0f, null) }
                // même fix que undo() : invalider la photo d'échantillonnage
                // périmée pour éviter la cascade de couleurs "carried" fausses
                c.sampleSnapshotAt = 0L
            }
            c.carriedColor = null
            c.inkReserve = 0f
            // restaure les obstacles de construction (redo)
            obstacles.clear()
            obstacles.addAll(snap.obstacles)
            invalidate()
        }
        snap.paint?.recycle()
        snap.wet?.recycle()
        UsageLog.d("redo (reste ${undoStack.size}, redo ${redoStack.size})")
    }

    // ---------------------------------------------------------------- actions

    fun togglePause() {
        setPaused(!paused)
    }

    /** Met la bille en pause (true) ou en lecture (false) — inopérant si l'état
     *  ne change pas. En passant en pause, les vitesses sont remises à zéro ;
     *  en passant en lecture, les vitesses sont CONSERVÉES (le lancer en pause
     *  garde ainsi son impulsion). */
    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        // 2026-08-13, demande explicite : "si je fais pause... la balle
        // devrait continuer son move [après], sauf si je la touche" — la
        // vitesse ne doit PLUS être remise à zéro à la pause (ancien
        // comportement) : pause = geler la simulation, pas annuler l'élan.
        // Attraper/lancer la bille pendant la pause écrase déjà la vitesse
        // via son propre mécanisme (ACTION_UP), donc rien n'est perdu de ce
        // côté. Idem pour l'ouverture d'un menu (Palette/Calques/Balle/
        // Export/réglages), qui met en pause par ce même chemin.
        lastFrameTime = 0L
        onPauseChanged?.invoke(paused)
        UsageLog.d(if (paused) "pause" else "play")
        invalidate()
        if (!paused) postInvalidateOnAnimation()
    }

    /** Efface le calque actif (peinture et taches — donnée jetable). */
    fun clear() {
        pushUndo() // un effacement est annulable
        val c = calques[actif]
        c.carriedColor = null
        c.inkReserve = 0f
        c.spotCount = 0
        c.paintLayer?.let { c.paintCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) }
        c.wetLayer?.let { c.wetCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) }
        invalidate()
        UsageLog.d("calque ${actif + 1} effacé")
    }

    fun isPaused(): Boolean = paused

    // ---------------------------------------------------------------- cycle de vie

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sensorManager.unregisterListener(this)
    }
}
