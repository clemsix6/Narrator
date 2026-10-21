# Relecture de M6, partie 3 : constats et lots de corrections

Relecture du 21/10/2026, sur `main` à **`a33adc5`** (fusion de M6-16). Périmètre : ce qui a été fusionné sans être relu,
soit le contenu des fusions de **M6-08** (`a37f052` : le Scénariste à la demande, `<programme>` dans le briefing, la
ligne `Idea:` du battement), **M6-12** (`f161b08` : pages mémoire, joueurs, mécaniques, Forge, style et SQL du tableau
de bord), **M6-FIX-2** (`68807c1` : tableau de bord optionnel, `ReadWatch`, `PageContext.inTurn`/`read`, code de
connexion à usage unique, gardes de `Html`), **M6-FIX-7/8** (`4ba51ba` : un seul calcul du coût, `TraceQueries.usage`,
fuseau des transcriptions, `/narrator style`) et **M6-16** (`a33adc5` : `director.md`, `storyteller.md`, `dreamer.md`,
`analyst.md`, retrait de `sql` au Scénariste). M6-FIX-4/5, M6-FIX-6, FORGE-ABANDON-FIX, FLAKY-6 et M6-17 ne sont pas
relus ici.

Lus : `M6.md` (§5, §6, §7, §8, §10, §12, §15, §16.2), `M6_DESIGN_REVIEW.md`, `M6_REVIEW_1.md` et `M6_REVIEW_2.md` (le
modèle, avec les décisions du superviseur), les passations M6-08, M6-12, M6-FIX-2, M6-FIX-7-8 et M6-16 (elles font foi
contre le design), `ARCHITECTURE.md` §17 ; tout le code, les prompts et les tests des cinq fusions, et ce qu'ils touchent
autour (`AgentLoop`, `Chapters` et sa demande de résumé, `chapter_summary.md`, `ExchangeLog`, `DuckJournal.read`,
`SqlRunner`, `DuckPlayerSheets`, `DuckPlayClock`, `ArsenalStore.source`, `GuardsImpl.activeOn`, `makeRoom`, `Hygiene`).

Chaque constat donne sa gravité, l'endroit (`fichier:ligne` sur `a33adc5`, sauf `journal/DuckJournal.kt`, cité sur
`c8b716c` ; chemins sous `TheMod/src/main/kotlin/io/github/clemsix6/narrator2/`, les prompts sous
`TheMod/src/main/resources/narrator2/prompts/`), un scénario, la preuve et un **correctif proposé**. **Vérifié** veut
dire lu des deux côtés, ou prouvé par une **sonde jetable** : un test temporaire lancé dans mon worktree
(`gradlew.bat test --tests "*ReviewProbe*"`), puis supprimé et jamais commité. **Raisonné** veut dire déduit de la
lecture sans exécution. Le superviseur décide. Les lots de la fin ne partagent aucun fichier.

**État de départ.** J'ai avancé mon worktree jusqu'à `main` (`c8b716c`, voir plus bas) et relancé les tests unitaires
(`gradlew.bat test --continue`) : **2 245 tests, 0 échec, 0 ignoré**, en 3 min 7 s. Je n'ai pas relancé les GameTests ;
le build final de M6-16 donne « All 398 required tests passed ». Les deux sondes citées dans les constats 1 et 3 ont
tourné dans ce worktree, avant ce lancement, puis ont été supprimées.

**`main` a bougé pendant la relecture.** Il est à `c8b716c` : M6-FIX-6 (`df4f13d`) et M6-FIX-4/5 (`c8b716c`) sont
fusionnés. Parmi les fichiers visés par les constats et les lots ci-dessous, seuls `journal/DuckJournal.kt` (le
constat 3 le cite sur `c8b716c` : six lignes de plus avant `read`) et `memory/MemoryService.kt` (une signature, sans
toucher à la ligne citée) ont changé (vérifié avec `git diff --stat a33adc5 main`). Conséquence sur les lots :
`journal/DuckJournal.kt`, `memory/MemoryService.kt` et `memory/Memory.kt` sont libres.

**Bilan : 8 constats. 0 bloquant, 0 important, 3 mineurs, 5 finitions.** S'y ajoutent quatre décisions à prendre.

- **Le Scénariste à la demande et le battement tiennent leurs bornes.** Six requêtes au plus par commande (huit au pire
  avec la limite dure), `<programme>` dans ses 3 000 caractères par construction, une idée par battement au plus toutes
  les deux heures, jamais un réveil de plus. Le Directeur ne voit que des outils branchés, `storytellerAvailable` est
  calculé à l'ouverture de chaque chapitre, et la conversation reste en ajout seul. Le Scénariste ne bloque jamais le
  Directeur. Seul trou : quand le Journal tombe en cours de route, le Scénariste reste proposé alors qu'il ne peut plus
  rien écrire (constat 4).
- **Le tableau de bord est sûr.**
  - Aucune requête ne nomme `private_messages` : la page SQL passe par l'accès `MODEL` de `SqlRunner`, la chronologie
    d'un joueur lit des canaux nommés un à un, et un chuchotement n'est jamais dans `events`.
  - Aucune adresse ne devient un chemin de fichier.
  - Le code de connexion est à usage unique, sans jeu de courses (tout se fait sous un verrou), et ne fuit ni par les
    journaux ni par l'en-tête `Referer`.
  - Le correctif de FIX-2 rend bien le tour de lecture à la vraie fin de la requête. Mais « une requête à la fois »
    refuse en fait deux lectures **successives** d'un même tour : 497 fois sur 500 dans la sonde (constat 3). Aucune
    page ne le fait aujourd'hui.
- **Les prompts font ce que M6-16 a mesuré** : 0 compte rendu de fin de tour sur 9, et le Scénariste vit sans `sql`.
  Mais deux phrases peuvent faire répondre ou taire le Directeur au mauvais moment :
  - « réponds « Fin. » […] de même pour un tour où tu ne fais rien » : un « Fin. » devient le résumé du chapitre, et
    le code l'accepte sans alerte (constat 1, prouvé par sonde) ;
  - « un début de chapitre : laisse passer », alors qu'un chapitre s'ouvre presque toujours sur l'arrivée d'un joueur
    (constat 2).
  Le ton tient : drôle, sec, jamais sombre.
- **M6-FIX-7/8 : sans constat.** Un seul calcul du coût, un seul libellé de `$ ?`, `usage` en une requête, les heures
  des transcriptions au fuseau du tableau de bord.

## Constats

1. **MINEUR (« Fin. », résumé de chapitre ; décision D8) : une réponse « Fin. » à la demande de résumé devient le résumé
   du chapitre, sans alerte.**
   - Où :
     - `director.md:229` : « réponds « Fin. » et rien d'autre, de même pour un tour où tu ne fais rien […] Seule
       exception : le résumé qu'il te demande quand un chapitre se ferme ». C'est la dernière section du prompt
       système, et « il » renvoie de loin au « moteur », trois phrases plus haut ;
     - `chapter_summary.md:1` : la demande, une note du moteur à la fin du message, ne dit rien de « Fin. » ;
     - `director/Chapters.kt:405` (`Closing.summary`) : le texte le plus long des réponses, quel qu'il soit. Puis
       `:318` et `:349-357` : un résumé non nul n'est pas un problème, et la décision `chapter closed` reste un
       `INFO`.
   - Scénario :
     - un chapitre se ferme (serveur vide, limite de tokens, `/narrator reload`) après des tours qui finissent tous
       par « Fin. » ;
     - la demande de résumé arrive comme un message de plus, et le modèle applique la dernière section de son prompt ;
     - le chapitre se ferme avec le résumé « Fin. ». Le briefing suivant le montre dans `<previous_chapters>`, la
       sieste du Chroniqueur le reçoit, et rien ne le signale (« summary of 4 characters », au niveau `INFO`) ;
     - les fils et les engagements que seul le résumé portait (ceux que le greffier n'a pas encore rattrapés, le
       « Répertoire utilisé ») sont perdus pour le chapitre suivant.
   - Ce qui tient : le contrôle réel de M6-16 (un chapitre de deux tours finis par « Fin. ») a reçu un résumé complet
     de 2 067 caractères. Le risque est donc faible, mais il repose sur un seul essai, alors que la consigne vise
     justement à faire répondre « Fin. » partout.
   - Preuve : **vérifié par sonde** pour le code, avec `DirectorHarness` : un tour fini par « Fin. », puis 11 min de
     serveur vide, puis une demande de résumé répondue « Fin. ». Le résultat est `summary=«Fin.»`, avec la décision
     `chapter 1, server_empty: no player online for 10 minutes; summary of 4 characters` (`summary_chars` 4), au
     niveau `INFO`. Que le modèle le fasse vraiment est **raisonné**.
   - Correctif proposé :
     - `Chapters.Closing.summary` : un texte de moins de `MIN_SUMMARY_CHARS` (environ 100 caractères) n'est pas un
       résumé. Le format en impose sept sections, même vides, soit plus de 150 caractères. Le chapitre se ferme alors
       `no summary: …` avec `problem = true` (décision en `WARN`), comme pour une réponse inutilisable ;
     - `chapter_summary.md` : une phrase, « Ni « Fin. » ni silence ici : le résumé, et lui seul. » ;
     - `director.md:229` : « le résumé que le moteur te demande quand un chapitre se ferme » ;
     - test : dans `ChapterScenariosTest`, une demande répondue « Fin. » ferme le chapitre sans résumé, avec un
       `WARN`.
   - Fichiers : `director/Chapters.kt`, `chapter_summary.md`, `director.md` ; test `ChapterScenariosTest`.

2. **MINEUR (prompt, « Ne rien faire » ; décision D9) : « un début de chapitre : laisse passer » invite au silence au
   moment où un joueur arrive.**
   - Où : `director.md:206` (« Un battement sans matière, une conversation entre joueurs qui se passe très bien de toi,
     une mort banale, un joueur inactif, un début de chapitre : laisse passer »).
   - Scénario :
     - un chapitre s'ouvre au premier réveil qui suit la fermeture du précédent (serveur vide 10 min, limite de
       tokens, rechargement, redémarrage) ;
     - ce réveil est presque toujours l'arrivée du premier joueur de la soirée (JOIN et `<memory>` SHEET avec Last
       session et While away), un message adressé, un DUE ou un TASK ;
     - la liste met « un début de chapitre » au rang d'« un battement sans matière ». Lue à la lettre, elle dit de
       laisser passer le retour d'un joueur, que le design traite comme un grand moment (§15.3, S-S1 : un joueur
       revient après une semaine, le Directeur passe commande au Scénariste) ;
     - pour un message adressé, la consigne suivante (« réponds presque toujours ») l'emporte sans doute. Pour une
       arrivée, rien ne tranche.
   - Origine : la situation `empty_heartbeat` du rejeu de M6-16, une ouverture de chapitre sans matière. Sa passation
     le dit elle-même : « mesurer le silence demande un battement qui suit de près un tour […], pas une ouverture de
     chapitre ».
   - Preuve : **raisonné** (lecture du prompt, et de `Chapters` et `Director.wake` : un chapitre s'ouvre sur ce qui
     réveille le Directeur).
   - Correctif proposé (si D9 = oui) : retirer « un début de chapitre » (−24 caractères). Le cas qu'il visait est déjà
     dans la liste : « un battement sans matière ».
   - Fichiers : `director.md`.

3. **MINEUR (tableau de bord, tour de lecture de M6-FIX-2 ; décision D10) : deux lectures successives dans un même tour
   sont refusées presque toujours, avec un message de délai dépassé qui n'a pas eu lieu.**
   - Où :
     - `journal/DuckJournal.kt:165-171` sur `c8b716c` : `result.complete(query(connection))` réveille l'appelant
       **avant** `endQuery`, `giveBack` et `watch?.end()` ;
     - `web/Routes.kt:146-151` (`Turn.start`) : tant que `running` est vrai, une autre requête du tour est refusée ;
     - `DuckJournal.kt:159-162` : le refus devient une `JournalTimeoutException` (« the Journal read did not finish
       within 5000 ms »).
   - Scénario :
     - une page ou un service fait deux `journal.read` à la suite dans `context.inTurn`. Le cas le plus probable : une
       future version de `DuckPlayerSheets`, ou une page de M6-17, qui lit une table puis une autre ;
     - la première lecture rend sa valeur, l'appelant lance aussitôt la seconde, et un autre fil de lecture prend une
       connexion libre et appelle `start()` ;
     - à ce moment, le premier fil n'a pas encore fini son `SELECT 1` (`endQuery`, environ 0,1 ms) : la seconde
       lecture est refusée ;
     - la page dit « The sheet could not be read: the Journal read did not finish within 10000 ms », à chaque
       chargement.
   - Ce qui tient : aucun appelant ne le fait aujourd'hui. `SqlPage` fait une barrière puis une seule lecture,
     `DuckPlayerSheets.sheet` une seule lecture (les horloges de jeu sont en mémoire), et `PageContext.read` une
     lecture par tour par construction. L'invariant du correctif (jamais deux lectures du tableau de bord en même
     temps) est juste. Mais la passation et le KDoc disent « une requête à la fois », ce qui laisse croire que des
     lectures successives passent.
   - Preuve : **vérifié par sonde**, avec un vrai `DuckJournal` à trois connexions et le vrai `PageContext` :
     500 tours de deux lectures successives triviales, dont **497 refusés** (« the Journal read did not finish within
     5000 ms »).
   - Correctif proposé (si D10 = oui ; environ 6 lignes) :
     - dans `DuckJournal.read`, garder le résultat ou l'exception de `query`, puis faire `endQuery`, `giveBack` et
       `watch?.end()`, et seulement ensuite compléter le futur. L'appelant reprend une fois la connexion rendue : les
       lectures successives d'un tour passent, et tous les appelants y gagnent une connexion libre plus tôt ;
     - un refus par `ReadWatch` lève un message à lui (« a read of the dashboard is already running in this turn »),
       et non un délai ;
     - test dans `DashboardServerTest` : deux lectures successives dans un tour passent toutes (100 tours), et la
       lecture parallèle reste refusée.
   - Fichiers : `journal/DuckJournal.kt`, `journal/ReadWatch.kt` (KDoc), `web/Routes.kt` (KDoc) ; tests
     `DashboardServerTest`, `DuckJournalTest`.

4. **FINITION (le Scénariste et le Journal tombé) : quand le Journal tombe en cours de route, le Scénariste reste
   proposé, et sa commande échoue après plusieurs requêtes payées.**
   - Où : `agent/Workers.kt:94` (`if (services.memory.available) add(Role.STORYTELLER)`) et `:104`.
     `MemoryService.available` vaut toujours vrai (`memory/MemoryService.kt:48`), alors que `DuckJournal.available`
     tombe quand l'écrivain échoue.
   - Scénario :
     - le Journal tombe pendant la soirée. L'Analyste et l'Éclaireur sont refusés (« the Journal is unavailable »),
       mais le Scénariste est encore proposé ;
     - au chapitre suivant, `delegate` reste montré pour lui seul, sans `inspire` (qui exige le Journal) ;
     - une commande lit `player_profile`, qui échoue, puis `propose_idea`, qui échoue aussi (`journal.write`), et rend
       un rapport sans idée : 0,1 à 0,3 $ pour rien.
   - Preuve : **vérifié** par lecture (`Workers.roles`, `MemoryService.available`, `DuckJournal.available`,
     `ToolCatalog`).
   - Correctif : `if (services.journal.available && services.memory.available) add(Role.STORYTELLER)`, et la raison
     « the Journal is unavailable » quand c'est lui qui manque ; un cas dans `WorkersTest`.
   - Fichiers : `agent/Workers.kt` ; test `WorkersTest`.

5. **FINITION (prompts : petites contradictions et oublis).**
   - `director.md:229`, « Une intention pour plus tard passe par `remind_me` ou `remember` » :
     - `remember` écrit au canon, « ce que tu as dit et qui fait loi » (`:170`, `:184` : « juste après avoir pris un
       engagement devant les joueurs […] avec tes mots exacts »). Une intention jamais annoncée (« punir Bartok s'il
       recommence ») y deviendrait une menace ouverte, avec son DUE, que les joueurs n'ont jamais entendue ;
     - `remind_me` est l'outil fait pour cela (`:166` : « Écris la note pour toi-même dans le futur ») ;
     - correctif : « Une intention pour plus tard passe par `remind_me`. »
   - `director.md:170` : la liste des références (« C-45 au canon, M-120 un souvenir, I-512 une interaction, H-88 la
     chronique, N-9 une note ») ignore `P-41`, qu'il lit dans `<programme>` et dans la ligne `Idea:`. Correctif :
     ajouter « P-41 une idée de ton programme, qu'`inspire` relit en entier » (environ 50 caractères).
   - `storyteller.md:9`, le mode « à la demande » :
     - il ne sait pas qu'il a six requêtes au plus (`Workers.STORYTELLER_STEPS`) ;
     - « Plusieurs appels par message » n'est écrit que pour le programme (`:54`) ;
     - lire deux fiches, tirer `inspire`, puis écrire trois idées l'une après l'autre atteint la limite. Les idées
       restent écrites, mais le rapport classé, lui, se perd (`step limit`) ;
     - correctif : « Six requêtes au plus : lis fiches et `inspire` dans un même message, écris les trois idées dans
       un autre. »
   - `storyteller.md:47` : « Évite les tics […] les poules en orbite », sans « à tout propos » comme chez le Directeur
     (`director.md:84`). Or `orbiting_chickens` est l'un des sept modules intégrés que `<arsenal>` lui montre : le
     Scénariste ne le proposera jamais. Correctif : « les poules en orbite à tout propos » (le propriétaire relit,
     point 6 de la passation de M6-16).
   - Fichiers : `director.md` (marge actuelle : 1 005 caractères ; ce lot en ajoute environ 50 et en retire environ
     40), `storyteller.md` (8 450 sur 9 000).

6. **FINITION (tableau de bord : simplicité) : deux pages des décisions du moteur, deux `DecisionRow`, deux `Where`,
   deux tables des décisions.**
   - Où :
     - M6-12 : `web/pages/MechanicsPage.kt:46`, `:66` et `:234-245` (l'onglet `/mechanics/decisions` : sorte
       `mechanic`, filtres « décision » et « sujet contenant ») ; `web/pages/PageParts.kt:75-117` (`Where`,
       `DecisionRow`, `Connection.decisions`, `decisionTable`) ;
     - M6-11 : `web/pages/DecisionsPage.kt` et `web/DashboardQueries.kt:118-131` (`Where`, privée), `:248-260`
       (`DecisionRow`, publique, de même nom simple dans le paquet parent).
   - Constat :
     - `/mechanics/decisions` refait `/decisions?kind=mechanic&decision=…&subject=…`, que M6-11 sert déjà avec les
       mêmes filtres et une pagination ;
     - `ForgePage` montre les 20 dernières décisions `forge` et renvoie à `/decisions?kind=forge` pour le reste :
       c'est le bon modèle ;
     - deux tâches parallèles ont écrit chacune leur lecture et leur rendu de `engine_decisions`.
   - Correctif :
     - retirer l'onglet `/mechanics/decisions`, et faire suivre la liste « Leur histoire » d'un lien vers
       `/decisions?kind=mechanic`, comme `ForgePage` ;
     - les « dernières décisions » de `ForgePage` passent par `DashboardQueries.decisions` et la table de
       `DecisionsPage`, mise en commun ;
     - un seul `Where` (celui de `PageParts`, que `DashboardQueries` réutilise) et un seul `DecisionRow`.
     - En option : les lecteurs de `ResultSet` (`instant`, `long`, `int`) sont recopiés dans `DashboardQueries`,
       `TraceQueries` et `memory/Jdbc.kt`.
   - Preuve : **vérifié** par lecture et par recherche des appelants.
   - Fichiers : `web/pages/MechanicsPage.kt`, `web/pages/ForgePage.kt`, `web/pages/PageParts.kt`,
     `web/pages/DecisionsPage.kt`, `web/DashboardQueries.kt` ; tests `DashboardPagesTest`, `DashboardPrivacyTest` (ses
     deux listes d'adresses nomment `/mechanics/decisions`), `TracePagesTest`.

7. **FINITION (docs et KDoc périmés).**
   - `memory/MemoryService.kt:71` (KDoc d'`OpenIdeas`) : le cache « gives the briefing's `<programme>` ». Depuis
     M6-08, `<programme>` lit le magasin (passation, écart 5) ; le KDoc de `Memory.openIdeas` le dit déjà.
   - `docs/design/M6.md` :
     - `:678` (§6.4) : la tâche du Scénariste tourne « `workers.maxSteps` et `workers.timeoutSeconds` comme les
       autres ». C'est six requêtes au plus (`Workers.STORYTELLER_STEPS`, écart 1 de M6-08) ;
     - `:726` (§6.7) : « plus le prompt et les outils (`sql` décrit le schéma) » ; le Scénariste n'a plus `sql`
       (M6-16) ;
     - `:756` (§7) : le format d'une ligne de `<programme>` écrit les notes `4/4/5`. Elles s'écrivent
       `novelty 4 fun 4 fair 5` (écart 4 de M6-08).
   - `docs/MEMOIRE.md` §8, point 5 : dire que les trois idées d'une commande au Scénariste (6 h de jeu) comptent dans
     le plafond de 12. À plafond, chacune fait expirer l'idée ouverte la plus ancienne jamais rendue
     (`memory/DuckMemoryStore.kt:340-347`, `makeRoom`), donc jusqu'à trois idées du programme d'un coup, qui ne
     reviennent qu'au rêve suivant. C'est acceptable : ce sont les moins servies. Mais personne ne l'a écrit.
   - Fichiers : `memory/MemoryService.kt`, `docs/design/M6.md`, `docs/MEMOIRE.md`.

8. **FINITION (tests) : un dossier temporaire qui fuit, et des trous sur des chemins jugés corrects à la lecture.**
   - `web/DashboardPrivacyTest.kt:29` : `Files.createTempDirectory("m6-12-privacy")` à chaque test, jamais supprimé,
     avec un Journal DuckDB sur disque et un faux secret. Même défaut que `TracePagesTest`, corrigé par M6-FIX-7 :
     utiliser `@TempDir`.
   - Aucun test ne vérifie qu'une idée portée par un battement dont la requête échoue ou est refusée n'est pas rendue,
     ni que le repos de deux heures ne démarre pas. Le code est juste à la lecture : le `Shown` est posé après la
     marque du chapitre et tombe avec elle (`giveBack`), et `lastBeatIdeaAt` n'est posé que par `commit` (`Director.kt`,
     `Turn.run` et `commit`). Mais S-S2 ne couvre que le succès.
   - Les trous des constats 1, 3 et 4 (résumé « Fin. », lectures successives d'un tour, Journal tombé).
   - Aucun piège `runBlocking` :
     - S-S2 est écrit `= DirectorHarness(…).use { … h.assertInvariants() }`, qui rend `Unit` ;
     - `TestMethodsReturnUnitTest` est vert.
   - Mesures à l'horloge murale, larges, à laisser à un lot `FLAKY` s'ils tombent sous charge :
     - les nouveaux tests du tour de lecture (`DashboardServerTest`) : une requête de 6 s contre une limite de 5 s,
       soit environ 4 s de marge pour la page suivante ; une lecture de 500 ms et une requête de 2,5 s ;
     - la requête lourde de `DashboardPagesTest` : arrêtée en moins de 5 s pour une limite de 1 s, et entre 4,5 et 15 s
       pour une limite de 5 s.
   - Fichiers : tests `DashboardPrivacyTest`, `DirectorStorytellerScenariosTest` (ou `DirectorTest`) ; ceux des
     constats 1, 3 et 4.

## Vérifié, sans constat

- **M6-08 : le Scénariste à la demande** (`agent/Workers.kt`, `tool/task/TaskTools.kt`).
  - `roles` ne contient le Scénariste que lorsque la mémoire tourne (le constat 4 excepté). Sinon `submit` le refuse
    avec sa raison, et le rôle reste dans l'`enum` : un outil ne change jamais de définition.
  - La tâche a sept outils, aucun d'effet `SPEECH`, `WORLD` ou `TASK`, et ses outils `ENGINE` sont les écritures de la
    mémoire. Elle écrit sous l'auteur `storyteller` (son rôle), et `storytellerAvailable` n'est vrai que pour elle.
  - Elle tourne dans `min(workers.maxSteps, 6)` requêtes, huit au pire avec la limite dure ; à la limite, la note du
    moteur demande le rapport. Son délai est de `workers.timeoutSeconds`, et son rapport fait 3 000 caractères au plus.
  - `submit` rend la main tout de suite. La commande vit dans sa propre coroutine (la barrière d'`inspire` comprise),
    et son `inspire` ne rend rien au Directeur.
  - Aucune borne horaire sur le nombre de commandes : comme pour l'Analyste, seuls le budget de tours et la file
    (`MAX_QUEUED`) les limitent (décision D11).
- **`storytellerAvailable` reste cohérent.**
  - Il est calculé une fois, à l'ouverture du chapitre (`Role.STORYTELLER in services.workers.roles`), et la liste des
    outils ne change plus jusqu'à la fin du chapitre.
  - La mémoire ne tombe jamais en cours de route : `MemoryService.available` vaut toujours vrai, `DisabledMemory`
    toujours faux. Seul le Journal peut tomber (constat 4).
  - `/narrator restart` reconstruit `Workers`, `Chapters` et le Directeur : rien ne survit de l'ancien calcul.
  - Sans mémoire : ni `inspire`, ni Scénariste (S-S4).
- **M6-08 : `<programme>`** (`memory/MemoryBriefing.kt`).
  - Il lit le magasin (`ideas(OPEN, 50)`) et n'enregistre aucun rendu : montrer n'est pas rendre.
  - Il est échappé (`BriefText.cut` et `quote`, `ModelText.escape`) et tient dans ses 3 000 caractères par
    construction : pitchs coupés à parts égales, 40 au moins, puis `(+N more open ideas: …)`.
  - La forme réduite montre la saison et trois idées. `MAX_CHARS` vaut 75 400, et M6-08 a mesuré 72 931 caractères au
    maximum.
  - Une idée échue reste ouverte 10 min au plus : `Hygiene.tidy` passe toutes les 10 minutes.
- **M6-08 : la ligne `Idea:`** (`director/Director.kt`, `director/EventFormatter.kt`).
  - L'idée est choisie après la décision de réveil, donc jamais un réveil de plus, dans le cache des idées ouvertes.
    Elle est ouverte, n'est pas la saison, n'est pas échue, ses joueurs sont tous en ligne, et elle n'a pas été rendue
    depuis 2 h.
  - Une seule toutes les 2 h : `lastBeatIdeaAt` n'est posé qu'une fois la requête acceptée.
  - Elle est rendue par `Shown` au `commit`, et tombe avec la marque du chapitre en cas d'échec.
  - Échappée, 300 caractères au plus. Elle est dans le message d'ouverture : rien n'est réécrit.
- **M6-12 : confidentialité.**
  - Le parcours de `DashboardPrivacyTest` atteint plus de 60 pages. Le proxy JDBC enregistre chaque requête préparée
    ou exécutée.
  - La chronologie d'un joueur lit `events` (qui n'a jamais de chuchotement) et quatre canaux de `chat` nommés un à un
    (`public`, `pray`, `narrator`, `operator`).
  - La page SQL passe par `SqlRunner` avec l'accès `MODEL` : une barrière puis une seule lecture, dans le tour du
    tableau de bord, 200 lignes et 5 s au plus.
  - Une source de module se lit par l'index de l'arsenal (`ArsenalStore.source`, sans verrou), jamais par un chemin
    venu de l'adresse. Un document se lit par `DocKey.parse`.
  - Filtres liés en paramètres (`Where`, `choice`), 400 pour une valeur inconnue. Une lecture par page, deux tours
    successifs pour la page d'un joueur (sa fiche passe par `inTurn`). `textarea` est échappé.
  - Les instantanés lus depuis un fil du tableau de bord (`guards.activeOn`, `BlockRestorer.byPlayer`,
    `forge.arsenal.list()`, la veille du style) sont documentés « Any thread ».
- **M6-FIX-2 : le tour de lecture.**
  - Toutes les courses ont été relues : expiration pendant la requête, expiration avant la connexion, requête refusée
    après `close`. `end` n'est appelé que si `start` a dit oui : jamais deux `release`.
  - Une requête dont l'appelant est parti pendant l'attente de sa connexion ne démarre pas (`result.isDone` après le
    `poll`).
  - Les deux appelants directs d'`inTurn` (`SqlPage`, `PlayersPage`) ne lancent qu'une lecture (voir le constat 3
    pour la suite).
- **M6-FIX-2 : le code de connexion.**
  - 128 bits de `SecureRandom`, gardés sous forme d'empreinte SHA-256 et comparés en temps constant.
  - Consommé sous un verrou : deux requêtes avec le même code ne passent jamais toutes les deux.
  - 10 min sur l'horloge du runtime, 16 codes au plus, et `reset` les annule.
  - Il ne fuit pas :
    - le mod ne le journalise jamais, et un refus ne journalise que la première racine ;
    - la ligne `DEBUG` du serveur HTTP du JDK et l'historique du navigateur ne gardent qu'un code mort une fois
      servi ;
    - `Referer` : le lien s'ouvre depuis le client du jeu, le 303 porte `Referrer-Policy: no-referrer` (comme toute
      réponse), et la CSP n'autorise aucune ressource extérieure.
  - Le jeton de session n'apparaît dans aucune adresse.
- **M6-FIX-2 : `Html` et le tableau de bord optionnel.**
  - Une URL d'attribut refuse `\`, les caractères de contrôle, les espaces et DEL. `formaction` est vérifié, les noms
    d'attributs sont comparés sans casse, et `<meta>` n'est écrit que par `head`.
  - Désactivé, le tableau de bord n'ouvre ni port ni fil, et le Journal n'a que deux connexions de lecture. Les deux
    réglages viennent de la même lecture de la configuration, au démarrage comme au `/narrator restart`.
- **M6-FIX-7/8.**
  - `Prices.costOf` et `Cost.text` sont le seul calcul et le seul libellé d'un coût.
  - `TraceQueries.usage` se fait en une requête. La latence moyenne (somme sur compte) vaut l'ancien `avg`, puisque
    `llm_calls.latency_ms` est `NOT NULL`.
  - `TraceCommands` n'importe plus rien de `web`.
  - Les heures d'une transcription sont au fuseau du tableau de bord. `/narrator style` est juste quand tout est
    `plain`.
- **M6-16 : les prompts.**
  - `director.md` fait 34 995 caractères sur 36 000. Des lignes 1 à 54, seule la ligne 13 change (D9). Les seize noms
    français correspondent un à un à `ComicRegister.VOICE`.
  - Chaque outil cité est branché (`PromptToolMentionsTest`, 48 outils). « Le Scénariste » n'est jamais entre accents
    graves, et aucune section du briefing n'est nommée entre chevrons.
  - Le Scénariste a sept outils, sans `sql`. Rien ne lui en parle plus : ni son prompt, ni `ProgrammeInputs`, ni les
    descriptions de ses outils (vérifié par recherche).
  - « Fin. » ne touche ni les assistants, ni la greffe, ni l'issue d'un tour :
    - `finalText` ne sert qu'à `Workers` et à `MemoryAgents`, qui ont leurs propres prompts ;
    - `ExchangeLog` ne lit que les répliques et les appels d'outils, jamais le texte libre du Directeur ;
    - l'issue `acted` ou `idle` d'un tour vient des outils.
    - Un « Fin. » de plus par tour dans les transcriptions et sur le tableau de bord : c'est voulu (passation, point
      2 pour le propriétaire).
  - `dreamer.md` décrit `<diversity>` comme `StyleReport.diversityBlock` l'écrit. `analyst.md` cite deux vues qui
    existent (`views.sql`, `SchemaDoc`).
  - Le ton tient. « Un dieu ne répare rien, il décrète » et « jamais pour meubler » sont dans la voix, et rien n'est
    sombre.

## Décisions à prendre

- **D8 — La consigne « Fin. »** (constat 1). **Recommandation : la garder**, puisque le gain est mesuré (0 compte rendu
  sur 9, −2,1 s par tour, environ 230 tokens de sortie de moins), **et ajouter le garde-fou** : un résumé de moins
  d'environ 100 caractères n'en est pas un, plus une phrase dans `chapter_summary.md`. Coût : une dizaine de lignes et
  un test. Sinon, revenir à « une ligne au plus » et perdre le gain.
- **D9 — « un début de chapitre : laisse passer »** (constat 2). **Recommandation : le retirer**, puisque
  « un battement sans matière » couvre déjà le cas visé. Le propriétaire relit cette section (point 3 de la passation
  de M6-16). Sinon, le préciser : « un chapitre qui s'ouvre n'appelle rien par lui-même ; un joueur qui arrive ou te
  parle, si » (environ 80 caractères).
- **D10 — Les lectures successives d'un tour du tableau de bord** (constat 3). **Recommandation : corriger
  `DuckJournal.read`**, qui rend la connexion avant de réveiller l'appelant (environ 6 lignes, utiles à tous les
  appelants), avec un message de refus à lui. Sinon, l'écrire dans le KDoc d'`inTurn` (« une lecture par tour ») et
  donner au refus un message juste.
- **D11 — Une borne horaire pour les commandes au Scénariste ?** **Recommandation : non, pas maintenant.** Le prompt
  les réserve aux grands moments, chacune coûte 0,15 à 0,30 $, et rien ne borne non plus l'Analyste. M6-18 compte les
  commandes par soirée (T5 et T6). Au-delà de deux par soirée, un repos d'une heure entre deux commandes dans
  `Workers.submit`, avec sa raison.

## Lots de corrections proposés

Chaque lot possède ses fichiers ; **aucun fichier n'est commun à deux lots**.

| Lot | Constats / décisions | Fichiers | Touche un fichier en cours ? | Critère |
|---|---|---|---|---|
| **M6-FIX-9 — la fin d'un tour, le silence et le Scénariste** | 1, 2, 4, 5, 7, 8 (le résumé « Fin. », l'idée du battement non rendue, le Journal tombé) ; D8, D9 | `director.md`, `storyteller.md`, `chapter_summary.md`, `director/Chapters.kt`, `agent/Workers.kt`, `memory/MemoryService.kt` (KDoc), `docs/design/M6.md`, `docs/MEMOIRE.md` ; tests `ChapterScenariosTest`, `WorkersTest`, `DirectorStorytellerScenariosTest` | **Non.** Aucun fichier de M6-17 (GameTests d'assemblage, `/narrator status`, lignes `ready`, `StylePage.kt`, `TraceCommands.kt`) ni de FORGE-ABANDON-FIX ; `MemoryService.kt` est libre depuis la fusion de M6-FIX-4/5 | une demande de résumé répondue « Fin. » ferme le chapitre sans résumé, avec un `WARN` ; le Scénariste n'est plus proposé quand le Journal est tombé ; `director.md` sous 36 000 avec 1 000 de marge ; `PromptsTest` et `PromptToolMentionsTest` verts ; build vert |
| **M6-FIX-10 — le tableau de bord** | 3, 6, 8 (le dossier temporaire, les lectures successives) ; D10 | `journal/DuckJournal.kt`, `journal/ReadWatch.kt` (KDoc), `web/Routes.kt` (KDoc), `web/pages/MechanicsPage.kt`, `web/pages/ForgePage.kt`, `web/pages/PageParts.kt`, `web/pages/DecisionsPage.kt`, `web/DashboardQueries.kt` ; tests `DashboardServerTest`, `DuckJournalTest`, `DashboardPagesTest`, `DashboardPrivacyTest`, `TracePagesTest` | **Non.** `DuckJournal.kt` est libre depuis la fusion de M6-FIX-6 ; de `web/pages/`, M6-17 ne possède que `StylePage.kt`. **À vérifier** : si FLAKY-6 touche `DashboardServerTest`, mettre le test des lectures successives dans `DuckJournalTest`, ou fusionner FLAKY-6 d'abord | deux lectures successives d'un tour passent (100 sur 100), la lecture parallèle reste refusée avec un message à elle ; plus d'onglet `/mechanics/decisions`, un seul `Where`, un seul `DecisionRow` ; build vert |

**Ordre conseillé.** Les deux lots peuvent partir en parallèle, et aussi pendant M6-17 : aucun ne touche les fichiers de
M6-17 ni ceux de FORGE-ABANDON-FIX. M6-FIX-9 passe en premier s'il faut choisir : il porte les deux constats qui
touchent le jeu, et ses changements de `director.md` sont à faire relire au propriétaire avec ceux de M6-16. Aucun des
deux ne demande de rejeu : les retouches du prompt sont courtes, et M6-18 (T6) les vérifie sur une soirée.

**Hors lots.**
- **Pour M6-17** (déjà dans les passations, rappelé ici) :
  - la page Style affiche `top register none` quand tout est `plain` : `Diversity.plainShare` est là pour le dire
    (passation de M6-FIX-7-8) ;
  - le tableau de bord est désactivé par défaut, et un lien ne sert qu'une fois (passation de M6-FIX-2).
- **Pour M6-18** :
  - T6 : compter les résumés de chapitre de moins de 100 caractères (constat 1, s'il n'est pas corrigé avant), et la
    part des ouvertures de chapitre sur une arrivée qui restent muettes (constat 2) ;
  - T5 et T6 : le nombre de commandes au Scénariste par soirée (D11).

## Décisions du superviseur (21/10/2026)

- **D8 — oui.** On garde « Fin. » en fin de tour, avec le garde-fou : un résumé de chapitre de moins d'environ
  100 caractères n'en est pas un (le moteur le refuse et le redemande ou garde le précédent, sans le prendre pour un
  résumé), et une phrase dans `chapter_summary.md`. Lot M6-FIX-9.
- **D9 — oui.** « Un début de chapitre » sort de la liste des moments où se taire (`director.md:206`) : un chapitre
  s'ouvre presque toujours sur une arrivée. Le propriétaire relira la section « Ne rien faire ». Lot M6-FIX-9.
- **D10 — oui.** `DuckJournal.read` rend sa connexion avant de réveiller l'appelant. Lot M6-FIX-10.
- **D11 — non**, pas de limite horaire aux commandes au Scénariste pour l'instant : M6-18 les compte par soirée, et on
  décidera sur ces chiffres.
- Les deux lots partent en parallèle ; leurs fichiers sont disjoints de M6-17 et de FORGE-ABANDON-FIX.
