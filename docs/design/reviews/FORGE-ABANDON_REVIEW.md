# Relecture adverse de FORGE-ABANDON : l'appel lâché tenu, puis repris

Relecture du 21/10/2026, sur `main` à `18f98c4` (fusion de FORGE-ABANDON ; diff relu : `git diff 18f98c4^1 18f98c4`).
Lus : `forge/host/ModuleThreads.kt`, `forge/host/ModuleHost.kt`, `forge/sdk/ActQueue.kt`, `forge/rt/Meter.java` et leurs
tests ; ce qui les appelle (`mechanics/module/ModuleEngine.kt`, `forge/host/SdkBridge.kt`, `forge/ForgeService.kt` pour le
banc et le blocage, `RetainedGauge.statics`, `BytecodeGuard` pour les moniteurs) ; `docs/ARCHITECTURE.md` (principe 3 de
la Forge) ; les passations FORGE-ABANDON, M5-05, M5-LOAD, M5-21, FLAKY-5 et `M5_REVIEW.md`.

Chaque constat donne sa gravité, l'endroit, un scénario, la preuve (**vérifié** : lu des deux côtés, ou montré par une
sonde ; **raisonné** sinon) et un **correctif proposé**. Le superviseur décide.

**Sondes.** Une classe de test jetable (`ForgeAbandonProbeTest`, paquet `mechanics.module`, sur `ModuleFixture` : le
serveur du banc d'essai dort `heldMillis` à chaque `dayTime` servi, ce qui lâche l'appel sans que le module calcule, comme
les tests de FORGE-ABANDON), lancée sur le code du correctif **puis sur le code d'avant** (les quatre fichiers de
production, `ModuleThreadsTest` et `HeldBackTest` remis à `18f98c4^1` le temps d'un passage). Tout est retiré ; l'arbre
est revenu à `18f98c4` (`git status` propre). Puis les tests du correctif ont tourné sur ma machine : `ModuleThreadsTest`
(11), `HeldBackTest` (8), `ActQueueTest` (17), `ModuleEngineTest` (20), `AbandonTest` (5) : 61 verts. Aucun appel réel à
Claude, pas de `runServer`, pas de build complet.

**Bilan : 8 constats — 0 bloquant, 1 important, 4 mineurs, 3 finitions.** Le cœur tient : un fil tenu ou coupé ne
touche plus jamais au monde, aucune course entre `cut`, la reprise et une requête en vol, aucun interblocage, la fin
demandée pendant une reprise passe avant tout autre appel, les actes ne sont ni perdus en silence, ni doublés, ni
réordonnés, et `cpuAtTrip` ne masque aucun calcul de plus de 40 ms. Le correctif **réduit** même le pire cas par tick
(voir la piste prioritaire). Le constat important est ailleurs : **le temps des reprises échappe à tous les comptes**
(budget du tick, `msPerSecond` affiché, lenteur), si bien qu'un appel tenu peut occuper le thread serveur à chaque tick
sans limite dans le temps, là où l'ancien modèle désactivait le module en cinq secondes.

## La piste prioritaire : `RecordHash held the tick 162 ms` (`EvilModulesGameTests`)

**Ce n'est pas la reprise.** `theDeepHashAttacksAreStoppedByTheGuardWithNoLostThread` ne mesure que `host.start(…)`
(`EvilModulesGameTests.kt:62-73`) : un **démarrage**, que FORGE-ABANDON ne tient ni ne reprend jamais (un démarrage lâché
est `stalled`, puis coupé). Le chemin de `run` pour un démarrage est inchangé, sauf la base de `computing` quand le
compteur s'est arrêté :

- **à chaud**, `RecordHash` est arrêté par `Guarded.deepLimit()` (« too large to hash »), un `ForgeLimit` direct qui ne
  passe pas par `Meter.trip` : `cpuAtTrip` reste `UNARMED`, `computing` est exactement celui d'avant ;
- **à froid**, le compteur s'arrête (« time budget », `cpuAtTrip` posé) ; si le fil est retenu par la machine pendant qu'il
  se dénoue au-delà de 55 ms, l'ancien code l'abandonnait (temps CPU depuis l'armement ≥ 50 ms : le test échoue sur
  « was abandoned »), le nouveau l'attend (le test échoue sur « held the tick ») : **même taux d'échec, autre message**.

La cause est celle que FLAKY-5 §8 a déjà décrite (`SetOfHash held the tick 98.98 ms`, et M6-13 : 55,2 ms) : depuis
M5-LOAD, un démarrage dont le fil n'a pas calculé est **attendu jusqu'à 2 s** (`STARTUP_PATIENCE_NANOS`) ; la borne murale
de 55 ms du test contredit cette patience sous charge. Constat 5.

**Le correctif peut-il tenir le thread serveur bien au-delà de 25 ms par tick ?** Par tick, **non, et moins qu'avant**
(vérifié, sondes B et C, avant/après) :

| Sonde (serveur : 30 ms par requête servie) | Avant FORGE-ABANDON | Après |
|---|---|---|
| B : deux faits du même module dans un tick | **62 ms** en un tick : le premier appel lâché, refusé, finit à l'instant ; le second court et est lâché à son tour (et ainsi de suite pour chaque fait du tick) | 30 ms par tick ; au pire **63 ms** une fois (un appel rejoué lâché, puis repris dans la foulée par `replay()` → `busy()`) |
| C : trois déploiements, chacun lâché à chaque tick | 94 ms par tick | 94 ms par tick |

Après le correctif, un déploiement coûte au plus, par tick, un appel lâché et une reprise (`work` : `busy()` puis rendre la
main ; `replay()` : s'arrêter au premier appel lâché ; la boucle des faits : les appels suivants sautés tant que l'appel
est tenu), plus le dépassement d'une requête en cours de service (préexistant). **Dans le temps, en revanche, oui** :
constat 1.

## Réponses aux questions de la mission

1. **Un fil perdu ou tenu peut-il encore agir sur le monde ?** Non (vérifié). Tout accès passe par `SdkBridge` →
   `toCaller` → `Worker.ask`, servi seulement par l'appelant qui attend l'appel (`attend`, états `RUNNING`/`RESUMED`) ;
   `cut`, la reprise et le service tournent tous sur le thread serveur, donc jamais en même temps. Les courses côté fil
   tiennent : `cut` avant que le fil pose sa requête (le fil relit `ABANDONED` dans sa boucle et lève), `resume` avant que
   le fil se gare (le permis de `unpark` le réveille), réveil parasite (la boucle relit l'état). Un module ne peut ni
   prendre un moniteur (`BytecodeGuard` refuse `synchronized`), ni interrompre un fil. Seul défaut voisin : le fil coupé
   au déchargement est **compté perdu** (constat 2).
2. **Le thread serveur bloqué ?** Une reprise attend au plus le budget + 5 ms (25 ms), seulement si le fil pose une
   requête ; une requête tenue qui ne revient jamais ne coûte rien au serveur (le fil est garé, sans délai, jusqu'à `cut`).
   Pas d'interblocage : aucun verrou commun (le fil n'attend que `request.done` ; le serveur n'attend le fil que dans
   `attend`, borné). Arrêt du serveur, `/narrator restart`, `forge off` : `retire` → `stop` sauté (l'appel est tenu) →
   `unload` → `cut`. Mais le temps des reprises n'est compté nulle part (constat 1), et trois fils tenus coupés d'un coup
   peuvent bloquer la Forge (constat 2).
3. **Comptes et limites.** Un appel tenu n'est pas un fil perdu (au plus un par déploiement ; garé, il ne coûte rien).
   La limite de 2 fils perdus tient, mais le déchargement y fait entrer des fils qui ne calculaient pas (constat 2). Un
   module ne gagne ni plus de 25 ms par tick (voir ci-dessus), ni plus de temps CPU : un seul armement par appel, donc les
   20 ms de son travail et la preuve CPU de 40 ms valent pour l'appel entier, reprises comprises. **Le temps de reprise,
   lui, est exploitable** : hors de `d.spent`, hors de la lenteur tant que l'appel ne finit pas (constat 1). Les 4 actes
   par appel ne tiennent pas (constat 3).
4. **Ordre et cohérence.** Une fin demandée pendant la reprise s'applique avant tout autre appel (vérifié : `work` et
   `replay` appellent `after(d)` dès que `busy()` rend vrai ; le test du correctif le couvre). Les actes du reste d'un
   appel attendent dans `late` d'une reprise à l'autre (aucune invocation entre elles : le déploiement est occupé), partent
   ensemble avant ceux de l'invocation ou de la livraison suivante (`sendLate`, fil unique) ; à la fermeture ils sont
   envoyés puis `dropped` et comptés. Ni perte silencieuse, ni doublon, ni inversion. Un appel repris lâché de nouveau
   reste tenu et est repris au tick suivant s'il demande ; si son déploiement finit entre-temps, il est coupé, la suite
   est refusée et ses actes en attente `dropped` : c'est voulu, mais son fil est compté perdu (constat 2).
5. **`Meter.cpuAtTrip`.** Ne masque pas une vraie faute (vérifié) : posé une seule fois par armement (après l'arrêt,
   `enter`, `tick` et `checkpoint` lèvent **avant** d'atteindre `trip`), non déplaçable par le code réécrit, rendu par
   `unnest` ; un fil qui calcule encore 40 ms après l'arrêt reste abandonné. Aucun chemin du JDK permis ne rattrape le
   `ForgeLimit` d'un rappel du module pour continuer un long calcul (relu : tris, tables, flux, `onClose` qui enchaîne
   les gestionnaires mais relance) : au pire 40 ms de CPU sont jugées depuis l'arrêt. Son effet sur un démarrage arrêté :
   la patience attend qu'il se dénoue au lieu de l'abandonner (voulu ; voir la piste prioritaire).
6. **Tests.** Les tests ajoutés sont déterministes (le lâcher est provoqué par un service lent, pas par la charge). Il
   manque : le compte des reprises dans le tick et la lenteur (constat 1), le déchargement d'un appel tenu garé sur sa
   requête (constat 2), les actes d'un appel repris après une invocation sautée (constat 3), un appel repris **lâché de
   nouveau** puis fini (au niveau de `ModuleThreads` et de l'hôte : `resume` → `ABANDONED` → `resume` → `DONE`), un appel
   tenu qui calcule au-delà de la preuve **pendant** une reprise (abandon au `settle` qui suit). La borne murale de
   `EvilModulesGameTests` est instable (constat 5).

## Constats

1. **IMPORTANT (disponibilité, budgets) : le temps des reprises n'est compté ni dans le budget du tick, ni dans le
   `msPerSecond` affiché, ni dans la lenteur tant que l'appel n'a pas fini ; un appel tenu peut occuper le thread
   serveur à chaque tick, sans faute ni avertissement, sans limite dans le temps.**
   - Où : `ModuleHost.resume()` (`forge/host/ModuleHost.kt:436-437`) ajoute le temps mural de la reprise à
     `spentThisSecond` (le `msPerSecond` **de l'hôte**, que personne ne lit : la vue `ModuleView.msPerSecond` est celle du
     moteur, `ModuleEngine.kt:941`, tirée de `d.spent`) et le temps servi à `Given.served`, qui n'entre dans
     `workThisSecond` qu'au règlement (`:463`). `ModuleEngine.Deployment.call` (`ModuleEngine.kt:344`) ne compte que
     `called.nanos` ; `busy()` ne rapporte rien : l'avertissement du tick (`:531`) et la vue ne voient pas les reprises.
   - Scénario : un module dont chaque appel fait trente requêtes du SDK servies lentement (une machine chargée, une pause,
     une lecture coûteuse). Avant : chaque appel était lâché à la première requête lente, la suite refusée, réglé au tick
     suivant avec son temps servi ; plus de 50 ms par seconde, cinq secondes de suite → **faute de lenteur, désactivé**.
     Après : l'appel est tenu et repris à **chaque tick** (une requête servie, lâché de nouveau, repris…) pendant trente
     ticks ; son temps servi n'est compté qu'à sa fin, d'un bloc, dans une seule seconde. Avec un appel par 1,5 s environ,
     au moins une seconde sur cinq ne compte aucun travail : la règle des cinq secondes de suite n'est **jamais** atteinte.
   - Preuve : **vérifié**, sonde A (module `m.timers().every(1, () -> { for (int i = 0; i < 30; i++)
     m.world().dayTime(); … })`, serveur à 30 ms par `dayTime`, 8 secondes de jeu, 5 ms réelles entre deux ticks) :

     | | Avant FORGE-ABANDON | Après |
     |---|---|---|
     | ticks où le thread serveur est tenu ≥ 25 ms | 100 sur 100, puis plus rien (module désactivé) | **155 sur 160** (≈ 31 ms chacun, 4,8 s de serveur en 8 s de jeu) |
     | `ModuleView.msPerSecond` affiché | 617, 621, 619, 620 | **32, 31, 0, 31, 30, 0, 31, 32** |
     | fautes | `slowness in second: … 5 seconds in a row (647 ms …)` à la 5e seconde | **aucune** ; 5 appels finis |

     Sonde E (même serveur, `while (true) m.world().dayTime();`) : seule la preuve CPU arrête un appel qui ne finit
     jamais, au bout de 3 s et 42 ticks tenus, sous le nom d'un abandon (« ran past 25 ms and was abandoned ») alors que sa
     boucle ne fait que demander (constat 4).
   - Gravité : **important**, non bloquant : par tick, le coût n'est pas plus grand qu'avant (voir la piste prioritaire),
     et il faut un service lent pour qu'une reprise soit lâchée de nouveau. Mais c'est exactement le cas d'une machine
     surchargée, où chaque déploiement tenu prend alors jusqu'à 25 ms à chaque tick (sonde C : 3 déploiements, 94 ms par
     tick), sans que l'opérateur le voie (`/narrator forge` affiche ~0-30 ms/s) ni que la règle de lenteur de M5-LOAD
     (règle 4 : « plus le temps que l'appelant a passé à servir ses requêtes ») joue. La passation (§3, « Le temps d'une
     reprise compte dans `msPerSecond` et le temps servi dans le travail de la seconde ») est inexacte sur ces deux points.
   - Correctif proposé : compter chaque reprise **là où elle a lieu**. Dans `ModuleHost.resume()`, ajouter
     `threads.served` à `workThisSecond` tout de suite (et ne plus l'ajouter à `Given.served`, qui garde le seul temps
     servi de l'appel d'origine, pour ne pas compter deux fois au règlement) ; exposer le temps mural de la dernière
     reprise (par exemple `ModuleHost.resumedNanos`, remis à zéro par `busy()`) et l'ajouter à `d.spent` dans
     `Deployment.busy()`. Corriger la KDoc de `ModuleHost.msPerSecond` (elle renvoie à `ModuleView.msPerSecond`, qui n'est
     pas elle). Test : la sonde A en test unitaire (`ModuleEngineTest`) : faute de lenteur à la 5e seconde, vue > 500 ms/s.
   - Fichiers touchés : `forge/host/ModuleHost.kt`, `mechanics/module/ModuleEngine.kt` ; `ModuleEngineTest`, `HeldBackTest`.

2. **MINEUR (fils perdus, disponibilité de la Forge) : décharger un module dont l'appel est tenu compte son fil comme
   perdu, alors qu'il est garé sur sa requête et se dénoue à l'instant ; trois d'un coup peuvent bloquer la Forge.**
   - Où : `ModuleHost.unload()` (`forge/host/ModuleHost.kt:379-385`) : `threads.cut(call.held)`, puis
     `if (call.held.thread.isAlive) { meter?.revoke(); threads.lose(…) }`. Un fil tenu est vivant par construction (garé
     sans délai) : juste après `cut`, il l'est toujours.
   - Scénario : fin d'un déploiement (expiration, `stop_module`, fin de partie, `forge off`, arrêt du serveur,
     `/narrator restart`) pendant qu'un de ses appels est tenu — le cas courant sous charge, où un appel tenu attend son
     tick de reprise. Chaque fin écrit `WARN a module thread was abandoned past its budget … (abandoned)` (faux : il n'a
     ni calculé ni dépassé). Avec trois déploiements tenus finis dans la même boucle (`halt`, `stopping`) et des fils que
     la machine tarde à faire tourner pour se dénouer, `LostThreads.add` en voit trois vivants : **la Forge est bloquée**
     jusqu'au redémarrage (`forge stuck`, ERROR, Forge indisponible). Avant le correctif, un fil lâché finissait seul dès
     sa requête suivante (refusée) : il n'était presque jamais vivant au déchargement.
   - Preuve : **vérifié**, sonde D (trois déploiements tenus, puis `engine.halt(…)`) : après le correctif, trois `WARN`
     « abandoned past its budget », un fil perdu à chaque fois ; avant, aucun. Le blocage lui-même est **raisonné** (dans
     la sonde, chaque fil coupé était mort avant le suivant : « 1 lost and still running »).
   - Correctif proposé : dans `unload()`, révoquer le compteur **avant** `cut` (le fil réveillé lève dès la première
     instruction du module, au lieu de pouvoir passer dans un gestionnaire pendant les quelques nanosecondes d'écart ; même
     ordre dans `abandoned()` et le cas `stalled`), et ne pas appeler `lose` quand l'appel était **garé sur sa requête**
     (`held.asking` lu avant `cut`) : sa requête refusée, il se dénoue aussitôt sans exécuter de code du module. Un fil
     tenu qui calcule ou que la machine retient hors d'une requête reste compté, comme avant. Test (`HeldBackTest`) : un
     hôte déchargé pendant que son appel tenu attend une requête → `threads.lost.alive() == 0`, aucun `WARN`.
   - Fichiers touchés : `forge/host/ModuleHost.kt` ; `HeldBackTest`.

3. **MINEUR (limites du SDK) : le reste d'un appel repris obtient quatre actes de plus dès qu'une invocation sautée a
   remis le compte à zéro.**
   - Où : `ActQueue.invocation` remet `inCall = 0` (`forge/sdk/ActQueue.kt:180`) **avant** de savoir si l'hôte a sauté
     l'appel ; `ask` compte les actes du reste d'un appel repris dans ce même `inCall` (`:197`, `:201`). Or, au tick où un
     appel est lâché, les faits, minuteurs et rappels suivants du même déploiement passent chacun par `invocation`, sautés
     par l'hôte (`ModuleEngine.work`, boucle des faits).
   - Scénario : un fait demande 4 actes, puis une lecture lente (lâché) ; un deuxième fait arrive dans le même tick (sauté,
     compte remis à 0) ; repris, l'appel demande 4 actes de plus : **8 actes dans un appel**, dans deux actes du moteur. La
     passation affirme le contraire (« Ils comptent dans les 4 actes de l'appel »). Pas d'escalade réelle (les plafonds de
     la minute tiennent, et deux appels donneraient autant), mais la règle « 4 acts a call » de `sdk.md` est fausse ici.
   - Preuve : **vérifié**, sonde F : `huds=8`, les quatre derniers `ok`.
   - Correctif proposé : `ModuleCall.run` rend si l'appel a couru (le moteur le sait : `called.skipped`) et `invocation`
     restaure `inCall` à sa valeur d'avant quand il a été sauté (un appel sauté ne demande rien). Test (`ActQueueTest`) :
     4 actes, appel lâché, invocation sautée, reprise : le 5e acte refusé `DENIED (module_acts)`.
   - Fichiers touchés : `forge/sdk/ActQueue.kt` (`ModuleCall`), `mechanics/module/ModuleEngine.kt` (`Deployment.call`) ;
     `ActQueueTest`.

4. **MINEUR (fausse faute possible) : la preuve CPU d'un appel tenu court sur toute sa vie, plusieurs ticks, et compte
   aussi l'attente active du fil dans `Worker.ask`.**
   - Où : `ModuleHost.computing` (`forge/host/ModuleHost.kt:487-495`) et `Meter.overTime` (`Meter.java:321-329`) comptent
     le CPU du fil depuis l'armement ; un appel repris n'est jamais réarmé. `Worker.ask` (`ModuleThreads.kt:372-379`) fait
     tourner le fil 200 µs puis le réveille par tranches de 100 µs tant que sa requête n'est pas servie (états `RUNNING` et
     `RESUMED`).
   - Scénario, deux effets : (a) l'argument de M5-LOAD (« 40 ms, trois pas de 15,6 ms de l'horloge CPU de Windows,
     qu'un appel de quelques millisecondes ne montre jamais ») suppose un appel **contigu** de 25 ms murales au plus ; un
     appel tenu s'étale sur plusieurs ticks, chaque reprise peut se voir imputer un pas entier, et trois pas imputés à
     10-15 ms de vrai calcul deviennent possibles (de l'ordre du pour cent, raisonné) : abandon, **désactivation
     immédiate**, fil perdu ; (b) un appel qui fait beaucoup de requêtes servies lentement accumule, d'une reprise à
     l'autre, le CPU de sa propre attente : la sonde E, qui ne calcule rien, est abandonnée au bout de 42 requêtes de
     30 ms (« ran past 25 ms and was abandoned »). Avant, la suite d'un appel lâché était refusée : rien ne s'accumulait.
   - Preuve : (b) **vérifié** (sonde E) ; (a) **raisonné**.
   - Correctif proposé (à trancher) : mesurer en recette les abandons d'un appel déjà repris (journal `resumed`, puis
     `abandoned`) ; au besoin, (b) faire attendre le fil sans tourner une fois son court tour passé (`park` : le service et
     `cut` le réveillent déjà) ou retrancher de la preuve le CPU passé dans `ask`, et (a) ajouter un pas de l'horloge CPU de
     tolérance par reprise, ou juger la preuve sur le CPU depuis le dernier lâcher.
   - Fichiers touchés : `forge/host/ModuleThreads.kt`, `forge/host/ModuleHost.kt` ; `HeldBackTest`.

5. **MINEUR (tests ; stabilité CI) : la borne murale de 55 ms de `EvilModulesGameTests` contredit la patience de 2 s
   d'un démarrage.**
   - Où : `gametest/…/EvilModulesGameTests.kt:62-73` (`held < budgetMs`, 55 ms, sans marge).
   - Scénario : sous la charge d'un build complet, le fil d'un démarrage est retenu par la machine ; l'hôte l'attend
     (M5-LOAD, voulu) ; le test échoue (`RecordHash held the tick 162 ms`, M6-12 ; `SetOfHash … 98.98 ms`, FLAKY-5 ;
     55,2 ms, M6-13). Sans lien avec FORGE-ABANDON (piste prioritaire).
   - Preuve : **vérifié** par lecture (le test, `ModuleHost.call` en démarrage, FLAKY-5 §8).
   - Correctif proposé : garder ce que le test protège (« bounded by the guard, not abandoned » : la cause, aucun fil
     perdu) et borner le **travail** du démarrage plutôt que le mur (`called.work`, comme `HeldBackTest`), ou ajouter
     `BusyMachine.SLACK_MILLIS` comme `theResidualScanIsStoppedByTheHost` le fait déjà.
   - Fichiers touchés : `src/gametest/…/EvilModulesGameTests.kt`.

6. **FINITION (cohérence) : un appel de jauge peut maintenant demander des actes, qui partent.**
   - Où : `ModuleEngine.gauge` → `ModuleHost.gauge` → `call(GAUGE, …)` hors de toute `invocation`
     (`ModuleEngine.kt:675`) ; `RetainedGauge.statics` lit les champs statiques par `Field.get`, qui initialise une classe
     du module pas encore initialisée : son `<clinit>` peut atteindre le contexte par un champ statique et demander un acte.
     Avant : `IllegalStateException` (« asked an act outside one of its calls »), une faute *exception* ; après : l'acte
     rejoint `late` et part à la prochaine invocation (plafonds de la minute compris). La passation dit que l'exception
     n'avait pas d'autre cause que l'appel tenu : inexact.
   - Preuve : **raisonné** (les deux côtés lus).
   - Correctif proposé : rien d'obligatoire (les garde-fous des actes s'appliquent) ; corriger la phrase de la passation,
     ou, si l'on veut l'ancien refus, n'accepter un acte hors invocation que pendant une reprise (un drapeau posé par
     `ModuleHost.resume`).

7. **FINITION (robustesse) : `cut` ne coupe qu'un appel `HELD`.**
   - Où : `ModuleThreads.cut` (`ModuleThreads.kt:143-145`). Si `attend` sortait par une exception pendant une reprise
     (une erreur de la JVM dans la boucle), l'appel resterait `RESUMED` : `cut` ne ferait rien, le fil attendrait en
     tournant par tranches de 100 µs, jamais refusé, et `asking` (qui exige `HELD`) ne le reprendrait plus.
   - Preuve : **raisonné**, improbable.
   - Correctif proposé : `cut` fait passer `HELD` **ou** `RESUMED` à `ABANDONED` (`getAndUpdate`).

8. **FINITION (documentation) : deux phrases de la passation sont fausses.** §3 « Le temps d'une reprise compte dans
   `msPerSecond` et le temps servi dans le travail de la seconde » (constat 1) ; §2 « Ils comptent dans les 4 actes de
   l'appel » (constat 3). À corriger avec les lots qui les règlent.

## Vérifié, sans constat

- **Le modèle des états** (`POSTED → RUNNING → HELD ⇄ RESUMED → DONE | ABANDONED`) : `runNext` passe `RUNNING`, `HELD`
  et `RESUMED` à `DONE` d'un seul geste et rend le fil d'un appel tenu (il finit avec lui) ; `resume` d'un appel fini rend
  `DONE`, d'un appel coupé `ABANDONED` ; `Held.asking` exige `HELD` et une requête posée ; le service se fait toujours en
  entier avant le test de l'échéance (une seule requête par reprise quand le service est lent, sonde A).
- **Le tour de la reprise** : `busy()` vrai après une reprise même finie, `after(d)` applique la fin ou la désactivation
  avant tout autre appel du module, dans `work` comme dans `replay`.
- **Le pire cas par tick s'améliore** (sondes B et C) : avant, chaque fait d'un tick pouvait être lâché tour à tour (le
  fil refusé finissait à l'instant, le règlement libérait le module) ; après, un déploiement prend au plus un appel lâché
  et une reprise par tick.
- **`STUCK` pendant une reprise**, **démarrage `stalled`** (coupé, révoqué, compté perdu au déchargement comme avant),
  **banc** (jamais repris, coupé au déchargement dans le `finally` de `ForgeService.bench`), **appels imbriqués** (refusés
  tant que l'appel qui les porte n'est pas attendu, `Call.attended()`).
- **Les objets du SDK servis un tick plus tard** (`Scheduler.after`, une créature partie entre-temps, `end` déjà demandé) :
  ils répondent comme à un appel ordinaire ; rien ne suppose un « appel en cours » hors de `ActQueue`.
- **`Meter.Nested`** porte `cpuAtTrip` et le rend : un arrêt dans un appel imbriqué du banc ne déplace pas la base de
  l'appel qui le porte.

## Lots de corrections proposés

Aucun fichier commun entre les deux lots.

| Lot | Constats | Fichiers | Critère |
|---|---|---|---|
| **FORGE-ABANDON-FIX — comptes des reprises** | 1, 2, 3, 7, 8 (et 4 si le superviseur le décide) | `forge/host/ModuleHost.kt`, `forge/host/ModuleThreads.kt`, `forge/sdk/ActQueue.kt`, `mechanics/module/ModuleEngine.kt` ; tests `ModuleEngineTest`, `HeldBackTest`, `ActQueueTest`, `ModuleThreadsTest` ; `docs/design/handoff/FORGE-ABANDON.md` (phrases fausses) | la sonde A devenue test : lenteur à la 5e seconde et vue > 500 ms/s ; un appel tenu garé déchargé n'est pas perdu ; 5e acte d'un appel repris refusé ; un appel repris lâché de nouveau puis fini (`ModuleThreadsTest`) ; build vert |
| **FLAKY-6 (ou avec le lot suivant de la recette) — borne de `EvilModulesGameTests`** | 5 | `src/gametest/…/EvilModulesGameTests.kt` | la borne porte sur le travail ou a la marge de `BusyMachine` ; « not abandoned » et « no lost thread » gardés ; vert sous charge |

Ordre conseillé : FORGE-ABANDON-FIX d'abord (le constat 1 retire un garde-fou de M5 sur une machine chargée) ; le lot de
la borne peut partir en parallèle. Décision à prendre : le constat 4 (mesurer d'abord, ou corriger tout de suite l'attente
active de `Worker.ask`, petite et sûre).

## Décisions du superviseur (21/10/2026)

- **Le lot FORGE-ABANDON-FIX est accepté en entier** : chaque reprise est comptée au moment où elle a lieu (budget du
  tick, `msPerSecond`, lenteur) ; un fil garé sur sa requête n'est pas compté comme perdu au déchargement, et le
  compteur est révoqué avant la coupure ; le compte d'actes survit à une invocation sautée ; un appel en cours de
  reprise peut être coupé ; les deux phrases fausses de la passation sont corrigées.
- **La fausse faute de la preuve CPU est corrigée tout de suite**, sans attendre la recette : une attente dans
  `Worker.ask` ne doit jamais compter comme du calcul du module. Désactiver un module honnête est pire qu'un léger
  surcoût de mesure.
- **`EvilModulesGameTests`** (borne murale de 55 ms contre la patience de 2 s d'un démarrage) revient à FLAKY-6.
