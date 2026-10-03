# Partage entre appareils (Supabase)

Sans Supabase, chaque téléphone et chaque PC surveille les balises Weenect **de son côté**. Il sonne seul et ses alertes ne sont pas reliées à celles des autres : un « Je m'en occupe » sur un PC ne coupe pas la sonnerie du téléphone de la collègue.

Avec un projet Supabase, tous les appareils d'un établissement **coordonnent leurs alertes** :

| Ce qui se passe sur un appareil | Effet sur tous les autres |
|---|---|
| Une sortie de zone est détectée | Ils sonnent aussi (« Signalé par : PC infirmerie »), même si leur propre connexion à Weenect est en panne |
| « Je m'en occupe » | L'alarme s'arrête partout, avec le nom du soignant |
| « Retrouvé » ou retour confirmé par la balise | L'alerte est levée partout |
| Sortie accompagnée démarrée ou terminée | La surveillance du résident est suspendue ou reprise partout |
| Exercice sur un résident | L'exercice sonne partout (pour tester toute la chaîne) |
| — | L'écran **État** liste les appareils en ligne et ceux dont la surveillance est dégradée |
| « Publier ma configuration » | Les autres peuvent récupérer la zone, les résidents et les comptes Weenect sans échanger de fichier |

**La surveillance reste locale et autonome.** Chaque appareil continue d'interroger Weenect et de sonner lui-même. Si Supabase est injoignable, rien ne change pour la sécurité : seule la coordination s'arrête. Les actions faites hors ligne sont envoyées au retour du réseau.

L'app peut se connecter à **n'importe quel projet Supabase** (supabase.com ou auto-hébergé). Un projet correspond à un établissement.

---

## Ce qui est stocké (et ce qui ne l'est pas)

| Table | Contenu | Pourquoi |
|---|---|---|
| `incidents` | une ligne par sortie de zone : identifiant de balise, nom du résident, heure, position **au moment de la sortie**, qui a pris en charge, comment l'alerte a été levée | coordonner l'alarme et garder la trace de qui a fait quoi |
| `resident_pauses` | sorties accompagnées en cours (balise, jusqu'à quand, motif, par qui) | ne pas sonner pendant une sortie prévue |
| `devices` | appareils connectés : nom, type, nombre de balises suivies, état de la surveillance, dernier contact | savoir qui surveille |
| `shared_config` | la configuration publiée (même format que l'export de l'app). **Les mots de passe Weenect y sont chiffrés sur l'appareil par une phrase secrète que Supabase ne connaît pas.** | installer un nouvel appareil en 1 minute |
| `staff` | les comptes autorisés | contrôle d'accès |
| `keepalive` | une seule ligne d'horodatage | éviter la mise en pause du projet gratuit |

Ce qui **n'est pas** stocké :
- **le suivi GPS continu** : chaque appareil interroge Weenect lui-même ;
- **les photos** ;
- **les identifiants Weenect en clair**.

Les résidents sont reconnus d'un appareil à l'autre par l'**identifiant de leur balise Weenect**. Il n'y a pas d'identifiant à synchroniser : deux appareils configurés séparément, par exemple par import de fichier, se comprennent tout de suite.

Le schéma complet est versionné dans [`supabase/migrations/`](supabase/migrations). Le script est **idempotent** : il ne supprime jamais rien et peut être relancé sans risque.

---

## Mise en place (environ 15 minutes, une fois par établissement)

### 1. Créer le projet
Sur [supabase.com](https://supabase.com) : **New project**. Choisissez une région en Europe (par exemple *West EU (Ireland)* ou *Central EU (Frankfurt)*) et notez le mot de passe de la base.

### 2. Créer les tables
**SQL Editor › New query** : collez tout le contenu de `supabase/migrations/20261002180000_alerte_residents_init.sql`, puis **Run**.
Avec la CLI, c'est équivalent : `supabase link --project-ref <ref>` puis `supabase db push`.

Pour vérifier : **Advisors › Security Advisor** ne doit afficher aucune alerte.

### 3. Fermer les inscriptions
**Authentication › Sign In / Providers** : désactivez **Allow new users to sign up**. Seul l'administrateur crée les comptes.

### 4. Créer les comptes
**Authentication › Users › Add user › Create new user**. Saisissez un e-mail et un mot de passe et cochez **Auto Confirm User**.
Vous pouvez créer un compte par poste (« pc-infirmerie@… ») ou un par personne.

### 5. Autoriser ces comptes
Un compte qui n'est pas dans la table `staff` ne voit **rien**, même connecté. Dans le **SQL Editor** :
```sql
select public.add_staff('pc-infirmerie@mon-ehpad.fr', 'PC infirmerie');
select public.add_staff('ide.nuit@mon-ehpad.fr', 'IDE de nuit');
```
Pour retirer un accès : `delete from public.staff where user_id = (select id from auth.users where email = '…');`. Vous pouvez aussi supprimer l'utilisateur dans **Authentication › Users**.

### 6. Relever l'adresse et la clé publique
**Project Settings › API Keys** (ou **Connect**) :
- **Project URL** : `https://xxxx.supabase.co` ;
- **Publishable key** (`sb_publishable_…`) ou l'ancienne clé **anon**.

Cette clé est faite pour être mise dans une application. Seule, elle ne donne accès à **aucune donnée**, uniquement à la fonction `keepalive()`.
⚠️ Ne mettez **jamais** la clé `service_role` / `secret` dans l'app.

### 7. Connecter chaque appareil
Dans l'app, sur Android comme sur Windows : **Paramètres › Partage entre appareils › Connecter à Supabase**. Renseignez l'adresse, la clé publique, l'e-mail, le mot de passe et un nom d'appareil parlant (« PC cadre de santé »).
Le mot de passe n'est pas conservé. Seul un jeton de session est gardé, chiffré par le Keystore Android ou par la protection de session Windows.

### 8. Partager la configuration (facultatif)
Sur l'appareil déjà configuré : **Publier ma config.**, avec une phrase secrète d'au moins 6 caractères.
Sur les autres : **Récupérer la config.**, puis saisissez la phrase et choisissez **Fusionner** ou **Remplacer**.
Transmettez la phrase de vive voix, jamais par le même canal que les identifiants.

### 9. Empêcher la mise en pause (projets gratuits)
Supabase met en pause un projet gratuit **après 7 jours sans activité**. L'action GitHub [`supabase-keepalive.yml`](.github/workflows/supabase-keepalive.yml) l'appelle 4 fois par jour.
Sur GitHub, ouvrez **Settings › Secrets and variables › Actions › New repository secret** et créez :

| Nom | Valeur |
|---|---|
| `SUPABASE_URL` | l'adresse du projet (étape 6) |
| `SUPABASE_ANON_KEY` | la clé publique (étape 6) |

Testez avec **Actions › Supabase keepalive › Run workflow**.
Bon à savoir :
- les actions planifiées ne tournent que depuis la **branche par défaut** du dépôt ;
- sans les secrets, l'action ne fait rien ;
- GitHub désactive les actions planifiées d'un dépôt public inactif depuis 60 jours ; l'action se réactive elle-même à chaque passage pour éviter cela.

Si le projet a quand même été mis en pause : tableau de bord Supabase › **Resume project**. C'est possible pendant 90 jours.

---

## Sécurité

- **Row Level Security sur toutes les tables.** L'accès est réservé aux comptes de `staff`. La fonction de contrôle (`private.is_staff`) est dans un schéma non exposé par l'API.
- **Clé publique seule** : aucun accès aux données, seulement `keepalive()`, qui n'écrit qu'un horodatage.
- **Écritures** : elles passent uniquement par des fonctions SQL documentées (`report_exit`, `handle_incident`, `resolve_incident`, `set_pause`, `sync_state`, `publish_config`, `get_shared_config`). Ces fonctions s'exécutent avec les droits de l'utilisateur connecté, donc la RLS s'applique.
- **Mots de passe Weenect** : ils ne sont jamais envoyés en clair. Dans la configuration partagée, ils sont chiffrés sur l'appareil (PBKDF2 + AES-GCM) par la phrase secrète.
- **Sur les appareils** : jeton de session chiffré par le Keystore Android ou DPAPI sous Windows.
- **Bonnes pratiques Supabase** : activez la double authentification sur le compte propriétaire du projet et gardez un deuxième propriétaire dans l'organisation.

## Données personnelles (RGPD)

Les noms des résidents et l'historique de leurs sorties sont des données personnelles, voire **de santé** dans un contexte EHPAD/MAS. **Avant la mise en service, validez ce point avec votre DPO** :
- **Hébergement de données de santé (HDS)** : en France, l'hébergement de données de santé pour le compte d'un établissement relève de la certification HDS. Supabase *cloud* n'est pas, à notre connaissance, certifié HDS. Deux options :
  - utiliser Supabase uniquement pour la coordination, en évitant les informations médicales dans les notes des fiches (elles sont incluses dans la configuration publiée) ;
  - ou **auto-héberger Supabase** chez un hébergeur certifié HDS. L'app accepte n'importe quelle adresse de projet.
- **Minimisation** : seule la position au moment de la sortie est conservée. Il n'y a ni trajet ni photo.
- **Durée de conservation** : la fonction `purge_history(jours)` supprime les incidents clos, les sorties accompagnées terminées et les appareils inactifs plus anciens que la durée choisie. Une alerte en cours n'est jamais supprimée.
  - **Purge automatique (recommandé)** : exécutez une fois [`supabase/optional/purge_automatique.sql`](supabase/optional/purge_automatique.sql) dans le SQL Editor. Il active l'extension `pg_cron` et planifie la purge chaque dimanche à 3 h 17 (UTC), avec une conservation de 365 jours. Adaptez cette durée avec votre DPO.
  - **Purge manuelle** : `select public.purge_history(365);` dans le SQL Editor.
  - **Vérification** : `select * from cron.job;` affiche la planification, `select * from cron.job_run_details order by start_time desc limit 5;` les dernières exécutions.
- **Droit d'accès / journal** : la table `incidents` donne l'historique complet (qui a pris en charge, quand, comment l'alerte a été levée).

---

## Référence technique

### Fonctions appelées par l'app
| Fonction | Rôle |
|---|---|
| `check_access()` | vérifie à la connexion que le compte est dans `staff` |
| `sync_state(device…, since)` | toutes les 6 s : signale l'appareil, renvoie les incidents ouverts et récents, les sorties accompagnées, les appareils et la version de la configuration |
| `report_exit(tracker, …)` | ouvre l'incident, ou renvoie celui déjà ouvert par un autre appareil (un seul incident ouvert par balise, garanti par un index unique) |
| `handle_incident(tracker, drill, staff)` | « Je m'en occupe » |
| `resolve_incident(tracker, drill, staff, resolution)` | `found` (retrouvé), `returned` (balise), `outing` (sortie accompagnée) |
| `set_pause(tracker, name, until, reason, staff)` | début ou fin d'une sortie accompagnée (clôt l'incident ouvert) |
| `publish_config(payload, by)` / `get_shared_config()` | configuration partagée |
| `keepalive()` | ouverte à la clé publique ; met à jour un horodatage |
| `add_staff(email, nom)`, `purge_history(jours)` | administration, depuis le SQL Editor uniquement |

### Faire évoluer le schéma
Ajoutez un **nouveau** fichier `supabase/migrations/AAAAMMJJHHMMSS_description.sql`, idempotent (`create … if not exists`, `create or replace`) et sans suppression de données. Appliquez-le avec `supabase db push` ou dans le SQL Editor. Ne modifiez pas un fichier déjà appliqué en production.

### Dépannage
| Message dans l'app | Cause probable |
|---|---|
| « E-mail ou mot de passe incorrect » | compte inexistant, non confirmé (cochez *Auto Confirm User*) ou mauvais mot de passe |
| « Compte reconnu mais pas autorisé » | il manque `select public.add_staff('email', 'Nom');` |
| « Base non initialisée » | le script de l'étape 2 n'a pas été exécuté sur ce projet |
| « Session expirée : reconnectez-vous » | compte supprimé ou mot de passe changé : **Se reconnecter** |
| « Hors ligne » | réseau ou proxy de l'établissement ; les alertes locales continuent |
| HTTP 540 dans l'action keepalive | projet en pause : **Resume project** |
