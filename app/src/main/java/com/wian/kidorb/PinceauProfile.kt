package com.wian.kidorb

/** Un « pinceau » nommé : paquet de réglages du trait libre (2026-08-22,
 *  demande explicite : "un éditeur de pinceaux à la suite des billes dans
 *  le menu" — même principe que BilleProfile, périmètre réduit aux
 *  réglages qui ont un sens pour un trait dessiné à la main (pas de
 *  physique : ni rebond, ni poids, ni friction, ni secousse). */
data class PinceauProfile(
    var nom: String,
    var couleur: Int = 0xFFE53935.toInt(),
    var largeurDp: Float = 26f,
    var textureAmount: Float = 0f,
    var fonduRate: Float = 0.4f,
    // 2026-08-22, demande explicite : "j'ai besoin des 2 options" — actif =
    // couleur figée au début du trait + fondu au contact d'une autre
    // couleur ; inactif = couleur du mélangeur prise en direct, jamais
    // mélangée (comportement d'avant l'ajout du fondu).
    var melangeActif: Boolean = true,
)
