# Passation M1-polish : les quatre défauts relevés par la recette M1 (T07)

Les quatre points listés par T07 sont corrigés, chacun avec un test qui échoue sans le correctif (vérifié en retirant chaque correctif un à un). Le log de fin des GameTests se termine maintenant par `[Journal] closing: checkpointed`, sans aucun `CHECKPOINT failed`.

## 1. `CHECKPOINT` refusé en fin de GameTests

### Cause réelle

Elle tient en trois faits, vérifiés hors du jeu sur DuckDB 1.5.5.1 (essai jetable, puis `DuckJournalTest`) :

1. **Les résultats sont lus en flux** (`jdbc_stream_results`, choix de T01 pour que `SqlRunner` ne matérialise jamais un gros résultat).
2. **Une requête dont on n'a pas lu le résultat jusqu'au bout reste ouverte sur sa connexion**, avec sa transaction de lecture (autocommit), tant que cette connexion n'exécute pas une autre instruction. Fermer le `ResultSet` et l'instruction, même par `use`, ne suffit pas : `ResultSet.close()` libère le résultat côté pilote, mais DuckDB garde la requête active dans le contexte de la connexion. Or presque toutes les lectures s'arrêtent tôt : `if (rows.next())`, `rows.next(); rows.getLong(1)`. Dans les GameTests, la dernière lecture sur la connexion n° 1 était celle de `SpeechGameTests` (un `if (r.next())`).
3. **Une transaction de lecture ouverte ne gêne pas le `CHECKPOINT` à elle seule.** En revanche, toute écriture qui **modifie ou supprime** des lignes après elle (fermeture de session, compteurs de `players`, `INSERT OR REPLACE` de `turns`, `conversations`, `stat_totals`) doit garder ses anciennes versions pour cette lecture. DuckDB la conserve alors, **avec son verrou de checkpoint**, jusqu'à la fin de la lecture. D'où « there are other write transactions active ». Des ajouts seuls (appenders) ne déclenchent rien.

Cela explique toutes les observations de T07 :

- l'essai hors du jeu ne reproduisait pas, car il n'y avait pas de mise à jour **après** la lecture inachevée ;
- en jeu, les départs des joueurs et les écritures du Directeur à l'arrêt arrivent après la dernière lecture ;
- sans Directeur, l'ordre des lectures change et la dernière lecture de chaque connexion finit par être complète ;
- un `SELECT 1` sur la connexion n° 1 débloque, parce qu'il clôt la requête restée ouverte.

**Sur un serveur réel**, le défaut existait aussi. `Chapters.previousNumber()` (`if (rows.next())`) et d'autres lectures laissaient une requête ouverte. Dès qu'un joueur partait, les `CHECKPOINT` d'après sauvegarde échouaient jusqu'à la lecture suivante sur la même connexion.

### Correctif à la source

C'est `DuckJournal.read` qui prête les connexions de lecture ; c'est donc lui qui les rend propres. Avant de remettre une connexion dans le pool, `endQuery` exécute `SELECT 1` et **le lit jusqu'au bout** : la requête laissée ouverte se termine, et `SELECT 1` ne laisse rien derrière lui. Coût mesuré : environ 0,1 ms par lecture. Les appelants n'ont rien à changer, et rien ne les oblige à lire leurs résultats jusqu'au bout.

Les autres moyens essayés ne marchent pas : `DuckDBConnection.interrupt()`, basculer `autoCommit`, `commit()` ou `rollback()` en autocommit (ils lèvent « no transaction is active »).

### Défense à la fermeture

`JournalWriter.finish()` appelle maintenant `endReads` **avant** le dernier `CHECKPOINT` :

- les lectures en cours ont 1 s pour finir ;
- puis les connexions de lecture sont fermées. Fermer une connexion interrompt une lecture encore en cours et termine toute requête restée ouverte ;
- ensuite seulement vient le `CHECKPOINT`.

Avant, les connexions n'étaient fermées qu'après le `CHECKPOINT`. `closeResources` passe par le même `endReads`, qui peut être appelé plusieurs fois sans effet.

**Pas de `FORCE CHECKPOINT`.** Vérifié : avec une requête restée ouverte sur une connexion de lecture, `FORCE CHECKPOINT` **attend indéfiniment**. Il attend la fin des transactions, et celle-là ne finit jamais d'elle-même. En secours à la fermeture, il aurait bloqué l'arrêt du serveur.

### Tests

Dans `DuckJournalTest` :

- `a read that stops before the end of its result does not hold the checkpoint back` : une lecture qui s'arrête à la première ligne, puis un départ (mise à jour), puis `checkpointAsync`. Aucun `CHECKPOINT failed`, et le WAL est à 0 ;
- `the close checkpoints even when a query was left open on a read connection` : une requête laissée ouverte directement sur une connexion de lecture, hors du pool, puis un départ et la fermeture. Le log doit montrer `closing: checkpointed`.

Chacun échoue sans son correctif, même quand l'autre correctif est en place.

## 2. L'outil `sql` et `/narrator sql` voient la dernière seconde

La `barrier()` est dans **`SqlRunner.query`**, juste avant la lecture. L'outil `sql` et `/narrator sql` passent tous deux par là, comme tout futur appelant de `SqlRunner` (briefing de T19, par exemple).

- Délai : `SqlRunner.BARRIER_TIMEOUT_MS` = 500 ms au plus. Au-delà, la requête tourne sur ce qui est écrit, avec une ligne `DEBUG`.
- Coût habituel : quelques millisecondes, car la barrière réveille le rédacteur, qui écrit tout de suite.
- Aucune barrière pour une requête vide ou trop longue.
- Délai de l'outil : la marge de `SqlTool` (`MARGIN_SECONDS` = 3 s) couvre la barrière (0,5 s) et l'attente d'une connexion (2 s). Elle est inchangée.

Tests :

- `SqlToolTest` › `a query sees the facts published just before it` ;
- `SqlRunnerTest` › `the model and the operators see the facts published just before their query`, pour l'accès du modèle et celui de l'opérateur.

Les deux tests tournent avec un rédacteur réglé pour n'écrire qu'une fois par heure, sauf barrière.

## 3. Égalités d'horodatage : `ORDER BY ts, id`

- **Les 15 vues de faits commencent par `id`** : `timeline`, `chat_log`, `narrator_actions`, `mining`, `building`, `deaths`, `kills`, `damage`, `trades`, `meals`, `crafts`, `advancements`, `visits`, `travels`, `griefs`.
  - Dans `timeline`, `id` est l'identifiant du bus, commun à `events` et `chat`, donc l'ordre de publication. Il porte un commentaire de colonne.
  - Dans `narrator_actions`, c'est `tool_calls.id`.
  - `play_sessions`, `playtime` et `player_stats` ne sont pas des faits : elles n'ont pas d'`id`.
- **Macros** :
  - `recent` trie par `ts, id` ;
  - `trail` trie, et calcule `moved`, par `ts, rowid`. `positions` n'a pas d'`id`, mais la table est en ajout seul : `rowid` y suit l'ordre d'écriture, donc l'ordre du flux.
- **`SchemaDoc`** :
  - l'en-tête dit « Rows may share a ts: ORDER BY ts, id. » ;
  - l'exemple de `chat_log` trie par `ts DESC, id DESC`.
- **Budget** : 7 461 caractères, soit ≈ 2 487 tokens sur 2 500 (avant : 7 401). Pour faire de la place, j'ai raccourci deux commentaires de vues dans `views.sql` :
  - `timeline` : « Events and chat, without item pickups and stats » ;
  - `travels` : la fin « : from where to where » est retirée.

  **Il reste 39 caractères de marge.**
- La colonne `id` s'ajoute à `SELECT *` sur ces vues. Aucune requête existante ne se fonde sur la position des colonnes : les GameTests de T07 et les prompts nomment leurs colonnes.

Tests :

- `SqlViewsTest` › `facts that share a timestamp keep their order` : des faits et des positions publiés dans la même milliseconde, lus dans l'ordre par `timeline`, `recent`, `mining`, `chat_log` et `trail` ;
- `SchemaDocTest` › `it tells how to order facts that share a timestamp` ;
- `columns_of('mining')` commence maintenant par `id`.

Limite du test de `trail` : sans le tri par `rowid`, DuckDB rend quand même les positions dans l'ordre sur ce petit volume (essayé avec 2 000 positions). Ce cas précis ne peut donc pas échouer sans le correctif. Le reste du test échoue, lui, car les vues n'avaient pas d'`id`.

## 4. Faits publiés avant `JOIN` : rattachés à la session qui s'ouvre

`JournalMapping` note, pour chaque joueur sans session ouverte, le premier fait « orphelin » du tick en cours. À la connexion, la session adopte ces faits s'ils remplissent deux conditions :

- ils sont du **même tick** que le `PlayerJoined` : même `gameTime` dans l'enveloppe. Ce qu'une connexion déclenche l'est dans `placeNewPlayer`, pendant le même tick ;
- ils datent de **moins de 2 s** (`JournalMapping.ADOPT_WINDOW`). Le temps de jeu s'arrête quand le serveur vide est en pause : le tick seul ne suffirait pas.

L'adoption est une instruction de plus, `Upsert.EVENTS_ADOPTED` : `UPDATE events SET session_id = ? WHERE player_uuid = ? AND session_id IS NULL AND id >= ? AND id < ?`, entre le premier fait orphelin et le `join`.

- **Ordre dans le lot** : `Upsert` gagne `afterAppends`, et ces instructions passent **après** le vidage des appenders, dans la même transaction. Les faits du lot en cours sont donc vus, comme ceux des lots précédents.
- **Écriture par moitiés après une erreur** : l'ordre reste bon, car les faits orphelins précèdent toujours leur `join` dans le flux.
- **Ce qui n'est jamais adopté** :
  - un fait d'un autre tick ;
  - un fait plus ancien ;
  - un fait d'un joueur qui ne se connecte jamais, comme le « Ghost » des GameTests de T07 ;
  - un fait publié avant un départ : `PlayerLeft` oublie les orphelins du joueur.
- **Mémoire** : les notes de plus de 2 s sont purgées à chaque connexion.
- **Positions** : non traitées. L'échantillonneur ne suit un joueur qu'après son `join`, donc aucune position n'arrive sans session dans ce cas.

Test : `DuckJournalTest` › `facts published in the tick of a join, just before it, belong to the session it opens`. Il couvre :

- un progrès dans le même lot que le `join` ;
- un progrès déjà écrit avant l'arrivée du `join` ;
- un fait du tick précédent, non adopté ;
- un fait ancien, non adopté ;
- un joueur qui ne se connecte jamais.

Vérifié aussi sur le Journal d'une course de GameTests : les seuls événements sans session sont les 10 000 du joueur fictif « Ghost » de T07. Il ne reste aucun progrès sans session.

## Fichiers touchés

- `journal/DuckJournal.kt` : `endQuery`, `endReads`.
- `journal/JournalWriter.kt` :
  - `endReads` avant le dernier `CHECKPOINT` ;
  - instructions `afterAppends` ;
  - aide `update`.
- `journal/JournalMapping.kt` : orphelins, `Upsert.EVENTS_ADOPTED`, `afterAppends`.
- `journal/SqlRunner.kt` : barrière.
- `journal/SchemaDoc.kt` : en-tête et exemple.
- `tool/sql/SqlTool.kt` : KDoc seulement.
- `resources/narrator2/sql/views.sql`.
- Tests : `DuckJournalTest`, `SqlRunnerTest`, `SqlViewsTest`, `SchemaDocTest`, `SqlToolTest`.

Aucun contrat de T01 n'est modifié :

- `Journal` et `DuckDatabase` sont intacts ;
- `JournalWriter` gagne un paramètre, qui a une valeur par défaut ;
- `Upsert` gagne une propriété, qui a elle aussi une valeur par défaut.

## Pour les tâches suivantes

- **Toute lecture passe par `journal.read`.** Une instruction exécutée directement sur `db.readers` échappe à `endQuery`, et peut de nouveau bloquer les `CHECKPOINT` jusqu'à l'arrêt.
- **T19 (briefing)** : `SqlRunner.query` fait déjà la barrière ; la `barrier()` explicite reste utile avant un `journal.read` brut.
- **T22 (prompts)** : les vues de faits ont `id`, et « ORDER BY ts, id » est dans la description de `sql`. Un exemple de prompt qui trie par `ts` seul reste valide, mais il est ambigu à la milliseconde près.
- **SchemaDoc** : 39 caractères de marge. Toute nouvelle vue ou tout nouveau type d'événement demande de raccourcir ailleurs.
- **Le design (`M1_M2.md` §6.4) liste les colonnes des vues sans `id`.** Ce sont le code et cette passation qui font foi.
