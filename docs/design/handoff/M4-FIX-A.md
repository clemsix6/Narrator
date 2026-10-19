# Passation M4-FIX-A : séquestre et paris (relecture de M4, constats 1, 4, 20, 28, 29, 36, 37 et 40)

Lot A des corrections de la relecture de M4 (`design/reviews/M4_REVIEW.md`, avec les décisions du superviseur sur
C36 et C37). Il corrige le **bloquant n° 1** : une pile dont le SNBT dépasse 65 535 octets était effacée du fichier
du joueur. **Interdit du propriétaire tenu : aucun objet n'est perdu, quel que soit le chemin** (mise, offrande,
confiscation, dû, gains d'une remise, `recover`). **En cas de contradiction avec `M4.md` (§3.2, §5.6) ou avec les
passations M4-15 et M4-20, le code et cette passation font foi.**

## Ce qui change, constat par constat

| Constat | Correctif | Tests (rouges avant, verts après) |
|---|---|---|
| **1** (bloquant) | Les piles en séquestre et l'état global n'ont plus de limite de longueur dans les fichiers (`core/LongString.kt`, ci-dessous). | `GuardStateCodecTest` › *a stack longer than an NBT string…* ; `EscrowGameTests.aFullBookAndAShulkerBoxOfBooksComeBackWholeThroughThePlayersFile` (le scénario de la relecture : livre et plume plein, 100 pages de 1 024 caractères, et boîte de Shulker de 27 livres écrits ; boucle `GuardState.CODEC` → `NbtIo.write` → `read` → `parse`, puis le vrai fichier par une reconnexion) |
| **4** | Une remise n'écrit plus que les fichiers `.dat` du gagnant puis du perdant, une fois chacun, jamais `saveAll()`. | `EscrowGameTests.aHandOverWritesTheTwoPlayersFilesAndNobodyElses` (fichiers relus sur le disque juste après ; celui d'un troisième joueur n'est pas écrit) |
| **20** (décodage) | Au décodage de l'état global, les mises d'un défi illisible et celles d'une instruction illisible sont rendues par une instruction `Settlement(owner, stake)`. | `MechanicsStateTest` › *the stakes of an unreadable challenge or settlement go back…* (et le test existant des entrées illisibles, adapté) |
| **28** | Le registre du gagnant garde les mises reçues (`GuardState.receivedStakes`) 30 jours : une remise rejouée après un crash ne paie plus deux fois. | `EscrowGameTests.aHandOverReplayedAfterTheWinnerWasPaidGivesNothingTwice` (le scénario de la relecture : 12 lingots, pas 24) ; `LedgerEscrowTest` › *winnings delivered before the loser's file lost the stake…* |
| **29** | L'état global passe par `LongString` ; `Settlement.at` daté, instruction abandonnée après `CEDED_RESCUE_DAYS`. | `MechanicsStateTest` › *a state longer than an NBT string…* ; `BetGameTests.anInstructionWhoseOwnerNeverComesBackIsDroppedAfterAMonth` |
| **36** (décision C36) | Un participant ne parie que sur lui-même, tant qu'il est en lice. | `BetSettlementTest` › *a participant bets on themselves only…* ; `BetGameTests.aParticipantBetsOnThemselvesOnly` |
| **37** (décision C37) | `/bet`, `/offer` et `/duel accept|decline` n'agissent qu'au nom du joueur qui envoie la commande lui-même. | `BetGameTests.onlyThePlayerThemselvesBetsOffersOrAnswersADuel` (console, puis une maîtresse du jeu, derrière `execute as` : rien n'est pris ; le joueur lui-même, derrière `execute as`, passe) |
| **40** | Joker caché refusé ; correcteur de données à la restitution ; trace juste d'une mise réglée après son échéance de secours. | `EscrowGameTests.aJokerHiddenInAShulkerBoxOrABundleIsNeitherStakedNorOffered`, `…aStackHeldAcrossAnUpdateOfTheGameComesBackUpToDate` ; `LedgerEscrowTest` › *a stake whose settlement comes after its fallback deadline…* |

Preuve du « rouge avant » :
- **Méthode** : chaque correctif sauf C37 a été retiré à la main, le temps d'un lancement, puis remis
  (`git checkout`).
- **Résultat** : six tests unitaires nouveaux, le test adapté des entrées illisibles et les sept GameTests nouveaux
  ci-dessus tombent, chacun sur sa propre assertion (`expected 12, got 24`, `its wear kept: expected 5, got 0`,
  `nobody else's file is written`, `not on his opponent`…).
- **C37** : le retrait d'un contrôle de sécurité n'a pas été lancé. Avant, les trois commandes prenaient
  `source.player`, l'entité de `execute as`, ce que la relecture a vérifié.

### Constat 1 : la vraie limite, et l'encodage choisi

- **La limite** (bytecode 26.3) : `NbtIo$StringFallbackDataOutput.writeUTF` attrape `UTFDataFormatException` et écrit
  `""`. `DataOutputStream.writeUTF` refuse plus de **65 535 octets en UTF-8 modifié**, soit 1 à 3 octets par
  caractère. Le chemin passe par `PlayerDataStorage.save` → `NbtIo.writeCompressed` pour un joueur, et par la
  sauvegarde des pièces jointes globales pour l'état des mécaniques.
- **`core/LongString.kt`** (nouveau) : un `Codec<String>` qui écrit un texte de 20 000 caractères au plus comme une
  chaîne, exactement comme avant (60 000 octets au pire), et un texte plus long comme une **liste de morceaux** de
  20 000 caractères. À la lecture, il accepte les deux formes.
  - Il sert à `Confiscation.items` (une entrée par pile) et à `MechanicsState.CODEC`.
  - Les fichiers déjà écrits se relisent tels quels : une chaîne courte reste une chaîne.
- **Écart avec la relecture**, qui proposait `CompoundTag.CODEC.listOf()` :
  - **aucune API ne change** : `Confiscation.items` reste du SNBT, alors que des tests de quatre autres lots
    construisent des `Confiscation` avec des chaînes ;
  - **aucun coût à la sauvegarde** : le SNBT n'est pas relu à chaque écriture du joueur ;
  - **un seul codec** pour les piles et pour l'état global (constat 29).
- La dernière limite qui reste est celle du jeu lui-même (le quota de lecture de `NbtAccounter`, en mégaoctets),
  la même que pour la pile tant qu'elle était dans l'inventaire.

### Constat 4 : deux fichiers, pas tous

- **Accesseur** : `mixin/guard/PlayerListAccessor` donne le champ privé `PlayerList.playerIo` (`PlayerDataStorage`).
  `ItemLedger.save(player)` appelle `PlayerDataStorage.save(player)`, public, qui écrit le seul `.dat` du joueur.
- **Pourquoi pas `PlayerList.save(player)`** (protégé, bytecode) : il écrit en plus les statistiques et les
  progrès. Or le registre est entièrement dans le `.dat` (pièce jointe `narrator2:guard_state`) : ces deux
  écritures ne serviraient à rien.
- **Ordre de la remise**, regroupée par perdant (`handOver`, puis `transfer`) :
  1. chaque gagnant reçoit ses gains, et la mise entre dans ses `receivedStakes` (`Escrow.receive`). Chaque gagnant
     qui a reçu quelque chose a son fichier écrit, une fois ;
  2. les mises sortent du registre du perdant, puis son fichier est écrit, une fois ;
  3. décisions `transferred`, `bet.lost`, livraison.
- **Garantie** : écritures synchrones, sur le thread serveur, dans le même tick. Aucune sauvegarde automatique ne
  peut s'intercaler, et le fichier du gagnant est sur le disque avant celui du perdant.
- **Mesure** (journal de `runGameTest`, ligne `a second of the engine took …`) :
  - avant : 456 à 1 094 ms par seconde du moteur où une mise est cédée ;
  - après : **9 à 21 ms**, partie `Bets`, soit un ou deux transferts à environ 5 ms par fichier sur cette machine.
  Plus aucune seconde gelée. Le seuil de 2 ms des secondes lentes reste dépassé lors d'une remise : c'est le coût
  d'écrire deux fichiers.
- **Non fait : regrouper les remises de toute une seconde** (deuxième volet de la proposition). `forfeit` remet la
  mise tout de suite (décision de M4-15), et `BetInstructions` applique les instructions une à une. Grouper au-delà
  d'un perdant obligerait à différer la remise ou à ajouter un mode, pour quelques millisecondes.

### Constat 28 : rien deux fois

- **Nouveau** : `GuardState.receivedStakes: List<ReceivedStake(stake, at)>`, clé `received_stakes`.
  - Une mise y est inscrite par `Escrow.receive`, à l'étape 1, et y reste `CEDED_RESCUE_DAYS` jours : aussi longtemps
    que le fichier du perdant peut encore contenir la mise.
  - Les entrées plus vieilles sont oubliées à la réception suivante.
- `Escrow.add` devient `Escrow.receive(state, winnings, now)`. Il rend null quand la mise est déjà dans le registre
  ou déjà reçue.
- Le codec de `GuardState` passe à 15 champs sur les 16 permis.
- **Cas restant** : un crash pile entre les deux écritures, **et** un perdant absent plus de 30 jours. Sa mise lui
  revient alors à l'échéance de secours, alors que le gagnant a été payé. L'objet est doublé, jamais perdu. Le
  fichier du perdant ne sait pas ce qu'a reçu le gagnant.

### Constat 29 : l'état global

- **Encodage** : `MechanicsState.CODEC = LongString.CODEC.xmap(decode, encode)`. Il n'y a plus de plafond, donc
  plus besoin d'alerte.
- **Écart** : la relecture proposait une chaîne par entrée ; les morceaux font mieux, sans toucher au décodage
  entrée par entrée.
- **`Settlement.at`** (nouveau, clé `at`, `Long?`) : la date d'écriture de l'instruction.
  - `Bets.settle` la date (`BetSettlement.settle(…, at)`).
  - Une instruction sans date (écrite par `Reconcile.drop` ou `BetBook.giveBack`, lot B, ou rendue au décodage)
    est datée par `BetInstructions` à la première seconde qui la voit.
  - Au-delà de `CEDED_RESCUE_DAYS` (30 jours) sans que son propriétaire revienne, elle est abandonnée : décision
    `mechanic settled` en `WARN`, avec `detail.dropped = true`. La mise est toujours dans le fichier du
    propriétaire et lui revient à son échéance de secours, à sa connexion.
- **KDoc** de `MechanicsState.returns` et `settlements` corrigées (constat 42) : elles ne parlent plus de joueurs
  « hors ligne ou morts ».

### Constat 20 : le décodage

- `MechanicsState.decode` lit les `bets` (`uuid`, `stake`) d'un défi illisible, et le `owner` et le `stake` d'une
  instruction illisible.
- Il en fait des `Settlement(owner, stake)` : la mise est **rendue**, même celle qui devait être cédée. C'est la
  voie sûre.
- Chaque instruction n'est ajoutée qu'une fois, et seulement si l'état n'en a pas déjà une pour cette mise. Une
  ligne `WARN` les liste.
- Un état entièrement illisible ne laisse rien à lire : ses mises reviennent à leur échéance de secours. Avec
  `LongString`, ce cas suppose maintenant un fichier corrompu.
- **Pas de balayage dans `Bets`** (l'autre option de la relecture) : le moteur du serveur des GameTests aurait
  rendu les mises des moteurs de test, dont le canon n'est pas dans son état.

### Constat 36 : un participant ne parie que sur lui-même

- `BetSettlement.options(participants, bettor)` et `pick(participants, typed, bettor)` prennent l'UUID du parieur.
  - Un participant, retardataire compris, n'a qu'un choix : lui-même, tant qu'il est en lice. Ni un autre
    participant, ni `nobody`.
  - Un participant déjà arrivé ou éliminé lit `bet.closed`.
  - Le refus d'un autre choix est `bet.unknown_option`, avec son seul nom (« Parie sur l'un de ces choix : Bob. »).
    Aucune clé de texte nouvelle.
- Les suggestions de `/bet` suivent la même règle.

### Constat 37 : la source réelle

- `mixin/guard/CommandSourceStackAccessor` donne le champ privé `CommandSourceStack.source`.
- `command/OwnPlayer.kt` (nouveau) : `ownPlayer(source, action)` rend le joueur seulement si
  `source.source === player.commandSource()`.
  - `ServerPlayer.commandSource()` est l'objet que `createCommandSourceStack()` met dans la pile, et que
    `execute as` ne change jamais.
  - Sinon, refus en rouge : `only a player can bet, for themselves: Bob did not send this command (execute as, a
    command block or the console).`
- Les boutons de l'invitation (`ClickEvent.RunCommand`) passent : c'est le joueur qui envoie la commande.
- Dans `DuelCommand.kt`, seule la ligne de la source a changé.

### Constat 40

- **Joker caché** : `ItemLedger.holdsJoker(stack)` cherche un joker dans tout composant `ContainerComponent` (boîte
  de Shulker, sac, imbriqués à toute profondeur).
  - `hold` refuse une telle pile, donc `/bet` et `/offer` aussi : `bet.no_item`, `offer.no_item`.
  - `isJoker` est inchangé : `Jokers.kt` (lot F) l'utilise pour reconnaître un joker joué.
- **Correcteur de données** : `Confiscation.dataVersion` (nouveau, clé `data_version`), version de données du jeu
  quand les piles ont été écrites.
  - Toutes les entrées nouvelles la portent : `confiscate`, `hold`, `owe`, `recover`, et les gains, qui reprennent
    celle de la mise.
  - À la restitution, une pile plus vieille passe par `DataFixer.update(References.ITEM_STACK, …)`.
  - Ce qui reste en séquestre est réécrit à la version courante.
  - Null (entrée écrite avant ce lot) : lue telle quelle.
- **Trace trompeuse** : `Confiscation.settledAt` (nouveau, clé `settled_at`) est posé par `release` et `forfeit`.
  - `Escrow.isFallback` ne devine plus : c'est une échéance de secours seulement si la mise n'a jamais été réglée,
    ou si elle a été cédée à un gagnant jamais revenu.
  - Une mise dont le règlement arrive après son échéance de secours (propriétaire absent une semaine) est
    maintenant « released » puis « returned », sans « fallback ».
- **Non fait : une commande d'opérateur pour rendre ou libérer une entrée** (dernier point du constat). Elle irait
  dans `command/GuardCommands.kt`, qui n'est pas au lot A. À reprendre dans le lot I ou plus tard.

## Formats changés (aucune migration : les anciens se relisent)

- `guard_state.confiscations[].items[]` : une chaîne, ou une liste de morceaux au-delà de 20 000 caractères.
- `guard_state.confiscations[]` : `data_version` (int) et `settled_at` (long), facultatifs.
- `guard_state.received_stakes` : `[{stake, at}]`, facultatif.
- `narrator2:mechanics` : une chaîne, ou une liste de morceaux. Dans le JSON, `settlements[].at`, facultatif.
- `M4.md` (§3.2 l. 306, §5.6 l. 871-876, qui cite `saveAll()`) n'est pas à moi : il est en retard sur ces points,
  et cette passation fait foi.

## Ce que les autres lots doivent savoir

### Lot H (moteur désactivé, gardien)

**Les instructions des paris sont extraites** dans `mechanics/challenge/Bets.kt` :

```kotlin
class BetInstructions(services: NarratorServices, store: StateStore, ledger: ItemLedger = services.guards.items, clock: Clock = services.clock) {
    fun applyOnline(server: MinecraftServer, now: Long)   // chaque tick (ou chaque seconde) : les propriétaires en ligne
    fun everySecond(server: MinecraftServer, now: Long)   // date les instructions sans date, abandonne celles de plus de 30 jours, puis applyOnline
    fun onJoin(player: ServerPlayer, now: Long)           // à la connexion, AVANT le JOIN des garde-fous
}
```

- **Besoins** : l'état du monde (`StateStore` sur `server.globalAttachments()`), le registre des garde-fous et une
  horloge. Ni base, ni moteur.
- **Remise au gagnant** : `BetInstructions` ne fait que `release` et `forfeit`. Le reste passe par
  `ItemLedger.giveBack`, que les garde-fous appellent chaque seconde même moteur désactivé : la remise dès que les
  deux joueurs sont en ligne, ce qui ne rentre pas, et l'échéance de secours.
- **`Bets`** délègue ses trois crochets (`everyTick`, `everySecond`, `onJoin`) à une instance de
  `BetInstructions` ; le gardien peut en créer une à lui.
- **Décisions** `mechanic settled`, comme avant ; `detail.dropped` pour une instruction abandonnée.

### Lot B (défis, `Reconcile`)

- `Settlement` a un champ `at: Long? = null`. `Reconcile.drop` et `BetBook.giveBack` peuvent le laisser nul : il
  est daté à la première seconde. Les comparaisons de tests du type `settlements == listOf(Settlement(…))` restent
  justes tant qu'aucune partie `Bets` ne tourne ; sinon, comparer `settlements.map { it.copy(at = null) }`.
- `BetSettlement.options` et `pick` ont un paramètre `bettor` (défaut nul : un spectateur).

### Lots C et F, et tout GameTest

- **Tests de commandes** : un test qui lance `/bet`, `/offer` ou `/duel` avec `createCommandSourceStack().withSource(output)` est
  maintenant **refusé**, car la source n'est plus le joueur. Il faut la pile du joueur lui-même et lire son chat :
  `DuelGameTests` le fait déjà (`player.live.createCommandSourceStack()`).
- **`ItemLedger.holdsJoker`** existe à côté de `isJoker`.
- `GuardState` a un champ de plus.
- Le lot F peut réutiliser `ownPlayer(source, action)` s'il ajoute une commande de joueur qui engage ses objets.

### Lot I (finition)

- KDoc corrigées ici (constat 42) : `MechanicsState.returns` et `settlements` ; `ItemLedger`, dont « a crash can
  only lose both or neither » redevient vrai ; `transfer` ; l'en-tête d'`Escrow`.
- Non touchés : `Bets.challengeRef`, dupliqué avec trois autres analyseurs, et la fenêtre des paris.

## Vérifications

- **Signatures** vérifiées avec `.research/jp.ps1`, une classe par appel, et `javap -c` pour les corps :
  - `PlayerList` : `playerIo` privé, `save(ServerPlayer)` protégé (corps : `.dat`, statistiques, progrès) ;
  - `PlayerDataStorage.save(Player)` public et synchrone (`NbtIo.writeCompressed`, puis `Util.safeReplaceFile`) ;
  - `NbtIo$StringFallbackDataOutput.writeUTF` (repli `""`) ;
  - `CommandSourceStack.source` privé, sans getter ;
  - `ServerPlayer.commandSource()`, et `createCommandSourceStack` qui le passe à la pile ;
  - `DataFixTypes` (pas de `ITEM_STACK`, d'où `References.ITEM_STACK`), `SharedConstants.getCurrentVersion()`,
    `WorldVersion.dataVersion()`, `DataVersion.version()`, `MinecraftServer.getFixerUpper()` ;
  - `ContainerComponent.itemCopies()`, `ItemContainerContents.fromItems`, `BundleContents.copyWithContents` ;
  - `WritableBookContent`, `WrittenBookContent`, `Filterable.passThrough` ;
  - `CompoundTag.getListOrEmpty`, `ListTag.getCompoundOrEmpty`, `AttachmentTarget.NBT_ATTACHMENT_KEY`,
    `LevelBasedPermissionSet.GAMEMASTER`.
- **Lancements ciblés** (`-Dfabric-api.gametest.filter=narrator2-gametest:escrow_game_tests_*`, puis `bet_*`) :
  16 et 11 GameTests verts.
- **`git merge main`** (lot E et conception de M5) : sans conflit.
- **Après la fusion, `.\gradlew.bat build` : vert.**
  - **1 498 tests unitaires**, 0 échec ;
  - **« All 314 required tests passed »**.
- Aucun appel réel à Claude ; pas de `runServer`.
