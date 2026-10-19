# Passation M4-FIX-F : garde-fous et catalogue des châtiments

Branche `worktree-agent-a87ce808ffef7b357`. Ce lot corrige les constats **10, 11, 16, 17, 18 (contrat et côté
garde-fous), 19 (côté garde-fous), 33 (`random_radius`, `glowing`, part de parole), 35, 39 (`punish` sans effet)** et
le point connu `jokersGivenAt` de la relecture de M4 (`docs/design/reviews/M4_REVIEW.md`), selon les décisions du
superviseur (C16, C17, C18, C19). **En cas de contradiction, le code fait foi**, et ce fichier explique pourquoi.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `guard/Guards.kt` | `hostile(…, punishment)` ; nouveaux `vet`, `withdraw`, `shield`, `jokerGiven`, `isDueling` ; `Rules.DUEL` ; `Guards.RESCUE_SHIELD_MS` ; `isPunished` ne compte plus un épisode seul |
| `guard/GuardsImpl.kt` | tout cela ; `verdict` commun à `hostile` et `vet` ; `spares` épargne aussi un joueur protégé par un bouclier ou en duel |
| `guard/Shields.kt` (nouveau) | `Shield(at, until, reason)` et `Shields` : les joueurs sauvés de la mort, en mémoire |
| `guard/Episodes.kt` | le `punish` de chaque acte sur chaque joueur (`punishmentBy`) et son retrait (`withdraw`) |
| `guard/TargetingPolicy.kt` | `check(…, shield, punishment)` : bouclier (`no_pile_on`) et un seul `punish` par acte (`already_punished`) |
| `guard/DeathGuard.kt` | tout sauvetage (au-delà du quota ou `rescue`) pose un bouclier de 5 min ; la raison le dit |
| `guard/CreatureShield.kt`, `mixin/policy/MobTargetGuardMixin.java` | une créature invoquée par le Narrateur ne vise que des joueurs (`isSummoned`) |
| `guard/EffectLedger.kt` (**hors liste**, une ligne) | un effet non bénéfique (donc `glowing`) est inscrit nuisible |
| `tool/action/NarratorExplosionDamage.kt` | `allow` en deux passes : `vet` de tous, puis `hostile` de tous |
| `tool/action/PlayerActionTools.kt` | `apply_effect` : tout effet non bénéfique est hostile ; `teleport` lointain : `vet` avant tout chargement |
| `tool/action/PunishTools.kt` | `hostile` avec la punition ; garde de chute de la cime ; retrait d'un `punish` qui n'a rien fait |
| `mechanics/punish/PunishmentCatalog.kt` | `tree_top` arme `Peril.ANY` ; `TREE_TOP_FALL_SECONDS_PER_BLOCK` |
| `mechanics/punish/Primitives.kt` | `Prepared.fallSeconds` ; la cime monte de 32 blocs au plus |
| `mechanics/Jokers.kt` | `jokersGivenAt` passe par `Guards.jokerGiven` |
| `tool/speech/SpeechLimiter.kt` | les actes du moteur disent au plus 4 des 8 répliques de la minute |
| `testing/FakeGuards.kt` | `vetted`, `withdrawn`, `shields`, `isDueling`, `Hostile.punishment`, `jokerGiven` |

`WorldActionTools.kt` n'a pas eu à changer : `Fuses.detonate` et `explosion` passent tous deux par
`NarratorExplosionDamage.allow`.

## Constat par constat

### 10 : `tree_top` arme la garde pour tout péril

- `PunishmentCatalog` : `tree_top` arme `Peril.ANY` pendant `TREE_TOP_SECONDS` (60 s), comme toute téléportation. Une
  mort sur la cime (squelette, phantoms, chute) compte au Narrateur : le joueur garde ses objets.
- La garde de **chute** dure plus longtemps, selon la hauteur : 60 s + 2 s par bloc de montée
  (`Prepared.fallSeconds`, armé par `punish` avant de poser le joueur).
- La montée est bornée à 32 blocs (`Primitives.TREE_TOP_MAX_RISE`) : un mineur dans une grotte n'est plus posé
  110 blocs plus haut. Le refus le dit : `nothing 6 to 32 blocks higher than Bartok within 24 blocks…`.

### 11 : une explosion refusée ne punit aucun spectateur

`NarratorExplosionDamage.allow` fait deux passes : `Guards.vet` de chaque spectateur (aucun effet de bord, sauf la
décision `guard denied` d'un refus), puis, seulement si tous passent, `hostile` de chacun et la garde de mort. Rien
entre les deux passes ne peut faire refuser un joueur accepté : le temps ne fait que lever les règles des garde-fous.
L'outil `explosion` et l'explosion d'une créature (`Fuses`) en profitent tous les deux.

### C16 : un seul `punish` par acte et par joueur

- `Guards.hostile(…, punishment: PunishmentId?)` : `punish` passe sa punition. `Episodes` garde, par joueur et par
  acte, le `punish` de l'heure écoulée. Un second `punish` du même acte est refusé :
  `DENIED (already_punished): this act already punished Bartok (hunger); one punish per act and player: compose it with the other tools, or let it run.`
- Les autres outils du même acte (`apply_effect`, `spawn`…) composent toujours la punition : la règle ne vise que
  `punish`. Elle tient une heure, même après la fin de l'épisode.
- **Côté règles** (lot D) : une règle à deux effets `punish` sur la même cible verra le second refusé à l'exécution
  (`already_punished`). La relecture propose aussi de le refuser à la validation (« au plus un effet `punish` par
  cible »), ce qui est au lot D.

### C17 : les créatures du Narrateur ne visent que des joueurs

`CreatureShield.keepsAway(attacker, target)` :
- cible joueur : comme avant (une créature du Narrateur laisse un joueur épargné) ;
- autre cible : refusée si l'attaquant est une créature **invoquée** (`isSummoned` : la pièce jointe `NarratorMob`,
  ou le propriétaire d'un vex, d'une flèche…).

Les deux mixins (`MobTargetGuardMixin`, `TargetingConditionsGuardMixin`) l'appellent : la créature ne prend ni
villageois, ni golem, ni animal pour cible, ni par ses buts, ni par `setTarget`. **Une créature seulement marquée par
une règle** (le tag `narrator2` sans `NarratorMob`) garde ses cibles : un golem accéléré par une loi défend encore son
village. La KDoc de `TargetingConditionsGuardMixin` (fichier hors lot) ne parle encore que des joueurs : au lot I.

### C18 : protection de 5 min après un sauvetage ou une mort refusée

Voir le contrat plus bas. `DeathGuard.save` (le sauvetage commun) pose le bouclier lui-même : le sauvetage au-delà
du quota et `Guards.rescue` protègent donc le joueur 5 min sans rien d'autre. La raison de la décision
`death_guard rescued` finit par `…, nothing hostile against them for 5m`.

Le bouclier est un `Rules.NO_PILE_ON` (« pas d'acharnement », comme après une mort), avec un message juste :
`Bartok was saved from death 1m ago (duel C-52: fatal blow stopped at 1 hp); hostile actions against them are refused until 19:35:00 (in 4m).`
Il refuse **tout acte**, même celui qui punissait déjà le joueur, et `spares` (créatures, explosions) l'épargne.

**Décision : en mémoire, pas dans `GuardState`.** `GuardState.kt` est au lot A (codec de `Confiscation`, et le
registre des mises reçues du constat 28 y ajoute sans doute un champ) ; un champ de plus aurait heurté sa fusion, et
la relecture interdit de réécrire `lastDeathAt` directement. Le bouclier survit à une reconnexion, pas à un
redémarrage (comme les punitions de l'heure et les catégories, M4 §3.1). Si le propriétaire veut qu'il survive à un
redémarrage, il suffit d'ajouter `shieldedUntil` et `shieldReason` à `GuardState` (le codec a encore deux places sur
seize, une si le lot A en prend une) et de relire `Shields` au `JOIN`.

### C19 (côté garde-fous) : `hostile` refuse un duelliste

`Guards.isDueling: (UUID) -> Boolean` vaut `{ false }` par défaut. Quand il répond vrai, `hostile` et `vet`
refusent : `DENIED (duel): Bob is fighting a duel; nothing hostile against a duelist while the duel runs.` (règle
`Rules.DUEL`, après la grâce, avant le reste). **Décision : `spares` l'épargne aussi** : les créatures et les
explosions du Narrateur ne touchent pas un duelliste, ce qui évite qu'elles tranchent le duel (sinon annulé, C19).

### 33 : `random_radius`, `glowing`, part de parole

- **`teleport` lointain** : quand la colonne visée est à plus de 32 blocs, `teleport` appelle `Guards.vet` (catégorie
  `teleport`) **avant** de charger ou de générer le tronçon ; un refus revient tel quel, rien n'est compté. La
  punition s'ouvre toujours après l'atterrissage, sur la distance réelle. Cela vaut pour `random_radius` comme pour
  `x`, `z`.
- **`glowing`** : `apply_effect` tient pour nuisible tout effet qui n'est pas `BENEFICIAL` (`Effects.harmful`) :
  `glowing` (neutre dans le jeu) passe par `hostile` (catégorie `senses`), est plafonné par le chaos, et
  `EffectLedger` l'inscrit nuisible, donc un pardon ou un joker le lève. Les autres effets neutres du jeu sont les
  présages, déjà interdits. **`EffectLedger.kt` n'est à aucun lot** : une ligne y a changé (`!= BENEFICIAL`), sans
  quoi l'effet restait hors de portée du pardon.
- **Part de parole** : les actes du moteur (`Caller.firingId` sans tour) disent ensemble au plus
  `ENGINE_LINES_PER_MINUTE` = 4 des 8 répliques de la minute ; le Directeur garde les autres. Refus :
  `DENIED (speech_limit): the engine's acts already said 4 lines in the last minute, their share (the rest is the Director's); the next is possible in 40s`.
  Les sons, titres et `actionbar` restent communs : `SpeechTools.kt` (hors lot) n'envoie pas l'appelant à
  `sound()`.

### 35 : un joker n'est plus consommé pour rien

`Guards.isPunished` ne compte plus un épisode seul : il faut un effet ou un changement nuisible, une confiscation
tenue, une créature nuisible ou un piège, ce qu'un pardon lève. Après `smite`, `tree_top`, `butterfingers` ou
`skyward`, le joueur garde son joker (`joker.nothing`) et l'épisode court jusqu'à sa fin (`already_punished` tient).
La réponse de `favor grace` ne dit plus « what you already inflict on them runs on » pour un épisode seul.

Cela corrige une phrase de la passation M4-FIX-E : une cage **entièrement cassée** ne rend plus `isPunished` vrai
(plus aucun bloc à retirer, donc rien à lever ; le joker est gardé). `already_punished`, lui, lit toujours l'épisode
et tient jusqu'à l'échéance du piège.

L'autre point « jokers » du constat 39 (un joker passe d'un joueur à l'autre) était « à trancher » et n'a pas de
décision : rien n'a changé.

### 39 : un `punish` sans aucun effet ne compte pas

Quand ni la primitive ni aucune étape n'a rien fait, `punish` appelle `Guards.withdraw` : l'épisode, la place
parmi les punitions de l'heure et les catégories frappées (celles des étapes comprises) reviennent à leur état d'avant
ce `punish`, la décision `guard withdrawn` est écrite, et l'acte peut punir de nouveau. La réponse reste l'échec, avec
son statut. La garde de mort armée reste armée (elle ne fait que protéger).

### `jokersGivenAt`

`Jokers.give` appelle `Guards.jokerGiven(player, now)` au lieu d'écrire la pièce jointe : la copie lisible de tout
thread des garde-fous (`GuardStates.snapshot`) le voit aussitôt. La lecture (`Jokers.allowed`) lit toujours la pièce
jointe sur le thread serveur.

## Contrats pour le lot H

### C18 : `Guards.shield`

```kotlin
fun shield(player: ServerPlayer, until: Long, reason: String)   // thread serveur
const val Guards.RESCUE_SHIELD_MS = 5 * 60_000L                 // = TargetingPolicy.NO_PILE_ON_MS
```

- Jusqu'à `until` (millisecondes epoch), `hostile` et `vet` refusent toute action hostile contre le joueur
  (`no_pile_on`, quel que soit l'acte), et ses créatures et explosions l'épargnent (`GuardsImpl.spares`).
- Un bouclier plus long déjà en cours est gardé. Décision `guard shielded` (raison, `detail.until`).
- **À brancher dans `Duels`** (épargne d'un coup fatal de l'adversaire, 1 PV sur place) :
  `services.guards.shield(loser, services.clock.millis() + Guards.RESCUE_SHIELD_MS, "duel C-52: fatal blow stopped at 1 hp")`.
  La raison est lue par le Directeur dans le refus : une courte phrase anglaise.
- **Rien à faire pour `rescue`** : `Guards.rescue` (l'autre cause d'une mort de duelliste) pose déjà son bouclier.
- Le bouclier ne protège pas d'un autre joueur : la fenêtre contre l'ex-adversaire (constat 18, côté duel) reste au
  lot C ou H.

### C19 : `Guards.isDueling`

```kotlin
var isDueling: (UUID) -> Boolean   // { false } par défaut
```

- **À brancher au démarrage du moteur** (`MechanicsService` ou `MechanicsModule`), avec ce que le lot C expose :
  `services.guards.isDueling = { uuid -> duels.isDueling(uuid) }` ; et le remettre à `{ false }` à l'arrêt du moteur
  (`stop`), pour qu'un moteur arrêté ne laisse pas un joueur « en duel » pour toujours.
- **Appelé souvent, sur le thread serveur** : `spares` le consulte à chaque choix de cible d'une créature du
  Narrateur et à chaque explosion. La fonction doit être une simple lecture (un ensemble d'UUID tenu par `Duels`),
  sans base ni attente.
- Il doit répondre vrai du départ du duel (`started`) à sa fin, pas pendant l'invitation.
- Quand `isDueling` est vrai, les créatures du Narrateur ne touchent plus le duelliste : un coup mortel « d'un tiers »
  ne peut plus venir que d'un autre joueur ou du monde (lave, chute…). Les GameTests de duel qui voudraient faire
  frapper un duelliste par une créature du Narrateur doivent en tenir compte.

## Ce que les autres lots doivent savoir

- **Lot D (règles)** : `vet` existe pour un contrôle pur des garde-fous (sans épisode ni compte) ; un second effet
  `punish` sur la même cible dans un déclenchement est refusé `already_punished` ; la part de parole du moteur est de
  4 répliques par minute pour toutes les règles ensemble.
- **Lot I** : KDoc de `TargetingConditionsGuardMixin` (C17) ; la description de `apply_effect` ne dit pas que
  `glowing` est une punition (budget des schémas : non touché).
- `FakeGuards.vet` répond `verdict`, comme `hostile`, et s'inscrit dans `vetted` (pas dans `hostiles`).

## Vérifications

- `git merge main` (lot E et conception de M5) : sans conflit. `.\gradlew.bat build` sur l'état fusionné : **vert**,
  **1 494 tests unitaires** (0 échec) et **« All 313 required tests passed »**. Un premier passage après la fusion
  a buté sur `MechanicsAssemblyGameTests.aDuelOfTheServersEngineKillsNobody` (« rescued, alive, put out: 3.33 »),
  l'instabilité connue du constat 22 (lot H), au message identique à celui de la relecture ; le passage suivant est
  vert, comme celui d'avant la fusion (308 tests).
- Un test existant a dû changer à cause de C17 : `GuardPolicyGameTests.theNarratorsZombieNeverBreaksADoor` lançait
  une créature **invoquée** sur un villageois, ce qui n'est plus possible. Le zombie du test porte maintenant le seul
  tag `narrator2` (celui d'une règle), que lit la garde des portes : le test garde son sens.
- Signatures vérifiées avec `.research/jp.ps1` : `Blocks.LIGHTNING_ROD` (une `WeatheringCopperCollection<Block>`,
  `asList()`), `AttachmentTarget.hasAttached`.
- Tests qui échouaient avant le correctif (chaque constat) :
  - **10** : `PunishmentCatalogTest` (`tree_top` arme `any`) ; GameTest `PunishGameTests.treeTopPutsThePlayerOnTheLeaves`
    (une mort quelconque sur la cime est au Narrateur, la chute couverte 60 s + 2 s par bloc) ;
  - **11** : GameTest `GuardPolicyGameTests.aRefusedExplosionPunishesNoBystander` ;
  - **C16** : `TargetingPolicyTest` › *an act uses punish once per player* ; GameTest
    `PunishGameTests.anActPunishesAPlayerOnce` ;
  - **C17** : GameTest `GuardPolicyGameTests.theNarratorsCreaturesLeaveVillagersAndAnimalsAlone` ;
  - **C18** : `TargetingPolicyTest` › *a shielded player is not a target* ; GameTests
    `GuardFavorGameTests.aShieldKeepsTheNarratorAwayUntilItEnds` et
    `theEnginesRescueTakesAPlayerOutOfTheLavaWithoutCounting` (refus après un sauvetage) ;
  - **C19** : GameTest `GuardFavorGameTests.aDuelistIsOutOfReachWhileTheDuelRuns` ;
  - **33** : `SpeechLimiterTest` › *the engine speaks within its share and leaves the Director its own* ; GameTests
    `PlayerActionGameTests.aRefusedFarTeleportLoadsNothing` et `hostileActionsTellTheirCategory` (`glowing`) ;
  - **35** : GameTests `FavorGameTests.aJokerIsPlayedOnlyAgainstAPunishment` (joker gardé après un épisode seul),
    `PunishGameTests` (`smite`, `tree_top`) ;
  - **39** : `TargetingPolicyTest` › *a withdrawn punish counts nowhere* ; GameTest
    `PunishGameTests.aPunishThatDidNothingCountsNowhere` (un éclair qui ne trouve que des paratonnerres) ;
  - **`jokersGivenAt`** : GameTest `FavorGameTests.favorGrantsAPardonAGraceAndAJokerWithinTheirLimits` (la copie des
    garde-fous le voit).
- Tests existants ajustés par ces changements : `PunishmentCatalogTest`, `SpeechLimiterTest` (*each firing of a rule
  has its own 4 lines*), `PunishGameTests.theSeverityIsCappedByTheChaosLevel` (les invités viennent d'un autre acte),
  `GuardFavorGameTests.aPardonEndsAPunishmentWithNothingLeftToLift` (`isPunished` faux pour un épisode seul).
- Aucun appel réel à Claude ; pas de `runServer`.
