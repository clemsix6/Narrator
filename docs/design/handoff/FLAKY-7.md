# Passation FLAKY-7 : le Directeur rendu tel qu'il a été trouvé, d'un lot de GameTests à l'autre

Mission : trouver pourquoi deux GameTests ont échoué ensemble dans un `runGameTest` de FORGE-ABANDON-FIX
(`a paused Director sends nothing` dans le lot `director`, puis `timed out waiting for: a quiet Director` dans `forge_e2e`,
avant tout module), le reproduire de façon déterministe et corriger les tests. Ajout du superviseur en cours de route :
rendre `RestartGameTests` indépendant des redémarrages des autres lots (vu par M6-17). Branche
`worktree-agent-ab9c25f0bdfa85cc0`. Seuls des fichiers de GameTests changent ; rien en production.
**En cas de contradiction avec FORGE-ABANDON-FIX (§ Vérifications) sur cet échec, cette passation fait foi.**

## 1. La cause : pas deux tests d'un lot, mais un test qui laissait le Directeur en pause aux lots suivants

L'hypothèse de départ ne tient pas. Les deux tests du lot `director` ne se gênent pas par la configuration :
`DirectorReminderGameTests` ne lit ni n'écrit `narrator2.json` et ne recharge rien (ses Directeurs sont construits sur les
services du runtime et jamais démarrés). La sonde A ci-dessous reproduit l'échec avec le lot `director` réduit au seul
`DirectorEndToEndTests` (`batch 0 (1 tests)`). Les lots, eux, ne se chevauchent pas : `GameTestRunner` ne lance le lot
suivant qu'une fois tous les tests du lot fini (javap de `runBatch` et de son écouteur `testCompleted`). L'ordre des lots,
lui, change à chaque lancement (le regroupement passe par une table de hachage de `Holder`).

**Le journal se lit à l'envers.** `runScript` exécute le `finally` du script avant de rendre son échec, et le GameTest
n'est rapporté qu'au tick suivant. Les lignes du nettoyage de ce test-ci (`reloaded … LLMGW key missing`,
`state paused → disabled`) précèdent donc `a paused Director sends nothing` dans le journal. FORGE-ABANDON-FIX les a prises
pour le nettoyage d'un autre test.

Deux défauts, tous deux dans les tests :

1. **La vérification de la pause comptait toutes les requêtes du faux serveur.** Pendant la pause du Directeur, d'autres
   travaux parlent légitimement à la même passerelle, avec la clé du test. D'abord ceux de la mémoire : le lot du
   Chroniqueur sur le chapitre ouvert, toutes les `memory.chronicleMinutes` (10 min) depuis le démarrage de la mémoire
   (`MemoryJobs.batch` ne saute que `CLOSING` et `DISABLED`, pas `PAUSED`), une sieste de rattrapage ou un rêve. Ensuite
   les tâches déjà lancées. C'est voulu : seul le Directeur se tait (M4 §24.3, décision K). Le Directeur lui-même
   n'envoie rien en pause : son tour est synchrone dans sa boucle, et `PAUSED` n'est publié qu'après la fin du tour.
   *Laquelle de ces requêtes* est tombée dans les 3 s de FORGE-ABANDON-FIX, on ne peut pas le savoir : son worktree et son
   journal n'existent plus. Le plus probable est le lot du Chroniqueur. Un `build` sous charge dépasse 10 min, et chaque
   redémarrage du Narrateur (`restart`, `dashboard_assembly`) remet l'horloge de la mémoire à zéro. Il faut aussi une
   fenêtre non vide : un chapitre ouvert depuis plus de `BATCH_LAG` (5 s), ce qui arrive sous charge.
2. **Le nettoyage ne rendait pas le Directeur comme il l'avait trouvé.** Une pause d'opérateur survit au rechargement qui
   désactive le Directeur : `Director.paused` n'est touché que par `Pause` et `Resume`, et `/narrator resume` est refusé
   tant que le Directeur est désactivé. Le lot suivant qui lui redonne une clé le trouve en pause
   (`state disabled → paused` dans le journal). Toutes ses attentes d'un Directeur calme (`awaitQuiet`, état `IDLE`)
   expirent alors. C'est l'échec de `forge_e2e` ; ce serait celui de n'importe quel lot qui réveille le Directeur ensuite.
   Même famille : un chapitre laissé ouvert par un échec, que le Directeur du lot suivant continue (M3-19).
   `DirectorEndToEndTests`, `MechanicsEndToEndTests`, `StyleGameTests` et `StyleAssemblyGameTests` ne fermaient pas leur
   chapitre dans leur `finally`. `DirectorEndToEndTests` laissait même ouvert, en cas de succès, le chapitre de sa
   dernière étape.

**Comportement de production noté, pas un défaut** : qu'une pause survive au retrait de la clé est cohérent (seul
`/narrator resume` lève une pause, et `CommandGameTests` le dit déjà : « a pause would outlast the missing key »). Rien
n'est à corriger en production.

## 2. Reproduction déterministe (sondes retirées)

Les tests sont filtrés sans toucher au build, par l'environnement du processus Gradle :
`$env:JAVA_TOOL_OPTIONS = '-Dfabric-api.gametest.filter=narrator2-gametest:*_end_to_end_tests_* -Dflaky7.probe=A'`.
`runGameTest` lance alors 10 tests : les lots `director` (le seul test de bout en bout), `forge_e2e`, `mechanics_e2e`,
`memory` et `programme`, plus les tests de bout en bout du lot par défaut. Le filtre de Fabric n'accepte qu'un motif.

- **Sonde A** : pendant la pause, après 5,5 s (la fenêtre d'un lot du Chroniqueur finit 5 s avant son départ),
  `runtime.memory.jobs.requestChronicle(…)`, c'est-à-dire le lot que son minuteur met en file. La sonde attend ensuite la
  requête du Chroniqueur.
- **Sonde B** : une exception juste après la pause, comme une vérification qui échoue à ce moment-là.
- **« La clé suivante »** (avec A et B) : après le nettoyage, le test redonne une clé au Directeur, comme le lot suivant,
  et journalise son état et son chapitre.
- **Sonde R** : avant que `RestartGameTests` ne commence, une décision `operator restarted` de `Server` est écrite dans le
  Journal, comme un lot passé avant lui qui aurait redémarré le Narrateur depuis la console. La sonde **R0** garde en plus
  l'ancien compte absolu.

| Lancement | Résultat |
|---|---|
| Sonde A, code d'avant | **4 rouges** : `director` (`a paused Director sends nothing`, après `sent while paused: [chronicler-…]`), puis `mechanics_e2e`, `forge_e2e` et `programme` (`timed out waiting for: a quiet Director`). Clé suivante : `asleep after the cleanup: chapter 2; the next key finds the Director PAUSED, chapter 2`. Le journal montre l'ordre trompeur décrit au §1 |
| Sonde A, correctif | **10 sur 10 verts** ; clé suivante : `chapter null; … IDLE, chapter null` |
| Sonde B, correctif | 1 rouge, l'échec forcé ; `memory`, `mechanics_e2e` et `programme`, passés après lui, sont verts ; clé suivante : `IDLE, chapter null` |
| Sonde B, **correctif sans la reprise** (`post(Resume)` retiré du nettoyage) | **3 rouges** : l'échec forcé, puis `programme` et `memory` (`a quiet Director`). Le chapitre ne se ferme pas (un Directeur en pause n'a pas de moment calme : `cleanup: the Director's chapter was not closed` au bout de 45 s). Clé suivante : `PAUSED, chapter 3` |
| Sonde R0 (`RestartGameTests` seul) | rouge : `the decision operator restarted`, le message vu par M6-17 |
| Sonde R, correctif | vert |

## 3. Le correctif

- **`DirectorEndToEndTests`, la pause** : seules les requêtes du Directeur (`isDirector`) comptent. L'échec cite le texte
  de réveil de chaque requête fautive.
- **`putTheDirectorToSleep(helper, runtime)`** (dans `DirectorEndToEndTests.kt`, avec `runScript` et `awaitQuiet`) :
  c'est le nettoyage commun des tests qui donnent une clé au Directeur. Il vient en premier dans leur `finally`, tant que
  leur clé et leur faux serveur sont en place. Il fait trois choses dans l'ordre, et chaque étape est tentée même si la
  précédente a échoué :
  1. Il poste `DirectorSignal.Resume`, comme `/narrator resume`, mais sans le refus d'un Directeur qui n'entend pas.
     Un Directeur qui n'est pas en pause l'ignore.
  2. Il ferme le chapitre ouvert avec son résumé (`/narrator chapter close`), puis attend `ended_at` dans le Journal,
     45 s au plus.
  3. Il remet `{}` dans `narrator2.json` et recharge la configuration.

  Le test attend ensuite `DISABLED`, comme avant. Sept tests s'en servent : `DirectorEndToEndTests`, `ForgeEndToEndTests`,
  `MechanicsEndToEndTests`, `MemoryEndToEndTests`, `StyleGameTests`, `StyleAssemblyGameTests` et `ProgrammeEndToEndTests`.
  Forge, Memory et Programme fermaient déjà leur chapitre : leur code de nettoyage est remplacé. Memory et Programme
  gardent leur `closeChapter` pour le chemin du succès, qui attend la sieste ou vérifie le chapitre.
- **`RestartGameTests`** compte ses redémarrages `operator restarted` de `Server` à partir du nombre trouvé à son
  début (`consoleRestarts + 1`, `+ 2`, `+ 3`). Le redémarrage de M6-17 demandé par `gametest-dashboard` reste valable,
  mais n'est plus nécessaire pour ce test : la KDoc de `DashboardAssemblyGameTests.restart` est un peu datée et je ne l'ai
  pas touchée.
- **Coût** : `DirectorEndToEndTests` ferme maintenant, aussi quand il réussit, le chapitre ouvert par sa dernière étape
  (« encore là ? »). Cela ajoute un moment calme (`Director.QUIET_MS`, 20 s) : le test passe d'environ 27 s à environ 47 s.
  Les autres tests ferment déjà leur chapitre quand ils réussissent : rien ne change pour eux.
- J'ai touché les fichiers de M6-17 (`StyleAssemblyGameTests`, `ProgrammeEndToEndTests`) après sa fusion dans `main`, pour
  leur donner le même nettoyage.

## 4. Les autres lots, vérifiés

- **Lots de plusieurs tests dont un recharge la configuration** : il n'y en a que deux.
  - `director` : le test des rappels ne dépend pas de la configuration.
  - Le lot par défaut, avec `NarratorGameTests.reloadKeepsTheServicesAndSwapsTheSettings`. Ce test recharge en plein lot,
    mais sans effet sur ses voisins : le Directeur reste désactivé, les réflexes coupés, et les autres réglages gardent
    leurs valeurs par défaut. Il laisse pourtant une clé en vigueur, pointée sur son faux serveur fermé, jusqu'au prochain
    test de bout en bout. Les travaux de la mémoire peuvent alors appeler un port fermé : des avertissements, sans échec.
    Son `fake.requests.single()` pourrait aussi être pris par une sieste de rattrapage (30 s après un démarrage de la
    mémoire, s'il y a un chapitre à rattraper). C'est négligeable : **pas modifié**.
  - Tous les autres tests qui rechargent la configuration sont seuls dans leur lot.
- **Le seul chemin où un nettoyage recharge la configuration pendant qu'un autre lot tourne** : un script qui épuise son
  budget. `runScript` annule le script à `maxTicks - 1`. Les étapes suspendues de son `finally` sont alors sautées
  (`CancellationException`), et seules les étapes non suspendues tournent : écrire `{}`, recharger. Elles le font au
  moment où le lot suivant démarre, et le joueur du test n'est pas retiré. Aucun échec observé : **pas modifié**. Pour le
  corriger, il faudrait annuler plus tôt (`maxTicks` moins une marge de nettoyage) et exécuter le nettoyage sous
  `NonCancellable`.
- **Comptes absolus dans le Journal partagé** : `RestartGameTests` est corrigé. `JournalEndToEndTests` attend exactement
  deux décisions `operator sql` ; il est le seul test qui lance `/narrator sql` en opérateur (celui
  d'`ObserverMixinGameTests` est refusé : non-opérateur). C'est juste aujourd'hui, mais c'est le même motif à surveiller.

## 5. Ce que les tâches suivantes doivent savoir

- **Un test qui donne une clé au Directeur** appelle `putTheDirectorToSleep(helper, runtime)` en premier dans son
  `finally`, puis fait son propre nettoyage, puis attend `DISABLED`. S'il met le Directeur en pause, c'est ce nettoyage
  qui l'en sort.
- **Un compte de requêtes sur un faux serveur** filtre par rôle (`isDirector`, `isAnalyst`…) : la mémoire et les tâches
  partagent la passerelle du test.
- **Un compte de lignes du Journal** sur un sujet partagé (`Server`, `gametest`…) part d'une base lue au début du test : le
  Journal est celui de tous les lots, dans un ordre qui change à chaque lancement.
- **Lancer un sous-ensemble de GameTests** sans toucher au build : `JAVA_TOOL_OPTIONS=-Dfabric-api.gametest.filter=<motif>`
  dans l'environnement de `gradlew.bat runGameTest`. Le motif est unique, avec `*`, sur l'identifiant du test
  (`narrator2-gametest:<classe>_<méthode>` en snake_case).

## Vérifications

- Sondes A, B, B sans reprise, R0, R : §2. Toutes retirées ; `git diff` ne touche que des fichiers de GameTests.
- **Build complet** (`.\gradlew.bat build`, après la fusion de `main` `a058057`, M6-17) : **2 267 tests unitaires, 0 échec,
  0 ignoré** ; **« All 401 required tests passed »**. Le lot `director` a pris 47 s, contre environ 27 s avant (§3).
- **Deux `runGameTest` de plus** : « All 401 required tests passed » les deux fois. L'ordre des lots changeait d'un
  lancement à l'autre : `restart` est passé une fois avant `director` et une fois après, et `dashboard_assembly` avant
  `restart` les deux fois.
- Aucun appel réel à Claude, pas de `runServer`, aucune charge ajoutée.
