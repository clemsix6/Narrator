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
     abandoned`), désactivé, compteur révoqué, fil perdu (`ModuleThreads.lose` : priorité minimale, plafond de deux) ;
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
fil perdu passe à la priorité minimale.

**ECJ** : ses limites (10 s, coincé à 60 s) comptent le **temps CPU de son fil**, lu toutes les 100 ms
(`EcjCompiler.WATCH_MS`) et à chaque contrôle d'ECJ, jamais l'horloge murale : à la priorité minimale sur une machine
saturée, une compilation de 40 ms de travail a pris 13,8 s (M5-15). Le fil reste à la priorité minimale.

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

## Sondes (retirées ensuite)

À remplir.

## Mesures avant et après

À remplir.

## Ce que M5-21 et M5-22 doivent savoir

À remplir.

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

À remplir.
