# Passation M2-fix-G : factorisation et finitions de M2

Branche `worktree-agent-a0183e4de88b61020`. Dernier lot de la relecture de M2 (`reviews/M2_REVIEW.md`) : constat **16** (factorisation), les défauts laissés par les passations T22, M2-fix-A, B, CD, EF, H, I, J et A2, `director.md` et la documentation. Fusionné avec `main` après les lots H, I et A2. **En cas de contradiction, le code fait foi**, et ce fichier explique pourquoi.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `tool/ToolText.kt` | **nouveau** : `ToolText` (durée, attente, nombre, chiffres romains, dimension, noms échappés), `shownName`, et les aides de toutes les familles : `refuse`, `answer`, `online`, `onlineNames`, `notOnline`, `punish`, `chaos`, `at`, `live` |
| `tool/ToolLimits.kt` | **nouveau** : `ToolLimits` (un par serveur) et `TurnCounter` |
| `runtime/NarratorServices.kt`, `NarratorRuntime.kt`, `testing/TestServices.kt` | `toolLimits` |
| `tool/speech/*`, `tool/action/*`, `tool/command/CommandTools.kt`, `tool/oracle/OracleTools.kt`, `tool/pacing/PacingTools.kt`, `tool/sql/SqlTool.kt`, `tool/task/TaskTools.kt`, `tool/ToolRunner.kt` | aides communes, durées et dimensions communes, noms échappés, plus aucun `WeakHashMap` ni compteur « par tour » à eux |
| `oracle/OracleText.kt`, `PlayerReport.kt`, `PlayerSnapshot.kt`, `StatSummary.kt` | `duration`, `dimension`, `dimensionId`, `level` retirés au profit de `ToolText` ; `ago` garde sa forme `[-3m]` ; noms échappés |
| `director/EventFormatter.kt`, `Briefing.kt`, `SituationReport.kt`, `Director.kt`, `agent/Workers.kt` | `ago` et `dim` retirés au profit de `ToolText.duration` et `ToolText.dimension` |
| `guard/TargetingPolicy.kt`, `EffectLedger.kt`, `DeathReport.kt`, `DeathGuard.kt`, `GuardsImpl.kt`, `command/GuardCommands.kt` | `span` et `roman` retirés ; durées communes ; noms échappés dans les refus |
| `core/ServerThread.kt` | `runOnServerThread` (sans attente, jamais hors du thread serveur) |
| `guard/GuardsImpl.kt` | `armDeathGuard` par `runOnServerThread` |
| `command/Replies.kt` | `replyOn` : les réponses différées passent par `onServerThread` |
| `NarratorRuntime.kt` | `tellOperatorsOnline` par `runOnServerThread` |
| `director/DirectorControl.kt`, `Director.kt`, `command/AdminCommands.kt` | `DirectorStatus.turnsThisHour` et `turnsPerHour`, ligne `Director:` de `/narrator status` ; KDoc de `post` |
| `guard/NarratorMobs.kt` | `activeOn` : échéance réelle d'une monture gardée sous son cavalier |
| `llm/ClaudeClient.kt`, `Conversation.kt` | `X-Session-ID` stable par rôle, `X-Narrator-Conversation` |
| `command/TraceCommands.kt`, `trace/TraceRecords.kt` | `rescued` au lieu de `prevented` (A2) |
| `journal/Journal.kt` | KDoc d'`IdKind` (réservations du lot J) |
| `resources/narrator2/prompts/director.md` | voir plus bas |
| `docs/design/M1_M2.md` | §5.4, §9 (textes communs, §9.2, §9.4 : `thunder`, dimensions, hostilité de `spawn`), §12.3 (budgets et battement), §12.6, §15 (`turnsPerHour`), §20 n° 12 |
| `docs/design/handoff/T16.md`, `docs/ARCHITECTURE.md` | note sur `thunder` ; affinité de session par rôle |

## 1. Factorisation (constat 16)

### Un seul format de durée

- **`ToolText.duration(ms)`** : `36s`, `4m05s`, `25m`, `1h12m`, `2d4h`. C'est le format qu'avaient déjà `EventFormatter.ago` et `OracleText.duration` (arrondi à la seconde la plus proche, secondes montrées sous 10 min), avec les jours d'`OracleText`. `EventFormatter.ago` écrivait `26h40m`, il écrit `1d2h`.
- **`ToolText.wait(ms)`** : même format, **arrondi au-dessus**, pour dire quand une chose redevient possible. Une attente non finie ne s'écrit jamais `0s`.
- Remplacés : `TargetingPolicy.span` (`4m30s`, arrondi au-dessus, secondes jusqu'à 1 h), `SpeechLimiter.describe` (`3 min 10 s`), le `describe` de `remind_me` (`12m30s`), `EventFormatter.ago`, `OracleText.duration`. Et les durées en `N s` ou `N min` des résultats : `for 60 s` devient `for 1m`, `in 5 min` devient `in 5m`, `exploding after 1 s` devient `after 1s`, ainsi que `set_weather`, le délai de `teleport`, les expirations d'outil (`did not finish within 5s`), le `TIMEOUT` de `sql`, le délai d'une tâche (`no report within 3m`), les décisions `death_guard died` (`armed for 5s of play more`, `in the last 1m`, `apply_effect 40s before`) et `death_guard rescued`.
- **Exception voulue** : les bornes d'un argument restent dans l'unité de l'argument (`lasts at most 300 s (asked 400 s)`, `seconds must be between 1 and 900`). Le modèle doit corriger un nombre de secondes : `5m` l'obligerait à convertir.
- `Briefing.span` n'est pas une durée : c'est une plage horaire (`20:02–21:38`). Je ne l'ai pas touchée. Les durées du briefing passent par `ToolText.duration`.
- Les commandes d'opérateurs (`TraceText.duration`, `8.1 s`) gardent leur format : le modèle ne les lit pas. `GuardCommands` utilise `ToolText`, faute de `TargetingPolicy.span`.

### Aides partagées

Dans `tool/ToolText.kt` : `refuse`, `answer` et `Refusal` (sortis de `PlayerActionTools`), `ToolContext.online(names)` et `online(name)` (refus avec la liste des joueurs en ligne), `onlineNames`, `notOnline`, `punish`, `chaos`, `at`, `MinecraftServer.live`, `ToolText.number` (`12.25`), `ToolText.roman` (aussi pour `EffectLedger.describe` et l'Oracle), `ToolText.names`. Les copies de `PlayerActionTools`, `WorldActionTools`, `CommandTools`, `SpeechTools` et `OracleTools` sont supprimées. Les messages ne changent pas, sauf l'échappement des noms. `EventFormatter.number` et `OracleText.number` (une décimale, pour la vie) restent : ce n'est pas la même règle.

### `ToolLimits`

- `NarratorServices.toolLimits` : `speech` (`SpeechLimiter`), `gifts` (`GiftLimiter`), `world` (`WorldLimits`), `commands` (`TurnCounter` de `run_command`). Construit par `NarratorRuntime` avec le serveur, et par `TestServices`. Les trois `WeakHashMap` (`SpeechLimiter.of`, `GiftLimiter.of`, `WorldLimits.of`) ont disparu.
- **`TurnCounter`** remplace les deux compteurs « par tour » (`say` et `run_command`). Il compte par (conversation, tour) et garde les 16 derniers tours. L'ancien code gardait un seul tour et remettait le compte à zéro dès qu'un autre tour appelait : deux conversations qui appellent le même outil à tour de rôle se remettaient mutuellement à zéro. `run_command` comptait par instance d'outil, donc par conversation ; il compte maintenant par serveur, et ses tests en jeu construisent leur propre `TurnCounter`.
- `GiveItemTool.limiter` est `internal`, pour qu'un test vérifie le câblage.

### Noms de joueurs dans les résultats d'outils (demande du superviseur, suite du lot I)

`ModelText.name` partout où un résultat d'outil ou un refus cite un joueur :
- les refus « not online » (toutes les familles) ;
- les réponses de la parole (`whispered to`, les attentes, `give_book`) ;
- les actions sur les joueurs et sur le monde (`shownName`) ;
- `list_players`, `inspect_player` (en-tête, joueurs proches), `scan_area` (lits et à qui, blocs posés par qui), `area_info`, `locate`, `player_stats` (y compris un joueur hors ligne lu dans le Journal) ;
- les refus des garde-fous (`TargetingPolicy`, `protected` de `GuardsImpl.hostile`).

La sortie de `run_command` est aussi échappée (`ModelText.lines`), parce que `list` ou un tableau de scores citent des noms. Les cellules de `sql` le sont par le lot H.

## 2. Défauts

- **`armDeathGuard` hors du thread serveur** : après l'arrêt, Minecraft exécute une tâche soumise sur le thread appelant, et l'ancien code se re-soumettait en boucle. Nouveau `BlockableEventLoop.runOnServerThread(block)` : il exécute tout de suite sur le thread serveur, sinon il soumet une tâche qui ne s'exécute que sur le thread serveur, et qui est abandonnée (retour `false`, log DEBUG) quand le serveur est arrêté. Même correctif pour `NarratorRuntime.tellOperatorsOnline` (lot J), qui lisait `playerList` depuis le thread appelant après l'arrêt : **ajout de ma part**.
- **`Replies.kt`** : `replyLater` délègue à `replyOn(loop, scope, …)`, qui envoie les lignes par `onServerThread` (délai de 10 s). `ServerStoppedException` : abandonné (DEBUG). Délai dépassé : abandonné (WARN).

## 3. Petites fonctions

- **`/narrator status`** : `Director: idle · turns this hour 12 of 30 (18 left) · inbox 0 · …`. Une fois le budget épuisé : `(none left: only addressed chat, prayers and whispers wake it)`. `DirectorStatus` gagne `turnsThisHour` (`TurnBudget.used`) et `turnsPerHour` (réglage en vigueur), sans valeur par défaut (contrat de T01, les faux sont mis à jour).
- **`<situation>`** : `NarratorMobs.overtime` (les montures gardées sous leur cavalier) devient une `ConcurrentHashMap` lisible par `activeOn`. Une monture gardée est listée à part : `Bartok 1 minecraft:strider, ridden past its time until 21:50`, avec l'échéance réelle (`expiresAt + MAX_OVERTIME_MS`, ou la prochaine tentative d'atterrissage au-delà). Vaut aussi pour `inspect_player` et `/narrator guards`.

## 4. Dimensions : une seule forme canonique, `the_nether`

- **Décision** : ce que le modèle lit écrit une dimension par son identifiant sans `minecraft:` : `overworld`, `the_nether`, `the_end`. T22 proposait l'inverse (les actions passeraient par `OracleText.dimensionId`, en gardant `nether` comme forme lue). J'ai choisi l'identifiant pour trois raisons :
  - c'est déjà la forme des vues du Journal (`bare(dim)`), qu'on ne pouvait pas changer sans toucher `views.sql` et la description de `sql` (24 caractères de marge) ;
  - c'est la forme de `run_command` (`execute in the_nether`) ;
  - la règle « identifiants sans `minecraft:` » devient la seule.
- **En argument**, tous les outils acceptent `the_nether`, `nether`, `minecraft:the_nether`, `Nether`, et de même pour `end` (`ToolText.dimensionId`). Les actions sur le monde passent par elle, et leur refus liste les dimensions sous leur forme canonique.
- Changent donc : l'Oracle, les événements (`DIMENSION Bartok overworld → the_nether`), la situation et le briefing.

## 5. Cache entre conversations

- **`X-Session-ID: narrator2-<rôle>`** (`ClaudeClient.sessionOf`) : toutes les conversations d'un rôle (chapitres, clôtures, tâches de l'Analyste, pings) demandent la même affinité, donc le compte amont où leur préfixe est en cache. L'identifiant de conversation part dans **`X-Narrator-Conversation`** (`ClaudeClient.CONVERSATION_HEADER`). Les traces et les transcriptions gardent l'identifiant de conversation ; les en-têtes des deux figurent dans la transcription JSONL.
- **`FakeAnthropic` strict** : ses sessions (vérification de l'ajout seul, scripts `enqueueFor`) sont indexées par `X-Narrator-Conversation` (`RecordedRequest.conversation`). La vérification n'est pas affaiblie, et une vérification s'y ajoute : une conversation qui change d'`X-Session-ID` en cours de route est rejetée (`moved from gateway session …`). Deux conversations de la même affinité sont vérifiées chacune pour soi (`FakeAnthropicTest`).
- **Tests** : `ClaudeClientTest` (deux chapitres et une tâche : `narrator2-director` ×2, `narrator2-analyst`, identifiants distincts), `TranscriptsTest`, `DirectorEndToEndTests` (les deux chapitres de la recette sur `narrator2-director`), et les tests qui lisaient la conversation dans `X-Session-ID` (`DirectorHarness.sessionOf`, `WorkersTest`, `DelegationScenarioTest`).
- **`liveTest`** (`LiveDirectorTest` c), deux tâches de l'Analyste à 20 s d'écart, tunnel ouvert par moi puis refermé. **Vert, assertion ajoutée** : la 1ʳᵉ requête de la 2ᵉ tâche **relit 5 875 tokens** du cache (et en écrit 3 001 : son premier message, propre à la tâche) ; la 1ʳᵉ de la 1ʳᵉ tâche en écrivait 8 873. Avant, T22 mesurait 0 relu et 8 862 réécrits. Coût de l'essai : **0,119 $**. Je n'ai pas mesuré le passage d'un chapitre à l'autre, qui suit le même mécanisme.
- **`AgentLoop`** : rien à changer, les en-têtes sont posés par `ClaudeClient`.

## 6. `director.md`

- Hors des outils : « termine ton tour sans rien écrire. Pas de compte rendu […] ; une ligne au plus » (T22, constat 2 : 130 à 400 tokens de sortie par tour).
- `<heartbeat>` : il peut être vide (réveillé par ce qui attend), `hint` le plus souvent absent. Le chat entre joueurs ne réveille pas.
- Plus d'orage : la pluie et `lightning`. Toute créature qui peut blesser est un châtiment (lot B).
- « Les refus du moteur » réécrit d'après les lots A, B, EF et A2. La phrase « aucun bloc modifié », trop large, est retirée : tes outils ne posent ni ne cassent aucun bloc, et tes créatures n'abîment pas ce que les joueurs ont posé. Ajouts : protection, `fair_share`, plafonds hors chaos (résistance III, enchantement max + 1, objets précieux), bienfait qui ne raccourcit pas un effet, et **au-delà du quota de morts, le sauvetage** (point de réapparition, trois cœurs, effets nuisibles retirés), à traiter en ressort comique.
- `PromptToolMentionsTest` passe (seul `lightning` est ajouté entre accents graves).

## 7. Documentation

`M1_M2.md` §5.4 (réservations du lot J), §9 (textes communs, dimensions, `set_weather` sans `thunder`, hostilité de `spawn` par liste blanche), §12.3 (le chat entre joueurs ne réveille plus, budgets par joueur et global, valeur du battement), §12.6, exemple de configuration (`turnsPerHour`), §20 n° 12 ; `T16.md` (note sur `thunder`) ; `ARCHITECTURE.md` (affinité par rôle) ; KDoc d'`IdKind` et de `DirectorControl.post`. La KDoc d'`Item.isUnaddressedChat` avait déjà été corrigée par le lot I.

## Suites des lots A2, H et I

- **A2** : `TraceCommands.REFUSALS` et la KDoc de `DecisionRecord` citent `rescued` (et `died`), plus `prevented` ; un test vérifie qu'un sauvetage s'affiche en rouge dans `why`. Les durées de la décision `rescued` passent par `ToolText`. **Les coussins de chute ne sont pas factorisés** : `NarratorMobs.land` n'a accès qu'à l'interface `Guards` (le `DeathGuard` est propre à `GuardsImpl`), et fusionner les deux aurait touché l'interface pour un gain faible. `DeathGuard.cushion` reste sans armement.
- **GameTest instable** `GuardPolicyGameTests.theNarratorsCreeperBreaksNothingButHurtsMonsters` (« an ordinary creeper breaks the planks ») : le creeper témoin, ordinaire, **disparaissait à son premier tick** dès qu'un joueur factice d'un autre test se trouvait à plus de 128 blocs. Vérifié en bytecode : `Mob.checkDespawn` fait un `discard()` quand le joueur le plus proche est au-delà de la distance de disparition de sa catégorie, sauf pour une créature persistante. Il n'explosait donc jamais. Celui du Narrateur est persistant (`NarratorMobs.mark`). Correctif : `setPersistenceRequired()` sur le témoin. La contre-épreuve reste entière : il n'a pas de tag, son explosion reste ordinaire et doit casser les planches.

## Limites

- **Contre-épreuves non faites** : le retrait temporaire des correctifs (pour montrer que chaque test échoue sans le sien) a été refusé par le garde-fou automatique de l'environnement, et je ne l'ai pas contourné. Chaque test vise pourtant le comportement exact de son correctif :
  - `ServerThreadTest` fait tourner l'ancienne forme de `armDeathGuard` et montre qu'elle boucle ;
  - `RepliesTest` : un serveur arrêté, rien n'est envoyé ;
  - les tests de `ToolLimits` : un outil d'un autre chapitre refuse ce que le serveur a déjà compté ;
  - `WakeBudgetScenariosTest` : budget épuisé, puis revenu ;
  - `GuardGapGameTests` : l'échéance de la monture ;
  - `WorldActionGameTests` : `nether` accepté ;
  - `FakeAnthropicTest` et `ClaudeClientTest` : les en-têtes.
- La macro SQL `ago()` du Journal garde sa forme à une seule unité (`42s ago`, `5m ago`, `3h ago`, `4d ago`) : mêmes lettres, mais moins précise. La changer touche `views.sql` et la description de `sql`, dont la marge est de 24 caractères.
- `M1_M2.md` §5.3 et §13 disent encore que `conversation_id` est « aussi l'`X-Session-ID` » (le commentaire de `V1__journal.sql` aussi : migration, je n'y touche pas). Le §20 n° 12 le signale.
- Un `TurnCounter` garde 16 tours : au-delà de 16 conversations qui alterneraient dans le même tour, un compte pourrait repartir de zéro. Aucun rôle n'en a plus d'une qui parle ou lance des commandes aujourd'hui.
- Pas de test en jeu avec un joueur nommé `<engine>` : les tests unitaires couvrent chaque chemin de nom (`ToolTextTest`, `SpeechToolsTest`, `SpeechLimiterTest`, `PlayerActionToolsTest`, `WorldActionToolsTest`, `OracleToolsTest`, `TargetingPolicyTest`), et les résultats qui passent par le serveur utilisent les mêmes `shownName` et `names`.

## Pour la suite

- **M3** : toute durée, tout niveau et toute dimension destinés au modèle passent par `ToolText`. Toute limite qui doit survivre à un chapitre va dans `ToolLimits`. Un nouveau champ de `NarratorServices` doit être ajouté aux faux (`TestServices`).
- **Tests** : la conversation d'une requête se lit par `RecordedRequest.conversation`, et l'affinité par `.session`. `enqueueFor(id)` prend l'identifiant de conversation.
- **Superviseur** : le coût de sortie du Directeur (compte rendu hors outils) mérite une mesure dans la prochaine recette réelle.

## Vérifications

- `git merge main` (lots H, I, A2) : sans conflit.
- `.\gradlew.bat build` : **739 tests unitaires et 169 GameTests, tous verts**.
- `runGameTest` relancé 3 fois de plus (`--rerun-tasks`, recompilation comprise) : **169/169 à chaque fois**, le test du creeper compris.
- `liveTest --tests …LiveDirectorTest.c*` : vert, 0,119 $. Le tunnel (mon processus `ssh`) a été refermé ; aucune clé dans les fichiers.
- Pas de `runServer`.
