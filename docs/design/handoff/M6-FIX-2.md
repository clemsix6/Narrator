# Passation M6-FIX-2 : le tableau de bord (dernier lot)

Lot de corrections de la relecture `reviews/M6_REVIEW_1.md` : constats 2, 6 (décision D1), 9 et le trou 2 du constat 11,
plus une décision du propriétaire (le tableau de bord devient optionnel) et le retrait de `notBuiltYet`. Passations lues :
M6-10, M6-11, M6-12, et la note « Pour M6-FIX-2 » de `reviews/M6_REVIEW_2.md`.

**En cas de contradiction avec `M6.md`, le code et cette passation font foi.**

Branche `worktree-agent-abeb9c4d2248802ef`.

## Ce qui est corrigé

| Point | Fichiers | Ce qui change |
|---|---|---|
| Constat 2 : le tour de lecture rendu trop tôt | `web/Routes.kt`, `journal/ReadWatch.kt` (**nouveau**), `journal/DuckJournal.kt` | `PageContext.inTurn` (et donc `read`) ne rend le permis du sémaphore que lorsque le travail est fini **et** que la requête qu'il a lancée a vraiment fini, une requête expirée comprise ; une requête dont l'appelant est parti ne démarre jamais |
| Constat 6, D1 : code de connexion à usage unique | `web/Dashboard.kt`, `web/Routes.kt`, `command/DashboardCommands.kt` | `/narrator dashboard` donne un lien `…/login?key=<code>` : 128 bits, 10 minutes, consommé à la première connexion ; le cookie garde le jeton de session, qu'aucune URL ne montre plus |
| Constat 9 : trous de garde de `Html` | `web/Html.kt` | `\`, caractères de contrôle et espaces refusés dans une URL d'attribut ; `formaction` vérifié ; nom d'attribut comparé sans casse ; `<meta>` réservé à l'en-tête de `document` (`Html.head`) ; `textarea` gardé |
| Décision du propriétaire : tableau de bord optionnel | `config/NarratorConfig.kt`, `web/DashboardModule.kt`, `command/DashboardCommands.kt`, `journal/DuckDatabase.kt`, `journal/JournalModule.kt`, `NarratorRuntime.kt` | `dashboard.enabled = false` par défaut ; désactivé : aucun port, aucun fil, **deux** connexions de lecture ; `/narrator dashboard` dit comment l'activer |
| `notBuiltYet` | `web/Routes.kt` | retiré (plus aucune page ne s'en servait) |

## Le tour de lecture (constat 2)

- **`ReadWatch`** (`journal/ReadWatch.kt`) : un élément du contexte de la coroutine. `DuckJournal.read` le lit
  (`currentCoroutineContext()[ReadWatch]`), lui demande `start()` une fois la connexion obtenue et juste avant la
  requête (un `false` : la requête ne tourne pas, la connexion est rendue), puis appelle `end()` une fois la requête
  finie **et la connexion rendue au pool**.
- **`Routes.PageContext.Turn`** (privé) l'implémente : `inTurn` prend le permis, lance `runBlocking(turn) { work() }`,
  puis `turn.close()`. Le permis revient au plus tard des deux : la fin du travail et la fin de la requête en cours.
  Après `close()`, `start()` refuse (personne n'attend plus le résultat).
- **Pourquoi un élément de contexte** plutôt qu'une enveloppe de la lambda dans `read`, comme le proposait la relecture :
  `inTurn` sert à des services qui prennent **leur propre** connexion (`DuckPlayerSheets`, `SqlRunner`) ; seul le
  Journal sait quand leur requête finit vraiment. Leur `journal.read` est appelé dans la coroutine de `inTurn` (vérifié :
  aucun `launch` en route), donc l'élément arrive jusqu'au Journal. `read` passe par `inTurn` : un seul mécanisme.
- **Un Journal qui ignore l'élément** (`DisabledJournal`, les faux Journaux des tests qui exécutent la requête sur le
  fil appelant) : le tour finit quand le travail rend la main, ce qui est alors exact. Les enveloppes de test qui
  délèguent à un vrai `DuckJournal` (`DashboardPagesTest`, `DashboardPrivacyTest`, `TracePagesTest`) le transmettent.
- **Écart** : un tour ne lance **qu'une requête à la fois**. Une seconde requête du même tour pendant que la première
  tourne est refusée (`JournalTimeoutException`). Aucune page ne le fait (une page fait une seule `read`, ou un service
  dans `inTurn` qui fait une seule lecture) ; la règle garantit l'invariant « jamais deux lectures du tableau de bord à
  la fois » même si une page le tentait.
- **Option de la relecture, faite** : `DuckJournal.read` ne lance pas une requête dont l'appelant est parti pendant
  qu'elle attendait sa connexion (`result.isDone` contrôlé après le `poll`). Utile à tous les appelants.
- **À savoir** (relecture, partie 2) : une page qui fait plusieurs requêtes dans une seule lecture (chacune bornée à 5 s,
  7 s pour `/turns/<id>`) peut garder sa connexion jusqu'à environ 35 s après le départ de l'appelant. Le tableau de
  bord répond alors 503 (« the Journal is busy ») pendant ce temps, au lieu de prendre une seconde connexion : c'est
  voulu. Interrompre la requête à l'expiration (`DuckDBConnection`) changerait `DuckJournal.read` pour tous : pas fait.

## Le code de connexion (D1)

- **`DashboardToken(clock, random)`** : le jeton de session (`value`, `matches`, 32 octets, inchangé) et les codes
  (`newCode()`, `redeem(code)`). Un code : 16 octets en hexadécimal (32 caractères), gardé **sous forme d'empreinte
  SHA-256**, valable `CODE_TTL_MS` (10 min, horloge du runtime), consommé par `redeem`. Au plus `MAX_CODES` (16) codes
  inutilisés : le plus ancien tombe. `reset()` tire un nouveau jeton **et** annule les codes. Comparaisons en temps
  constant (`MessageDigest.isEqual` sur les empreintes).
- **`Dashboard.loginUrl()` devient `newLoginUrl()`** : chaque appel tire un code, le nom le dit. `DashboardServer.token`
  passe `internal` (les tests lisent le jeton de session).
- **`/login?key=<code>`** : `redeem`, puis le cookie `narrator2=<jeton>; HttpOnly; SameSite=Strict; Path=/` et 303
  vers `/` ; un code faux, servi ou expiré : 403 « This link is wrong, already used or expired ». Le jeton de session
  n'est pas un code (403).
- **Réponse de la commande** : au joueur, `[Sign in]` et « a link for one sign-in, within 10 minutes: keep it to
  yourself » ; à la console, le lien et « This link works once, within 10 minutes: run /narrator dashboard again for
  another. » Le conseil de `reset` après un usage depuis la console est retiré : le lien recopié dans `latest.log` est
  mort une fois servi.
- `HEAD /login?key=<code>` consomme aussi le code (même porte que `GET`) : accepté.

## `Html` (constat 9)

- **URL d'attribut** (`Html.insideUrl`) : commence par `/` sans `//`, ou par `#`, et ne contient ni `\`, ni caractère de
  contrôle, ni espace, ni DEL. **Écart, plus large que le rapport** : les navigateurs retirent aussi les tabulations et
  les sauts de ligne d'une URL (`/<tab>/evil.example` devient `//evil.example`) ; `link()` encode tous ces caractères,
  donc aucun lien légitime ne les porte.
- `URL_ATTRIBUTES` = `href`, `action`, `formaction`, `src`, comparés **en minuscules** (`HREF` passait).
- **`<meta>` n'est plus dans `Html.ELEMENTS`** : `element("meta", …)` échoue. L'en-tête du document est écrit par
  `Html.head(title, refresh)`, où `refresh` est un `Int` (aucune URL possible). `textarea` reste (page SQL).
- `element` vérifie le nom puis écrit par `tag` (privé), qui garde toutes les vérifications d'attributs.
- **Pas fait** (option du constat 9) : borner la file des requêtes (`ArrayBlockingQueue`). Ce n'était pas demandé, et
  le comportement du `HTTP-Dispatcher` du JDK face à un refus d'exécution n'a pas été vérifié.

## Tableau de bord optionnel

- `DashboardConfig.enabled = false` par défaut. `createDashboard` rend alors `DisabledDashboard(SWITCHED_OFF)`
  (`"dashboard.enabled is false"`, constante de `DisabledDashboard`) : aucun `HttpServer`, aucun fil `narrator2-web-*`.
- `/narrator dashboard`, désactivé par la configuration : « no dashboard: dashboard.enabled is false. » puis « The
  dashboard is optional and off by default. To turn it on, set "dashboard": { "enabled": true } in
  config/narrator2/narrator2.json, then run /narrator restart (or restart the server). » Pour une autre raison (port
  pris…) : la raison, puis « dashboard.enabled and dashboard.port (…) are read when the Narrator starts: /narrator
  restart applies a change. » Il n'existe pas de `/narrator help`.
- **La troisième connexion de lecture n'est ouverte qu'avec le tableau de bord** : `DuckDatabase.Options.readers` vaut
  **2** par défaut, et `Options.of(config, dashboard)` rend 3 avec `dashboard = true`. `openJournalOrDisable(…,
  dashboard)` le transmet (paramètre par défaut `false` : les appels des tests ne changent pas), et
  `NarratorRuntime.prepare` passe `config.dashboard.enabled` (une ligne). Les réglages viennent de la même lecture que
  ceux de `createDashboard` (au démarrage, comme à un `/narrator restart`, qui relit la configuration). La ligne
  `opened …` du Journal dit désormais `· N read connections ·`. Le nombre de fils `narrator2-sql-*` suit (un par
  connexion).
- Un `narrator2.json` écrit **avant** ce lot avec toutes les valeurs par défaut porte `"enabled": true` et garde le
  tableau de bord (le fichier appartient à l'opérateur). Celui de `TheServer/` n'a pas de section `dashboard` : il prend
  le nouveau défaut.
- **GameTests** : `build.gradle.kts` et `RestartGameTests` forcent toujours `dashboard.enabled = false`. C'est désormais
  le défaut ; je les garde, comme protection explicite si le défaut changeait (et ce ne sont pas mes fichiers).

## Fichiers touchés hors de la liste du lot

- `web/DashboardModule.kt` : la constante `DisabledDashboard.SWITCHED_OFF` et le KDoc.
- `journal/JournalModule.kt`, `journal/DuckDatabase.kt`, `NarratorRuntime.kt` (une ligne de `prepare`) : le choix de
  `readers` (« l'endroit où `readers` est choisi »).
- `journal/ReadWatch.kt` : nouveau.
- Tests : `ConfigValidationTest` (le défaut, et la fusion clé par clé qui pose désormais `enabled: true`),
  `DuckDatabaseTest` (2 connexions sans tableau de bord), GameTest `DashboardServerGameTests` (`newLoginUrl`, la réponse
  dit comment l'activer).

## Ce que les tâches suivantes doivent savoir

- **M6-FIX-6** (qui touche aussi `journal/DuckJournal.kt` et `NarratorRuntime.kt`) : mes changements y sont courts — dans
  `DuckJournal.read`, la lecture de `ReadWatch` et le bloc juste après le `poll` de la connexion, plus `watch?.end()`
  dans le `finally` ; dans `NarratorRuntime.prepare`, l'appel à `openJournalOrDisable(…, dashboard = …)`. Fusion
  attendue sans difficulté.
- **M6-17** : le tableau de bord du runtime est **désactivé par défaut** ; l'assemblage qui le teste doit l'activer
  (`dashboard = (true, 0)`) ou appeler `createDashboard` comme `DashboardServerGameTests`. Le lien vient de
  `newLoginUrl()` et **ne sert qu'une fois**. `notBuiltYet` est déjà retiré. Le `runServer` réel avec tableau de bord
  demande `"dashboard": { "enabled": true }` dans `config/narrator2/narrator2.json`.
- **Une page** : toujours **une** `context.read` (ou un service dans `context.inTurn`) ; deux lectures parallèles dans
  un même tour sont refusées.
- **Le propriétaire** : pour essayer le tableau de bord, l'activer dans `narrator2.json`, puis `/narrator restart`.

## Vérifications

- **Tests nouveaux ou étendus** :
  - `DashboardServerTest` (14 → 20) : une lecture qui expire garde le tour jusqu'à la vraie fin de sa requête (requête
    de 6 s, page partie à 5 s, la page suivante attend puis passe : jamais deux lectures à la fois) ; même chose pour un
    service dans `inTurn` (lecture de 500 ms, requête de 2,5 s) ; un tour, une requête à la fois ; trois connexions de
    lecture seulement avec le tableau de bord (`Options`, et `openJournalOrDisable` sur disque, d'après la ligne
    `opened`) ; le tableau de bord désactivé par défaut ; codes tirés au hasard (32 caractères hexadécimaux), les 16
    derniers gardés, un code faux, tronqué, allongé ou le jeton refusés, consommé une fois ; un lien sert une fois (le
    navigateur reste connecté), encore bon à 9 min 59 s, mort à 10 min ; `reset` annule les codes non servis ; le lien ne
    contient jamais le jeton de session, le cookie jamais le code ; ni le jeton ni un code dans le journal.
  - `HtmlTest` (9 → 10) : `/\…`, `\/…`, tabulation, saut de ligne, retour chariot, espace, `HREF`, `formaction` et
    `formAction` externes refusés, `formaction` interne accepté ; `<meta>` refusé à une page (refresh vers une URL,
    charset) ; `textarea` échappé.
  - `DashboardCommandsTest` (3 → 4) : le code hors du texte du chat, le clic, la console (une fois, 10 minutes, plus de
    conseil de `reset`), désactivé (comment l'activer), un échec de démarrage (sa raison, `/narrator restart`).
- **Rouge avant, vert après** : avec l'ancien `inTurn` (permis rendu dans le `finally`) et l'ancien `Html.kt` remis le
  temps d'un essai, les nouveaux tests échouent : « never two reads of the dashboard at once, even after a timeout
  ==> expected: <1> but was: <2> » (lecture expirée, et service dans `inTurn`), « a second query of the turn … is
  refused … actual <null> », et `/\evil.example/` accepté par `Html`. Avec le correctif : verts.
- **Build complet** (`build --continue`, après `git merge main` jusqu'à `3d5c7f9`, M6-08 et la relecture M6 partie 2
  compris, sans conflit) : **vert**, **2 225 tests unitaires** (0 échec, 0 ignoré) et **« All 398 required tests
  passed »**. Aucun test de temps instable n'a échoué.
- **Non fait** : la vérification dans un vrai navigateur, et un `runServer` réel avec le tableau de bord activé (M6-17).
  Aucun appel réel à Claude.
