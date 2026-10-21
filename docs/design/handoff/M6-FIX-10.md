# Passation M6-FIX-10 : le tableau de bord (finitions de la relecture M6, partie 3)

Lot de corrections de `reviews/M6_REVIEW_3.md` : constat 3 (décision D10 : oui), constat 6 (les doublons des
décisions) et le dossier temporaire du constat 8. Passations lues : M6-FIX-2, M6-12, M6-11. Le tableau de bord reste
optionnel et désactivé par défaut ; rien n'est ajouté au-delà du lot.

**En cas de contradiction avec `M6.md` ou les passations M6-11, M6-12 et M6-FIX-2, le code et cette passation font foi.**

Branche `worktree-agent-a16c54437c43e4426`.

## Ce qui change

| Point | Fichiers | Ce qui change |
|---|---|---|
| Constat 3, D10 : deux lectures successives d'un tour refusées | `journal/DuckJournal.kt`, `journal/ReadWatch.kt`, `web/Routes.kt` | `DuckJournal.read` garde le résultat (ou l'exception) de la requête, fait `endQuery`, rend la connexion, prévient le `ReadWatch` (`end`), **puis seulement** réveille l'appelant. Un refus du `ReadWatch` lève une `IllegalStateException` avec sa raison (« a read of the dashboard is already running in this turn »), plus un faux délai dépassé |
| Constat 6 : `/mechanics/decisions` refaisait `/decisions?kind=mechanic` | `web/pages/MechanicsPage.kt` | l'onglet est retiré (`/mechanics/decisions` répond 404) ; sous « Their history in the canon », un lien « Their decisions: decisions of the mechanics engine » vers `/decisions?kind=mechanic`, comme `ForgePage` |
| Constat 6 : deux `Where` | `web/pages/PageParts.kt`, `web/DashboardQueries.kt` | un seul, celui de `PageParts` (valeurs désormais non nulles, `Any`), que `DashboardQueries` réutilise : `"… WHERE $where ORDER BY …"`, puis `*where.values` (`WHERE true` sans filtre) |
| Constat 6 : deux `DecisionRow`, deux lectures et deux tables des décisions | `web/pages/PageParts.kt`, `web/pages/DecisionsPage.kt`, `web/pages/ForgePage.kt`, `web/DashboardQueries.kt` | un seul `DecisionRow` (celui de `DashboardQueries`, public) ; `Connection.decisions` de `PageParts` retiré ; une seule table, `decisionTable` (interne, dans `DecisionsPage.kt`), que `/decisions` et les deux pages de la Forge utilisent ; la Forge lit par `DashboardQueries.decisions` |
| Constat 8 : `DashboardPrivacyTest` laissait un dossier `m6-12-privacy*` par test | test `web/DashboardPrivacyTest.kt` | `@TempDir` (champ), les champs qui en dépendent en `by lazy`, les fichiers de clé écrits dans un `@BeforeEach` |

## Le tour de lecture (D10)

- **L'ordre dans `DuckJournal.read`** : `runCatching { query(connection) }`, puis `endQuery` et `giveBack` (dans un
  `try`), puis, dans le `finally`, `watch?.end()` et la complétion du futur. L'appelant reprend avec la connexion déjà
  dans le pool et la fin déjà entendue : une seconde lecture lancée aussitôt trouve le tour libre (`running` faux) et une
  connexion libre. Tous les appelants du Journal y gagnent une connexion rendue plus tôt, pas seulement le tableau de
  bord.
- **Comportement de M6-FIX-2 gardé** : le permis du tableau de bord n'est rendu qu'à la vraie fin de la requête (une
  requête expirée comprise) ; une requête dont l'appelant est parti pendant l'attente de sa connexion ne démarre pas
  (`result.isDone` après le `poll`, la connexion rendue, sans rien compléter : le futur l'est déjà).
- **Écart, `ReadWatch.start()` rend `String?`** au lieu de `Boolean` : `null` laisse partir la requête ; sinon c'est la
  raison du refus, que `DuckJournal` lève en `IllegalStateException`. Le Journal ne connaît pas le vocabulaire du
  tableau de bord : c'est le `Turn` de `Routes.kt` qui écrit la raison. **`Routes.kt` change donc un peu plus que son
  KDoc** : `Turn.start` rend « a read of the dashboard is already running in this turn » (deux lectures à la fois dans
  un tour, ou une lecture lancée pendant qu'une lecture expirée du même tour tourne encore) ou « the dashboard's turn is
  over: nobody waits for this read » (personne ne le voit : l'appelant est parti).
- **Ce que voit la page** : une `IllegalStateException` n'est ni une `JournalUnavailableException` ni une
  `JournalTimeoutException` ; elle passe par le `catch` général de `Routes.respond` : réponse 500 « The dashboard
  failed: IllegalStateException: a read of the dashboard is already running in this turn. See the server log. » et une
  ligne `ERROR` avec la pile. C'est voulu : deux lectures parallèles dans un tour sont une faute de la page, pas un
  Journal occupé. Aucune page ne le fait.
- KDoc mis à jour : `PageContext.inTurn` (lectures successives permises, parallèles refusées, avec le message),
  `Turn`, `ReadWatch`, `DuckJournal.read`.

## Les décisions du moteur (constat 6)

- **Une table** : `Html.decisionTable(decisions, context)`, celle de `/decisions` : Time, Kind, Decision, Subject,
  Reason, Where (le tour `turn #42`, sinon la conversation, et l'événement), Detail (JSON plié). Sur la page de la
  Forge, la colonne « #Turn » devient donc « Where », et Kind (toujours `forge`) et Detail apparaissent.
- **Écart, la couleur rouge** : les deux tables ne coloraient pas les mêmes décisions (`/decisions` :
  `TraceText.REFUSALS` ; M6-12 : `failed`, `fault`, `refused`, `broken`, `stuck`, `leak`, `unreadable`, `denied`). La
  table unique colore **l'union** (`FAILED_DECISIONS` dans `DecisionsPage.kt`) : `/decisions` montre désormais en
  rouge aussi les échecs de la Forge, des lois et des modules, et rien de ce qui était rouge ne l'est plus.
- **La Forge** lit ses 20 dernières décisions par `DashboardQueries.decisions(filter, Paging(1, size = 20))` :
  `DecisionFilter(kind = "forge")` pour `/forge`, et `DecisionFilter(kind = "forge", module = <nom>)` pour
  `/forge/<module>`. **Champ ajouté `DecisionFilter.module`** : la condition que `ForgePage` écrivait elle-même
  (`(subject = ? OR starts_with(subject, ?))`, sujet `nom` ou `nom vN`), désormais `DECISION_MODULE` dans
  `DashboardQueries` et dans `STATEMENTS`. `/decisions` ne l'expose pas en filtre.
- **Les mécaniques** : `MechanicsPage.render` ne sert plus que `/mechanics` ; plus de `tabs` sur cette page (`tabs`
  reste dans `PageParts`, pour la mémoire et le style).
- **Pas fait** (option du rapport, non demandée) : les lecteurs de `ResultSet` (`instant`, `long`, `int`) recopiés dans
  `DashboardQueries`, `TraceQueries` et `memory/Jdbc.kt`.

## Ce que les tâches suivantes doivent savoir

- **Une page** peut faire plusieurs lectures **l'une après l'autre** dans un même tour (`context.inTurn { a = journal.read
  …; b = journal.read … }`), jamais deux à la fois. Attention : `context.read` **dans** `context.inTurn` reprend le
  sémaphore du tableau de bord, qui n'est pas réentrant : il attend 5 s puis échoue (`JournalTimeoutException`). Dans
  `inTurn`, appeler `journal.read` (ou un service) directement.
- **M6-17** : `/mechanics/decisions` n'existe plus (404) ; les décisions des mécaniques sont sur
  `/decisions?kind=mechanic`, à un lien de `/mechanics`.
- **Tests** : `DashboardPrivacyTest` ne laisse plus de dossier. `TestServices` crée toujours son `dataDir`
  (`narrator2-test*`) sans le supprimer quand on ne lui en donne pas, et `DashboardPrivacyTest` y écrit son faux secret
  par `DashboardDataset.services` (qui n'accepte pas de `dataDir`) : hors de mes fichiers, à reprendre si l'on veut un
  dossier temporaire propre partout.

## Vérifications

- **Tests nouveaux ou étendus** :
  - `DashboardServerTest` : **100 tours de deux lectures successives** dans `inTurn`, toutes passées, jamais deux
    lectures à la fois, le tour rendu à la fin ; la lecture parallèle d'un tour reste refusée, **avec son message**
    (« a read of the dashboard is already running in this turn », `IllegalStateException`, plus un délai).
  - `DuckJournalTest` : un `ReadWatch` dont `end` dure 300 ms a fini quand l'appelant reprend (connexion rendue, fin
    entendue) ; un `ReadWatch` qui refuse : `IllegalStateException` avec sa raison, la requête ne tourne pas, la
    connexion revient au pool.
  - `DashboardPagesTest` : `/mechanics/decisions` en 404, le lien de `/mechanics`, le contenu de
    `/decisions?kind=mechanic` (filtre `decision` compris) ; une ligne de la Forge identique sur `/forge` et sur
    `/decisions?kind=forge` ; une décision `broken` en rouge sur les deux ; la page d'un module ne montre que ses
    décisions (`trap_module` : « None yet. »).
  - `DashboardPrivacyTest` : `@TempDir` ; ses deux listes d'adresses nomment `/decisions?kind=mechanic`.
- **Rouge avant, vert après** : avec l'ancien ordre remis le temps d'un essai (le futur complété avant `endQuery`,
  `giveBack` et `end`), les deux nouveaux tests du tour échouent : « a read of the dashboard is already running in this
  turn » au premier tour dans `DashboardServerTest`, « the connection is back and the end heard before the caller
  resumes » dans `DuckJournalTest`. Avec le correctif : verts.
- Lot ciblé (`DashboardServerTest`, `DuckJournalTest`, `DashboardPagesTest`, `DashboardPrivacyTest`, `TracePagesTest`,
  `DashboardQueriesTest`, `NoScriptTest`, `TranscriptPageTest`) : 80 tests, tous verts.
- **Build complet** (`build --continue`, après `git merge main` jusqu'à `0631ef5`, FLAKY-6, sans conflit ; journal
  `C:\Users\Public\narrator2-tmp\m6-fix-10-build1.log`) : **vert**, **2 247 tests unitaires** (0 échec, 0 ignoré) et
  **« All 398 required tests passed »**. Aucun test de temps instable n'a échoué.
- **Non fait** : la vérification dans un vrai navigateur. Aucun appel réel à Claude.
