# Passation M2-fix-A : garde de mort et effets

Branche `worktree-agent-a7f8c06df00cdce08`. Ce lot corrige les constats **1, 2, 3, 10, 11, 12 et 13** de la relecture de M2 (`docs/design/reviews/M2_REVIEW.md`), ainsi que l'attribution par `CreatureShield.isNarratorsDoing` du constat 9, selon les décisions du superviseur. En chemin, il corrige un défaut de l'`AttributeLedger` que le coussin de chute rendait dangereux (voir « Hors constats »). **En cas de contradiction, le code fait foi**, et ce fichier explique pourquoi.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `guard/Peril.kt` | **nouveau** : ce qu'une action peut plausiblement tuer (`FALL`, `FIRE`, `DROWNING`, `MAGIC`, `HUNGER`, `ANY`) |
| `guard/DeathGuard.kt` | armement par péril, attribution par `CreatureShield.isNarratorsDoing` et par péril, une réanimation par épisode, sauvetage du vide, `cushionFall`, `fallSeconds`, `armedFor` |
| `guard/EffectLedger.kt` | `apply` oublie l'ancienne entrée avant de retirer l'effet ; `entryOf` |
| `guard/AttributeTable.kt` | `AttributeKey.peril` et `favorableUpTo` (0,6 pour `jump_strength`) |
| `tool/action/PlayerActionTools.kt` | marge de 30 s, péril de chaque armement, remplacement d'un bienfait, résistance III, `set_attribute` favorable sans armement, téléportation (garde 30 s, joueur protégé refusé), budget d'objets précieux dans `GiftLimiter` ; descriptions de `apply_effect`, `set_attribute`, `teleport`, `give_item` |
| `tool/action/ItemSpecs.kt` | plafond d'enchantement, `preciousness` |
| `guard/GuardState.kt` | **ajout** des champs `perils` et `revivedUntil` (codec : `perils`, `revived_until`, facultatifs) |
| `guard/Guards.kt` | **ajout** de `armDeathGuard(target, seconds, peril)`, avec un corps par défaut (les faux l'ignorent) |
| `guard/GuardsImpl.kt` | `DeathGuard` construit après l'`AttributeLedger`, qu'il reçoit ; la nouvelle surcharge ; `onEffectEnded` (ajout). `onDeath` n'est pas touché. |
| `guard/GuardModule.kt` | **ajout** du crochet `ServerMobEffectEvents.AFTER_REMOVE` |
| `guard/AttributeLedger.kt` | une ligne : l'ancien modificateur est retiré avant le nouveau |
| `docs/design/M1_M2.md` | §9.3 (`apply_effect`, `set_attribute`, `teleport`, `give_item`), §10.2 (`GuardState`, `AttributeLedger`), §10.3 (hostile, `DeathGuard`) |

Tests : `AttributeTableTest`, `DeathQuotaTest`, `PlayerActionToolsTest` (unitaires) ; `GuardGameTests` (5 nouveaux, 2 mis à jour) et `PlayerActionGameTests` (6 nouveaux, 3 mis à jour).

## Le mécanisme central : l'armement par péril (constat 11)

Avant, une garde armée rendait **toute** mort attribuée (sauf PvP et `/kill`) pendant sa durée, et une mort empêchée la réarmait 5 s à chaque coup : un joueur sous 15 min de lenteur, au quota épuisé, était immortel.

- **`Peril`** : chaque armement nomme ce que l'action peut tuer, par types de dégâts. `FALL` couvre la chute, le vide, `fly_into_wall`, la stalagmite, et ce sur quoi on atterrit (lave, feu, sol brûlant, feu de camp, cactus, baies). `FIRE` couvre la lave et le feu, `DROWNING` la noyade, `MAGIC` `magic`, `indirect_magic` et `wither`, `HUNGER` la faim. `ANY` couvre tout, pour ce qui affaiblit le joueur contre tout.
- **État** : `GuardState.perils` associe l'identifiant d'un péril à la valeur de `PLAY_TIME` jusqu'où il est armé. `armedUntil` reste le plus tardif (lu par `/narrator guards` et par `isArmed`).
- **Attribution** (`DeathGuard.attributed`) : `generic_kill`, jamais ; créature du Narrateur **ou ce qu'elle possède** (`CreatureShield.isNarratorsDoing`), toujours (constat 9 : un vex d'un évocateur du Narrateur, une flèche) ; coup d'un autre joueur, jamais ; sinon, un péril armé qui couvre la cause.
- **API** : `Guards.armDeathGuard(uuid, seconds, peril)`. L'ancienne `armDeathGuard(uuid, seconds)` arme `ANY` : les appels du lot B (`spawn`, `lightning`, `explosion`) gardent leur comportement.
- **N'armer que le nuisible** : `set_attribute` n'arme plus rien pour un changement favorable. Un changement défavorable arme `durée + 30 s` pour le péril de l'attribut (`null` : rien à armer).

Table des périls, dans le code :
- **Effets** (`Effects.perilOf`, donnés s'ils sont nuisibles, retirés s'ils sont bénéfiques) : lévitation, chute lente, saut amélioré → `FALL` ; wither, poison, dégâts instantanés → `MAGIC` ; faim → `HUNGER` ; résistance au feu → `FIRE` ; respiration aquatique, force de conduit, grâce du dauphin, souffle du nautile → `DROWNING` ; chance, malchance, héros du village, surbrillance → rien ; tous les autres → `ANY`.
- **Attributs** (`AttributeKey.peril`) : `gravity`, `jump_strength`, `safe_fall_distance`, `fall_damage_multiplier`, `bounciness`, `air_drag_modifier` → `FALL` ; `oxygen_bonus` → `DROWNING` ; `burning_time` → `FIRE` ; `block_break_speed`, `step_height`, `block_interaction_range`, `mining_efficiency`, `luck`, `sneaking_speed` → rien ; les autres → `ANY`.
- **Outils** : `launch` → `FALL` 15 s ; `take_item` → `ANY` 15 s ; `teleport` → `ANY` 30 s.

### Une réanimation par épisode

- Au-delà du quota, `allowDeath` refuse la mort **si `revivedUntil` est passé**. Le joueur est remis à 1 PV, la garde est réarmée 5 s (`ANY`), puis `revivedUntil` prend la valeur de `armedUntil` : la fin de l'armement en cours. Décision `death_guard prevented`, dont le `detail` porte `revived_until` (et `rescued_to` pour le vide).
- **Une seconde mort attribuée dans le même épisode a lieu**, avec l'inventaire conservé. Elle est comptée : `narratorKills` peut dépasser le plafond, et la raison de `counted` le dit. J'ai choisi de conserver l'inventaire plutôt que de le faire tomber : la mort vient bien du Narrateur, et §17 promet que l'inventaire est gardé.
- **« Épisode »** désigne donc l'armement pendant lequel la réanimation a eu lieu, pas l'`Episodes` des châtiments. Un nouvel armement qui prolonge celui-là ne rend pas de réanimation avant la fin de `revivedUntil`. Cette lecture est prudente.
- L'ancienne limite de journalisation, une décision `prevented` au plus toutes les 5 s, est retirée : une réanimation par armement d'au moins 5 s donne déjà ce rythme.
- **À signaler au propriétaire** : §17.4 dit « au-delà du quota, la mort est empêchée ». C'est désormais vrai **une fois par épisode**. La phrase gagnerait à le dire ; je n'ai pas touché `ARCHITECTURE.md`.

## Constat 1 : la lévitation

- La marge d'un effet nuisible passe de 5 à **30 s**, pour tous les effets nuisibles, avec le péril de l'effet (`FALL` pour la lévitation).
- **Fin de la lévitation du Narrateur** : le crochet `ServerMobEffectEvents.AFTER_REMOVE` appelle `GuardsImpl.onEffectEnded`. Fabric le déclenche à `onEffectsRemoved` : expiration dans `tickEffects`, `removeEffect` et le lait, vérifié en bytecode. Si l'entrée de l'`EffectLedger` est encore là, lue sans élagage par `entryOf`, la lévitation était celle du Narrateur. Le crochet appelle alors `DeathGuard.cushionFall`, qui fait deux choses :
  - `fall_damage_multiplier × 0` par l'`AttributeLedger` (faveur, décision `ledger applied`), pendant **30 s + la durée d'une chute depuis la hauteur du joueur jusqu'au bas du monde** (`fallSeconds`, qui simule la gravité et le frottement du jeu, avec la gravité actuelle du joueur, 900 s au plus). La décision dit 30 s ; j'ai allongé : une lévitation IV de 900 s (chaos 5) monte d'environ 3 600 blocs, soit une chute de près de 50 s.
  - la garde armée `FALL` pour la même durée, en temps joué.
- Un coussin favorable du Narrateur déjà en place, à 0 et plus long, est gardé.
- `EffectLedger.apply` oublie l'entrée **avant** de retirer l'effet qu'il remplace : une lévitation remplacée par une autre ne déclenche pas de coussin. Il n'est posé qu'à la fin de la dernière.
- `remove_effect levitation` et `all_narrator` retirent l'effet puis l'entrée : la fin est amortie. Lever la lévitation en plein ciel ne tue donc plus.
- Un joueur mort (`isDeadOrDying`) n'a pas de coussin.

## Constat 2 : un bienfait qui raccourcit un effet vital

`ToolContext.replacing`, pour un effet non nuisible et non instantané que le joueur a déjà, comparé à l'instance visible (`getEffect`) :
- **au moins aussi long et aussi fort** (ni plus long, ni plus fort) : refus `Invalid arguments: Bob already has fire_resistance I for 7m50s` (`with no end` pour un effet infini). Il n'y a rien à donner, même à un joueur protégé ;
- **plus long mais plus faible, ou plus fort mais plus court** : c'est un retrait, donc une action hostile. Un épisode de 30 s est ouvert (`punish`), comme pour `remove_effect`, et la garde est armée pour le péril de l'effet **pendant la durée du nouvel effet + 30 s**. La décision dit « 30 s » ; ce qui est perdu disparaît quand le nouvel effet, plus court, se termine. Le retour le dit : `ok: Bob (in place of resistance I for 8m: a punishment) gets resistance II for 30 s …` ;
- sinon (plus long ou plus fort, sans rien perdre) : un cadeau, comme avant.

## Constat 3 : le vide au-delà du quota

- Le passe-droit `|| FELL_OUT_OF_WORLD` est retiré. Une mort du Narrateur dans le vide, au quota épuisé, suit le chemin de la réanimation, précédée d'un **sauvetage** (`DeathGuard.rescue`) :
  1. le point de réapparition, tel que le jeu le trouve (`findRespawnPositionAndUseSpawnBlock(false, …)`) : un lit, une ancre **sans consommer de charge**, un point forcé, sinon le point d'apparition du monde. La transition est reconstruite sans le drapeau « pas de lit » : le joueur ne réapparaît pas, il ne doit pas recevoir ce message ;
  2. sinon, le sol sûr de la colonne où il est tombé (`Teleports.landing`, tronçons chargés seulement, puis `Teleports.move`) ;
  3. sinon, la vie est rendue telle qu'elle était, et la mort a lieu, inventaire conservé.
- Le joueur est à 1 PV, sans vitesse ni distance de chute, et reçoit le coussin de chute (`cushionFall`). Décision `death_guard prevented`, avec la cause `minecraft:out_of_world` (c'est l'identifiant du type de dégâts `FELL_OUT_OF_WORLD`) et `rescued_to`.
- Le sauvetage a lieu **dans** `hurtServer` (`ALLOW_DEATH`). La vie est remise à 1 **avant** de déplacer le joueur. Un changement de dimension à cet endroit est ce que fait le jeu pour un portail pendant le tick de l'entité.

## Constat 10 : `jump_strength`

`AttributeKey.favorableUpTo` vaut 0,6 pour `jump_strength` (`Double.MAX_VALUE` pour les autres). Pour `HIGHER`, `harmful` renvoie vrai si la valeur baisse **ou** dépasse ce seuil. Au-delà de 0,6, le changement est hostile : plafond de durée du chaos, épisode, garde `FALL`.

## Constat 12 : téléportation

- Un joueur protégé (`isProtected(uuid)`) est refusé **avant tout chargement**, de près comme de loin : `DENIED (protected): Bob is protected by the operators; the Narrator never teleports them.`
- Toute téléportation arme la garde `ANY` 30 s juste avant le déplacement. L'épisode de 60 s d'une téléportation lointaine est inchangé.

## Constat 13 : plafonds des bienfaits

- `apply_effect resistance` au niveau **III** au plus : `Invalid arguments: resistance goes up to level 3: …`.
- **Enchantements** : niveau maximum du jeu + 1 au plus (`Enchantment.getMaxLevel() + 1`, soit tranchant 6), vérifié dans `ItemSpecs.resolve` : `Invalid arguments: sharpness goes up to level 6, one past the game's own maximum (asked 7)`. La doc du schéma le dit.
- **Objets précieux** : chaque joueur a **4 points par heure glissante**, en mémoire, dans le `GiftLimiter` du serveur.
  - 1 point : diamant, minerais de diamant, pomme dorée.
  - 4 points, soit toute l'heure : tout `minecraft:netherite_*` et `minecraft:diamond_*` (équipement, bloc), débris antiques, élytres, totem, pomme dorée enchantée, balise, étoile du Nether, conduit, cœur de la mer, noyau lourd, masse, trident, œuf de dragon, carapace de shulker, et tout le tag `#shulker_boxes`.
  - Le don coûte `points × count`. Refus : `DENIED (precious_limit): Bob got 3 of the 4 precious points a player may get in an hour, and 2x diamond is worth 2; it is possible at 21:30:00 (in 40m). The Narrator slows progression down: …`. Un refus ne compte ni dans les points ni dans les 20 dons.
  - La liste et les seuils sont un choix de ma part, à ajuster : la décision disait « une petite quantité ».

## Hors constats : un défaut de l'`AttributeLedger`

`AttributeInstance.addOrUpdateTransientModifier` du jeu remplace l'entrée par identifiant, mais **laisse l'ancien modificateur dans la table de son opération** quand l'opération change (vérifié en bytecode). Un `set_attribute add` suivi d'un `multiply` sur le même attribut comptait donc les deux : la valeur réelle dépassait l'aperçu vérifié contre les bornes. Surtout, après le retrait, l'ajout restait sur le joueur **hors du registre** jusqu'à sa déconnexion.

Le coussin de chute (une multiplication) posé sur un `fall_damage_multiplier +2` du Narrateur aurait laissé les dégâts de chute triplés pour de bon. `apply` retire maintenant l'ancien modificateur avant de poser le nouveau (une ligne). GameTest : `aModifierReplacedByAnotherOperationLeavesNothingBehind`.

## Pour les autres lots

- **Lot B** : `armDeathGuard(uuid, seconds)` arme `ANY` ; `armDeathGuard(uuid, seconds, peril)` est disponible. La foudre (`lightning_bolt`) et les explosions (`explosion`, `player_explosion`) ne sont couvertes que par `ANY` ; si le lot B veut un péril plus précis, il faut l'ajouter à `Peril`. Pour une monture du Narrateur qui descend un joueur (constat 4), `DeathGuard.cushionFall(player, turnId)` pose le coussin et arme `FALL`. `GuardsImpl.death` le rend accessible.
- **Lot EF** (constat 18) : l'état de la garde tient désormais dans `armedUntil`, `perils` (péril → échéance, en ticks joués) et `revivedUntil`. `DeathGuard.allowDeath` et `afterDeath` écrivent `prevented` et `counted` ; je n'ai pas touché `GuardsImpl.onDeath`. Une ligne ajoutée à ces décisions devra se fusionner avec ce fichier.
- **Lot G** (`director.md`, `ToolText`) :
  - nouveaux refus que le prompt peut annoncer : `already has …` (un bienfait ne raccourcit pas un effet), `precious_limit`, résistance III, enchantement max + 1, téléportation d'un joueur protégé ;
  - un bienfait qui remplace un effet plus long ou plus fort est un châtiment ;
  - la marge des effets nuisibles est de 30 s.
  - Deux constantes « 20 ticks par seconde » de plus (`PlayerActionTools.TICKS_PER_SECOND`, `DeathGuard.TICKS_PER_SECOND`), et le format de durée est celui de `TargetingPolicy.span`.
- **Tests en jeu** : un joueur de test n'est pas « tické » par sa connexion. Pour faire expirer un effet, appeler `player.doTick()`, ce qui fait aussi avancer `PLAY_TIME` d'un tick (voir `theEndOfALevitationCushionsTheFallAndStaysTheNarrators`).

## Limites qui restent

- **La seconde mort d'un épisode a lieu** : un joueur coincé dans la lave au quota épuisé meurt au second coup, avec son inventaire. C'est la décision (« au plus une réanimation »). §17.4 est à reformuler (voir plus haut).
- **`ANY` reste large** : une lenteur, une cécité ou une téléportation rendent attribuée toute mort non-PvP pendant leur garde. La réanimation unique borne cet effet. Il n'y a pas de péril « créatures », parce qu'un joueur ralenti tué par un zombie ordinaire est bien une conséquence plausible.
- **Sauvetage vers une autre dimension** : le cas d'un lit dans l'Overworld et d'une chute dans le vide de l'End n'est pas testé en jeu. Le GameTest couvre un point de réapparition forcé dans la même dimension. Le chemin est celui du jeu (`ServerPlayer.teleport(TeleportTransition)`).
- **Durée du coussin** : elle est calculée à la fin de la lévitation, avec la gravité du moment. Une gravité baissée par le Narrateur **après** ce calcul allonge la chute au-delà du coussin ; la garde, elle, reste armée pour la chute.
- **Remplacement d'un bienfait** : la comparaison porte sur l'instance visible. Un effet plus long caché derrière elle par le jeu (`hiddenEffect`) n'est pas vu, et `removeEffect` l'efface.
- **Protection par nom** : `isProtected(uuid)` ne connaît les noms de `safety.protectedPlayers` que pour les joueurs vus à la connexion par les garde-fous. Un joueur entré avant le démarrage du Narrateur pourrait être téléporté de près. Le refus de loin passe toujours par `hostile()`.
- **Objets précieux** : le budget vit en mémoire (un redémarrage le remet à zéro) et compte par joueur. Les objets d'autres mods ne sont pas classés, et rien ne limite l'équipement en fer non plus.
- **Anciens armements** : un état sauvé avant ce lot (`armedUntil` sans `perils`) n'attribue plus rien. Ces armements duraient quelques minutes, et je n'ai pas écrit de reprise.
- **Coussin inutile** : une lévitation qui finit au sol, sous un plafond, pose quand même un coussin de 30 s. Il est sans danger.

## Vérifications

- `gradlew build` passe **après fusion de `main`** (lot CD et T22 compris) : **660 tests unitaires, 153 GameTests**, sans échec.
- Nouveaux tests unitaires :
  - `AttributeTableTest` : `jump_strength` au-delà de 0,6, périls des attributs ;
  - `DeathQuotaTest` : `fallSeconds` (540 blocs ≈ 9-10 s, 3 664 blocs > 45 s, gravité lunaire, gravité nulle bornée) ;
  - `PlayerActionToolsTest` : budget d'objets précieux (message, reste, heure glissante, don trop précieux pour une heure).
- Nouveaux GameTests :
  - `GuardGameTests` :
    - `aSecondDeathOfTheSameEpisodeGoesOnWithTheInventory` (11) ;
    - `theGuardCoversOnlyWhatTheActionCanKillWith` (11) ;
    - `aDeathInTheVoidBeyondTheQuotaIsARescue` (3 : survie, point de réapparition, coussin, décision lue dans `engine_decisions`) ;
    - `aKillByTheVexOfTheNarratorsEvokerIsTheNarrators` (9) ;
    - `aModifierReplacedByAnotherOperationLeavesNothingBehind`.
  - `PlayerActionGameTests` :
    - `theEndOfALevitationCushionsTheFallAndStaysTheNarrators` (1 : garde de 31 s, expiration réelle par les ticks du joueur, coussin, garde de la chute plus longue, puis mort de chute après la garde de l'effet, attribuée et inventaire conservé) ;
    - `aBeneficialEffectNeverCutsAVitalOneShort` (2, y compris sur un joueur protégé) ;
    - `onlyAChangeInThePlayersDisfavorArmsTheGuard` (11 et 10) ;
    - `anyTeleportArmsTheGuardAndNeverMovesAProtectedPlayer` (12) ;
    - `resistanceEnchantmentsAndPreciousItemsAreCapped` (13).
  - Mis à jour : le codec de `GuardState`, l'oubli des armements échus, la résistance au feu retirée (garde `FIRE`), `jump_strength × 3` hostile, et l'épée donnée (tranchant 6 au lieu de 7).
- **Contre-épreuves**, correctifs retirés par lots puis remis, **chaque test visé échoue sur sa propre assertion** :
  - **lot 1** (`DeathGuard`) : passe-droit du vide, réanimation illimitée, attribution sans péril, attribution par tag seul. Échecs : `the death in the void is refused`, `the second death of the episode goes on`, `not drowning`, `a kill by the vex is the Narrator's`.
  - **lot 2** : sans crochet de fin de lévitation, `no fall damage` ; sans `replacing`, `refused` ; sans plafond 0,6, le test unitaire et `above 0.6, every jump hurts` ; avec l'armement d'un changement favorable, `a favor arms nothing` ; sans la garde de téléportation, `but the death guard is armed 30 s` ; sans le plafond de résistance, `resistance IV` accordée ; sans la ligne de l'`AttributeLedger`, `nothing left behind`.
  - **lot 3** : marge ramenée à 5 s, `armed … 30 s more` pour l'effet et l'attribut ; sans refus du joueur protégé, `a protected player` ; sans plafond d'enchantement, `sharpness VII` ; sans budget précieux, le test unitaire.
  - **lot 4** : sans budget précieux, le GameTest (`gave 2x diamond`).
- Pas de `runServer`.
