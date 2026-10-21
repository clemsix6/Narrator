# Passation M5-FIX-3 : la fausse fuite de `LeakWatch`, et le repli dans `/narrator trace`

Deux défauts relevés par la recette réelle M5-22 (défaut 4, et la remarque de CLAUDE-FALLBACK sur `/narrator trace`).
**En cas de contradiction avec `M5.md`, une passation plus ancienne (M5-05, M5-11, FLAKY-4) ou CLAUDE-FALLBACK, le code
et cette passation font foi.** `M5.md` §3.7 a un encart « passation M5-FIX-3 ».

## Défaut 1 : `LeakWatch` signalait une fuite après chaque fin de module

### Ce qu'a vu la recette

Pendant la soirée (passe 5, `.research/m5-live/pass5/forge.md`), `LeakWatch` a écrit **trois** WARN « a leak », pas
un seul. C'est donc **chaque** module fini, et pas seulement `orbiting_chickens` :

| Module | Fin | WARN |
|---|---|---|
| `C-1 orbiting_chickens` | 01:44:27, `fulfilled`, 0 faute | 01:46:28 |
| `C-3 mental_math` | 01:51:08, `fulfilled`, 0 faute | 01:53:09 |
| `C-10 metronome` | 01:51:41, `disabled` (deux fautes *limite*) | 01:53:41 |

Chaque WARN tombe 120 s plus 1 s après la fin : le délai, puis la vérification de la seconde suivante. Les « 2
collectes » étaient donc déjà comptées à ce moment-là.

### La cause, prouvée : une collecte jeune ne décharge jamais de classes

- Un chargeur qui a défini des classes n'est collecté **que par une collecte qui décharge des classes**.
- Avec G1, le collecteur du serveur, ce sont deux cas : une collecte complète, ou la *remark* d'un cycle concurrent. Une collecte jeune (ou mixte) traite les données de **chaque** chargeur de classes comme une racine. Le chargeur d'un module lâché y survit donc toujours.
- Or `LeakWatch` comptait **toutes** les collectes (la somme des `GarbageCollectorMXBean`), et les jeunes arrivent toutes les quelques secondes.
- Le cycle concurrent, lui, peut ne jamais venir. Sur cette machine, la JVM du serveur de GameTest a un tas maximal de 8 Gio (un quart des 31 Gio, G1 par ergonomie, vérifié par `-XX:+PrintFlagsFinal`). Le seuil d'un cycle concurrent est de 45 % du tas : un serveur qui occupe 1 Gio n'en lance pas.
- Résultat : au bout de 120 s, chaque chargeur sain était « encore en mémoire après 2 collectes », et `LeakWatch` disait « a leak ».

**La preuve, mesurée** : une sonde dans un GameTest (non commitée telle quelle, gardée dans `LeakWatchGameTests` sous sa
forme finale). Elle utilise :
- le vrai `orbiting_chickens`, construit depuis le jar par une Forge à elle ;
- un moteur des modules à lui (vrai hôte, vrai SDK, vrais outils, vraies créatures), dans un lot à lui ;
- l'**ancien** `LeakWatch`, avec sa propre horloge.

Ce qu'elle a mesuré :
1. Le module finit seul : `fulfilled`, 5 actes, 0 faute, « Les poules sont redescendues sur terre. ». Son chargeur est surveillé.
2. Trois collectes jeunes, provoquées par des déchets de 256 Kio : `G1 Young Generation +3, G1 Concurrent GC +0, G1 Old Generation +0`. Le chargeur, lu par réflexion dans la sonde, est **vivant**.
3. L'horloge avance de 120 s, puis `check()`. On obtient le WARN de la recette, mot pour mot : `the classes of C-1 orbiting_chickens are still in memory 120 s after its end: a leak`.
4. Un seul `System.gc()` (`G1 Old Generation +1`, une collecte complète) : le chargeur est **collecté**.

Le chargeur n'était donc tenu par rien de fort : ni le fil des modules (FLAKY-4 le renouvelle au déchargement), ni les créatures, ni `Storage`, ni un rappel, ni l'`ActQueue`. Il attendait seulement une collecte qui décharge des classes. C'était une **fausse alerte**, causée par la façon de compter, et **pas une fuite**. `ModuleEngine` n'a rien à changer.

`mental_math` et `metronome` n'ont pas été rejoués : leur WARN a la même forme et le même délai, et le comptage faux les explique de la même façon. Le `metronome` désactivé avait levé des exceptions (ses fautes *limite*). FLAKY-4 couvre ce cas : la dernière exception est lâchée avec le fil.

### Le correctif (`forge/host/LeakWatch.kt`)

**Le principe** : `LeakWatch` attend désormais **deux collectes qui déchargent des classes**, et non plus deux collectes quelconques.

**La signature** ne change pas : `LeakWatch(clock, collections)`. C'est le compteur par défaut qui change :
- **avant**, la somme des `GarbageCollectorMXBean` ;
- **maintenant**, `UnloadingCollections().count`, une nouvelle classe interne du même fichier.

**Comment compte `UnloadingCollections`** : par un **canari**.
- Le canari est un chargeur à lui (`ClassLoader(null)`). Il définit une classe vide `narrator2.LeakCanary`, écrite avec ASM.
- Rien ne le tient, sauf une `WeakReference`. Il ne part donc qu'avec une collecte qui décharge des classes, exactement comme le chargeur d'un module lâché.
- À chaque appel de `count()`, si le canari est parti, une collecte est comptée et un canari neuf est posé.
- Un canari vu vivant lors d'un appel part dans une collecte **postérieure** à cet appel. Si le compte a grandi de `n` depuis un appel, il y a donc eu au moins `n` collectes de ce genre après lui. Deux collectes entre deux appels ne comptent que pour une : on peut sous-compter, ce qui est prudent, mais jamais signaler trop tôt.
- Un canari créé pendant un marquage concurrent survit à ce cycle, comme un chargeur lâché pendant ce marquage (SATB). Les deux restent donc cohérents.

**Pourquoi un canari plutôt que les noms des collecteurs** (« G1 Old Generation », « G1 Concurrent GC », « ZGC Major Cycles »…) :
- le canari ne dépend ni du collecteur ni de ses noms : il mesure exactement l'événement qui compte ;
- si la JVM ne décharge jamais de classes (`-XX:-ClassUnloading`), il ne part jamais, et rien n'est jamais dit. C'est juste : dans ce cas, aucun chargeur ne pourrait partir.

**Coût** : une classe vide (environ 1 Kio de métaspace) par collecte complète observée. Elle est définie sur le fil qui appelle `check()` ou `watch()` (le thread serveur), le temps de définir une classe vide (non mesuré à part), une fois par collecte complète au plus. Le premier canari est posé à la construction de `LeakWatch`, au démarrage du moteur. Il n'y a **jamais** de `System.gc()` en jeu.

**Pas aveugle** : une vraie fuite, par exemple un rappel du module resté dans une structure du serveur, est toujours dite. Il faut deux collectes qui déchargent des classes après la fin, plus 120 s (`ForgeLimits.LEAK_GC_CYCLES`, `LEAK_CHECK_MS`, inchangés). La contrepartie : sur un serveur dont le tas est grand et peu rempli, une vraie fuite n'est dite qu'**à la première occasion qu'a eue la JVM de la collecter**. C'est voulu : avant, aucun chargeur, sain ou fuyant, ne pouvait être distingué. La métaspace qui grandit finit d'ailleurs par déclencher elle-même ces collectes (« Metadata GC Threshold »).

**Le texte du WARN** devient : `the classes of C-1 orbiting_chickens are still in memory 120 s and 2 collections that unload classes after its end: a leak`.

**À reprendre par M5-FIX-1** : la décision `forge leak` de `ModuleEngine` dit « … 120 s and 2 collections after its end ». C'est toujours exact. Son propriétaire peut ajouter « that unload classes » s'il le souhaite. Je n'ai pas touché `ModuleEngine.kt`.

## Défaut 2 : `/narrator trace` présentait un repli comme un échec

**Avant** : une requête servie par le modèle de repli s'affichait en rouge avec `refused: cyber`. C'est le cas d'un `llm_calls.model = claude-opus-4-8`, avec `refusal_category = cyber` et `stop_reason = end_turn` ou `tool_use`.

**Maintenant** (`command/TraceCommands.kt`, lignes `Request N` de `trace turn` et de `trace task`) :

| Cas | Ce qu'on lit | Couleur |
|---|---|---|
| un repli qui a répondu | `… · tool_use · fallback (cyber) → claude-opus-4-8` | jaune (`Look.WARNING`, nouveau) |
| un vrai refus de l'API (`stop_reason = refusal`) | `… · refusal · refused: cyber` | rouge |
| **un repli qui n'a rien dit** : CLAUDE-FALLBACK le traite en refus (`SendResult.Refused`), mais sa ligne `llm_calls` ressemble à un repli servi (`end_turn` et `cyber`) | `… · end_turn · refused: cyber` | rouge |
| une erreur | inchangé | rouge |
| le reste | inchangé | gris |

**Comment le dernier cas est reconnu, sans colonne nouvelle** :
- Un refus termine aussitôt son tour ou sa tâche (`AgentLoop` rend `TurnOutcome.Refused`). C'est donc **la dernière requête** :
  - d'un tour fini `refused` (`TurnRecord.Outcome.REFUSED`) ;
  - ou d'une tâche dont l'erreur commence par `refused by the safety filter` (`Workers`, `MemoryAgents`). Ce texte est repris en constante dans `TraceText` (`REFUSED_TASK`) ; `WorkersTest` le fige de son côté.
- Un refus de l'API sans catégorie (`refusal` sans `stop_details`), autrefois gris, est maintenant rouge lui aussi.

**Hors de ma liste, une ligne** : `trace/TraceQueries.kt`. `ModelCall` gagne `model`, la colonne `llm_calls.model`, lue en dernier dans `MODEL_CALLS`. Elle n'est construite que par cette requête.

`/narrator usage` ne change pas : il ne compte comme échec que `error`.

## Tests

| Test | Couvre |
|---|---|
| `forge/host/LeakWatchTest` (nouveau, 3 tests, vrais modules et vraies collectes de la JVM) | (1) Après trois collectes jeunes de G1, le chargeur d'un module fini est vivant. L'ancien comptage (toutes les collectes) le dit fuyant, le nouveau ne juge pas. La première collecte complète le prend, le canari la voit, et il est oublié sans fuite dite. Le test est sauté si un cycle concurrent tombe pendant les collectes jeunes, ou si la JVM n'utilise pas G1. (2) Un module dont le rappel (une lambda du module) reste dans une structure « du serveur » : rien avant 120 s ; ensuite, une fuite dite **une fois** après deux collectes qui déchargent des classes. Lâché, il part. (3) Le `LeakWatch()` du jeu (compteur par défaut), avec un module sain et un module accroché : seul l'accroché est dit, le sain est collecté et oublié. |
| `gametest/LeakWatchGameTests` (nouveau, lot `forge_leak`) | le vrai `orbiting_chickens` sur un moteur des modules à lui, fini `fulfilled`. Son chargeur est vivant après des collectes jeunes seules. Aucune fuite n'est dite 120 s plus tard, malgré les `check()` du moteur à chaque seconde. Le premier `System.gc()` le prend, et la surveillance l'oublie. |
| `ModuleHostTest`, `ModuleEngineTest` (inchangés) | leurs `LeakWatch(clock) { collections }` donnent un compteur à eux. Ils vérifient toujours le délai, le compte, « dit une fois », et le fil perdu qui tient son module (une vraie fuite, dite) |
| `command/TraceTextTest` (+1) | `model` lu dans le Journal ; repli servi en jaune (`fallback (cyber) → claude-opus-4-8`) ; vrai refus en rouge (`refusal · refused: cyber`) ; repli muet à la fin d'un tour `refused` en rouge ; la même requête dans un tour qui a agi, en jaune ; une tâche finie sur le rapport d'un repli (jaune) ou refusée par le filtre (rouge). Les couleurs sont vérifiées sur les composants du chat. |

**Pourquoi un lot à lui** : le GameTest fait collecter le serveur. Une collecte complète fige la JVM, et elle étirerait les ticks d'un test voisin, ceux que mesure `ModuleHostGameTests` par exemple. Le lot `narrator2-gametest:forge_leak` (`test_environment/forge_leak.json`) le fait tourner seul. Sa ligne dans `src/gametest/resources/fabric.mod.json` est une ligne ajoutée à un fichier partagé.

## Vérifications

- **Sonde sur l'ancien code** : voir « La cause ». Elle a été lancée seule par `liveGameTest -Pnarrator2.live.dry -Pnarrator2.live.filter=narrator2-gametest:leak_watch_*`, une passe à sec, sans clé ni appel réel.
- **GameTest final, seul** : vert. Il écrit au journal : « M5-FIX-3 in the server: C-1 orbiting_chickens ended well; its loader alive after young collections (G1 Young Generation +3, G1 Concurrent GC +0, G1 Old Generation +0), no leak told 120 s later; collected after 1 System.gc() (… G1 Old Generation +1) ».
- **Tests ciblés** : `LeakWatchTest` (3, aucun sauté), `TraceTextTest`, `ModuleHostTest`, `ModuleEngineTest`, `trace.*` : verts.
- **Build complet** (`.\gradlew.bat build`, `main` sans commit nouveau depuis `0480272`) : **vert**, **1 957 tests unitaires** (0 échec, 0 ignoré, `LeakWatchTest` non sauté) et **« All 394 required tests passed »** (GameTests : 393, plus `LeakWatchGameTests`).
- **Un premier build** a eu un échec, sans lien avec cette tâche : `TrapGameTests.aGlassPushedInPlaceOfAPaneByAPistonStays` (« both glass blocks moved on tick 10002 »). Il est dans le lot `minecraft:default`, alors que mon test tourne dans `forge_leak`, après lui. Relancé sans rien changer : vert. Ce test pose sa cage avec une échéance murale de 1,5 s (`System.currentTimeMillis() + 1_500`). Si le serveur cale plus de 1,5 s avant que le piston pousse, la vitre peut être retirée avant la poussée, et le test attend alors pour rien. **Instabilité possible, à signaler** au propriétaire de M4-FIX-E.
- Aucun appel réel à Claude ; pas de `runServer`.
