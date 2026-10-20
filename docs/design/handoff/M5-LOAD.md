# Passation M5-LOAD : une faute n'est imputée à un module que si c'est son fait

Mission : sur une machine très chargée (processeur à 97–100 %), des modules sains étaient désactivés à tort, en jeu
comme au banc. Il fallait l'empêcher **sans affaiblir le bac à sable** : un module qui calcule trop est toujours arrêté et
compté, le tick du serveur reste protégé. Branche `worktree-agent-a25324e0c36e05802`, fusionnée avec `main` après M5-20 et
M5-21. **En cas de contradiction avec `M5.md` ou une passation plus ancienne (M5-04, M5-05, M5-10, FLAKY-4), le code et
cette passation font foi** (`M5.md` a un encart « Passation M5-LOAD » au §3.7, et ses §3.6, §3.8 et §19.1 sont corrigés).

## À lire d'abord (M5-21, M5-22) : ce qui change dans les signatures

| Où | Changement | Compatible ? |
|---|---|---|
| `ForgeService(…)` | **nouveau paramètre `onUnavailable: (reason: String) -> Unit = {}`**, en dernier : appelé **une fois**, avec la raison (`unavailableReason`), quand la Forge devient indisponible pendant le jeu (compilateur bloqué, trop de fils perdus, arsenal illisible au démarrage), **jamais** à l'arrêt du serveur (`stop`) | oui (défaut) ; M5-21 peut y brancher son message aux opérateurs et retirer le guet de `ForgeSetup.kt` |
| `ForgeService.build` | **une seule compilation par version** : deux appels qui manquent le cache ensemble (la vérification des versions forgées au démarrage et la reconstruction d'un déploiement par le moteur) partagent la même ; le second attend le premier, puis lit le cache (ou le refus `broken`) | oui |
| `ModuleHost(…)` | `patienceNanos: Long = 0` ajouté (avant `onFault`) ; **`gaugeNanos` retiré** (crochet de test de FLAKY-4 : la jauge n'a plus d'horloge) | oui pour les appels positionnels du jeu (`ModuleEngine`, `ForgeService`) ; un appel nommé `gaugeNanos =` ne compile plus |
| `ModuleHost` | nouveaux : `busy()`, `stalled`, `starved`, `skipped` ; `Called` gagne `work` et `skipped` (avec défauts) ; `SLOW_MEASURES` retiré | oui |
| `ModuleThreads.run(…)` | paramètres ajoutés avec défauts : `patience: Patience? = null`, `lose: Boolean = true` ; nouveaux : `served`, `lose(thread)`, classe `Patience` | oui |
| `RetainedGauge(loader, maxObjects, maxBytes)` | `maxNanos` remplacé par `maxBytes` ; `measure(roots)` ne lit plus les champs statiques : `statics(classes)` à part | interne à l'hôte |
| `HostCall` (`forge/sim`) | `work: Long = nanos` ajouté | oui |
| `forge/rt/Meter.java` (**hors liste**, voir « Décisions », 1) | `worked()`, `cpu()`, `cpuAtArm()`, `UNARMED`, `RUN_GAP_NANOS`, `MIN_CPU_EVIDENCE_NANOS`, `cpuEvidence(budget)` ajoutés ; le record `Nested` a plus de composants | oui (le record n'est construit que par le compteur) |
| `ModuleEngine` | `internal fun settle()` (pour les tests) ; `neutralNote(ending)` et `ENDED_ITSELF_NOTE` dans son compagnon | oui |
| `ForgeLimits.GAUGE_MAX_NANOS` | n'est plus lu (fichier hors de ma liste) : à retirer par son propriétaire | — |
| Priorités (comportement, sans signature) | le fil du compilateur (`narrator2-forge-compiler`) et les fils perdus (`ModuleThreads.lose`) passent de la priorité **minimale** à la priorité **normale** (voir « Les causes », dernier point) | oui |

## Les causes

Mesuré ici (Windows 11, JDK 25, 16 fils logiques) :

- **L'horloge CPU d'un fil avance par pas de 15,625 ms** (`ThreadMXBean`, `GetThreadTimes` sous Windows), échantillonnée
  à chaque interruption d'horloge : un appel de 1 ms lit 0 ms 298 fois sur 300 et 15,6 ms deux fois ; 3 ms : 15,6 ms une
  fois sur huit ; 20 ms : 15,6 ou 31,25 ms. Une lecture coûte **315 ns** (`System.nanoTime` : 20 ns ;
  `getCurrentThreadAllocatedBytes` : 12 ns). Le temps CPU ne peut donc pas juger seul un appel à l'échelle de 20 ms.
- Avant M5-LOAD, **toutes** les limites de temps lisaient l'horloge murale : le compteur (`Meter`, arrêt à 20 ms
  murales, et `disarm` qui marquait « time budget » tout appel fini après 20 ms), l'abandon à 25 ms (faute, désactivation
  immédiate, fil perdu), `LATE` compté comme faute *limite*, la *lenteur* (temps mural cumulé par seconde), l'horloge de
  5 ms de la jauge (trois mesures incomplètes désactivent) et l'appel de la jauge abandonné à 25 ms, l'abandon de `start`
  à 55 ms, le banc (« took 20,5 ms »), ECJ (10 s murales sur un fil à la priorité minimale). Sous charge, un fil retenu
  par la machine dépasse ces bornes sans avoir calculé.
- Le fil des modules tournait à la priorité normale, sous les fils de calcul ordinaires, alors que le thread serveur de
  Minecraft est à la priorité 8 dès qu'il y a plus de quatre cœurs (vérifié à javap : `MinecraftServer.spin`).
- **Un fil à la priorité minimale fige toute la JVM sous charge.** Chaque pause de la JVM (une collecte du G1, `G1Remark`,
  `G1Cleanup`…) attend d'abord que **chaque** fil qui exécute du Java atteigne un point d'arrêt (*safepoint*) ; une
  machine saturée affame un fil à la priorité minimale (Windows ne le relève qu'au bout de secondes). Mesuré sous charge
  (`-Xlog:safepoint`, `-XX:+SafepointTimeout`) dans le test « nothing heavy runs on the caller's thread » de
  `ForgeServiceTest` : « Reaching safepoint » de **2,2 à 2,6 s** à chaque collecte pendant les compilations, le fil
  fautif nommé par la JVM : `"narrator2-forge-compiler" prio=1 os_prio=-2` (et une fois un
  `ForkJoinPool.commonPool-worker` à `prio=1` : un fil hérite de la priorité du fil qui le crée, et le premier
  `CompletableFuture.delayedExecutor` du compilateur crée l'ordonnanceur du JDK et des fils du pool commun). L'appelant,
  pourtant à la priorité 8, était figé 2,57 s ; en jeu, ce serait le thread serveur. Dans le serveur de GameTest sous
  charge, la Forge mettait **67 à 75 s** à « construire » ses sept modules intégrés (0 ou 1 construit) et les deux
  GameTests d'`ArsenalCreaturesGameTests` expiraient (tick 10 000). Un fil perdu à la priorité minimale qui calcule
  ferait de même à chaque collecte, jusqu'à la fin de son appel du JDK (FLAKY-4 n'avait mesuré que la durée de la pause
  elle-même, 4 à 5 ms, pas l'attente du point d'arrêt).

## Les règles retenues

**Ce qui est une faute** (le fait du module) :

1. **Le compteur mesure le travail du module, pas l'horloge** (`Meter`). Tous les 16 `tick` (et à chaque `Meter.sample`
   d'un parcours profond de `Guarded`, et au `disarm`), il lit l'horloge ; l'écart depuis la lecture précédente compte
   dans `worked` **s'il ne dépasse pas 1 ms** (`RUN_GAP_NANOS`). Un écart plus long, c'est la machine qui a retenu le fil
   (un autre fil sur son cœur, une pause du GC) ou une étape longue du JDK : il n'est pas compté. L'appel est arrêté
   (`time budget at X.java:N`) dès que `worked` atteint son budget : une boucle du module est arrêtée à 20 ms de son
   travail, à 20 ms murales sur une machine calme comme avant, plus tard sur une machine chargée, jamais à tort.
2. **Une étape longue du JDK** (un `indexOf` O(n·m), un `sort`…) entre deux lectures n'avance pas `worked` : passé son
   budget mural, l'appel est arrêté quand le **temps CPU de son fil** atteint `cpuEvidence(budget)` = son budget, **40 ms
   au moins** (`MIN_CPU_EVIDENCE_NANOS`) : deux budgets d'appel, car l'horloge CPU peut lire un ou deux pas de 15,6 ms de
   plus qu'un appel court n'a tourné ; jamais pour un appel de quelques millisecondes. Lu une fois par milliseconde au
   plus (315 ns). Sans horloge CPU (JVM sans `java.management`), l'horloge murale, comme avant.
3. **Un appel lâché** (l'appelant ne l'attend jamais plus que son budget + 5 ms : le filet du tick, inchangé) **n'est
   plus jugé à l'instant** : il finit **seul** sur son fil (le SDK le refuse : toute requête lève `ForgeLimit`), le module
   **attend** (`busy()` : ses appels suivants sont **sautés**, `Called.skipped`, et le moteur les **rejoue** au tick
   suivant), puis l'hôte le **règle** (`settle`, à chaque appel, seconde, jauge) :
   - son fil a fini : la faute de son compteur s'il en a une (`time budget at …` : faute *limite* ordinaire, la première
     ne désactive pas — c'est le cas de la boucle arrêtée qui se dénoue après la marge de 5 ms, problème 2) ; un arrêt
     par le temps CPU (étape du JDK) est un **abandon** ; sinon **personne** (`starved`, journal « nobody's fault ») ;
   - son fil vit encore et a calculé `cpuEvidence(budget)` : **abandonné** (`time budget: one call ran past 25 ms and was
     abandoned`), désactivé, compteur révoqué, fil perdu (`ModuleThreads.lose` : priorité normale, plafond de deux) ;
   - sinon, rien encore.
4. **Lenteur** : plus de 50 ms **de son travail** par seconde (le travail de chaque appel : `max(worked, min(cpu, mur))`,
   plus le temps que l'appelant a passé à servir ses requêtes du SDK), cinq secondes de suite. `msPerSecond` reste le
   temps mural (ce que le serveur a attendu).
5. **Mémoire** (jauge) : ses plafonds sont **du travail, plus du temps** : 200 000 objets, ou 32 Mio estimés (la marche
   s'arrête dès qu'elle les passe : « retains too much to measure (over 200000 objects) », « retains over 32 MiB »). Une
   référence suivie a été payée 8 octets par ce qui la tient : une marche suit au plus 4 Mi références. Les champs
   statiques sont lus dans l'appel mesuré (`statics`, l'initialiseur statique du module y tourne) ; la marche elle-même,
   qui n'exécute aucun code du module, tourne après le `disarm`, sur le fil du module : son coût n'est jamais compté au
   module, et un appel de jauge lâché donne son verdict quand il a fini.

**Ce qui n'est pas une faute** (journalisé et compté à part) :

- un appel que le fil des modules n'a pas même commencé à temps (`LATE`) : **sauté** (`skipped`), rejoué par le moteur ;
- un appel lâché dont le fil n'a pas calculé : **personne** (`starved`) ; il a fini seul, le module continue ;
- le coût de la jauge ; une pause du GC ; le temps où la machine retient le fil ;
- un démarrage retenu (voir ci-dessous) ; au banc, un appel retenu (le banc attend).

**Patience** (`ModuleThreads.Patience`) : un appelant qui peut attendre attend au-delà du budget **tant que le fil n'a pas
calculé** (`cpuEvidence`), garé par tranches de 1 ms (le fil des modules le réveille pour une requête du SDK) :

- le **démarrage** (constructeur, `start`) : jusqu'à **2 s** (`STARTUP_PATIENCE_NANOS`) — un démarrage ne peut pas finir
  seul (ses requêtes du SDK seraient refusées) ; une machine saturée a tenu un démarrage froid (28 ms de travail) plus
  d'une seconde. Un démarrage qui calcule est lâché à 50 ms de son temps CPU (`cpuEvidence(50 ms)`) : faute, désactivé.
  Un démarrage retenu plus de 2 s sans calculer est `stalled` : le déploiement finit `disabled` avec la raison (« its
  start did not end within … ms, its thread held back by an overloaded machine »), **aucune faute** notée sur la version ;
- le **banc** : jusqu'à **10 s** (`ForgeService.BENCH_PATIENCE_NANOS`) ; au-delà, l'échec dit « the server held the
  module's thread back without it computing (an overloaded server, not the module's doing); test again » ; les verdicts
  du banc (« slow », lenteur, plus lent appel) lisent `HostCall.work`, jamais l'horloge murale.

**Priorité** : le fil des modules prend la priorité de son appelant à chaque remise (8 pour le thread serveur, 5 au
banc et dans les tests). Un appel est le travail du thread serveur, remis à un autre fil : les fils que le serveur
devance ne le retiennent plus. Un fil lâché garde sa priorité jusqu'à son verdict (pour finir vite s'il est sain) ; un
fil perdu passe à la priorité **normale**. **Aucun fil de la Forge ne tourne plus à la priorité minimale** (cause
ci-dessus) : à la priorité normale, un fil partage le processeur avec les fils ordinaires et atteint un point d'arrêt en
quelques tranches de temps ; le thread serveur (8) passe toujours devant lui.

**ECJ** : ses limites (10 s, coincé à 60 s) comptent le **temps CPU de son fil**, lu toutes les 100 ms
(`EcjCompiler.WATCH_MS`) et à chaque contrôle d'ECJ, jamais l'horloge murale : sur une machine saturée, une compilation
de 40 ms de travail a pris 13,8 s (M5-15, à la priorité minimale). Le fil passe à la priorité **normale**.

## Le moteur (`ModuleEngine`)

- Un appel **sauté** (`Called.skipped`) est gardé avec son corps et l'opération de l'hôte (un `start`) et **rejoué** en
  premier au tick suivant, chacun une invocation : un fait, un minuteur ponctuel ou un rappel n'est jamais perdu.
- Tant qu'un appel lâché court (`ModuleHost.busy`), le tick du déploiement attend : ses minuteurs comptent le tick
  (`timers.advance`), ses faits et rappels restent dans leurs files.
- Un démarrage `stalled` finit le déploiement (`disabled: it failed to start: …`), sans faute notée.
- **Fin refusée par la mémoire** (M5-16, défaut 2) : si `resolveCanon` refuse la note (`MemoryRejected` : la raison de
  `ctx.end` à la première ou à la deuxième personne, `VoiceGuard`), le moteur réessaie avec ses propres mots neutres
  (`neutralNote` : `it ended by itself` pour `fulfilled`, `it was stopped`, `it was disabled by its faults`, `its time
  ran out`, `the Forge was switched off by an operator`, `it could not restart`) : la ligne du canon se ferme toujours.
  Le Directeur lit toujours les mots du module dans la ligne `MODULE`.

## Décisions

1. **`forge/rt/Meter.java` touché, hors de ma liste** (ajouts seulement, aucune protection retirée). Sans lui, le
   compteur arrêtait à l'horloge murale : sous charge, la boucle de `HostBatteryTest` arrêtée à 20 ms murales n'avait
   souvent que 8 à 12 ms de CPU (une lecture de 0 ou 15,6 ms), et aucune règle de l'hôte ne pouvait alors distinguer
   « boucle fautive retenue » de « appel sain retenu » (problème 2 et faux arrêts des appels sains). Le compteur est le
   seul à voir passer le code du module : c'est là que se mesure son travail.
2. **Un seul seuil de preuve CPU**, `cpuEvidence(budget)` = max(budget, 40 ms), partout (compteur, appel lâché,
   patience) : sous Windows, 40 ms est trois pas de l'horloge CPU, qu'un appel de quelques millisecondes ne montre jamais.
   Prix assumé : une **seule** étape du JDK de 20 à 40 ms dans un appel n'est plus comptée comme faute (elle est toujours
   coupée par l'abandon mural à 25 ms) ; répétée, elle est une *lenteur*. Avant, elle était une faute *limite*.
3. **La jauge sans horloge** (demande : « ne désactive jamais un module à cause de son propre coût ») : seuls ses plafonds
   de travail donnent un verdict. Un module qui retiendrait entre 120 000 et 200 000 objets échappait avant à toute mesure
   (5 ms de marche) ; il est maintenant mesuré jusqu'au bout.
4. **Abandon jugé au règlement** : `AbandonTest`, `ModuleHostGameTests` et l'attaque de L2 attendent le règlement
   (`Hosts.settled`, `host.busy()`) puis vérifient la même faute (`LIMIT`, désactive, « … and was abandoned »). Entre
   l'abandon et le verdict, le module est occupé : aucun code de lui ne tourne.
5. **Les tests sous charge** : là où un test mesurait « 18 ms de module » par une boucle sur l'horloge murale (qui fait
   moins de travail sous charge), il boucle un nombre de tours étalonné une fois par JVM (`Hosts.rounds`, mesuré par le
   compteur sur des appels de 100 ms : l'horloge CPU y pèse peu).
6. **`busy()` est une fonction** (elle règle l'appel lâché, ce qui peut compter une faute), pas une propriété.
7. **Les priorités du compilateur et des fils perdus** (décisions de M5-02 et M5-05, que je change) : priorité normale
   au lieu de minimale. `EcjCompiler.kt` ne m'était ouvert que pour sa limite ; la priorité de son fil en fait partie
   (problème 7 : la compilation affamée) et surtout protège le tick (cause « un fil à la priorité minimale fige toute
   la JVM »). Sur une machine calme, rien ne change (le fil tourne sur un cœur libre) ; sur une machine chargée, le
   thread serveur (8) passe toujours devant. Le test « nothing heavy runs on the caller's thread » garde sa borne de
   400 ms ; son appelant tourne comme le thread serveur (priorité 8, ses délais sur son propre fil) : c'est lui qui a
   montré le gel.
8. **`docs/ARCHITECTURE.md`** (point 3 de la Forge) dit encore « son fil, qui finit son calcul à la priorité minimale » :
   fichier hors de ma liste, à corriger par le superviseur (« à la priorité normale »).
9. **Un défaut de `ModuleThreads` corrigé** (vu sous charge) : le fil des modules prenait l'appel remis en deux temps
   (`next` lu, puis remis à `null`) ; un appel remis entre les deux était effacé, jamais pris, et donc sauté (`LATE`).
   Observé sous forte charge : le chat « vite » de `ModuleEngineTest` jamais lu par le module (sa première ligne, une
   trace temporaire, jamais écrite), trois passages sur trois ; c'est la course que le code permettait quand un fil
   des modules lent à démarrer se réveille juste quand l'appelant remet l'appel suivant. Désormais un `AtomicReference`
   pris et vidé d'un seul geste (`getAndSet`). Et un fil des modules naît à la priorité de son appelant (celui qu'on
   crée après un abandon démarre tout de suite, au lieu d'attendre derrière les fils ordinaires).
10. **Tests ajustés à la charge, sans rien relâcher de ce qu'ils protègent** : `EcjCompilerTest` (« held back… never too
   long ») chauffe ECJ d'abord puis bloque la compilation 2 s, au-delà de ses limites de 300 et 600 ms de travail (sous
   charge, la première compilation d'une JVM coûte des centaines de millisecondes de travail : le JIT est en retard) ;
   `ModuleEngineTest` (« held back… none is lost ») fait des secondes jusqu'à ce que l'appel sauté ait couru (20 au
   plus), et admet qu'il soit lâché à son tour (voir M5-22, « un appel coupé »), mais exige toujours 0 faute et le
   rejeu ; `ForgeServiceTest` (« nothing heavy… ») fait tourner son appelant comme le thread serveur ; `HeldBackTest`
   vérifie qu'un appel sauté parce que le module attend l'est **sur-le-champ** (0 ns : jamais remis à un fil), ce qui le
   distingue d'un appel `LATE`. L'attaque `DistinctShared` (`test/resources/forge/evil`) hache L1 **quatre fois** au
   lieu d'une : un seul hachage (50 à 65 ms de CPU) tombait, aux pas de 15,6 ms de l'horloge CPU, à cheval sur la preuve
   de 50 ms du démarrage ; quatre la dépassent nettement. Le résiduel documenté (M5-18) et la faute attendue
   (`limit abandoned`) sont les mêmes ; sa borne dans `EvilModulesGameTests` est `55 + 16 ms + BusyMachine.SLACK_MILLIS`.

## Sondes (retirées ensuite)

Chaque sonde casse **une** règle dans une copie de travail à part (un worktree temporaire, supprimé), lance les tests
qui doivent la voir, puis remet le code (script `probes.ps1` du bloc-notes de la session, rien dans le dépôt). Toutes
sous charge, sauf S6 (rejouée au calme après le durcissement de `HeldBackTest`). **Toutes couvertes** : au moins un
test échoue, pour la bonne raison.

| Sonde | La règle cassée | Les tests qui échouent (et pourquoi) |
|---|---|---|
| S1 | le compteur ne crédite aucun travail | `MeterTest` (« 0 ns of its own work ») ; `HeldBackTest` (la boucle arrêtée devient un abandon) |
| S2 | le compteur crédite tout écart (l'horloge murale d'avant) | 7 : `MeterTest` (un appel retenu « time budget », 75 ms de « travail ») ; `HeldBackTest` × 5 (fautes *limite* et *lenteur* sur des appels retenus) |
| S3 | pas de règle CPU pour une étape longue du JDK | `MeterTest` (« disarm trips a call that overran inside one call of the JDK ») |
| S4 | un appel lâché n'est jamais vu calculer | 4 : `AbandonTest` × 3 (plus d'abandon, plus de fil perdu), `HeldBackTest` (le banc patient n'abandonne plus) |
| S5 | un appel lâché dont le compteur a sauté n'est pas une faute | `HeldBackTest` (« one limit fault » : aucune) |
| S6 | les appels d'un module occupé ne sont plus sautés | `HeldBackTest` (« never two calls of a module at once ») |
| S7 | le moteur ne rejoue pas les appels sautés | `ModuleEngineTest` (« vite » jamais lu) |
| S8 | pas de note neutre quand la mémoire refuse | `ModuleEngineTest` (la ligne du canon reste ouverte) |
| S9 | pas de patience au démarrage | 3 : `HeldBackTest` (le démarrage retenu ne démarre pas, etc.) |
| S10 | la jauge sans plafond d'octets | `RetainedGaugeTest`, `ModuleHostTest` (« retains over 32 MiB ») |
| S11 | lenteur à l'horloge murale | 5 : `HeldBackTest` × 2, `ModuleHostTest` × 3 (secondes étirées jugées lentes) |
| S11b | plus de lenteur du tout | `ModuleHostTest` (« over 50 ms of its code a second… » : `SLOWNESS` attendue) |
| S12 | le fil des modules garde sa priorité | `ModuleThreadsTest` (« runs at its caller's priority ») |
| S13 | les limites d'ECJ à l'horloge murale | `EcjCompilerTest` (« held back… never too long » : `stuck`) |
| S14 | deux compilations d'une même version à la fois | `ForgeServiceTest` (« compile it once » : 4 compilations) |
| S15 | `onUnavailable` jamais appelé | `ForgeServiceTest` × 2 (compilateur bloqué, fils perdus) |
| S16 | le banc juge l'horloge murale | `BenchTest` (« 40 ms held back, no work » : `slow`) |
| S18 | l'appelant attend 3 s de plus (plus de filet mural) | 5 : `AbandonTest` (« holds the caller no longer », « never holds the caller past the budget »…) |
| S19 | un fil abandonné n'est pas perdu | `AbandonTest` × 2 (plafond des fils perdus, L2) |
| S20 | le compilateur à la priorité minimale | `EcjCompilerTest` (le fil à `prio 1`) |
| S21 | un fil perdu à la priorité minimale | `ModuleThreadsTest` (« expected 5 but was 1 ») |

Non couverts par une sonde : `LATE` (un appel jamais commencé, sauté sans faute : seule la charge le produit ; vu dans
les journaux des passages) et la course de `ModuleThreads` corrigée (Décisions, 9 : non déterministe).
Les attaques (`EvilModulesTest`, `HostBatteryTest`, `AbandonTest`, `EvilModulesGameTests`) font partie des passages
sous charge et du build final : toutes vertes, inchangées sinon `DistinctShared` (Décisions, 10).

## Mesures avant et après

Charge : 32 fils de calcul en plus (un processus Java à part) sur 16 fils logiques, processeur à 100 % pendant toutes
les mesures « sous charge » (méthode de FLAKY-4).

**Un module sain qui agit à chaque tick** (sonde temporaire `LoadProbeTest`, retirée : le minuteur d'`orbiting_chickens`
imité, un `dayTime` et huit sinus ; 3 000 appels espacés de 10 ms, sous charge) :

| | Règles d'avant (horloge murale), jugées sur la même série | Règles d'après |
|---|---|---|
| fil des modules à la priorité 5 | 6 appels au-delà de 20 ms (6 fautes *limite*), 6 à 25 ms ou plus (le premier, à l'appel 218, aurait désactivé le module), 2 tranches de 20 appels au-delà de 50 ms ; appel le plus long : 56,6 ms murales | **0 faute** ; 3 appels lâchés réglés sans faute (`starved`), 4 sautés puis rejoués ; 0 seconde lente ; au plus 9,83 ms de travail |
| à la priorité 8 (celle du thread serveur, que le fil des modules prend désormais) | — | aucun appel retenu : 0,4 ms murales au plus |

**Les pauses de la JVM** (`-Xlog:safepoint`, attente du point d'arrêt, sous charge) :

| | Avant (compilateur à la priorité minimale) | Après (priorité normale) |
|---|---|---|
| test « nothing heavy runs on the caller's thread » | appelant (priorité 8) figé 511, 529 et 2 572 ms : 3 échecs sur 5 passages | 0 échec en dix passages |
| JVM des tests (hôte, moteur, service, arsenal) | 3 JVM, 153 points d'arrêt : 11 attentes de plus de 100 ms, **5 de plus de 400 ms, la plus longue 2 565 ms** ; fil en retard nommé : `narrator2-forge-compiler prio=1` (et un `commonPool-worker` à `prio=1`) | 10 JVM (les dix passages), 853 points d'arrêt : 79 de plus de 100 ms, **aucune de plus de 400 ms, la plus longue 391 ms** ; aucun fil de la Forge parmi les fils en retard |
| serveur de GameTest (`ArsenalCreaturesGameTests`) | la Forge « prête » en 67 à 75 s avec 0 ou 1 des 7 modules intégrés construits ; les 2 GameTests expirent (tick 10 000), deux fois sur deux | la Forge prête en 4,3 à 5,1 s, 7 sur 7 ; `orbiting_chickens ended (fulfilled): 5 acts, 0 faults` à chaque fois ; 11 JVM de GameTest, 807 points d'arrêt, une attente de plus de 400 ms (685 ms, fils de la JVM et du jeu, aucun de la Forge) |

**Les passages des tests sous charge** (hôte, moteur, service, arsenal, attaques : 13 classes, 107 tests) : avant
la priorité normale du compilateur (passages a à f, au fil des corrections), un échec par passage ou presque, de 590 à
740 s chacun (dont le test de l'appelant, figé par le compilateur) ; après, de 157 à 370 s (511 s une fois, quand les
sondes tournaient en même temps) : les compilations ne sont plus affamées.

Les mesures « avant » du cahier des charges (`ForgeServiceTest` 19 échecs sur 220 appels au banc, 8 cas de
`ModuleEngineTest`, ECJ annulé à 13,8 s, 82 ms/s de lenteur murale) viennent de M5-15, M5-21 et FLAKY-4 : je ne les ai
pas remesurées sur `main`.

## Ce que M5-21 et M5-22 doivent savoir

### M5-21 (l'assemblage, fusionné avant moi)

- **`onUnavailable`** : `ForgeService(services, onUnavailable = { reason -> … })` remplace le guet de `ForgeSetup.kt`
  (une lecture de `available` par seconde) : appelé une fois, sur le fil qui constate la chute (le fil du pool de la
  surveillance du compilateur, ou celui qui perd le troisième fil), jamais à `stop()`. À brancher par M5-21 (je n'ai pas
  touché `ForgeSetup.kt`).
- **Une compilation par version** au redémarrage : plus de `compiled keeper v1` en double.
- Le **moteur** construit ses hôtes aux valeurs du jeu (aucun crochet) : la patience du démarrage (2 s) et la priorité du
  fil des modules (celle du thread serveur, 8) s'appliquent d'elles-mêmes. Le **banc** a sa patience (10 s) dans
  `ForgeService.bench`.
- `ModuleThreads.close()` à l'arrêt, inchangé. Un fil lâché qui court encore au déchargement d'un module est révoqué et
  compté parmi les fils perdus (prudent : il finit au prochain code du module).

### M5-22 (la recette)

- **Ce qu'il faut compter sur le serveur réel** (journal `Forge`, `INFO`) : `its <call> call was given up at N ms, its
  thread held back; it runs on alone` (un appel lâché), puis `… ended without computing past its budget … nobody's fault
  (N so far)` (réglé sans faute), et les `DEBUG` `… is skipped` (appels sautés puis rejoués). Une décision `mechanic fault`
  sur un module sain serait un défaut à signaler, avec sa cause.
- Sur le serveur, le fil des modules tourne à la priorité 8 : sous 32 fils de calcul en plus, la sonde de charge
  (« Mesures ») n'a vu **aucun** appel retenu au-delà de 0,4 ms à cette priorité.
- La **lenteur** est du travail, plus du temps mural : `ModuleView.msPerSecond` (temps mural) peut dépasser 50 sans faute
  sur un serveur chargé ; c'est voulu.
- La **jauge** sans horloge : une marche peut durer plus de 5 ms sur un module qui retient beaucoup ; elle tourne sur le
  fil du module, le serveur ne l'attend jamais plus de 25 ms (au-delà, elle finit seule et son verdict vient ensuite).
- **ECJ** : à la priorité normale désormais ; sous forte charge, une compilation prend plus de temps mural sans jamais
  être « trop longue » (son travail est compté) ; `forge_test` a son propre délai de 45 s (l'outil).
- **Les pauses de la JVM** : sur un serveur chargé, `-Xlog:safepoint` montre l'attente du point d'arrêt (« Reaching
  safepoint ») ; avec `-XX:+UnlockDiagnosticVMOptions -XX:+SafepointTimeout -XX:SafepointTimeoutDelay=300`, la JVM nomme
  le fil en retard. Un fil de la Forge n'y doit plus figurer à `prio=1`.
- **Un appel coupé par une pause de la JVM** : même sans fil à la priorité minimale, une machine saturée fait attendre
  les points d'arrêt (des fils ordinaires affamés) : dans la JVM des tests, une attente de plus de 100 ms toutes les
  25 s environ, 391 ms au plus.
  Un appel en vol quand une pause commence est lâché au réveil de l'appelant (ses 25 ms murales sont passées) et coupé
  (ses requêtes du SDK refusées) : **aucune faute**, journalisé (`given up` puis `nobody's fault`). Possible, pas
  observé : ni dans les GameTests sous charge, ni dans les passages (`ModuleEngineTest` l'admet pour l'appel rejoué ;
  l'échec que j'y ai d'abord pris pour cela était la course de `ModuleThreads`, Décisions 9). Si la recette en voit
  trop en jeu, une piste bornée : quand l'appelant constate qu'il a lui-même été arrêté
  (un écart de plus de 1 ms dans son attente), lui laisser encore la marge (5 ms) de son propre temps, deux fois au plus
  par appel — le serveur n'attendrait jamais plus de 35 ms de son temps à lui. Je ne l'ai pas fait : le filet mural
  reste tel que la mission le demande.
- Refaire une série de démarrages réels avec la sonde de M5-21 une fois cette branche fusionnée.

## Petits défauts réglés au passage

- `SandboxPolicy` : le refus de `String.matches` conseille « use Text.words(), Text.normalize(), contains() or
  equals() » (et non `ctx.text()`, qui n'existe pas).
- `Spawn` (Javadoc de `count`) : les plafonds du chaos (0, 2, 4, 6, 10, 12 créatures par appel du chaos 0 au chaos 5 ;
  0, 4, 8, 16, 24, 32 créatures du Narrateur vivantes dans le monde), « deux par deux », le refus à gérer.
  `Creature.velocity`/`push` : hors de ±2, `ForgeRefusal`, jamais une valeur bornée. `sdk.md` : une ligne pour le chaos
  au §4, le commentaire de `Spawn.of`, celui de `velocity` et `push`, et « 20 ms de son propre travail » au §7 ;
  29 936 caractères (une ligne d'exemple de refus de la garde retirée, redondante avec le §8).
- `ModuleEngine.close` : la note refusée (ci-dessus), avec son test.

## Vérifications

Charge : un processus Java à part, 32 fils de calcul sur 16 fils logiques, processeur à 100 % (méthode de FLAKY-4).

- **Build complet** (`.\gradlew.bat build`, machine calme, sur le code final fusionné avec `main` à `7105e7d`) : vert ;
  **1 944 tests unitaires** (224 classes), 0 échec ; **390 GameTests** (« All 390 required tests passed »).
- **Dix passages sous charge sur le code final**, sans aucun échec : à chaque fois les 13 classes de l'hôte, du moteur,
  du service, de l'arsenal et des attaques (`ModuleHostTest`, `HeldBackTest`, `AbandonTest`, `ModuleThreadsTest`,
  `HostBatteryTest`, `RetainedGaugeTest`, `PipelineTest`, `SdkBridgeTest`, `MeterTest`, `ForgeServiceTest`,
  `ArsenalScenariosTest`, `EvilModulesTest`, `ModuleEngineTest`), 107 tests, soit 1 070 exécutions, 0 échec.
- **Le compilateur et le banc sous charge** (`EcjCompilerTest`, `BenchTest`, 48 tests) : trois passages sur le code
  final, 0 échec.
- **En jeu, sous charge** (serveur de GameTest) : `ArsenalCreaturesGameTests` (le module sain `orbiting_chickens`, qui
  agit à chaque tick, et `parade`) et `ModuleEngineGameTests` (le module `spinner`, qui boucle), cinq fois chacun (trois
  avant la dernière retouche de `ModuleThreads`, deux sur le code final), puis `ModuleHostGameTests` et
  `EvilModulesGameTests` (deux fois chacun, dont une sur le code final) : tout vert, 38 GameTests exécutés.
  `orbiting_chickens` finit à chaque fois `ended (fulfilled): 5 acts, 0 faults` (jamais désactivé) ; `spinner` est à
  chaque fois `disabled: limit in timer: time budget at Spinner.java:7` (2 fautes) ; l'attaque `indexOf` est abandonnée
  à 25 ms, `DistinctShared` à 55 ms ; la Forge prête en 2,6 à 5,5 s.
- **Les échecs rencontrés en route** (tous expliqués et réglés, rien d'ignoré) : le test de l'appelant figé (le
  compilateur à la priorité minimale, Causes) ; `EcjCompilerTest` « held back » (le travail d'une première compilation
  sous charge, Décisions 10) ; `ModuleEngineTest` « none is lost » (la course de `ModuleThreads`, Décisions 9) ;
  plus tôt, l'attaque `DistinctShared` à cheval sur sa preuve (Décisions 10).
- Les sondes S1 à S21 (ci-dessus), retirées ; le fichier de sonde de charge `LoadProbeTest` retiré.
