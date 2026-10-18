# Passation M3-FIX-B : outils et prompts des travaux

Branche `worktree-agent-a8685cd2af9dc85fd`. Ce lot corrige les constats **1 (côté outils), 4, 7 et 16** de la relecture de M3 (`docs/design/reviews/M3_REVIEW.md`), selon les décisions du superviseur. Le magasin (`DuckMemoryStore`, `Quotes`, `Dedup`) appartient au lot A : il n'est pas touché ici. **En cas de contradiction, le code fait foi**, et ce fichier explique pourquoi.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `tool/memory/MemoryArgs.kt` | `RememberKind.told` (les huit sortes dites aux joueurs) ; la description de `quote` dit la règle du journal d'échanges |
| `tool/memory/MemoryTools.kt` | `remember` et `resolve` refusent, avant le magasin, ce que le rôle de l'appelant ne peut pas écrire ; `note_interaction` cite les lignes de chat du réveil et lit la faveur par `Favor.read` |
| `tool/ToolCatalog.kt` | `DREAMER_SHARED` sans `sql` ni `player_profile` |
| `prompts/chronicler.md` | `quote` obligatoire, trois mots au moins, la ligne NARRATOR en tête de `evidence` |
| `prompts/dreamer.md` | plus de `sql` ni de `player_profile` ; la règle du canon du Rêveur |

`ChroniclerTools.kt` et `DreamerTools.kt` n'ont pas eu à changer.

Tests : `MemoryToolsTest` (2 nouveaux, 3 modifiés), `ChroniclerToolsTest` (1 nouveau), `DreamerToolsTest` (1 nouveau, 1 modifié), `ToolCatalogTest`, `PromptToolMentionsTest` et `DreamReplayTest` (modifiés).

## Constat 1 : chaque rôle n'écrit que ce qu'il a le droit d'écrire

Les règles sont vérifiées dans `RememberTool.checkApplies` et `ResolveTool.act`, avant tout appel au magasin, et avec le rôle de `Caller` (pas la `Source` de l'auteur) :

| Rôle | `remember` | `resolve` |
|---|---|---|
| Directeur | toutes les sortes, comme avant | toutes les entrées |
| Chroniqueur | toutes les sortes ; pour `promise`, `threat`, `challenge`, `law`, `prophecy`, `nickname`, `title` et `place` (`RememberKind.told`), `quote` est obligatoire | toutes les entrées |
| Rêveur | `arc` (qui part `planned`), `lore` et `observation` seulement (`RememberKind.DREAMER_KINDS`), et jamais de `quote` | les `arc` seulement |

Messages, tels que le modèle les lit :

- Chroniqueur sans citation : `Invalid arguments: a threat needs quote: copy 3 words or more exactly from the Narrator's NARRATOR line, and cite that line in evidence`. Ce problème s'ajoute aux autres de `checkApplies`, tous rendus d'un coup.
- Rêveur, sorte interdite : `Invalid arguments: the Dreamer remembers only an arc (planned), lore or an observation, not a promise: what the Narrator tells the players is recorded from its own words, by itself or the Chronicler`. Ce refus passe avant tous les autres : les autres problèmes n'ont pas de sens pour une sorte refusée.
- Rêveur, citation : `quote does not apply to the Dreamer, who never heard the Narrator: leave it out`.
- `resolve` du Rêveur sur autre chose qu'un récit : `Invalid arguments: the Dreamer resolves only arcs, and C-45 is a promise: the Narrator and the Chronicler settle the rest`. L'entrée est lue d'abord (`store.row`) ; une entrée qui n'existe pas est laissée au magasin (`C-99 does not exist`).

### Décisions

1. **`running_gag` refusé au `remember` du Rêveur.** La décision du superviseur met `running_gag` dans le canon du Rêveur « pour ses opérations de gag existantes », c'est-à-dire `set_gag` : le magasin du lot A doit donc l'accepter pour `Source.DREAMER`. Un nouveau gag par `remember`, en revanche, ferait parler le Narrateur à sa place, et l'outil le refuse. Pour l'autoriser, il suffit d'ajouter `RememberKind.RUNNING_GAG` à `DREAMER_KINDS`.
2. **L'outil n'exige pas `evidence`, et ne compte pas les mots de la citation.** Ces deux contrôles sont du magasin (lot A), qui seul normalise la citation (accents, guillemets, espaces : `Quotes.normalize`) et retrouve la ligne citée. Un second compte dans l'outil, sur le texte brut, dirait autre chose que le magasin : `« le Plongeur »` fait quatre mots bruts, deux une fois normalisé. Exiger `evidence` dans l'outil aurait aussi cassé `MemoryServiceTest` (lot C) avant la fusion du lot A (voir plus bas). L'outil ne vérifie donc que la présence de `quote` : c'est le contournement que la relecture décrit (le modèle retire la citation pour passer), et il est fermé dès ce lot, avant même la fusion du lot A.
3. **Le Directeur n'a aucune restriction** : il est le Narrateur, dans son propre tour. Son `remember` sans citation reste permis.
4. **Le schéma** : seule la description de `quote` change (+ 125 caractères, lus par le Directeur à chaque requête) : « With an exchange log: required for what was said to players, 3 words or more copied from the NARRATOR line cited in evidence. » Les sortes du Rêveur ne sont pas dans le schéma : la description de `remember` est commune aux trois rôles, et le Directeur n'a pas à lire une règle du Rêveur. `dreamer.md` et le message de refus la disent.

### Prompts

- `chronicler.md`, étape 1 du lot : `quote` est obligatoire, d'au moins trois mots copiés tels quels, et pour un surnom c'est la phrase qui le donne (« désormais tu es le Plongeur »), pas le surnom seul ; `evidence` commence par la ligne NARRATOR, « où le magasin cherche la citation ». La règle « Un engagement n'existe que si le Narrateur l'a dit » ajoute : « Sans ses mots copiés et sa ligne NARRATOR citée, l'outil refuse : ce que tu ne peux pas citer ne s'enregistre pas. » Le premier exemple « Mal » cite aussi la citation de deux mots et la ligne absente de `evidence`. Taille : 10 742 caractères sur 11 000.
- `dreamer.md`, « Règles » : « Le canon est la parole du Narrateur » (des récits et du lore sans citation, des observations, `resolve` pour les récits, `set_gag` pour les gags, l'outil refuse le reste). Taille : 13 969 caractères sur 15 000.

## Constat 4 : le Directeur cite les lignes de chat de son réveil

`note_interaction`, appelé dans un tour du Directeur (`Caller.turnId` non nul), ajoute à ses preuves les lignes de chat **du joueur visé** (canaux `public` et `pray`) qui figurent dans `turns.trigger_events` du tour en cours.

- La ligne `turns` est écrite par le Directeur au début du tour (`Turn.run`, `services.trace.turn(record(null))`), avec ses `trigger_events` : le `barrier()` qui précédait déjà la lecture des appels du tour suffit. Une seule lecture rend les lignes et, pour une sanction, les appels.
- **Limite** : les événements injectés pendant le tour (`injected_events`) ne sont écrits dans `turns` qu'à la fin du tour (`Director.kt`, lot D). Une ligne de chat arrivée entre deux appels d'outils n'est donc pas citée. C'est le cas rare : le joueur qui réveille le Directeur est dans `trigger_events`. Pour la couvrir, le Directeur devrait réécrire sa ligne `turns` à chaque injection.
- Ces preuves partagent la borne `EVIDENCE_MAX` (20) avec les preuves données et les appels rattachés ; les appels passent en premier.
- Conséquences dans le magasin :
  - l'heure de l'interaction devient celle de la première ligne citée, et non l'heure de l'appel : c'est plus juste ;
  - `Dedup.interaction` trouve une preuve commune quand le Chroniqueur note la même ligne dix minutes plus tard : `ok: already noted as I-…`. Le doublon « sans preuve » du lot A reste utile pour les réveils sans chat (un `DUE`, un battement).
- Le résultat de l'outil ne change pas : le Directeur ne voit jamais les références `e…`. Le log `DEBUG` dit `I-1000 cites e900 of turn 42`.

## Constat 7 : le Rêveur n'a plus `sql` ni `player_profile`

`DREAMER_SHARED` = `remember`, `resolve`, `recall`, `note_to_director` : **15 outils** (11 de sa famille et 4 partagés), au lieu de 17. Sans mémoire, le Rêveur n'a plus aucun outil. `dreamer.md` dit que chaque étape reçoit ce dont elle a besoin, la fiche comprise, et ne garde que `recall`.

Mesure de `DreamReplayTest` (la requête réelle envoyée au faux serveur) : les définitions d'outils du Rêveur font **16 665 caractères**, contre ≈ 24 400 relevés par la relecture, soit ≈ 7 700 caractères (≈ 2 200 tokens) de moins dans le préfixe de chaque requête d'un rêve.

Le `ToolEnv` d'une conversation de mémoire (`MemoryAgents`, hors de ce lot) passe toujours `journalAvailable` : ni le Chroniqueur ni le Rêveur n'ont plus d'outil qui en dépende. C'est sans effet, et il n'y a rien à changer.

## Constat 16 : la faveur avant et après sans assembler deux fiches

`note_interaction` lit la faveur par `journal.read { Favor.read(it, uuid, maintenant) }`, avant et après l'écriture, au lieu de `PlayerSheets.sheet(…).favor` : quatre requêtes au lieu d'une trentaine, deux fois. C'est la même formule que la fiche (`DuckPlayerSheets` appelle `Favor.read`). L'heure est relue après l'écriture, pour que l'interaction écrite à l'heure du magasin soit comptée (`ts <= now`). Un échec de lecture omet toujours la ligne de faveur (`WARN`), sans faire échouer l'appel.

Effet sur les tests : la faveur vient maintenant du vrai Journal, plus des fiches factices. Dans `MemoryToolFixture`, Bartok a 0 s de jeu, donc chaque interaction pèse 1 : `favor = 100 · Σv / (3 · (n + 3))`, soit `-17` après une insulte (−2), puis `-17 → -20` après une provocation (−1), et `+8` après des excuses (+1).

## Ce que les autres lots doivent savoir

- **Lot A (magasin)** :
  - le refus d'une citation inventée n'est plus vérifié mot pour mot dans `MemoryToolsTest` (seulement `INVALID` et rien d'écrit) : le message est le vôtre ;
  - `MemoryAuditTest` (votre fichier) écrit une promesse avec `Author(Source.DREAMER, runId = 9)` (ligne 109) : votre nouvelle règle la refusera ;
  - la décision du superviseur met `running_gag` dans le canon du Rêveur, pour `set_gag` (`setGag`, opération sur un gag existant) ; l'outil `remember` ne lui permet pas d'en créer (décision 1).
- **Lot C** :
  - `MemoryServiceTest` › « a batch of the Chronicler writes a promise… » : la promesse scriptée a une citation de huit mots, mais **pas de `evidence`**. Elle passe avec ce lot ; elle sera refusée dès que le magasin du lot A exigera la ligne citée. Correctif attendu : garder l'identifiant rendu par `publish("20:04:00", NarratorSpoke(…))` dans `evening()`, et ajouter `"evidence": ["e<cet identifiant>"]` à l'appel `remember`.
  - `ChroniclerReplayTest` : rien à changer pour ce lot. Ses trois `remember` ont une citation de six mots ou plus et citent déjà leur ligne NARRATOR (`e${e.slimes}`, `e${e.granted}`). Seule l'assertion `results[2].third.startsWith("Invalid arguments: quote must be words")` dépend du nouveau message de refus du lot A.
- **Lot D** : voir la limite du constat 4, avec la ligne `turns` écrite seulement au début et à la fin du tour.
- **Docs** : `M3-09.md` et `M3-11.md` disent encore que le Rêveur a `player_profile` et `sql` (17 outils) : cette passation les remplace.

## Vérifications

- Tests ciblés (`MemoryToolsTest`, `ChroniclerToolsTest`, `DreamerToolsTest`, `ToolCatalogTest`, `PromptToolMentionsTest`, `DreamReplayTest`, `ChroniclerReplayTest`, `PromptsTest`, `PersonaFrozenTest`, `MemoryServiceTest`) : verts.
- Nouveaux tests :
  - `MemoryToolsTest` › le Chroniqueur sans citation refusé, avec une citation inventée refusée par le magasin, preuve mal formée, puis promesse écrite, et `lore` sans citation acceptée ;
  - `MemoryToolsTest` › le Rêveur : dix sortes refusées, citation refusée, `lore` et `observation` acceptées, `resolve` d'une promesse refusé ;
  - `MemoryToolsTest` › la note du Directeur cite e900 (la ligne de Bartok dans `trigger_events`), pas celles de Mimosa, du Narrateur ni une ligne hors du réveil ; elle est datée de cette ligne ; la note du Chroniqueur sur e900 est `already noted` ;
  - `ChroniclerToolsTest` › sur la soirée de référence, une menace sans citation refusée, puis écrite avec sa citation et sa ligne NARRATOR ;
  - `DreamerToolsTest` › sur la soirée du rêve, menace et surnom refusés, `resolve` du surnom et du gag refusés (leur statut reste `active`), un récit prévu puis abandonné, du lore.
- `PromptToolMentionsTest` : le Rêveur a 15 outils, tous cités par `dreamer.md`, et rien d'autre.
- `git merge main` (lot F) : sans conflit. Puis `.\gradlew.bat build` (tests unitaires puis `runGameTest`) : **vert, 1 105 tests unitaires (0 échec, 0 sauté) et 171 GameTests** (`All 171 required tests passed`).
- Aucun appel réel à Claude ni à Jev ; pas de `runServer`.
