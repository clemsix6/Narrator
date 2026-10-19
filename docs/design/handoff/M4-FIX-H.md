# Passation M4-FIX-H : assemblage, moteur désactivé, stabilité

Lot H des corrections de la relecture de M4 (`design/reviews/M4_REVIEW.md`) : constats **9**, **22** (lot `assembly`,
`DirectorEndToEndTests`, cinq lancements verts), **23** et question 3 ; les branchements **C18** et **C19** (contrats
des lots C et F) ; le bouclier rendu persistant ; et, à la demande du superviseur après la fusion du lot B, les
**récompenses dues durables** (suite de C13) et **C24 côté démarrage**. Branche `worktree-agent-ae42a757a16a03949`.
**En cas de contradiction avec `M4.md` ou les passations M4-23, M4-FIX-B, M4-FIX-C et M4-FIX-F, le code et cette
passation font foi.**

## Ce qui change

| Fichier | Changement |
|---|---|
| `mechanics/MechanicsService.kt` | `EngineActions` ne reçoit que `TOOLS` (`engineTools`) ; `start` passe par `starting` : état remis tel quel si le démarrage échoue (C24), puis `guards.isDueling` branché ; `stop` le remet à `{ false }` s'il est toujours le sien |
| `mechanics/EngineGuardian.kt` (nouveau) | le gardien du moteur désactivé (C9) |
| `mechanics/Mechanics.kt` | `DisabledMechanics(reason, guardian = null, ui = null)` : `tick`, `onJoin`, `onMobLoad` vont au gardien |
| `mechanics/MechanicsModule.kt` | `createMechanics` donne un gardien sur l'état du monde à tout `DisabledMechanics` (configuration, Journal, mémoire, démarrage raté) |
| `mechanics/EngineActions.kt` | `names` (interne, pour les tests) ; KDoc |
| `mechanics/MechanicsState.kt` (**hors liste**, lot A fusionné) | `rewards: List<OwedReward>` et la classe `OwedReward` |
| `mechanics/challenge/Challenges.kt` (**hors liste**, lot B fusionné) | les récompenses dues passent de la mémoire à `MechanicsState.rewards` |
| `mechanics/challenge/Duels.kt` (**hors liste**, lot C fusionné) | C18 : bouclier après l'épargne ; KDoc |
| `guard/GuardState.kt`, `guard/Shields.kt`, `guard/GuardsImpl.kt`, `guard/DeathGuard.kt`, `guard/Guards.kt` (**hors liste**, lots A et F fusionnés) | le bouclier est sauvé avec le joueur (`GuardState.shield`) |
| tests unitaires | `MechanicsServiceTest` (+2, 1 complété), `EngineGuardianTest` (nouveau, 2), `GuardStateCodecTest` (+1), `MechanicsStateTest` (+1, 1 adapté) |
| GameTests | `MechanicsAssemblyGameTests` (réécrit, +1), `GuardFavorGameTests` (+1), `ChallengeStakesGameTests` (1 complété), `DirectorEndToEndTests` (robuste à l'ordre) |

`test_environment/assembly.json` n'a pas changé (voir « Écarts », point 6).

## 1. C18 et C19 branchés

- **C18** : dans `Duels.allowDeath`, juste après `spare(player)`,
  `services.guards.shield(player, services.clock.millis() + Guards.RESCUE_SHIELD_MS, "duel C-52: fatal blow stopped at 1 hp")`.
  L'horloge est celle des garde-fous (`services.clock`), pas celle du moteur : c'est elle qui compare `until`. Le
  sauvetage (`Guards.rescue`) pose déjà le sien.
- **C19** : `MechanicsService.start` pose `services.guards.isDueling = dueling` (une lambda gardée, `{ duels.isDueling(it) }`)
  une fois le moteur démarré ; `stop` remet `{ false }` **seulement si c'est encore la sienne** : un moteur de test qui
  s'arrête ne débranche pas celui du serveur.
- Preuve sur le vrai service, `MechanicsAssemblyGameTests.aDuelOfTheServersEngineKillsNobody` :
  - pendant le duel, `runtime.guards.isDueling` vaut vrai pour les deux, et `vet` refuse Bob avec la règle `duel` ;
  - après le coup fatal d'Ann (1 PV) : plus en duel, `GuardState.shield` posé avec la raison du duel et environ 5 min
    devant lui, `vet` refuse `no_pile_on` en citant la raison, `spares` vrai.

## 2. Le bouclier persistant

- `GuardState.shield: Shield?` (clé `shield`, `{at, until, reason}`), **16e et dernier champ** que prend un
  `RecordCodecBuilder` : le prochain champ de `GuardState` devra aller dans un enregistrement imbriqué.
- `Shields` n'a plus de table en mémoire : `shield(player, until, reason)` écrit dans la pièce jointe du joueur (un
  bouclier plus long est gardé), `active(player)` la lit (thread serveur, comme tous ses appelants : `verdict`,
  `spares`). Le contrôle de chaque seconde (`GuardsImpl.check`) oublie un bouclier échu (`tidy`). Il suit le joueur à
  la reconnexion, à la réapparition (`copyOnDeath`) et au redémarrage, comme `lastDeathAt`.
- Tests : `GuardStateCodecTest` › *a shield goes to the player's file and back…* (par le vrai écrivain NBT) ;
  `GuardFavorGameTests.aShieldHoldsAcrossARestart` (bouclier posé, reconnexion depuis le fichier, garde-fous neufs :
  refus `no_pile_on` avec la raison, `spares` vrai).

## 3. Constat 9 : le gardien du moteur désactivé

`EngineGuardian(services, store, ledger = services.guards.items, clock = services.clock)`, sans base, thread serveur.
`createMechanics` le construit sur `StateStore(server)` pour tout `DisabledMechanics` ; il **démarre à son premier
tick**.

| Quoi | Quand | Comment |
|---|---|---|
| duels | premier tick, puis chaque seconde | `EngineGuardian.release` (pure) : retours (`arena` → `returns`), paris rendus (`Settlement(owner, stake)`), `endsAt = now` ; le duel **reste dans l'état**, sans arène ni paris ; décision `mechanic released` |
| retours d'arène | chaque seconde | `DuelReturns.putBack` (lot C), joueurs en ligne |
| duellistes | à leur connexion | `duel.cancelled` en privé (une fois, en mémoire) |
| paris d'un défi | chaque seconde, **à son échéance** (`Reconcile.deadline` : la fin, ou celle du jugement) | `release` : mises rendues, le défi **reste** sans ses paris |
| instructions de paris | chaque tick, chaque seconde, à la connexion (avant le `JOIN` des garde-fous) | `BetInstructions` (lot A) |
| marques des règles | créatures chargées au premier tick, puis à chaque chargement | `RuleEngine.marksOn` / `unmark` (lot D) |

Ce qui attend le moteur : le canon, les règles de l'état, les défis eux-mêmes. Au démarrage suivant du moteur,
`Reconcile.startup` voit un duel ou un défi échu (`endsAt` passé) et applique ses règles (lot B) : annulé, ou gagné par
ceux qui avaient fini ; ses paris ne sont pas payés deux fois, il n'en a plus.

Tests : `EngineGuardianTest` (2 : un duel libéré, ses retours et ses paris ; les paris d'un défi à son échéance, pas
avant, jamais deux fois, et ceux d'un défi en jugement à la fin du jugement) ;
`MechanicsAssemblyGameTests.theGuardianOfAnEngineOffLeavesNothingFrozen` (un vrai `DisabledMechanics` avec son
gardien sur un état à lui : duel dans une arène avec le pari de Cid, défi qui finit 4 s plus tard avec le pari d'Ann,
instruction de Bob ; deux vaches marquées par une règle, l'une chargée avant, l'autre après ; Bob et Ann remis où ils
étaient, les trois mises rendues, celle d'Ann à l'échéance seulement, `duel.cancelled` dit à Bob, décision `released`).

## 4. Constat 23 : `EngineActions` ne reçoit que ses outils

- `MechanicsService.TOOLS` = `RuleTargets.ALLOWED` (avec les trois outils propres aux règles) + l'outil de chaque
  `RewardKind` (`give_item`, `apply_effect`, `favor`) + `punish`.
- `engineTools(services)` construit **seulement** les familles qui les portent (parole, actions sur les joueurs et le
  monde, `punish`, `favor`, outils des règles), filtre par nom, et **échoue** au démarrage si l'un manque (un outil
  renommé ou déplacé) : le moteur passe alors en `DisabledMechanics` avec la raison.
- `sql`, `run_command`, `delegate`… répondent `Invalid arguments: unknown tool "sql" for the engine`.
- **`SchemaDoc` n'est plus généré au démarrage** : l'outil `sql` n'est plus construit par le moteur. Le log ne montre
  plus le double `applied migration`. La description paresseuse de `sql` n'a **pas** été faite : `description` est un
  `val` du constructeur de `NarratorTool`, partagé par une cinquantaine d'outils ; ce n'est pas « simple », et ce n'est
  plus nécessaire.
- Test : `MechanicsServiceTest` › *the engine's acts reach the tools of the rules and the stakes, never sql…*.

## 5. Constat 22 : la stabilité

- **Nettoyage dans les `finally`** (`MechanicsAssemblyGameTests`) :
  - le défi : annulé s'il court encore (`ChallengeControl.end`, auteur `operator gametest`), et `lastChallengeAt`
    remis **tel qu'il était avant le test** ;
  - les duels : chacun annulé s'il court encore ;
  - la loi : retirée si elle est encore active.
- **Limite de deux** : le lot `assembly` tient au plus un défi et un duel à la fois sur le moteur du serveur (les duels
  se suivent) ; le cinquième test (le gardien) a son propre état.
- **Le duel de la lave** prend modèle sur `DuelGameTests.ServerTicks` (lot C) : un `ServerTicks` à lui laisse tourner
  le serveur ; à chaque tick, le monde fait tourner Bob (son temps de recharge des dégâts décroît), le moteur du serveur
  tourne, puis Bob fait son `doTick` comme son client le lui ferait faire. On attend la fin du duel (200 ticks au
  plus), puis Bob vit 140 ticks de serveur. Dans les journaux, le second duel commence moins d'une seconde après le coup
  fatal du premier, le cas qui échouait.
- **`DirectorEndToEndTests`** : le modèle joué (`ScriptedModel`) n'envoie son `sql` sur la salutation qu'une fois la
  salutation écrite au Journal (`journal.barrier()` dans la réponse du faux serveur). `sql` n'attend que 0,5 s les
  faits en file ; l'écrivain du Journal peut être tenu plus longtemps par un lot précédent, selon l'ordre des lots.
- **Question 3, troisième point** (le `RuleEngine` du serveur ôte au chargement les marques des moteurs de test) :
  inchangé ; le lot D marque ses créatures après les avoir ajoutées au monde. Le test du gardien vit dans le lot
  `assembly` pour la même raison : son premier tick balaie **toutes** les créatures chargées, ce qui ôterait les
  marques de `RuleGameTests.aCreatureARuleChangedStillPicksUpItems` s'ils tournaient ensemble dans le lot par défaut.

## 6. Demandes du superviseur après le lot B

### Les récompenses dues survivent à tous les redémarrages

- `MechanicsState.rewards: List<OwedReward(challenge, winner, at)>` (clé `rewards`), décodé entrée par entrée ; une
  entrée illisible est écartée seule (`Unreadable("reward", null, …)` : **sans id**, la réconciliation n'annule jamais
  un défi gagné).
- `Challenges.close` écrit la dette **dans la même mise à jour** que la sortie du défi de l'état (`challenge` sans ses
  paris). `payOwed` (chaque seconde) la retire de l'état au moment où le paiement commence, pour les gagnants revenus ;
  une dette de plus de `CEDED_RESCUE_DAYS` (30 jours) est abandonnée avec un `WARN`. `stopping` ne perd plus rien (une
  ligne `INFO` compte ce qui reste dû).
- Limite acceptée : un arrêt brutal entre le retrait de l'état et le paiement perd la récompense (jamais un objet du
  joueur : un cadeau du Narrateur).
- Tests : `MechanicsStateTest` › *a reward owed goes to the world's file and back…* ;
  `ChallengeStakesGameTests.aChallengeMadeBeforeAStopIsWonAfterItAndTheRewardPaidOnceBack`, complété : un **second
  arrêt** (état encodé puis décodé) avant le retour de Bob, un troisième moteur, puis Bob revient et reçoit son diamant.

### C24 côté démarrage

`MechanicsService.starting` garde l'état d'avant ; si le démarrage échoue après que `Reconcile.startup` a élagué
l'état, le moteur s'arrête et l'état **redevient celui d'avant** (`WARN the engine's start failed: its state is back
as it was in the world`), puis l'exception remonte (`createMechanics` → `DisabledMechanics`, avec son gardien). Le
démarrage suivant réconcilie normalement, sans la fausse note « lost when the world rolled back ». Les garde-fous ne
sont branchés qu'après un démarrage réussi. Test : `MechanicsServiceTest` › *a start that fails stops the engine and
puts the state back…* (bus fermé : l'abonnement échoue après l'élagage).

## Écarts et décisions

1. **Le gardien garde les entrées dans l'état** au lieu du `Reconcile.drop` proposé par la relecture : `drop` les
   ôterait, et au retour du moteur, la réconciliation résoudrait leur canon avec la note fausse « lost when the world
   rolled back ». Gardées sans paris ni arène (et un duel avec `endsAt = now`), elles sont closes par le moteur comme
   tout ce qui a fini pendant un arrêt.
2. **Il démarre à son premier tick**, pas dans `createMechanics` : `MechanicsCommandGameTests` construit un
   `DisabledMechanics` sur le serveur de test **pendant que le vrai moteur tourne** ; un gardien démarré là aurait
   libéré les duels du monde. Jamais branché aux crochets, il ne fait rien.
3. Le message `duel.cancelled` est en mémoire : un redémarrage avant la connexion du duelliste l'oublie (rien d'autre).
4. **`engineTools` liste les familles à la main** : la vérification au démarrage tient la liste à jour.
5. **`isDueling` n'est remis à `{ false }` que s'il est encore celui du service** (comparaison d'identité).
6. **Pas de `challengeGapMinutes = 0` dans `assembly.json`** : un environnement de test du jeu ne règle pas la
   configuration du mod. Le test du défi remet `lastChallengeAt` tel qu'il l'a trouvé : même effet, sans toucher aux
   autres.
7. Le joueur du test du défi passe en survie (`makeVulnerable`) : prêt pour le blocage du créatif proposé par le lot B
   (constat 26).

## Ce que les lots suivants doivent savoir

- **Lot I** :
  - `GuardState` est plein (16 champs) ;
  - `DisabledMechanics(reason, guardian, ui)` : le gardien passe avant `ui` (un appel `DisabledMechanics(r) { ui }`
    reste valide) ;
  - `EngineActions.names` est interne ; la copie du format de `mechanic action` dans `Stakes.giveItem` (lot B) peut
    maintenant viser `EngineActions` ;
  - sont en retard, et cette passation fait foi : M4-FIX-F (bouclier en mémoire), M4-FIX-B (dette en mémoire),
    M4-23 (`EngineActions` avec tout le catalogue, `SchemaDoc` au démarrage), `M4.md` §3.2 (l'état sans `rewards`).
- **Recette réelle** : avec le moteur coupé, le log dit `the engine's guardian runs while the mechanics are off: N
  duels released…` au premier tick.

## Vérifications

- Signatures vérifiées avec `.research/jp.ps1` : `AttributeInstance.getModifier(Identifier)`,
  `addPermanentModifier`.
- **Rouge avant le correctif**, vérifié en retirant chaque ligne le temps d'un lancement puis en la remettant
  (`git checkout`) :
  - sans le branchement de `isDueling` : `aDuelOfTheServersEngineKillsNobody` échoue (« the guards know both duel »),
    et `MechanicsServiceTest` aussi ;
  - sans le bouclier de `Duels.allowDeath` : le même GameTest échoue (« shielded 5 minutes, in his file: null ») ;
  - sans la remise de l'état dans `starting` : `MechanicsServiceTest` › *a start that fails…* échoue (C-50 sorti de
    l'état).
  - Le bouclier persistant, les récompenses durables et le gardien n'existaient pas : leurs tests ne pouvaient pas
    passer avant (un `GuardsImpl` neuf avait une table vide, la dette était perdue avec le moteur, rien ne tournait
    moteur coupé).
- `git merge main` (lot B) : avance rapide, la ligne du lot B dans `MechanicsAssemblyGameTests` gardée.
- **Tests unitaires** : 1 533, 0 échec.
- **Cinq lancements verts d'affilée** sur l'état fusionné (lot B compris), voir le tableau ci-dessous.

  Sur le commit `76e94f1`, un `.\gradlew.bat build` (passe 1 : 1 533 tests unitaires, 0 échec) puis quatre
  `runGameTest`, sans rien changer entre deux. Ordre des lots lu dans les journaux (`Running test environment …`) ;
  `default×7` est la suite des sept lots par défaut (50 tests chacun, 26 pour le dernier), `default(1)` le lot par
  défaut d'un seul test.

  | Passe | Résultat | Ordre des lots |
  |---|---|---|
  | 1 (`build`) | « All 349 required tests passed » | rules > default(1) > memory > mechanics > assembly > director > default×7 > mechanics_commands |
  | 2 | « All 349 required tests passed » | director > mechanics > default(1) > mechanics_commands > memory > rules > default×7 > assembly |
  | 3 | « All 349 required tests passed » | mechanics > memory > default×7 > mechanics_commands > default(1) > rules > assembly > director |
  | 4 | « All 349 required tests passed » | memory > default(1) > mechanics_commands > director > assembly > rules > mechanics > default×7 |
  | 5 | « All 349 required tests passed » | memory > mechanics_commands > default×7 > assembly > rules > mechanics > default(1) > director |

  Le lot `director` est passé premier, au milieu et dernier ; `assembly` avant et après les lots par défaut. Dans les
  cinq passes, le sauvetage de la lave du second duel d'assemblage tombe dans la même seconde que le coup fatal du
  premier (`shielded …` puis `rescued … duel C-…: lava`) : le cas qui échouait avant.

- Aucun appel réel à Claude ; pas de `runServer`.
