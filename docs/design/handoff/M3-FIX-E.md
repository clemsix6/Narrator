# Passation M3-FIX-E : hygiène, Rêveur, stats

Branche `worktree-agent-a447a1a4fca708dff`. Ce lot corrige les constats **6, 9** (`expiredNote` et `GrimoireFormat`), **10, 15** et **25** (délai de `Tics`) de `docs/design/reviews/M3_REVIEW.md`, et ajoute la KDoc de `Tics.STOP_WORDS` (section « Vérifié, sans constat »). Seuls les fichiers du lot E ont changé. **En cas de contradiction, le code fait foi.**

## Ce qui est en place

### Constat 6 : les paires ne débordent plus

- `PairStats` (CTE `together`) filtre d'abord par axe (`abs(a.x - b.x) <= 32`, de même pour `y` et `z`), puis calcule le carré de la distance en `BIGINT` (`CAST(a.x AS BIGINT) - b.x`…).
- Le `BIGINT` reste nécessaire même avec le filtre : DuckDB peut réordonner les conditions d'un `AND`. La différence elle-même reste en `INTEGER` sans risque (60 millions au plus avec la bordure du monde).
- La KDoc de la requête explique le piège.

### Constat 9 : noms entre « » et voix du Grimoire laissée au magasin

- `Hygiene.expiredNote` met les noms entre « » : `C-45 promise (« Mimosa ») came due 05-14 22:10 with no resolution recorded: « … »`. `VoiceGuard` ignore ce qui est entre guillemets : un joueur nommé `Moi`, `Toi` ou `Tu` ne fait plus refuser la note.
- `GrimoireFormat.problems` ne vérifie plus que la **forme** : sept sections dans l'ordre, rien avant la première, aucune autre en-tête, aucune vide. Il n'appelle plus `VoiceGuard.check`.
- **Le Grimoire passe toujours une vérification de voix** : celle du magasin, dans `DuckMemoryStore.writeDoc` (`voice(content, "text", MemoryLimits.GRIMOIRE)`). Elle couvre aussi le budget, le texte vide et les chevrons. Le lot A y ajoute le masquage des noms des joueurs connus.
- Conséquence pour le modèle : les problèmes de forme et ceux de voix arrivent en deux temps (la forme d'abord, puis le refus du magasin), et non plus dans le même message. L'outil `write_grimoire` garde son `@MaxLen`.

### Constat 10 : les notes se ferment toutes les 10 minutes

- `closeNotes` tourne dans `tidy`, donc toutes les 10 minutes, et plus seulement avant un rêve. `beforeDream` le reçoit par `tidy` et ne le relance pas. `tidy` journalise aussi quand il n'a fermé que des notes.
- Règles (`Hygiene.closeNotes`) :
  - une piste de rêve (`dream_hint`) passe `done` **dès qu'elle a été montrée** (`shownCount > 0`) ;
  - une piste jamais montrée passe `done` après `DREAM_HINT_LIFE` (7 jours), comme avant ;
  - toute autre note (`coherence`, `expired`, `operator`) passe `done` après `COHERENCE_SHOWINGS` affichages (2), qui est aussi `MemoryBriefing.NOTE_SHOWINGS`.
- `expiresAt` n'a pas été touché : son sort est réglé au lot G.

### Constat 15 : le Rêveur ne relit plus ses propres observations comme du nouveau

- `DreamInputs.candidates` ne compte plus les souvenirs `source = 'dreamer'`. Le bloc `<new_memories>` de `DreamInputs.player` les exclut aussi, pour que son `count` reste celui du candidat.
- **Option choisie : exclure ces souvenirs**, plutôt que poser `at` à la fin de la conversation. Raisons :
  - Poser `at` plus tard **perdrait du vrai nouveau** : le jeu, ou les souvenirs du Directeur et du Chroniqueur écrits pendant la conversation, qui dure jusqu'à 10 minutes.
  - Cela ne couvrirait pas non plus **les observations que le Rêveur écrit ailleurs** : sur un joueur B pendant la conversation du joueur A, ou aux étapes 2 à 5, après toutes les réflexions.
  - Enfin, les stats « avant » de la réflexion sont lues au même moment que `at` : décaler l'un sans l'autre les rendrait incohérents.
  - Exclure est exact : le Rêveur n'écrit que des souvenirs `observation` (contrat du constat 1), qui sont ses conclusions et rien de ce que le joueur a fait. Ses écritures de canon (`arc`, `lore`, `running_gag`) n'entrent pas dans `candidates`, et il n'écrit pas d'interactions.
- `Dreamer.kt` n'a pas changé.

### Constat 25 : délai de `Tics.lines`

- `Tics.lines` passe par `SheetStats.query`, qui pose `queryTimeout = SheetStats.QUERY_TIMEOUT_SECONDS` (30 s), comme les autres requêtes des stats.
- La KDoc de `Tics.STOP_WORDS` dit que ces mots sont des données de recherche en français, jamais montrées, comme `CueWords`.

## Tests

| Test | Ce qu'il prouve |
|---|---|
| `PairStatsTest` › `players far apart are compared without overflow` (paramétré : 40 000 et 9 000 000 blocs) | deux joueurs à 10 blocs l'un de l'autre, loin sur deux axes, et un troisième aussi loin de l'autre côté. Avant le correctif : « Overflow in multiplication of INT32 » dans les deux cas. Après : la seule paire proche, avec 10 minutes ensemble |
| `HygieneTest` › `the names of the note are quoted, so a player called Moi…` | une note d'expiration pour `Moi` et `Toi` : texte exact, et `VoiceGuard.check` ne trouve rien |
| `HygieneTest` › `every 10 minutes, notes shown as often as the briefing shows them…` | via `tidy` : `coherence`, `operator` et `expired` fermées à 2 affichages et pas à 1 ; une piste fermée à 1 affichage ou à 7 jours ; une seule écriture `markNote` par note ; le rêve ne trouve plus rien à fermer |
| `HygieneTest` (expirations, note coupée) | les textes attendus ont les noms entre « » |
| `GrimoireFormatTest` › `the voice, the chevrons and the budget are left to the store…` | `problems` ne dit rien de la voix, des chevrons ni de la longueur. Par le vrai outil `write_grimoire` sur le vrai magasin (`DreamerFixture`), un Grimoire à la première personne et un Grimoire à chevrons sont refusés, sans rien écrire, et le Grimoire valide est écrit |
| `DreamInputsTest` › `the Dreamer's own memories are nothing new about a player` | une observation du Rêveur sur Bartok et Mimosa, écrite après leur réflexion : aucun candidat. Un souvenir du Directeur ensuite : Bartok seul, `memories = 1`, et `<new_memories>` ne montre pas l'observation |
| `TicsTest` › `the lines are read within the stats' time limit` | une connexion mandataire (`java.lang.reflect.Proxy`) relève le `setQueryTimeout` : 30 s |

## Ce que les autres lots doivent savoir

- **Lot C (`MemoryJobs.tidy`)** : la ligne `memory_runs` de l'hygiène n'est écrite que si `report.expired` ou `report.worn` ne sont pas vides. Il faut y ajouter `report.notesDone`. Sinon, les lignes `memory_log` des notes fermées portent un `run_id` sans ligne `memory_runs`. Sans danger, mais l'audit est incomplet. Ce fichier n'est pas à moi, donc rien n'a changé ici.
- **Lot A** : `GrimoireFormat` compte sur le masquage des noms dans `writeDoc`. Sans lui, un joueur nommé `Moi` dans `## Players` bloque encore le Grimoire, cette fois au magasin.
- **Lot D (briefing, connexions)** : une note fermée par `tidy` ne revient plus. Un cas limite existe :
  - une note (ou une piste) montrée pour la dernière fois, puis rendue (`giveBack`), devait être remontrée par `Showings.again` dans le même chapitre ;
  - si `tidy` passe entre les deux (fenêtre de 10 minutes au plus), elle est déjà `done` et ne reparaît pas.
  - L'effet est faible : la note a déjà été montrée au moins une fois avant. Si le lot D déplace les `markNote` après la validation de la requête (constat 3), le cas disparaît.
- **Lot G** :
  - `Hygiene.COHERENCE_SHOWINGS` vaut désormais pour toutes les notes sauf les pistes. On pourrait le renommer `NOTE_SHOWINGS`, en touchant la ligne `MemoryBriefing.NOTE_SHOWINGS = Hygiene.COHERENCE_SHOWINGS`, qui n'est pas à moi.
  - `NoteDraft.expiresAt` reste inutilisé.
  - `Tics` passe maintenant par `SheetStats.query` : il n'a plus de plomberie JDBC propre.

## Vérification

- Tests ciblés : `HygieneTest` (9), `PairStatsTest` (3), `TicsTest` (6), `GrimoireFormatTest` (3), `DreamInputsTest` (5), `DreamerTest` (2), `DreamReplayTest` (2), `DreamerToolsTest` : verts.
- `git merge main` (lot F inclus), puis `.\gradlew.bat build` : vert, avec 1 106 tests unitaires et 171 GameTests, sans échec.
- Log des GameTests (`build/run/gameTest/logs/latest.log`, et la sortie de Gradle) : **0** `pairs failed`, 0 `Overflow`, 0 `stats left … no pairs`.
  - Un worktree sans ce correctif en compte 92 sur les mêmes GameTests, tous des `Overflow in multiplication of INT32 (-5446196 * -5446196)`.
  - Un recalcul réussi se journalise en `DEBUG` (décision `memory stats`), d'où l'absence de ligne `stats` au niveau `INFO`.
