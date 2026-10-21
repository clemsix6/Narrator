# Passation FORGE-ABANDON : un appel lâché sans faute n'est plus jamais à moitié fait

Mission : corriger le défaut de production de la Forge trouvé par FLAKY-5 (§4 et §8 de sa passation) : sous charge, un
appel lâché « sans faute » perdait la suite de son travail, et le module restait en vie sans suite jusqu'à son échéance.
Second point : `ModuleHost.settle` jugeait « abandonnée » une boucle déjà arrêtée par son compteur pendant qu'elle se
dénouait (FLAKY-5 §6). Branche `worktree-agent-ace117a65d8c2d699`. Fichiers : la Forge (`forge/host`, `forge/sdk`,
`forge/rt/Meter.java`), ses tests, et `docs/ARCHITECTURE.md` (principe 3 de la Forge).
**En cas de contradiction avec M5-05, M5-LOAD, `M5.md` §3.7 ou `M5_REVIEW.md`, le code et cette passation font foi.**

## 1. La cause exacte

Ce que FLAKY-5 a vu (`C-191 password_race`) : le joueur écrit « Lanterne » ; l'appel du fait demande un titre et
`game.win(…)` (servis par le thread serveur), puis le thread serveur atteint son échéance (25 ms murales, 32 ms lues) et
lâche l'appel, dont le fil n'a pas calculé (retenu par la machine). Depuis M5-LOAD :

1. `ModuleThreads.run` passait l'appel à `ABANDONED` ; toute requête du SDK suivante de ce fil levait `ForgeLimit`
   (`Worker.ask` : `state != RUNNING`). Le `last.then(o -> ctx.end(…))` de `heard` était donc **refusé** : `ForgeLimit` dans
   le code du module, qui se dénoue.
2. `ActQueue.invocation` envoyait quand même les actes demandés **avant** le lâcher (le titre, la victoire : défi gagné,
   enjeux payés).
3. `ModuleHost.settle` réglait l'appel une fois son fil fini : pas de faute du compteur, « nobody's fault » ; l'exception
   `ForgeLimit` du module n'est pas lue. Le module, `over = true`, n'avait plus rien à faire : il ne finissait jamais.

Ce n'est pas propre à `then` : **toute** requête du SDK après le lâcher était perdue (un acte, une écriture du stockage,
`ctx.end`, un minuteur), alors que celles d'avant avaient pris effet. `ModuleEngineTest` le montrait déjà sans le voir :
« a module the machine holds back… » vérifiait que l'écriture de l'appel lâché était perdue (`{ vite=lu }`).

## 2. Le choix : l'appel lâché est tenu, puis repris

**Rejeté : tout ou rien avec rejeu** (les actes d'un appel lâché ne partent pas, l'appel est rejoué comme un `LATE`). Un
`LATE` n'a rien exécuté ; un appel lâché, si : il a changé l'état Java du module (`over = true`) et l'état du SDK, sur le
champ (`display.cancel()`, un abonnement, le stockage, une créature pilotée). Ni l'un ni l'autre ne se défait. Rejoué,
`heard` revoit `over == true` et rend la main : le défi n'est plus même gagné, le module ne finit toujours pas. Rejouer un
appel à moitié exécuté est incohérent par nature.

**Rejeté : honorer seulement les suites enregistrées avant l'abandon.** Le `then` de FLAKY-5 vient **après** ; et toute
requête du SDK doit tourner sur le thread serveur (objets du SDK confinés, monde lu et écrit par lui seul).

**Retenu : l'appel lâché sans preuve de travail est tenu, et le thread serveur le reprend.** Le seul « tout » possible :
l'appel va à sa fin, chaque requête du SDK servie sur le thread serveur, dans l'ordre, simplement plus tard.

- `ModuleThreads.run(…, hold = { … })` : au-delà de l'échéance, l'appel passe à `HELD` (au lieu d'`ABANDONED`) et l'appelant
  reçoit un `ModuleThreads.Held`. Son fil continue ; une requête du SDK qu'il fait **attend** (le fil se gare), jamais
  refusée, jamais servie dans le dos de l'appelant.
- `ModuleThreads.resume(held, budget)` : l'appelant attend de nouveau l'appel, au plus `budget`, en servant ses requêtes
  (la même boucle que `run`, `attend`) ; `DONE` à sa fin, `ABANDONED` s'il est lâché de nouveau (toujours tenu).
- `ModuleThreads.cut(held)` : l'appel passe à `ABANDONED`, sa requête en attente et toutes les suivantes lèvent `ForgeLimit`.
- `ModuleHost.busy()` (le moteur l'appelle au début du tick de chaque déploiement) règle l'appel, puis le **reprend s'il
  demande quelque chose au SDK** (`Held.asking`), au plus son budget + 5 ms, puis le règle de nouveau. Un appel tenu qui
  calcule, ou que la machine retient entre deux requêtes, n'est pas attendu : le thread serveur ne paie que le service.
- **Le tour d'une reprise est celui de l'appel** : après une reprise, `busy()` répond `true` pour ce tick même si l'appel a
  fini. Le moteur (non modifié) prend alors sa branche « occupé » : `after(d)` applique ce que l'appel a demandé (un
  `ctx.end`, une désactivation) **avant** tout autre appel du module, qui attend le tick suivant. Sans cela, un `ctx.end`
  demandé pendant la reprise laissait courir les faits et minuteurs du même tick avant la fin (vu : `heard encore`). Le
  thread serveur ne donne aussi à ce déploiement, ce tick-là, que la reprise (25 ms au plus).
- `ActQueue` : un acte demandé entre deux invocations (le reste d'un appel repris) rejoint `late` ; ces actes partent
  **ensemble**, comme un acte du moteur, avant ceux de l'invocation suivante (`invocation`, `deliver`), c'est-à-dire une fois
  l'appel fini (le moteur ne fait aucun autre appel du module tant qu'il est tenu) ; à la fermeture, ils sont `dropped`
  comme les autres. Ils comptent dans les 4 actes de l'appel (`inCall` n'est remis à zéro qu'à l'invocation suivante).
  L'ancienne `IllegalStateException` « asked an act outside one of its calls » disparaît : c'était sa seule cause possible.

Résultat pour `password_race` : le titre et la victoire partent à la fin de l'invocation ; au tick suivant, le moteur
reprend l'appel, sert le `then` ; l'issue de la victoire revient, son rappel appelle `ctx.end`, le module finit
`fulfilled`, le Directeur est prévenu.

## 3. Ce qui est préservé (les garde-fous de M5)

| Garantie | Comment |
|---|---|
| Un fil perdu n'agit plus jamais sur le monde | perdu = abandonné (il a calculé) ou module déchargé : `abandoned()` et `unload()` appellent `cut` avant `lose` ; la requête en attente et les suivantes lèvent `ForgeLimit`, le compteur est révoqué comme avant. Un fil **tenu** n'agit sur le monde que par le thread serveur, pendant une reprise, comme pendant l'appel lui-même |
| Au plus 2 fils perdus | inchangé : seul un abandon (ou un déchargement) perd un fil. Un fil tenu n'est pas compté, comme le fil « qui finit seul » d'avant ; au plus un par déploiement (le module attend) |
| Faute seulement avec preuve de travail | inchangé : `settle`, `computing`, le compteur. La reprise n'ajoute aucune faute |
| Rien ne bloque le thread serveur | une reprise attend au plus le budget d'un appel (25 ms), seulement quand le fil tenu a posté une requête ; sinon 0. Le temps d'une reprise compte dans `msPerSecond` et le temps servi dans le travail de la seconde (lenteur) |
| Un démarrage retenu (`stalled`) | inchangé : coupé (`cut`), le déploiement finit `disabled` sans faute |
| Le banc | inchangé en pratique : un appel lâché après 10 s de patience est tenu, le banc échoue (`OVERLOADED`) et décharge l'hôte, qui le coupe |

Le banc et les appels imbriqués : un appel imbriqué dans un appel tenu reste refusé (`ForgeLimit`, `Call.attended()` : il
n'est servi que pendant que l'appelant l'attend) ; seul le banc imbrique, et il ne reprend jamais.

**Un appel tenu doit être repris ou coupé** : son fil se gare sans délai sur sa requête. En production, chaque hôte est
déchargé (fin du déploiement, arrêt du serveur, fin du banc) et `unload` coupe. Un test qui laisse un appel tenu sans
`Hosts.settled` ni `unload` laisse un fil démon garé.

## 4. Le second point : la boucle arrêtée qui se dénoue

Cause (FLAKY-5 §6) : `computing` comptait le temps CPU du fil depuis l'armement ; sous contention, l'horloge CPU de
Windows (pas de 15,6 ms imputés au fil qui tourne à chaque interruption) lit 40 ms ou plus pour 20 ms de travail découpé.
Une boucle que son compteur a arrêtée et qui se dénoue après l'échéance de l'appelant était alors jugée « abandonnée »
(désactivation immédiate, fil perdu) au lieu de la faute *limite* de son compteur (la première ne désactive pas).

Correctif (simple et sûr) : `Meter` note l'horloge CPU au moment où il arrête l'appel (`cpuAtTrip`, lu par l'hôte comme
`cpuAtArm`) ; `computing` compte **depuis l'arrêt** quand il y en a eu un. Sûr parce qu'un arrêt est collant : après lui,
chaque `enter` et chaque `tick` du module relève `ForgeLimit` (y compris à l'entrée d'un gestionnaire d'exception) ; aucun
code du module ne tourne plus, il ne peut plus lancer d'appel du JDK. Plus prudent que « un appel arrêté n'est jamais
jugé » : un fil qui calculerait encore 40 ms après l'arrêt (cas que je ne sais pas construire) serait toujours abandonné.
`Meter.Nested` gagne le composant `cpuAtTrip` (sauvé et rendu autour d'un appel imbriqué).

## 5. Tests

Déterministes : ni charge ni attente d'un temps réel pour que le défaut se montre. Le lâcher est forcé par un crochet qui
retient **l'appelant** pendant qu'il sert une requête (60 ms, au-delà des 25 ms) : le fil du module n'a pas calculé, l'appel
est forcément lâché sans faute. Un `LATE` (le fil pas même démarré, sous charge) est rejoué et retrouve le crochet intact.

| Test | Défaut | Avant le correctif |
|---|---|---|
| `ModuleEngineTest` › *a call given up between its act and its then still ends its module - FLAKY-5's password race* (nouveau) : `say` demandé, lecture du monde retenue 60 ms (`ModuleFixture.Server.heldOnce`, nouveau), puis `sayTo(…).then(o -> m.end(…))` | 1 | rouge : `timed out waiting for: C-1 ended` (le symptôme exact de FLAKY-5) |
| `ModuleEngineTest` › *a module the machine holds back…* (mis à jour : l'écriture de l'appel lâché n'est plus perdue) | 1 | rouge : `expected <{lent=lu, vite=lu}> but was <{vite=lu}>` |
| `HeldBackTest` › *a call given up after the caller was held serving it is resumed…* (nouveau, au niveau de l'hôte) | 1 | rouge : `expected <[asked, then]> but was <[asked]>` |
| `ActQueueTest` › *the rest of a call given up and resumed by its host acts all the same…* (nouveau) | 1 | rouge : `IllegalStateException: C-70 asked an act outside one of its calls` |
| `ModuleEngineTest` › *a call given up that asks its end once resumed ends its module before any other call* (nouveau) : lecture retenue, puis `m.end` ; un second fait attend | 1 (tour de la reprise) | sans la réponse `true` après une reprise (sonde P5) : `heard encore` après la fin demandée ; sur le code d'avant, la fin même est refusée |
| `ModuleThreadsTest` › *a call given up and held asks the SDK only when its caller resumes it…*, *a held call cut is refused…* (nouveaux) | 1 (API) | ne compilent pas (API nouvelle) |
| `HeldBackTest` › *a loop its meter stopped, whose thread's CPU clock reads past the evidence as it unwinds, is one limit fault* (nouveau) | 2 | rouge : `time budget: one call ran past 35 ms and was abandoned` |

Le test du second point : la boucle a un budget de 30 ms (elle lit alors un ou deux pas de l'horloge CPU avant son arrêt,
jamais trois), puis, après l'arrêt et hors du code du module, son fil calcule jusqu'à la preuve **depuis l'armement**
(`Meter.cpuAtArm()` du compteur, 40 ms) et reste vivant 200 ms. Depuis l'armement : abandon ; depuis l'arrêt : deux pas au
plus, sa faute *limite*. Avec 20 ms de boucle, l'arrêt lisait de 0 à 2 pas : non déterministe (vu une fois rouge avec le
correctif) ; d'où 30 ms.

### Sondes (« retirer le correctif → rouge »), toutes retirées, l'arbre vérifié identique ensuite (`cmp` du diff)

| Sonde | Ce qui est retiré | Rouges |
|---|---|---|
| 0 | les quatre fichiers de production et `ModuleThreadsTest` remis à `HEAD` (le code d'avant) | 5 : les quatre tests 1 ci-dessus et celui du point 2, chacun sur sa vérification |
| P1 | `busy()` sans `resume()` | la course au mot de passe (`timed out waiting for: C-1 ended`) — seul lancé : les autres attendraient 300 s un appel jamais repris |
| P2 | `computing` compte depuis l'armement | le test du point 2 (`… was abandoned`) |
| P3 | `ActQueue.ask` refuse un acte hors d'une invocation | `ActQueueTest` (`asked an act outside one of its calls`) |
| P4 | `Worker.ask` refuse une requête d'un appel tenu (l'ancien mécanisme) | `HeldBackTest` (`[asked]`), `ModuleEngineTest` × 2 (`{vite=lu}`, `timed out`) |
| P5 | `busy()` ne répond plus `true` après une reprise | `ModuleEngineTest` (`heard encore` après la fin demandée) |

La sonde 0 a tourné avant l'ajout du test de P5 (écrit ensuite) ; sur le code d'avant, celui-ci ne peut qu'échouer (la
requête `end` y est refusée, `ending()` jamais rempli).

## 6. Ce que les tâches suivantes doivent savoir

- **Signatures** : `ModuleThreads.run(…, lose: Boolean)` devient `run(…, hold: ((Held) -> Unit)?)` (seul `ModuleHost` le
  passait). Nouveaux : `ModuleThreads.Held` (`thread`, `ended`, `asking`), `resume`, `cut`. `Meter.cpuAtTrip()`.
  `ModuleThreads.Call`, `Phase` et `Request` sont `internal` (exposés par `Held`).
- **Journal** : `its <call> call was given up at N ms, its thread held back; it runs on, resumed when it asks the server, the
  module waiting for it` (`INFO`), puis `… ended without computing past its budget … nobody's fault` comme avant. Une
  reprise lâchée de nouveau : `DEBUG` (`… resumed, was given up again`).
- **Le moteur** (`ModuleEngine`, non modifié) : son `d.spent` (avertissement du tick lent) ne compte pas le temps d'une
  reprise, que l'hôte compte dans `msPerSecond`. `stop` reste sauté tant qu'un appel est tenu (comme avant), puis
  `unload` le coupe. Les actes du reste d'un appel repris partent au tick qui suit sa fin (`deliver` ou l'invocation
  suivante) ; comme tout acte encore en file, ils sont `dropped` si l'appel a demandé `ctx.end` (règle de `sdk.md` :
  finir dans le rappel du dernier acte, ce que fait `password_race`).
- **`replay()`** appelle `busy()` après chaque appel rejoué : un appel rejoué et lâché à l'instant peut y être repris tout
  de suite s'il attend déjà une requête (25 ms de plus ce tick-là, au plus). Assumé : borné, et rare.
- **`M5_REVIEW.md`** (« un appel en vol pendant une pause de la JVM ») disait « la correction (un fait, un minuteur) n'est
  pas perdue » : c'était vrai des appels **suivants**, pas de la fin de l'appel lâché ; ça l'est désormais aussi.
  `M5.md` §3.7 et M5-LOAD (règle 3 : « il finit seul … le SDK le refuse ») sont dépassés sur ce point.
- **`docs/ARCHITECTURE.md`**, principe 3 de la Forge, mis à jour : il disait encore que tout appel lâché désactivait le
  module (faux depuis M5-LOAD) ; il distingue maintenant l'appel abandonné (il a calculé) de l'appel tenu et repris.
- `ForgeEndToEndTests` n'est pas modifié : son attente `C-191 ended by itself` doit désormais tenir sous charge.

## Vérifications

- **Reproduction avant correctif** : sonde 0 (§5), 5 rouges sur le code d'avant, chacun sur sa vérification ; le test du
  mot de passe échoue comme FLAKY-5 l'a vu en jeu (`timed out waiting for: C-1 ended`).
- **Tests ciblés** (`ModuleThreadsTest`, `HeldBackTest`, `ModuleEngineTest`, `ActQueueTest`, `ModuleHostTest`,
  `AbandonTest`, `MeterTest`, `HostBatteryTest`, `SdkBridgeTest`) : verts ; `HeldBackTest` 5 passages sur 5, le trio
  moteur, file, fils 3 sur 3, puis le lot (sans `MeterTest`, `HostBatteryTest`, `SdkBridgeTest`) 2 sur 2 après le tour
  de la reprise.
- **Build complet** (`.\gradlew.bat build --continue`) après la fusion de `main` (09d020e puis 879c957), avant le tour de la
  reprise : 2 183 tests unitaires, 0 échec, 0 ignoré ; « All 398 required tests passed ».
- **Build final** après la dernière fusion de `main` (1f3f2d9, M6-FIX-3) : **2 187 tests unitaires, 0 échec, 0 ignoré** ;
  **« All 398 required tests passed »**. Puis **`runGameTest` seul** : « All 398 required tests passed » (`password_race`,
  C-171, `ended (fulfilled): 4 acts, 0 faults`).
- Aucun test sans rapport n'a échoué. Aucun appel réel à Claude, pas de `runServer`, pas de charge ajoutée.
- Sondes P1 à P5 retirées ; l'arbre vérifié identique au correctif après chacune (`cmp` du diff, puis `git diff` de
  `src/main`).
