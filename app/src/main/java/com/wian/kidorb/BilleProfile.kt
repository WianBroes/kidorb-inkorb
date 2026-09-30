package com.wian.kidorb

/** Un « type de bille » nommé : paquet de réglages physiques/comportementaux
 *  (2026-08-20, demande explicite : "je veux pouvoir avoir tout les reglages
 *  actuels mais par billes" — but à terme remplacer le réglage au slider par
 *  le choix d'une bille toute faite, cf. onglet Bille Setup). Valeurs par
 *  défaut alignées sur les constantes DEF_* de BallCanvasView.
 *  2026-08-31, aller-retour dans la même session : "on va deconnecter les
 *  melanges de couleurs et les billes... on commence a se perdre" a
 *  temporairement retiré fonduRate/mixVividMode/trailEdgeMode/cometTrail/
 *  rainbowMode/speedColorMode d'ici (réglages globaux le temps de fiabiliser
 *  le moteur — "Net + doux" confirmé en usage réel avec la Protection
 *  anti-retour). Une fois le moteur jugé fiable : "on peut reconnecter ça
 *  aux billes proprement" — de retour ci-dessous, UN SEUL endroit pour
 *  chacun (ici, plus de doublon global). Seule `melangeExperiment`
 *  (Protection anti-retour, jamais une propriété de bille — algorithme
 *  d'échantillonnage, pas une identité de bille) reste globale, cf.
 *  BallCanvasView/buildParametresModeKid. */
data class BilleProfile(
    var nom: String,
    var couleurBille: Int = 0xFF202124.toInt(),
    var ballRadiusDp: Float = 34f,
    var restitution: Float = 0.85f,
    var poids: Float = 1f,
    var frictionRate: Float = 0.6f,
    var tiltEffect: Float = 1f,
    var textureAmount: Float = 0f,
    // 2026-08-21, demande explicite : "le mode de fusion des couleurs doit
    // etre une option des billes... et avoir accès aux valeurs [de
    // l']élément modifiable" — vitesse de fondu du mélange pigmentaire au
    // contact, valeur brute réglable (0.05 = très fondu/smeary, 1 = net/
    // quasi instantané), plus les 3 préréglages existants comme raccourcis.
    var fonduRate: Float = 0.4f,
    var shakeThreshold: Float = 3.5f,
    var shakeToVel: Float = 800f,
    var vitesseEpaisseur: Boolean = false,
    var boundsActive: Boolean = true,
    var wrapActive: Boolean = false,
    var ballVisible: Boolean = true,
    // 2026-08-20, demande explicite : "je veux que ca sois un paramettre
    // des differentes billez comme ca on sait quand on la choisi quelle
    // agira comme ca" — trait de comportement au toucher, pas un réglage
    // global : chaque bille sait si elle réagit au contact du doigt en
    // pinceau libre (pousse/éjecte) ou l'ignore.
    var ballGrabInPinceau: Boolean = false,
    // 2026-08-22, demande explicite : "augmenter les effets de
    // l'inclinaison... plus ça s'incline plus la bille accélère" — courbe
    // au carré au lieu de la réponse linéaire actuelle.
    var tiltCurveMode: Boolean = false,
    // 2026-08-25, demande explicite : "des pastilles avec des icônes qui
    // représentent plus les billes que j'ai créées" — icône d'identité
    // choisie dans l'éditeur (cf. buildBilleProfileEditor), affichée à la
    // place du disque générique teinté dans les sélecteurs (buildBilleCell).
    // null = pas de concept choisi, comportement inchangé (disque teinté).
    var icone: String? = null,
    // 2026-08-25, demande explicite : "est-ce que tu pourrais utiliser ça
    // [le dégradé de volume des icônes] pour la bille dans l'app ? ajoute un
    // toggle avec la possibilité de désactiver ça" — dégradé radial clair→
    // sombre sur le disque de la bille (cf. BallCanvasView.volumeMode), au
    // lieu du disque plat + point de brillance fixe par défaut.
    var volumeMode: Boolean = false,
    // 2026-08-31, demande explicite : "on peut reconnecter ça aux billes
    // proprement" — vivacité du mélange pigmentaire (cf. PigmentMix.mix) :
    // 0 = Kubelka-Munk réaliste (défaut), 1 = chroma repoussée, 2 = fondu
    // de teinte HSV.
    var mixVividMode: Int = 0,
    // Bord du trait (cf. BallCanvasView.stampBallTrail) : 0 = dur (défaut),
    // 1 = doux (adoucit aussi le contour extérieur), 2 = net + doux
    // (contour net, transitions de couleur lissées par EMA entre tampons —
    // confirmé en usage réel le 2026-08-31 combiné à la Protection
    // anti-retour : "c'est bcp mieux comme ça").
    var trailEdgeMode: Int = 0,
    // 2026-08-22, IDEAS.md "diversité des billes" — effet comète (trait qui
    // rétrécit fortement avec la vitesse) et bille arc-en-ciel (couleur qui
    // boucle dans le temps au lieu de rester fixe sur couleurBille).
    var cometTrail: Boolean = false,
    var rainbowMode: Boolean = false,
    // 2026-08-22, demande explicite : "un toggle pour que la vitesse influe
    // la couleur... vers le bleu la lenteur et vers le jaune rouge la
    // vitesse, comme la rentrée dans l'atmosphère".
    var speedColorMode: Boolean = false,
    // 2026-09-01, demande explicite : "toutes les pastilles ont pas la meme
    // taille... peut etre qu'il faut un slider pour regler la taille de
    // l'image de la bille dans la pastille" — indépendant du rayon physique
    // réel (ballRadiusDp, qui régit la bille en jeu, pas sa vignette dans
    // le sélecteur). 1 = taille de référence (cf. buildBilleCell), <1
    // réduit la vignette, >1 l'agrandit — jamais couplé à ballRadiusDp.
    var iconScale: Float = 1f,
    // 2026-09-02, demande explicite : "+" dans le menu de raccourcis (création
    // rapide, cf. MainActivity.showBilleQuickEditor) + mode gigote pour
    // supprimer — les 6 billes d'usine (defaultBilleProfiles) restent
    // protégées de cette suppression rapide, tout le reste (créé via ce + ou
    // via l'éditeur avancé de l'onglet Billes) reste supprimable comme avant.
    var isDefault: Boolean = false,
)
