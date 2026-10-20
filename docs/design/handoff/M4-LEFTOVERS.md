# Passation M4-LEFTOVERS : les reliquats de M4

Trois reliquats de la recette réelle de M4 (`M4-24.md`, « Pour la suite » de `M4-POST.md`), plus un GameTest instable
signalé par M5-05. **En cas de contradiction avec `M4.md`, `M4-POST.md` ou une passation antérieure, le code et cette
passation font foi.**

Branche `worktree-agent-aa1699ebc8635c826`, `main` (M5-05, `140f998`) fusionné.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `guard/NarratorMobs.kt` | `NarratorMob.SPAWN` ; `NarratorMob.punishment` vaut aussi `spawn` ; `retirePunishments` renvoie les créatures que `CreatureShield.boundTo` lie à leur joueur |
| `guard/CreatureShield.kt` | `boundTo` : une créature d'un module n'est liée à personne |
| `guard/GuardsImpl.kt` | `allowDeath` : la raison du sauvetage nomme `spawn` ou `punish <id>` ; `activeOn` passe par `Escrow.active` et trie les entrées sans échéance en dernier |
| `tool/action/WorldActionTools.kt` | `spawn` marque ses créatures hostiles `spawn` ; une phrase de sa description |
| `guard/Escrow.kt` | `Escrow.active` : une entrée du registre parmi les effets actifs, une mise ou un dû sous son but |
| `guard/Guards.kt` | `ActiveEffect.until` devient nullable : pas d'échéance pour une mise ni pour un dû |
| `director/Briefing.kt`, `director/SituationReport.kt`, `oracle/PlayerReport.kt`, `command/GuardCommands.kt`, `command/MechanicsCommands.kt` | une ligne chacun : rien après `until` quand il n'y a pas d'échéance |
| `tool/action/PlayerActionTools.kt` | description de `apply_effect` : « A harmful effect, glowing included, is a punishment » |
| `docs/ARCHITECTURE.md` §17 | une phrase : les créatures hostiles de `spawn` ne tuent pas non plus, sauf celles d'un module |
| tests | `PunishmentCreaturesGameTests` (2 GameTests) ; `PlayerActionGameTests` (une assertion) ; `MechanicsCommandGameTests.theCommandsAnswerOnTheTestServer` ; `LedgerDescriptionsTest`, `BriefingTest`, `SituationReportTest` (un test chacun) |

## 1. Une créature de `spawn` ne tue pas

### Ce qui est fait

On réutilise le mécanisme de M4-POST tel quel, sans en créer un second : la marque `NarratorMob.punishment`, puis
`CreatureShield.boundTo`, puis `GuardsImpl.allowDeath` et `NarratorMobs.retirePunishments`. `spawn` marque désormais
`punishment = "spawn"` (`NarratorMob.SPAWN`) sur toute créature **hostile** qu'il fait apparaître. Une créature est
hostile quand elle peut blesser ou qu'elle est envoyée sur le joueur (`target`) : c'est le `hostile` qui ouvre déjà la
punition `creatures`. `punish` garde sa marque (`guests`, `slime_rain`).

Une telle créature appartient à son seul joueur, comme les invités :
- elle ne prend pour cible et ne blesse que lui (ciblage, dégâts, effets) ;
- elle part à sa mort, à son départ, ou au coup fatal refusé ;
- son coup fatal devient un sauvetage sur place : 3 cœurs, rien de perdu, rien compté, bouclier de 5 minutes. La
  décision le dit : `death_guard rescued`, `spawn: a fatal blow of its creatures, 1 minecraft:zombie sent away; the
  Narrator's creatures never kill`.

Un coup qui ne tue pas passe toujours : l'invocation garde son sens. Un animal inoffensif sans `target` (les poulets du
comité d'accueil) n'est pas marqué. Il ne blesse personne, et il reste quand son joueur meurt.

La description de `spawn` le dit en une phrase : « Those go for that player alone, and their blows never kill. »

### Décisions

- **Tout appelant de `spawn`, pas seulement le Directeur.** La marque ne dépend pas de l'appelant : les créatures d'une
  règle (une loi qui invoque des zombies) sont liées de même. Le moteur agit « par les outils mêmes du Directeur » :
  une règle ne doit pas pouvoir faire ce que le Directeur ne peut pas.
- **Sauf les créatures d'un module.** Un acte d'un module invoque aussi par `spawn`, puis le module les adopte (tag
  `narrator2_module_<id>`, `CreatureControl.adopt`). M5 §4.5 les règle autrement : le module les vise
  (`creature_attack` peut les envoyer sur un autre joueur), un passant n'est jamais tué, et la mort de leur cible va à
  la garde de mort (quota). `CreatureShield.boundTo` ne lie donc jamais une créature d'un module, même si elle porte la
  marque `spawn`. C'est la seule exception, posée là où le lien est lu ; `forge/` n'est pas touché. Entre l'invocation
  et l'adoption (au plus un tick), la créature est liée au joueur visé, ce qui ne change rien.
- **L'explosion d'`explode` reste une explosion.** Une créature mise à feu (`explode`) disparaît puis explose : le
  souffle n'est pas « son coup », c'est une explosion du Narrateur (catégorie `explosion`, gardes par joueur
  touché), comme avant. Le souffle propre d'un creeper invoqué, lui, est son coup : il ne touche que son joueur et ne
  le tue pas.
- Les créatures qu'une règle n'a fait que **marquer** (le tag seul, `mob_spawn` : « creepers sous caféine ») ne sont
  pas invoquées : rien ne change pour elles.
- Le nom du champ reste `punishment` : une créature envoyée sur un joueur est une punition de ce joueur (description de
  `spawn`). `spawn` n'est pas un châtiment du catalogue, d'où la raison du sauvetage, `spawn:` plutôt que
  `punish spawn:`.

### Tests

GameTests de `PunishmentCreaturesGameTests` (lot par défaut) :
- `aZombieOfSpawnHurtsItsPlayerButNeverKills` : un `spawn` zombie (`target`) du Directeur sur Pistache. Le zombie porte
  `spawn`. Bartok, voisin, n'est pas une cible valable et son coup ne l'atteint pas. Un premier coup blesse Pistache
  sans le tuer, et le zombie reste. Ensuite, à 1 PV, le coup fatal est refusé : sauvetage sur place, 6 PV, diamants
  gardés, ni mort ni compte, un bouclier, et le zombie est renvoyé.
- `aModulesCreatureIsBoundToNobody` : le zombie d'un `spawn`, lié, ne l'est plus une fois marqué comme celui d'un
  module (`RuleMarks.mark(…, MarkOwner.module(…))`), et il reste quand son joueur part.

## 2. Une mise ou un dû dit son but

`Escrow.active(entry, now, name)` (fonction pure) range une entrée du registre parmi les effets actifs.
`GuardsImpl.activeOn` l'appelle, et tous les rendus en profitent : situation, briefing, `inspect_player`,
`/narrator guards`.

| Entrée | `what` | `until` |
|---|---|---|
| mise en cours | `2x minecraft:emerald staked on C-50: a bet that Mimosa wins` | aucune |
| mise perdue, à céder | `2x minecraft:emerald staked on C-50 and lost: goes to Mimosa once both are online` (`its winner` quand le gagnant n'est pas en ligne) | aucune |
| mise réglée, en attente de place | `2x minecraft:emerald staked on C-50 and settled (back to them once their inventory has room)` | aucune |
| dû | `1x minecraft:golden_apple owed to them (given once their inventory has room): the reward of the challenge C-50` | aucune |
| confiscation | `3x minecraft:diamond` | son retour, nuisible |

**Décision : pas d'échéance pour une mise ni pour un dû.** `ActiveEffect.until` devient `Long?`. L'heure affichée
trompait autant que l'absence de but :
- celle d'une mise est son échéance de secours, sept jours après la fin prévue du défi : `until 21:40` ne disait pas
  le jour ;
- celle d'un dû est l'heure où il est né, déjà passée.

C'est le défaut que M4-24 (constat 2) avait vu sur les offrandes : « le guichet ferme à 18h18 ». Les rendus n'écrivent
plus `until …` quand `until` est nul : la situation, le briefing, `inspect_player` (`(stake)` sans « ends in »), le
résumé de `/narrator guards` et `/narrator traps` (les pièges en ont toujours une). Le tri met les entrées sans
échéance en dernier. Les lignes du registre pour les opérateurs (`/narrator guards <joueur>`, `ledgerLine`) avaient
déjà leur but : elles ne changent pas.

Le gagnant d'une mise perdue est nommé s'il est en ligne (`PlayerStates`). Sinon, c'est « its winner » : la mise n'est
listée chez le perdant que jusqu'à ce que les deux joueurs soient en ligne, et la ligne du défi dit qui l'a gagné.

Tests unitaires :
- `LedgerDescriptionsTest` › *a stake and what is owed read under their purpose, with no deadline* : les cinq cas ;
- `SituationReportTest` et `BriefingTest` › *a stake and what is owed tell their purpose among the active effects* :
  la ligne `Your active effects` rendue en entier, une mise et un dû sans `until`, un effet avec le sien.

## 3. `apply_effect` dit la vérité sur `glowing`

Vérifié dans le code et dans le jeu :
- `Effects.harmful` tient pour nuisible tout effet qui n'est pas `BENEFICIAL`. `glowing` est `NEUTRAL` dans 26.3
  (javap `-c` de `MobEffects` : `glowing` → `MobEffectCategory.NEUTRAL`). Les autres effets neutres sont les présages
  (`bad_omen`, `trial_omen`, `raid_omen`), déjà interdits ;
- donc `glowing` passe par `hostile` (catégorie `senses`), est plafonné par le chaos, et `EffectLedger` l'inscrit
  nuisible (le pardon le lève).

La description de `apply_effect` dit maintenant « A harmful effect, glowing included, is a punishment: … ». Une
assertion de `PlayerActionGameTests.hostileActionsTellTheirCategory`, juste après celle du registre, vérifie que la
description le dit. Rien d'autre ne change dans le schéma. Les conversations déjà ouvertes gardent l'ancienne liste
d'outils, figée ; les suivantes ont la nouvelle description.

## 4. `theCommandsAnswerOnTheTestServer` indépendant des autres lots

### Cause

`/narrator traps restore` prend tous les pièges du serveur. Il en compte comme « in a chunk still loading » ceux dont
le tronçon est dans l'index mais que `getChunkNow` ne rend pas. C'est le cas d'un tronçon en cours de chargement, mais
aussi d'un tronçon **lâché et pas encore déchargé**, ou **gardé en mémoire sous le niveau FULL**. Un tronçon qui était
FULL et qui se retrouve au niveau 34 dans l'anneau d'une zone chargée garde son `LevelChunk` et ses pièges dans
l'index, sans `CHUNK_UNLOAD`, aussi longtemps que cette zone reste chargée.

Les pièges viennent d'autres lots. `ModuleActsGameTests.aModulesBlocksGoInThinAirOnly…` (lot `forge`) laisse deux
vitres teintées et une toile pour 10 s : ce sont très probablement les « 3 » de l'échec de M5-05. Le test fini, leurs tronçons
sont lâchés avant l'échéance. Le test du lot `mechanics_commands` voyait alors
`2 trap blocks taken away now; 3 in a chunk still loading, taken away at the next pass.`, et sa seconde restauration
ne disait plus « nothing to take away ».

### Correction (le test seul, le code de production ne change pas)

Partir d'un état propre n'est pas sûr : un tronçon de l'anneau peut garder ses pièges indéfiniment, hors de portée de
toute restauration. Le test **vérifie donc la réponse contre l'état même que la commande a vu**, et le piège de
l'opérateur pour lui-même :
- chaque `traps restore` tourne dans une seule tâche du fil serveur, avec le nombre de blocs en attente lu juste avant
  et juste après. Rien ne change entre les deux : aucun tronçon ne se charge pendant une tâche ;
- la réponse attendue est écrite par le test (`restoreReply(before, left)`), sans réutiliser la fonction de production.
  Les blocs partis valent `before - left`, et ceux restés le suffixe « in a chunk still loading » ;
- le piège de l'opérateur (`2 minecraft:glass`) est en attente avant la première restauration, puis absent avant la
  seconde. Ses vitres sont de l'air aussitôt, et il n'est plus dans `/narrator traps` ;
- les décisions `operator` attendues sont exactement celles des restaurations qui avaient quelque chose en attente,
  avec leur réponse. Seul sur le serveur, cela donne la même chose qu'avant : une décision, puis « nothing to take
  away ».

Le test n'est pas affaibli : l'ancien vérifiait la réponse par une expression régulière (`\d+ trap blocks? taken away
now\.`) ; le nouveau la vérifie au caractère près.

### Preuves

Une sonde (non commitée, retirée) pose un piège « d'un autre lot » dans un tronçon lointain chargé de force. Elle force
ensuite le tronçon à trois tronçons de là et lâche le premier. Il passe au niveau 34 : `getChunkNow` le rend nul, et il
reste ainsi au moins 3 s, sans déchargement.
- **Ancien test** avec la sonde : **rouge**, exactement l'échec de M5-05 : `restore: [[Narrator] 2 trap blocks taken
  away now; 1 in a chunk still loading, taken away at the next pass.]`.
- **Nouveau test** avec la sonde : **vert**. Première restauration : 3 en attente, `2 trap blocks taken away now; 1 in a
  chunk still loading…`. Seconde : 1 en attente, `0 trap blocks taken away now; 1 in a chunk still loading…`. Deux
  décisions `operator`.

La sonde confirme la cause : un tronçon gardé sous le niveau FULL garde ses pièges dans l'index sans être prêt, et ce
n'est pas passager.

## Pour la suite

- Les créatures d'un module restent régies par M5 §4.5 : un module peut tuer sa cible dans le quota. Si l'on veut
  qu'« une créature invoquée par le Narrateur ne tue jamais » vaille aussi pour les modules, c'est à décider en M5, et
  il suffira alors de retirer l'exception de `CreatureShield.bound`.
- Le souffle d'une créature mise à feu (`explode`) peut toujours tuer, comme toute explosion du Narrateur (quota, garde
  de mort).
- `/narrator traps restore` dit « in a chunk still loading » aussi pour un tronçon lâché ou gardé sous le niveau FULL.
  Le libellé est un peu inexact, sans conséquence : ces pièges partent quand leur tronçon redevient prêt. Un GameTest
  qui compte les pièges du serveur doit faire comme celui-ci : lire l'état dans la même tâche que la commande.
- `ModuleActsGameTests.aModulesBlocksGoInThinAirOnly…` laisse 3 blocs pour 10 s après sa fin. C'est sans gravité
  désormais, mais la Forge peut les reprendre à la fin de son test.

## Vérifications

- `git merge main` (M5-05, `140f998`) : sans conflit (`ARCHITECTURE.md` fusionné automatiquement).
- **`.\gradlew.bat build` sur l'état fusionné : vert**, **1 857 tests unitaires** (0 échec, 0 ignoré) et **« All 376
  required tests passed »** (374 de `main` + les 2 de `PunishmentCreaturesGameTests`).
- Avant ce build vert, deux `build` ont échoué sur des tests unitaires de la Forge (M5-05), **hors de cette tâche** :
  - `ModuleHostTest` › *twenty unloaded loaders out of twenty are collected…* (« 1 of 20 loaders are still alive ») ;
  - `ModuleHostTest` › *over 50 ms of its code a second…* (« 18 ms is within a call's budget », coupé par le budget de
    temps) ;
  - `HostBatteryTest` › *a finally cannot swallow the meter's error…*.

  Ces tests dépendent du ramasse-miettes et du temps. Ils échouent quand `test` tourne en même temps que `runGameTest`,
  ou que d'autres agents compilent. `ModuleHostTest` seul passe, et le build relancé sans rien changer est vert. À
  signaler à M5 : c'est le même genre d'instabilité que celle du cas des sous-listes, corrigée par M5-05.
- **Trois `runGameTest` complets d'affilée**, sans rien changer : verts, « All 376 required tests passed » chaque fois.
  Ordres des lots, avec `mechanics_commands` en gras :
  1. mechanics > default×7 > live_evals > memory > mechanics_e2e > rules > **mechanics_commands** > default(1) >
     assembly > forge > live_evening > director ;
  2. director > default×7 > assembly > rules > mechanics > default(1) > live_evals > mechanics_e2e > memory >
     live_evening > **mechanics_commands** > forge ;
  3. mechanics > memory > rules > **mechanics_commands** > forge > mechanics_e2e > default×7 > live_evening >
     assembly > default(1) > live_evals > director.

  `forge` n'est jamais passé juste avant `mechanics_commands` dans ces trois lancements. La sonde du point 4 reproduit
  ce cas de façon déterministe.
- Sondes (filtre `JAVA_TOOL_OPTIONS=-Dfabric-api.gametest.filter=narrator2-gametest:…`, non commitées, retirées) :
  - **sans la marque `spawn`** (`bound = punishment?.id`), `aZombieOfSpawnHurtsItsPlayerButNeverKills`, ses premières
    assertions changées en traces : le zombie prend Bartok pour cible, son coup le blesse (16 PV), puis le coup fatal
    tue Pistache, mort comptée (« counted … a death caused by the Narrator… 1 of 1 »). Le test échoue sur « the fatal
    blow is refused » ;
  - **sans l'exception des modules** dans `CreatureShield.bound` : `aModulesCreatureIsBoundToNobody` échoue (« a
    module's, bound to nobody: expected null, got NarratorMob(… punishment=spawn) ») ;
  - **les pièges d'un autre lot** : voir le point 4.
- Vérifié avec javap : `Mob.doHurtTarget(ServerLevel, Entity): boolean`. Avec javap `-c`, la catégorie des effets de
  `MobEffects` : `glowing` est `NEUTRAL`, comme `bad_omen`, `trial_omen` et `raid_omen`.
- Aucun appel réel à Claude ; pas de `runServer`. `director.md`, `forger.md` et `forge/` ne sont pas touchés.
- Les journaux de build sont dans un dossier propre à cette tâche. Le dossier de travail temporaire est commun à tous
  les agents de la session : deux journaux au même nom (`build1.log`) s'y sont mélangés une fois. Utilisez des noms
  uniques.
