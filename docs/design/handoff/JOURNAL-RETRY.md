# Passation JOURNAL-RETRY : le Journal retente son ouverture au démarrage

Petite tâche issue de la décision E du design M4 (`M4.md` §« Décisions », ligne E) : un Journal qui ne s'ouvre pas au démarrage restait un `DisabledJournal` jusqu'au redémarrage, sans mémoire ni traces. La réouverture automatique du lot J de M2 (`M2-fix-J.md`) ne vaut qu'une fois le Journal ouvert. **Le code et cette passation font foi.**

## Ce qui a changé

- **Trois tentatives d'ouverture, en 10 s au plus**, à 0 s, puis 2 s, puis 6 s environ (attentes de 2 s puis 4 s). C'est `journal/OpenRetry.kt` (nouveau), appelé par `openJournal` (`JournalModule.kt`).
- **Seule une erreur passagère est retentée** (voir plus bas). Les autres le sont une seule fois.
- **Journalisation** (tag `[Journal]`) :
  - chaque tentative ratée écrit un `WARN` avec sa cause sur une seule ligne (le message de verrou de DuckDB nomme le processus qui tient le fichier sur des lignes à part : on les garde) : `opening the Journal failed (attempt 1 of 3), trying again in 2 s: IO Error: Cannot open file … File is already open in …\java.exe (PID 29512)` ;
  - la dernière tentative ratée écrit aussi un `WARN` (« giving up ») ;
  - une erreur qui ne passera pas : `WARN` « not tried again as it would fail the same way » ;
  - une réussite à la 2ᵉ ou 3ᵉ tentative : `INFO` « opened at attempt 2 of 3, 2.2 s after the first », en plus de la ligne `opened …` habituelle ;
  - l'échec final : un `ERROR` (avec la pile) `the Journal cannot open, facts will not be recorded: opening failed after 3 attempts in 6.2 s: …`, puis le `WARN` `the Journal is unavailable: …` et l'état `DisabledJournal` habituels.
- **État « Journal unavailable » : inchangé**, sauf sa raison. `DisabledJournal.health().lastError`, `/narrator status` et les messages de `/narrator sql` et `/narrator trace` montrent maintenant `opening failed after 3 attempts in 6.2 s: IO Error: …` (ou `opening failed, and is not tried again as it would fail the same way: …`). **Les opérateurs ne sont pas prévenus au démarrage, comme avant** : aucun n'est en ligne à ce moment, et `tellOperators` n'existe que sur `DuckJournal`.
- **Le serveur n'attend que sur son thread de démarrage** (`SERVER_STARTED`, avant la première tick) : ARCHITECTURE §17 point 8 et `M1_M2.md` §2.2 le disent maintenant. La borne de 30 s de la mémoire reste à part.

## Quelles erreurs sont retentées

Constat de départ, vérifié en reproduisant les erreurs avec le vrai DuckDB 1.5.5.1 (sonde jetable, et les tests) : **DuckDB ne donne aucune structure**. Toutes ses erreurs sont des `java.sql.SQLException` simples, sans sous-classe, sans `SQLState` (null), sans code d'erreur (0), sans cause. Seul le début du message distingue la catégorie (`IO Error:`, `Catalog Error:`, `Invalid Input Error:`…). Et `IO Error:` couvre à la fois ce qui passe et ce qui ne passe pas :

| Situation réelle | Message DuckDB | Retentée ? |
|---|---|---|
| Fichier tenu par un autre processus (Windows) | `IO Error: Cannot open file "…": The process cannot access the file because it is being used by another process.` puis `File is already open in <exe> (PID n)` | **oui** |
| Fichier tenu (Linux, macOS ; lu dans la bibliothèque native) | `IO Error: Could not set lock on file "…": Conflicting lock is held in …` | **oui** |
| `Access is denied` (antivirus, fichier en attente de suppression, ou vraie permission) | `IO Error: Cannot open file "…": Access is denied.` | **oui** (ambigu : retenter coûte 6 s) |
| Disque plein, erreur d'écriture | `IO Error: Could not write …` | **oui** |
| `IOException` Java (par exemple `Files.createDirectories`) | | **oui**, sauf `FileAlreadyExistsException` (le dossier est un fichier) |
| Fichier qui n'est pas une base | `IO Error: The file "…" exists, but it is not a valid DuckDB database file!` | non |
| Base corrompue | `IO Error: Corrupt database file: computed checksum … does not match …` | non |
| Version de stockage illisible | `IO Error: Trying to read a database file with version number …` | non |
| WAL qui ne se rejoue pas | `IO Error: Failure while replaying WAL file …` | non |
| Migration qui échoue | `V1__journal.sql failed: Catalog Error: …` (enveloppe de `DuckDatabase`, cause : l'erreur de DuckDB) | non |
| Base d'une version plus récente du mod | `the Journal has schema version 999, but this version of the mod only knows up to 2…` | non |
| Réglage refusé par DuckDB, fuseau invalide, mémoire | `Invalid Input Error: …`, `ZoneRulesException`, `Out of Memory Error: …` | non |

### Pourquoi cette distinction (`isTransientOpenFailure`)

- **On juge la cause racine** (le bout de la chaîne `cause`), pas l'enveloppe : une migration qui échoue sur une erreur d'entrée-sortie reste retentable.
- **Une erreur est passagère si c'est une `IOException`, ou une `SQLException` dont le message commence par `IO Error:` sans contenir l'un des quatre messages permanents ci-dessus.** Ces quatre messages sont les mots de DuckDB lui-même, relevés dans sa bibliothèque native (`libduckdb_java.so_*`) : ils ne dépendent pas de la langue du système.
- **On n'a pas retenu une liste blanche de messages de verrou** : sous Windows, le texte du système (« being used by another process ») est **traduit** (le propriétaire est en français), et `File is already open in` n'apparaît que si DuckDB retrouve le processus. Une liste blanche anglaise aurait raté la panne qu'on veut couvrir.
- **Le biais est voulu** : se tromper en retentant une erreur permanente coûte deux tentatives, environ 6 s de démarrage et deux `WARN` ; se tromper en ne retentant pas une erreur passagère coûte un Journal perdu jusqu'au redémarrage. Une erreur d'entrée-sortie inconnue est donc retentée.
- **Si une version future de DuckDB change ses messages** : les cas permanents deviendraient retentés (coût : 6 s, deux `WARN`), pas l'inverse. `OpenRetryTest` et `JournalOpenRetryTest` utilisent les vrais messages : une mise à jour de DuckDB qui les change fait échouer les tests « tried once ».

## La borne de 10 s

`OpenRetry.budgetMs = 10_000`. **Une tentative ne démarre que si elle peut démarrer dans les 10 s qui suivent la première** : si la précédente a échoué lentement, on ne retente pas plutôt que d'attendre moins (`elapsed + attente > 10 s`, journalisé « not tried again as the next attempt would start past the 10 s allowed »). Au nominal (échecs immédiats), il y a 6 s d'attente. **Une tentative déjà en cours n'est pas interrompue** (c'est un appel natif) : si DuckDB se bloque lui-même, la borne ne le sauve pas. Une ouverture qui échoue rend la main tout de suite : mesuré, un fichier tenu échoue en quelques millisecondes. Une interruption du fil pendant l'attente arrête les tentatives et garde l'indicateur d'interruption.

Mesuré en temps réel, avec la politique par défaut, contre un vrai enfant qui tient le fichier :

| Scénario | Résultat |
|---|---|
| Verrou relâché 1,5 s après le début | le Journal s'ouvre à la 2ᵉ tentative, **2,2 s** après le début |
| Verrou jamais relâché | 3 tentatives, `DisabledJournal` après **6,18 s** |

## Code

- **Nouveau, `journal/OpenRetry.kt`** : `OpenRetry` (politique : attentes, borne, horloge et attente injectables), `JournalOpenException` (porte `attempts`, et la dernière erreur en cause), `isTransientOpenFailure`.
- **`journal/JournalModule.kt`** :
  - `openJournal(dir, config, clock)` garde sa signature ; il retente maintenant, et lève `JournalOpenException` ;
  - **nouveau**, `openJournalOrDisable(dir, config, clock)` : `openJournal`, ou `DisabledJournal` avec l'`ERROR` et le `WARN` de l'état indisponible. Ce code vivait dans `NarratorRuntime.openJournalOrDisable` ; il est déplacé ici pour être testable sans serveur ;
  - versions `internal` avec `retry` et `hookNanos` injectables. **`hookNanos`** : la ligne qui lisait `ObserverState.current()` (donc `NarratorRuntime`, qu'un test unitaire ne peut pas initialiser) est devenue la fonction `observerHookNanos`, valeur par défaut du paramètre. Avant, aucun test unitaire ne pouvait appeler `openJournal` (voir `M3-19.md`).
- **`NarratorRuntime.kt`** : `openJournalOrDisable()` privé supprimé, `wire()` appelle `openJournalOrDisable(dataDir, …)`. L'`ERROR` passe du tag `[Narrator]` à `[Journal]`.
- **`LogCapture` (test, ajout seul)** : l'enregistrement garde le niveau de chaque ligne ; nouvelle méthode `at(level)`. `text` et `messages` sont inchangés.

## Tests (déterministes : aucune attente réelle, aucun `sleep` fragile)

- **`OpenRetryTest`** (11 tests, sans DuckDB) : la politique par défaut (3 tentatives, attentes 2 s et 4 s, somme ≤ borne) ; réussite immédiate ; échecs passagers puis réussite à la 3ᵉ ; échec qui ne passe jamais (3 tentatives, 6 s, un `WARN` par tentative, dernier en cause) ; échec permanent (1 tentative) ; échecs lents (la 3ᵉ tentative est refusée : borne) ; 3ᵉ tentative exactement à 10 s ; interruption ; classement des erreurs, y compris l'enveloppe de migration et un message de verrou traduit en français. L'horloge et l'attente sont `FakeWait` : l'attente avance l'horloge sans dormir.
- **`JournalOpenRetryTest`** (6 tests) : le vrai verrou DuckDB, tenu par un vrai autre programme (`JournalChild` en mode `hold`, comme dans `JournalRecoveryTest`), et de vrais fichiers invalides :
  - **verrou relâché pendant la première attente** → le Journal s'ouvre à la 2ᵉ tentative. Le programme est tué **dans** l'attente (c'est le « relâché après 1,5 s » du scénario), puis le test attend, sans délai fixe, que le système laisse ouvrir le fichier (boucle d'ouverture à 5 ms, comme `JournalChild.hold`). La 2ᵉ tentative trouve donc le fichier libre à coup sûr, quelle que soit la vitesse de la machine. Le Journal ouvert est le vrai (migrations appliquées, lecture) ;
  - **verrou jamais relâché** → 3 tentatives, attentes 2 s et 4 s, `DisabledJournal`, 6 s simulées (≤ 10 s), 3 `WARN` puis 1 `ERROR` ;
  - **une seule tentative, sans attente**, pour : un fichier qui n'est pas une base, une base corrompue (un octet changé dans un bloc d'en-tête), une migration qui échoue (une table `players` déjà là), une base d'une version plus récente.
- **Contre-épreuves** (vérifiées en modifiant `isTransientOpenFailure`) : en renvoyant toujours « permanent », les deux tests de verrou échouent avec le vrai message (« opening failed, and is not tried again … File is already open in …java.exe (PID n) ») ; en renvoyant toujours « passagère », les quatre tests « tried once » échouent (« expected: <[]> but was: <[2000, 4000]> »).
- **Ce que les tests ne couvrent pas** : un vrai `runServer` avec la base verrouillée. `NarratorRuntime.wire()` ne change que d'un appel de fonction, et `openJournalOrDisable` est testé directement.

## Pour la suite

- **`DisabledJournal` au démarrage reste réservé à M6** (`/narrator restart`, qui reconstruit le runtime) : cette tâche n'en réduit que la probabilité. Un fichier tenu plus de 6 s, ou une erreur permanente, désactive toujours le Journal jusqu'au redémarrage.
- Un test qui veut appeler `openJournal` ou `openJournalOrDisable` doit passer `hookNanos = { null }` (version `internal`), sinon l'écrivain initialise `NarratorRuntime` et échoue (`FabricLoader.getConfigDir` nul).
- Si DuckDB gagne des erreurs typées, remplacer `PERMANENT_IO_ERRORS` par la vérification du type.
- Ajouter une migration (M4) ne change rien : `JournalOpenRetryTest` compte `DuckDatabase.MIGRATIONS.size`.

## Fichiers

- **Code** : `journal/OpenRetry.kt` (nouveau), `journal/JournalModule.kt`, `NarratorRuntime.kt` (retrait d'une fonction et de deux imports, un appel).
- **Tests** : `journal/OpenRetryTest.kt`, `journal/JournalOpenRetryTest.kt` (nouveaux), `testing/LogCapture.kt` (ajout seul).
- **Doc** : `ARCHITECTURE.md` §17 point 8, `design/M1_M2.md` §2.2.

## Vérifications

- `git merge main` : déjà à jour (aucune fusion à faire au moment de finir).
- `.\gradlew.bat build` : **vert**, en 2 min 56 s : **1 160 lignes de tests unitaires `PASSED`, 0 échec** (dont les 17 nouveaux), et « All 172 required tests passed » pour les GameTests. Le log de fin des GameTests montre `[Journal] closing: checkpointed … 0 records lost`.
- Réel, hors de la suite : une classe de test jetable (supprimée) a lancé la politique par défaut, en temps réel, contre un vrai enfant qui tient le fichier. Chiffres dans le tableau plus haut.
- Pas de `runServer`, aucun appel réel à Claude ni à Jev.
