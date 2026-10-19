# Passation M4-FIX-D : règles (relecture de M4, constats 7, 8, 12, 16, 22, 30, 31, 33)

Lot D des corrections de la relecture de M4 (`design/reviews/M4_REVIEW.md`), ajusté par les « Décisions du
superviseur » (C8, C12, C31). **En cas de contradiction avec `M4.md` §8, ou avec les passations M4-14 et M4-18, le
code et cette passation font foi.**

Fichiers de code : `mechanics/rule/RuleEngine.kt`, `RuleBook.kt`, `RuleValidator.kt`, `RuleOnlyTools.kt`,
`mixin/policy/MobPickUpGuardMixin.java`. `RuleContext.kt` et `tool/mechanics/RuleTools.kt` n'avaient rien à changer.

Hors de ma liste (signalé) :
- `testing/MechanicsSamples.kt` : l'exemple `flying_cows` n'utilise plus `levitation` (désormais interdite), mais
  `glowing`, avec l'énoncé « les vaches frappées s'illuminent puis explosent en feu d'artifice ». La clé reste
  `flying_cows` pour ne pas toucher `MechanicsContractsTest`. `M4.md` §8.8 n'est pas mis à jour (design).
- `MechanicsCommandGameTests.kt`, une ligne : la décision du moteur attendue après `/narrator rule remove` est
  `rule_ended`, plus `removed`.

## Ce qui a été fait, constat par constat

### 7 — un effet différé d'une règle finie ne s'exécute plus

- Coroutine d'un déclenchement : avant chaque effet, après son délai, la règle doit être encore dans l'état
  (`store.snapshot`) et au travail (`live` : ni finissante, ni échue). Sinon l'effet est `skipped` (« C-60 ended
  before it »), sans appel d'outil, et le bilan n'est pas compté puisque la règle n'est plus dans l'état.
- `runMobFirings` : un déclenchement `mob_spawn` dont la règle est partie ou finit est abandonné.
- `left()` retire aussi les déclenchements de créatures en attente (`mobFirings.removeIf`) et le résumé en cours.
- Pas de `Job` par règle : annuler une coroutine au milieu d'un outil (une invocation en cours sur le thread
  serveur) laisserait un acte à moitié fait. On ne saute que ce qui n'a pas commencé.
- Tests : `RuleEngineTest` › *a delayed effect of a rule removed meanwhile never runs* (`notify` différé de 10 s,
  règle retirée par un opérateur : aucun `notified`, aucune `action`) ; GameTest
  `aHerdChangesInStepsAndALawRemovedChangesNoMore` (seconde moitié : une loi `mob_spawn` à 30 s de délai retirée,
  aucun creeper ne brille). Les deux sont rouges sans le correctif (vérifié en le retirant).

### 8 — une loi ne tue ni le bétail ni les villageois (décision C8)

- `mob_effect` refuse `levitation` (« it ends in a fall that kills ») et aussi **`jump_boost`** (« a higher jump is
  a fall that hurts ») : c'est l'équivalent en effet de `jump_strength`, et un saut IV ou V blesse à chaque retombée.
  Refus `DENIED (forbidden_effect)`, à la création comme à l'exécution (le `check` de l'outil).
- `apply_effect` dans une règle refuse aussi `levitation` (la décision dit « interdite aux règles », sans
  distinguer les joueurs) : `a rule never gives levitation: an effect that ends in a fall is yours to give…`.
- Bornes propres aux règles (`MobAttributeTool.bounds`) :

  | Attribut | Règle | Pourquoi |
  |---|---|---|
  | `scale` | 0,5 à 2 | une créature trop grande étouffe sous un plafond bas |
  | `max_health` | 1 à 5 | jamais de vie maximale réduite |
  | `jump_strength` | 0 à 1 | jamais un saut plus haut (une chute qui blesse) |
  | `gravity` | 1 à 2 | jamais plus léger (on retombe de plus haut) |
  | `movement_speed`, `attack_damage` | ceux de `spawn` | — |

  « Jamais de dégâts de chute provoqués » est tenu par les bornes plutôt que par un modificateur de
  `fall_damage_multiplier` : un saut jamais plus haut et une gravité jamais plus faible, même à plusieurs règles,
  ne font jamais tomber de plus haut. **Les facteurs de toutes les règles sur une même créature restent ensemble dans
  ces bornes** : deux lois `scale ×1,5` sur un creeper, la seconde répond `FAILED: … already has scale ×1.5 from
  other rules…` (donc `skipped`, voir 33).
- Les exclusions de `mob_spawn` (animal apprivoisé, créature nommée, créature du Narrateur) valent maintenant pour
  **tous les déclencheurs** : `mob_effect` et `mob_attribute` `at subject` sur une telle créature sont sautés
  (« the cow is tamed, named or the Narrator's own: a rule leaves it alone »). Les outils de lieu (une explosion à
  sa place) restent permis.
- Tests : `RuleValidationTest` › *a rule never kills the cattle nor the villagers* (vache et villageois, en
  `mob_spawn` et frappés : lévitation, saut, `scale` 4 et 0,25, `jump_strength` 3, `gravity` 0,1, `max_health` 0,5
  refusés au mot près ; lévitation refusée à `apply_effect` ; cinq lois dans les bornes acceptées) ; GameTests
  `theCreaturesToolsMarkTheCreatureAsTheRules` (lévitation `DENIED` sur une vraie vache, cumul de `scale` refusé)
  et `aCowHitGlowsThenBurstsWhereItIs` (une vache nommée frappée : son explosion part, l'effet est sauté, aucune
  marque). Rouges sans le correctif.

### 12 — jamais la victime d'un coup entre joueurs (décision C12)

- Validation : quand le sujet **peut** être un joueur (`damage_dealt` sans `when.entity`, ou `entity: player`),
  `at subject` n'est permis qu'aux outils qui parlent (`RuleTargets.WORDS` : `say`, `actionbar`, `show_title`,
  `play_sound`, `notify`). Message : `at subject may be a player hit by another for damage_dealt (no when.entity),
  and a rule never turns on the victim of a hit: aim at the actor, name a creature in when.entity, or only speak to
  the subject (…)`. `mob_effect`/`mob_attribute` `at subject` sans `when.entity` restent permis (ils ne visent que
  les créatures ; un joueur frappé les fait sauter).
- Exécution (défense en profondeur, pour un état d'avant) : un sujet joueur n'est visé que par un outil de
  `AUDIENCE` ou `NEWS`, sinon l'effet est sauté (« a rule never turns on the player hurt »).
- `teleport` avec `to_player` est interdit dans une règle (`RuleTargets.forbidden`, demandé par la validation et par
  le moteur ; à l'exécution c'est un échec `INVALID`) : `to_player is not allowed in a rule: a law never brings a
  player to another; give x and z, or random_radius`.
- Pour mémoire : `projectile_hit` a pour acteur le tireur quand un joueur tire (`ProjectileHit.player`), donc `at
  actor` y vise l'agresseur. `death` a pour acteur le mort ; `no_pile_on` le protège déjà.
- Tests : `RuleValidationTest` › *a rule never turns on a player hit by another* (`punish smite at subject`,
  `explosion at subject` avec `entity: player`, `teleport to_player` refusés ; `punish at actor`, `say at subject`,
  `explosion at subject` d'une vache acceptés). Rouges sans le correctif (la relecture les avait prouvés acceptés).

### 22 — horloges et stabilité des tests de règles

- `RuleBook(services, store, validator, clock = services.clock)` : l'échéance d'une règle est posée sur l'horloge
  donnée. En jeu c'est la même qu'avant ; le banc `RuleEngineGameTests.Rig` donne au livre l'horloge de son moteur.
  `MechanicsService` (lot H) n'a rien à changer (valeur par défaut).
- Explosions à l'écart : les trois GameTests qui explosent (squelette, vache, diamants) ont `@GameTest(padding =
  16)`. `padding` gonfle les bornes du test dans la grille (`TestInstanceBlockEntity.getTestBounds`, bytecode) : le
  test voisin commence au moins 21 blocs plus loin, au-delà des 8 blocs des spectateurs d'une explosion et des
  16 blocs de `ExplosionCoalescer`.
- Comité du Nether : les tronçons de la salle sont **forcés** (`ServerLevel.setChunkForced`) avant de la bâtir,
  attendus chargés (`getChunkNow`), puis relâchés dans le `finally`. Cause probable de « no free ground » : `spawn`
  ne cherche que dans des tronçons chargés, et ceux que `setBlockAndUpdate` avait chargés pouvaient être relâchés
  avant que le ticket du joueur arrivé les recharge. La salle n'a pas eu besoin d'être agrandie.
- Test : `RuleEngineTest` › *the book sets a rule's end on the engine's clock* (horloge du moteur une heure en
  retard : la loi de 5 min finit sur elle ; rouge si le livre lit `services.clock`).
- Les onze `RuleEngineGameTests` passent seuls ; voir « Vérifications » pour le `build`.

### 30 — la garde de ramassage

- `MobPickUpGuardMixin` ne teste plus le tag `narrator2` mais la pièce jointe `narrator2:mob`
  (`mob.hasAttached(NarratorMob.TYPE)`) : seules les créatures invoquées par le Narrateur ne ramassent rien. Une
  créature qu'une règle a changée ramasse comme les autres (piglins et or, villageois et pain).
- Test : GameTest `RuleGameTests.aCreatureARuleChangedStillPicksUpItems` (un zombie marqué par une règle ramasse
  l'épée ; rouge avec l'ancien mixin, vérifié). `GuardPolicyGameTests.theNarratorsCreaturesNeverPickUpItems`
  couvre toujours les créatures invoquées.

### 31 — les fins de règles (décision C31, contrat avec le lot G)

- **Une seule décision pour toute fin de règle : `mechanic rule_ended`** (`RuleBook.ENDED`), écrite par `RuleBook`
  (`finish`), sujet `C-60`, conversation `mechanics:C-60`, tour du demandeur s'il y en a un, et
  **`detail = {"ending": "expired" | "disabled" | "removed" | "fulfilled"}`** (`RuleBook.ENDING`, enum
  `RuleEnding`). Les anciennes décisions de fin des règles (`removed`, `expired`, `disabled`, `fulfilled`)
  n'existent plus ; `expired`, `won`, `lost`, `cancelled` ne viennent donc plus que des défis et des duels.
- Raison (une ligne qui tient seule, pour le Chroniqueur) :
  `C-60 law expired, canon retired: 12 fired, 3 skipped; « its time ran out »`,
  `C-60 law removed by operator Clement, canon retired: 0 fired; « Le Narrateur a levé la loi. »`,
  `C-61 plan removed by the director (turn 42), canon cancelled: …`, `C-61 plan fulfilled, canon fulfilled: …`,
  `C-60 law disabled, canon retired: 0 fired; « disabled after 5 failures in a row: … »`.
- `RuleBook.end(id, status, note, ending: RuleEnding)` remplace le dernier argument `decision: String`.
- **Retrait par un opérateur** (`/narrator rule remove`, et tout auteur qui n'est pas le Directeur) : un `RULE` non
  prioritaire part, `C-60 law ended: removed by operator Clement (12 fired, 3 skipped); « note »` (note échappée,
  ligne coupée à `RULE_LINE`). Un retrait par `remove_rule` ne prévient pas le Directeur (c'est lui).
- **Lot G (fusionné)** : `ExchangeLog.ENGINE_NEWS` lit `rule_ended` et montre `detail.ending` après le sujet ; la
  raison répète la fin, ce qui la laisse lisible seule ailleurs (`engine_decisions`, logs). **Lot I** :
  `ExchangeLog.RULE_ENDED` peut pointer vers `RuleBook.ENDED`.
- Tests : `RuleBookTest` (raisons, `detail.ending`, le `RULE` d'un retrait d'opérateur, rien pour un retrait du
  Directeur), `RuleEngineTest` (`expired` ×2, `fulfilled`, `disabled` dans `detail.ending`), GameTest
  `aRuleThatFailsFiveTimesInARowIsDisabled`.

### 33 — coût et équité (partie « règles »)

- **Les échecs passagers sont des `skipped`** : un outil qui répond `FAILED` (le monde ou l'instant : joueur mort,
  pas de sol libre, tronçon non chargé, créature sans l'attribut, cumul hors bornes) donne un effet `skipped`, qui
  ne compte jamais dans la série de cinq. Seuls `INVALID` (les arguments ne tiennent plus : le chaos a baissé, une
  téléportation hors de la bordure) et `UNKNOWN` (outil du monde qui n'a pas fini) restent des échecs, avec les
  erreurs propres du moteur. Choix assumé : `ToolStatus.FAILED` dit « pas la faute du modèle », une règle désactivée
  dit « refais-la corrigée ». Conséquence : une exception dans un outil (que `ToolRunner` rend `FAILED`) ne désactive
  plus une règle ; elle est loguée en `ERROR` et comptée `skipped`.
- **L'attribut vérifié à la création** : `mob_attribute` sur une créature nommée en `when.entity` (pas un tag) qui
  n'a pas l'attribut est refusé (`DefaultAttributes`) : `cow has no attack_damage; change another attribute, or name
  another creature in when.entity`. Avec un tag, une créature sans l'attribut est sautée au déclenchement.
- **Balayage étalé** : `runMobFirings` traite au plus `RuleEngine.MOB_FIRINGS_PER_TICK` = 50 créatures par tick
  (celles dont un effet est dû). La recherche des créatures du balayage reste en une tick (elle est bon marché).
- **Une décision résumée par minute** pour les créatures qui apparaissent : plus de décision `fired` (ni `skipped`,
  ni `failed`) par créature. Chaque règle `mob_spawn` écrit au plus une décision `fired` par minute, sujet `C-60`
  (DEBUG) : `C-60 since 20:31:05: fired on 12 creatures that appeared (9 creeper, 3 zombie), 2 skipped: cow:
  mob_attribute skipped: FAILED: …`. Le balayage de la création ou du démarrage garde sa décision unique (`C-60
  changes the 12 creatures already loaded: …`). Les compteurs de la règle (`fired`, `skipped`, `failures`) restent
  exacts, créature par créature. Les déclenchements `mob_spawn` n'ouvrent plus d'acte : la vérification sèche se
  fait avec un `Caller` du Directeur sur la conversation de la règle.
- Le reste du constat 33 (part de parole, `random_radius`, `glowing`) est au lot F.
- Tests : `RuleEngineTest` › *a firing the world could not do is skipped, and never disables the rule* (six
  explosions `FAILED` : aucune désactivation) ; `RuleValidationTest` › *a creature named has the attribute a rule
  changes* ; GameTests `aHerdChangesInStepsAndALawRemovedChangesNoMore` (60 creepers : 0, puis 50, puis 60) et
  `creepersUnderCaffeineAndBackToNormal` (le résumé). Rouges sans le correctif (vérifié pour l'étalement et les
  `FAILED`).
- Le test des cinq échecs : la `teleport to_player` absente (désormais interdite) est remplacée par une explosion
  trop forte pour un chaos abaissé (`RuleEngineTest`, `FakeGuards.chaos = 1`) et, en jeu, par une téléportation à
  `x 29999999` (au-delà de la bordure : `INVALID` seulement à l'exécution).

### 16, côté règles — un seul `punish` par cible (renvoyé au lot D par M4-FIX-F)

- Le lot F refuse un second `punish` d'un même acte sur un joueur (`already_punished`). Une règle à deux effets
  `punish` sur la même cible (`at` identique) est donc refusée dès la création : `effect 3 (punish): effect 1 already
  punishes the actor, and an act punishes a player once: compose it with the other tools`.
- Test : `RuleValidationTest` › *a rule never piles on a player*.

### Avec le lot F (fusionné avant mon dernier `build`)

- La part de parole des actes du moteur (4 répliques par minute pour toutes les règles) et `glowing` tenu pour
  nuisible par `apply_effect` ne touchent pas mes tests (`glowing` des vaches passe par `mob_effect`, pas par
  `apply_effect`).
- `Guards.vet` (contrôle pur) n'est pas utilisé ici : le moteur des règles n'a pas de pré-contrôle à faire de son côté.

## Ce que les lots suivants doivent savoir

### Lot H (moteur désactivé, assemblage, stabilité)

- **`unmark` est déjà utilisable sans moteur** : `RuleEngine.unmark(entity, ids)` et `RuleEngine.marksOn(entity)`
  sont des fonctions du `companion object`, sans état, thread serveur. Pour le gardien du moteur désactivé, à chaque
  `Mob` qui se charge : `RuleEngine.marksOn(entity).takeIf { it.isNotEmpty() }?.let { RuleEngine.unmark(entity, it) }`.
  Cela retire les tags `narrator2_rule_<id>`, les modificateurs `narrator2:rule/<id>/<attribut>`, ramène la vie au
  maximum, puis le tag `narrator2` s'il ne reste aucune marque et pas de pièce jointe `narrator2:mob`.
- Le ramassage n'a plus besoin du gardien : une créature marquée par une règle ramasse de nouveau (constat 30).
- Question 3 de la relecture confirmée par mes tests : le `RuleEngine` du serveur retire, au chargement, les marques
  des règles qu'il ne connaît pas, donc celles des moteurs de test. `aCreatureARuleChangedStillPicksUpItems` marque
  son zombie **après** l'avoir ajouté au monde pour cette raison.
- `RuleBook` a un paramètre `clock` (défaut `services.clock`) ; `MechanicsService` n'a rien à changer.
- `MechanicsAssemblyGameTests` : aucune attente sur les anciens noms de décisions des règles n'y figurait.

### Lot G (Directeur, mémoire)

- Ajouter `rule_ended` à `ENGINE_NEWS` (voir 31). Le `mechanic:expired` actuel ne verra plus que les défis.
- `director.md` peut dire, si la place le permet : jamais de lévitation dans une règle (ni `jump_boost` sur une
  créature) ; `mob_attribute` dans une règle : `scale` 0,5 à 2, `jump_strength` ≤ 1, `gravity` ≥ 1, `max_health` ≥ 1 ;
  `at subject` d'un `damage_dealt` qui peut toucher un joueur : la parole seulement ; pas de `teleport to_player`
  dans une règle ; un retrait par un opérateur réveille avec `RULE C-60 law ended: removed by operator …`. Les refus
  le disent de toute façon, avec la correction.

## Vérifications

- Chaque nouveau test comportemental a été vu rouge en retirant son correctif (contrôle `alive`, `FAILED` →
  `skipped`, horloge du livre, étalement, exclusions des créatures, abandon des déclenchements de créatures, ancien
  mixin), puis vert. Les tests de validation affirment des refus que l'ancien code acceptait (prouvé par la relecture
  pour la lévitation et `punish at subject`).
- GameTests lancés seuls : `rule_engine_game_tests_*` (11/11), `rule_game_tests_*` (4/4),
  `mechanics_command_game_tests_*` (2/2).
- Après `git merge main` (lot E, design de M5) : `build` à 1 496 tests unitaires verts, GameTests 307/308, seul
  échec `mechanics_assembly_game_tests_a_duel_of_the_servers_engine_kills_nobody` (« rescued, alive, put out:
  3.33 », l'instabilité connue du constat 22, lot H) ; relancés : « All 308 required tests passed ».
- Après un second `git merge main` (lot F) et l'ajout du constat 16 : **`build` vert, 1 500 tests unitaires,
  « All 315 required tests passed »**, puis `runGameTest` relancé : de nouveau 315/315.
- Après un troisième `git merge main` (lot G, qui lit déjà `rule_ended` et son `detail.ending`) : `build` à
  1 512 tests unitaires verts, GameTests 314/315 (de nouveau le seul duel de l'assemblage, même message) ;
  `runGameTest` relancé : **« All 315 required tests passed »**.
- Après un quatrième `git merge main` (lot C, duels) : **`build` vert du premier coup, 1 513 tests unitaires,
  « All 330 required tests passed »**.
- Signatures vérifiées avec `jp.ps1` : `TestInstanceBlockEntity.getTestBounds` (padding), `GameTest.padding`,
  `ServerLevel.setChunkForced`, `ServerChunkCache.getChunkNow`, `ChunkPos.containing`, `DefaultAttributes.getSupplier`
  / `hasSupplier`, `AttributeSupplier.hasAttribute`, `AttributeInstance.getModifiers`, `AttributeModifier.id` /
  `amount`, `Identifier.getNamespace` / `getPath`.
