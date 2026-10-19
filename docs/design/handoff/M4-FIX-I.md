# Passation M4-FIX-I : finitions (relecture de M4, constats 38 et 42, restes des lots A à H)

Lot I, le dernier des corrections de la relecture de M4 (`design/reviews/M4_REVIEW.md`) : le texte `duel.lost`
(constat 38, décision C38), les KDoc fausses, le code mort et les duplications (constat 42), le créatif d'un
participant nommé (reste de C26), et ce que les passations M4-FIX-A à H laissaient au lot I. **En cas de
contradiction avec `M4.md` ou une passation plus ancienne, le code et cette passation font foi.**

## Ce qui change

| Fichier | Changement |
|---|---|
| `resources/narrator2/ui/fr.properties` | `duel.lost` réécrit (C38) |
| `memory/MemoryTypes.kt` | `Ref.canonOf` : la seule lecture d'une référence de défi, de duel ou de règle (`C-50`, `c50`, `50`) |
| `mechanics/MechanicsState.kt` | `settling`, `givingBack`, `puttingBack` : la seule construction des instructions et des retours ; `OutReason.DECLINED` retiré |
| `mechanics/challenge/Challenges.kt` | `betWindow`, `online`, `writeCanon`, `cancelRefused`, `replace`, `EMPTY_TITLE` partagés avec `Duels` ; `start` rend la ligne de départ ; `plays` (créatif) ; `resolve` sans nouvel essai sur une ligne déjà fermée |
| `mechanics/challenge/Duels.kt` | utilise les fonctions partagées de `Challenges` (40 lignes nettes de moins) |
| `mechanics/EngineActions.kt` | `runInstead` ; `cut` retiré au profit de `ModelText.cut` |
| `mechanics/challenge/Stakes.kt` | `giveItem` passe par `EngineActions.runInstead` (plus de copie du format de `mechanic action`) |
| `tool/mechanics/ChallengeTools.kt` | `start_challenge` répond avec la ligne de départ du moteur (plus de recalcul de la fenêtre des paris) |
| `command/DuelCommand.kt`, `MechanicsCommands.kt`, `BetCommand.kt`, `tool/mechanics/RuleTools.kt` | `Ref.canonOf` |
| `mechanics/Reconcile.kt`, `EngineGuardian.kt`, `challenge/Bets.kt` | `givingBack`, `puttingBack`, `settling` |
| `memory/chronicle/ExchangeLog.kt` | `RULE_ENDED` retiré : `RuleBook.ENDED` |
| `director/Inbox.kt`, `DirectorControl.kt` | KDoc de l'éviction juste ; branche `Mechanic -> SOON` retirée |
| `guard/Escrow.kt`, `guard/ItemLedger.kt`, `command/GuardCommands.kt` | `/narrator guards <joueur> release <id>` (`Escrow.returnNow`, `ItemLedger.returnNow`) ; `Escrow.forfeit` ne cède plus une mise déjà rendue |
| KDoc | `MechanicPart.onEvent`, `Challenges.onEvent`, `RuleEngine.onEvent`, `Engine.deliver`, `ChallengeView.line`, `TargetingConditionsGuardMixin`, `OutReason` |
| tests | `RefTest` (+1), `GoalsTest` (+1), `LedgerEscrowTest` (+1), `EngineActionsTest` (1 adapté), `ChallengeToolsTest` (1 adapté, 1 déplacé dans `GoalsTest`), `ExchangeLogTest`, `FakeMechanics` ; trois tests d'analyseurs retirés, dont `DuelCommandTest` (couverts par `RefTest`) ; GameTests `CommandGameTests` (+1), `ChallengeGameTests` (le créatif nommé), bancs de `ChallengeGameTests`, `ChallengeStakesGameTests` et `BetGameTests` en survie |
| docs | `M4.md` (encart des corrections, §3.2, §5.6, §8.8, §9.1, §18.1, §19), `handoff/M4-06.md` |

## Constat 38 : `duel.lost`

`Tu perds le duel. Ton orgueil ne s'en relèvera pas ; toi, si.`

- Le texte est lu dans trois cas : le coup fatal arrêté à 1 PV, le sauvetage hors de la lave ou du vide, et un duel
  terminé `won` par le Directeur ou un opérateur. La proposition de la relecture (« rattrapé avant le sol ») ne
  valait que pour une chute ; celle-ci vaut partout, raille le perdant et ne dit jamais qu'il aurait pu mourir.
- Les autres textes de duel (`duel.invite` « personne n'y meurt », `won`, `draw`, `cancelled`, `declined`,
  `expired`, `not_invited`) sont sobres et justes : inchangés.

## Constat 42 : une seule source pour chaque chose

### Duplications

| Quoi | Avant | Maintenant |
|---|---|---|
| Fenêtre des paris | `Challenges`, `Duels`, `ChallengeTools` | `Challenges.betWindow(config, minutes)`. `start_challenge` ne la recalcule plus : `Challenges.start` rend une vue dont `line` est la ligne de départ (`ChallengeLines.started`), et l'outil la répète |
| Référence `C-50`/`c50`/`50` | `DuelCommand`, `MechanicsCommands`, `Bets`, `ChallengeTools` ; `remove_rule` refusait `60` | `Ref.canonOf`, partout (`remove_rule` accepte donc `60`, comme `end_challenge`) |
| Canon, refus, remplacement, joueurs par nom | `Challenges` et `Duels` | `Challenges.writeCanon(draft, caller)`, `cancelRefused(ref, refusal, moment)`, `replace`, `Challenges.online(server, names)`, `EMPTY_TITLE` ; chacun garde son `draft` |
| Instructions et retours | `BetBook.giveBack`, `BetSettlement.settle`, `Challenges.close`, `Reconcile.drop`, `EngineGuardian.release`, `MechanicsState.decode` | `MechanicsState.settling` (jamais deux instructions pour une même mise), `givingBack`, `puttingBack` (jamais deux retours pour un joueur) |
| Décision `mechanic action` d'une récompense d'objet | recopiée dans `Stakes.giveItem` | `EngineActions.runInstead(firing, tool, args) { … }` : les contrôles secs de l'outil, puis le corps donné, puis la décision de `run` avec `detail.instead = true` (au lieu de `detail.ledger`, que personne ne lisait) |
| Couper un texte | `EngineActions.cut` (en `char`) et `ModelText.cut` (en points de code) | `ModelText.cut` seul ; les limites des lignes du moteur se comptent en points de code, comme le reste |
| `rule_ended` | `ExchangeLog.RULE_ENDED` et `RuleBook.ENDED` | `RuleBook.ENDED` |

Écarts :
- Le message « X, Y are not online » d'un duel sépare désormais les noms par une virgule, comme les défis et les
  outils (`ToolText.notOnline`), au lieu de « X and Y ».
- `Challenges.resolve` relit la ligne du canon quand la mémoire refuse la résolution : déjà fermée (un opérateur, la
  réconciliation), elle est laissée telle quelle avec une ligne `INFO`, sans le second essai qui finissait en `ERROR`.

### KDoc et code mort

- `MechanicPart.onEvent` et les trois parties qui l'implémentent disent la même chose : « en ligne sur le fil qui a
  publié le fait » (le thread serveur pour les faits du jeu), en ne lisant que l'instantané. `Engine.deliver` aussi.
- `ChallengeView.line` : la ligne que donne l'appel (départ, fin, ou situation).
- `Inbox` : l'ordre d'éviction réel (ce qui ne réveille pas, puis les événements `SOON` hors chat, puis le chat, puis
  le plus ancien du reste : résultat de tâche, rappel, échéance, nouvelle d'une mécanique, chuchotement), et personne
  n'est prévenu d'une éviction sauf le modèle. `DirectorControl.post` renvoie à `Inbox` au lieu de recopier l'ordre.
- `Item.urgency` : la branche `Mechanic -> SOON` (identique au `else`) est retirée.
- `OutReason.DECLINED` n'était écrit nulle part : retiré. `OutReason.LEFT` ne concerne qu'un `survive` (un duel finit
  au lieu de sortir quelqu'un). Un état ancien qui contiendrait `declined` perdrait ce défi au décodage (entrée
  illisible, mises rendues) : aucune version ne l'a jamais écrit.
- `TargetingConditionsGuardMixin` dit que les créatures invoquées ne visent que des joueurs (C17).
- Déjà corrigées par leurs lots : `MechanicsState.returns`/`settlements` et `ItemLedger` (A), `WakeScheduler` (G), la
  classe `Challenges` (B).

## C26 : un participant nommé en créatif ne progresse pas

- `Challenges.plays(player)` : ni spectateur ni créatif. `detect` (chaque seconde) et `applyFacts` (mises à mort et
  réponses) ignorent un participant qui ne joue pas ; l'inscription d'office (`enrolls`) passe par la même règle.
  Une mort compte toujours (un `survive` perdu reste perdu).
- Les bancs de `ChallengeGameTests`, `ChallengeStakesGameTests` et `BetGameTests` mettent leurs joueurs en survie
  (`setGameMode(SURVIVAL)`, sans `makeVulnerable` : ils ne prennent pas de dégâts tant que leur client n'est pas
  « chargé »). `MechanicsAssemblyGameTests` le faisait déjà (lot H).

## L'opérateur rend une entrée du registre (reste de C40)

`/narrator guards <joueur> release <id>` (`command/GuardCommands.kt`), joueur en ligne, id `812` (`O-812` pour une offrande ; `#` ne passe pas dans un mot de Brigadier)
(suggestions : ses entrées pas encore échues).

- `Escrow.returnNow` (pur) : l'entrée devient due tout de suite pour son propriétaire, quelle qu'elle soit : une
  confiscation avant son heure, une offrande non prise (le Directeur lit `OFFERING O-812 went back to …, not taken`),
  une mise, même cédée à un gagnant (elle est réglée par l'opérateur : `settledAt`, pas une échéance de secours). Null
  pour une entrée inconnue ou déjà due (un dû ou une restitution qui attend de la place).
- `ItemLedger.returnNow` l'applique puis rend aussitôt (`giveBack`) : ce qui ne rentre pas attend, comme toujours.
- Décision `operator ledger_release`, sujet le joueur, raison `Clement gave confiscation 812 of Bartok (3x
  minecraft:emerald) back now`, `detail` {`operator`, `entry`, `purpose`, `items`} ; la décision `ledger returned`
  suit.
- **Le pari reste dans son défi.** À la fin du défi, son instruction ne trouve plus rien (`release` comme `forfeit`
  répondent « nothing more to do »). Pour qu'une instruction de cession ne reprenne pas ce qui attend encore de la
  place, `Escrow.forfeit` refuse maintenant une mise déjà réglée pour son propriétaire (`settledAt` sans `owedTo`).
  Une cession corrigée vers un autre gagnant reste possible (test existant).
- Tests : `LedgerEscrowTest` › *an operator gives any entry back now…* ; GameTest
  `CommandGameTests.anOperatorGivesAnEntryOfTheLedgerBackNow` (refus d'un id inconnu sans rien rendre, puis trois
  émeraudes confisquées pour une heure rendues tout de suite, la réponse, la décision au Journal).

## Ce qui reste, hors de ce lot

- `apply_effect` : sa description ne dit pas que `glowing` compte comme une punition (lot F, budget des schémas).
- Passations en retard sur le code, signalées par H et toujours vraies : M4-FIX-F (bouclier en mémoire), M4-FIX-B
  (dette en mémoire), M4-23 (`EngineActions` avec tout le catalogue).
- Le décodage de l'état (`MechanicsState.decode`) garde sa propre lecture des mises d'une entrée illisible
  (`givenBack`) : elle lit du JSON brut, pas des `Bet`.

## Bilan en lignes (contre `main`, `95e1a4f`)

- Code (`src/main`) : +335, −310, soit **+25 net**. La commande d'opérateur ajoute 90 lignes nettes (`GuardCommands`,
  `Escrow`, `ItemLedger`) : les duplications, le code mort et les KDoc ont retiré **65 lignes nettes**.
- Tests (`src/test`, `src/gametest`) : +122, −78 (+44 net : quatre tests nouveaux, dont un GameTest, et trois tests
  d'analyseurs retirés).

## Vérifications

- Signatures vérifiées avec `.research/jp.ps1` : `Container.countItem(Item)`, `Inventory.add(ItemStack)`.
- **Rouge avant le correctif** :
  - C26 : `plays` remis à « pas spectateur » le temps d'un lancement filtré
    (`fabric-api.gametest.filter=narrator2-gametest:challenge_game_tests_a_spectator_or*`) : le GameTest échoue sur
    `named, in creative mode he makes no progress: won already`, puis passe une fois la règle remise ;
  - le GameTest de la commande a d'abord échoué pour une vraie raison : `#812` n'est pas un mot de Brigadier, la
    commande ne s'analysait pas. Seuls `812` et `O-812` sont acceptés ;
  - `Escrow.forfeit` d'avant cédait une mise déjà rendue : l'assertion ajoutée à `LedgerEscrowTest` l'aurait vu.
- Le GameTest de B `aSpectatorMakesNoProgressAndNobodyInSpectatorOrCreativeJoinsLate` s'appelle maintenant
  `aSpectatorOrACreativeMakesNoProgressAndNobodyInSpectatorOrCreativeJoinsLate` (le créatif nommé y est vérifié).
- `git merge main` : déjà à jour (`95e1a4f`), l'autre agent (recette M4-24) n'avait rien fusionné.
- `.\gradlew.bat build` sur l'état final : **vert**, **1 532 tests unitaires** (0 échec, 0 ignoré) et **« All 350
  required tests passed »** ; un lancement précédent, avant le correctif de `#812`, n'avait que ce test en échec.
- Aucun appel réel à Claude ; pas de `runServer`.
