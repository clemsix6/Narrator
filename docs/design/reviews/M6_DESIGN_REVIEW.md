# Relecture du design de M6 (Finitions)

Relecture du 20/10/2026 de `docs/design/M6.md` (premier jet de l'architecte, fusionné en `ff21225`), avant que les 18
tâches en dépendent. Elle confronte le design au **code réel de `main`** (`ff21225` : M5-05 fusionné, M5 en cours), aux
passations (`M4-24`, `M4-POST`, `REFLEX-HAIKU`, `M3-19`, `M5-05`, `M5-12`), à `M5.md` et aux vraies API (javap de
Fabric API 26.3, liste des modules du JDK 25). Les essais jetables sont dans `.research/spikes/m6/` (corpus
`lines.txt` des répliques de la recette de M4, relu pour les amorces).

Comme pour M4 et M5, la relecture **corrige directement le design** : chaque constat bloquant ou important dit ce qui a
changé dans `M6.md` (et `ARCHITECTURE.md`) ; l'encart de tête de `M6.md` résume les changements. Les décisions du
superviseur sur le §18.2 sont intégrées.

Chaque constat donne sa gravité, l'endroit, un scénario, la preuve (**vérifié** : lu dans le code, javap ou la
documentation de l'API ; **raisonné** sinon) et le changement fait. Gravités, comme pour M5 : **bloquant** (un
interdit du §17 tombe, le serveur gèle, ou le découpage casse le build de plusieurs tâches), **important**, **mineur**,
**finition**.

**Bilan** : 17 constats — **2 bloquants, 6 importants, 8 mineurs, 1 finition**. Deux changements de périmètre :
**`/narrator restart` passe au repli « serveur vide »** (D7, constat 1 ; confirmé par le superviseur) et **la tâche
M6-15 est annulée** (ses deux points sont faits par M4-LEFTOVERS, fusionné en `ee0abdd`, constat 8). Dix-sept tâches
restent. La branche de la relecture a fusionné `main` après `ee0abdd`.

**Ce qui tient.** Les fondations sont justes et vérifiées dans le code : `ReflexClient.label(system, request, schema)`
est bien générique (`reflex/ReflexClient.kt:126`) ; `NarratorSpoke` porte `turnId` et `operator`
(`event/GameEvent.kt:676`), et l'identifiant de l'enveloppe est celui de la ligne `chat` (`journal/JournalMapping.kt:468`),
donc `line_labels.event_id` est atteignable ; le bus a des abonnés asynchrones à file bornée qui comptent leurs pertes
(`event/EventBus.kt:106`) ; `Caller.role` existe pour réserver le refus d'amorce au Directeur (`tool/Tool.kt:150`) ;
`SqlAccess.MODEL` refuse `private_messages` et les fichiers de DuckDB (`journal/SqlRunner.kt:55`) ; le rôle
`storyteller` est configuré depuis M0 (`config/NarratorConfig.kt:67`, Opus 5.5, `medium`) ; `Briefing.MAX_CHARS` vaut
bien 72 400 (`director/Briefing.kt:491`) ; `director.md` fait exactement **33 986** caractères et ses lignes 1 à 54,
**7 914** (mesuré) ; les tailles des sections du §10 sont exactes au caractère près ; le budget de **36 000** après
M5-20 est bien prévu par `M5.md` §9.4 (« budget de `PromptsTest` porté de 34 000 à 36 000 ») et par la tâche M5-20
(« La Forge » ≤ 1 800 caractères) ; aucune migration SQL n'est prévue par M5 (`M5.md`, « aucune migration »), donc `V4`
est libre ; le module `jdk.httpserver` est dans le JDK 25 installé (`java --list-modules`) et se résout pour une
application lancée sur le classpath ; le coût de la relecture et de l'étape « programme » est cohérent avec les mesures
réelles (`REFLEX-HAIKU` : 0,0001 à 0,0005 $ l'appel ; `M3-19` §21.4 : 0,08 à 0,13 $ par étape du rêve à l'effort
`high`). Les constats ci-dessous sont les écarts et les trous.

## Constats

1. **BLOQUANT (§17, D7) : `/narrator restart` avec des joueurs en ligne coupe les garde-fous pendant l'échange, et ses
   arrivées fictives touchent une dizaine de fichiers que personne ne possède.**
   - Où : `M6.md` §9 (étape 1 : « `NarratorRuntime.current` devient nul (les crochets ne font plus rien pendant
     l'échange) » ; étape 3 : arrivées fictives « par le chemin d'une vraie connexion », « pas annoncées comme des
     `JOIN` »), M6-13.
   - Scénario 1, le §17 tombe : pendant l'étape 2 (fermeture et réouverture du Journal, préparation de la mémoire :
     1 à 3 s d'ordinaire, jusqu'à ~35 s au pire selon les bornes du design), Bartok subit un châtiment `guests`. Le coup
     fatal d'une créature du châtiment passe par `ALLOW_DEATH` ; sans runtime, le crochet efface la marque et **laisse
     mourir** (`guard/GuardModule.kt:41-50` : `running()` nul → `DeathGuard.clearMarkWithoutGuards(entity); true`).
     Bartok meurt pour de bon et **perd son inventaire**, alors que le §17.4 et `M4-POST` garantissent qu'un châtiment
     ne tue jamais et qu'un mort du Narrateur garde tout. De même, un joueur protégé (`/narrator protect`) ou en grâce
     n'est plus épargné : `CreatureShield.spares` lit le runtime (`guard/CreatureShield.kt:78`).
   - Scénario 2, le contrat des arrivées fictives : les consommateurs de `PlayerJoined`/`PlayerLeft` sont la saillance
     (`director/Salience.kt:313`, réveil `SOON`), les règles au déclencheur `JOIN` (`mechanics/rule/RuleContext.kt:118`
     : une loi « à chaque arrivée… » se déclencherait), les faits des modules (`forge/sdk/Facts.kt:103`), l'horloge de
     jeu (`memory/DuckPlayClock.kt:79`), le Journal (`journal/JournalMapping.kt:220`) ; et, hors du bus, les crochets
     Fabric `JOIN` des garde-fous (`guard/GuardModule.kt:77`, `GuardsImpl.onJoin` rend les objets dus) et du moteur
     (`mechanics/MechanicsHooks.kt:60` : barres de boss, `Bets.onJoin` qui règle les mises en attente du joueur). Les
     sessions vivent dans `observer/FabricObserver.kt` (`joined`/`left`, l. 262-289), **pas** dans
     `ObserverHooks.kt` que M6-13 possède. Distinguer une arrivée fictive demande un drapeau sur `PlayerJoined` et
     `LeaveReason` (`event/GameEvent.kt:525-557`) lu par chacun de ces consommateurs : du code de M1 à M5 qu'aucune
     tâche de M6 ne possède, et un invariant (« aucun joueur en ligne quand le runtime se câble ») que M4 et M5 n'ont
     jamais testé.
   - Preuve : **vérifié** (lecture du code cité).
   - Changement : **repli « serveur vide »**, que l'architecte prévoyait déjà (D7). `/narrator restart` refuse quand un
     joueur est en ligne (`cannot restart: 2 players online (Bartok, Mimosa); restart when the server is empty, or
     restart the server`). Pendant l'échange, **les connexions sont retenues** par Fabric (`ServerLoginConnectionEvents.
     QUERY_START` et `ServerLoginNetworking.LoginSynchronizer.waitFor(Future)`, **vérifiés à javap** sur Fabric API
     26.3) jusqu'à la fin du câblage : personne n'entre dans un monde sans Narrateur. Plus d'arrivée ni de départ
     fictif, plus de drapeau sur les événements, plus de trou dans les garde-fous (aucun joueur à protéger). Le besoin
     de la décision E de M4 reste couvert : un Journal tenu au démarrage se voit à la ligne `ready`, avant toute
     connexion. M6-13 possède `RestartSequence.kt`, `NarratorRuntime.kt`, `ShutdownSequence.kt`, `MemoryModule.kt`,
     `AdminCommands.kt` et une ligne de `Narrator2.kt` (le crochet de connexion, enregistré une fois) ; ni
     `ObserverHooks.kt` ni `FabricObserver.kt`. `RestartGameTests` vérifie le refus avec un joueur factice et le
     redémarrage sans joueur. `M6.md` §2 (décision 10), §9, §13, §15, §16 et `ARCHITECTURE.md` §13 et §16 sont mis à
     jour. Un redémarrage joueurs en ligne pourra revenir plus tard, avec ses propres tâches.

2. **BLOQUANT (découpage) : des tâches parallèles se partagent des fichiers, et M6-06 casse en vague A des tests
   qu'elle ne possède pas.**
   - Où : `M6.md` §16.
   - Recouvrements dans une même vague :
     - `ToolCatalogTest.kt` : M6-05 (la part d'`inspire`) **et** M6-06 (vague A). Le test vérifie les outils de chaque
       rôle (« the roles without tools nothing », `tool/ToolCatalogTest.kt:80`) : remplir la famille du Scénariste
       change ce que voit le rôle.
     - `PromptsTest.kt` et `PromptToolMentionsTest.kt` : M6-07 **et** M6-14, toutes deux en vague B.
   - Casses hors propriété : M6-06 retire au Rêveur le `remember arc`, ce qui casse `DreamReplayTest:182` (« an arc
     planned », possédé par M6-07, vague B), `DreamInputsTest:142,145,218`, `DreamerToolsTest:70-85` et
     `MemoryToolsTest:306-312` (possédés par personne). La règle est aussi dans l'outil, pas seulement dans le magasin
     (`tool/memory/MemoryTools.kt:189`, `DREAMER_KINDS` l. 240). M6-03 change les amorces de `Tics` (deux jetons
     normalisés au lieu de trois mots) et casse `BriefingMemoryTest:47` (« « Ah Bartok encore » ×4 »).
   - Fichiers non possédés : `memory/MemoryService.kt` (implémente `Memory.openIdeas`), `tool/memory/MemoryTools.kt`
     (auteur et sortes du Scénariste, constat 3), `tool/memory/DreamerTools.kt` (la description de `write_grimoire`
     cite les sept sections, l. 524-527), `reflex/ClaudeReflexes.kt` (l'écriture des lignes `llm_calls`, constat 9),
     une classe de GameTest pour M6-10 (constat 16).
   - Preuve : **vérifié** (`grep` des tests cités).
   - Changement (`M6.md` §16) :
     - **M6-01 déclare entièrement** `inspire`, `propose_idea` et `drop_idea` (nom, description, arguments, effet,
       disponibilité), avec un `run` qui répond `FAILED (not wired yet)` ; M6-05 et M6-06 n'écrivent que les `run`.
       `ToolCatalogTest` passe de « 01 → 05 → 16 » à « 01 → 16 ».
     - `PromptsTest` : M6-01 seulement (les deux nouveaux budgets ; celui de `director.md` reste à 36 000).
       `PromptToolMentionsTest` : M6-07 puis M6-16. **M6-14 ne possède que `director.md`** : le test vérifie qu'il ne
       perd aucune mention d'outil.
     - **Le retrait du `remember arc` du Rêveur passe de M6-06 à M6-07**, avec `dreamer.md`, `DreamerTools.kt`,
       `MemoryTools.kt` (la ligne du Rêveur) et les tests `DreamReplayTest`, `DreamInputsTest`, `DreamerToolsTest`,
       `MemoryToolsTest`. M6-06 ne fait qu'**ajouter** les droits du Scénariste.
     - Les fichiers non possédés reçoivent un propriétaire : `MemoryService.kt` (01 → 06), `MemoryTools.kt` (06 → 07),
       `DreamerTools.kt` (07), `BriefingMemoryTest` (03 → 08), `ClaudeReflexes.kt` (02).

3. **IMPORTANT (contrats, mémoire) : les écritures du Scénariste seraient signées par le Rêveur, ou refusées.**
   - Où : `M6.md` §6.3 (« auteur `Author(STORYTELLER, run)` »), §6.5, §12.3 (`Source` gagne `STORYTELLER`).
   - Scénario : l'étape « programme » est une conversation d'un rêve. Ses outils trouvent l'auteur par
     `RunContext.author`, qui donne `Source.DREAMER` à **toute** conversation d'un rêve (`memory/Memory.kt:75-76`) :
     chaque `propose_idea` et chaque `remember arc` serait signé « dreamer », et refusé une fois le `remember arc` retiré
     au Rêveur. Une tâche du Scénariste (`delegate`) n'a pas de `RunContext` : `ToolContext.author()` lève « the
     storyteller does not write in the memory » (`tool/memory/MemoryTools.kt:466-476`). `MemoryAgents.run` refuse tout
     rôle autre que le Chroniqueur et le Rêveur (`memory/MemoryAgents.kt:83`). Côté magasin, une source inconnue passe
     sans contrôle : `canonSource` a une branche `else -> Unit` (`memory/DuckMemoryStore.kt:153-164`), donc un
     Scénariste oublié par M6-06 pourrait écrire une promesse ou une loi sans citation ; `memoryKind` ne restreint que le
     Rêveur (l. 140-144) ; `MODELS` ne contient pas le Scénariste, donc ses citations ne sont pas bornées (l. 1012).
   - Preuve : **vérifié**.
   - Changement (`M6.md` §6.3, §6.5, §12.3, §16) : `RunContext` porte le rôle de la conversation et `author()` en
     déduit la source (M6-01, `Memory.kt`) ; `MemoryAgents.run` accepte `STORYTELLER` (but `PROGRAMME`, M6-01) ;
     `ToolContext.author()` gagne `Role.STORYTELLER → Source.STORYTELLER` et `remember` accepte du Scénariste
     `arc` (planned) et `lore` seulement (M6-06, `MemoryTools.kt`) ; le magasin traite `STORYTELLER` explicitement dans
     `canonSource` (arc planned, lore, sans citation), `memoryKind` (aucun souvenir `M-`) et `MODELS` (M6-06).
     `IdeasStoreTest` vérifie qu'une promesse du Scénariste est refusée.

4. **IMPORTANT (contrats, outils) : le rôle `storyteller` de `delegate` ne peut pas être caché, et le Scénariste ne
   verrait jamais `inspire`.**
   - Où : `M6.md` §12.2 (`ToolEnv.storytellerAvailable` « montre `inspire` et le rôle `storyteller` de `delegate` »),
     §16.1 (« cachés tant que… »).
   - Scénario : les rôles de `delegate` sont un `enum` statique (`WorkerRole`, `tool/task/TaskTools.kt:31-38`) décrit
     par un `@Doc` statique (l. 43-49) ; un outil est statique par construction (« description English and static »,
     `tool/Tool.kt:191`). Le schéma ne dépend donc pas de `ToolEnv` : dès que `STORYTELLER` entre dans l'`enum`, il est
     montré, mémoire ou pas. `ARCHITECTURE.md` §7.3 promet pourtant que l'`enum` « ne contient que les rôles réellement
     câblés ». Par ailleurs, `Workers` et `MemoryAgents` construisent le `ToolEnv` de leurs conversations sans le
     drapeau (`agent/Workers.kt:174`, `memory/MemoryAgents.kt:90`) : `inspire`, réglé sur `storytellerAvailable`, ne
     serait montré ni à la tâche du Scénariste ni à l'étape « programme », alors que le §6.5 le lui donne.
   - Preuve : **vérifié**.
   - Changement (`M6.md` §6.4, §12.2, §16 ; `ARCHITECTURE.md` §7.3) : `storytellerAvailable` ne règle plus que
     `inspire` ; `Chapters` le pose à `Role.STORYTELLER in services.workers.roles` (M6-08), `Workers` et `MemoryAgents`
     à vrai pour une conversation du Scénariste. `WorkerRole.STORYTELLER` entre dans l'`enum` avec M6-08 (schéma
     statique, comme tout outil) ; `Workers.roles` ne le contient que si la mémoire tourne, et `submit` refuse sinon
     (`no storyteller can take a task: the memory is unavailable`), comme il refuse déjà un rôle non câblé
     (`agent/Workers.kt:82-85`). La phrase d'`ARCHITECTURE.md` §7.3 devient : l'`enum` ne contient que les rôles câblés
     dans le code ; un rôle momentanément indisponible est refusé avec sa raison.

5. **IMPORTANT (refus d'amorce) : une boucle de refus peut clore le chapitre, et la fenêtre mêle titres et répliques.**
   - Où : `M6.md` §3.4, §3.5, D3.
   - Scénario 1, la boucle : chaque refus coûte une requête du Directeur (0,015 à 0,03 $, `M4-24`) et une étape du
     tour. Si la reformulation retombe sur une autre amorce au repos (la liste peut en compter plusieurs), le tour
     s'allonge ; à `director.maxStepsPerTurn` (12), le tour atteint sa limite dure et **le chapitre se ferme**
     (`director/Director.kt:773`, `RUNAWAY_TURN`), soit un nouveau briefing écrit en cache (0,15 à 0,35 $, `M4-24`).
   - Scénario 2, les faux positifs : `show_title` et `actionbar` publient aussi un `NarratorSpoke` du canal `narrator`
     avec le tour (`tool/speech/SpeechTools.kt:411-413`) et la table `chat` ne distingue pas un titre d'une réplique
     (`V1__journal.sql:57`). Des titres comme « BARTOK vs MIMOSA », « GASPARD vs FIRMIN » (corpus de l'essai) font
     l'amorce `<nom> vs` et peuvent mettre au repos l'amorce d'une réplique. Côté formules rituelles, le corpus montre
     surtout des tics (`<n> secondes` ×5 à chaque fin de défi) ; une formule de loi (« Décret divin » ×2) ou de défi
     (« Défi : » ×2), utiles à la lisibilité, n'atteignent pas 4 occurrences dans 200 répliques sur l'essai, mais
     pourraient sur une longue semaine de jeu.
   - Preuve : **vérifié** (code) ; **raisonné** (corpus `.research/spikes/m6/lines.txt`).
   - Changement (`M6.md` §3.4, §3.5, §18.1) : **un refus au plus par tour** (le `say` suivant du même tour n'est plus
     contrôlé : une formule voulue passe au second essai, et le tour ne peut pas tourner en rond) ; le refus cite
     **toutes** les amorces au repos (cinq au plus) ; les amorces ne se comptent que sur les répliques d'au moins 25
     caractères (ce qui écarte titres et lignes de la barre d'action, comme l'essai) ; décision `style opener_refused`
     avec le tour. La recette (T6) compte les refus par heure et vérifie qu'aucune formule de loi ou de défi n'est
     refusée plus d'une fois par soirée ; sinon, le seuil passe à 5.

6. **IMPORTANT (rejeu et juge) : un seul appel par situation ne valide pas un changement de `director.md`, et un outil
   `grade` sans `tool_choice` forcé n'est pas fiable.**
   - Où : `M6.md` §4.4, §4.5.
   - Scénario 1 : dans un premier message de chapitre, le Directeur commence souvent par lire (`recall`,
     `inspect_player`, `player_profile`, `sql`) avant d'agir et de parler. « Un seul appel, aucun outil exécuté » :
     l'échantillon s'arrête sur une lecture, sans réplique ni acte ; les contrôles structurels (« agir avant d'annoncer
     un châtiment », `end_challenge` après `JUDGE`) ne se mesurent pas, et le verdict B ≥ A porte sur une poignée
     d'échantillons — or c'est lui qui autorise le compactage de M6-14.
   - Scénario 2 : Opus 5.5 refuse `tool_choice` `any`/`tool` (erreur 400, `CLAUDE.md`) ; avec `auto`, rien ne garantit
     l'appel de `grade`. Le juge E4 de M3-19 demandait un tableau JSON dans le texte
     (`live/LiveMemoryCycleTest.kt:444-451`) ; la sortie structurée (`output_config.format`) existe pour Opus 5.5 et est
     déjà employée par les réflexes.
   - Preuve : **vérifié** (documentation de l'API, `CLAUDE.md`, code du juge E4) ; **raisonné** pour la fréquence des
     lectures.
   - Changement (`M6.md` §4.4, §4.5, §15.4) : le rejeu va **jusqu'à 4 requêtes** par situation ; une lecture reçoit le
     résultat figé de la situation (ou `ok: nothing more to read in this replay`), un acte reçoit `ok` sans être
     exécuté ; les échantillons sans réplique sont comptés et rapportés. Le juge rend sa note par la sortie structurée
     (`output_config.format`), sans outil. Budget de T3 : ≈ 5 à 7 $.

7. **IMPORTANT (tableau de bord, Journal) : le tableau de bord partagerait les deux connexions de lecture du
   Directeur.**
   - Où : `M6.md` §8.3 (« une lecture à la fois, 5 s », page SQL « 10 s »), §8.5.
   - Scénario : le Journal n'a que **deux** connexions de lecture et deux fils DuckDB (`journal/DuckDatabase.kt:51-53`),
     partagés par le briefing (10 s), les outils du Directeur (5 s), la mémoire, la veille du style, `inspire`. Le
     propriétaire laisse `/style` ou `/costs` se rafraîchir toutes les 15 s, ou lance une requête lourde sur `/sql`
     (10 s) : une des deux connexions est prise, un chapitre qui s'ouvre attend son briefing ou voit `recall` échouer.
   - Preuve : **vérifié**.
   - Changement (`M6.md` §8.3, §8.5) : le Journal ouvre **une troisième connexion de lecture**
     (`DuckDatabase.Options.readers` = 3, M6-10) et le sémaphore du tableau de bord (une lecture à la fois) laisse les
     deux autres au reste ; pages 5 s, page SQL aux limites du `sql` du Directeur (`journal.sqlTimeoutSeconds` = 5 s
     au lieu de 10, 200 lignes au plus, `journal/SqlRunner.kt:45-47`).

8. **IMPORTANT (découpage, contexte du superviseur) : M6-15 est faite par M4-LEFTOVERS.**
   - Où : `M6.md` §11 (deux lignes), §15.1, §15.2, M6-15.
   - M4-LEFTOVERS est fusionné dans `main` (`ee0abdd`, `handoff/M4-LEFTOVERS.md`). Il fait les deux points de M6-15,
     **autrement que ne le prévoyait `M6.md`** :
     - les créatures hostiles de `spawn` portent la marque `NarratorMob.SPAWN`, ne visent et ne blessent que leur
       joueur, et leur coup fatal devient un sauvetage ; les créatures d'un module en sont exclues (M5 §4.5) ;
     - les mises et les dus **restent** dans `activeOn` (« Your active effects ») au lieu d'en sortir, mais sous leur
       but (`Escrow.active` : « staked on C-50: a bet that Mimosa wins », « owed to them… ») et sans échéance
       (`ActiveEffect.until` devient `Long?`).
     Il corrige aussi la description de `glowing` dans `apply_effect` et un GameTest instable. Il ne reste de « Pour
     la suite » de `M4-POST` qu'une question de règle (une mort d'une autre cause pendant un châtiment du catalogue
     reste comptée), qui n'est pas une tâche tant que le propriétaire ne l'a pas tranchée.
   - Changement : **M6-15 est annulée** (le numéro reste réservé) ; le §11 et le bloc de M6-15 décrivent ce que
     M4-LEFTOVERS a fait, et `M6.md` ne cite plus l'ancien comportement prévu (les mises sorties d'`activeOn`). Une
     conséquence pour M6 : le tableau de bord (M6-10, M6-12) rend un `ActiveEffect` sans rien après le but quand
     `until` est nul, comme la situation et le briefing.

9. **MINEUR (relecture Haiku) : disjoncteur partagé, ligne `llm_calls`, schéma, coût.**
   - Le disjoncteur de `ReflexClient` (5 échecs de suite → 5 min, `reflex/ReflexClient.kt:80`) est commun au tri du
     chat et à la relecture : une relecture qui échoue systématiquement (prompt refusé, schéma rejeté) couperait aussi
     le tri du chat, qui décide des réveils. Le risque est faible (les appels s'entrelacent), mais la relecture est le
     moins important des deux : le relecteur se met lui-même en pause après 3 échecs de suite (5 min) avant d'atteindre
     le disjoncteur commun.
   - La ligne `llm_calls` n'est pas écrite par `ReflexClient` mais par `ClaudeReflexes.record`, privé
     (`reflex/ClaudeReflexes.kt:101-136`) : M6-02 l'en sort (une fonction partagée du paquet `reflex`) plutôt que de la
     recopier.
   - Les sorties structurées n'acceptent ni bornes numériques ni contraintes de tableau complexes (documentation de
     l'API) : `0..1` et « ≤ 3 béquilles » sont vérifiés par le code (valeur hors bornes → étiquettes rejetées, comme
     `REFLEX-HAIKU` ; béquilles coupées à trois).
   - Coût : `REFLEX-HAIKU` mesure 0,0001 à 0,0005 $ l'appel selon que LLMGW relit ou réécrit le préfixe ; à 20 à 40
     répliques par heure, **0,005 à 0,025 $ de l'heure** (« moins de 0,02 $ » était l'hypothèse basse).
   - Changement : `M6.md` §3.3.

10. **MINEUR (tableau de bord, sécurité et robustesse).**
    - Lancé depuis la console, `/narrator dashboard` écrit le lien et son jeton dans `logs/latest.log` (la console de
      Minecraft journalise les réponses des commandes) : « jamais écrit sur disque » ne vaut que pour le mod. Le jeton
      meurt à chaque démarrage et `reset` existe ; le design le dit et conseille `reset` après un usage depuis la
      console.
    - `style-src 'self'` interdit les attributs `style="…"` : les barres se dessinent en SVG (attributs `width`,
      `fill`), jamais en style en ligne ; `NoScriptTest` vérifie aussi l'absence de `style=`.
    - Échappement des attributs (guillemets) et encodage des segments d'URL (`/players/<nom>`, `/memory/<ref>`).
    - En-têtes en plus : `Cache-Control: no-store` (les pages contiennent le chat), `Referrer-Policy: no-referrer`,
      `X-Content-Type-Options: nosniff` ; comparaison du jeton en temps constant (`MessageDigest.isEqual`).
    - Un cookie n'est pas isolé par port : un autre service sur `127.0.0.1` pourrait le recevoir si le navigateur le
      visite. Accepté pour un outil local ; dit dans la doc.
    - `/narrator restart` reconstruit le tableau de bord : nouveau jeton, il faut se reconnecter. Accepté.
    - Changement : `M6.md` §8.3, §8.6.

11. **MINEUR (contrats) : `TraceSink.lineLabels(r: LineLabels)` ferait dépendre `trace` de `style`.**
    - Le motif existant est un enregistrement de `trace/TraceRecords.kt` (`ChatSignalsRecord`, `trace/TraceSink.kt`).
    - Changement (`M6.md` §12.1, §12.3) : `LineLabelsRecord` dans `TraceRecords.kt`, écrit par `TraceSink.lineLabels` ;
      `StyleWatch.labeled` reçoit `LineLabels` (le type du style), que le relecteur convertit.

12. **MINEUR (chiffres du design).**
    - Les prix sont recopiés dans **cinq** fichiers, pas trois : `LiveDirectorTest.kt:232`, `LiveMemoryHarness.kt:151`
      (`LivePrices`), `LiveReflexEvalTest.kt:205`, `LiveDirectorGameTests.kt:93-96`, `LiveMechanicsGameTests.kt:151-152`
      (sans compter ceux de M5-22). M6-18 les remplace tous.
    - §10 : les cibles du tableau libèrent **≈ 3 100** caractères (1 200 + 808 + 460 + 463 + 174), pas 2 600. Et le
      point de départ de M6-14 est le fichier réel après M5-20 (M5-20 cite encore 33 763, quand le fichier en fait
      33 986 depuis `M4-POST`) : M6-14 mesure en commençant.
    - §6.3 : les budgets de `ProgrammeInputs` font **30 500** caractères, pas 33 000.
    - Changement : `M6.md` §0, §6.3, §10.

13. **MINEUR (superflu) : deux éléments sans consommateur.**
    - `ideas.archived` : une idée close a déjà un statut (`dropped`, `expired`) et « rien n'est supprimé » ; aucune
      hygiène n'archive les idées. Retiré.
    - La décision `style line_dropped` : le bus compte déjà les pertes par abonné et logue un `WARN` par minute
      (`event/EventBus.kt:199-218`, `SubscriberStats.dropped`). Retirée ; `/narrator style` lit le compteur du bus.
    - Changement : `M6.md` §1.1, §12.3, §14.

14. **MINEUR (idées et index) : `P-` dans l'index de la mémoire.**
    - L'index BM25 est commun (`memory/search/SearchIndex.kt`, `search_docs`) : y entrer les idées sert la règle « pas
      de redite ». Mais `recall` ne doit pas rendre d'idées (ce ne sont pas des faits) : `RefKind.IDEA` reste hors de
      `RefKind.searchable` (`memory/MemoryTypes.kt:99`). Le code qui parcourt `RefKind.entries` (lecture d'une ligne,
      journal de la mémoire, commandes) doit accepter `IDEA` ou l'écarter explicitement.
    - Changement : `M6.md` §6.2, §12.3.

15. **MINEUR (D9) : les noms des registres seraient à quatre endroits.**
    - Ils sont dans l'`enum` `ComicRegister` (§12.1), la liste de la voix de `director.md`, le prompt de la relecture
      (qui devrait les expliquer à Haiku) et la prose de `ARCHITECTURE.md` §11. La décision du superviseur demande un
      seul endroit.
    - Changement (`M6.md` §10, §12.1) : un **seul tableau** des registres dans `M6.md` §12.1 (identifiant, nom
      français, statut : existant ou à valider) ; dans le code, les identifiants anglais ne sont que dans
      `ComicRegister`, les noms français que dans la ligne de la voix de `director.md` ; `line_review.md` ne les
      recopie pas (le schéma porte l'`enum`, généré depuis `ComicRegister`, et les identifiants anglais se lisent
      seuls). Changer un nom = une ligne de `director.md` ; remplacer un registre = cette ligne et l'`enum`, dans le
      même commit (M6-16). Remarque pour le propriétaire : le registre « service client **céleste** » de la voix
      amorce peut-être la béquille « céleste » (18 % des répliques de l'essai).

16. **MINEUR (GameTests).**
    - Les GameTests au faux LLMGW coupent les réflexes (`REFLEX-HAIKU`, point 10) : `StyleAssemblyGameTests` (M6-17)
      doit les activer et scénariser à la fois le tri du chat et la relecture (requêtes sans `stream`, session
      `narrator2-reflex`).
    - Le port 0 des tests contredit la validation `dashboard.port` 1024 à 65535 (§12.5) : 0 est accepté (« any free
      port »). La configuration des GameTests et des `runServer` d'agents met `dashboard.enabled = false`, sauf
      l'assemblage : sinon les serveurs de test de plusieurs worktrees se disputent le port 8770.
    - Le GameTest de M6-10 (« `com.sun.net.httpserver` se charge dans le serveur ») demande une classe et une ligne de
      `fabric.mod.json` : `DashboardServerGameTests`, possédé par M6-10.
    - Changement : `M6.md` §12.5, §15.2, §16.

17. **FINITION (formulations).**
    - Une note de ton d'urgence `NEXT` peut arriver entre deux appels d'outils d'un même tour
      (`director/DirectorControl.kt:24`), pas seulement au réveil suivant : tant mieux, le design le dit.
    - Le contrôle d'amorce de `say` tourne dans la coroutine du Directeur, avant l'envoi au thread serveur (§1.1).
    - `ConfigStore.problems()` est `problems(config)` dans `config/NarratorConfig.kt:442`.
    - `conversations.transcript` est le chemin du `.md` (`V1__journal.sql:96`) : le `.jsonl` est à côté, même nom.
    - `InspireKind` (`challenge`, `punishment`, `favor`, `twist`, `any`) est défini dans les contrats.

## Les cinq points demandés par le superviseur

- **La relecture Haiku par réplique** : le cadre « tâche d'étiquetage » de `reflex.md` vaut pour `line_review.md`
  (LLMGW impose l'identité « Claude Code », un prompt de rôle la déclencherait) ; le contenu (des répliques comiques à
  noter) ne présente pas de risque de refus connu. Débit : 20 à 40 appels par heure de jeu actif, une file de 64,
  aucun souci. Coût : 0,005 à 0,025 $ de l'heure (constat 9). Le vrai risque est le couplage du disjoncteur, traité.
- **Le refus d'amorces par `say`** : boucle bornée à un refus par tour, faux positifs des titres écartés (constat 5).
- **`/narrator restart`** : avec des joueurs en ligne, le §17 tombe pendant l'échange et le contrat des arrivées
  fictives touche une dizaine de fichiers de M1 à M5 ; **il faut prendre le repli « serveur vide »** (constat 1). Ce qui
  ne pose pas de problème, vérifié : les crochets sont enregistrés une fois par JVM et passent par
  `NarratorRuntime.current` (`Narrator2.kt`), les garde-fous se reconstruisent depuis les pièces jointes et
  `registerLoaded` (`guard/GuardModule.kt:97-98`), le `BlockRestorer` vit dans les pièces jointes des chunks, la
  réconciliation du moteur ne lit la base que dans une coroutine (`mechanics/Reconcile.kt`), les barres de boss sont
  retirées par `stopMechanics` ; la Forge suit le chemin d'un arrêt et d'un démarrage du serveur (M5-21 le teste).
- **Le tableau de bord** : jeton, `127.0.0.1`, CSP et échappement tiennent ; page SQL en accès `MODEL` conforme au §17.7 ;
  `jdk.httpserver` présent et résolu ; lectures hors du thread serveur, mais sur une connexion à elles (constat 7) ;
  détails (constats 10 et 16).
- **Le Scénariste** : coût plausible (comparable aux étapes du Rêveur mesurées par M3-19) et mesuré par T4 ; rapport
  au Rêveur clair (une seule plume) ; la table `ideas` tient, moins une colonne (constat 13) ; le risque de doubler le
  Directeur est contenu par l'absence de cadence, une idée au plus par battement et aucun outil d'action (T6 le
  mesure) ; le vrai trou est l'auteur de ses écritures (constat 3) et sa visibilité (constat 4).
- **Le compactage de `director.md`** : la voix est protégée (M6-14 ne touche qu'à « Comment tu travailles », vérifié
  par le diff dans la passation) ; le budget de 36 000 est bien celui de M5 ; le rejeu, corrigé (constat 6), valide
  vraiment ; l'arithmétique du §10 est rectifiée (constat 12).

## Décisions du superviseur (§18.2) intégrées

- **D1** (pas de JavaScript dans le tableau de bord, CSP qui l'impose) : **acceptée**.
- **D2** (tableau de bord actif par défaut sur `127.0.0.1:8770`, jeton) : **acceptée**.
- **D3** (`say` refuse une amorce servie 4 fois dans les 200 dernières répliques) : **acceptée**, avec les garde-fous du
  constat 5 (un refus par tour, répliques d'au moins 25 caractères).
- **D4** (`Plans` quitte le Grimoire ; les récits prévus passent au Scénariste) : **acceptée** ; le retrait au Rêveur
  se fait dans M6-07 (constat 2).
- **D5** (une relecture Haiku par réplique) : **acceptée**.
- **D6** (juge Opus hors ligne seulement) : **acceptée**.
- **D7** (`/narrator restart` complet, joueurs en ligne) : acceptée par le superviseur sous réserve d'un risque
  sérieux ; **la relecture en trouve un** (constat 1 : le §17 tombe pendant l'échange). **Le superviseur confirme le
  repli « serveur vide »**, avec les connexions retenues pendant l'échange ; le propriétaire pourra rouvrir plus tard
  le cas joueurs en ligne.
- **D8** (compacter « Comment tu travailles », validé par le rejeu) : **acceptée**.
- **D9** (cinq registres de plus dans la voix) : **acceptée provisoirement** ; les noms (bande-annonce, guide
  touristique, horoscope, mode d'emploi, vente aux enchères) seront validés par le propriétaire. Ils sont tenus à un
  seul endroit par langue (constat 15).
- **D10** (le Scénariste ne commande pas de module) : **acceptée**.

## Ce que le propriétaire doit encore trancher

- Les noms des cinq registres (D9) et, s'il le veut, « service client céleste » → « service client » (constat 15).
- Plus tard, s'il le souhaite : rouvrir le redémarrage joueurs en ligne (D7 : le superviseur a retenu le repli
  « serveur vide »).
- La relecture de `storyteller.md` (M6-07), du compactage (M6-14) et des ajouts de `director.md` (M6-16).
- Le point laissé ouvert par `M4-POST` : une mort d'une autre cause pendant un châtiment du catalogue reste comptée.

## Essais et vérifications de cette relecture

- javap (`.research/jp.ps1`) : `ServerLoginConnectionEvents.QUERY_START`, `ServerLoginConnectionEvents$QueryStart.
  onLoginStart(…, LoginSynchronizer)`, `ServerLoginNetworking$LoginSynchronizer.waitFor(Future)` et
  `ClickEvent$OpenUrl(URI)` existent dans Fabric API et Minecraft 26.3.
- `java --list-modules` (JDK 25.0.1) : `jdk.httpserver`, `jdk.management` présents.
- Mesures de `director.md` par section (PowerShell, caractères UTF-8) : 33 986 au total, 7 914 pour les lignes 1 à 54,
  tailles des sections du §10 confirmées.
- Corpus `.research/spikes/m6/lines.txt` relu pour les amorces normalisées : `<n> secondes` ×5, `bonsoir mortels` ×3,
  `décret divin` ×2, titres `<nom> vs` ×2 et plus.
- Documentation de l'API (sorties structurées : ni `minimum`/`maximum`, ni contraintes de tableau complexes ; Opus 5.5
  refuse `tool_choice` `any`/`tool`).
