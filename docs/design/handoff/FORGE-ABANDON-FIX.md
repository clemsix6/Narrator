# Passation FORGE-ABANDON-FIX : les reprises comptées, l'attente jamais prise pour du calcul, le `stop` jamais perdu

Mission : appliquer le lot FORGE-ABANDON-FIX de la relecture adverse de FORGE-ABANDON
(`docs/design/reviews/FORGE-ABANDON_REVIEW.md`, constats 1, 2, 3, 7, 8) et la décision du superviseur sur le constat 4
(l'attente dans `Worker.ask` ne compte jamais comme du calcul du module). Ajout au lot en cours de route, par décision du
superviseur : le `stop` perdu d'un retrait en rafale que FLAKY-6 a trouvé (`FLAKY-6.md` §5), voir §5 ci-dessous. Branche
`worktree-agent-a8c8b26549bd4f865`. Fichiers : `forge/host/ModuleHost.kt`, `forge/host/ModuleThreads.kt`,
`forge/sdk/ActQueue.kt`, `forge/rt/Meter.java`, `mechanics/module/ModuleEngine.kt` (deux changements dans `Deployment`),
leurs tests (`ModuleFixture` compris), et la passation `FORGE-ABANDON.md` (phrases fausses). **En cas de contradiction
avec FORGE-ABANDON, M5-LOAD, M5-05 ou FLAKY-6 §5, le code et cette passation font foi.**

## 1. Ce qui est corrigé

| Constat | Correctif | Où |
|---|---|---|
| **1 (important)** : le temps des reprises n'était compté ni dans le budget du tick, ni dans le `msPerSecond` affiché, ni dans la lenteur avant la fin de l'appel | Chaque reprise compte **au moment où elle a lieu**. Son temps servi entre tout de suite dans le travail de la seconde (`workThisSecond`), donc dans la lenteur. Son temps mural va dans `spentThisSecond` et dans le nouveau `ModuleHost.resumedNanos` (remis à zéro à chaque `busy()`), que `Deployment.busy()` ajoute à `d.spent` : budget du tick et `ModuleView.msPerSecond`. Le temps servi de l'appel d'origine compte aussi à son lâcher, plus à son règlement (`Given.served` retiré) ; le règlement n'ajoute plus que le travail propre du module (`max(worked, cpu)`). La KDoc de `ModuleHost.msPerSecond` ne renvoie plus à tort à `ModuleView.msPerSecond` | `ModuleHost.resume`, `call`, `settle`, `busy` ; `ModuleEngine.Deployment.busy` |
| **2** : au déchargement, un fil tenu garé sur sa requête était compté perdu (trois d'un coup pouvaient bloquer la Forge) | `unload()` lit `held.asking` **avant** la coupure, révoque le compteur **avant** `cut`, et n'appelle `lose` que si le fil n'attendait pas une requête. Refusé, ce fil se dénoue sans exécuter de code du module : chaque gestionnaire d'exception tique, et le compteur révoqué lève. Il n'a rien calculé. Un fil qui calcule, ou que la machine retient hors d'une requête, reste compté perdu comme avant. Même ordre (révoquer puis couper) dans `abandoned()` et pour un démarrage `stalled` | `ModuleHost.unload`, `abandoned`, `givenUp` |
| **3** : le reste d'un appel repris gagnait 4 actes après une invocation sautée | Quand l'hôte saute l'appel, `Deployment.call` le dit à la file : `ActQueue.skipped()` rend à `inCall` sa valeur d'avant l'invocation (`lastCall`). Un appel sauté n'a rien demandé ; le compte reste celui de l'appel tenu | `ActQueue.invocation`, `skipped` ; `ModuleEngine.Deployment.call` |
| **4 (décision)** : l'attente active dans `Worker.ask` comptait comme du calcul (sonde E : abandonné au bout de 3 s sans rien calculer) | **Les deux à la fois.** (a) `ask` encadre son attente par `Meter.waitStarts()` et `Meter.waited(started)` : le temps CPU passé à attendre fait avancer `cpuAtArm` (et `cpuAtTrip`) d'autant. Il sort donc de toute preuve de calcul : `overTime` et `disarm` du compteur, `Meter.cpu()` (le travail de l'appel), `ModuleHost.computing` (abandon, patience). (b) Après sa courte attente active (200 µs : une requête est servie en une microseconde), le fil se gare **sans délai** jusqu'à être servi, au lieu de se réveiller par tranches de 100 µs. (a) est la garantie (« jamais ») ; (b) réduit le gaspillage | `Worker.ask` ; `Meter.waitStarts`, `waited` |
| **7** : `cut` ne coupait qu'un appel `HELD` | `cut` coupe un appel `HELD` **ou** `RESUMED`. `attend` rend `ABANDONED` aussitôt si l'appel a été coupé pendant qu'il l'attend : sinon une reprise attendrait sans fin un appel coupé, dont l'état n'est plus jamais `DONE` et que le `compareAndSet` ne peut plus lâcher | `ModuleThreads.cut`, `attend` |
| **8** : deux phrases fausses dans la passation | Corrigées dans `FORGE-ABANDON.md`, en italique, avec mention de ce correctif : §2 « Ils comptent dans les 4 actes de l'appel » ; §3 « Le temps d'une reprise compte dans `msPerSecond`… ». J'ai aussi corrigé la phrase visée par le **constat 6** (« c'était sa seule cause possible ») et deux lignes devenues inexactes (§3 « Au plus 2 fils perdus », §6 « Le moteur ») | `docs/design/handoff/FORGE-ABANDON.md` |

Pas traités ici : le **constat 5** (borne murale de `EvilModulesGameTests`), qui revient à FLAKY-6 ; le **constat 6**, accepté
tel quel par la relecture (un acte demandé par le `<clinit>` d'un appel de jauge rejoint `late`), dont seule la phrase de la
passation est corrigée.

## 2. Décisions et écarts

1. **`ActQueue.skipped()` plutôt qu'un `ModuleCall.run` qui renvoie un booléen** (la proposition de la relecture). Changer la
   signature de `ModuleCall` touchait `forge/sim/BenchImpl.kt` et `BenchTest`, hors de ma liste. Le moteur appelle donc
   `queue.skipped()` dans `Deployment.call`, dans l'invocation en cours (`check` sinon). Le `ModuleCall` du banc ne
   l'appelle pas : au banc, un appel sauté (`OVERLOADED`) fait échouer le banc.
2. **Le temps servi de l'appel d'origine compte à son lâcher**, pas à son règlement : c'est le même principe que pour les
   reprises (« au moment où il a lieu »), un pas plus loin que la proposition de la relecture.
3. **La preuve CPU juge toujours depuis l'armement**, attentes retirées. La piste de la relecture « juger depuis le dernier
   lâcher » est écartée : elle aurait laissé un module enchaîner sans fin des étapes du JDK de 24 ms, une par reprise. Il
   tiendrait le thread serveur 25 ms à chaque tick sans jamais réunir 40 ms de preuve.
4. **Le constat 4 (a) n'est pas traité** : il n'est pas dans la décision. Sous Windows, l'horloge CPU avance par pas de
   15,6 ms ; sur un appel tenu étalé sur plusieurs reprises, trois pas peuvent tomber sur 10 à 15 ms de vrai calcul,
   attente déjà exclue. C'est raisonné, rare (il faut un appel tenu, donc une machine chargée, et un gros calcul), et
   reste à mesurer en recette : journal `resumed, was given up again`, puis `abandoned`.
5. **Appels imbriqués** : une attente pendant un appel imbriqué ne serait pas rendue à l'appel qui le porte, car `unnest`
   restaure ses bases. Ça n'arrive jamais : seul le banc imbrique, et son SDK est servi sur place, jamais par `ask`. Rien
   n'est ajouté pour ce cas.
6. **`docs/ARCHITECTURE.md`, principe 3 : inchangé.** Le principe ne change pas : un appel tenu est repris « au plus son
   budget à chaque fois », et le compte suit désormais ce que le principe dit déjà.

**Coût** : deux lectures de l'horloge CPU du fil par requête du SDK, soit environ 0,6 µs (315 ns par lecture, mesure de
M5-LOAD), seulement dans un appel compté. Hors appel compté (les tests de `ModuleThreads`), `waitStarts` rend `UNARMED`
sans rien lire.

## 3. Tests ajoutés (déterministes)

Le lâcher est provoqué comme dans FORGE-ABANDON : un service lent sur le thread de l'appelant, jamais la charge. Les
sondes du relecteur (`ForgeAbandonProbeTest`, hors dépôt) sont devenues :

| Test | Constat | Sur le code d'avant (sonde 0) |
|---|---|---|
| `ModuleEngineTest` › *a call held and resumed at each tick counts at each tick - the tick's budget, its milliseconds a second, its slowness* (sonde A : 30 lectures de 30 ms par appel) | 1 | rouge : aucune faute en 8 s |
| `ModuleEngineTest` › *the rest of a call given up keeps the call's four acts, though a fact came meanwhile* (sonde F) | 3 | rouge : `four acts a call: [hud × 8]` |
| `ActQueueTest` › *the rest of a call given up keeps the call's four acts across a call its host skipped* | 3 | API nouvelle (`skipped`) : voir P3 |
| `HeldBackTest` › *a module unloaded while its held call waits for the server loses no thread, and three at once leave the Forge running* (sonde D) | 2 | rouge : trois `WARN a module thread was abandoned past its budget` |
| `HeldBackTest` › *a held call that waits for a thousand slow SDK requests never computed, however long its thread spun waiting* (sonde E : 1 000 requêtes de 300 µs, soit environ 200 ms de CPU passés à attendre) | 4 | rouge : `time budget: one call ran past 25 ms and was abandoned` |
| `MeterTest` › *the CPU time a call waits for its caller is never its computing* (80 ms de CPU entre `waitStarts` et `waited`) | 4 | API nouvelle : voir P5 |
| `ModuleThreadsTest` › *a call cut while it is resumed is given up at once, and the SDK refuses it from then on* (la requête servie coupe l'appel) | 7 | rouge : `expected <ABANDONED> but was <DONE>` |
| `ModuleThreadsTest` › *a held call given up again as it is resumed stays held, and a later resume runs it to its end* | couverture (relecture, Q6) | vert (pas un défaut) |
| `HeldBackTest` › *a held call that computes once resumed is abandoned at its host's next check* (un `indexOf` sans fin après la reprise) | couverture : l'exclusion des attentes ne cache pas un vrai calcul | vert (pas un défaut) |

Les tests de `HeldBackTest` sont **ajoutés** à la fin de la classe (consigne : FLAKY-6 y travaille), avec deux aides
locales (`awaitParked`, `Serving`). Le test du déchargement attend que chaque fil tenu soit vraiment garé sur sa requête :
son module a posé `asking` juste avant de demander, et le fil est `WAITING`. Sans cela, le fil pourrait être encore en
train de revenir de sa première requête.

### Sondes « retirer le correctif → rouge » (toutes retirées, l'arbre revenu au commit du correctif par `git checkout`)

| Sonde | Ce qui est retiré | Rouges |
|---|---|---|
| 0 | les cinq fichiers de production remis à `bf145b6` (avant le correctif) ; `ActQueueTest` et `MeterTest` aussi (leurs nouveaux tests demandent la nouvelle API) | 5 sur 7 : les cinq marqués « rouge » ci-dessus, chacun sur sa vérification ; les deux tests de couverture restent verts |
| P1a | `resume()` n'ajoute plus le temps servi au travail de la seconde | sonde A : `expected <[SLOWNESS]> but was <[]>` (la vue montre 615 à 623 ms/s : le correctif de la vue tient seul) |
| P1b | `Deployment.busy()` n'ajoute plus `resumedNanos` à `d.spent` | sonde A : `its milliseconds a second: [32.7, 31.0, 0.0, 31.1]`, les chiffres exacts de la relecture |
| P2 | `unload()` perd le fil même garé sur sa requête | le test du déchargement : trois `WARN … abandoned past its budget` |
| P3 | `ActQueue.skipped()` ne rend pas le compte | `ActQueueTest` (6 `hud` au lieu de 5) et `ModuleEngineTest` sonde F (8 au lieu de 4) |
| P4 | `cut` ne coupe qu'un appel `HELD` | `ModuleThreadsTest` : `expected <ABANDONED> but was <DONE>` |
| P5 | `Meter.waited` ne rend rien (attente garée gardée) | `MeterTest` (`moved past the wait: 0 ns`) et le test des mille requêtes (abandonné) : l'exclusion est nécessaire même avec le fil garé |

Non sondés, faute de test déterministe : l'ordre « révoquer puis couper » (un écart de quelques nanosecondes) ; la sortie
d'`attend` sur un appel coupé (sans elle, avec `cut` corrigé, le test P4 boucle sans fin au lieu d'échouer : raisonné, pas
lancé).

## 4. Ce que les tâches suivantes doivent savoir

- **API nouvelle** : `ModuleHost.resumedNanos`, `ActQueue.skipped()`, `Meter.waitStarts()` et `Meter.waited(long)` (statiques,
  pour le code de l'hôte sur le fil du module ; un module ne peut pas les nommer : `Instrumenter` refuse tout code de
  module qui nomme `forge.rt`). `ModuleThreads.cut` accepte un appel en cours de reprise ; `resume` rend `ABANDONED` pour un
  appel coupé pendant qu'il l'attend, et prend une `Patience` facultative (§5). `ModuleThreads.served` vaut aussi pour la
  dernière reprise. `ModuleThreads(…, onStart)` est un crochet de test (§5). `ModuleHost.stop` est patient (§5).
- **Qui appelle `ModuleHost.busy()`** doit compter `resumedNanos` là où il compte le temps de ses appels, comme le moteur.
  Le banc ne reprend jamais.
- **Qui saute un appel dans une `ActQueue.invocation`** doit le dire à la file (`skipped()`), comme `Deployment.call`.
- **En jeu**, sur une machine chargée : un module dont les appels sont tenus et repris tick après tick montre maintenant ses
  vraies ms/s dans `/narrator forge` et dans la page des mécaniques. Il déclenche l'avertissement du tick lent, et il est
  désactivé pour **lenteur** au bout de cinq secondes, comme avant FORGE-ABANDON. Un module qui ne fait qu'attendre ses
  requêtes n'est jamais `limit … abandoned`.
- **La recette** : compter les `… resumed, was given up again` (`DEBUG`) suivis d'un abandon ; voir §2, point 4.
- La borne murale de `EvilModulesGameTests` (constat 5) a été traitée par FLAKY-6.

## 5. Ajout au lot : un `stop` n'est jamais perdu en silence (FLAKY-6 §5)

**Le défaut** (FLAKY-6, sonde G : 910 retraits sur 2 000 sous charge) : `unload()` du premier module d'un retrait en rafale
(`forge off`, deux arrêts de suite, l'arrêt du serveur) démarre un fil des modules neuf (`renew`, FLAKY-4). Le `stop` du
second lui est remis quelques microsecondes plus tard, avec le budget d'un appel (25 ms) et sans patience. Si la machine
ne lance pas ce fil à temps, l'appel est `LATE` : sauté, journalisé en `DEBUG`, jamais redemandé (le déploiement est déjà
retiré). Ce qu'un module écrit dans son stockage pendant `stop(SERVER_STOPPING)` pour sa reprise était perdu. Deux cas
voisins : un `stop` lâché pendant que le thread serveur sert sa requête était tenu, puis coupé par `unload` (à moitié
fait, sonde H de FLAKY-6) ; et un `stop` demandé pendant qu'un autre appel du module était tenu était sauté sur-le-champ,
puis l'appel tenu coupé (passation FORGE-ABANDON §6 : « `stop` reste sauté tant qu'un appel est tenu »).

**Le correctif retenu**, le plus simple des trois proposés : **le `stop` a la patience d'un démarrage** (`ModuleHost.stop`).

- Son appelant l'attend au-delà de son budget **tant que son fil n'a pas calculé** (`computing`, la preuve CPU, attentes
  exclues), `STARTUP_PATIENCE_NANOS` (2 s) au plus. Un fil neuf que la machine tarde à lancer, ou un `stop` dont la
  requête est servie lentement, va donc au bout.
- **Un appel tenu est attendu d'abord**, dans la même patience (`resume(until)` : une reprise patiente, qu'il demande ou
  non quelque chose au serveur ; `ModuleThreads.resume` reçoit pour cela la `Patience` que `attend` savait déjà gérer). Le
  `stop` vient après lui, jamais à sa place. Le temps restant de la patience passe ensuite au `stop`.
- **Perdu malgré tout, il l'est avec un `WARN`**, jamais en silence. S'il calcule au-delà de sa preuve : abandonné, une
  faute *limite* (`… and was abandoned`, `WARN … disabled`), comme tout appel. Retenu au-delà de sa patience sans
  calculer : `WARN its stop was given up at N ms … (nobody's fault)`, ou `its stop does not run: the module thread did not
  start it within N ms`, ou `its stop does not run: a call given up before still runs past its patience`. Dans ces trois
  cas, il n'y a **pas de faute** : la garantie « une faute seulement avec preuve de travail » l'emporte.
- **Borne pour le thread serveur** : au plus une patience de démarrage (2 s) par module retiré, partagée entre l'appel tenu
  et le `stop`, plus deux budgets d'appel. Elle ne s'épuise que si la machine retient le fil du module sans qu'il calcule.
  À l'arrêt du serveur, cela fait au pire 2 s par module (`forge.maxActive` : 4 par défaut, 8 au plus). C'est la borne
  qu'un démarrage a déjà à la remise en route. Dans le cas courant (le fil neuf lancé en retard), l'attente est le retard du
  fil : 40 ms dans le test. **Point à trancher par le superviseur si cette borne paraît trop large** : une échéance commune
  à toute la rafale (dans `ModuleEngine.stopping`/`halt`), que je n'ai pas faite pour ne pas toucher davantage au moteur.
- **Rien d'autre ne change** : le fil est toujours renouvelé à chaque déchargement (FLAKY-4), le moteur n'est pas modifié
  pour ce point, et un appel sauté ordinaire reste rejoué au tick suivant.

**Un crochet de test** : `ModuleThreads(…, onStart: () -> Unit = {})` s'exécute au début de chaque fil des modules neuf.
Les tests y retiennent le fil, comme une machine chargée tarde à le lancer : c'est la sonde G de FLAKY-6, rendue
déterministe sans toucher au code de production. Le jeu garde le défaut. `ModuleFixture` reçoit son `ModuleThreads` en
paramètre (défaut inchangé).

| Test | Cas | Avant ce correctif (sonde S0 : `ModuleHost.kt` remis à `8aebda6`) |
|---|---|---|
| `ModuleEngineTest` › *each module the server stops gets its stop, though each new module thread starts late* (trois modules, `stopping()`, chaque fil neuf lancé 40 ms en retard) | fil neuf en retard | rouge : `expected <[SERVER_STOPPING × 3]> but was <[SERVER_STOPPING, null, null]>` |
| `HeldBackTest` › *a stop held back past its 25 ms is waited for, and runs to its end* | `stop` lâché, puis coupé | rouge : `never given up` |
| `HeldBackTest` › *a stop asked while a call given up still runs comes after that call, never instead* | `stop` sauté derrière un appel tenu | rouge : `the stop ran` |
| `HeldBackTest` › *a stop held back past its patience is lost with a warning, nobody's fault* (requête servie en 2,1 s) | perte explicite | rouge : aucun `WARN` |
| `HeldBackTest` › *a stop its module thread does not even start within its patience is lost with a warning* (fil neuf lancé 2,1 s en retard) | perte explicite | rouge : aucun `WARN` |

**Sonde G de FLAKY-6, rejouée** (le `ModuleThreads` de `ModuleFixture` retenu 40 ms au départ de chaque fil neuf ; sonde
retirée ensuite) : sur l'ancien hôte, les deux tests de `ModuleBookTest` échouent exactement comme FLAKY-6 l'a vu
(`each module's stop(FORGE_OFF) ==> expected: <2> but was: <1>`, et la ligne `C-2 greeter: stop STOPPED` absente) ; avec le
correctif, les 6 tests de `ModuleBookTest` et tout `ModuleEngineTest` passent. `ModuleBookTest` n'est pas modifié.

## Vérifications

- **Tests ciblés** (`ModuleThreadsTest`, `HeldBackTest`, `ActQueueTest`, `MeterTest`, `ModuleEngineTest`,
  `ModuleBookTest`, `ModuleHostTest`, `AbandonTest`) : verts.
- **Sonde 0, P1a à P5, S0 et G** : ci-dessus, chacune rouge sur sa vérification, puis l'arbre revenu au correctif.
- **Premier lot (sans le §5)** : build complet après la fusion de `main` (`a33adc5`) : 2 239 tests unitaires, 0 échec,
  0 ignoré ; « All 398 required tests passed ». Puis un `runGameTest` : 2 échecs sans rapport avec la Forge, dans le lot
  `director`, qui fait tourner ses deux tests en même temps. Le nettoyage de l'un recharge la configuration (« LLMGW key
  missing », le Directeur `paused → disabled`) pendant que l'autre tourne (`a paused Director sends nothing`), et le
  Directeur reste en pause pour `forge_e2e`, le lot suivant (`timed out waiting for: a quiet Director`, avant tout module).
  Relancé seul : « All 398 required tests passed ». Après la fusion de `c8b716c` : 2 254 tests unitaires, 0 échec ; « All
  398 required tests passed » ; `runGameTest` : « All 398 required tests passed ».
- **Build final** (avec le §5, après la fusion de `0631ef5`, FLAKY-6) : **2 259 tests unitaires, 0 échec, 0 ignoré** ;
  **« All 398 required tests passed »**. Puis **`runGameTest` seul** : « All 398 required tests passed ». Aucun `WARN`
  de `stop` perdu dans ces journaux.
- Aucun appel réel à Claude, pas de `runServer`, aucune charge ajoutée.
