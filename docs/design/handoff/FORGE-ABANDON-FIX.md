# Passation FORGE-ABANDON-FIX : les reprises comptées, l'attente jamais prise pour du calcul

Mission : appliquer le lot FORGE-ABANDON-FIX de la relecture adverse de FORGE-ABANDON
(`docs/design/reviews/FORGE-ABANDON_REVIEW.md`, constats 1, 2, 3, 7, 8) et la décision du superviseur sur le constat 4
(l'attente dans `Worker.ask` ne compte jamais comme du calcul du module). Branche `worktree-agent-a8c8b26549bd4f865`.
Fichiers : `forge/host/ModuleHost.kt`, `forge/host/ModuleThreads.kt`, `forge/sdk/ActQueue.kt`, `forge/rt/Meter.java`,
`mechanics/module/ModuleEngine.kt` (deux changements dans `Deployment`), leurs tests, et la passation `FORGE-ABANDON.md`
(phrases fausses). **En cas de contradiction avec FORGE-ABANDON, M5-LOAD ou M5-05, le code et cette passation font foi.**

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
  appel coupé pendant qu'il l'attend. `ModuleThreads.served` vaut aussi pour la dernière reprise.
- **Qui appelle `ModuleHost.busy()`** doit compter `resumedNanos` là où il compte le temps de ses appels, comme le moteur.
  Le banc ne reprend jamais.
- **Qui saute un appel dans une `ActQueue.invocation`** doit le dire à la file (`skipped()`), comme `Deployment.call`.
- **En jeu**, sur une machine chargée : un module dont les appels sont tenus et repris tick après tick montre maintenant ses
  vraies ms/s dans `/narrator forge` et dans la page des mécaniques. Il déclenche l'avertissement du tick lent, et il est
  désactivé pour **lenteur** au bout de cinq secondes, comme avant FORGE-ABANDON. Un module qui ne fait qu'attendre ses
  requêtes n'est jamais `limit … abandoned`.
- **La recette** : compter les `… resumed, was given up again` (`DEBUG`) suivis d'un abandon ; voir §2, point 4.
- La borne murale de `EvilModulesGameTests` (constat 5) est à FLAKY-6.

## Vérifications

- **Tests ciblés** (`ModuleThreadsTest`, `HeldBackTest`, `ActQueueTest`, `MeterTest`, `ModuleEngineTest`) : verts.
- **Sonde 0 et P1a à P5** : ci-dessus, chacune rouge sur sa vérification, puis l'arbre revenu au correctif.
- **Build complet** après la fusion de `main` (`a33adc5`, M6-16) : À REMPLIR.
- Aucun appel réel à Claude, pas de `runServer`, aucune charge ajoutée.
