# Passation M6-FIX-4 et M6-FIX-5 : le programme du rêve, une seule plume

Lots M6-FIX-4 et M6-FIX-5 de la relecture de M6, partie 2 (`design/reviews/M6_REVIEW_2.md`) : constats **1, 2, 7, 8**
(option comprise) et les trous du rêve du constat **12** (décision **D5 : oui**) ; constat **3** (décision **D6 : oui**) ;
l'option de M6-FIX-5, la simplification de `propose_idea` (constat 10 de la partie 1), **faite**.

**En cas de contradiction avec `M6.md`, le rapport ou les passations M6-06, M6-07 et M6-08, le code et cette passation
font foi.**

Branche `worktree-agent-a76e61fc8f20e8da3`.

## Ce qui change

| Fichier | Changement |
|---|---|
| `memory/dream/ProgrammeInputs.kt` | `<ideas>` en deux parts (constat 1, D5) ; les idées closes lues en SQL depuis le dernier rêve ; une réplique ne suit plus aucune idée (constat 2) ; répliques lues une fois (constat 8) ; `Table.copy` au lieu de 15 arguments |
| `memory/dream/DreamInputs.kt` | étape 2 : deux versions de la revue lues, plus toutes ; le style lu à part, jamais décisif (constat 7) |
| `memory/dream/Dreamer.kt` | la décision `memory programme` compte les récits prévus et abandonnés (option du constat 8) |
| `style/StyleReport.kt` | `lines` ne rend que les répliques relisibles (`Tics.opens`) ; `registers` accepte les répliques déjà lues (constat 8) |
| `storyteller/Deck.kt` (hors fiche, un mot) | `Table` devient `data class` (pour `copy`) |
| `memory/DuckMemoryStore.kt`, `tool/memory/MemoryTools.kt` | le Rêveur refusé sur un récit `planned` (constat 3, D6) ; `addIdea` rend `IdeaAdded` (option) |
| `memory/MemoryStore.kt` | `IdeaAdded(row, closed, open)` ; KDoc des règles du Rêveur |
| `memory/Memory.kt`, `memory/MemoryService.kt` | signature de `addIdea` (une ligne chacun) |
| `tool/storyteller/StorytellerTools.kt` | `propose_idea` ne relit plus les idées ouvertes (option) |
| `docs/MEMOIRE.md` §5, `docs/design/M6.md` §6.3 | le bloc `<ideas>` (4 000) ; le Rêveur ne touche pas un récit prévu |
| tests hors fiche : `IdeasStoreTest`, `DreamReplayTest` | `addIdea` rend `IdeaAdded` ; une aide clôt un récit prévu par le Directeur ; la décision `memory programme` nomme le récit prévu |

### Constat 1 et D5 : le bloc `<ideas>`

```
<ideas open="12" closed="4" closed_since="10-01 21:30">
Open, the latest first:
- P-41 challenge · 47h12m of play left · Bartok, Mimosa · « La course au pic » · n4 f4 e4 sports_commentary · dealt 2: 10-01 23:00 inspire → challenge reach 4m later; 10-01 23:30 heartbeat → nothing: Course au sommet du pic enneigé…
…
Closed since 10-01 21:30, those dealt first:
- P-38 punishment « La pluie de grenouilles » · dropped 10-01 22:20 (Bartok ne provoque plus le Narrateur.) · dealt 1: 10-01 21:01 inspire → nothing
- P-12 twist « Le coffre inversé » · expired 10-01 22:05 (its hours of world play are over) · never dealt
(+2 more closed)
</ideas>
```

- **Deux parts qui ne se prennent pas la place.** Les closes d'abord, dans leur part : `CLOSED_MAX` = 1 000 caractères
  de lignes, chaque ligne coupée à `CLOSED_LINE_MAX` = 240 (donc **quatre au moins** au pire), le reste compté
  (`(+N more closed)`). Les ouvertes prennent **toute la place restante** de `IDEAS_MAX` = **4 000** : **toutes** sont
  montrées, chaque ligne coupée par partage équitable (`BriefText.share`, water-filling) ; le pitch vient en dernier
  (100 caractères au plus, `OPEN_PITCH`), donc c'est lui qui part d'abord, puis la fin des rendus détaillés.
- **Les closes sans pitch** : référence, sorte, titre, statut, date et raison (80 caractères), rendus et ce qui a suivi.
  Ni joueurs ni notes. **Les rendues d'abord** (c'est leur suite qui renseigne le Scénariste), puis les plus récentes.
- **Les ouvertes** perdent `open, ` de leur état (la part le dit) ; le reste de la ligne est inchangé.
- **Lues en SQL** : `status <> 'open' AND closed_at >= ?` (dernier rêve, ou 7 jours sans rêve précédent),
  `ORDER BY dealt > 0 DESC, closed_at DESC, id DESC LIMIT 100`, dans la même lecture que le reste. Une idée écrite il y a
  dix jours et close hier est montrée, même si soixante idées ont été écrites après elle.
- Vide : `<ideas open="0">(none open, none closed lately)</ideas>`, comme avant (`DreamReplayTest`).
- `STEP_MAX` suit (somme des budgets) : **32 750** au lieu de 31 250.

**Mesures** (`ProgrammeInputsTest`) :

| Cas | Avant | Après |
|---|---|---|
| sonde de la relecture : 12 ouvertes (2 joueurs, titre 40, pitch 350, rendues 2 fois), 4 closes (dont une écrite il y a 10 jours) | 8 idées montrées sur 16 (2 252 caractères, relecture) | **12 ouvertes et 4 closes**, 3 999 caractères ; lignes ouvertes de 271-272 caractères, soit **≈ 70 caractères de pitch** |
| maximum (13 ouvertes à 8 joueurs, titres de 60, rendues 5 fois ; 10 closes) | une partie des ouvertes, aucune close (le test ne comptait pas) | **13 ouvertes et 4 closes**, 3 999 caractères (les rendus des ouvertes coupés) |
| ouverture entière au maximum | 29 726 | 31 275 (sur 32 750) |

**Écart au rapport : le pitch.** Le rapport visait « un pitch coupé à environ 100 caractères » et « environ 4 000 ». Avec
12 ouvertes rendues deux fois (≈ 200 caractères d'en-tête chacune) et 4 closes, 4 000 caractères laissent ≈ 70 caractères
de pitch ; 100 partout demanderait ≈ 4 400. J'ai gardé 4 000 (la décision) et un pitch de 100 au plus, réduit
équitablement quand la place manque : le titre identifie l'idée, et le magasin refuse de toute façon une redite.

**Coût** : le bloc passe de 2 500 à 4 000 caractères au plus, soit **≈ 680 tokens de plus au pire** (2,2 caractères par
token, M3-19 §21.1) ; dans le cas de la sonde, +1 750 caractères ≈ **+800 tokens** sur une ouverture d'environ 9 000
tokens en soirée réelle. Le premier message est écrit en cache une fois et relu aux requêtes suivantes : **≈ 0,007 $ par
programme** aux prix de la configuration.

### Constat 2 : une réplique ne suit aucune idée

`follows` rend faux pour une clé `say:<registre>` pour les sortes sans levier propre (saison, étape de récit, `forge`,
retournement), qui gardent « n'importe quel levier ». Les autres sortes ne comptaient déjà jamais une réplique.

### Constat 7 : la revue de l'étape 2

- `docVersions(Review)` n'est plus appelé : `doc(key)` puis `doc(key, version - 1)`. **Limite** : si un `revert` a
  supprimé la version précédente (versions 1 et 3), la période d'avant manque et `<diversity>` n'a pas de comparaison ;
  rien d'autre ne change.
- Les trois blocs de style (`<registers>`, `<tone_flags>`, `<diversity>`) sont lus **dans leur propre lecture** (15 s),
  dans un `try` : sur erreur ou expiration, un `WARN` (`dream: the style of the review could not be read, step 2 goes
  without it: …`) et la revue part sans eux, comme sans mots de réaction. L'annulation passe.

### Constat 8 : les comptes des répliques

- **`StyleReport.lines` ne rend plus que les répliques que la relecture lit** (`Tics.opens` : dites avec `say`, 25
  caractères au moins), avec la même condition SQL que `coverage` (`REVIEWED_TEXT`, partagée). **Écart au rapport**, qui
  proposait de ne compter que dans les deux blocs sans toucher `lines` ; la consigne du superviseur était que la page
  Style en profite. Conséquences :
  - `<history>` (« N said ») et `<registers lines=…>` comptent les répliques relisibles ;
  - **la page Style de M6-12** (`StylePage.kt:167`, inchangée) : « N lines of the Director », la colonne `#Lines` et les
    tics par session portent sur ces répliques, comme sa ligne de couverture ;
  - les tics (expressions et points de suspension) d'une période ne voient plus les titres ni les apartés courts ;
  - **la veille du style n'a pas changé** : `/narrator style` et ses mesures (`StyleWatch.diversity`) portent toujours
    sur les répliques tenues, titres compris. Les deux comptes peuvent donc différer de quelques titres.
- `ProgrammeInputs` lit les répliques une fois : `StyleReport.registers(connection, period, words, lines)` (paramètre
  facultatif, les appels existants ne changent pas).
- `Table` de `storyteller/Deck.kt` devient `data class` ; `draws` fait `copy(ideas = emptyList())`.
- **Option** : la décision `memory programme` compte aussi les récits : raison `done: 2 ideas written, 0 closed, 1 arc
  planned` (les récits ne sont nommés que s'il y en a), détail `{status, written, closed, arcs_planned, arcs_abandoned}`.
  Le rapport de l'étape suit (`step 5 done: 2 ideas written, 0 closed, 1 arc planned`).

### Constat 3 et D6 : le Rêveur ne touche pas un récit prévu

| Couche | Message |
|---|---|
| magasin (`resolveCanon`, source `DREAMER`) | `the Dreamer settles an active arc only; C-12 is planned: a planned arc is the storyteller's, and the Narrator starts it` |
| outil `resolve` (rôle `DREAMER`, avant le magasin) | `the Dreamer resolves only an active arc, and C-12 is planned: a planned arc is the storyteller's, and the Narrator starts it` |

Vers `active` comme vers `abandoned`. Un récit actif se clôt toujours (`resolved`, `abandoned` : `CanonKind.allows`). Le
Directeur lance un récit prévu, le Scénariste l'abandonne ; un opérateur et le moteur restent libres.

### Option de M6-FIX-5 : `propose_idea` ne relit plus les idées ouvertes

- `MemoryStore.addIdea` rend **`IdeaAdded(row, closed, open)`** au lieu d'`Added<Idea>` (une idée n'est jamais un
  doublon) : l'idée, celles que son écriture a closes (la saison remplacée, l'idée au-delà des douze), et le nombre d'idées
  ouvertes après l'écriture, compté dans la même tâche d'écriture.
- `propose_idea` en tire sa réponse, mot pour mot la même qu'avant : plus aucune lecture des idées ouvertes autour de
  l'écriture (deux lectures de moins par idée).
- Suivis : `Memory.DisabledStore`, le magasin enveloppé d'`OpenIdeas.watching`, `FakeMemory` (qui ne clôt rien) et
  `IdeasStoreTest` (deux lignes : `.row` au lieu d'`assertIs<Added.New<Idea>>`).

## Prompts

**Aucune phrase n'est à changer.** `dreamer.md:102` réserve déjà `resolve` aux récits actifs ; `storyteller.md:8` décrit
déjà `<ideas>` (ouvertes, closes depuis le dernier programme, rendus et suite). Seule précision facultative pour M6-16,
si `dreamer.md` a la place (14 949 sur 15 000 à M6-07) : ligne 111, remplacer « `resolve` ne tranche que des récits. »
par « `resolve` ne tranche que des récits actifs. ».

## Ce que les tâches suivantes doivent savoir

- **M6-12 et toute page qui lit `StyleReport.lines`** : ce sont désormais les répliques relisibles. Un compte de toutes
  les lignes du Directeur se lit dans `chat` (`channel = 'narrator'`, `turn_id` non nul).
- **M6-17 (`ProgrammeEndToEndTests`)** : le format de `<ideas>` ci-dessus ; la décision `memory programme` nomme les
  récits quand il y en a.
- **M6-18 (T4)** : relire si ≈ 70 caractères de pitch suffisent au Scénariste pour juger une idée ouverte ; sinon porter
  `IDEAS_MAX` à ≈ 4 400.
- **Tout appelant d'`addIdea`** : `IdeaAdded.row` comme avant ; `closed` et `open` sont exacts au moment de l'écriture.

## Vérifications

- **Rouges avant le correctif** (tests écrits d'abord, lancés sur le code de `3d5c7f9`) : 13 échecs, tous attendus.
  - `ProgrammeInputsTest` : la sonde de la relecture (12 ouvertes, 4 closes, dont une de dix jours derrière soixante
    autres) et le test au maximum (l'en-tête n'avait pas `closed=`, et les comptes) ; le format en deux parts ; un
    retournement suivi d'une réplique (`heartbeat → say courtroom 1m later` au lieu de `→ nothing`) ; `<history>` qui
    comptait un titre et un aparté (`Lines: 4 said` au lieu de 2).
  - `DreamInputsTest` : `every version of review read` (le magasin enveloppé refuse `docVersions`) ; le style illisible
    (vue `narrator_lines` supprimée) faisait échouer toute la revue (`Catalog Error`).
  - `StyleReportTest` : `lines` rendait le titre, la barre d'action, le livre et les apartés.
  - `DreamerTest` : la décision ne comptait pas le récit abandonné ; `DreamReplayTest` : idem pour le récit prévu.
  - `DuckMemoryStoreTest`, `MemoryToolsTest`, `DreamerToolsTest` : le Rêveur lançait un récit prévu (`ok: C-12 arc is
    now active, due 10-08 22:10…`).
  - Option : `StorytellerToolsTest` rouge avec l'ancien `propose_idea` remis à titre d'essai (`the open ideas read
    again`), vert avec le nouveau.
  - Les deux trous du constat 12 sur des chemins justes (entrées du programme qui lèvent, annulation pendant l'étape 5)
    sont verts avant comme après : ils gardent le comportement.
- **Tests ajoutés ou étendus** : `ProgrammeInputsTest` (+2, 3 adaptés), `DreamerTest` (+3 : programme en échec avec le
  Grimoire écrit, entrées qui lèvent, annulation puis reprise à l'étape 5), `DreamInputsTest` (+2), `StyleReportTest` (+1),
  `DuckMemoryStoreTest`, `MemoryToolsTest`, `DreamerToolsTest`, `StorytellerToolsTest` (1 réécrit), `IdeasStoreTest`
  (`closed` et `open` rendus par `addIdea` ; son aide `arc` clôt un récit prévu par le Directeur, plus par le Rêveur),
  `DreamReplayTest` (rejoué : la décision et le rapport nomment le récit prévu).
- **Build complet** (`.\gradlew.bat build --continue`) après la fusion de `main` à `a33adc5` (M6-FIX-2, M6-FIX-7-8,
  M6-16) : **vert**, **2 238 tests unitaires, 0 échec, 0 ignoré** ; **« All 398 required tests passed »**. Le build
  précédent (avant M6-16) n'avait qu'un échec, l'aide d'`IdeasStoreTest` ci-dessus, corrigée. Aucun test de temps
  instable rencontré.
- Aucun appel réel à Claude. Journaux de build sous des noms `m6fix45-*` (dossier de travail de la session).
