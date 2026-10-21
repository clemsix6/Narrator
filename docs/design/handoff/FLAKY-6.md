# Passation FLAKY-6 : les derniers tests instables

Mission : trouver la vraie cause de six instabilités vues sous charge (la file de la parade, la borne de 55 ms
d'`EvilModulesGameTests`, le réveil `task` de `DirectorEndToEndTests`, le scénario S11 de `DelegationScenarioTest`, deux
tests de `ModuleBookTest`, `AbandonTest`), corriger le test quand le défaut est le sien, et décrire sans le corriger un
défaut de production. Branche `worktree-agent-a72ae435129a70ddc`. Seuls des fichiers de test changent.
**En cas de contradiction avec FLAKY-5, M5-FIX-2 ou M6-10 sur ces tests, le code et cette passation font foi.**

| Test | Cause | Où était le défaut | Correction |
|---|---|---|---|
| `ArsenalCreaturesGameTests` › parade, la file | avant FORGE-ABANDON, un pas de la parade lâché par le serveur (machine chargée) perdait ses `moveTo` restants : un pas sans correction, dix ticks de rupture de plus | **production, corrigé par FORGE-ABANDON** | aucune (§1) |
| `EvilModulesGameTests` › *the deep hash attacks…* | borne murale de 55 ms sur un **démarrage**, que l'hôte attend tant que son fil n'a pas calculé (M5-LOAD, jusqu'à 2 s) | test | borne + `BusyMachine.SLACK_MILLIS`, causes inchangées (§2) |
| `DirectorEndToEndTests` › le réveil `task` | 15 s pour une chaîne que la production cadence elle-même jusqu'à plus de 15 s (sommeil `SOON` jusqu'à 10 s, mémoire 3 s par réveil, tours d'autres nouvelles) | test | attente de 45 s, garde contre un blocage (§3) |
| `DelegationScenarioTest` › S11 | 15 s pour le premier tour d'une JVM froide : 2 s au calme, 20 s mesurées par M6-10 sur une machine saturée | test | attente de 2 min, garde contre un blocage (§4) |
| `ModuleBookTest` × 2 (*the switch…*, *the Director's stop…*) | **le `stop` du second module d'un retrait en rafale est sauté** (`LATE`) : il est remis au fil des modules neuf que le déchargement du premier vient de démarrer, que la machine n'a pas encore lancé ; un `stop` sauté n'est jamais redemandé | **production, à trancher** | aucune : le test a raison d'échouer (§5) |
| `AbandonTest` › *a single long call of the JDK…* | un appelant retenu ~50 ms après son échéance trouvait l'`indexOf` de 75 ms fini : jugé par son compteur (une faute *limite*, qui ne désactive pas), `disabled` nul (l. 57) | test | un appel du JDK de ~2 s, plus long que le budget et la marge (§6) |

## 1. La parade (`ArsenalCreaturesGameTests.theParadeFollowsItsPlayer…`)

**Le mécanisme des ruptures.** La parade avance tous les 10 ticks ; elle ne renvoie une poule que si elle est à plus de
2 blocs (`GAP` + `SLACK`) de celle qui la précède, vers une place à 1 bloc de la position **actuelle** de celle-ci, et
`moveTo` s'arrête un bloc avant : au repos, chaque liaison fait environ 2 blocs. Entre deux pas, une poule flâne
(`WaterAvoidingRandomStrollGoal`, environ 0,12 bloc par tick) ; tant que sa propre liaison reste sous 2 blocs, elle n'est
pas renvoyée, et celles qui la suivent visent l'endroit où elle se trouve. La meilleure file du test dépasse alors
3,5 blocs quelques ticks, jusqu'au pas suivant et au retour. Sonde (journal de chaque tick : liaison, buts de chaque
poule, chemins nouveaux, lignes de l'hôte) pendant 20 cycles « Bob marche 14 blocs, la file se resserre 100 ticks, puis
240 ticks surveillés » :

| Code, conditions | Ruptures (en ticks d'affilée) | La plus longue |
|---|---|---|
| actuel, au calme (2 lancements) | 1, 1, 1, 3, 8 ; 1, 1, 1, 2, 3, 3, 5, 10 | 10 |
| actuel, 32 fils de calcul | 2, 3, 4, 11 (un pas lâché, repris au tick suivant) | 11 |
| actuel, **un pas sur deux lâché** (la première lecture du monde de chaque pas retenue 35 ms) | 12 ruptures, de 1 à 6 (498 pas lâchés, tous repris) | 6 |
| **avant FORGE-ABANDON** (sonde 0 : les quatre fichiers de la Forge et leurs tests remis à `a74534d`), un pas sur deux lâché | 47 ruptures, dont **37, 32, 23**, 19, 18, 18, 17×3 ; 3 fenêtres sur 20 au-delà des 20 ticks permis | 37 |

**La cause de l'échec vu par FLAKY-5** (`broken 25 ticks in a row`, liaison de 4,7 blocs, serveur à 65 ticks par seconde,
lot `forge` sous charge, avant FORGE-ABANDON) : un pas lâché par le serveur perdait ses requêtes du SDK suivantes (les
`moveTo`), la poule n'était pas renvoyée avant le pas d'après, et une flânerie de 10 ticks en durait 20 à 37. FORGE-ABANDON
(un appel lâché est tenu, puis repris : ses `moveTo` partent au tick suivant) a corrigé la cause. Le test n'a pas changé :
il mesure ce qu'il dit (la file à chaque tick, une rupture réparée en moins de 20 ticks), et la queue mesurée sur le code
actuel (11 ticks, 6 avec un pas sur deux lâché) lui laisse sa marge.

## 2. `EvilModulesGameTests` › *the deep hash attacks are stopped by the guard…*

La relecture adverse de FORGE-ABANDON (`docs/design/reviews/FORGE-ABANDON_REVIEW.md`, constat 5) l'a établi par lecture ;
je l'ai reproduit. Le test ne mesure que `host.start(…)` : un démarrage, que l'hôte attend **tant que son fil n'a pas
calculé** (M5-LOAD, `STARTUP_PATIENCE_NANOS`, 2 s). Sous charge, le fil est retenu par la machine et le thread serveur
l'attend : `SetOfHash` 99 ms (FLAKY-5), 55,2 ms (M6-13), `RecordHash` 162 ms (M6-12). Ce n'est ni la garde ni l'hôte qui
tiennent le tick, c'est la patience voulue d'un démarrage.

- **Reproduction déterministe** (sonde : le fil des modules du test dort 100 ms avant chaque appel, sans calculer) :
  l'ancienne borne échoue (`SharedHash held the tick 112.56 ms`), la nouvelle passe (les dix attaques arrêtées par la
  garde en 104 à 119 ms).
- **Mesure sous 32 fils de calcul** (400 démarrages, classe seule) : aucun au-delà de 55 ms (au plus 19,7 ms) ; le fil
  serveur est à la priorité 8, la machine doit être plus chargée (lot `forge` entier, builds d'autres agents).
- **Correction** : la borne murale devient `budget + BusyMachine.SLACK_MILLIS` (555 ms), comme dans
  `theResidualScanIsStoppedByTheHost` du même fichier. Le travail du module reste borné par ce que le test vérifiait
  déjà : la cause (« too large to hash » ou « time budget », jamais « abandoned ») et aucun fil perdu. Au-delà de son
  budget, le compteur l'arrête, et un long pas du JDK est abandonné par l'hôte dès que l'horloge CPU du fil le montre.
  Borner le travail par l'horloge CPU (`called.work`, comme le proposait la relecture) n'est pas sûr ici : elle avance par
  pas de 15,6 ms sous Windows, et un fil découpé en tranches par une machine chargée (justement le cas) est lu plusieurs
  pas au-dessus de ce qu'il a fait (FLAKY-5 §6).
- **Sondes, le comportement retiré** : fil retenu 600 ms → rouge (`SharedHash held the tick 613.6 ms`) ; une attaque que
  la garde ne borne pas (`ScanShared` ajoutée à la liste) → rouge (`ScanShared was abandoned, not bounded by the guard`).

## 3. `DirectorEndToEndTests` › le réveil `task`

Entre la question (« où suis-je ? ») et la requête du Directeur qui lit `TASK T-…` : le tour du Directeur (un réveil dont
la mémoire peut prendre `Director.ATTACH_TIMEOUT_MS`, 3 s ; deux requêtes ; `delegate` et `say`), les deux requêtes de
l'Analyste et sa lecture du joueur, puis le rapport, un élément saillant (`SOON`) : il réveille le Directeur
`SOON_QUIET_MS` (3 s) après le dernier élément saillant, au plus `SOON_MAX_MS` (10 s) après le premier ; puis la mémoire
du réveil, encore jusqu'à 3 s. Dans un `runGameTest` complet, les nouvelles des lots précédents (défis, offrandes,
joueurs) réveillent aussi des tours entre-temps (vu dans un journal complet : `woke turn …: mechanic CHALLENGE C-24`,
`OFFERING O-699` pendant le chapitre du test). La production s'autorise donc plus de 15 s, l'attente du test 15 s.

Mesures (sonde : l'heure de chaque requête du faux serveur) : **3,3 s** au calme, 3,3 s sous 32 fils de calcul (deux
lancements), 3,3 s dans un `runGameTest` complet. Je n'ai pas revu l'échec de M6-10 (« 15 s entre le réveil `task` et la
requête », sous la charge des builds d'autres agents) ; son journal n'a pas été gardé.

- **Correction** : l'attente du rapport passe à `REPORT_WAKE_MS` = 45 s (comme celle du résumé de chapitre, déjà à 45 s),
  avec la chaîne ci-dessus dans la KDoc. C'est une garde contre un blocage, le test ne mesure aucun temps.
- **Sondes** (la réponse finale de l'Analyste retardée par le faux serveur) : 13 s avec l'ancienne attente → rouge
  (`timed out waiting for: the Director reads the Analyst's report`) ; 13 s avec la nouvelle → vert ; 50 s (le rapport ne
  vient pas à temps) → rouge.

## 4. `DelegationScenarioTest` › S11

M6-10 l'a vu rouge sous charge, seul et dans le build (« timed out waiting for the Director's first turn ») : le premier
tour mettait 20 s à s'ouvrir, de la `SchemaDoc` (une base DuckDB en mémoire, ses migrations) à l'outillage du Directeur ;
au calme, tout le test prend 2,0 s. C'est du calcul et de l'E/S sur d'autres fils (HTTP vers le faux serveur, DuckDB et
son propre groupe de fils), pas du temps virtuel : le test n'en mesure rien.

- **Correction** : l'attente de chaque étape passe de 15 s à `PATIENCE_NANOS` = 2 min, garde contre un blocage.
- **Sondes** : la première réponse du Directeur retardée de 16 s par le faux serveur → l'ancienne attente échoue
  exactement comme vu (`timed out waiting for the Director's first turn: RUNNING`), la nouvelle passe ; un Directeur qui ne
  répond jamais → rouge (`timed out waiting for the Analyst at Claude: BACKOFF`).
- Piste hors de mon périmètre : DuckDB lance autant de fils que de cœurs ; sur une machine saturée, ses tâches
  parallèles se paient très cher (20 s pour quelques requêtes). Le Journal et la mémoire en production y sont exposés de
  la même façon ; à mesurer si le serveur de jeu est chargé.

## 5. `ModuleBookTest` : le `stop` sauté d'un retrait en rafale (défaut de production, non corrigé)

**Ce qui échoue** : *the switch stops every module at once…* (`each module's stop(FORGE_OFF) ==> expected: <2> but was:
<1>`) et *the Director's stop and an operator's call stop STOPPED…* (le compte des lignes `stop STOPPED`). Dans les deux,
deux modules sont retirés l'un après l'autre (`ModuleBook.halt`, ou deux `book.stop` de suite), et **le second ne reçoit
jamais son `stop`**, sans faute ni trace au niveau `INFO`.

**Le mécanisme** (`forge/host/ModuleHost.kt`, `ModuleThreads.kt`, `mechanics/module/ModuleEngine.kt`) :

1. `ModuleEngine.retire(premier)` appelle son `stop`, puis `host.unload()`, qui appelle `threads.renew()` (FLAKY-4) : le fil
   des modules se termine et un fil **neuf** est démarré (`Thread.start`, il n'a pas encore tourné).
2. `retire(second)` remet aussitôt son `stop` à ce fil neuf (`d.hosted(STOP) { it.stop(why) }`, budget du jeu : 20 + 5 ms,
   sans patience). Si la machine ne lance pas le fil neuf dans ce délai, l'appel est `LATE` : sauté, « personne n'est en
   faute », journalisé en `DEBUG` seulement.
3. `Deployment.call` range l'appel sauté dans `skipped`, que seul `work`/`replay` redemande… mais le déploiement vient
   d'être retiré (`phase = ENDED`, hors de `live`) : **le `stop` n'est jamais redemandé**. `context.close()` et `unload()`
   suivent.

FLAKY-4 démarrait le fil suivant au déchargement pour qu'il soit garé quand l'appel suivant arrive ; dans un retrait en
rafale, l'appel suivant arrive quelques microsecondes après.

**Preuves** :
- **Sous charge** (32 fils de calcul et les builds d'autres agents ; sonde : 2 000 `halt` de deux modules dans une JVM) :
  **910 sur 2 000** ont perdu le `stop` du second module (la ligne manque, aucune ligne « given up »), 2 autres l'ont vu
  lâché puis coupé (ci-dessous). Au calme : 0 sur 2 000. Une seconde rafale de charge plus courte : 0 sur 2 000 (la machine
  était alors moins prise par les autres agents, dont les serveurs de jeu tournent à la priorité 8, celle du fil neuf).
- **Déterministe** (sonde : le fil neuf démarre 40 ms en retard, `Thread.sleep(40)` au début de `Worker.run`) : les deux
  tests échouent exactement comme vu (`expected: <2> but was: <1>`), et la sonde des 2 000 `halt` perd le second `stop`
  787 fois sur 787.

**Conséquences en jeu** : `/narrator forge off`, le retrait de plusieurs modules de suite, et surtout l'arrêt du serveur
(`ModuleEngine.stopping` retire tous les déploiements l'un après l'autre avec `SERVER_STOPPING`) : sur un serveur chargé,
tous les modules après le premier peuvent ne pas recevoir leur `stop` ; ce qu'un module écrit dans son stockage pendant
`stop(SERVER_STOPPING)` (sa reprise après redémarrage) est alors perdu, ses derniers actes aussi.

**Un second cas voisin**, déterministe (sonde : un `stop` qui lit le monde pendant que le thread serveur est retenu 60 ms) :
un `stop` **lâché** est tenu, puis aussitôt coupé par `unload` : ses requêtes suivantes sont refusées (`stop STOPPED`
jamais journalisé) et son fil est compté perdu, avec l'avertissement `a module thread was abandoned past its budget …
(1 lost and still running, 2 at most)`. La relecture de FORGE-ABANDON en a vu la moitié (constat 2 : le fil compté perdu),
traitée par FORGE-ABANDON-FIX ; le `stop` à moitié fait reste.

**Pistes, à trancher** (fichiers de FORGE-ABANDON-FIX) : donner au `stop` la patience d'un démarrage (attendu tant que
son fil n'a pas calculé) ; ou redemander un `stop` sauté dans `retire`, comme le moteur rejoue un appel sauté ; ou, dans un
retrait en rafale, ne renouveler le fil qu'une fois, après le dernier. Le test a raison d'échouer ; je ne l'ai pas touché.

## 6. `AbandonTest` › *a single long call of the JDK never holds the caller past the budget…*

**Cause.** L'attaque était un `indexOf` de ~75 ms, un seul appel du JDK. L'appelant (le fil du test) l'abandonne à
l'échéance (20 + 5 ms) ; s'il est retenu par la machine ~50 ms au-delà, il trouve l'appel **fini** : le compteur l'a
jugé à son `disarm` (« time budget », preuve CPU de 40 ms), une faute *limite* qui ne désactive pas à la première ;
`Hosts.settled` revient aussitôt, `host.disabled` est nul, le test échoue l. 57. C'est le symptôme que M5-FIX-2 attribuait
au seul `LATE` (corrigé alors par la redemande de `attack`) ; ce cas-ci restait. En jeu, ce comportement est le bon : le
compteur juge un appel fini, le serveur était en retard de lui-même.

**Reproduction déterministe** (sonde dans `ModuleThreads.attend` : l'appelant dort 100 ms à son échéance) : sur l'ancien
test, rouge (`actual value is null`) ; dans une boucle de sonde, 300 attaques sur 300 finissent sans désactiver le module
(299 jugées finies par leur compteur, « time budget », une réglée sans faute). Le test 4 (l'`indexOf` en boucle) n'y est pas sensible : le compteur ne regarde l'heure que tous les 16 tours
de boucle, soit 16 `indexOf` (plus d'une seconde), et l'appel est toujours lâché avant.

**Correction.** L'attaque du test 1 est un balayage d'égalité de ~2 s en un seul appel du JDK (`LONG_SCAN`, 100
références de `SCAN_SETUP`, le timer 0 de `SHARED` à la place de l'`indexOf`, qui n'avait plus d'autre utilisateur), suivi
d'un `m.log` de ce qu'il a trouvé : quoi que fasse la machine, l'appelant ne peut repartir que pendant que l'appel tourne
encore, donc l'abandonner ; s'il est retenu au-delà du budget et de `BusyMachine.SLACK_MILLIS`, la borne murale échoue,
comme elle le doit. Le `m.log` rend vraie la vérification « nothing reaches the SDK after » (avant, l'`indexOf` ne
journalisait jamais rien). Sondes : appelant retenu 100 ms → vert (abandonné à 125,9 ms) ; retenu 3 s (il voit la fin de
l'appel, comme si l'abandon n'existait pas) → rouge (`the caller went on at the budget: 3025.8 ms`). Sous 32 fils de
calcul : 30 attaques sur 30 abandonnées, le test vert.

## 7. Sondes (aucune commitée ; `git diff` de `TheMod/src/main` vide après chacune)

| Sonde | Ce qu'elle fait | Résultat |
|---|---|---|
| A, parade longue | 20 cycles marche, resserrement, 240 ticks surveillés ; journal de chaque tick | §1 |
| B, pas lâchés | la première lecture du monde d'un pas sur deux retenue 35 ms (un `ModuleServer` du banc) | code actuel : ruptures ≤ 6 ; avant FORGE-ABANDON : jusqu'à 37 |
| 0, avant FORGE-ABANDON | `Meter.java`, `ModuleHost.kt`, `ModuleThreads.kt`, `ActQueue.kt` et leurs tests remis à `a74534d` | §1 |
| C, démarrage retenu | le fil des modules d'`EvilModulesGameTests` dort 100 / 600 ms avant chaque appel | ancienne borne rouge à 100 ; nouvelle verte à 100, rouge à 600 |
| D, attaque non gardée | `ScanShared` ajoutée aux attaques gardées | rouge : `was abandoned, not bounded by the guard` |
| E, rapport lent | la réponse finale de l'Analyste retardée de 13 / 50 s | ancienne attente rouge à 13 s ; nouvelle verte à 13 s, rouge à 50 s |
| F, premier tour lent | la première réponse du Directeur retardée de 16 s ; puis jamais | ancienne attente rouge ; nouvelle verte ; jamais : rouge |
| G, fil neuf en retard | `Worker.run` dort 40 ms avant son premier appel | les deux tests de `ModuleBookTest` rouges comme vu |
| H, `stop` lâché | un `stop` qui lit le monde, thread serveur retenu 60 ms | `stop` coupé, fil compté perdu (§5) |
| I, 2 000 `halt` | deux modules retirés d'un coup, 2 000 fois, au calme et sous charge | 0 / 2 000 au calme ; 910 / 2 000 sous charge |
| J, appelant en retard | `attend` dort 100 ms / 3 s à l'échéance | ancien test 1 rouge à 100 ms ; nouveau vert à 100 ms, rouge à 3 s |
| K, horloges d'`EvilModules` | 400 démarrages mesurés : mur, travail, CPU du thread serveur et du fil des modules | au calme et sous 32 fils, aucun au-delà de 20 ms |

## 8. Pour la suite

- **Défaut de production à trancher** : le `stop` sauté d'un retrait en rafale, et le `stop` lâché coupé au déchargement
  (§5). `ModuleBookTest` restera instable sous charge tant qu'il n'est pas corrigé.
- **La règle de FLAKY-5 se précise** : une attente sur ce que d'autres fils font en temps réel (HTTP, DuckDB, les réveils
  du Directeur) est une garde contre un blocage, réglée sur ce que la production s'autorise elle-même (ses propres
  échéances), pas sur ce qu'on mesure au calme ; une borne murale sur un appel de module prend `BusyMachine.SLACK_MILLIS`
  dès que l'hôte peut attendre ce que la machine retient (démarrage patient, appel tenu).
- Un test qui veut qu'un appel soit **lâché** (et non jugé fini) doit faire durer l'appel au-delà du budget et de
  `SLACK_MILLIS` : sinon un appelant en retard le voit finir.

## Vérifications

- Reproductions et sondes : §1 à §7 ; chacune retirée, `git diff` de `TheMod/src/main` vide ensuite.
- Charge : quatre séances de 32 fils de calcul (`Flaky6Burn.java`, qui s'arrête de lui-même : 9, 2,5, 4 et 7 min ; les
  deux dernières arrêtées à la main ; processus vérifiés absents ensuite), plus les builds des autres agents.
- Sous charge, code final : `AbandonTest` et `DelegationScenarioTest` verts, `EvilModulesGameTests` vert (2 tests).
- `runGameTest` complet avant la fusion finale (sonde de la chaîne du Directeur en place) : « All 398 required tests
  passed ».
- **Build complet** (`.\gradlew.bat build --continue`) sur `2ce5ffc` : 2 217 tests unitaires, 0 échec, 0 ignoré ;
  « All 398 required tests passed ». Puis `git merge main` (`c8b716c` : M6-16, M6-FIX-4 à 8), sans conflit, et **build
  complet** : **2 245 tests unitaires, 0 échec, 0 ignoré** ; **« All 398 required tests passed »** ; puis **`runGameTest`
  seul** : « All 398 required tests passed » (`RecordHash` arrêté par la garde en 17,5 ms).
- Aucun appel réel à Claude ; pas de `runServer`. Journaux des sessions sous des noms `flaky6-*` (dossier de travail de
  la session).
