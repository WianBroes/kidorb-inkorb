package com.wian.kidorb

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    // 2026-09-01, demande explicite : pastilles préremplies avec les
    // couleurs de référence standard de l'arc-en-ciel (ROYGBIV), pas des
    // teintes maison — valeurs hex confirmées sur plusieurs sources
    // (webnots.com/vibgyor-rainbow-color-codes, itechguides.com/roygbiv-vs-
    // vibgyor-rainbow-color-codes, colortutorial.design/rainbow-color-
    // palette-hex-codes-for-all-seven-colors), noir/blanc gardés en
    // bookends (neutres utiles pour peindre, absents de ROYGBIV lui-même).
    // 2026-09-03, demande explicite : "j'ai rajouter plein de couleur dans
    // la palette du fairphone je veux que la palette par defaut sois plus
    // remplie" — les 18 emplacements (PALETTE_CAPACITY) remplacés par la
    // palette réellement configurée par Wian sur son Fairphone, extraite de
    // shared_prefs/encrebille.xml (`paletteState`, via `run-as`). Les 8
    // premières couleurs sont inchangées (ROYGBIV + noir) ; le blanc pur
    // d'origine a été remplacé par 10 teintes supplémentaires (ambre,
    // moutarde, turquoise, bleu ciel, gris clair, rose, fuchsia, bordeaux,
    // vert foncé, périwinkle).
    private val paletteColors = intArrayOf(
        0xFF000000.toInt(), // noir pur
        0xFFFF0000.toInt(), // rouge
        0xFFFF7F00.toInt(), // orange
        0xFFFFFF00.toInt(), // jaune
        0xFF00FF00.toInt(), // vert
        0xFF0000FF.toInt(), // bleu
        0xFF4B0082.toInt(), // indigo
        0xFF8B00FF.toInt(), // violet
        0xFFFEB300.toInt(), // ambre
        0xFFEFC400.toInt(), // moutarde
        0xFF00D5AB.toInt(), // turquoise
        0xFF3EBBFE.toInt(), // bleu ciel
        0xFFEEEEEE.toInt(), // gris clair
        0xFFFA68FF.toInt(), // rose
        0xFFE80075.toInt(), // fuchsia
        0xFFA60050.toInt(), // bordeaux
        0xFF009D19.toInt(), // vert foncé
        0xFF4F65FE.toInt()  // périwinkle
    )
    private val defaultColorIndex = 1 // rouge

    private lateinit var canvas: BallCanvasView
    private lateinit var versionLabel: TextView    // repère de version, masqué quand un panneau recouvre son coin
    private lateinit var panelMenu: FrameLayout    // panneau de menu partagé (à l'opposé de la barre)
    private lateinit var menuFlipper: ViewFlipper  // contenu de l'onglet actif
    private lateinit var btnBalle: ImageView
    private lateinit var btnMenu: ImageView
    private lateinit var submenuObstacle: LinearLayout // sous-menu des outils de construction
    // Repasse au pinceau (cf. activerPinceau, local à onCreate) — exposé en
    // callback de champ pour être appelable depuis buildColorGrid/
    // rebuildQuickColors (méthodes de classe séparées, hors de portée des
    // fonctions locales d'onCreate). 2026-08-11, demande explicite :
    // "quand on sélectionne une couleur, ça doit sélectionner le pinceau
    // d'office vu qu'on voulait peindre".
    private var retourPinceau: (() -> Unit)? = null
    private lateinit var pauseBtn: ImageView
    private lateinit var btnHideUi: ImageView
    private lateinit var btnUndo: ImageView
    private lateinit var btnRedo: ImageView
    private lateinit var controlsCluster: LinearLayout // cluster (mini-rangée + réglages + couleurs + menu) — bascule de côté
    private lateinit var tabbarView: LinearLayout
    // échelle du menu latéral (tabbar) — réglable au curseur (2026-08-13,
    // demande explicite), appliquée via scaleX/scaleY (transform visuel,
    // ne touche pas les tailles dp individuelles de chaque bouton)
    private var tabbarScale: Float = 1f
    // taille réelle (dp, pas un scale visuel) des icônes fixes du tabbar ET
    // des pastilles de couleur rapides — réduite automatiquement sous 44dp
    // sur écran court pour garder des pastilles de couleur cliquables
    // (cf. bloc de réduction dans onCreate, 2026-09-03)
    private var tabbarButtonSizeDp: Float = 44f
    // espacement (dp) entre le cluster de contrôles et le bord d'écran —
    // réglable au curseur, même bord des 2 côtés (2026-08-13, demande explicite).
    // Peut descendre sous 0 (chevauche légèrement le bord) — "je dois encore
    // pouvoir me rapprocher plus" (2026-08-13, suite immédiate).
    private var controlsEdgeMargin: Float = 10f
    // position verticale (dp, distance depuis le bas) du cluster de
    // raccourcis — réglable au curseur (2026-08-13, demande explicite :
    // "j'aimerais pouvoir le monter et le descendre aussi")
    private var controlsBottomMargin: Float = 74f
    // position verticale (dp) du panneau à onglets (Réglages/Palette/...) —
    // indépendante du cluster de raccourcis ci-dessus (2026-08-14, demande
    // explicite : "faudrait pouvoir bouger le menu des raccourcis
    // indépendamment que la fenêtre avec les onglets" — les deux étaient
    // couplés sur controlsBottomMargin, découplés ici)
    private var panelBottomMargin: Float = 74f
    private lateinit var quickColorsContainer: LinearLayout // raccourcis couleur défilables
    // Mode enfant (2026-08-20, demande explicite : "simplifier... à la place
    // de plein de pastilles, un curseur qui sélectionne la couleur") : les
    // raccourcis couleur (liste de pastilles) sont remplacés par une seule
    // barre dégradée verticale (glisser haut/bas = choisir la teinte), même
    // gabarit que la liste qu'elle remplace. Bascule dans Paramètres.
    private var modeEnfant = false
    // 2026-08-21, demande explicite mode kid : "bloquer le zoom dezoom a la
    // taille de lecran pour que le canva soit remplis"
    private var zoomLocked = false
    // 2026-08-25, demande explicite : "l'édition de l'interface ne doit pas
    // être visible si le mode dev n'est pas sélectionné, il faut un toggle
    // dans mode dev pour choisir d'afficher ou cacher cela par profil" —
    // par défaut l'onglet "Interface" de Paramètres (curseurs de mise en
    // page + langue) n'existe que si devModeOn est actif, comme l'onglet
    // "Mode kid" ; ce toggle (propre à chaque profil, réglable UNIQUEMENT
    // depuis l'onglet développeur) permet de le garder visible sans mode dev
    // pour un profil donné (cf. refreshParamSousOnglets, buildParametresModeKid).
    private var interfaceEditVisible = false
    // 2026-08-21, demande explicite : "un menu developper pour configurer
    // un peu tout... je dois pouvoir configurer le mode normal ett le mode
    // kid" — N profils de réglages (modeEnfant/zoomLocked/pickerType/
    // wheelSizeDp/disabledTools/tabbarScale/...), chacun avec ses propres
    // clés préfixées par un id stable (indépendant du nom, renommable sans
    // perdre les données) ; devModeOn masque tout sauf ce switch quand off,
    // pour qu'un enfant ne tombe jamais dessus par hasard.
    // 2026-08-25, demande explicite : "quand on installe l'app elle soit
    // dans le profil kid, avec le mode dev désélectionné (tjs disponible,
    // juste pas actif)" — app pensée pour être donnée telle quelle à un
    // enfant dès l'installation, pas seulement après configuration manuelle.
    private var devModeOn = false
    // 2026-08-25, demande explicite : "je dois pouvoir désactiver dans le
    // menu dev les différents logs" — indépendant de devModeOn (on peut
    // vouloir garder le mode dev pour la config mais couper les logs avant
    // de donner l'app).
    // 2026-09-02, demande explicite : "les logs désactiver, il faut bien se
    // dire que c'est destiné au grand public" — défaut basculé à false (pas
    // de fichier de diagnostic écrit sans qu'on l'ait demandé) ; le switch
    // (Mode développeur) reste disponible pour le réactiver au besoin.
    private var usageLogEnabled = false
    // 2026-08-25, demande explicite : réglage "qualité du canvas" (Galaxy
    // A13, ça ramait) — cf. BallCanvasView.pageOversizeFactor.
    private var pageOversizeFactor = 2f
    // 2026-08-29, en test réel avec Wian ("mélange couleur", cf. CHANGELOG) —
    // cf. BallCanvasView.melangeExperiment pour le détail des 3 pistes.
    private var melangeExperiment = 1 // 2026-09-01, demande explicite : Protection anti-retour activée par défaut
    // 2026-08-30, retour testeur externe ("bille lente/lourde") — cf.
    // BallCanvasView.tiltResponseMode pour le détail des pistes.
    private var tiltResponseMode = 1 // 2026-09-01, demande explicite : réponse à l'inclinaison linéaire par défaut
    // mixVividMode (vivacité du mélange) : rendu propre à chaque bille le
    // 2026-08-30 (cf. BilleProfile.mixVividMode) — plus de variable globale.
    // 2026-08-29, demande explicite : "je peux choisir des valeurs par
    // défaut [planète/planète inverse/accélérateur] et que l'outil ne sera
    // modifiable que par leur taille" — cf. BallCanvasView.planeteMassDefault
    // et consorts, réglés dans Paramètres → Développeur → Outils.
    private var planeteMassDefault = BallCanvasView.PLANETE_MASS_DEFAULT
    private var planeteInverseMassDefault = BallCanvasView.PLANETE_MASS_DEFAULT
    private var accelerateurGaugeDefault = 0.6f
    // 2026-08-29, demande explicite : "quand la balle passe dans la portée
    // de la planète, ça annulerait la gravité du téléphone... ça résoudrait
    // pas les problèmes de fun avec ? probablement qu'il faut un toggle
    // aussi" — cf. BallCanvasView.planeteCancelTilt.
    private var planeteCancelTilt = false
    // 2026-08-21, demande explicite : "je pense qu'on devrait pouvoir creer
    // dautre mode que juste kid... prevois pour le moment de pouvoir cree
    // un autre mode avec un petit plus" — plus de simple binaire 0/1,
    // liste ouverte de profils nommés (comme billeProfiles), plus un "+".
    private data class ModeProfil(val id: Int, var nom: String)
    private val modeProfils = mutableListOf<ModeProfil>()
    private var nextModeId = 2
    // 2026-08-26, demande explicite : la variante "kid" (BuildConfig.
    // ADVANCED_EDITION=false) démarre sur le profil Kid ; la variante
    // "advanced" (InkOrb) sur le profil Normal — même code, défaut différent
    // par variante Gradle (cf. app/build.gradle.kts).
    private val defaultConfigProfil = if (BuildConfig.ADVANCED_EDITION) 0 else 1
    private var configProfilActif = defaultConfigProfil // id du ModeProfil actif (0 = Normal, 1 = Kid, 2+ = créés via "+")
    private val modeSelectorContainers = mutableListOf<LinearLayout>()
    private var appLocked = false
    private lateinit var btnQuitterModeKid: TextView
    // Bille Setup (2026-08-20, demande explicite : "je veux pouvoir avoir
    // tout les reglages actuels mais par billes... les creer, les renomer")
    // — bibliothèque de billes nommées, indépendante des calques (l'utilisateur
    // a précisé : "on ne parle pas ici de calques mais de billes"). Chaque
    // bille porte son propre paquet de réglages physiques ; l'appliquer
    // (menu long-press du bouton Balle, ou bouton dédié dans l'onglet Bille
    // Setup) écrit ses valeurs dans canvas.* sans toucher position/couleur/
    // encre en cours ("si je choisi une autre bille je continue mon dessin
    // avec"). billeSetupOnglet = sous-onglet affiché DANS l'onglet Bille
    // Setup, indépendant de la bille effectivement appliquée au canvas.
    private val billeProfiles = mutableListOf<BilleProfile>()
    // index de la bille actuellement appliquée au canvas (2026-08-20,
    // demande explicite : "auto-appliquer si c'est la bille active" —
    // modifier un réglage sur CE profil se répercute tout de suite ; -1 =
    // aucune bille encore appliquée cette session (au lancement, avant le
    // premier choix explicite).
    private var billeActiveIndex = -1
    private var billeSetupOnglet = 0
    private lateinit var billeSetupSubTabs: LinearLayout
    private lateinit var billeSetupContent: FrameLayout
    // 2026-08-22, demande explicite : "un éditeur de pinceaux à la suite
    // des billes dans le menu" — même principe que Bille Setup ci-dessus,
    // périmètre réduit (PinceauProfile : couleur/largeur/texture/fondu).
    private val pinceauProfiles = mutableListOf<PinceauProfile>()
    private var pinceauActiveIndex = -1
    private var pinceauSetupOnglet = 0
    private lateinit var pinceauSetupSubTabs: LinearLayout
    private lateinit var pinceauSetupContent: FrameLayout
    // pont vers refreshBalleSubmenu (fonction locale à onCreate, cf. même
    // principe que `retourPinceau` déjà utilisé pour activerPinceau) —
    // permet aux méthodes de classe (Bille Setup) de rafraîchir le sous-menu
    // long-press du bouton Balle après création/suppression/renommage.
    private var refreshBalleSubmenuCb: (() -> Unit)? = null
    // 2026-09-02, demande explicite : "+" facile dans le menu de raccourcis
    // (submenuBalle) + long-press sur une pastille qui gigote (billes non
    // protégées seulement) — cf. showBilleQuickEditor, qui porte aussi le
    // bouton Supprimer (plus de croix flottante dans la grille, "comme ça
    // pas d'erreur possible"). Séparé de l'éditeur avancé
    // (buildBilleProfileEditor, onglet Billes) sur demande explicite : "on
    // va garder séparer les 2 menus".
    private var billeJiggling = false
    private var billeQuickEditorOverlay: View? = null
    // Outils désactivables (2026-08-20, demande explicite : "la possibilité
    // de desactiver des outils comme ca je peux introduire progressivement
    // a lenfant... par section d outil et puis outil par outil. et les
    // espace vide libere de la place") — clés stables, indépendantes des
    // libellés affichés. Bouton masqué (View.GONE, pas juste grisé) →
    // libère son espace dans la barre/le sous-menu. Pont vers
    // applyToolVisibility() (fonction locale à onCreate, même principe que
    // refreshBalleSubmenuCb) pour que les switches de Paramètres puissent
    // déclencher le rafraîchissement.
    private val disabledTools = mutableSetOf<String>()
    private var refreshToolVisibilityCb: (() -> Unit)? = null
    private lateinit var quickColorsScrollView: ScrollView
    private lateinit var hueSliderGroup: LinearLayout
    private lateinit var hueSlider: HueSliderView
    private lateinit var colorWheel: ColorWheelView
    private lateinit var joystickPalette: JoystickPaletteView
    private lateinit var wheelPanel: FrameLayout
    private lateinit var wheelPreviewSwatch: View
    private lateinit var huePreviewSwatch: View
    // Taille de la roue, réglable dans Paramètres (2026-08-20, demande
    // explicite : "pour regler sa taille dans les options").
    private var wheelSizeDp: Float = 190f
    // 2026-08-25, demande explicite : "un toggle pour cacher le nom des
    // billes, car c'est moche et irrégulier d'avoir des icônes de tailles
    // différentes" — réglage global (pas par profil : masque le nom dans TOUS
    // les sélecteurs de bille d'un coup, cf. buildBilleCell), pas persisté
    // par profil comme les réglages de bille eux-mêmes.
    private var billeNamesVisible = true
    // Position libre du panneau roue (2026-08-20, demande explicite : "je
    // veux aussi pouvoir deplacer cette roue ou je veux") — dp depuis le
    // coin haut-gauche de rootLayout ; -1 = pas encore déplacé, position
    // par défaut calculée au-dessus de la barre (cf. positionWheelPanel()).
    private var wheelPosX: Float = -1f
    private var wheelPosY: Float = -1f
    // Type de sélecteur en mode enfant (2026-08-20, demande explicite :
    // "garde le premier principe en memoire et le 2eme comme des choix
    // possible que je veux activer... cherche une 3eme voie avec une
    // colorwheel") — 0 = pavé 2D (blanc/arc-en-ciel/noir × saturation),
    // 1 = roue (anneau + triangle). Bascule dans Paramètres, pas dans la
    // barre de raccourcis elle-même (elle reste étroite).
    private var pickerType = 0
    private lateinit var rootLayout: FrameLayout
    private var panW = 0 // dimensions du panneau de menu (80 % × 85 % de l'écran)
    private var panH = 0
    private var dragBadge: LinearLayout? = null // repère flottant pendant un glissé bille/pinceau (cercle d'aperçu + valeur)
    private var dragBadgeCircle: View? = null   // cercle à la taille réelle
    private var dragBadgeText: TextView? = null // valeur en dp
    // Badge flottant AU-DESSUS du doigt pendant le survol d'un sous-menu
    // (2026-08-10, demande explicite : « vu qu'on a le doigt dessus on voit
    // rien, faudrait avoir le même icône qui est au-dessus du doigt ») —
    // suit le doigt, décalé assez haut pour ne jamais être caché dessous.
    // Intitulé ajouté au-dessus de l'icône (2026-08-13, demande explicite :
    // "avoir le nom de l'outil quand on survole avant de le sélectionner" —
    // certaines icônes, comme la planète, n'étaient pas reconnaissables).
    private var hoverBadge: LinearLayout? = null
    private var hoverBadgeIcon: ImageView? = null
    private var hoverBadgeLabel: TextView? = null
    // 2026-09-01, demande explicite : défauts changés (mur→bézier, portail→planète)
    private var lastOutilsObstacle = 4 // dernière FORME utilisée (défaut: bézier)
    // dernier EFFET utilisé (défaut: planète) — bouton dédié séparé des
    // formes (2026-08-13, demande explicite)
    private var lastEffetUsed = 7
    private var lastGommeType = 1 // dernier type de gomme utilisé : 1 = peinture, 2 = obstacles (entiers), 3 = obstacles locale (formes fermées)
    // état exact (outil/gomme) juste avant d'entrer en outil sélection —
    // restauré à la sortie, pour ne pas retomber en pinceau libre sans
    // que ce soit affiché (cause de tracés parasites)
    private var preSelectOutil = 0
    private var preSelectGommeType = 0 // 0 = aucune, 1 = peinture, 2 = obstacles (entiers), 3 = obstacles locale (formes fermées)
    private var controlsOnRight = true
    private var uiHidden = false
    private var ongletBtns: MutableList<TextView> = mutableListOf() // onglets du panneau hamburger (Export/Paramètres uniquement)
    // Palette a son propre bouton dédié qui ouvre le panneau directement sur
    // son contenu, SANS barre d'onglets ni swipe — vue isolée (2026-08-19,
    // demande explicite : "je veux que le menu hamburger ne fasse que ces 2
    // choses là [Export/Paramètres]"). Indices du ViewFlipper (cf.
    // menuFlipper.addView ci-dessous, 2026-09-01 : ex-onglet "Balle" retiré,
    // code mort) : 0=Export,1=Palette,2=Paramètres,3=Billes,4=Pinceaux ;
    // seuls 0, 2, 3, 4 sont atteignables depuis le panneau hamburger (tabsRow).
    private val hamburgerTabIndices = listOf(0, 2, 3, 4)
    // 2026-08-24, demande explicite : "bille et pinceaux devrait etre visible
    // que dans le mode developpeur" — la BIBLIOTHÈQUE (créer/modifier/
    // supprimer des profils, onglets Billes/Pinceaux) est réservée au mode
    // développeur ; la sélection rapide parmi les profils déjà créés (tap
    // court sur les boutons Balle/Pinceau de la barre) reste disponible en
    // mode normal/kid, hors périmètre de cette demande.
    private val devOnlyTabs = setOf(3, 4)
    private lateinit var tabsRow: LinearLayout
    private lateinit var tabsRowScroll: HorizontalScrollView
    private var ongletCourant = 0 // le hamburger s'ouvre par défaut sur Export
    private lateinit var gridContainer: LinearLayout
    private val PALETTE_CAPACITY = 18 // emplacements de la grille (6 colonnes × 3 rangées — 2026-08-19, demande explicite : "rajouter une ligne de 6 couleurs" ; plus de bouton Nouvelle palette pour vider d'un coup, cf. commit précédent)
    private val paletteState = MutableList<Int?>(PALETTE_CAPACITY) { i ->
        if (i < paletteColors.size) paletteColors[i] else null
    }
    private lateinit var gridLabel: TextView
    private var panelContentWidthPx = 0 // largeur réelle dispo dans le panneau — la grille de couleurs s'y cale
    private lateinit var oeuvresContainer: LinearLayout // liste des œuvres sauvegardées en interne
    private lateinit var melangeHint: TextView
    private var couleurCreee: Int? = null // couleur choisie au mélangeur, en attente de placement
    private val prefs by lazy { getSharedPreferences("encrebille", MODE_PRIVATE) }
    // 2026-09-01, demande explicite : tuto 4 étapes au tout premier lancement
    // (couleur d'écriture, choix de bille, agrandir bille/pinceau/gomme,
    // flèche) — rejouable depuis Paramètres (cf. buildParametresPanel).
    private var tutoShown = false
    private var tutoOverlay: FrameLayout? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    // 2026-09-08, bug réel confirmé par les logs (voir CHANGELOG) : le
    // mélangeur (hueSlider) vit dans un ConstraintLayout plein écran
    // (match_parent nécessaire à son propre calcul de hauteur, cf.
    // commentaires XML — jamais touché ici). Dès que ce ConstraintLayout
    // devient la cible tactile active (premier doigt posé n'importe où sur
    // le mélangeur), le mécanisme de split natif d'Android teste les
    // pointeurs suivants sur les RECTANGLES des enfants du FrameLayout
    // racine, PAS sur le widget réel qui a capté le geste — comme ce
    // rectangle (le ConstraintLayout) fait toute la taille de l'écran, TOUT
    // second doigt, où qu'il touche, correspond à ce rectangle déjà actif et
    // Android fusionne son id de pointeur dans le MÊME flux au lieu de
    // chercher plus bas jusqu'au canvas — confirmé par pointerCount=2 mesuré
    // côté hueSlider pour un doigt posé au centre de l'écran, canvas ne
    // recevant STRICTEMENT rien. Contournement : si le doigt qui a ouvert le
    // geste est sur le mélangeur, tout doigt suivant qui n'est PAS sur le
    // mélangeur est manuellement réinjecté vers `canvas` en plus du flux
    // fusionné existant (jamais à la place — hueSlider continue de lire
    // event.x/y, donc pointer 0 uniquement, la fusion ne le perturbe pas).
    private var firstPointerOnMixer = false
    private var canvasOrphanPointerId = -1

    private fun mixerVisible(): Boolean = ::hueSliderGroup.isInitialized && hueSliderGroup.visibility == View.VISIBLE

    // `ev.getX/getY` à ce niveau (Activity.dispatchTouchEvent) sont relatifs
    // à la fenêtre, pas à l'écran — converties ici via le decorView avant
    // toute comparaison à `View.getLocationOnScreen()` (coordonnées écran).
    private fun windowPointOnView(
        view: View,
        wx: Float,
        wy: Float,
    ): Boolean {
        val local = windowPointToViewLocal(view, wx, wy)
        return local[0] >= 0 && local[0] < view.width && local[1] >= 0 && local[1] < view.height
    }

    private fun windowPointToViewLocal(
        view: View,
        wx: Float,
        wy: Float,
    ): FloatArray {
        val decorLoc = IntArray(2)
        window.decorView.getLocationOnScreen(decorLoc)
        val viewLoc = IntArray(2)
        view.getLocationOnScreen(viewLoc)
        return floatArrayOf(wx + decorLoc[0] - viewLoc[0], wy + decorLoc[1] - viewLoc[1])
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                firstPointerOnMixer = mixerVisible() && windowPointOnView(hueSlider, ev.getX(0), ev.getY(0))
                canvasOrphanPointerId = -1
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                val x = ev.getX(idx)
                val y = ev.getY(idx)
                if (firstPointerOnMixer && canvasOrphanPointerId == -1 && !windowPointOnView(hueSlider, x, y)) {
                    val local = windowPointToViewLocal(canvas, x, y)
                    canvasOrphanPointerId = ev.getPointerId(idx)
                    val split =
                        MotionEvent.obtain(
                            ev.downTime,
                            ev.eventTime,
                            MotionEvent.ACTION_DOWN,
                            local[0],
                            local[1],
                            ev.metaState,
                        )
                    canvas.dispatchTouchEvent(split)
                    split.recycle()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (canvasOrphanPointerId != -1) {
                    val idx = ev.findPointerIndex(canvasOrphanPointerId)
                    if (idx >= 0) {
                        val local = windowPointToViewLocal(canvas, ev.getX(idx), ev.getY(idx))
                        val split =
                            MotionEvent.obtain(
                                ev.downTime,
                                ev.eventTime,
                                MotionEvent.ACTION_MOVE,
                                local[0],
                                local[1],
                                ev.metaState,
                            )
                        canvas.dispatchTouchEvent(split)
                        split.recycle()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = ev.actionIndex
                if (canvasOrphanPointerId != -1 && ev.getPointerId(idx) == canvasOrphanPointerId) {
                    val local = windowPointToViewLocal(canvas, ev.getX(idx), ev.getY(idx))
                    val split =
                        MotionEvent.obtain(
                            ev.downTime,
                            ev.eventTime,
                            MotionEvent.ACTION_UP,
                            local[0],
                            local[1],
                            ev.metaState,
                        )
                    canvas.dispatchTouchEvent(split)
                    split.recycle()
                    canvasOrphanPointerId = -1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                canvasOrphanPointerId = -1
                firstPointerOnMixer = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsageLog.init(this)
        // 2026-08-21, "plantage" signalé sans détail : logcat ne remonte rien
        // pour cette app tierce en environnement proot (déjà vérifié), donc
        // seul un handler dédié écrivant dans UsageLog donne la vraie trace
        // au lieu de re-deviner une 3e fois à l'aveugle.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                UsageLog.e("CRASH thread=${thread.name} : ${throwable.javaClass.name}: ${throwable.message}\n${Log.getStackTraceString(throwable)}")
            } catch (_: Exception) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        canvas = findViewById(R.id.canvas)
        rootLayout = findViewById(R.id.rootLayout)

        // Restauration des profils/billes depuis la sauvegarde externe
        // (2026-08-22, demande explicite : "si je dois delete et reinstaller
        // pour fixer un bug je perd [mes billes/profils], c'est pas bon") —
        // AVANT toute lecture de prefs : une install neuve (ou une
        // réinstallation après désinstallation, qui vide les SharedPreferences
        // au même titre) retrouve directement la bibliothèque de billes et
        // les profils sauvés au lieu de repartir des valeurs par défaut.
        restoreProfilsSiPremierLancement()

        // config retenue entre les sessions et les updates (SharedPreferences)
        wheelSizeDp = prefs.getFloat("wheelSizeDp", 190f)
        billeNamesVisible = prefs.getBoolean("billeNamesVisible", true)
        wheelPosX = prefs.getFloat("wheelPosX", -1f)
        wheelPosY = prefs.getFloat("wheelPosY", -1f)
        disabledTools.clear()
        disabledTools.addAll((prefs.getString("disabledTools", "") ?: "").split(",").filter { it.isNotBlank() })
        canvas.gravityToPx = prefs.getFloat("gravityToPx", 300f)
        canvas.smoothing = prefs.getFloat("smoothing", 0.5f)
        canvas.shakeThreshold = prefs.getFloat("shakeThreshold", 3.5f)
        canvas.shakeToVel = prefs.getFloat("shakeToVel", 800f)
        canvas.gravityDeadZone = prefs.getFloat("gravityDeadZone", 4f)
        canvas.trailWidthDp = prefs.getFloat("trailWidthDp", 26f) // indépendant de la taille de la bille
        canvas.ballRadiusDp = prefs.getFloat("ballRadiusDp", 34f)
        canvas.gommePeintureRadiusDp = prefs.getFloat("gommePeintureRadiusDp", 35f)
        canvas.gommeObstaclesRadiusDp = prefs.getFloat("gommeObstaclesRadiusDp", 35f)
        canvas.restitution = prefs.getFloat("restitution", 0.85f)
        canvas.poids = prefs.getFloat("poids", 1f)
        // BUG CORRIGÉ (2026-08-18, bord de trait ondulé — persistait malgré
        // 3 pistes de fix ET un vidage complet des données) : le défaut de
        // REPLI ici était 1f (ondulation max) alors que le champ lui-même
        // (BallCanvasView.textureAmount) a pour défaut 0f (« tracé net »).
        // Sur une install neuve, AUCUNE préférence n'existe encore — donc
        // getFloat retombait sur ce 1f et écrasait le 0f du code à CHAQUE
        // démarrage, indépendamment de toute valeur persistée d'avant.
        // C'est pour ça qu'un vidage cache+données+réinstall ne changeait
        // rien : ce n'est pas une vieille valeur qui survivait, c'est le
        // défaut de repli lui-même qui était faux.
        canvas.textureAmount = prefs.getFloat("textureAmount", 0f)
        // 2026-08-31 : fonduRate/mixVividMode/trailEdgeMode/cometTrail/
        // rainbowMode/speedColorMode ne se chargent plus ici — reconnectés
        // aux billes ("on peut reconnecter ça aux billes proprement"), donc
        // pilotés par loadBilleProfiles()/appliquerBilleProfile() plus bas,
        // UN SEUL chemin de chargement par réglage (plus de doublon global).
        canvas.vitesseEpaisseur = prefs.getBoolean("vitesseEpaisseur", false)
        canvas.tiltEffect = prefs.getFloat("tiltEffect", 1f)
        canvas.frictionRate = prefs.getFloat("frictionRate", 0.6f)
        canvas.boundsActive = prefs.getBoolean("boundsActive", true)
        canvas.wrapActive = prefs.getBoolean("wrapActive", false)
        canvas.ballVisible = prefs.getBoolean("ballVisible", true)
        canvas.ballGrabInPinceau = prefs.getBoolean("ballGrabInPinceau", false)
        tutoShown = prefs.getBoolean("tutoShown", false)
        tabbarScale = prefs.getFloat("tabbarScale", 1f)
        controlsEdgeMargin = prefs.getFloat("controlsEdgeMargin", 10f)
        controlsBottomMargin = prefs.getFloat("controlsBottomMargin", 74f)
        panelBottomMargin = prefs.getFloat("panelBottomMargin", 74f)
        canvas.rechargeProgressive = false
        canvas.showJauge = false
        canvas.selectedColor = prefs.getInt("selectedColor", paletteColors[1])
        canvas.bgColor = prefs.getInt("backgroundColor", 0xFFF7F3EC.toInt())
        val savedPalette = prefs.getString("paletteState", "")
        if (savedPalette.isNullOrBlank()) {
            // migration depuis l'ancienne « Ma palette » (v18/v19)
            prefs.getString("palettePerso", "")?.split(";")?.filter { it.isNotBlank() }?.forEachIndexed { k, s ->
                if (paletteColors.size + k < PALETTE_CAPACITY) paletteState[paletteColors.size + k] = s.toIntOrNull()
            }
        } else {
            savedPalette.split(";").forEachIndexed { i, s ->
                if (i < PALETTE_CAPACITY) {
                    // Format actuel : décimal signé (couleurs négatives acceptées),
                    // "x" pour vide. Anciens formats en migration : "-1" (vide) et
                    // "#RRGGBBAA" (hexa v61-v70 — reparsé en Long pour dépasser Int.MAX).
                    paletteState[i] = when {
                        s == "x" || s == "-1" -> null // emplacement vide
                        s.startsWith("#") -> s.removePrefix("#").toLongOrNull(16)?.toInt()
                        else -> s.toIntOrNull()
                    }
                }
            }
        }

        // repère de version
        versionLabel = findViewById(R.id.version_label)
        versionLabel.text = "v${BuildConfig.VERSION_CODE} · ${BuildConfig.VERSION_NAME}"

        // cluster de contrôles : bascule côté + masquage UI (toujours visibles)
        controlsCluster = findViewById(R.id.controlsCluster)
        tabbarView = findViewById(R.id.tabbar)
        // pivot ancré en haut-centre : le redimensionnement (slider "Taille
        // menu latéral") s'étend vers le bas (jamais dans btn_switch_side
        // juste au-dessus) tout en restant centré horizontalement (symétrique
        // à gauche comme à droite). Fixer pivotY explicitement AVANT le
        // premier layout fige aussi pivotX à sa valeur par défaut (0 = bord
        // gauche, pas le centre) — Android n'auto-recalcule plus aucun des
        // deux pivots dès qu'un seul est fixé à la main ; d'où le bouton
        // décentré et l'asymétrie gauche/droite (2026-08-13, régression du
        // 1er correctif). Il faut donc fixer aussi pivotX, une fois la
        // largeur réelle connue après le premier layout.
        tabbarView.scaleX = tabbarScale
        tabbarView.scaleY = tabbarScale
        tabbarView.post {
            tabbarView.pivotX = tabbarView.width / 2f
            tabbarView.pivotY = 0f
        }
        quickColorsContainer = findViewById(R.id.quickColors)
        quickColorsScrollView = findViewById(R.id.quickColorsScroll)

        // Réduction automatique des icônes fixes du tabbar sur écran court —
        // le fix ConstraintLayout (2026-09-03) garantit que controlsCluster
        // ne chevauche plus jamais topRow, mais sur un écran court (Huawei
        // P30 Lite), les 7 boutons fixes à 44dp ne laissaient presque plus
        // de place au ScrollView de couleurs (weight=1 réduit à ~1 pastille
        // tronquée, palette illisible) — demande explicite de Wian après
        // test réel : "probablement qu'il faut introduire une réduction en
        // fonction de la hauteur de l'écran". controlsCluster.height est
        // maintenant fiable dès le premier layout (contrainte
        // ConstraintLayout déterministe, contrairement à l'ancien
        // FrameLayout+gravity dont le measure dépendait du timing).
        // fixedOverheadDp = tout ce qui n'est PAS une icône réductible :
        // switch_side (36+6 marge) + padding tabbar (6+6) + 6 marges de 6dp
        // entre les 7 boutons fixes + marge avant/après le ScrollView (8+8).
        controlsCluster.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (controlsCluster.height == 0) return
                controlsCluster.viewTreeObserver.removeOnGlobalLayoutListener(this)
                recalculerTaillesTabbar()
            }
        })

        // Mode enfant : pastille d'aperçu + pavé dégradé — ajoutés juste
        // après dans le même parent (tabbar), les deux (quickColorsScroll /
        // hueSliderGroup) se togglent par visibilité. Reste étroit — seul le
        // pavé vit ici, la roue est séparée dans son propre panneau (cf.
        // wheelPanel ci-dessous, 2026-08-20, demande explicite : "il faut
        // separer la roue du menu faire son petit espace a elle avec un
        // fond noir"). Largeur alignée sur tabbarButtonSizeDp (44dp par
        // défaut, réduit sur écran court) plutôt qu'une constante fixe
        // indépendante — demande explicite (2026-09-04) : "même la largeur
        // de la barre de dégradé... cohérent avec la taille des autres
        // icônes" ; réajustée dynamiquement dans le bloc de réduction de
        // l'OnGlobalLayoutListener ci-dessus si l'écran est court.
        run {
            val density = resources.displayMetrics.density
            val groupSize = (tabbarButtonSizeDp * density).toInt()
            hueSliderGroup = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // Hauteur fixe 264dp — valeur d'origine, restaurée le
                // 2026-09-04 ("ce qu'il fallait faire c'est allonger la
                // taille du mélangeur des raccourcis pour kidorb"). Réduite
                // entre-temps (weight=1f jugé trop grand, puis 140dp jugé
                // trop petit) mais le vrai correctif attendu était bien
                // d'allonger LE MÉLANGEUR lui-même — pas seulement de fixer
                // l'étirement du tabbar autour de lui (measure() manuel,
                // OnGlobalLayoutListener de controlsCluster ci-dessus).
                // Aucun risque de débordement sur écran court : le measure()
                // plafonne déjà tabbarView à l'espace réellement disponible.
                layoutParams = LinearLayout.LayoutParams(groupSize, (264 * density).toInt()).apply {
                    topMargin = (8 * density).toInt()
                }
                visibility = View.GONE
            }
            // 2026-09-04, demande explicite : "en soi la pastille de
            // visualisation de ce qui est sélectionné a pas besoin d'être
            // aussi grande" — réduite de 40dp à 24dp (simple aperçu, pas un
            // contrôle interactif) ; libère mécaniquement de la place pour
            // le mélangeur en dessous sur écran court, puisqu'il reçoit
            // maintenant tout ce que l'overhead MESURÉ (icônes+ce
            // qui l'entoure) ne consomme pas (cf. calcul measure() dans
            // onCreate).
            huePreviewSwatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                    bottomMargin = (8 * density).toInt()
                }
                background = getDrawable(R.drawable.bg_swatch)
                backgroundTintList = ColorStateList.valueOf(canvas.selectedColor)
            }
            val onPick = { color: Int ->
                canvas.selectedColor = color
                huePreviewSwatch.backgroundTintList = ColorStateList.valueOf(color)
                wheelPreviewSwatch.backgroundTintList = ColorStateList.valueOf(color)
            }
            // clearActiveCarriedColor() UNE FOIS au début du geste (pas dans
            // onPick/onColorChange, appelé en CONTINU à chaque frame de
            // glissé sur le mélangeur/la roue/le joystick) — régression
            // trouvée par test réel sur les 2 appareils (2026-09-04) :
            // "quand la bille passe sur une couleur dessinée, elle ne se
            // charge plus". Effacer carriedColor à chaque frame pendant tout
            // un glissé empêchait le mécanisme de contact (samplePaintColor,
            // cf. BallCanvasView) de jamais se stabiliser — la bille ne
            // "voyait" plus jamais une couleur croisée comme distincte
            // puisque `ref` (carriedColor ?: selectedColor) suivait
            // `selectedColor` en changement permanent.
            val onPickStart = {
                retourPinceau?.invoke()
                canvas.clearActiveCarriedColor()
            }
            val onEnd = {
                saveSettings()
                UsageLog.d("mode enfant : couleur = #%06X".format(canvas.selectedColor and 0xFFFFFF))
            }
            hueSlider = HueSliderView(this).apply {
                layoutParams = LinearLayout.LayoutParams(groupSize, 0, 1f)
                this.onPickStart = onPickStart
                onColorChange = onPick
                onPickEnd = onEnd
            }
            hueSliderGroup.addView(huePreviewSwatch)
            hueSliderGroup.addView(hueSlider)
            tabbarView.addView(hueSliderGroup, tabbarView.indexOfChild(quickColorsScrollView) + 1)

            // roue : son propre espace, détaché de la barre, sous le menu de
            // raccourci (2026-08-20, demande explicite : "jaimerais avoir la
            // roue sous le menu de raccourci... le fond avzc le meme style
            // que lz reste de linterface" — même fond que les autres
            // panneaux/sous-menus, bg_palette, pas un noir plein dédié comme
            // la version précédente). Position calculée en direct par
            // rapport à tabbarView dans positionWheelPanel().
            wheelPanel = FrameLayout(this).apply {
                background = getDrawable(R.drawable.bg_palette)
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                visibility = View.GONE
            }
            val wheelCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            wheelPreviewSwatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply {
                    bottomMargin = (8 * density).toInt()
                }
                background = getDrawable(R.drawable.bg_swatch)
                backgroundTintList = ColorStateList.valueOf(canvas.selectedColor)
            }
            colorWheel = ColorWheelView(this).apply {
                layoutParams = LinearLayout.LayoutParams((wheelSizeDp * density).toInt(), (wheelSizeDp * density).toInt())
                this.onPickStart = onPickStart
                onColorChange = onPick
                onPickEnd = onEnd
            }
            joystickPalette = JoystickPaletteView(this).apply {
                layoutParams = LinearLayout.LayoutParams((wheelSizeDp * density).toInt(), (wheelSizeDp * density).toInt())
                this.onPickStart = onPickStart
                onColorChange = onPick
                onPickEnd = onEnd
            }
            wheelCol.addView(wheelPreviewSwatch)
            wheelCol.addView(colorWheel)
            wheelCol.addView(joystickPalette)
            wheelPanel.addView(wheelCol)
            rootLayout.addView(wheelPanel)
            positionWheelPanel()


            // glissé libre du panneau (2026-08-20, demande explicite : "je
            // veux aussi pouvoir deplacer cette roue ou je veux") — sur le
            // fond du panneau (pastille/roue gardent leur propre geste,
            // consommé avant de remonter ici). Position mémorisée (prefs).
            var dragStartRawX = 0f
            var dragStartRawY = 0f
            var dragStartLeft = 0f
            var dragStartTop = 0f
            wheelPanel.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartRawX = event.rawX
                        dragStartRawY = event.rawY
                        val loc = IntArray(2)
                        v.getLocationOnScreen(loc)
                        val rootLoc = IntArray(2)
                        rootLayout.getLocationOnScreen(rootLoc)
                        dragStartLeft = (loc[0] - rootLoc[0]).toFloat()
                        dragStartTop = (loc[1] - rootLoc[1]).toFloat()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newLeft = (dragStartLeft + (event.rawX - dragStartRawX))
                            .coerceIn(0f, (rootLayout.width - v.width).coerceAtLeast(0).toFloat())
                        val newTop = (dragStartTop + (event.rawY - dragStartRawY))
                            .coerceIn(0f, (rootLayout.height - v.height).coerceAtLeast(0).toFloat())
                        wheelPosX = newLeft / density
                        wheelPosY = newTop / density
                        v.layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.TOP or Gravity.START
                            leftMargin = newLeft.toInt()
                            topMargin = newTop.toInt()
                        }
                        v.requestLayout()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        saveSettings()
                        true
                    }
                    else -> false
                }
            }
        }

        val btnSwitchSide = findViewById<ImageView>(R.id.btn_switch_side)
        btnSwitchSide.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        btnSwitchSide.setOnClickListener {
            if (panelMenu.visibility == View.VISIBLE) closeMenus() // referme avant le déplacement (évite le saut de côté)
            controlsOnRight = !controlsOnRight
            applyControlsSide()
            saveSettings()
            UsageLog.d("cluster de contrôles → ${if (controlsOnRight) "droite" else "gauche"}")
        }
        btnHideUi = findViewById(R.id.btn_hide_ui)
        btnHideUi.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        btnHideUi.setOnClickListener { toggleHideUi() }

        // undo / redo : 5 niveaux en arrière + refaire
        btnUndo = findViewById(R.id.btn_undo)
        btnUndo.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        btnUndo.setOnClickListener { canvas.undo() }
        btnRedo = findViewById(R.id.btn_redo)
        btnRedo.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        btnRedo.setOnClickListener { canvas.redo() }

        // bouton pause : fixe en bas à droite de l'écran (hors barre et menu)
        pauseBtn = findViewById(R.id.pause_btn)
        pauseBtn.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        pauseBtn.elevation = UiStyle.dp(this, 3f).toFloat()
        pauseBtn.setOnClickListener { canvas.togglePause() }
        fun syncPauseIcon(paused: Boolean) {
            // l'icône montre l'action qu'un tap va déclencher, pas l'état
            // courant : en pause → ▶️ (reprendre) ; en lecture → ⏸️ (pauser)
            pauseBtn.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        }
        canvas.onPauseChanged = { paused -> syncPauseIcon(paused) }
        // BUG TROUVÉ (2026-08-30, rapporté : "la bille bouge et le bouton
        // est sur play, il devrait être sur pause") : onPauseChanged ne se
        // déclenche que sur un CHANGEMENT (togglePause/setPaused), jamais à
        // l'initialisation — le XML fixait l'icône de départ à ic_play,
        // resté figé depuis que l'app démarrait EN PAUSE ; depuis le
        // 2026-08-20 elle démarre en LECTURE par défaut sans que l'icône de
        // départ n'ait été mise à jour en conséquence.
        syncPauseIcon(canvas.isPaused())

        // barre minimale : réglages rapides par glissé (boule, pinceau) + menu
        btnBalle = findViewById(R.id.btn_balle)
        val btnPinceau = findViewById<ImageView>(R.id.btn_pinceau)
        btnBalle.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        btnPinceau.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        // glisser vers le HAUT = agrandir, vers le BAS = réduire — aperçu à la
        // taille réelle (2026-08-13 : Portail/Planète/Accélérateur ont leur
        // propre bouton "Effets" dédié maintenant, donc Balle retrouve son
        // glissé de taille d'origine, demande explicite : "on récupère le
        // bouton boule comme avant"). 2026-08-20 : le glissé cohabite
        // maintenant avec un appui long → sous-menu de variantes physiques.
        val submenuBalle = findViewById<LinearLayout>(R.id.submenu_balle)
        val submenuPinceauTaille = findViewById<LinearLayout>(R.id.submenu_pinceau_taille)
        // max relevé à 240dp (2026-08-20, demande explicite : "aussi gros
        // que la gomme") — la gomme va jusqu'à 120dp de RAYON (240dp de
        // diamètre, cf. ResizeSpec ci-dessous) ; le pinceau plafonnait à
        // 60dp de large, largement en dessous.
        attachDragResize(btnPinceau, getString(R.string.label_trail), 6f, 240f, { canvas.trailWidthDp }, { canvas.trailWidthDp = it }, apercu = { canvas.trailWidthDp })
        // 2026-08-20, demande explicite : "j'aimerais avoir le menu avec le
        // choix des billes sur un tap plutot que en lassant appuyer" — les 2
        // gestes s'échangent : tap = choix de bille, appui long = onglet
        // Balle. Câblage complet plus bas (cf. ouvrirSousMenuPres/
        // submenuObstacle etc., déclarés après ce point).
        // outils de construction : 1 bouton principal (tap = sous-menu,
        // cf. attachTapToggleMenu plus bas) + outil sélection + œil construction
        val btnObstacle = findViewById<ImageView>(R.id.btn_obstacle)
        val submenuObstacle = findViewById<LinearLayout>(R.id.submenu_obstacle)
        this.submenuObstacle = submenuObstacle
        val btnMur = findViewById<ImageView>(R.id.btn_mur)
        val btnBouchon = findViewById<ImageView>(R.id.btn_bouchon)
        val btnTriangle = findViewById<ImageView>(R.id.btn_triangle)
        val btnBezier = findViewById<ImageView>(R.id.btn_bezier)
        val btnTrampoline = findViewById<ImageView>(R.id.btn_trampoline)
        val btnRectangle = findViewById<ImageView>(R.id.btn_rectangle)
        val btnEffets = findViewById<ImageView>(R.id.btn_effets)
        val submenuEffets = findViewById<LinearLayout>(R.id.submenu_effets)
        val btnPortail = findViewById<ImageView>(R.id.btn_portail)
        val btnPlanete = findViewById<ImageView>(R.id.btn_planete)
        val btnPlaneteInverse = findViewById<ImageView>(R.id.btn_planete_inverse)
        val btnAccelerateur = findViewById<ImageView>(R.id.btn_accelerateur)
        val btnEllipse = findViewById<ImageView>(R.id.btn_ellipse)
        val btnSelect = findViewById<ImageView>(R.id.btn_select)
        val btnConstrEye = findViewById<ImageView>(R.id.btn_construction_eye)
        // 2026-08-21, demande explicite : "le bouton palette ne sert plus a
        // certain moment il faut aussi ajouter un toggle pour l'enlever du
        // mode kid" — déclaré ici (avant outilButtons ci-dessous) pour
        // pouvoir y participer comme n'importe quel autre outil désactivable.
        val btnPalette = findViewById<ImageView>(R.id.btn_palette)
        // gomme : bouton principal indépendant (tap = sous-menu
        // peinture/obstacles), même principe que le multi-outil obstacle
        val btnGomme = findViewById<ImageView>(R.id.btn_gomme)
        val submenuGomme = findViewById<LinearLayout>(R.id.submenu_gomme)
        val btnGommePeinture = findViewById<ImageView>(R.id.btn_gomme_peinture)
        val btnGommeObstacles = findViewById<ImageView>(R.id.btn_gomme_obstacles)

        // 2026-08-21, demande explicite : "quand on ouvre un menu des
        // raccourcis ca dois fermer celui deja actif si yen a un" — chaque
        // sous-menu fermait certains des autres à la main (liste ad hoc,
        // oubliée pour les 2 nouveaux sous-menus de tailles pinceau/gomme).
        // Une seule liste centrale : plus jamais deux sous-menus ouverts en
        // même temps, peu importe lequel s'ouvre.
        val tousLesSousMenus = listOf(submenuBalle, submenuObstacle, submenuEffets, submenuGomme, submenuPinceauTaille)
        fun closeAllSubmenus(except: LinearLayout? = null) {
            tousLesSousMenus.forEach { if (it !== except) it.visibility = View.GONE }
            // 2026-09-02 : quitte le mode gigote dès que le menu billes se
            // ferme (dessin lancé, autre bouton ouvert...) — sinon il
            // réapparaît figé en mode gigote à la prochaine ouverture.
            if (billeJiggling && submenuBalle !== except) {
                billeJiggling = false
                refreshBalleSubmenuCb?.invoke()
            }
        }

        // outils désactivables (2026-08-20, demande explicite) — masque
        // complètement le bouton (View.GONE, pas juste grisé) : le
        // sous-menu/la barre qui le contient récupère l'espace libéré
        // automatiquement (LinearLayout).
        val outilButtons = mapOf(
            "mur" to btnMur, "bouchon" to btnBouchon, "triangle" to btnTriangle,
            "bezier" to btnBezier, "trampoline" to btnTrampoline, "rectangle" to btnRectangle, "ellipse" to btnEllipse,
            "portail" to btnPortail, "planete" to btnPlanete,
            "planete_inverse" to btnPlaneteInverse, "accelerateur" to btnAccelerateur,
            "select" to btnSelect, "construction_eye" to btnConstrEye,
            "undo" to btnUndo, "redo" to btnRedo, "palette" to btnPalette,
        )
        // 2026-08-20, rapporté : "quand je decoche une categorie elle
        // disparait pas" — les outils de Formes/Effets vivent dans des
        // sous-menus déjà masqués par défaut (appui long seulement) :
        // cacher chaque outil un par un dedans ne se voit jamais tant que
        // le bouton principal (btnObstacle/btnEffets) reste affiché dans la
        // barre. Ce bouton doit aussi disparaître quand TOUTE sa catégorie
        // est désactivée (et revenir dès qu'un seul outil de la catégorie
        // est réactivé).
        val formesKeys = listOf("mur", "bouchon", "triangle", "bezier", "trampoline", "rectangle", "ellipse")
        val effetsKeys = listOf("portail", "planete", "planete_inverse", "accelerateur")
        fun applyToolVisibility() {
            outilButtons.forEach { (key, btn) ->
                btn.visibility = if (key in disabledTools) View.GONE else View.VISIBLE
            }
            btnObstacle.visibility = if (formesKeys.all { it in disabledTools }) View.GONE else View.VISIBLE
            btnEffets.visibility = if (effetsKeys.all { it in disabledTools }) View.GONE else View.VISIBLE
            // 2026-08-21, demande explicite : "la gomme onstacle doit aussi
            // disparaitre du menu si on enleve les obstacles" — plus aucun
            // outil de construction (formes ET effets) disponible → plus
            // rien à effacer avec la gomme obstacles.
            btnGommeObstacles.visibility = if (formesKeys.all { it in disabledTools } && effetsKeys.all { it in disabledTools }) View.GONE else View.VISIBLE
        }
        applyToolVisibility()
        refreshToolVisibilityCb = { applyToolVisibility() }

        fun refreshOutils() {
            val outil = canvas.outilObstacle
            val gommeObs = canvas.gommeObstacles
            val gommePeint = canvas.gommePeinture
            val sel = canvas.outilSelection
            btnObstacle.setImageResource(when {
                // outil actif → icône correspondante (formes seulement — les
                // effets ont leur propre bouton dédié, 2026-08-13)
                outil == 1 -> R.drawable.ic_crayon
                outil == 2 -> R.drawable.ic_bouchon
                outil == 3 -> R.drawable.ic_triangle
                outil == 4 -> R.drawable.ic_limite
                outil == 11 -> R.drawable.ic_trampoline
                outil == 5 -> R.drawable.ic_rectangle
                outil == 9 -> R.drawable.ic_ellipse
                // aucun outil de construction actif (pinceau libre ou outil
                // sélection) : l'icône reste sur le dernier outil choisi —
                // pinceau et flèche sont des détours, pas un choix d'un
                // « autre » outil ; ne redevient pinceau que si aucun outil
                // de construction n'a encore jamais été choisi. Ça ne dit pas
                // ce que le toucher fait là maintenant (ça, c'est
                // canvas.outilObstacle/outilSelection qui le déterminent,
                // correctement peu importe l'icône affichée).
                lastOutilsObstacle == 1 -> R.drawable.ic_crayon
                lastOutilsObstacle == 2 -> R.drawable.ic_bouchon
                lastOutilsObstacle == 3 -> R.drawable.ic_triangle
                lastOutilsObstacle == 4 -> R.drawable.ic_limite
                lastOutilsObstacle == 11 -> R.drawable.ic_trampoline
                lastOutilsObstacle == 5 -> R.drawable.ic_rectangle
                lastOutilsObstacle == 9 -> R.drawable.ic_ellipse
                else -> R.drawable.ic_pinceau
            })
            // bouton Effets : même principe, restreint à Portail/Planète/
            // Accélérateur/Anti-Planète (2026-08-13, demande explicite)
            btnEffets.setImageResource(when {
                outil == 6 -> R.drawable.ic_portail
                outil == 7 -> R.drawable.ic_planete
                outil == 8 -> R.drawable.ic_accelerateur
                outil == 10 -> R.drawable.ic_planete_inverse
                lastEffetUsed == 7 -> R.drawable.ic_planete
                lastEffetUsed == 8 -> R.drawable.ic_accelerateur
                lastEffetUsed == 10 -> R.drawable.ic_planete_inverse
                else -> R.drawable.ic_portail
            })
            // pas de « sel » ici : la flèche a son propre surlignage (btnSelect) ;
            // le bouton obstacle ne doit pas paraître actif quand seul l'outil
            // sélection est choisi
            btnObstacle.background = UiStyle.ripple(this, if (outil != 0 && outil != 6 && outil != 7 && outil != 8 && outil != 10) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            // bouton Effets : surligné quand un de ses 4 outils est actif
            // (2026-08-13, demande explicite — bouton dédié séparé des formes)
            btnEffets.background = UiStyle.ripple(this, if (outil == 6 || outil == 7 || outil == 8 || outil == 10) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            // même logique que btnObstacle : l'icône reflète le dernier type
            // de gomme choisi (peinture par défaut, jamais l'icône générique)
            btnGomme.setImageResource(
                if (gommeObs || (!gommePeint && lastGommeType == 2)) R.drawable.ic_obstacle
                else R.drawable.ic_gomme
            )
            btnGomme.background = UiStyle.ripple(this, if (gommePeint || gommeObs) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            // pinceau (dessin libre) : surligné quand aucun outil de construction/gomme/sélection n'est actif
            btnPinceau.background = UiStyle.ripple(this, if (outil == 0 && !gommeObs && !gommePeint && !sel) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnSelect.background = UiStyle.ripple(this, if (canvas.outilSelection) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnConstrEye.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
            btnConstrEye.setImageResource(if (canvas.constructionVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            // surbrillance des sous-boutons selon l'outil actif
        btnMur.background = UiStyle.ripple(this, if (canvas.outilObstacle == 1) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnBouchon.background = UiStyle.ripple(this, if (canvas.outilObstacle == 2) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnTriangle.background = UiStyle.ripple(this, if (canvas.outilObstacle == 3) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnBezier.background = UiStyle.ripple(this, if (canvas.outilObstacle == 4) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnTrampoline.background = UiStyle.ripple(this, if (canvas.outilObstacle == 11) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnRectangle.background = UiStyle.ripple(this, if (canvas.outilObstacle == 5) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnPortail.background = UiStyle.ripple(this, if (canvas.outilObstacle == 6) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnPlanete.background = UiStyle.ripple(this, if (canvas.outilObstacle == 7) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnAccelerateur.background = UiStyle.ripple(this, if (canvas.outilObstacle == 8) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnPlaneteInverse.background = UiStyle.ripple(this, if (canvas.outilObstacle == 10) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnEllipse.background = UiStyle.ripple(this, if (canvas.outilObstacle == 9) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnGommePeinture.background = UiStyle.ripple(this, if (gommePeint) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
            btnGommeObstacles.background = UiStyle.ripple(this, if (gommeObs) R.drawable.bg_round_btn_sel else R.drawable.bg_round_btn)
        }

        fun choisirOutil(mode: Int) {
            canvas.gommeObstacles = false
            canvas.gommePeinture = false
            canvas.outilSelection = false
            canvas.outilObstacle = mode
            // 2026-08-13 : les effets (6/7/8/10) ont leur propre bouton dédié —
            // ne doivent plus mettre à jour lastOutilsObstacle (sinon le
            // bouton Formes afficherait à tort une icône d'effet).
            if (mode in 6..8 || mode == 10) lastEffetUsed = mode else lastOutilsObstacle = mode
            closeAllSubmenus()
            refreshOutils()
            UsageLog.d("outil obstacle = ${canvas.outilObstacle}")
        }
        // retour au dessin libre (pinceau) — ne touche PAS lastOutilsObstacle
        // (contrairement à choisirOutil), pour que le bouton obstacle
        // retrouve le dernier outil de construction utilisé au prochain tap.
        fun activerPinceau() {
            canvas.outilObstacle = 0
            canvas.gommeObstacles = false
            canvas.gommePeinture = false
            canvas.outilSelection = false
            closeAllSubmenus()
            refreshOutils()
        }
        retourPinceau = { activerPinceau() }

        // positionne un sous-menu (obstacle ou gomme) juste à côté de son
        // bouton, du côté OPPOSÉ de la barre — même principe pour les deux
        // gapDp (2026-08-20, demande explicite : "faut eloignzr un peu le
        // menu des raccourcis" pour le menu billes) — écart supplémentaire
        // entre le bouton et le sous-menu, 0 par défaut pour ne rien
        // changer aux autres sous-menus (obstacle/effets/gomme, collés
        // comme avant).
        fun ouvrirSousMenuPres(bouton: ImageView, submenu: LinearLayout, gapDp: Float = 0f) {
            val btnLoc = IntArray(2)
            bouton.getLocationOnScreen(btnLoc)
            val rootLoc = IntArray(2)
            rootLayout.getLocationOnScreen(rootLoc)
            val btnLeft = btnLoc[0] - rootLoc[0]
            val btnRight = btnLeft + bouton.width
            val btnCY = btnLoc[1] - rootLoc[1] + bouton.height / 2
            val gapPx = UiStyle.dp(this@MainActivity, gapDp)
            val subLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = btnCY - UiStyle.dp(this@MainActivity, 18f)
                if (controlsOnRight) {
                    gravity = Gravity.TOP or Gravity.END
                    rightMargin = rootLayout.width - btnLeft + gapPx
                } else {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = btnRight + gapPx
                }
            }
            submenu.layoutParams = subLp
            submenu.visibility = View.VISIBLE
            refreshOutils()
        }

        // Menu à ouverture par simple tap (2026-08-30, demande explicite :
        // "changer le type de menu pour la selection des obstacles... s'ouvre
        // avec un tap sur l'icone, et qu'il reste ouvert jusque au moment ou
        // tap sur celui que je veux", étendue le même jour à btnEffets
        // ("même chose pour effets aussi") — remplace l'appui long d'origine
        // comme SEULE façon d'ouvrir, pour les 3 boutons à sous-menu
        // (obstacle/gomme/effets) : plus facile d'accès pour un enfant qu'un
        // délai de long-press. Complété le même jour ("si on laisse appuyer
        // et que ça s'ouvre ça peut être complémentaire non ?") : l'ancien
        // geste continu (maintenir ouvre, glisser SANS relâcher survole,
        // relâcher sélectionne) reste disponible en plus du tap — les deux
        // cohabitent sur le même bouton, désambiguïsés par la durée de
        // l'appui, comme avant le 2026-08-30. Le réglage de taille en
        // glissant DIRECTEMENT dessus (gomme) reste géré ici, inchangé
        // (déclenché seulement si le sous-menu n'était pas déjà ouvert).
        class ResizeSpec(
            val label: String, val minVal: Float, val maxVal: Float,
            val get: () -> Float, val set: (Float) -> Unit, val apercu: () -> Float,
        )
        fun hitTestOption(options: List<Pair<ImageView, () -> Unit>>, rawX: Float, rawY: Float): ImageView? {
            val loc = IntArray(2)
            for ((opt, _) in options) {
                if (opt.visibility != View.VISIBLE) continue
                opt.getLocationOnScreen(loc)
                if (rawX >= loc[0] && rawX <= loc[0] + opt.width && rawY >= loc[1] && rawY <= loc[1] + opt.height) return opt
            }
            return null
        }
        fun attachTapToggleMenu(
            bouton: ImageView,
            submenu: LinearLayout,
            options: List<Pair<ImageView, () -> Unit>>,
            resize: ResizeSpec? = null,
            // isLongPress : distingue tap simple (2026-09-04, "le premier
            // tap active la catégorie et le 2e tap ouvre le menu") d'appui
            // long, qui doit TOUJOURS ouvrir le sous-menu (geste de survol/
            // sélection existant, jamais une activation directe).
            onToggle: (isLongPress: Boolean) -> Unit,
        ) {
            val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
            val slopPx = ViewConfiguration.get(this).scaledTouchSlop
            val density = resources.displayMetrics.density
            val dragSpanDp = 160f
            val handler = Handler(Looper.getMainLooper())
            var openRunnable: Runnable? = null
            var wasAlreadyOpen = false
            var longPressOpened = false
            var highlighted: ImageView? = null
            var resizing = false
            var startRawX = 0f
            var startRawY = 0f
            var startVal = 0f

            bouton.setOnClickListener { onToggle(false) }

            bouton.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        resizing = false
                        longPressOpened = false
                        highlighted = null
                        startRawX = event.rawX
                        startRawY = event.rawY
                        if (resize != null) startVal = resize.get()
                        wasAlreadyOpen = submenu.visibility == View.VISIBLE
                        // long-press = geste complémentaire, seulement si le
                        // menu était fermé (sinon un tap court le referme).
                        val r = Runnable {
                            if (!wasAlreadyOpen) {
                                longPressOpened = true
                                onToggle(true)
                            }
                        }
                        openRunnable = r
                        handler.postDelayed(r, longPressMs)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (resize != null && !resizing && !wasAlreadyOpen && !longPressOpened) {
                            val dxPx = event.rawX - startRawX
                            val dyPx = event.rawY - startRawY
                            if (abs(dxPx) > slopPx || abs(dyPx) > slopPx) {
                                resizing = true
                                openRunnable?.let { handler.removeCallbacks(it) }
                                showDragBadge("${resize.label} ${formatValue(startVal)}", (resize.apercu() * density).toInt())
                            }
                        }
                        if (resizing && resize != null) {
                            val deltaDp = (event.rawY - startRawY) / density
                            val newVal = (startVal - deltaDp / dragSpanDp * (resize.maxVal - resize.minVal)).coerceIn(resize.minVal, resize.maxVal)
                            resize.set(newVal)
                            showDragBadge("${resize.label} ${formatValue(newVal)}", (resize.apercu() * density).toInt())
                        } else if (longPressOpened) {
                            val hit = hitTestOption(options, event.rawX, event.rawY)
                            if (hit != highlighted) {
                                refreshOutils()
                                hit?.background = UiStyle.ripple(v.context, R.drawable.bg_round_btn_sel)
                                highlighted = hit
                            }
                            if (hit != null) showHoverBadge(hit.drawable, event.rawX, event.rawY, hit.contentDescription?.toString())
                            else hideHoverBadge()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        openRunnable?.let { handler.removeCallbacks(it) }
                        if (resizing) {
                            saveSettings()
                            hideDragBadge()
                        } else if (longPressOpened) {
                            hideHoverBadge()
                            val hit = highlighted
                            highlighted = null
                            submenu.visibility = View.GONE
                            if (hit != null) options.firstOrNull { it.first == hit }?.second?.invoke()
                            else refreshOutils()
                        } else {
                            v.performClick() // pas de long-press déclenché : tap court normal
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        openRunnable?.let { handler.removeCallbacks(it) }
                        if (resizing) {
                            hideDragBadge()
                        } else if (longPressOpened) {
                            hideHoverBadge()
                            highlighted = null
                            submenu.visibility = View.GONE
                            refreshOutils()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        // Sélection dans un sous-menu déjà ouvert (2026-08-30, même demande) :
        // un tap franc sur une option (down+up sans déplacement notable) la
        // sélectionne directement ; un glissé SANS relâcher, démarré sur
        // n'importe quelle option, surligne celle survolée (même badge
        // qu'avant) et sélectionne celle relâchée en dernier ; relâcher hors
        // de toute option referme juste le sous-menu. Couvre le cas où le
        // menu était DÉJÀ ouvert (tap précédent) et qu'on démarre un
        // nouveau geste directement sur une option — attachTapToggleMenu
        // ci-dessus ne couvre que les gestes démarrés sur le bouton
        // PRINCIPAL.
        fun attachMenuItemHoverSelect(submenu: LinearLayout, options: List<Pair<ImageView, () -> Unit>>) {
            var highlighted: ImageView? = null
            options.forEach { (opt, _) ->
                opt.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            highlighted = opt
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val hit = hitTestOption(options, event.rawX, event.rawY)
                            if (hit != highlighted) {
                                refreshOutils()
                                hit?.background = UiStyle.ripple(v.context, R.drawable.bg_round_btn_sel)
                                highlighted = hit
                            }
                            if (hit != null) showHoverBadge(hit.drawable, event.rawX, event.rawY, hit.contentDescription?.toString())
                            else hideHoverBadge()
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            hideHoverBadge()
                            val hit = highlighted
                            highlighted = null
                            if (hit != null) options.firstOrNull { it.first == hit }?.second?.invoke()
                            else {
                                submenu.visibility = View.GONE
                                refreshOutils()
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            hideHoverBadge()
                            highlighted = null
                            submenu.visibility = View.GONE
                            refreshOutils()
                            true
                        }
                        else -> false
                    }
                }
            }
        }

        val optionsObstacle = listOf(
            btnMur to { choisirOutil(1) },
            btnBouchon to { choisirOutil(2) },
            btnTriangle to { choisirOutil(3) },
            btnBezier to { choisirOutil(4) },
            btnTrampoline to { choisirOutil(11) },
            btnRectangle to { choisirOutil(5) },
            btnEllipse to { choisirOutil(9) },
        )
        attachTapToggleMenu(btnObstacle, submenuObstacle, optionsObstacle) { isLongPress ->
            // 2026-09-04, demande explicite : "le premier tap [active] la
            // catégorie et le 2e tap ouvre le menu pour choisir lequel" —
            // 1er tap (catégorie Formes pas déjà active) = active direct la
            // dernière forme utilisée, sans ouvrir le sous-menu ; 2e tap
            // (déjà sur une forme) = ouvre le sous-menu pour en changer.
            // L'appui long ouvre TOUJOURS le sous-menu (geste de survol
            // existant, inchangé). Même principe appliqué à Effets et
            // Gomme ci-dessous.
            val obstacleDejaActif = canvas.outilObstacle != 0 && canvas.outilObstacle !in 6..8 && canvas.outilObstacle != 10
            if (submenuObstacle.visibility == View.VISIBLE) {
                submenuObstacle.visibility = View.GONE
            } else if (isLongPress || obstacleDejaActif) {
                closeAllSubmenus()
                ouvrirSousMenuPres(btnObstacle, submenuObstacle)
            } else {
                choisirOutil(lastOutilsObstacle)
            }
        }
        attachMenuItemHoverSelect(submenuObstacle, optionsObstacle)

        // bille : bibliothèque de billes créées dans l'onglet Bille Setup
        // (2026-08-20, demande explicite : "un menu bille... quand on tap
        // dessu, ca ouvre une liste d'outil... des billes differente", puis
        // "je veux ... avoir tout les reglages actuels mais par billes" —
        // plus de 3 variantes fixes, contenu regénéré à chaque changement de
        // la bibliothèque). 2026-08-20, précisé ensuite : "le menu avec le
        // choix des billes sur un tap plutot que en lassant appuyer" — tap
        // court = ouvre/ferme le choix de bille, appui long = onglet Balle
        // (réglages physiques, ancien comportement du tap), glissé =
        // redimensionnement — les 3 gestes coexistent (même principe que
        // btnObstacle/btnGomme), plus besoin de la mécanique hold-drag-
        // select puisque chaque bouton de la liste a déjà son propre
        // setOnClickListener (simple tap dessus, la liste reste ouverte
        // jusqu'à un choix).
        // synchronise btnBalle (icône) + canvas (couleur/physique) avec la
        // bille active — loadBilleProfiles() appelle appliquerBilleProfile()
        // en interne depuis le 2026-08-29 (cf. son commentaire), plus besoin
        // de le refaire ici séparément.
        loadBilleProfiles()
        loadPinceauProfiles()
        // BUG TROUVÉ (2026-08-24, "le seul pinceau visible ne mélange pas
        // les couleurs en principe et pourtant le fait") : loadPinceauProfiles
        // relit bien pinceauActiveIndex + la liste, mais rien ne pousse ce
        // profil vers le canvas au démarrage — contrairement à la bille,
        // dont les champs (fonduRate/textureAmount/...) sont aussi lus
        // directement depuis des clés plates ci-dessus (canvas.fonduRate
        // etc.), le pinceau n'a QUE appliquerPinceauProfile() comme chemin
        // d'écriture vers canvas.pinceauMelangeActif/pinceauFonduRate/
        // pinceauTextureAmount — jamais appelé au lancement, donc ces 3
        // champs restaient bloqués sur leur défaut codé en dur (true/0.4/0)
        // à chaque démarrage, quel que soit le pinceau actif sauvegardé.
        appliquerPinceauProfile(pinceauActiveIndex.takeIf { it in pinceauProfiles.indices } ?: 0)
        // 2026-08-20, demande explicite : "les billez c'ezr vraiment
        // lisible et lezpace entre lez billez est trop grand... un grand
        // menu comme les onglets qui souvre et en fonction du nombre de
        // bille il s'aggrandi" — un seul fond commun (comme les panneaux à
        // onglets, plus le fond par-case) qui GRANDIT avec le nombre de
        // billes : grille qui s'enroule sur plusieurs rangées de
        // COLS_BILLES colonnes au lieu d'une rangée horizontale qui
        // s'espace/déborde. Cases collées (pas de marge visible entre
        // elles), lisible sur le fond opaque du panneau.
        // 2026-09-02 : animateurs du mode gigote (cf. refreshBalleSubmenu) —
        // annulés explicitement à chaque reconstruction, sinon ils continuent
        // de tourner sur des vues déjà détachées (removeAllViews ne les
        // arrête pas tout seul).
        val jiggleAnimators = mutableListOf<ObjectAnimator>()
        fun refreshBalleSubmenu() {
            submenuBalle.removeAllViews()
            jiggleAnimators.forEach { it.cancel() }
            jiggleAnimators.clear()
            val colsBilles = 3
            var row: LinearLayout? = null
            fun newRowIfNeeded(i: Int) {
                if (i % colsBilles == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    submenuBalle.addView(row)
                }
            }
            billeProfiles.forEachIndexed { i, p ->
                newRowIfNeeded(i)
                // 2026-08-25, essayé puis retiré sur retour explicite : "le
                // surlignage de la balle active n'a pas beaucoup de sens vu
                // qu'on la voit comme sélectionnée dans le menu" — retour au
                // comportement d'origine, aucune cellule surlignée ici.
                // 2026-09-02, demande explicite : en mode gigote, le tap
                // ouvre l'édition de CETTE bille (menu simple, séparé de
                // l'éditeur avancé) au lieu de l'appliquer au canvas.
                val cell = buildBilleCell(p, highlighted = false) {
                    if (billeJiggling) {
                        showBilleQuickEditor(i)
                    } else {
                        appliquerBilleProfile(i)
                        submenuBalle.visibility = View.GONE
                    }
                }
                // appui long : entre en mode gigote (croix rouge de
                // suppression sur chaque pastille non protégée, cf. plus bas)
                // — 2026-09-02, demande explicite : "ça doit être d'abord sur
                // InkOrb, ça viendra peut-être sur KidOrb plus tard" —
                // billeJiggling ne passe jamais à true côté kid (pas de
                // listener), donc tout ce qui en dépend plus bas (croix,
                // gigote, +) reste inatteignable sans y toucher séparément.
                if (BuildConfig.ADVANCED_EDITION) {
                    cell.setOnLongClickListener {
                        if (!billeJiggling) { billeJiggling = true; refreshBalleSubmenu() }
                        true
                    }
                }
                val wrapper = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                wrapper.addView(cell)
                // 2026-09-02, demande explicite : "sauf les 7 premières qui
                // ne doivent pas vibrer" — la gigote elle-même (pas
                // seulement une croix de suppression, retirée d'ici et
                // déplacée dans showBilleQuickEditor : "je préfère qu'il
                // soit dans le menu d'édition... comme ça pas d'erreur
                // possible") signale maintenant "supprimable" ; une bille
                // protégée (cf. BilleProfile.isDefault) reste immobile mais
                // s'édite toujours normalement au tap.
                if (billeJiggling && !p.isDefault) {
                    val anim = ObjectAnimator.ofFloat(cell, "rotation", -3f, 3f).apply {
                        duration = 120
                        repeatMode = ObjectAnimator.REVERSE
                        repeatCount = ObjectAnimator.INFINITE
                        startDelay = (i % 3) * 40L // léger déphasage, moins mécanique que toutes synchrones
                    }
                    anim.start()
                    jiggleAnimators.add(anim)
                }
                row!!.addView(wrapper)
            }
            // 2026-09-02, demande explicite : "+" pour créer une bille
            // facilement — menu simplifié (showBilleQuickEditor), séparé de
            // l'éditeur avancé de l'onglet Billes ("on va garder séparer les
            // 2 menus"). Masqué en mode gigote (pas d'ajout pendant qu'on
            // supprime/édite). Réservé à InkOrb pour l'instant ("ça viendra
            // peut-être sur KidOrb plus tard").
            if (BuildConfig.ADVANCED_EDITION && !billeJiggling) {
                newRowIfNeeded(billeProfiles.size)
                val addBtn = UiStyle.glyphButton(this, "+", sizeDp = 32f, muted = true)
                addBtn.setOnClickListener { showBilleQuickEditor(null) }
                row!!.addView(addBtn)
            }
        }
        refreshBalleSubmenu()
        refreshBalleSubmenuCb = { refreshBalleSubmenu() }
        btnBalle.setOnClickListener {
            if (submenuBalle.visibility == View.VISIBLE) {
                submenuBalle.visibility = View.GONE
                // 2026-09-02 : referme aussi le mode gigote (sinon il
                // réapparaît figé à la prochaine ouverture, cf. closeAllSubmenus)
                if (billeJiggling) { billeJiggling = false; refreshBalleSubmenu() }
            } else {
                closeAllSubmenus()
                ouvrirSousMenuPres(btnBalle, submenuBalle, gapDp = 14f)
            }
        }
        // 2026-08-21, demande explicite : "le menu quand on laisse appuyer
        // le raccourci bille ne sert a rien, a partir de maintenant on
        // utilise que les billes presente dans les raccourcis" — l'ancien
        // onglet "Balle" (réglages d'UNE seule bille globale) est
        // superflu depuis l'onglet Billes (profils multiples, cf.
        // buildBilleProfileEditor) : plus d'appui long pour y accéder.
        attachDragResize(
            btnBalle, getString(R.string.label_ball), 1f, 90f,
            { canvas.ballRadiusDp }, { canvas.ballRadiusDp = it }, apercu = { canvas.ballRadiusDp * 2f },
        )

        // 2026-08-13, demande explicite : "un autre bouton dans le menu pour
        // les effets : planète, accélérateur et portail. et comme ça on
        // récupère le bouton boule comme avant" — bouton dédié séparé de
        // Balle. 2026-08-30, demande explicite ("même chose pour effets
        // aussi") : même conversion tap-toggle + hover-select que btnObstacle
        // et btnGomme (cf. attachTapToggleMenu/attachMenuItemHoverSelect
        // plus haut) — remplace l'appui long d'origine, plus facile d'accès.
        val optionsEffets = listOf(
            btnPortail to { choisirOutil(6) },
            btnPlanete to { choisirOutil(7) },
            btnAccelerateur to { choisirOutil(8) },
            btnPlaneteInverse to { choisirOutil(10) },
        )
        attachTapToggleMenu(btnEffets, submenuEffets, optionsEffets) { isLongPress ->
            // même principe que btnObstacle plus haut (2026-09-04, demande
            // explicite : "si je veux juste tap sur l'outil effet planète...
            // le premier tap [active] la catégorie et le 2e tap ouvre le
            // menu pour choisir lequel")
            val effetDejaActif = canvas.outilObstacle in 6..8 || canvas.outilObstacle == 10
            if (submenuEffets.visibility == View.VISIBLE) {
                submenuEffets.visibility = View.GONE
            } else if (isLongPress || effetDejaActif) {
                closeAllSubmenus()
                ouvrirSousMenuPres(btnEffets, submenuEffets)
            } else {
                choisirOutil(lastEffetUsed)
            }
        }
        attachMenuItemHoverSelect(submenuEffets, optionsEffets)
        // 2026-08-21, demande explicite : "pour le pinceau il faudrait que
        // quand on tap dessus alors qu'il est deja selectionner ca, ouvre un
        // menu... avec dedant des tailles predefinie... on peut regler plus
        // precisement avec le mode precedent" — complément rapide au glissé
        // précis (attachDragResize ci-dessus, inchangé), pas un remplacement.
        val pinceauTaillePresets = listOf(
            12f to getString(R.string.preset_taille_fin),
            30f to getString(R.string.preset_taille_moyen),
            60f to getString(R.string.preset_taille_epais),
            120f to getString(R.string.preset_taille_tres_epais),
        )
        // 2026-08-22, demande explicite : "je ne vois pas les pinceaux dans
        // l'interface kid" — la bille a son sélecteur rapide (submenuBalle,
        // tap sur le bouton Balle), le pinceau n'en avait aucun : seul
        // moyen de choisir un pinceau était l'onglet complet Pinceaux
        // (hamburger), pas praticable pendant qu'on dessine en mode enfant.
        // Rangée de pastilles ajoutée EN PLUS des tailles prédéfinies
        // (inchangées ci-dessous), dans le même sous-menu déjà existant —
        // pas de nouveau geste à apprendre. Masqué si un seul pinceau
        // visible (rien à choisir), respecte les pinceaux désactivés en
        // Mode kid (cf. section "Pinceaux" de Paramètres).
        fun refreshPinceauTailleSubmenu() {
            submenuPinceauTaille.removeAllViews()
            val visibles = pinceauProfiles.withIndex().filter { (_, p) -> "pinceau:${p.nom}" !in disabledTools }
            if (visibles.size > 1) {
                val pinceauRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                visibles.forEach { (i, p) ->
                    val cell = buildPinceauCell(p, highlighted = i == pinceauActiveIndex) {
                        appliquerPinceauProfile(i)
                        savePinceauProfiles()
                        submenuPinceauTaille.visibility = View.GONE
                    }
                    pinceauRow.addView(cell)
                }
                submenuPinceauTaille.addView(pinceauRow)
            }
            val tailleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pinceauTaillePresets.forEach { (dp, label) ->
                val cell = buildTailleCell(dp, label, selected = canvas.trailWidthDp == dp) {
                    canvas.trailWidthDp = dp
                    saveSettings()
                    UsageLog.d("pinceau taille (preset) = $dp")
                    submenuPinceauTaille.visibility = View.GONE
                }
                tailleRow.addView(cell)
            }
            submenuPinceauTaille.addView(tailleRow)
        }
        fun pinceauActif() = canvas.outilObstacle == 0 && !canvas.gommeObstacles && !canvas.gommePeinture && !canvas.outilSelection
        // tap sur le pinceau (barre du haut) → retour au dessin libre (seul
        // moyen de sortir d'un outil de construction/gomme/sélection actif) ;
        // tap alors qu'il est DÉJÀ actif → sous-menu des tailles prédéfinies.
        btnPinceau.setOnClickListener {
            closeMenus()
            if (pinceauActif()) {
                if (submenuPinceauTaille.visibility == View.VISIBLE) {
                    submenuPinceauTaille.visibility = View.GONE
                } else {
                    closeAllSubmenus()
                    refreshPinceauTailleSubmenu()
                    ouvrirSousMenuPres(btnPinceau, submenuPinceauTaille)
                }
            } else {
                activerPinceau()
            }
        }
        // btnMur/btnBouchon/btnTriangle/btnBezier/btnTrampoline/btnRectangle/
        // btnEllipse : plus de setOnClickListener individuel, couverts par
        // attachMenuItemHoverSelect(submenuObstacle, ...) ci-dessus.
        // btnPortail/btnPlanete/btnAccelerateur/btnPlaneteInverse : idem,
        // couverts par attachMenuItemHoverSelect(submenuEffets, ...).

        // gomme : 2 outils séparés (peinture / obstacles) sous le même
        // sous-menu — la 3ᵉ variante "obstacles locale" (2026-08-10) a
        // fusionné avec "obstacles" tout court (2026-08-11, demande
        // explicite : la gomme obstacles est TOUJOURS locale maintenant,
        // cf. BallCanvasView.gommeObstacles).
        fun choisirGomme(type: Int) {
            canvas.outilObstacle = 0
            canvas.outilSelection = false
            canvas.gommePeinture = type == 1
            canvas.gommeObstacles = type == 2
            lastGommeType = type
            closeAllSubmenus()
            refreshOutils()
            UsageLog.d("gomme = $type")
        }
        // 2026-08-30, demande explicite (même chantier que btnObstacle plus
        // haut, "pareil pour la gomme aussi") : tap ouvre directement le
        // menu de type (peinture/obstacles), même mécanique tap-toggle +
        // hover-select que btnObstacle. Le sous-menu des tailles
        // prédéfinies (accessible avant par un tap quand la gomme était
        // déjà active) est retiré sur choix explicite de Wian — le réglage
        // de taille reste possible uniquement par le glissé direct sur
        // l'icône (ResizeSpec ci-dessous, inchangé).
        val optionsGomme = listOf(
            btnGommePeinture to { choisirGomme(1) },
            btnGommeObstacles to { choisirGomme(2) },
        )
        attachTapToggleMenu(
            btnGomme, submenuGomme, optionsGomme,
            resize = ResizeSpec(
                getString(R.string.label_eraser), 8f, 120f,
                get = { if (canvas.gommeObstacles) canvas.gommeObstaclesRadiusDp else canvas.gommePeintureRadiusDp },
                set = { if (canvas.gommeObstacles) canvas.gommeObstaclesRadiusDp = it else canvas.gommePeintureRadiusDp = it },
                apercu = { (if (canvas.gommeObstacles) canvas.gommeObstaclesRadiusDp else canvas.gommePeintureRadiusDp) * 2f },
            ),
        ) { isLongPress ->
            // même principe que btnObstacle plus haut (2026-09-04)
            val gommeDejaActive = canvas.gommePeinture || canvas.gommeObstacles
            if (submenuGomme.visibility == View.VISIBLE) {
                submenuGomme.visibility = View.GONE
            } else if (isLongPress || gommeDejaActive) {
                closeAllSubmenus()
                ouvrirSousMenuPres(btnGomme, submenuGomme)
            } else {
                choisirGomme(lastGommeType)
            }
        }
        attachMenuItemHoverSelect(submenuGomme, optionsGomme)

        btnSelect.setOnClickListener {
            closeMenus()
            if (!canvas.outilSelection) {
                // entrée : mémorise l'état exact pour le restaurer à la sortie
                preSelectOutil = canvas.outilObstacle
                preSelectGommeType = when {
                    canvas.gommePeinture -> 1
                    canvas.gommeObstacles -> 2
                    else -> 0
                }
                canvas.outilObstacle = 0
                canvas.gommeObstacles = false
                canvas.gommePeinture = false
                canvas.outilSelection = true
            } else {
                canvas.outilSelection = false
                // sortie : restaure exactement ce qui était actif avant —
                // sinon le dessin retombe en pinceau libre sans que ce soit
                // affiché (tracés parasites)
                canvas.gommePeinture = preSelectGommeType == 1
                canvas.gommeObstacles = preSelectGommeType == 2
                if (preSelectGommeType == 0) canvas.outilObstacle = preSelectOutil
            }
            closeAllSubmenus()
            refreshOutils()
            UsageLog.d("outil selection = ${canvas.outilSelection}")
        }
        // tap = montrer/masquer les obstacles ; appui long (~5s) = tout effacer
        val eyeHoldHandler = Handler(Looper.getMainLooper())
        var eyeHoldFired = false
        val eyeHoldRunnable = Runnable {
            eyeHoldFired = true
            canvas.clearObstacles()
            Toast.makeText(this, R.string.toast_obstacles_cleared, Toast.LENGTH_SHORT).show()
            UsageLog.d("obstacles effacés (appui long œil 5s)")
        }
        btnConstrEye.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    eyeHoldFired = false
                    eyeHoldHandler.postDelayed(eyeHoldRunnable, 5000)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    eyeHoldHandler.removeCallbacks(eyeHoldRunnable)
                    if (!eyeHoldFired) {
                        closeMenus()
                        canvas.constructionVisible = !canvas.constructionVisible
                        refreshOutils()
                        UsageLog.d("construction visible = ${canvas.constructionVisible}")
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    eyeHoldHandler.removeCallbacks(eyeHoldRunnable)
                    true
                }
                else -> false
            }
        }
        // état initial des icônes/surlignages — sinon btnObstacle reste sur
        // l'icône générique (triangle+cercle) de la XML jusqu'au premier
        // tap sur un outil (icône « parasite » au tout premier lancement)
        refreshOutils()
        // Palette : tap = ouvre/ferme l'onglet Palette du menu (2026-08-11,
        // demande explicite ; toggle ajouté suite au retour : "si la
        // palette est déjà ouverte et qu'on retape sur le bouton, faut le
        // fermer proprement").
        btnPalette.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        // 2026-08-19 : même bascule que Balle ci-dessus — re-taper Palette
        // pendant que Balle est ouverte doit y basculer, pas fermer le menu.
        btnPalette.setOnClickListener {
            if (panelMenu.visibility == View.VISIBLE && ongletCourant == 1) closeMenus() else openMenu(1)
        }

        // bouton menu : sorti de la barre, fixé en haut à gauche de l'écran
        // (2026-08-11, demande explicite) — ouvre le panneau sur le dernier
        // onglet consulté, toggle.
        btnMenu = findViewById(R.id.btn_menu)
        btnMenu.background = UiStyle.ripple(this, R.drawable.bg_round_btn)
        btnMenu.setOnClickListener {
            if (panelMenu.visibility == View.VISIBLE) closeMenus()
            // si le panneau était resté sur Balle/Palette (ouvert via leur
            // bouton dédié), le hamburger retombe sur Export plutôt que de
            // rouvrir une vue qui n'a pas sa place dans son propre menu
            else openMenu(if (ongletCourant in hamburgerTabIndices) ongletCourant else hamburgerTabIndices[0])
        }
        tabbarView.elevation = UiStyle.dp(this, 3f).toFloat()

        // panneau de menu partagé : barre d'onglets + pause en tête, contenu en dessous
        panelMenu = findViewById(R.id.panel_menu)
        panW = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        panH = (resources.displayMetrics.heightPixels * 0.78f).toInt() // agrandi vers le haut (60→78 %) pour loger les sliders teinte/saturation sans scroll serré
        panelMenu.layoutParams = FrameLayout.LayoutParams(panW, panH)
        panelMenu.elevation = UiStyle.dp(this, 8f).toFloat()
        panelContentWidthPx = panW - 2 * UiStyle.dp(this, 12f) // largeur dispo dans le panneau (padding 12dp inclus)

        val density = resources.displayMetrics.density
        val panelCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // ✕ en tête de panneau (2026-08-11, demande explicite : "remets une
        // croix pour le fermer proprement" — existait avant, avait disparu
        // sans que son commentaire ne soit retiré, cf. ligne fantôme au-
        // dessus d'addSlider) : coin haut du panneau, ferme quel que soit
        // l'onglet actif.
        val panelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        val panelCloseBtn = UiStyle.glyphButton(this, "✕", sizeDp = 32f, muted = true)
        panelCloseBtn.setOnClickListener { closeMenus() }
        panelHeader.addView(panelCloseBtn)
        tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // onglets du panneau hamburger : Export + Paramètres seulement
        // (Palette ouverte isolément par son propre bouton, cf. champ hamburgerTabIndices)
        val labels = listOf(getString(R.string.tab_export), getString(R.string.tab_settings), getString(R.string.tab_bille_setup), getString(R.string.tab_pinceau_setup))
        ongletBtns = labels.mapIndexed { i, label ->
            UiStyle.pillButton(this, label, muted = true).also { b ->
                b.setOnClickListener { openMenu(hamburgerTabIndices[i]) }
            }
        }.toMutableList()
        ongletBtns.forEach { tabsRow.addView(it) }
        // barre d'onglets scrollable horizontalement — sur écran étroit ou
        // en Mode avancé (2 onglets de plus, "Billes"/"Pinceaux"), les 4
        // libellés côte à côte ne rentraient plus dans panelCol (largeur
        // fixe, panW) : le TextView de pillButton n'a pas de singleLine, le
        // texte se repliait caractère par caractère ("Pi/nc/ea/ux"),
        // agrandissant la zone cliquable de façon imprévisible — repéré sur
        // Huawei P30 Lite en Mode avancé, "on sait pas bouger dans le menu,
        // les boutons foirent" (2026-09-03). Chaque onglet garde maintenant
        // sa taille naturelle et son texte complet, lisible et cliquable de
        // façon prévisible ; on scrolle plutôt que de tronquer/casser.
        tabsRowScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, (6 * density).toInt())
            addView(tabsRow)
        }
        menuFlipper = ViewFlipper(this)
        menuFlipper.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        // ✕ en tête, contenu ensuite, ONGLETS EN BAS du panneau
        panelCol.addView(panelHeader)
        panelCol.addView(menuFlipper)
        panelCol.addView(tabsRowScroll)
        panelMenu.addView(panelCol)

        // côté du cluster de contrôles (droite par défaut) — chargé AVANT la
        // construction des panneaux : le bouton ✕ de fermeture suit le côté
        // (le panneau s'ouvre à l'OPPOSÉ de la barre, en miroir)
        controlsOnRight = prefs.getBoolean("controlsOnRight", true)
        applyControlsSide()
        // BuildConfig.ADVANCED_EDITION=false (variante "kid") : jamais true,
        // même si une pref antérieure le disait — pas de toggle dans cette
        // variante pour le repasser à true (cf. buildParametresPanel), donc
        // aucune raison de partir d'autre chose que false ici.
        devModeOn = BuildConfig.ADVANCED_EDITION && prefs.getBoolean("devModeOn", false)
        usageLogEnabled = prefs.getBoolean("usageLogEnabled", false)
        UsageLog.enabled = usageLogEnabled
        pageOversizeFactor = prefs.getFloat("pageOversizeFactor", 2f)
        canvas.pageOversizeFactor = pageOversizeFactor
        melangeExperiment = prefs.getInt("melangeExperiment", 1)
        canvas.melangeExperiment = melangeExperiment
        // 2026-08-30, rapporté par Wian : "les types de mélange se
        // désactivent régulièrement, retour en mode normal" — pas trouvé de
        // cause à la lecture du code (un seul point d'écriture, cf. pill
        // buttons ci-dessous), instrumenté pour capturer la valeur chargée
        // à chaque démarrage et confirmer si la perte se produit vraiment
        // ici (persistance) plutôt qu'ailleurs.
        UsageLog.d("melangeExperiment chargé au démarrage = $melangeExperiment")
        tiltResponseMode = prefs.getInt("tiltResponseMode", 1)
        canvas.tiltResponseMode = tiltResponseMode
        // mixVividMode : plus chargé ici, propre à chaque bille désormais
        // (cf. loadBilleProfiles/appliquerBilleProfile, appelé plus loin).
        planeteMassDefault = prefs.getFloat("planeteMassDefault", BallCanvasView.PLANETE_MASS_DEFAULT)
        planeteInverseMassDefault = prefs.getFloat("planeteInverseMassDefault", BallCanvasView.PLANETE_MASS_DEFAULT)
        accelerateurGaugeDefault = prefs.getFloat("accelerateurGaugeDefault", 0.6f)
        planeteCancelTilt = prefs.getBoolean("planeteCancelTilt", false)
        canvas.planeteMassDefault = planeteMassDefault
        canvas.planeteInverseMassDefault = planeteInverseMassDefault
        canvas.accelerateurGaugeDefault = accelerateurGaugeDefault
        canvas.planeteCancelTilt = planeteCancelTilt
        refreshHamburgerTabsVisibility()
        loadModeProfils()
        configProfilActif = prefs.getInt("configProfilActif", defaultConfigProfil)
        if (modeProfils.none { it.id == configProfilActif }) configProfilActif = modeProfils.first().id
        // migration : avant les profils, une seule config valait pour tout
        // le monde (clés simples "modeEnfant"/"zoomLocked"/...) — copiée
        // une fois vers le profil "normal" (id 0) pour ne rien perdre.
        if (!prefs.contains("modeEnfant_normal")) {
            prefs.edit()
                .putBoolean("modeEnfant_normal", prefs.getBoolean("modeEnfant", false))
                .putBoolean("zoomLocked_normal", prefs.getBoolean("zoomLocked", false))
                .putInt("pickerType_normal", prefs.getInt("pickerType", 0))
                .putFloat("wheelSizeDp_normal", wheelSizeDp)
                .putString("disabledTools_normal", disabledTools.joinToString(","))
                .putFloat("tabbarScale_normal", tabbarScale)
                .putFloat("controlsEdgeMargin_normal", controlsEdgeMargin)
                .putFloat("controlsBottomMargin_normal", controlsBottomMargin)
                .putFloat("panelBottomMargin_normal", panelBottomMargin)
                .apply()
        }
        appliquerProfilConfig(configProfilActif)

        // bouton discret « quitter le mode kid » (2026-08-21, demande
        // explicite : "ajouter aussi un bouton quitter le mode kid discret
        // dans l'app") — visible seulement quand l'écran est épinglé
        // (appLocked) ; en un tap : désépingle, réactive le mode
        // développeur (les réglages étaient masqués tant qu'il était off) et
        // rouvre directement Paramètres → Mode kid.
        btnQuitterModeKid = UiStyle.glyphButton(this, "🔓", sizeDp = 40f, muted = true).apply {
            layoutParams = FrameLayout.LayoutParams(
                UiStyle.dp(this@MainActivity, 40f), UiStyle.dp(this@MainActivity, 40f)
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = UiStyle.dp(this@MainActivity, 8f)
                leftMargin = UiStyle.dp(this@MainActivity, 8f)
            }
            alpha = 0.55f
            visibility = View.GONE
            setOnClickListener {
                try {
                    stopLockTask()
                } catch (e: Exception) {
                    UsageLog.d("stopLockTask (quitter mode kid) échoué: ${e.message}")
                }
                appLocked = false
                visibility = View.GONE
                // variante "kid" (BuildConfig.ADVANCED_EDITION=false) : pas
                // de toggle Mode avancé dans cette variante pour le repasser
                // à false ensuite — ne jamais le faire passer à true ici,
                // sous peine de réexposer l'onglet Mode avancé sans bouton
                // pour le refermer.
                if (BuildConfig.ADVANCED_EDITION) {
                    devModeOn = true
                    paramSousOnglet = 1
                    refreshParamSousOnglets()
                }
                saveSettings()
                openMenu(2)
            }
        }
        rootLayout.addView(btnQuitterModeKid)

        // taille explicite MATCH_PARENT : ViewFlipper (FrameLayout) donne sinon
        // WRAP_CONTENT par défaut à chaque enfant — le contenu (ScrollView)
        // restait à sa hauteur naturelle, indifférent à l'agrandissement du
        // panneau (panH) au lieu de remplir tout l'espace disponible
        val flipperChildLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        // 2026-08-25, rapporté : "le demarage est un peu lent, ya comme un
        // freez au depart" — log temporaire (à retirer une fois confirmé,
        // même logique que le log wrap InkOrb du 20/08) : mesure le temps de
        // construction de chaque panneau, suspect n°1 = MelangeurView
        // (bitmap OKLCh 360×100 avec gamut mapping par bissection, coûteux —
        // cf. MelangeurView.kt), construit 3 fois d'un coup ici (palette,
        // bille, pinceau) de façon synchrone dans onCreate.
        fun <T> timed(label: String, build: () -> T): T {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = build()
            UsageLog.d("démarrage panneau $label : ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            return r
        }
        menuFlipper.addView(timed("export") { buildExportPanel() }, FrameLayout.LayoutParams(flipperChildLp))    // onglet 0 : export/sauvegarde
        menuFlipper.addView(timed("palette") { buildPalettePanel() }, FrameLayout.LayoutParams(flipperChildLp))   // onglet 1 : couleurs (ouvert par défaut)
        menuFlipper.addView(timed("parametres") { buildParametresPanel() }, FrameLayout.LayoutParams(flipperChildLp)) // onglet 2 : interface générale
        menuFlipper.addView(timed("bille_setup") { buildBilleSetupPanel() }, FrameLayout.LayoutParams(flipperChildLp)) // onglet 3 : bibliothèque de billes
        menuFlipper.addView(timed("pinceau_setup") { buildPinceauSetupPanel() }, FrameLayout.LayoutParams(flipperChildLp)) // onglet 4 : bibliothèque de pinceaux
        updateOngletActif(0)

        // gestes du canvas : 0 = fermer le panneau (repoussé vers son origine)
        canvas.onSwipeOpen = { dir ->
            if (dir == 0) closeMenus()
        }
        // 2026-08-22, demande explicite : "quand je peins le menu reste
        // ouvert" — ferme tout sous-menu (taille pinceau, choix de bille…)
        // dès le début d'un nouveau trait.
        canvas.onDrawStart = { closeAllSubmenus() }

        // rafraîchit l'UI quand les calques changent (ajout/suppression/actif) + sauvegarde
        canvas.onCalquesChanged = {
            saveSettings()
        }

        // restauration des calques (nombre, couleurs, opacités)
        val savedCalques = prefs.getInt("calques", 1)
        for (i in 1 until savedCalques) canvas.addCalque()
        for (i in 0 until canvas.calqueCount()) {
            canvas.setCalqueBaseColor(i, prefs.getInt("calque_${i}_color", canvas.calqueBaseColor(i)))
            canvas.setCalqueOpacity(i, prefs.getFloat("calque_${i}_opacity", 1f))
        }

        if (!tutoShown) showTuto()
    }

    // ---------------------------------------------------------------- tuto

    /** 4 étapes fixes (choix de bille, agrandir bille/pinceau/gomme, poser
     *  un obstacle/effet, flèche) — carte centrée sur voile semi-opaque,
     *  même fond que les panneaux (bg_palette). Rejouable à volonté (bouton
     *  dans Paramètres) : ne marque `tutoShown` que sur la sortie du 1er
     *  passage réel, jamais sur un simple replay. 2026-09-01, retiré :
     *  l'étape "couleur d'écriture" ("oublie la 1ere etape du tuto c'est
     *  useless") — malgré plusieurs passes de raffinement (dégradé, taches,
     *  séquence en 5 phases), jugée superflue à l'usage.
     *
     *  2026-09-08, demande explicite : réactivé pour KidOrb (retiré le
     *  2026-09-03, "pour kidorb il faut pas de tuto") — mais seulement les 2
     *  premières étapes (choix de bille, dimension bille/pinceau/gomme),
     *  celles qui ont un sens sans obstacles/effets/flèche ni palette
     *  déroulante (fonctionnalités absentes de KidOrb). Ces 2 étapes sont
     *  les index 0 et 1 de `buildTutoIllustration` — l'ordre des 2 premiers
     *  éléments ci-dessous ne doit donc jamais changer. */
    private val tutoSteps =
        if (BuildConfig.ADVANCED_EDITION) {
            listOf(
                R.string.tuto_step2_title to R.string.tuto_step2_body,
                R.string.tuto_step3_title to R.string.tuto_step3_body,
                R.string.tuto_step5_title to R.string.tuto_step5_body,
                R.string.tuto_step4_title to R.string.tuto_step4_body,
                // 2026-09-03, demande explicite : "expliquer que l'on peut
                // scroll la palette des couleurs avec les pastilles du menu
                // des raccourcis"
                R.string.tuto_step_scroll_title to R.string.tuto_step_scroll_body,
            )
        } else {
            listOf(
                R.string.tuto_step2_title to R.string.tuto_step2_body,
                R.string.tuto_step3_title to R.string.tuto_step3_body,
            )
        }

    /** Construit l'illustration d'une étape et retourne (vue, teardown) — le
     *  teardown annule toute animation/callback en cours, à appeler par
     *  l'appelant avant de reconstruire l'étape suivante ou de fermer.
     *  2026-09-01, demande explicite : "il faut des images pour designer les
     *  outils et des fleches, voir une micro animation" (étape 1ère version
     *  "couleur" retirée depuis, cf. tutoSteps). Réutilise les icônes
     *  d'outils déjà dans l'app (aucune nouvelle image) : la bille pulse
     *  doucement (étape 0, "tape ici"), bille/pinceau/gomme illustrent tap +
     *  appui + glissé haut/bas en grossissant/rétrécissant réellement
     *  (étape 1, "on voit l'icone grandire", même sens que le vrai geste —
     *  cf. attachDragResize : "glisser vers le HAUT = agrandir"). Étape 2 :
     *  "la fleche qui selectionne, un outil et click sur une poignee pour
     *  agrandire" — mini-scène rejouée en boucle plutôt qu'une icône
     *  isolée : flèche → obstacle (surlignage de sélection,
     *  bg_round_rect_sel) → poignée (coin, bg_swatch) → agrandissement
     *  (shapeBox scalée depuis son coin haut-gauche, la poignée s'écarte
     *  donc d'elle-même comme si on la tirait, sans calcul de position
     *  séparé). */
    private fun buildTutoIllustration(step: Int): Pair<View, () -> Unit> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiStyle.dp(this@MainActivity, 4f) }
        }
        return when (step) {
            0 -> {
                // 2026-09-01, "la selection de balle, faut montre pareil,
                // un tap sur l'icone qui ouvre un menu et choisir une
                // balle" — remplace la bille qui pulse seule par une
                // scène : tap sur le bouton Bille → un petit menu s'ouvre
                // → le doigt vient toucher l'une d'elles → elle réagit
                // (sélectionnée) → le menu se referme.
                // 2026-09-01, retour direct : "la selection de bille se
                // passe sur le coté gauche, tu tap et ca ouvre un menu a
                // gauche, le tuto doit montrer ca" — vérifié sur
                // ouvrirSousMenuPres() : le sous-menu s'ouvre HORIZONTALEMENT
                // à côté du bouton (pas au-dessus), à l'opposé du bord où
                // vit la barre de contrôles.
                // 2026-09-01, retour direct : "le menu par defaut s'ouvre
                // vers la gauche" — controlsOnRight vaut true PAR DÉFAUT
                // (cf. sa déclaration) : la barre vit à droite par défaut,
                // donc le sous-menu s'ouvre à GAUCHE du bouton, pas à
                // droite comme posé dans la 1ère version. Bouton Bille
                // collé au bord DROIT de la scène, menu qui grossit depuis
                // son propre bord DROIT (contre le bouton) vers la gauche.
                val density = resources.displayMetrics.density
                fun dpf(v: Float) = v * density
                fun dp(v: Float) = dpf(v).toInt()
                val scene = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(120f), dp(64f))
                }
                val mainSize = dp(32f)
                val mainX = dp(120f) - mainSize - dp(4f)
                val mainY = (dp(64f) - mainSize) / 2
                val mainIcon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_balle)
                    layoutParams = FrameLayout.LayoutParams(mainSize, mainSize).apply { leftMargin = mainX; topMargin = mainY }
                }
                scene.addView(mainIcon)

                val popupW = dp(66f); val popupH = dp(28f)
                val popupX = mainX - popupW - dp(6f)
                val popupY = mainY + mainSize / 2 - popupH / 2
                val popup = FrameLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(popupW, popupH).apply { leftMargin = popupX; topMargin = popupY }
                    background = getDrawable(R.drawable.bg_palette)
                    pivotX = popupW.toFloat(); pivotY = popupH / 2f // grossit depuis son bord droit, contre le bouton
                    scaleX = 0.15f; scaleY = 0.15f
                    alpha = 0f
                }
                val swatchSize = dp(14f); val swatchGap = dp(4f)
                val swatchColors = listOf(0xFFFF3B30.toInt(), 0xFF43A047.toInt(), 0xFF2979FF.toInt())
                val swatches = swatchColors.mapIndexed { i, color ->
                    View(this).apply {
                        layoutParams = FrameLayout.LayoutParams(swatchSize, swatchSize).apply {
                            leftMargin = (popupW - (swatchSize * 3 + swatchGap * 2)) / 2 + i * (swatchSize + swatchGap)
                            topMargin = (popupH - swatchSize) / 2
                        }
                        setBackgroundResource(R.drawable.bg_swatch)
                        backgroundTintList = ColorStateList.valueOf(color)
                    }
                }
                swatches.forEach { popup.addView(it) }
                scene.addView(popup)

                val fingerSize = dp(18f)
                val finger = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(fingerSize, fingerSize).apply {
                        leftMargin = mainX + mainSize / 2 - fingerSize / 2
                        topMargin = mainY + mainSize / 2 - fingerSize / 2
                    }
                    setBackgroundResource(R.drawable.bg_swatch)
                    backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    alpha = 0f
                }
                scene.addView(finger)

                // cible = pastille du milieu, en coordonnées de scène
                val targetX = (popupX + swatches[1].layoutParams.let { (it as FrameLayout.LayoutParams).leftMargin } + swatchSize / 2 - fingerSize / 2).toFloat()
                val targetY = (popupY + (popupH - swatchSize) / 2 + swatchSize / 2 - fingerSize / 2).toFloat()
                val fingerStartX = finger.layoutParams.let { (it as FrameLayout.LayoutParams).leftMargin }.toFloat()
                val fingerStartY = finger.layoutParams.let { (it as FrameLayout.LayoutParams).topMargin }.toFloat()

                val handler = Handler(Looper.getMainLooper())
                var alive = true
                var currentSet: AnimatorSet? = null
                fun resetVisualState() {
                    popup.alpha = 0f; popup.scaleX = 0.15f; popup.scaleY = 0.15f
                    finger.alpha = 0f; finger.translationX = 0f; finger.translationY = 0f
                    swatches.forEach { it.scaleX = 1f; it.scaleY = 1f }
                }
                fun playOnce() {
                    if (!alive) return
                    resetVisualState()

                    // 2026-09-01, retour direct : "l'anim est un peu
                    // rapide" — durées allongées (~1.4×) sur toute la scène.
                    val fingerAppear = ObjectAnimator.ofPropertyValuesHolder(
                        finger,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.85f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1f),
                    ).setDuration(300)
                    val popupOpen = ObjectAnimator.ofPropertyValuesHolder(
                        popup,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.15f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.15f, 1f),
                    ).setDuration(400)
                    val moveToChoice = ObjectAnimator.ofPropertyValuesHolder(
                        finger,
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, targetX - fingerStartX),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, targetY - fingerStartY),
                    ).setDuration(550)
                    val selectPop = ObjectAnimator.ofPropertyValuesHolder(
                        swatches[1],
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f, 1f),
                    ).setDuration(400)
                    val fingerFade = ObjectAnimator.ofFloat(finger, View.ALPHA, 0.85f, 0f).setDuration(280)
                    val popupClose = ObjectAnimator.ofPropertyValuesHolder(
                        popup,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.15f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.15f),
                    ).setDuration(300)

                    val set = AnimatorSet()
                    set.play(fingerAppear).with(popupOpen)
                    set.play(moveToChoice).after(fingerAppear)
                    set.play(selectPop).with(fingerFade).after(moveToChoice)
                    set.play(popupClose).after(selectPop)
                    set.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (alive) handler.postDelayed({ playOnce() }, 500)
                        }
                    })
                    currentSet = set
                    set.start()
                }
                playOnce()

                row.addView(scene)
                row to {
                    alive = false
                    handler.removeCallbacksAndMessages(null)
                    currentSet?.cancel()
                }
            }
            1 -> {
                // 2026-09-01, "juste le pinceau suffit pour comprend avec
                // l'animation, tu met le pinceau a gauche avec
                // l'illustration et a sa droite, les icones [et] les choses
                // qui se modifient de la sorte" — seul le Pinceau porte
                // l'animation (doigt qui voyage + icône qui grossit), à
                // gauche ; Bille/Gomme statiques à sa droite.
                // 2026-09-01, suite : "il faut que toutes les fenetres du
                // tuto soit les memes, tout doit suivre une meme logique
                // visuel, base toi sur la derniere pour faire ca" — même
                // scène 120×64 que les 2 autres étapes, et même rythme
                // qu'elles (une passe jouée, pause, reprise via Handler),
                // pas une oscillation infinie sans pause comme avant.
                val density = resources.displayMetrics.density
                fun dpf(v: Float) = v * density
                fun dp(v: Float) = dpf(v).toInt()
                val scene = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(120f), dp(64f))
                }

                // 2026-09-01, retour direct : "le cercle qui grandit doit
                // etre a gauche du pinceau, et ca doit etre plus lent" —
                // cercle d'aperçu déplacé à gauche de l'icône (plus un
                // badge flottant au-dessus), tout le rythme ralenti.
                val circleBoxSize = dp(18f)
                val badgeCircleMax = dp(15f)
                val badgeCircle = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(badgeCircleMax, badgeCircleMax, Gravity.CENTER)
                    setBackgroundResource(R.drawable.bg_swatch)
                    backgroundTintList = ColorStateList.valueOf(0xFFB0B0B0.toInt())
                    scaleX = 0.3f; scaleY = 0.3f
                }
                val circleBox = FrameLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(circleBoxSize, circleBoxSize).apply {
                        leftMargin = dp(2f); topMargin = (dp(64f) - circleBoxSize) / 2
                    }
                    addView(badgeCircle)
                    alpha = 0f
                }
                scene.addView(circleBox)

                val animCellSize = dp(38f)
                val iconSize = dp(27f)
                val animCellX = dp(2f) + circleBoxSize + dp(6f)
                val animCell = FrameLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(animCellSize, animCellSize).apply {
                        leftMargin = animCellX; topMargin = (dp(64f) - animCellSize) / 2
                    }
                }
                val icon = ImageView(this).apply {
                    setImageResource(R.drawable.ic_pinceau)
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
                }
                val fingerSize = dp(13f)
                val finger = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(fingerSize, fingerSize, Gravity.CENTER)
                    setBackgroundResource(R.drawable.bg_swatch)
                    backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    alpha = 0f
                }
                animCell.addView(icon)
                animCell.addView(finger)
                scene.addView(animCell)

                // Bille/Gomme statiques à droite — "pareil pour elles".
                val staticIconSize = dp(22f)
                listOf(R.drawable.ic_balle, R.drawable.ic_gomme).forEachIndexed { i, res ->
                    scene.addView(ImageView(this).apply {
                        setImageResource(res)
                        layoutParams = FrameLayout.LayoutParams(staticIconSize, staticIconSize).apply {
                            leftMargin = animCellX + animCellSize + dp(6f) + i * (staticIconSize + dp(6f))
                            topMargin = (dp(64f) - staticIconSize) / 2
                        }
                    })
                }

                val upY = dpf(-8f); val downY = dpf(5f)
                val holdDelay = 500L
                val handler = Handler(Looper.getMainLooper())
                var alive = true
                var currentSet: AnimatorSet? = null
                fun resetVisualState() {
                    icon.translationY = 0f; icon.scaleX = 1f; icon.scaleY = 1f
                    finger.translationY = 0f; finger.alpha = 0f
                    circleBox.alpha = 0f
                    badgeCircle.scaleX = 0.3f; badgeCircle.scaleY = 0.3f
                }
                fun playOnce() {
                    if (!alive) return
                    resetVisualState()

                    val tapAppear = ObjectAnimator.ofPropertyValuesHolder(
                        finger,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.85f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1f),
                    ).setDuration(450)
                    // la bille/le trait reste "tenu" (visible, immobile) un
                    // instant avant que le glissé commence — cf. holdDelay.
                    val cycleDuration = 2200L
                    val iconCycle = ObjectAnimator.ofPropertyValuesHolder(
                        icon,
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, upY, downY, 0f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f, 0.65f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f, 0.65f, 1f),
                    ).apply { duration = cycleDuration; startDelay = holdDelay }
                    val fingerCycle = ObjectAnimator.ofPropertyValuesHolder(
                        finger,
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, upY, downY, 0f),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 0.85f, 0.85f, 0f),
                    ).apply { duration = cycleDuration; startDelay = holdDelay }
                    // le cercle d'aperçu suit le même rythme : petit au
                    // repos, grossit pendant la montée, rétrécit pendant la
                    // descente — littéralement "le cercle qui définit la
                    // taille" du vrai showDragBadge().
                    val circleAppear = ObjectAnimator.ofFloat(circleBox, View.ALPHA, 0f, 1f, 1f, 0f).apply { duration = cycleDuration; startDelay = holdDelay }
                    val badgeCircleCycle = ObjectAnimator.ofPropertyValuesHolder(
                        badgeCircle,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.3f, 1f, 0.5f, 0.3f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.3f, 1f, 0.5f, 0.3f),
                    ).apply { duration = cycleDuration; startDelay = holdDelay }

                    val set = AnimatorSet()
                    set.play(iconCycle).with(fingerCycle).with(circleAppear).with(badgeCircleCycle).after(tapAppear)
                    set.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (alive) handler.postDelayed({ playOnce() }, 650)
                        }
                    })
                    currentSet = set
                    set.start()
                }
                playOnce()

                row.addView(scene)
                row to {
                    alive = false
                    handler.removeCallbacksAndMessages(null)
                    currentSet?.cancel()
                }
            }
            2 -> {
                // 2026-09-01, "il [faut] un nouveau panneau de tuto, qui
                // explique le click avec un obstacle de forme ou un effect.
                // une fois cliquer on laisse appuyer et en bougeant ca
                // aggrandit la forme, base toi sur les autres anim pour
                // etre dans le meme style" — vérifié sur le vrai geste avant
                // d'animer (BallCanvasView, bloc de placement des formes) :
                // la forme est posée IMMÉDIATEMENT au tap, à sa taille par
                // défaut ("placé immédiatement au down, taille par défaut") ;
                // si le doigt reste posé et bouge, ça redimensionne
                // directement depuis le point de contact — pas de poignée
                // séparée à chercher, contrairement à l'étape flèche
                // (qui édite une forme DÉJÀ posée). Même style que les
                // autres étapes : scène 120×64, doigt blanc qui voyage,
                // rythme tap→pause→geste→relâche→pause.
                val density = resources.displayMetrics.density
                fun dpf(v: Float) = v * density
                fun dp(v: Float) = dpf(v).toInt()
                val scene = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(120f), dp(64f))
                }

                val anchorX = dpf(50f); val anchorY = dpf(32f)
                val shapeBaseSize = dp(16f)
                val growScale = 2.3f
                val shape = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(shapeBaseSize, shapeBaseSize).apply {
                        leftMargin = (anchorX - shapeBaseSize / 2f).toInt()
                        topMargin = (anchorY - shapeBaseSize / 2f).toInt()
                    }
                    setBackgroundResource(R.drawable.bg_swatch)
                    backgroundTintList = ColorStateList.valueOf(0xFF00E676.toInt())
                    scaleX = 0f; scaleY = 0f
                }
                scene.addView(shape)

                val fingerSize = dp(14f)
                val finger = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(fingerSize, fingerSize).apply {
                        leftMargin = (anchorX - fingerSize / 2f).toInt()
                        topMargin = (anchorY - fingerSize / 2f).toInt()
                    }
                    setBackgroundResource(R.drawable.bg_swatch)
                    backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    alpha = 0f
                }
                scene.addView(finger)

                // le doigt glisse jusqu'au bord de la forme agrandie — même
                // convention que l'étape flèche (poignée sur le bord droit
                // du cercle), pour rester dans le même style visuel.
                val grownRadius = (shapeBaseSize / 2f) * growScale
                val holdDelay = 500L
                val handler = Handler(Looper.getMainLooper())
                var alive = true
                var currentSet: AnimatorSet? = null
                fun resetVisualState() {
                    shape.scaleX = 0f; shape.scaleY = 0f
                    finger.alpha = 0f; finger.translationX = 0f
                }
                fun playOnce() {
                    if (!alive) return
                    resetVisualState()

                    // tap = la forme est posée AUSSITÔT à sa taille par
                    // défaut, en même temps que le doigt apparaît.
                    val tapAppear = ObjectAnimator.ofPropertyValuesHolder(
                        finger,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.85f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1f),
                    ).setDuration(450)
                    val shapePop = ObjectAnimator.ofPropertyValuesHolder(
                        shape,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f, 1f),
                    ).setDuration(300)
                    // "on laisse appuyer" — pause avant que le glissé ne
                    // commence, cf. holdDelay des autres étapes.
                    val dragGrow = ObjectAnimator.ofFloat(finger, View.TRANSLATION_X, 0f, grownRadius).apply {
                        duration = 1600; startDelay = holdDelay
                    }
                    val shapeGrow = ObjectAnimator.ofPropertyValuesHolder(
                        shape,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, growScale),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, growScale),
                    ).apply { duration = 1600; startDelay = holdDelay }
                    val fingerRelease = ObjectAnimator.ofFloat(finger, View.ALPHA, 0.85f, 0f).setDuration(350)

                    val set = AnimatorSet()
                    set.play(tapAppear).with(shapePop)
                    set.play(dragGrow).with(shapeGrow).after(tapAppear)
                    set.play(fingerRelease).after(dragGrow)
                    set.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (alive) handler.postDelayed({ playOnce() }, 650)
                        }
                    })
                    currentSet = set
                    set.start()
                }
                playOnce()

                row.addView(scene)
                row to {
                    alive = false
                    handler.removeCallbacksAndMessages(null)
                    currentSet?.cancel()
                }
            }
            3 -> {
                // 2026-09-01, 2e retour : "il faut la poignee sois sur le
                // cercle exterieur (vert fluo) du disque" — la poignée
                // n'est plus au coin d'une boîte diagonale mais EXACTEMENT
                // sur le bord droit du cercle (cx+r, cy), même position que
                // la vraie poignée de Obstacle.Bouchon dans drawObstacles()
                // ("poignée unique (bord droit) — agrandir en l'éloignant
                // du centre"). Le cercle grossit depuis son propre CENTRE
                // (pivot par défaut d'une View = son centre), donc la
                // poignée n'a besoin d'être décalée QUE sur X, en suivant
                // le rayon × l'échelle — pas de conteneur/pivot séparé.
                val density = resources.displayMetrics.density
                fun dpf(v: Float) = v * density
                fun dp(v: Float) = dpf(v).toInt()
                val scene = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(120f), dp(64f))
                }
                val obstacleSize = dp(30f)
                val obstacleRadius = obstacleSize / 2f
                val handleSize = dp(13f)
                val boxLeft = dpf(14f); val boxTop = dpf(16f)
                // obstacle = cercle (bg_swatch), vert non sélectionné →
                // orange sélectionné, mêmes teintes que limitePaint en jeu.
                val obstacleView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(obstacleSize, obstacleSize).apply {
                        leftMargin = boxLeft.toInt(); topMargin = boxTop.toInt()
                    }
                    setBackgroundResource(R.drawable.bg_swatch)
                    backgroundTintList = ColorStateList.valueOf(0xFF00E676.toInt())
                }
                // poignée = petit disque jaune posé SUR le bord droit du
                // cercle (pas au coin d'une boîte), invisible tant que
                // l'obstacle n'est pas sélectionné — apparaît en même temps
                // que le vert devient orange, comme en jeu.
                val handleView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(handleSize, handleSize).apply {
                        leftMargin = (boxLeft + obstacleSize - handleSize / 2f).toInt()
                        topMargin = (boxTop + obstacleRadius - handleSize / 2f).toInt()
                    }
                    setBackgroundResource(R.drawable.bg_swatch)
                    backgroundTintList = ColorStateList.valueOf(0xFFFFEB3B.toInt())
                    alpha = 0f
                    scaleX = 0.4f; scaleY = 0.4f
                }
                scene.addView(obstacleView)
                scene.addView(handleView)
                val arrowSize = dpf(26f)
                val arrow = ImageView(this).apply {
                    setImageResource(R.drawable.ic_select)
                    layoutParams = FrameLayout.LayoutParams(arrowSize.toInt(), arrowSize.toInt())
                }
                scene.addView(arrow)

                // ic_select a la pointe du curseur en HAUT-GAUCHE de son
                // viewport 24×24 (pathData "M4,4l16,8…"), pas au centre —
                // décalage retiré pour que la POINTE, pas le coin de
                // l'icône, se pose exactement sur la cible.
                val tip = arrowSize * (4f / 24f)
                val obstacleCenterX = boxLeft + obstacleRadius
                val obstacleCenterY = boxTop + obstacleRadius
                val handleCenterX = boxLeft + obstacleSize // = centre + rayon, bord droit du cercle
                val handleCenterY = obstacleCenterY
                val growScale = 1.3f
                // le cercle grossit depuis son centre (pivot par défaut) :
                // son bord droit s'éloigne de centre + rayon×growScale.
                val handleGrownX = obstacleCenterX + obstacleRadius * growScale
                val handleGrowDx = handleGrownX - handleCenterX // déplacement de la poignée pendant la croissance

                val startX = dpf(90f); val startY = 0f
                val toObstacleX = obstacleCenterX - tip; val toObstacleY = obstacleCenterY - tip
                val toHandleX = handleCenterX - tip; val toHandleY = handleCenterY - tip
                val draggedX = handleGrownX - tip; val draggedY = toHandleY

                val handler = Handler(Looper.getMainLooper())
                var alive = true
                var currentSet: AnimatorSet? = null
                fun resetVisualState() {
                    obstacleView.backgroundTintList = ColorStateList.valueOf(0xFF00E676.toInt())
                    obstacleView.scaleX = 1f; obstacleView.scaleY = 1f
                    handleView.alpha = 0f; handleView.scaleX = 0.4f; handleView.scaleY = 0.4f
                    handleView.translationX = 0f
                    arrow.translationX = startX; arrow.translationY = startY
                }
                fun playOnce() {
                    if (!alive) return
                    resetVisualState()

                    val toObstacle = ObjectAnimator.ofPropertyValuesHolder(
                        arrow,
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, startX, toObstacleX),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, startY, toObstacleY),
                    ).setDuration(500)
                    // clic = sélection : le cercle passe vert → orange, la
                    // poignée jaune apparaît sur son bord (pop-in), pas avant.
                    val select = ObjectAnimator.ofPropertyValuesHolder(
                        handleView,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1f),
                    ).setDuration(200)
                    select.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            if (alive) obstacleView.backgroundTintList = ColorStateList.valueOf(0xFFFF9800.toInt())
                        }
                    })
                    val toHandle = ObjectAnimator.ofPropertyValuesHolder(
                        arrow,
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, toObstacleX, toHandleX),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, toObstacleY, toHandleY),
                    ).setDuration(400)
                    // tirer la poignée : le cercle grossit depuis son
                    // centre, la poignée (collée à son bord droit) et la
                    // flèche (qui la tient) avancent du même déplacement.
                    val dragArrow = ObjectAnimator.ofPropertyValuesHolder(
                        arrow,
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_X, toHandleX, draggedX),
                        PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, toHandleY, draggedY),
                    ).setDuration(550)
                    val dragHandle = ObjectAnimator.ofFloat(handleView, View.TRANSLATION_X, 0f, handleGrowDx).setDuration(550)
                    val growObstacle = ObjectAnimator.ofPropertyValuesHolder(
                        obstacleView,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, growScale),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, growScale),
                    ).setDuration(550)

                    val set = AnimatorSet()
                    set.playSequentially(toObstacle, select, toHandle)
                    set.play(dragArrow).with(dragHandle).with(growObstacle).after(toHandle)
                    set.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (alive) handler.postDelayed({ playOnce() }, 500)
                        }
                    })
                    currentSet = set
                    set.start()
                }
                playOnce()

                scene to {
                    alive = false
                    handler.removeCallbacksAndMessages(null)
                    currentSet?.cancel()
                }
            }
            else -> {
                // 2026-09-03, demande explicite : "expliquer que l'on peut
                // scroll la palette des couleurs avec les pastilles du menu
                // des raccourcis" — un doigt glisse verticalement sur la
                // colonne de pastilles, qui défilent avec lui
                // (translationY), pour montrer que d'autres couleurs
                // restent accessibles au-delà de ce qui est visible sans
                // scroller. Même style que les autres étapes : scène
                // 120×64, doigt blanc translucide, rythme apparition→
                // glissé→pause.
                val density = resources.displayMetrics.density
                fun dpf(v: Float) = v * density
                fun dp(v: Float) = dpf(v).toInt()
                val scene = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(120f), dp(64f))
                }

                // viewport = fenêtre visible de la colonne, même principe
                // que le vrai ScrollView des raccourcis couleur
                // (quickColorsScroll). Retour direct après un 1er essai :
                // "il manque des couleurs, on voit juste des couleurs qui
                // bougent, on comprend pas vraiment le scroll" — cause
                // trouvée : bg_palette (#99000000) est EXACTEMENT le même
                // fond que la carte du tuto elle-même (cf. showTuto,
                // `card.background`), donc le viewport était invisible,
                // fondu dans le fond du popup — rien ne délimitait "voici la
                // fenêtre à travers laquelle tu regardes une liste plus
                // longue". Fond gris clair opaque, nettement contrasté avec
                // le noir semi-transparent environnant ; 7 couleurs (au lieu
                // de 5) pour que le défilement révèle clairement du contenu
                // neuf plutôt qu'un simple va-et-vient de 2-3 pastilles.
                val viewportW = dp(28f); val viewportH = dp(54f)
                val viewport = FrameLayout(this).apply {
                    layoutParams = FrameLayout.LayoutParams(viewportW, viewportH).apply {
                        leftMargin = dp(46f); topMargin = (dp(64f) - viewportH) / 2
                    }
                    background = GradientDrawable().apply {
                        setColor(0xFF4A4A4A.toInt())
                        cornerRadius = dpf(10f)
                    }
                    clipChildren = true
                    clipToPadding = true
                }
                scene.addView(viewport)

                // Chaque pastille est un enfant DIRECT du viewport (position
                // fixe via topMargin, PAS un LinearLayout wrap_content
                // transformé) — retour direct après un 1er essai avec
                // colonne LinearLayout : "les premieres pastilles on les
                // vois mais quand ca bouge on voit pas les suivants" (le
                // groupe wrap_content + translationY ne révélait pas le
                // nouveau contenu de façon fiable). Ici les 7 pastilles sont
                // TOUTES animées ensemble (même ObjectAnimator sur chacune,
                // synchronisées par l'AnimatorSet) — chacune garde sa
                // propre position de départ connue, rien ne dépend d'un
                // measure de conteneur intermédiaire.
                val swatchSize = dp(16f); val swatchGap = dp(4f)
                val swatchColors = listOf(
                    0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(),
                    0xFF34C759.toInt(), 0xFF2979FF.toInt(), 0xFFAF52DE.toInt()
                )
                val swatchStep = swatchSize + swatchGap
                val swatches = swatchColors.mapIndexed { i, color ->
                    View(this).apply {
                        layoutParams = FrameLayout.LayoutParams(swatchSize, swatchSize).apply {
                            leftMargin = (viewportW - swatchSize) / 2
                            topMargin = dp(4f) + i * swatchStep
                        }
                        setBackgroundResource(R.drawable.bg_swatch)
                        backgroundTintList = ColorStateList.valueOf(color)
                    }
                }
                swatches.forEach { viewport.addView(it) }

                val fingerSize = dp(14f)
                val finger = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(fingerSize, fingerSize).apply {
                        leftMargin = dp(46f) + (viewportW - fingerSize) / 2
                        topMargin = (dp(64f) - viewportH) / 2 + viewportH - fingerSize - dp(4f)
                    }
                    setBackgroundResource(R.drawable.bg_swatch)
                    backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    alpha = 0f
                }
                scene.addView(finger)

                // le doigt glisse vers le haut, les pastilles suivent — même
                // distance pour toutes, pour rester "collées" au doigt.
                // Assez grand (56dp, sur ~86dp de contenu total dépassant le
                // viewport) pour révéler clairement plusieurs couleurs
                // neuves, pas juste un léger va-et-vient.
                val scrollDistance = dpf(56f)
                val handler = Handler(Looper.getMainLooper())
                var alive = true
                var currentSet: AnimatorSet? = null
                fun resetVisualState() {
                    finger.alpha = 0f; finger.translationY = 0f
                    swatches.forEach { it.translationY = 0f }
                }
                fun playOnce() {
                    if (!alive) return
                    resetVisualState()

                    val fingerAppear = ObjectAnimator.ofPropertyValuesHolder(
                        finger,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.85f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1f),
                    ).setDuration(350)
                    val scrollUp = ObjectAnimator.ofFloat(finger, View.TRANSLATION_Y, 0f, -scrollDistance).setDuration(900)
                    val swatchesScroll = swatches.map { ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, 0f, -scrollDistance).setDuration(900) }
                    val fingerFade = ObjectAnimator.ofFloat(finger, View.ALPHA, 0.85f, 0f).setDuration(300)

                    val set = AnimatorSet()
                    val scrollTogether = AnimatorSet().apply { playTogether(listOf(scrollUp) + swatchesScroll) }
                    set.play(scrollTogether).after(fingerAppear)
                    set.play(fingerFade).after(scrollTogether)
                    set.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (alive) handler.postDelayed({ playOnce() }, 600)
                        }
                    })
                    currentSet = set
                    set.start()
                }
                playOnce()

                row.addView(scene)
                row to {
                    alive = false
                    handler.removeCallbacksAndMessages(null)
                    currentSet?.cancel()
                }
            }
        }
    }

    private fun showTuto() {
        // 2026-09-01, demande explicite : "il faut des fleches pour revenir
        // en arriere dans le tuto, pour circuler et une fleche en haut a
        // droite du tuto pour le fermer, il faut cacher plus l'interface
        // derriere quand le tuto est actif" — flèche ◀ (recule, masquée à
        // l'étape 0) ajoutée au premier plan des boutons, ✕ de fermeture en
        // en-tête de carte (même motif que panelHeader/panelCloseBtn), voile
        // nettement plus opaque (0xAA→0xF2, cache vraiment le dessin
        // derrière au lieu de le laisser transparaître).
        if (tutoOverlay != null) return // déjà affiché (évite un doublon si rappelé pendant l'affichage)
        val density = resources.displayMetrics.density
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xD0000000.toInt())
            isClickable = true // bloque les gestes sur le dessin en dessous
        }
        rootLayout.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        tutoOverlay = scrim
        // 2026-09-02, demande explicite : "pour le tuto j'aimerais que
        // l'interface se cache en attendant que le tuto soit fermé, comme
        // pour le menu de création de bille" — même mécanisme que
        // showBilleQuickEditor (masque, ne touche pas au réglage persistant
        // si déjà masqué par choix de l'utilisateur).
        val uiWasVisibleBefore = !uiHidden
        if (uiWasVisibleBefore) {
            tabbarView.visibility = View.GONE
            controlsCluster.visibility = View.GONE
            btnUndo.visibility = View.GONE
            btnRedo.visibility = View.GONE
            versionLabel.visibility = View.GONE
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_palette)
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (18 * density).toInt())
        }
        val cardLp = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.82f).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        scrim.addView(card, cardLp)

        var step = 0
        var illustrationTeardown: (() -> Unit)? = null
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val closeBtn = UiStyle.glyphButton(this, "✕", sizeDp = 28f, muted = true)
        header.addView(closeBtn)
        val illustrationSlot = FrameLayout(this)
        val progressView = TextView(this).apply { UiStyle.hint(this) }
        val titleView = TextView(this).apply { UiStyle.title(this) }
        val bodyView = TextView(this).apply {
            UiStyle.body(this)
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        }
        val backBtn = UiStyle.pillButton(this, "◀", muted = true)
        backBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (10 * density).toInt() }
        val nextBtn = UiStyle.pillButton(this, "▶")
        nextBtn.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginStart = (10 * density).toInt() }
        btnRow.addView(backBtn)
        btnRow.addView(nextBtn)
        card.addView(header)
        card.addView(illustrationSlot)
        card.addView(progressView)
        card.addView(titleView)
        card.addView(bodyView)
        card.addView(btnRow)

        fun closeTuto() {
            illustrationTeardown?.invoke()
            illustrationTeardown = null
            rootLayout.removeView(scrim)
            tutoOverlay = null
            tutoShown = true
            saveSettings()
            if (uiWasVisibleBefore) {
                tabbarView.visibility = View.VISIBLE
                controlsCluster.visibility = View.VISIBLE
                btnUndo.visibility = if ("undo" in disabledTools) View.GONE else View.VISIBLE
                btnRedo.visibility = if ("redo" in disabledTools) View.GONE else View.VISIBLE
                versionLabel.visibility = View.VISIBLE
            }
        }
        fun renderStep() {
            illustrationTeardown?.invoke()
            illustrationSlot.removeAllViews()
            val (view, teardown) = buildTutoIllustration(step)
            illustrationSlot.addView(view)
            illustrationTeardown = teardown
            val (titleRes, bodyRes) = tutoSteps[step]
            progressView.text = "${step + 1}/${tutoSteps.size}"
            titleView.text = getString(titleRes)
            bodyView.text = getString(bodyRes)
            // 2026-09-01, demande explicite : "des fleches pour suivant et
            // precedent plutot que du texte" — "▶" tant qu'il reste une
            // étape, texte "Compris" seulement à la fin (action distincte
            // de naviguer, garde son libellé).
            nextBtn.text = if (step == tutoSteps.lastIndex) getString(R.string.tuto_done) else "▶"
            backBtn.visibility = if (step == 0) View.GONE else View.VISIBLE
        }
        closeBtn.setOnClickListener { closeTuto() }
        backBtn.setOnClickListener { if (step > 0) { step--; renderStep() } }
        nextBtn.setOnClickListener {
            if (step == tutoSteps.lastIndex) closeTuto()
            else { step++; renderStep() }
        }
        renderStep()
    }

    // ---------------------------------------------------------------- palette

    private fun buildPalettePanel(): View {
        val density = resources.displayMetrics.density
        // contenu scrollable dans le panneau de menu partagé (taille fixe)
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        // petit ✕ pour repousser le panneau hors de l'écran

        // pas de mode à choisir : tap = rond, appuyer et bouger = trait continu

        // UNE grille de 6 colonnes : emplacements remplis + emplacements
        // vides (anneaux discrets) — le compteur indique combien de places
        // sont encore disponibles pour déposer une couleur
        melangeHint = TextView(this).apply {
            text = getString(R.string.palette_hint)
            UiStyle.hint(this)
        }
        gridLabel = TextView(this).apply {
            text = getString(R.string.palette_title)
            UiStyle.title(this)
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        col.addView(gridLabel)
        // Bouton "Nouvelle palette" retiré (2026-08-19, demande explicite :
        // "ya pas besoin de bouton nouvelle palette") — plus de moyen dédié
        // pour vider paletteState en un tap ; la palette se réédite pastille
        // par pastille comme avant.
        gridContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(gridContainer)
        buildColorGrid()

        // type de sélecteur (2026-08-21, demande explicite : "le mode
        // enfant... c'est en fait un mode de mélangeur comme le joystick et
        // autre... ajoute un mode pastille qui designe le mode de pastille
        // scrollable dans les raccourcis") — un seul sélecteur unifié :
        // "Pastille" = liste scrollable classique (mode enfant OFF),
        // les 2 autres = mode enfant ON + le mélangeur choisi. Déplacé ici
        // depuis Paramètres → Mode dev → Mode kid le 2026-09-04, demande
        // explicite : "le type de sélecteur de couleur devrait être le menu
        // palette plutôt que planqué" — vivait derrière devModeOn (jamais
        // atteignable sans activer le Mode développeur), alors que ce
        // panneau Palette est visible par défaut sur InkOrb.
        // 2026-09-04, suite immédiate, demande explicite : "il faut garder
        // uniquement le simple, et le pavé (à renommer autrement) et les
        // pastilles" — Roue et Joystick retirés du sélecteur (pickerType 1
        // et 2 restent gérés par le code — wheelPanel, drag, etc. — juste
        // plus atteignables depuis cette liste) ; "Pavé" renommé "Dégradé"
        // (`preset_picker_pad`, plus explicite : c'est un dégradé tactile).
        val pickerLabel = TextView(this).apply {
            text = getString(R.string.label_picker_type)
            UiStyle.body(this)
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        col.addView(pickerLabel)
        val pickerContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pickerBtns = mutableListOf<TextView>()
        val pickerOptions = listOf(
            getString(R.string.preset_picker_pastille) to -1,
            getString(R.string.preset_picker_pad) to 0,
            getString(R.string.preset_picker_simple) to 3,
        )
        fun selectionActuelle() = if (!modeEnfant) -1 else pickerType
        val colsPicker = 3
        var pickerRowCur: LinearLayout? = null
        pickerOptions.forEachIndexed { idx, (label, type) ->
            if (idx % colsPicker == 0) {
                pickerRowCur = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pickerContainer.addView(pickerRowCur)
            }
            val btn = UiStyle.pillButton(this, label, muted = selectionActuelle() != type)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
            btn.setOnClickListener {
                if (type == -1) modeEnfant = false else { modeEnfant = true; pickerType = type }
                applyModeEnfant() // appelle aussi applyPickerType()
                val actuel = selectionActuelle()
                pickerBtns.forEachIndexed { i, b -> b.setTextColor(if (pickerOptions[i].second == actuel) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
                saveSettings()
                sauverProfilConfigActif()
                // recalcule icônes/mélangeur/pastilles pour le NOUVEAU mode
                // — sans ça la taille restait figée sur celle calculée pour
                // le mode actif au lancement (2026-09-04, retour direct :
                // "la largeur change si je passe en mode pastille,
                // vérifie").
                recalculerTaillesTabbar()
            }
            pickerBtns.add(btn)
            pickerRowCur!!.addView(btn)
        }
        col.addView(pickerContainer)

        // mélangeur : un point à déplacer sur la carte de toutes les couleurs ;
        // quand une couleur est choisie, un TAP sur une pastille l'ajoute
        val melangeLabel = TextView(this).apply {
            text = getString(R.string.mixer_title)
            UiStyle.title(this)
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        col.addView(melangeLabel)
        val melangeur = MelangeurView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (130 * density).toInt()
            )
            // clearActiveCarriedColor() ici (fin de geste), pas dans
            // onColorPicked (appelé en CONTINU à chaque frame de glissé) —
            // même régression que le mélangeur de la barre de raccourcis,
            // cf. commentaire de onPickStart plus haut dans onCreate.
            onColorCommitted = { saveSettings(); canvas.clearActiveCarriedColor() }
        }
        col.addView(melangeHint)
        col.addView(melangeur)
        // sliders teinte/saturation : en complément du mélangeur, pas à sa
        // place — ils modifient le même échantillon (mélangeur.setHue/Chroma),
        // et sont mis à jour en retour quand on touche directement le mélangeur
        val teinteSeek = addSlider(col, getString(R.string.slider_hue), 0f, 360f, melangeur.hue.toFloat()) { melangeur.setHue(it.toDouble()) }
        val satSeek = addSlider(col, getString(R.string.slider_saturation), 0f, 0.4f, melangeur.chroma.toFloat()) { melangeur.setChroma(it.toDouble()) }
        melangeur.onColorPicked = { c ->
            couleurCreee = c
            canvas.selectedColor = c
            melangeHint.text = getString(R.string.mixer_ready_hint)
        }
        // synchronise les sliders SEULEMENT sur un toucher direct de la carte —
        // jamais depuis les sliders eux-mêmes (réassigner le progress d'une
        // SeekBar en plein glissé fait lutter/ramer contre le doigt de l'utilisateur)
        melangeur.onDirectTouch = {
            teinteSeek.progress = (melangeur.hue / 360.0 * 1000).toInt().coerceIn(0, 1000)
            satSeek.progress = (melangeur.chroma / 0.4 * 1000).toInt().coerceIn(0, 1000)
        }


        buildFondSection(col)

        return scroll
    }

    /** Couleur de fond du canvas — rangée de pastilles cliquables (papier +
     *  blanc + palette de raccourcis). Extrait de buildPalettePanel() le
     *  2026-09-03, demande explicite : "dans les paramettre de kidorb il y
     *  ai les couleurs de fond, histoire de pouvoir remplire instant" — le
     *  bouton Palette complet (mélangeur, grille de 18 couleurs) est caché
     *  sur KidOrb par conception (`defaultDisabledToolsKid` inclut
     *  "palette"), donc cette section n'y était plus accessible du tout ;
     *  réutilisée telle quelle dans buildParametresPanel() pour rester
     *  disponible même sans le bouton Palette. */
    private fun buildFondSection(col: LinearLayout) {
        val density = resources.displayMetrics.density
        val fondLabel = TextView(this).apply {
            text = getString(R.string.background_title)
            UiStyle.title(this)
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        col.addView(fondLabel)
        val fondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val fondColors = intArrayOf(
            0xFFF7F3EC.toInt(), // papier (crème)
            0xFFFFFFFF.toInt(), // blanc
            *paletteColors
        )
        val fondWrappers = mutableListOf<LinearLayout>()
        for (color in fondColors) {
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((26 * density).toInt(), (26 * density).toInt())
                setBackgroundResource(if (color == canvas.bgColor) R.drawable.bg_swatch_ring else 0)
            }
            val sw = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
                background = getDrawable(R.drawable.bg_swatch)
                backgroundTintList = ColorStateList.valueOf(color)
            }
            sw.setOnClickListener {
                canvas.bgColor = color
                fondWrappers.forEachIndexed { fi, w ->
                    w.setBackgroundResource(if (fondColors[fi] == canvas.bgColor) R.drawable.bg_swatch_ring else 0)
                }
                saveSettings()
                UsageLog.d("fond = #%06X".format(color and 0xFFFFFF))
            }
            wrapper.addView(sw)
            fondWrappers.add(wrapper)
            fondRow.addView(wrapper)
        }
        col.addView(fondRow)
    }

    /** Reconstruit la grille : PALETTE_CAPACITY emplacements (remplis ou
     *  anneaux vides). Tap = sélectionner (anneau blanc = seule à écrire),
     *  long-press = vider l'emplacement, drop = remplir/remplacer. */
    private fun buildColorGrid() {
        gridContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val cols = 6
        // taille calée sur la largeur réelle du panneau (fixe, en dp) — avant,
        // 6 × 46dp + marges dépassait la largeur dispo et les pastilles se
        // faisaient couper/écraser en bord de grille
        val cellW = if (panelContentWidthPx > 0) panelContentWidthPx / cols else (54 * density).toInt()
        val margin = (4 * density).toInt()
        val swatchSize = cellW - 2 * margin - (4 * density).toInt()
        val wrappers = mutableListOf<LinearLayout>()
        var rowL: LinearLayout? = null
        val vides = paletteState.count { it == null }
        gridLabel.text = resources.getQuantityString(R.plurals.palette_empty_slots, vides, vides)
        melangeHint.text = if (couleurCreee != null)
            getString(R.string.mixer_ready_hint)
        else
            getString(R.string.palette_hint)
        for (i in 0 until PALETTE_CAPACITY) {
            if (i % cols == 0) {
                rowL = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                gridContainer.addView(rowL)
            }
            val color = paletteState[i]
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                // pas de halo par défaut : seule la pastille sélectionnée en a un (selectSwatch)
                layoutParams = LinearLayout.LayoutParams(
                    swatchSize + (4 * density).toInt(),
                    swatchSize + (4 * density).toInt()
                ).apply {
                    setMargins(margin, margin, margin, margin)
                }
            }
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(
                swatchSize - (4 * density).toInt(),
                swatchSize - (4 * density).toInt()
            )
            if (color == null) {
                // emplacement vide : anneau discret (pas de halo plein)
                v.background = getDrawable(R.drawable.bg_swatch_empty)
            } else {
                v.background = getDrawable(R.drawable.bg_swatch)
                v.backgroundTintList = ColorStateList.valueOf(color)
                v.setOnLongClickListener {
                    paletteState[i] = null
                    saveSettings()
                    buildColorGrid()
                    true
                }
            }
            // tap : une couleur choisie au mélangeur ? → l'ajouter ici (remplit
            // ou remplace) ; sinon, sélectionner la pastille (pour écrire)
            v.setOnClickListener {
                val aPlacer = couleurCreee
                if (aPlacer != null) {
                    paletteState[i] = aPlacer
                    couleurCreee = null
                    saveSettings()
                    buildColorGrid()
                    UsageLog.d("palette[$i] = #%06X".format(aPlacer and 0xFFFFFF))
                } else if (color != null) {
                    canvas.selectedColor = color
                    canvas.clearActiveCarriedColor()
                    UsageLog.d("grille palette → couleur = #%06X".format(color and 0xFFFFFF))
                    // choisir une couleur pour peindre → repasse au pinceau
                    // (2026-08-11, demande explicite : "vu qu'on voulait
                    // peindre") — même si un autre outil était actif.
                    // `retourPinceau` (callback de champ) : `buildColorGrid`
                    // est une méthode de classe séparée, hors de portée de
                    // `activerPinceau` (fonction locale à onCreate).
                    retourPinceau?.invoke()
                    closeMenus()
                    selectSwatch(wrappers, i)
                    rebuildQuickColors()
                    saveSettings()
                }
            }
            wrapper.addView(v)
            rowL!!.addView(wrapper)
            wrappers.add(wrapper)
        }
        val idx = paletteState.indexOf(canvas.selectedColor)
        selectSwatch(wrappers, if (idx >= 0) idx else 0)
        rebuildQuickColors()
    }

    /** Raccourcis rapides : toute la palette (jusqu'à 12), même axe vertical
     *  et même taille que les icônes d'onglet (tabbarButtonSizeDp, 44dp par
     *  défaut, réduite sur écran court), défilement haut/bas dans le
     *  ScrollView borné qui les contient (quickColorsScroll). */
    private fun rebuildQuickColors() {
        quickColorsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (tabbarButtonSizeDp * density).toInt() // même taille que les icônes d'onglet
        val gap = (6 * density).toInt()
        paletteState.filterNotNull().take(PALETTE_CAPACITY).forEachIndexed { i, color ->
            val selected = color == canvas.selectedColor
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                // anneau blanc sur la sélection, cadre discret sinon (rend le noir visible sur fond sombre)
                setBackgroundResource(if (selected) R.drawable.bg_swatch_ring else R.drawable.bg_swatch_frame)
                layoutParams = LinearLayout.LayoutParams(
                    size + (4 * density).toInt(), size + (4 * density).toInt()
                ).apply { if (i > 0) topMargin = gap }
            }
            val sw = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    size - (4 * density).toInt(), size - (4 * density).toInt()
                )
                background = getDrawable(R.drawable.bg_swatch)
                backgroundTintList = ColorStateList.valueOf(color)
            }
            sw.setOnClickListener {
                canvas.selectedColor = color
                canvas.clearActiveCarriedColor()
                retourPinceau?.invoke() // cf. commentaire de la grille de couleurs
                saveSettings()
                if (menuFlipper.displayedChild == 0) buildColorGrid() else rebuildQuickColors()
                closeMenus()
                UsageLog.d("raccourci couleur = #%06X".format(color and 0xFFFFFF))
            }
            wrapper.addView(sw)
            quickColorsContainer.addView(wrapper)
        }
    }


    /** Sauvegarde toute la config (retrouvée à la prochaine session/update). */
    private fun saveSettings() {
        val e = prefs.edit()
        e.putFloat("gravityToPx", canvas.gravityToPx)
        e.putFloat("smoothing", canvas.smoothing)
        e.putFloat("shakeThreshold", canvas.shakeThreshold)
        e.putFloat("shakeToVel", canvas.shakeToVel)
        e.putFloat("gravityDeadZone", canvas.gravityDeadZone)
        e.putFloat("trailWidthDp", canvas.trailWidthDp)
        e.putFloat("ballRadiusDp", canvas.ballRadiusDp)
        e.putFloat("gommePeintureRadiusDp", canvas.gommePeintureRadiusDp)
        e.putFloat("gommeObstaclesRadiusDp", canvas.gommeObstaclesRadiusDp)
        e.putFloat("restitution", canvas.restitution)
        e.putFloat("poids", canvas.poids)
        e.putFloat("textureAmount", canvas.textureAmount)
        // fonduRate/mixVividMode/trailEdgeMode/cometTrail/rainbowMode/
        // speedColorMode : cf. commentaire du chargement plus haut — plus de
        // sauvegarde globale, saveBilleProfiles() s'en charge par bille.
        e.putBoolean("vitesseEpaisseur", canvas.vitesseEpaisseur)
        e.putBoolean("boundsActive", canvas.boundsActive)
        e.putBoolean("wrapActive", canvas.wrapActive)
        e.putBoolean("ballVisible", canvas.ballVisible)
        e.putBoolean("ballGrabInPinceau", canvas.ballGrabInPinceau)
        e.putBoolean("tutoShown", tutoShown)
        e.putFloat("tabbarScale", tabbarScale)
        e.putFloat("controlsEdgeMargin", controlsEdgeMargin)
        e.putFloat("controlsBottomMargin", controlsBottomMargin)
        e.putFloat("panelBottomMargin", panelBottomMargin)
        e.putFloat("tiltEffect", canvas.tiltEffect)
        e.putFloat("frictionRate", canvas.frictionRate)
        e.putBoolean("rechargeProgressive", canvas.rechargeProgressive)
        e.putBoolean("showJauge", canvas.showJauge)
        e.putBoolean("controlsOnRight", controlsOnRight)
        e.putBoolean("modeEnfant", modeEnfant)
        e.putBoolean("zoomLocked", zoomLocked)
        e.putBoolean("devModeOn", devModeOn)
        e.putBoolean("usageLogEnabled", usageLogEnabled)
        e.putFloat("pageOversizeFactor", pageOversizeFactor)
        e.putInt("melangeExperiment", melangeExperiment)
        e.putInt("tiltResponseMode", tiltResponseMode)
        e.putFloat("planeteMassDefault", planeteMassDefault)
        e.putFloat("planeteInverseMassDefault", planeteInverseMassDefault)
        e.putFloat("accelerateurGaugeDefault", accelerateurGaugeDefault)
        e.putBoolean("planeteCancelTilt", planeteCancelTilt)
        e.putInt("configProfilActif", configProfilActif)
        e.putInt("pickerType", pickerType)
        e.putFloat("wheelSizeDp", wheelSizeDp)
        e.putBoolean("billeNamesVisible", billeNamesVisible)
        e.putFloat("wheelPosX", wheelPosX)
        e.putFloat("wheelPosY", wheelPosY)
        e.putString("disabledTools", disabledTools.joinToString(","))
        e.putInt("selectedColor", canvas.selectedColor)
        e.putInt("backgroundColor", canvas.bgColor)
        e.putString("paletteState", paletteState.joinToString(";") { it?.toString() ?: "x" }) // décimal (négatifs OK), "x" = vide
        e.putInt("calques", canvas.calqueCount())
        for (i in 0 until canvas.calqueCount()) {
            e.putInt("calque_${i}_color", canvas.calqueBaseColor(i))
            e.putFloat("calque_${i}_opacity", canvas.calqueOpacity(i))
        }
        e.apply()
    }

    /** Sauvegarde à la fermeture (ceinture : si l'app est tuée avant un changement). */
    override fun onStop() {
        super.onStop()
        saveSettings()
        backupProfilsVersStockagePartage()
    }

    // ---------------------------------------------------------------- paramètres (onglet dédié)

    /** Réglages d'interface généraux, pas spécifiques à la bille — onglet
     *  séparé (2026-08-13, demande explicite : "il faut un onglet paramètre
     *  pour mettre le slider de l'interface"). */
    // 2026-08-20, demande explicite : "dans les parametreq tu peuw cree 2
    // onglets un pour l'interface et un pour la config du mode kid" — même
    // principe de sous-onglets que Bille Setup (barre de pilules +
    // conteneur qui échange son contenu), pour ranger ce qui avait
    // beaucoup grossi cette session (verrouillage, mode enfant, sélecteurs,
    // outils désactivables d'un côté ; mise en page générale de l'autre).
    private var paramSousOnglet = 0
    private lateinit var paramSubTabs: LinearLayout
    private lateinit var paramContent: FrameLayout
    // 2026-08-24, demande explicite : la SÉLECTION de mode (pastilles) de
    // l'onglet Paramètres reste toujours visible, avec ou sans mode
    // développeur — seule la GESTION (bouton "+" pour créer, tag "manage")
    // est réservée au mode développeur (cf. populateModeSelector) ; celui
    // de Fichier (sélection seule, cf. buildExportPanel) reste inchangé,
    // toujours visible.
    private lateinit var modeSelectorSectionParam: LinearLayout
    // même principe pour la section qualité/inclusion de l'onglet Fichier
    // (cf. buildExportPanel) — réservée au mode développeur elle aussi.
    private lateinit var exportAdvancedSection: LinearLayout
    // verrouillage d'écran dupliqué dans Fichier — toujours visible, cf.
    // commentaire sur son addView dans buildExportPanel().
    private lateinit var appLockRowFichier: LinearLayout

    private fun buildParametresPanel(): View {
        // ScrollView autour de TOUT le panneau (titre, toggles, langue,
        // tuto, sélecteur de mode, sous-onglets, ET paramContent) — jusqu'ici
        // seuls les 3 sous-panneaux (Interface/Mode avancé/Outils) avaient
        // leur propre scroll interne, mais le contenu AU-DESSUS d'eux
        // (surtout en Mode avancé : toggle dev + sélecteur de profil en
        // plus) pouvait à lui seul dépasser la hauteur du panneau sur un
        // écran court, poussant paramContent entièrement hors champ, sans
        // AUCUN moyen d'y accéder — "quand je tape sur avancé, je peux pas
        // descendre" (Huawei P30 Lite, 2026-09-03). Un seul scroll ici,
        // les 3 sous-panneaux perdent le leur juste en dessous (scroll
        // imbriqué = conflit de gestes, l'un des deux finit par ne plus
        // répondre).
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        val title = TextView(this).apply {
            text = getString(R.string.settings_title)
            UiStyle.title(this)
        }
        col.addView(title)

        // 2026-08-21, demande explicite : "il faut 2 toggle, un mode dev...
        // qui debloque un onglet developper... et un autre toggle mode kid,
        // qui... permet d'activer le mode kid de l'app ou de le
        // désactiver" — les deux vivent ICI, au-dessus des sous-onglets
        // (toujours visibles, quel que soit le sous-onglet affiché) :
        // "Mode développeur" ajoute/retire l'onglet "Développeur" de la
        // liste ; "Mode kid" bascule le profil actif (normal/kid) en
        // direct, indépendamment de l'onglet développeur. Renommé "Mode
        // avancé" en interface le 2026-08-26 (demande explicite) — même
        // toggle, même mécanisme, juste un terme moins technique. Suite
        // immédiate, même session : variante "kid" (BuildConfig.
        // ADVANCED_EDITION=false) — ce toggle et tout ce qu'il débloque
        // n'existent pas du tout dans cette variante ("zéro option
        // avancée"), pas juste désactivés par défaut ; devModeOn reste
        // false en permanence (cf. son chargement, forcé par le même flag).
        if (BuildConfig.ADVANCED_EDITION) {
            val devRow = UiStyle.switchRow(this, getString(R.string.toggle_dev_mode), devModeOn) { checked ->
                devModeOn = checked
                saveSettings()
                refreshParamSousOnglets()
                refreshHamburgerTabsVisibility()
                refreshModeSelectors() // fait/retire le bouton "+" (manage) selon devModeOn
                exportAdvancedSection.visibility = if (devModeOn) View.VISIBLE else View.GONE
            }
            col.addView(devRow)
        }

        // langue de l'app, indépendante de la langue système — bascule
        // immédiate (recreate) sur sélection d'une option. 2026-08-26,
        // demande explicite : "l'option pour changer de langue doit être
        // dans les paramètres de base" — déplacée depuis l'onglet Interface
        // (qui exigeait le mode avancé, ou la dérogation par profil) vers
        // ici, au-dessus des sous-onglets, toujours visible comme le reste
        // de cette section.
        val densityLang = resources.displayMetrics.density
        val langLabel = TextView(this).apply {
            text = getString(R.string.settings_language_title)
            UiStyle.body(this)
        }
        langLabel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * densityLang).toInt() }
        col.addView(langLabel)
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val langOptions = listOf(
            LocaleHelper.SYSTEM to getString(R.string.lang_system),
            "en" to getString(R.string.lang_en),
            "fr" to getString(R.string.lang_fr),
        )
        val currentLang = LocaleHelper.getLanguage(this)
        langOptions.forEach { (code, label) ->
            val btn = UiStyle.pillButton(this, label, muted = currentLang != code)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * densityLang).toInt() }
            btn.setOnClickListener {
                LocaleHelper.setLanguage(this, code)
                recreate()
            }
            langRow.addView(btn)
        }
        col.addView(langRow)

        // 2026-09-01, demande explicite : tuto rejouable à volonté, pas
        // seulement au premier lancement (cf. showTuto()).
        // 2026-09-08, demande explicite : KidOrb a de nouveau un tuto (2
        // étapes, cf. tutoSteps) — bouton remis pour les 2 variantes (retiré
        // le 2026-09-03 en même temps que le tuto lui-même).
        val tutoBtn = UiStyle.pillButton(this, getString(R.string.btn_replay_tuto), muted = true)
        tutoBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * densityLang).toInt() }
        tutoBtn.setOnClickListener { closeMenus(); showTuto() }
        col.addView(tutoBtn)

        // 2026-09-03, demande explicite : "dans les parametre de kidorb il
        // y ai les couleurs de fond, histoire de pouvoir remplire instant"
        // — cf. buildFondSection() : le bouton Palette complet (où vivait
        // cette section) est caché sur KidOrb par conception, donc gardée
        // accessible ici, au-dessus des sous-onglets comme Langue.
        buildFondSection(col)

        // 2026-08-25, demande explicite : "avoir une option pour réduire la
        // taille ou la résolution [de la page] pourrait aider non ?" (Galaxy
        // A13, ça ramait) — la page (bitmap peinture + couche humide par
        // calque) fait pageOversizeFactor² fois la surface de l'écran ;
        // réduire le facteur réduit mémoire ET le coût de la copie
        // périodique d'échantillonnage de couleur (cf. BallCanvasView.
        // applyPageSize) sans toucher à la précision des couleurs
        // mélangées. Contrepartie du réglage Performance : moins de marge
        // pour dézoomer/déplacer la page au-delà de l'écran visible — sans
        // effet en mode kid, où le zoom est de toute façon verrouillé
        // (zoomLocked_kid) donc cette marge n'est jamais visible. Déplacé
        // ici depuis Paramètres → Mode dev → Mode kid le 2026-09-04, demande
        // explicite : "faudrait que ça soit accessible sans le mode avancé...
        // histoire de permettre l'usage sur des moins bonnes machines" —
        // vivait derrière devModeOn (jamais atteignable du tout sur KidOrb,
        // où l'onglet Mode avancé n'existe pas) alors que son bénéfice
        // mémoire/performance vaut justement aussi, sinon surtout, pour
        // KidOrb.
        val perfLabel = TextView(this).apply {
            text = getString(R.string.label_canvas_quality)
            UiStyle.body(this)
        }
        perfLabel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * densityLang).toInt() }
        col.addView(perfLabel)
        val perfRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val perfBtns = mutableListOf<TextView>()
        val perfOptions = listOf(2f to getString(R.string.canvas_quality_normal), 1.4f to getString(R.string.canvas_quality_perf))
        perfOptions.forEach { (factor, label) ->
            val btn = UiStyle.pillButton(this, label, muted = pageOversizeFactor != factor)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * densityLang).toInt() }
            btn.setOnClickListener {
                pageOversizeFactor = factor
                canvas.pageOversizeFactor = factor
                perfBtns.forEachIndexed { i, b -> b.setTextColor(if (perfOptions[i].first == factor) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
                saveSettings()
            }
            perfBtns.add(btn)
            perfRow.addView(btn)
        }
        col.addView(perfRow)
        val perfHint = TextView(this).apply {
            text = getString(R.string.hint_canvas_quality)
            UiStyle.hint(this)
        }
        col.addView(perfHint)

        // 2026-08-26, demande explicite : "kidorb ne dois pas avoir de
        // selecteur de mode, il est coincer en kid" — variante "kid"
        // (BuildConfig.ADVANCED_EDITION=false) : aucun sélecteur de profil
        // du tout, pas moyen de sortir du profil Kid depuis l'app (déjà
        // seul profil livré par défaut, cf. defaultConfigProfil ; ici on
        // retire aussi la porte de sortie). Le sélecteur Fichier avait déjà
        // été retiré le 2026-08-22 pour les 2 variantes (cf. commentaire
        // buildExportPanel) — celui-ci, dans Paramètres, était le dernier.
        if (BuildConfig.ADVANCED_EDITION) {
            modeSelectorSectionParam = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val modeLabel = TextView(this).apply {
                text = getString(R.string.label_config_profil)
                UiStyle.body(this)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
            }
            modeSelectorSectionParam.addView(modeLabel)
            val modeSelectorParam = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // tag = autorise création (+) et suppression ICI seulement
            // (2026-08-22, demande explicite : Fichier ne garde que la
            // sélection des profils déjà créés) — cf. populateModeSelector().
            modeSelectorParam.tag = "manage"
            modeSelectorSectionParam.addView(modeSelectorParam)
            modeSelectorContainers.add(modeSelectorParam)
            populateModeSelector(modeSelectorParam)
            val kidHint = TextView(this).apply {
                text = getString(R.string.hint_mode_kid_actif)
                UiStyle.hint(this)
            }
            modeSelectorSectionParam.addView(kidHint)
            col.addView(modeSelectorSectionParam)
        }

        // 2026-08-31, rapporté sur le vrai écran (screenshot à l'appui) :
        // "Config moteur mélange" (4e onglet) ne tenait pas sur une seule
        // rangée avec les 3 autres — le panneau (panel_menu, wrap_content en
        // XML) ne s'élargit pas pour ses enfants, donc le dernier bouton se
        // faisait mesurer avec quasiment aucune largeur restante et son
        // texte s'empilait un caractère par ligne. VERTICAL ici, rempli par
        // rangées de 2 (cf. refreshParamSousOnglets) — même principe que
        // melangeContainer/conceptContainer plus bas dans ce fichier.
        paramSubTabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
        }
        col.addView(paramSubTabs)
        paramContent = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        col.addView(paramContent)
        refreshParamSousOnglets()
        return scroll
    }

    private fun refreshParamSousOnglets() {
        paramSubTabs.removeAllViews()
        // 2026-08-25, revert explicite (2026-08-24 était une erreur) : l'onglet
        // "Mode kid" ne doit exister QUE si devModeOn est actif — le principe
        // est que rien ne soit éditable pour l'enfant qui se perdrait dans les
        // menus ; pour éditer un mode (dont le mode kid), il faut activer le
        // mode développeur, un point c'est tout.
        // 2026-08-25, suite immédiate : "l'édition de l'interface ne doit pas
        // être visible si le mode dev n'est pas sélectionné" — l'onglet
        // "Interface" suit la même règle, SAUF dérogation explicite par
        // profil (interfaceEditVisible, toggle réservé à l'onglet développeur
        // — cf. buildParametresModeKid).
        val interfaceEligible = devModeOn || interfaceEditVisible
        val onglets = mutableListOf<Pair<String, Int>>()
        if (interfaceEligible) onglets.add(getString(R.string.tab_param_interface) to 0)
        if (devModeOn) onglets.add(getString(R.string.tab_param_mode_kid) to 1)
        // 2026-08-29, demande explicite : "un onglet pour configurer plus en
        // détail certains outils [planète/planète inverse/accélérateur]...
        // valeurs par défaut... l'outil ne sera modifiable que par leur
        // taille" — même règle que les autres onglets dev : réservé à
        // devModeOn.
        if (devModeOn) onglets.add(getString(R.string.tab_param_outils) to 2)
        // si l'onglet actuellement affiché vient de disparaître (dérogation
        // désactivée, mode dev coupé...), retombe sur le premier disponible —
        // jamais un onglet fantôme.
        if (onglets.none { it.second == paramSousOnglet }) {
            paramSousOnglet = onglets.firstOrNull()?.second ?: -1
        }
        val densityTabs = resources.displayMetrics.density
        val colsTabs = 2
        var tabsRowCur: LinearLayout? = null
        onglets.forEachIndexed { idx, (label, i) ->
            if (idx % colsTabs == 0) {
                tabsRowCur = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                paramSubTabs.addView(tabsRowCur)
            }
            val btn = UiStyle.pillButton(this, label, muted = paramSousOnglet != i)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * densityTabs).toInt()
                bottomMargin = (6 * densityTabs).toInt()
            }
            btn.setOnClickListener {
                paramSousOnglet = i
                refreshParamSousOnglets()
            }
            tabsRowCur!!.addView(btn)
        }
        paramContent.removeAllViews()
        when (paramSousOnglet) {
            0 -> paramContent.addView(buildParametresInterface())
            1 -> paramContent.addView(buildParametresModeKid())
            2 -> paramContent.addView(buildParametresOutils())
        }
    }

    /** Valeurs par défaut des outils "modificateurs" (planète, planète
     *  inverse, accélérateur) — 2026-08-29, demande explicite : réglées une
     *  fois ici plutôt qu'avec un slider par objet posé sur le canvas (jugé
     *  "moins lisible à l'usage"). Seule la taille reste éditable par objet
     *  (poignée existante, inchangée) — cf. BallCanvasView.planeteMassDefault
     *  et consorts. */
    private fun buildParametresOutils(): View {
        // pas de ScrollView ici : buildParametresPanel() en pose déjà un
        // autour de tout le panneau Paramètres (scroll imbriqué = conflit
        // de gestes, cf. commentaire là-bas, 2026-09-03).
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val hint = TextView(this).apply {
            text = getString(R.string.hint_outils_defaults)
            UiStyle.hint(this)
        }
        col.addView(hint)

        val planeteLabel = TextView(this).apply {
            text = getString(R.string.label_planete_mass)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * resources.displayMetrics.density).toInt() }
        }
        col.addView(planeteLabel)
        addSliderPow(col, getString(R.string.slider_gravite), BallCanvasView.PLANETE_MASS_MAX, planeteMassDefault, power = 2f) {
            planeteMassDefault = it
            canvas.planeteMassDefault = it
            saveSettings()
        }

        val planeteInverseLabel = TextView(this).apply {
            text = getString(R.string.label_planete_inverse_mass)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * resources.displayMetrics.density).toInt() }
        }
        col.addView(planeteInverseLabel)
        addSliderPow(col, getString(R.string.slider_gravite), BallCanvasView.PLANETE_MASS_MAX, planeteInverseMassDefault, power = 2f) {
            planeteInverseMassDefault = it
            canvas.planeteInverseMassDefault = it
            saveSettings()
        }

        // 2026-08-29, demande explicite : "quand la balle passe dans la
        // portée de la planète, ça annulerait la gravité du téléphone...
        // probablement qu'il faut un toggle aussi" — sans ça, l'inclinaison
        // du téléphone continue de tirer la bille pendant l'orbite, ce qui
        // brouille l'effet. Réutilise nearPlanete (déjà calculé pour couper
        // la friction en orbite, cf. BallCanvasView.step()).
        val tiltCancelRow = UiStyle.switchRow(this, getString(R.string.toggle_planete_cancel_tilt), planeteCancelTilt) { checked ->
            planeteCancelTilt = checked
            canvas.planeteCancelTilt = checked
            saveSettings()
        }
        tiltCancelRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (14 * resources.displayMetrics.density).toInt() }
        col.addView(tiltCancelRow)
        val tiltCancelHint = TextView(this).apply {
            text = getString(R.string.hint_planete_cancel_tilt)
            UiStyle.hint(this)
        }
        col.addView(tiltCancelHint)

        val accelerateurLabel = TextView(this).apply {
            text = getString(R.string.label_accelerateur_gauge)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * resources.displayMetrics.density).toInt() }
        }
        col.addView(accelerateurLabel)
        addSlider(col, getString(R.string.slider_jauge), -1f, 1f, accelerateurGaugeDefault) {
            accelerateurGaugeDefault = it
            canvas.accelerateurGaugeDefault = it
            saveSettings()
        }

        return col
    }


    /** Mise en page générale de l'interface — indépendant du mode kid. */
    private fun buildParametresInterface(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // curseur d'échelle du menu latéral (barre d'icônes) — transform
        // visuel (scaleX/scaleY), pas un retracé des tailles individuelles
        addSlider(col, getString(R.string.slider_side_menu_size), 0.7f, 1.5f, tabbarScale) {
            tabbarScale = it
            tabbarView.scaleX = it
            tabbarView.scaleY = it
            sauverProfilConfigActif()
        }
        // espacement entre le cluster de contrôles (raccourcis) et le bord
        // d'écran — même marge des 2 côtés, cf. applyControlsSide
        // (2026-08-13, demande explicite). Plage élargie à -20..300
        // (2026-08-14, suite immédiate : "les raccourcis aussi" — même
        // élargissement que la hauteur, 40 en haut ne suffisait pas).
        addSlider(col, getString(R.string.slider_edge_spacing), -20f, 300f, controlsEdgeMargin) {
            controlsEdgeMargin = it
            applyControlsSide()
            sauverProfilConfigActif()
        }
        // position verticale du cluster de raccourcis — distance depuis le
        // bas de l'écran (2026-08-13, demande explicite : "j'aimerais
        // pouvoir le monter et le descendre aussi"). Mini élargi à -200
        // (2026-08-14, suite immédiate : "le curseur doit permettre de
        // descendre davantage la barre des raccourcis" — -20 ne suffisait
        // pas à descendre assez).
        addSlider(col, getString(R.string.slider_shortcuts_height), -200f, 600f, controlsBottomMargin) {
            controlsBottomMargin = it
            applyControlsSide()
            sauverProfilConfigActif()
        }
        // position verticale du panneau à onglets — indépendante du cluster
        // ci-dessus (2026-08-14, demande explicite : "faudrait pouvoir bouger
        // le menu des raccourcis indépendamment que la fenêtre avec les onglets")
        addSlider(col, getString(R.string.slider_tabs_panel_height), -20f, 600f, panelBottomMargin) {
            panelBottomMargin = it
            applyControlsSide()
            sauverProfilConfigActif()
        }

        return col
    }

    /** Verrouillage, mode enfant + sélecteurs, outils désactivables — tout
     *  ce qui sert à préparer/restreindre l'app pour un enfant. */
    private fun buildParametresModeKid(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Mode développeur + Mode kid : les deux toggles vivent maintenant
        // dans buildParametresPanel() (au-dessus des sous-onglets, toujours
        // visibles) — 2026-08-21, demande explicite : "il faut 2 toggle...
        // dans les parametres" (pas ici, dans l'onglet lui-même). Cet
        // onglet n'est de toute façon construit QUE si devModeOn est actif
        // (cf. refreshParamSousOnglets), donc pas besoin d'y re-gater quoi
        // que ce soit — 2026-08-25, confirmé explicitement après un aller-
        // retour (2026-08-24 : rien n'est éditable sans mode dev, principe
        // non négociable — l'enfant qui se perdrait dans ce menu ne doit
        // jamais pouvoir l'atteindre).

        // 2026-08-25, demande explicite : "il faut un toggle dans mode dev
        // pour choisir d'afficher ou cacher [l'édition de l'interface] par
        // profil" — dérogation par profil à la règle ci-dessus, réservée à
        // CET onglet (donc déjà protégée par devModeOn) : active, l'onglet
        // "Interface" (curseurs de mise en page + langue) reste visible pour
        // ce profil même quand le mode dev est ensuite désactivé.
        val interfaceVisibleRow = UiStyle.switchRow(
            this, getString(R.string.toggle_interface_edit_visible), interfaceEditVisible
        ) { checked ->
            interfaceEditVisible = checked
            saveSettings()
            sauverProfilConfigActif()
            refreshParamSousOnglets()
        }
        interfaceVisibleRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        col.addView(interfaceVisibleRow)

        // Verrouillage de l'app (2026-08-20, demande explicite : "ma fille
        // appuie un peu partout et fini par ouvrire lez notifications ou
        // appuiyer sur les boutons android poue naviguer dedant") — épingle
        // l'écran (Android startLockTask/stopLockTask, API native, aucune
        // dépendance ajoutée) : bloque Accueil/Récents et limite l'accès
        // aux notifications tant que c'est actif. Pas de statut « propriétaire
        // de l'appareil » requis (ça, c'est le kiosque complet — bien plus
        // intrusif, à provisionner en usine) : ici l'utilisateur reste libre
        // de désépingler par le geste système standard (maintenir Retour +
        // Récents, ou glisser-maintenir selon la version d'Android), OU par
        // le bouton discret dédié (2026-08-21, demande explicite : "ajouter
        // aussi un bouton quitter le mode kid discret dans l'app") — cf.
        // btnQuitterModeKid, affiché tant que appLocked est vrai. Pas
        // persisté entre sessions (redémarrer l'app redemande l'activation).
        // Dupliqué dans l'onglet Fichier (cf. buildExportPanel) — 2026-08-21,
        // demande explicite : "pas de raison que ca soit pas dans le menu
        // file aussi" — même widget construit deux fois (build*, pas une
        // vue partagée : une View Android ne peut avoir qu'un seul parent),
        // toujours visible dans les deux, sans condition de mode kid.
        col.addView(buildAppLockRow())

        // 2026-08-25, demande explicite : "je dois pouvoir désactiver dans
        // le menu dev les différents logs" — app donnée à quelqu'un d'autre,
        // plus de raison d'accumuler un fichier de diagnostic dans son
        // Téléchargements. Réservé au mode dev (déjà le cas : tout cet
        // onglet ne se construit que si devModeOn est actif).
        val density3 = resources.displayMetrics.density
        val logRow = UiStyle.switchRow(this, getString(R.string.toggle_usage_log), usageLogEnabled) { checked ->
            usageLogEnabled = checked
            UsageLog.enabled = checked
            saveSettings()
        }
        logRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density3).toInt() }
        col.addView(logRow)
        val logHint = TextView(this).apply {
            text = getString(R.string.hint_usage_log)
            UiStyle.hint(this)
        }
        col.addView(logHint)

        // Qualité du canvas déplacée dans buildParametresPanel() (2026-09-04,
        // demande explicite : "faudrait que ça soit accessible sans le mode
        // avancé... histoire de permettre l'usage sur des moins bonnes
        // machines") — cf. commentaire là-bas : ce réglage n'a rien de
        // risqué à exposer à un enfant (contrairement au reste de cet
        // onglet, verrouillé derrière devModeOn par principe), et son
        // bénéfice mémoire/performance vaut justement aussi (surtout ?) pour
        // KidOrb, où l'onglet Mode avancé n'existe même pas.

        // 2026-08-29, en test réel avec Wian : la bille retombe dans la
        // couleur dominante de l'écran dès qu'elle la retouche, le fondu
        // pigmentaire étant symétrique et sans mémoire — cf. commentaire de
        // BallCanvasView.melangeExperiment pour le détail. "Désactivé" =
        // comportement actuel, INCHANGÉ par défaut ; les 3 autres sont des
        // pistes à comparer en usage réel, aucune tranchée pour l'instant —
        // ne remplace pas les réglages de fondu existants, s'ajoute par-dessus.
        // 2026-08-31, resté volontairement global (pas une propriété de
        // bille) : un algorithme d'échantillonnage, pas une identité de
        // bille, contrairement à fonduRate/mixVividMode/etc. reconnectés à
        // l'éditeur de bille le même jour ("on peut reconnecter ça aux
        // billes proprement").
        val melangeLabel = TextView(this).apply {
            text = getString(R.string.label_melange_experiment)
            UiStyle.body(this)
        }
        melangeLabel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density3).toInt() }
        col.addView(melangeLabel)
        val melangeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val melangeOptions = listOf(
            getString(R.string.melange_experiment_off) to 0,
            getString(R.string.melange_experiment_protection) to 1,
            getString(R.string.melange_experiment_rayon) to 2,
            getString(R.string.melange_experiment_retour_lent) to 3,
        )
        val melangeBtns = mutableListOf<TextView>()
        val colsMelange = 2
        var melangeRowCur: LinearLayout? = null
        melangeOptions.forEachIndexed { idx, (label, mode) ->
            if (idx % colsMelange == 0) {
                melangeRowCur = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                melangeContainer.addView(melangeRowCur)
            }
            val btn = UiStyle.pillButton(this, label, muted = melangeExperiment != mode)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density3).toInt()
                bottomMargin = (6 * density3).toInt()
            }
            btn.setOnClickListener {
                melangeExperiment = mode
                canvas.melangeExperiment = mode
                melangeBtns.forEachIndexed { i, b ->
                    b.setTextColor(if (melangeOptions[i].second == mode) UiStyle.TEXT else UiStyle.TEXT_MUTED)
                }
                saveSettings()
                UsageLog.d("melangeExperiment = $mode")
            }
            melangeBtns.add(btn)
            melangeRowCur!!.addView(btn)
        }
        col.addView(melangeContainer)
        val melangeHint = TextView(this).apply {
            text = getString(R.string.hint_melange_experiment)
            UiStyle.hint(this)
        }
        col.addView(melangeHint)

        // 2026-08-30, retour d'un testeur externe transmis par Wian : "la
        // bille est lente et donne une sensation de lourdeur, elle met du
        // temps à se mettre en mouvement" — la courbe au carré de
        // tiltCurveMode (active sur la bille par défaut) écrase la réponse
        // à une inclinaison normale. Même principe que melangeExperiment
        // ci-dessus : plusieurs pistes à comparer en usage réel, aucune
        // tranchée pour l'instant.
        val tiltLabel = TextView(this).apply {
            text = getString(R.string.label_tilt_response_experiment)
            UiStyle.body(this)
        }
        tiltLabel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density3).toInt() }
        col.addView(tiltLabel)
        val tiltContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tiltOptions = listOf(
            getString(R.string.tilt_response_current) to 0,
            getString(R.string.tilt_response_lineaire) to 1,
            getString(R.string.tilt_response_adouci) to 2,
        )
        val tiltBtns = mutableListOf<TextView>()
        tiltOptions.forEach { (label, mode) ->
            val btn = UiStyle.pillButton(this, label, muted = tiltResponseMode != mode)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density3).toInt()
                bottomMargin = (6 * density3).toInt()
            }
            btn.setOnClickListener {
                tiltResponseMode = mode
                canvas.tiltResponseMode = mode
                tiltBtns.forEachIndexed { i, b ->
                    b.setTextColor(if (tiltOptions[i].second == mode) UiStyle.TEXT else UiStyle.TEXT_MUTED)
                }
                saveSettings()
                UsageLog.d("tiltResponseMode = $mode")
            }
            tiltBtns.add(btn)
            tiltContainer.addView(btn)
        }
        col.addView(tiltContainer)
        val tiltHint = TextView(this).apply {
            text = getString(R.string.hint_tilt_response_experiment)
            UiStyle.hint(this)
        }
        col.addView(tiltHint)

        // 2026-08-30 : la vivacité du mélange (Actuel/Chroma renforcée/
        // Fondu de teinte) vivait ici en réglage GLOBAL — déplacée dans
        // l'éditeur de bille (propre à chaque bille, cf. BilleProfile.
        // mixVividMode) sur retour explicite : "faut des options par billes
        // et un setup général fiable", le réglage global étant invisible
        // depuis la bille et pas mémorisé de façon fiable d'une session à
        // l'autre.

        // zoom verrouillé (2026-08-21, demande explicite : "bloquer le zoom
        // dezoom a la taille de lecran pour que le canva soit remplis et
        // sue quand on exporte l'oeuvre il soit rempli pour le mode kid") —
        // fige la caméra à l'échelle écran (plus de pinch zoom/pan) et
        // l'export capture alors exactement cette fenêtre, jamais la page
        // entière (cf. BallCanvasView.zoomLocked / renderArtwork).
        val zoomLockRow = UiStyle.switchRow(this, getString(R.string.toggle_zoom_lock), zoomLocked) {
            zoomLocked = it
            canvas.zoomLocked = it
            saveSettings()
            sauverProfilConfigActif()
        }
        zoomLockRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
        col.addView(zoomLockRow)

        // Type de sélecteur déplacé dans buildPalettePanel() (2026-09-04,
        // demande explicite : "le type de sélecteur de couleur devrait être
        // le menu palette plutôt que planqué aussi") — planqué ici derrière
        // devModeOn alors que le bouton Palette (menu où il vit maintenant)
        // est visible par défaut sur InkOrb, sans mode dev.

        // taille de la roue (2026-08-20, demande explicite : "pour regler sa
        // taille dans les options")
        addSlider(col, getString(R.string.slider_wheel_size), 80f, 260f, wheelSizeDp) {
            wheelSizeDp = it
            val sizePx = (it * resources.displayMetrics.density).toInt()
            colorWheel.layoutParams = (colorWheel.layoutParams as LinearLayout.LayoutParams).apply {
                width = sizePx
                height = sizePx
            }
            colorWheel.requestLayout()
            joystickPalette.layoutParams = (joystickPalette.layoutParams as LinearLayout.LayoutParams).apply {
                width = sizePx
                height = sizePx
            }
            sauverProfilConfigActif()
            joystickPalette.requestLayout()
            if (modeEnfant && pickerType != 0) positionWheelPanel() // repositionne (grandit vers le haut)
        }

        // outils désactivables (2026-08-20, demande explicite : "la
        // possibilité de desactiver des outils comme ca je peux introduire
        // progressivement a lenfant. deja par section d outil et puis
        // outil par outil. et les espace vide libere de la place") —
        // interrupteur = outil VISIBLE (coché = affiché), regroupés par
        // section comme les sous-menus existants (Formes/Effets), plus une
        // section Autres pour Sélection/Œil construction.
        val outilsTitle = TextView(this).apply {
            text = getString(R.string.section_title_outils)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * resources.displayMetrics.density).toInt() }
        }
        col.addView(outilsTitle)
        fun toolRow(key: String, label: String): Pair<LinearLayout, Switch> {
            val row = UiStyle.switchRow(this, label, key !in disabledTools) { checked ->
                if (checked) disabledTools.remove(key) else disabledTools.add(key)
                refreshToolVisibilityCb?.invoke()
                saveSettings()
                sauverProfilConfigActif()
            }
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
            return row to (row.getChildAt(1) as Switch)
        }
        // interrupteur maître par catégorie (2026-08-20, demande explicite :
        // "jai besoin de toggle par categorie") — bascule tous les outils de
        // la section d'un coup ; les switches individuels suivent (mis à
        // jour directement, sans redéclencher leur propre listener via un
        // faux événement — juste leur état visuel + disabledTools).
        fun categorySection(title: String, tools: List<Pair<String, String>>) {
            col.addView(TextView(this).apply {
                text = title
                UiStyle.hint(this)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            })
            val rowsAndSwitches = tools.map { (key, label) -> key to toolRow(key, label) }
            val masterRow = UiStyle.switchRow(
                this, getString(R.string.toggle_category_all, title), tools.all { it.first !in disabledTools }
            ) { checked ->
                rowsAndSwitches.forEach { (key, pair) ->
                    if (checked) disabledTools.remove(key) else disabledTools.add(key)
                    pair.second.isChecked = checked
                }
                refreshToolVisibilityCb?.invoke()
                saveSettings()
                sauverProfilConfigActif()
            }
            masterRow.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (2 * resources.displayMetrics.density).toInt() }
            (masterRow.getChildAt(0) as TextView).apply { UiStyle.body(this) } // libellé maître en gras visuel léger (pas muted comme les autres)
            col.addView(masterRow)
            rowsAndSwitches.forEach { (_, pair) -> col.addView(pair.first) }
        }
        categorySection(getString(R.string.section_shapes), listOf(
            "mur" to getString(R.string.cd_wall),
            "bouchon" to getString(R.string.cd_bumper),
            "triangle" to getString(R.string.cd_triangle),
            "bezier" to getString(R.string.cd_bezier),
            "trampoline" to getString(R.string.cd_trampoline),
            "rectangle" to getString(R.string.cd_rectangle),
            "ellipse" to getString(R.string.cd_ellipse),
        ))
        categorySection(getString(R.string.section_effects), listOf(
            "portail" to getString(R.string.cd_portal),
            "planete" to getString(R.string.cd_planet),
            "planete_inverse" to getString(R.string.cd_antiplanet),
            "accelerateur" to getString(R.string.cd_accelerator),
        ))
        categorySection(getString(R.string.section_other_tools), listOf(
            "select" to getString(R.string.cd_select),
            "construction_eye" to getString(R.string.cd_construction_visibility),
            "undo" to getString(R.string.cd_undo),
            "redo" to getString(R.string.cd_redo),
            "palette" to getString(R.string.cd_open_palette),
        ))
        // 2026-08-22, demande explicite : "un système pour rajouter des
        // nouveaux types de pinceaux et pouvoir les désactiver dans le mode
        // développeur" — liste DYNAMIQUE (pas une liste fixe comme les
        // sections au-dessus) : une entrée par pinceau créé, clé stable =
        // son nom (cf. refreshPinceauSetupSelector, qui masque les pinceaux
        // dont la clé est dans disabledTools sans jamais les supprimer).
        if (pinceauProfiles.isNotEmpty()) {
            categorySection(getString(R.string.section_pinceaux), pinceauProfiles.map { "pinceau:${it.nom}" to it.nom })
        }

        // Supprimer LE profil actif (2026-08-22, demande explicite : "a la
        // suite des toggles [de ce profil] tout en un bouton pour supprimer
        // le profil qui est sélectionné" — après avoir essayé une liste de
        // tous les profils à supprimer, séparée de la sélection, jugée trop
        // indirecte). Un seul bouton, agit sur configProfilActif (celui dont
        // on vient de régler tous les toggles ci-dessus) ; masqué s'il ne
        // reste qu'un profil.
        if (modeProfils.size > 1) {
            val delBtn = UiStyle.pillButton(this, getString(R.string.btn_delete_profil), muted = true)
            delBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (18 * resources.displayMetrics.density).toInt() }
            delBtn.setOnClickListener {
                val suppr = configProfilActif
                modeProfils.removeAll { it.id == suppr }
                configProfilActif = modeProfils.first().id
                saveModeProfils()
                saveSettings()
                appliquerProfilConfig(configProfilActif)
                refreshModeSelectors()
                refreshParamSousOnglets()
            }
            col.addView(delBtn)
        }

        return col
    }

    // ---------------------------------------------------------------- bille setup

    /** Charge la bibliothèque de billes (prefs → billeProfiles). Amorce aux 6
     *  billes construites au fil des sessions (Balle/Rebondissante/Arc en
     *  ciel/Comète/Psychédélique/Microbilles) quand aucune pref n'existe —
     *  2026-08-20 disait « pas de presets pré-remplis, l'utilisateur
     *  construit sa bibliothèque lui-même », mais 2026-08-25, demande
     *  explicite contraire : "que les profils soient sauvegardés dans
     *  l'app, pas dans les data du téléphone, parce que je l'ai donné à
     *  quelqu'un et les profils étaient pas là" — SharedPreferences ne
     *  survit pas à une install sur un autre appareil ; les valeurs figées
     *  ici viennent du dernier `KidOrb-profils-backup.json` exporté. */
    private fun loadBilleProfiles() {
        billeProfiles.clear()
        // 2026-08-29, BUG TROUVÉ + demande explicite : "je veux que la
        // première bille qui apparaît... ce soit la première balle
        // configurée" — pour un appareil avec des profils déjà sauvegardés
        // (count>0), appliquerBilleProfile() n'était JAMAIS appelée au
        // démarrage (seulement dans la branche count==0 ci-dessous) : le
        // canvas restait sur BALL_COLOR, la constante générique de
        // BallCanvasView (#202124, gris très sombre — d'où "la première
        // bille est noire" alors qu'aucun profil n'a cette couleur).
        // billeActiveIndex n'est plus relu depuis les prefs non plus :
        // toujours 0 au lancement, plus de mémorisation du dernier choix
        // entre sessions.
        billeActiveIndex = 0
        val count = prefs.getInt("billeProfileCount", 0)
        if (count == 0) {
            billeProfiles.addAll(defaultBilleProfiles())
        } else {
            for (i in 0 until count) {
                val k = "billeProfile_$i"
                val nomLu = prefs.getString("${k}_nom", null) ?: getString(R.string.default_bille_name, i + 1)
                billeProfiles.add(
                    BilleProfile(
                        nom = nomLu,
                        couleurBille = prefs.getInt("${k}_couleurBille", 0xFF202124.toInt()),
                        ballRadiusDp = prefs.getFloat("${k}_ballRadiusDp", 34f),
                        restitution = prefs.getFloat("${k}_restitution", 0.85f),
                        poids = prefs.getFloat("${k}_poids", 1f),
                        frictionRate = prefs.getFloat("${k}_frictionRate", 0.6f),
                        tiltEffect = prefs.getFloat("${k}_tiltEffect", 1f),
                        textureAmount = prefs.getFloat("${k}_textureAmount", 0f),
                        fonduRate = prefs.getFloat("${k}_fonduRate", 0.4f),
                        shakeThreshold = prefs.getFloat("${k}_shakeThreshold", 3.5f),
                        shakeToVel = prefs.getFloat("${k}_shakeToVel", 800f),
                        vitesseEpaisseur = prefs.getBoolean("${k}_vitesseEpaisseur", false),
                        boundsActive = prefs.getBoolean("${k}_boundsActive", true),
                        wrapActive = prefs.getBoolean("${k}_wrapActive", false),
                        ballVisible = prefs.getBoolean("${k}_ballVisible", true),
                        ballGrabInPinceau = prefs.getBoolean("${k}_ballGrabInPinceau", false),
                        tiltCurveMode = prefs.getBoolean("${k}_tiltCurveMode", false),
                        icone = prefs.getString("${k}_icone", null),
                        volumeMode = prefs.getBoolean("${k}_volumeMode", false),
                        mixVividMode = prefs.getInt("${k}_mixVividMode", 0),
                        trailEdgeMode = prefs.getInt("${k}_trailEdgeMode", 0),
                        cometTrail = prefs.getBoolean("${k}_cometTrail", false),
                        rainbowMode = prefs.getBoolean("${k}_rainbowMode", false),
                        speedColorMode = prefs.getBoolean("${k}_speedColorMode", false),
                        iconScale = prefs.getFloat("${k}_iconScale", 1f),
                        // 2026-09-02, demande explicite : "les 7 premières
                        // [ses 6 billes d'usine + sa bille perso Comète arc-
                        // en-ciel] ne doivent pas vibrer" — la protection ne
                        // se limite pas aux 6 noms d'usine, elle "grand-père"
                        // TOUT profil déjà sauvegardé avant l'ajout de ce
                        // champ (clé `_isDefault` absente = créé avant que la
                        // notion existe = protégé) ; seule une bille créée
                        // APRÈS ce champ (via le + ou l'éditeur avancé,
                        // isDefault=false explicite) reste supprimable.
                        isDefault = prefs.getBoolean("${k}_isDefault", true),
                    )
                )
            }
        }
        // pousse la 1ère bille de la bibliothèque vers le canvas dans les 2
        // cas (avant : uniquement quand count==0) — sans ça le canvas
        // démarre sur les constantes DEF_*/BALL_COLOR génériques de
        // BallCanvasView au lieu du preset réellement configuré.
        appliquerBilleProfile(0)
    }

    /** Les 6 billes par défaut à l'installation — cf. commentaire de
     *  loadBilleProfiles(). Noms fixes (pas via strings.xml) : ce sont des
     *  noms propres donnés par l'utilisateur à ses créations, pas du texte
     *  d'interface à traduire. Valeurs reprises telles que configurées par
     *  Wian sur son Fairphone en usage réel (extraites de
     *  shared_prefs/encrebille.xml, 2026-09-03) — remplace les valeurs
     *  d'origine, ajustées à la main au fil des sessions (fonduRate
     *  uniformisé à 0.15 sur la plupart des billes, trailEdgeMode=2 "Net +
     *  doux" adopté partout sauf Psychédélique, couleur de Psychédélique
     *  changée, Balle passée en restitution 0.95 au lieu de 0.165). */
    private fun defaultBilleProfiles(): List<BilleProfile> = listOf(
        BilleProfile(
            nom = "Balle", couleurBille = -14680051, ballRadiusDp = 19.868f, restitution = 0.95f,
            poids = 2.5f, frictionRate = 0f, tiltEffect = 1f, textureAmount = 0f, fonduRate = 0.15f,
            shakeThreshold = 3.5f, shakeToVel = 800f, vitesseEpaisseur = false,
            boundsActive = true, wrapActive = false, ballVisible = true, ballGrabInPinceau = true,
            tiltCurveMode = true, trailEdgeMode = 2,
            icone = null, volumeMode = true, isDefault = true,
        ),
        BilleProfile(
            nom = "Rebondissante", couleurBille = -3669917, ballRadiusDp = 13.638f, restitution = 0.95f,
            poids = 2.5f, frictionRate = 0.174f, tiltEffect = 1f, textureAmount = 0f, fonduRate = 0.15f,
            shakeThreshold = 3.5f, shakeToVel = 800f, vitesseEpaisseur = false,
            boundsActive = true, wrapActive = false, ballVisible = true, ballGrabInPinceau = true,
            tiltCurveMode = true, trailEdgeMode = 2,
            icone = "rebondissante", volumeMode = true, isDefault = true,
        ),
        BilleProfile(
            nom = "Arc en ciel", couleurBille = -8531458, ballRadiusDp = 34f, restitution = 0.85f,
            poids = 1f, frictionRate = 0.6f, tiltEffect = 1f, textureAmount = 0f, fonduRate = 0.15f,
            shakeThreshold = 3.5f, shakeToVel = 800f, vitesseEpaisseur = false,
            boundsActive = true, wrapActive = false, ballVisible = true, ballGrabInPinceau = true,
            tiltCurveMode = false, trailEdgeMode = 2,
            icone = "arc_en_ciel", volumeMode = true, rainbowMode = true, isDefault = true,
        ),
        BilleProfile(
            nom = "Comète", couleurBille = -77312, ballRadiusDp = 34f, restitution = 0.85f,
            poids = 1f, frictionRate = 0f, tiltEffect = 1f, textureAmount = 0f, fonduRate = 0.15f,
            shakeThreshold = 3.5f, shakeToVel = 800f, vitesseEpaisseur = false,
            boundsActive = true, wrapActive = true, ballVisible = true, ballGrabInPinceau = true,
            tiltCurveMode = true, trailEdgeMode = 2,
            icone = "comete", volumeMode = true, cometTrail = true, speedColorMode = true, isDefault = true,
        ),
        BilleProfile(
            nom = "Psychédélique", couleurBille = -4771330, ballRadiusDp = 39.27f, restitution = 0f,
            poids = 2.5f, frictionRate = 0.993f, tiltEffect = 1f, textureAmount = 0f, fonduRate = 0.9f,
            shakeThreshold = 3.5f, shakeToVel = 800f, vitesseEpaisseur = false,
            boundsActive = true, wrapActive = true, ballVisible = true, ballGrabInPinceau = true,
            tiltCurveMode = true, trailEdgeMode = 0,
            icone = "psychedelique", volumeMode = true, cometTrail = true, isDefault = true,
        ),
        BilleProfile(
            nom = "Microbilles", couleurBille = -16743508, ballRadiusDp = 4.293f, restitution = 1f,
            poids = 2.5f, frictionRate = 0.411f, tiltEffect = 1f, textureAmount = 0f, fonduRate = 0.15f,
            shakeThreshold = 10f, shakeToVel = 3000f, vitesseEpaisseur = false,
            boundsActive = true, wrapActive = false, ballVisible = true, ballGrabInPinceau = true,
            tiltCurveMode = false, trailEdgeMode = 2,
            // 2026-09-01, retour direct : "microbille est trop grosse" —
            // vignette réduite manuellement (cf. iconScale), la seule bille
            // par défaut à s'écarter de la taille de référence.
            icone = null, volumeMode = true, cometTrail = true, iconScale = 0.6f, isDefault = true,
        ),
    )

    private fun saveBilleProfiles() {
        val e = prefs.edit()
        e.putInt("billeProfileCount", billeProfiles.size)
        e.putInt("billeActiveIndex", billeActiveIndex)
        billeProfiles.forEachIndexed { i, p ->
            val k = "billeProfile_$i"
            e.putString("${k}_nom", p.nom)
            e.putInt("${k}_couleurBille", p.couleurBille)
            e.putFloat("${k}_ballRadiusDp", p.ballRadiusDp)
            e.putFloat("${k}_restitution", p.restitution)
            e.putFloat("${k}_poids", p.poids)
            e.putFloat("${k}_frictionRate", p.frictionRate)
            e.putFloat("${k}_tiltEffect", p.tiltEffect)
            e.putFloat("${k}_textureAmount", p.textureAmount)
            e.putFloat("${k}_fonduRate", p.fonduRate)
            e.putFloat("${k}_shakeThreshold", p.shakeThreshold)
            e.putFloat("${k}_shakeToVel", p.shakeToVel)
            e.putBoolean("${k}_vitesseEpaisseur", p.vitesseEpaisseur)
            e.putBoolean("${k}_boundsActive", p.boundsActive)
            e.putBoolean("${k}_wrapActive", p.wrapActive)
            e.putBoolean("${k}_ballVisible", p.ballVisible)
            e.putBoolean("${k}_ballGrabInPinceau", p.ballGrabInPinceau)
            e.putBoolean("${k}_tiltCurveMode", p.tiltCurveMode)
            e.putString("${k}_icone", p.icone)
            e.putBoolean("${k}_volumeMode", p.volumeMode)
            e.putInt("${k}_mixVividMode", p.mixVividMode)
            e.putInt("${k}_trailEdgeMode", p.trailEdgeMode)
            e.putBoolean("${k}_cometTrail", p.cometTrail)
            e.putBoolean("${k}_rainbowMode", p.rainbowMode)
            e.putBoolean("${k}_speedColorMode", p.speedColorMode)
            e.putFloat("${k}_iconScale", p.iconScale)
            e.putBoolean("${k}_isDefault", p.isDefault)
        }
        e.apply()
    }

    /** Applique une bille au canvas — écrit tout le paquet de réglages d'un
     *  coup. Ne touche PAS position/couleur portée/réserve d'encre : le
     *  dessin en cours continue (2026-08-20, demande explicite : "si en
     *  cour de route je choisi une autre bille et bien je continue mon
     *  dessin avec"). */
    /** Résout l'icône + le tint d'une bille pour l'affichage (sélecteurs,
     *  bouton raccourci) — icône de concept choisie (cf.
     *  buildBilleProfileEditor), affichée SANS tint (elle porte ses propres
     *  couleurs) ; sinon un disque généré avec un vrai dégradé de volume
     *  (cf. billeSphereDrawable), teinté par couleurBille. */
    private fun applyBilleIcon(iv: ImageView, p: BilleProfile) {
        val res = when (p.icone) {
            "arc_en_ciel" -> R.drawable.ic_bille_arcenciel
            "psychedelique" -> R.drawable.ic_bille_psychedelique
            "comete" -> R.drawable.ic_bille_comete
            "comete_arcenciel" -> R.drawable.ic_bille_comete_arcenciel
            "rebondissante" -> R.drawable.ic_bille_rebondissante
            else -> null
        }
        if (res != null) {
            iv.setImageResource(res)
            iv.imageTintList = null
        } else {
            iv.imageTintList = null
            iv.setImageDrawable(billeSphereDrawable(p.couleurBille))
        }
    }

    /** Génère un disque avec un dégradé radial (clair en haut-gauche, sombre
     *  en bas-droite) à partir d'une couleur de base — donne un vrai effet de
     *  volume. 2026-08-25, rapporté sur les icônes de bille : "ça ne reflète
     *  pas une bille en volume... ce sont des icônes plates" — le tint simple
     *  (une seule teinte, alpha seul sur ic_balle.xml) ne peut PAS produire de
     *  clair ET de sombre à la fois avec une couleur de base déjà extrême
     *  (quasi noire pour "Classique", ou très claire) : à alpha égal, un
     *  survol plus sombre de la même teinte reste noir sur noir, invisible.
     *  Ici la teinte claire/sombre est calculée directement à partir de la
     *  couleur, jamais par simple alpha — visible quelle que soit la couleur. */
    private fun billeSphereDrawable(baseColor: Int): Drawable {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - 2f
        fun mix(c: Int, target: Int, factor: Float): Int {
            val f = factor.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(c) + (Color.red(target) - Color.red(c)) * f).toInt().coerceIn(0, 255),
                (Color.green(c) + (Color.green(target) - Color.green(c)) * f).toInt().coerceIn(0, 255),
                (Color.blue(c) + (Color.blue(target) - Color.blue(c)) * f).toInt().coerceIn(0, 255),
            )
        }
        val light = mix(baseColor, Color.WHITE, 0.55f)
        val dark = mix(baseColor, Color.BLACK, 0.45f)
        val shader = RadialGradient(cx - r * 0.35f, cy - r * 0.35f, r * 1.8f, light, dark, Shader.TileMode.CLAMP)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(cx, cy, r, paint)
        return BitmapDrawable(resources, bmp)
    }

    private fun appliquerBilleProfile(index: Int) {
        val p = billeProfiles.getOrNull(index) ?: return
        // 2026-08-25, demande explicite : "je parle de l'icône, quand la
        // bille est sélectionnée elle doit refléter la bonne, pas un icône
        // générique" — le bouton raccourci (btnBalle, toolbar principale)
        // reflète maintenant la bille active, comme les sélecteurs.
        applyBilleIcon(btnBalle, p)
        canvas.ballColor = p.couleurBille
        canvas.ballRadiusDp = p.ballRadiusDp
        canvas.restitution = p.restitution
        canvas.poids = p.poids
        canvas.frictionRate = p.frictionRate
        canvas.tiltEffect = p.tiltEffect
        canvas.textureAmount = p.textureAmount
        canvas.fonduRate = p.fonduRate
        canvas.shakeThreshold = p.shakeThreshold
        canvas.shakeToVel = p.shakeToVel
        canvas.vitesseEpaisseur = p.vitesseEpaisseur
        canvas.boundsActive = p.boundsActive
        canvas.wrapActive = p.wrapActive
        canvas.ballVisible = p.ballVisible
        canvas.ballGrabInPinceau = p.ballGrabInPinceau
        canvas.tiltCurveMode = p.tiltCurveMode
        canvas.volumeMode = p.volumeMode
        canvas.mixVividMode = p.mixVividMode
        canvas.trailEdgeMode = p.trailEdgeMode
        canvas.cometTrail = p.cometTrail
        canvas.rainbowMode = p.rainbowMode
        canvas.speedColorMode = p.speedColorMode
        billeActiveIndex = index
        saveSettings()
        UsageLog.d("bille appliquée = ${p.nom}")
    }

    /** Bibliothèque de pinceaux (2026-08-22, demande explicite : "un
     *  éditeur de pinceaux à la suite des billes") — même principe que
     *  loadBilleProfiles, périmètre réduit à couleur/largeur/texture/fondu.
     *  Amorce aux 2 pinceaux construits en session (figés en dur) quand
     *  aucune pref n'existe — même raison que defaultBilleProfiles(). */
    private fun loadPinceauProfiles() {
        pinceauProfiles.clear()
        pinceauActiveIndex = prefs.getInt("pinceauActiveIndex", -1)
        val count = prefs.getInt("pinceauProfileCount", 0)
        if (count == 0) {
            pinceauActiveIndex = 0
            pinceauProfiles.addAll(defaultPinceauProfiles())
            appliquerPinceauProfile(0)
        } else {
            for (i in 0 until count) {
                val k = "pinceauProfile_$i"
                pinceauProfiles.add(
                    PinceauProfile(
                        nom = prefs.getString("${k}_nom", null) ?: getString(R.string.default_pinceau_name, i + 1),
                        couleur = prefs.getInt("${k}_couleur", 0xFFE53935.toInt()),
                        largeurDp = prefs.getFloat("${k}_largeurDp", 26f),
                        textureAmount = prefs.getFloat("${k}_textureAmount", 0f),
                        fonduRate = prefs.getFloat("${k}_fonduRate", 0.4f),
                        melangeActif = prefs.getBoolean("${k}_melangeActif", true),
                    )
                )
            }
        }
    }

    /** Les 2 pinceaux par défaut à l'installation — cf. commentaire de
     *  loadPinceauProfiles(). */
    private fun defaultPinceauProfiles(): List<PinceauProfile> = listOf(
        PinceauProfile(
            nom = "pinceau net sans mix", couleur = -9230592, largeurDp = 26f,
            textureAmount = 0f, fonduRate = 0.15f, melangeActif = false,
        ),
        PinceauProfile(
            nom = "pinceau mix", couleur = -1900381, largeurDp = 26f,
            textureAmount = 0f, fonduRate = 0.9f, melangeActif = true,
        ),
    )

    private fun savePinceauProfiles() {
        val e = prefs.edit()
        e.putInt("pinceauProfileCount", pinceauProfiles.size)
        e.putInt("pinceauActiveIndex", pinceauActiveIndex)
        pinceauProfiles.forEachIndexed { i, p ->
            val k = "pinceauProfile_$i"
            e.putString("${k}_nom", p.nom)
            e.putInt("${k}_couleur", p.couleur)
            e.putFloat("${k}_largeurDp", p.largeurDp)
            e.putFloat("${k}_textureAmount", p.textureAmount)
            e.putFloat("${k}_fonduRate", p.fonduRate)
            e.putBoolean("${k}_melangeActif", p.melangeActif)
        }
        e.apply()
    }

    /** Applique un pinceau au canvas — ne touche pas au trait en cours,
     *  même logique que appliquerBilleProfile. 2026-08-22, demande
     *  explicite : "la couleur avec laquelle le pinceau écrit doit être
     *  celle sélectionnée dans l'onglet de raccourcis" — p.couleur ne sert
     *  QUE de teinte d'icône dans la liste des pinceaux (cf. buildPinceauCell),
     *  jamais poussée vers canvas.selectedColor (la couleur réellement
     *  dessinée reste celle choisie dans la palette, indépendamment du
     *  pinceau actif). */
    private fun appliquerPinceauProfile(index: Int) {
        val p = pinceauProfiles.getOrNull(index) ?: return
        canvas.trailWidthDp = p.largeurDp
        canvas.pinceauTextureAmount = p.textureAmount
        canvas.pinceauFonduRate = p.fonduRate
        canvas.pinceauMelangeActif = p.melangeActif
        pinceauActiveIndex = index
    }

    /** Onglet dédié (2026-08-20, demande explicite : "un onglet bille
     *  setup... un onglet par bille... pouvoir ajouter des nouvelles
     *  billes... les creer, les renomer"). Sous-barre horizontale (une
     *  pastille par bille + "+") au-dessus, contenu de la bille sélectionnée
     *  en dessous — même principe de sous-onglets que le panneau hamburger,
     *  à un niveau plus bas. */
    // 2026-08-21, demande explicite : sous-menu de tailles prédéfinies pour
    // pinceau/gomme, "comme le style pour les billes" — même gabarit de
    // cellule (60dp, icône/aperçu + libellé sur 2 lignes) que buildBilleCell,
    // mais l'aperçu est un disque dont le diamètre reflète la taille réelle
    // (plafonné à 34dp pour rester lisible dans la cellule) plutôt qu'une
    // icône fixe.
    private fun buildTailleCell(previewDp: Float, label: String, selected: Boolean, onClick: () -> Unit): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 56f), LinearLayout.LayoutParams.WRAP_CONTENT)
            background = UiStyle.ripple(this@MainActivity, if (selected) R.drawable.bg_round_rect_sel else R.drawable.bg_round_rect)
            isClickable = true
            val pad = UiStyle.dp(this@MainActivity, 4f)
            setPadding(pad, pad, pad, pad)
        }
        val previewBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 40f), UiStyle.dp(this@MainActivity, 40f))
        }
        val dot = View(this).apply {
            val sizePx = UiStyle.dp(this@MainActivity, previewDp.coerceIn(4f, 34f))
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply { gravity = Gravity.CENTER }
            background = getDrawable(R.drawable.bg_swatch)
            backgroundTintList = ColorStateList.valueOf(UiStyle.TEXT)
        }
        previewBox.addView(dot)
        val labelTv = TextView(this).apply {
            text = label
            UiStyle.body(this)
            maxLines = 2
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        cell.addView(previewBox)
        cell.addView(labelTv)
        cell.setOnClickListener { onClick() }
        return cell
    }

    // 2026-08-20, demande explicite : le sélecteur de bille de l'onglet
    // "Billes" doit reprendre le même visuel (icône teintée + nom sur 2
    // lignes) que le menu rapide des raccourcis (submenuBalle) — cellule
    // partagée entre les deux endroits pour garantir la parité.
    private fun buildBilleCell(p: BilleProfile, highlighted: Boolean, onClick: () -> Unit): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 2026-08-20, demande explicite : "5 cest le max par ligne et
            // cest un peu trop grand ca dzborde" — cellule rétrécie (52dp,
            // était 60dp) pour que 5 tiennent sans déborder.
            layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 52f), LinearLayout.LayoutParams.WRAP_CONTENT)
            background = UiStyle.ripple(this@MainActivity, if (highlighted) R.drawable.bg_round_rect_sel else R.drawable.bg_round_rect)
            isClickable = true
        }
        // 2026-08-25 : taille de l'icône liée au rayon de la bille (46dp
        // pour une grosse, 16dp pour une "Micro"), essayée puis retirée sur
        // retour explicite : "les pastilles doivent toutes avoir la même
        // taille, c'est pas parce que la bille est plus petite que le
        // bouton doit l'être" — taille fixe pour toutes, seule la variante
        // "sans les noms" reste plus grande (plus de place disponible).
        val iconDp = if (billeNamesVisible) 30f else 46f
        // 2026-09-01, retour direct : "le menu des balles doit avoir des
        // pastilles toutes de la même taille" — le rayon réel de la bille
        // (p.ballRadiusDp) faisait varier le padding interne (2026-08-25,
        // courbe en racine carrée) donc le DISQUE visible dans la pastille,
        // pas seulement la cellule. Retiré : marge de référence identique
        // pour toutes (84% de remplissage, p.iconScale=1) — plus jamais
        // couplée au rayon physique.
        // 2026-09-01, suite : "microbille est trop grosse... peut etre
        // qu'il faut un slider pour regler la taille de l'image de la
        // bille dans la pastille" — p.iconScale (réglage manuel par bille,
        // cf. buildBilleProfileEditor) module ce remplissage de référence,
        // borné pour ne jamais faire disparaître ni déborder l'icône.
        val targetFill = (0.84f * p.iconScale).coerceIn(0.15f, 0.98f)
        val padDp = iconDp * (1f - targetFill) / 2f
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, iconDp), UiStyle.dp(this@MainActivity, iconDp))
            val pad = UiStyle.dp(this@MainActivity, padDp)
            setPadding(pad, pad, pad, pad)
            // 2026-08-25 : anneau clair (bg_swatch_frame) essayé pour la
            // visibilité sur fond noir, puis retiré des icônes de concept
            // ("le cercle gris parasite les icônes"), et maintenant retiré
            // aussi du disque générique — encore rapporté "cercle gris" dessus
            // (l'anneau blanc à 40% d'alpha rendu sur fond de cellule sombre
            // se voit gris terne, pas blanc net). billeSphereDrawable fournit
            // déjà son propre contraste (zone claire du dégradé) — plus
            // besoin de ce cadre séparé, quelle que soit la bille.
            applyBilleIcon(this, p)
        }
        val label = TextView(this).apply {
            text = p.nom
            UiStyle.body(this)
            maxLines = 2
            // 2026-09-01, retour direct : "les pastilles n'ont tjs pas la
            // meme taille, base toi sur la plus grand et defini cette
            // taille max (2 lignes de texte)" — la CELLULE est en
            // WRAP_CONTENT (hauteur), donc un nom court sur 1 ligne
            // (« Balle ») donnait une pastille plus basse qu'un nom qui
            // enveloppe sur 2 lignes (« Rebondissante », « Comète arc en
            // ciel »). minLines=2 réserve toujours la hauteur des 2 lignes,
            // même pour un nom tenant sur une seule — même hauteur partout.
            minLines = 2
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            // 2026-08-25, demande explicite : "un toggle pour cacher le nom
            // des billes, car c'est moche et irrégulier d'avoir des icônes
            // de tailles différentes" — cf. billeNamesVisible.
            visibility = if (billeNamesVisible) View.VISIBLE else View.GONE
        }
        cell.contentDescription = p.nom
        cell.addView(icon)
        cell.addView(label)
        cell.setOnClickListener { onClick() }
        return cell
    }

    /** Menu de création/édition RAPIDE d'une bille — "on va garder séparer
     *  les 2 menus" (demande explicite du 2026-09-02) : celui-ci reste
     *  volontairement réduit (nom, couleur, icône, taille, rebond) et ne
     *  touche jamais aux réglages techniques (friction, texture, fondu,
     *  inclinaison...) réservés à l'éditeur avancé (buildBilleProfileEditor,
     *  onglet Billes du menu hamburger — "pour créer les billes par
     *  défaut"). Accessible par le "+" du menu de raccourcis (index = null,
     *  crée) et par le tap sur une pastille en mode gigote (index = la
     *  bille éditée, cf. refreshBalleSubmenu). Travaille sur une COPIE
     *  (BilleProfile.copy()) jusqu'au tap sur le bouton d'action : fermer
     *  avec ✕ n'écrit rien. */
    private fun showBilleQuickEditor(index: Int?) {
        if (billeQuickEditorOverlay != null) return // déjà ouvert
        val density = resources.displayMetrics.density
        val editingExisting = index != null
        val nomDefaut = getString(R.string.default_bille_name, billeProfiles.size + 1)
        // 2026-09-02, demande explicite : "quand je tap sur l'icône qui
        // bouge pour l'éditer, il faut que ça sélectionne la bille en
        // question" — édition = sélection immédiate, comme un tap normal ;
        // mémorisé pour tout restaurer si l'édition est annulée (cf. cancelEdit).
        val previousActiveIndex = billeActiveIndex
        if (editingExisting) appliquerBilleProfile(index!!)
        // 2026-09-02, demande explicite : "par défaut le touché de la bille
        // actif" + "par défaut actif sur toute l'inclinaison amplifiée" — ces
        // 2 réglages démarrent actifs pour une bille créée ici (pas de
        // switch dédié pour eux, juste ce défaut), contrairement au
        // BilleProfile() nu (les deux à false) utilisé ailleurs.
        val work = if (editingExisting) {
            billeProfiles[index!!].copy()
        } else {
            BilleProfile(nom = nomDefaut, ballGrabInPinceau = true, tiltCurveMode = true)
        }

        // 2026-09-02, demande explicite : "je préfère voir en live la bille
        // bouger de taille en bas de l'écran... est-ce que l'interface se
        // cache... comme ça on peut ne pas avoir un fond avec une opacité et
        // voir la bille sélectionnée bouger selon la taille et l'effet
        // choisi en direct" — remplace l'aperçu figé par le VRAI rendu
        // canvas : plus de voile sombre (juste bloquer le dessin en dessous),
        // barre/raccourcis/undo-redo masqués comme le fait déjà l'œil
        // (toggleHideUi) — sauf si déjà masqués par choix de l'utilisateur,
        // auquel cas on n'y touche pas.
        val uiWasVisibleBefore = !uiHidden
        if (uiWasVisibleBefore) {
            tabbarView.visibility = View.GONE
            controlsCluster.visibility = View.GONE
            btnUndo.visibility = View.GONE
            btnRedo.visibility = View.GONE
            versionLabel.visibility = View.GONE
        }
        // submenuBalle est une val locale à onCreate (pas un champ de
        // classe) — pas accessible ici directement, on repasse par l'id.
        findViewById<View>(R.id.submenu_balle)?.visibility = View.GONE

        val scrim = FrameLayout(this).apply {
            isClickable = true // bloque les gestes sur le dessin en dessous, aucun voile visuel
        }
        rootLayout.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        billeQuickEditorOverlay = scrim

        // pousse les valeurs de travail sur le canvas réel — c'est LUI
        // l'aperçu maintenant (taille, trajectoire, trainée... tout ce
        // qu'une icône figée ne pouvait pas montrer, "en fonction du zoom").
        // Ne touche ni billeProfiles ni les prefs : pur affichage, annulé
        // par cancelEdit() si on ferme sans valider.
        fun pushPreviewToCanvas() {
            canvas.ballColor = work.couleurBille
            canvas.ballRadiusDp = work.ballRadiusDp
            canvas.restitution = work.restitution
            canvas.frictionRate = work.frictionRate
            canvas.fonduRate = work.fonduRate
            canvas.wrapActive = work.wrapActive
            canvas.boundsActive = work.boundsActive
            canvas.cometTrail = work.cometTrail
            canvas.speedColorMode = work.speedColorMode
            canvas.tiltCurveMode = work.tiltCurveMode
            canvas.ballGrabInPinceau = work.ballGrabInPinceau
        }
        pushPreviewToCanvas()

        fun restoreUi() {
            if (uiWasVisibleBefore) {
                tabbarView.visibility = View.VISIBLE
                controlsCluster.visibility = View.VISIBLE
                btnUndo.visibility = if ("undo" in disabledTools) View.GONE else View.VISIBLE
                btnRedo.visibility = if ("redo" in disabledTools) View.GONE else View.VISIBLE
                versionLabel.visibility = View.VISIBLE
            }
        }
        fun teardown() {
            rootLayout.removeView(scrim)
            billeQuickEditorOverlay = null
            restoreUi()
            // 2026-09-02 : referme aussi le mode gigote en quittant l'éditeur
            // (créer/éditer une bille est un point d'arrêt naturel, pas la
            // peine de rester en mode suppression après)
            billeJiggling = false
            refreshBalleSubmenuCb?.invoke()
        }
        // ✕ / annuler : la sélection (tap = sélectionne, cf. plus haut) reste
        // acquise, mais les réglages non validés sont annulés — reproduit sur
        // le canvas la bille telle qu'elle est VRAIMENT enregistrée.
        fun cancelEdit() {
            if (editingExisting) appliquerBilleProfile(index!!)
            else if (previousActiveIndex in billeProfiles.indices) appliquerBilleProfile(previousActiveIndex)
            teardown()
        }

        val scroll = ScrollView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_palette)
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (18 * density).toInt())
        }
        scroll.addView(card)
        // 2026-09-02, retour explicite : l'ancrage en haut (58% de hauteur,
        // essayé pour dégager le bas de l'écran) "coupe la fenêtre en deux,
        // c'est pas beau" — revient à la carte centrée d'origine. Le canvas
        // reste quand même visible sur les bords (pas de voile derrière,
        // cf. pushPreviewToCanvas), juste moins d'espace dégagé qu'avec la
        // version ancrée en haut.
        val cardLp = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        ).apply { gravity = Gravity.CENTER }
        scrim.addView(scroll, cardLp)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleTv = TextView(this).apply {
            text = if (editingExisting) getString(R.string.quick_bille_edit_title) else getString(R.string.quick_bille_new_title)
            UiStyle.title(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = UiStyle.glyphButton(this, "✕", sizeDp = 28f, muted = true)
        closeBtn.setOnClickListener { cancelEdit() }
        header.addView(titleTv)
        header.addView(closeBtn)
        card.addView(header)

        // 2026-09-02, revient sur la 1re version (icône figée redimensionnée
        // selon la taille) : "je préfère voir en live la bille bouger de
        // taille en bas de l'écran... sinon on le voit pas l'effet réel, en
        // fonction du zoom" — le VRAI canvas (cf. pushPreviewToCanvas) sert
        // maintenant d'aperçu ; cette icône redevient un simple repère fixe,
        // comme celle du raccourci.
        // 2026-09-02, retour explicite : "l'espace réservé à l'icône de la
        // balle est énorme comparé au reste de la fenêtre" — boîte et icône
        // réduites de moitié (simple repère, plus un aperçu à faire valoir
        // depuis que le vrai canvas joue ce rôle, cf. commentaire ci-dessus).
        val previewBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 52f), UiStyle.dp(this@MainActivity, 52f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (4 * density).toInt()
            }
        }
        val previewIcon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(UiStyle.dp(this@MainActivity, 40f), UiStyle.dp(this@MainActivity, 40f)).apply {
                gravity = Gravity.CENTER
            }
        }
        previewBox.addView(previewIcon)
        card.addView(previewBox)
        fun refreshPreview() { applyBilleIcon(previewIcon, work) }
        refreshPreview()

        val nomEdit = EditText(this).apply {
            setText(work.nom)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
            }
        }
        card.addView(nomEdit)

        val couleurLabel = TextView(this).apply {
            text = getString(R.string.label_ball_color)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            }
        }
        card.addView(couleurLabel)
        val melangeur = MelangeurView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (110 * density).toInt())
        }
        card.addView(melangeur)

        // icône (mêmes concepts que l'éditeur avancé, même lien concept→
        // réglage — cf. buildBilleProfileEditor — mais dupliqué ici à
        // dessein : les 2 menus restent 2 arbres de vues séparés)
        val conceptLabel = TextView(this).apply {
            text = getString(R.string.label_bille_concept)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            }
        }
        card.addView(conceptLabel)
        val conceptOptions = listOf(
            Triple<String?, String, Int>(null, getString(R.string.concept_defaut), R.drawable.ic_balle),
            Triple("arc_en_ciel", getString(R.string.concept_arc_en_ciel), R.drawable.ic_bille_arcenciel),
            Triple("psychedelique", getString(R.string.concept_psychedelique), R.drawable.ic_bille_psychedelique),
            Triple("comete", getString(R.string.concept_comete), R.drawable.ic_bille_comete),
            Triple("comete_arcenciel", getString(R.string.concept_comete_arcenciel), R.drawable.ic_bille_comete_arcenciel),
            Triple("rebondissante", getString(R.string.concept_rebondissante), R.drawable.ic_bille_rebondissante),
        )
        val conceptContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(conceptContainer)
        val conceptCells = mutableListOf<Pair<String?, LinearLayout>>()
        val conceptDefautIcons = mutableListOf<ImageView>() // rafraîchis quand la couleur change
        // 2026-09-02, retour explicite : "en adaptant un peu ya moyen de
        // remonter la rebondissante" — 6 colonnes (au lieu de 5) + cellules
        // resserrées : les 6 concepts tiennent sur une seule ligne, plus de
        // 2e ligne borgne avec juste "Rebondissante" dessus.
        val colsConcept = 6
        var conceptRow: LinearLayout? = null
        var conceptColCount = colsConcept
        fun refreshConceptSelection() {
            conceptCells.forEach { (key, cell) ->
                cell.background = UiStyle.ripple(this, if (work.icone == key) R.drawable.bg_round_rect_sel else R.drawable.bg_round_rect)
            }
        }
        conceptOptions.forEach { (key, label, res) ->
            if (conceptColCount == colsConcept) {
                conceptRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                conceptContainer.addView(conceptRow)
                conceptColCount = 0
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 46f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (3 * density).toInt()
                    bottomMargin = (4 * density).toInt()
                }
                isClickable = true
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 28f), UiStyle.dp(this@MainActivity, 28f))
                val pad = UiStyle.dp(this@MainActivity, 2f)
                setPadding(pad, pad, pad, pad)
                if (key == null) {
                    imageTintList = null
                    setImageDrawable(billeSphereDrawable(work.couleurBille))
                    conceptDefautIcons.add(this)
                } else {
                    setImageResource(res)
                    imageTintList = null
                }
            }
            val labelTv = TextView(this).apply {
                text = label
                UiStyle.body(this)
                maxLines = 2
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            cell.addView(icon)
            cell.addView(labelTv)
            cell.setOnClickListener {
                // 2026-09-02, demande explicite : "en vrai du coup, la
                // validation de concept, ça serait plutôt juste une
                // sélection d'icône" — contrairement à l'éditeur avancé
                // (lien concept→réglage automatique), ce menu-ci expose
                // maintenant traînée comète/couleur vitesse/rebond comme
                // réglages explicites plus bas : l'icône ne doit plus les
                // toucher en douce, juste choisir l'apparence.
                work.icone = key
                refreshPreview()
                refreshConceptSelection()
            }
            conceptCells.add(key to cell)
            conceptRow!!.addView(cell)
            conceptColCount++
        }
        refreshConceptSelection()

        melangeur.onColorPicked = { c ->
            work.couleurBille = c
            refreshPreview()
            conceptDefautIcons.forEach { it.setImageDrawable(billeSphereDrawable(work.couleurBille)) }
            pushPreviewToCanvas()
        }

        addSlider(card, getString(R.string.slider_ball_radius), 1f, 90f, work.ballRadiusDp) { work.ballRadiusDp = it; pushPreviewToCanvas() }
        addSlider(card, getString(R.string.slider_bounce), 0f, 1f, work.restitution) { work.restitution = it; pushPreviewToCanvas() }
        addSlider(card, getString(R.string.slider_friction), 0f, 3f, work.frictionRate) { work.frictionRate = it; pushPreviewToCanvas() }

        // 2026-09-02, demande explicite : "le mode de fondu" — les 4
        // préréglages seuls (pas le slider de valeur brute en dessous dans
        // l'éditeur avancé) : un MODE à choisir, pas une valeur à affiner.
        val fonduLabel = TextView(this).apply {
            text = getString(R.string.blend_fade_title)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
            }
        }
        card.addView(fonduLabel)
        val fonduRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fonduPresets = listOf(
            getString(R.string.preset_crisp) to 0.9f,
            getString(R.string.preset_soft) to 0.4f,
            getString(R.string.preset_very_soft) to 0.15f,
            getString(R.string.preset_extreme_soft) to 0.05f,
        )
        val fonduBtns = mutableListOf<TextView>()
        fun refreshFonduSelection() {
            fonduBtns.forEachIndexed { i, b -> b.setTextColor(if (fonduPresets[i].second == work.fonduRate) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
        }
        fonduPresets.forEach { (label, rate) ->
            val btn = UiStyle.pillButton(this, label, muted = work.fonduRate != rate)
            btn.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6 * density).toInt()
            }
            btn.setOnClickListener { work.fonduRate = rate; refreshFonduSelection(); pushPreviewToCanvas() }
            fonduBtns.add(btn)
            fonduRow.addView(btn)
        }
        card.addView(fonduRow)
        refreshFonduSelection()

        // 2026-09-02, demande explicite : "je veux pouvoir valider ou pas la
        // téléportation, ... et la sortie de l'écran, ... la traînée comète,
        // ainsi que la couleur selon la vitesse" — 4 réglages physiques déjà
        // présents dans l'éditeur avancé, ajoutés ici comme switches
        // explicites (plus de lien caché icône→réglage, cf. le tap sur une
        // icône de concept plus haut).
        card.addView(UiStyle.switchRow(this, getString(R.string.toggle_edge_wrap), work.wrapActive) { work.wrapActive = it; pushPreviewToCanvas() })
        card.addView(UiStyle.switchRow(this, getString(R.string.toggle_screen_bounds), work.boundsActive) { work.boundsActive = it; pushPreviewToCanvas() })
        card.addView(UiStyle.switchRow(this, getString(R.string.toggle_comet_trail), work.cometTrail) { work.cometTrail = it; pushPreviewToCanvas() })
        card.addView(UiStyle.switchRow(this, getString(R.string.toggle_speed_color), work.speedColorMode) { work.speedColorMode = it; pushPreviewToCanvas() })

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (14 * density).toInt()
            }
        }
        // 2026-09-02, demande explicite : "le delete, je préfère qu'il soit
        // dans le menu d'édition de la bille comme ça pas d'erreur possible"
        // — retire la croix rouge du menu de raccourcis (cf. refreshBalleSubmenu),
        // remplacée par ce bouton, accessible seulement en édition (pas à la
        // création) et jamais pour une bille protégée (cf. BilleProfile.isDefault).
        if (editingExisting && !work.isDefault && billeProfiles.size > 1) {
            val delBtn = UiStyle.pillButton(this, getString(R.string.btn_delete_bille), muted = true)
            delBtn.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * density).toInt()
            }
            delBtn.setOnClickListener {
                billeProfiles.removeAt(index!!)
                if (billeActiveIndex >= billeProfiles.size) billeActiveIndex = billeProfiles.size - 1
                saveBilleProfiles()
                UsageLog.d("bille rapide: supprimée (${work.nom}), total=${billeProfiles.size}")
                // la bille éditée n'existe plus : réapplique celle qui reste
                // active pour que le canvas reflète une bille réelle, pas
                // l'aperçu de travail qu'on vient de jeter.
                appliquerBilleProfile(billeActiveIndex)
                teardown()
            }
            actionsRow.addView(delBtn)
        }
        val saveBtn = UiStyle.pillButton(
            this,
            if (editingExisting) getString(R.string.btn_save_bille_quick) else getString(R.string.btn_create_bille_quick)
        )
        saveBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        saveBtn.setOnClickListener {
            work.nom = nomEdit.text.toString().trim().ifEmpty { nomDefaut }
            if (editingExisting) billeProfiles[index!!] = work else billeProfiles.add(work)
            saveBilleProfiles()
            UsageLog.d("bille rapide: ${if (editingExisting) "modifiée" else "créée"} (${work.nom}), total=${billeProfiles.size}")
            // 2026-09-02, demande explicite : "une fois qu'on valide, ça
            // verrouille le paramétrage de la bille" — appliquerBilleProfile
            // persiste billeActiveIndex + réécrit canvas depuis la copie
            // maintenant enregistrée (plus depuis `work`, qui a fait son office).
            appliquerBilleProfile(if (editingExisting) index!! else billeProfiles.size - 1)
            teardown()
        }
        actionsRow.addView(saveBtn)
        card.addView(actionsRow)
    }

    private fun buildBilleSetupPanel(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 2026-08-20, demande explicite : "a 5 fait que le + passe a la
        // ligne du dessou sino je peux rien ajouter" — grille qui
        // s'agrandit verticalement (comme submenuBalle) au lieu d'un
        // scroll horizontal : le "+" reste toujours atteignable, il
        // retombe sur la ligne suivante dès qu'une rangée est pleine.
        billeSetupSubTabs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(billeSetupSubTabs)
        billeSetupContent = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(billeSetupContent)
        refreshBilleSetupTabs()
        return root
    }

    /** Reconstruit UNIQUEMENT la rangée de sélection (icônes + noms), pas
     *  l'éditeur (billeSetupContent) — 2026-08-21, rapporté : "le melangeur
     *  de la bille rame" : l'éditeur contient un MelangeurView dont la
     *  construction régénère une carte de couleurs coûteuse (cf.
     *  MelangeurView.rebuildBitmapSync) ; le rappeler à chaque relâchement
     *  de couleur (juste pour retinter l'icône de l'onglet) causait le
     *  ralentissement. Cette fonction légère met à jour le sélecteur seul. */
    private fun refreshBilleSetupSelector() {
        val density = resources.displayMetrics.density
        billeSetupOnglet = billeSetupOnglet.coerceIn(0, billeProfiles.size - 1)
        billeSetupSubTabs.removeAllViews()
        val colsBilleSetup = 5
        var row: LinearLayout? = null
        var colCount = 0
        fun newRow() {
            row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            billeSetupSubTabs.addView(row)
            colCount = 0
        }
        newRow()
        billeProfiles.forEachIndexed { i, p ->
            if (colCount == colsBilleSetup) newRow()
            val cell = buildBilleCell(p, highlighted = i == billeSetupOnglet) {
                billeSetupOnglet = i
                // 2026-08-25, suite immédiate : "quand je sélectionne une
                // bille... elle se sélectionne pas en arrière-plan" — jusqu'ici
                // seul le fait de TOUCHER un réglage dans l'éditeur
                // (sauverEtSyncSiActive) appliquait au canvas ; le simple
                // clic sur l'onglet d'une bille dans ce sélecteur n'y touchait
                // pas. Même principe désormais : sélectionner = appliquer.
                appliquerBilleProfile(i)
                // même anti-pattern que le mélangeur : refreshBilleSetupTabs()
                // supprime cette cellule de l'arbre alors que son propre clic
                // est encore en cours de traitement — déféré par précaution.
                billeSetupSubTabs.post {
                    refreshBilleSetupTabs()
                    refreshBalleSubmenuCb?.invoke() // surbrillance du menu rapide à jour aussi
                }
            }
            cell.layoutParams = (cell.layoutParams as LinearLayout.LayoutParams).apply {
                marginEnd = (4 * density).toInt()
            }
            row!!.addView(cell)
            colCount++
        }
        if (colCount == colsBilleSetup) newRow()
        val addBtn = UiStyle.glyphButton(this, "+", sizeDp = 32f, muted = true)
        addBtn.setOnClickListener {
            UsageLog.d("bille setup: + nouvelle bille (avant, total=${billeProfiles.size})")
            billeProfiles.add(BilleProfile(nom = getString(R.string.default_bille_name, billeProfiles.size + 1)))
            billeSetupOnglet = billeProfiles.size - 1
            saveBilleProfiles()
            refreshBilleSetupTabs()
            refreshBalleSubmenuCb?.invoke()
            UsageLog.d("bille setup: + nouvelle bille (après, total=${billeProfiles.size})")
        }
        row!!.addView(addBtn)
    }

    private fun refreshBilleSetupTabs() {
        refreshBilleSetupSelector()
        billeSetupContent.removeAllViews()
        billeSetupContent.addView(buildBilleProfileEditor(billeSetupOnglet))
    }

    private fun buildBilleProfileEditor(index: Int): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        val p = billeProfiles.getOrNull(index) ?: return scroll
        val density = resources.displayMetrics.density
        // 2026-08-20, demande explicite initiale : "auto-appliquer si c'est
        // la bille active" — réglage gardé par (index == billeActiveIndex).
        // 2026-08-25, demande explicite qui remplace ce garde-fou : "j'aimerais
        // que ça prennent effet direct sur la bille en arrière-plan... pour
        // tester la config en live et voir si ça me plaît" — l'éditeur ÉTANT
        // fait pour prévisualiser, chaque réglage modifié se répercute
        // maintenant tout de suite sur le canvas, quel que soit le profil en
        // cours d'édition (ça bascule aussi billeActiveIndex vers lui, cf.
        // appliquerBilleProfile — même mécanisme que le bouton Appliquer,
        // déclenché automatiquement dès qu'on touche un réglage).
        fun sauverEtSyncSiActive() {
            saveBilleProfiles()
            appliquerBilleProfile(index)
        }

        val nomEdit = EditText(this).apply {
            setText(p.nom)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        col.addView(nomEdit)
        nomEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                UsageLog.d("bille setup: nomEdit perd le focus (index=$index)")
                val nouveau = nomEdit.text.toString().trim()
                p.nom = nouveau.ifEmpty { getString(R.string.default_bille_name, index + 1) }
                saveBilleProfiles()
                // 2026-08-20, rapporté : crash "en quittant la configuration
                // d'une nouvelle bille" — refreshBilleSetupTabs() fait
                // billeSetupContent.removeAllViews() puis reconstruit
                // l'éditeur, DONC ce nomEdit lui-même, alors qu'on est
                // encore en plein traitement de SON PROPRE callback de perte
                // de focus (le framework fait aussi son propre travail de
                // focus juste après ce callback). Appeler ça reporte
                // l'opération après la fin du cycle courant (nomEdit.post),
                // évite de muter l'arbre de vues pendant que le système de
                // focus est encore en train de le parcourir.
                UsageLog.d("bille setup: nom sauvé = ${p.nom}, refreshBilleSetupTabs reporté (post)")
                nomEdit.post {
                    UsageLog.d("bille setup: refreshBilleSetupTabs (post) — début")
                    refreshBilleSetupTabs()
                    refreshBalleSubmenuCb?.invoke()
                    UsageLog.d("bille setup: refreshBilleSetupTabs (post) — fin")
                }
            }
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        val applyBtn = UiStyle.pillButton(this, getString(R.string.btn_apply_bille))
        applyBtn.setOnClickListener { appliquerBilleProfile(index) }
        actionsRow.addView(applyBtn)
        if (billeProfiles.size > 1) {
            val delBtn = UiStyle.pillButton(this, getString(R.string.btn_delete_bille), muted = true)
            delBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * density).toInt() }
            delBtn.setOnClickListener {
                billeProfiles.removeAt(index)
                saveBilleProfiles()
                refreshBilleSetupTabs()
                refreshBalleSubmenuCb?.invoke()
            }
            actionsRow.addView(delBtn)
        }
        col.addView(actionsRow)

        // couleur du disque de la bille (2026-08-20, demande explicite :
        // "j'ai besoin de pouvoir editer le visuel de la bille", puis "pour
        // lz selection de la couleur de la bille je veux un melangeur
        // plutot") — même widget carte OKLCh que le mélangeur du panneau
        // Palette (MelangeurView), pas juste les pastilles de la palette
        // perso : la bille peut prendre n'importe quelle couleur.
        val couleurLabel = TextView(this).apply {
            text = getString(R.string.label_ball_color)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        col.addView(couleurLabel)
        val couleurMelangeur = MelangeurView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (130 * density).toInt()
            )
        }
        col.addView(couleurMelangeur)
        val couleurTeinteSeek = addSlider(col, getString(R.string.slider_hue), 0f, 360f, couleurMelangeur.hue.toFloat()) { couleurMelangeur.setHue(it.toDouble()) }
        val couleurSatSeek = addSlider(col, getString(R.string.slider_saturation), 0f, 0.4f, couleurMelangeur.chroma.toFloat()) { couleurMelangeur.setChroma(it.toDouble()) }
        // aperçu en direct pendant le glissé (pas de sauvegarde/reconstruction
        // du panneau tant qu'on n'a pas relâché — la vue disparaîtrait en
        // plein geste sinon)
        couleurMelangeur.onColorPicked = { c ->
            p.couleurBille = c
            canvas.ballColor = c
        }
        couleurMelangeur.onDirectTouch = {
            couleurTeinteSeek.progress = (couleurMelangeur.hue / 360.0 * 1000).toInt().coerceIn(0, 1000)
            couleurSatSeek.progress = (couleurMelangeur.chroma / 0.4 * 1000).toInt().coerceIn(0, 1000)
        }
        couleurMelangeur.onColorCommitted = {
            // 2026-08-21, crash confirmé (trace complète capturée) : NPE
            // dans FrameLayout.onMeasure — reconstruire tout l'éditeur (donc
            // supprimer couleurMelangeur) PENDANT que son propre
            // onTouchEvent(ACTION_UP) était encore en cours de traitement
            // (même anti-pattern que le crash nomEdit déjà corrigé) —
            // déféré via .post(). ET, rapporté séparément ("le melangeur de
            // la bille rame") : on ne touche plus qu'au sélecteur (icônes),
            // pas plus à billeSetupContent — reconstruire l'éditeur à
            // chaque relâchement recréait le MelangeurView à chaque fois,
            // donc regénérait sa carte de couleurs (coûteux), d'où le
            // ralentissement.
            couleurMelangeur.post {
                sauverEtSyncSiActive()
                refreshBilleSetupSelector()
                // 2026-08-25, même faille que les pastilles de concept (cf.
                // plus bas) : refreshBilleSetupSelector() ne touche QUE le
                // sélecteur de cet éditeur — le menu rapide des raccourcis
                // (submenuBalle) est un arbre de vues séparé, construit une
                // fois et jamais reconstruit tant qu'on ne l'appelle pas.
                refreshBalleSubmenuCb?.invoke()
            }
        }

        // 2026-08-25, demande explicite : "des pastilles avec des icônes qui
        // représentent plus les billes que j'ai créées... en dessous du
        // mélangeur... les concepts que je te donnerai petit à petit" — une
        // dizaine de cases prévues à terme, 3 pour l'instant + "Défaut" pour
        // revenir au disque teinté.
        // 2026-08-31 : lien automatique pastille→réglage restauré (arc-en-
        // ciel → rainbowMode, comète → cometTrail/speedColorMode) — ces
        // réglages sont redevenus une propriété de LA bille choisie ici.
        val conceptLabel = TextView(this).apply {
            text = getString(R.string.label_bille_concept)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        col.addView(conceptLabel)
        val conceptOptions = listOf(
            Triple<String?, String, Int>(null, getString(R.string.concept_defaut), R.drawable.ic_balle),
            Triple("arc_en_ciel", getString(R.string.concept_arc_en_ciel), R.drawable.ic_bille_arcenciel),
            Triple("psychedelique", getString(R.string.concept_psychedelique), R.drawable.ic_bille_psychedelique),
            Triple("comete", getString(R.string.concept_comete), R.drawable.ic_bille_comete),
            Triple("comete_arcenciel", getString(R.string.concept_comete_arcenciel), R.drawable.ic_bille_comete_arcenciel),
            Triple("rebondissante", getString(R.string.concept_rebondissante), R.drawable.ic_bille_rebondissante),
        )
        val conceptContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val colsConcept = 5
        var conceptRow: LinearLayout? = null
        var conceptColCount = colsConcept
        conceptOptions.forEach { (key, label, res) ->
            if (conceptColCount == colsConcept) {
                conceptRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                conceptContainer.addView(conceptRow)
                conceptColCount = 0
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    UiStyle.dp(this@MainActivity, 52f), LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (4 * density).toInt()
                    bottomMargin = (4 * density).toInt()
                }
                background = UiStyle.ripple(this@MainActivity, if (p.icone == key) R.drawable.bg_round_rect_sel else R.drawable.bg_round_rect)
                isClickable = true
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 30f), UiStyle.dp(this@MainActivity, 30f))
                val pad = UiStyle.dp(this@MainActivity, 3f)
                setPadding(pad, pad, pad, pad)
                if (key == null) {
                    imageTintList = null
                    setImageDrawable(billeSphereDrawable(p.couleurBille))
                } else {
                    setImageResource(res)
                    imageTintList = null
                }
            }
            val labelTv = TextView(this).apply {
                text = label
                UiStyle.body(this)
                maxLines = 2
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            cell.addView(icon)
            cell.addView(labelTv)
            cell.setOnClickListener {
                p.icone = key
                when (key) {
                    "arc_en_ciel" -> p.rainbowMode = true
                    "comete" -> p.cometTrail = true
                    "comete_arcenciel" -> { p.cometTrail = true; p.speedColorMode = true }
                    // 2026-08-25, rapporté : "l'icône [ne] reflète [pas] ce
                    // qu'elle fait" — lie enfin le concept à son réglage.
                    "rebondissante" -> p.restitution = 0.95f
                }
                sauverEtSyncSiActive()
                refreshBilleSetupTabs()
                // 2026-08-25, rapporté : "les icônes existent mais
                // n'apparaissent pas dans le menu de raccourci" —
                // refreshBilleSetupTabs() ne touche que le sélecteur DE CET
                // ÉDITEUR ; le menu rapide (submenuBalle) est un arbre de
                // vues séparé, jamais reconstruit sans cet appel explicite.
                refreshBalleSubmenuCb?.invoke()
            }
            conceptRow!!.addView(cell)
            conceptColCount++
        }
        col.addView(conceptContainer)

        // 2026-09-01, demande explicite : "separation entre les slider pour
        // les icones soit distinguer de ceux des parametre de la bille",
        // puis "le slider de l'apparence devrait etre sous l'icone sinon je
        // vois pas" — déplacé juste sous la grille Concept ci-dessus (là où
        // l'icône elle-même se choisit), pas en fin de liste après tous les
        // réglages physiques où il passait inaperçu. Ne pilote QUE la
        // vignette du sélecteur (buildBilleCell), jamais couplé à
        // slider_ball_radius (taille réelle en jeu, plus haut).
        val iconScaleLabel = TextView(this).apply {
            text = getString(R.string.label_icon_scale_section)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        col.addView(iconScaleLabel)
        addSlider(col, getString(R.string.slider_icon_scale), 0.3f, 2f, p.iconScale) {
            p.iconScale = it
            sauverEtSyncSiActive()
            refreshBilleSetupSelector()
            refreshBalleSubmenuCb?.invoke()
        }

        // 2026-08-25, demande explicite : "un toggle pour cacher le nom des
        // billes, car c'est moche et irrégulier d'avoir des icônes de
        // tailles différentes" — réglage global (billeNamesVisible, pas par
        // profil), affecte les 2 sélecteurs (menu rapide + bibliothèque)
        // d'un coup, cf. buildBilleCell.
        val namesVisibleRow = UiStyle.switchRow(this, getString(R.string.toggle_bille_names_visible), billeNamesVisible) { checked ->
            billeNamesVisible = checked
            saveSettings()
            refreshBilleSetupTabs()
            refreshBalleSubmenuCb?.invoke()
        }
        namesVisibleRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(namesVisibleRow)

        addSlider(col, getString(R.string.slider_ball_radius), 1f, 90f, p.ballRadiusDp) { p.ballRadiusDp = it; sauverEtSyncSiActive() }
        addSlider(col, getString(R.string.slider_tilt_sensitivity), 0f, 1f, p.tiltEffect) { p.tiltEffect = it; sauverEtSyncSiActive() }
        addSlider(col, getString(R.string.slider_bounce), 0f, 1f, p.restitution) { p.restitution = it; sauverEtSyncSiActive() }
        addSlider(col, getString(R.string.slider_weight), 0.5f, 2.5f, p.poids) { p.poids = it; sauverEtSyncSiActive() }
        addSlider(col, getString(R.string.slider_friction), 0f, 3f, p.frictionRate) { p.frictionRate = it; sauverEtSyncSiActive() }
        addSlider(col, getString(R.string.slider_trail_texture), 0f, 1f, p.textureAmount) { p.textureAmount = it; sauverEtSyncSiActive() }

        // Mode texture de bille essayé puis abandonné (2026-08-22, "ça
        // marche pas, ça semble pas possible avec le moteur présent, efface
        // la fonction") — retiré intégralement, cf. BallCanvasView.

        // 2026-08-31, aller-retour dans la même session : déconnectées du
        // profil de bille ("on commence a se perdre"), passées par une bille
        // de test unique le temps de fiabiliser "Net + doux" (confirmé en
        // usage réel avec la Protection anti-retour) — maintenant reconnectées
        // ici, proprement, pour CHAQUE bille ("on peut reconnecter ça aux
        // billes proprement").
        val fonduLabel = TextView(this).apply {
            text = getString(R.string.blend_fade_title)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        col.addView(fonduLabel)
        val fonduRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fonduPresets = listOf(
            getString(R.string.preset_crisp) to 0.9f,
            getString(R.string.preset_soft) to 0.4f,
            getString(R.string.preset_very_soft) to 0.15f,
            getString(R.string.preset_extreme_soft) to 0.05f,
        )
        val fonduBtns = mutableListOf<TextView>()
        lateinit var fonduSeek: SeekBar
        fonduPresets.forEach { (label, rate) ->
            val btn = UiStyle.pillButton(this, label, muted = p.fonduRate != rate)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
            btn.setOnClickListener {
                p.fonduRate = rate
                fonduBtns.forEachIndexed { i, b -> b.setTextColor(if (fonduPresets[i].second == rate) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
                fonduSeek.progress = ((rate - 0.05f) / 0.95f * 1000).toInt().coerceIn(0, 1000)
                sauverEtSyncSiActive()
            }
            fonduBtns.add(btn)
            fonduRow.addView(btn)
        }
        col.addView(fonduRow)
        fonduSeek = addSlider(col, getString(R.string.slider_fondu_rate), 0.05f, 1f, p.fonduRate) {
            p.fonduRate = it
            fonduBtns.forEachIndexed { i, b -> b.setTextColor(if (fonduPresets[i].second == it) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
            sauverEtSyncSiActive()
        }

        val mixVividLabel = TextView(this).apply {
            text = getString(R.string.label_mix_vivid_experiment)
            UiStyle.body(this)
        }
        mixVividLabel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
        col.addView(mixVividLabel)
        val mixVividRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mixVividOptions = listOf(
            getString(R.string.mix_vivid_current) to 0,
            getString(R.string.mix_vivid_chroma) to 1,
            getString(R.string.mix_vivid_teinte) to 2,
        )
        val mixVividBtns = mutableListOf<TextView>()
        mixVividOptions.forEach { (label, mode) ->
            val btn = UiStyle.pillButton(this, label, muted = p.mixVividMode != mode)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
            btn.setOnClickListener {
                p.mixVividMode = mode
                mixVividBtns.forEachIndexed { i, b -> b.setTextColor(if (mixVividOptions[i].second == mode) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
                sauverEtSyncSiActive()
            }
            mixVividBtns.add(btn)
            mixVividRow.addView(btn)
        }
        col.addView(mixVividRow)
        val mixVividHint = TextView(this).apply {
            text = getString(R.string.hint_mix_vivid_experiment)
            UiStyle.hint(this)
        }
        col.addView(mixVividHint)

        val edgeModeLabel = TextView(this).apply {
            text = getString(R.string.label_trail_edge_mode)
            UiStyle.body(this)
        }
        edgeModeLabel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
        col.addView(edgeModeLabel)
        val edgeModeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val edgeModeOptions = listOf(
            getString(R.string.trail_edge_dur) to 0,
            getString(R.string.trail_edge_doux) to 1,
            getString(R.string.trail_edge_net) to 2,
        )
        val edgeModeBtns = mutableListOf<TextView>()
        edgeModeOptions.forEach { (label, mode) ->
            val btn = UiStyle.pillButton(this, label, muted = p.trailEdgeMode != mode)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
            btn.setOnClickListener {
                p.trailEdgeMode = mode
                edgeModeBtns.forEachIndexed { i, b -> b.setTextColor(if (edgeModeOptions[i].second == mode) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
                sauverEtSyncSiActive()
            }
            edgeModeBtns.add(btn)
            edgeModeRow.addView(btn)
        }
        col.addView(edgeModeRow)
        val edgeModeHint = TextView(this).apply {
            text = getString(R.string.hint_trail_edge_mode)
            UiStyle.hint(this)
        }
        col.addView(edgeModeHint)

        val cometRow = UiStyle.switchRow(this, getString(R.string.toggle_comet_trail), p.cometTrail) { p.cometTrail = it; sauverEtSyncSiActive() }
        cometRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
        col.addView(cometRow)
        val rainbowRow = UiStyle.switchRow(this, getString(R.string.toggle_rainbow_ball), p.rainbowMode) { p.rainbowMode = it; sauverEtSyncSiActive() }
        rainbowRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(rainbowRow)
        val speedColorRow = UiStyle.switchRow(this, getString(R.string.toggle_speed_color), p.speedColorMode) { p.speedColorMode = it; sauverEtSyncSiActive() }
        speedColorRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(speedColorRow)

        addSlider(col, getString(R.string.slider_shake_threshold), 1.5f, 10f, p.shakeThreshold) { p.shakeThreshold = it; sauverEtSyncSiActive() }
        addSlider(col, getString(R.string.slider_shake_force), 200f, 3000f, p.shakeToVel) { p.shakeToVel = it; sauverEtSyncSiActive() }

        val vitRow = UiStyle.switchRow(this, getString(R.string.toggle_speed_thickness), p.vitesseEpaisseur) { p.vitesseEpaisseur = it; sauverEtSyncSiActive() }
        vitRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
        col.addView(vitRow)
        val tiltCurveRow = UiStyle.switchRow(this, getString(R.string.toggle_tilt_curve), p.tiltCurveMode) { p.tiltCurveMode = it; sauverEtSyncSiActive() }
        tiltCurveRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(tiltCurveRow)
        // 2026-08-25, demande explicite : "est-ce que tu pourrais utiliser ça
        // [le dégradé de volume des icônes] pour la bille dans l'app ? ajoute
        // un toggle avec la possibilité de désactiver ça".
        val volumeRow = UiStyle.switchRow(this, getString(R.string.toggle_ball_volume), p.volumeMode) { p.volumeMode = it; sauverEtSyncSiActive() }
        volumeRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(volumeRow)
        val boundsRow = UiStyle.switchRow(this, getString(R.string.toggle_screen_bounds), p.boundsActive) { p.boundsActive = it; sauverEtSyncSiActive() }
        boundsRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(boundsRow)
        val wrapRow = UiStyle.switchRow(this, getString(R.string.toggle_edge_wrap), p.wrapActive) { p.wrapActive = it; sauverEtSyncSiActive() }
        wrapRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(wrapRow)
        val ballVisRow = UiStyle.switchRow(this, getString(R.string.toggle_ball_visible), p.ballVisible) { p.ballVisible = it; sauverEtSyncSiActive() }
        ballVisRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(ballVisRow)
        // 2026-08-20, demande explicite : "je veux que ca sois un
        // paramettre des differentes billez comme ca on sait quand on la
        // choisi quelle agira comme ca" — trait de comportement au toucher
        // propre à chaque bille, retiré de l'onglet Balle global (où il
        // vivait avant, source de confusion : "ca devrais etre dans le
        // paramettre de la bille non ?").
        val ballGrabRow = UiStyle.switchRow(this, getString(R.string.toggle_ball_grab_pinceau), p.ballGrabInPinceau) { p.ballGrabInPinceau = it; sauverEtSyncSiActive() }
        ballGrabRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        col.addView(ballGrabRow)

        return scroll
    }

    // ---------------------------------------------------------------- pinceau setup

    private fun buildPinceauCell(p: PinceauProfile, highlighted: Boolean, onClick: () -> Unit): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 52f), LinearLayout.LayoutParams.WRAP_CONTENT)
            background = UiStyle.ripple(this@MainActivity, if (highlighted) R.drawable.bg_round_rect_sel else R.drawable.bg_round_rect)
            isClickable = true
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(UiStyle.dp(this@MainActivity, 30f), UiStyle.dp(this@MainActivity, 30f))
            val pad = UiStyle.dp(this@MainActivity, 3f)
            setPadding(pad, pad, pad, pad)
            setImageResource(R.drawable.ic_pinceau)
            imageTintList = ColorStateList.valueOf(p.couleur)
        }
        val label = TextView(this).apply {
            text = p.nom
            UiStyle.body(this)
            maxLines = 2
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        cell.contentDescription = p.nom
        cell.addView(icon)
        cell.addView(label)
        cell.setOnClickListener { onClick() }
        return cell
    }

    private fun buildPinceauSetupPanel(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pinceauSetupSubTabs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pinceauSetupSubTabs)
        pinceauSetupContent = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(pinceauSetupContent)
        refreshPinceauSetupTabs()
        return root
    }

    private fun refreshPinceauSetupSelector() {
        val density = resources.displayMetrics.density
        pinceauSetupOnglet = pinceauSetupOnglet.coerceIn(0, pinceauProfiles.size - 1)
        pinceauSetupSubTabs.removeAllViews()
        val cols = 5
        var row: LinearLayout? = null
        var colCount = 0
        fun newRow() {
            row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pinceauSetupSubTabs.addView(row)
            colCount = 0
        }
        newRow()
        pinceauProfiles.forEachIndexed { i, p ->
            // 2026-08-22, demande explicite : masqué (pas supprimé) si
            // désactivé dans Paramètres → Mode développeur → Mode kid.
            if ("pinceau:${p.nom}" in disabledTools) return@forEachIndexed
            if (colCount == cols) newRow()
            val cell = buildPinceauCell(p, highlighted = i == pinceauSetupOnglet) {
                pinceauSetupOnglet = i
                pinceauSetupSubTabs.post { refreshPinceauSetupTabs() }
            }
            cell.layoutParams = (cell.layoutParams as LinearLayout.LayoutParams).apply {
                marginEnd = (4 * density).toInt()
            }
            row!!.addView(cell)
            colCount++
        }
        if (colCount == cols) newRow()
        val addBtn = UiStyle.glyphButton(this, "+", sizeDp = 32f, muted = true)
        addBtn.setOnClickListener {
            pinceauProfiles.add(PinceauProfile(nom = getString(R.string.default_pinceau_name, pinceauProfiles.size + 1)))
            pinceauSetupOnglet = pinceauProfiles.size - 1
            savePinceauProfiles()
            refreshPinceauSetupTabs()
        }
        row!!.addView(addBtn)
    }

    private fun refreshPinceauSetupTabs() {
        refreshPinceauSetupSelector()
        pinceauSetupContent.removeAllViews()
        pinceauSetupContent.addView(buildPinceauProfileEditor(pinceauSetupOnglet))
    }

    private fun buildPinceauProfileEditor(index: Int): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        val p = pinceauProfiles.getOrNull(index) ?: return scroll
        val density = resources.displayMetrics.density
        // BUG TROUVÉ (2026-08-22, "désactivé continue de mélanger") — le
        // pinceau n'a pas d'état en cours à préserver (pas de trait qui se
        // dessine tout seul), donc appliqué systématiquement, sans condition,
        // pour que chaque réglage agisse tout de suite pendant qu'on teste.
        // La bille avait initialement un garde-fou équivalent à celui qu'on
        // évitait ici (index == billeActiveIndex, gardant la bille déjà
        // active de ne pas changer sous les pieds) — retiré le 2026-08-25 à
        // la demande explicite ("tester la config en live"), même logique
        // que ci-dessous désormais, cf. sauverEtSyncSiActive.
        fun sauverEtSyncSiActivePinceau() {
            savePinceauProfiles()
            appliquerPinceauProfile(index)
        }

        val nomEdit = EditText(this).apply {
            setText(p.nom)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        col.addView(nomEdit)
        nomEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val nouveau = nomEdit.text.toString().trim()
                p.nom = nouveau.ifEmpty { getString(R.string.default_pinceau_name, index + 1) }
                savePinceauProfiles()
                // même anti-pattern que nomEdit de la bille : reporter la
                // reconstruction après la fin du cycle de focus en cours.
                nomEdit.post { refreshPinceauSetupTabs() }
            }
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        val applyBtn = UiStyle.pillButton(this, getString(R.string.btn_apply_bille))
        applyBtn.setOnClickListener { appliquerPinceauProfile(index); savePinceauProfiles() }
        actionsRow.addView(applyBtn)
        if (pinceauProfiles.size > 1) {
            val delBtn = UiStyle.pillButton(this, getString(R.string.btn_delete_bille), muted = true)
            delBtn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * density).toInt() }
            delBtn.setOnClickListener {
                pinceauProfiles.removeAt(index)
                savePinceauProfiles()
                refreshPinceauSetupTabs()
            }
            actionsRow.addView(delBtn)
        }
        col.addView(actionsRow)

        val couleurLabel = TextView(this).apply {
            text = getString(R.string.label_pinceau_color)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        col.addView(couleurLabel)
        val couleurMelangeur = MelangeurView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (130 * density).toInt()
            )
        }
        col.addView(couleurMelangeur)
        val couleurTeinteSeek = addSlider(col, getString(R.string.slider_hue), 0f, 360f, couleurMelangeur.hue.toFloat()) { couleurMelangeur.setHue(it.toDouble()) }
        val couleurSatSeek = addSlider(col, getString(R.string.slider_saturation), 0f, 0.4f, couleurMelangeur.chroma.toFloat()) { couleurMelangeur.setChroma(it.toDouble()) }
        // 2026-08-22, demande explicite : cette couleur ne sert que de
        // teinte d'icône (identification du pinceau dans la liste) —
        // n'écrase plus canvas.selectedColor, qui reste piloté par la
        // palette/onglet de raccourcis.
        couleurMelangeur.onColorPicked = { c -> p.couleur = c }
        couleurMelangeur.onDirectTouch = {
            couleurTeinteSeek.progress = (couleurMelangeur.hue / 360.0 * 1000).toInt().coerceIn(0, 1000)
            couleurSatSeek.progress = (couleurMelangeur.chroma / 0.4 * 1000).toInt().coerceIn(0, 1000)
        }
        couleurMelangeur.onColorCommitted = {
            // mêmes 2 correctifs que le mélangeur de la bille : reconstruction
            // différée (crash NPE sinon) et refresh du sélecteur SEUL (pas
            // tout l'éditeur, pour ne pas régénérer la carte de couleurs).
            couleurMelangeur.post {
                sauverEtSyncSiActivePinceau()
                refreshPinceauSetupSelector()
            }
        }

        addSlider(col, getString(R.string.slider_pinceau_width), 1f, 90f, p.largeurDp) { p.largeurDp = it; sauverEtSyncSiActivePinceau() }
        addSlider(col, getString(R.string.slider_trail_texture), 0f, 1f, p.textureAmount) { p.textureAmount = it; sauverEtSyncSiActivePinceau() }

        val fonduLabel = TextView(this).apply {
            text = getString(R.string.blend_fade_title)
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        col.addView(fonduLabel)
        val fonduRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fonduPresets = listOf(
            getString(R.string.preset_crisp) to 0.9f,
            getString(R.string.preset_soft) to 0.4f,
            getString(R.string.preset_very_soft) to 0.15f,
            getString(R.string.preset_extreme_soft) to 0.05f,
        )
        val fonduBtns = mutableListOf<TextView>()
        lateinit var fonduSeek: SeekBar
        fonduPresets.forEach { (label, rate) ->
            val btn = UiStyle.pillButton(this, label, muted = p.fonduRate != rate)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
            btn.setOnClickListener {
                p.fonduRate = rate
                fonduBtns.forEachIndexed { i, b -> b.setTextColor(if (fonduPresets[i].second == rate) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
                fonduSeek.progress = ((rate - 0.05f) / 0.95f * 1000).toInt().coerceIn(0, 1000)
                sauverEtSyncSiActivePinceau()
            }
            fonduBtns.add(btn)
            fonduRow.addView(btn)
        }
        col.addView(fonduRow)
        fonduSeek = addSlider(col, getString(R.string.slider_fondu_rate), 0.05f, 1f, p.fonduRate) {
            p.fonduRate = it
            fonduBtns.forEachIndexed { i, b -> b.setTextColor(if (fonduPresets[i].second == it) UiStyle.TEXT else UiStyle.TEXT_MUTED) }
            sauverEtSyncSiActivePinceau()
        }

        // 2026-08-22, demande explicite : "j'ai besoin des 2 options" —
        // actif = ci-dessus (couleur figée + fondu) ; inactif = couleur du
        // mélangeur en direct, jamais mélangée (comportement d'avant fondu).
        val melangeRow = UiStyle.switchRow(this, getString(R.string.toggle_pinceau_melange), p.melangeActif) { p.melangeActif = it; sauverEtSyncSiActivePinceau() }
        melangeRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
        col.addView(melangeRow)

        return scroll
    }

    // ---------------------------------------------------------------- export

    // Onglet Export = écran d'accueil du panneau (2026-08-19, demande
    // explicite : "c'est le menu principal d'accueil, pour créer des
    // documents, sauver, avec des icônes classiques, ranger la page, pour
    // créer un nouveau") — rangée d'icônes classiques (Nouveau/Sauver/
    // Exporter) en tête, galerie « Mes œuvres » juste en dessous comme
    // contenu principal, réglages d'export (qualité/inclusion) démotés en
    // section secondaire tout en bas. Nouveau garde son comportement exact
    // (efface direct, pas de sauvegarde auto — confirmé explicitement, pas
    // de changement de logique ici, seulement de présentation).
    private fun buildExportPanel(): View {
        val density = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)

        var exportScale = 2f // Haute par défaut : net sans fichier énorme
        var exportBall = false
        var exportObstacles = false

        // 2026-08-24, demande explicite : "la selection du mode kid quoi
        // etre dans parametre comme ca on y change pas" — le sélecteur de
        // mode dupliqué ici depuis le 2026-08-21 (accès rapide) est retiré ;
        // Paramètres reste l'unique endroit pour changer de mode, pour ne
        // plus risquer de le faire par erreur depuis l'écran d'accueil.

        // verrouillage d'écran, dupliqué depuis Paramètres > Mode kid
        // (2026-08-21). 2026-08-24, était passé en dev-only ("doublons
        // réservés au mode dev") en même temps que le sélecteur de mode
        // retiré ci-dessus — mais 2026-08-25, devModeOn passe à false par
        // défaut à l'installation (cf. champ devModeOn) et Paramètres >
        // Mode kid (qui contient l'original) devient alors inatteignable
        // sans activer le mode dev : ce doublon-ci redevient le SEUL accès
        // pratique au verrouillage en usage kid normal, donc remis toujours
        // visible, sans condition — demande explicite : "dans file, il dois
        // y avoir l'option pour verrouiller l'écran... la même option qui
        // se trouve dans les paramètres".
        appLockRowFichier = buildAppLockRow()
        appLockRowFichier.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (10 * density).toInt() }
        col.addView(appLockRowFichier)

        // ------------------------------------------------- rangée d'icônes
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun iconSlot() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        // Nouveau dessin — efface tout et repart d'une page blanche
        // (2026-08-11, demande explicite)
        val nouveauBtn = UiStyle.iconButton(this, R.drawable.ic_new, getString(R.string.caption_new))
        nouveauBtn.layoutParams = iconSlot()
        nouveauBtn.setOnClickListener {
            canvas.clearObstacles()
            for (i in canvas.calqueCount() - 1 downTo 1) canvas.removeCalque(i)
            canvas.clear()
            canvas.bgColor = 0xFFF7F3EC.toInt()
            closeMenus()
            UsageLog.d("nouveau dessin")
        }
        iconRow.addView(nouveauBtn)

        val saveBtn = UiStyle.iconButton(this, R.drawable.ic_save, getString(R.string.caption_save))
        saveBtn.layoutParams = iconSlot()
        saveBtn.setOnClickListener { saveOeuvre() }
        iconRow.addView(saveBtn)

        val exportBtn = UiStyle.iconButton(this, R.drawable.ic_export, getString(R.string.caption_export))
        exportBtn.layoutParams = iconSlot()
        exportBtn.setOnClickListener { exportToGallery(exportScale, exportBall, exportObstacles) }
        iconRow.addView(exportBtn)

        col.addView(iconRow)

        // ------------------------------------------------------- œuvres internes
        val oeuvresTitle = TextView(this).apply {
            text = getString(R.string.my_artworks_title)
            UiStyle.title(this)
            setPadding(0, (18 * density).toInt(), 0, (6 * density).toInt())
        }
        col.addView(oeuvresTitle)
        oeuvresContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(oeuvresContainer)
        refreshOeuvres()

        // ------------------------------------------------- réglages export (secondaires)
        // 2026-08-24, demande explicite : "le mode export on va garder la
        // version 2x partout... des toggle pour reactiver plus tard si je
        // veux, pareil pour les modes d'inclusion" — qualité et inclusion
        // bille/obstacles restent sur leurs valeurs par défaut (2x, aucune
        // inclusion) pour tout le monde ; la section qui permet de les
        // changer est réservée au mode développeur (réactivable en cochant
        // le switch dans Paramètres), pas supprimée.
        exportAdvancedSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        exportAdvancedSection.visibility = if (devModeOn) View.VISIBLE else View.GONE
        col.addView(exportAdvancedSection)

        val exportTitle = TextView(this).apply {
            text = getString(R.string.export_title)
            UiStyle.title(this)
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        exportAdvancedSection.addView(exportTitle)

        val qualityLabel = TextView(this).apply {
            text = getString(R.string.export_quality_label)
            UiStyle.body(this, muted = true)
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        exportAdvancedSection.addView(qualityLabel)

        val options = listOf(getString(R.string.quality_screen) to 1f, getString(R.string.quality_high) to 2f, getString(R.string.quality_very_high) to 3f)
        val qualityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val qualityButtons = mutableListOf<TextView>()

        fun refreshQualityButtons() {
            qualityButtons.forEachIndexed { i, b ->
                b.background = UiStyle.ripple(
                    this, if (options[i].second == exportScale) R.drawable.bg_round_rect_sel else R.drawable.bg_round_rect
                )
            }
        }
        options.forEachIndexed { i, (label, scale) ->
            val btn = UiStyle.pillButton(this, label)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) marginStart = (6 * density).toInt() }
            btn.setOnClickListener {
                exportScale = scale
                refreshQualityButtons()
            }
            qualityButtons.add(btn)
            qualityRow.addView(btn)
        }
        refreshQualityButtons()
        exportAdvancedSection.addView(qualityRow)

        // 2026-08-13, demande explicite : "dans les export aussi avec ou sans
        // bille, avec ou sans obstacle" — OFF par défaut (comportement
        // d'origine inchangé si on ne touche à rien : juste le dessin).
        val includeLabel = TextView(this).apply {
            text = getString(R.string.export_include_label)
            UiStyle.body(this, muted = true)
            setPadding(0, (14 * density).toInt(), 0, (4 * density).toInt())
        }
        exportAdvancedSection.addView(includeLabel)
        val ballToggle = UiStyle.switchRow(this, getString(R.string.toggle_ball_export), exportBall) { exportBall = it }
        ballToggle.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        exportAdvancedSection.addView(ballToggle)
        val obstaclesToggle = UiStyle.switchRow(this, getString(R.string.toggle_obstacles_export), exportObstacles) { exportObstacles = it }
        obstaclesToggle.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        exportAdvancedSection.addView(obstaclesToggle)

        return scroll
    }

    // ---------------------------------------------------------------- œuvres internes

    private fun oeuvresDir(): File = File(filesDir, "oeuvres").apply { mkdirs() }

    /** Sauvegarde l'état complet (fond + calques + couleurs de base + opacités)
     *  dans le stockage interne de l'app — survit aux mises à jour (patches). */
    private fun saveOeuvre() {
        try {
            val dir = File(oeuvresDir(), "oeuvre_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date()))
            dir.mkdirs()
            val n = canvas.calqueCount()
            for (i in 0 until n) {
                val bmp = canvas.calqueBitmapCopy(i) ?: continue
                FileOutputStream(File(dir, "calque_$i.png")).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()
            }
            canvas.renderArtwork(0.25f).let { ap ->
                FileOutputStream(File(dir, "apercu.png")).use { out ->
                    ap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                ap.recycle()
            }
            val sb = StringBuilder().append("bg=").append(canvas.bgColor).append('\n')
                .append("calques=").append(n).append('\n')
            for (i in 0 until n) {
                sb.append("c").append(i).append("_base=").append(canvas.calqueBaseColor(i)).append('\n')
                sb.append("c").append(i).append("_opacity=").append(canvas.calqueOpacity(i)).append('\n')
            }
            File(dir, "config.txt").writeText(sb.toString())
            // Obstacles (2026-08-10, bug réel corrigé : n'étaient jamais
            // sauvegardés) — fichier séparé, texte simple.
            File(dir, "obstacles.txt").writeText(canvas.serializeObstacles())
            UsageLog.d("œuvre sauvegardée : ${dir.name} ($n calques)")
            refreshOeuvres()
        } catch (e: Exception) {
            UsageLog.e("sauvegarde œuvre : ${e.javaClass.simpleName} — ${android.util.Log.getStackTraceString(e)}")
        }
    }

    /** Charge une œuvre sauvegardée : remplace fond + calques pour continuer. */
    private fun loadOeuvre(dir: File) {
        try {
            val config = File(dir, "config.txt").readLines()
            var bg = canvas.bgColor
            var n = 1
            val bases = mutableListOf<Int>()
            val opas = mutableListOf<Float>()
            for (line in config) {
                val parts = line.split("=")
                if (parts.size < 2) continue
                when (parts[0]) {
                    "bg" -> bg = parts[1].toIntOrNull() ?: bg
                    "calques" -> n = parts[1].toIntOrNull() ?: 1
                    else -> {
                        val m = Regex("c(\\d+)_(base|opacity)").matchEntire(parts[0])
                        if (m != null) {
                            val idx = m.groupValues[1].toInt()
                            while (bases.size <= idx) { bases.add(0xFFE53935.toInt()); opas.add(1f) }
                            if (m.groupValues[2] == "base") bases[idx] = parts[1].toIntOrNull() ?: bases[idx]
                            else opas[idx] = parts[1].toFloatOrNull() ?: opas[idx]
                        }
                    }
                }
            }
            canvas.bgColor = bg
            canvas.resetCalques(n, bases.getOrElse(0) { 0xFFE53935.toInt() }, opas.getOrElse(0) { 1f })
            // inMutable = true : BitmapFactory renvoie un bitmap IMMUABLE par
            // défaut, or Canvas.setBitmap() exige un bitmap mutable — sans ça,
            // ça plante (IllegalStateException) et le canvas de peinture reste
            // bloqué sur l'ancien bitmap déjà recyclé (plus de dessin possible)
            val decodeOpts = BitmapFactory.Options().apply { inMutable = true }
            for (i in 0 until n) {
                val bmp = BitmapFactory.decodeFile(File(dir, "calque_$i.png").path, decodeOpts)
                if (bmp != null) canvas.setCalqueBitmap(
                    i, bmp, bases.getOrElse(i) { 0xFFE53935.toInt() }, opas.getOrElse(i) { 1f }
                )
            }
            // Obstacles (2026-08-10, bug réel corrigé) — absent des œuvres
            // sauvegardées avant ce fix, `obstaclesFile.exists()` gère ce
            // cas sans planter (vide simplement les obstacles).
            val obstaclesFile = File(dir, "obstacles.txt")
            canvas.deserializeObstacles(if (obstaclesFile.exists()) obstaclesFile.readText() else "")
            saveSettings()
            refreshOeuvres()
            UsageLog.d("œuvre chargée : ${dir.name} ($n calques)")
        } catch (e: Exception) {
            // e.message est souvent null pour ce type d'erreur — la classe +
            // stacktrace est indispensable pour diagnostiquer sans device réel
            UsageLog.e("chargement œuvre : ${e.javaClass.simpleName} — ${android.util.Log.getStackTraceString(e)}")
        }
    }

    /** Liste les œuvres sauvegardées (miniature + nom + ouvrir/supprimer). */
    private fun refreshOeuvres() {
        if (!::oeuvresContainer.isInitialized) return
        oeuvresContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val dirs = oeuvresDir().listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("oeuvre_") }
            ?.sortedByDescending { it.name } ?: emptyList()
        if (dirs.isEmpty()) {
            oeuvresContainer.addView(TextView(this).apply {
                text = getString(R.string.no_saved_artworks)
                UiStyle.body(this, muted = true)
            })
            return
        }
        val dateRe = Regex("oeuvre_(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})")
        for (dir in dirs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = UiStyle.ripple(this@MainActivity, R.drawable.bg_round_rect)
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
            }
            val apercu = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(File(dir, "apercu.png").path))
            }
            val m = dateRe.matchEntire(dir.name)
            val label = if (m != null) "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]} ${m.groupValues[4]}:${m.groupValues[5]}" else dir.name
            val name = TextView(this).apply {
                text = label
                UiStyle.body(this)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((8 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            val loadBtn = UiStyle.pillButton(this, getString(R.string.btn_open), muted = true)
            loadBtn.setOnClickListener { loadOeuvre(dir) }
            val delBtn = UiStyle.pillButton(this, "✕", muted = true)
            delBtn.setOnClickListener {
                dir.deleteRecursively()
                refreshOeuvres()
            }
            row.addView(apercu)
            row.addView(name)
            row.addView(loadBtn)
            row.addView(delBtn)
            oeuvresContainer.addView(row)
        }
    }

    // État de l'export en attente pendant qu'une permission legacy (Android < 10
    // uniquement) est demandée à l'exécution — rejoué depuis onRequestPermissionsResult.
    private var pendingExport: Triple<Float, Boolean, Boolean>? = null
    private val REQUEST_CODE_EXPORT_STORAGE = 1001

    /** Composite fond + calques (sans la bille) et enregistre en PNG dans la
     *  galerie. Android 10+ (API 29+) : MediaStore Images, scoped storage,
     *  aucune permission. Android < 10 : MediaStore.Downloads/RELATIVE_PATH
     *  n'existent pas encore (plantage trouvé le 29/08 sur un Redmi réel) —
     *  chemin legacy (dossier public direct + MediaScannerConnection),
     *  demandant WRITE_EXTERNAL_STORAGE à l'exécution (déclarée avec
     *  maxSdkVersion=28 dans le manifest, sans effet sur Android 10+). */
    private fun exportToGallery(scale: Float, includeBall: Boolean = false, includeObstacles: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = Triple(scale, includeBall, includeObstacles)
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_EXPORT_STORAGE)
            return
        }
        val bmp = canvas.renderArtwork(scale, includeBall, includeObstacles)
        val appName = getString(R.string.app_name)
        val name = "${appName}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())}.png"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$appName")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    Toast.makeText(this, R.string.toast_exported_gallery, Toast.LENGTH_SHORT).show()
                    UsageLog.d("export PNG → $name (${bmp.width}×${bmp.height}, ×$scale)")
                } else {
                    Toast.makeText(this, R.string.toast_export_failed, Toast.LENGTH_SHORT).show()
                    UsageLog.w("export PNG : insert MediaStore a retourné null")
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), appName)
                dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                Toast.makeText(this, R.string.toast_exported_gallery, Toast.LENGTH_SHORT).show()
                UsageLog.d("export PNG (legacy) → ${file.absolutePath} (${bmp.width}×${bmp.height}, ×$scale)")
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_export_failed_reason, e.message), Toast.LENGTH_SHORT).show()
            UsageLog.e("export PNG : ${e.message}")
        } finally {
            bmp.recycle()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_EXPORT_STORAGE) {
            val export = pendingExport
            pendingExport = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && export != null) {
                exportToGallery(export.first, export.second, export.third)
            } else {
                Toast.makeText(this, R.string.toast_export_failed, Toast.LENGTH_SHORT).show()
                UsageLog.w("export PNG : permission WRITE_EXTERNAL_STORAGE refusée")
            }
        }
    }

    private fun selectSwatch(wrappers: List<LinearLayout>, index: Int) {
        wrappers.forEachIndexed { i, w ->
            w.setBackgroundResource(when {
                i == index -> R.drawable.bg_swatch_ring
                paletteState[i] != null -> R.drawable.bg_swatch_frame // rend le noir visible sur le fond sombre du panneau
                else -> 0
            })
        }
    }

    /** Ligne réglage : label + valeur + SeekBar. */
    private fun addSlider(
        parent: LinearLayout,
        label: String,
        minVal: Float,
        maxVal: Float,
        defVal: Float,
        onChange: (Float) -> Unit
    ): SeekBar {
        val density = resources.displayMetrics.density
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelTv = TextView(this).apply {
            text = label
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(this).apply {
            text = formatValue(defVal)
            UiStyle.body(this, muted = true)
        }
        head.addView(labelTv)
        head.addView(valueTv)

        val seek = SeekBar(this).apply {
            max = 1000
            progress = (((defVal - minVal) / (maxVal - minVal)) * 1000).toInt()
            layoutParams = LinearLayout.LayoutParams((200 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = minVal + (maxVal - minVal) * progress / 1000f
                valueTv.text = formatValue(v)
                if (fromUser) {
                    onChange(v)
                    saveSettings()
                    UsageLog.d("param $label = ${formatValue(v)}")
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })

        val margin = (8 * density).toInt()
        block.addView(head)
        val seekLp = seek.layoutParams as LinearLayout.LayoutParams
        seekLp.setMargins(0, (2 * density).toInt(), 0, margin)
        block.addView(seek)
        parent.addView(block)
        return seek
    }

    private fun formatValue(v: Float): String =
        if (v >= 10f) "%.0f".format(v) else "%.2f".format(v)

    /** Comme addSlider, mais interpole en courbe PUISSANCE (value = maxVal ×
     *  (progress/1000)^power) plutôt que linéaire — 2026-08-29, 2 retours
     *  successifs de Wian sur le slider de masse planète : d'abord "beaucoup
     *  trop faible pour faire des réglages" (un slider linéaire sur ×300,
     *  1M-300M, écrasait les valeurs faibles/moyennes dans les premiers %
     *  du curseur), puis après un essai en log, "la valeur est trop élevée
     *  besoin de partir de 0 et vers bcp plus" (le log ne peut pas inclure
     *  0 comme minimum). La courbe puissance résout les deux : 0 exact tout
     *  en bas du curseur, et `power` > 1 donne plus de résolution en bas de
     *  plage qu'en haut (même logique que le log, sans l'exiger le min > 0).
     *  Affiche toujours la vraie valeur. */
    private fun addSliderPow(
        parent: LinearLayout,
        label: String,
        maxVal: Float,
        defVal: Float,
        power: Float,
        onChange: (Float) -> Unit
    ): SeekBar {
        val density = resources.displayMetrics.density
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelTv = TextView(this).apply {
            text = label
            UiStyle.body(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(this).apply {
            text = formatValue(defVal)
            UiStyle.body(this, muted = true)
        }
        head.addView(labelTv)
        head.addView(valueTv)

        val seek = SeekBar(this).apply {
            max = 1000
            progress = (Math.pow((defVal / maxVal).coerceIn(0f, 1f).toDouble(), (1.0 / power)) * 1000).toInt()
            layoutParams = LinearLayout.LayoutParams((200 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val t = progress / 1000f
                val v = maxVal * Math.pow(t.toDouble(), power.toDouble()).toFloat()
                valueTv.text = formatValue(v)
                if (fromUser) {
                    onChange(v)
                    saveSettings()
                    UsageLog.d("param $label = ${formatValue(v)}")
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })

        val margin = (8 * density).toInt()
        block.addView(head)
        val seekLp = seek.layoutParams as LinearLayout.LayoutParams
        seekLp.setMargins(0, (2 * density).toInt(), 0, margin)
        block.addView(seek)
        parent.addView(block)
        return seek
    }

    // ---------------------------------------------------------------- menus

    /** Ouvre le menu d'un onglet dans le panneau partagé (même endroit, même taille). */
    private fun openMenu(index: Int, animDir: Int = 0) {
        // ouvrir le menu = mettre la bille en pause (on règle, pas de bille qui roule)
        canvas.setPaused(true)
        // panneau ancré à l'OPPOSÉ du cluster (miroir) : fermeture en le
        // repoussant vers son bord (1 = gauche, 2 = droite, cf. BallCanvasView)
        canvas.openDirection = if (controlsOnRight) 1 else 2
        val offscreen = UiStyle.dp(this, 90f).toFloat().let { if (controlsOnRight) -it else it }
        // animation de slide dans le SENS DU GESTE (le contenu suit le doigt) :
        // animDir = 1 → le contenu glisse vers la gauche, -1 → vers la droite
        val dir = if (animDir != 0) animDir else if (index >= ongletCourant) 1 else -1
        menuFlipper.setInAnimation(this, if (dir > 0) R.anim.slide_in_right else R.anim.slide_in_left)
        menuFlipper.setOutAnimation(this, if (dir > 0) R.anim.slide_out_left else R.anim.slide_out_right)
        menuFlipper.displayedChild = index
        ongletCourant = index
        updateOngletActif(index)
        versionLabel.visibility = View.GONE
        if (panelMenu.visibility != View.VISIBLE) {
            panelMenu.visibility = View.VISIBLE
            panelMenu.alpha = 0f
            panelMenu.translationX = offscreen
            panelMenu.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun closeMenus() {
        UsageLog.d("closeMenus: début") // 2026-08-20, rapporté : crash "en quittant la configuration d'une nouvelle bille"
        // fermer le sous-menu obstacle aussi
        submenuObstacle.visibility = View.GONE
        // fermer le menu = remettre la bille en lecture
        canvas.setPaused(false)
        canvas.openDirection = 0
        updateOngletActif(-1)
        if (!uiHidden) versionLabel.visibility = View.VISIBLE
        val offscreen = UiStyle.dp(this, 90f).toFloat().let { if (controlsOnRight) it else -it }
        if (panelMenu.visibility == View.VISIBLE) {
            panelMenu.animate()
                .translationX(offscreen)
                .alpha(0f)
                .setDuration(160)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    panelMenu.visibility = View.GONE
                    panelMenu.alpha = 1f
                }
                .start()
        }
    }

    /** Surligne l'icône de l'onglet actif (charte uniforme) et affiche la
     *  barre d'onglets seulement pour Export/Paramètres — Palette reste une
     *  vue isolée, sans barre. */
    private fun updateOngletActif(index: Int) {
        ongletBtns.forEachIndexed { i, b ->
            b.background = UiStyle.ripple(this, if (hamburgerTabIndices[i] == index) R.drawable.bg_round_rect_sel else R.drawable.bg_round_rect)
        }
        tabsRowScroll.visibility = if (index in hamburgerTabIndices) View.VISIBLE else View.GONE
    }

    /** Masque les onglets bibliothèque bille/pinceau (4/5) hors mode
     *  développeur — cf. devOnlyTabs. Rabat sur Export si l'onglet
     *  actuellement affiché vient de disparaître (mode dev désactivé
     *  pendant qu'on y était). */
    private fun refreshHamburgerTabsVisibility() {
        hamburgerTabIndices.forEachIndexed { i, tab ->
            ongletBtns.getOrNull(i)?.visibility = if (tab in devOnlyTabs && !devModeOn) View.GONE else View.VISIBLE
        }
        if (!devModeOn && ongletCourant in devOnlyTabs) openMenu(0)
    }

    /** Bascule tout le cluster de contrôles (mini-rangée + pause + onglets +
     *  raccourcis) entre le bord droit et le bord gauche de l'écran. */
    /** Bascule entre la liste de pastilles (défaut) et le sélecteur mode
     *  enfant (pavé dans la barre, ou roue dans son panneau séparé selon
     *  pickerType) — même emplacement pour pastilles/pavé, un seul visible
     *  à la fois ; la roue vit à part (cf. applyPickerType()). */
    private fun applyModeEnfant() {
        quickColorsScrollView.visibility = if (modeEnfant) View.GONE else View.VISIBLE
        applyPickerType()
    }

    /** Bascule entre le pavé 2D (dans la barre de raccourcis, étroit) et la
     *  roue (panneau séparé, fond noir, centré à l'écran) — un seul visible
     *  à la fois, tous deux masqués hors mode enfant. */
    private fun applyPickerType() {
        // 0 = pavé (2D), 3 = simple (2026-08-20, demande explicite : "je
        // veux aussi le simple sans la saturation, comme au debut") — même
        // widget hueSlider, même emplacement dans la barre, juste la
        // saturation verrouillée à fond.
        val padeActif = modeEnfant && (pickerType == 0 || pickerType == 3)
        val panelActif = modeEnfant && (pickerType == 1 || pickerType == 2) // roue OU joystick, même panneau séparé
        hueSliderGroup.visibility = if (padeActif) View.VISIBLE else View.GONE
        hueSlider.saturationLocked = pickerType == 3
        wheelPanel.visibility = if (panelActif) View.VISIBLE else View.GONE
        colorWheel.visibility = if (modeEnfant && pickerType == 1) View.VISIBLE else View.GONE
        joystickPalette.visibility = if (modeEnfant && pickerType == 2) View.VISIBLE else View.GONE
        if (pickerType == 2) joystickPalette.colors = paletteState.filterNotNull()
        if (panelActif) positionWheelPanel()
    }

    /** Applique wheelSizeDp aux deux widgets qui en dépendent (roue + joystick). */
    private fun applyWheelSize() {
        val sizePx = (wheelSizeDp * resources.displayMetrics.density).toInt()
        colorWheel.layoutParams = (colorWheel.layoutParams as LinearLayout.LayoutParams).apply {
            width = sizePx; height = sizePx
        }
        colorWheel.requestLayout()
        joystickPalette.layoutParams = (joystickPalette.layoutParams as LinearLayout.LayoutParams).apply {
            width = sizePx; height = sizePx
        }
        joystickPalette.requestLayout()
        if (modeEnfant && pickerType != 0) positionWheelPanel()
    }

    private val backupDisplayName = "KidOrb-profils-backup.json"

    /** Écrit TOUTES les prefs (profils de mode, billes, tous les réglages)
     *  dans un fichier JSON du stockage partagé (Téléchargements) — survit à
     *  une désinstallation/réinstallation, contrairement à SharedPreferences
     *  (2026-08-22, demande explicite : "si je dois delete et reinstaller
     *  pour fixer un bug je perd [mes billes], c'est pas bon"). Un seul
     *  fichier réécrit à chaque fois (pas d'accumulation), appelé depuis
     *  onStop() comme saveSettings() — même logique ceinture. */
    private fun backupProfilsVersStockagePartage() {
        // MediaStore.Downloads n'existe qu'à partir d'Android 10 (API 29) —
        // provoquait un plantage (NoClassDefFoundError) sur les Android plus
        // anciens (trouvé le 29/08 via un crash réel sur Redmi). Fonctionnalité
        // secondaire (survit à une désinstallation) : désactivée proprement sur
        // les vieux Android plutôt que de complexifier avec un chemin legacy.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val json = JSONObject()
            prefs.all.forEach { (k, v) ->
                val tagged = JSONObject()
                when (v) {
                    is Boolean -> { tagged.put("t", "b"); tagged.put("v", v) }
                    is Int -> { tagged.put("t", "i"); tagged.put("v", v) }
                    is Float -> { tagged.put("t", "f"); tagged.put("v", v.toDouble()) }
                    is Long -> { tagged.put("t", "l"); tagged.put("v", v) }
                    is String -> { tagged.put("t", "s"); tagged.put("v", v) }
                    is Set<*> -> { tagged.put("t", "set"); tagged.put("v", JSONArray(v.toList())) }
                    else -> return@forEach
                }
                json.put(k, tagged)
            }
            val bytes = json.toString(2).toByteArray()
            val resolver = contentResolver
            val existing = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(backupDisplayName), null
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
            val uri = existing ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, backupDisplayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            })
            uri?.let { u -> resolver.openOutputStream(u, "wt")?.use { it.write(bytes) } }
        } catch (e: Exception) {
            UsageLog.d("backup profils échoué: ${e.message}")
        }
    }

    /** Restaure les prefs depuis la sauvegarde du stockage partagé — appelé
     *  en tout début d'onCreate, AVANT toute lecture de prefs. Ne touche à
     *  rien si des prefs existent déjà (pas d'écrasement d'une session en
     *  cours) : ne s'applique qu'à une install neuve ou une réinstallation
     *  après désinstallation, les deux se présentant de façon identique
     *  (prefs vides) côté app. */
    private fun restoreProfilsSiPremierLancement() {
        if (prefs.contains("billeProfileCount") || prefs.contains("modeProfils_ids")) return
        // Même garde que backupProfilsVersStockagePartage() — MediaStore.Downloads
        // n'existe qu'à partir d'Android 10, plantait au tout premier lancement
        // sur les Android plus anciens (juste après le fix UsageLog, même onCreate).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val uri = contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(backupDisplayName), null
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null } ?: return
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
            val json = JSONObject(text)
            val e = prefs.edit()
            json.keys().forEach { k ->
                val tagged = json.getJSONObject(k)
                when (tagged.getString("t")) {
                    "b" -> e.putBoolean(k, tagged.getBoolean("v"))
                    "i" -> e.putInt(k, tagged.getInt("v"))
                    "f" -> e.putFloat(k, tagged.getDouble("v").toFloat())
                    "l" -> e.putLong(k, tagged.getLong("v"))
                    "s" -> e.putString(k, tagged.getString("v"))
                    "set" -> {
                        val arr = tagged.getJSONArray("v")
                        val set = mutableSetOf<String>()
                        for (i in 0 until arr.length()) set.add(arr.getString(i))
                        e.putStringSet(k, set)
                    }
                }
            }
            e.apply()
            UsageLog.d("profils restaurés depuis $backupDisplayName (${json.length()} clés)")
        } catch (e: Exception) {
            UsageLog.d("restauration profils échouée: ${e.message}")
        }
    }

    // id 0/1 gardent leurs clés d'origine ("_normal"/"_kid", posées avant
    // que le système ne devienne une liste ouverte) — tout nouveau mode créé
    // via "+" (id ≥ 2) utilise "_m<id>", stable même si le nom est modifié.
    private fun profilSuffix(profil: Int = configProfilActif) = when (profil) {
        0 -> "normal"
        1 -> "kid"
        else -> "m$profil"
    }

    /** Charge la liste des profils/modes (Normal/Kid + ceux créés via "+")
     *  depuis les prefs — 2026-08-21, demande explicite : "je pense qu'on
     *  devrait pouvoir creer dautre mode que juste kid... prevois pour le
     *  moment de pouvoir cree un autre mode avec un petit plus". */
    private fun loadModeProfils() {
        modeProfils.clear()
        val ids = (prefs.getString("modeProfils_ids", "0,1") ?: "0,1")
            .split(",").mapNotNull { it.toIntOrNull() }
        for (id in ids) {
            val defautNom = when (id) { 0 -> "Normal"; 1 -> "Kid"; else -> "Mode $id" }
            modeProfils.add(ModeProfil(id, prefs.getString("modeProfils_nom_$id", defautNom) ?: defautNom))
        }
        if (modeProfils.isEmpty()) modeProfils.add(ModeProfil(0, "Normal"))
        nextModeId = prefs.getInt("modeProfils_nextId", (modeProfils.maxOf { it.id } + 1).coerceAtLeast(2))
    }

    private fun saveModeProfils() {
        val e = prefs.edit()
        e.putString("modeProfils_ids", modeProfils.joinToString(",") { it.id.toString() })
        e.putInt("modeProfils_nextId", nextModeId)
        modeProfils.forEach { e.putString("modeProfils_nom_${it.id}", it.nom) }
        e.apply()
    }

    /** Reconstruit les sélecteurs de mode affichés (Paramètres + Fichier) —
     *  plusieurs instances à tenir synchronisées, cf. modeSelectorContainers. */
    private fun refreshModeSelectors() {
        modeSelectorContainers.forEach { populateModeSelector(it) }
    }

    /** Construit le toggle de verrouillage d'écran (épingle l'app via
     *  startLockTask/stopLockTask) — toujours visible, indépendamment du
     *  mode kid ; utilisé en Paramètres > Mode kid et dupliqué dans
     *  l'onglet Fichier pour un accès direct. */
    private fun buildAppLockRow(): LinearLayout {
        val density = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lockRow = UiStyle.switchRow(this, getString(R.string.toggle_app_lock), appLocked) { checked ->
            try {
                if (checked) startLockTask() else stopLockTask()
                appLocked = checked
                btnQuitterModeKid.visibility = if (checked) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                UsageLog.d("verrouillage app échoué: ${e.message}")
            }
        }
        lockRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
        col.addView(lockRow)
        val lockHint = TextView(this).apply {
            text = getString(R.string.hint_app_lock)
            UiStyle.hint(this)
        }
        col.addView(lockHint)
        return col
    }

    /** Peuple UN conteneur de sélecteur de mode : pastilles (une par
     *  profil, rangées de 3 qui s'enchaînent vers le bas comme la grille de
     *  billes) + "+" pour en créer un nouveau, copie du profil actif. */
    /** Peuple UN conteneur de sélecteur de mode. `container.tag == "manage"`
     *  (posé seulement sur l'instance Paramètres) autorise création (+),
     *  en plus réservée à `devModeOn` (2026-08-24, demande explicite : la
     *  sélection reste possible sans mode dev, la création non) —
     *  2026-08-22, demande explicite : Fichier ne garde QUE la sélection
     *  des profils déjà créés, pas de bouton +. La suppression ne vit PAS
     *  ici (essayé en rangée séparée sous les pastilles, jugé illisible) —
     *  elle vit dans buildParametresModeKid(), en bouton unique agissant
     *  sur le profil actif, à la suite de ses réglages (cf. commentaire
     *  là-bas : "à la suite des toggles, un bouton pour supprimer LE
     *  profil sélectionné", pas une liste de tous les profils ici). */
    private fun populateModeSelector(container: LinearLayout) {
        val manage = container.tag == "manage"
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val cols = 3
        var row: LinearLayout? = null
        var colCount = cols // force newRow() dès le premier ajout
        fun addToGrid(view: View) {
            if (colCount == cols) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                container.addView(row)
                colCount = 0
            }
            row!!.addView(view)
            colCount++
        }
        modeProfils.forEach { m ->
            val btn = UiStyle.pillButton(this, m.nom, muted = m.id != configProfilActif)
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt(); bottomMargin = (6 * density).toInt() }
            btn.setOnClickListener {
                configProfilActif = m.id
                saveSettings()
                appliquerProfilConfig(configProfilActif)
                refreshModeSelectors()
                refreshParamSousOnglets()
            }
            addToGrid(btn)
        }
        if (manage && devModeOn) {
            val addBtn = UiStyle.glyphButton(this, "+", sizeDp = 32f, muted = true)
            addBtn.setOnClickListener {
                val newId = nextModeId
                nextModeId++
                modeProfils.add(ModeProfil(newId, "Mode ${modeProfils.size + 1}"))
                // nouveau profil = copie du profil ACTUELLEMENT actif (état live) —
                // point de départ raisonnable plutôt que des valeurs par défaut à l'aveugle.
                sauverProfilConfigActif(newId)
                configProfilActif = newId
                saveModeProfils()
                saveSettings()
                appliquerProfilConfig(configProfilActif)
                refreshModeSelectors()
                refreshParamSousOnglets()
            }
            addToGrid(addBtn)
        }
    }

    /** Charge le profil/mode demandé depuis ses clés préfixées et l'applique
     *  en direct à l'app — 2026-08-21, demande explicite : "tout les toggle
     *  que je chippote on effet directement" (pas de bouton "valider"
     *  différé, contrairement à ce qui avait été proposé). */
    // 2026-08-25, demande explicite : "que les profils soient sauvegardés
    // dans l'app, pas dans les data du téléphone" — le profil Kid livré à
    // l'installation (cf. devModeOn/configProfilActif) doit déjà être
    // configuré pour un enfant, pas un profil vide aux réglages génériques
    // ci-dessous. Valeurs figées depuis le dernier `KidOrb-profils-
    // backup.json` exporté ; seuls ces 4 champs divergent des défauts
    // génériques pour "kid" (les autres — wheelSizeDp/tabbarScale/marges —
    // coïncident déjà).
    // 2026-08-29, demande explicite : "je ne veux pas de trampoline dans
    // kidorb, ni de gomme à obstacle" — trampoline ajouté à la liste (même
    // catégorie que bezier, déjà caché) ; effet de bord bienvenu : sans
    // trampoline dans cette liste, formesKeys.all{disabled} redevenait faux
    // (cf. applyToolVisibility), ce qui réaffichait aussi la gomme
    // obstacles à tort (elle ne se cache que quand TOUT Formes+Effets l'est).
    private val defaultDisabledToolsKid =
        "mur,bouchon,triangle,bezier,trampoline,rectangle,ellipse,planete,planete_inverse,accelerateur,select,construction_eye,undo,redo,palette,pinceau:pinceau mix,portail"

    private fun appliquerProfilConfig(profil: Int) {
        val suf = profilSuffix(profil)
        modeEnfant = prefs.getBoolean("modeEnfant_$suf", suf == "kid")
        zoomLocked = prefs.getBoolean("zoomLocked_$suf", suf == "kid")
        interfaceEditVisible = prefs.getBoolean("interfaceEditVisible_$suf", false)
        pickerType = prefs.getInt("pickerType_$suf", if (suf == "kid") 3 else 0)
        wheelSizeDp = prefs.getFloat("wheelSizeDp_$suf", 190f)
        disabledTools.clear()
        val disabledToolsDefault = if (suf == "kid") defaultDisabledToolsKid else ""
        disabledTools.addAll((prefs.getString("disabledTools_$suf", disabledToolsDefault) ?: "").split(",").filter { it.isNotBlank() })
        // 2026-08-21, demande explicite : "est ce que les modes sauvegarde
        // bien la position des raccourcis et des menu onglet" → non,
        // c'était global — maintenant propre à chaque profil aussi.
        tabbarScale = prefs.getFloat("tabbarScale_$suf", 1f)
        controlsEdgeMargin = prefs.getFloat("controlsEdgeMargin_$suf", 10f)
        controlsBottomMargin = prefs.getFloat("controlsBottomMargin_$suf", 74f)
        panelBottomMargin = prefs.getFloat("panelBottomMargin_$suf", 74f)
        canvas.zoomLocked = zoomLocked
        applyModeEnfant()
        applyWheelSize()
        refreshToolVisibilityCb?.invoke()
        tabbarView.scaleX = tabbarScale
        tabbarView.scaleY = tabbarScale
        applyControlsSide()
    }

    /** Sauve l'état courant (modeEnfant/zoomLocked/pickerType/wheelSizeDp/
     *  disabledTools/position des raccourcis et du panneau) dans les clés
     *  du profil actuellement sélectionné — appelé par chaque toggle des
     *  onglets Développeur et Interface, comme sauverEtSyncSiActive() pour
     *  les billes. */
    private fun sauverProfilConfigActif(profil: Int = configProfilActif) {
        val suf = profilSuffix(profil)
        prefs.edit()
            .putBoolean("modeEnfant_$suf", modeEnfant)
            .putBoolean("zoomLocked_$suf", zoomLocked)
            .putBoolean("interfaceEditVisible_$suf", interfaceEditVisible)
            .putInt("pickerType_$suf", pickerType)
            .putFloat("wheelSizeDp_$suf", wheelSizeDp)
            .putString("disabledTools_$suf", disabledTools.joinToString(","))
            .putFloat("tabbarScale_$suf", tabbarScale)
            .putFloat("controlsEdgeMargin_$suf", controlsEdgeMargin)
            .putFloat("controlsBottomMargin_$suf", controlsBottomMargin)
            .putFloat("panelBottomMargin_$suf", panelBottomMargin)
            .apply()
    }

    /** Recalcule et applique les tailles du tabbar (icônes, mélangeur ou
     *  pastilles, hauteur totale) selon l'espace RÉELLEMENT disponible.
     *  Appelée une fois au premier layout (OnGlobalLayoutListener de
     *  controlsCluster, onCreate) ET à chaque changement de type de
     *  sélecteur (pickerType/modeEnfant, cf. le sélecteur de
     *  buildPalettePanel()) — les deux affichent des éléments de largeur/
     *  hauteur différentes (mélangeur fixe vs pastilles qui partagent la
     *  taille des icônes) et rien ne redéclenchait ce calcul en cours
     *  d'usage jusqu'ici (l'ancien listener se retirait après son premier
     *  passage). Signalé par Wian (2026-09-04) : "la largeur change si je
     *  passe en mode pastille, vérifie" — les icônes restaient bloquées à
     *  la taille calculée pour le mode actif AU LANCEMENT, jamais
     *  recalculée en rebasculant vers l'autre mode. Applique désormais
     *  TOUJOURS sizeDp (pas seulement s'il est réduit) pour pouvoir aussi
     *  bien réduire qu'agrandir selon le mode qui vient de s'activer. */
    private fun recalculerTaillesTabbar() {
        if (controlsCluster.height == 0) return
        val density = resources.displayMetrics.density
        val hDp = controlsCluster.height / density
        // fixedOverheadDp = tout ce qui n'est PAS une icône réductible :
        // switch_side (36+6 marge) + padding tabbar (6+6) + 6 marges de 6dp
        // entre les 7 boutons fixes + marge avant/après le ScrollView (8+8).
        val fixedOverheadDp = 106f
        val defaultSizeDp = 44f
        // marge de sécurité : les multiples .toInt() (troncature, pas
        // arrondi) sur 9 tailles (8 icônes + ScrollView) perdent chacun
        // jusqu'à ~1dp — sans cette marge, la dernière pastille du
        // ScrollView se retrouvait tronquée de quelques px (repéré par
        // Wian : "la pastille jaune est coupée").
        val safetyMarginDp = 10f
        val iconGapDp = 6f
        val fixedTabbarButtonIds = intArrayOf(
            R.id.btn_balle, R.id.btn_pinceau, R.id.btn_obstacle, R.id.btn_effets,
            R.id.btn_gomme, R.id.btn_select, R.id.btn_construction_eye, R.id.btn_palette
        )
        val hueVisible = ::hueSliderGroup.isInitialized && hueSliderGroup.visibility == View.VISIBLE
        // taille des icônes : deux formules distinctes selon ce qui occupe
        // le bas du tabbar (jamais les deux à la fois) — le mélangeur
        // (hueSliderGroup) ne partage PAS sa taille avec les icônes,
        // contrairement aux pastilles (rebuildQuickColors lit
        // tabbarButtonSizeDp).
        // 2026-09-04, retour Huawei (2 passes) : d'abord réservait un
        // plancher fixe de 100dp pour le mélangeur ("les icones du menu
        // sont petites aussi" — le plancher était en fait trop haut pour ce
        // cas précis, réduisant les icônes sans raison sur un écran qui en
        // avait besoin) ; puis "il est pas assez long surtout" — le
        // plancher fixe capait le mélangeur bien avant que les icônes
        // n'aient cédé tout ce qu'elles pouvaient. Priorité désormais
        // explicite au mélangeur : icônes à taille pleine (44dp) tant que
        // le budget total (icônes + mélangeur à 264dp) tient, sinon elles
        // cèdent jusqu'à LEUR PROPRE plancher (28dp) avant que le mélangeur
        // n'accepte de rester sous 264dp (cf. 2e calcul plus bas, qui lui
        // donne tout le reste une fois les icônes fixées ici).
        val hueTopMarginDp = 8f
        val hueTargetDp = 264f
        val sizeDp = if (hueVisible) {
            val budgetDp = hDp - fixedOverheadDp - safetyMarginDp - hueTopMarginDp -
                (fixedTabbarButtonIds.size - 1) * iconGapDp
            val fullBudgetDp = fixedTabbarButtonIds.size * defaultSizeDp + hueTargetDp
            if (budgetDp >= fullBudgetDp) defaultSizeDp
            else ((budgetDp - hueTargetDp) / fixedTabbarButtonIds.size).coerceIn(28f, defaultSizeDp)
        } else {
            val visiblePastillesTarget = 4f
            // chaque pastille de rebuildQuickColors() a un wrapper de
            // size+4dp, avec 6dp de marge entre elles (pas avant la 1re)
            val pastilleWrapperExtraDp = 4f
            val pastilleGapDp = 6f
            val extraDp = fixedOverheadDp + safetyMarginDp +
                visiblePastillesTarget * pastilleWrapperExtraDp +
                (visiblePastillesTarget - 1f) * pastilleGapDp
            ((hDp - extraDp) / (fixedTabbarButtonIds.size + visiblePastillesTarget))
                .coerceIn(28f, defaultSizeDp)
        }
        val sizePx = (sizeDp * density).toInt()
        // même ratio icône-visible/container que l'original (44dp
        // container, 10dp padding → icône visible 24dp, 54,5 %)
        val paddingPx = (sizeDp * 0.2273f * density).toInt()
        for (id in fixedTabbarButtonIds) {
            val btn = findViewById<ImageView>(id)
            btn.layoutParams = btn.layoutParams.apply {
                width = sizePx
                height = sizePx
            }
            btn.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }
        // les pastilles de couleur (rebuildQuickColors) lisent
        // tabbarButtonSizeDp — mettre à jour puis reconstruire pour
        // qu'elles s'affichent déjà à la bonne taille, qu'elles aient été
        // construites avant ou après ce point.
        tabbarButtonSizeDp = sizeDp
        rebuildQuickColors()
        // largeur du mélangeur (hueSliderGroup/hueSlider) alignée sur la
        // même taille que les icônes plutôt qu'une constante fixe (48dp)
        // indépendante — demande explicite (2026-09-04) : "même la largeur
        // de la barre de dégradé... cohérent avec la taille des autres
        // icônes c'est mieux".
        if (::hueSliderGroup.isInitialized) {
            (hueSliderGroup.layoutParams as LinearLayout.LayoutParams).width = sizePx
            (hueSlider.layoutParams as LinearLayout.LayoutParams).width = sizePx
            hueSliderGroup.requestLayout()
        }
        // applyControlsSide() a déjà été appelée une fois dans onCreate
        // avec tabbarButtonSizeDp=44f (valeur par défaut, avant que ce
        // calcul ne s'exécute) — la rappeler recalcule le centrage sur
        // btn_hide_ui/btn_menu avec la vraie taille, réduite ou non.
        applyControlsSide()
        if (sizeDp < defaultSizeDp - 1f) {
            Log.d("InkOrbLayout", "icônes tabbar réduites à ${sizeDp.toInt()}dp (écran court, clusterH=${hDp.toInt()}dp)")
        }
        // mélangeur (hueSliderGroup, mode enfant + pickerType
        // Dégradé/Simple) : hauteur FIXE (264dp par défaut), sans
        // ScrollView pour absorber un manque de place, contrairement à
        // quickColorsScroll (weight=1, se comprime tout seul) — débordait
        // tel quel sur écran court, emportant btn_palette (dernier enfant
        // du tabbar, juste après lui) hors de portée. Signalé par Wian sur
        // le Huawei (P30 Lite), InkOrb en mode "Dégradé" : "la barre de
        // raccourci semble trop courte, il manque des couleurs et je perds
        // l'accès au bouton palette" (2026-09-04).
        // 2026-09-04, suite : un 1er calcul basé sur fixedOverheadDp
        // (constante calibrée pour le cas quickColorsScroll, jamais
        // revérifiée pour celui-ci) sous-estimait l'espace réel d'environ
        // 50dp — vérifié sur pièce (uiautomator dump : tabbar s'arrêtait à
        // 1566px alors que controlsCluster allait jusqu'à 1698px, ~50dp de
        // vide jamais utilisé) — "la barre de raccourci pourrait être plus
        // longue". Remplacé par une MESURE réelle : le mélangeur est
        // temporairement réduit à 0, tabbarView mesuré (donne l'overhead
        // RÉEL des icônes + palette + marges, sans estimation), puis le
        // mélangeur reçoit tout ce qu'il reste de `availableForTabbarPx`
        // (même valeur d'espace dispo que le bloc de mesure de tabbarView
        // juste en dessous), plafonné à 264dp.
        if (hueVisible) {
            val hueTargetPx = (hueTargetDp * density).toInt()
            hueSliderGroup.layoutParams = (hueSliderGroup.layoutParams as LinearLayout.LayoutParams).apply { height = 0 }
            tabbarView.measure(
                View.MeasureSpec.makeMeasureSpec(tabbarView.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED
            )
            val overheadPx = tabbarView.measuredHeight
            val availableTotalPx = controlsCluster.height - tabbarView.top
            val availableForHuePx = (availableTotalPx - overheadPx).coerceAtLeast(0)
            val hueAbsoluteMinPx = (80 * density).toInt()
            val finalHuePx = availableForHuePx.coerceIn(hueAbsoluteMinPx, hueTargetPx)
            hueSliderGroup.layoutParams = (hueSliderGroup.layoutParams as LinearLayout.LayoutParams).apply {
                height = finalHuePx
            }
            hueSliderGroup.requestLayout()
            Log.d("InkOrbLayout", "mélangeur = ${(finalHuePx / density).toInt()}dp (mesuré, dispo=${(availableTotalPx / density).toInt()}dp, overhead=${(overheadPx / density).toInt()}dp)")
        }
        // tabbar est en layout_weight=1 dans le XML (nécessaire pour que
        // quickColorsScroll, weight=1 À L'INTÉRIEUR de tabbar, puisse se
        // comprimer sur écran court — weight exige un parent à taille
        // EXACTE, incompatible avec wrap_content). Conséquence : sur un
        // écran plus haut que nécessaire ou un contenu réduit (KidOrb,
        // plusieurs boutons cachés par defaultDisabledToolsKid,
        // hueSliderGroup à taille fixe), tabbar s'étirait quand même pour
        // remplir tout l'espace, laissant un grand vide sous son contenu
        // réel — "le menu des raccourcis est très grand alors que c'est
        // vide" (2026-09-03). Mesuré manuellement ici (measure() en
        // wrap_content réel, hors du système de weight) puis fixé à sa
        // hauteur naturelle, plafonnée par l'espace disponible — ne remplit
        // plus l'espace aveuglément, ne déborde jamais.
        tabbarView.measure(
            View.MeasureSpec.makeMeasureSpec(tabbarView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        val availableForTabbarPx = controlsCluster.height - tabbarView.top
        val naturalHeightPx = tabbarView.measuredHeight
        val finalHeightPx = naturalHeightPx.coerceAtMost(availableForTabbarPx.coerceAtLeast(0))
        (tabbarView.layoutParams as LinearLayout.LayoutParams).apply {
            height = finalHeightPx
            weight = 0f
        }
        tabbarView.requestLayout()
    }

    private fun applyControlsSide() {
        // ConstraintLayout.LayoutParams (parent = ConstraintLayout dédié à
        // topRow/controlsCluster, activity_main.xml 2026-09-03) : on
        // réutilise l'objet existant (déjà du bon type après inflate) et ne
        // touche QUE les contraintes horizontales — topToBottomOf(topRow) +
        // bottomToBottom(parent), posées dans le XML, ne sont jamais
        // modifiées ici, ce qui garantit qu'aucun chevauchement avec topRow
        // n'est possible peu importe le côté choisi.
        val clusterLp = controlsCluster.layoutParams as ConstraintLayout.LayoutParams
        // "Grille" commune aux deux côtés — même logique de centrage pour
        // btn_menu (hamburger, gauche) et btn_hide_ui (œil, droite), au lieu
        // d'un ancrage par bord + réglage manuel ("Espacement bord") : les
        // deux boutons de coin ont la même géométrie (marge écran 10dp,
        // largeur 36dp), donc le même centre à 10+18=28dp du bord — valeur
        // fixe dérivée du XML de topRow/btn_menu, jamais réduite. Sans ça,
        // le cluster (⇄ + tabbar, plus large que ces boutons de coin) ancré
        // par son bord extérieur avait son CENTRE décalé de plusieurs dp par
        // rapport au bouton de coin correspondant — "pas aligné sur l'axe
        // de l'œil" à droite (2026-09-03), même défaut à gauche sur le
        // hamburger jamais corrigé jusqu'ici ("à gauche maintenant c'est pas
        // alligné sur le burger"). clusterWidthDp dérivé de
        // tabbarButtonSizeDp (padding tabbar fixe 6+6dp) plutôt que mesuré :
        // appelé aussi depuis le calcul de réduction des icônes, avant que
        // le nouveau layout (donc controlsCluster.width) ne soit stable.
        // Écrase le réglage "Espacement bord" (controlsEdgeMargin) des deux
        // côtés désormais — coercé à 0 minimum pour ne jamais sortir de
        // l'écran si le cluster dépasse 56dp de large.
        val cornerButtonCenterFromEdgeDp = 28f
        // +4dp : les pastilles de couleur (rebuildQuickColors) ont un
        // wrapper de tabbarButtonSizeDp+4dp, plus large que les boutons
        // fixes carrés — oublié au premier calcul, le cluster réel mesurait
        // 46dp au lieu des 43dp attendus, laissant ~3dp d'écart résiduel
        // (perçu comme un décalage visible entre hamburger et ⇄, 2026-09-03).
        val clusterWidthDp = tabbarButtonSizeDp + 4f + 12f
        val marginDp = (cornerButtonCenterFromEdgeDp - clusterWidthDp / 2f).coerceAtLeast(0f)
        val marginPx = UiStyle.dp(this@MainActivity, marginDp)
        if (controlsOnRight) {
            clusterLp.startToStart = ConstraintLayout.LayoutParams.UNSET
            clusterLp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            clusterLp.marginStart = 0
            clusterLp.marginEnd = marginPx
        } else {
            clusterLp.endToEnd = ConstraintLayout.LayoutParams.UNSET
            clusterLp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            clusterLp.marginStart = marginPx
            clusterLp.marginEnd = 0
        }
        clusterLp.bottomMargin = UiStyle.dp(this@MainActivity, controlsBottomMargin) // réglable (Paramètres)
        controlsCluster.layoutParams = clusterLp
        // aligne les enfants (mini-rangée, onglets, raccourcis) sur le
        // même bord que le cluster — sinon la rangée de raccourcis (plus
        // large que les icônes) se retrouve centrée au lieu de rester collée
        controlsCluster.gravity = if (controlsOnRight) Gravity.END else Gravity.START
        controlsCluster.requestLayout()
        // pauseBtn est fixe en haut à côté du hamburger (activity_main.xml,
        // 2026-09-03) — ne suit plus la bascule, plus rien à faire ici.
        // panneau à l'OPPOSÉ de la barre (miroir), ancré EN BAS — hauteur
        // adaptée au contenu (max 85 % de l'écran via le flipper).
        // panelBottomMargin est un réglage indépendant (Paramètres), plus lié
        // au bouton play depuis que celui-ci est passé en haut (2026-09-03).
        val menuSide = if (controlsOnRight) Gravity.LEFT else Gravity.RIGHT
        val panelLp = FrameLayout.LayoutParams(
            panW.coerceAtLeast(1), panH.coerceAtLeast(1)
        ).apply {
            gravity = Gravity.BOTTOM or menuSide
            leftMargin = if (controlsOnRight) UiStyle.dp(this@MainActivity, 10f) else 0
            rightMargin = if (controlsOnRight) 0 else UiStyle.dp(this@MainActivity, 10f)
            bottomMargin = UiStyle.dp(this@MainActivity, panelBottomMargin) // indépendant du cluster (Paramètres)
        }
        panelMenu.layoutParams = panelLp
        panelMenu.requestLayout()
        positionWheelPanel()

        // cacher le sous-menu si on change de côté (sera repositionné au prochain affichage)
        submenuObstacle.visibility = View.GONE

        controlsCluster.post {
            val lp = controlsCluster.layoutParams as ConstraintLayout.LayoutParams
            UsageLog.d("cluster pos x=${controlsCluster.x.toInt()} y=${controlsCluster.y.toInt()} w=${controlsCluster.width} h=${controlsCluster.height} startS=${lp.startToStart} endE=${lp.endToEnd} S=${lp.marginStart} E=${lp.marginEnd}")
        }
        panelMenu.post {
            val lp = panelMenu.layoutParams as FrameLayout.LayoutParams
            UsageLog.d("panel pos x=${panelMenu.x.toInt()} w=${panelMenu.width} g=${lp.gravity} L=${lp.leftMargin} R=${lp.rightMargin}")
        }
    }

    /** Roue de couleur : son propre panneau, positionné juste AU-DESSUS de
     *  la barre de raccourcis (2026-08-20, demande explicite : "la couleure
     *  selectionnée peu appzraitre au dessu du menu" — la barre est ancrée
     *  près du bord bas de l'écran, donc peu de place en dessous ; au-dessus
     *  il y a toute la hauteur du canevas disponible). Calcul en direct sur
     *  la position réelle de tabbarView (même principe que
     *  ouvrirSousMenuPres pour les sous-menus obstacle/effets/gomme/balle),
     *  aligné sur le même bord que la barre. Grandit vers le HAUT (gravity
     *  BOTTOM) quand la taille change, ne redescend jamais sous la barre. */
    private fun positionWheelPanel() {
        if (!::wheelPanel.isInitialized || !::tabbarView.isInitialized) return
        // position libre choisie par glissé (2026-08-20, demande explicite :
        // "je veux aussi pouvoir deplacer cette roue ou je veux") — prime
        // sur le calcul par défaut dès qu'elle a été déplacée une fois.
        if (wheelPosX >= 0f && wheelPosY >= 0f) {
            val density = resources.displayMetrics.density
            wheelPanel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (wheelPosX * density).toInt()
                topMargin = (wheelPosY * density).toInt()
            }
            wheelPanel.requestLayout()
            return
        }
        tabbarView.post {
            val barLoc = IntArray(2)
            tabbarView.getLocationOnScreen(barLoc)
            val rootLoc = IntArray(2)
            rootLayout.getLocationOnScreen(rootLoc)
            val barLeft = barLoc[0] - rootLoc[0]
            val barRight = barLeft + tabbarView.width
            val barTop = barLoc[1] - rootLoc[1]
            wheelPanel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or (if (controlsOnRight) Gravity.END else Gravity.START)
                bottomMargin = (rootLayout.height - barTop) + UiStyle.dp(this@MainActivity, 6f)
                if (controlsOnRight) {
                    rightMargin = rootLayout.width - barRight
                } else {
                    leftMargin = barLeft
                }
            }
            wheelPanel.requestLayout()
        }
    }

    /** Masque/réaffiche onglets + raccourcis — la mini-rangée (bascule côté +
     *  cet œil) reste toujours accessible, tout comme pause/lecture et le
     *  menu hamburger (2026-08-13, demandes explicites successives : "je veux
     *  le bouton pause même quand on cache l'interface", puis "j'aimerais
     *  aussi avoir le menu hamburger quand l'interface est cachée" — pouvoir
     *  agir sans devoir tout réafficher). */
    private fun toggleHideUi() {
        uiHidden = !uiHidden
        val vis = if (uiHidden) View.GONE else View.VISIBLE
        // tout disparaît : barre, raccourcis, ⇄, undo/redo, numéro de patch
        tabbarView.visibility = vis
        controlsCluster.visibility = vis
        // undo/redo désactivables (2026-08-20, demande explicite) — ne pas
        // les réafficher au retour si l'utilisateur les a désactivés
        btnUndo.visibility = if (uiHidden || "undo" in disabledTools) View.GONE else View.VISIBLE
        btnRedo.visibility = if (uiHidden || "redo" in disabledTools) View.GONE else View.VISIBLE
        versionLabel.visibility = vis
        btnHideUi.setImageResource(if (uiHidden) R.drawable.ic_eye_off else R.drawable.ic_eye)
        if (uiHidden) closeMenus()
    }

    /** Glisser gauche/droite sur une icône = régler une valeur en direct
     *  (bille, épaisseur du trait) sans ouvrir de panneau. Le tap normal de
     *  l'icône (s'il existe) reste fonctionnel : en dessous du seuil de
     *  déplacement, l'événement est laissé remonter comme un clic classique. */
    @Suppress("ClickableViewAccessibility")
    private fun attachDragResize(
        icon: ImageView,
        label: String,
        minVal: Float,
        maxVal: Float,
        get: () -> Float,
        set: (Float) -> Unit,
        apercu: () -> Float, // taille d'aperçu en dp (largeur du trait / diamètre de la boule)
        // appui long optionnel (2026-08-20, demande explicite pour btnBalle :
        // "le menu avec le choix des billes sur un tap plutot que en
        // lassant appuyer" — tap = performClick (comportement normal du
        // bouton, inchangé), appui long = cette action-ci, en plus du
        // glissé qui redimensionne toujours). null = comportement d'origine
        // (aucun appui long géré), pour ne rien changer aux autres appels.
        onLongPress: (() -> Unit)? = null,
    ) {
        val density = resources.displayMetrics.density
        val dragSpanDp = 160f // glisser sur ~160dp parcourt toute la plage
        val slopPx = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val handler = Handler(Looper.getMainLooper())
        var startRawY = 0f
        var startVal = 0f
        var dragging = false
        var longPressFired = false
        var longPressRunnable: Runnable? = null
        icon.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // on capture dès le départ : le glisser peut sortir de l'icône
                    startRawY = event.rawY
                    startVal = get()
                    dragging = false
                    longPressFired = false
                    if (onLongPress != null) {
                        val r = Runnable { longPressFired = true; onLongPress() }
                        longPressRunnable = r
                        handler.postDelayed(r, longPressMs)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dyPx = event.rawY - startRawY
                    if (!dragging && abs(dyPx) > slopPx) {
                        dragging = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        showDragBadge("$label ${formatValue(startVal)}", (apercu() * density).toInt())
                    }
                    if (dragging) {
                        val deltaDp = dyPx / density
                        // glisser vers le HAUT (dyPx négatif) = agrandir, vers le BAS = réduire
                        val newVal = (startVal - deltaDp / dragSpanDp * (maxVal - minVal)).coerceIn(minVal, maxVal)
                        set(newVal)
                        showDragBadge("$label ${formatValue(newVal)}", (apercu() * density).toInt())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (dragging) {
                        saveSettings()
                        UsageLog.d("$label (glissé) = ${formatValue(get())}")
                        hideDragBadge()
                    } else if (!longPressFired && event.actionMasked == MotionEvent.ACTION_UP) {
                        // tap sans déplacement ni appui long : comportement normal du bouton
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Badge de glissé : cercle d'aperçu à la TAILLE RÉELLE (largeur du trait
     *  ou diamètre de la boule) + la valeur — à côté de la barre, centré. */
    private fun showDragBadge(label: String, apercuPx: Int) {
        val badge = dragBadge ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = UiStyle.dp(this@MainActivity, 10f)
            setPadding(pad, pad, pad, pad)
            background = getDrawable(R.drawable.bg_palette)
            elevation = UiStyle.dp(this@MainActivity, 10f).toFloat()
            dragBadgeCircle = View(this@MainActivity).apply {
                background = getDrawable(R.drawable.bg_swatch)
                backgroundTintList = ColorStateList.valueOf(0xFFB0B0B0.toInt())
            }
            dragBadgeText = TextView(this@MainActivity).apply {
                UiStyle.title(this)
                setPadding(UiStyle.dp(this@MainActivity, 10f), 0, 0, 0)
            }
            addView(dragBadgeCircle)
            addView(dragBadgeText)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            // collé à côté de la barre (côté intérieur), centré verticalement
            lp.gravity = Gravity.CENTER_VERTICAL or (if (controlsOnRight) Gravity.END else Gravity.START)
            val sidePad = UiStyle.dp(this@MainActivity, 74f) // barre ~56dp + marge
            lp.marginEnd = if (controlsOnRight) sidePad else 0
            lp.marginStart = if (controlsOnRight) 0 else sidePad
            rootLayout.addView(this, lp)
            dragBadge = this
        }
        val size = apercuPx.coerceAtLeast(UiStyle.dp(this@MainActivity, 8f))
        dragBadgeCircle?.layoutParams = LinearLayout.LayoutParams(size, size)
        dragBadgeText?.text = label
        badge.invalidate()
    }

    private fun hideDragBadge() {
        dragBadge?.let { rootLayout.removeView(it) }
        dragBadge = null
    }

    /** Badge flottant qui suit le doigt pendant le survol d'un sous-menu
     *  (2026-08-10, demande explicite — cf. commentaire de `hoverBadge`) :
     *  montre le MÊME icône que le bouton actuellement survolé, décalé
     *  au-dessus du point de contact pour ne jamais être caché par le
     *  doigt, avec son nom au-dessus (2026-08-13, demande explicite).
     *  `screenX`/`screenY` = coordonnées ÉCRAN (event.rawX/rawY). */
    private fun showHoverBadge(icon: Drawable?, screenX: Float, screenY: Float, label: String? = null) {
        val badge = hoverBadge ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = getDrawable(R.drawable.bg_palette)
            val pad = UiStyle.dp(this@MainActivity, 10f)
            setPadding(pad, pad, pad, pad)
            elevation = UiStyle.dp(this@MainActivity, 12f).toFloat()
            hoverBadgeLabel = TextView(this@MainActivity).apply {
                UiStyle.body(this)
                textSize = 12f
                setPadding(0, 0, 0, UiStyle.dp(this@MainActivity, 4f))
            }
            addView(hoverBadgeLabel)
            hoverBadgeIcon = ImageView(this@MainActivity).apply {
                val size = UiStyle.dp(this@MainActivity, 44f)
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            addView(hoverBadgeIcon)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.TOP or Gravity.START
            rootLayout.addView(this, lp)
            hoverBadge = this
        }
        hoverBadgeIcon?.setImageDrawable(icon)
        hoverBadgeLabel?.text = label ?: ""
        badge.visibility = View.VISIBLE
        // mesure à la volée : la largeur varie selon la longueur du nom
        badge.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = badge.measuredWidth
        val h = badge.measuredHeight
        val rootLoc = IntArray(2)
        rootLayout.getLocationOnScreen(rootLoc)
        // Décalage vers le haut assez large pour sortir de sous le doigt
        // (empiriquement plus que la hauteur d'un doigt posé + le badge
        // lui-même) — centré horizontalement sur le point de contact.
        val aboveFinger = UiStyle.dp(this@MainActivity, 90f)
        val lp = badge.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (screenX - rootLoc[0] - w / 2f).toInt()
        lp.topMargin = (screenY - rootLoc[1] - aboveFinger - h / 2f).toInt()
        badge.layoutParams = lp
    }

    private fun hideHoverBadge() {
        hoverBadge?.let { rootLayout.removeView(it) }
        hoverBadge = null
        hoverBadgeIcon = null
        hoverBadgeLabel = null
    }

    // ---------------------------------------------------------------- system

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }
}
