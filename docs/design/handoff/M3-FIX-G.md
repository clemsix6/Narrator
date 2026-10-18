# Passation M3-FIX-G : finitions

Branche `worktree-agent-add0f78d69c8caa56`. Ce lot corrige les constats **22, 24** (le reste) et **25** (la plomberie) de `docs/design/reviews/M3_REVIEW.md`, et reprend les points que les lots A à F lui ont laissés. Il passe après la fusion des lots A à F. **En cas de contradiction, le code fait foi.**

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `memory/MemoryState.kt` (nouveau) | `MemoryState` (`state`, `setState`, `deleteState`) et `DuckMemoryState(journal, clock)` |
| `memory/Jdbc.kt` (nouveau) | la seule plomberie JDBC de la mémoire : `query`, `update`, `SqlArray`, lecteurs de colonnes |
| `memory/MemoryRows.kt` | ne garde que les lignes de la mémoire ; `Names.all` |
| `memory/MemoryStore.kt`, `DuckMemoryStore.kt`, `Memory.kt` | plus de `state` ni `setState` ; KDoc de `archive` ; notes sans échéance |
| `memory/MemoryModule.kt` | `LatePlayClock` supprimée : état, puis horloge chargée, puis magasin |
| `memory/DuckPlayClock.kt`, `MemoryJobs.kt`, `Reminiscence.kt`, `dream/Dreamer.kt` | reçoivent un `MemoryState` |
| `memory/JobsData.kt` | plus de `deleteState` |
| `memory/MemoryTypes.kt`, `memory/Recall.kt` | vocabulaire mort retiré |
| `memory/Hygiene.kt`, `MemoryBriefing.kt` | `NOTE_SHOWINGS` ; plomberie partagée |
| `memory/search/SearchIndex.kt`, `HybridRecall.kt` | plomberie partagée |
| `memory/stats/SheetStats.kt`, `PairStats.kt`, `Favor.kt`, `Tics.kt`, `Reactions.kt`, `StatsRefresher.kt` | plomberie partagée, `SheetStats.query` supprimé |
| `memory/dream/DreamInputs.kt`, `memory/chronicle/ExchangeLog.kt` | lecteurs de colonnes partagés |
| `command/MemoryCommands.kt` | notes sans échéance |
| `command/AdminCommands.kt` | `turnsLine` cite les engagements dus |
| `prompts/director.md` | une phrase sur la fiche « could not be read in time » |
| `sql/views.sql` | commentaires de `mem_memories` sans `reflection` |
| `docs/design/M1_M2.md`, `docs/design/M3.md` | `maxCellChars` 400 ; §17 ; énumérations, `memory_state`, `catch_up` |

## Constat 22 : `MemoryState`

- **`MemoryState`** tient les clés du code dans `memory_state` : l'horloge du monde et sa sauvegarde (`DuckPlayClock`), la pause, le rêve en attente, les étapes d'un rêve, la fin du dernier rêve et `memory:since` (`MemoryJobs`), les réflexions du Rêveur (`reflected:<uuid>`), le jour du dernier anniversaire (`Reminiscence`).
- **Ce ne sont pas des écritures de mémoire** : ni règle, ni audit (`memory_log`), ni décision. Chaque changement est un travail `Journal.write` court (10 s au plus), chaque lecture une lecture du pool (5 s au plus). `MemoryStateTest` le vérifie.
- **Écart avec la relecture : une interface et sa classe**, `MemoryState` et `DuckMemoryState`, et non une seule classe. Les tests de l'ordonnanceur tournent en temps virtuel, sans Journal : ils ont besoin d'un faux, comme pour `JobsData` et `DuckJobsData`. Le faux est `testing/FakeMemoryState` (ses `values`), qui remplace `FakeMemoryStore.state`.
- **Noms** : ceux de la mission (`state`, `setState`, `deleteState`). Dans le code, le champ s'appelle `state`, d'où `state.state(PAUSED_KEY)`.
- **`LatePlayClock` disparaît.** `MemoryModule.assemble` construit l'état, puis l'horloge, la charge (`StartupReads`, inchangé), et seulement ensuite le magasin, qui reçoit l'horloge chargée.
- **`JobsData.deleteState` disparaît** : l'ordonnanceur passe par `MemoryState.deleteState`.
- **Ce qui reste hors de `MemoryState`, et c'est voulu** :
  - `DuckMemoryStore.revertRun` supprime les `reflected:<uuid>` du rêve annulé dans **son** travail d'écriture : c'est la même transaction que l'annulation ;
  - `DreamInputs` lit toutes les réflexions d'un coup, dans sa propre lecture.
- `MemoryStore` n'a plus `state` ni `setState`. Ni `DisabledMemory` ni `Memory` n'exposent l'état : personne hors de la mémoire ne le lisait.

## Constat 24 : vocabulaire mort

Supprimé :

- `RecallPurpose.JOIN` et `DREAM` ;
- `MemoryKind.REFLECTION` : personne ne l'écrivait (aucune sorte de `remember`, et le magasin la refuse au Rêveur depuis le lot A), personne ne la lisait, `V2__memory.sql` n'a pas de `CHECK` sur `memories.kind`, et aucun prompt ne la nomme. Les commentaires de la vue `mem_memories` (`views.sql`, rejoué à chaque ouverture) disent maintenant « episode or observation » ;
- `RunKind.STATS` : aucun travail `stats` n'a de ligne `memory_runs` (les stats écrivent une décision `memory stats`) ;
- `NoteDraft.expiresAt`, et avec lui `Note.expiresAt`, la colonne dans `NOTE_COLUMNS`, le filtre d'`openNotes`, le compte et le détail de `/narrator memory status` et `show`. Personne ne la posait : le filtre ne servait pas.

**Non touché :** `V2__memory.sql`, migration déjà appliquée sur les mondes de dev. Il en reste :

- `notes.expires_at`, colonne toujours nulle, que plus rien ne lit ni n'écrit ;
- le commentaire `-- episode | observation | reflection` de `memories.kind` ;
- le commentaire de `memory_runs.trigger`, sans `catch_up` (voir le lot C).

Une ligne `memories.kind = 'reflection'` ou `memory_runs.kind = 'stats'` ferait maintenant échouer sa lecture (`unknown MemoryKind`, `unknown run kind`). Aucune n'a jamais été écrite.

`RunTrigger.STARTUP` reste : il marque le premier rattrapage (lot C).

## Constat 25 : une seule plomberie JDBC

`memory/Jdbc.kt` :

- `Connection.query(sql, paramètres…, timeoutSeconds = 10) { ligne }` et `Connection.update(sql, paramètres…, timeoutSeconds = 10)`. Chaque instruction est préparée, a son délai, ferme son `ResultSet`. Liaison : un `Instant` en UTC, un `SqlArray` en tableau typé, `null`, le reste tel quel ;
- `SqlArray`, `uuidArray`, `longArray`, `stringArray` ;
- les lecteurs de colonnes : `instant`, `instantOrNull`, `longOrNull`, `intOrNull`, `doubleOrNull`, `uuid`, `uuids`, `longs`, `strings`.

Passent par lui, en plus du magasin, de `JobsData`, des entrées du rêve et du journal d'échanges, qui l'utilisaient déjà :

| Où | Avant | Délai |
|---|---|---|
| `SearchIndex`, `HybridRecall` | leurs `rows`, `update`, `array` | 10 s (inchangé) |
| `Hygiene` | son `query` sur `createStatement` et une instruction à la main | 20 s (inchangé) |
| `DuckPlayClock` | deux instructions à la main | 20 s (inchangé) |
| `SheetStats`, `Rates`, `PairStats`, `Favor`, `Tics`, `StatsRefresher.activeSince` | `SheetStats.query` (supprimé) | 30 s, `SheetStats.QUERY_TIMEOUT_SECONDS` (inchangé) |
| `Reactions` | quatre instructions à la main, **sans délai** | 30 s |
| `MemoryModule.schemaProblems` | `createStatement`, **sans délai** | 10 s |

Doublons supprimés en passant : `HybridRecall` (`strings`, `uuids`), `DreamInputs.uuidAt`, `ExchangeLog.doubleOrNull`, et `DuckMemoryStore.Job.playerNames`, qui relisait `players` alors que `Names` la lit déjà (`Names.all`, signalé par le lot A).

Changements de SQL, sans effet sur les résultats :

- `Reactions` et `StatsRefresher.activeSince` lient leurs instants en paramètres, au lieu d'un `CAST(? AS TIMESTAMPTZ)` sur du texte ou d'un littéral ;
- les requêtes sans paramètre (`Tics`, stats) sont préparées, et non plus passées à `createStatement`. `TicsTest` relève donc le délai sur un `PreparedStatement` ;
- `Favor.toward` lit ligne à ligne puis compte, au lieu de compter dans la boucle du `ResultSet`.

**Laissés tels quels :**

- `SheetStats.write` et `PairStats.write` préparent une instruction et l'exécutent pour chaque ligne, dans un travail d'écriture. `update` la préparerait à chaque ligne ;
- la plomberie hors de `memory/` (`journal/Repair.kt`, `director/Briefing.kt`, `director/Chapters.kt`, `trace/TraceQueries.kt`, `tool/oracle/OracleTools.kt`, `guard/DeathReport.kt`, `observer/StatsImport.kt`) : hors du périmètre du lot.

## Points reportés par les lots

- **Lot E** : `Hygiene.COHERENCE_SHOWINGS` devient `NOTE_SHOWINGS`. L'alias `MemoryBriefing.NOTE_SHOWINGS` est supprimé : `MemoryBriefing` lit `Hygiene.NOTE_SHOWINGS`.
- **Lot D** :
  - KDoc de `MemoryStore.archive` : une ligne archivée par le code reste trouvable avec `include_closed`, pas une ligne oubliée par un opérateur ;
  - `AdminCommands.turnsLine` : `none left: only addressed chat, prayers, whispers and due commitments wake it` (et `StatusLinesTest`) ;
  - `director.md`, « Ta mémoire », bloc `<memory>` : « « could not be read in time » : la fiche du joueur n'a pas pu être lue à temps ; `player_profile` te la donne si tu en as besoin. » Aucune balise de section, seul `player_profile` entre accents graves, 27 735 caractères sur 30 000. Rien d'autre n'a changé dans le prompt ;
  - **Non fait** : remplir `RecallContext.turnId` à la validation (suggestion du lot D, hors de la liste du lot G).
- **Lot F** : `docs/design/M1_M2.md` donne `maxCellChars = 400`.
- **Lot C** : `docs/design/M3.md` §17 a `memory.dreamMinHours` (6, de 0 à 24) et `memory.catchUpDays` (2, de 0 à 30), et note que `catch_up` est une valeur de `memory_runs.trigger` absente du commentaire de `V2__memory.sql`.
- `docs/design/M3.md` suit aussi le code pour `MemoryKind`, `RunKind`, `RecallPurpose`, les colonnes mortes du schéma et `memory_state` hors de `MemoryStore`.

## Ce que les tâches suivantes doivent savoir

- **`memory_state`** : `MemoryStore.state` et `setState` n'existent plus. On passe par `DuckMemoryState(journal, clock)` ou par du SQL. Dans les tests : `StoreFixture.state`, `DreamerFixture.state`, ou `FakeMemoryState().values`.
- **Constructeurs changés** :
  - `DuckPlayClock(state, journal, players)` ;
  - `MemoryJobs(services, store, state, data, chronicler, dreamer, hooks)` ;
  - `Reminiscence(recall, store, state, journal, …)` ;
  - `Dreamer(services, agents, hygiene, state)`.
- **SQL de la mémoire** : `import io.github.clemsix6.narrator2.memory.query` (et `update`, `SqlArray`, `stringArray`…). `memory.search.rows`, `array` et `update`, et `SheetStats.query` n'existent plus. Un délai autre que 10 s se passe par `timeoutSeconds =`.
- **M3-19 (recette, en parallèle)** : ses nouveaux fichiers de test doivent suivre ces noms s'ils lisaient `store.state`, `FakeMemoryStore.state` ou les aides de `memory.search`.

## Vérification

- Avant ce lot (`main` à `78aa639`) : `.\gradlew.bat test`, **1 141 tests unitaires**, 0 échec.
- Après :
  - tests unitaires : **1 143**, 0 échec, 0 sauté. Aucun test n'a disparu : les deux tests de l'état (`DuckMemoryStoreTest`, `DuckJobsDataTest`) sont passés dans `MemoryStateTest`, et le test des notes ne vérifie plus l'échéance (« notes are shown, then done »). Deux tests nouveaux, `JdbcTest` : le délai par défaut et celui de l'appelant, la liaison d'un instant, d'un tableau et de `null` ;
  - `.\gradlew.bat build` (tests unitaires puis `runGameTest`) : **vert**, `All 171 required tests passed`. Le log des GameTests n'a aucun `WARN` ni `ERROR` de la mémoire (`the memory is on` une fois).
- `git merge main` : rien à fusionner, `main` n'a pas bougé depuis `78aa639`.
- Lignes : dans `src/main/kotlin`, 402 ajoutées et 494 retirées (−92 nettes, `Jdbc.kt` et `MemoryState.kt` compris) ; dans les tests, +98 nettes (`MemoryStateTest`, `JdbcTest`).
- Aucun appel réel à Claude ni à Jev ; pas de `runServer`.
