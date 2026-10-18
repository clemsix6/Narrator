# Passation M3-FIX-D : Directeur, briefing, connexion

Branche `worktree-agent-abfa57dea2853e3ff`. Ce lot corrige les constats **3, 11, 12, 18, 23** (`DirectorControl`) et **26** de `docs/design/reviews/M3_REVIEW.md`, selon les décisions du superviseur. **En cas de contradiction avec une passation antérieure (M3-07, M3-13, M3-14, M3-15), le code et cette passation font foi.**

## Constats 3 et 11 : ce que la mémoire montre n'est écrit qu'une fois la requête validée

### Le choix

Les écritures `recalls` et `markNote` ne se font plus pendant la construction du texte, mais **après la validation de la requête qui l'a porté**, par le Directeur. C'est l'option que la relecture jugeait la meilleure. Je l'ai préférée à « écrire une fois le texte construit » pour quatre raisons :

- **C'est la seule qui soit juste dans tous les cas.** Écrire en fin de construction règle le délai dépassé, mais pas le 529, le 429, le refus ni le contexte plein : la requête n'est pas validée, le modèle n'a rien lu, et pourtant le souvenir serait renforcé, son délai de 180 min lancé, la note comptée comme montrée.
- **Elle règle le constat 11 sans mécanisme de plus.** Un briefing reconstruit après un échec n'écrit rien, seul celui qui est accepté est enregistré, donc une seule fois par chapitre et dans la forme rendue.
- **Elle supprime du code.** `Showings` (le registre des notes montrées par chapitre), le jeu `delivered` des indices de `Reminiscence` et les blocs `try/catch` d'écriture de `MemoryBriefing`, `JoinBrief` et `Reminiscence` disparaissent. Une note montrée par une requête refusée n'a jamais été marquée : elle revient d'elle-même.
- **Elle supprime le cas limite signalé par le lot E** : une note rendue par `giveBack` puis fermée par `Hygiene.tidy` avant d'être remontrée. Elle n'est plus marquée tant que la requête n'est pas validée, donc `tidy` ne la ferme pas.

### Le mécanisme

- `ChapterMemory.unrecorded` : la liste des `Shown(refs, channel, context, notes)` des requêtes pas encore validées. Le briefing (`briefing`), une connexion (`join`), une réminiscence ou un indice livré (`reminiscence`) y ajoutent ce qu'ils montrent ; ils ne touchent plus au magasin.
- `ChapterMemory.mark()` rend un `ChapterMemory.Mark`. Il remplace `MemoryMark`, qui était privé au Directeur, et `restore()` remet `shownRefs`, `sheetsShownAt` **et** `unrecorded`.
- **Validation** (`Director.Turn.commit`) : `takeUnrecorded()` vide la liste, puis `Memory.recordShown(shown)` part dans une coroutine du Directeur (`scope.launch`). Le Directeur n'attend donc jamais la base entre deux requêtes, et une erreur y est journalisée sans casser la boucle.
- **Échec** (`giveBack`) : `mark.restore()` jette ce qui n'a pas été montré.
- `Memory.recordShown` a une implémentation par défaut, `Shown.record(store, shown)` : une ligne `recalls` par `Shown`, puis chaque note marquée `shown` une seule fois (dédoublonnée sur toute la liste). Chaque écriture est tentée à part, et un échec donne un `WARN`. `MemoryService` n'a pas besoin de la redéfinir.

### Délais et échecs de la mémoire (constat 3)

- `Director.attachments` prend une marque avant l'appel et la restaure sur une exception ou au-delà de `ATTACH_TIMEOUT_MS` (3 s).
- Au-delà du délai, le bloc devient :
  ```
  <memory>
  (the sheet of Bartok could not be read in time: player_profile Bartok)
  </memory>
  ```
  Une ligne apparaît pour chaque joueur dont la fiche aurait été attachée (`WakeAttachments.late`, `JoinBrief.sheetsDue`) : pas un premier passage, pas de fiche montrée depuis sa connexion, ni il y a moins de 30 min. Sans un tel joueur, le bloc est `null`.
- Sur une exception, aucune ligne n'est ajoutée, comme la décision le prévoit : seul le délai en reçoit une. Le cas n'arrive pas en pratique, car `WakeAttachments` ne lève jamais d'exception pour la mémoire.
- Comme la marque est restaurée, la fiche revient à la connexion suivante dans le chapitre, et rien n'est enregistré.
- `Briefing.memorySections` fait de même au-delà de `MEMORY_TIMEOUT_MS` (10 s) ou sur une exception : il restaure la marque, et les six sections disent `(unavailable: …)`.

### Forme réduite (constat 11)

- `Memory.briefing(chapter, now, reduced = false)` : le `Briefing` passe la forme qu'il rend.
- `MemoryBriefing` construit toujours les deux formes (le contrat `BriefingSection` ne change pas), mais n'enregistre que ce que la forme demandée montre :

| Section | Forme complète | Forme réduite |
|---|---|---|
| `grimoire` | rien (une référence citée dans un texte n'est pas montrée) | rien |
| `chronicle` | saga, saison, sessions, chapitres nommés | saga et saison |
| `canon` | les lignes rendues | les mêmes |
| `sheets` | les lignes des fiches condensées, et leurs joueurs dans `sheetsShownAt` | rien |
| `memories` | les souvenirs rendus | rien (section absente) |
| `notes` | les notes rendues, à marquer | les mêmes |

- **Changement de règle par rapport à M3-13** : `sheetsShownAt` ne reçoit plus que les joueurs dont la fiche **condensée** est rendue. Une ligne `LINE` (forme réduite, ou au-delà de 6 joueurs ou du budget) n'est pas la fiche.
- Conséquence : la connexion de ce joueur dans le chapitre attache sa fiche, y compris dans le même réveil. C'est voulu, car les derniers arrivés, ceux qui ont une ligne, sont souvent ceux dont l'événement `JOIN` ouvre le réveil.

## Constat 12 : tri par score hors de l'outil `recall`

- Dans `HybridRecall`, une requête sans critère (ni mot, ni joueur, ni étiquette, ni identifiant) prend toutes les lignes, avec une pertinence de 1 :
  - pour `RecallPurpose.TOOL`, les `LATEST_CANDIDATES` (200) plus récentes de chaque table, **triées par date**, comme avant ;
  - pour les autres usages, **toutes** les lignes (filtrées par statut) **triées par score** (importance × force × âge), donc les plus forts du serveur.
- Le seul usage concerné aujourd'hui est `<memories>` quand personne n'est en ligne : un événement rare (une lecture de toute la table `memories`).
- `Match.latest` est devenu `any` (aucun critère) et `latest` (et tri par date).

## Constat 18 : ce qu'un opérateur oublie ne sort plus de `recall`

- Même avec `include_closed`, une ligne `archived` est exclue des candidats quand son **dernier** `memory_log` d'op `archive` ou `restore` est un `archive` d'un acteur `operator:…` (`CandidateQuery.notForgotten`, un `arg_max(…, rowid)` sur `memory_log`). Cela vaut pour les souvenirs, le canon et les interactions.
- Une ligne archivée par l'hygiène (`engine`) ou fusionnée (`merge`) reste visible avec `include_closed`. Si un opérateur restaure une ligne et que l'hygiène l'archive ensuite, elle redevient visible : son dernier archivage n'est plus le sien.
- Les tables brutes restent lisibles par `sql`, comme le dit la réponse de `forget` (lot F).

## Constat 23 : KDoc de `DirectorStatus.turnsThisHour`

- Elle dit maintenant : « only addressed chat, prayers, whispers and the commitments that come due (`DUE`, M3 §11.3) wake the Director ».

## Constat 26 : les références viennent des lignes

- `SheetRenderer.rendered(sheet, detail, now, parts)` rend un `SheetRenderer.Rendered(text, refs)`. `render` en est le texte.
- Les références sont celles des lignes que la forme garde et nomme :
  - en-tête : surnom et titre ;
  - interactions (historique, marquées, manques de respect en `FULL`) ;
  - canon ouvert et souvenirs en `FULL`.
- Chaque `Item` porte sa `ref`, et `fitItems` ne rend que celles des éléments gardés après la coupe.
- Une preuve (`proofs M-140`), un lien (`· canon C-50`) ou les mots d'un joueur (« souviens-toi de M-999 ») ne sont pas des lignes montrées. La forme `LINE` n'en montre aucune.
- `SheetRenderer.refs(texte)` et son expression régulière sont supprimés.
- `MemoryBriefing` et `JoinBrief` rendent les fiches avec leur propre `SheetRenderer(zone)`, celui de `DuckPlayerSheets`. Le paramètre `sheets` ne sert plus qu'à lire la fiche.

## Contrats et API changés

| Où | Changement |
|---|---|
| `Memory` | `briefing(chapter, now, reduced = false)` ; `recordShown(shown)`, avec une implémentation par défaut |
| `ChapterMemory` | `unrecorded`, `mark()` / `Mark.restore()`, `takeUnrecorded()` |
| `Shown` (nouveau, `Memory.kt`) | `Shown(refs, channel, context, notes)` et `Shown.record(store, shown)` |
| `SheetRenderer` | `rendered()`, `Rendered` ; `refs(String)` supprimé |
| `JoinBrief` | `joins(signals)` et `sheetsDue(signals, chapter, now)` dans le companion |
| `WakeAttachments` | `late(signals, chapter, now)` dans le companion |
| `MemoryBriefing` | `sections(chapter, now, reduced = false)` ; `Showings` et `ENGINE` supprimés |
| `Director` | `MemoryMark` supprimé (remplacé par `ChapterMemory.Mark`) |
| `FakeMemory` | `attach` devient suspendante, et un test peut y attendre ; `brief` (ce que le briefing fait au chapitre) ; `briefedReduced` ; `recordShown` écrit dans `store` (`recalls`, `writes`) |

## Lignes touchées hors du lot

- `memory/MemoryService.kt` (lot C) : **une ligne**, la délégation de `briefing` avec `reduced`. À garder telle quelle lors de la fusion avec le lot C.
- `test/…/memory/MemoryServiceTest.kt` (lot C) : **une ligne**, la redéfinition de `briefing` dans `LateMemory`, sans quoi le test ne compile plus.
- `test/…/memory/BriefingFixture.kt` : l'aide `accept(chapter)` (trois lignes), qui enregistre comme le fait le Directeur. C'est le banc de `BriefingMemoryTest` et `JoinBriefTest`.

## Ce que les autres doivent savoir

- **Tests de la mémoire** : après `sections()`, `block()` ou `forWake()`, rien n'est écrit. Il faut d'abord « accepter » la requête : `Shown.record(store, chapter.takeUnrecorded())`, ou `f.accept(chapter)` dans `BriefingFixture` et `ReminiscenceTest`.
- **Délai de la réminiscence** : il part maintenant de la validation de la requête, et non de la construction du bloc. Une réminiscence dont la requête échoue ne lance aucun délai : elle revient telle quelle (`attached`), sans nouvelle décision.
- **Délai dépassé sur les pièces jointes** : la décision `memory recalled` et le compte horaire d'une réminiscence ont pu être pris avant la coupure. Les événements partent sans le bloc et sont validés, donc la réminiscence de cet événement est perdue. C'est sans gravité : une réminiscence est une occasion, pas une obligation.
- **Arrêt** : une écriture lancée après la validation peut être annulée par l'arrêt du serveur. On perd au plus une ligne `recalls` (un renforcement), jamais un texte.
- **Lot F (fusionné)** : la réponse de `forget` (« out of the briefings, reminiscences and recall (even with include_closed); the raw tables stay readable by sql ») est maintenant exacte.
- **Hors de mes fichiers, non modifié** :
  - la KDoc de `MemoryStore.archive` dit « it stays searchable with `include_closed` » : c'est faux pour un archivage d'opérateur (lot G) ;
  - `AdminCommands.turnsLine` dit encore « none left: only addressed chat, prayers and whispers wake it », sans les engagements dus.
  - `director.md` pourrait décrire la ligne `(the sheet of X could not be read in time: player_profile X)` : c'est un fait consigné, et l'outil à appeler s'il veut la fiche.
- **Lot G** : `RecallContext.turnId` reste `null` pour le briefing, les connexions et les réminiscences. On pourrait le remplir à la validation, puisque le Directeur connaît alors le tour.

## Tests

| Test | Ce qu'il prouve |
|---|---|
| `MemoryScenariosTest` › `what the memory showed is recorded once its request is accepted, never for a request that failed` | un 529 : rien d'écrit. La requête rejouée est acceptée : briefing reconstruit, `C-45` (`briefing`) et `I-512` (`join`) écrits une fois chacun, `N-9` marquée une fois (constats 3 et 11) |
| `MemoryScenariosTest` › `a memory too slow to attach showed nothing, and the sheet it would have shown is named` | une mémoire qui marque le chapitre puis attend 10 s : la ligne de remplacement après `</events>`, le chapitre restauré, rien d'écrit (constat 3) |
| `MemoryScenariosTest` › `a memory too slow to brief showed nothing, and its sections say so` | le briefing au-delà de 10 s : sections `(unavailable: …)`, chapitre restauré, rien d'écrit (constat 3) |
| `BriefingTest` › `a memory that fails says so in each of its sections…` | sur une exception, le chapitre est restauré (`shownRefs`, `sheetsShownAt`, `unrecorded`) |
| `BriefingTest` › `the reduced briefing keeps the grimoire…` | la forme réduite est demandée à la mémoire (`briefedReduced`) |
| `BriefingMemoryTest` › `what the briefing shows goes into the chapter and gets a briefing recall once the request is accepted` | rien n'est écrit avant `accept`, puis tout l'est |
| `BriefingMemoryTest` › `a reduced briefing shows and records only what its reduced form holds` | canon, saga, saison et notes, mais ni sessions, ni souvenirs, ni lignes de fiches, ni `sheetsShownAt` (constat 11) |
| `BriefingMemoryTest` › `a briefing built again after a refused request shows the same notes and is recorded once` | `mark`, puis `restore`, puis reconstruction : mêmes notes, chaque note marquée une fois et chaque référence écrite une fois (constat 11) |
| `BriefingMemoryTest` › budgets au maximum | `sheetsShownAt` = les seules fiches condensées |
| `BriefingMemoryTest` › `a player's words that look like a ref are not a ref shown` | « souviens-toi de M-999 » est dans la fiche, mais pas dans `shownRefs` (constat 26) |
| `SheetRendererTest` › `the refs a sheet shows are the rows it names…`, `a player's words that look like a ref…` | les références viennent des lignes : pas les preuves ni les liens, aucune dans `LINE`, aucune venue d'une citation ou d'un résumé (constat 26) |
| `JoinBriefTest` › `a memory too slow to attach leaves a line for each sheet it would have shown` | `WakeAttachments.late` : fiche due, fiche du briefing, premier passage, joueur reparti |
| `JoinBriefTest` › `the dream hint is shown at one join, and again only when the request that showed it failed` | `mark`, puis `restore`, puis le même bloc : piste marquée une fois, références écrites une fois |
| `ReminiscenceTest` › `a request that was not accepted gets the same reminiscence again`, `a cue reaching the Director…` | une seule écriture et une seule décision, après acceptation |
| `HybridRecallTest` › `what an operator forgot stays out, even with includeClosed…` | oubli d'un opérateur exclu (souvenir, canon, interaction) ; hygiène, fusion et « restauré puis archivé par l'hygiène » visibles (constat 18) |
| `HybridRecallTest` › `without any criterion a briefing gets the strongest rows, and the tool the latest` | `BRIEFING` trie par score, `TOOL` par date (constat 12) |

## Vérification

- Tests ciblés, avant la fusion de `main` : `MemoryScenariosTest` (12), `BriefingTest` (16), `BriefingMemoryTest` (14), `JoinBriefTest` (14), `ReminiscenceTest` (22), `HybridRecallTest` (15), `SheetRendererTest` (12), `SheetGoldenTest` (3), `BriefingVoiceTest` (3), `MemoryServiceTest` (3), `MemoryContractsTest` (3), `PersonaFrozenTest` (1) : tous verts.
- `git merge main` (lots A, B, E et F) : sans conflit.
- Puis `.\gradlew.bat build` : **1 130 tests unitaires, 1 échec**, qui ne vient pas de ce lot.
  - C'est `MemoryServiceTest` › « a batch of the Chronicler writes a promise… » (lot C) : sa promesse n'a pas de preuve citée, et le magasin du lot A la refuse (`invalid`). L'échec est attendu jusqu'à la fusion du lot C (passations M3-FIX-A et M3-FIX-B), et il se produit sur `main` de la même façon.
  - Les 31 scénarios du Directeur (`DirectorScenariosTest`, `DirectorResilienceTest`, dont les 22 de T18) et les 12 de `ChapterScenariosTest` (dont les 11 de T19) sont verts.
- `.\gradlew.bat runGameTest`, lancé à part puisque le build s'arrête sur cet échec : **All 171 required tests passed**.
- Aucun appel réel à Claude ni à Jev ; pas de `runServer`.
