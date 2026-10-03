# Règles ProGuard/R8 — KidOrb et InkOrb.
#
# Fichier vide volontairement : le build release active R8 (v509) avec les
# règles par défaut d'Android (`proguard-android-optimize.txt`), et les
# dépendances du projet (AndroidX uniquement) apportent les leurs.
#
# Si l'app se casse au lancement après avoir activé R8, c'est presque toujours
# une classe atteinte par réflexion (nom de classe dans une chaîne) : garder la
# classe ici, par exemple :
#   -keep class com.wian.kidorb.MonNomDeClasse { *; }
