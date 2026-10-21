# Passation M6-POLISH : cinq reliquats de M6

Les reliquats rangés par le superviseur dans M6-POLISH (passation M6-18, « Décisions du superviseur »), relevés par
M6-FIX-6, FORGE-ABANDON-FIX, M6-FIX-10, M6-17 et FLAKY-7. Branche `worktree-agent-a074eecf7ea319cbb`.
**En cas de contradiction avec ces passations, le code et celle-ci font foi.**

| # | Reliquat | Fichiers |
|---|---|---|
| 1 | Jamais deux Journaux sur un fichier, au démarrage du serveur aussi | `journal/DuckDatabase.kt`, KDoc de `journal/JournalModule.kt`, `journal/OpenRetry.kt`, `NarratorRuntime.kt` (`start`), `runtime/RestartSequence.kt` ; tests `DuckDatabaseTest`, `JournalOpenRetryTest` |
| 2 | L'arrêt du serveur : une seule échéance pour tous les `stop` | `forge/host/ModuleHost.kt` (`stop`), `mechanics/module/ModuleEngine.kt` ; test `ModuleEngineTest` |
| 3 | Le dossier temporaire de `TestServices` | `testing/TestServices.kt` (tests) |
| 4 | Le fuseau recopié dans `DreamInputs.kt` | `memory/dream/DreamInputs.kt`, `journal/DuckDatabase.kt` (`Options.of`) ; test `DreamInputsTest` |
| 5 | Les comptes absolus de `JournalEndToEndTests` | `gametest/JournalEndToEndTests.kt` |

Documentation : `ARCHITECTURE.md`, principe 3 de la Forge (§10.2) et décision §17.8 (ouverture du Journal).

## 1. Jamais deux Journaux sur un fichier, au démarrage aussi

### Le trou réel

**Sonde** (test jetable, retiré) : un vrai `DuckJournal` ouvert, puis un second `DuckDatabase.open` du même fichier dans
la même JVM. DuckDB ne refuse pas ce second accès : il le branche sur la base déjà ouverte (son cache d'instances), et
il n'échoue que plus loin, par chance :

- mêmes options : `Invalid Input Error: Cannot change configuration option "TimeZone" - the configuration has been
  locked`, parce que le premier Journal a verrouillé sa configuration (étape 6 de `DuckDatabase`) ;
- autres options : `Connection Error: Can't open a connection to same database file with a different configuration than
  existing connections`.

Pour `isTransientOpenFailure`, les deux erreurs sont **permanentes** : aucune nouvelle tentative, et un `DisabledJournal`
dont la raison ne dit rien de la vraie cause. C'est exactement ce que M6-FIX-6 avait vu au redémarrage.

**Où le démarrage du serveur peut y tomber** :

- `fabric.mod.json` déclare `"environment": "*"` : le mod se charge aussi sur un client, donc sur un **serveur intégré**.
  Un joueur quitte son monde, le Journal dépasse ses 10 s de fermeture et son fil d'écriture (un démon) continue. Il
  rouvre le même monde dans la même JVM : `SERVER_STARTED` ouvre un second Journal sur le fichier.
- Un `/narrator restart` raté laisse son Narrateur dans `unclosed`, puis le serveur s'arrête. Personne ne ferme ce
  Narrateur (`current` est nul), et la suite est la même si le monde est rouvert dans la JVM.
- `NarratorRuntime.start` avec un `current` resté là (un `SERVER_STOPPED` manqué) : `finishShutdown`, puis l'ouverture,
  même si l'ancien Journal tient encore sa base.

**Pas de trou ailleurs** :

- Un serveur dédié démarre une seule fois par JVM.
- Les GameTests tournent sur un seul serveur par JVM. Aucun GameTest n'ouvre dans la JVM le fichier du Journal du
  serveur : `RestartGameTests` le tient depuis une autre JVM (`JournalHolder`). Le seul second Journal, celui du
  redémarrage, est gardé par `unclosed`. Aucune instance restée en vie ne tient le fichier.

### Le correctif, à la racine

`DuckDatabase.open` tient l'ensemble des fichiers ouverts dans le processus (`OPEN`), de `open` à `close` :

- un second `open` d'un fichier encore ouvert lève `FileStillOpenException` **avant que DuckDB le voie** ;
- `close` libère le fichier, une seule fois (`AtomicBoolean`) : un second `close` ne libère jamais un fichier qu'une
  autre base a repris entre-temps ;
- une ouverture ratée (migration, fichier corrompu) libère aussi le fichier.

`FileStillOpenException` est une `IOException`, donc **passagère** pour `OpenRetry` : elle est retentée à 2 s puis à
6 s. Si l'ancien fil d'écriture finit entre-temps, le Journal s'ouvre. Sinon, le Journal est désactivé avec la raison
exacte :

```
[Journal] opening the Journal failed (attempt 1 of 3), trying again in 2 s: …\narrator2\narrator.duckdb is still open in this process: an earlier Journal has not closed it yet
[Journal] the Journal cannot open, facts will not be recorded: opening failed after 3 attempts in 6 s: …\narrator.duckdb is still open in this process: an earlier Journal has not closed it yet
```

La garde couvre tous les chemins : le démarrage, le redémarrage, la réouverture après une panne par le fil d'écriture
d'un ancien Journal, et les tests.

**La garde du redémarrage n'est pas dupliquée** : `unclosed`, `holdsDatabase` et `stopAndClose` ne changent pas. Les deux
couches n'ont pas le même rôle :

- la garde du redémarrage laisse le Narrateur éteint avec une raison claire, et le redémarrage suivant referme d'abord
  l'ancien. Sans elle, un redémarrage dont l'ancien Journal tient la base attendrait 10 s, puis tournerait sans Journal,
  ce qui serait moins bien ;
- la garde de fichier tient l'invariant, quel que soit l'appelant. Au démarrage du serveur, aucun opérateur n'est là
  pour réessayer : un Journal indisponible qui dit pourquoi est le bon comportement. C'est le même que pour un fichier
  tenu par un autre programme (JOURNAL-RETRY).

**Une nuance assumée** : pendant une panne d'un ancien Journal, sa base est fermée (`released`) et le fichier sort de
`OPEN`. Un nouveau Journal peut alors l'ouvrir, et la réouverture de l'ancien sera refusée. Deux bases ne sont donc
jamais ouvertes à la fois. L'ancien perd ce qu'il avait encore en file, comme à son échéance de fermeture.
`holdsDatabase` (au niveau du Journal) reste vrai pendant la panne, comme avant : la garde du redémarrage attend.

### Tests

| Test | Ce qu'il garde | Sonde (garde retirée) |
|---|---|---|
| `DuckDatabaseTest` › *a file still open in this process is refused before DuckDB sees it, until it is closed* | refus avant DuckDB (même par un chemin `dir/./narrator.duckdb`), le message, passager ; libéré à `close` ; un second `close` ne libère pas le fichier de la base suivante ; en mémoire, sans fichier, rien n'est gardé | rouge : `was java.sql.SQLException: … the configuration has been locked` |
| `DuckDatabaseTest` › *a database from a newer version of the mod is refused* (étendu) | l'ouverture ratée libère le fichier : la suivante échoue de la même façon, pas en « fichier encore ouvert » | — |
| `JournalOpenRetryTest` › *a file an earlier Journal of this process still holds is tried again, and opened once that Journal let go* | un vrai `DuckJournal` dont le fil d'écriture survit à sa fermeture (tâche d'écriture retenue) : `openJournalOrDisable` est refusé trois fois, avec la raison exacte, sans jamais l'erreur de DuckDB ; puis, le fil libéré pendant la première attente, `openJournal` ouvre à la deuxième tentative | rouge : `opening failed, and is not tried again as it would fail the same way: … the configuration has been locked` |

## 2. L'arrêt du serveur : une seule échéance pour tous les `stop`

### Ce que j'ai trouvé

`ModuleEngine.stopping()` (arrêt du serveur, `/narrator restart`) et `ModuleEngine.halt()` (`/narrator forge off`)
retirent les déploiements l'un après l'autre. Chaque `ModuleHost.stop` calculait sa propre échéance (maintenant + 2 s),
d'où 2 s par module au pire. `halt` tourne pendant un tick : avec 8 modules (`forge.maxActive` au plus), il pouvait
figer le serveur 16 s. C'est le même défaut, je l'ai traité aussi.

### Pourquoi pas en parallèle

Le principe 3 de la Forge et `ModuleThreads` le décident :

- un seul fil des modules sert à la fois (« calls never overlap ») ;
- le thread serveur sert les requêtes du SDK appel par appel ;
- chaque déchargement renouvelle le fil (FLAKY-4).

Arrêter les modules en parallèle demanderait plusieurs fils des modules suivis à la fois par le thread serveur, donc une
réécriture de la remise des appels, la partie la plus fragile de la Forge (FORGE-ABANDON, FLAKY-4 à 6). Le gain ne
jouerait que sur une machine surchargée, la seule où la patience s'épuise, et là ces fils se disputeraient le même
processeur. L'échéance commune donne la borne demandée sans toucher à la remise.

### Le correctif

- `ModuleHost.stop(why, until = maintenant + 2 s × timeScale)` : `until` termine la patience du `stop` et celle de
  l'appel tenu qu'il attend d'abord. La valeur par défaut est celle d'avant.
- `ModuleEngine.stopDeadline()` (maintenant + `STARTUP_PATIENCE_NANOS`). `stopping()` et `halt()` en prennent une seule
  pour toute la rafale et la passent à `retire` et à `finish`. Un `stop` isolé garde sa propre patience : `stop_module`,
  fin de temps, fin demandée, désactivation.
- **Après l'échéance**, un `stop` garde son budget d'appel (20 + 5 ms), et l'appel tenu qu'il attend une reprise de
  25 ms au plus. **Borne du thread serveur : 2 s, plus deux budgets par module.** Avec 8 modules : 2,4 s au pire, contre
  16 s avant.
- **Le WARN reste vrai**, et aucun texte n'a changé :
  - `its stop does not run: the module thread did not start it within N ms` : l'appel passe de `POSTED` à `CANCELLED`,
    et le fil ne le prendra jamais, même une fois lancé. Le test le vérifie en attendant que les fils retardés démarrent ;
  - `its stop was given up at N ms … past its patience` : la patience épuisée est alors la patience commune ;
  - `its stop does not run: a call given up before still runs past its patience` : inchangé.
- Le journal donne la durée de l'arrêt : `INFO the server's stop waited 2077 ms for the stops of 6 modules, under one
  deadline` (seulement à l'arrêt, et seulement s'il y a des modules).

### Tests

`ModuleEngineTest` › *the stops of the server's stop share one deadline, however late each new module thread starts* et
› *the stops of forge off share one deadline too* : six modules, chaque fil des modules neuf lancé 700 ms en retard
(crochet `onStart` de FLAKY-6, comme une machine chargée).

- Avec le correctif, la rafale prend **2 077 ms**. Les `stop` des trois premiers modules tournent. Les trois derniers
  sont perdus avec un seul `WARN` chacun (`within 620 ms`, puis `within 25 ms` deux fois), et aucun ne tourne ensuite.
- Le test vérifie :
  - la durée : moins de 2 s, plus deux budgets par module, plus 400 ms ;
  - l'ordre : les `stop` passent dans l'ordre jusqu'à l'échéance ;
  - un `WARN` par `stop` perdu, et aucun pour un `stop` qui a tourné.
- **Sonde** (`retire` sans `until`) : rouge, `the burst took 3509 ms` et `3511 ms`.
- Le test de FLAKY-6 (*each module the server stops gets its stop, though each new module thread starts late*, 3 × 40 ms)
  passe sans changement.

## 3. Le dossier temporaire de `TestServices`

**Trouvé** : chaque `TestServices()` sans `dataDir` créait un dossier `narrator2-test<nombre>` qui n'était jamais
effacé, et les tests en créent environ 173 par build. Dans le `TEMP` partagé (`C:\Users\Public\narrator2-tmp`), on en
comptait **164 487** au début de la tâche.

**Correctif** : `TestDataDirs`, dans `TestServices.kt` :

- un dossier racine `narrator2-services-<nombre>` par JVM, créé au premier besoin ;
- un sous-dossier `services<nombre>` par `TestServices` ;
- le tout effacé à la sortie de la JVM, par un crochet d'arrêt (`deleteRecursively`, sans échec si un fichier reste
  tenu).

Rien n'est effacé avant la fin de la JVM, et c'est voulu : un test peut garder ses services, ou passer leur dossier à une
autre pièce (`DashboardPrivacyTest` y écrit son faux secret par `DashboardDataset.services`), aussi longtemps qu'il
tourne. Les GameTests passent par le même code.

**Vérifié** :

- Une sonde (retirée) crée deux `TestServices` et écrit un fichier dans l'un : les deux dossiers sont sous la même racine,
  et la racine a disparu une fois la JVM de test finie.
- Après chaque build et chaque `runGameTest` de cette tâche, aucun `narrator2-services-*` ne restait (voir
  « Vérifications »).

**Pas fait** : effacer les 164 487 dossiers déjà là. D'autres agents travaillent dans le même `TEMP`, et leurs tests,
sans ce correctif, en créent encore (voir « À trancher »).

## 4. Le fuseau de `DreamInputs.kt`

**Trouvé** : `DreamInputs.zone` recopiait le choix du fuseau, `journal.timeZone` sinon l'horloge, avec un `ZoneId.of`
qui lève sur un nom refusé. L'original est `JournalConfig.zone(fallback)` (`config/NarratorConfig.kt`), désigné par
M6-17 comme le seul choix du fuseau.

**Correctif** : `DreamInputs.zone` vaut `config.journal.zone(services.clock.zone)`.

**Trouvé en passant** : `DuckDatabase.Options.of`, qui donne son fuseau à la base elle-même, en avait une autre copie,
avec `ZoneId.systemDefault()` en repli. Il passe par `config.zone(ZoneId.systemDefault())`. Le résultat est le même pour
toute configuration valide, puisque la validation refuse un nom inconnu. Il ne reste plus de copie : une recherche de
`timeZone.` ne trouve que la fonction et la validation.

**Test** : `DreamInputsTest` › *the times are shown in the Journal's zone, chosen as everywhere else* :
`Europe/Paris` ; sans nom, le fuseau de l'horloge du moteur ; un nom refusé (`Mars/Olympus`) donne aussi le fuseau de
l'horloge. **Sonde** (l'ancienne ligne) : rouge, le nom refusé lève.

## 5. Les comptes absolus de `JournalEndToEndTests`

**Trouvé** : `operatorsReadTheJournalWithNarratorSql` attendait exactement deux décisions `operator sql` dans tout le
Journal, que tous les lots partagent. Ses vérifications `leak` (compte) et `detail` (`LIMIT 1`) portaient aussi sur
toutes ces décisions. Les autres comptes du fichier portent sur des joueurs au nom unique (`Actor` ajoute un morceau
d'UUID) : ils ne changent pas.

**Correctif**, sur le modèle de FLAKY-7 pour `RestartGameTests` :

- La base est le plus grand `id` des décisions `operator sql`. Elle est lue après la barrière qui écrit la ligne de chat
  du test : cette lecture remplace l'ancien drapeau `written`. Elle a lieu avant les commandes.
- Les trois requêtes ne gardent que `id > base`. Les ids sont donnés en ordre croissant au moment où la décision est
  enregistrée, donc les décisions du test viennent toutes après la base.
- `detail` vérifie maintenant ses **deux** décisions (`DISTINCT`), et plus seulement une.

## Vérifications

- **Tests ciblés** : `DuckDatabaseTest`, `JournalOpenRetryTest`, `ModuleEngineTest`, `DreamInputsTest`,
  `DuckJournalTest`, `HeldBackTest`, `ModuleBookTest`, `ModuleHostTest`, `OpenRetryTest`, `RestartSequenceTest` et
  `ShutdownSequenceTest` passent.
- **Sondes** : chaque correctif a été retiré puis remis, depuis une copie, et l'arbre vérifié ensuite (aucune marque de
  sonde). Les cinq tests nouveaux sont rouges, chacun sur sa vérification (voir §1, §2, §4).
- **Build complet n° 1** (`.\gradlew.bat build --continue`, `main` à `d2b22b0`, rien à fusionner) : vert en 9 min 46 s,
  **2 275 tests unitaires, 0 échec, 0 ignoré**, **« All 402 required tests passed »**.
  - Dans le journal du GameTest, l'arrêt du serveur écrit `the server's stop waited 0 ms for the stops of 2 modules,
    under one deadline`, sans aucun `WARN` de `stop`.
  - Aucune erreur `still open in this process` ni `configuration has been locked`.
- **Retouche après ce build** : le singulier de la ligne de journal (`the stop of 1 module`).
- **`git merge main`** (déjà à jour), puis **build complet n° 2** : vert en 10 min 19 s, **2 275 tests unitaires,
  0 échec, 0 ignoré**, **« All 402 required tests passed »**.
- **`runGameTest` seul**, troisième passage des GameTests : **« All 402 required tests passed »** en 6 min 15 s.
- **Dossiers temporaires** : après chacun de ces lancements, aucun `narrator2-services-*` ne restait dans
  `C:\Users\Public\narrator2-tmp`.

Aucun appel réel à Claude, pas de `runServer`.

## Ce que les tâches suivantes doivent savoir

- **`DuckDatabase.open` refuse un fichier encore ouvert dans le processus** (`FileStillOpenException`). Un test qui
  relit le fichier d'un Journal doit d'abord fermer celui-ci, ce que tous les tests font déjà. Un Journal qui doit
  attendre l'ancien passe par `openJournal`, qui retente.
- **`ModuleHost.stop(why, until)`** : une rafale de retraits prend une seule échéance (`ModuleEngine.stopDeadline()`).
  Toute nouvelle rafale (par exemple une Forge bloquée qui arrêterait tout) doit faire de même.
- **`TestServices()` sans `dataDir`** reçoit un dossier effacé à la sortie de la JVM. Un test qui veut garder un fichier
  au-delà doit fournir son propre dossier.
- **Un compte de décisions** dans un GameTest part d'une base, celle de FLAKY-7 ou un `id` : le Journal est commun à
  tous les lots.

## À trancher par le superviseur

1. **Arrêt des modules en parallèle** : non fait, pour les raisons du §2. L'échéance commune borne l'arrêt à
   2 s + 2 budgets par module. Si l'on veut que tous les `stop` aient leur chance même sur une machine surchargée, il
   faudra plusieurs fils des modules suivis à la fois, un chantier à part.
2. **Les 164 487 dossiers `narrator2-test<nombre>` déjà dans `C:\Users\Public\narrator2-tmp`** : à effacer à la main,
   une fois cette branche fusionnée et quand aucun build ne tourne. Les branches qui n'ont pas encore ce correctif en
   créent encore. Le motif `narrator2-test*` ne prend pas les dossiers `narrator2-services-*` du nouveau code.
3. **`"environment": "*"`** dans `fabric.mod.json` : le mod se charge sur un client, donc sur un serveur intégré, et
   c'est ce qui rend réel le trou du §1. Le §1 le ferme. Si le mod ne vise que les serveurs dédiés (« côté serveur
   uniquement »), `"environment": "server"` serait plus juste, mais c'est une décision de produit : je ne l'ai pas prise.
