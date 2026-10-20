# Passation FLAKY-4 : l'hôte des modules sur une machine chargée

Mission : rendre déterministes, sans les affaiblir, les tests de l'hôte des modules (M5-05) qui échouaient sous charge,
et ceux de `forge/host` et `forge/sandbox` qui dépendent de l'horloge murale, du GC ou de l'ordonnancement ; puis, à la
demande du superviseur, `ModuleHostTest` › *twenty unloaded loaders…* (rouge à chaque `build` depuis M5-11) et les
GameTests de l'hôte et du moteur vus instables par M5-10, et le premier acte froid du banc (M5-16). Branche
`worktree-agent-af6a98845d846e9d1`, fusionnée avec `main` après M5-18, M5-10, M5-16 et M5-17. **En cas de contradiction avec
`M5.md` ou une passation plus ancienne (M5-05), le code et cette passation font foi.**

| Test | Cause | Où était le défaut | Correction |
|---|---|---|---|
| `ModuleHostTest` › *twenty unloaded loaders…* | une fois le code compilé, la JVM garde sur un fil **la dernière exception levée sur ce fil** ; le fil des modules vit après le déchargement, et la pile de l'exception tient les classes du dernier module | **hôte** : vraie rétention, aussi en jeu | `ModuleHost.unload()` termine le fil des modules et en démarre un neuf (`ModuleThreads.renew`) ; test nouveau ; attente des collectes par condition |
| `ModuleHostTest` › *over 50 ms…* | 18 ms de calcul sous un budget de 20 ms : 2 ms de préemption suffisent | test | appels patients |
| `HostBatteryTest` › *a finally…*, *catch Exception…* (et tout cas « de temps ») | le compteur arrête la boucle, mais un fil chargé met plus que la marge de 5 ms à se dénouer : l'appel est **abandonné**, sans la ligne du module | test | budgets du jeu, marge de 500 ms ; les autres cas, patients |
| `ModuleHostTest` › *gauge walks a cycle…* | l'horloge de 5 ms de la jauge : une marche froide coûte ≈ 11 ms, et sous charge les deux essais sont coupés | test | horloge de la jauge patiente |
| autres tests de `ModuleHostTest`, `PipelineTest`, `AbandonTest`, `ModuleThreadsTest`, `RetainedGaugeTest`, `InstrumenterTest`, `MeterTest`, `EvilModulesTest`, `RewriteBatteryTest` | même famille : appel innocent coupé par l'horloge, abandon au lieu de l'arrêt du compteur, retard de l'appelant, horloge CPU de 15,6 ms, fil perdu affamé | test | §2 et §3 |
| `ModuleHostGameTests` (serveur arrêté par une `NullPointerException`, ligne 141) | `start` désactivé sous charge, `host.call` rend `null`, `called!!` | test | `start` patient, échec par assertion, jamais par `!!` |
| `ModuleEngineGameTests.aFaultyModuleIsDisabledAndItsCreaturesGo` | l'appel arrêté par le compteur est abandonné (marge de 5 ms), ce qui désactive dès la première faute | test | les deux chemins acceptés, chacun vérifié |
| `MechanicsCommandGameTests` (vu par M5-10) | le cas du point 4 de M4-LEFTOVERS, vu sur une branche **sans** son correctif | — | rien à faire (§5) |
| `ArsenalScenariosTest` (vu par M5-16) | le premier acte d'une JVM charge le chemin d'un acte dans l'appel mesuré du module | préchargement de l'hôte et du banc | chemin d'un acte préchargé (§6) |

## 1. La vraie rétention : le dernier module déchargé restait en mémoire

### Cause, établie

Le chargeur retenu est toujours **le dernier** des vingt, un `Clinger` (son `start` lève `Oops`). Sonde (non commitée) :

- le fil des modules vit et se gare dans `take()` ; il n'a **aucun** `ThreadLocal` ;
- un appel vide sur ce fil **ne libère pas** le chargeur ; un appel qui lève et rattrape une exception quelconque **le
  libère** ; terminer le fil le libère aussi ;
- `-Xint` : jamais retenu ; `-XX:TieredStopAtLevel=1` (C1 seul) : retenu ; C1 et C2 : retenu ; sans OSR : retenu.

L'hypothèse de M5-10 (C2 inline le code du dernier module dans la boucle du fil, et ce cadre compilé le garde) est donc
écartée : un appel vide remplacerait ce cadre, et C1 seul retient aussi. C'est un état **par fil** de la JVM : la
dernière exception levée sur le fil par du code compilé y reste référencée jusqu'à l'exception suivante sur ce fil ou
jusqu'à la fin du fil. Sa pile (`backtrace`) tient les classes du module, donc son chargeur.

**Reproduction sans la Forge** (JDK 25.0.1, Windows) : un fil exécute l'un après l'autre des `Runnable` chargés chacun
par un chargeur neuf, qui lèvent une exception de leur propre classe, rattrapée par le fil ; puis une tâche vide ; puis
le fil attend. Le dernier chargeur est-il collecté ?

| Réglage | 20 tours | 200 tours | 1 000 et 5 000 tours | 5 000 tours puis une exception levée et rattrapée sur le fil |
|---|---|---|---|---|
| `-Xint` | oui | oui | oui | oui |
| C1 seul | — | — | **non** | oui |
| C1 et C2 (défaut) | oui | oui | **non** | oui |

Que le `catch` lise l'exception ou non n'y change rien. D'où l'instabilité : le test passe tant que le chemin des
exceptions de l'hôte est interprété (seul, ou en début de JVM), et échoue une fois ce chemin compilé, ce qui dépend des
classes passées avant (`ForgeServiceTest` en fait tourner des milliers d'appels) et du rythme du compilateur.

**En jeu**, c'est une vraie fuite, bornée à un chargeur à la fois : le dernier module déchargé qui avait levé une
exception (la sienne, ou celle de son compteur : toute faute *limite* en lève une) restait en mémoire jusqu'à la
prochaine exception sur le fil des modules, et `LeakWatch` l'aurait dit (`forge leak`) à tort au bout de deux minutes.

### Correction (`forge/host`, au plus petit)

`ModuleHost.unload()` appelle `threads.renew()` (nouveau, `ModuleThreads`) : le fil des modules se termine, et un fil
neuf est **démarré aussitôt** pour l'appel suivant, de n'importe quel module (≈ 0,1 ms sur le fil appelant, une fois
par fin de déploiement). Rien n'est démarré si aucun fil ne vivait (le banc ferme son `ModuleThreads` avant `unload`).
Un fil qui se termine lâche tout ce que la JVM gardait pour lui : l'exception, la pile, et aussi un `ThreadLocal` que
laisserait un code mal fermé (le `Meter.CURRENT` de M5-18 est retiré à chaque `disarm`, mais rien ne garantit
l'avenir). KDoc de `unload` et de `ModuleThreads.renew`, encart dans `M5.md` §3.7.

Pourquoi démarrer le suivant tout de suite : une première version fermait seulement le fil, et l'appel suivant devait
en créer et en démarrer un. Sous charge, le démarrage d'un fil peut dépasser l'échéance d'une remise : l'appel est
alors `LATE`, une faute *limite* (au `start`, elle désactive). Vu une fois dans `EvilModulesTest` (« Capacity: the module
thread did not start the call within 55 ms »). Démarré au déchargement, le fil suivant est en général garé quand
l'appel arrive, comme le fil d'avant : le risque redevient celui de tout fil garé.

Le moteur (M5-11, `ModuleEngine.retire`) et le banc (M5-10) n'ont rien à changer. Un fil **perdu** n'est pas
concerné : il tient déjà son module tant qu'il calcule (M5-05).

### Tests

- **Nouveau, déterministe** : *unloading ends the module thread its code ran on, and the last exception the JVM keeps
  there* : le fil qui a exécuté l'appel fautif est terminé après `unload()`, et le chargeur est collecté.
- *twenty unloaded loaders…* : inchangé dans ce qu'il vérifie ; `Hosts.collected` attend désormais **une condition**
  (tous collectés) jusqu'à 30 s, au lieu de 20 tours de 50 ms.
- **Sonde A** (`threads.close()` retiré de `unload`) : le test nouveau échoue à chaque passage ; *twenty unloaded
  loaders* échoue à chaque passage sur une JVM chaude (paquet `forge.host` trois fois de suite, ou `ForgeServiceTest`
  puis `ModuleHostTest` trois fois : « 1 of 20 loaders are still alive » aux passages 2 et 3). Avec la correction : vert.

## 2. L'horloge murale

### Ce que fait une machine chargée (mesuré)

Charge : 32 fils qui calculent sur 16 processeurs logiques (2 fois le nombre de cœurs), plus les builds des autres agents.

| Mesure | Valeur |
|---|---|
| retard de l'appelant après l'échéance d'un abandon (priorité normale, 1 000 à 3 000 essais) | médiane 0,7 à 19 ms, p99 5 à 76 ms, **maximum 137 ms** |
| même chose, appelant en priorité 8 (celle du fil serveur de Minecraft quand il y a plus de 4 processeurs) / 10 | maximum 81 ms / 14 ms : la priorité aide, ne suffit pas |
| boucle arrêtée par son compteur à 20 ms : temps pour se dénouer | p50 1,4 ms, p99 6 à 7,5 ms ; **1 sur 100 abandonnée** avec la marge du jeu (5 ms) |
| appel vide d'un module (fil chaud) | p99 0,03 ms, maximum 4,3 ms |
| appel remis juste après un autre (le fil tourne encore) et jamais commencé avant l'échéance (`LATE`) | 0 sur 3 000 |
| horloge CPU d'un fil (`ThreadMXBean`, Windows) | **pas de 15,625 ms** |
| fil perdu à la priorité minimale, cœurs tous pris | affamé : 0,75 s de calcul a pris plus d'une minute ; les pauses du GC n'en souffrent pas (4 à 5 ms, mesuré) |
| un appel de 75 ms de calcul, une fois | tenu **plus de 2 s** (JVM d'essai saturée) |

Ces valeurs sont dans la KDoc de `testing/BusyMachine.kt`.

### Deux règles (`testing/BusyMachine`)

- **Un test dont le sujet n'est pas le temps** donne à son module la patience d'une machine chargée :
  `BusyMachine.TIME_SCALE` (500) pour un `ModuleHost` — un appel 10 s, `start` 25 s, la marge et l'horloge de la jauge
  2,5 s —, ou `BusyMachine.PATIENT_NANOS` (10 s) pour le compteur, la jauge, les fils des modules, le harnais de la
  réécriture. Seule la limite testée peut alors arrêter le module ; si elle disparaissait, une autre l'arrêterait (le
  temps, l'allocation), **avec une autre faute**, et le test échouerait.
- **Un test du temps** garde les budgets du jeu, et vérifie **ce qui s'est passé** exactement (abandonné ou non, quel
  fil, quelle faute, quelle ligne). Une borne sur l'horloge murale au-delà d'une échéance prend
  `BusyMachine.SLACK_MILLIS` (500 ms, 3,6 fois le pire retard mesuré), bien moins que ce que prendrait le code sans sa
  limite (un module bloqué une minute ; une boucle d'`indexOf` sans abandon, 1 s mesurée).

### Les crochets de test de `ModuleHost` (changement de `forge/host`)

```kotlin
class ModuleHost(mainClass, classes, threads, label, clock = Clock.systemUTC(), lineWaitMillis = 0,
                 timeScale: Long = 1,                                          // nouveau
                 abandonMarginNanos: Long = ABANDON_MARGIN_NANOS * timeScale,  // nouveau
                 gaugeNanos: Long = ForgeLimits.GAUGE_MAX_NANOS * timeScale,   // nouveau
                 onFault: (Fault) -> Unit = {})
```

Le jeu garde les valeurs par défaut. `timeScale` multiplie les limites **propres** de l'hôte (le budget d'un appel,
celui de `start`, la marge, l'horloge de la jauge) ; un budget passé à `call(what, budgetNanos, body)` est pris tel quel
(c'est ainsi qu'un test du temps garde le budget du jeu dans un hôte patient). L'allocation permise suit toujours le
budget **du jeu** (16 Mio par appel), jamais le budget étiré. Les textes des fautes donnent les valeurs réelles (« ran
past 25 ms », « seen in 5 ms »).

### Test par test

- `ModuleHostTest` : tous les hôtes patients. *two limits* : boucles au budget du jeu (`call("timer", CALL_NANOS)`),
  marge de 2,5 s, donc toujours l'arrêt du compteur. *nested* : la boucle imbriquée au budget du jeu, bornée à
  20 ms + 500 ms (contre 10 s pour le scénario) ; le second cas agrandi (600 ms en tout, 100 ms passées avant, l'appel
  imbriqué demande 2 s et doit s'arrêter avant 500 + 500 ms). *nested in abandoned* : l'appel n'est plus « dormi 60 ms »
  mais attend que son appelant l'ait abandonné (un verrou), donc toujours abandonné. *BadStart* : `timeScale = 10`
  (sa boucle tourne jusqu'à 500 ms ; arrêtée ou abandonnée, la faute est la même). *gauge measures* : le volet « large »
  garde l'horloge du jeu (`gaugeNanos = 5 ms`) pour exercer le chemin des trois mesures lentes ; chaque mesure est
  vérifiée **incomplète** (déterministe), et sa durée bornée à 5 ms + 500 ms. *gauge walks a cycle* : une mesure, complète,
  chaque objet une fois ; la borne « moins de 5 ms » est remplacée par cette preuve (le temps de la marche est imprimé).
- `HostBatteryTest` et `RewriteBatteryTest` : un cas est `timed` quand il porte sur le temps du compteur (`Loop`,
  `Swallow`, `FinallyLoop`, `CatchException`, `FlatMap`, `FlatMapReference`) : budget du jeu et, dans l'hôte, marge de
  500 ms. Les autres cas sont patients.
- `AbandonTest` : `attacked()` (start patient, attaque `attack {}` au budget **et à la marge du jeu** : 25 ms). Bornes
  du retard + 500 ms ; ajouts causaux : la cause est celle de l'abandon (y compris pour L2 dans sa JVM : `cause=`
  imprimé par `SharedStructureAttack`), et, pour la boucle d'`indexOf`, l'abandon est exigé dès qu'un `indexOf` seul
  dépasse le budget (75 ms ici ; mesuré dans un appel patient). Les fils perdus sont attendus jusqu'à 5 min (affamés,
  voir le tableau) ; la ligne du banc, jusqu'à 60 s.
- `ModuleThreadsTest` : 10 s pour un appel qui finit aussitôt (jamais `LATE`), 500 ms pour un appel à abandonner
  (assez pour que le fil le commence toujours ; les 20 + 5 ms du jeu sont dans `AbandonTest`), borne du retard + 500 ms.
- `RetainedGaugeTest` : horloge patiente, sauf le plafond de temps (1 ms), vérifié par « arrêté avant la fin » (comparé
  à la marche complète) et une borne de 1 ms + 500 ms.
- `InstrumenterTest` : le témoin réécrit, appels patients ; *its overhead is bounded* mesurait parfois « x Infinity »
  (un temps CPU lu nul : pas de 15,6 ms) : chaque mesure dure désormais au moins 300 ms de CPU, moyennée par appel.
- `MeterTest` : les deux appels « sains » armés 10 s. `EvilModulesTest` (M5-18) : `Keeper`, `ForName` et les liaisons
  sans la garde ont un `start` patient ; les cas `limit` gardent les budgets du jeu avec une marge de 500 ms (la
  boucle, la garde ou l'abandon les arrêtent, jamais une remise trop lente à commencer).
- `PipelineTest` : hôte patient (aucune faute attendue ; premier `start` à froid).

## 3. Le GC

`Hosts.collected(references, millis = 30_000)` attend que **tous** soient collectés, `System.gc()` toutes les 50 ms,
jusqu'à 30 s ; une vraie fuite échoue au bout de 30 s. Aucune attente de durée fixe ne reste.

## 4. Sondes (aucune commitée)

| Sonde | Ce qu'elle retire | Résultat |
|---|---|---|
| A | la fin du fil à `unload` (alors `threads.close()`, devenu `renew()`) | *unloading ends…* rouge toujours ; *twenty loaders* rouge sur JVM chaude |
| C | la faute de lenteur (`second()` rend toujours `null`) | *over 50 ms…* rouge (`expected SLOWNESS but was null`) |
| E | l'ensemble d'identité de la jauge (chaque objet revu) | *gauge walks a cycle* rouge ; `RetainedGaugeTest` *a cycle…* rouge |
| G | « jamais au-delà de ce qu'il reste au parent » (appel imbriqué armé pour tout son budget) | *nested* rouge |
| H | l'horloge de la jauge | `RetainedGaugeTest` *ceiling in time* rouge |
| F | l'abandon (l'appelant attend la fin) | `ModuleThreadsTest` : 4 tests rouges ; `AbandonTest` : 4 tests rouges (dont *a single long call…* par la cause, sa durée de 75 ms passant sous la borne : d'où les vérifications causales) |
| D′ | les `tick` de la boucle du `finally` de `FinallyLoop` (octets du module modifiés dans le test) | `HostBatteryTest` : abandon à 550 ms sans ligne, `assertTripped` rouge |
| I′ | `Guarded.add` remplacé par `Collection.add` dans `ListGrowth` (octets du module) | hôte et harnais patients : arrêt par l'allocation (16 Mio), `assertGuarded` rouge |

D′ et I′ modifient les octets du module dans un test jetable, pas `forge/rt` : retirer temporairement la protection de
`Meter`/`Guarded` (code de M5-18) m'a été refusé par la permission « Security Test Removal », et ce n'était pas mon
périmètre. `MeterTest` *a sound call is not tripped* n'a pas de sonde : la seule protection qu'il couvre (pas de faux
arrêt) est indépendante du budget, et un budget de 10 s n'en cache aucun (un arrêt par la profondeur ou l'allocation
reste visible, et une échéance mal calculée aussi).

## 5. GameTests

- `ModuleHostGameTests` : la `NullPointerException` venait de `called!!` quand `start`, coupé par l'horloge sous
  charge, avait déjà désactivé l'hôte. Les `start` sont patients ; chaque étape échoue par une assertion, jamais par
  `!!`. Les attaques de temps (boucle, `indexOf`, allocation) gardent le budget et la marge du jeu ; celles que la garde
  arrête (hachage partagé, liste qui grossit) ont 10 s, et doivent finir en `EXCEPTION` de la garde (`ForgeLimit: …`) ;
  la boucle d'`indexOf` doit être abandonnée (ou pas même remise à temps), jamais attendue. Bornes du tick + 500 ms.
- `ModuleEngineGameTests.aFaultyModuleIsDisabledAndItsCreaturesGo` : le moteur (M5-11) construit ses hôtes aux valeurs du
  jeu, sans crochet, et c'est voulu. Sous charge, la boucle arrêtée par son compteur peut être abandonnée (marge de
  5 ms), ce qui désactive dès la première faute. Le test accepte les deux chemins, chacun exactement : deux fautes et
  « time budget at Spinner.java: », ou un abandon (« …and was abandoned ») avec une ou deux fautes.
- `MechanicsCommandGameTests` : le message rapporté par M5-10 (« restore: 2 trap blocks taken away now; 3 in a chunk
  still loading », sans « for N blocks pending (expected …) ») est celui de l'**ancien** test. Vérifié dans l'historique :
  les commits de M5-10 antérieurs à `28d8990` (sa fusion de M4-LEFTOVERS) n'ont pas le correctif `ee0abdd`. Même cas que
  le point 4 de M4-LEFTOVERS, déjà corrigé ; rien de nouveau.

## 6. Le premier acte au banc (demande du superviseur, après M5-16)

M5-16 avait vu le premier `say` de `tag` (premier module de `ArsenalScenariosTest`) dépasser 25 ms sous charge : le
premier acte d'une JVM chargeait le chemin d'un acte **dans l'appel mesuré du module**. Lu dans un journal de chargement
des classes (`-Xlog:class+load`) pendant le premier passage de `tag` au banc : `JsonObjectBuilder`,
`JsonElementBuildersKt`, `StringOpsKt` (le JSON de l'acte et son texte), `Act`, `Act$Kind`, `ActQueue$Asked`,
`Scheduler$Due`, `ModelText`, `Guarded$Handles`, `Texts$Out`, `Meter$Nested` (le runtime), et côté banc `ActRecord`,
`DryCall`, `SimContext$BenchPending`, `SimActsKt`, `SimContextKt`, `BenchGame`.

- `ModuleHost.preload()` (`forge/host`) charge et initialise désormais aussi le chemin d'un acte et ces classes du
  runtime, et construit une fois le JSON d'un acte et son texte (`buildJsonObject {…}.toString()`).
- `BenchImpl.loadSdk()` (`forge/sim`, le préchargement du banc) construit une fois un `DryCall` et son texte, lit le
  statut d'un `ToolResult` (`ActRecord.statusOf`), et charge `SimContext$BenchPending`, `SimActsKt`, `SimContextKt`,
  `BenchGame`. Un nom faux ferait échouer l'initialisation du banc, donc tous les tests du banc : pas d'échec silencieux.

Mesuré (`ArsenalScenariosTest` seul, JVM neuve) : l'appel le plus lent de `tag` passe de **7,6 ms à 2,7 à 3,8 ms**
(trois JVM, machine calme), `simon_says` (second, déjà chaud) 0,5 à 0,7 ms. Sous la charge des builds des autres
agents : **6 passages sur 6 verts**, `tag` 2,6 à 3,6 ms (une fois 13,4 ms). Ce qui reste froid ne se précharge pas par
nom : les lambdas (classes cachées créées à leur première exécution), et le code encore interprété. Sous double
saturation (32 fils de calcul en plus), `ArsenalScenariosTest` échoue plus tôt, et pour une autre raison : `tag was not
built when the Forge started` — ECJ y dépasse sa limite de 10 s (`COMPILE_MS`, M5-02) au démarrage de la Forge. Ce n'est
pas le chemin des actes. Piste pour M5-10/M5-21 : un `forge_test` d'échauffement au démarrage de la Forge (hors du fil
serveur), qui chaufferait tout le banc, lambdas comprises, comme le fait déjà `ForgeServiceGameTests`.

## 7. Pour la suite

- **La marge de 5 ms, en jeu** : sur un serveur chargé, une boucle que le compteur arrête peut être abandonnée (1 sur
  100 mesuré sous double charge), et un abandon désactive dès la première faute au lieu de la deuxième ; son fil est
  perdu (il se termine au prochain code du module). C'est le choix de M5-05 (le serveur n'attend jamais plus de 25 ms) ;
  à garder en tête pour la recette.
- **`LATE` compte comme une faute *limite*** (`ModuleHost.call`), alors que la KDoc de `ModuleThreads.Ran.LATE` dit
  « nobody's fault » : sous forte charge, deux remises tardives dans les dix minutes désactiveraient un module sain. Non
  observé ici (0 sur 3 000 juste après un appel), possible quand le fil des modules s'est garé. Décision à prendre.
- **Le banc (M5-10, son point 3)** : les mesures ci-dessus l'éclairent. Un appel de quelques microsecondes peut prendre
  4 ms, un arrêt du compteur 7,5 ms de plus que le budget, et une JVM saturée a tenu une fois 2 s un appel de 75 ms de
  calcul : un banc qui juge chaque appel à 20 ms d'horloge murale verra de fausses lenteurs et de faux abandons sur une
  machine chargée. L'horloge CPU ne peut pas servir à cette échelle sous Windows (pas de 15,6 ms).
- **Un test qui héberge un module** suit `BusyMachine` : patience (`TIME_SCALE`) quand son sujet n'est pas le temps ;
  sinon budgets du jeu, vérifications de ce qui s'est passé, et `SLACK_MILLIS` sur toute borne d'horloge murale. Un
  test qui attend un fil perdu attend une condition, longtemps : un fil perdu est à la priorité minimale.

## Vérifications

- **Reproduction avant correction**, sous 32 fils de calcul : 6 JVM de deux passages des paquets `forge.host` et
  `forge.sandbox` donnaient de 3 à 8 échecs chacune (*over 50 ms* à chaque passage, *twenty loaders* à chaque second
  passage, `PipelineTest`, `HostBatteryTest`, les jauges, les bornes de `AbandonTest` et `ModuleThreadsTest`…), et sans
  aucune charge, *twenty loaders* échouait au second passage d'une même JVM.
- **Vingt passages sous charge, code final** (`e07c4a8`) : 10 JVM neuves de 2 passages chacune, pendant que 16 fils
  de calcul tournaient et que les builds d'autres agents (deux worktrees en `build` ou `test`) occupaient la machine. Classes :
  `ForgeServiceTest` en tête (l'ordre où M5-10 voyait *twenty loaders* rouge), puis `AbandonTest`, `HostBatteryTest`,
  `ModuleHostTest`, `ModuleThreadsTest`, `PipelineTest`, `RetainedGaugeTest`, `SdkBridgeTest`, tout `forge.sandbox`,
  tout `forge.rt`, `EvilModulesTest` : **125 tests × 20 = 2 500 exécutions, 0 échec**. `ForgeServiceTest` (M5-10, hors
  de cette tâche) a échoué 19 fois sur 220 : le banc juge ses appels à 20 ms d'horloge murale, et ECJ y dépasse parfois
  4 s (§7).
- Un passage antérieur, sous 32 fils de calcul, avait relevé les deux derniers défauts corrigés : l'attente du fil
  perdu du test du banc (plus d'une minute), et « x Infinity » dans `InstrumenterTest`.
- Sondes : §4. Reproduction sans la Forge : §1.
- `git merge main` quatre fois (M5-18 `ed2d256`, conflit dans `AbandonTest` résolu en gardant ses attaques ; M5-10
  `053e347` ; M5-16 `7da9584` ; M5-17 `6e9b92b`), sans autre conflit.
- **`.\gradlew.bat build`** sur l'état fusionné avec M5-16 : vert, 1 908 tests unitaires et « All 385 required tests passed ».
  Puis **sur l'état final, fusionné avec M5-17** (`6e9b92b`), pendant les builds d'autres agents : **vert**,
  **1 908 tests unitaires** (0 échec, 0 ignoré) et **« All 388 required tests passed »** (GameTests). Dans
  `ModuleHostGameTests` : la boucle tient le tick 20,1 ms (arrêtée par son compteur), l'`indexOf` 25,1 ms (abandonné),
  le hachage partagé 5,5 ms (la garde), la liste 0,6 ms, l'allocation 0,8 ms ; ticks de 6 à 39,8 ms.
- Aucun appel réel à Claude ; pas de `runServer`.
