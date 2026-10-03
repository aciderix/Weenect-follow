# Journal des versions

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Les versions suivent [SemVer](https://semver.org/lang/fr/).

## [2.1.3] — 2026-10-03

### Surveillance qui ne s'arrête plus en silence
Constat : Android (ou l'économiseur de batterie du fabricant) peut arrêter l'application. La surveillance cessait alors sans prévenir : la notification permanente disparaissait et aucune alerte n'était donnée, même à la fin d'une sortie accompagnée avec la balise toujours hors zone.

- **Chien de garde** : une alarme système relance la surveillance toutes les 15 minutes si elle a été arrêtée, même téléphone en veille. Elle relance aussi juste après un balayage de l'appli dans les applis récentes, et après un plantage. Au redémarrage, la position est relue immédiatement : l'alarme sonne si un résident est dehors.
- **Interruption tracée** : à la relance, « Surveillance interrompue de 10:50 à 19:52 (9 h 2 min) » est inscrit au journal (à traiter) et notifié, avec la cause (plantage ou arrêt par le téléphone). L'appli Windows le fait aussi (poste éteint, en veille ou appli fermée).
- **Les autres appareils sont prévenus** : un appareil qui surveillait des résidents et ne donne plus signe de vie depuis 10 minutes est signalé une fois sur les autres (« 📵 Karim ne surveille plus »), puis à son retour.
- **Écran État** :
  - nouvelle vérification « Relance automatique » (autorisation Alarmes et rappels) ;
  - pour les marques connues pour arrêter les applis (Xiaomi, Samsung, Huawei, Oppo, OnePlus…), un lien vers le guide de réglage de la marque.
- Une erreur imprévue dans une tâche de fond ne fait plus planter l'application.

## [2.1.2] — 2026-10-03

### Correction
- Notification permanente : un résident en sortie accompagnée n'est plus « oublié ». Elle affichait « 🟢 0 résident(s) en sécurité » quand le seul résident était en sortie ; elle affiche maintenant « ⏸️ 1 résident(s) en sortie accompagnée : surveillance de zone suspendue », et ajoute « • N en sortie accompagnée » au décompte habituel.

## [2.1.1] — 2026-10-03

### Corrections
- **Mises à jour sans désinstaller** : les APK sont désormais tous signés avec la même clé (clé de test partagée du dépôt). Une nouvelle version s'installe par-dessus l'ancienne et conserve résidents, réglages et journal. Auparavant, chaque build avait sa propre clé : il fallait désinstaller, ce qui effaçait les données.
- **Zone vierge** : un appareil neuf ne partage plus sa zone d'usine (« Mon établissement »). Une zone vierge déjà partagée n'écrase plus la vraie zone d'un appareil configuré : c'est la vraie zone qui est partagée à la place.
- **Appareils en double** : après une réinstallation, l'ancienne installation hors ligne n'apparaît plus à côté de la nouvelle dans « Partage entre appareils ».

### Amélioration
- La fenêtre de connexion à Supabase demande aussi le **nom du soignant** de l'appareil, pré-rempli et modifiable. C'est lui qui s'affiche sur « Pris en charge par … » chez les autres.

> La 2.1.1 doit encore être installée après désinstallation de la 2.1.0 (clé différente). Les versions suivantes s'installeront par-dessus.

## [2.1.0] — 2026-10-03

### Synchronisation automatique des résidents (Supabase)
- Un résident ajouté, modifié ou retiré sur un appareil apparaît, change ou disparaît automatiquement sur tous les autres, en quelques secondes. Fini l'ajout manuel sur chaque téléphone et chaque PC.
- Idem pour les comptes Weenect et la zone de l'établissement.
- Les mots de passe Weenect sont partagés chiffrés par la **phrase secrète de l'établissement**, saisie une fois par appareil et vérifiée auprès du projet.
- Au premier raccordement, les fiches déjà présentes sont rattachées aux fiches partagées de même balise (sans balise : même nom et même chambre), sans doublon.
- Remplace « Publier / Récupérer la configuration ».

### Sécurité
- Une sortie signalée par un autre appareil fait sonner celui-ci **même si le résident n'y est pas encore configuré** (auparavant, l'alerte était ignorée).

### Technique
- Nouveau script `supabase/migrations/20261003120000_sync_configuration.sql`, à exécuter après le premier.
- Base Android v5 (identifiant de synchronisation), migration testée sans perte de données.

## [2.0.0] — 2026-10-03

Première version publiée : refonte complète après audit, application Windows et partage entre appareils.

### Sécurité des résidents (surveillance « fail-safe »)
- État réel de la surveillance affiché partout (en-tête, notification permanente, écran **État**). Une erreur réseau ne coupe **jamais** une alarme.
- Statut **Position inconnue** : aucune coordonnée inventée. Statut **Signal ancien** basé sur l'heure réelle du fix de la balise.
- Confirmation de sortie (précision GPS, second fix ou 60 s), rappel sonore si personne ne prend en charge, alertes batterie faible et balise muette.
- Une vraie sortie déclenche la même interface qu'un exercice : pop-up multi-alarmes, « Je m'en occupe », « Retrouvé », « Guider ».

### Plusieurs résidents
- Tri par urgence, vues détaillée / compacte / grille, filtres, regroupement par unité, recherche.
- Carte avec photos ou initiales, regroupement des marqueurs, trajet sur 2 h ou 6 h, zones annexes et mode nuit.
- Sorties accompagnées (y compris en groupe), niveau de risque, journal filtrable avec export PDF/CSV, prise de poste.

### Application Windows (nouveau)
- Poste fixe de surveillance (Compose Desktop) : sirène, volume forcé, fenêtre au premier plan pendant une alarme.
- Démarrage automatique avec la session Windows, icône près de l'horloge, une seule instance, installateur MSI sans droits administrateur.
- Proxy de l'établissement pris en compte, données dans `%LOCALAPPDATA%\AlerteResidents`, mots de passe protégés par DPAPI.

### Partage entre appareils — Supabase (nouveau, facultatif)
- Une sortie détectée par un appareil sonne sur tous ; « Je m'en occupe » coupe l'alarme partout avec le nom du soignant.
- Sorties accompagnées partagées, liste des appareils en ligne, configuration partagée chiffrée de bout en bout par une phrase secrète.
- Connexion à **n'importe quel** projet Supabase ; schéma versionné dans `supabase/migrations/`, RLS et accès réservé aux comptes autorisés.
- Action GitHub qui empêche la mise en pause des projets gratuits ; purge automatique de l'historique (pg_cron, facultatif).

### Sécurité et technique
- Comptes Weenect avec mots de passe chiffrés (Android Keystore / DPAPI), export de configuration chiffré par code, paramètres protégés par code PIN.
- Aucun journal réseau en production, sauvegardes Android désactivées.
- Module `core` partagé entre Android et Windows, Room v4 avec migration testée, R8 en release.
- Sirène intégrée (« Alarme détecteur de fumée 3 », La Sonothèque).
- Plus de 110 tests automatisés : moteur, migration, chiffrement, partage, rendu des écrans.

[2.1.3]: https://github.com/aciderix/Weenect-follow/releases/tag/v2.1.3
[2.1.2]: https://github.com/aciderix/Weenect-follow/releases/tag/v2.1.2
[2.1.1]: https://github.com/aciderix/Weenect-follow/releases/tag/v2.1.1
[2.1.0]: https://github.com/aciderix/Weenect-follow/releases/tag/v2.1.0
[2.0.0]: https://github.com/aciderix/Weenect-follow/releases/tag/v2.0.0
