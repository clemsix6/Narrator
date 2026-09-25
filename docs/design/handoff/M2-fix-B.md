# Passation M2-fix-B : créatures et monde

Branche `worktree-agent-a5eda18006a1c61a3`. Ce lot corrige les constats **4, 5 et 6** de la relecture de M2 (`docs/design/reviews/M2_REVIEW.md`), plus la classification « hostile » et les renforts des zombies du **constat 9**, selon les décisions du superviseur. L'attribution des morts dans `DeathGuard` reste au lot A. **En cas de contradiction, le code fait foi**, et ce fichier explique pourquoi.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `guard/NarratorMobs.kt` | une monture reste sous son cavalier (10 min au plus), puis le pose sur un sol sûr ; `retire` (nouveau, public) : la seule façon dont une créature du Narrateur quitte le monde ; aucun renfort (`mark`, `inherit`) ; correctif de `release` (propriétaire lu avant de retirer la laisse) |
| `guard/SpawnPolicy.kt` | `HARMLESS` (liste blanche) et `isHarmless` |
| `tool/action/WorldActionTools.kt` | `spawn` : hostile sauf créature inoffensive, description ; `Fuses.detonate` passe par `NarratorMobs.retire` ; `set_weather` : plus de `thunder`, description |
| `mixin/policy/FarmlandTrampleGuardMixin.java` | **nouveau** : pas de terre cultivée piétinée |
| `mixin/policy/TurtleEggTrampleGuardMixin.java` | **nouveau** : pas d'œuf de tortue piétiné |
| `mixin/policy/RemoveBlockGoalGuardMixin.java` | **nouveau** : les zombies du Narrateur ne cherchent pas les œufs à écraser |
| `mixin/policy/ProjectileBlockGuardMixin.java` | **nouveau** : les projectiles du Narrateur ne touchent aucun bloc ; ils portent le tag dès que leur tireur est posé |
| `narrator2.policy.mixins.json` | les quatre mixins |

Tests : `WorldActionToolsTest` (un test de plus, un modifié) ; GameTests `GuardGapGameTests` (6 de plus) et `WorldActionGameTests` (3 de plus, le test de météo modifié).

## Constat 4 : une monture du Narrateur ne disparaît plus sous son cavalier

### À l'échéance (`NarratorMobs.expire` et `mayGo`)

- Une créature due **qu'un joueur chevauche** (directement ou sur un autre passager, `indirectPassengers`) reste. Une ligne de journal le dit, une fois : `minecraft:strider … stays past its time: Bartok rides it (10 min more at most)`.
- **Au plafond** (`MAX_OVERTIME_MS`, 10 min après l'échéance d'origine), chaque cavalier est posé sur un sol sûr, puis la créature part (`retire`).
- **Sans sol sûr à portée** (un océan sous un nautile, le vide de l'End sous un happy ghast), la créature reste, et l'atterrissage est retenté toutes les 10 s (`LANDING_RETRY_MS`), avec un avertissement dans le journal. La vie du joueur passe avant le plafond.
- Rien n'est écrit sur la créature : le report se déduit à chaque seconde de l'échéance d'origine et de la présence d'un cavalier. Après un redémarrage, la règle s'applique de même. Un joueur qui se déconnecte en selle emporte sa monture avec lui (le jeu la sauve avec le joueur) : elle revient avec lui, et la règle reprend.

### L'atterrissage (`safeGround`, `land`)

- Le sol sûr est celui de `Teleports.landing` (lot A) : sol solide, ni liquide ni bloc dangereux, place pour le joueur, chunks chargés seulement. Il est cherché colonne par colonne, **de la plus proche à la plus lointaine**, dans un rayon de 8 blocs autour de la monture, en partant de sa hauteur.
- Le premier sol **au niveau de la monture** (à 3 blocs près) l'emporte : la rive du lac de lave que traverse un arpenteur, pas une grotte sous la lave. À défaut, le plus proche de tous : le sol sous un happy ghast en altitude.
- Le joueur y est téléporté (`Teleports.move` : descendu de sa monture, chute remise à zéro). Puis, pendant **30 s** (`LANDING_GUARD_SECONDS`, comme la décision du constat 1) :
  - la **garde est armée** (`armDeathGuard`) ;
  - **aucun dégât de chute** : `fall_damage_multiplier ×0` par l'`AttributeLedger` (`harmful = false`, sans tour). Une protection identique qui dure plus longtemps est gardée ; toute autre modification de cet attribut par le Narrateur est remplacée.
- Les garde-fous sont ceux du Narrateur en marche (`NarratorRuntime.current`, pour le même serveur) : `NarratorMobs` est construit par `GuardsImpl`, que ce lot ne touche pas.

### `retire` et les créatures qui explosent

`NarratorMobs.retire(entity, now)` est maintenant **le seul chemin** par lequel une créature du Narrateur quitte le monde : l'échéance et `Fuses.detonate`. Dans l'ordre :
1. les cavaliers sont posés au sol ; sans sol sûr, ils sont seulement descendus, sous la garde et sans dégât de chute ;
2. `release` : ce que les joueurs ont confié à la créature leur revient (T09c) ;
3. `discard()`.

`Fuses.detonate` appelait `discard()` sans `release()` : une selle donnée à une vache explosive disparaissait. C'est corrigé, comme l'a décidé le superviseur. **J'ai ajouté** la descente des cavaliers : une créature qui explose sous un joueur le laissait tomber, sans garde armée si l'explosion était inoffensive.

### Bogue trouvé en passant (T09c)

`release` lisait le propriétaire **après** `Belongings.takeFrom`, qui retire la laisse. Le propriétaire d'une créature non apprivoisée, celui qui tient sa laisse, n'était donc jamais trouvé : ses objets tombaient au sol au lieu de lui revenir. Le test de T09c ne le voyait pas, parce que son âne était apprivoisé. Corrigé : le propriétaire est lu d'abord. Le test de la vache explosive (cochon sellé, tenu en laisse) le couvre.

## Constat 5 : plus d'orage

- `WeatherKind` n'a plus que `clear` et `rain`. Le `thunder` du modèle est refusé avant l'outil : `Invalid arguments: weather must be one of: clear, rain (got "thunder")`. Rien ne change, et le délai de 10 min n'est pas consommé.
- Description de `set_weather` : `Changes the weather of the whole server for a while: clear or rain. There is no thunderstorm: for one, bring the rain and strike with the lightning tool, whose bolts start no fire. One change every 10 minutes.`
- `run_command weather` reste interdit (`CommandPolicy`, `Reason.TOOL`).

## Constat 6 : les créatures du Narrateur épargnent les blocs des joueurs

Les quatre mixins refusent l'action quand `CreatureShield.isNarratorsDoing` est vrai : une entité qui porte le tag `narrator2`, ou ce que l'une d'elles possède. Chaque cible a été vérifiée en `javap -c`.

| Mixin | Cible (26.3) | Effet |
|---|---|---|
| `FarmlandTrampleGuardMixin` | `@WrapWithCondition` (MixinExtras v2) sur l'appel à `turnToBaseBlock` dans `FarmlandBlock.fallOn` | la terre reste cultivée, sa culture aussi ; la chute et ses dégâts suivent leur cours. En 26.3, la classe s'appelle `FarmlandBlock` (plus `FarmBlock`). Un joueur sur une monture du Narrateur ne piétine pas non plus : c'est la monture qui atterrit. |
| `TurtleEggTrampleGuardMixin` | tête de `TurtleEggBlock.canDestroyEgg(ServerLevel, BlockPos, Entity)`, privée | `stepOn` (1 fois sur 100) et `fallOn` (1 fois sur 3) passent par `destroyEgg`, qui la consulte d'abord |
| `RemoveBlockGoalGuardMixin` | tête de `RemoveBlockGoal.canUse()` (champ `removerMob`) | le but ne démarre jamais. Seul `Zombie.ZombieAttackTurtleEggGoal` l'étend : il cherche les œufs à 24 blocs et les écrase. |
| `ProjectileBlockGuardMixin` | tête de `Projectile.mayInteract(ServerLevel, BlockPos)` et `mayBreak(ServerLevel, BlockPos)` ; fin de `Projectile.setOwner(Entity)` | voir ci-dessous |

### Les projectiles : ce que 26.3 fait déjà, et ce qui restait

Les blocs qui réagissent à un projectile demandent `mayInteract`, et `mayBreak` pour casser : feu de camp allumé, TNT amorcée, pot décoré et fleur de chorus cassés, dripstone cassé par un trident (`SpeleothemBlock`). Aucun projectile ne redéfinit ces deux méthodes.

**En 26.3, `mayBreak` refuse déjà un projectile dont le tireur est une créature** : il exige alors un prédicat de mode aventure (`canBreak`), jamais posé sur les leurs. La flèche d'un squelette vivant ne casse donc pas un pot, contrairement à ce que supposait T09b. Il restait deux trous réels :
- **`mayInteract`** (`owner == null || mobGriefing`) : la flèche enflammée d'un squelette du Narrateur (qui brûle au soleil) **allume un feu de camp** et **amorce la TNT** d'un joueur, bloc consommé ;
- **le tireur disparu** : une flèche ou un trident en vol dont le squelette ou le noyé expire entre-temps n'a plus de propriétaire (`getOwner()` rend `null` pour une entité retirée). Le jeu la traite alors comme sans tireur, et elle **casse pots et dripstone**. `isNarratorsDoing` ne la reconnaissait plus non plus.

D'où le troisième point d'injection, **ajouté** à la décision : à la fin de `setOwner(Entity)`, un projectile tiré par une créature du Narrateur **prend le tag `narrator2`**. Il reste celui du Narrateur toute sa vie, tireur disparu compris. Effets de bord voulus : `CreatureShield` épargne aussi les joueurs protégés d'une flèche dont le tireur a disparu, et `DeathGuard.attributed` attribue au Narrateur une mort par cette flèche (entité directe marquée). L'événement de chargement ignore ces projectiles : ils n'ont pas la pièce jointe `narrator2:mob`.

## Constat 9 (partie B) : hostilité et renforts

### Hostile = tout sauf une créature inoffensive

- `SpawnPolicy.HARMLESS`, liste blanche de 26 identifiants : `armadillo`, `bat`, `camel`, `chicken`, `cod`, `cow`, `donkey`, `frog`, `glow_squid`, `happy_ghast`, `horse`, `mooshroom`, `mule`, `parrot`, `pig`, `salmon`, `sheep`, `skeleton_horse`, `sniffer`, `squid`, `strider`, `tadpole`, `tropical_fish`, `turtle`, `wandering_trader`, `zombie_horse`.
- **Laissées dehors exprès** : ce qui rend les coups ou défend les siens (loups, abeilles, ours polaires, golems de fer, chèvres, lamas, dauphins, pandas, poissons-globes, nautiles), les chasseurs des animaux des joueurs (chats, ocelots, renards, axolotls), `camel_husk`, `sulfur_cube`, et tous les monstres. Une créature que personne n'a relue, nouvelle en 26.x ou d'un autre mod, est donc hostile par défaut.
- `spawn` est hostile dès qu'un membre du groupe (créature, monture, cavalier) n'est pas inoffensif, ou avec `target`. Le premier appel de ce genre ouvre un châtiment ; un autre tour est refusé (`already_punished`), et `fair_share` compte.
- Nouvelle description de `spawn` : `… Every creature that can hurt (monsters, but also wolves, bees, bears, golems, goats, llamas, pufferfish…), and any creature sent at the player, is a punishment of that player; only harmless animals are not. …`

### Les renforts des zombies

- `Zombie.handleAttributes` tire une base de `spawn_reinforcements` jusqu'à 0,1 et donne à un zombie chef un bonus de 0,5 à 0,75. En difficile, un zombie blessé appelle alors des zombies ordinaires, sans tag.
- `NarratorMobs.mark` met la **base à 0 et retire les modificateurs** de cet attribut : la valeur vaut 0, sauvée avec la créature. Cela vaut pour tous les zombies (noyés, zombies momifiés, zombies villageois, piglins zombifiés).
- Une **conversion** retire de nouveau la chance (`convertToZombieType` rappelle `handleAttributes`, avant `addFreshEntity`) : `NarratorMobs.inherit` (`MOB_CONVERSION`, qui passe après) remet la valeur à 0.

## Pour les autres lots

- **Lot A** :
  - `NarratorMobs` appelle `Teleports.landing(level, player, x, z, fromY)` et `Teleports.move(player, spot)`. En cas de changement de signature, adaptez `NarratorMobs.safeGround` et `land`.
  - `DeathGuard.attributed` : les projectiles tirés par les créatures du Narrateur portent maintenant le tag. `isNarrators(source.directEntity)` couvre donc déjà une flèche dont le tireur a disparu. Les vex restent à couvrir par `CreatureShield.isNarratorsDoing`, comme décidé.
- **Lot G** : la protection de chute de ce lot (`NarratorMobs.land`) et celle de la lévitation (constat 1, lot A) font la même chose. Elles peuvent être factorisées. `director.md` peut dire : pas d'orage, mais la pluie et `lightning` ; un loup, une abeille, un golem de fer sont des châtiments.
- **Superviseur** : `M1_M2.md` §9.4 (`set_weather : clear|rain|thunder`) et le tableau de `T16.md` citent encore `thunder`. Je n'y ai pas touché, ces fichiers ne sont pas à ce lot.
- **Tous les GameTests** :
  - le cadre GameTest entoure chaque test de barrières, toit compris (`GameTestInfo.placeStructure` → `TestInstanceBlockEntity.encaseStructure`). Un sol cherché au-dessus de la zone atterrit sur ce toit ;
  - `spawnWithNoFreeWill` retire **tous les buts** de la créature. Pour garder ses buts sans IA, créez-la et mettez `isNoAi = true`.
- **T22 / recette** : à voir avec un vrai client : un arpenteur sellé sur un lac de lave, puis un happy ghast en altitude, les deux au-delà de 10 min.

## Limites

1. **Pas de sol sûr à portée** : une monture chevauchée au-dessus du vide ou d'un océan reste tant qu'on la monte. La recherche (environ 200 colonnes) est refaite toutes les 10 s.
2. **Après une explosion sans sol sûr** : le cavalier est seulement descendu, sous la garde et sans dégât de chute. S'il tombe dans la lave, la mort est attribuée : inventaire gardé, ou mort empêchée au-delà du quota.
3. **Échéance affichée** : `activeOn`, et donc `<situation>` et `inspect_player`, donnent l'échéance d'origine d'une monture gardée sous son cavalier : une heure passée.
4. **Protection de chute** : elle remplace, pour 30 s, toute autre modification de `fall_damage_multiplier` par le Narrateur. Un châtiment « chutes ×3 » s'arrête donc plus tôt, et un bienfait « chutes ×0,5 » plus long se perd.
5. **Projectiles** : restent possibles ce que les blocs font sans demander `mayInteract` : une flèche enflammée **allume une bougie** (`AbstractCandleBlock`), une flèche actionne un bouton en bois ou une plaque de pression, fait sonner une cloche, émet un signal sur une cible, fait basculer une grande feuille de dripleaf. Rien n'est détruit. Un projectile chargé d'une sauvegarde d'avant ce correctif n'a pas le tag.
6. **Autres dégâts de créatures autorisées**, hors de la décision : un mouton du Narrateur mange encore l'herbe (`EatBlockGoal`, herbe → terre) ; ses chats, renards et ocelots, désormais hostiles, peuvent tuer les animaux d'un élevage.
7. **Météo** : `rain` règle, comme `/weather rain`, le minuteur d'orage sur la durée de la pluie. À la fin de la pluie, le jeu bascule son drapeau d'orage, et une pluie naturelle qui reprendrait peu après pourrait être orageuse. C'est le cycle naturel, pas un orage du Narrateur.

## Vérifications

- `gradlew build` passe **après fusion de `main`** (T22 et le lot CD compris) : **657 tests unitaires et 152 GameTests**, sans échec.
- Nouveaux GameTests :
  - `aMountStaysUnderItsRiderThenSetsThemDownOnTheShore` : un arpenteur du Narrateur sur un lac de lave de 2 × 2, un joueur en selle. Dû, puis dû depuis 9 min 59 s : il reste. À 10 min, le joueur est posé sur la rive (pierre, pas lave), sous la garde, sans dégât de chute, et l'arpenteur est parti ;
  - `aRiderHighUpIsSetDownOnTheGroundBelow` : un happy ghast du Narrateur à 20 blocs de hauteur. À 10 min, le cavalier est posé sur le premier sol sous lui (le toit de barrières du test), sous la garde, sans dégât de chute ;
  - `aCreatureOnItsFuseSetsDownItsRiderAndGivesBackWhatItWasGiven` : un cochon de `spawn` qui explose au bout d'une seconde, sellé par Bob, tenu en laisse et monté par lui. Bob est descendu, sous la garde, sans dégât de chute, et retrouve la selle et la laisse dans son inventaire ;
  - `theNarratorsCreaturesTrampleNoFarmland` : deux zombies adultes lâchés de 3 blocs dans des puits, sur une terre cultivée avec du blé. Celle du zombie ordinaire devient de la terre, celle du zombie du Narrateur reste, blé compris ;
  - `theNarratorsCreaturesCrackNoTurtleEgg` : 2 000 pas du zombie du Narrateur et 2 000 chutes de son squelette laissent l'œuf intact ; un zombie ordinaire le casse ;
  - `theNarratorsZombiesSeekNoTurtleEgg` : le but `ZombieAttackTurtleEggGoal` démarre pour un zombie ordinaire, jamais pour celui du Narrateur ;
  - `theNarratorsProjectilesBreakNoBlock` : la flèche enflammée d'un squelette du Narrateur n'allume pas le feu de camp. Une flèche et un trident dont le squelette et le noyé ont disparu en vol ne cassent ni le pot ni le dripstone. Les mêmes projectiles sans tireur allument et cassent les leurs ;
  - `everyCreatureThatCanHurtIsAPunishment` : un ours polaire ouvre un châtiment, des abeilles d'un autre tour sont refusées (`already_punished`), des vaches passent sans châtiment. Chaque identifiant de `HARMLESS` existe et peut être invoqué ;
  - `theNarratorsZombiesCallNoReinforcements` : les zombies de `spawn` ont une chance nulle (base 0, aucun modificateur), comme le noyé qui en naît par conversion alors que sa chance est retirée. Un zombie ordinaire en tire une.
- Test modifié : `weatherAndTimeChangeOnceInTheirCooldown` envoie le `{"weather":"thunder"}` du modèle par `ToolRunner` (refusé, météo inchangée), puis de la pluie (sans orage). Test unitaire ajouté : `there is no thunderstorm to set, only clear and rain`.
- **Contre-épreuves**, en trois passes, correctifs retirés puis remis (le build repasse ensuite) :
  - passe 1 (sans le report sous le cavalier, avec `thunder`, avec l'ancienne règle `MONSTER`, sans le mixin des terres cultivées, sans la remise à zéro des renforts dans `mark`) : **exactement 6 GameTests échouent**, les deux montures, la terre cultivée, la météo (`ok: thunder for 5 min`), l'ours polaire (`… summoned …` sans châtiment) et les renforts (base 0,038). Le test unitaire de l'orage échoue aussi ;
  - passe 2 (`discard()` dans `Fuses.detonate`, sans le mixin des œufs, sans la remise à zéro dans `inherit`, sans le tag posé par `setOwner`) : **exactement 4 échecs**, la créature explosive, l'œuf, le noyé converti (base 0,1 et bonus de chef) et les projectiles ;
  - passe 3 (sans le mixin de `RemoveBlockGoal`, sans les gardes `mayInteract` et `mayBreak`, avec l'ancien ordre de `release`) : **exactement 3 échecs**, le but des œufs, les projectiles et la selle (tombée au sol). Sans les gardes, le test des projectiles relève les trois dégâts : `the campfire is lit, the pot is broken, the dripstone is broken`.
- Le démarrage du serveur de test prouve que les quatre mixins trouvent leur cible (`defaultRequire: 1`).
- Pas de `runServer`.
