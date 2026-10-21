# Relecture de M6, partie 1 : constats et lots de corrections

Relecture du 21/10/2026, sur `main` à **`b754f7e`** (M6-01 à M6-06, M6-09, M6-10 et M6-14 fusionnés). Périmètre :
`git diff f9c6d3d..b754f7e` (`f9c6d3d` = M5 terminé), soit le code et les tests de **M6-01** (contrats, schéma V4),
**M6-02** (relecture des répliques), **M6-03** (veille du style, tics normalisés, refus d'amorce), **M6-04**
(`StyleReport`, `/narrator style`), **M6-05** (`inspire`), **M6-06** (idées du Scénariste), **M6-09** (juge et rejeu,
tests `live`), **M6-10** (tableau de bord : serveur, porte, vue d'ensemble, `Prices.kt`, `readers = 3`) et **M6-14**
(compactage de `director.md`). La partie 2 (M6-07, M6-08, M6-11, M6-12, M6-13, M6-16, M6-17) n'est pas relue ici.

Lus : `M6.md` en entier, `M6_DESIGN_REVIEW.md`, les passations M6-01 à M6-06, M6-09, M6-10 et M6-14 (elles font foi
contre le design), `M5_REVIEW.md` (le modèle), `ARCHITECTURE.md` §17, et tout le code de `web/`, `style/`,
`storyteller/`, `tool/storyteller/`, `memory/stats/Tics.kt`, `trace/Prices.kt`, ainsi que les changements de M6 dans
`memory/`, `director/`, `journal/`, `config/`, `tool/` et `command/`.

Chaque constat donne sa gravité, l'endroit (`fichier:ligne` sur `b754f7e`, chemins sous
`TheMod/src/main/kotlin/io/github/clemsix6/narrator2/` sauf mention), un scénario, la preuve et un **correctif
proposé**. **Vérifié** veut dire lu des deux côtés, bytecode compris quand il le faut, ou prouvé par une **sonde
jetable** : un test temporaire lancé dans mon worktree (`gradlew.bat test --tests …ReviewProbeTest`), puis supprimé et
jamais commité. **Raisonné** veut dire déduit de la lecture sans exécution. Le superviseur décide. Les lots de la fin ne
partagent aucun fichier.

**`main` a bougé pendant la relecture** : il est à `09d020e` (FLAKY-5, qui ne touche que des tests, et M6-13, de la
partie 2). Aucun des fichiers visés par les constats ci-dessous n'a changé entre `b754f7e` et `09d020e` (vérifié avec
`git diff --stat`), sauf `memory/MemoryModule.kt` (M6-13), dont dépend l'option recommandée du lot M6-FIX-3.

**État de départ.** Le build de `main` est vert d'après les passations (M6-14 après la fusion de M6-02 et M6-10 :
2 140 tests unitaires et « All 397 required tests passed » sur une machine sans charge). Les seuls rouges vus pendant
la vague A sont des tests de temps déjà connus (`PairStatsTest`, `SearchLatencyTest`, `DelegationScenarioTest`,
`ModuleBookTest`, l'orbite et la parade d'`ArsenalCreaturesGameTests`), que FLAKY-5 a traités. Je n'ai pas relancé le
build complet. Je n'ai lancé que la sonde jetable, sur ma branche, puis je l'ai supprimée.

**Bilan : 11 constats. 0 bloquant, 2 importants, 5 mineurs, 4 finitions.** S'y ajoutent quatre décisions à prendre. La
**sécurité du tableau de bord tient** : liaison à `127.0.0.1` seulement, contrôle de l'hôte, jeton de 256 bits comparé
en temps constant, cookie `HttpOnly; SameSite=Strict; Path=/`, CSP sans script, échappement par construction, `GET` et
`HEAD` seulement, aucune requête vers `private_messages`. Le **thread serveur n'attend jamais** quoi que ce soit de M6.
Les conversations restent **en ajout seul** et le modèle ne voit que des **outils réellement branchés**. Les deux
constats importants faussent un mécanisme en régime réel sans mettre en défaut un interdit du §17 :

- la relecture Haiku étiquette aussi les titres, les lignes de la barre d'action et les livres, et nourrit ainsi de
  fausses notes de ton ;
- le sémaphore du tableau de bord se libère avant la fin de sa lecture : le tableau de bord peut alors tenir deux
  connexions de lecture, ce que la troisième connexion devait empêcher.

## Constats

1. **IMPORTANT (relecture, notes de ton) : les titres, les lignes de la barre d'action et les livres sont relus,
   alors que la relecture ne devait porter que sur les répliques de `say`.**
   - Où :
     - `style/LineReviewer.kt:206-208` : le filtre `reviewed` ne garde que `text.length >= MIN_CHARS` (25) ;
     - `tool/speech/SpeechTools.kt:198` (`"[title] $title" + " / sous-titre"`), `:250` (`"[actionbar] $text"`) et `:400`
       (`"[book \"…\"] pages…"`) : ce sont les textes publiés tels quels dans le `NarratorSpoke` du canal `narrator`,
       avec le tour (`deliver` → `ctx.spoke(record, …)`, `:463` ; livre `:403`, publication `:470`) ;
     - le test `style/LineReviewerTest.kt:193` publie un titre **sans son préfixe** (`"BARTOK vs MIMOSA"`, 16
       caractères), ce qui n'arrive jamais en jeu.
   - Scénario : le Directeur lance un défi. Il écrit `show_title` (« DÉFI : LA COURSE AU PIC / Premier au sommet »),
     puis `actionbar` (« Il reste trente secondes ! »), puis `say`. Les trois sont relus. Haiku juge les deux premiers
     plats et sans cadre (`plain`, `flat` ≥ 0,6, comme M6-02 l'avait prévu). Avec un troisième plat dans les huit
     dernières lignes étiquetées, `ToneWatch` envoie au Directeur `Engine: tone check: 3 of your last 8 lines are
     reports without a punchline`, à cause de ses titres. De même, des titres répétés d'un défi à l'autre (« DÉFI : … »)
     font monter `repeats`. Chaque livre (jusqu'à 1 000 caractères par requête) coûte une relecture. Les registres
     `plain` des titres faussent la ligne `Style:`, `leastUsedRegisters` et donc `inspire`, la clé `say:<registre>`
     d'`Interventions`, et `/narrator style` (« Review: X of the latest 50 lines labeled »). M6-02 le savait et voulait
     l'éviter (passation, écart 1 : « Les répliques de moins de 25 caractères ne sont pas relues… ce sont les titres et
     les lignes de la barre d'action ») ; M6-03 avait relevé le piège du préfixe pour les amorces (`[title] GASPARD vs
     FIRMIN` fait 25 caractères) et l'a réglé dans `Tics.opens`, mais pas dans le relecteur.
   - Effets secondaires (finitions, même cause) :
     - `StyleWatchImpl.recentLines` (`style/StyleWatchImpl.kt:177-178`) donne à Haiku des lignes `[title] …` comme
       contexte de redite ;
     - `StyleReport.coverage` (`style/StyleReport.kt:166-172`) compte les titres, la barre d'action et les livres parmi
       les « répliques non relues ».
   - Preuve : **vérifié** par lecture des deux côtés (publication avec préfixe, filtre sur la seule longueur, test sur
     un texte sans préfixe). Le déclenchement de la note `FLAT` est **raisonné** : il dépend de la note que donne Haiku,
     mais M6-02 le prévoyait.
   - Correctif proposé :
     - `LineReviewer.reviewed` : remplacer `event.text.length >= MIN_CHARS` par **`Tics.opens(event.text)`** (sans
       préfixe de sorte, 25 points de code au moins : une seule règle pour les amorces et pour la relecture) ;
     - `recentLines` : ne rendre que les lignes sans préfixe ;
     - `coverage` : ne compter que les lignes relisibles ;
     - `LineReviewerTest` : publier les vrais textes (`[title] GASPARD vs FIRMIN / la revanche`,
       `[actionbar] Il reste trente secondes !`, `[book "Prophétie"] …`) et vérifier qu'aucun n'est relu.
   - Fichiers : `style/LineReviewer.kt`, `style/StyleWatchImpl.kt`, `style/StyleReport.kt` ; tests `LineReviewerTest`,
     `StyleWatchTest`, `StyleReportTest`.

2. **IMPORTANT (tableau de bord, Journal) : le sémaphore « une lecture à la fois » est rendu quand la lecture expire,
   alors que la requête continue sur sa connexion ; le tableau de bord peut donc tenir deux connexions de lecture, voire
   trois.**
   - Où : `web/Routes.kt:116-123` (`PageContext.read` : `tryAcquire`, puis `runBlocking { journal.read(5 000, query) }`,
     puis `release()` dans un `finally`) ; `journal/DuckJournal.kt:137-167` (à l'expiration, `withTimeout` lève
     `JournalTimeoutException` et annule le `CompletableFuture`, mais la tâche continue sur son fil `narrator2-sql-*`
     avec sa connexion ; une tâche qui a passé le contrôle `isDone` peut même attendre une connexion puis lancer la
     requête **après** l'abandon de l'appelant).
   - Scénario : le Journal est lent (gros fichier, point de contrôle, écritures en rafale). Une page attend jusqu'à
     5 s sa connexion, puis sa requête démarre avec sa propre limite de 5 s. À 5 s, `PageContext.read` échoue (503) et
     rend le permis. La page suivante, rechargée à la main ou par `?refresh=5`, prend alors une **deuxième** connexion
     pendant que la première tourne encore, et ainsi de suite. Il ne reste alors qu'une connexion, voire aucune, au
     briefing, à `recall`, à la veille du style et à `inspire`, au moment où le Journal est déjà sous pression. C'est
     précisément ce que la troisième connexion et le sémaphore devaient empêcher (constat 7 de la relecture du design,
     `M6.md` §8.3 : « le Directeur et la mémoire gardent les deux autres »). La durée est bornée par les limites de
     requête (5 s de `rows`, celles de `TraceQueries`), mais le mal se répète tant que le Journal reste lent.
   - Preuve : **vérifié par la sonde**. Sur le vrai `DuckJournal` (`JournalFixture`) et le vrai `PageContext` :
     - une lecture A qui tient sa connexion 7 s expire à 5 s (`the Journal read did not finish within 5000 ms`) ;
     - la lecture B, lancée juste après, s'exécute **pendant** A : `PROBE B runs while 2 dashboard reads hold a
       connection`, `most dashboard reads at once: 2`.
     Le test existant (`DashboardServerTest`, « one read of the dashboard at a time ») ne couvre que des lectures qui
     finissent dans les temps.
   - Correctif proposé, dans `PageContext.read` (environ 15 lignes) : **rendre le permis quand la requête finit
     vraiment**. Un état atomique `WAITING → RUNNING → DONE` ou `WAITING → ABANDONED` règle tout :
     - la lambda passée à `journal.read` fait d'abord `compareAndSet(WAITING, RUNNING)`. Si cela échoue, elle ne lance
       rien (personne n'attend plus sa réponse). Sinon, elle exécute `query` et rend le permis dans son `finally` ;
     - le `finally` de l'appelant ne rend le permis que s'il gagne `compareAndSet(WAITING, ABANDONED)`, c'est-à-dire si
       la requête n'a jamais démarré (Journal indisponible, attente de connexion expirée).
     En option, et utile à tous les appelants : dans `DuckJournal.read`, ne pas exécuter `query` si `result.isDone`
     **après** avoir obtenu la connexion. Test de non-régression : le scénario de la sonde, avec une lecture de 7 s
     puis une seconde lecture qui attend ou reçoit un 503 au lieu de tourner en même temps.
   - Fichiers : `web/Routes.kt` (et en option `journal/DuckJournal.kt`) ; test `DashboardServerTest`.

3. **MINEUR (idées, cache) : l'hygiène clôt les idées échues avec le magasin nu ; le cache garde une idée échue jusqu'à
   10 min, et la surcharge `expireIdeas` du magasin enveloppé n'est jamais appelée.**
   - Où :
     - `memory/MemoryModule.kt:85` sur `b754f7e` (`:131` sur `09d020e`) : `Hygiene(store, …)` reçoit le
       `DuckMemoryStore` nu ;
     - `memory/Hygiene.kt:146` : `store.expireIdeas(author)`, seul appelant d'`expireIdeas` (vérifié par recherche) ;
     - `memory/MemoryService.kt:120` : `watching(store).expireIdeas`, qu'aucun appel n'atteint.
   - Scénario : une idée arrive à échéance. L'hygiène la clôt (`expired`), mais `Memory.openIdeas()` la rend encore
     jusqu'au prochain passage de la minuterie `narrator2-ideas` (10 min, sans lien avec le rythme de l'hygiène).
     `inspire` s'en protège en filtrant `expiresPlaySeconds` (`storyteller/Deck.kt:388`), mais le `<programme>` du
     briefing et la ligne `Idea:` du battement (M6-08, partie 2) lisent ce même cache. M6-06 le savait (« elle rattrape
     ce que les pièces internes écrivent avec le magasin nu »), mais la surcharge `expireIdeas` laisse croire que le
     cache suit l'hygiène : c'est du code mort.
   - Preuve : **vérifié** par lecture (appelants d'`expireIdeas`, construction de `Hygiene` et de `MemoryJobs`).
   - Correctif proposé, au choix :
     - **(recommandé)** créer `OpenIdeas` dans `assemble` (`MemoryModule.kt`) et passer le magasin **enveloppé** à
       `Hygiene`, à `MemoryJobs`, au briefing et à `MemoryService`. Le cache suit alors toutes les écritures, et la
       minuterie ne sert plus que de filet ;
     - **(minimal, sans toucher `MemoryModule.kt`)** retirer la surcharge morte, écrire dans le KDoc de `openIdeas()`
       qu'une idée échue peut y rester jusqu'à 10 min, et demander à tout lecteur de filtrer `expiresPlaySeconds`
       (consigne pour M6-08).
   - Fichiers : `memory/MemoryService.kt` (et `memory/MemoryModule.kt` pour l'option recommandée) ; tests
     `IdeasStoreTest`, `MemoryServiceTest`.

4. **MINEUR (veille du style, normalisation) : un nom de joueur élidé (`d'Opaline`, `qu'Opaline`) n'est pas normalisé ;
   il casse les constructions et devient un mot béquille candidat.**
   - Où : `memory/stats/Tics.kt:268-294` (`tokens` cherche les noms sur le mot entier, élision comprise : `d'opaline`
     ne commence aucun nom) et `:300-303` (`words` retire ensuite l'article élidé : `d'opaline` → `opaline`).
   - Scénario :
     - « le cadeau d'Opaline » et « le cadeau de Bartok » ne font pas la même construction (`cadeau d'opaline` contre
       `cadeau de <nom>`), et « Qu'Opaline se méfie » n'ouvre pas sur `<nom>` ;
     - surtout, `crutchCandidates` peut proposer `opaline` comme mot béquille, alors que le design exclut les noms des
       joueurs (§3.3 : « ni nom de joueur ») ; Haiku peut le marquer, et un nom passe alors « au repos » dans la ligne
       `Style:` et dans `inspire`.
   - Preuve : **vérifié par la sonde** : `Tics.tokens("Le cadeau d'Opaline et de Bartok")` rend `[le, cadeau,
     d'opaline, et, de, <nom>]`, et `"Qu'Opaline se méfie, Bartok approche"` rend `[qu'opaline, se, méfie, <nom>,
     approche]`. Le passage par `words` est vérifié par lecture. `TicsTest` ne teste aucun nom élidé.
   - Correctif proposé : dans `tokens`, quand un mot porte une élision dont la partie avant l'apostrophe est un mot vide
     (`d'`, `l'`, `qu'`, `j'`, `n'`, `s'`, `jusqu'`…), chercher les noms à partir de la partie après l'apostrophe. Le
     jeton devient alors `<nom>` (l'article élidé étant vide, rien ne se perd pour les expressions). Ajouter le cas à
     `TicsTest`.
   - Fichiers : `memory/stats/Tics.kt` ; test `TicsTest`.

5. **MINEUR (relecture, mots béquilles) : les candidats sont reconnus par sous-chaîne, et `narrateur` reste un candidat
   malgré la recommandation de M6-02.**
   - Où :
     - `style/LineReviewLabels.kt:129-132` (`inLine` : `fold(candidat) in fold(réplique)`, donc une sous-chaîne) ;
     - `style/StyleWatchImpl.kt:159-171` (`candidates` ne retire ni `narrateur` ni ses formes).
   - Scénario :
     - le candidat `grand` est envoyé à Haiku et peut être accepté comme béquille pour une réplique qui ne contient que
       « grandiose » ;
     - M6-02 a mesuré que Haiku marque `narrateur` à tort 3 à 4 fois sur 60 répliques (le Narrateur parle de lui à la
       troisième personne), et M6-03 a constaté que `narrateur` devient le **premier** candidat sur les soirées de M4 et
       M5 enchaînées (15 répliques). Avec la règle « 3 des 30 dernières », `narrateur` peut passer au repos : la ligne
       `Style:` dirait alors « crutch words « narrateur » » et `inspire` l'écarterait. M6-02 recommandait de le traiter
       comme un nom dans `crutchCandidates`, mais M6-03, fusionnée avant, ne l'a pas fait.
   - Preuve : **vérifié par la sonde** (`inLine(["grand", "pomme"], "Un plan grandiose, des pommes.")` rend `[grand,
     pomme]`) ; **raisonné** pour `narrateur`, sur les mesures de T1 (passation M6-02) et des corpus (passation M6-03).
   - Correctif proposé :
     - `inLine` : comparer aux **mots** de la réplique (`Tics.words`, repliés en casse et en accents, pluriel en `s`
       compris), pas à une sous-chaîne ;
     - `candidates` : écarter `narrateur` et `narrateurs` (décision D4).
   - Fichiers : `style/LineReviewLabels.kt`, `style/StyleWatchImpl.kt` ; tests `LineReviewerTest`, `StyleWatchTest`.

6. **MINEUR (tableau de bord, sécurité ; décision D1) : le lien de connexion **est** le jeton de session. Il reste
   valable jusqu'au `reset` ou au redémarrage, et il survit dans `latest.log` (depuis la console), dans l'historique du
   navigateur et dans le journal DEBUG du serveur HTTP du JDK.**
   - Où :
     - `web/Dashboard.kt:110` (`loginUrl = …login?key=${token.value}`) et `web/Routes.kt:212-221` (le cookie reçoit le
       même `token.value`) ;
     - `command/DashboardCommands.kt:128` (console) ;
     - `sun.net.httpserver.ServerImpl.logReply`, au niveau `DEBUG`.
   - Scénario : un opérateur se connecte depuis la console. Minecraft écrit alors le lien dans `logs/latest.log`, ce que
     le design accepte, avec le conseil de faire `reset`. S'il oublie le `reset`, quiconque lit les journaux (une
     sauvegarde, un rapport de bug partagé, un autre compte de la machine) peut ouvrir le tableau de bord jusqu'au
     prochain redémarrage. Le même lien reste dans l'historique du navigateur. Enfin, le serveur HTTP du JDK journalise
     la ligne de requête (chemin et paramètres) au niveau `DEBUG` : sans effet avec le réglage par défaut de
     `java.util.logging` (INFO), mais présent dès qu'un environnement active ce niveau.
   - Preuve : **vérifié** pour le niveau (bytecode de `ServerImpl.logReply` dans `jdk.httpserver` du JDK 25 :
     `isLoggable(DEBUG)` puis `log(DEBUG, …)`) et pour l'égalité entre le lien et le cookie ; **raisonné** pour le
     contenu de la ligne journalisée (la ligne de requête du client).
   - Correctif proposé (si D1 = oui) : **un code de connexion à usage unique**, distinct du jeton de session.
     - `/narrator dashboard` tire un code de 128 bits, valable 10 min et consommé à la première connexion ;
     - `/login?key=<code>` pose le cookie avec le jeton de session et ne l'écrit jamais dans une URL ;
     - `reset` change le jeton (et invalide les codes) ;
     - un lien recopié dans un journal ou un historique est mort dès qu'il a servi, et le conseil de `reset` après un
       usage depuis la console devient inutile.
     Environ 30 lignes et trois tests (code consommé une fois, expiré, invalidé par `reset`).
   - Fichiers : `web/Dashboard.kt` (`DashboardToken`), `web/Routes.kt` (`login`), `command/DashboardCommands.kt` ; tests
     `DashboardServerTest`, `DashboardCommandsTest`.

7. **MINEUR (mesures de diversité ; décision D3) : le « registre dominant » compte `plain` et `other`, que l'entropie
   écarte.**
   - Où : `style/StyleReport.kt:125-129` (`topRegister` et `topRegisterShare` sur tous les registres relus) et `:132`
     (`registerEntropy` sur `ComicRegister.VOICE` seulement). Mêmes valeurs dans `StyleWatch.diversity()`,
     `/narrator style`, le bloc `<diversity>` du Rêveur (M6-07) et l'éval T6 (§3.7 : part du registre dominant ≤ 35 %).
   - Scénario : une soirée saine, riche en piques directes, dont 40 % sont `plain`, affiche « top register plain 40 % »,
     échoue au seuil de 35 % et pousse le Rêveur à commenter la rotation des cadres sur ce qui n'en est pas un. À
     l'inverse, la rotation réelle des cadres de la voix n'est pas mesurée. La ligne `Style:` fait déjà la distinction
     (« un registre **de la voix** tient 4 des 8 »), ce que M6-03 a choisi pour la même raison.
   - Preuve : **raisonné** par lecture. M6-03 a choisi ce comportement (« `plain` compris, comme le contrat du
     `Diversity` »), sans le confronter au seuil de l'éval.
   - Correctif proposé (si D3 = oui) : calculer le registre dominant parmi les registres de la voix, sur les répliques
     relues, et montrer à part la part de `plain`, qui est une information utile.
   - Fichiers : `style/StyleReport.kt`, `command/StyleCommands.kt` (si l'affichage change) ; tests `StyleReportTest`,
     `StyleCommandsTest`, `DiversityTest`. **À rejouer** : les tests de M6-07 qui lisent `<diversity>`.

8. **FINITION (coûts ; décision D2) : `Prices.dollars` affiche « $1.0 » entre 0,995 et 1, et le modèle de repli n'a pas
   de prix par défaut.**
   - Où : `trace/Prices.kt:36-41` ; `config/NarratorConfig.kt` (`DEFAULT_PRICES`).
   - Constats :
     - `Prices.dollars(0.996)` donne `$1.0` : l'arrondi à deux chiffres significatifs passe au dollar sans repasser par
       le format à deux décimales ;
     - `claude-opus-4-8`, le modèle de repli que l'amont de LLMGW substitue (CLAUDE-FALLBACK), apparaît dans les traces
       de la recette de M5 (`.research/m5-live`) et `llm_calls.model` le retient. Il s'affiche « $ ? » et sort du total
       de la vue d'ensemble, qui le compte à part : c'est le comportement voulu, mais il sous-estime systématiquement la
       soirée.
   - Preuve : **vérifié par la sonde** (`[0.996, 0.9949, 0.042, 1.0]` → `[$1.0, $0.99, $0.042, $1.00]`) ; **vérifié**
     dans les traces (les identifiants de modèle sont nus : `claude-opus-5-5`, `claude-haiku-5-5`, `claude-opus-4-8` ;
     aucun suffixe daté qui empêcherait la correspondance des prix).
   - Correctif proposé : arrondir d'abord, puis choisir le format (`≥ 1` après arrondi → deux décimales) ; ajouter le
     prix du modèle de repli aux défauts si D2 = oui.
   - Fichiers : `trace/Prices.kt`, `config/NarratorConfig.kt` (si D2) ; tests `PricesTest`, `ConfigValidationTest`.

9. **FINITION (tableau de bord, défense en profondeur) : quelques trous de garde dans `Html.element`, et une file de
   requêtes non bornée.**
   - Où : `web/Html.kt:96-98` et `:204-219` ; `web/Dashboard.kt:133`.
   - Constats, aucun exploitable aujourd'hui puisque seul le code écrit des noms et des valeurs d'attributs :
     - le contrôle des URL accepte `/\autre-site` (les navigateurs lisent `\` comme `/`, soit une URL relative au
       protocole) ;
     - `formaction` n'est pas dans `URL_ATTRIBUTES` ;
     - `<meta>` accepte un `content` libre, et une `refresh` avec une URL serait possible ;
     - `Executors.newFixedThreadPool(2)` a une file sans borne, qu'un processus local peut remplir. Les lectures de
       page sont bornées à 10 s : c'est une charge, pas un gel du serveur de jeu.
   - Correctif proposé (court) :
     - refuser `\` dans une URL d'attribut et ajouter `formaction` à `URL_ATTRIBUTES` ;
     - réserver `meta http-equiv` à `document` (une valeur entière) ;
     - en option, borner la file (une `ArrayBlockingQueue` de 32, et un refus par fermeture de l'échange).
   - Fichiers : `web/Html.kt`, `web/Dashboard.kt` ; test `HtmlTest`.

10. **FINITION (simplicité) : de petites duplications et des API sans appelant.**
    - Trois copies de « échapper, puis « » → ‹ › » : `style/StyleWatchImpl.kt:272`, `style/ToneWatch.kt:112`,
      `tool/speech/SpeechTools.kt` (`quoted`). Une fonction de `core/ModelText` suffirait.
    - `StyleReport.Flaw` (`style/StyleReport.kt:63-68`) a les cinq mêmes constantes, dans le même ordre, que
      `director/StyleNoteKind` (`ToneWatch` les relie à la main).
    - `propose_idea` relit deux fois les idées ouvertes autour de `addIdea` pour dire ce que l'écriture a clos
      (`tool/storyteller/StorytellerTools.kt:129-132`), alors que le magasin le calcule déjà (`makeRoom`). Corriger
      demanderait de changer `MemoryStore.addIdea`, et donc de toucher `DuckMemoryStore.kt`, que M6-07 modifie : **à
      faire après M6-07**, pas dans les lots ci-dessous.
    - `Svg.curve`, `Filter.Field` et `pager` n'ont pas encore d'appelant en production : ils sont posés pour M6-11 et
      M6-12. **M6-17** vérifiera qu'ils servent, ou les retirera (comme `notBuiltYet`).
    - Correctif : regrouper l'échappement des citations dans `ModelText` et réutiliser `StyleNoteKind` dans
      `StyleReport` (lot M6-FIX-1).

11. **FINITION (tests) : trois trous sur des chemins visés par les constats ci-dessus, et quelques mesures à l'horloge
    murale, larges mais structurellement fragiles.**
    - Trous :
      - aucun test ne publie un vrai titre, une vraie ligne de barre d'action ou un vrai livre à la relecture
        (constat 1) ;
      - aucun test ne fait expirer une lecture du tableau de bord (constat 2) ;
      - aucun nom élidé dans `TicsTest` (constat 4).
    - Horloge murale, à laisser à un lot `FLAKY` s'ils tombent sous charge :
      - `DashboardServerTest` : « stop < 2 s » (`:283-285`) ; « une lecture à la fois », trois lectures de 150 ms sous un
        sémaphore de 5 s (`:292-315`) ;
      - `IdeasStoreTest:279-280` : attente de la minuterie jusqu'à 5 s ;
      - `LineReviewerTest:260-266` : une relecture expirée en moins de 5 s.
      Les marges sont larges, et FLAKY-5 a déjà retiré les cas connus.
    - Aucun piège `runBlocking` : les `= runBlocking { … }` des nouveaux tests sont des fonctions d'aide, pas des
      méthodes `@Test`, et `TestMethodsReturnUnitTest` reste vert d'après les passations.

## Vérifié, sans constat

- **Tableau de bord : liaison et porte** (`web/Dashboard.kt`, `web/Routes.kt`).
  - `HttpServer` lié à `InetAddress.getByAddress(127.0.0.1)` (ni IPv6 ni autre interface ; testé contre chaque adresse
    IPv4 non locale).
  - Hôte attendu : `127.0.0.1`, `localhost` ou `[::1]` avec le **même port**, casse ignorée ; sinon 421, y compris pour
    `/login`.
  - `GET` et `HEAD` seulement, sinon 405 avec `Allow`.
  - `/style.css` sans jeton (aucune donnée). Toute autre racine exige le cookie, sinon 403 sans données ni barre de
    navigation.
  - Une page inconnue donne 404, un encodage cassé 400 (`URLDecoder` lève `IllegalArgumentException`, qui est
    attrapée).
  - Une racine `style.css/../turns` ne contourne rien : les pages sont choisies par la première racine, et aucun
    fichier n'est servi depuis un chemin.
  - Un `Host` étranger (rebinding DNS) est refusé.
  - Une requête intersite reçoit 403 : un cookie `SameSite=Strict` n'est jamais envoyé, et le tableau de bord n'écrit
    rien de toute façon.
- **Jeton.**
  - 32 octets de `SecureRandom`, en mémoire seulement (`@Volatile`).
  - Comparaison des empreintes SHA-256 par `MessageDigest.isEqual` : temps indépendant de la position de la différence
    et de la longueur du candidat.
  - `reset` invalide les cookies et les liens.
  - Le jeton n'apparaît dans aucun log du mod. Les refus sont journalisés une ligne par minute, avec la méthode, la
    première racine et la raison (un `Host` tronqué à 80 caractères) ; une connexion réussie donne un `INFO` sans le
    jeton.
  - Au joueur, la commande envoie un clic `OpenUrl` dont le texte ne montre pas le jeton, avec `sendSuccess(…, false)`
    : rien n'est diffusé aux opérateurs ni écrit dans le journal du serveur.
- **Cookie** : `narrator2=<jeton>; HttpOnly; SameSite=Strict; Path=/`, sans `Max-Age` (cookie de session) et sans
  `Secure` (HTTP local), ce qui est voulu.
- **En-têtes sur toute réponse**, refus, 303 et feuille de style compris : `Content-Security-Policy: default-src 'none';
  style-src 'self'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'` (sans
  `script-src`, donc rien ne s'exécute), `nosniff`, `no-store`, `no-referrer` (et la balise `<meta name="referrer">`).
- **Échappement par construction** (`web/Html.kt`, `web/Svg.kt`).
  - Seuls `text()` et `element()` écrivent dans le tampon ; `Html` a un constructeur `internal`.
  - Tout texte et toute valeur d'attribut passent par `escape` (`< > & " '`, NUL), et les attributs sont toujours entre
    guillemets doubles.
  - Les éléments viennent d'une liste fermée (ni `script`, ni `style`, ni `img`, ni `iframe`) ; les attributs
    `on…`/`style` sont refusés ; `href`, `action` et `src` doivent commencer par `/` (pas `//`) ou `#`.
  - Un `Link` ne se construit que par `link()` ou `WebRequest.with`, avec chaque segment encodé (RFC 3986).
  - Le SVG ne passe que par ces mêmes éléments, avec ses tailles en attributs.
  - `refresh` est un `Int` borné de 5 à 3 600.
  - Les pièges de `NoScriptTest` (`<img onerror>`, `"><b>`, `<script>`) sont rendus en texte. Aucune chaîne brute ne
    contourne l'échappement dans `OverviewPage`.
- **Lectures et arrêt.**
  - Une page tourne sur un fil `narrator2-web-*` (démon), jamais sur le thread serveur, et lit les états vivants dans des
    instantanés sûrs depuis n'importe quel fil (`director.status()`, `players.online`, `workers.list()`,
    `memory.jobs.status()`, `mechanics.situationLines`, `BlockRestorer.pending()`, `forge.status()`).
  - La vue d'ensemble ne fait qu'une lecture du Journal (`COST_BY_ROLE` à 5 s, `TraceQueries.lastTurns` avec sa propre
    limite).
  - À `SERVER_STOPPING`, le tableau de bord s'arrête en premier : `stop(0)` n'attend aucun échange (le JDK 25 ne joint
    que son fil `HTTP-Dispatcher`, qui ne lit aucune requête), puis `shutdownNow`. Une page interrompue rend son permis
    dans le `finally`.
  - Le serveur démarre depuis un fil démon éphémère : le `HTTP-Dispatcher` en hérite et ne retient jamais la JVM.
  - Un port pris ou l'absence de `jdk.httpserver` donnent un `WARN` et un `DisabledDashboard`, jamais un échec du
    démarrage.
  - `abandon()` arrête le tableau de bord ; la configuration des GameTests le coupe (`build.gradle.kts`).
- **Vie privée.**
  - Aucun fichier du mod ne nomme `private_messages` en dehors de `journal/` et `event/` (vérifié par recherche).
  - La vue d'ensemble lit `llm_calls`, `turns` et `TraceQueries`. Aucune page ne montre la configuration ni la clé.
- **Thread serveur.**
  - `createStyle`, `createLineReviewer` et `createDashboard` (sur le thread serveur à `SERVER_STARTED`) s'abonnent,
    lancent une coroutine ou se lient, sans attente ; le démarrage du tableau de bord rejoint un fil qui vit une
    milliseconde.
  - La relecture (client asynchrone, 3 s), l'amorçage et la relecture des noms de la veille, le cache des idées,
    `inspire`, `/narrator style` (`replyLater`) et `/narrator memory ideas` (`read`) tournent dans des coroutines.
  - Le refus d'amorce lit une vue publiée, sans verrou, en quelques microsecondes, dans la coroutine du Directeur.
  - La ligne `Style:` et les notes de ton ne lisent que la mémoire vive.
  - Files bornées : 64 pour la relecture, 256 pour la veille. Les pertes sont comptées par le bus et visibles dans
    `/narrator style`.
- **Ajout seul et outils branchés.**
  - La note de ton est un `OperatorNote.engine` ajouté au prochain envoi (une seule par requête, la plus grave),
    échappée et coupée à 400 caractères. Elle ne réveille jamais le Directeur (urgence `NEXT`, saillance 0) et un
    silence la jette.
  - La ligne `Style:` arrive en dernier dans `<situation>`, échappée (`ModelText.escape` est idempotent) ; une erreur
    donne `Style: unavailable (…)`.
  - Le refus d'amorce est un `Invalid arguments` du Directeur seul (`Role.DIRECTOR`, un tour, pas de `firingId`), après
    le contrôle de l'audience et avant le limiteur (rien d'envoyé ni de compté). Il tombe **une fois par tour** au plus
    (`refusedTurn`) et peut se couper (`style.refuseRestingOpeners`).
  - `inspire` est caché sans `storytellerAvailable`, que personne ne pose encore pour le Directeur (M6-08), mais que
    `MemoryAgents` pose pour la conversation du Scénariste.
  - `propose_idea` et `drop_idea` ne sont qu'au Scénariste (`ToolCatalog`), et le **magasin** refuse tout autre auteur.
  - Les règles de la source `STORYTELLER` sont explicites et exhaustives (`memoryKind`, `canonSource`/`planner`,
    `resolveCanon` limité à « récit `planned` → `abandoned` », `addInteraction` refusé, `MODELS`) et doublées par les
    outils (`remember` limité à `STORYTELLER_KINDS`, `resolve`, `author()`).
  - Le module `ready` est vérifié par l'outil (`Arsenal.entry(name)` rend la dernière version prête) ; brief, références
    (`RefKind.facts`), joueurs, `arc_step` et « pas de redite » sont vérifiés par le magasin.
- **Exactitude.**
  - Normalisation des nombres (chiffres, heures, ordinaux, lettres ; `un` et `une` exclus ; `dix-sept` fusionné), des
    suites de noms (y compris avec « et ») et des élisions de mots vides (`c'est` vide, `l'aviation` plein).
  - Préfixes de `chat` retirés ; amorces sur `say` d'au moins 25 points de code, avec au moins un mot plein ; repos des
    béquilles à 3 sur 30 avec maintien à 2.
  - Entropie H / ln(min(16, n)) sur les registres de la voix (0 sous deux répliques).
  - Périodes `[from, to)` de `StyleReport` ; ordre des colonnes de `narrator_lines` et de `Flaw` vérifié colonne par
    colonne.
  - `ToneWatch` : seuils de T1, une note par étiquette, cadence de 30 min par dérive ; `flat` et `repeats` exigent que
    la réplique elle-même dérive.
  - Expiration des idées en **temps de jeu du monde** ; plafond de 12 avec la saison comprise mais jamais remplacée,
    l'idée jamais rendue la plus ancienne d'abord ; cohérence du cache après une écriture enveloppée (`Mutex` : la
    dernière lecture finie porte l'état le plus récent).
  - `mem_ideas.players` : nom ou UUID, **vérifié par la sonde** (`[Bartok, 00000000-…-0000000000aa]`).
- **Schéma V4.**
  - Migration ajoutée à la liste et appliquée sur un monde en V3 (`DuckDatabaseTest`) ; `MemorySchema.MIGRATION` =
    V4 ; graines des identifiants (`ideas.id`, `ideas.run_id`, `line_labels.event_id`).
  - `revertRun` annule les idées d'un rêve et leur index (`RefKind.indexed`), y compris celles que `makeRoom` a closes,
    restaurées par le même travail.
  - `line_labels` est hors de la description du modèle et lue par `narrator_lines`.
- **`inspire`.**
  - Budget de temps tenu : barrière 0,5 s, puis historique et mémoire en parallèle (2 s), puis écritures (1 s), soit au
    plus environ 3,5 s sous les 5 s de l'outil ; rien n'échoue faute de source.
  - Les clés de levier d'`Interventions.key` correspondent aux vrais noms d'arguments (`punishment`, `goal`, `favor`,
    `kind`, `module`, `effect`, `attribute`), vérifiés dans `PunishTools`, `ChallengeSpec`, `FavorTools`, `RuleSpec`,
    `ModuleTools`, `PlayerActionTools` et `MemoryArgs`.
  - Tout texte rendu est échappé (titres, pitchs, accroches, noms) ; les clés de levier viennent d'appels réussis, donc
    validés.
  - Les tirages du Scénariste ne rendent rien au Directeur.
- **Coûts** : `Prices` porte sur le modèle qui a répondu ; les prix sont validés (finis, positifs ou nuls) et fusionnés
  modèle par modèle (`FREE_FORM`).
- **M6-14** (`prompts/director.md`).
  - Lignes 1 à 54 intactes ; diff limité à « Comment tu travailles » ; T3 B ≥ A (0 contrôle perdu sur 29, « drôle »
    1,48 → 1,52).
  - Ce qui a été retiré se retrouve dans les descriptions d'outils, vérifié par recherche : le gage jamais pour un
    retardataire (`ChallengeSpec.penalty`), le thème d'un récit (`RememberArgs.theme`), la règle de remplacement d'un
    effet (`apply_effect`), le duel arrêté à 1 PV (`propose_duel`), les enchantements au maximum du jeu plus un
    (`ItemSpecs`), les trois faveurs (`favor`).
  - Les deux points ouverts (lisibilité des durées, compte rendu en fin de tour) sont confiés à M6-16.
- **M6-09** (`live/`).
  - `ToneJudge`, `DirectorReplayEval`, `LiveToneEvalTest` et `LiveLineReviewEvalTest` portent `@Tag("live")` ;
    `ReplayHarnessTest` (dans `build`) passe par `FakeAnthropic`.
  - La clé n'est ni imprimée ni écrite.
  - Les prix recopiés (dont Haiku dans `LiveLineReviewEvalTest`) passeront à `Prices.kt` avec M6-18 : déjà prévu, sans
    constat.

## Décisions à prendre

- **D1 — Lien de connexion à usage unique ?** (constat 6) **Recommandation : oui.** Un code de 128 bits, valable
  10 min et consommé à la première connexion, distinct du jeton de session posé en cookie. Le lien recopié par la
  console dans `latest.log`, l'historique du navigateur ou un journal DEBUG ne vaut plus rien après usage. Coût :
  environ 30 lignes dans trois fichiers de M6-10. Sinon, garder l'état actuel (accepté par le design) et le dire dans
  la réponse de la commande.
- **D2 — Prix par défaut du modèle de repli `claude-opus-4-8` ?** (constat 8) **Recommandation : oui, si le
  propriétaire confirme son prix** : il apparaît dans les traces réelles, et sans prix la vue d'ensemble sous-estime la
  soirée. Sinon, rien à faire : « $ ? » et le compte à part disent déjà le manque.
- **D3 — Registre dominant parmi les registres de la voix ?** (constat 7) **Recommandation : oui** pour
  `topRegister`/`topRegisterShare`, avec la part de `plain` montrée à part. Le seuil de T6 (≤ 35 %) mesure alors la
  rotation des cadres, ce qu'il vise. Coût : quelques lignes de `StyleReport`, et les tests de M6-07 qui lisent
  `<diversity>` à rejouer.
- **D4 — Écarter `narrateur` des mots béquilles candidats ?** (constat 5) **Recommandation : oui**, comme M6-02 le
  proposait, dans `crutchCandidates` seulement. Il ne faut pas en faire un `<nom>` : « Le Narrateur… » doit rester
  visible comme amorce.

## Lots de corrections proposés

Chaque lot possède ses fichiers ; **aucun fichier n'est commun à deux lots**.

| Lot | Constats / décisions | Fichiers | Touche un fichier de la partie 2 ? | Critère |
|---|---|---|---|---|
| **M6-FIX-1 — la veille du style** | 1, 4, 5, 7 (si D3), 10 (citations, `Flaw`), 11 (trous 1 et 3) ; D3, D4 | `style/LineReviewer.kt`, `style/LineReviewLabels.kt`, `style/StyleWatchImpl.kt`, `style/StyleReport.kt`, `style/ToneWatch.kt`, `memory/stats/Tics.kt`, `tool/speech/SpeechTools.kt` (la citation seulement), `core/ModelText.kt` (une fonction) ; tests `LineReviewerTest`, `TicsTest`, `StyleWatchTest`, `StyleReportTest`, `ToneWatchTest`, `SayRestingOpenerTest`, `DiversityTest`, `StyleCommandsTest` | **Non** : aucun de ces fichiers n'est possédé par M6-07, M6-08 ou M6-16. **Mais** M6-07 (`DreamInputs`, bloc `<diversity>`, `<tics>`) et M6-08 (`MemoryBriefing`, `Style: overused lately`) lisent `Tics` et `StyleReport` : rejouer `DreamInputsTest`, `DreamReplayTest` et `BriefingMemoryTest` à la fusion | titres, barre d'action et livres jamais relus (vrais textes préfixés) ; nom élidé normalisé ; candidats par mot entier, sans `narrateur` ; registre dominant de la voix (si D3) ; une seule fonction de citation ; build vert |
| **M6-FIX-2 — le tableau de bord** | 2, 6 (si D1), 9, 11 (trou 2) ; D1 | `web/Routes.kt`, `web/Html.kt`, `web/Dashboard.kt`, `command/DashboardCommands.kt`, en option `journal/DuckJournal.kt` (ne pas lancer une requête abandonnée) ; tests `DashboardServerTest`, `HtmlTest`, `DashboardCommandsTest` | **Pas de page** (`web/pages/**` intact) et aucune signature changée (`PageContext.read`, `Html`, `Link`) : M6-11 et M6-12 ne sont pas gênées. **M6-17** doit retirer `notBuiltYet` de `web/Routes.kt` : conflit trivial ; fusionner M6-FIX-2 avant M6-17 | sonde du constat 2 en test de non-régression (jamais deux lectures du tableau de bord à la fois, même après une expiration) ; code de connexion à usage unique (si D1) ; `\`, `formaction` et `meta` refusés ; build vert |
| **M6-FIX-3 — idées et prix** | 3, 8 ; D2 | `memory/MemoryService.kt`, `memory/MemoryModule.kt` (option recommandée du constat 3 seulement), `trace/Prices.kt`, `config/NarratorConfig.kt` (si D2) ; tests `IdeasStoreTest`, `MemoryServiceTest`, `PricesTest`, `ConfigValidationTest` | **`memory/MemoryModule.kt`** a été modifié par **M6-13** (partie 2, fusionné, en cours de relecture) : si la relecture de la partie 2 propose aussi un lot sur ce fichier, prendre **l'option minimale** du constat 3 (rien dans `MemoryModule.kt`) ou ordonner les deux lots. Aucun fichier côté rêve. M6-08 consomme `openIdeas()` : il profite du correctif, sans conflit de fichier | le cache suit l'hygiène (ou la surcharge morte est retirée et la consigne de filtrer `expiresPlaySeconds` est écrite pour M6-08) ; `Prices.dollars(0.996)` = `$1.00` ; prix du repli si D2 ; build vert |

**Ordre conseillé.** M6-FIX-1 et M6-FIX-2 partent tout de suite, en parallèle : ce sont les deux constats importants,
et ils ne partagent aucun fichier avec la partie 2. M6-FIX-2 passe avant M6-17. M6-FIX-3 part avec l'option minimale
tout de suite, ou avec l'option recommandée une fois connue la relecture de la partie 2 sur `MemoryModule.kt`.

**Hors lots** : la simplification de `propose_idea` (constat 10) touche `DuckMemoryStore.kt` et `MemoryStore.kt`. Elle
est à reprendre après M6-07, qui modifie `DuckMemoryStore.kt`.

**Pour la relecture de la partie 2** : M6-08 doit filtrer `expiresPlaySeconds` des idées lues par `openIdeas()` si
M6-FIX-3 prend l'option minimale ; M6-17 vérifie l'usage de `Svg.curve`, `Filter.Field` et `pager` (constat 10).
