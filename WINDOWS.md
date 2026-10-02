# Alerte Résidents — application Windows

Version PC de l'application, pensée pour un **poste fixe** (bureau infirmier, accueil) qui surveille les résidents en continu et **sonne** en cas de sortie de zone. Elle interroge Weenect elle-même : pas de serveur, pas besoin que le téléphone soit allumé.

## Installer

1. Sur GitHub : onglet **Actions** → workflow **Build Windows app** → dernière exécution réussie → télécharger l'artefact **AlerteResidents-Windows** (fichier `.msi`).
2. Double-cliquer sur le `.msi`. L'installation se fait pour l'utilisateur courant (pas de droits administrateur).
   Windows SmartScreen peut afficher « Windows a protégé votre ordinateur » (application non signée) : **Informations complémentaires → Exécuter quand même**.
3. Lancer **Alerte Résidents** depuis le menu Démarrer.

Pour mettre à jour : installer le nouveau `.msi` par-dessus, les réglages et résidents sont conservés.

## Démarrage automatique

Au premier lancement, l'application s'inscrit pour **démarrer automatiquement à l'ouverture de la session Windows** (réduite dans la zone de notification, à côté de l'horloge). Désactivable dans **Paramètres → Windows**.

- **Fermer la fenêtre (✕) ne coupe pas la surveillance** : l'application reste active dans la zone de notification. Double-clic sur l'icône pour la rouvrir.
- Pour l'arrêter vraiment : clic droit sur l'icône → **Quitter** (une confirmation est demandée).
- Un seul exemplaire peut tourner à la fois : relancer le raccourci ré-affiche simplement la fenêtre.

## Quand un résident sort de la zone

- La sirène (ou le son système, au choix dans les Paramètres) retentit, le volume de Windows est monté au maximum si l'option est active ;
- la fenêtre passe **au premier plan, au-dessus des autres applications**, avec la fenêtre d'alarme (« Je m'en occupe », « Couper le son », « Guider ») ;
- une notification Windows s'affiche ; l'icône de la zone de notification permet aussi de **couper l'alarme**.

« Couper le son » n'agit que sur ce PC. « Je m'en occupe » et « Retrouvé » sont partagés avec les autres appareils lorsque le partage Supabase est configuré.

## Reprendre la configuration du téléphone

Sur le téléphone : **Paramètres → Sauvegarde / nouveau téléphone → Exporter** (avec un code pour inclure les mots de passe Weenect) puis transférer le fichier sur le PC (mail, clé USB…).
Sur le PC : **Paramètres → Sauvegarde / import depuis le téléphone → Importer**, choisir le fichier et saisir le code.

## Pour que la surveillance soit fiable

- Laisser le PC **allumé et la session ouverte** (l'écran peut se verrouiller, mais pas la session se fermer). L'application empêche la mise en veille automatique tant qu'elle tourne ; une mise en veille manuelle ou un arrêt du PC coupe la surveillance.
- Haut-parleurs branchés et allumés. Faire un **test d'alarme** à chaque prise de poste (**Paramètres → Son de l'alarme → Tester maintenant**).
- L'écran **État** liste les points à vérifier (démarrage automatique, notifications, connexion, dernière vérification).

## Déploiement sur plusieurs postes (infirmerie, cadres de santé…)

- Installer le `.msi` sur chaque PC.
- **Recommandé : relier tous les appareils à un projet Supabase** (voir [SUPABASE.md](SUPABASE.md)). Une sortie détectée par un appareil sonne alors sur tous, et « Je m'en occupe » coupe l'alarme partout avec le nom du soignant. La configuration (zone, résidents, comptes) se récupère en un clic.
- Sans Supabase, chaque poste surveille et sonne seul : il faut couper l'alarme sur chacun. La configuration se transmet alors par export/import de fichier depuis le téléphone.
- Chaque poste interroge Weenect toutes les 15 s environ : avec beaucoup de postes, gardez un œil sur l'écran **État** (erreurs de connexion Weenect).
- Le proxy internet configuré dans Windows est utilisé automatiquement. Si le service informatique bloque les applications non signées, transmettez-lui le `.msi` pour qu'il l'autorise.

## Données

Tout est stocké dans `%LOCALAPPDATA%\AlerteResidents` (résidents, zone, journal, réglages, cache des cartes, journal technique). Les mots de passe Weenect sont chiffrés avec la protection de session Windows (DPAPI) : le fichier copié sur un autre PC ou un autre compte Windows ne permet pas de les relire.

## Développement

```
./gradlew :desktop:run             # lancer l'application
./gradlew :desktop:test            # tests (stockage, chiffrement, instance unique, interface)
./gradlew :desktop:packageMsi      # installateur (à exécuter sous Windows)
```

Le module `:core` contient la logique partagée avec l'app Android (API Weenect, évaluation de zone, statuts, sauvegardes) ; `:desktop` contient l'interface Compose Desktop et l'intégration Windows.
