# Passation M6-FIX-7 et M6-FIX-8 : les traces, les docs et les KDoc

Deux lots de la relecture de M6, partie 2 (`design/reviews/M6_REVIEW_2.md`), confiés au même agent :
- **M6-FIX-7** (les traces) : constats **9** et **10**, les trous des transcriptions du constat **12**, décision **D7
  (oui)** : un seul calcul du coût par rôle, un seul libellé de `$ ?` ;
- **M6-FIX-8** (docs et KDoc) : constat **11**, dont `/narrator style` quand toutes les répliques relues sont `plain`.

**En cas de contradiction avec `M6.md`, les passations M6-10, M6-11, M6-FIX-1 ou le rapport, le code et cette passation
font foi.**

Branche `worktree-agent-a7632247a72cfc9a8`.

## M6-FIX-7 : les traces

### Constat 9 : les heures d'une transcription au fuseau du tableau de bord

- `TranscriptPage.read(file, from, zone, budget = PART_BYTES)` prend le fuseau ; chaque entrée y est rendue par
  `OffsetDateTime.parse(stamp).atZoneSameInstant(zone)`, quel que soit le décalage écrit par `Transcripts` (celui du
  serveur). La page passe `context.zone` (`journal.timeZone`, sinon l'horloge du serveur), comme pour son en-tête.
- Serveur en UTC, `journal.timeZone = Europe/Paris` : l'en-tête dit « 21:30:00 » et les entrées « 21:30:12 » (avant :
  « 19:30:12 »).
- **Laissé, hors périmètre** (la relecture le signale comme antérieur à M6) : `/narrator trace` reste au fuseau de
  `runtime.clock.zone` (`command/TraceCommands.kt`, `readJournal`), et non à `journal.timeZone`. Le choix du fuseau
  (`journal.timeZone`, sinon l'horloge) est recopié à cinq endroits (`PageContext.zoneOf`, `Workers`, `ExchangeLog`,
  `MemoryModule`, `DreamInputs`) : une fonction commune, que `/narrator trace` utiliserait aussi, serait une
  simplification à faire en une fois.

### Constat 10 et D7 : un seul calcul du coût par rôle

| Où | Ce qui change |
|---|---|
| `trace/Prices.kt` | reçoit `ModelTokens`, `Cost` et `costOf`, qui devient une **méthode** de `Prices` (les appels `prices.costOf(…)` ne changent pas d'écriture). `Cost.text` est **le seul libellé** d'un coût : `$1.92`, `$ ?`, `$1.92 + 3 calls at $ ?` (`1 call` au singulier) ; il ne dépend plus de `command.TraceText` |
| `trace/TraceQueries.kt` | `usage(since)` : **une seule requête**, groupée par fournisseur, rôle et modèle ; `Usage` gagne `models: List<ModelTokens>` (dernier champ, vide par défaut) ; les sommes du rôle et la latence moyenne (somme des latences / appels, arrondie) sont faites en Kotlin, dans le même ordre qu'avant (fournisseur, les plus servis d'abord, rôle). `TURN_SUMMARY` devient `internal` ; `ResultSet.turnSummary()` et `ResultSet.modelTokens(from)` deviennent des fonctions `internal` du fichier, avec `TOKEN_SUMS` (les sommes qu'elles lisent) |
| `web/DashboardQueries.kt` | `turns` = `TraceQueries.TURN_SUMMARY` + ses conditions, lu par `turnSummary()` ; `TOKENS` = `TraceQueries.TOKEN_SUMS`. Retirés : `TURNS` (la copie), `tokensByRole`, `TOKENS_BY_ROLE`, `ModelTokens`, `Cost`, `costOf`. `STATEMENTS` contient `TraceQueries.TURN_SUMMARY` |
| `web/pages/OverviewPage.kt` | retirés : `COST_BY_ROLE`, `RoleCost`, `CostRow`, `costByRole`, sa copie de `QUIET_OUTCOMES` et sa table des tours. Une lecture : `TraceQueries.usage` (24 h) et `lastTurns`. Chaque barre : `Cost.text · N calls[, F failed]`, comme la barre de `/costs` ; le total : `Total $0.081 + 1 call at $ ?. At the API's prices: …`. Les tours passent par `turnTable` |
| `web/pages/TraceParts.kt`, `CostsPage.kt`, `TranscriptPage.kt` | importent `Cost` et `ModelTokens` de `trace` |
| `command/TraceCommands.kt` | n'importe plus rien de `web`. `/narrator usage` lit **une** requête (`TraceQueries.usage`) au lieu de deux ; `TraceText.usage(rows, hours, prices)` prend les prix et non plus une table des coûts par rôle |

- **Écart au rapport** : il proposait de déplacer `tokensByRole` dans `TraceQueries`. Je l'ai **fondu dans `usage`** :
  c'était le même groupement (par rôle et par modèle, sur la même période), `usage` devient la seule lecture des appels
  par rôle (ligne de `/narrator status`, `/narrator usage`, vue d'ensemble), et `/narrator usage` ne lit plus deux fois
  `llm_calls` (l'autre point du constat). La signature `usage(since): List<Usage>` ne change pas : `AdminCommands`
  (ligne de statut, lot M6-FIX-6) n'est pas touché.
- **Libellé changé sur la vue d'ensemble** : « + 3 calls of a model without a price in the configuration (prices) »
  devient « + 3 calls at $ ? », le libellé commun. La page `/costs` dit toujours ce que veut dire `$ ?`.
- **La table des tours de la vue d'ensemble** est celle de `/turns` : elle gagne la colonne « Took » et le lien du
  chapitre vers sa conversation.
- `/costs` garde son propre regroupement par rôle (en mémoire, depuis ses sommes par rôle, but et modèle) : il passe
  par le même `Prices.costOf` et le même `Cost.text`, donc par le même calcul et le même libellé.

### Constat 12 : les trous des transcriptions

- **La branche `toRealPath` de `locate`** est enfin exercée : un dossier du jour, sous le dossier des transcriptions,
  qui est un lien vers un dossier extérieur. Le contrôle lexical passe, `toRealPath` refuse (`Outside`), la page le dit
  et ne montre rien du fichier ; un lien qui reste dans le dossier est suivi (`Found`, chemin réel).
  - Le lien est un lien symbolique, sinon, sous Windows sans le droit d'en créer (le cas de cette machine : « Administrator
    privilege required »), une **jonction** (`cmd /c mklink /J`, sans droit particulier) ; le test n'est ignoré
    (`assumeTrue`) que si aucun des deux ne peut être créé. Il a tourné ici (0 ignoré). Les liens sont retirés dans des
    `finally`, avant le nettoyage du `@TempDir`.
- **Une ligne plus longue qu'une page** (`Entry.Unreadable`) : budget de 1 000 octets, une note courte, une ligne de
  5 ko, une note courte.
  Trois parties : la note d'avant, la ligne sautée seule dans la sienne (« a line of 5 kB, more than a page holds », à
  son premier octet), puis la note d'après : la lecture continue.
- `TracePagesTest` : le dossier temporaire qui fuyait est un `@TempDir`.

## M6-FIX-8 : docs et KDoc

### `/narrator style` quand toutes les répliques relues sont `plain` ou `other`

- `Diversity` gagne **`plainShare: Double? = null`**, en dernier (compatible à la source) : la part des répliques
  étiquetées `plain`, de 0 à 1 ; **nulle quand aucune réplique n'est étiquetée**. `StyleWatchImpl.diversity()` la remplit
  (`measures.plainShare` quand `measures.reviewed > 0`).
- **Écart au rapport** (« une valeur par défaut ») : la valeur par défaut est `null`, pas `0.0`. Avec un seul champ, c'est
  la seule façon de distinguer « aucune réplique étiquetée » de « toutes `other` » : les deux ont `topRegister` nul et 0 %
  de `plain`.
- `StyleCommands.diversityLine` :
  - un registre de la voix : `top register divine_bureaucracy 38% · plain lines 20% · register entropy 0.71` (sans la
    part de `plain` si elle est inconnue, comme dans un `FakeStyle` qui ne la donne pas) ;
  - aucun, mais des répliques étiquetées : `no register of the voice · plain lines 100%` (avant : « no line labeled »,
    faux) ;
  - aucune réplique étiquetée : `no line labeled`.
- La page Style de M6-12 (`web/pages/StylePage.kt`, hors de mes fichiers) affiche encore `top register none` dans ce
  cas ; elle peut lire `Diversity.plainShare` pour dire la même chose.

### KDoc et docs

| Où | Correction |
|---|---|
| `style/Style.kt` | KDoc de `Diversity` : `topRegister` (le registre **de la voix** des répliques étiquetées, nul quand toutes sont `plain` ou `other`), `topRegisterShare` (sa part parmi les répliques de la voix), `plainShare` ; `StyleWatch.crutchCandidates` : « ni le nom du Narrateur » (point 1 de la passation de M6-FIX-1). L'`enum` `ComicRegister` n'est pas touchée |
| `memory/Hygiene.kt` | les candidats à la fusion vont à l'étape **6** du Rêveur (et non 5) |
| `tool/memory/MemoryArgs.kt` | `Outcome` : seul un récit que prépare le programme du Scénariste commence `planned` (et non « le Rêveur ») |
| `memory/MemoryTypes.kt` | `CanonKind.startsAs` : de même |
| `docs/MEMOIRE.md` | §2 : le schéma des couches cite les six sections du Grimoire (« ce qui tombe à plat » au lieu des « plans ») ; §5 : le coût d'un rêve compte le programme (45 000 à 115 000 tokens d'entrée de plus, 60 à 70 % relus du cache, environ 0,22 à 0,40 $, passation de M6-07) |
| `docs/design/M6.md` §3.7 | « Part du registre dominant » se compte parmi les répliques dont le registre est de la voix ; une ligne « Part de `plain` » ; l'entropie se compte sur les registres de la voix |

## Fichiers touchés hors de la liste des lots

- `web/DashboardPrivacyTest.kt` (deux lignes) : `OverviewPage.COST_BY_ROLE` n'existe plus ; les requêtes de la vue
  d'ensemble restent enregistrées par le parcours du test, qui rend `/`.
- `docs/design/handoff/M6-FIX-7-8.md` : cette passation.

## Ce que les tâches suivantes doivent savoir

- **Un coût** se calcule par `Prices.costOf(List<ModelTokens>)` et s'écrit par `Cost.text`, nulle part ailleurs. Les
  appels par rôle d'une période : `TraceQueries.usage(since)`, et leurs modèles dans `Usage.models`. `ModelTokens`,
  `Cost` sont dans le paquet `trace` (plus dans `web`).
- **Une liste de tours** : `TraceQueries.TURN_SUMMARY` lu par `turnSummary()`, rendue par `turnTable` sur le tableau de
  bord.
- **M6-16** (hors lot, inchangé) : `dreamer.md:57` doit encore dire la part de `plain` et que le registre dominant se
  compte parmi les registres de la voix.
- **M6-12 / page Style** : `Diversity.plainShare` est disponible (voir plus haut).
- **Un test qui a besoin d'un lien de dossier** sous Windows : le modèle `linkFolder` de `TranscriptPageTest` (lien
  symbolique, sinon jonction).

## Vérifications

- **Tests ajoutés ou changés** (+5 au total) :
  - `TranscriptPageTest` (5 → 8) : les heures au fuseau du tableau de bord (tampons `Z` et `+02:00`, rendus à Paris
    puis en UTC, en-tête compris) ; un lien hors du dossier refusé et un lien intérieur suivi ; une ligne plus longue
    qu'une page ; les appels de `read` prennent le fuseau.
  - `TracePagesTest` (4 → 5) : la vue d'ensemble, avec un appel d'un modèle sans prix : la barre du Directeur
    `$0.065 + 1 call at $ ? · 5 calls`, **la même sur `/costs`**, le total `$0.081 + 1 call at $ ?` sur les deux pages,
    la table des tours de `/turns` (colonne « Took », lien du chapitre) ; `@TempDir`.
  - `TraceQueriesTest` (6 → 7) : `usage` avec ses modèles ; le test des dollars par rôle avec le modèle de repli
    (déplacé de `DashboardQueriesTest`, sur son propre jeu de données : la classe partage le sien entre ses tests), plus
    la somme du rôle et la latence moyenne sur deux modèles.
  - `PricesTest` (7 → 8) : `Cost.text` et `costOf` (déplacé de `DashboardQueriesTest`), plus `1 call` au singulier.
  - `DashboardQueriesTest` (9 → 7) : les deux tests déplacés ; les tours restent identiques à `lastTurns`.
  - `TraceTextTest` : `usage` avec les prix ; le cas sans prix d'Opus (`$ ?` pour le Directeur et l'Analyste, total
    `$0.000024 + 5 calls at $ ?`).
  - `StyleCommandsTest` (5 → 6) : sur une **vraie** veille (`StyleSamples.watch`), aucune étiquette → `no line labeled` ;
    trois `plain` et un `other` → `no register of the voice · plain lines 75%` ; un seul `other` → `plain lines 0%` ;
    un mélange → `top register courtroom 67% · plain lines 25% · register entropy 0.58`. La réponse la plus longue
    (avec la part de `plain`) tient toujours sur une page.
  - `DashboardPrivacyTest` : voir « Fichiers touchés hors de la liste ».
- **Rouge avant, vert après** (constat 9 et branche `toRealPath`) : avec l'ancien `time` (sans `atZoneSameInstant`) et
  le contrôle de `toRealPath` neutralisé le temps d'un essai, les deux nouveaux tests échouent (`expected: <Outside> but
  was: <Found(…\elsewhere\director-link.jsonl)>`, et l'heure « 19:30:12 » au lieu de « 21:30:12 ») ; code rétabli, verts.
  Le test du lien a tourné par une jonction (lien symbolique refusé sur cette machine), 0 ignoré.
- **Build complet** (`.\gradlew.bat build --continue`, après `git merge main` jusqu'à `68807c1`, M6-FIX-2 compris, sans
  conflit) : **vert**, **2 230 tests unitaires** (0 échec, 0 ignoré) et **« All 398 required tests passed »**. Aucun
  test de temps instable n'a échoué.
- Aucun appel réel à Claude. Journaux de build sous des noms `fix78-*` (`C:\Users\Public\narrator2-tmp\`).
