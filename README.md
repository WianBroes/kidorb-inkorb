# KidOrb / InkOrb

Deux applications de dessin Android (une bille qui peint), construites depuis la même base de code :

| App | Identifiant | Flavor Gradle |
|---|---|---|
| **KidOrb** — version simple, sans option avancée | `com.wian.kidorb` | `kid` |
| **InkOrb** — toutes les options, mode avancé | `com.wian.inkorb` | `advanced` |

Aucune collecte de données, aucun service Google, aucune publicité. Seule permission : écriture du stockage, limitée à Android 9 et avant (`maxSdkVersion=28`).

## Compiler

```
./gradlew assembleKidRelease       # KidOrb
./gradlew assembleAdvancedRelease  # InkOrb
```

Prérequis : JDK 17, SDK Android 36. Sans `local.properties`, l'APK de release n'est pas signé.

## Licence

Code sous **GNU GPL v3.0 ou ultérieure** — voir [LICENSE](LICENSE). Copyright © 2026 Wian Broes.
Composants tiers : voir [NOTICE](NOTICE).
