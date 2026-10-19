# Relecture du design de M4 (Mécaniques)

Relecture du 18/10/2026 de `docs/design/M4.md` (premier jet de l'architecte, `eefe8fd`) et des retouches de
`ARCHITECTURE.md` (`63abf85`), avant que 24 agents l'implémentent. Elle confronte le design au code de `main`
(`8b08140`), aux passations M2, M3 et M3-FIX, et aux vraies classes de Minecraft 26.3 et de Fabric API 0.161.0
(javap et bytecode, `.research/jp.ps1`).

Contrairement aux relectures de M2 et M3, qui portaient sur du code et proposaient des lots, celle-ci **corrige
directement le design** : chaque constat dit ce qui a été changé dans `M4.md` (et `ARCHITECTURE.md`). L'encart de
tête de `M4.md` résume les changements. Les décisions A à F du superviseur y sont intégrées (§24.2) ; ce qui reste
à trancher est au §24.3 de `M4.md` et à la fin de ce document.

Chaque constat donne sa gravité, l'endroit, un scénario, la preuve (**vérifié** : lu dans le code ou le bytecode ;
**raisonné** sinon) et le changement fait. Gravités : **bloquant** (un interdit du §17 tombe, le serveur gèle, ou
le découpage casse le build de plusieurs tâches), **important**, **mineur**, **finition**.

**Bilan** : 28 constats, dont 5 bloquants, 9 importants, 10 mineurs et 4 finitions.

## Constats

1. **BLOQUANT (découpage) : les vagues B et C cassent le build dès qu'elles ajoutent un outil au Directeur.**
   - Où : `tool/PromptToolMentionsTest.kt:29-38` (`assertNamesExactly` : le prompt cite **tous** les outils du rôle
     et rien d'autre) ; `tool/ToolCatalog.kt:110` (le Directeur reçoit toute famille ajoutée à `allTools`).
   - Scénario : M4-13 écrit `punish` dans `PunishTools.kt`. L'outil entre dans le jeu du Directeur, `director.md`
     ne le cite pas (il appartient à M4-22, en clôture) : `PromptToolMentionsTest` rouge, `build` rouge. Même chose
     pour `trap` (M4-15), `favor` (M4-16), `answer_offering` (M4-20). Les outils du canon n'y échappaient que par
     chance (`mechanicsAvailable` faux par défaut).
   - Preuve : **vérifié** (lecture du test et du catalogue).
   - Changement : tous les outils de M4 dépendent de `env.mechanicsAvailable`, `punish` et `favor` compris (§12.1).
     `PromptToolMentionsTest` garde `mechanicsAvailable = false` jusqu'à M4-22, qui écrit `director.md` et passe le
     test à `true` dans le même commit (§22.1). En jeu, `DisabledMechanics` cache ces outils jusqu'à M4-23. Point J
     du §24.3.

2. **BLOQUANT (§17, décision C) : la fosse et les sols piégés pouvaient toucher une construction.**
   - Où : `M4.md` §9.1-9.2 (premier jet) : remplacement de « terrain naturel » (`#dirt`, `#base_stone_*`,
     `terracotta`…) quand l'index des blocs posés ne dit pas « posé par un joueur ».
   - Scénario :
     - Le vrai serveur sera migré avec son monde (`ARCHITECTURE.md` §17.1) : **toutes** ses constructions datent
       d'avant le mod, l'index ne les connaît pas. Une cabane de terre ou un mur de terre cuite passe pour du
       terrain : un `ice_floor` en change les blocs, même pour 60 s. C'est exactement ce que la décision C interdit.
     - Une torche, une pousse ou des fleurs posées par un joueur **sur** le terrain changé perdent leur support ; à
       la prochaine mise à jour voisine, elles tombent en objets (la restauration ne les remet pas).
   - Preuve : **vérifié** (l'index n'enregistre que les poses vues par le mod, `observer/PlacedBlocks.kt`) et
     **raisonné** pour les blocs décrochés.
   - Changement : le `BlockRestorer` ne pose **que dans l'air** et ne remplace jamais un bloc (§9.1) ; restent la
     cage de verre et les toiles ; fosse et sols retirés de M4 (§9.2). Le `BlockRestorer` ne dépend plus de
     l'index. Point G du §24.3.

3. **BLOQUANT (§17, objets) : une offrande acceptée n'était compensée que si le modèle y pensait.**
   - Où : `M4.md` §7.2 (premier jet) : `answer_offering accept` consommait les objets ; « le Narrateur accorde la
     faveur promise dans le même tour (le prompt le dit) ».
   - Scénario : Mimosa offre 10 diamants. Le Directeur accepte, puis son tour s'arrête (une erreur, une limite
     d'étapes, un oubli). Les diamants sont perdus pour de bon, sans compensation : « une confiscation est
     temporaire ou compensée » (§17.4) ne tient que par le prompt.
   - Preuve : **raisonné** (rien dans le design ne lie l'acceptation à une faveur).
   - Changement : plus d'outil `answer_offering`. Une offrande se prend par `favor(…, offering, note)` : la faveur
     est accordée d'abord, l'offrande consommée **seulement** si elle l'est (§7.1, §7.2). Refus : l'offrande revient
     d'elle-même. Décision A respectée par le code. Point L du §24.3.

4. **BLOQUANT (décision D, §17) : on pouvait mourir d'un duel, et le refus de mort laissait le joueur à 0 PV.**
   - Où : `M4.md` §5.5 (premier jet) : seul le coup de l'adversaire était arrêté ; « une autre mort (lave,
     créature, Narrateur) suit les règles ordinaires ».
   - Scénario :
     - Bartok fait reculer Mimosa dans la lave, ou du haut d'une falaise, ou dans le vide : mort ordinaire,
       inventaire lâché, dans un combat que le Narrateur a organisé (et dont il a pu choisir l'arène).
     - Une épée enflammée met le feu au perdant ; il reste à 1 PV, la résistance de 3 s s'arrête, le feu le tue
       après la fin du duel.
     - Bytecode : Fabric redirige `isDeadOrDying()` dans `LivingEntity.hurtServer` ; un `ALLOW_DEATH` qui refuse
       saute le totem et `die()`, **sans toucher à la vie**. À 0 PV, `tickDeath` retire le joueur du monde 20 ticks
       plus tard. Un écouteur qui refuse doit remettre de la vie lui-même.
   - Preuve : **vérifié** (bytecode de `LivingEntity` et des mixins de Fabric ; `DeathGuard.rescue` privé,
     `guard/DeathGuard.kt:130`).
   - Changement : pendant un duel, **toute** mort d'un duelliste est refusée (sauf `/kill` d'un opérateur) ; coup de
     l'adversaire : 1 PV, feu éteint, effets nuisibles retirés, résistance 5 s ; autre cause : `Guards.rescue`, le
     sauvetage de la garde de mort sans quota (§5.5, §13.2). À la fin, les deux duellistes sont éteints. L'arène
     est dans la dimension des duellistes (pas de voyage imposé dans le Nether).

5. **BLOQUANT (faisabilité) : restaurer des blocs dans `CHUNK_LOAD` gèlerait le serveur.**
   - Où : `M4.md` §9.1 (premier jet) : « restauration à `CHUNK_LOAD` ».
   - Scénario : `CHUNK_LOAD` part à la fin de `ChunkStatusTasks.full`, sur le thread serveur, avant que l'avenir
     `FULL` du tronçon soit terminé. `Level.setBlock` passe par `ServerChunkCache.getChunk(x, z, FULL, true)`, qui
     attend cet avenir (`managedBlock`) : il ne peut se terminer qu'après le retour de la tâche en cours. Le
     serveur boucle pour toujours. Un GameTest qui appelle le gestionnaire sur un tronçon déjà chargé ne le verrait
     pas.
   - Preuve : **vérifié** (bytecode de `ChunkStatusTasks` et `ServerChunkCache`), gel **raisonné** (pas exécuté).
     `server.execute {}` et `onServerThread {}` ne diffèrent pas sur le thread serveur.
   - Changement : `CHUNK_LOAD` ne fait qu'indexer le tronçon ; le retrait a lieu à la seconde suivante (§9.1) ;
     critère et GameTest de M4-05 et démarrage réel de M4-23 en tiennent compte.

6. **IMPORTANT (découpage) : des fichiers que le code fait changer n'avaient pas de propriétaire.**
   - Où, et ce qui a été attribué (§22.2) :
     - V3 casse `journal/DuckDatabaseTest.kt:47-56` (versions `[1,2]`), `memory/MemoryServiceTest.kt:241-246` et
       `memory/MemorySchemaTest.kt:40-48` (`MemorySchema.MIGRATION`) → M4-01.
     - `ToolCatalogTest:116,127-128` compare le JSON exact de la décision `tool_catalog` → M4-01, puis M4-11, M4-22.
     - L'Éclaireur : l'énumération de `delegate` est statique (`tool/task/TaskTools.kt:27-32`) ; `TaskToolsTest:43`
       et `WorkersTest:493` (« there is no scout yet ») cassent → M4-11.
     - Le nouveau format de l'index casse `oracle/AreaScan.kt:92-103` (valeurs brutes prises pour des indices) ;
       `oracle/Enclosure.kt:187` contourne la vérification → M4-06.
     - Rappels persistants : le Directeur ne voit le serveur que par `GameView` (`director/DirectorModule.kt:37`),
       et beaucoup de tests le construisent sans serveur → M4-10 (`ReminderStore` fourni par `GameView`, §15.1).
     - `tool/ToolLimits.kt`, `tool/speech/SpeechLimiter.kt` (constat 10) → M4-03.
     - `guard/DeathGuard.kt` (sauvetage public), `guard/DeathReport.kt` (constat 24), `guard/CommandPolicy.kt`
       (constat 15) → M4-04.
     - `tool/ToolRunner.kt` (constat 24) → M4-02 ; `memory/Dedup.kt` (constat 12) → M4-07.
     - `guard/ItemLedger.kt` servait le pardon (M4-04), les offrandes et les paris (M4-20, vague C) : le séquestre
       et `/offer` deviennent M4-15 en vague A ; M4-20 garde les paris seuls.
     - Défis et duels partagent leur fin (enjeux, paris, canon, interactions, signal) : M4-12 écrit `finish`, que
       M4-19 appelle ; M4-23 implémente `ChallengeControl` en déléguant à `Challenges`, `Duels` et `Bets`.
     - **Branches ouvertes** : M3-POST (`ToolArgs`, `TraceRecords` avec `Kind.TOOL`, `MemoryStore.addNote`,
       `DuckMemoryStore`, `MemoryTools`, `director.md`…) et la nouvelle tentative d'ouverture du Journal
       (`NarratorRuntime.kt`, `JournalModule.kt`) touchent M4-01, M4-07 et M4-08 : fusion **avant** M4-01.
   - Preuve : **vérifié** (lecture de chaque fichier et test cités).
   - Changement : §22 réécrit ; le numéro 15 (les pièges, fondus dans M4-05 et M4-13) sert au séquestre. Toujours
     24 tâches, chemin critique inchangé (sept étapes).

7. **IMPORTANT (§17, objets) : une mise pouvait rester bloquée pour toujours.**
   - Où : `M4.md` §5.6 (premier jet) : `returnAt = Long.MAX_VALUE` jusqu'au règlement.
   - Scénario :
     - Le fichier du joueur (mise en séquestre) et l'état global (le pari, puis l'instruction de règlement) se
       sauvent à des moments différents. Un crash entre les deux laisse une mise sans pari, ou un pari réglé dont
       l'instruction est perdue : la mise ne revient jamais.
     - Une mise cédée dort dans le registre du perdant tant que le gagnant ne se connecte pas en même temps ; s'il
       ne revient jamais, elle n'est à personne.
     - À la connexion, les écouteurs `JOIN` des garde-fous et des mécaniques passent dans l'ordre d'enregistrement :
       une restitution pouvait précéder l'instruction.
   - Preuve : **raisonné** (ordre de sauvegarde de `saveEverything`, `ItemLedger.giveBack`).
   - Changement : chaque mise a une **échéance de secours** (fin du défi + 7 jours ; 30 jours une fois cédée) ;
     les instructions sont idempotentes et appliquées avant toute restitution ; ce qui ne rentre pas attend en
     `owed` (§5.6).

8. **IMPORTANT (§17, acharnement et mort) : `punish` comptait sur « la première étape hostile ».**
   - Où : `M4.md` §6.3 (premier jet).
   - Scénario : `tree_top` téléporte à moins de 32 blocs, ce qui n'est pas hostile pour `teleport`
     (`Caps.HOSTILE_TELEPORT_BLOCKS`) ; `butterfingers` et les pièges n'ont pas d'outil. Ces punitions
     n'ouvraient pas d'épisode : pas d'`already_punished`, pas de `fair_share`, pas de `same_punishment`, et
     `tree_top` n'armait aucune garde de mort (le joueur peut tomber de l'arbre).
   - Preuve : **vérifié** (`tool/action/PlayerActionTools.kt:563-588`, `guard/Caps.kt`).
   - Changement : `punish` ouvre lui-même l'épisode avec sa catégorie, arme la garde de mort pour ses périls, et fait
     d'abord ce qui peut échouer sans rien changer (terrain d'un piège, cime) : une punition impossible ne compte
     pas (§6.3).

9. **IMPORTANT (acharnement) : `act = turnId ?: firingId` confondait un déclenchement et un tour.**
   - Où : `M4.md` §4.3 ; `journal/Journal.kt:34` (deux suites, `IdKind.TURN` et `IdKind.DECISION`).
   - Scénario : le déclenchement 812 d'une règle et le tour 812 du Directeur ont le même `act` : la règle passe
     pour une action du même tour, `already_punished` ne la refuse pas.
   - Preuve : **vérifié**.
   - Changement : `act = turnId ?: -firingId` ; `OpenEpisode` sauvé avec l'acte, relu depuis `turn_id` (§4.3,
     §13.2).

10. **IMPORTANT (faisabilité) : une règle se serait tue après quatre répliques pour la vie du serveur.**
    - Où : `tool/ToolLimits.kt:50`, `tool/speech/SpeechLimiter.kt:36` (`TurnCounter` rangé par
      `(conversationId, turnId)`, 4 `say` par tour).
    - Scénario : `Caller("mechanics:C-60", turnId = null)` : tous les déclenchements de C-60 partagent un compteur ;
      au cinquième, `say` est refusé jusqu'à ce que seize autres tours poussent l'entrée dehors.
    - Preuve : **vérifié**.
    - Changement : limites par tour rangées par `Caller.act` (M4-03, §4.3).

11. **IMPORTANT (§17 et gameplay) : les créatures touchées par une règle le restaient pour toujours.**
    - Où : `M4.md` §8.4, §8.6 ; `guard/NarratorMobs.kt:273` (`isNarrators` = le tag seul) ; `DeathGuard.kt:90`.
    - Scénario :
      - Une loi `mob_spawn` d'une heure sur les creepers marque chaque creeper chargé. Le tag `narrator2` est sauvé
        avec la créature et n'était jamais retiré : ces creepers restent « du Narrateur » pour toujours.
      - Conséquence que le design ne disait pas : une mort qu'ils causent compte dans le quota, puis est **refusée
        avec un sauvetage** ; au chaos 1 et 2 (quota nul), chaque mort par creeper devient un retour au lit.
      - `mob_effect` permettait `wither`, `poison`, `instant_damage` et `mob_attribute` une `max_health` de 0,25 :
        une loi `mob_spawn` sur les villageois décimait un comptoir d'échanges.
      - Une créature rechargée a déjà son modificateur : `addPermanentModifier` lève
        `IllegalArgumentException` (bytecode).
    - Preuve : **vérifié**.
    - Changement : marque de règle `narrator2_rule_<id>` retirée à sa fin (et `narrator2` quand il n'en reste
      aucune) ; pas d'effet qui tue ; `max_health` jamais réduite ; `addOrReplacePermanentModifier` ; l'attribution
      des morts est écrite (§8.4, §8.6). Point I du §24.3.

12. **IMPORTANT (mémoire) : la déduplication de M3 aurait avalé les écritures du moteur.**
    - Où : `memory/Dedup.kt:19-29` (canon : mêmes sorte et joueurs ouverts) et `:39-56` (interaction sans preuve =
      doublon à ±10 min, relecture M3 constat 4).
    - Scénario : `start_challenge` reçoit `Added.Duplicate` d'un autre défi ouvert des mêmes joueurs ; deux
      `challenge_result` du moteur (sans `tool_calls`) à cinq minutes d'écart n'en font qu'un.
    - Preuve : **vérifié**.
    - Changement : aucune déduplication d'une ligne dont `mechanic` n'est pas nul ; les interactions `ENGINE` ne se
      dédupliquent qu'à `canon_id`, joueur et sorte égaux (§3.3, M4-07).

13. **IMPORTANT (faisabilité) : la détection d'`obtain` n'aurait jamais vu de gagnant.**
    - Où : `M4.md` §5.2 (`clearOrCountMatchingItems(predicate, 0, craftSlots)`).
    - Scénario : la vraie signature est `(Predicate, boolean countOnly, int maxCount, Container craftSlots)` ; la
      forme du design n'existe pas, et `maxCount = 0` sans `countOnly` rend 0. Elle compte aussi le curseur et la
      grille d'artisanat.
    - Preuve : **vérifié** (javap et bytecode de `Inventory`).
    - Changement : comptage case par case sur `0 until containerSize` (36 cases puis l'équipement), comme
      `ItemLedger.confiscate` (§5.2).

14. **IMPORTANT (§17, mort et objets) : la cage et les toiles n'avaient pas de garde-fous de terrain.**
    - Où : `M4.md` §9.2 (premier jet).
    - Scénario :
      - Un joueur en l'air (élytres, chute) : la cage pose un plancher de verre sous lui ; au retrait, il tombe de
        haut.
      - De la lave à côté : la case de lave reste un trou dans la cage, la lave coule vers le joueur enfermé.
      - Une case occupée : du verre posé dans un cadre ou une peinture les décroche (`HangingEntity.survives`,
        bytecode), dans une créature l'étouffe.
      - Aucune garde de mort armée : un joueur enfermé tué par un squelette perdait son inventaire.
    - Preuve : **vérifié** (bytecode de `BlockAttachedEntity` et `HangingEntity`) et **raisonné**.
    - Changement : refus quand le joueur n'est pas au sol, vole, monte ou dort, ou quand un liquide, du feu ou un
      bloc qui tombe est dans la boîte ; aucune case occupée par une entité ; garde de mort armée pour toute la
      durée (§9.1, §9.2).

15. **MINEUR (§17, PvP) : `duel`, `bet` et `offer` n'étaient interdites à `run_command` que par défaut.**
    - Où : `guard/CommandPolicy.kt:115-120,160` (`pray` et `narrator` interdites), `:175-178`.
    - Scénario : un opérateur ouvre `duel` par `safety.extraAllowedCommands` (ou un futur lot l'ajoute à `ALLOWED`) ;
      le Narrateur fait `execute as Bartok run duel accept C-52` : un duel forcé. La politique suit bien la
      redirection d'`execute`, c'est la liste qui manque.
    - Preuve : **vérifié**.
    - Changement : les trois commandes entrent dans la liste interdite (M4-04, §18.2).

16. **MINEUR (ton) : un gage pour tous les inscrits d'office, retardataires compris ; le tribut de la v1.**
    - Où : `M4.md` §5.1-5.3, §16.
    - Scénario : sans `players`, tous les joueurs en ligne participent, et tout joueur qui se connecte ; la
      pénalité (jusqu'à la sévérité maximale du chaos) frappait chaque perdant, y compris celui arrivé 30 s avant la
      fin. Rien n'empêchait non plus le Directeur de réclamer des offrandes, le « tribut exigé » que la v1 a rendu
      glauque (`ARCHITECTURE.md` §1).
    - Preuve : **raisonné**.
    - Changement : pénalité de sévérité 1 ou 2 (un gage), jamais pour un joueur arrivé en cours de défi ;
      `director.md` : ne jamais réclamer d'offrande ni punir qui n'en fait pas (§5.1, §5.2, §16). Point H du §24.3.

17. **MINEUR (coût, simplicité) : trois outils et un enjeu de trop.**
    - Constat : `trap` doublonnait `punish` (un piège est un châtiment, avec catégorie, sévérité et épisode) ;
      `answer_offering` séparait l'acceptation de la faveur (constat 3) ; l'enjeu `title` obligeait le moteur à
      écrire un canon `title` ; `Caps.trapSeconds` doublonnait la sévérité.
    - Changement : `punish cage` et `punish web` ; offrande par `favor` ; pas d'enjeu `title` (le Directeur décerne
      un titre au tour du `CHALLENGE`) ; durée d'un piège = 10·s s. Le Directeur passe de 37 à **44** outils au lieu
      de 46 (§12.1). Le schéma de `start_challenge`, qui porte deux `StakeSpec`, maigrit d'autant.

18. **MINEUR (coût) : les chiffres de tokens et de budgets du premier jet étaient faux.**
    - Où : `M4.md` §12.1, §16 ; `config/PromptsTest.kt:37`.
    - Constat :
      - M3-19 §21.1 mesure 2,2 à 2,4 caractères par token, pas 3,5 : 14 000 caractères de définitions font
        ≈ 6 000 tokens, pas 4 000.
      - M3-19 §21.3 : le prompt système n'est **pas** dans le préfixe gardé en cache d'une conversation à l'autre ;
        il est réécrit à chaque chapitre. Chaque caractère ajouté à `director.md` coûte à chaque chapitre.
      - « 28 519 » pour `director.md` est en octets ; `PromptsTest` compte 27 735 caractères pour un budget de
        30 000 (le design visait 36 000 sans changer le test). `chronicler.md` (10 844 + 600) dépassait les 11 000
        du test.
      - Tout identifiant en minuscules entre accents graves passe pour un outil dans `PromptToolMentionsTest`.
      - Les arguments des pseudo-outils (`mob_effect`, `mob_attribute`, `notify`) n'étaient pas dans la description
        de `create_rule` ; le modèle ne voit pas ces outils.
    - Changement : chiffres corrigés ; `director.md` ≤ 34 000 caractères (budget du test porté à 34 000),
      `chronicler.md` à 11 500 ; règle des accents graves écrite ; arguments des pseudo-outils dans la description
      (§8.1, §12.1, §16).

19. **MINEUR (coût) : trop de réveils prioritaires.**
    - Où : `M4.md` §4.4 ; `director/WakeScheduler.kt:45` (seul `MemoryDue` passe outre le budget aujourd'hui).
    - Scénario : chaque acceptation de duel réveillait le Directeur en priorité ; un joueur pouvait enchaîner
      `/offer` (une pierre, puis une autre, dès la précédente rendue) et réveiller le Directeur malgré son budget
      horaire.
    - Changement : `DUEL` prioritaire seulement à la fin ; une `/offer` par joueur et par 10 min (`lastOfferAt`)
      (§4.4, §7.2).

20. **MINEUR (robustesse) : la réconciliation pouvait annuler un défi tout neuf.**
    - Où : `M4.md` §4.6.
    - Scénario : le Directeur lance un défi pendant la réconciliation : la ligne du canon existe, l'état pas encore ;
      la réconciliation la prend pour un reste de crash et l'annule.
    - Changement : seules les lignes créées avant le démarrage du serveur sont réconciliées (§4.6).

21. **MINEUR (faisabilité) : `teleport random_radius` « huit essais » ne tenait pas dans le délai de l'outil.**
    - Où : `tool/action/PlayerActionTools.kt:529` (12 s) ; `tool/action/Teleports.kt` (`landing` ne lit que des
      tronçons chargés, `awaitLoaded` attend jusqu'à 8 s).
    - Changement : un point au hasard, une seule attente de chargement, puis jusqu'à huit colonnes de ce tronçon
      (§22.2, M4-03).

22. **MINEUR (faits) : plusieurs déclencheurs ne se comportent pas comme le premier jet le disait.**
    - `DamageDealt` n'est publié que quand un joueur frappe, et **jamais pour le coup mortel**
      (`FabricObserver.kt:150,160`) : un poisson d'argent tué d'un coup ne déclenche rien.
    - `EntityKilled` inclut le PvP (`pvp = true`, `FabricObserver.kt:196`) : `kill` (déclencheur et objectif) le
      filtre.
    - `ChatMessage.pos` est nul (`GameEvent.kt:653`) : la position d'un `chat` vient de `PlayerStates`.
    - La structure de `PlayerStates` a jusqu'à 5 s de retard, `BiomeEntered` ≈ 3 s.
    - Les feux d'artifice à des coordonnées exigent un pas de tir à 6 blocs de tout joueur
      (`WorldActionTools.kt:828-837`) : l'effet de l'exemple des vaches sera souvent `FAILED`.
    - Changement : §5.2, §8.2, §8.8 corrigés.

23. **MINEUR (découpage) : les pièges des GameTests n'étaient pas rappelés.**
    - Constat : un test qui réveille le Directeur tourne dans l'environnement `narrator2-gametest:director` et
      ferme son chapitre (T22, M3-19) ; l'ordre des lots est aléatoire (M2-fix-EF) ; un seul test par lot recharge
      les réglages (T02) ; sous Windows, signaux plutôt que délais (FLAKY-1). Rien n'en était dit pour M4-10, M4-23
      et M4-24.
    - Changement : §21.2 et les tâches concernées le disent ; `runServer` avec `gateway.baseUrl` sur un port fermé
      (M3-18).

24. **MINEUR (traces) : les actes du moteur échappaient aux lecteurs de `tool_calls`, et `EngineActions` aurait
    recopié le lanceur.**
    - Où : `guard/DeathReport.kt:104-114` (l'explication d'une mort ne lit que `tool_calls`) ;
      `tool/ToolRunner.kt:46,63` (`run` et `execute` privés, seul `runAll` est public et audite toujours).
    - Changement : `DeathReport` lit aussi les décisions `mechanic action` (M4-04) ; `ToolRunner.invoke` (décodage,
      délai, exceptions, sans audit) est partagé par `runAll` et `EngineActions` (M4-02, §4.3).

25. **FINITION : la règle `pvp` est celle du serveur.** `ServerLevel.getGameRules()` délègue au serveur ;
    `ServerLevel.isPvpAllowed()` la lit ; il n'y a plus de réglage `pvp` dans `server.properties` (bytecode). « Vraie
    dans leur dimension » corrigé (§5.5).

26. **FINITION : démarrage et détails.**
    - Les créatures chargées avant `SERVER_STARTED` n'ont pas d'`onMobLoad` (`NarratorRuntime.current` n'est posé
      qu'après `wire()`) : la partie des règles les parcourt à son `started` (§4.6). `createMechanics` ne touche pas
      au Directeur, posé après lui.
    - `end_challenge` du Directeur : le moteur résout quand même en `ENGINE` (§3.3). `CanonKind.PLAN` a besoin de sa
      branche dans `allows` (aujourd'hui `else -> RETIRED`).
    - `UseItemCallback` ne part pas d'un clic sur un coffre ou une porte : le joker se joue dans le vide (§4.2).
    - `slime_rain` : des slimes qui se divisent laissent des petits sans tag ni échéance ; taille 1
      (`net.minecraft.world.entity.monster.cubemob.Slime.setSize`, public) (§6.3).
    - Un joker sans place dans l'inventaire attend en `owed` au lieu de disparaître (§7.1).
    - `FakeGuards` n'enregistrait pas les appels à `hostile` : M4-01 l'ajoute (§13.2).
    - `/narrator pause` ne met en pause que le Directeur : point K du §24.3.

27. **FINITION : LLMGW et décision E.** Le serveur LLMGW n'a été hors ligne que de 21:42 à 22:25 le 18/10 ; il est
    joignable par le tunnel `cloudflared` (`CLAUDE.md`) et M3-19 a passé sa recette. §24.1 point 8 et
    `ARCHITECTURE.md` §14 corrigés. Les trois tentatives d'ouverture du Journal sortent de M4 (§15.2).

28. **FINITION (outillage) : `.research/jp.ps1 -Classes a,b` échoue par `powershell -File`.** La liste arrive en une
    seule chaîne (« class not found ») : appeler le script une fois par classe. À dire aux agents qui vérifient des
    signatures.

## Vérifié, sans constat

- **Bus** : les abonnés synchrones tournent sur le fil qui publie, sous le verrou du bus ; tout est publié sur le
  thread serveur, sauf `StatsSnapshot` (`StatsImport`, `Dispatchers.IO`), que le moteur n'écoute pas. `DamageDealt`
  est publié tout de suite (seul `DamageTaken` est regroupé) et porte l'UUID de la victime. `BlockPlaced` et
  `PlaceKind.BLOCK` existent. Aucun `when` exhaustif sur `DirectorSignal`, `Trigger` ou `Kind` : ajouter
  `Mechanic` compile ; `Inbox.timeOf` et `EventFormatter.line` doivent seulement le connaître (M4-01).
- **Explosions et foudre du Narrateur** : `ServerExplosionGuardMixin` ne blesse ni ne pousse que les joueurs non
  épargnés et les monstres ; objets au sol, cadres, supports d'armure, wagonnets, animaux et villageois sont
  intacts. `lightning` est visuelle (`setVisualOnly`), sans feu. Une règle qui fait exploser un minerai ne détruit
  donc aucun objet.
- **API 26.3** (javap) : `ServerEntityEvents.Load` (créatures nées et chargées), `ServerPlayerEvents.JOIN`, `LEAVE`,
  `AFTER_RESPAWN`, `ALLOW_DEATH` (phases possibles, arrêt au premier refus, avant le totem), `UseItemCallback`,
  `ServerBossEvent(UUID, Component, BossBarColor, BossBarOverlay)`, `GameRules.PVP`, `ClickEvent.RunCommand`,
  `Heightmap.Types`, `Block.UPDATE_CLIENTS` (2) et `UPDATE_SKIP_ALL_SIDEEFFECTS` (816), `NbtUtils`, `TagParser`,
  `WeatheringCopper.getFirst`, `HoneycombItem.WAX_OFF_BY_BLOCK`, `PlayerList.saveAll()` public,
  `ServerLevel.getAllEntities()`, `globalAttachments()` sur `MinecraftServer`, `ServerChunkEvents.CHUNK_LOAD`,
  `MobEffects.RESISTANCE`, `Entity.addTag`/`removeTag`/`entityTags()`. Les attributs `bounciness`,
  `friction_modifier` et `air_drag_modifier` du tableau des catégories existent bien.
- **État persistant** : pièce jointe globale et pièce jointe de tronçon relues après redémarrage (essai de
  l'architecte) ; `ALTER TABLE canon ADD COLUMN` sur DuckDB 1.5.5.1 ; aucune contrainte `CHECK` sur `canon`, la
  sorte `plan` passe. Les lignes sans `due_at` échappent bien à `DUE` et à l'expiration de 24 h.
- **Garde-fous existants** : `ItemLedger` n'écrit jamais au sol (sauf la restitution d'une créature expirée sans
  propriétaire en ligne, objets éternels) et garde ce qui ne rentre pas ; `DeathGuard` ne couvre jamais le PvP ;
  `CommandPolicy` suit les redirections d'`execute`.
- **Outils** : les noms de paramètres que le moteur remplit (`to`, `players`, `player`, `near`, `x`, `y`, `z`,
  `dimension`) existent tels que le §8.4 les écrit.
- **Configuration et commandes** : sections fusionnées clé par clé, `UiTexts.KEYS` vérifié par `UiTextsTest` ; une
  commande de joueur s'enregistre par une ligne de `NarratorCommand.kt`.

## Ce qui reste à trancher

Au §24.3 de `M4.md`, avec la recommandation de la relecture :

- **G.** Retirer la fosse et les sols piégés de M4 : **oui**, les rouvrir avec la Forge et une heuristique
  d'environnement naturel.
- **H.** Gage d'un défi de sévérité 1 ou 2, jamais pour un retardataire : **oui**.
- **I.** Créatures des règles « du Narrateur » tant que la règle vit (quota, puis sauvetage) : **garder**.
- **J.** Tous les outils de M4 dépendent du moteur, `punish` et `favor` compris : **oui**.
- **K.** `/narrator pause` laisse tourner lois, défis et pièges : **garder**, et le dire dans la réponse de `pause`
  à la prochaine retouche des commandes du Directeur.
- **L.** Pas de refus explicite d'une offrande (elle revient seule après 10 min) : **oui** ; `/offer cancel` si
  l'attente gêne en jeu.
