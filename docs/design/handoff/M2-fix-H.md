# Passation M2-fix-H : confidentialité SQL (constats 19, 20, cellules SQL du 27)

Lot H de la relecture de M2. Corrige le constat **bloquant 19** (l'outil `sql` pouvait lire les fichiers de la
base), le constat **20** (fuite de la requête d'un opérateur par `engine_decisions`) et la partie **« cellules `sql` »
du 27** (chevrons non échappés). **En cas de contradiction, le code et cette passation font foi.**

## Ce qui a été prouvé (duckdb_jdbc 1.5.5.1, base sur disque, essai jetable)

`enable_external_access=false` ferme tous les fichiers **sauf ceux de DuckDB** :
- `read_blob`/`read_text` lisent la base, son `.wal`, son `.wal.checkpoint` et les fichiers de `<base>.tmp/` ;
- `glob` les liste ; `current_setting('allowed_paths')` (et `allowed_directories`, `temp_directory`) en donne les chemins ;
- sous pression mémoire, DuckDB **évince des blocs de `private_messages` vers `<base>.tmp/`** ; `read_blob` sur un petit
  fichier size-classé (`duckdb_temp_storage_S32K-0.tmp`, ~200–500 kB) en a extrait le texte d'un message privé (octets bruts) ;
- sous Windows, la lecture du `.wal` et de la base n'échoue que sur le verrou de l'OS (l'erreur donne alors le chemin) ;
  sous Linux, verrou consultatif, la lecture réussirait.

La requête ne nomme jamais `private_messages` et son plan ne parcourt aucune table cachée : les deux barrières
d'origine (nom + `Table` du plan) étaient bien contournées.

## Mécanisme retenu

`SqlRunner.query(sql, limits, access)` — l'accès `OPERATOR` lit tout ; seul `MODEL` (outil `sql`) est filtré. Ajouté au
filtrage existant (nom d'une table cachée, catalogue, fonctions de SQL-en-texte) :

1. **Sur l'arbre syntaxique** (`json_serialize_sql`, requête en paramètre) :
   - fonctions de fichier/réglage refusées : préfixes `read_`, `parquet_` et `glob`, `sniff_csv`, `current_setting`
     (`FILE_FUNCTION_PREFIXES`, `FILE_FUNCTIONS`) ;
   - `json_serialize_plan` ajoutée à `SQL_FROM_TEXT` (elle lie son texte — une fonction de fichier lirait alors son
     fichier — et rend le plan avec les constantes pliées, dont `current_setting('allowed_paths')`) ;
   - **scans de remplacement** : tout nom de table/schéma/catalogue qui n'est pas un nom simple (`[\p{L}_][\p{L}\p{N}_]*`)
     est refusé (`FROM '…/narrator.duckdb'`, `FROM "C:/…"`), et le schéma doit être `main` ;
   - en `FROM`, seules `range`, `unnest` (`MODEL_TABLE_FUNCTIONS`) et les macros de table du Journal (lues à chaud dans
     `duckdb_functions()`) sont acceptées comme fonctions de table.
2. **Sur le plan** (`EXPLAIN (FORMAT JSON)`), quelles que soient les vues/macros traversées : **liste blanche**. Chaque
   parcours (`Table`) doit viser une table visible (sinon on retombe sur le refus par nom caché) ; chaque fonction de
   table du plan (`Function`, ou `Name` d'un `INOUT_FUNCTION`) doit être `RANGE`, `UNNEST` ou `DUCKDB_COLUMNS` (atteinte
   seulement par `columns_of`) ; tout autre opérateur doit appartenir à `PLAN_OPERATORS` (jointures, agrégats, fenêtres,
   tris, CTE, UNION, `VALUES`, `DESCRIBE`… qui ne lisent rien). Un lecteur de fichier caché derrière une vue est arrêté
   là. Un plan illisible est refusé.
3. **Messages du modèle** : `forModel()` retire tout chemin absolu (`[path]`) des erreurs et échappe `<` `>` en `‹` `›`
   (une erreur de DuckDB peut citer une valeur d'origine joueur). L'opérateur voit les messages tels quels.

La liste `PLAN_OPERATORS` a été relevée depuis la bibliothèque native de DuckDB 1.5.5.1 et vérifiée sur toutes les vues,
macros et sur une large matrice de formes de `SELECT` (`SqlRunnerTest`).

## Constat 20

`command/SqlCommand.kt` : la décision `operator` ne garde plus la requête, l'erreur ni le nombre de lignes. Elle garde
`detail = {query_hash, query_chars, status}` (et la même chose en raison) : `query_hash` = HMAC-SHA256 (clé aléatoire
tirée au démarrage, 16 hex) — à clé pour qu'on ne puisse pas la retrouver avec le `sha256()` de DuckDB. La requête
complète ne va que dans le log du serveur, avec son empreinte. Le masquage de l'événement `command` de `/narrator sql`
(Observateur) reste au lot CD, non touché.

## Constat 27 (cellules `sql`)

`SqlFormat` échappe chaque cellule et chaque nom de colonne via l'échappement commun **`core/ModelText.escape`**
(`<` → `‹`, `>` → `›`, caractères de contrôle → espace). Rien d'autre changé (`|` → `¦`, `\n` → `⏎`, plafonds inchangés).

- **`core/ModelText`** : j'avais créé ma propre version (le fichier partagé demandé par le constat 27) ; le **lot I** a
  livré sa version, plus riche (`escape`, `lines`, `name`, `quote`, `attribute`, `cut`), en même temps. Au `git merge main`
  j'ai **gardé celle du lot I** (une seule implémentation, comme demandé) — `SqlFormat` et `SqlRunner` appellent son
  `escape`. Aucune divergence : les noms, le chat et les textes de l'Oracle passent par le lot I, les cellules SQL par moi.

## Fichiers

Du lot H :
- `journal/SqlRunner.kt` : `SqlAccess.MODEL` élargi ; `refusal()` (fichiers, scans de remplacement, fonctions de table) ;
  `planRefusal()` réécrit en liste blanche ; `forModel()` ; nouvelles constantes ; KDoc.
- `journal/SqlFormat.kt` : échappement `‹›` des cellules et en-têtes.
- `journal/DuckDatabase.kt` : KDoc (les fichiers propres de DuckDB restent lisibles).
- `command/SqlCommand.kt` : décision réduite à l'empreinte/taille/statut.
- `core/ModelText.kt` : échappement commun — **version du lot I gardée à la fusion**, la mienne écartée.
- Tests : `journal/SqlFileAccessTest.kt` (nouveau, base **sur disque**), `journal/SqlRunnerTest.kt`,
  `journal/SqlFormatTest.kt`, `journal/SqlViewsTest.kt`, `command/SqlCommandTest.kt`,
  `journal/JournalFixture.kt` (paramètre `options` optionnel, additif),
  `gametest/JournalEndToEndTests.kt` (le test `/narrator sql` vérifie la décision réduite du constat 20).
- Design : `docs/design/M1_M2.md` §5.1, §6.1, §6.6.

Aucun contrat de T01 touché (`Journal`, `DuckDatabase`, `Upsert` intacts). `Journal.HIDDEN_FROM_MODEL` et
`SQL_FROM_STRING_FUNCTIONS` inchangés.

## Attaques testées

`SqlFileAccessTest` (base sur disque, messages privés écrits par le vrai Journal, blocs de `private_messages` déversés
dans `<base>.tmp/` par un tri de 20 M lignes) : ~45 attaques toutes **refusées** pour le modèle, sans octet ni chemin —
`read_blob`/`read_text` sur la base, le `.wal`, le `.wal.checkpoint`, `<base>.tmp/*` et un fichier déversé précis ;
`glob` ; `current_setting('allowed_paths')` et `json_serialize_plan` ; scans de remplacement (`'…/narrator.duckdb'`,
`"…"`, `'narrator.duckdb'`) ; `sniff_csv`, `read_csv`, `read_duckdb`, `parquet_metadata`, `read_json_objects` ; variantes
casse mixte, guillemets, noms qualifiés (`main.`, `system.main.`) ; sous-requêtes (scalaire, `IN`, `EXISTS`, `LATERAL`),
CTE (simple, `MATERIALIZED`, `RECURSIVE`), `UNION` ; et **vues/macro piégées** qui lisent les fichiers (arrêtées par le
plan). **Contre-épreuve** : les mêmes requêtes en accès `OPERATOR` ne sont jamais `Rejected` (DuckDB les exécute), un
`read_blob` d'un petit fichier déversé rend un message privé, et `allowed_paths`/`glob` rendent les chemins réels.

`SqlRunnerTest` : les 49 attaques d'origine passent toujours ; toutes les vues et macros passent ; une large matrice de
formes de `SELECT` légitimes (agrégats, fenêtres, `QUALIFY`, `SAMPLE`, tous les types de jointures, `ASOF`, `PIVOT`/
`UNPIVOT`, CTE récursives, `VALUES`, `unnest`, `range`, `DESCRIBE`/`SUMMARIZE`/`SHOW`…) est acceptée ; une vue/macro qui
appelle une autre fonction de table (`generate_series`, `json_each`, `test_all_types`, `repeat_row`) est refusée par le
plan ; les erreurs du modèle sont sans chemin et sans `<>`.

`SqlFormatTest`, `SqlCommandTest` : l'échappement des cellules/colonnes et la décision réduite (empreinte à clé, jamais
la requête/erreur/compte).

## Vérifications

- `.\gradlew.bat build` : vert après fusion de `main` (tests unitaires + les 164 GameTests).
- `SqlFileAccessTest` relancé 3× (`--rerun-tasks`) : stable. La fuite est forcée de façon déterministe avec une base à
  limite mémoire **64 Mo** et un agrégat de 3 M clés distinctes (fichiers déversés minuscules, un seul résultat, donc pas
  de gros tampon ni de risque pour la JVM de test partagée) ; on lit les fichiers déversés et le message privé en sort.
- Pas de `runServer`. Aucune GameTest ajoutée (liste gelée) ; `JournalEndToEndTests` (T04) couvre déjà l'accès `MODEL`
  en jeu et n'est pas modifié.

## Limites

- Le message privé n'est extrait de `<base>.tmp/` que quand DuckDB déverse (tri > mémoire). Sur cette machine Windows,
  la base et le `.wal` sont verrouillés par l'OS : la contre-épreuve les atteint (erreur avec chemin) mais ne lit leurs
  octets que par les fichiers déversés ; sous Linux les trois seraient lisibles. La correction bloque les trois pour le
  modèle indépendamment de l'OS.
- La liste blanche `PLAN_OPERATORS` est celle de duckdb_jdbc 1.5.5.1. Une mise à jour de DuckDB peut renommer ou ajouter
  un opérateur : une requête légitime serait alors refusée (fail-closed, sûr), à recaler. Un test recense les opérateurs
  de toutes les vues/macros, il signalera la dérive.
- L'empreinte de la requête d'opérateur est à clé de session : elle ne corrèle pas deux requêtes identiques d'un
  redémarrage à l'autre (voulu).
