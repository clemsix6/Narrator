# Passation M6-FIX-1 : la veille du style

Lot M6-FIX-1 de la relecture de M6, partie 1 (`design/reviews/M6_REVIEW_1.md`) : constats 1, 4, 5 et 7, la partie
« citations et `Flaw` » du constat 10, les trous 1 et 3 du constat 11, décisions **D3** et **D4** (oui toutes deux).
**En cas de contradiction avec `M6.md`, les passations M6-02 à M6-04 ou le rapport, le code et cette passation font
foi.**

Branche `worktree-agent-a5610cf095976ffef`.

## Ce qui change

| Fichier | Changement |
|---|---|
| `style/LineReviewer.kt` | le filtre du bus passe par `Tics.opens` (constat 1) ; `MIN_CHARS` retirée |
| `style/LineReviewLabels.kt` | `inLine` compare les candidats aux **mots** de la réplique (constat 5) |
| `style/StyleWatchImpl.kt` | `recentLines` sans titres, barre d'action ni livres (constat 1) ; `narrateur(s)` hors des candidats (D4) ; citations par `ModelText.guillemets` |
| `style/StyleReport.kt` | `Flaw` devient un alias de `StyleNoteKind` (constat 10) ; registre dominant de la voix et part de `plain` (D3) ; `coverage` ne compte que les répliques relisibles (constat 1) |
| `style/ToneWatch.kt`, `tool/speech/SpeechTools.kt` | citations par `ModelText.guillemets` (constat 10) |
| `memory/stats/Tics.kt` | noms élidés normalisés (constat 4) ; `bySay`, `KIND_PATTERN` ; six mots vides de plus |
| `core/ModelText.kt` | `guillemets(text, max)` : la seule citation « … » des mots du Narrateur |
| tests | `LineReviewerTest` (+1, 2 adaptés), `TicsTest` (+1), `StyleWatchTest` (+2), `StyleReportTest` (+2, 4 adaptés), `DiversityTest` (1 adapté), `ModelTextTest` (+1), `LiveLineReviewEvalTest` (`Tics.opens` au lieu de `MIN_CHARS`) |

## Constat 1 : titres, barre d'action et livres ne sont jamais relus

- **Une seule règle**, `Tics.opens(text)` : dit avec `say` (aucun préfixe `[title] `, `[actionbar] `, `[book "…"] `) et
  25 points de code au moins. Elle décide des amorces (M6-03) **et** de la relecture (`LineReviewer.reviewed`). Avant,
  la relecture ne regardait que la longueur : `[title] DÉFI : LA COURSE AU PIC / Premier au sommet` était relu.
- La longueur se compte désormais en **points de code** (avant : en unités UTF-16, `String.length`), comme les amorces.
- `StyleWatch.recentLines` ne rend que les lignes dites avec `say` (`Tics.bySay`), courtes comprises : un « Non. » est
  du contexte pour la redite, un titre ne l'est pas.
- `StyleReport.coverage` ne compte que les répliques relisibles : en SQL, `NOT regexp_matches(c.text, Tics.KIND_PATTERN)
  AND length(c.text) >= 25` (le motif est celui de `Tics`, lu par RE2 ; `length` de DuckDB compte les caractères, comme
  `Tics.opens` : un test avec des émojis vérifie qu'il ne compte ni les octets ni les unités UTF-16). `/narrator style` dit donc « Review: 9 of the Director's latest 10 lines
  labeled » sur les seules répliques que la relecture peut lire.
- **Le test publie les vrais textes**, préfixés comme `SpeechTools` les publie dans un tour : deux titres (dont
  `[title] GASPARD vs FIRMIN / la revanche`), `[actionbar] Il reste trente secondes !`, un livre, plus une réplique
  courte ; aucune n'est relue, la vraie réplique l'est.

## Constat 4 : les noms élidés

- `Tics.tokens` : quand un mot porte une élision dont la partie avant l'apostrophe est un mot vide (`d'`, `qu'`, `l'`,
  `jusqu'`…) et qu'un nom commence après l'apostrophe, le mot donne **deux jetons** : le mot vide écrit en entier (son
  `e` rendu : `de`, `que`, `jusque` ; `l'` et `s'` valent aussi `la` et `si`, jamais devant un nom), puis `<nom>`.
  - « Le cadeau d'Opaline et de Bartok » → `le cadeau de <nom> et de <nom>` ;
  - « Qu'Opaline se méfie » → amorce `que <nom>` (sans mot plein : jamais un tic) ;
  - un nom qui porte lui-même une élision (surnom « l'Ancien ») est d'abord cherché tel quel ;
  - `l'aviation`, `aujourd'hui` : inchangés (aucun nom après l'apostrophe).
- **Écart au rapport** : il proposait un seul jeton `<nom>`. Mais alors « le cadeau d'Opaline » (`cadeau <nom>`) et
  « le cadeau de Bartok » (`cadeau de <nom>`) restaient deux constructions, ce que le scénario du rapport dénonçait.
  Avec l'article écrit en entier, c'est une seule (`cadeau de <nom> brille encore` ×3 dans le test).
- `Tics.words` (les candidats béquilles) ne voit plus `opaline` : c'est un `<nom>`.
- Mots vides ajoutés : `jusqu`, `jusque`, `lorsqu`, `lorsque`, `puisqu`, `puisque` (conjonctions, comme `si`, `mais`,
  `comme` déjà présents). Les corpus de M4 et M5 donnent exactement les mêmes amorces, expressions et mesures.

## Constat 5 et D4 : les mots béquilles candidats

- `LineReviewLabels.inLine` compare chaque candidat aux mots de la réplique (`Tics.words` : mots pleins, article élidé
  retiré), à la casse, aux accents et au pluriel en `s` près : `grand` n'est plus dans « grandiose », `enquête` est
  dans « l'enquête ». Les béquilles gardées (`crutches`) passent par la même fonction.
- `StyleWatchImpl.crutchCandidates` écarte `narrateur` et `narrateurs` (`NOT_CANDIDATES`, donnée de recherche en
  français comme les mots vides de `Tics`). **Ce n'est pas un `<nom>`** : « Le Narrateur… » reste une amorce visible.

## Constat 7 et D3 : le registre dominant

- `StyleReport.Measures.topRegister` : le registre **de la voix** (`ComicRegister.VOICE`) le plus servi parmi les
  répliques relues ; `null` quand aucune réplique relue n'a de registre de la voix (toutes `plain` ou `other`).
- `topRegisterShare` : sa part **parmi les répliques de la voix** (le même dénominateur que l'entropie), et non plus
  parmi toutes les relues. C'est ce que mesure le seuil de T6 (≤ 35 %) : la rotation des cadres. Sur le jeu de
  données des tests, `divine_bureaucracy` passe de 60 % (3 sur 5 relues) à 75 % (3 sur 4 de la voix).
- **`Measures.plainShare`** (nouveau) : la part de `plain` parmi les répliques relues, montrée à part.
- Bloc `<diversity>` du Rêveur :
  `openers distinct 1.00 (1.00 before) · top register divine_bureaucracy 75% (before: courtroom 50%) · plain lines 20% (0% before) · register entropy 0.41 (0.75 before) · crutch « céleste » 40% (25% before)`.
  Sans registre de la voix : `no register of the voice` (avant, `topRegister!!` aurait levé une exception dans ce cas,
  devenu possible) ; une période d'avant sans registre de la voix : `(before: none)`.
- `StyleWatch.diversity()` (donc `/narrator style`) prend la nouvelle définition par `StyleReport.measures` : le
  chiffre affiché change de sens sans changer de forme. La part de `plain` n'y est pas (voir « Hors de mes fichiers »).

## Constat 10 : une seule citation, un seul `Flaw`

- `ModelText.guillemets(text, max = ∞)` : `« … »`, coupé à `max` points de code, échappé, ses « » changés en ‹ ›. Elle
  remplace les trois copies (`StyleWatchImpl.quote`, `ToneWatch.quote`, `SayTool.quoted`) et sert aussi à la béquille
  du bloc `<diversity>`. Sorties identiques (les tests de `ToneWatch`, du refus de `say` et de la ligne `Style:` passent
  sans changement).
- `StyleReport.Flaw` est désormais **`typealias Flaw = StyleNoteKind`** (alias imbriqué, stable depuis Kotlin 2.3 ; le
  projet est en 2.4.20). Mêmes constantes, même ordre, même `id` : tout code qui écrit `StyleReport.Flaw.DARK`,
  `Flaw.entries` ou `flaw.id` compile sans changement (dont `web/pages/StylePage.kt` de M6-12, vu dans son worktree).
  On pourra remplacer l'alias par `StyleNoteKind` partout plus tard ; je ne l'ai pas fait pour ne pas toucher M6-12.

## Signatures publiques

**Aucune signature lue par M6-07 ou M6-08 ne change** (`Tics.read`, `Tics.render`, `Tics.Tic`, `StyleReport.review`,
`registers`, `registerLine`, `lines`, `measures`, les budgets). Le détail :

| API | Changement | Qui l'utilise |
|---|---|---|
| `LineReviewer.MIN_CHARS` | **retirée** (code mort une fois la règle unique) | `LiveLineReviewEvalTest`, adapté (`Tics.opens`) |
| `StyleReport.Flaw` | enum → alias de `StyleNoteKind` : compatible à la source, pas au binaire (tout recompile ensemble) | `StyleWatchImpl`, `StyleCommands` (`flaw.id`), `StyleReportTest`, `StylePage` (M6-12) |
| `Measures.topRegister`, `topRegisterShare` | **sens changé** (D3) ; `topRegister` peut être nul avec des répliques relues | `StyleWatchImpl.diversity()`, `/narrator style`, `<diversity>`, `StylePage` (M6-12), évals |
| `Measures.plainShare`, `Tics.bySay`, `Tics.KIND_PATTERN`, `ModelText.guillemets` | ajoutées | |
| `StyleWatch.recentLines`, `crutchCandidates`, `StyleReport.coverage`, `LineReviewLabels.inLine`, `Tics.tokens`, `Tics.isStop` | même signature, comportement corrigé (ci-dessus) | |

## Hors de mes fichiers : à ordonner

Je n'ai touché que les fichiers du lot. Restent, chacun de quelques lignes :

1. **`style/Style.kt`** (KDoc seulement) : `Diversity.topRegister` (« the register of the most labeled lines ») et
   `topRegisterShare` (« its share of the labeled lines ») disent l'ancienne définition ; `StyleWatch.crutchCandidates`
   devrait ajouter « ni le nom du Narrateur ».
2. **`/narrator style`** (`command/StyleCommands.kt` et `Diversity` dans `style/Style.kt`) : pour y montrer la part de
   `plain` (D3), ajouter `plainShare` à `Diversity` (en dernier, avec une valeur par défaut : compatible à la source)
   et l'afficher dans `diversityLine`. **Défaut d'affichage introduit par D3** : quand les 50 dernières répliques
   relues sont toutes `plain` ou `other`, `diversityLine` dit « no line labeled » (elle teste `topRegister == null`),
   ce qui est faux ; il faudrait « no register of the voice · plain lines 100% ».
3. **`prompts/dreamer.md`** (étape 2) décrit `<diversity>` comme « amorces distinctes, registre dominant, entropie,
   béquille la plus fréquente » : y ajouter la part des répliques `plain` et préciser que le registre dominant se
   compte parmi les registres de la voix (M6-16 relit les prompts).
4. **`M6.md` §3.7** : la ligne « Part du registre dominant » se lit désormais « parmi les répliques relues dont le
   registre est de la voix (sans `plain` ni `other`) ».

## Ce que les tâches suivantes doivent savoir

- **M6-07 (rêve, fusionné)** : `DreamInputsTest` et `DreamReplayTest` rejoués après la fusion de `main`, verts. Le bloc
  `<diversity>` gagne `plain lines N%` (il tient toujours sous 400 caractères ; budget testé).
- **M6-08 (briefing)** : `BriefingMemoryTest` vert ; `Tics.read` et `Tics.render` inchangés. Les tics normalisent
  maintenant les noms élidés : un « d'Opaline » compte comme `de <nom>`.
- **M6-12 (page Style)** : `Measures.topRegister` peut être nul alors que des répliques sont relues ; `plainShare` est
  disponible pour une colonne à part. `StyleReport.Flaw` reste utilisable tel quel.
- **M6-17 (assemblage)** : seule une réplique de `say` d'au moins 25 points de code est relue ; un `show_title`, un
  `actionbar` ou un `give_book` ne déclenche jamais d'appel au faux LLMGW.
- **M6-18 (évals)** : la part du registre dominant de T6 est désormais celle de la voix ; la part de `plain` est à côté.

## Vérifications

- **Rouges avant le correctif** (tests écrits d'abord, lancés sur le code de `main` à `879c957`) : 10 échecs, tous
  attendus.
  - `LineReviewerTest` : le titre préfixé est relu (`expected: <9> but was: <4>`, l'unique réponse partie au titre) ;
    `inLine(["grand", "pomme"], "Un plan grandiose…")` rendait `[grand, pomme]` ; le candidat `<line>` était trouvé
    dans `</line>` (sous-chaîne).
  - `TicsTest` : `[le, cadeau, d'opaline, et, de, <nom>]` au lieu de `[le, cadeau, de, <nom>, et, de, <nom>]`.
  - `StyleWatchTest` : candidats `[narrateur, cadeau, opaline, …]` ; contexte avec `[title] …`, `[actionbar] …`,
    `[book …] …`.
  - `StyleReportTest` : `coverage` comptait 15 répliques au lieu de 10 ; registre dominant `PLAIN` au lieu de
    `SPORTS_COMMENTARY` ; part 0,6 au lieu de 0,75. `DiversityTest` : 0,6 au lieu de 0,75.
- **Verts après** : `style.*`, `TicsTest`, `ModelTextTest`, `SayRestingOpenerTest`, `SpeechToolsTest`,
  `StyleCommandsTest`, `DreamInputsTest`, `DreamReplayTest`, `BriefingMemoryTest`, `TestMethodsReturnUnitTest` :
  115 tests, 0 échec.
- **Build complet** (`build --continue`) :
  - sur `main` à `879c957` (M6-11 fusionné) avec le correctif : **2 184 tests unitaires**, 0 échec, 0 ignoré ; **« All
    398 required tests passed »** ;
  - après la fusion de `main` à `1f3f2d9` (M6-FIX-3, aucun fichier commun) : **2 187 tests unitaires**, 0 échec,
    0 ignoré ; **« All 398 required tests passed »**. Aucun test instable rencontré.
- Aucun appel réel à Claude. Journaux de build sous des noms `m6-fix-1-*` (dossier de travail de la session).
