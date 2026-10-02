# Audit — Weenect Follow (« Alerte Résidents »)

_Audit réalisé le 2 octobre 2026 sur la branche `claude/gracious-bell-z12ib9` (commit de base `e2d1714`)._

## Méthode

- Lecture de l'ensemble du code Kotlin (~9 000 lignes), du manifeste, de la config Gradle et de la CI.
- Comparaison des appels réseau avec le client de référence `weenect-go` (endpoints, en-têtes, préfixe `JWT`, format des positions).
- Compilation de l'APK debug et exécution des tests unitaires en local (JDK 21, SDK Android 36.1).
- Ajout d'une suite de tests automatisés (voir [§ Tests](#tests)), dont des tests « bout en bout » du `WeenectRepository` avec une vraie base Room en mémoire et un faux serveur Weenect (MockWebServer).

L'application est une app de **sécurité des personnes** : le principe qui guide l'audit est qu'en cas de doute (réseau coupé, balise muette, identifiants faux…), l'app doit **alerter** plutôt que d'afficher « en sécurité » (_fail-safe_). C'est là que se concentrent les problèmes les plus importants.

## Synthèse

| Niveau | # | Thème |
|---|---|---|
| 🔴 Critique | C1 | Une panne de synchronisation est invisible… et peut couper une alarme en cours |
| 🔴 Critique | C2 | Plusieurs cas « inconnus » sont affichés « en sécurité » (fail-open) |
| 🔴 Critique | C3 | Une position ancienne est affichée comme récente |
| 🔴 Critique | C4 | Mots de passe et données de santé exposés (logs, sauvegardes, export) |
| 🔴 Critique | C5 | Une vraie alarme n'a pas la même interface qu'une alarme de test |
| 🔴 Critique | C6 | Le « X » du bandeau rouge lève toutes les alertes et téléporte les résidents |
| 🟠 Majeur | M1–M9 | Fiabilité Android, concurrence, commandes balise, migrations, guidage GPS, multi-téléphones |
| 🟡 Mineur | — | Esthétique (mode sombre), valeurs codées en dur, dépendances inutiles… |

Le code est lisible, bien commenté en français, et la mécanique principale (détection de transition dedans → dehors, alarme sur le canal ALARM, notification plein écran, service au premier plan, redémarrage au boot) est en place et **fonctionne dans le cas nominal** — les tests le confirment. Les défauts concernent surtout les cas dégradés, qui sont justement ceux qui comptent la nuit.

---

## 🔴 Problèmes critiques

### C1 — Une panne de synchronisation est invisible et peut couper l'alarme

`service/ResidentMonitoringService.kt` (`doMonitoringCycle`)

- `outsideCount` ne compte que les synchronisations **réussies**. Si la synchro échoue (réseau, API Weenect en panne, jeton refusé), le résident n'est pas compté → `outsideCount == 0` → **`stopAlarm()` est appelé** alors que le résident est toujours dehors.
- La notification permanente affiche alors « 🟢 N résidents suivis en sécurité » même si **toutes** les synchros ont échoué.
- Le tableau de bord affiche « Active 24h/24 » et « Surveillance permanente active » en vert **codé en dur**, sans lien avec l'état réel.

**Proposition** : calculer un état de santé par résident (`OK / signal ancien / erreur`) et global ; ne couper l'alarme que si chaque résident en alerte a été **vu rentré** ; afficher un bandeau orange « Surveillance dégradée : 2 balises injoignables depuis 5 min » et, au-delà d'un seuil, déclencher une alerte sonore distincte.

### C2 — Les cas « inconnus » sont traités comme « en sécurité »

`data/remote/WeenectRepository.kt` (`syncResidentPosition`)

1. **Mauvais mot de passe Weenect** : si le login échoue, `token == null`, le bloc réseau est sauté et le code tombe dans la branche « mode exercice », qui renvoie `Result.success` avec l'ancienne position. L'erreur n'est jamais remontée. _(test `wrong Weenect password is reported as a failure`)_
2. **Résident sans identifiants / sans numéro de balise** : il est placé au **centre de la zone** et affiché « Dans l'enceinte de l'établissement » en vert. Un oubli de saisie = un résident non surveillé qui paraît protégé. _(test `resident without tracker credentials is not reported as safe`)_
3. **Position sans coordonnées** (`latitude: null`) : remplacée par la dernière position, ou à défaut par le centre de la zone. _(test `position without coordinates is not treated as inside the zone`)_

**Proposition** : introduire un statut explicite `UNKNOWN` (gris/orange) distinct de `IN_ZONE`, ne jamais inventer de coordonnées, et refuser d'enregistrer un résident « suivi » sans balise associée (ou l'afficher clairement « Non suivi »).

### C3 — Une position ancienne est affichée comme récente

`lastUpdatedTime = System.currentTimeMillis()` à chaque requête réussie, au lieu de l'heure réelle du fix (`date_tracker` / `last_message`, déjà présents dans `WeenectPositionDto` mais ignorés). Conséquences :

- une balise éteinte, déchargée ou sans réseau continue d'afficher « Signal reçu : À l'instant » ;
- l'indicateur « Signal ancien (> 15 min) » de `ResidentCard` **ne peut jamais s'allumer** tant que l'API répond ;
- la zone est évaluée sur une position potentiellement vieille de plusieurs heures.

_(test `last update time reflects the tracker fix time`)_

**Proposition** : stocker l'heure du fix, afficher son âge, et lever une alerte « balise muette » au-delà d'un seuil configurable. Exploiter aussi `is_in_deep_sleep`, `off_reason` et le champ `type` (`CMD-X` = position WiFi « aimantée », cf. `weenect-go/types.go`).

### C4 — Mots de passe et données de santé exposés

- `HttpLoggingInterceptor` en niveau **BODY** dans **toutes** les versions (y compris release) : chaque login écrit le **mot de passe Weenect** et le **jeton JWT** en clair dans logcat (lisible par ADB, outils de diagnostic, rapports de bug).
- Mots de passe stockés **en clair** dans Room, avec `android:allowBackup="true"` et des règles de sauvegarde vides → la base part dans la **sauvegarde Google Drive** du téléphone.
- L'export de configuration contient les mots de passe en clair et est envoyé via la feuille de partage (WhatsApp, e‑mail…). _(test `export does not leak Weenect passwords in clear text`)_
- Noms, chambres, contacts d'urgence et notes (« fugueuse »…) sont des **données de santé** au sens du RGPD dans un contexte MAS/EHPAD : chiffrement, journalisation des accès et durée de conservation sont attendus.
- La clé Supabase est en dur dans le code ; c'est normal pour une clé `anon` **à condition** que la Row Level Security soit activée côté Supabase (à vérifier).

**Proposition** : logs réseau uniquement en debug et niveau `BASIC` ; `allowBackup="false"` (ou exclure la base) ; chiffrer les identifiants avec l'Android Keystore (EncryptedSharedPreferences / SQLCipher) ; export chiffré par un code saisi à l'import, ou export sans mots de passe.

### C5 — Une vraie alarme n'a pas la même interface qu'une alarme de test

`_isAlarmRinging` (ViewModel) n'est passé à `true` **que** par les fonctions de test (`simulateZoneExit`, `triggerManualLoudAlarmTest`). Lors d'une **vraie** sortie (détectée par le service), l'alarme sonne via `SoundAlertManager` mais :

- la pop-up « 🚨 SORTIE DE ZONE DÉTECTÉE » de `MainActivity` ne s'affiche pas ;
- le bouton « Couper son » du bandeau rouge n'apparaît pas ;
- les réglages « Alertes sonores / Vibration » de la zone sont **ignorés** : `SecuriResidentApp` force `soundEnabled = true, vibrateEnabled = true`.

Les tests d'alarme de l'écran Paramètres donnent donc une image fausse de ce qui se passera réellement.

**Proposition** : exposer l'état de l'alarme depuis `SoundAlertManager` sous forme de `StateFlow` (source unique de vérité) et le lire dans le ViewModel ; passer les réglages de la zone au déclenchement réel.

### C6 — Le « X » du bandeau rouge lève toutes les alertes, sans confirmation

Dans `AlertBanner`, l'icône « X » (qui ressemble à « fermer le bandeau ») appelle `resolveAllActiveAlerts()` : **tous** les résidents hors zone sont déplacés au centre de l'établissement en base, marqués « en sécurité », et toutes les alertes sont acquittées. Un appui accidentel suffit.

De même, « Sécurisé ✅ » remplace la position réelle du résident par le centre de la zone : la carte et l'historique deviennent faux.

**Proposition** : supprimer le « X » (ou en faire un simple repli visuel), demander une confirmation, et séparer **l'état d'alerte** (acquittée, par qui, quand) de **la position** (qui ne doit venir que de la balise).

---

## 🟠 Problèmes majeurs

**M1 — Commandes balise (Sonner, Vibrer, SuperLive, Rafraîchir)** : le code HTTP de la réponse n'est pas vérifié et il n'y a pas de nouvelle tentative après un 401 (jeton expiré). L'app affiche « Balise en cours de sonnerie » même si Weenect a refusé ; et en cas d'échec le message est « Commande sonnerie transmise » — l'inverse de la réalité. _(test `ring command reports server errors`)_

**M2 — Concurrence** : le service et le ViewModel (actualisation manuelle, ajout de résident, changement de zone) synchronisent en parallèle les mêmes résidents → doubles alertes possibles, et `updateResident(objet complet)` peut écraser une modification faite entre-temps (la requête `updatePosition` existe dans le DAO mais n'est pas utilisée). `isCycleRunning` et l'état de `SoundAlertManager` ne sont pas protégés contre les accès concurrents. → un `Mutex` dans le repository et des mises à jour partielles.

**M3 — Service au premier plan de type `location`** : l'app n'utilise pas le GPS du téléphone en arrière-plan (elle interroge l'API Weenect). Depuis Android 14, démarrer un service `location` depuis l'arrière-plan (ex. au redémarrage du téléphone) est refusé → `SecurityException` non interceptée dans `onStartCommand`, donc risque de **plantage au boot et de surveillance arrêtée**. Garder uniquement `specialUse`. Retirer les permissions inutilisées (`FOREGROUND_SERVICE_DATA_SYNC`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM` — cette dernière est de plus refusée par le Play Store hors réveils/agendas).

**M4 — Réveil de l'écran** : `startActivity` depuis l'arrière-plan est bloqué depuis Android 10 ; seul le _full-screen intent_ fonctionne, et depuis Android 14 il nécessite une autorisation spéciale (`NotificationManager.canUseFullScreenIntent()`), à vérifier et à demander dans l'écran Paramètres. `ACQUIRE_CAUSES_WAKEUP` n'a aucun effet avec un `PARTIAL_WAKE_LOCK`. `setBypassDnd(true)` est ignoré sans l'accès « Ne pas déranger ».

**M5 — Migrations destructives** : `fallbackToDestructiveMigration()` + `exportSchema = false` → la prochaine modification d'une entité **effacera sans prévenir** résidents, identifiants, zone et journal d'alertes sur tous les téléphones mis à jour. → exporter le schéma et écrire des migrations.

**M6 — Pas d'escalade** : après « Couper sonnerie », si le résident reste dehors, plus rien ne se passe. Prévoir un rappel sonore après X minutes sans acquittement. Le type `LOW_BATTERY` est prévu dans `AlertEvent` mais jamais émis ; idem pour « balise hors ligne ».

**M7 — Faux positifs en bordure de zone** : une seule position hors zone déclenche l'alarme ; la précision du fix (`radius`) et le type de fix ne sont pas pris en compte. Prévoir une hystérésis (ex. confirmer sur 2 positions, ou `distance − précision > rayon`).

**M9 — « Guider » ouvre toujours le navigateur** (relevé par Android Lint) : depuis Android 11, `resolveActivity()` renvoie `null` pour Google Maps si le manifeste ne déclare pas de bloc `<queries>`. Le test `mapIntent.resolveActivity(...) != null` échoue donc toujours et l'app ouvre Google Maps **dans le navigateur** au lieu du guidage piéton de l'appli (`MainActivity`, `DashboardScreen`, `MapScreen`). Correctif : appeler `startActivity` dans un `try/catch (ActivityNotFoundException)`, ou déclarer `<queries><package android:name="com.google.android.apps.maps"/></queries>`. À noter aussi : la notification passe des coordonnées `0,0` (golfe de Guinée) si la position est inconnue.

**M8 — « Partage multi-téléphones (Cloud) »** : l'écran annonce une synchronisation Supabase « Infirmières & chefs de service », mais seule une requête de test est faite ; rien n'est partagé. Avec plusieurs téléphones, chaque appareil a son propre état d'alerte et ses propres acquittements.

---

## 🟡 Mineur, ergonomie et esthétique

- **Mode sombre** : couleurs claires codées en dur (`0xFFFFF5F5`, `0xFFFFF0F0`, `0xFFF1F5F9`, `0xFFE2E8F0`) → texte blanc sur fond clair, illisible, dans les cartes résident hors zone et le journal d'alertes.
- **Valeurs propres à un établissement codées en dur** : « MAS l'Épeau », adresse de Bouguenais, titre de la notification permanente, e-mail `contact@mas-epeau.fr` ; le ViewModel **renomme de force** certaines zones en « MAS l'Épeau » au démarrage.
- **Seuils de batterie incohérents** : 20 % (carte), 25 % (filtre), « < 20 % » (texte).
- **Suppression d'un résident sans confirmation**, à côté de « Modifier ».
- **« Simuler sortie »** est dans le menu de production et crée une **vraie** alerte `EXIT_ZONE` dans le journal (non distinguée d'un incident réel).
- **Journal d'alertes** : acquittant toujours « Équipe Soins », pas d'heure d'acquittement, pas de filtre ni d'export, croissance illimitée ; les retours en zone (`ENTER_ZONE`) non acquittés gonflent le badge rouge.
- Les résidents dont le suivi est **désactivé** sont quand même comptés « en sécurité » / « hors zone ».
- La notification dit « à X m **du centre** » même en zone polygonale (c'est la distance au bord).
- Le logo PNG de 193 Ko est décodé à **chaque** mise à jour de la notification (toutes les 15 s) ; `ic_app_logo.png` est un doublon.
- L'état « optimisation batterie » de l'écran Paramètres n'est pas rafraîchi au retour des réglages Android.
- Navigation par `remember` : l'onglet courant est perdu à la rotation (utiliser `rememberSaveable`).
- Beaucoup de textes en 10–11 sp : peu lisibles pour un soignant en mouvement ; vérifier aussi les contrastes (vert `#10B981` sur fond clair).

## Technique / projet

- **Android Lint** (`./gradlew lintDebug`) : 0 erreur, 94 avertissements, dont les utiles : `QueryPermissionsNeeded` (→ M9), `Wakelock` (→ M4), `WakelockTimeout` (wakelock du service sans délai : la batterie est sollicitée en permanence — acceptable sur un téléphone de garde branché, à documenter), `BatteryLife` (la demande directe d'exemption batterie est encadrée par la politique Play Store, à justifier si publication), `DefaultLocale`, 27 ressources inutilisées, 22 dépendances non à jour.

- Dépendances **inutilisées** : Firebase AI, App Check, Coil, Navigation Compose (+ `metadata.json` et `.env.example` Gemini hérités d'AI Studio). Elles alourdissent l'APK.
- `isMinifyEnabled = false` en release ; `applicationId = com.aistudio.securiresident.wnktcz`, `namespace = com.example` : à renommer avant toute diffusion (le changement d'`applicationId` = nouvelle app).
- Un seul `OkHttpClient` / `Moshi` devrait être partagé (4 clients créés aujourd'hui).
- En-têtes Weenect copiés 8 fois → un `Interceptor`.
- `ExampleRobolectricTest` était cassé (attendait « My Application ») et la CI ne lançait aucun test.

---

## Tests

Avant l'audit : 6 tests, dont 1 en échec (`ExampleRobolectricTest` attendait « My Application ») ; la CI ne lançait pas les tests.

Après l'audit : **41 tests — 35 réussis, 6 ignorés volontairement, 0 échec** ; l'APK debug compile.

Ajouté :

| Fichier | Ce qui est testé |
|---|---|
| `GeoUtilsTest` | Haversine, cap/points cardinaux, point dans polygone (convexe, concave, orientation, dégénéré), distance au bord, centre, `formatTimeAgo` |
| `data/model/FacilityZoneTest` | Encodage/décodage du polygone, JSON invalide, zone par défaut |
| `util/ConfigBackupManagerTest` | Aller-retour export → import, JSON invalide, valeurs par défaut |
| `data/remote/WeenectRepositoryTest` | Bout en bout avec Room + faux serveur Weenect : dedans/dehors, alerte de sortie unique, retour en zone, jeton expiré → reconnexion, préfixe `JWT`, zone polygonale, zone désactivée, erreur serveur, liste des balises, commande « sonner » |
| `ExampleRobolectricTest` | Corrigé |

Les tests marqués `@Ignore("AUDIT …")` décrivent le comportement **attendu** pour les défauts C2, C3, C4 et M1 : ils échouent avec le code actuel (vérifié). Il suffit de retirer le `@Ignore` une fois le défaut corrigé pour avoir un test de non-régression.

La CI (`.github/workflows/build-apk.yml`) lance désormais `testDebugUnitTest` avant de construire l'APK.

Changement de code de production : uniquement l'ajout d'un paramètre `baseUrl` (valeur par défaut inchangée) au constructeur de `WeenectRepository`, pour pouvoir le tester.

Lancer les tests : `./gradlew testDebugUnitTest`

---

## Idées d'amélioration (fonctionnelles)

1. **Écran « État de la surveillance »** : dernière synchro réussie, balises muettes, autorisations Android (notifications, plein écran, batterie, alarmes), avec des pastilles vert/orange/rouge — à consulter à chaque prise de poste.
2. **Check-list de prise de poste** : test de sonnerie + vérification des balises en un écran, horodaté dans le journal.
3. **Statut « Résident accompagné / en sortie autorisée »** avec durée (ex. sortie famille 2 h) au lieu de désactiver le suivi à la main — et réactivation automatique.
4. **Plages horaires** de surveillance renforcée (nuit) et zones multiples (bâtiment, jardin, parking).
5. **Escalade** : rappel sonore puis notification à un second téléphone si l'alerte n'est pas acquittée en N minutes.
6. **Vraie synchronisation multi-téléphones** (Supabase Realtime) : une alerte acquittée sur un téléphone l'est sur tous, avec le nom du soignant.
7. **Historique de trajet** sur la carte (l'API renvoie les positions sur une période) pour savoir par où le résident est parti.
8. **Photo du résident** dans la notification et la pop-up d'alarme (le champ `photoUri` existe déjà) — utile pour un intérimaire qui ne connaît pas le résident.
9. **Export PDF/CSV du journal** pour les rapports d'événements indésirables.
10. **Code PIN / verrouillage** de l'écran Paramètres pour éviter les modifications involontaires.

---

# Corrections apportées (version 2.0)

_Tous les points de l'audit ont été traités, **sauf la synchronisation Supabase** (points 15 et 46), laissée en l'état à ta demande : l'écran Supabase est inchangé._

## Points critiques

| # | Point | Ce qui a été fait |
|---|---|---|
| 1 | Panne de synchro invisible | Le service publie son état réel (`MonitoringHealth`) : en-tête vert / orange / rouge sur le tableau de bord, notification permanente qui dit la vérité (« ⚠️ aucune balise joignable »), avertissement sonore « Surveillance dégradée » après 3 cycles en échec. **L'alarme n'est plus jamais coupée par une erreur réseau** : elle ne s'arrête que si la balise confirme le retour ou si un soignant agit. |
| 2 | Cas inconnus affichés « en sécurité » | Nouveau statut **« Position inconnue »** (orange). Mauvais mot de passe, balise absente, position vide → erreur enregistrée et affichée (« Identifiants Weenect refusés », « Aucune balise associée »…). Aucune coordonnée n'est jamais inventée. On ne peut plus enregistrer un résident « suivi » sans compte ni balise. |
| 3 | Position ancienne affichée récente | L'heure affichée est celle du **fix de la balise** (`date_tracker`). Statut « Signal ancien » au-delà du délai réglable (15 min par défaut) + avertissement « Balise muette ». |
| 4 | Mots de passe exposés | Comptes Weenect séparés, mots de passe **chiffrés (AES-256-GCM, Android Keystore)**. Migration automatique des anciens mots de passe en clair. Logs réseau uniquement en debug, sans corps ni jeton. Sauvegarde Google et transfert d'appareil désactivés. Export : sans mot de passe, ou chiffré par un code (PBKDF2 + AES-GCM). |
| 5 | Vraie alarme ≠ alarme de test | Une seule source de vérité (`SoundAlertManager.activeAlarms`) : la pop-up s'affiche pour **toute** alarme réelle. Les réglages son / vibration de la zone sont respectés (sans son, la vibration reste toujours active). |
| 6 | « X » qui lève tout / téléportation | Le « X » est remplacé par un simple repli. « Je m'en occupe » et « Retrouvé » changent l'**état de l'alerte**, jamais la position : la balise confirme ensuite le retour. |

## Points majeurs

| # | Point | Ce qui a été fait |
|---|---|---|
| 7 | Commandes balise | Code HTTP vérifié, reconnexion automatique sur jeton expiré, message d'échec explicite. |
| 8 | Synchros concurrentes | Un verrou par résident, mises à jour partielles (la fiche et le suivi ne s'écrasent plus). Testé : 4 synchros simultanées = 1 seule alarme. |
| 9 | Service « location » | Type `specialUse` uniquement ; permissions inutiles retirées (`DATA_SYNC`, `LOCATION`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`). Wakelock renouvelé par cycle avec délai. |
| 10 | Réveil de l'écran | Vérification de l'autorisation « plein écran » (Android 14+) avec bouton « Corriger » dans l'écran État. Suppression du wakelock d'écran inopérant. |
| 11 | Migrations destructives | Schéma exporté (`app/schemas`), migration v3 → v4 écrite et **testée** ; plus aucune suppression silencieuse. |
| 12 | Pas de rappel / batterie / hors ligne | Rappel sonore si la sortie n'est pas prise en charge après N min (réglable). Alertes « Batterie faible » (20 %) et « Balise muette » / « Connexion Weenect impossible », une seule fois puis réarmées. |
| 13 | Faux positifs en bordure | Sortie confirmée seulement si le fix est hors zone malgré sa précision, ou par un 2ᵉ fix, ou après 60 s. Un nouveau fix est demandé immédiatement à la balise. |
| 14 | « Guider » dans le navigateur | Bloc `<queries>` + `MapsNavigator` : appli Google Maps (guidage piéton), sinon toute appli de cartes, sinon navigateur. Plus de coordonnées 0,0. |

## Plusieurs résidents

| # | Point | Ce qui a été fait |
|---|---|---|
| 16 | Tri par urgence | Hors zone (le plus ancien d'abord) → inconnu → signal ancien → batterie faible → en sécurité → en pause → désactivé ; puis niveau de risque, puis nom. |
| 17 | Vue compacte / grille | 3 modes : cartes détaillées, liste compacte, grille de tuiles colorées (mémorisé). |
| 18 | Filtres / recherche | Filtres « Hors zone », « Sans position fiable », « Batterie faible », « En sécurité », « En pause / non suivis » avec compteurs ; recherche toujours visible (nom, chambre, unité) ; seuil de batterie unique. |
| 19 | Résidents désactivés | Section séparée « En pause / suivi désactivé », exclus des compteurs d'alerte. |
| 20 | Pop-up multi-résidents | Liste toutes les alarmes en cours, avec photo, chambre, distance, « depuis X min » et boutons par résident. |
| 21 | Bandeau compact | Trié par heure de sortie, « Sorti(e) depuis X min », repliable. |
| 22 | Couper le son | Par résident (« Alarme de Jeanne coupée — 1 autre alarme en cours ») ou « Couper tout ». |
| 23 | Notifications regroupées | Groupe + notification de synthèse (« 3 résidents hors zone »). |
| 24 | « Je m'en occupe » | Bouton dans la pop-up, le bandeau, la carte et la notification ; nom du soignant affiché à toute l'équipe sur ce téléphone et tracé dans le journal. |
| 25 | Carte : initiales / regroupement | Marqueurs avec photo ou initiales et anneau de couleur du statut ; regroupement des marqueurs proches (sauf hors zone). |
| 26 | Carte : pas de position inventée | Les résidents sans position ne sont plus dessinés (liste « sans position » à part) ; résidents désactivés masqués. |
| 27 | Carte : sélection | Résident sélectionné mis en évidence, bouton « hors zone suivant », bouton « tout afficher ». |
| 28 | Comptes Weenect | Écran de gestion des comptes ; dans la fiche, choix du compte puis de la balise dans la liste ; avertissement si la balise est déjà associée. |
| 29 | Sortie accompagnée | Pour un résident (menu) ou plusieurs (appui long → sélection) ; durée au choix, motif ; reprise automatique et alarme si le résident n'est pas rentré. |
| 30 | Unité / risque | Champs « Unité / étage » et « Niveau de vigilance » ; regroupement par unité ; tri par risque. |
| 31 | Interrogation parallèle | 4 balises à la fois. |

## Ergonomie, esthétique, technique, nouvelles fonctions

| # | Point | Ce qui a été fait |
|---|---|---|
| 32 | Mode sombre | Couleurs de statut adaptées au thème + couleurs « pleines » pour les fonds avec texte blanc (vérifié sur captures). |
| 33 | Valeurs en dur | Plus de « MAS l'Épeau », d'adresse ni d'e-mail codés en dur ; plus de renommage forcé de la zone. |
| 34 | Suppression | Confirmation obligatoire. |
| 35 | Mode exercice | Les simulations ne modifient plus le résident, sont marquées « EXERCICE » et ne comptent pas dans le badge. |
| 36 | Journal | Nom et heure d'acquittement, filtres, confirmation de « Tout acquitter », purge (manuelle à 90 j, automatique à 180 j) ; retours en zone hors badge. |
| 37 | Photo | Choix d'une photo (réduite et stockée dans l'app), affichée partout et dans la notification. |
| 38 | Initiales | « Jean Dupont » → « JD ». |
| 39 | Lisibilité | Textes à 12 sp minimum, boutons ≥ 44–48 dp. |
| 40 | Petits défauts | État des autorisations rafraîchi au retour des réglages ; onglet conservé à la rotation ; « distance à la limite » en zone polygonale. |
| 41 | Ménage | Firebase, App Check, Coil, Navigation, secrets/google-services, Gemini et le logo en double retirés. |
| 42 | Release | Minification R8 active (APK release ≈ 2 Mo) ; package renommé **`fr.alerteresidents`**. |
| 43 | HTTP | Un client OkHttp partagé, en-têtes Weenect centralisés, Moshi sans réflexion. |
| 44 | État de la surveillance | Nouvel écran : service, réglages du téléphone (avec « Corriger »), état de chaque balise, **prise de poste** tracée dans le journal. |
| 45 | Plages horaires / zones | Zones annexes (jardin, parking…) autorisées le jour et/ou la nuit ; mode nuit avec intervalle de vérification dédié. |
| 47 | Trajet | Trajet des 2 h / 6 h dans la carte. |
| 48 | Export journal | PDF et CSV (Excel) de l'affichage courant. |
| 49 | Code PIN | Protection des Paramètres (empreinte salée), reverrouillage après 2 min en arrière-plan. |

## ⚠️ À savoir avant d'installer la version 2.0

- **Nouveau nom de package (`fr.alerteresidents`)** : Android l'installe comme une **nouvelle application**, à côté de l'ancienne. Pour récupérer la configuration : dans l'ancienne app, *Paramètres › Exporter* ; dans la nouvelle, *Importer* le fichier (l'ancien format avec mots de passe en clair est reconnu et ses mots de passe sont chiffrés à l'import), puis désinstaller l'ancienne. Si tu préfères garder l'ancien identifiant, il suffit de remettre `applicationId` dans `app/build.gradle.kts`.
- Après installation : ouvrir **État de la surveillance** et corriger ce qui est en rouge (notifications, plein écran, batterie).
- Non testé sur un vrai téléphone (pas d'émulateur disponible ici) : les écrans ont été rendus et testés sous Robolectric, mais un test réel (alarme écran verrouillé, sortie de zone avec une vraie balise) reste indispensable avant usage en production.

## Tests (version 2.0)

**85 tests, 0 échec** (`./gradlew testDebugUnitTest`, ~1 min 30) :

| Fichier | Contenu |
|---|---|
| `WeenectRepositoryTest` (31) | Moteur complet avec Room + faux serveur Weenect : sorties, retours, échecs (mot de passe, balise absente, coordonnées vides, erreur serveur), fraîcheur du fix, hystérésis, rappels, prise en charge, « retrouvé », sortie accompagnée, batterie, concurrence, chiffrement, migration des identifiants, commandes, exercices, zones annexes / nuit, trajet |
| `ZoneEvaluatorTest`, `ZoneTransitionTest`, `ResidentStatusResolverTest` | Logique pure : zones, transitions, statuts et tri par urgence |
| `MigrationTest` | Migration de base v3 → v4 sans perte |
| `ConfigBackupManagerTest`, `PassphraseCryptoTest`, `DateParsingTest`, `GeoUtilsTest`, `FacilityZoneTest` | Export/import (v1 et v2), chiffrement, PIN, dates, géométrie |
| `UiSmokeTest` / `UiSmokeDarkTest` | Rendu réel de 11 écrans et dialogues avec 8 résidents dans tous les états, en clair et en sombre ; captures dans `app/build/ui-screenshots/` (aperçu dans `docs/captures/`) |

Android Lint : 0 erreur.
