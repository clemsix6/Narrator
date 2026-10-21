# Passation FLAKY-5 : des tests qui attendaient en secondes ce qui vient en ticks

Mission : rendre déterministes, sans les affaiblir, les GameTests qui échouaient sous charge parce qu'ils attendaient en
secondes réelles (`ArsenalCreaturesGameTests`, `TrapGameTests.aGlassPushedInPlaceOfAPaneByAPistonStays`), chercher les
autres de la même famille, puis, à la demande du superviseur, `PairStatsTest` (« 1 500 pairs… 100 ms »), `HeldBackTest`,
`ForgeEndToEndTests` et `SearchLatencyTest`. Branche `worktree-agent-acf5103dfb7707e41`. Seuls des fichiers de test et
d'utilitaires de test changent.
**En cas de contradiction avec une passation plus ancienne (M5-15, M5-16, M5-17, M4-FIX-E, M5-LOAD), le code et cette
passation font foi.**

| Test | Cause | Où était le défaut | Correction |
|---|---|---|---|
| `ArsenalCreaturesGameTests` › orbite | le moteur du banc est tické une fois par tick du serveur ; le module dure environ 265 ticks ; le test attendait 15 s réelles. Les 32 tests du lot `forge` sous charge font tourner le serveur à 14 ticks par seconde : 215 ticks en 15 s | test | attente comptée en ticks du moteur (`awaitTicks`) |
| `ArsenalCreaturesGameTests` › parade | **deux causes** : (1) le `sayTo` de la parade (« Une poule a quitté les rangs ») passe par la limite de parole **du serveur**, partagée avec le lot : les autres modules avaient dit les 4 répliques par minute du moteur, `DENIED (speech_limit)`, Bob n'est jamais prévenu ; (2) les étapes (marche de 140 ticks, file surveillée 160 ticks) attendues en secondes | test | limites de parole **à soi**, sur l'horloge du banc (comme `ArsenalWorldGameTests`), et attentes en ticks |
| `TrapGameTests.aGlassPushedInPlaceOfAPaneByAPistonStays` | le piège était posé dans la fonction du test (ticker des GameTests, après les niveaux) et le piston pousse aux événements de bloc du tick **suivant** ; entre les deux, la passe du restaurateur (fin du tick serveur) retire la vitre si le reste du tick a duré plus de 1,5 s (le démarrage d'un lot de 50 tests) | test | piège posé **au début du tick du niveau qui pousse**, avant ses événements de bloc |
| `ArsenalGamesGameTests` | un tick du moteur par vérification (50 ms + un aller-retour au fil serveur) : le premier ordre de `simon_says` (200 ticks) a pris 17 s d'un budget de 30 s sous charge | test | attentes en ticks |
| `ArsenalWorldGameTests` | `playUntil` jouait des secondes de jeu dans 15 s réelles : les vagues de `gravity_storm`, 40 s de jeu l'une après l'autre | test | attentes en ticks |
| `DuelGameTests` (4 tests « …once the server runs ») | 400 ticks du serveur attendus en 60 s : 27 s sous charge dans un lot de 50 tests | test | les ticks sont comptés, pas chronométrés |
| `PairStatsTest` › *1 500 pairs…* | 100 ms d'horloge murale par tâche d'écriture : 157 et 175 ms vues sous charge pour quelques ms de travail | test | temps CPU du fil d'écriture (méthode FLAKY-1) ; borne murale gardée, avec la marge de `BusyMachine` |
| `HeldBackTest` | (1) les appels aux budgets du jeu peuvent ne pas même commencer en 25 ms (`LATE` : sautés) ; (2) des appels annexes (« le module continue ») aux budgets du jeu ; (3) **une boucle de 20 ms de travail jugée abandonnée** : à la priorité normale, au milieu d'autant de fils occupés, l'horloge CPU de Windows lit 40 ms ou plus pendant qu'elle se dénoue | test, et un comportement de l'hôte à signaler (§6) | rejeu des appels `LATE` (comme le moteur, et `AbandonTest.attack`) ; appels annexes patients ; le fil du test à la priorité du thread serveur (8), comme `ModuleFixture` |
| `ForgeEndToEndTests` | (1) une seule attente porte sur ce que les ticks produisent : la barre d'action, que le module montre tous les 20 ticks, attendue 10 s ; (2) **l'échec vu sous charge** : l'appel du module qui entend le bon mot est lâché (fil retenu, « nobody's fault »), et la suite qu'il demandait après l'abandon (`then` → `ctx.end`) est perdue : le module ne finit jamais (§4) | (1) test ; (2) **production (Forge), à trancher** | (1) la barre d'action attendue en ticks du serveur ; (2) rien, signalé |
| `SearchLatencyTest` | 50 ms d'horloge murale en moyenne par recherche : 50,97 ms vues sous charge (19,9 à 22,5 ms au calme, M3-04) | test | temps CPU du fil qui cherche (§7) |

## 1. L'outil : `awaitTicks` (`GameTestSupport.kt`)

```kotlin
suspend fun awaitTicks(what: String, ticks: Int, played: suspend () -> Int, timeoutMs: Long = 15_000, condition: suspend () -> Boolean)
```

Attend que `condition` tienne, vérifiée toutes les 50 ms. `played` compte les ticks (d'un moteur du test, du serveur).
L'attente n'échoue qu'une fois **`ticks` ticks passés et `timeoutMs` écoulées**, les deux : un serveur ralenti par le
lot a tous ses ticks, et un serveur rapide laisse quand même leurs secondes aux coroutines des outils (les actes d'un
module, la mémoire). `timeoutMs` reprend l'ancien budget de chaque attente : une attente n'échoue jamais plus tôt
qu'avant. Le message dit ce qui a été consommé : `timed out waiting for: X (N ticks and M ms; T ticks and B ms allowed)`.

Règle pour la suite : **ce qui avance au rythme des ticks s'attend en ticks**. Une attente en secondes réelles ne
convient qu'à ce qui avance à l'horloge murale : les entrées-sorties (Journal, faux serveur Anthropic), et les échéances
de production à l'horloge réelle (le moment calme d'un chapitre, `QUIET_MS` 20 s ; la fin d'un défi en temps réel).

## 2. Les créatures de l'arsenal (`ArsenalCreaturesGameTests`)

- **Attentes** : `Rig.await(what, ticks, alive, timeoutMs)` compte les ticks du moteur du banc (`Rig.ticks`, désormais
  `@Volatile`). Budgets : la fin de l'orbite `ORBIT_END_TICKS` = 600 (10 s d'orbite, 3 s d'atterrissage, le décollage
  et le nuage : 262 à 263 ticks mesurés) ; une invocation de la parade `SUMMON_TICKS` = 1 200 (une invocation refusée
  — le plafond de créatures est celui du serveur, partagé avec le lot — attend 30 s de jeu avant la suivante) ; la marche
  140 + 100, la file 160 + 100 ; ce qui suit un acte d'un ou deux ticks (`SLACK_TICKS`, `GONE_TICKS`) 100.
- **Limites de parole à soi** : `Rig.services.toolLimits` = un `ToolLimits(clock)` du banc, comme `ArsenalWorldGameTests`.
  Les limites restent celles de la production ; seule l'instance change. La règle de FLAKY-3 (« un test qui attend une
  réplique par les limites du serveur est un script ») ne suffit pas dans le lot `forge` : ses 32 tests tournent
  ensemble, et leurs modules parlent pendant que la parade attend la sienne.
- Les vérifications ne changent pas (orbite, file, poule remplacée, Bob prévenu, fin, poules parties).

## 3. Le piège poussé par un piston (`TrapGameTests`)

Ordre d'un tick du serveur (javap de `MinecraftServer.tickChildren` et `ServerLevel.tick`, 26.3) : les niveaux
(`ServerLevel.tick` → `runBlockEvents`), puis `GameTestTicker.tick` (les fonctions et séquences des tests), puis
`END_SERVER_TICK` (le `BlockRestorer` : `forgetChanged`, puis la passe de chaque seconde). La fonction du test pose le
bloc de redstone : l'événement du piston attend le tick suivant. Avec le piège posé dans la fonction et une échéance de
1,5 s, la passe de la fin du **même** tick retirait la vitre dès que le reste du tick dépassait 1,5 s ; le piston ne
poussait alors que le verre du joueur, et le test attendait jusqu'au tick 10 002.

Correction : `atNextLevelTick(level) { … }` (crochet `ServerTickEvents.START_LEVEL_TICK`, enregistré une fois, comme
les crochets de chunks du même fichier ; il n'envoie jamais d'exception dans le tick) pose le piège **au début du tick du
niveau qui pousse**. Le piston pousse dans le même tick, et `forgetChanged` voit le `moving_piston` avant toute passe :
la case est oubliée, quel que soit le temps que prenne le serveur. L'échéance de 1,5 s, la fin du piège par la passe
« due » et toutes les vérifications restent.

Les autres tests du fichier à 1,5 s ou 1 s ne sont pas concernés : tout ce qu'ils vérifient avant l'échéance est fait
dans la fonction du test, avant toute fin de tick.

## 4. Les autres tests à l'horloge murale

Méthode : une sonde temporaire dans `awaitCondition` a journalisé, pour chaque attente, le temps pris et son budget,
pendant un `runGameTest` complet sous 16 fils de calcul. Classement (part du budget consommée) :

| Attente | Part | Nature | Suite |
|---|---|---|---|
| `simon_says` : premier ordre | 17,1 s / 30 s | ticks (un par vérification) | **converti** |
| `DuelGameTests` : 400 ticks du serveur (×4) | 25,4 à 26,9 s / 60 s | ticks | **converti** |
| chapitre clos au moment calme (×4) | 19,9 à 20,1 s / 45 s | horloge réelle (`Director.QUIET_MS` = 20 s) | laissé : attend un temps réel |
| `tag` : la proie devient loup | 4,3 s / 15 s | ticks | **converti** |
| mise de Ann rendue à la fin du défi | 4,0 s / 15 s | horloge réelle (`CHALLENGE_LEFT_MS` = 4 s) | laissé |
| `gravity_storm` : deuxième vague ; fins de modules | 3,2 à 5,9 s / 15 à 30 s | secondes de jeu | **converti** |
| réveils du Directeur | 3 s / 30 s | horloge réelle (`WakeScheduler.SOON_QUIET_MS`) | laissé |
| tout le reste | moins de 10 % | E/S, ou peu de ticks | laissé |

Les autres échéances murales des GameTests ont été lues une à une : échéances lointaines (`+ 600_000`), déjà passées
(`- 1`), vérifiées dans le même appel synchrone (les pièges de 1,5 s, les coussins de 30 s vérifiés `>= + 29_000`
aussitôt créés), ou lectures du Journal (15 s pour une écriture asynchrone). `RuleEngineGameTests`,
`ModuleEngineGameTests`, `ChallengeGameTests`, `BetGameTests`… : un tick par vérification, mais quelques ticks
seulement par attente (moins de 5 % du budget mesuré).

**`ForgeEndToEndTests`** (demande du superviseur : un échec sous charge, sans message conservé). Ses attentes, mesurées
sous 16 fils de calcul : toutes sous 10 % de leur budget, sauf le chapitre clos (20 s de `QUIET_MS` sur 45 s, horloge
réelle). La seule qui attend ce que les ticks produisent est la barre d'action (le module la montre tous les 20 ticks) :
elle est désormais attendue en ticks du serveur (`awaitTicks`, 100 ticks et 10 s). 6 passages seuls sous 32 fils de
calcul et 5 dans les `runGameTest` complets : verts.

**Puis l'échec s'est montré, avec son message, dans mon premier build final** (sans charge ajoutée, pendant les builds
des autres agents) : `timed out waiting for: C-191 ended by itself on tick 772`. Ce n'est **pas** une attente du test,
c'est un **défaut de la Forge** (production, hors de mon périmètre) : le journal dit
`C-191 password_race: its fact call was given up at 32 ms, its thread held back; it runs on alone, the module waiting for
it`, puis `ended without computing past its budget (0 ms of its own work): its thread was held back, nobody's fault`.
L'appel du fait (le joueur écrit « Lanterne ») a été lâché ; les actes demandés **avant** l'abandon sont partis (le défi
C-190 est gagné, les biscuits payés), mais la suite, `last.then(o -> ctx.end(…))`, n'a **jamais** couru : le module
n'avait plus rien à faire et ne s'est jamais fini (le test l'a retiré 30 s plus tard). Mécanisme probable : depuis
M5-LOAD, une requête du SDK faite par un appel lâché est refusée (`ForgeLimit`) ; le `then` posé après l'abandon est
donc perdu, sans faute ni trace. En jeu : sous charge, un module peut rester en vie sans suite jusqu'à son échéance, son
défi déjà réglé, le Directeur jamais prévenu de sa fin. **À trancher** (M5-LOAD, `ModuleHost`/`ActQueue`) : par exemple
rejouer, ou laisser finir, ce qu'un appel lâché sain demande après l'abandon, ou au moins le tracer. Le test a raison
d'échouer ; je ne l'ai pas touché pour cela.

## 5. `PairStatsTest` › *1 500 pairs are written in short jobs…*

Le test mesure désormais, pour chaque tâche, le **temps CPU du fil d'écriture** pendant le bloc de la tâche
(`ThreadMXBean.currentThreadCpuTime`, code natif de DuckDB compris) et exige moins de 100 ms ; la borne murale reste,
à 100 ms + `BusyMachine.SLACK_MILLIS` (500), pour une tâche qui attendrait au lieu de travailler (l'ancien défaut :
une tâche de 2 s). Le test imprime les deux séries (`[FLAKY-5] pair_stats jobs of 100 pairs: …`).

| Mesure (16 fils de calcul) | CPU par tâche | mur par tâche |
|---|---|---|
| écriture par lots (le code), 15 passages sous 16 ou 32 fils | 0 à 46 ms (pas de 15,6 ms) | 12 à 108 ms |
| **sonde** : une ligne par instruction (`chunked(1)`) | **125 à 281 ms**, rouge 3 fois sur 3 | 262 à 849 ms |

Les 104 et 169 ms vus ailleurs sont des lectures **murales** de l'ancien test : le nouveau les admet (108 ms mesurés ici
dans un passage vert) et juge le travail.

## 6. `HeldBackTest`

- **`LATE`** : un appel aux budgets du jeu que le fil des modules ne commence pas en 25 ms est sauté, rien n'en a couru
  (M5-LOAD). `callRun` le redemande, comme le moteur rejoue un appel sauté (et comme `AbandonTest.attack`), 20 fois au
  plus, et rend le dernier appel et le nombre de sautés. Les vérifications portent sur l'appel qui a couru ;
  `host.skipped` compte en plus les essais sautés.
- **Appels annexes patients** (`BusyMachine.PATIENT_NANOS`) : « the module goes on » et l'appel du minuteur après un
  démarrage retenu. Leur sujet n'est pas le temps : aux 25 ms du jeu, ils pouvaient être lâchés ou sautés, et le
  compteur ou le journal lus trop tôt.
- L'appel retenu du premier cas dort **2 s** au lieu de 500 ms : l'appel suivant doit le trouver encore en cours, et
  M5-LOAD a mesuré jusqu'à 391 ms de retard d'un fil.
- **La priorité** : le fil du test prend la priorité du thread serveur (`ModuleFixture.SERVER_PRIORITY` = 8), que le fil
  des modules reprend à chaque remise, comme en jeu ; le cas du banc (*a patient caller, the bench…*) repasse à la
  priorité normale, celle du banc.

**Ce que le test a révélé (à trancher, hors de mon périmètre : `forge/host`)** : à la priorité normale, sous 16 fils de
calcul, *a loop its meter stopped, given up while it unwinds* a échoué **2 fois sur 60** (et 1 passage sur 6 avant) :
`time budget: one call ran past 25 ms and was abandoned` au lieu de la faute *limite* de son compteur. La boucle n'a que
20 ms de travail, mais pendant qu'elle se dénoue (le `finally` de 40 ms), `ModuleHost.settle` voit son fil vivant et lit
son horloge CPU depuis l'armement à `Meter.cpuEvidence` (40 ms) ou plus : sous Windows, l'horloge CPU avance par pas de
15,6 ms imputés au fil qui tourne à chaque pas, et un travail découpé en tranches par la contention peut être lu deux ou
trois pas au-dessus de ce qu'il a coûté. En jeu, l'effet serait un module en boucle désactivé à la première faute au
lieu de la deuxième ; un appel sain très découpé pourrait en théorie être abandonné à tort. Le fil des modules tourne à
la priorité 8 en jeu, où je n'ai vu **aucun** cas (200 répétitions de ce cas, 0 échec). Piste : dans `settle`, ne pas
juger « calculé » un appel dont le compteur a déjà sauté (il se dénoue), ou exiger plus de 40 ms de preuve CPU.

## 7. `SearchLatencyTest`

| Mesure, 205 recherches | mur, moyenne | CPU du fil qui cherche | CPU du processus |
|---|---|---|---|
| sous 32 fils de calcul (3 passages) | **61 à 80 ms** (rouge à 50) | 21,1 à 23,0 ms | 36,1 à 37,9 ms |
| sans charge ajoutée (4 passages) | 24,0 à 27,4 ms | 22,0 à 23,1 ms | 38,5 à 39,5 ms |
| **sonde** : 30 ms de calcul en plus dans `HybridRecall.search` | 56,9 à 59,0 ms | 48,9 à 49,4 ms | 67,9 à 68,5 ms |

DuckDB tourne sur deux fils : le fil appelant et un fil natif, que les horloges de fils de la JVM ne voient pas. Le
fil qui cherche calcule les neuf dixièmes du temps d'une recherche (22 ms sur 24) : son temps CPU est une borne
inférieure de la latence, et 50 ms de latence en valent 45 de son CPU. Le test exige donc :
- **moins de 45 ms de CPU du fil qui cherche** en moyenne (la sonde à 30 ms de plus est rouge, 48,9 ms ; avec 50, elle
  passait à 49,4 ms alors que l'ancien test l'aurait vue à 59 ms : c'est pourquoi 45) ;
- moins de 100 ms de CPU du **processus** par recherche (le fil de DuckDB compris : deux fils occupés 50 ms) ;
- moins de 50 ms + `BusyMachine.SLACK_MILLIS` d'horloge murale, pour une recherche qui attendrait au lieu de calculer.

Le test imprime les trois mesures (`CPU a search: … of the searching thread, … of the whole process`).

## 8. Instabilités vues, non traitées (pour un éventuel FLAKY-6)

- **`ForgeEndToEndTests`** : le défaut de la Forge du §4 (la suite d'un appel lâché perdue). À corriger en production ;
  le test restera instable sous charge tant que ce n'est pas fait.

- **`ArsenalCreaturesGameTests` › parade, la file** (1 passage du lot `forge` sur 20, sous charge) : `broken 25 ticks in
  a row` (au plus 20 permis), la plus longue liaison montant à 4,7 blocs. Ce n'est pas une attente murale : ce serveur
  tournait à environ 65 ticks par seconde, la parade entière a pris 5 s. Les 16 autres passages journalisés n'ont **aucun**
  tick rompu (liaison au plus 3,4). Hypothèse : la parade suit la position **actuelle** de la poule qui la précède ; une
  poule de tête qui flâne entraîne les suivantes, corrigées l'une après l'autre à 10 ticks d'intervalle, ce qui peut
  dépasser deux pas de la parade. M5-FIX-1 avait calibré 20 ticks sur 8 lancements (7 au plus). Module (`parade.java`)
  et seuil à revoir ensemble ; je n'ai rien changé.
- **`EvilModulesGameTests.theDeepHashAttacksAreStoppedByTheGuardWithNoLostThread`** (1 passage sur 20) : `SetOfHash held
  the tick 98.98 ms (bounded, not abandoned)` pour une borne de 55 ms. Depuis M5-LOAD, un démarrage qui n'a pas calculé
  est attendu (patience de 2 s) : sous charge il tient l'appelant plus de 55 ms sans faute. La cause (« abandoned »
  absent) est déjà vérifiée ; la borne murale demande la marge de `BusyMachine`, comme M5-LOAD l'a fait ailleurs.
- Signalés par le superviseur, non examinés : `DirectorEndToEndTests` (le réveil `task`), `DelegationScenarioTest`,
  `ModuleBookTest` (deux fois).
- `HeldBackTest` : le comportement de l'hôte du §6, à trancher (`forge/host`).

## 9. Sondes (aucune commitée)

| Sonde | Ce qu'elle fait | Ancien code | Nouveau code |
|---|---|---|---|
| A, serveur ralenti | 400 ms de sommeil à chaque `START_SERVER_TICK` (environ 2,5 ticks par seconde), classes Trap, Duel, Creatures, Games, World | **9 rouges** : 4 duels (`400 ticks of the server`), orbite, parade (`Bob's walk`), `simon_says` (premier ordre), `tag` (la proie), `gravity_storm` (deuxième vague) | **51 sur 51 verts** : orbite finie en 262 ticks et 122 s, premier ordre en 194 ticks et 91 s, 400 ticks des duels en 195 s, deuxième vague en 820 ticks et 20 s |
| B, tick bloqué | 2 s de sommeil dans le tick où le piège est posé, avant le piston | rouge : `both glass blocks moved on tick 10002` (les deux vitres « taken away (due) » avant la poussée) | vert : `trap skipped …: 1 block changed since set (before due): minecraft:moving_piston` |
| C, minute du moteur épuisée | 4 répliques du moteur dites par la limite du serveur juste avant la mort de la poule | rouge : `DENIED (speech_limit)`, `timed out waiting for: Bob told of the fallen chicken` | vert |
| D, le comportement retiré | `BlockRestorer` sans `forgetChanged` ; `parade.java` sans le `sayTo` ; `orbiting_chickens.java` sans la fin de l'orbite ; `simon_says.java` sans premier ordre à temps ; `gravity_storm.java` sans vague suivante ; les ticks des duels non comptés | — | voir « Vérifications » |
| E, `PairStats.write` une ligne à la fois | `chunked(1)` | — | rouge 3 fois sur 3 (125 à 281 ms de CPU par tâche) |
| F, `ModuleHost` : un module occupé n'est plus sauté | `if (given != null && false)` | — | rouge : *a call held back…* (« skipped at once… expected 0 but was 25005600 ») |
| G, `ModuleHost.settle` : la faute du compteur d'un appel lâché oubliée | `else -> null` | — | rouge : *a loop its meter stopped…* (`List is empty`) |
| H, `HeldBackTest` à la priorité normale | 60 répétitions du cas de la boucle | rouge 2 fois sur 60 (« abandoned ») | à la priorité 8 : 200 répétitions, 0 échec (233 exécutions de la classe) |
| I, recherche lente | 30 ms de calcul en plus dans `HybridRecall.search` | (aurait été rouge : 59 ms murales) | rouge : 48,9 ms de CPU du fil qui cherche |

Résultats de la sonde D (le comportement retiré, nouveau code) : **9 rouges, chacun sur sa vérification** — le piston
(`the player's glass stays`), les 4 duels (arrêtés par le budget de ticks du test), la parade (`Bob told of the fallen
chicken (338 ticks and 15053 ms; 100 ticks and 15000 ms allowed)`), l'orbite (`C-65 closed FULFILLED (601 ticks…; 600
ticks…)`), `simon_says` (`the first order above both hotbars (400 ticks…)`), `gravity_storm` (`the second wave passes…
(1328 ticks…; 1300 ticks…)`).

## Vérifications

- **Reproduction avant correction** (16 fils de calcul) : `runGameTest` complet, 394 tests, 2 rouges — l'orbite
  (`timed out waiting for: C-10 closed FULFILLED on tick 215`) et la parade (`Bob told of the fallen chicken`, après
  `DENIED (speech_limit): the engine's acts already said 4 lines in the last minute`). Puis une passe mesurée : l'orbite
  a fini en 263 ticks et 16,6 s (au-delà des 15 s de l'ancien test). Le piège : sonde B.
- **Sondes** : §9 (A à I), toutes retirées ; `git diff` de `TheMod/src/main` vide à chaque fois.
- **Passages sous charge** (code fusionné avec `main` 01e617c, 16 fils de calcul en plus des builds des autres agents) :
  **5 `runGameTest` complets : 395 sur 395 à chaque fois** ; **10 passages du lot `forge` seul** (33 tests) : 9 verts,
  1 rouge (la file de la parade, §8). Orbite : 200 à 204 ticks d'orbite et 60 d'atterrissage à chaque passage ;
  piège : `moving_piston` oublié avant toute passe, à chaque passage.
- **Après la fusion de `main` 34f7031** (32 fils de calcul : le second brûleur a survécu à son arrêt par erreur) :
  `ForgeEndToEndTests` seul 6 fois sur 6 vert ; lot `forge` seul **10 passages, 9 verts, 1 rouge**
  (`EvilModulesGameTests`, §8) ; `HeldBackTest` et `PairStatsTest` 6 passages sur 6 verts (plus 6 avant la fusion).
- `SearchLatencyTest` : §7 (3 passages sous 32 fils, 5 sans charge ajoutée, sonde I).
- **Build complet** (`.\gradlew.bat build --continue`) après la fusion de `main` (f8a624a, M6-02 et M6-10), sans charge
  ajoutée : premier build, **2 140 tests unitaires verts** (0 échec, 0 ignoré) mais un GameTest rouge,
  `ForgeEndToEndTests` (le défaut de la Forge du §4) ; relancé : **vert**, tests unitaires à jour et **« All 397 required
  tests passed »**.
- Brûleurs : les miens sont arrêtés (le second avait survécu à son arrêt : `Remove-Item` sur un chemin court de
  Windows a échoué sans bruit ; ils ont chargé la machine de 16:09 à 17:50 environ).
- Aucun appel réel à Claude ; pas de `runServer`.
