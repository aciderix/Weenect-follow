# Journal des versions

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/). Les versions suivent [SemVer](https://semver.org/lang/fr/).

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

[2.0.0]: https://github.com/aciderix/Weenect-follow/releases/tag/v2.0.0
