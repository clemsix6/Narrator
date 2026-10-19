# Passation M4-FIX-E : pièges (relecture de M4, constats 2 et 39)

Lot E des corrections de la relecture de M4 (`design/reviews/M4_REVIEW.md`). Il corrige le **bloquant n° 2** : à la
fin d'un piège, un verre ou une toile qu'un joueur avait reposé dans une case du piège était détruit sans rien
lâcher. Il traite aussi la partie « pièges » du constat 39. **En cas de contradiction avec `M4.md` §9.1 ou avec la
passation M4-05, le code et cette passation font foi.**

## Ce qui change

Un seul fichier de code : `guard/BlockRestorer.kt`. `mechanics/traps/TrapShapes.kt` n'avait rien à changer.

**Règle nouvelle : une case n'appartient au `BlockRestorer` que tant qu'elle garde le bloc posé, intact.** Le même
état ne le prouve pas, puisque le verre d'un joueur est identique à celui du piège. Une case est donc **oubliée pour
de bon** (son entrée part de la pièce jointe et de l'index, et on n'y touchera plus) dès que son bloc n'est plus
celui qui a été posé. Trois chemins mènent à cet oubli.

1. **Un joueur casse un bloc du piège** (`PlayerBlockBreakEvents.BEFORE`, nouveau crochet de `registerHooks`) :
   - si la case garde encore le bloc posé, le bloc part **sans rien lâcher** (constat 39). Le bris du jeu est
     annulé, puis refait par le crochet comme le fait le jeu, sauf les objets, l'usure de l'outil et les
     statistiques : `Block.playerWillDestroy` (particules pour les autres joueurs, événement de jeu
     `BLOCK_DESTROY`), puis `Level.removeBlock(pos, false)`. Quand `destroyBlock` rend faux, le jeu renvoie au
     joueur l'état de la case, ici de l'air (`ServerPlayerGameMode.destroyAndAck`, bytecode) : son client reste
     juste ;
   - si la case garde autre chose, l'entrée est oubliée et le bris suit son cours normal, objets compris ;
   - ligne `INFO` au tag `Guard` : `trap block minecraft:glass at x y z of Bartok broken by Mimosa: gone without a
     drop`.
2. **Tout autre changement** (un piston, une explosion, un remplacement) est vu **à la fin de chaque tick**
   (`forgetChanged`, dans `END_SERVER_TICK`, avant le passage de chaque seconde). Le bloc mobile d'un piston
   (`minecraft:moving_piston`) reste au moins jusqu'à la fin du tick où il apparaît : le verre d'un joueur poussé à
   la place d'une vitre est donc toujours vu après la vitre partie, jamais confondu avec elle. Cela produit une
   décision `trap skipped` avec la raison `before due`.
3. **Un bloc posé par un joueur** (`PlacedBlocks.placerAt` connaît un poseur) n'est jamais retiré, même identique.
   C'est le filet des changements faits dans le même tick, avant que le guet de la fin du tick les voie.

Ces trois tests sont réunis dans `stillSet(chunk, pos, entry)` : même état que le bloc posé, et aucun poseur dans
l'index. Le guet de chaque tick, le crochet du bris et le retrait (`takeAway`) s'en servent tous les trois.

### Messages de la décision `trap skipped` (constat 39)

Une case déjà vide n'est plus décrite comme un bloc « changé, laissé tel quel » :

- `1 block already gone (due)` : la case est de l'air ;
- `2 blocks changed since set, left as they are (before due): minecraft:moving_piston at x y z, minecraft:glass at
  x y z placed by Mimosa` : autre chose est dans la case, avec le poseur s'il est connu (cinq cases nommées au plus) ;
- `1 block already gone; 1 block changed since set, left as it is (lifted): …` : les deux à la fois.

Raisons : `due`, `lifted` et `taken away at once` comme avant, et `before due` pour le guet de chaque tick.

## Écarts par rapport au correctif proposé par la relecture

1. **`BEFORE` au lieu de `AFTER`.** La relecture proposait d'oublier la case dans `PlayerBlockBreakEvents.AFTER`.
   `BEFORE` fait la même chose, et c'est le seul moment où l'on peut encore supprimer les objets lâchés. Le
   constat 39 laissait le choix entre accepter les objets gratuits et les supprimer par `BEFORE`. **J'ai choisi de
   les supprimer.** Une toile n'est pas fabricable en survie : six toiles prises aux cisailles à chaque `punish
   web`, ou vingt-trois verres avec Toucher de soie à chaque cage, feraient du châtiment une ferme.
2. **Le guet de chaque tick a été ajouté.** Le correctif de la relecture (`AFTER` et `placerAt`) ne couvrait pas
   deux cas :
   - un piston pousse d'un coup le verre du joueur dans la case et la vitre une case plus loin ;
   - une explosion vide la case, puis un piston y pousse le verre d'un joueur.

   Dans les deux cas, le verre n'est marqué nulle part à sa nouvelle place, puisque l'index des blocs posés ne
   suit pas les pistons (M4-06). Le coût du guet : au plus 512 lectures d'état par tick, et seulement s'il y a des
   pièges en attente (`index.isEmpty()` d'abord).
3. **La passation M4-06 se trompait** en disant que la vérification « encore celui qu'on a posé » suffisait pour un
   bloc reposé par un joueur. `M4.md` §9.1 (puce « Retrait ») dit encore la même chose : c'est cette passation qui
   fait foi. Je n'ai pas modifié `M4.md`, qui n'appartient pas au lot E.

## Conséquences à connaître

- **Un bris de bloc de piège n'est plus un `BlockBroken`.** Le bris étant annulé (`PlayerBlockBreakEvents.CANCELED`),
  `AFTER` ne part pas, et l'observateur ne publie rien :
  - aucune ligne « mined glass » au Journal ni dans le résumé d'activité ;
  - aucun déclencheur `block_break` pour les règles ;
  - aucun progrès de défi.

  C'est voulu : casser le verre du Narrateur n'est pas miner. Les blocs du piège ne sont jamais dans l'index des
  blocs posés, donc aucun `grief` ne se perd. Un piège en partie cassé se lit à la fin dans `trap restored` (par
  exemple 22 au lieu de 23 blocs retirés).
- **`activeOn`, `byPlayer` et `pending` reculent dès qu'une case est oubliée** : une cage entièrement cassée
  n'apparaît plus dans `/narrator traps`. Le correctif de la relecture (`AFTER`) avait le même effet. `isPunished`
  (`GuardsImpl`) reste vrai tant que l'épisode du piège court (`episodes.active`), même si toute la cage est cassée :
  le garde-fou `already_punished` n'est pas affaibli.
- **Une case oubliée peut recevoir un nouveau piège** dès qu'elle redevient de l'air. Le refus `already holds a
  trap block` ne se voit plus que dans le tick même où la case s'est vidée.
- **Restes acceptés (constat 39, à documenter)** :
  - **une vitre poussée par un piston reste dans le monde pour toujours**, là où elle est allée. Le
    `BlockRestorer` ne connaît que les cases où il a posé. Le GameTest du piston le vérifie ;
  - une toile détruite par un piston (`PushReaction.DESTROY`) ou emportée par l'eau lâche encore une ficelle,
    selon les règles du jeu. Seul le bris par un joueur est neutralisé : c'est le seul chemin vers une toile entière.
- **Hors du lot E** : les deux autres puces « pièges » du constat 39 (un `punish` dont toutes les étapes échouent
  compte dans `fair_share`, et les jokers qui passent d'un joueur à l'autre) appartiennent au lot F
  (`mechanics/punish/*`, `mechanics/Jokers.kt`).

## Tests

Les cinq nouveaux GameTests de `TrapGameTests` **échouaient avant le correctif**. Je les ai lancés seuls sur le
code d'origine, avec le filtre `fabric-api.gametest.filter=narrator2-gametest:trap_game_tests_*` passé par
`JAVA_TOOL_OPTIONS`, sans toucher au build. Les 8 tests existants passaient, et les 5 nouveaux échouaient :

| GameTest | Scénario | Échec avant le correctif |
|---|---|---|
| `aGlassAPlayerPutsInPlaceOfABrokenPaneStays` (constat 2) | Bartok casse une vitre de sa cage avec une pioche Toucher de soie (`gameMode.destroyBlock`), Mimosa pose son verre à la place (`placeAt`, vraie pose marquée par l'observateur) | `her glass stays` |
| `aGlassAPlayerPlacedInPlaceOfAPaneWithinTheTickStays` (constat 2, `placerAt`) | vitre partie sans joueur, puis verre d'un joueur marqué dans `PlacedBlocks`, dans le même tick | `the player's glass stays` |
| `aGlassPushedInPlaceOfAPaneByAPistonStays` (constats 2 et 39) | un piston pousse le verre d'un joueur dans la case et la vitre une case plus loin ; à la fin, le verre du joueur reste, et la vitre poussée aussi, pour toujours | `the player's glass stays` |
| `aTrapBlockAPlayerBreaksDropsNothing` (constat 39) | verre à la pioche Toucher de soie, toile aux cisailles, toile à l'épée : rien ne tombe et les entrées sont oubliées ; une toile ordinaire aux cisailles lâche toujours une toile (témoin) | `nothing dropped: expected [], got [Glass, Cobweb, String]` |
| `aTrapBlockAlreadyGoneIsSaidGone` (constat 39) | vitre partie dans le tick, puis `restoreFor` ; décision relue dans `engine_decisions` | `expected [1 block already gone (lifted)], got [1 block changed since set, left as it is (lifted): minecraft:air at …]` |

Avec le correctif, les 13 tests de `TrapGameTests` passent. Les journaux montrent les trois chemins :
- `trap block … broken by …: gone without a drop` ;
- `trap skipped …: 1 block changed since set, left as it is (before due): minecraft:moving_piston at …` ;
- `…: minecraft:glass at … placed by …` et `1 block already gone (lifted)`.

Commentaires de deux tests existants mis à jour : `placeRefusesAllOrNothing` vide la case sans joueur, et
`aBlockChangedSinceItWasSetStays` change la case sans dire qui. Leurs assertions sont inchangées.

## Vérifications

- Signatures vérifiées avec `.research/jp.ps1`, une classe par appel :
  - `PlayerBlockBreakEvents` (`BEFORE`, `AFTER`, `CANCELED`), `$Before.beforeBlockBreak(Level, Player, BlockPos,
    BlockState, BlockEntity): boolean`, `$After` ;
  - `Level` (`destroyBlock`), `LevelWriter.removeBlock(BlockPos, boolean)`, `LevelAccessor.levelEvent`,
    `Block.getId`, `LevelEvent` ;
  - `ServerPlayerGameMode.destroyBlock(BlockPos)` ;
  - pour les tests : `GameTestHelper` (`placeAt`, `getEntities`, `killAllEntitiesOfClass`), `EntityTypes.ITEM`,
    `Enchantments.SILK_TOUCH`, `ItemStack.enchant`, `BlockStateProperties.FACING`.
- Bytecode lu avec javap :
  - le mixin de Fabric `ServerPlayerGameModeMixin` place `BEFORE` sur l'appel de `Block.playerWillDestroy` et
    `AFTER` sur `Block.destroy`, avant les objets lâchés ;
  - `ServerPlayerGameMode.destroyBlock` et `destroyAndAck` ;
  - `Block.playerWillDestroy` : particules sans le joueur, `GameEvent.BLOCK_DESTROY`, colère des piglins.
- `git merge main` : déjà à jour (`271a5e4`).
- `.\gradlew.bat build`, lancé deux fois :
  - **1er lancement** : 305 GameTests sur 306. Le seul échec est
    `mechanics_assembly_game_tests_a_duel_of_the_servers_engine_kills_nobody` (« rescued, alive, put out: 3.33 »),
    déjà connu sur `main` (relecture, constat 22, lot H). Il ne touche ni aux pièges ni aux bris de blocs ;
  - **2e lancement (`--continue`)** : **vert**, **1 490 tests unitaires** (0 échec) et **« All 306 required tests
    passed »**, dont les 13 de `TrapGameTests`.
- Aucun appel réel à Claude ni à Jev. Pas de `runServer`.

## Ce que les tâches suivantes doivent savoir

- **Lot I (finition)** : pas de KDoc à reprendre ici. `M4.md` §9.1 (puce « Retrait ») peut être mis à jour avec la
  règle ci-dessus si le superviseur le souhaite.
- **Lot F (`punish cage`/`web`, `isPunished`)** : `BlockRestorer.activeOn` ne compte plus que les blocs encore en
  place. Pour savoir si un joueur est puni jusqu'à l'échéance de son piège, même après une évasion, il faut
  regarder l'épisode, ce que fait déjà `isPunished`.
- **Tout code qui pose ou casse des blocs pour un joueur** (`FakePlayer` d'un autre mod compris) passe par ces
  crochets : un bris par un `FakePlayer` d'un bloc du piège ne lâche rien non plus.
