# Passation M4-FIX-B : défis

Lot B des corrections de la relecture de M4 (`docs/design/reviews/M4_REVIEW.md`) : constats **13, 14, 15, 24, 25
(défis), 26 (défis), 27 et 36 (récompenses par `owe`)**, selon les décisions du superviseur du 19/10/2026. Les duels
(lot C), les paris (lot A) et l'assemblage (lot H) ne sont pas touchés. **En cas de contradiction, le code fait foi**,
et ce fichier explique pourquoi.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `mechanics/challenge/Answers.kt` | `Answers.answers` (la réponse d'un quiz, `EXTRA_WORDS` = 3 mots de plus au plus) ; `matches` inchangé ; `Answers.ATTEMPT_GAP_MS` (5 s) ; classe `Attempts` |
| `mechanics/challenge/Goals.kt` | `Goals.afterDowntime(challenge)` (C13) ; `Goals.atStart(spec, prepared, everyone, online)` (C27) |
| `mechanics/challenge/Challenges.kt` | départ non annulable après le canon (`track`), participants recalculés à l'insertion, inscription sans spectateur ni créatif, filtre des morts apprivoisés ou nommés, tentatives de quiz, spectateurs sans progrès, canon résolu d'abord (`NonCancellable`), pas de « lost » pour un retardataire, cache des cibles nulles, reprise au premier tick (`resume`), récompenses dues (`owed`, `payOwed`) |
| `mechanics/challenge/Stakes.kt` | récompense d'objet par le registre (`giveItem`, `intoLedger`) |
| `mechanics/Reconcile.kt` | ce qui survit à l'arrêt reste dans l'état ; `Startup.told`, postés par `run` |
| tests | `AnswersTest` (+2), `GoalsTest` (+2), `ReconcileTest` (+1, 1 modifié), `StakesTest` (+1, 3 modifiés) ; GameTests `ChallengeGameTests` (+8, 1 modifié), `ChallengeStakesGameTests` (+2, 1 complété) |
| hors lot | `MechanicsAssemblyGameTests` (lot H, qui passe après) : une attente ajoutée, voir le constat 24 |

`ChallengeTools.kt` n'a pas eu à changer.

## Constat 13 : le redémarrage

Décision du superviseur appliquée telle quelle, par `Goals.afterDowntime` (pure), que `Reconcile.startup` et
`Challenges` partagent :

- **Défi jugé** (`judged`) dont la fin est passée pendant l'arrêt, **ou** déjà en `JUDGING` (que sa fenêtre soit
  passée ou non) : il reste dans l'état. Au **premier tick** du moteur, `Challenges.resume` le (re)met en `JUDGING`
  avec une fenêtre entière (`maintenant + judgeMinutes`), écrit la décision `judging` et renvoie `JUDGE` (prioritaire).
  Le `JUDGE` d'avant vivait dans l'`Inbox`, perdue avec l'arrêt : il est toujours renvoyé.
- **Autre objectif** dont au moins un participant avait fini (`doneAt`) avant l'arrêt : il reste dans l'état, et
  `resume` le clôt `won` par ces participants (dans l'ordre, `winners` au plus). **Aucun gage** pour les autres.
  Les paris sont réglés comme pour toute victoire (`BetBook.settle`).
- **Sinon** (personne n'avait fini, un `survive`, un duel) : annulé sans enjeux au démarrage, paris rendus, comme
  avant. Chaque annulation de démarrage produit en plus un événement **`CHALLENGE` (ou `DUEL`) non prioritaire**,
  gardé dans `Startup.told` et posté au début de `Reconcile.run` (le Directeur est créé après le moteur) :
  `C-50 cancelled: the server was down when it ended (10-19 18:15); no stakes; 1 bet given back`.

Pourquoi le **premier tick** et pas la première seconde : entre le démarrage et la première seconde, un fait du bus
(une réponse, une mise à mort) peut déclencher `decide`, qui aurait expiré un `JUDGING` passé ou clos le défi gagné
avec ses gages. Le premier tick suit `wire()` : le Directeur existe déjà.

Ligne de fin d'un défi gagné pendant l'arrêt :
`C-50 won by Mimosa in 9m12s (turtle_egg 1/1); the server was down when it ended: no penalty · reward: Mimosa paid once back online`.
Le canon est résolu `won` avec ce résumé.

### Les récompenses « à la connexion »

Au premier tick, personne n'est en ligne : `Stakes.pay` aurait dit `offline, not paid` à tous. La récompense d'un
gagnant hors ligne au moment de la clôture est donc **due** (`Challenges.owed`) : chaque seconde, `payOwed` paie ceux
que `PlayerStates` voit de nouveau en ligne, par le chemin normal (`Stakes.pay`, un acte du moteur, l'interaction de
l'historique). Seule une fin pendant l'arrêt crée une dette : une fin ordinaire paie toujours les seuls joueurs en
ligne (M4 §5.3).

**Limite connue, à trancher** : la dette est **en mémoire**. Un second arrêt avant le retour du gagnant la perd ;
`stopping` le dit dans le log (`WARN … reward was never paid`). La rendre durable demande un champ de
`MechanicsState` (par exemple `rewards: List<OwedReward(challenge, uuid, reward)>`), fichier du lot A pendant cette
vague. Je propose de l'ajouter dans le lot H ou I, une fois A fusionné : `close` y écrit la dette dans le même
`update`, `payOwed` la retire au paiement.

## Constat 14 : les quiz

- `Answers.answers` (nouveau, pour les quiz) : un message ne compte que s'il a au plus **trois mots de plus** que la
  réponse (`EXTRA_WORDS`), mots comptés après normalisation. « c'est une enclume » répond `enclume` ; « C'est une
  ENCLUME, évidemment ! » répond `une enclume` mais pas `enclume` seul ; une liste de propositions ne répond rien. Une
  phrase longue (« je crois bien que c'est une enclume ») ne compte plus : c'est le prix du correctif.
- **`Answers.matches` garde son sens** (les mots n'importe où dans le message) : les `words` du déclencheur `chat`
  d'une règle s'en servent (`RuleContext`, lot D). Une première version qui bornait `matches` lui-même cassait
  `RuleEngineTest` (« Il est nul, ce Narrateur » ne déclenchait plus la loi du mot « nul ») ; d'où deux fonctions.
- `Attempts` : un message public ou de prière d'un participant est une **tentative** si sa dernière tentative dans
  ce quiz date de 5 s ou plus (`ATTEMPT_GAP_MS`, horloge du moteur) ; les messages entre deux tentatives ne sont pas
  lus. Toute parole compte comme tentative, réponse ou non. Les tentatives d'un quiz sont oubliées à sa fin.

## Constat 15 : `kill`

Un fait `EntityKilled` ne compte que si `!pvp && !tamed && victim.name == null`, comme les exclusions de
`RuleEngine.spawnable`. Conséquence à connaître (prompt, lot G) : une créature nommée **par le Narrateur** (`spawn`
avec `name`) ne compte pas non plus ; un défi « tuer les gardiens » doit viser leur type, pas leur nom.

## Constat 24 : la fin

`conclude` suit maintenant cet ordre : **canon** (sous `NonCancellable`), **résultats** (`challenge_result`),
**enjeux**, décision `mechanic <fin>`, événement. Ni la résolution (note ou résumé) ni les résultats ne dépendent des
enjeux. La moitié `MechanicsService.start` du constat (état élagué avant un échec du démarrage) est au lot H.

Conséquence pour les tests : le canon fermé ne veut plus dire « enjeux payés ». Un test qui attend le canon puis lit
aussitôt une récompense dans l'historique doit attendre la décision de fin (ou l'événement), écrite en dernier.
`MechanicsAssemblyGameTests.aChallengeStartedByTheRealToolIsWonPaidAndItsBarTakenDown` (lot H) le faisait : j'y ai
ajouté **une seule ligne**, l'attente de la décision `won`, sans quoi il échouait une fois sur deux. Le lot H passe
après les autres et part de `main` : pas de conflit attendu.

## Constat 25 : le départ (défis)

Après `writeCanon`, `start` passe sous `NonCancellable` (`track`) : le délai de l'outil ou l'arrêt n'annulent plus
l'insertion. Toute exception de l'insertion (délai de `onServerThread`, serveur arrêté, erreur) résout la ligne
`cancelled` par `cancelRefused`, sauf si le défi est quand même dans l'état. Le côté duels est au lot C.

## Constat 26 : spectateurs et créatif (défis)

- **Inscription automatique** (défi ouvert à tous, au départ et en retardataire) : ni spectateur ni créatif
  (`Challenges.enrolls`).
- **Participant nommé en spectateur** : aucun progrès tant qu'il l'est (`detect` l'ignore, ses faits `kill` et
  `answer` sont jetés à l'application).
- **Écart : un participant nommé en créatif progresse encore.** Le serveur de GameTest est en créatif par défaut, et
  les bancs `BetGameTests` (lot A) et `MechanicsAssemblyGameTests` (lot H) font gagner des joueurs de test en créatif :
  bloquer le créatif les aurait cassés, et ces fichiers ne sont pas à moi. Une fois A et H fusionnés, deux lignes
  suffisent : passer leurs joueurs en survie (`makeVulnerable()` ou `setGameMode(SURVIVAL)`), puis remplacer
  `isSpectator` par `isSpectator || isCreative` dans `detect` et `applyFacts`. Le Directeur qui nomme un joueur en
  créatif le fait exprès ; le scénario de la relecture (un opérateur inscrit d'office) est, lui, fermé.

## Constat 27 : cycle de vie

- **Participants figés au `prepare`** : `insert` les recalcule (`Goals.atStart`) sur le thread serveur, au moment
  d'écrire l'état. Défi ouvert à tous : exactement les joueurs en ligne qui peuvent participer (qui est parti n'y est
  pas, qui est arrivé y est, **non** retardataire). Défi nommé : la liste ne change pas, mais un participant qui n'est
  plus en ligne est `out = left` d'un `survive`. Un défi ouvert sans plus personne est refusé (`nobody is online…`)
  et sa ligne du canon annulée.
- **Retardataires** : plus d'interaction `challenge_result` pour un retardataire qui n'a pas gagné.
- **Cible introuvable** : `target` garde aussi `null` (un `ERROR` une fois, plus à chaque seconde).

## Constat 36 : récompenses par le registre

Une récompense d'objet ne passe plus par le `run` de `give_item`, qui pose aux pieds ce qui ne tient pas. `Stakes`
l'exécute comme un acte du moteur, avec les mêmes contrôles et limites :

1. la décision `mechanic stake` de l'acte (inchangée) ;
2. les contrôles secs de `give_item` (`EngineActions.check`, avec le `Caller` de l'acte) : objet connu, permis, pas
   trop précieux ;
3. sur le thread serveur : `ItemSpecs.resolve`, les limites du serveur (`toolLimits.gifts.take` : 20 cadeaux par
   heure, points précieux), `ItemSpecs.build`, puis **`ItemLedger.owe`** : ce qui ne tient pas attend dans le
   registre (`OWED`) et arrive dès qu'il y a de la place, jamais au sol ;
4. une décision `mechanic action` au format d'`EngineActions` (`tool: give_item`, plus `ledger: true`), pour que
   « une décision `action` par acte » reste vrai (`checkActs`).

Réponse : `ok: gave 1x golden_apple to Mimosa`, suivi de `; it waits in their ledger until their inventory has room`
quand il le faut. Écart : c'est le seul enjeu qui n'est pas payé par le `run` de son outil. `EngineActions.action`
étant privé (fichier du lot H), son format est recopié dans `Stakes.giveItem` : le lot I peut l'exposer et retirer la
copie. Les récompenses `effect`, `grace` et `joker` passent toujours par leur outil (le joker passait déjà par le
registre).

Les autres récompenses `give_item` du Directeur (hors défis) tombent toujours aux pieds : le lot F en décide.

## Ce que les autres lots doivent savoir

- **Lot C (duels)** : `Challenges.finish` et `room` n'ont pas changé de signature. `close` ignore toujours les duels
  au redémarrage (`resume` saute `spec == null`) ; un duel échu pendant l'arrêt reste annulé par `Reconcile`, avec
  maintenant un `DUEL` non prioritaire.
- **Lot H** : `Startup` a un champ `told` (défaut vide), `Engine` n'a rien à changer. Un `GameTest` sur la vraie
  pièce jointe verrait les événements de démarrage dans le vrai Directeur.
- **Lot G** : nouvelles lignes `CHALLENGE`/`DUEL` du démarrage (`… cancelled: the server was down when it ended
  (…); no stakes …`) et de fin pendant l'arrêt (`…; the server was down when it ended: no penalty · reward: X paid
  once back online`). Le prompt peut dire que les quiz ne lisent qu'une tentative toutes les 5 s et qu'une liste de
  propositions ne gagne pas.
- **Lot I** : la copie du format de `mechanic action` dans `Stakes` ; la dette en mémoire (voir C13) ; le créatif
  nommé (voir C26).

## Vérifications

- Signatures vérifiées avec `.research/jp.ps1`, une classe par appel : `ServerPlayer.setGameMode`/`gameMode`,
  `Player.isSpectator`/`isCreative`, `TamableAnimal.tame`, `Entity.setCustomName`, `EntityGetter.getEntitiesOfClass`,
  `AABB.inflate`, `Inventory.setItem`/`getItem`, `ItemEntity.getItem` ; bytecode de `GameTestServer` : le monde de
  test est en **créatif** (d'où l'écart du constat 26).
- **Un test par constat, rouge avant le correctif.** Les nouveaux tests ont été lancés sur le code d'avant
  (`271a5e4`, dans un worktree jetable, avec seulement les nouveaux tests et les aides `Answers`/`Goals`) : les
  **11** GameTests nouveaux ou modifiés y échouent, et `StakesTest` › *an item reward passes give_item's checks…*
  aussi :

  | Constat | Test | Échec avant |
  |---|---|---|
  | 13 (jugé) | `ChallengeGameTests.judgedChallengesOverWhileTheServerWasDownAreJudgedAgainAtTheFirstTick` | `both kept by the startup: challenges=[]` |
  | 13 (gagné) | `ChallengeStakesGameTests.aChallengeMadeBeforeAStopIsWonAfterItAndTheRewardPaidOnceBack` | `kept by the startup` |
  | 13 (annulé) | `ReconcileTest` › *what ended while the server was down…* (`told`, événements non prioritaires) | ne compilait pas : pas d'événement |
  | 14 | `ChallengeGameTests.answerTakesThePublicChatAndPrayersButNeverAWhisper` ; `AnswersTest` (+2) | la seconde réponse de Bob comptait |
  | 15 | `ChallengeGameTests.killNeverCountsAnothersPetNorANamedCreature` | le chien d'Ann comptait |
  | 24 | `ChallengeGameTests.theCanonIsResolvedBeforeTheStakesArePaid` | `[ACTIVE]` au paiement |
  | 25 | `ChallengeGameTests.aStartCutShortOnceItsCanonIsWrittenStillStarts` | ligne ouverte sans état (délai dépassé) |
  | 26 | `ChallengeGameTests.aSpectatorMakesNoProgressAndNobodyInSpectatorOrCreativeJoinsLate` | le spectateur gagnait |
  | 27 | `ChallengeGameTests.aParticipantGoneAsTheChallengeStartsIsOutOfASurvive`, `aGoalThatCannotBeResolvedIsSaidOnce`, `ChallengeStakesGameTests.aLostChallengesForfeitSparesTheLatecomerAndTheOffline` | Ann « en lice » ; trois `ERROR` ; « lost » pour Dan |
  | 36 | `ChallengeStakesGameTests.anItemRewardForAFullInventoryWaitsInTheLedgerNeverOnTheGround`, `StakesTest` (+1) | la pomme au sol, rien dans le registre |

- `git merge main` trois fois (lot E et le design de M5 ; puis les lots A, C, F, G ; puis le lot D) : sans conflit.
  Le lot A a daté `Settlement` (`at` nul par défaut) : `Reconcile.drop` et `BetBook.giveBack` le laissent nul.
- `.\gradlew.bat build` sur l'état fusionné final : **vert**, **1 527 tests unitaires** (0 échec, 0 ignoré) et **« All
  347 required tests passed »**. Les lancements précédents ont révélé deux défauts corrigés depuis : `Answers.matches`
  borné cassait `RuleEngineTest`, et l'ordre canon puis enjeux rendait le test d'assemblage instable (voir plus haut).
  Deux lancements intermédiaires ont échoué sur des tests hors de ce lot, les instabilités connues du constat 22
  (lot H) : `rule_engine_game_tests_a_cow_hit_flies_then_bursts_where_it_is` (« timed out waiting for: the blast
  where the cow is ») et `mechanics_assembly_game_tests_a_duel_of_the_servers_engine_kills_nobody` (« rescued, alive,
  put out: 3.33 », le message exact de la relecture).
- Aucun appel réel à Claude ; pas de `runServer`.
