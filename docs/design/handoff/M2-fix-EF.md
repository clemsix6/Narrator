# Passation M2-fix-EF : coût, threads et observabilité

Branche `worktree-agent-ae289bfd69fa7375f`. Ce lot corrige les constats **14, 17, 18** de `docs/design/reviews/M2_REVIEW.md`, et le constat **26** de l'addendum (lot EF étendu). Voici ce qui est en place, les écarts, les fichiers touchés hors du lot et ce que les autres lots doivent savoir. **En cas de contradiction, le code fait foi.**

## Ce qui est en place

### Constat 14 : budget de réveils immédiats par joueur

- `Salience` (triage du chat) : un joueur réveille le Directeur **à l'immédiat 6 fois par fenêtre glissante de 10 min** au plus (`Salience.IMMEDIATE_PER_PLAYER`, `IMMEDIATE_WINDOW_MS`). Le chat adressé et les prières partagent ce budget : alterner les deux ne le contourne pas.
- Au-delà, le message est posté en `SOON` (il garde son annotation `(to you …)`) et la décision est `salience throttled`, par exemple : `prayer: fake 0.92 >= 0.60; Steve already woke the Narrator 6 times within 10 minutes: it waits like a salient event`.
- La fenêtre se compte en heure des messages (`Envelope.ts`) ; seuls les réveils accordés comptent.
- **`PrayCommand` n'a pas changé** : le budget vit dans la saillance, seul endroit qui voit à la fois les prières et le chat adressé. Le délai de 10 s entre deux prières reste.
- **Le constat 14 seul ne suffit pas** : un message en `SOON` réveille le Directeur 3 s plus tard, donc une prière toutes les 10 s coûtait encore un tour par prière. C'est le budget global du constat 26 qui borne tout (voir les chiffres plus bas).

### Constat 26 : coût (addendum)

- **(a) Le chat entre joueurs ne réveille plus.** `WakeScheduler.wakeAt` n'a plus la règle « 4ᵉ message ou 45 s » (`CHAT_PILE` et `CHAT_WAIT_MS` supprimés). Un message non adressé reste `NEXT` : il part avec le prochain réveil, battement compris, ou entre deux appels d'outils.
- **(b) Le battement sans Jev vaut ce qui attend.** `WakeScheduler.heartbeatWorth(verdict, waiting)` :
  - l'heuristique ne juge rien (elle dit toujours 1.0) : le battement vaut alors les éléments saillants **qui attendent dans la boîte depuis le dernier tour** (`WakeScheduler.salience`) : 0,125 par ligne de chat entre joueurs (4 réveillent), 0,25 par autre événement `NEXT` (2 réveillent : minerai précieux, coffre, téléportation, dimension…), 1 pour un élément qui réveille seul ; plafond 1, éléments rétrogradés après des refus ignorés ;
  - avec Jev, son verdict compte, **mais jamais en dessous de ce qui attend**, parce que Jev ne lit que le résumé d'activité, pas le chat : sans ce plancher, le chat entre joueurs n'atteindrait jamais le Narrateur avec Jev. **Décision prise par moi, à valider** ;
  - un résumé d'activité vide n'est plus un saut automatique si des éléments saillants attendent : le battement est alors évalué sur eux seuls, sans appeler les réflexes (bloc `<heartbeat>` vide, `<events>` rempli) ;
  - raisons des décisions : `worth 0.50 by 2 salient items waiting`, `worth 0.80 by jev-1.13.0`, `worth 0.50 by 2 salient items waiting (jev-1.13.0 said 0.31)`.
- **(c) Budget global de tours.** Nouveau réglage `director.turnsPerHour` (défaut 30, de 1 à 600, validé). `TurnBudget` (dans `WakeScheduler.kt`) compte les tours **commencés** dans l'heure glissante, tous déclencheurs confondus (`Director.wake` appelle `started`). Une fois le budget atteint :
  - seuls les éléments `IMMEDIATE` réveillent : chat adressé, prières dans le budget de leur auteur, **et les chuchotements d'opérateur** (écart assumé : un opérateur doit pouvoir joindre le Directeur) ;
  - un événement saillant est toujours posté en `SOON`, mais sa décision est `salience throttled` (`death; the budget of 30 turns per hour is spent: it waits for a turn to leave the hour`). Il attend qu'un tour sorte de l'heure (le Directeur se réexamine au moins toutes les 60 s) ou part avec le prochain réveil ;
  - les battements sont sautés avec la décision `salience throttled`, sujet `heartbeat`. La fenêtre d'activité n'est pas consommée : elle s'accumule jusqu'au battement suivant ;
  - les résultats de tâches et les rappels (`SOON`) attendent aussi.
- **Chiffres mesurés en temps virtuel** (`WakeBudgetScenariosTest`) : une prière toutes les 10 s pendant 30 min donne 30 tours dans les 5 premières minutes (6 immédiats, puis un tour `SOON` par prière jusqu'au budget), puis 6 tours par tranche de 10 min (les immédiats de ce joueur) : 13 tours sur les 25 minutes suivantes, dont celui d'un autre joueur qui interpelle le Narrateur. Sans le constat 14, ou sans le 26 (c), c'est 84 tours sur ces 25 minutes. Au régime permanent, un joueur seul coûte au plus 36 tours par heure, et les autres joueurs gardent leurs réveils immédiats.

### Constat 17 : plus de `server.execute` hors du thread serveur

- `ServerGameView` (barre d'action, indicateur « réfléchit », message aux opérateurs) passe par `showOnServer(loop, scope, timeoutMs, block)` (`DirectorModule.kt`) :
  - sur le thread serveur, le bloc s'exécute aussitôt (l'effacement immédiat de T18 est gardé) ;
  - sinon, `onServerThread` dans `services.scope`, lancé en `CoroutineStart.UNDISPATCHED` : le `execute` part avant le retour, donc **l'ordre des affichages est gardé** ;
  - après l'arrêt du serveur (Minecraft exécute alors la tâche sur le thread appelant), le bloc est abandonné (`ServerStoppedException`, log DEBUG) ; au-delà de 5 s sans thread serveur, abandonné aussi (WARN) ; une fois la portée annulée (`SERVER_STOPPING`), les affichages en attente sont abandonnés.

### Constat 18 : relier une mort aux actions du Narrateur

- **Chaque mort** (`AFTER_DEATH`) écrit une décision `death_guard died` (en plus de `counted`, écrite par `DeathGuard` pour une mort du Narrateur). Sa raison, par exemple :
  `minecraft:fall, not the Narrator's (guard not armed), inventory dropped; 1 of 1 Narrator deaths left this played hour; the Narrator acted on Bartok in the last 60 s: apply_effect 20 s before (turn 42)`.
- Son `detail` est le `DeathReport` complet (`guard/DeathReport.kt`, sérialisé en JSON) : `player`, `at` (ms epoch), `cause`, `narrator`, `kept_inventory`, `protected`, `play_time` et `armed_until` (ticks de `PLAY_TIME`, 0 = pas armée), `deaths_left`, `deaths_per_hour`, `actions` (`id`, `at`, `tool`, `turn_id`, `status`, `result` coupé à 200 caractères) ou `actions_unknown`.
- **Comment** (`GuardsImpl.onDeath` → `explain`) :
  - l'état de la garde est lu sur le thread serveur, au moment de la mort ;
  - les actions sont lues dans le Journal hors du thread serveur, **2 s après la mort** (l'outil qui a tué a le temps de rendre son résultat et d'écrire sa ligne), après une barrière : les `tool_calls` d'effet `world`, de statut `ok` ou `unknown`, dont les cibles contiennent le nom exact du joueur, commencés dans les 60 s avant la mort (`DeathReport.actionsOn`) ;
  - Journal indisponible ou lecture en échec : la décision est écrite quand même, avec `actions unknown (raison)`.
- **`/narrator why <joueur>`** : après la réponse sur le dernier message (inchangée), une seconde réponse sur la **dernière mort** (`TraceQueries.lastDeath`, `TraceText.death`) :
  ```
  [Narrator] why: Bartok's last death · 19:35:10, 1m ago
  Cause: minecraft:fall · not the Narrator's · inventory dropped          (en rouge si le Narrateur a agi juste avant)
  Death guard: ran out 12 s of play before · 1 of 1 Narrator deaths left this played hour
  The Narrator acted on Bartok in the 60 s before:
    -40 s apply_effect (turn #42) ok: ok: levitation II on Bartok for 30 s
  More: /narrator why turn 42
  ```
  Un joueur qui n'a jamais écrit mais qui est mort n'a que la seconde réponse. Un message `throttled` en attente affiche `Not told to the Director yet: it waits: the Narrator's wake-ups are throttled (see Salience).`
- Les décisions `guard allowed` sont journalisées au niveau **INFO** (elles étaient en DEBUG).

## Fichiers touchés hors du lot

Ajouts, sauf mention. **Pour la fusion avec le lot I**, qui possède `Director.kt` et `Inbox.kt` :

| Fichier | Changement |
|---|---|
| `director/Director.kt` | champ `budget` ; `Salience(…, budget)` dans `start()` ; argument `budget.spent(now)` de `WakeScheduler.wakeAt` dans `step()` ; `budget.started(now)` avant `Turn(…).run` dans `wake()` ; **`heartbeat()` réécrite** (budget, éléments en attente, `heartbeatWorth`) ; une phrase de KDoc |
| `config/NarratorConfig.kt` | champ `DirectorConfig.turnsPerHour` et sa validation (le lot CD possède la section `observer` : pas de chevauchement) |
| `trace/TraceQueries.kt` | `lastDeath` et `DeathLine` |
| `command/TraceCommands.kt` | `whyPlayer` lit aussi la dernière mort ; `TraceText.death` ; un cas `throttled` dans `waiting` |
| `guard/GuardsImpl.kt` | hors de `onDeath` : `explain` (appelée par `onDeath`), trois constantes, et le niveau de `guard allowed` dans `hostile` (décision du constat 18) |
| tests | `DirectorHarness` (paramètre `turnsPerHour`), `DirectorScenariosTest` (le test du moment calme reposait sur la règle des 45 s : il utilise maintenant une mort, même propriété), `ConfigValidationTest` ; `fabric.mod.json` du mod de test (une ligne) |
| `gametest/DirectorEndToEndTests.kt` (T22) | `calls >= 3` devient `calls >= 2` à l'étape `status` : le tour du salut fait 2 appels, le 3ᵉ venait d'un événement hérité des lots précédents. **L'ordre des lots de GameTests est aléatoire** (regroupement par environnement dans une table de hachage) : quand le lot `director` passe en premier, il n'y a que 2 appels et le test échouait, avec ou sans ce lot. Vu 2 fois sur 5 lancements |

Nouveaux fichiers : `guard/DeathReport.kt`, tests `director/WakeBudgetScenariosTest.kt`, `director/ShowOnServerTest.kt`, `guard/DeathReportTest.kt`, GameTests `DeathTraceGameTests.kt`.

## Pour les autres lots

- **Lot I** : la KDoc d'`Item.isUnaddressedChat` (`Inbox.kt`) dit encore « it waits for company » : ce n'est plus vrai. `Director.triggerOf` garde son cas `isUnaddressedChat` en dernier ; il ne sert plus qu'au cas où un tel élément serait seul au réveil (impossible aujourd'hui). La boîte bornée du constat 22 doit garder les éléments `SOON` retenus par le budget (ils attendent jusqu'à une heure).
- **Lot G** (`director.md`) : le battement peut maintenant arriver avec un bloc `<heartbeat>` vide, quand il est réveillé par du chat entre joueurs ou des événements en attente ; la phrase « si les joueurs ont été actifs » reste vraie à peu près. Le prompt ne connaît pas le budget de tours (inutile qu'il le connaisse).
- **Superviseur** : `/narrator status` ne montre pas le budget de tours (`DirectorStatus` est un contrat de T18) ; les décisions `salience throttled` le montrent. Le design (`M1_M2.md` §12.3 et l'exemple de configuration) décrit encore la règle des 45 s et n'a pas `turnsPerHour` : cette passation fait foi.
- **T22 (recette)** : un test de bout en bout qui compterait sur un réveil par le chat entre joueurs, ou sur un battement heuristique toujours accepté, doit changer : le chat entre joueurs attend un battement, et un battement sans Jev ne réveille que si des événements saillants attendent.

## Limites

- Le budget de tours est en mémoire : un redémarrage le remet à zéro, comme les budgets par joueur.
- Pendant un budget épuisé, le Directeur est `IDLE` (pas `WAITING`) même si des événements attendent, et il les reprend au plus 60 s après qu'un tour a quitté l'heure (`RECHECK_MS`).
- Un joueur qui dépasse son budget avant que le budget global soit épuisé réveille encore le Directeur par des tours `SOON` (un toutes les 10 s au plus, groupés) : c'est le budget global qui le borne. Les 30 premiers tours d'une heure peuvent donc tous être les siens ; les autres joueurs gardent leurs réveils immédiats.
- La décision `death_guard died` arrive environ 2 s après la mort : un `/narrator why` lancé juste après ne la voit pas encore. Si le serveur s'arrête dans ces 2 s, elle est perdue. Une action dont l'outil a mis plus de 2 s à rendre son résultat après la mort peut manquer.
- Les actions sont reliées par le **nom** du joueur (la colonne `targets` de `tool_calls`) ; un joueur renommé entre l'action et la mort n'est pas relié. Seuls les outils qui déclarent leurs cibles sont vus (`run_command` n'en déclare pas).
- Même classe de défaut que le constat 17, **hors de mon lot, non corrigée** : `GuardsImpl.armDeathGuard` (`server.execute { armDeathGuard(…) }` hors du thread serveur ; après l'arrêt, Minecraft l'exécute sur le thread appelant, qui rappelle `execute`… récursion sans fin) et `command/Replies.kt:68` (`runtime.server.execute { … sendSuccess … }`).

## Vérifications

- `gradlew build` après fusion de `main` (lots CD et J, T22 compris) : **680 tests unitaires et 145 GameTests** verts, le lot `director` passé en premier ; `runGameTest` relancé 3 fois de plus avant la fusion du lot J, dans les deux ordres : vert.
- Tests ajoutés, tous en temps virtuel pour le Directeur :
  - `WakeSchedulerTest` : chat entre joueurs jamais réveillant, budget (seuls les immédiats réveillent), `TurnBudget` sur l'heure glissante et relu à chaque fois, valeur du battement avec l'heuristique et avec Jev ;
  - `SalienceTest` : 6 réveils immédiats par joueur sur 10 min, prières et chat ensemble, autre joueur indépendant, fenêtre glissante ; décision `throttled` d'un événement saillant sous budget épuisé ;
  - `WakeBudgetScenariosTest` (Directeur complet contre le faux serveur strict) : chat entre joueurs remis au battement ; battement sans Jev sauté pour de la pierre, réveillé par deux diamants ; budget de 2 tours : mort retenue, battement sauté, chat adressé qui réveille et emporte la mort retenue, réveil normal une fois les tours sortis de l'heure ; prière toutes les 10 s pendant 30 min ; interpellation toutes les 2 s pendant 10 min ;
  - `ShowOnServerTest` : serveur arrêté → rien sur le thread appelant ; ordre gardé ; exécution immédiate sur le thread serveur ;
  - `DeathReportTest` : phrase, `detail` aller-retour, requête des actions sur un vrai Journal (fenêtre, effet, statut, cibles), `lastDeath` et rendu de `why` (couleur) ;
  - GameTests `DeathTraceGameTests` : une vraie mort après une action enregistrée → décision `died` qui la cite, puis `/narrator why <joueur>` par le vrai dispatcher ; `guard allowed` au niveau INFO (capture des logs).
- **Contre-épreuves** (correctif retiré, test relancé, puis correctif remis) :
  - budget par joueur neutralisé → `SalienceTest` (7ᵉ message `IMMEDIATE`) et les deux scénarios d'abus échouent (59 tours au lieu de 10, 84 au lieu de 13) ;
  - règle des 45 s remise → `WakeSchedulerTest` et le scénario du chat échouent ;
  - battement heuristique à 1.0 → `WakeSchedulerTest` et le scénario du battement échouent ;
  - budget global neutralisé → `WakeSchedulerTest`, `SalienceTest` et trois scénarios échouent (49 tours au lieu de 10, 84 au lieu de 13) ;
  - ancien `onServer` (`execute`) → `ShowOnServerTest` échoue (bloc exécuté sur le thread du test) ;
  - `explain` retiré → le GameTest de la mort expire ; niveau DEBUG remis → le GameTest du log échoue.
- Pas de `runServer`.
