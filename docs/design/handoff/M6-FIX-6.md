# Passation M6-FIX-6 : le redémarrage

Lot M6-FIX-6 de `reviews/M6_REVIEW_2.md` : constats 4, 5 et 6, et les trous de tests du redémarrage (constat 12).
Branche `worktree-agent-a15fdcfbd7f680d74`. **En cas de contradiction avec la passation de M6-13 ou `M6.md` §9, le code et
cette passation font foi.**

## Constat 4 : jamais deux Journaux sur le fichier, par conception

Le défaut : un ancien Journal qui ne se ferme pas dans ses 10 s laissait le redémarrage préparer le nouveau. DuckDB
refusait alors la seconde ouverture (« configuration has been locked »), par chance, et le Narrateur revenait sans Journal
avec une raison trompeuse.

Ce qui est en place :

| Où | Quoi |
|---|---|
| `journal/Journal.kt` | `val holdsDatabase: Boolean` (faux par défaut) : le Journal tient encore sa base, de son ouverture jusqu'à ce que `close` l'ait fermée, pannes comprises (son fil d'écriture la rouvre). Une fermeture qui a dépassé son temps la laisse tenue jusqu'à la fin du fil d'écriture. `DisabledJournal` et les journaux factices des tests gardent la valeur par défaut |
| `journal/DuckJournal.kt` (fermeture seulement) | `holdsDatabase` est vrai jusqu'à ce que `closeResources` ait fermé la base. Elle peut être fermée par `close`, ou plus tard par le fil d'écriture lui-même quand il finit. Le message dit désormais `the database stays open until the writer ends` (et non plus « until the server exits », qui était faux). Un nouvel appel de `close` attend de nouveau le fil, dans son nouveau délai |
| `runtime/ShutdownSequence.kt` | `closeJournal` lit `holdsDatabase` : `WARN stopping: the Journal did not close within its 10000 ms: its database stays open until its writer ends` au lieu de `Journal closed in 10012 ms`. `close()` ne tourne qu'une fois. Rappelé alors que le Journal tient encore sa base, il n'attend que lui, une fois de plus et avec la même borne : ni le bus, ni les clients, ni la phase ne sont repris |
| `NarratorRuntime.kt` | `stopAndClose()` lève `its Journal is still closing after 10 s, its database open until its writer ends` tant que le Journal tient sa base. Il peut être rappelé, et attend alors ce Journal une fois de plus. `abandon()` (échec au démarrage du serveur) attrape cette exception et la journalise |
| `runtime/RestartSequence.kt` | `unclosed` : les Narrateurs qu'un redémarrage a arrêtés ou créés, et dont la fermeture a échoué. Le redémarrage suivant les ferme **d'abord** (`stopAndClose` de nouveau), dans l'étape « the old Narrator did not stop », et ne prépare rien tant que l'un d'eux échoue encore |

**L'invariant** : un redémarrage ne prépare un Narrateur (et donc n'ouvre un Journal) qu'une fois fermés, sans exception,
tous les Narrateurs qu'il a arrêtés ou créés jusque-là. Il couvre l'ancien Narrateur, un nouveau Narrateur au câblage ou à
la préparation ratés, et le redémarrage suivant.

Pour que la séquence ferme aussi un Narrateur dont la **préparation** échoue, `Restartable` gagne `prepare()`. La séquence
reçoit une fabrique `create` (au lieu de `prepare`), puis appelle elle-même `prepare()`, de sorte qu'elle possède tout
Narrateur qu'elle a créé. `NarratorRuntime.prepare(server)` disparaît. `NarratorRuntime.create(server)` devient publique :
c'est la fabrique par défaut, et celle du GameTest. Le `try/catch` qui refermait la préparation ratée dans
`NarratorRuntime` disparaît aussi : c'est la séquence qui ferme, par le même chemin que pour tout autre échec.

Ce que voit l'opérateur :

```
> narrator restart
[Narrator] restarting the Narrator; the logins wait until it is done…
[Narrator] the restart failed after 15.0 s, the Narrator stays off: the old Narrator did not stop: its Journal is still
closing after 10 s, its database open until its writer ends; /narrator restart tries again.
> narrator restart                        (une fois le fil d'écriture fini)
[Narrator] Narrator restarted in 0.6 s: Journal available · memory on · mechanics on · forge on
```

Dans le journal du serveur, au second : `restart: the Narrator an earlier restart could not close is closed`.

**Limites, assumées** :
- Le démarrage du serveur (`SERVER_STARTED`) ne regarde pas `unclosed`. Sur un serveur dédié, une JVM ne démarre
  qu'un serveur. Sur un serveur intégré qui rouvrirait le même monde après un arrêt dont le Journal a dépassé son
  temps, DuckDB refuserait la seconde ouverture comme avant (sans corruption). La garde au niveau du fichier serait dans
  `openJournalOnce` (`journal/JournalModule.kt`), hors de ma fiche.
- Un Journal qui dépasse son temps à l'arrêt du serveur n'est pas attendu au-delà : son fil d'écriture est un démon,
  comme avant.

## Constat 5 : le refus nomme sans adresse

`RestartSequence.joiningName(listener)` : pour `ServerLoginPacketListenerImpl`, le nom tiré de `getUserName()` par
`loginName`. Ce nom vaut `<nom> (<adresse>)` une fois le paquet `hello` reçu, et l'adresse seule avant (vérifié à
javap : `requestedUsername`, `getLoggableAddress(logIPs)`, qui rend l'adresse, `IP hidden` ou `local`). `loginName`
garde ce qui précède ` (` ; sans nom, c'est `a connection`. Un nom ne contient jamais ` (` : `handleHello` le valide par
`StringUtil.isValidPlayerName` avant de le garder (javap). Pour `ServerConfigurationPacketListenerImpl`, c'est
`owner.name()`, comme avant. Exemple : `cannot restart: 2 players joining (Bartok, a connection); …`.

## Constat 6 : chemins secondaires et libellés

- **Arrêt du serveur pendant l'étape 2** : `stopping()` est testé juste après la fermeture de l'ancien Narrateur. Si le
  serveur s'arrête, aucun nouveau Narrateur n'est créé ni préparé (aucun Journal ouvert, aucune lecture de la mémoire), et
  la raison est `the server is stopping`. Le cas d'un arrêt pendant la préparation reste traité au câblage, comme avant.
- **`RestartReport.times()`** : sans `· N ms without a Narrator` quand le redémarrage échoue (le Narrateur reste
  éteint). Le temps d'un arrêt qui échoue compte désormais son attente (`stop 15000 ms`, et non plus `stop 0 ms`, vu
  dans le premier build : la mesure sautait quand la fermeture levait).
- **KDoc** : le Journal, « 10 s and 2 s of grace » (et non 14 s).
- **Pendant l'échange**, `/narrator status` et toute commande qui passe par `runningNarrator` répondent `a restart is
  running, try again once it is done.` (`Replies.notRunning()`, lu aussi par `AdminCommands.status`). Ailleurs, la
  réponse reste `the Narrator is not running, see the server log.`.
- **Libellés neutres**, sans changer d'interface :
  - `ModuleEngine.stopping` : `the Narrator stops: N modules stopped until it starts again (…), M acts in their queues
    dropped` ;
  - `RuleEngine.stopping` : `the Narrator stops: N firings in progress and M waiting dropped` ;
  - la cause d'annulation du travail en cours (`ShutdownSequence`) : `the Narrator stops`.

  Seul le libellé change dans `ModuleEngine.kt` (coordination avec FORGE-ABANDON-FIX). Les assertions de
  `ModuleEngineTest` et `RuleEngineTest` sur ces deux textes sont mises à jour (une ligne chacune).
- **Forge arrêtée en pleine construction** (`ForgeService`) : quand une construction de module intégré est refusée parce
  que la Forge est arrêtée, `builtIns` sort de la boucle avec `INFO the Forge stopped while it built its built-in modules:
  N built, <nom> and the rest not`, et non plus `ERROR the built-in module … was not built: the server is stopping`.
  `open` ne dit plus `the Forge is ready` d'une Forge arrêtée, et `checkForged` s'arrête sans `WARN`. La raison
  `unavailableReason` (`the server is stopping`) ne change pas : `ForgeSetupTest` la lit, et seul l'ancien runtime, qui
  n'est plus `current`, la porte.
- **KDoc de `NarratorServices`** : « as long as the runtime », sauf `server`, qui lui survit, et `settings`.

## Tests

| Test | Ce qu'il garde |
|---|---|
| `RestartSequenceTest` › *an old Narrator whose Journal is still closing leaves the Narrator off, nothing prepared, and the next restart closes it first* | constat 4 : échec clair, rien de créé ; second essai, refermé d'abord, toujours rien de préparé ; troisième, une fois fermé, prépare et câble ; quatrième, l'ancien n'est plus jamais rappelé |
| `RestartSequenceTest` › *a new Narrator that does not close after a failed wiring is closed first by the next restart* | constat 4, côté nouveau Narrateur |
| `RestartSequenceTest` › *a preparation that fails closes what it opened…* | la séquence ferme le Narrateur à la préparation ratée ; une fabrique qui lève ne laisse rien à fermer |
| `RestartSequenceTest` › *a server that stops while the old Narrator closes gets no new Narrator, not even prepared* | constat 6, premier point, et `times()` sans le trou |
| `RestartSequenceTest` › *a server that starts stopping while the new Narrator is prepared…* | l'ancien chemin (fermé, jamais câblé) |
| `RestartSequenceTest` › *a player joining is named without the address…* | constat 5, sur les formes exactes de `getUserName()` |
| `ShutdownSequenceTest` › *a Journal still holding its database after its close is said so, and a new close waits for it alone* | le message, et `close()` une seule fois plus le Journal seul |
| `DuckJournalTest` › *a writer still running at the close's timeout holds the database until it ends, then the file opens again* | un vrai `DuckJournal` dont le fil d'écriture est retenu par une tâche d'écriture : `holdsDatabase` vrai après `close`, un second `close` attend en vain, puis le fil ferme la base lui-même ; le fichier se rouvre dans la JVM |
| `ForgeStopTest` (nouveau) | une vraie Forge arrêtée après son premier module intégré : aucun `ERROR` ni `WARN`, jamais `ready` |
| `RestartGameTests` | un vrai `ServerLoginPacketListenerImpl` (adresse et `requestedUsername` posés par réflexion) : `a connection`, puis `Bartok`. Puis le constat 4 de bout en bout : le fil d'écriture du vrai Journal retenu par une tâche d'écriture, `/narrator restart` échoue avec la raison exacte, la fabrique jamais appelée, `current` nul, l'ancien Journal ouvert ; `/narrator status` dit `a restart is running…` pendant l'échange ; le fil libéré, `/narrator restart` rend un Narrateur entier, et trois décisions `operator restarted` par `Server` |

**Sondes** (le correctif retiré, puis remis à l'identique ; arbre vérifié ensuite) : sans la reprise de `unclosed`, les
deux tests du constat 4 rougissent (`expected: <the old Narrator did not stop: …> but was: <null>`). Sans le test de
`stopping()`, le test de l'arrêt rougit (`new prepare` dans les étapes). Sans la garde de `close()`, le test de
`ShutdownSequence` rougit (`phase STOPPED` répété). Sans la sortie de `builtIns`, `ForgeStopTest` rougit avec
exactement le défaut vu par M6-13 : `[Forge] the built-in module parade was not built: the server is stopping`.

## Ce que les tâches suivantes doivent savoir

- **`Restartable`** gagne `prepare()`. `RestartSequence.start(…, create, …)` remplace `prepare`.
  `NarratorRuntime.restart(…, create = { NarratorRuntime.create(server) })` : un test qui tient le fichier du Journal le
  fait dans `create`, la séquence prépare ensuite.
- **`stopAndClose` peut lever** (Journal encore ouvert) et peut être rappelé ; `ShutdownSequence.close()` ne tourne
  qu'une fois.
- La passation de M6-13 est dépassée sur trois points : le Journal se ferme en 10 s plus 2 s de grâce (et non 14 s) ;
  « Journal closed toujours avant opened » est désormais garanti par conception ; l'échec d'une préparation est fermé
  par la séquence.
- Le `RestartGameTests` remplace maintenant le runtime du serveur **cinq** fois, dont une attente d'environ 15 s (mémoire,
  puis Journal retenus) : les lots qui passent après lui tournent sur le dernier runtime.

## Vérifications

- **Tests ciblés** (`RestartSequenceTest`, `ShutdownSequenceTest`, `DuckJournalTest`, `ForgeStopTest`, `RuleEngineTest`,
  `ModuleEngineTest`) : verts. Les cinq sondes ci-dessus sont rouges, chacune sur sa vérification. Les fichiers ont
  ensuite été remis depuis une copie, et l'arbre vérifié par recherche des marques de sonde.
- **Premier build complet** (`.\gradlew.bat build --continue`, après la fusion de `main` à `68807c1`, M6-FIX-2) :
  vert en 10 min 43 s, **2 232 tests unitaires, 0 échec, 0 ignoré**, **« All 398 required tests passed »**. Dans le
  journal du GameTest :
  - le redémarrage au Journal retenu échoue après 15,0 s avec la raison exacte, puis
    `restart: the Narrator an earlier restart could not close is closed`, puis `Narrator restarted in 0.1 s: Journal
    available · memory on · mechanics on · forge on` ;
  - une Forge arrêtée pendant sa construction par le redémarrage suivant écrit `INFO the Forge stopped while it built
    its built-in modules: 1 built, parade and the rest not`, au lieu de l'`ERROR` vu par M6-13.
- **Correctif après ce build** : le temps d'arrêt d'un échec (voir le constat 6). `RestartSequenceTest` et
  `RestartGameTests` le vérifient (`stopMs` ≥ 50 ms, et ≥ 10 s en jeu).
- **Serveur réel** (`runServer`, `TheServer/` neuf du worktree, monde plat, port 25971, RCON 25972,
  `gateway.baseUrl` = `http://127.0.0.1:1`, Directeur et réflexes coupés, EULA acceptée). Par RCON, avec un petit
  client PowerShell dans le dossier de travail de la session :
  - `narrator restart` puis, aussitôt, `narrator status` : `[Narrator] restarting the Narrator; the logins wait until
    it is done…`, puis `[Narrator] a restart is running, try again once it is done.` ;
  - le redémarrage prend 0,1 s (`stop 38 ms · prepare 69 ms · wire 1 ms · 107 ms without a Narrator`), un second 1,2 s
    plus tard aussi, et `/narrator status` est entier ensuite ;
  - `stop` est propre (`Journal closed in 26 ms`, `stopped`) et la JVM sort avec le code 0 ;
  - aucune exception ni `ERROR` du mod (seul `No key layers` de Minecraft). La Forge a construit ses 7 modules en
    140 à 168 ms, trop vite pour être prise en pleine construction : ce cas est vu par le GameTest.

  Serveur fermé, `TheServer/` supprimé.
- **Build final** (`.\gradlew.bat build --continue`, après la fusion de `main` à `4ba51ba`, M6-FIX-7 et M6-FIX-8) :
  vert en 6 min 54 s, **2 237 tests unitaires, 0 échec, 0 ignoré**, **« All 398 required tests passed »**. Le
  redémarrage au Journal retenu y dit `the restart failed after 15.0 s, … (stop 15002 ms · prepare 0 ms · wire 0 ms)`.
  Aucun test sans rapport n'a échoué, dans ce build comme dans le premier.
- Aucun appel réel à Claude. Journaux sous des noms `m6fix6-*` (dossier de travail de la session).
