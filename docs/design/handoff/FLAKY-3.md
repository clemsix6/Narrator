# Passation FLAKY-3 : la minute de parole et le tour repris

Mission : deux GameTests échouaient de temps en temps selon l'ordre des lots (signalés par M5-01 et M5-14). Méthode de
FLAKY-1 et FLAKY-2 : la cause réelle d'abord, puis la preuve, sans affaiblir les tests. Branche
`worktree-agent-abdcbee1392271ca8`. **En cas de contradiction avec un document de conception ou une passation plus
ancienne (M4-POST sur `forgetTheMinute`), le code et cette passation font foi.**

| Test | Cause | Où était le défaut | Correction |
|---|---|---|---|
| `RuleEngineGameTests.aRuleLivesThroughARestart`, et trois autres tests qui attendent une réplique | la limite de parole du serveur (8 répliques par minute glissante) est commune à tous les lots : un lot bavard passé juste avant la remplit | banc d'essai | **une règle** : `runScript` oublie la minute de parole au début de chaque histoire |
| `DirectorEndToEndTests.aChatWakesTheDirectorWhoAnswersWithItsRealTools` | un grief laissé par un lot précédent est dit 10 s plus tard, daté de la casse : il réveille le Directeur sur-le-champ (`salient`) juste après le tour de la prière, et le test lisait le **dernier** tour du chapitre | test | le test lit le **tour repris** : le premier tour du chapitre après la pause |

La limite de production ne change pas : 8 répliques par minute, dont 4 pour les actes du moteur, 10 sons par minute.
`forgetTheMinute` n'a toujours aucun appelant en production.

## 1. La parole

### Cause

`ToolLimits.speech` est un seul `SpeechLimiter` par serveur. Tous les lots de GameTests tournent sur le même serveur,
l'un après l'autre, dans un ordre tiré au sort. Deux lots parlent par cette limite :
- `director` : 5 répliques en une trentaine de secondes ;
- `mechanics_e2e` : 3 répliques en une dizaine de secondes.

Si ces deux lots passent en moins d'une minute, juste avant un test qui attend une réplique, la fenêtre est pleine. Le
`say` est alors refusé : `DENIED (speech_limit): 8 lines already in the last minute`, comme l'a vu M5-14.

### Les tests concernés

Ce sont ceux qui attendent qu'une réplique passe par la limite du serveur. Je les ai cherchés dans tous les GameTests
(`say`, `play_sound`, `show_title`, `give_book`, `speechTools`, `allTools`, `toolSetFor`, les rigs
`NarratorServices by runtime`) :

| Test | Lot | La réplique attendue |
|---|---|---|
| `RuleEngineGameTests.aRuleLivesThroughARestart` | `rules` | la loi `bonjour` répond par `say`, un acte du moteur du banc (`Rig`, sur les services du serveur, donc sur sa limite) |
| `MechanicsAssemblyGameTests.aLawMadeByTheRealToolFiresAndIsRetired` | `assembly` | la loi répond par `say`, un acte du moteur du serveur |
| `DirectorEndToEndTests.aChatWakesTheDirectorWhoAnswersWithItsRealTools` | `director` | le `say` de la salutation doit rendre `ok: said to everyone` |
| `MechanicsEndToEndTests.aChallengeALawAndAnOfferingThroughTheDirector` | `mechanics_e2e` | les félicitations (déjà protégées par M4-POST, protection remplacée par la règle) |

Les autres ne sont pas concernés :
- les tests des outils de parole (`SpeechGameTests`) et du temps (`WorldActionGameTests`) ont leur propre limiteur ;
- les défis, duels, paris et offrandes n'annoncent rien par `say` : leurs lignes passent par le registre, sans limite de parole. Seules les félicitations du Directeur en dépendent, dans `MechanicsEndToEndTests` ;
- `ModuleActsGameTests` passe par `speechTools(runtime)`, mais n'utilise que `hud` (un délai par joueur, et chaque test a ses joueurs) ;
- les autres limites du serveur ne débordent pas d'un lot à l'autre : `gifts` est compté par joueur, `commands` par tour, `world` a son propre limiteur dans les tests.

### Correction : une règle unique dans `runScript`

`runScript` (dans `DirectorEndToEndTests.kt`) est le point d'entrée commun des quatre tests : chacun est une histoire
jouée en temps réel. Désormais, toute histoire commence par `narrator().toolLimits.speech.forgetTheMinute()`. La règle
est écrite dans la KDoc de `runScript` : **un test qui attend une réplique passée par les limites du serveur est un
script ; tout autre test a son propre `SpeechLimiter`.**

Détails :
- `forgetTheMinute` (M4-POST) oublie les répliques et les sons de la minute glissante, part du moteur comprise, comme un
  redémarrage. Les délais par joueur et les comptes par tour restent ;
- l'appel posé par M4-POST dans `MechanicsEndToEndTests` est retiré, puisque la règle le couvre ;
- environ 90 appels de `runScript` (défis, duels, règles, Forge, tests réels…) oublient aussi la minute à leur
  départ. C'est sans effet pour eux : aucun ne vérifie un refus de la limite du serveur, puisque les tests de limites
  ont leur propre limiteur. Les tests d'un même lot démarrent ensemble, donc l'oubli n'efface que ce qu'ont dit les
  lots précédents ;
- les tests réels (`LiveDirectorGameTests`, `LiveMechanicsGameTests`) sont des scripts : un lot réel ne trouve plus
  la minute dépensée par le précédent.

Deux options écartées :
- **un environnement de test** : un type `TestEnvironmentDefinition` maison, listé dans chaque `test_environment/*.json`,
  qui oublierait la minute au début du lot. Il faut enregistrer un type, ajouter un point d'entrée `main` au mod de
  test et modifier 12 fichiers, et les lots par défaut (`minecraft:default`) ne seraient pas couverts ;
- **un limiteur propre au banc** : c'est impossible pour les tests qui jouent le Directeur et le moteur du serveur
  eux-mêmes (`assembly`, `director`, `mechanics_e2e`).

## 2. `DirectorEndToEndTests` : un grief d'un autre lot

### Cause

À l'étape 4, le test lisait le déclencheur du **dernier** tour du chapitre
(`SELECT trigger FROM turns WHERE conversation_id = ? ORDER BY turn_id DESC LIMIT 1`), après `awaitQuiet`. Il échoue
dès qu'un tour se glisse après le tour de la prière. Ce tour `salient` vient d'un **grief laissé par un lot
précédent** :
- `Salience.grief` compte les blocs d'un joueur cassés par un autre pendant `GRIEF_WINDOW_MS` (10 s), puis poste **un**
  événement `SOON`, daté de la **première casse** (`window.first`) ;
- l'élément arrive donc vieux de 10 s : `WakeScheduler.wakeAt` (sa date + 3 s) est déjà passé, et le Directeur se
  réveille sur-le-champ, avec le déclencheur `salient` ;
- la coroutine du grief survit à son test et à son lot. Les joueurs du grief sont partis, mais le Directeur du lot
  suivant reçoit l'événement, s'il a une clé.

C'est visible dans un lancement normal (`base1`, vert) : le lot `memory`, dont l'unique test a un seul joueur (`me_`),
passe juste après `rules` et `forge`, et son Directeur se réveille deux fois pour les griefs de ces lots :

```
[20:44:27] Running test environment 'narrator2-gametest:rules' batch 0 (11 tests)...
[20:44:28] Running test environment 'narrator2-gametest:forge' batch 0 (16 tests)...
[20:44:30] Running test environment 'narrator2-gametest:memory' batch 0 (1 tests)...
[20:44:37] [Director] soon re_3a8a00fd85d24: grief: broke 1 block placed by re_11ffc6cef3524 within 10s: oak_planks 1
[20:44:37] [Director] woke turn 2: salient + 4 items
[20:44:38] [Director] soon wfaa6097fd4b68f4: grief: broke 1 block placed by wfb8c82e4bd874c4 within 10s: oak_planks 1
[20:44:38] [Director] woke turn 3: salient + 1 item
```

Dans `DirectorEndToEndTests`, la reprise arrive environ 7 s après le début du lot. Un grief né d'une casse faite
environ 3 s avant la fin du lot précédent tombe donc juste après le tour de la prière. `awaitQuiet` attend alors la
fin du tour `salient`, qui devient le dernier tour du chapitre : « the resumed turn's trigger: [salient] ». Les casses
de joueurs ne manquent pas : `RuleEngineGameTests.whoBreaksAnotherPlayersBuildIsMadeTiny`, les tests des blocs posés,
de la Forge…

Causes écartées :
- **un battement** : `heartbeatMinutes` vaut `[60, 60]` dans ce test ;
- **un rappel** : son déclencheur serait `reminder`, et les Directeurs de `DirectorReminderGameTests` (même lot) ne
  sont jamais démarrés ;
- **un événement pendant la pause** : `mute()` jette tout événement qui n'est pas immédiat ;
- **un événement pendant le tour de la prière** : il y est injecté (`<new_events>`) et ne réveille rien ensuite.

Le code de production n'est pas en cause : un grief dit 10 s après la casse qui réveille le Directeur, c'est voulu
(M1_M2 §12.3). C'est le test qui supposait qu'aucun tour ne suivrait celui de la prière.

### Correction

Le test lit **le tour repris**, c'est-à-dire le premier tour du chapitre après la pause :
- une fois l'état `PAUSED` atteint, il relève `max(turn_id)` du chapitre. Aucun tour ne tourne en pause : la pause
  attend la fin du tour en cours ;
- après la reprise, il lit `turn_id > ce repère ORDER BY turn_id LIMIT 1`.

Le premier tour après une pause est toujours celui de la reprise : `resumeWake` réveille le Directeur aussitôt, avec
tout ce que contient sa boîte. Ce que le test prouve ne change pas :
- le tour ouvert par la reprise a le déclencheur `pray`, la prière passant avant `resume` (`triggerOf`) ;
- sa requête porte la prière et la note de silence. Ces deux vérifications sur `resumed` sont inchangées.

Les autres étapes supportent déjà un tour de plus, d'après la lecture du test :
- l'étape 1 désigne le tour de la salutation par sa réplique (`chat.turn_id`) ;
- toute requête du chapitre porte le briefing en premier message ;
- `why`, `why <joueur>` et `trace` sont vérifiés avec `any` sur toutes leurs lignes ;
- l'étape 5 cherche ses requêtes par leur contenu.

## Vérifications

Toutes les passes sont des `runGameTest` complets, sauf W2 (filtre `JAVA_TOOL_OPTIONS=-Dfabric-api.gametest.filter=…`).
L'ordre des lots vient des journaux (`Running test environment …`). `default×7` désigne les sept lots par défaut
(50 tests chacun, 30 pour le dernier) et `default(1)` le lot par défaut d'un seul test.

### Essais forcés (sondes non commitées, retirées)

Deux sondes :
- **sonde de parole** : au départ de chacun des quatre tests concernés, avant `runScript`, 8 répliques sont dites par la
  limite du serveur. Le journal le confirme : `PROBE minute spent: 8 lines allowed, then Denied(… 8 lines already in the last minute …)` ;
- **sonde de grief** : dans `DirectorEndToEndTests`, une fois le tour de la prière fini, un joueur casse les planches
  d'un autre (`PlacedBlocks.mark`, `destroyBlock`), puis le test attend 12 s avant `awaitQuiet` et la vérification.

| Passe | Code | Sondes | Résultat |
|---|---|---|---|
| `probe_with` | correctif | les deux | **vert**, « All 372 required tests passed ». Le grief est dit 10 s plus tard (`soon gx_…: grief…`) et réveille le Directeur (`woke turn 5: salient + 2 items`) ; le test lit quand même le tour repris, `pray` |
| W1, `probe_without_speech` | sans la règle de `runScript`, avec l'appel d'origine de M4-POST dans `MechanicsEndToEndTests` | parole | **rouge, 3 tests**, l'échec de M5-14 : `director` (`say: DENIED (speech_limit): 8 lines already in the last minute`), `rules` (`action: say denied … 8 lines already in the last minute`, puis « timed out waiting for: the greeting after the restart »), `assembly` (« timed out waiting for: the law's answer ») ; `mechanics_e2e` passe grâce à l'appel de M4-POST |
| W2, `probe_without_director` | l'ancienne requête (le dernier tour), avec la règle de `runScript` | grief | **rouge**, l'échec de M5-14 : « the resumed turn's trigger: [salient] » |

### Cinq lancements verts d'affilée (code final, sans sonde)

Ces cinq `runGameTest` ont tourné sur le commit `39f955c`, sans rien changer entre deux. Les cinq sont verts, avec
« All 372 required tests passed ».

| Passe | Ordre des lots |
|---|---|
| 1 | assembly > default×7 > mechanics > forge > **director > mechanics_e2e** > live_evening > live_evals > **rules** > memory > default(1) > mechanics_commands |
| 2 | forge > memory > assembly > mechanics > mechanics_e2e > rules > mechanics_commands > default(1) > live_evals > default×7 > live_evening > director |
| 3 | forge > mechanics_commands > director > memory > rules > default×7 > mechanics_e2e > mechanics > assembly > live_evals > live_evening > default(1) |
| 4 | assembly > rules > memory > default×7 > director > live_evals > forge > mechanics_e2e > mechanics > default(1) > live_evening > mechanics_commands |
| 5 | mechanics_commands > assembly > mechanics_e2e > mechanics > live_evals > rules > memory > live_evening > default(1) > default×7 > director > forge |

**La passe 1 a rencontré les deux cas d'échec, sans sonde** :
- **la parole** : `director` (5 répliques, de 21:04:06 à 21:04:34) puis `mechanics_e2e` (3 répliques, jusqu'à
  21:04:42), puis `rules` à 21:05:02. C'est l'ordre de l'échec de M5-14 : sans la règle, la salutation de la loi
  aurait trouvé 8 répliques dans la minute ;
- **le grief** : `forge` tourne juste avant `director`. Juste après le tour de la prière (`woke turn 6: pray`,
  21:04:13), un grief du lot `forge` arrive (`soon wfa3632dcce9e7f4: grief: broke 1 block placed by
  wfb63b67127f6384…`, 21:04:14) et réveille le Directeur (`woke turn 7: salient + 1 item`). L'ancienne requête
  aurait lu `[salient]` ; la nouvelle lit le tour repris, `pray`.

Passe 3 : un grief du lot `forge` arrive aussi pendant le lot `director` (21:08:59), mais pendant la pause. `mute()` le
jette, et le tour de la prière n'a pas d'autre élément (`woke turn 4: pray`) : aucun tour de plus.

Ordres couverts :
- `director` est passé troisième, au milieu, avant-dernier et dernier ;
- `rules` est passé juste après un lot bavard : passe 1, et passe 2 (juste après `mechanics_e2e`) ;
- `assembly` est passé premier (passes 1 et 4), après `mechanics_e2e` (passe 3) et juste avant (passe 5).

### Le reste

- **Lancement de référence** (`base1`, avant tout changement) : vert. C'est lui qui montre les griefs des lots `rules`
  et `forge` réveillant le Directeur du lot `memory` (extrait plus haut).
- `git merge main` (M5-08, `e43a1df`) : sans conflit, aucun GameTest touché.
- `.\gradlew.bat build` sur l'état fusionné :
  - un premier essai a échoué sur un test unitaire **hors de cette tâche** :
    `RewriteBatteryTest` › *a sub-list, a list iterator and a stream builder count against their limit* (Forge,
    M5-04). Le cas `SubSubList` insère au milieu d'une `ArrayList` (coût quadratique) : sous la charge des builds
    parallèles, le budget de temps (20,4 ms) se déclenche avant la limite de 10 000 éléments
    (`time budget at SubSubList.java:8` au lieu de `a collection over 10000 elements`). Je n'y ai pas touché : c'est
    le domaine de la Forge (M5-05) ;
  - le second essai, sans rien changer, est **vert** : **1 792 tests unitaires** (0 échec, 0 ignoré) et « All 372
    required tests passed ». Ordre des lots : mechanics_e2e > assembly > director > live_evening >
    mechanics_commands > forge > mechanics > live_evals > rules > default×7 > default(1) > memory.
- Aucun appel réel à Claude ni à Jev ; pas de `runServer`. Aucune nouvelle signature de Minecraft.

## Fichiers

- `gametest/.../DirectorEndToEndTests.kt` : `runScript` oublie la minute de parole (KDoc : la règle) ; l'étape 4 lit
  le tour repris.
- `gametest/.../MechanicsEndToEndTests.kt` : l'appel de M4-POST retiré, la KDoc renvoie à `runScript`.
- `main/.../tool/speech/SpeechLimiter.kt` : KDoc de `forgetTheMinute` seulement.

## Pour la suite

- **Parole** : un GameTest qui attend une réplique passée par les limites du serveur est un script (`runScript`). Sinon,
  il construit son propre `SpeechLimiter`, comme `SpeechGameTests`. Cela vaut pour un futur test de la Forge qui ferait
  dire une ligne à un module par `speechTools(runtime)`.
- **Tests de bout en bout du Directeur** : ne jamais lire « le dernier tour ». Des lots précédents laissent des griefs
  jusqu'à 10 s après leur fin, et ils réveillent aussitôt un Directeur qui a une clé (`MemoryEndToEndTests` et
  `MechanicsEndToEndTests` en voient aussi, sans dommage). Désigner un tour par ce qui le caractérise : sa réplique, ou
  son rang après un repère.
- La passation M4-POST (« un autre test de bout en bout qui fait parler ce Directeur devrait appeler
  `forgetTheMinute` ») est remplacée par la règle de `runScript`.
