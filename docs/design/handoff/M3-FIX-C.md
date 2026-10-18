# Passation M3-FIX-C : ordonnanceur, Chroniqueur, assemblage

Branche `worktree-agent-aa76f60f4cd5f0bdf`. Ce lot corrige les constats **2, 5, 8, 19, 20, 21 (la borne), 24 (`RunTrigger`) et 27** de `docs/design/reviews/M3_REVIEW.md`, selon les décisions du superviseur. Il ajoute aussi la ligne `memory_runs` de l'hygiène quand elle n'a fermé que des notes (demande du superviseur après le lot E). Seuls les fichiers du lot C ont changé. **En cas de contradiction, le code fait foi.**

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `memory/MemoryJobs.kt` | fenêtre passée après deux échecs ; `memory:since` et le rattrapage borné ; `dreamMinHours` ; arrêt (horloge d'abord, borne de 5 s, plus de sieste après l'arrêt) ; clés `dream:<run>:step` supprimées ; déclencheurs `startup` et `catch_up` ; hygiène qui n'a fermé que des notes |
| `memory/JobsData.kt` | `batchEnd`, `batchFailedFrom`, `chaptersToNap(…, closedSince)`, `chaptersNeverNapped`, `deleteState`, `PASSED_PREFIX` ; `lastRun(kind)` simplifié |
| `memory/MemoryJobsControl.kt` | `RunTrigger.CATCH_UP` |
| `memory/chronicle/Chronicler.kt` | un bloc en échec n'arrête plus les suivants ; la sieste va jusqu'à la consolidation ; la couverture des châtiments tourne toujours ; `<written>` borné au chapitre |
| `memory/chronicle/ExchangeLog.kt` | `written(…, runId)` : les lignes du travail en plus de la période |
| `memory/MemoryModule.kt` | `StartupReads` : les lectures du démarrage partagent une borne de 30 s |
| `config/NarratorConfig.kt` | `memory.dreamMinHours` (6, de 0 à 24) et `memory.catchUpDays` (2, de 0 à 30), ajoutés **en fin** de `MemoryConfig` |

`memory/MemoryService.kt` et `memory/chronicle/Sanctions.kt` n'ont pas changé.

## Constat 2 : une fenêtre qui échoue ne bloque plus le chapitre

### Lot

- **Un bloc en échec n'arrête pas les suivants.** Le lot finit `failed` si l'un de ses blocs a échoué, et son rapport donne chaque raison une fois (`raison 1 / raison 2`).
- **Seul un 402 arrête aussitôt** les blocs suivants : ils échoueraient de la même façon.
- **`sanctions.cover` tourne quel que soit le résultat du modèle** (échec et 402 compris) : c'est du code pur.
  - Conséquence : un lot qui échoue ne laisse plus au lot suivant « la première main » sur les châtiments de sa fenêtre. Le code les a déjà couverts, et `<known>` les montre comme notés.

### Fenêtre passée (`MemoryJobs.failure`)

- Quand un lot échoue (hors 402), l'ordonnanceur cherche **un autre lot du même chapitre, en échec, parti du même `window_from`** (`JobsData.batchFailedFrom`).
  - S'il y en a un, la fenêtre est **passée**. La ligne reste `failed`, et son `error` commence par `passed:` (`DuckJobsData.PASSED_PREFIX`), par exemple : `passed: the window 19:59:00–20:19:55 failed twice, the next batch starts after it: refused by the safety filter (category cyber)`.
  - Un `WARN` et la décision `memory window_passed` (sujet `run N`) le disent. Exemple : `the batch window 19:59:00–20:19:55 of director-c1 failed twice: passed, the next batch starts at 20:19:55: …`.
- **Le marqueur est ce préfixe, sans clé ni colonne nouvelle**, comme `budget:` pour les siestes. `batchStart` lit `JobsData.batchEnd` : la plus grande `window_to` des lots `done`, `skipped` ou passés du chapitre, sinon le début du chapitre. La sieste en hérite : sa fenêtre part de là.
- **Ne comptent pas** : un 402 (`budget:`), un lot annulé ou interrompu. **Comptent** : un refus, une coupure, une limite d'étapes, un délai, et aussi une `engine error` (exception du Chroniqueur), pour qu'une erreur déterministe du code ne bloque pas non plus.
- Coût : une fenêtre qui échoue toujours coûte deux lots, pas un toutes les 10 minutes à vie. Une panne passagère a droit à un second essai.

### Sieste

- Elle **va jusqu'à la consolidation** même si son dernier lot échoue. **Son statut est celui de la consolidation** (`done`, ou `failed` et le rattrapage la refait, trois fois au plus).
- Un 402 dans le dernier lot arrête la sieste (`failed` avec `budget`, remise en file par l'ordonnanceur) ; la couverture tourne quand même.
- **Décision** : si le dernier lot a échoué et que la sieste ne finit pas `failed`, sa fenêtre est passée **dès ce premier échec**. Personne n'y reviendra : le chapitre a sa sieste. Le `Chronicler` l'écrit lui-même : `WARN` et décision `memory window_passed`, par exemple `the last batch of the nap of chapter 13 (20:10–20:30) failed; the nap went on without it: …`. Le rapport de la sieste commence alors par `failed: <raison> / ` avant celui du modèle.
- Rien n'est écrit si la sieste échoue : le rattrapage refera le dernier lot.

## Constat 5 : `memory:since` et `memory.catchUpDays`

- **`memory_state` `memory:since`** (`MemoryJobs.SINCE_KEY`, ms epoch) est posé au premier démarrage de la mémoire sur le monde, dans `recover()`, et n'est plus jamais réécrit.
- **Le rattrapage ne prend que les chapitres clos depuis `memory:since − memory.catchUpDays`** (`chaptersToNap(…, closedSince)`). Le cache du statut et `requestChronicle` suivent la même règle.
- **Au premier démarrage**, le log dit combien de chapitres anciens ne seront jamais rattrapés (`chaptersNeverNapped`) : `the memory starts on this world: the chapters closed since 2026-10-01 20:00 get their nap (memory.catchUpDays = 2); the 1 older one(s) never will`.
- **Décision** : la date du premier démarrage est gardée telle quelle, et `catchUpDays` s'applique à chaque lecture. Relever le réglage plus tard fait donc dormir les chapitres plus anciens au rattrapage suivant ; `0` ne prend que les chapitres clos après le premier démarrage. La borne de 30 jours évite un rattrapage de plusieurs mois par erreur.
- Un chapitre clos pendant que la mémoire tourne est toujours rattrapé, même des jours plus tard (serveur arrêté longtemps) : seule la date du premier démarrage compte.

## Constat 8 : `memory.dreamMinHours`

- Un rêve `server_empty` attend `dreamMinHours` (6 h) après la fin du dernier rêve (`dream:last_end`). Les autres conditions sont inchangées : une sieste `done` depuis, et le serveur vide depuis `dreamAfterEmptyMinutes`.
- **Ne sont pas soumis à ce délai** : le rêve `daily`, et le rêve demandé par un opérateur (`requestDream` ne regardait déjà aucun délai).
- Le premier rêve (aucun `dream:last_end`) n'attend pas.
- `nextDreamAt` (statut) en tient compte : le plus tardif de « vide depuis 20 min » et « fin du dernier rêve + 6 h », puis le plus tôt avec le rêve quotidien.

## Constat 19 : `<written>` d'une vieille sieste

- La consolidation lit les lignes **insérées entre le début et la fin du chapitre**, plus **celles du travail** (`memory_log.run_id` = la sieste), quelle que soit leur heure. Ces dernières sont la chronique écrite par le code et les écritures du dernier lot.
- Pareil pour le canon clos : clos pendant le chapitre, ou par le travail (lignes `update` de `canon` du travail dans `memory_log`).
- `<known>` des lots n'a pas changé (`runId` nul) : il lit toujours `]début de la fenêtre, maintenant]`.

## Constat 20 : l'arrêt

- `chapterClosed` ne met plus rien en file quand l'ordonnanceur s'arrête. Il n'en tire pas non plus d'identifiant, et le dit en `DEBUG` (`… not queued: the memory stops; the catch-up naps it at the next start`).
- `stop()` sauve l'horloge **d'abord** (5 s au plus), puis annule les travaux (`cancelAndJoin` borné à 5 s, `MemoryJobs.STOP_WAIT_MS`). Au-delà : `WARN` `the memory jobs did not end within 5s: left behind`. Le pire cas, 10 s, tient dans les 11 s de `ShutdownSequence`.

## Constat 21 : la borne de 30 s au total

- `StartupReads` (interne, `MemoryModule.kt`) donne aux lectures du démarrage une seule échéance, sur une horloge monotone : le schéma, puis le chargement des horloges de jeu. Chaque lecture reçoit ce qu'il reste. Quand il ne reste rien, elle ne part même pas.
- L'échec garde sa forme : `DisabledMemory("it could not start: loading the play clocks went over the 30s the startup's reads may take")`, avec un `ERROR`.
- La doc de l'exception est dans `ARCHITECTURE.md` §17, point 8 (lot F).

## Constat 24 : `RunTrigger`

- **`startup` est gardé** pour le seul vrai rattrapage de démarrage : le premier, 30 s après le passage en `RUNNING`. Il fait dormir les chapitres qu'un arrêt ou un plantage a laissés sans sieste.
- **`catch_up`** (nouveau) marque les rattrapages suivants, toutes les 30 min.
- Le commentaire de la colonne `trigger` dans `V2__memory.sql` ne liste pas `catch_up`. Je n'ai pas touché à une migration appliquée : c'est un commentaire, la colonne est un `VARCHAR` libre.

## Constat 27 : les clés `dream:<run>:step`

- Elles sont supprimées par `JobsData.deleteState` (`DELETE FROM memory_state`) :
  - celle de l'essai repris, dès qu'un nouvel essai a pris son étape (`prepare`) ;
  - celle de l'essai courant, quand le rêve finit pour de bon (`endDream`, `forGood`, donc `done`, `failed` ou une erreur du moteur).
- Il ne reste donc au plus qu'une clé d'étape : celle du rêve en attente (arrêt ou 402).
- **Écart** : `MemoryStore` n'a pas de suppression d'état, et ce fichier est au lot A. La suppression passe donc par `JobsData`, comme `canon.due_signaled_at`. Le lot G la déplacera dans `MemoryState` (constat 22).

## Hygiène qui n'a fermé que des notes (après le lot E)

`MemoryJobs.tidy` écrit sa ligne `memory_runs` (`hygiene`, `done`) dès que `expired`, `worn` **ou `notesDone`** n'est pas vide. Ainsi, chaque ligne `memory_log` d'une note fermée a son travail.

## Tests

| Test | Ce qu'il prouve |
|---|---|
| `MemoryJobsTest` › `a batch window that fails twice from the same start is passed…` | trois lots : le premier échoue (gardé tel quel), le second échoue du même départ et passe la fenêtre (`error` exact, décision `window_passed` exacte, ligne `WARN` au log), le troisième part de 20:19:55 |
| `MemoryJobsTest` › `the catch-up naps only the chapters closed since the memory first started less catchUpDays…` | chapitre clos il y a 3 jours jamais rattrapé, ligne exacte du log ; chapitres d'hier et de l'heure rattrapés en `startup`, puis la sieste ratée refaite en `catch_up` ; `memory:since` = le premier démarrage, gardé au redémarrage, sans nouveau log |
| `MemoryJobsTest` › `a dream after an empty server waits dreamMinHours…` | serveur vide, dernier rêve fini 1 h avant : aucun rêve avant +5 h, `nextDreamAt` = +5 h, rêve `server_empty` à +5 h ; un rêve d'opérateur juste après n'attend pas |
| `MemoryJobsTest` › `the stop saves the clock first, waits 5 seconds at most…` | un rêve qui ne lâche pas : `stop()` rend la main en 5 s exactement (temps virtuel), l'horloge est sauvée à l'heure du début de l'arrêt ; une clôture de chapitre après l'arrêt ne met rien en file et ne tire aucun identifiant |
| `MemoryJobsTest` › `the housekeeping writes its run when it only closed notes…` | rapport vide : pas de ligne ; une note fermée : ligne `done` avec l'identifiant du travail et `notes done 1` |
| `MemoryJobsTest` › `a dream stopped with the server…` (complété) | la clé de l'essai arrêté est gardée (`2`), puis plus aucune clé `dream:<run>:step` à la fin du rêve repris |
| `MemoryJobsTest` › rattrapage existant | le premier rattrapage est `startup` |
| `DuckJobsDataTest` › `batches resume after the latest window done, skipped or passed…` | SQL de `batchEnd` (`done`, `skipped`, `passed:` ; un échec simple et un 402 ne comptent pas) et de `batchFailedFrom` (lui-même, autre chapitre, autre départ, 402 exclus) |
| `DuckJobsDataTest` › `chapters to nap…` (complété) | `closedSince` inclusif, chapitres plus anciens exclus, `chaptersNeverNapped` (sieste faite, chapitre ouvert et analyste exclus) |
| `DuckJobsDataTest` › `a memory_state key done with is deleted` | `deleteState` |
| `ChroniclerTest` › `a block that fails does not stop the next ones, but a 402 does` | trois blocs : le deuxième refusé, le troisième lu quand même (`done`, `failed`, `done`), lot `failed` ; un 402 au premier bloc : une seule conversation |
| `ChroniclerTest` › `a nap whose last batch fails goes on to its consolidation…` | dernier lot refusé, consolidation faite : sieste `done`, rapport `failed: … / Chapitre consolidé.`, décision `window_passed` exacte, châtiment et cadeau couverts |
| `ChroniclerTest` › `the nap of an old chapter reads what was written during it and by its own job…` | sieste à 23:00 d'un chapitre clos à 20:30 : `<written>` a la menace de 20:06 et l'interaction écrite par le dernier lot, pas le souvenir écrit à 22:00 pour un autre chapitre |
| `ChroniclerTest` › refus et 402 (modifiés) | le code couvre le châtiment et le cadeau même quand le modèle échoue (`writes` = 2) |
| `MemoryServiceTest` › `the startup's reads share one bound of 30 seconds` | 20 s puis 11 s consommés : la troisième lecture ne part pas, message exact |
| `ConfigValidationTest` | valeurs par défaut (6 et 2), bornes inclusives, refus hors bornes |

### Données scénarisées conformes au contrat du constat 1 (lots A et B)

- `MemoryServiceTest` : la promesse du Chroniqueur cite maintenant sa ligne NARRATOR (`"evidence": ["e<id>"]`, l'identifiant rendu par `publish` dans `evening()`), avec sa citation de huit mots. La réponse du modèle est construite à la requête (`fake.enqueue { … }`), quand l'identifiant est connu.
- `ChroniclerReplayTest` : l'assertion sur la citation inventée ne dépend plus du message du magasin. Elle vérifie le préfixe `Invalid arguments: ` et l'absence de toute promesse écrite.

## Ce que les autres lots doivent savoir

- **Lot D** : `MemoryService.kt` (lot C) n'a pas changé. Si `Memory.briefing` prend la forme (`reduced`, constat 11), la délégation `sections.sections(chapter, now)` de `MemoryService` est la seule ligne à adapter.
- **Lot F / docs** : le tableau de configuration de `design/M3.md` (§17) n'a pas `dreamMinHours` ni `catchUpDays`. Les KDoc de `MemoryConfig` font foi.
- **Lot G** :
  - `memory:since` est une clé de `memory_state` de plus pour `MemoryState` ;
  - `JobsData.deleteState` est à déplacer dans `MemoryState` (constat 22) ;
  - `RunTrigger.STARTUP` reste utilisé : ne pas le retirer avec le reste du vocabulaire mort (constat 24).
- **M3-19 (recette)** : au premier démarrage sur `TheServer/`, la ligne `the memory starts on this world…` dit combien de chapitres de M1 et M2 ne seront jamais rattrapés.

## Vérification

- Tests ciblés (`MemoryJobsTest` 17, `DuckJobsDataTest`, `ChroniclerTest`, `ChroniclerReplayTest`, `MemoryServiceTest`, `ConfigValidationTest`, `ExchangeLogTest`) : verts.
- `git merge main` (lots E, F puis B) : sans conflit.
- `.\gradlew.bat build` sur l'état fusionné : voir le rapport de fin de tâche.
- Aucun appel réel à Claude ni à Jev ; pas de `runServer`.
