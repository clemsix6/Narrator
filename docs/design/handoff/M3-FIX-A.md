# Passation M3-FIX-A : magasin et voix

Branche `worktree-agent-ad834f7912c46958b`. Ce lot corrige les constats **1** (côté magasin), **4**, **9** (`VoiceGuard` et noms masqués), **13**, **14** et **23** (KDoc de `MemoryStore`) de `docs/design/reviews/M3_REVIEW.md`, selon les décisions du superviseur. Seuls les fichiers du lot A ont changé, plus une donnée de `MemoryCommandsTest` (voir la fin). **En cas de contradiction, le code fait foi.**

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `memory/DuckMemoryStore.kt` | règles par auteur (Chroniqueur, Rêveur), citations vérifiées sur les lignes citées, `voice` par travail (noms masqués, citations bornées pour les modèles), `played_s` lu dans `sessions`, `revertRun` qui fait échouer la sieste ou le rêve annulé, surnoms comparés par leur nom entre « » |
| `memory/Quotes.kt` | `substantial`, `narratorLines`, `saidIn` ; `saidByNarrator` (par fenêtre) retiré ; U+02BC normalisé |
| `memory/Dedup.kt` | doublon d'interaction « sans aucune preuve », dans les deux sens |
| `memory/VoiceGuard.kt` | passages entre guillemets bornés, apostrophes U+02BC et U+2018, noms masqués |
| `memory/DuckPlayClock.kt` | `playerSecondsAt(connection, player, at)` ; KDoc de `playerPlaySeconds` |
| `memory/MemoryStore.kt` | KDoc vraie (règles par auteur, suppressions de `revertRun`, ce qu'il ne défait pas) |

`MemoryLog.kt` n'a pas eu à changer : ce que `revertRun` fait en plus est dans le magasin (`Job.forgetRun`).

## Constat 1 : un engagement vient des mots du Narrateur

Vérifié dans le travail d'écriture de `addCanon`, après l'existence des preuves (`Job.canonSource`).

### Chroniqueur (`Source.CHRONICLER`)

- Pour les huit sortes dites aux joueurs, `DuckMemoryStore.SAID_KINDS` (`promise`, `threat`, `challenge`, `prophecy`, `law`, `nickname`, `title`, `place`), `quote` est **obligatoire**.
- **Toute** citation de canon du Chroniqueur (les autres sortes comprises, si elle est donnée) doit :
  - faire au moins **3 mots ou 15 caractères** une fois normalisée (`Quotes.substantial`, `MIN_WORDS`, `MIN_CHARS`) ;
  - être contenue, normalisée, dans une **ligne du Narrateur citée dans `evidence`** (`Quotes.narratorLines`) : une ligne `e…` de `chat` sur le canal `narrator` ou `operator`, ou un appel `t…` **réussi** de `say`, `give_book` ou `show_title` (ses chaînes, sauf `to`).
- **La fenêtre du travail ne compte plus pour le canon** : la preuve citée suffit. Elle compte toujours pour la citation d'une interaction (mots du joueur, `public` ou `pray`, dans la fenêtre).
- Messages (le modèle les lit tels quels) :
  - `quote is required for a promise, the Narrator's exact words: cite the NARRATOR line in evidence and copy its words`
  - `quote is too short to show what the Narrator said (3 words or 15 characters at least): cite the NARRATOR line in evidence and copy its words`
  - `quote must be words of a NARRATOR line cited in evidence (an e… NARRATOR line, or a t… say, give_book or show_title call): cite the NARRATOR line in evidence and copy its words`

  Aucun ne dit plus « leave quote out ». Le refus d'une citation d'**interaction** le dit encore : elle est facultative, et une interaction n'engage pas le Narrateur.

### Rêveur (`Source.DREAMER`)

| Écriture | Permis |
|---|---|
| `addMemory` | `observation` seulement |
| `addCanon` | `arc` au statut `planned`, `lore` ; jamais de `quote` (`DuckMemoryStore.DREAMER_CANON`) |
| `resolveCanon` | les `arc` seulement |
| `addInteraction` | rien |
| `setGag` | permis (fraîcheur des gags existants) |

- **`running_gag` par `addCanon` est refusé au Rêveur**, comme `remember` le refuse au niveau de l'outil (lot B, décision 1) : ses « opérations de gag existantes » sont `set_gag`, que le magasin accepte.
- **Ajout de ma part** : `addInteraction` est refusé au Rêveur (`the Dreamer does not rate interactions…`). Aucun de ses outils n'en écrit ; une interaction déplace la faveur, et le Rêveur lit le chat des joueurs.
- `useCanon` n'est pas restreint : le Rêveur n'a pas `use_canon`.

### Surnoms et titres : le nom entre « » d'abord

Conséquence de la longueur minimale : le Chroniqueur cite quelques mots autour du nom (« Salut, le Plongeur »), le Directeur souvent le nom seul (« le Plongeur »). `sameName` compare donc d'abord **le premier nom entre « » des deux énoncés**, puis les citations, puis les énoncés, normalisés. Sans cela, le Chroniqueur aurait retiré le surnom actif du Directeur pour le même surnom (et perdu ses usages).

## Constat 4 : une interaction sans preuve

`Dedup.interaction` : parmi les interactions non archivées du même joueur et de la même sorte à ±10 min, la plus récente est un doublon :
- si elle partage une preuve (`e…` ou appel d'outil) ;
- si **elle n'a aucune preuve** (ni `evidence_events` ni `tool_calls`) ;
- ou, quel que soit le reste, si **celle qui arrive n'a aucune preuve**.

Sinon, la similarité de texte (0,6) comme avant. Deux interactions qui citent chacune leur propre ligne restent deux (c'est le cas du Directeur depuis le lot B, qui cite les lignes de chat de son réveil).

À savoir :
- deux notes du Directeur **sans preuve**, de même sorte, à moins de 10 min (un réveil sans chat, un `DUE`) n'en font qu'une : c'est le prix accepté ;
- un châtiment noté par le Directeur sans appel rattaché absorbe la couverture du code (`Sanctions`, source `engine`) du même joueur à ±10 min : la couverture rend `Duplicate` à chaque passage, sans rien écrire (décision `duplicate` en `DEBUG`).

## Constat 9 : la voix

`VoiceGuard.check(text, field, maxChars, names = emptyList(), boundQuotes = true)` :
- **`boundQuotes`** : chaque passage entre guillemets (contenu sans espaces de bord) fait au plus `VoiceGuard.MAX_QUOTED` = 200 caractères **et** au plus la moitié du texte (`longueur × 2 ≤ texte`). Message : `<field> quotes too much (a passage of 54 characters between quotes, in a text of 58): quote 200 characters at most and less than half the text; tell the rest in the third person`.
- **Apostrophes** : `'`, `’`, `‘` (U+2018) et `ʼ` (U+02BC). U+02BC est une lettre pour Java : il est testé avant les lettres. `Quotes.normalize` plie aussi U+02BC.
- **`names`** : remplacés par `Joueur` avant la recherche des pronoms, mots entiers, sans casse. Un joueur nommé `Moi` masque aussi le pronom « moi » écrit par le modèle : c'est le prix accepté.

Dans le magasin (`Job.voice`) :
- les noms sont **tous ceux de la table `players`** (une requête par travail, à la première vérification), pas seulement ceux du travail : le Grimoire, dont seul `writeDoc` vérifie la voix depuis le lot E, peut citer `Moi` et `Toi` hors guillemets (testé) ;
- les citations ne sont bornées que pour les textes **des modèles** (`DIRECTOR`, `CHRONICLER`, `DREAMER`). Le code (`ENGINE`) cite des énoncés déjà vérifiés et des résultats d'outils (`apply_effect « slowness II on Bartok for 1m »`, la note d'expiration du lot E), souvent plus de la moitié de son texte ; un opérateur est un humain.
- Effet pour les modèles : un résumé qui n'est presque qu'une citation (`a dit « narrateur t'es nul et tu le sais »`) est refusé ; les mots exacts vont dans `quote`, quand l'outil en a un.

## Constat 13 : l'horloge d'une interaction à son heure

- `DuckPlayClock.playerSecondsAt(connection, player, at)` : la somme, sur les sessions ouvertes avant `at`, de `least(coalesce(left_at, at), at) - joined_at`. C'est la formule `playtime` de la fiche bornée à `at` (le Journal ferme à son ouverture les sessions qu'un plantage a laissées ouvertes).
- `addInteraction` l'utilise pour `played_s`, dans le travail d'écriture. `PlayClock.playerPlaySeconds` reste l'horloge **maintenant** (sa KDoc le dit) ; `setTrait` s'en sert toujours, à l'heure de l'écriture.
- Limite : une interaction sans preuve écrite juste après une connexion dont le Journal n'a pas encore écrit la session perd au plus le début de cette session.

## Constat 14 : annuler une sieste ou un rêve

Dans le même travail que l'annulation, et seulement si elle a défait au moins une écriture (`Job.forgetRun`) :
- la ligne `memory_runs` d'une sieste ou d'un rêve passe `failed`, `error = "reverted by operator:Clement"` : le rattrapage refait la sieste (elle compte dans `MAX_NAP_FAILURES`) ;
- pour un rêve, les clés `memory_state` `reflected:<uuid>` dont `updated_at` est entre `started_at` et `ended_at` (ou maintenant) du rêve sont **supprimées** : le rêve suivant relit ces joueurs comme une première réflexion (pas de « avant → après »). La valeur d'avant n'est connue nulle part : on ne peut pas la remettre ;
- la décision `memory reverted` le dit : `2 writes undone on 2 rows; dream run 80 marked failed; reflections forgotten: reflected:<uuid> (by operator:Clement)`.
- Un lot (`batch`) annulé reste `done` (décision du superviseur : siestes et rêves seulement). Une ligne `memory_runs` absente : rien de plus.
- **Non fait** : la réponse de `/narrator memory revert` (lot F) ne détaille pas ces effets ; `revertRun` rend toujours un `Int`. `canon.due_signaled_at` n'est toujours pas audité : la KDoc le dit.

## Constat 23 : KDoc de `MemoryStore`

- La KDoc de l'interface décrit les règles par auteur et dit que `revertRun` supprime des lignes (leur contenu reste dans `memory_log`).
- Celle de `revertRun` dit ce qu'il fait (lignes, sieste, rêve) et ce qu'il ne défait pas (`recalls` et renforcements, le reste de `memory_state` et de `memory_runs`, les références orphelines, `due_signaled_at`).

## Ce que les autres lots doivent savoir

- **Constantes publiques** pour les outils et les prompts : `DuckMemoryStore.SAID_KINDS`, `DREAMER_CANON`, `Quotes.MIN_WORDS` (3), `Quotes.MIN_CHARS` (15), `VoiceGuard.MAX_QUOTED` (200).
- **Lot C, `MemoryServiceTest`** › « a batch of the Chronicler writes a promise… » : **échoue sur cette branche**, comme l'annonçait le lot B. La promesse scriptée n'a pas de `evidence`. Correctif attendu : garder l'identifiant de la ligne `NarratorSpoke` publiée à 20:04:00 dans `evening()` (ou le lire après `journal.sync()`), et ajouter `"evidence": ["e<identifiant>"]` à l'appel `remember`. La réponse du modèle étant scriptée avant `evening()`, ce n'est pas une simple donnée : je ne l'ai pas modifié.
- **Lot F, `MemoryCommandsTest`** › « revert undoes every write of a run… » : une donnée changée (`MemoryKind.REFLECTION` → `OBSERVATION` pour le souvenir écrit par le Rêveur), que la nouvelle règle refusait. Le test reste ce qu'il était.
- **`ChroniclerReplayTest`**, **`MemoryToolsTest`** (lot B) : verts sans changement ; le premier vérifie seulement que le refus commence par `quote must be words`, ce que le nouveau message garde.
- **Lot G** : `Job.voice` relit `SELECT name FROM players` alors que `Names.of` lit déjà la table ; une seule plomberie pourra servir les deux. `MemoryKind.REFLECTION` n'est plus permis au Rêveur, seul auteur prévu : il peut disparaître.

## Vérifications

- Un test par constat, qui échouait avant le correctif :
  - constat 1, les trois scénarios de la relecture, sur le vrai magasin (`DuckMemoryStoreTest`) : « a promise proven only by a player's chat line is refused, with or without a quote » (et les huit sortes dites sans citation), « a quote of one word proves nothing », « the Dreamer plans arcs, keeps lore and observations, and records nothing that was said » ; plus « the Chronicler's commitments copy a Narrator line it cites » (ligne non citée, ligne d'opérateur, `give_book`, `say` en échec, opinion sans citation) et le surnom du Chroniqueur reconnu par son nom entre « » ;
  - constat 4 : « an interaction without evidence is the same as one of the same player and kind within ten minutes, both ways » ;
  - constat 9 : `VoiceGuardTest` (bornes des citations, apostrophes, noms masqués) et `DuckMemoryStoreTest` › « players' names are no voice, the passages quoted by a model are bounded » (un Grimoire qui cite `Moi` et `Toi` hors guillemets est accepté ; le code cite librement) ;
  - constat 13 : `DuckMemoryStoreTest` › « an interaction recorded late takes the player's clock at its time », `DuckPlayClockTest` › « a player's clock at a past time… » ;
  - constat 14 : `MemoryAuditTest` › « reverting a nap or a dream fails its run, and a dream forgets the reflections it wrote ».
- `StoreOnJournalTest` cite la ligne publiée sur le vrai Journal. Les autres tests du lot ont été adaptés aux règles (auteurs `ENGINE` pour les annulations génériques, un Rêveur qui n'écrit plus de promesse).
- `git merge main` (lots B, E et F) : sans conflit.
- `.\gradlew.bat build` sur l'état fusionné : **1 121 tests unitaires, 1 échec**, `MemoryServiceTest` du lot C (voir plus haut), attendu tant que le lot C n'a pas ajouté la preuve. Le build s'arrête là ; `.\gradlew.bat runGameTest` lancé à part : **All 171 required tests passed**.
- Aucun appel réel à Claude ni à Jev ; pas de `runServer`.
