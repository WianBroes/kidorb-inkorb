package com.wian.kidorb

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Mélange de pigments réaliste — portage de Spectral.js (MIT).
 *
 * Copyright (c) 2025 Ronald van Wijnen — https://github.com/rvanwijnen/spectral.js
 * Licence MIT : https://raw.githubusercontent.com/rvanwijnen/spectral.js/master/LICENSE
 *
 * Principe (théorie de Kubelka-Munk à constante unique, 1931, domaine public) :
 *  1. chaque couleur sRGB est convertie en un spectre de réflectance de 38 bandes,
 *     combinaison pondérée de 7 pigments de base (blanc, cyan, magenta, jaune,
 *     rouge, vert, bleu) ;
 *  2. le mélange se fait dans l'espace K/S (absorption/diffusion), pondéré par une
 *     « concentration effective » = facteur² × force de teinte² × luminance ;
 *  3. le spectre résultant est reconverti en sRGB via les fonctions de correspondance
 *     CIE (illuminant D65), l'espace OKLab pour un gamut mapping perceptuel.
 *
 * Résultat : bleu+jaune → vert, jaune+rouge → orange, rouge+bleu → violet sombre —
 * le croisement de teinte réel des pigments, impossible en RGB.
 */
object PigmentMix {

    private const val SIZE = 38
    private const val GAMMA = 2.4
    private const val EPS = 2.220446049250313e-16 // Number.EPSILON (JS)

    // ---------------------------------------------------------------- spectres de base

    /** 7 pigments de base : réflectance sur 38 bandes (~380-750 nm). */
    private val BASE_SPECTRA: Map<Char, DoubleArray> = mapOf(
        'W' to doubleArrayOf(
            1.00116072718764, 1.00116065159728, 1.00116031922747, 1.00115867270789, 1.00115259844552, 1.00113252528998, 1.00108500663327, 1.00099687889453, 1.00086525152274,
            1.0006962900094, 1.00050496114888, 1.00030808187992, 1.00011966602013, 0.999952765968407, 0.999821836899297, 0.999738609557593, 0.999709551639612, 0.999731930210627,
            0.999799436346195, 0.999900330316671, 1.00002040652611, 1.00014478793658, 1.00025997903412, 1.00035579697089, 1.00042753780269, 1.00047623344888, 1.00050720967508,
            1.00052519156373, 1.00053509606896, 1.00054022097482, 1.00054272816784, 1.00054389569087, 1.00054448212151, 1.00054476959992, 1.00054489887762, 1.00054496254689,
            1.00054498927058, 1.000544996993
        ),
        'C' to doubleArrayOf(
            0.970585001322962, 0.970592498143425, 0.970625348729891, 0.970786806119017, 0.971368673228248, 0.973163230621252, 0.976740223158765, 0.981587605491377, 0.986280265652949,
            0.989949147689134, 0.99249270153842, 0.994145680405256, 0.995183975033212, 0.995756750110818, 0.99591281828671, 0.995606157834528, 0.994597600961854, 0.99221571549237,
            0.986236452783249, 0.967943337264541, 0.891285004244943, 0.536202477862053, 0.154108119001878, 0.0574575093228929, 0.0315349873107007, 0.0222633920086335, 0.0182022841492439,
            0.016299055973264, 0.0153656239334613, 0.0149111568733976, 0.0146954339898235, 0.0145964146717719, 0.0145470156699655, 0.0145228771899495, 0.0145120341118965,
            0.0145066940939832, 0.0145044507314479, 0.0145038009464639
        ),
        'M' to doubleArrayOf(
            0.990673557319988, 0.990671524961979, 0.990662582353421, 0.990618107644795, 0.99045148087871, 0.989871081400204, 0.98828660875964, 0.984290692797504, 0.973934905625306,
            0.941817838460145, 0.817390326195156, 0.432472805065729, 0.13845397825887, 0.0537347216940033, 0.0292174996673231, 0.021313651750859, 0.0201349530181136, 0.0241323096280662,
            0.0372236145223627, 0.0760506552706601, 0.205375471942399, 0.541268903460439, 0.815841685086486, 0.912817704123976, 0.946339830166962, 0.959927696331991, 0.966260595230312,
            0.969325970058424, 0.970854536721399, 0.971605066528128, 0.971962769757392, 0.972127272274509, 0.972209417745812, 0.972249577678424, 0.972267621998742, 0.97227650946215,
            0.972280243306874, 0.97228132482656
        ),
        'Y' to doubleArrayOf(
            0.0210523371789306, 0.0210564627517414, 0.0210746178695038, 0.0211649058448753, 0.0215027957272504, 0.0226738799041561, 0.0258235649693629, 0.0334879385639851,
            0.0519069663740307, 0.100749014833473, 0.239129899706847, 0.534804312272748, 0.79780757864303, 0.911449894067384, 0.953797963004507, 0.971241615465429, 0.979303123807588,
            0.983380119507575, 0.985461246567755, 0.986435046976605, 0.986738250670141, 0.986617882445032, 0.986277776758643, 0.985860592444056, 0.98547492767621, 0.985176934765558,
            0.984971574014181, 0.984846303415712, 0.984775351811199, 0.984738066625265, 0.984719648311765, 0.984711023391939, 0.984706683300676, 0.984704554393091, 0.98470359630937,
            0.984703124077552, 0.98470292561509, 0.984702868122795
        ),
        'R' to doubleArrayOf(
            0.0315605737777207, 0.0315520718330149, 0.0315148215513658, 0.0313318044982702, 0.0306729857725527, 0.0286480476989607, 0.0246450407045709, 0.0192960753663651,
            0.0142066612220556, 0.0102942608878609, 0.0076191460521811, 0.005898041083542, 0.0048233247781713, 0.0042298748350633, 0.0040599171299341, 0.0043533695594676,
            0.0053434425970201, 0.0076917201010463, 0.0135969795736536, 0.0316975442661115, 0.107861196355249, 0.463812603168704, 0.847055405272011, 0.943185409393918, 0.968862150696558,
            0.978030667473603, 0.982043643854306, 0.983923623718707, 0.984845484154382, 0.985294275814596, 0.985507295219825, 0.985605071539837, 0.985653849933578, 0.985677685033883,
            0.985688391806122, 0.985693664690031, 0.985695879848205, 0.985696521463762
        ),
        'G' to doubleArrayOf(
            0.0095560747554212, 0.0095581580120851, 0.0095673245444588, 0.0096129126297349, 0.0097837090401843, 0.010378622705871, 0.0120026452378567, 0.0160977721473922,
            0.026706190223168, 0.0595555440185881, 0.186039826532826, 0.570579820116159, 0.861467768400292, 0.945879089767658, 0.970465486474305, 0.97841363028445, 0.979589031411224,
            0.975533536908632, 0.962288755397813, 0.92312157451312, 0.793434018943111, 0.459270135902429, 0.185574103666303, 0.0881774959955372, 0.05436302287667, 0.0406288447060719,
            0.034221520431697, 0.0311185790956966, 0.0295708898336134, 0.0288108739348928, 0.0284486271324597, 0.0282820301724731, 0.0281988376490237, 0.0281581655342037,
            0.0281398910216386, 0.0281308901665811, 0.0281271086805816, 0.0281260133612096
        ),
        'B' to doubleArrayOf(
            0.979404752502014, 0.97940070684313, 0.979382903470261, 0.979294364945594, 0.97896301460857, 0.977814466694043, 0.974724321133836, 0.967198482343973, 0.949079657530575,
            0.900850128940977, 0.76315044546224, 0.465922171649319, 0.201263280451005, 0.0877524413419623, 0.0457176793291679, 0.0284706050521843, 0.020527176756985, 0.0165302792310211,
            0.0145135107212858, 0.0136003508637687, 0.0133604258769571, 0.013548894314568, 0.0139594356366992, 0.014443425575357, 0.0148854440621406, 0.0152254296999746,
            0.0154592848180209, 0.0156018026485961, 0.0156824871281936, 0.0157248764360615, 0.0157458108784121, 0.0157556123350225, 0.0157605443964911, 0.0157629637515278,
            0.0157640525629106, 0.015764589232951, 0.0157648147772649, 0.0157648801149616
        )
    )

    /** Fonctions de correspondance CIE pondérées par l'illuminant D65 (3×38). */
    private val CMF = arrayOf(
        doubleArrayOf(
            0.0000646919989576, 0.0002194098998132, 0.0011205743509343, 0.0037666134117111, 0.011880553603799, 0.0232864424191771, 0.0345594181969747, 0.0372237901162006,
            0.0324183761091486, 0.021233205609381, 0.0104909907685421, 0.0032958375797931, 0.0005070351633801, 0.0009486742057141, 0.0062737180998318, 0.0168646241897775,
            0.028689649025981, 0.0426748124691731, 0.0562547481311377, 0.0694703972677158, 0.0830531516998291, 0.0861260963002257, 0.0904661376847769, 0.0850038650591277,
            0.0709066691074488, 0.0506288916373645, 0.035473961885264, 0.0214682102597065, 0.0125164567619117, 0.0068045816390165, 0.0034645657946526, 0.0014976097506959,
            0.000769700480928, 0.0004073680581315, 0.0001690104031614, 0.0000952245150365, 0.0000490309872958, 0.0000199961492222
        ),
        doubleArrayOf(
            0.000001844289444, 0.0000062053235865, 0.0000310096046799, 0.0001047483849269, 0.0003536405299538, 0.0009514714056444, 0.0022822631748318, 0.004207329043473,
            0.0066887983719014, 0.0098883960193565, 0.0152494514496311, 0.0214183109449723, 0.0334229301575068, 0.0513100134918512, 0.070402083939949, 0.0878387072603517,
            0.0942490536184085, 0.0979566702718931, 0.0941521856862608, 0.0867810237486753, 0.0788565338632013, 0.0635267026203555, 0.05374141675682, 0.042646064357412,
            0.0316173492792708, 0.020885205921391, 0.0138601101360152, 0.0081026402038399, 0.004630102258803, 0.0024913800051319, 0.0012593033677378, 0.000541646522168,
            0.0002779528920067, 0.0001471080673854, 0.0000610327472927, 0.0000343873229523, 0.0000177059860053, 0.000007220974913
        ),
        doubleArrayOf(
            0.000305017147638, 0.0010368066663574, 0.0053131363323992, 0.0179543925899536, 0.0570775815345485, 0.113651618936287, 0.17335872618355, 0.196206575558657,
            0.186082370706296, 0.139950475383207, 0.0891745294268649, 0.0478962113517075, 0.0281456253957952, 0.0161376622950514, 0.0077591019215214, 0.0042961483736618,
            0.0020055092122156, 0.0008614711098802, 0.0003690387177652, 0.0001914287288574, 0.0001495555858975, 0.0000923109285104, 0.0000681349182337, 0.0000288263655696,
            0.0000157671820553, 0.0000039406041027, 0.000001584012587, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        )
    )

    // ---------------------------------------------------------------- matrices de conversion

    private val RGB_XYZ = arrayOf(
        doubleArrayOf(0.41239079926595934, 0.357584339383878, 0.1804807884018343),
        doubleArrayOf(0.21263900587151027, 0.715168678767756, 0.07219231536073371),
        doubleArrayOf(0.01933081871559182, 0.11919477979462598, 0.9505321522496607)
    )
    private val XYZ_RGB = arrayOf(
        doubleArrayOf(3.2409699419045226, -1.537383177570094, -0.4986107602930034),
        doubleArrayOf(-0.9692436362808796, 1.8759675015077202, 0.04155505740717559),
        doubleArrayOf(0.05563007969699366, -0.20397695888897652, 1.0569715142428786)
    )
    private val XYZ_LMS = arrayOf(
        doubleArrayOf(0.819022437996703, 0.3619062600528904, -0.1288737815209879),
        doubleArrayOf(0.0329836539323885, 0.9292868615863434, 0.0361446663506424),
        doubleArrayOf(0.0481771893596242, 0.2642395317527308, 0.6335478284694309)
    )
    private val LMS_XYZ = arrayOf(
        doubleArrayOf(1.2268798758459243, -0.5578149944602171, 0.2813910456659647),
        doubleArrayOf(-0.0405757452148008, 1.112286803280317, -0.0717110580655164),
        doubleArrayOf(-0.0763729366746601, -0.4214933324022432, 1.5869240198367816)
    )
    private val LMS_LAB = arrayOf(
        doubleArrayOf(0.210454268309314, 0.7936177747023054, -0.0040720430116193),
        doubleArrayOf(1.9779985324311684, -2.4285922420485799, 0.450593709617411),
        doubleArrayOf(0.0259040424655478, 0.7827717124575296, -0.8086757549230774)
    )
    private val LAB_LMS = arrayOf(
        doubleArrayOf(1.0, 0.3963377773761749, 0.2158037573099136),
        doubleArrayOf(1.0, -0.1055613458156586, -0.0638541728258133),
        doubleArrayOf(1.0, -0.0894841775298119, -1.2914855480194092)
    )

    // ---------------------------------------------------------------- API publique

    // 2026-08-25, optimisation (Galaxy A13, ça ramait) : mémoïsation sRGB →
    // spectre — mais le fondu pigmentaire (carriedColor évoluant en continu
    // vers la couleur touchée, cf. BallCanvasView.lerpColor) génère une
    // teinte ARGB quasi différente à chaque frame pendant tout le contact :
    // sans limite, ce cache grossit sans jamais réutiliser ses entrées lors
    // d'une longue session de dessin (fuite mémoire lente + coût de hachage
    // croissant). Vidé au-delà d'un seuil large — perd juste quelques
    // recalculs, jamais de correction (fonction pure).
    private const val CACHE_MAX = 4096
    private val cacheR = HashMap<Int, DoubleArray>() // sRGB → spectre (mémoïsé)

    // 2026-08-30, demande explicite : "les couleurs on de nouveau des
    // tendances a faire des melanges moche a tendance brun, caca d'oie" —
    // Kubelka-Munk est réaliste (les peintures réelles font pareil en
    // mélangeant des teintes complémentaires), mais pas toujours ce que
    // Wian veut visuellement. 2 pistes toggle-ables en plus du réalisme
    // actuel (0 = inchangé) : 1 = même mélange K-M, chroma OKLCh repoussée
    // après coup (compense le ternissement sans changer la teinte/
    // luminosité calculées) ; 2 = abandonne K-M, simple fondu de teinte HSV
    // par le chemin le plus court (jamais de brun, mais perd le
    // comportement "peinture réelle").
    private const val VIVID_CHROMA_BOOST = 1.35
    // 2026-08-30, retour direct après test réel : la chroma renforcée ne
    // faisait "aucune différence" sur les mélanges les plus brun/ternes —
    // un ×1.35 sur une chroma déjà quasi nulle (exactement le cas qu'on veut
    // corriger) reste quasi nulle. Plancher ADDITIF en plus du facteur
    // multiplicatif : relève vraiment les mélanges ternes, sans changer
    // grand-chose à ceux déjà vifs (où le ×1.35 seul suffisait déjà).
    private const val VIVID_CHROMA_FLOOR_BOOST = 0.04
    // Sous ce seuil de saturation HSV, la teinte devient numériquement
    // instable/quasi arbitraire (gris/noir/blanc) — cf. hueBlend.
    private const val HUE_UNSTABLE_SATURATION = 0.08

    /** Mélange pigmentaire de deux couleurs (ARGB) — t = 0 → a, t = 1 → b.
     *  [vividMode] : 0 = Kubelka-Munk réaliste (défaut, inchangé), 1 = K-M +
     *  chroma repoussée, 2 = fondu de teinte HSV (pas de K-M). */
    fun mix(a: Int, b: Int, t: Double, vividMode: Int = 0): Int {
        if (t <= 0.0) return a
        if (t >= 1.0) return b
        if (vividMode == 2) return hueBlend(a, b, t)
        val ra = spectrumOf(a)
        val rb = spectrumOf(b)
        val fa = 1.0 - t
        val fb = t
        val la = luminanceOf(ra)
        val lb = luminanceOf(rb)
        val mixed = DoubleArray(SIZE)
        for (i in 0 until SIZE) {
            var ksMix = 0.0
            // couleur a
            var concentration = fa * fa * la
            ksMix += ksOf(ra[i]) * concentration
            // couleur b
            concentration = fb * fb * lb
            ksMix += ksOf(rb[i]) * concentration
            mixed[i] = kmOf(ksMix / (fa * fa * la + fb * fb * lb))
        }
        val result = toArgb(mixed)
        return if (vividMode == 1) boostChroma(result, VIVID_CHROMA_BOOST) else result
    }

    /** Repousse la chroma OKLCh d'une couleur ARGB déjà calculée, teinte et
     *  luminosité inchangées — `gamutMap` ramène seul à l'intérieur du sRGB
     *  si le résultat dépasse le gamut. Facteur multiplicatif + plancher
     *  additif (cf. VIVID_CHROMA_FLOOR_BOOST) : un ×facteur seul ne relève
     *  quasiment rien quand la chroma de départ est déjà proche de 0 —
     *  exactement le cas terne/brun que ce mode doit corriger. */
    private fun boostChroma(argb: Int, factor: Double): Int {
        val r = uncompand(((argb shr 16) and 0xFF) / 255.0)
        val g = uncompand(((argb shr 8) and 0xFF) / 255.0)
        val b = uncompand((argb and 0xFF) / 255.0)
        val oklab = lrgbToOklab(doubleArrayOf(r, g, b))
        val c = kotlin.math.sqrt(oklab[1] * oklab[1] + oklab[2] * oklab[2])
        val h = atan2(oklab[2], oklab[1]) * 180.0 / Math.PI
        val hue = if (h >= 0.0) h else h + 360.0
        val boostedC = c * factor + VIVID_CHROMA_FLOOR_BOOST
        val xyz = oklabToXyz(oklchToOklab(oklab[0], boostedC, hue))
        return lrgbToArgb(gamutMap(mulMatVec(XYZ_RGB, xyz), xyz))
    }

    /** Fondu de teinte HSV pur (chemin le plus court sur la roue), sans
     *  mélange pigmentaire — saturation/valeur interpolées linéairement
     *  (jamais de zone terne/brune, contrairement à K-M ou à un fondu RGB
     *  direct).
     *  2026-08-30, retour direct après test réel : "transition nette" au
     *  lieu d'un fondu — sous HUE_UNSTABLE_SATURATION, la teinte HSV est
     *  numériquement quasi arbitraire (gris/noir/blanc n'ont pas de vraie
     *  teinte) ; comme cette fonction est rappelée à CHAQUE FRAME avec la
     *  couleur portée courante (qui peut traverser une zone peu saturée en
     *  cours de fondu), une teinte instable/bruitée fait sauter le chemin le
     *  plus court d'une frame à l'autre — hérite la teinte de l'AUTRE
     *  couleur quand une des deux est trop peu saturée pour que la sienne
     *  ait un sens, au lieu de faire confiance à une valeur non fiable. */
    private fun hueBlend(a: Int, b: Int, t: Double): Int {
        val hsvA = rgbToHsv((a shr 16) and 0xFF, (a shr 8) and 0xFF, a and 0xFF)
        val hsvB = rgbToHsv((b shr 16) and 0xFF, (b shr 8) and 0xFF, b and 0xFF)
        val ha = if (hsvA[1] < HUE_UNSTABLE_SATURATION) hsvB[0] else hsvA[0]
        val hb = if (hsvB[1] < HUE_UNSTABLE_SATURATION) hsvA[0] else hsvB[0]
        var dh = hb - ha
        if (dh > 180.0) dh -= 360.0
        if (dh < -180.0) dh += 360.0
        val h = (ha + dh * t + 360.0) % 360.0
        val s = hsvA[1] + (hsvB[1] - hsvA[1]) * t
        val v = hsvA[2] + (hsvB[2] - hsvA[2]) * t
        return hsvToRgb(h, s, v)
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int): DoubleArray {
        val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val delta = max - min
        val v = max
        val s = if (max <= 0.0) 0.0 else delta / max
        var h = 0.0
        if (delta > 0.0) {
            h = when (max) {
                rf -> 60.0 * (((gf - bf) / delta).mod(6.0))
                gf -> 60.0 * (((bf - rf) / delta) + 2.0)
                else -> 60.0 * (((rf - gf) / delta) + 4.0)
            }
        }
        return doubleArrayOf(h, s, v)
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val hh = h / 60.0
        val x = c * (1.0 - kotlin.math.abs(hh.mod(2.0) - 1.0))
        val (r1, g1, b1) = when {
            hh < 1.0 -> Triple(c, x, 0.0)
            hh < 2.0 -> Triple(x, c, 0.0)
            hh < 3.0 -> Triple(0.0, c, x)
            hh < 4.0 -> Triple(0.0, x, c)
            hh < 5.0 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = v - c
        val r = ((r1 + m) * 255.0).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255.0).toInt().coerceIn(0, 255)
        val bl = ((b1 + m) * 255.0).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }

    /** Couleur ARGB depuis OKLCh (L ∈ [0,1], C ≥ 0, h en degrés) — gamut mapping inclus.
     *  Utilisé par le mélangeur (carte perceptuellement uniforme). */
    fun oklchToArgb(L: Double, C: Double, h: Double): Int {
        val lab = oklchToOklab(L, C, h)
        val xyz = oklabToXyz(lab)
        val lrgb = mulMatVec(XYZ_RGB, xyz)
        return lrgbToArgb(gamutMap(lrgb, xyz))
    }

    // ---------------------------------------------------------------- internes

    private fun spectrumOf(argb: Int): DoubleArray {
        cacheR[argb]?.let { return it }
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val lr = uncompand(r)
        val lg = uncompand(g)
        val lb = uncompand(b)
        val w = minOf(lr, lg, lb)
        val cR = doubleArrayOf(lr - w, lg - w, lb - w)
        val c = minOf(cR[1], cR[2])
        val m = minOf(cR[0], cR[2])
        val y = minOf(cR[0], cR[1])
        val rp = maxOf(0.0, minOf(cR[0] - cR[2], cR[0] - cR[1]))
        val gp = maxOf(0.0, minOf(cR[1] - cR[2], cR[1] - cR[0]))
        val bp = maxOf(0.0, minOf(cR[2] - cR[1], cR[2] - cR[0]))
        val S = BASE_SPECTRA
        val out = DoubleArray(SIZE)
        for (i in 0 until SIZE) {
            out[i] = maxOf(
                EPS,
                w * S['W']!![i] + c * S['C']!![i] + m * S['M']!![i] + y * S['Y']!![i] +
                    rp * S['R']!![i] + gp * S['G']!![i] + bp * S['B']!![i]
            )
        }
        if (cacheR.size >= CACHE_MAX) cacheR.clear()
        cacheR[argb] = out
        return out
    }

    /** Luminance (Y de XYZ), au moins EPS. */
    private fun luminanceOf(R: DoubleArray): Double {
        var y = 0.0
        val cmfY = CMF[1]
        for (i in 0 until SIZE) y += cmfY[i] * R[i]
        return maxOf(EPS, y)
    }

    /** Kubelka-Munk : réflectance → K/S. */
    private fun ksOf(R: Double): Double = (1.0 - R) * (1.0 - R) / (2.0 * R)

    /** Kubelka-Munk : K/S → réflectance mélangée. */
    private fun kmOf(ks: Double): Double =
        1.0 + ks - kotlin.math.sqrt(ks * ks + 2.0 * ks)

    private fun toArgb(R: DoubleArray): Int {
        // R → XYZ
        val xyz = DoubleArray(3)
        for (row in 0..2) {
            var s = 0.0
            val cmfRow = CMF[row]
            for (i in 0 until SIZE) s += cmfRow[i] * R[i]
            xyz[row] = s
        }
        // XYZ → lRGB + gamut mapping perceptuel (OKLab/OKLCh, bissection sur la chroma)
        val mapped = gamutMap(mulMatVec(XYZ_RGB, xyz), xyz)
        return lrgbToArgb(mapped)
    }

    private fun lrgbToArgb(lrgb: DoubleArray): Int {
        val sr = (compand(lrgb[0]) * 255.0).toInt().coerceIn(0, 255)
        val sg = (compand(lrgb[1]) * 255.0).toInt().coerceIn(0, 255)
        val sb = (compand(lrgb[2]) * 255.0).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (sr shl 16) or (sg shl 8) or sb
    }

    /** Gamut mapping : bissection sur la chroma en OKLCh (portage fidèle). */
    private fun gamutMap(lRGB: DoubleArray, xyz: DoubleArray): DoubleArray {
        if (inGamut(lRGB)) return lRGB
        val oklab = xyzToOklab(xyz)
        val L = oklab[0]
        if (L >= 1.0) return doubleArrayOf(1.0, 1.0, 1.0)
        if (L <= 0.0) return doubleArrayOf(0.0, 0.0, 0.0)
        val C = kotlin.math.sqrt(oklab[1] * oklab[1] + oklab[2] * oklab[2])
        val h = atan2(oklab[2], oklab[1]) * 180.0 / Math.PI
        val hue = if (h >= 0.0) h else h + 360.0

        val jnd = 0.03
        val e = 0.0001
        var min = 0.0
        var max = C
        var minInGamut = true
        var current = lRGB
        var clipped = lrgbToOklab(clampRgb(current))
        var E = deltaEOK(clipped, lrgbToOklab(current))
        if (E < jnd) {
            return oklabToLrgb(clipped)
        }
        while (max - min > e) {
            val chroma = (min + max) / 2.0
            val lab = oklchToOklab(L, chroma, hue)
            val newXyz = oklabToXyz(lab)
            current = mulMatVec(XYZ_RGB, newXyz)
            if (minInGamut && inGamut(current)) {
                min = chroma
            } else {
                clipped = lrgbToOklab(clampRgb(current))
                E = deltaEOK(clipped, lab)
                if (E < jnd) {
                    if (jnd - E < e) break else {
                        minInGamut = false
                        min = chroma
                    }
                } else {
                    max = chroma
                }
            }
        }
        return oklabToLrgb(clipped)
    }

    // 2026-08-25, optimisation (Galaxy A13, ça ramait) : `.map{}.toDoubleArray()`
    // boxe chaque Double (List<Double> intermédiaire) — coûteux appelé à
    // chaque itération de la bissection de gamutMap(), elle-même appelée à
    // chaque frame de mélange pigmentaire (cf. BallCanvasView.lerpColor).
    // Boucle manuelle, même résultat, zéro boxing.
    private fun clampRgb(a: DoubleArray): DoubleArray =
        DoubleArray(3) { i -> a[i].coerceIn(0.0, 1.0) }

    private fun inGamut(lRGB: DoubleArray): Boolean =
        lRGB[0] in -0.0..1.0 && lRGB[1] in -0.0..1.0 && lRGB[2] in -0.0..1.0

    private fun deltaEOK(a: DoubleArray, b: DoubleArray): Double {
        val dl = a[0] - b[0]
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return kotlin.math.sqrt(dl * dl + da * da + db * db)
    }

    private fun xyzToOklab(xyz: DoubleArray): DoubleArray {
        val lms = mulMatVec(XYZ_LMS, xyz).map { cbrt(it) }.toDoubleArray()
        return mulMatVec(LMS_LAB, lms)
    }

    private fun oklabToXyz(oklab: DoubleArray): DoubleArray {
        val lms = mulMatVec(LAB_LMS, oklab).map { it * it * it }.toDoubleArray()
        return mulMatVec(LMS_XYZ, lms)
    }

    private fun lrgbToOklab(lRGB: DoubleArray): DoubleArray =
        xyzToOklab(mulMatVec(RGB_XYZ, lRGB))

    private fun oklabToLrgb(oklab: DoubleArray): DoubleArray =
        mulMatVec(XYZ_RGB, oklabToXyz(oklab))

    private fun oklchToOklab(L: Double, C: Double, h: Double): DoubleArray {
        val hr = h * Math.PI / 180.0
        return doubleArrayOf(L, C * cos(hr), C * sin(hr))
    }

    private fun mulMatVec(m: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        val out = DoubleArray(3)
        for (row in 0..2) {
            var s = 0.0
            val mr = m[row]
            for (i in 0..2) s += mr[i] * v[i]
            out[row] = s
        }
        return out
    }

    private fun uncompand(x: Double): Double =
        if (x > 0.04045) ((x + 0.055) / 1.055).pow(GAMMA) else x / 12.92

    private fun compand(x: Double): Double =
        if (x > 0.0031308) 1.055 * x.pow(1.0 / GAMMA) - 0.055 else x * 12.92
}
