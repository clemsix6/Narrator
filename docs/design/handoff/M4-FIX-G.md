# Passation M4-FIX-G : Directeur, mémoire, coût (relecture de M4)

Lot G des corrections de la relecture de M4 (`design/reviews/M4_REVIEW.md`) : constats **5**, **6**, **31** (côté
`ENGINE_NEWS`), **32** et **41**, plus les deux points connus **`PairStats`** et **`JoinBrief`**. Les décisions du
superviseur (C31) priment. **En cas de contradiction avec `M4.md`, avec la relecture ou avec les passations M4-08,
M4-10, M4-22 et FLAKY-2, le code et cette passation font foi.**

## Fichiers

| Fichier | Changement |
|---|---|
| `director/Director.kt` | rappels gardés dans le monde jusqu'à ce qu'on les ait dits (C5) ; sous-budget des offrandes (C6) |
| `director/WakeScheduler.kt` | `PLAYER_NEWS_PER_HOUR`, `byPlayer`, `wokenByPlayers`, `pastBudget(item, playerNewsSpent)` ; `TurnBudget.playerNewsSpent/Used` |
| `memory/chronicle/ExchangeLog.kt` | `RULE_ENDED` dans `ENGINE_NEWS`, `detail.ending` montré ; `Line.worthABatch` |
| `memory/chronicle/Chronicler.kt` | une fenêtre sans raison de lot est sautée (le registre seul n'en est pas une) ; KDoc et message justes |
| `memory/Dedup.kt` | les lignes du moteur comparées quels que soient les joueurs ; résultats de défi ; deux offrandes |
| `memory/JoinBrief.kt` | les plans qui visent le joueur dans « While away » |
| `memory/stats/PairStats.kt` | `store` : des travaux de 100 paires, une requête chacun |
| `prompts/director.md`, `prompts/chronicler.md`, `sql/views.sql` | constats 32 et 41 |
| tests | `DirectorMechanicsScenariosTest` (+2), `WakeBudgetScenariosTest` (+1), `WakeSchedulerTest` (+2), `ExchangeLogTest` (+1), `ChroniclerTest` (+1), `DedupTest` (nouveau, 3), `JoinBriefTest` (+1), `PairStatsTest` (+1) |

Hors de la liste du lot, signalé :
- **`memory/stats/StatsRefresher.kt`** : l'appel des paires devient `PairStats.store(journal, computed, WRITE_TIMEOUT_MS)`,
  et sa KDoc le dit. Aucun autre lot ne touche ce fichier.
- **`docs/ARCHITECTURE.md`** (les offrandes, §9.3) et **`docs/MEMOIRE.md`** (la ligne du Chroniqueur, §4).

`Inbox.kt` et `DirectorControl.kt` n'ont pas changé : le sous-budget tient dans `TurnBudget`.

## Constat 5 : un rappel échu ne quitte le monde qu'une fois dit

Correctif retenu : le premier de la relecture (« ne retirer un rappel du monde qu'une fois envoyé le tour qui le
porte »), pas la variante de l'arrêt.

- `releaseReminders` met le rappel échu dans la boîte et le marque `inInbox` ; il **reste dans le monde**. Seul un
  rappel de plus de 24 h de retard en sort aussitôt, avec sa décision `wake dropped`, comme avant.
- Il sort du monde (`forgetReminders`, sauvegarde) quand :
  - une requête qui le porte est acceptée (`Turn.commit`, ouverture ou injection entre deux appels d'outils) ;
  - il est abandonné après trois échecs (`putBack`, la décision `refusal dropped` existante).
- Un Directeur désactivé jette sa boîte : les rappels qu'elle portait redeviennent « à venir » (`untellReminders`),
  et seront rendus quand il pourra les entendre, avec la règle des 24 h.
- `nextReminderAt` et `pendingReminders` ignorent les rappels déjà dans la boîte. Sans cela, l'attente de la boucle
  tomberait à zéro. Conséquence : `/narrator status`, la situation et le plafond de `remind_me` ne montrent plus un
  rappel échu qui attend dans la boîte (pendant une pause par exemple). C'était déjà le cas avant.
- Pause, blocage, attente après erreur ou budget de tours épuisé : le rappel attend dans la boîte **et** dans le
  monde. Si le serveur s'arrête entre-temps, le Directeur suivant le relit et le dit (en retard s'il est échu).

Limites, acceptées :
- **Au moins une fois** : un rappel dit juste avant l'arrêt peut l'être encore au démarrage suivant, si la sauvegarde
  qui l'oublie n'a pas atteint le thread serveur (`WorldReminderStore.save` abandonne une fois le serveur arrêté).
- Un `ReminderDue` chassé d'une boîte pleine (200 éléments, en tout dernier recours, `Inbox.trim`) reste dans le
  monde sans être dit avant le prochain démarrage. `Inbox` ne prévient pas de ses évictions ; je n'ai pas ajouté de
  crochet pour ce cas.

## Constat 6 : les offrandes ont un sous-budget horaire

- **Signal concerné** : `WakeScheduler.byPlayer(signal)` = un `Mechanic` prioritaire de sorte `OFFERING`. C'est le seul
  signal prioritaire qu'un joueur envoie de lui-même : les fins de défi et de duel et les `JUDGE` sont bornés par le
  Directeur, qui les lance.
- **Plafond** : `WakeScheduler.PLAYER_NEWS_PER_HOUR` = **4** réveils par heure glissante, tous joueurs confondus,
  comptés dans `TurnBudget` (`started(at, byPlayers)`, `playerNewsSpent`, `playerNewsUsed`). C'est une constante, pas
  une clé de configuration (`NarratorConfig` n'est pas à ce lot).
- **Ce qui compte** : un tour qui démarre budget épuisé et que seules des offrandes ont réveillé
  (`WakeScheduler.wokenByPlayers` : aucun chat adressé, prière, chuchotement, `DUE` ni autre nouvelle prioritaire ne
  l'aurait réveillé ; pas une reprise ni un battement). Un tour dans le budget ne compte pas. Un tour de sous-budget
  compte aussi dans le budget normal.
- **Au-delà** : `pastBudget(item, playerNewsSpent = true)` est faux pour une offrande. Elle reste `SOON` et attend
  qu'un tour quitte l'heure, ou part avec le réveil suivant (une prière, un `DUE`, une fin de défi).
- **Traces** :
  - la décision `wake woke` d'un tour de sous-budget finit par
    `; past the budget for the players' news (2 of 4 this hour)` ;
  - une offrande postée quand les deux budgets sont épuisés écrit `salience throttled`, sujet `OFFERING O-812`, raison
    `the budget of 30 turns per hour is spent, and so are the 4 wake-ups past it for the players' news this hour: it
    waits for a turn to leave the hour`.

Coût : 4 tours d'Opus de plus par heure au plus, au lieu de 48 (8 joueurs, `offeringMinutes = 10`) ou 240
(`offeringMinutes = 2`) dans le scénario de la relecture.

## Constat 31 (côté `ENGINE_NEWS`)

- `ExchangeLog.ENGINE_NEWS` lit `mechanic rule_ended` (`ExchangeLog.RULE_ENDED`), contrat du superviseur avec le lot D.
- La ligne montre `detail.ending` après le sujet, quand il dit autre chose que le nom de la décision :
  `[20:02:00] ENGINE mechanic rule_ended C-61 (removed): <raison>`. Les fins de défi (`detail.ending` = le nom de la
  décision) n'en ont pas : `ENGINE mechanic expired C-50: …`.
- `mechanic expired` reste dans la liste, pour les défis seulement : une fois D fusionné, aucune règle ne l'écrit plus.
  Les anciennes `disabled` des règles ne sont pas lues.
- `chronicler.md` cite « la fin d'une règle » parmi les lignes ENGINE.

## Constat 32 : le Chroniqueur ne double plus un résultat ni un défi du moteur

- **Canon** (`Dedup.canon`) : une ligne ouverte **suivie par le moteur** (`mechanic` non nul) est comparée quels que
  soient ses joueurs. Un défi « → all » (`players = []`) avale donc le `remember challenge` du Chroniqueur qui nomme
  les joueurs (même citation, ou énoncé similaire à 0,6), et le `DUE` en trop disparaît. Pas de fenêtre de temps : une
  ligne ouverte du moteur est un défi en cours (180 min au plus), une loi ou un plan actifs ; la similarité garde le
  filtre. Les lignes hors moteur gardent la règle des mêmes joueurs.
- **Interactions** (`Dedup.interaction`) : un `challenge_result` d'un autre que le moteur (le Chroniqueur, le
  Directeur, qui n'ont pas de `canon`) est le même que celui du moteur sans preuve, à 10 min près, dans les deux sens :
  la faveur ne bouge qu'une fois. Entre deux lignes du moteur, la règle de M4-12 tient (même entrée du canon). Les
  autres sortes (`punishment`…) ne changent pas.
- **Prompt** : `chronicler.md` dit « les résultats et enjeux des défis […] sont notés par le moteur ».

## Constat 41

| Point | Correctif |
|---|---|
| `director.md:166`, « échéance d'un défi » donné en exemple de rappel | exemple retiré |
| `director.md:197`, « `resolve` de celle-ci » pour une loi remplacée | « retirée par `remove_rule` si elle est (enforced), sinon par `resolve` » |
| `judging` et `notified` adressés au Directeur | `chronicler.md` : « Leurs consignes (judge it now…) visent le Narrateur. » |
| `narrator_actions` ignore les actes du moteur, `director.md` ne cite pas `engine_decisions` | commentaire de la vue : `(the engine's acts: engine_decisions)` ; `director.md` : « `sql` lit aussi les vues mem_… et engine_decisions (les actes du moteur) ». La vue elle-même n'a pas changé : le briefing (`Briefing.actions`) la lit pour les « dernières actions » du Directeur, qui doivent rester les siennes |
| une fenêtre d'une seule ligne `ENGINE` lance un lot, même pour un `ledger returned` | `ExchangeLog.Line.worthABatch` : chat, réplique, appel d'outil, nouvelle d'une mécanique (`mechanic`) ; **pas** un fait ni une nouvelle du registre (`ledger`). `Chronicler` saute la fenêtre sinon : `nothing to note: no chat, no line of the Narrator, no tool call and no news of a mechanic in the window`. Les lignes du registre restent dans le journal d'échanges comme contexte |
| deux offrandes d'un joueur prises à moins de 10 min : la seconde avalée | deux interactions `offering` **sans preuve** ne sont plus la même d'office : leurs textes décident (0,6). Chaque `favor` écrit la sienne. Une note du Chroniqueur avec preuve reste le doublon de celle du Directeur |

`director.md` dit aussi que « While away » montre les plans (point `JoinBrief`).

## `PairStats` : jamais plus de 100 ms d'écriture d'affilée

- `PairStats.store(journal, pairs, timeoutMs)` écrit les paires en travaux `Journal.write` de `PairStats.CHUNK` = **100**
  paires, l'un après l'autre : les lots d'événements et les autres travaux passent entre deux.
- Chaque travail fait **une seule requête** `INSERT OR REPLACE … VALUES (…), (…)` (900 paramètres), sans lot JDBC.
  `PairStats.write(connection, pairs)` reste pour les tests et découpe aussi par 100.
- Un travail qui échoue arrête les suivants ; le recalcul suivant réécrit tout (les paires sont recalculées sur tout
  l'historique). Entre deux travaux, un lecteur peut voir un mélange d'anciennes et de nouvelles lignes, chacune juste.
- **Mesure, 1 500 paires** (Journal en mémoire, test `PairStatsTest`, machine partagée avec d'autres builds) :
  - **avant** (une requête par paire, un seul travail) : **1 651 ms** d'un bloc. Avec la limite de mémoire du banc
    (256 Mo), l'ancien code échoue même (« Out of Memory Error ») : il a fallu 2 Go pour le mesurer ;
  - **après** : **15 travaux de 7 à 12 ms**, trois lancements :
    `[11, 9, 8, 8, 12, 8, 8, 8, 8, 8, 9, 10, 10, 8, 10]`, `[9, 9, 8, 7, 8, 8, 8, 7, 7, 7, 8, 8, 8, 7, 8]`,
    `[9, 9, 8, 8, 12, 8, 8, 7, 7, 7, 8, 8, 8, 7, 8]` ms (appel `write` compris, `BEGIN` et `COMMIT` inclus).

## `JoinBrief` : les plans dans « While away »

« open about this player » prend les lignes ouvertes d'une sorte à échéance **et les plans** (`JoinBrief.isCommitment`,
la même règle que le `<canon>` du briefing, mais sur `status.open`, comme avant). Un plan est écrit comme une menace
sans citation : `C-64 plan: Un comité d'accueil attend Mimosa dans le Nether.` Il entre dans les références montrées
(`shownRefs`, `recalls` à l'acceptation). Les lois ne s'y ajoutent pas. Les plans viennent après les engagements
datés (l'ordre de `openCanon` : échéance, puis importance).

## Prompts et budgets

| Fichier | Avant | Après | Budget |
|---|---|---|---|
| `director.md` | 33 763 | **33 848** (+85) | 34 000 |
| `chronicler.md` | 11 418 | **11 482** (+64) | 11 500 |
| `SchemaDoc`, section VIEWS | | 1 965 | 2 300 |

`director.md` : −20 (exemple de rappel), +48 (loi remplacée), +15 (« plans compris » de While away), +42
(`engine_decisions`). `chronicler.md` : −15 (« des mécaniques »), +13 (fin d'une règle), +53 (consignes), +13
(résultats). La voix (lignes 1 à 54 de `director.md`) n'a pas bougé.

## Ce que les autres lots doivent savoir

- **Lot D** : la décision de fin d'une règle doit s'appeler exactement `rule_ended` (`ExchangeLog.RULE_ENDED`) avec
  `detail.ending` ∈ {`expired`, `disabled`, `removed`, `fulfilled`}. La raison n'a pas besoin de répéter la fin : la
  ligne la montre. Le lot I peut faire pointer `RULE_ENDED` vers la constante de D.
- **Lot I (constat 42)** : la KDoc de `WakeScheduler.pastBudget` (« 10 minutes ») est déjà réécrite. Restent à I :
  l'ordre d'éviction dans la KDoc d'`Inbox` (et de `DirectorControl.post`) et la branche `Mechanic -> SOON` redondante
  d'`Item.urgency`.
- **Tests du Directeur** : un rappel échu est encore dans `h.game.reminders.state` tant que le tour qui le porte n'a
  pas été accepté.

## Vérifications

- **Chaque constat a un test qui échouait avant.** Contre-épreuve faite sur le code final : six mutations temporaires
  (non commitées) remettent l'ancien comportement, et les dix tests nouveaux échouent, chacun sur son assertion :
  - C5 : `a reminder due during a pause stays in the world, and the Director of the next run tells it` et
    `a reminder due while the budget of turns is spent stays in the world until a wake-up tells it` (le monde est vide) ;
  - C6 : `past the budget, the players' offerings wake the Director 4 times an hour, then wait` (6 tours au lieu de 5) ;
  - C31 : `a rule's end is an ENGINE line with its ending, and only a mechanic's news is worth a batch` ;
  - C41 (registre seul) : `a window whose only news is the ledger's is skipped, with no conversation` (`FAILED` : le
    lot partait vers le faux serveur) ;
  - C32 : `the Chronicler's challenge naming the players who played is the engine's challenge for all` et
    `a challenge's result noted by the Chronicler or the Director is the engine's, so that the favor moves once` ;
  - C41 (offrandes) : `two offerings taken within ten minutes are two, unless their notes say the same` ;
  - `JoinBrief` : `what is open about a player who joins includes the plans that target them` ;
  - `PairStats` : `1 500 pairs are written in short jobs, none holding the writer 100 ms`.

  S'y ajoutent deux tests unitaires de `WakeSchedulerTest` (règles pures du sous-budget, `TurnBudget` glissant).
- Tests existants verts sans retouche, dont le test de M4-12 `the engine's interactions without evidence are the same
  only for the same canon entry` et ceux des rappels de M4-10.
- `PromptsTest`, `PromptToolMentionsTest`, `PersonaFrozenTest`, `SchemaDocTest`, `SqlViewsTest`,
  `ChroniclerReplayTest` : verts.
- `git merge main` (lot E et le design de M5) : sans conflit. Build : voir le rapport.
- Aucun appel réel à Claude ni à Jev ; pas de `runServer`.
