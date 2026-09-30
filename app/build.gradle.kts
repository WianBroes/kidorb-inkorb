import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// Secrets de signature release — jamais dans le code, lus depuis
// local.properties (gitignored). Absent = build release non signé (les
// tâches assembleDebug/etc. ne sont pas affectées, seule la config des
// signingConfigs release l'est). F-Droid signe avec sa propre clé.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "com.wian.kidorb"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        // Version incrémentée à la main, versionName = date du build (repère
        // visible dans l'app — évite de confondre vieux et nouvel APK).
        // Commune aux 2 variantes — pas de raison
        // de les faire diverger, même base de code.
        versionCode = 508
        versionName = "v508 · 2026-09-11"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keystore de debug fixe (signature stable entre machines/sessions, pas
    // de désinstallation forcée au prochain build). Absent du dépôt public :
    // sans lui, Gradle retombe sur son keystore de debug par défaut.
    signingConfigs {
        if (file("encrebille-debug.keystore").exists()) {
            getByName("debug") {
                storeFile = file("encrebille-debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Keystores release générés le 2026-08-26 (keytool, RSA 2048,
        // validité 10000 jours → 2054) — 2 keystores distincts, un par app,
        // jamais partagés entre variantes. Mots de passe dans
        // local.properties (gitignored). Créés seulement si les propriétés
        // existent (cf. commentaire sur localProps plus haut). Ce bloc doit
        // rester AVANT productFlavors dans ce fichier : les flavors
        // référencent ces signingConfigs par nom (getByName), qui doivent
        // donc déjà exister au moment où ce bloc plus bas s'exécute.
        if (localProps.getProperty("KIDORB_STORE_FILE") != null) {
            create("kidRelease") {
                storeFile = file(localProps.getProperty("KIDORB_STORE_FILE"))
                storePassword = localProps.getProperty("KIDORB_STORE_PASSWORD")
                keyAlias = localProps.getProperty("KIDORB_KEY_ALIAS")
                keyPassword = localProps.getProperty("KIDORB_KEY_PASSWORD")
            }
        }
        if (localProps.getProperty("INKORB_STORE_FILE") != null) {
            create("advancedRelease") {
                storeFile = file(localProps.getProperty("INKORB_STORE_FILE"))
                storePassword = localProps.getProperty("INKORB_STORE_PASSWORD")
                keyAlias = localProps.getProperty("INKORB_KEY_ALIAS")
                keyPassword = localProps.getProperty("INKORB_KEY_PASSWORD")
            }
        }
    }

    // 2026-08-26, demande explicite : sortie en 2 apps depuis LE MÊME code
    // (plus de resynchronisation manuelle façon InkOrb/KidOrb, qui a pris 6
    // jours et ~120 versions de retard) — "kid" = zéro option avancée (le
    // toggle Mode avancé n'existe même pas dans ce build, cf.
    // BuildConfig.ADVANCED_EDITION dans MainActivity), "advanced" = tout
    // disponible, profil Normal par défaut, mode avancé off mais togglable.
    flavorDimensions += "edition"
    productFlavors {
        create("kid") {
            dimension = "edition"
            applicationId = "com.wian.kidorb"
            buildConfigField("boolean", "ADVANCED_EDITION", "false")
            if (localProps.getProperty("KIDORB_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("kidRelease")
            }
        }
        create("advanced") {
            dimension = "edition"
            // réutilise l'identité applicationId d'InkOrb (fiche Play Store
            // existante, pas de nouveau nom).
            applicationId = "com.wian.inkorb"
            buildConfigField("boolean", "ADVANCED_EDITION", "true")
            if (localProps.getProperty("INKORB_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("advancedRelease")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// Première dépendance externe du projet (jusqu'ici zéro-dépendance) —
// nécessaire pour contraindre structurellement le cluster de contrôles à ne
// jamais chevaucher la barre du haut (undo/redo/œil), quelle que soit la
// hauteur d'écran : avec FrameLayout + gravity="bottom", rien ne garantit
// qu'un élément ne remonte pas par-dessus un autre si le contenu total
// dépasse l'espace réel (mesuré sur Huawei P30 Lite, écran plus court que
// la référence Fairphone — plusieurs tentatives de calcul dynamique en
// Kotlin ont échoué à corriger ça de façon fiable). ConstraintLayout rend
// cette garantie déclarative plutôt que calculée. 2026-09-03.
dependencies {
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    // constraintlayout 2.2.2 tire encore appcompat 1.2.0 (donc fragment
    // 1.1.0 / activity 1.0.0, signalés obsolètes par Play Console) — forcer
    // une version plus récente d'appcompat fait gagner la résolution de
    // versions Gradle (plus haute demandée) sans dépendance directe à ces
    // API. 2026-09-11.
    implementation("androidx.appcompat:appcompat:1.8.0")
}
