# Relecture de M6, partie 2 : constats et lots de corrections

Relecture du 21/10/2026, sur `main` à **`a74534d`** (fusion de M6-FIX-1). Périmètre : ce qui a été fusionné depuis la
partie 1 (`b754f7e`), soit le contenu des fusions de **M6-13** (`09d020e` : `/narrator restart`, `RestartSequence`,
`prepare`/`wire`, `ShutdownSequence` coupé en deux, `prepareMemory`, crochet `QUERY_START`, `AdminCommands`), **M6-07**
(`a1bbd05` : étape « programme » du rêve, `ProgrammeInputs`, blocs de style de la revue, Grimoire à six sections,
`storyteller.md`, `dreamer.md`, refus des récits prévus côté Rêveur), **M6-11** (`879c957` : `DashboardQueries`, pages
des tours, conversations, transcriptions, tâches, décisions et coûts, `TraceParts`, `/narrator usage` en dollars) et
des correctifs **M6-FIX-3** (`1f3f2d9`) et **M6-FIX-1** (`a74534d`), relus contre les constats de la partie 1. M6-08,
M6-12, M6-16, M6-17 et FORGE-ABANDON, en cours, sont laissés à la partie 3.

Lus : `M6.md` (§3.7, §4.6, §5, §6, §8, §9, §12, §15, §16.2), `M6_DESIGN_REVIEW.md`, les passations M6-06, M6-07,
M6-10, M6-11, M6-13, M6-FIX-1 et M6-FIX-3 (elles font foi contre le design), `M6_REVIEW_1.md` (le modèle),
`ARCHITECTURE.md` §17, `MEMOIRE.md` §2 et §5 ; tout le code et les tests des cinq fusions, et ce qu'ils touchent autour
(crochets Fabric, mixins, Observateur, garde-fous, `DuckJournal`, `OpenRetry`, `DuckDatabase`, magasin de la mémoire,
`Transcripts`, `Salience`, `SqlRunner`).

Chaque constat donne sa gravité, l'endroit (`fichier:ligne` sur `a74534d`, chemins sous
`TheMod/src/main/kotlin/io/github/clemsix6/narrator2/` sauf mention), un scénario, la preuve et un **correctif
proposé**. **Vérifié** veut dire lu des deux côtés, bytecode compris quand il le faut (javap sur le jar 26.3 et
`fabric-networking-api-v1` 6.3.8), ou prouvé par une **sonde jetable** : un test temporaire lancé dans un worktree de
relecture (`gradlew.bat test --tests …ReviewProbeTest`), puis supprimé et jamais commité. **Raisonné** veut dire déduit
de la lecture sans exécution. Le superviseur décide. Les lots de la fin ne partagent aucun fichier.

**État de départ.** J'ai relancé les tests unitaires de `main` (`a74534d`) dans mon worktree (`gradlew.bat test
--continue`) : **2 187 tests, 0 échec, 0 ignoré**, en 7 min 49 s, sur une machine chargée par deux autres builds. C'est
le compte de la passation de M6-FIX-1. Je n'ai pas relancé les GameTests ; le build final de M6-FIX-1 donne « All 398
required tests passed ». Les sondes citées dans les constats ont tourné dans des worktrees de relecture, puis ont été
supprimées.

**`main` a bougé pendant la relecture.** Il est à `a37f052` : M6-12, FORGE-ABANDON et M6-08 sont fusionnés (partie 3).
Aucun des fichiers de code visés par les constats et les lots ci-dessous n'a changé entre `a74534d` et `a37f052`
(vérifié avec `git diff --stat`). Seul `docs/MEMOIRE.md` a gagné deux lignes vers la ligne 220, sans toucher aux lignes
citées. Deux conséquences sur les lots : `memory/Memory.kt` (M6-08) est libre, et M6-12 ne réutilise ni `TraceParts`
ni `DashboardQueries`.

**Bilan : 12 constats. 0 bloquant, 1 important, 3 mineurs, 8 finitions.** S'y ajoutent trois décisions à prendre.

- **`/narrator restart` tient.** Le refus et la pose de la porte des connexions se font dans la même tâche du thread
  serveur. Toute connexion est soit refusée (en ligne, ou en phase de connexion ou de configuration), soit retenue par
  `QUERY_START`, que Fabric déclenche sur le thread serveur juste avant `verifyLoginAndFinishConnectionSetup`. Aucune
  référence à l'ancien runtime ne survit à l'échange : les crochets, les mixins et les commandes passent tous par
  `NarratorRuntime.current`, et les coroutines et les fils sont annulés ou fermés. Le thread serveur n'attend rien. Le
  seul trou (constat 4) est un ancien Journal qui ne se ferme pas à temps : le nouveau ne s'ouvre pas, avec une raison
  trompeuse. Aucune double connexion n'est possible, mais par chance plutôt que par conception.
- **Les pages de trace sont sûres.** Aucune requête ne nomme `private_messages`, et un chuchotement n'atteint ni un
  modèle, ni une transcription, ni un argument d'outil. La lecture d'une transcription reste bornée au dossier, liens
  et jonctions compris. L'échappement et les bornes tiennent. Ne restent que des finitions.
- **Le programme du rêve ne casse jamais le rêve**, et il écrit au nom du Scénariste, dans ses bornes. Mais son bloc
  `<ideas>` est trop petit pour son contenu (constat 1, important) : en régime normal, le Scénariste ne voit ni les
  idées ouvertes les plus anciennes ni aucune idée close, donc jamais « ce qui a suivi ».
- **Les correctifs FIX-1 et FIX-3 corrigent bien les constats de la partie 1, sans régression.** Il n'y a plus qu'un
  magasin enveloppé pour toutes les écritures d'idées, et `Tics.opens` couvre les trois préfixes réellement publiés.

## Constats

1. **IMPORTANT (programme du rêve, `<ideas>`) : en régime normal, le bloc ne montre que 8 des 12 idées ouvertes, et
   aucune idée close. Le retour « ce qui a suivi » (§6.3) n'atteint donc jamais le Scénariste.**
   - Où :
     - `memory/dream/ProgrammeInputs.kt:310-324` (`ideas`) : `inputs.shared(items, IDEAS_MAX)` puis
       `block(…, IDEAS_MAX)`, avec `IDEAS_MAX` = 2 500 (`:453`) ;
     - `memory/dream/DreamInputs.kt:555-559` (`shared`) : chaque corps est coupé à sa part, mais jamais sous
       `MIN_BODY` = 60 (`:710`), et l'en-tête n'est jamais coupé ; `fit` jette ensuite les dernières lignes ;
     - les idées closes sont triées en dernier (`ProgrammeInputs.kt:311`) : ce sont elles qui partent les premières.
   - Scénario : après deux programmes, les 12 idées ouvertes du plafond sont là. Avec ses rendus détaillés (`dealt 2:
     10-01 21:34 heartbeat → nothing; …`), l'en-tête d'une idée fait environ 210 caractères, la ligne environ 270. Les
     4 idées ouvertes les plus anciennes ne sont pas montrées, alors que ce sont celles que le prompt demande de relire
     pour `drop_idea` (« une idée rendue plusieurs fois sans suite »). Aucune idée close n'apparaît, alors que
     l'attribut annonce `open="12"` et que le bloc doit dire ce que sont devenues les idées closes depuis le dernier
     rêve. Même sans aucun rendu (en-tête d'environ 125 caractères), les idées closes restent coupées.
   - Défaut voisin : les idées closes sont cherchées parmi les 60 idées les plus récentes, puis filtrées en Kotlin
     (`:86`, `CLOSED_READ` `:477`). À deux rêves par jour et 5 à 8 idées par rêve, une idée créée il y a plus de 4 à 5
     jours et close depuis le dernier rêve n'est jamais montrée.
   - Preuve : **vérifié par sonde**, avec le vrai `ProgrammeInputs` sur le Journal de `DreamerFixture` : 12 idées
     ouvertes (2 joueurs, titre de 40 caractères, pitch de 350, rendues 2 fois) et 4 closes depuis le dernier rêve.
     Résultat : `<ideas> 2252 chars, 8 ideas shown of 16 (12 open)`, puis `(… cut: the rest went over the budget)`. Le
     test au maximum (`ProgrammeInputsTest:104`) ne vérifie que les tailles, pas le nombre d'idées montrées.
   - Correctif proposé (D5) :
     - deux parts fixes dans le bloc, les ouvertes puis les closes ;
     - les closes rendues sans pitch : référence, sorte, titre, statut et raison, rendus et ce qui a suivi ;
     - les ouvertes avec un pitch coupé à environ 100 caractères ;
     - `IDEAS_MAX` porté à environ 4 000 (et `STEP_MAX` d'autant : l'ouverture mesure 29 726 caractères au maximum,
       environ 20 000 sur une soirée réelle) ;
     - les closes lues en SQL par `closed_at >= ?` au lieu de `ideas(null, 60)` filtré ;
     - dans le test, vérifier que les 12 idées ouvertes et au moins quatre closes sont montrées.
   - Fichiers : `memory/dream/ProgrammeInputs.kt` ; test `ProgrammeInputsTest`.

2. **MINEUR (programme du rêve, « ce qui a suivi ») : pour une saison, une étape de récit, une idée `forge` ou un
   retournement, la simple réplique suivante compte comme une suite.**
   - Où : `memory/dream/ProgrammeInputs.kt:351` (`SEASON, ARC_STEP, FORGE, TWIST -> true`) ; `:331` cherche dans
     `read.interventions`, qui contient les clés `say:<registre>` des répliques relues
     (`storyteller/Interventions.kt:76`). `history()` les écarte (`ProgrammeInputs.kt:281`), mais `dealt()` ne le fait
     pas.
   - Scénario : la carte d'un retournement est rendue au Directeur par le battement, et il parle dans la minute.
     `<ideas>` dit alors `heartbeat → say plain 1m later` : le Scénariste croit l'idée suivie. Pour ces quatre sortes
     sur treize, le signal est presque toujours un faux positif, et le Scénariste garde des idées mortes.
   - Preuve : **vérifié** par lecture des deux côtés.
   - Correctif proposé : `follows` rend `false` pour une clé `say:` (une ligne), et un cas de test.
   - Fichiers : `memory/dream/ProgrammeInputs.kt` ; test `ProgrammeInputsTest`.

3. **MINEUR (une seule plume par sujet ; décision D6) : le Rêveur peut encore lancer (`active`) ou abandonner un récit
   que le Scénariste a prévu.**
   - Où :
     - `memory/DuckMemoryStore.kt:501-503` : pour `Source.DREAMER`, seule la sorte `arc` est vérifiée, et
       `CanonKind.allows(PLANNED, ACTIVE)` est vrai ;
     - `tool/memory/MemoryTools.kt:281-286` : même contrôle dans l'outil `resolve`, dont la description propose « a
       planned arc started ».
   - Scénario : à l'étape 4, `<arcs>` montre un récit `planned` écrit par le programme de la veille. Le Rêveur le
     « lance » : le récit devient actif, avec une échéance à 7 jours, sans que le Narrateur l'ait jamais annoncé. Or
     lancer un récit revient au Directeur (§6.1, `resolve … active`). Le Rêveur peut aussi l'abandonner, alors que les
     récits prévus sont désormais au Scénariste. `dreamer.md:102` limite `resolve` aux récits actifs, mais rien ne
     l'impose dans le code. Le trou date de M3, mais M6-07 fait de la plume unique une règle, et le magasin l'impose
     déjà au Scénariste (`:504-506`).
   - Preuve : **vérifié par sonde** sur le vrai magasin : `resolveCanon(arc planned, ACTIVE, …, Author(DREAMER))` rend
     `active, due 2026-10-08T22:10:00Z`, et `ABANDONED` rend `abandoned`. Que le modèle le fasse vraiment est
     **raisonné**.
   - Correctif proposé (si D6 = oui) : pour le Rêveur, n'accepter qu'un récit `active`, vers `resolved` ou `abandoned`.
     Le contrôle va dans le magasin et dans l'outil, avec un message clair (« a planned arc is the storyteller's; the
     Narrator starts it »).
   - Fichiers : `memory/DuckMemoryStore.kt`, `tool/memory/MemoryTools.kt` ; tests `DuckMemoryStoreTest`,
     `MemoryToolsTest`, `DreamerToolsTest`.

4. **MINEUR (redémarrage, Journal) : un ancien Journal qui ne s'est pas fermé à temps fait échouer l'ouverture du
   nouveau, sans nouvelle tentative et avec une raison trompeuse.**
   - Où :
     - `runtime/RestartSequence.kt:189-194` : `old?.stopAndClose()` puis `prepare()`, sans savoir si le Journal s'est
       fermé ;
     - `journal/DuckJournal.kt:254-258` : quand le fil d'écriture ne s'arrête pas dans le délai (10 s,
       `Journal.DEFAULT_CLOSE_TIMEOUT`), `close` rend la main et la base reste ouverte jusqu'à la fin de ce fil ;
     - `runtime/ShutdownSequence.kt:147-153` : le fil `narrator2-journal-close` a fini, donc la séquence écrit
       `stopping: Journal closed in 10012 ms` alors que la base est encore ouverte ;
     - `journal/DuckDatabase.kt:138` (`SET lock_configuration = true`) et `journal/OpenRetry.kt:116-128` (seule une
       `IO Error:` est réessayée).
   - Scénario : un Journal de plusieurs centaines de Mo (§18.1, point 7), dont la file à vider et le dernier point de
     contrôle prennent plus de 10 s. Le fil du redémarrage passe à `prepare()` alors que l'ancienne base est encore
     ouverte dans la JVM. Le pilote JDBC de DuckDB réutilise l'instance ouverte pour le même fichier : la connexion
     s'ouvre, puis `SET TimeZone` échoue (« Invalid Input Error: Cannot change configuration option "TimeZone" - the
     configuration has been locked »). Ce n'est pas une `IO Error:`, donc `OpenRetry` ne réessaie pas. Le Narrateur
     revient « redémarré » avec `Journal unavailable · memory off (the Journal is unavailable: opening failed, and is
     not tried again as it would fail the same way: Invalid Input Error: …)`, mémoire et mécaniques éteintes, et
     l'opérateur ne comprend pas pourquoi. Un second `/narrator restart`, une fois l'ancien fil d'écriture fini,
     réussit.
   - Ce qui tient : jamais deux instances DuckDB sur le fichier (le cache d'instances et `lock_configuration` refusent
     la seconde ouverture), donc aucune corruption. Mais c'est un effet de bord. La passation affirme que « Journal
     closed » précède toujours « opened » : c'est vrai seulement quand la fermeture tient dans son délai.
   - Preuve : **vérifié par sonde** pour l'ouverture : deux `DuckDatabase.open` sur le même fichier dans la même JVM,
     la seconde échoue avec ce message ; après fermeture de la première, l'ouverture réussit. Le chemin de
     l'expiration est **raisonné** (lecture de `DuckJournal.close`, `closeJournal` et `OpenRetry`).
   - Correctif proposé (une dizaine de lignes) :
     - `Journal` gagne `val closed: Boolean` (vrai par défaut ; pour `DuckJournal`, ses ressources fermées) ;
     - `closeJournal` le lit pour son message (« did not close within … ms: the database stays open until its writer
       ends ») ;
     - `NarratorRuntime.stopAndClose` lève une exception quand le Journal reste ouvert. `RestartSequence` en fait déjà
       un échec, à l'étape « the old Narrator did not stop » : pas de `prepare`, et le Narrateur reste éteint avec la
       raison « the old Journal is still closing; /narrator restart tries again ».
     - Test : un Narrateur factice dont `stopAndClose` échoue ne prépare rien ; et un `DuckJournal` dont le fil
       d'écriture tarde dit `closed = false`.
   - Fichiers : `journal/Journal.kt`, `journal/DuckJournal.kt`, `runtime/ShutdownSequence.kt`, `NarratorRuntime.kt` ;
     tests `RestartSequenceTest`, `ShutdownSequenceTest`, `DuckJournalTest`.

5. **FINITION (redémarrage, refus) : le refus « joining » montre l'adresse IP du joueur, et non son seul nom.**
   - Où : `runtime/RestartSequence.kt:318` (`listener.userName`).
   - Constat : `ServerLoginPacketListenerImpl.getUserName()` rend `"<nom> (<adresse>)"`, ou l'adresse seule avant
     le paquet `hello` (vérifié à javap : recette `\u0001 (\u0001)` sur `requestedUsername` et
     `getLoggableAddress(logIPs)`). La réponse est donc `cannot restart: 1 player joining (Bartok
     (/203.0.113.7:51234))`, et non `(Bartok)` comme le disent la passation et le KDoc. Elle ne part qu'à la source de
     la commande (la console ou RCON : un opérateur en jeu est refusé avant, comme joueur en ligne), et l'adresse est
     déjà dans le journal du serveur quand `log-ips` est vrai. C'est donc cosmétique. Aucun test ne passe par un vrai
     écouteur : `refusal(online, joining, …)` est testé avec des noms.
   - Correctif : garder ce qui précède ` (`, et dire `a connection` pour une adresse seule.
   - Fichiers : `runtime/RestartSequence.kt` ; test `RestartSequenceTest`.

6. **FINITION (redémarrage, chemins secondaires et libellés).**
   - Arrêt du serveur pendant l'étape 2 (`runtime/RestartSequence.kt:189-197`) : le fil prépare quand même un nouveau
     runtime (Journal ouvert, jusqu'à trois tentatives en 10 s, puis lectures de la mémoire, 30 s au plus) avant de
     voir l'arrêt au câblage, puis le referme. Comme le fil n'est pas un démon, la JVM attend. Correctif : tester
     `stopping()` juste après `old?.stopAndClose()`, deux lignes.
   - Rapport d'un échec (`:209-210`, `:74`) : `gapMs` n'est calculé que si le câblage a tourné. Un échec avant le
     câblage écrit donc `wire 0 ms · 0 ms without a Narrator` dans le `WARN`, alors que le Narrateur reste éteint.
     Correctif : `times()` omet le trou quand `failure` n'est pas nul.
   - KDoc de `RestartSequence` (`:88`) et passation : « the Journal, 14 s ». La borne réelle est de 10 + 2 s
     (`Journal.DEFAULT_CLOSE_TIMEOUT` et `JOURNAL_GRACE`).
   - Pendant l'échange, `/narrator status` et toute commande qui passe par `runningNarrator` répondent « the Narrator
     is not running, see the server log » (`command/AdminCommands.kt:115-118`, `command/Replies.kt:44`). Correctif :
     « a restart is running » quand `NarratorRuntime.restarts.running`.
   - Déjà connus, à ranger ici :
     - au redémarrage, les libellés restent ceux de l'arrêt du serveur :
       - `mechanics/module/ModuleEngine.kt:926` : `the server stops: … modules stopped until the restart` ;
       - `mechanics/rule/RuleEngine.kt:790` ;
       - la cause d'annulation `the server is stopping` (`runtime/ShutdownSequence.kt:97`) ;
       - correctif : une formulation neutre (« the Narrator stops »), sans changer d'interface ;
     - une Forge arrêtée en pleine construction écrit `ERROR the built-in module … was not built: the server is
       stopping` (`forge/ForgeService.kt:259`, la raison venant de `:137` et `:496`). Correctif : dans ce `catch`,
       sortir de la boucle avec un `INFO` quand la Forge est arrêtée. FORGE-ABANDON, fusionné, n'a pas touché ce
       fichier ;
     - le KDoc de `NarratorServices` (`runtime/NarratorServices.kt:41`) parle de services qui vivent « as long as the
       server » ; c'est désormais « as long as the runtime ».
   - Fichiers : `runtime/RestartSequence.kt`, `runtime/NarratorServices.kt`, `command/Replies.kt`,
     `command/AdminCommands.kt`, `mechanics/module/ModuleEngine.kt`, `mechanics/rule/RuleEngine.kt`,
     `forge/ForgeService.kt` (et `runtime/ShutdownSequence.kt`, déjà dans le lot du constat 4) ; test
     `RestartSequenceTest`.

7. **FINITION (revue de l'étape 2 : lectures non bornées, et un risque d'échec du rêve entier).**
   - `memory/dream/DreamInputs.kt:316` : `docVersions(Review)` lit **toutes** les versions de la revue (contenu et
     preuves) à chaque rêve, seulement pour deux dates. Rien ne purge `memory_docs` : environ 730 versions de 3 Ko par
     an. Correctif : lire `doc(key)` puis `doc(key, version - 1)`.
   - M6-07 ajoute quatre requêtes (répliques, réactions, période d'avant, noms : `StyleReport.review`) à la lecture
     unique de l'étape 2, bornée à 15 s (`:318-329`). Une expiration lève hors de `Dreamer.review` : c'est alors le
     rêve entier qui échoue (« engine error »), pas seulement l'étape. **Raisonné**, peu probable. Correctif : lire le
     style à part, de façon non décisive comme le programme (sans réaction ou sur erreur, les trois blocs sont omis).
   - Fichiers : `memory/dream/DreamInputs.kt` ; test `DreamInputsTest`.

8. **FINITION (programme du rêve : simplicité et comptes).**
   - `memory/dream/ProgrammeInputs.kt:404-409` reconstruit `Table` avec 15 arguments positionnels, seulement pour vider
     `ideas`. Un paramètre de `Deck.draw`, ou un `copy` sur un `Table` devenu `data`, suffirait.
   - `:99-100` : `StyleReport.lines(connection, period).size` lit toutes les répliques pour les compter, puis
     `StyleReport.registers` les relit.
   - Ce compte inclut les titres, la barre d'action et les livres, que la relecture ne lit jamais depuis FIX-1 : dans
     `<history>` (« N said, M reviewed », `:295`) et dans `<registers lines= reviewed=>` de la revue
     (`style/StyleReport.kt:157-163`, `:276-287`), le modèle voit un écart qui n'en est pas un. Correctif : dans ces deux
     blocs, compter les répliques relisibles (`Tics.opens`), comme `coverage`, sans changer `StyleReport.lines` (que la
     page Style de M6-12 peut lire).
   - En option : la décision `memory programme` ne compte ni les récits prévus ni les récits abandonnés
     (`memory/dream/Dreamer.kt:370`).
   - Fichiers : `memory/dream/ProgrammeInputs.kt`, `style/StyleReport.kt`, `memory/dream/Dreamer.kt` (option).

9. **FINITION (transcription, fuseau) : les heures des entrées d'une transcription sont au fuseau de la JVM, pas à celui
   du tableau de bord (`journal.timeZone`).**
   - Où : `web/pages/TranscriptPage.kt:345` (`OffsetDateTime.parse(stamp).format(TIME)` garde le décalage écrit par
     `Transcripts`, `OffsetDateTime.now()`) ; l'en-tête de la même page passe par `context.time`, donc par
     `PageContext.zone`.
   - Scénario : serveur en UTC, `journal.timeZone = Europe/Paris`. L'en-tête et `/turns/<id>` disent « 21:30:12 », les
     entrées de la transcription « 19:30:12 ».
   - Preuve : **raisonné** par lecture des deux côtés. Hors périmètre et antérieur à M6 : `/narrator trace` utilise
     `runtime.clock.zone` (`command/TraceCommands.kt:147`) et non `journal.timeZone`.
   - Correctif : `OffsetDateTime.parse(stamp).atZoneSameInstant(zone).format(TIME)`, et un test avec un tampon `Z`.
   - Fichiers : `web/pages/TranscriptPage.kt` ; test `TranscriptPageTest`.

10. **FINITION (traces : simplicité) : deux calculs du coût par rôle, deux tables des tours, une copie de requête, et
    `/narrator usage` qui dépend du paquet `web`.**
    - `web/DashboardQueries.kt:154-163` (`TURNS`) recopie `trace/TraceQueries.kt:244-254` (`TURN_SUMMARY`, privée) ;
      seul un test les tient en phase (écart 3 de la passation de M6-11).
    - Le coût par rôle est calculé deux fois :
      - M6-10 : `web/pages/OverviewPage.kt:53-57` et `:246-257` (`COST_BY_ROLE`, `CostRow`, `RoleCost`,
        `costByRole`) ;
      - M6-11 : `DashboardQueries.tokensByRole`, `ModelTokens`, `Cost`, `Prices.costOf` ;
      - les deux disent différemment un modèle sans prix : « + 3 calls of a model without a price… » sur la vue
        d'ensemble, `$X + 3 calls at $ ?` sur `/costs` et dans `usage`.
    - Il y a deux tables des tours : `OverviewPage.turns` (`:220-241`, avec sa propre copie de `QUIET_OUTCOMES`,
      `:273`) et `TraceParts.turnTable`.
    - `command/TraceCommands.kt:27-29` et `:126` importent `web.Cost`, `web.DashboardQueries` et `web.costOf`, qui
      sont des notions de trace ; `usage` fait deux requêtes sur `llm_calls` quand une suffirait.
    - Correctif :
      - rendre `TURN_SUMMARY` `internal` et l'utiliser ;
      - déplacer `ModelTokens`, `Cost` et `costOf` dans `trace/Prices.kt`, et `tokensByRole` dans `TraceQueries` ;
      - faire réutiliser à `OverviewPage` `tokensByRole`, `Cost.text` et `turnTable`, puis retirer ses copies.
    - Preuve : **vérifié** par lecture et par recherche des appelants.
    - Fichiers : `web/DashboardQueries.kt`, `trace/TraceQueries.kt`, `trace/Prices.kt`, `command/TraceCommands.kt`,
      `web/pages/OverviewPage.kt`, `web/pages/TraceParts.kt`, `web/pages/CostsPage.kt` ; tests `DashboardQueriesTest`,
      `TraceQueriesTest`, `TraceTextTest`.

11. **FINITION (docs et KDoc périmés).**
    - Rêve :
      - `memory/Hygiene.kt:42` : « step 5 » au lieu de 6 (déjà signalé par M6-07) ;
      - `tool/memory/MemoryArgs.kt:114` : « which only the Dreamer starts with » ;
      - `memory/MemoryTypes.kt:243` : « an arc the Dreamer prepares » ;
      - `docs/MEMOIRE.md:28-29` : le schéma des couches cite encore les « plans » ;
      - `docs/MEMOIRE.md:162` : le coût d'un rêve (environ 200 000 tokens) n'inclut pas le programme (45 000 à
        115 000 tokens d'entrée de plus, passation de M6-07).
    - Déjà connu (effet de D3) : quand toutes les répliques relues sont `plain` ou `other`, `/narrator style` dit « no
      line labeled » (`command/StyleCommands.kt:95-96`, qui teste `topRegister == null`). Correctif :
      - ajouter `plainShare` à `Diversity`, en dernier et avec une valeur par défaut (`style/Style.kt:125`, rempli par
        `style/StyleWatchImpl.kt:226`) ;
      - dire « no register of the voice · plain lines 100% » ;
      - le KDoc de `Diversity.topRegister` et de `topRegisterShare` (`style/Style.kt:116-117`) décrit encore l'ancienne
        définition ;
      - `M6.md` §3.7, la ligne « Part du registre dominant », est à mettre à jour.
    - Fichiers : `memory/Hygiene.kt`, `tool/memory/MemoryArgs.kt`, `memory/MemoryTypes.kt`, `style/Style.kt`,
      `style/StyleWatchImpl.kt`, `command/StyleCommands.kt`, `docs/MEMOIRE.md`, `docs/design/M6.md` ; test
      `StyleCommandsTest`.

12. **FINITION (tests) : des trous sur des chemins jugés corrects à la lecture, et un dossier temporaire qui fuit.**
    - Rêve :
      - le chemin `inputs: …` (des entrées du programme qui lèvent) n'est pas testé ;
      - un programme en échec avec le Grimoire écrit, qui doit laisser le rêve `done`, n'est pas testé : le seul test
        d'échec (`DreamerTest:29`) a aussi un Grimoire raté ;
      - une annulation pendant l'étape 5, qui doit laisser `stepKey` à 4 et reprendre à 5, n'est pas testée ;
      - le nombre d'idées montrées : voir le constat 1.
    - Transcriptions : la branche `toRealPath` de `TranscriptPage.locate` (`:156-161`) n'est jamais exercée, car les
      sept chemins de `TranscriptPageTest:178-186` tombent tous au contrôle lexical (`:153`).
      - Une sonde (JDK 25, jonction Windows `root\link → outside`) montre que le contrôle lexical passe et que
        `toRealPath` refuse : le code est juste, mais aucun test ne le garde.
      - Correctif : un test avec `Files.createSymbolicLink`, ignoré par `assumeTrue` si l'OS refuse le lien.
      - Une ligne plus longue qu'une page (`Entry.Unreadable`, `:210`) n'est pas testée. Une sonde (budget de 1 000
        octets, lignes de 113 o, 5 ko et 113 o) l'a vérifiée correcte.
    - `TracePagesTest:65` crée un dossier temporaire par test, jamais supprimé : utiliser `@TempDir`.
    - Redémarrage : aucun test du Journal resté ouvert (constat 4), ni d'un vrai écouteur de connexion (constat 5).
    - Aucun piège `runBlocking` dans les tests des cinq fusions : les tests du rêve écrivent `runBlocking<Unit>`, et les
      autres usages sont des fonctions d'aide. Aucune nouvelle mesure fragile à l'horloge murale : les bornes de
      `RestartSequenceTest` sont de 10 s, et sa borne de câblage de 200 ms est le comportement testé.
    - Fichiers : tests `ProgrammeInputsTest`, `DreamerTest`, `TranscriptPageTest`, `TracePagesTest` (et ceux des
      constats 4 et 5).

## Vérifié, sans constat

- **`/narrator restart` : la porte des connexions** (`runtime/RestartSequence.kt`, `Narrator2.kt`).
  - Le refus (joueurs en ligne, écouteurs `ServerLoginPacketListenerImpl` et `ServerConfigurationPacketListenerImpl`
    lus sous le verrou de `connections`) et la pose de la porte se font dans la même tâche du thread serveur.
  - Le mixin de Fabric (`ServerLoginPacketListenerImplMixin`, `fabric-networking-api-v1` 6.3.8) enveloppe l'appel de
    `verifyLoginAndFinishConnectionSetup` dans `tick()` par `queryTick()`, qui déclenche `QUERY_START` puis attend les
    futures. **Vérifié à javap.** Une connexion encore en phase de poignée de main passe donc forcément par la porte.
  - La porte est complétée après `install` (`current` posé), dans le `finally` du câblage, puis de `restart()` et de
    `go()` en cas d'échec. Un second redémarrage reste refusé jusqu'à la fin du fil (la porte n'est retirée qu'à la
    fin) : jamais deux redémarrages en même temps.
- **Aucune référence à l'ancien runtime.**
  - Le câblage n'enregistre aucun crochet Fabric : la recherche des `register` hors de l'initialisation ne trouve
    que `Fuses`, une fois par JVM, qui jette les mèches d'un runtime qui n'est plus `RUNNING`.
  - Les crochets (`GuardModule`, `MechanicsHooks`, `FabricObserver`, `ObserverHooks`, `BlockRestorer`) et les mixins
    (`ObserverHooks`, `CreatureShield`, `ExplosionGuard`, `DeathGuard`, `ModuleCreatures`) passent par
    `NarratorRuntime.current`, par `ObserverState.current()` (les parties statiques de l'Observateur sont comparées à
    l'état courant) ou par des pièces jointes. `BlockRestorer` marche sans runtime.
  - Les coroutines (Directeur, travailleurs, mémoire, `narrator2-ideas`, relecture, veille du style, moteurs des règles
    et des modules, qui sont des portées filles de `services.scope`) sont annulées par `stopWaiting`. Les fils
    `narrator2-journal` et `narrator2-sql-*` se ferment avec le Journal, `narrator2-web-*` par `shutdownNow`.
  - Les commandes passent par `current` ; `pingInFlight` et `reloadInFlight` sont remis à faux à l'annulation
    (`invokeOnCompletion`).
- **Thread serveur.** L'étape 1 (tableau de bord, mécaniques, Forge, vidage de l'Observateur) et l'étape 3 (câblage :
  1 à 7 ms mesurées par M6-13) n'attendent ni le réseau ni la base. La décision `operator restarted` et la réponse
  passent par la file d'écriture.
- **Échecs partiels.**
  - Un câblage raté passe par `stopInGame` (branche « non câblé ») sur le thread serveur, puis par `stopAndClose` sur
    le fil.
  - Une préparation ratée se referme dans `NarratorRuntime.prepare`.
  - Quand le serveur s'arrête, la tâche du câblage est réclamée (`AtomicBoolean`) et jamais câblée. Une fois le
    serveur arrêté, `MinecraftServer.execute` exécute la tâche sur l'appelant (`scheduleExecutables` teste
    `isStopped()`, **vérifié à javap**), et `isSameThread` le détecte.
  - `DedicatedServer.onServerExit` n'appelle pas `System.exit` (**vérifié à javap**) : le fil non démon du redémarrage
    finit bien de fermer son Journal.
- **Démarrage du serveur inchangé** : `prepare` puis `wire` sur le thread serveur, avec les mêmes bornes (§17.8).
  `abandon()` enchaîne les deux moitiés de l'arrêt.
- **M6-FIX-3, un seul magasin enveloppé.**
  - Un seul `DuckMemoryStore` est construit (`memory/MemoryModule.kt:128`), et seul `OpenIdeas` le reçoit nu. Toutes
    les pièces reçoivent le magasin enveloppé (`:130-157`).
  - Les outils, le Rêveur (`Dreamer.store` = `services.memory.store`), le deck d'`inspire` (`storyteller/Deck.kt:839`)
    et `StorytellerTools` passent tous par `services.memory.store`.
  - Tout changement d'une idée passe par `addIdea` (`makeRoom`), `dropIdea`, `expireIdeas`, `revertRun`
    (`closeIdea` n'a pas d'autre appelant) ou `dealt`, et les cinq sont enveloppés. `recorded` ne touche pas `ideas`.
  - Non-régression prouvée par le test de FIX-3 (rouge avec le magasin nu).
  - `Prices.dollars` arrondit d'abord. Le prix du repli (5 / 25 / 0,50 / 10 $) suit D2.
- **M6-FIX-1.**
  - `Tics.opens` couvre les trois préfixes réellement publiés : `tool/speech/SpeechTools.kt:195` (`[title] `), `:247`
    (`[actionbar] `) et `:397` (`[book "…"] `).
  - Les seuls autres `NarratorSpoke` viennent de `Speech.say`, sans préfixe : `say`, et `/narrator say` sur le canal
    `operator`, que le relecteur écarte (`LineReviewer.reviewed` exige aussi un tour).
  - Titres et pages passent par `Speech.clean`, qui les met sur une ligne : le `.*?` du motif ne bute jamais sur un
    saut de ligne. Le motif est lu de la même façon par RE2 (`StyleReport.coverage`).
  - Les candidats sont reconnus par mots entiers (`inLine`), `narrateur` est hors des candidats, et les noms élidés
    donnent `de <nom>`. Le registre dominant est celui de la voix ; `topRegister` nul est géré dans `<diversity>`, et
    dans `/narrator style` à l'affichage près (constat 11).
- **M6-11 : confidentialité.**
  - Aucune requête ne nomme `private_messages` (`DashboardQueries.STATEMENTS`, et les requêtes réellement exécutées,
    enregistrées par le proxy JDBC de `TracePagesTest`).
  - Un chuchotement ne va que dans `private_messages` (`JournalMapping.kt:227`, `:479`), jamais dans `events` ni dans
    `chat` : l'id d'un chuchotement cité par un tour ne trouve rien.
  - Un chuchotement n'atteint jamais un modèle (`Salience.kt:308` et `:372`, `ReflexModule.kt:56`,
    `Reminiscence.kt:541`, `CommandIssued.text` nul pour `/msg`, `/tell`, `/w`, `/teammsg`, `/tm`) : il ne peut donc
    être ni dans une transcription, ni dans `tool_calls`, ni dans `engine_decisions.detail`. L'outil `sql` refuse
    `HIDDEN_FROM_MODEL` (`SqlRunner.kt:225`, `:286`).
  - Les en-têtes de requête du `.jsonl` ne sont jamais rendus.
- **M6-11 : fichiers de transcription.**
  - Le contrôle lexical passe avant tout accès disque : un chemin UNC ou absolu hors du dossier, ou un
    `InvalidPathException`, donne `Outside` sans rien lire.
  - Le `.jsonl` et la racine passent par `toRealPath` (jonction vérifiée par sonde). Un `.jsonl` qui est un dossier
    donne `Missing`.
  - `from` négatif devient 0, et `from` au-delà de la fin donne une page vide. En mémoire, une ligne tient en 4 Mio au
    plus ; une ligne plus longue est lue en flux puis sautée. L'UTF-8 n'est jamais coupé, et une dernière ligne en
    cours d'écriture est laissée pour plus tard.
  - Le fichier brut n'est jamais servi. La fenêtre entre `toRealPath` et l'ouverture demande un droit d'écriture sur
    le dossier du monde : accepté.
- **M6-11 : échappement et bornes.**
  - Les pages n'écrivent que par `Html`, sans aucune écriture brute dans `web/pages/` ; le JSON indenté va dans un
    `<pre>` échappé.
  - Une seule `context.read` par page, et un délai de 5 s par requête (prouvé par le proxy). Les listes lisent
    `LIMIT 51`.
  - Paramètres liés partout (`Where` ne concatène que des conditions constantes, le sujet passe par `contains(…)`) ;
    400 pour un filtre invalide, 404 pour un id inconnu ; ordres totaux. Les sommes portent sur 90 jours au plus.
- **M6-11 : exactitude.**
  - Le repli est facturé au modèle qui a répondu. Les latences p50 et p95 passent par `quantile_cont`.
  - La marque « Not committed » suit l'ordre d'écriture de `ClaudeClient`. `TraceText.safety` reprend la logique de
    `/narrator trace`.
  - `/narrator usage` passe par `replyLater`, hors du thread serveur.
  - Constat 10 de la partie 1 : `Svg.curve`, `Filter.Field` et `pager` ont désormais des appelants.
- **M6-07 : le programme ne casse jamais le rêve.**
  - `opening` est dans un `try` qui relance l'annulation et transforme toute autre erreur en `FAILED inputs: …`.
    `agents.run` et `programmeDone` attrapent tout sauf l'annulation. L'issue ne regarde que le Grimoire.
  - Un 402 laisse `stepKey` à 4 et reprend à l'étape 5. Une annulation remonte jusqu'à `MemoryJobs`. Jamais deux
    programmes dans un même run. Une reprise enregistrée par l'ancien code passe sans dommage.
- **M6-07 : auteur, outils et bornes.**
  - `MemoryAgents.run` fait `context.copy(role)`, et `RunContext.author` rend `STORYTELLER`.
  - Le Scénariste a 8 outils et aucun du Rêveur. Le magasin le borne : aucun souvenir, canon limité à `arc` prévu et
    `lore`, `resolve` seulement vers `abandoned`.
  - 10 requêtes, 10 min. Chaque bloc est coupé après échappement (`ModelText.escape` garde la longueur) ; `STEP_MAX`
    31 250 ≥ 29 726 mesurés. SQL borné (`LIMIT 8`, 30 jours, `ROWS_MAX` 5 000).
- **M6-07 : le reste.**
  - Revue : la période part de la revue précédente, et la période d'avant est prise entre les deux dernières versions.
    `topRegister` nul est géré.
  - Refus du Rêveur cohérents sur les trois couches (outil, magasin, prompt), avec des messages exacts.
  - Grimoire : un ancien Grimoire avec `## Plans` reste lisible, puisque rien n'en analyse les sections ;
    `DreamReplayTest` vérifie son refus à l'écriture.
  - `<draws>` n'enregistre rien.
  - `storyteller.md` fait 8 287 caractères sur 9 000, `dreamer.md` 14 949 sur 15 000. Les deux sont conformes au
    magasin et au §17, et il n'y a aucun texte français dans le code.

## Décisions à prendre

- **D5 — Le bloc `<ideas>` du programme** (constat 1). **Recommandation : 4 000 caractères**, en deux parts (ouvertes,
  puis closes sans pitch), et les closes lues en SQL depuis le dernier rêve. Coût : environ 700 tokens de plus par
  programme, sur environ 9 000 en soirée réelle. Sinon, garder 2 500 caractères, mais retirer les pitchs des idées
  closes et couper ceux des ouvertes à environ 60 caractères.
- **D6 — Le Rêveur et les récits prévus** (constat 3). **Recommandation : oui**, le Rêveur ne touche qu'un récit
  `active` : le Directeur lance un récit prévu, le Scénariste l'abandonne. Sinon, le dire dans `dreamer.md` (M6-16)
  et accepter le trou.
- **D7 — La simplification des traces** (constat 10) : la faire maintenant, ou la laisser. **Recommandation : la
  faire**, dans un lot court. M6-12 est fusionné et ne réutilise ni `TraceParts` ni `DashboardQueries`. Le gain
  principal est un seul calcul du coût par rôle, avec un seul libellé de `$ ?`. Sans urgence.

## Lots de corrections proposés

Chaque lot possède ses fichiers ; **aucun fichier n'est commun à deux lots**.

| Lot | Constats / décisions | Fichiers | Touche un fichier en cours ? | Critère |
|---|---|---|---|---|
| **M6-FIX-4 — le programme du rêve** | 1, 2, 7, 8, 12 (trous du rêve) ; D5 | `memory/dream/ProgrammeInputs.kt`, `memory/dream/DreamInputs.kt`, `memory/dream/Dreamer.kt` (option du constat 8), `style/StyleReport.kt` (compte des répliques relisibles) ; tests `ProgrammeInputsTest`, `DreamerTest`, `DreamInputsTest`, `StyleReportTest` | **Non**. M6-08 lit `StyleReport` sans le modifier ; M6-12 (`StylePage`) lit `StyleReport.measures` : aucune signature ne change, seul le compte `lines` des blocs du Rêveur. Rejouer `DreamReplayTest` | 12 idées ouvertes et au moins 4 closes montrées au maximum ; aucune réplique ne vaut suite pour une saison ; revue lue sans toutes ses versions, le style non décisif ; trous du rêve couverts ; build vert |
| **M6-FIX-5 — une seule plume** | 3 ; D6 ; en option, la simplification de `propose_idea` (constat 10 de la partie 1) | `memory/DuckMemoryStore.kt`, `tool/memory/MemoryTools.kt` ; pour l'option : `memory/MemoryStore.kt`, `memory/MemoryService.kt`, `memory/Memory.kt`, `tool/storyteller/StorytellerTools.kt`, `testing/FakeMemory.kt` (test) ; tests `DuckMemoryStoreTest`, `MemoryToolsTest`, `DreamerToolsTest`, `StorytellerToolsTest` | **Non** : `memory/Memory.kt` était à M6-08, désormais fusionné | le Rêveur refusé sur un récit `planned` (magasin et outil), message clair ; option : `addIdea` rend ce qu'il a clos, et `propose_idea` ne relit plus les idées ouvertes ; build vert |
| **M6-FIX-6 — le redémarrage** | 4, 5, 6, 12 (trous du redémarrage) | `journal/Journal.kt`, `journal/DuckJournal.kt`, `runtime/ShutdownSequence.kt`, `runtime/RestartSequence.kt`, `runtime/NarratorServices.kt`, `NarratorRuntime.kt`, `command/Replies.kt`, `command/AdminCommands.kt`, `mechanics/module/ModuleEngine.kt`, `mechanics/rule/RuleEngine.kt`, `forge/ForgeService.kt` ; tests `RestartSequenceTest`, `ShutdownSequenceTest`, `DuckJournalTest` | **Non** (FORGE-ABANDON, fusionné, n'a pas touché `ForgeService.kt`). `DisabledJournal` et les journaux factices des tests gardent la valeur par défaut de `closed` | un ancien Journal resté ouvert donne un échec clair, sans `prepare` ; rien de préparé quand le serveur s'arrête ; refus sans adresse ; libellés neutres, plus d'`ERROR` de la Forge à l'arrêt ; build vert, `RestartGameTests` compris |
| **M6-FIX-7 — les traces** | 9, 10, 12 (trous des transcriptions) ; D7 | `web/pages/TranscriptPage.kt`, `web/DashboardQueries.kt`, `web/pages/TraceParts.kt`, `web/pages/CostsPage.kt`, `web/pages/OverviewPage.kt`, `trace/TraceQueries.kt`, `trace/Prices.kt`, `command/TraceCommands.kt` ; tests `TranscriptPageTest`, `TracePagesTest`, `DashboardQueriesTest`, `TraceQueriesTest`, `TraceTextTest`, `PricesTest` | **Non** : aucun fichier de M6-FIX-2 (`Routes.kt`, `Html.kt`, `Dashboard.kt`, `DashboardCommands.kt`, `NarratorConfig.kt`), et M6-12, fusionné, ne réutilise ni `TraceParts` ni `DashboardQueries` | heures des transcriptions au fuseau du tableau de bord ; un seul calcul du coût par rôle et un seul libellé de `$ ?` ; lien et ligne trop longue testés ; build vert |
| **M6-FIX-8 — docs et KDoc** | 11 | `memory/Hygiene.kt`, `tool/memory/MemoryArgs.kt`, `memory/MemoryTypes.kt`, `style/Style.kt`, `style/StyleWatchImpl.kt`, `command/StyleCommands.kt`, `docs/MEMOIRE.md`, `docs/design/M6.md` (§3.7) ; test `StyleCommandsTest` | **Non** ; `Diversity` gagne un champ par défaut, compatible à la source (M6-12 n'en dépend pas) | `/narrator style` juste quand tout est `plain` ; KDoc et docs à jour ; build vert |

**Ordre conseillé.** Les cinq lots peuvent partir en parallèle : aucun ne touche un fichier de M6-FIX-2 ni de M6-16
(`director.md`, `storyteller.md`, `dreamer.md`, `ToolCatalog`), et M6-08, M6-12 et FORGE-ABANDON sont fusionnés.
M6-FIX-4 passe en premier s'il faut choisir, puisqu'il porte le constat important. M6-FIX-5 garde l'option
`propose_idea` pour la fin : c'est elle qui touche le plus de fichiers, pour un gain faible (deux lectures des idées
ouvertes par `propose_idea`). Si le temps manque, l'abandonner.

**Hors lots.**
- **Pour M6-16** :
  - `dreamer.md:57` : y ajouter la part de `plain`, et préciser que le registre dominant se compte parmi les registres
    de la voix (passation de FIX-1) ;
  - décider du retrait de `sql` au Scénariste (environ 3 100 tokens de préfixe par programme, passation de M6-07).
- **Pour la partie 3**, vu en vérifiant que `main` n'avait pas touché aux lots :
  - M6-08 n'enregistre aucun rendu pour les idées de `<programme>` (`MemoryBriefing.programme` rend un `Built` sans
    `Showing`) : seuls le battement (`RecallChannel.HEARTBEAT`) et `inspire` en enregistrent, comme il le faut pour
    `makeRoom` et `<ideas>` ;
  - la page Style de M6-12 compte ses répliques par `StyleReport.lines` (`web/pages/StylePage.kt:167`), qui inclut
    les titres, la barre d'action et les livres (voir le constat 8).
- **Pour M6-FIX-2** (constat 2 de la partie 1) : une page fait plusieurs requêtes dans une seule lecture, chacune
  bornée à 5 s (7 pour `/turns/<id>`, par `TraceQueries.turn`). Une fois l'appelant parti à 5 s, la connexion peut
  rester prise jusqu'à environ 35 s. Avec le correctif prévu (permis rendu à la vraie fin), le tableau de bord répond
  503 pendant ce temps. C'est correct, mais à savoir. L'option « ne pas lancer une requête abandonnée » de
  `DuckJournal.read` en réduit l'effet.
