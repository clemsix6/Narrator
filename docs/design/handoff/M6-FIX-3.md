# Passation M6-FIX-3 : le cache des idées et les prix

Lot M6-FIX-3 de la relecture de M6, partie 1 (`design/reviews/M6_REVIEW_1.md`) : constats **3** et **8**, décision
**D2 (oui)** du superviseur. Le constat 3 prend **l'option recommandée** (M6-13, qui modifiait `MemoryModule.kt`, est
fusionné).

**En cas de contradiction avec `M6.md`, le code et cette passation font foi.**

Branche `worktree-agent-a0d99f9136ba6bc44`.

## Ce qui est corrigé

### Constat 3 : le cache des idées suit l'hygiène

- **`memory/MemoryModule.kt` (`assemble`)** crée le cache `OpenIdeas` sur le `DuckMemoryStore`, puis le magasin
  enveloppé `ideas.watching(duckStore)`. **Ce magasin enveloppé est le seul que reçoivent les pièces de la mémoire** :
  `Hygiene`, `Reminiscence`, `MemoryJobs`, `MemoryBriefing`, `JoinBrief` et `MemoryService` (donc aussi, par
  `services.memory.store`, les outils, le Rêveur, le Scénariste et les commandes, comme avant). Le magasin nu ne sert plus
  qu'à construire le cache.
- **`memory/MemoryService.kt`** : `MemoryService` reçoit le magasin enveloppé et le cache au lieu de les construire
  (`internal constructor`, puisque `OpenIdeas` est interne ; seul `assemble` le construit). La coroutine
  `narrator2-ideas` est inchangée.
- Effets :
  - une idée que l'hygiène clôt (`tidy`, toutes les 10 minutes de l'horloge du serveur, et avant chaque rêve) quitte
    `openIdeas()` **avant le retour de `expireIdeas`**, dans la coroutine de l'hygiène : la surcharge `expireIdeas` de
    `watching` n'est plus du code mort ;
  - de même pour **tout** `revertRun` et tout `dealt`, quel que soit l'appelant ;
  - la minuterie de 10 minutes n'est plus qu'un **filet** : elle rattrape une relecture qui a échoué (Journal
    indisponible au moment de l'écriture). Son KDoc et celui de `MemoryService` le disent.

### Constat 8 : `Prices.dollars`

`trace/Prices.kt` : le montant est **d'abord arrondi** à deux chiffres significatifs, puis le format est choisi : deux
décimales si l'arrondi atteint un dollar, sinon les deux chiffres significatifs. `0.996` → `$1.00`, `0.9949` →
`$0.99`, `0.042` → `$0.042`, `1.0` → `$1.00`, `9.996` → `$10.00`, `0.0996` → `$0.10`. Le reste est inchangé (`$0`,
`$ ?`, `$12.40`, `$0.000040`).

### D2 : le prix du modèle de repli

`config/NarratorConfig.kt`, `DEFAULT_PRICES` :
`"claude-opus-4-8" to ModelPrice(input = 5.0, output = 25.0, cacheRead = 0.50, cacheWrite = 10.0)`, dans la forme des
deux autres (dollars par million de jetons, écritures du cache au tarif d'une heure). Le fichier de configuration écrit
au premier démarrage porte donc trois prix ; un fichier existant reçoit ce prix par la fusion modèle par modèle.

**Source** : la référence de l'API Claude fournie localement (compétence `claude-api`, `shared/`), lue le 21/10/2026.

- 5 $ / 25 $ : `model-migration.md`, section Claude Opus 5 : « It is a drop-in upgrade at Opus 4.8's pricing - $5 per
  million input tokens, $25 per million output ».
- Lectures du cache à **0,1×** l'entrée, soit 0,50 $ : `model-migration.md`, section Claude Fable 5.1 : « Claude Opus 5.5
  reads at 0.05x, and every other model at 0.1x » (Opus 4.8 n'est pas dans les exceptions) ; même règle dans
  `prompt-caching.md` (« Cache reads cost ~0.1× base input price - 0.025× on Claude Fable 5.1 … and 0.05× on Claude Opus
  5.5 »).
- Écritures du cache à **1,25×** (5 min) ou **2×** (1 h) : `prompt-caching.md` (« Cache writes cost 1.25× for 5-minute
  TTL, 2× for 1-hour TTL »). La table garde le tarif d'une heure, comme pour les autres modèles : 10 $.
- `models.md` ne donne pas de prix pour Opus 4.8 (« 1M context window at standard API pricing ») ; il confirme
  l'identifiant `claude-opus-4-8`, celui que retient `llm_calls.model` (CLAUDE-FALLBACK, vérifié par la relecture dans
  les traces de M5).

Le commentaire de `DEFAULT_PRICES` cite ces deux phrases et les fichiers.

## Écarts et décisions

1. **Fichiers hors de ma fiche**, changés parce que D2 les rendait faux (aucun code de production) :
   - `config/ConfigStoreTest.kt` : une ligne (les clés de `prices` écrites au premier démarrage : trois modèles) ;
   - les trois tests de M6-11 qui supposaient le modèle de repli **sans prix** : `web/DashboardQueriesTest` (coût du
     Directeur : 5 appels au prix, `$0.068` ; nom du test), `web/TracePagesTest` (le repli à `$0.0063` par requête, le
     tour 47 à `$0.011` ; nom du test et KDoc de la classe), `web/TranscriptPageTest` (le repli à `$0.0030`, l'en-tête à
     `2 · $9.00`). Attentes recalculées aux prix du repli (5 / 25 $), messages ajustés.
   - Le chemin `$ ?` garde sa couverture : `PricesTest` (un modèle sans prix, `claude-opus-4-7`),
     `DashboardQueriesTest` (« a cost is in dollars, $ ? when no call has a price… »), `TraceTextTest` (`/narrator
     usage`).
2. **`M6.md` §12.5** montre encore deux prix : non modifié (document de design ; cette passation fait foi).
3. **Un seul magasin pour toutes les pièces**, pas seulement l'hygiène, les travaux, le briefing et le service comme le
   proposait la relecture : `Reminiscence` et `JoinBrief` le reçoivent aussi. Un magasin par pièce aurait laissé une
   écriture d'idée possible hors du cache ; ici, aucune.

## Ce que les tâches suivantes doivent savoir

- **M6-08 (briefing, battement)** : `memory.openIdeas()` suit **toutes** les écritures de la mémoire, l'hygiène comprise.
  Une idée close (abandonnée, remplacée, expirée, annulée par un `revert`) n'y est plus dès le retour de l'écriture.
  **Seule réserve** : une idée dont les heures de jeu sont passées reste ouverte **jusqu'au passage suivant de
  l'hygiène** (au plus 10 minutes de l'horloge du serveur, `MemoryJobs.TIDY_MS`, sur un tour de minuterie de 30 s) :
  c'est l'état du magasin, que `/narrator memory ideas` montre aussi (`due, the hygiene closes it`). Filtrer
  `expiresPlaySeconds` contre `memory.clock.worldPlaySeconds(now)` reste possible pour un lecteur qui veut être exact à
  la seconde (comme `inspire`, `storyteller/Deck.kt`), mais n'est plus nécessaire pour éviter une idée close.
- **Toute nouvelle pièce de la mémoire** construite dans `assemble` prend `store` (le magasin enveloppé), jamais
  `duckStore` : sinon ses écritures d'idées échappent au cache.
- **Tests** : `MemoryService` ne se construit que par `createMemory` (constructeur interne) ; un test du cache seul
  garde `OpenIdeas(store)` et `ideas.watching(store)` (`IdeasStoreTest`, inchangé).
- **M6-18** (tests réels) : `Prices.of(NarratorConfig())` connaît désormais le modèle de repli.

## Vérifications

- **Tests ajoutés ou changés** :
  - `MemoryServiceTest` (+1) « an idea the hygiene expires leaves the open ideas at once » : sur la vraie mémoire de
    `createMemory`, une idée d'une heure de jeu écrite par `memory.store`, une heure de jeu du monde passée (Bartok en
    ligne, par pas de `DuckPlayClock.MAX_GAP_MS`), puis un rêve demandé : sa première requête au modèle est retenue (`Hold`),
    donc l'hygiène d'avant le rêve est passée ; l'idée est `expired` dans le magasin et **`openIdeas()` est vide**, sans
    la minuterie de 10 minutes. **Preuve de non-régression** : avec `Hygiene(duckStore, …)` remis à titre d'essai dans
    `assemble`, ce test échoue (`the cache followed the hygiene ==> expected: <[]> but was: <[Idea(ref=P-1, …`), puis
    passe une fois le correctif rétabli.
  - `PricesTest` (+2) : le prix par défaut du repli (5 / 25 / 0,50 / 10, un tour à `$0.044`) ; l'arrondi
    (`[0.996, 0.9949, 0.042, 1.0]` → `[$1.00, $0.99, $0.042, $1.00]`, `0.9950001` → `$1.00`, `9.996` → `$10.00`,
    `0.0996` → `$0.10`) ; le modèle sans prix devient `claude-opus-4-7`.
  - `ConfigValidationTest` : les défauts (trois prix) et la fusion modèle par modèle (le repli gardé à côté d'un modèle
    ajouté) ; `ConfigStoreTest`, `DashboardQueriesTest`, `TracePagesTest`, `TranscriptPageTest` : voir les écarts.
  - `IdeasStoreTest` : inchangé (aucun conflit avec M6-07).
- **Build complet** (`.\gradlew.bat build --continue`, après `git merge main` jusqu'à `879c957`, M6-07 et M6-11
  compris) : **vert**, **2 180 tests unitaires** (0 échec, 0 ignoré) et **« All 398 required tests passed »**. Aucun test
  de temps n'est tombé.
- Aucun appel réel à Claude. Journaux de build sous des noms `m6fix3-*` (dossier de travail de la session).
