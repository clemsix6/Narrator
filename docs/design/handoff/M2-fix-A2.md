# Passation M2-fix-A2 : sauvetage au-delà du quota de morts

Branche `worktree-agent-a3516fcaed8712fc7`. Ce lot remplace la « réanimation unique par épisode » du lot A (`M2-fix-A.md`) par un **sauvetage systématique**, selon la décision du superviseur. Il tient la promesse de `ARCHITECTURE.md` §17.4 : au chaos 3, le Narrateur tue directement un joueur **au plus une fois par heure de jeu**. **En cas de contradiction, le code fait foi**, et ce fichier explique pourquoi.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `guard/DeathGuard.kt` | `allowDeath` : au-delà du quota, toujours `false` et `rescue` ; `moveToSafety` (l'ancien `rescue` du vide) ; `afterTick` ; `cushionFall` scindé (`cushion` sans armement) ; plus de `revive` ni de `PREVENTED_REARM_SECONDS` ; constantes `RESCUE_HEALTH`, `RESCUE_FOOD`, `RESCUES_BEFORE_WARNING`, `RESCUE_WINDOW_MS`, `RESPAWN_POINT`, `SAFE_GROUND` ; `recentRescues` ; nouveau paramètre `effects: EffectLedger` |
| `guard/GuardState.kt` | `revivedUntil` (`revived_until`) **retiré**, remplacé par `rescues: List<Long>` (`rescues`, facultatif) |
| `guard/EffectLedger.kt` | `removeHarmful` (ajout) |
| `guard/AttributeLedger.kt` | `resetHarmful` (ajout) |
| `guard/GuardsImpl.kt` | `effects` construit avant `death`, qui le reçoit ; `tick` appelle d'abord `death.afterTick()` |
| `docs/ARCHITECTURE.md` | §17.4, une phrase : « au-delà du quota, la mort est toujours empêchée : le joueur est sauvé… » |
| `docs/design/M1_M2.md` | §10.2 (`GuardState.rescues`, `resetHarmful`, `removeHarmful`) et §10.3 (le sauvetage) |

Tests : `GuardGameTests` (7 nouveaux, 2 retirés, 4 mis à jour).

## Le comportement

Au-delà du quota (morts du Narrateur dans la dernière heure jouée ≥ plafond du chaos, donc **toujours** au chaos 0), toute mort attribuée au Narrateur est refusée à `ALLOW_DEATH`, **à chaque fois**, quelle que soit la cause. Le joueur est sauvé (`DeathGuard.rescue`), dans cet ordre :

1. **vie à 1** avant tout déplacement (Fabric ne rend pas la vie, lot A) ;
2. **effets et modificateurs nuisibles du Narrateur retirés** par les registres : `EffectLedger.removeHarmful` et `AttributeLedger.resetHarmful` (décisions `ledger removed`). Ils passent avant le déplacement, parce qu'une `scale` réduite change l'endroit où le joueur tient et qu'une lévitation le soulèverait de nouveau. Les effets des autres sources (le poison d'une sorcière) et les faveurs du Narrateur restent. Les entrées d'effet sont **oubliées avant le retrait** : une lévitation levée ainsi ne se termine pas « comme celle du Narrateur », donc elle ne pose pas de coussin et n'arme rien ;
3. **point de réapparition**, tel que le jeu le trouve (lit, ancre **sans consommer de charge**, point forcé, sinon point d'apparition du monde), avec la transition reconstruite sans le drapeau « pas de lit » (code du lot A, devenu `moveToSafety`). À défaut, le sol sûr de la colonne où il est (`Teleports.landing` puis `Teleports.move`). **Vérifié en bytecode** : `ServerPlayer.teleport(TeleportTransition)` ne rend `null` que pour un joueur retiré (`isRemoved`), et `findRespawnPositionAndUseSpawnBlock` rend toujours une transition. Le repli ne sert donc qu'en dernier recours. Si rien ne marche, le joueur reste en vie où il est, avec une erreur au journal ;
4. chute arrêtée (distance et vitesse à 0), **éteint** (`clearFire`), **air** au maximum, **nourriture** portée à 6 si elle était à 0 (un joueur affamé remourrait en mode difficile ; c'est mon ajout, dans l'esprit de « sortir du danger »), **aucun dégât de chute pendant 30 s** (`fall_damage_multiplier` × 0 par l'`AttributeLedger`, une faveur, `CUSHION_SECONDS`), et **6 PV** (3 cœurs, `RESCUE_HEALTH`, au plus la vie maximale). J'ai choisi 6 PV : assez pour survivre à un coup ordinaire en arrivant, sans que le sauvetage devienne un soin complet ;
5. **éteint de nouveau à la fin du tick** : `GuardsImpl.tick` (`END_SERVER_TICK`) appelle `DeathGuard.afterTick`. **Vérifié en bytecode** : les effets de blocs d'un tick (`InsideBlockEffectApplier.StepBasedCollector`) sont appliqués pas à pas, par blocs traversés : [allumage, dégâts de lave] pour chaque bloc de lave. Le sauvetage a lieu pendant les premiers dégâts, et l'allumage du bloc suivant rallumerait le joueur chez lui pour 15 s. Pour la noyade, le jeu met l'air à 0 **avant** les dégâts ; pour la brûlure, il retire un tick de feu **après** : les deux suivent bien le sauvetage ;
6. décision **`death_guard rescued`** (remplace `prevented`), par exemple : `minecraft:lava: the Narrator's quota of deaths (1 per played hour) is spent; taken to respawn point (x 12.5, y 64.0, z -7.5 in overworld), fire put out, air given, lifted slowness II, max_health, 6.0 health, no fall damage for 30 s; nothing armed (the quota comes back in 3550 s of play)`. Son `detail` : `cause`, `cap`, `recent_kills`, `rescued_by` (`respawn_point` ou `safe_ground`), `rescued_to`, `lifted`, `health`, `recent_rescues`.

**Un sauvetage n'arme rien** : ni les 5 s du lot A, ni la garde de chute du coussin (`cushion` est la moitié de `cushionFall` sans l'armement). Les armements des actions continuent tels quels ; une nouvelle mort attribuée pendant ce temps est un nouveau sauvetage. Il n'y a plus de seconde mort, et la boucle du joueur réanimé dans la lave est impossible, puisqu'il n'y est plus.

**Borne anti-abus** : `GuardState.rescues` garde l'heure (ms epoch, temps réel) des sauvetages des 10 dernières minutes, sauvée avec le joueur (une reconnexion ne la remet pas à zéro), oubliée par `tidy`. Au-delà de **3 sauvetages en 10 min**, chaque nouveau sauvetage est écrit au niveau **`WARN`**, avec en fin de raison `rescued 4 times in 10 min: look at what keeps putting them in danger`. Le joueur est quand même sauvé. Un joueur sous une garde `ANY` au-delà du quota pourrait sauter dans la lave pour rentrer chez lui : l'avertissement le montre aux opérateurs.

**`counted`** : la mention « (beyond the quota: this episode's one revival was already spent) » a disparu, car une mort du Narrateur au-delà du quota n'a plus lieu.

## Pour les autres lots

- **Lot G** (`director.md`, `ToolText`) : le Narrateur qui tente de tuer au-delà du quota **renvoie le joueur chez lui avec 3 cœurs**, sans ses effets nuisibles. Le prompt peut le dire, pour que ce ne soit pas une tactique : « au-delà du quota, un joueur qui devrait mourir est sauvé et ramené à son point de réapparition ». La factorisation des coussins de chute (`NarratorMobs.land` et `DeathGuard.cushionFall`, signalée par le lot B) doit garder `DeathGuard.cushion` **sans armement**.
- **Lot EF / `DeathReport`** : une mort au-delà du quota n'arrive plus, donc `death_guard died` ne peut plus suivre un sauvetage. `GuardState.revivedUntil` n'existe plus ; `rescues` est disponible si `/narrator why` veut montrer les sauvetages récents.
- **Traces** : `prevented` n'est plus écrit par la garde de mort. `TraceCommands.REFUSALS` et la KDoc de `DecisionRecord` le citent encore. C'est sans effet, car les décisions de la garde n'ont pas de tour, et je n'ai pas touché ces fichiers hors du paquet `guard`. Le commentaire de `V1__journal.sql` est laissé tel quel (migration).
- **Tests en jeu** : `GuardGameTests.homeAt(helper, player)` pose un sol de pierre et un point de réapparition forcé en (6, 1, 6) ; un joueur sauvé y va. Sans lui, un joueur factice est envoyé au point d'apparition du monde, loin de la zone du test. `basin` construit une cuve de lave ou d'eau en (1, 1, 1), et `deathGuardDecisions` lit les décisions `death_guard` d'un joueur dans le Journal. Un test qui tue un joueur au-delà du quota (ou au chaos 0) doit s'attendre à 6 PV, et non plus à 1.

## Limites

- **Le sauvetage est un retour chez soi** : un joueur loin de son lit perd son chemin, et un joueur de l'End ou du Nether est ramené dans la dimension de son point de réapparition. C'est la décision. L'avertissement signale l'abus.
- **Ce qui ne vient pas du Narrateur reste** : le wither d'un vrai squelette wither, pendant une garde `MAGIC` ou `ANY`, peut tuer de nouveau le joueur une fois rentré. C'est alors un nouveau sauvetage tant que la garde dure, puis une mort ordinaire. Les créatures du Narrateur restent là où était le joueur.
- **Point de réapparition dangereux** : il n'est pas vérifié au-delà de ce que fait le jeu, qui choisit lui-même une position debout près d'un lit ou du point d'apparition.
- **Réallumage entre deux ticks** : si le sauvetage a lieu pendant le traitement d'un paquet de mouvement, entre deux ticks, l'extinction de fin de tick attend la fin du tick suivant. Au pire, le joueur subit un point de dégât de feu.
- **Anciens états** : un `revived_until` sauvé par le lot A est ignoré à la lecture, et rien ne le reprend.
- **Chargement synchrone** : ramener un joueur vers un lit lointain charge ce tronçon sur le thread serveur, comme le fait une réapparition du jeu.

## Vérifications

- `gradlew build` (711 tests unitaires, 169 GameTests) passe avant la fusion de `main`. Le résultat après fusion est plus bas.
- **Nouveaux GameTests** (`GuardGameTests`) :
  - `aDeathBeyondTheQuotaIsARescueThatArmsNothing` : coup mortel. Le joueur est chez lui avec 6 PV. `max_health` × 0,5, lenteur et lévitation du Narrateur sont retirées, sa vitesse (faveur) et le poison d'une autre source restent. Il est nourri, l'inventaire est intact, sans marque, rien n'est compté, **rien n'est armé** (ni pour la fin de la lévitation). Une heure de jeu plus tard, le quota revient ;
  - `twoDeathsOfTheSameEpisodeAreTwoRescues` : deux morts dans le même armement, **deux sauvetages**, aucune mort ;
  - `aPlayerInLavaBeyondTheQuotaIsRescuedOnce` : une vraie cuve de lave, des ticks réels (`doTick`), un joueur à 1 PV. Il est sauvé au premier coup de lave, éteint, chez lui, sans rien d'armé. Un second allumage dans le même tick est éteint à la fin du tick. **200 ticks plus tard, il est vivant avec un seul sauvetage : pas de boucle.** La décision lue dans le Journal est `rescued` / `minecraft:lava` ;
  - `aFallAfterTheNarratorsLevitationBeyondTheQuotaIsARescue` : fin réelle de la lévitation, coussin échu, puis une vraie chute (`causeFallDamage`) de 60 blocs. Le joueur est sauvé, avec un coussin de 30 s en faveur, sans rien d'armé ; une seconde chute aussitôt ne lui fait rien ;
  - `aPlayerBurningToDeathBeyondTheQuotaIsPutOut` : brûlure réelle à 1 PV, sauvé et éteint, sans brûlure 200 ticks plus tard ;
  - `aDrowningPlayerBeyondTheQuotaIsRescuedWithAir` : une vraie colonne d'eau, noyade réelle, sauvé hors de l'eau avec tout son air ;
  - `manyRescuesInAShortTimeWarnTheOperators` : 4 sauvetages, seul le 4e porte l'avertissement.
- **Mis à jour** : `aDeathInTheVoidBeyondTheQuotaIsARescue` (6 PV, rien d'armé, décision `rescued`), `aSpentDeathGuardIsForgotten` (sauvetages anciens oubliés), `theGuardStateSurvivesItsCodec` (`rescues`), `protectionAndChaosZeroComeBeforeAnyHostileAction` (sauvetage au chaos 0).
- **Retirés** (remplacés) : `aDeathBeyondTheQuotaIsPrevented`, `aSecondDeathOfTheSameEpisodeGoesOnWithTheInventory`.
- **Contre-épreuves** : six passes, chacune sur `runGameTest`. Le correctif est retiré, puis remis avec `git checkout`. Chaque test visé échoue sur sa propre assertion :
  - **A**, sans déplacement : exactement 7 échecs sur la position. Ce sont les tests du vide, des deux morts, de la lave, de la noyade, de la brûlure, de la chute et du premier test ;
  - **B**, sans extinction, sans air, sans retrait des nuisibles et sans avertissement : exactement 5 échecs. La lave et la brûlure échouent sur `put out`, la noyade sur `air in their lungs`, le premier test sur `harmful modifier is lifted` et le test des sauvetages répétés sur `then a warning` ;
  - **C**, sans `afterTick`, sans nourriture et sans coussin : exactement 4 échecs. La lave échoue sur `put out again at the end of the tick`, le premier test sur `fed`, la chute et le vide sur `no fall damage` ;
  - **D**, le comportement du lot A (réarmement de 5 s et un seul sauvetage, la mort suivante ayant lieu) : les 5 tests visés échouent. Les deux morts et le test des sauvetages répétés échouent sur `no second death` et `never let die`, la lave, le vide et la chute sur `nothing more armed`. Le premier test n'y est pas sensible (sa garde `ANY` de 60 s couvre déjà les 5 s), mais sa passe **F** le couvre. Pendant cette passe, `GuardPolicyGameTests.theNarratorsCreeperBreaksNothingButHurtsMonsters` a aussi échoué une fois (`an ordinary creeper breaks the planks`). Il concerne un creeper **ordinaire** et ne passe pas par la garde de mort, et il est vert dans toutes les autres courses. Je le crois instable ;
  - **E**, vie laissée à 1 : les 9 assertions de vie échouent ;
  - **F**, `removeHarmful` qui retire l'effet avant d'oublier l'entrée : un seul échec, sur `a rescue arms nothing, not even for the end of the levitation` (une garde `fall` est apparue).
- Pas de `runServer`.
