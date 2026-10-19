# Passation FLAKY-2 : les statistiques des départs en retard

Mission : `MemoryEndToEndTests` › `a_player_who_joins_has_their_sheet_and_their_stats_follow_what_they_did` échouait
deux fois sur quatre (`timed out waiting for: the stats of me_… after the departure`), et un de ces échecs avait fait
tomber `DirectorEndToEndTests`. Méthode de FLAKY-1 : cause mesurée, correction au bon endroit, preuve de stabilité.
**En cas de contradiction avec un document de conception, le code et cette passation font foi.**

## La cause, mesurée

Sondes temporaires (retirées) dans `MemoryJobs.follow` et `StatsRefresher.refresh` : attente de chaque départ dans la file
du bus, longueur de cette file, durée de chaque étape d'un recalcul. Lancement `probe1`, code d'avant M4-19 :

- un lancement de `runGameTest` fait **305 départs** de faux joueurs, presque tous pendant les lots par défaut (environ
  25 s, jusqu'à 12 départs par seconde) ;
- chaque départ lançait **son propre recalcul** (`refresh(listOf(uuid), "left")`), en série, dans la coroutine qui lit
  aussi les échantillons de position ;
- un recalcul refait toutes les paires de joueurs. Leur écriture domine : 366 à 531 ms pour 240 à 415 paires (une
  insertion par paire), contre 60 à 80 ms pour les stats d'un joueur. D'où les `a write job held the writer for 7xx
  ms` toutes les secondes vus par M4-21 ;
- à la fin des lots par défaut, la file `memory-jobs` du bus contenait **204 événements** (capacité 256) et les départs
  y attendaient **18 s** ; à l'arrêt du serveur, 136 départs seulement avaient été traités, et 169 jamais.

Quand le lot `memory` passe juste après les lots par défaut, le départ du test arrive derrière plus de 150 recalculs
d'environ 0,5 s : plus de 30 s d'attente. Quand il passe avant, la file est vide et le test passe. Sur un vrai serveur,
une vague de départs (fin de soirée, redémarrage) aurait le même effet, et une file pleine perdrait des départs et des
échantillons.

## La correction

M4-19 a fusionné pendant ma tâche son propre regroupement dans `follow` : au premier départ, on vide la file du bus, on
calcule ensemble tous les départs trouvés, puis on traite les échantillons mis de côté. Je l'ai mesuré (lancement
`m419probe1`, `memory` après les lots par défaut) : 324 départs en 22 recalculs, et le test passe. Mais la coroutine du bus
reste bloquée pendant chaque recalcul (jusqu'à 4,8 s pour 78 joueurs) : la file du bus remontait à **90 événements sur
256**, et les échantillons de position attendaient la fin du recalcul. Sur ordre du superviseur, il ne reste qu'**une
implémentation** : j'ai remplacé celle de M4-19 par la mienne, plus simple et qui ne bloque jamais le bus.

`memory/MemoryJobs.kt` :

- `follow` ne fait plus qu'**ajouter le joueur** à `leavers` (un `LinkedHashSet`, l'ordre des départs, chaque joueur une
  fois) et réveiller `departures` (un canal `CONFLATED`). Il ne touche jamais au Journal pour un départ : la file du bus
  ne se remplit plus derrière un recalcul, et un échantillon est traité tout de suite ;
- une nouvelle coroutine enfant, `refreshDepartures`, fait **un recalcul à la fois** : elle prend tous les joueurs en
  attente, vide l'ensemble, puis appelle `hooks.refreshStats(joueurs, "left")` ;
- un départ attend donc **au plus le recalcul en cours**, puis passe dans le suivant avec tous ceux arrivés entre-temps ;
- un joueur qui repart pendant un recalcul (revenu, puis reparti) est remis dans l'ensemble et passe dans le recalcul
  suivant : celui en cours a peut-être lu le Journal avant ce départ ;
- l'étape s'appelle `stats of the departures` dans les journaux (`attempt`).

Sémantique gardée :

- un départ isolé donne exactement l'appel d'avant, `refresh(listOf(uuid), "left")` ;
- la décision `memory stats` (sujet `left`) est écrite par `StatsRefresher` comme avant, une par recalcul, avec le
  nombre de joueurs ;
- aucun départ n'est perdu ni compté deux fois. Un échec du recalcul est journalisé une fois, sans nouvelle tentative,
  comme avant. Les départs encore en attente à l'arrêt ne sont pas calculés, comme avant ; le recalcul d'avant le rêve
  (`activeSince`) les couvre.

Mesure avec ce correctif (lancement `mineprobe1`, `memory` après les lots par défaut) :

| | avant (`probe1`) | M4-19 (`m419probe1`) | FLAKY-2 (`mineprobe1`) |
|---|---|---|---|
| recalculs `left` pour ~310 départs | 1 par départ (136 faits avant l'arrêt, 169 jamais) | 22 | 18 |
| file du bus `memory-jobs` au plus haut | 204 / 256 | 90 / 256 | **0** |
| plus longue attente d'un départ avant son recalcul | 18,2 s (et croissante) | 4,4 s | 5,5 s |
| départ du test → stats écrites | > 30 s (échec) | ≈ 1,5 s | ≈ 4,7 s |

L'attente la plus longue vient du plus gros recalcul (90 joueurs, 5,5 s) : environ 60 ms de lecture par joueur, plus les
paires. Dans `mineprobe1`, le départ du test est tombé pendant un recalcul de 58 joueurs, d'où 4,7 s au lieu de 1,5 s :
c'est le hasard de la vague, pas une différence entre les deux implémentations.

## Le test, plus strict et propre après un échec

`gametest/MemoryEndToEndTests.kt` :

- **Plus strict** : les stats doivent avoir été calculées **après le départ** (`sheet_stats.computed_at` ≥ le
  `left_at` de sa session, fonction `statAfterDeparture`). Avant, un recalcul `timer` des joueurs en ligne, passé entre
  la mort et le départ, aurait suffi à faire passer le test sans aucun recalcul de départ.
- **Délai inchangé** : 30 s, contre 1,5 à 5 s mesurées après le correctif. Rien ne justifiait de l'allonger.
- **Nettoyage dans le `finally`, même après un échec** : le joueur est retiré (`close` est sans effet s'il est déjà
  parti), puis le chapitre ouvert du Directeur est clos avec son résumé (`closeChapter`), tant que le faux LLMGW et la
  clé sont encore là. Viennent ensuite la configuration vide et la fermeture du faux serveur. Chaque étape est dans un
  `runCatching`, et un échec est journalisé (`narrator2-gametest`). C'était le vrai lien avec `DirectorEndToEndTests` :
  l'échec arrivait avant l'étape 5, qui clôt le chapitre, et le test suivant lisait le briefing de ce chapitre (M3-19,
  « À savoir »). L'étape 5 réutilise `closeChapter`, puis attend la sieste comme avant.

## Tests unitaires

`MemoryJobsTest` (19 tests) :

- **nouveau**, `departures during a refresh wait for that one only, then share the next, each player once, and samples
  never wait for it` : chaque recalcul prend 2 s en temps virtuel ;
  - un départ seul a son recalcul tout de suite ;
  - pendant ce recalcul, quatre départs (Josiane, Bartok, Josiane encore, Mimosa qui repart) et un échantillon de
    position arrivent ;
  - l'échantillon est demandé tout de suite et aucun recalcul ne s'ajoute ;
  - 2 s plus tard, un seul recalcul `[Josiane, Bartok, Mimosa]` `left` ;
  - plus rien ensuite ;
- le test de M4-19 (`departures waiting together are computed in one refresh…`) est gardé tel quel et passe ;
- le harnais a un `refreshTook` (durée d'un recalcul, zéro par défaut) et une aide `leave`.

Contre-épreuve : avec l'ancien `follow` (un recalcul par départ), le nouveau test et celui de M4-19 échouent. Avec
l'implémentation de M4-19, le nouveau test échoue sur l'échantillon (« the sample is asked during the refresh »).

## Ce que les tâches suivantes doivent savoir

- **Écriture des paires** : sur le serveur de test, des centaines de faux joueurs se croisent, et `pair_stats` monte à
  **1 506 paires**. Leur écriture tient alors l'écrivain du Journal **2 s** d'un bloc (`a write job held the writer for
  2033 ms`), avec une insertion par paire (`PairStats.write`). En production, avec n joueurs, il y a au plus
  n(n−1)/2 paires (190 pour 20 joueurs) : ce n'est pas urgent. Mais la promesse de M3-05 (« jamais plus de 100 ms
  d'écriture d'affilée ») ne tient plus au-delà de quelques centaines de paires. Pistes pour le propriétaire de la
  mémoire : n'écrire que les paires qui ont changé, ou découper l'écriture en plusieurs travaux.
- Un abonné asynchrone du bus ne doit pas attendre le Journal dans sa boucle : sa file (256) se remplit, puis perd des
  événements. Il vaut mieux noter le travail, et le faire ailleurs.
- `MemoryJobs` tourne désormais en quatre boucles dans `narrator2-memory` : le travailleur, le bus, les départs et les
  minuteries.
- La passation M3-12 (« `PlayerLeft` : `hooks.refreshStats(listOf(joueur), "left")` ») est remplacée sur ce point par
  celle-ci.

## Fichiers

- `memory/MemoryJobs.kt` : `leavers`, `departures`, `refreshDepartures` ; `follow` simplifié ; KDoc de la classe.
- `test/.../memory/MemoryJobsTest.kt` : un test, `refreshTook` et `leave` dans le harnais.
- `gametest/.../MemoryEndToEndTests.kt` : `finally`, `closeChapter`, `statAfterDeparture`.
- `docs/MEMOIRE.md` §3 : la ligne **Stats**.

## Vérifications

- **Preuve de stabilité** : 8 lancements de `runGameTest` d'affilée sur le code final, sans sonde (après la fusion de
  M4-19), pendant que d'autres agents compilaient. **8 sur 8 verts** (« All 287 required tests passed »). L'ordre des lots
  vient des journaux (`Running test environment …`) :

  | lancement | place du lot `memory` | durée du test de mémoire |
  |---|---|---|
  | 1 | premier, avant les lots par défaut | 27,9 s |
  | 2 | avant les lots par défaut | 27,3 s |
  | 3 | **juste après les lots par défaut**, puis `director` | 26,5 s |
  | 4 | **juste après les lots par défaut** | 26,4 s |
  | 5 | avant les lots par défaut | 26,3 s |
  | 6 | avant les lots par défaut | 27,2 s |
  | 7 | **après les lots par défaut** (`mechanics_commands`, un test, entre les deux) | 26,4 s |
  | 8 | **après les lots par défaut** (`mechanics` et `mechanics_commands` entre les deux, environ 5 s) | 26,4 s |

  Le cas « `memory` après les lots par défaut » est couvert **4 fois**. La durée du test ne dépend plus de l'ordre :
  avant le correctif, l'attente du seul départ dépassait 30 s dans ce cas. Aucun `queue full` ni aucun échec de
  `stats of the departures` dans les huit journaux.
- **Échec forcé, pour vérifier le nettoyage** (modification temporaire, non commitée : condition impossible et délai de
  5 s à l'étape 3). Le hasard a placé le lot `director` juste après `memory`, ce qui est le cas de M4-21. Le test de
  mémoire échoue comme prévu (`timed out waiting for: the stats of me_… after the departure`). Le `finally` clôt le
  chapitre 1 (`closed director-…: chapter 1, operator: closed at a quiet moment; summary of 34 characters`), puis le
  Directeur se rendort. `DirectorEndToEndTests` passe ensuite : « 1 required tests failed », le seul test de mémoire.
- **Mesures avant et après** : lancements avec sondes `probe1`, `m419probe1` et `mineprobe1` (tableau plus haut), tous
  verts ; les deux derniers avaient `memory` après les lots par défaut.
- `MemoryJobsTest` : 19 tests verts. Contre-épreuves décrites plus haut.
- `git merge main` deux fois : M4-19 (`920ce18`, avance rapide, au début de la tâche), puis M4-18 (`b95162a`, sans
  conflit, aucun fichier de la mémoire touché).
- `.\gradlew.bat build` sur l'état fusionné avec M4-18 : **vert**, avec **1 487 tests unitaires** (0 échec, 0 ignoré)
  et **« All 297 required tests passed »** (`memory` en premier dans ce lancement).
- Aucun appel réel à Claude ni à Jev ; pas de `runServer`.
