# Relecture de M5 : la Forge — constats et lots de corrections

Relecture du 21/10/2026, sur `main` à `973e2c9` (M5-01 à M5-21, M5-LOAD, FLAKY-4, M4-LEFTOVERS fusionnés ; seule la
recette réelle M5-22 tourne en parallèle, non relue). Elle porte sur tout le code de la Forge : `forge/**` (`api`, `rt`,
`sandbox`, `compile`, `host`, `sim`, `sdk`, `arsenal`, `ForgeService`, `ForgeSetup`), `mechanics/module/**`,
`mechanics/MechanicsService.kt`, `mechanics/Reconcile.kt`, `tool/forge/**`, `tool/mechanics/ModuleTools.kt`,
`command/ForgeCommands.kt`, `agent/Workers.kt`, `resources/narrator2/forge/**`, `prompts/forger.md`, la section « La
Forge » de `director.md`, et les tests et GameTests de la Forge. Lus aussi : `M5.md` en entier, `M5_DESIGN_REVIEW.md`,
`M4_REVIEW.md` (le modèle), `ARCHITECTURE.md` §10.2 et §17, et **toutes les passations M5-01 à M5-21, M5-LOAD,
FLAKY-4, M4-LEFTOVERS**.

Chaque constat donne sa gravité, l'endroit, un scénario, la preuve (**vérifié** : lu des deux côtés, bytecode ou
javadoc compris ; **raisonné** sinon ; je n'ai pas lancé de sonde jetable cette fois) et un **correctif proposé**. Le
superviseur décide. Les lots de la fin ne partagent aucun fichier ; l'ordre conseillé est donné.

**État de départ.** Le build de `main` est vert d'après les passations (M5-LOAD : 1 944 tests unitaires, « All 390
required tests passed » ; M5-21b : idem). Un seul GameTest a été **vu rouge une fois** sans lien avec la tâche qui le
voyait : `ArsenalCreaturesGameTests.theParadeFollowsItsPlayer…` (constat 7). Je n'ai pas relancé le build (worktree de
relecture, pas de modification de code).

**Bilan : 9 constats — 0 bloquant, 2 importants, 4 mineurs, 3 finitions —** plus trois décisions à prendre. Le cœur du
bac à sable, le modèle des fils de M5-05/M5-LOAD, la persistance et le cycle de vie des déploiements sont **solides** et
tiennent à la lecture ; aucune évasion, aucun gel du thread serveur, aucune fuite de données privées, aucun contournement
d'un garde-fou de M4 n'a été trouvé (voir « Vérifié, sans constat »). Les deux constats importants sont une dégradation
de disponibilité déjà bornée et un outil caché à tort ; aucun ne met un interdit du §17 en défaut.

## Réponses aux questions de la mission

### Le résidu accepté par M5-18 (hachage profond non couvert par la garde)

**Vérifié.** `Guarded.deep` (M5-18) borne le hachage et l'égalité **explicites** des types du JDK, les opérations
collection-contre-collection (`containsAll`/`removeAll`/`retainAll`), les lectures (`contains`/`containsKey`/`get`), les
insertions dans une table (`put`/`add`/`merge`/`compute*`/`addFirst/Last` d'un `Set`) et les records (amorce
`Guarded.record`). Restent **non couverts par la garde ni la réécriture** (`forge/rt/Guarded.java`,
`forge/sandbox/Instrumenter.kt` ne les enveloppent pas ; le cas `DistinctShared.java` de `EvilModulesTest` attend
explicitement `limit abandoned`) :

- `Stream.distinct()` et les collecteurs qui hachent (`Collectors.toSet`/`toMap`/`groupingBy`/`toCollection(HashSet::new)`) ;
- les fabriques immuables (`Set.of`/`copyOf`, `Map.of`/`copyOf`) et les **constructeurs de copie** (`new HashSet<>(coll)`,
  `new HashMap<>(map)`, `new TreeSet<>(coll)`, `List.copyOf`…) ;
- un **agrégat de records** (une liste de 10 000 records enveloppant chacun une structure partagée : `list.hashCode()`
  hache ~2×10⁸ emplacements sans que `deep` descende dans les records, qui sont des feuilles pour lui).

**Est-ce assez borné maintenant ?** Oui, pour la disponibilité, et M5-LOAD l'a plutôt **amélioré** : un tel appel est
lâché par le thread serveur à son budget (le serveur n'attend jamais plus de 25 ms), puis, une fois que son fil a calculé
au-delà de `cpuEvidence` (≥ 40 ms de temps CPU), **abandonné** (module désactivé, fil perdu) ; au-delà de **2** fils
perdus, la Forge est bloquée (fail-safe). Le fil perdu brûle un cœur jusqu'au bout de l'appel du JDK ; mais, depuis
M5-LOAD, il tourne à la **priorité normale** et non minimale, de sorte qu'il atteint ses points d'arrêt de GC
normalement : le coût §3.8.1 d'origine (65–90 ms par collecte, mesuré avec un fil à la priorité minimale) n'est **plus
représentatif** et **n'a pas été remesuré** à la priorité normale (constat 1). Verdict : **borné et sûr** (deux fils au
plus, puis Forge coupée), mais le coût résiduel en jeu reste à mesurer en recette.

**Ce que coûterait le fermer.** Envelopper `distinct`, les collecteurs de hachage, les fabriques immuables et les
constructeurs de copie par `Guarded.deep` est **faisable mais à surface large et non triviale pour `distinct`** : le flux
est paresseux, les éléments ne sont connus qu'à l'opération terminale, il faut donc un `Spliterator`/flux enveloppant qui
borne chaque élément au fil de l'eau (pas un simple remplacement de site d'appel). Les collecteurs demandent d'envelopper
l'accumulateur ; les constructeurs de copie et les fabriques se bornent, eux, au site d'appel (plus simple). Estimation :
une tâche de 3 h, le gros du travail étant l'enveloppe paresseuse du flux et ses tests. **Recommandation : laisser le
résidu tel quel en v1** (le module est désactivé au premier essai, le mal est borné), mais **remesurer le coût GC en
recette** et compter les `distinct`/`Set.of` abandonnés d'un module sain. Constat 1.

### La piste laissée ouverte par M5-LOAD : un appel en vol pendant une pause de la JVM

**Vérifié, traité, résiduel cosmétique.** M5-LOAD (`handoff/M5-LOAD.md`, « Un appel coupé par une pause de la JVM ») et
le code (`ModuleHost.settle`/`givenUp`, `ModuleThreads.run`) gèrent le cas : un appel en vol quand une pause du GC
commence est **lâché** au réveil de l'appelant (ses 25 ms murales sont passées), ses requêtes du SDK refusées ; comme son
fil **n'a pas calculé** au-delà de son budget (`computing` = false), c'est un `starved`, **aucune faute** — il finit seul
ou est **sauté puis rejoué** par `ModuleEngine.work`. Un module sain n'est donc **pas désactivé** par une pause du GC ; la
correction (un fait, un minuteur) n'est pas perdue. Le seul résiduel est cosmétique (des lignes `given up` / `nobody's
fault` dans le journal `Forge`) et une marge : le serveur n'attend jamais plus de 25 ms, mais une pause de plus de 25 ms
fait toujours lâcher l'appel. La piste bornée que M5-LOAD laisse ouverte (rendre à l'appelant sa propre marge de 5 ms
quand il constate qu'il a lui-même été arrêté, deux fois au plus) est **optionnelle** et n'est à ouvrir que si la recette
voit trop d'appels lâchés sur le serveur du propriétaire (§19.1 point 3 de `M5.md`). **Pas de constat : à compter en
recette.**

### Cohérence des règles de jeu : les créatures d'un module peuvent tuer leur cible

Voir la **décision D1** (« Décisions à prendre »). En bref : une créature d'un `spawn` du Directeur ou d'une règle ne tue
jamais (liée à son joueur par `CreatureShield.boundTo`, coup fatal → sauvetage sur place, hors quota, M4-POST,
M4-LEFTOVERS), **mais** une créature d'un module tue sa cible dans le quota de morts (`CreatureShield.bound` fait une
**exception** pour les créatures d'un module, et `ModuleCreatures.allowDeath` ne sauve que les passants, pas la cible —
`forge/sdk/CreatureControl.kt`, passation M5-07 écart 2). Asymétrie à trancher.

### Ce que voit le modèle

- **`sdk.md`** : une inexactitude utile à corriger — il ne dit pas que `hud` est **hors** du quota de 30 actes par minute
  (constat 3, signalé par la recette M5-22). Le motif de refus de `String.matches` (qui conseillait un `ctx.text()`
  inexistant, signalé par M5-13) est **corrigé** depuis M5-LOAD (`SandboxPolicy.kt:107` : « use Text.words(),
  Text.normalize(), contains() or equals() ») : plus de constat.
- **`director.md` « La Forge »** et **`forger.md`** : relus, ils disent la vérité du code (loi/plan, défi arbitré rendant
  un `CHALLENGE`, mêmes refus que le Directeur, `(run by a module)` clos par le moteur). Pas de constat.
- **Un outil offert alors qu'il ne marche pas ?** Non ; l'inverse : `tasks` est **caché** quand le Journal est
  indisponible alors que le Forgeron peut encore travailler (constat 2, signalé par M5-13 et M5-20).

### Hors M5 : le `fallback` de `ClaudeClient.replayOf` (demandé par le superviseur)

**Vérifié, traité ailleurs (CLAUDE-FALLBACK).** Le seul endroit qui suppose qu'une réponse ne contient que des blocs
connus est `ClaudeClient.replayOf` (`llm/ClaudeClient.kt:432`), qui appelle `ContentBlock.toParam()` sur chaque bloc —
l'appel qui tombe sur `Unknown ContentBlock`. **Les autres parcours de `message.content()` du mod sont sûrs** : ils
lisent par accesseurs `Optional` qui ignorent un bloc inconnu — `AgentLoop.kt:320`
(`block.thinking().map { … }.orElse(null)`) et `ReflexClient.kt:225` (`block.text().map { … }.orElse(null)`). Aucun autre
endroit n'itère les blocs en supposant un type connu. Pas de lot proposé ici.

## Constats

1. **IMPORTANT (bac à sable, disponibilité ; décision D3) : le résidu de hachage profond non couvert par la garde n'est
   borné que par l'hôte, et son coût en jeu n'a pas été remesuré depuis que M5-LOAD a changé la priorité des fils.**
   - Où : `forge/rt/Guarded.java` et `forge/sandbox/Instrumenter.kt` enveloppent le hachage/égalité explicites, les
     insertions, les records et `Arrays.deep*`, **mais pas** `Stream.distinct`, les collecteurs de hachage
     (`Collectors.toSet`/`toMap`/`groupingBy`), les fabriques immuables (`Set.of`/`copyOf`, `Map.of`/`copyOf`) ni les
     constructeurs de copie (`new HashSet<>(coll)`, `new HashMap<>(map)`…) ; `src/test/resources/forge/evil/DistinctShared.java`
     attend `limit abandoned` (l'hôte, pas la garde).
   - Scénario : un module forgé fait `players.stream().map(p -> sharedBig).distinct().count()` (`sharedBig` = une
     structure partagée bornée en taille). Le `distinct()` hache dans un `HashSet` que la réécriture n'enveloppe pas :
     des minutes de hachage **en un seul appel du JDK**, que ni la garde, ni le compteur, ni une garde de site d'appel ne
     voient. L'hôte abandonne l'appel au budget (le serveur ne gèle pas), le module est désactivé, un fil est perdu.
   - Mécanisme (M5-LOAD) : le fil perdu brûle un cœur jusqu'au bout, désormais à la **priorité normale** (et non
     minimale). La mesure §3.8.1 d'origine (65–90 ms par collecte du GC) a été prise avec un fil à la priorité minimale :
     elle **n'est plus représentative** et **n'a pas été refaite** à la priorité normale.
   - Preuve : **vérifié** par lecture (la liste de `Guarded`/`Instrumenter`, le verdict attendu de `DistinctShared`, la
     priorité des fils dans `ModuleThreads.lose`, l'absence de remesure dans `M5-LOAD.md` « Mesures »).
   - Gravité : **important** mais **borné** — pas de gel (l'hôte garantit ≤ 25 ms d'attente du serveur), au plus 2 fils
     perdus avant que la Forge se bloque (fail-safe). C'est une dégradation de disponibilité, de la famille du §3.8.
   - Correctif proposé (deux options) :
     - **Minimal (recommandé pour v1)** : ne rien envelopper de plus ; **remesurer en recette** le coût GC d'un fil perdu
       à la priorité normale et compter les abandons `distinct`/`Set.of` d'un module sain ; mettre à jour le §3.8.1 de
       `M5.md` (la valeur de 65–90 ms est pour l'ancienne priorité). Fermer les **constructeurs de copie et les fabriques
       immuables** seuls (bornables au site d'appel, surface étroite) si l'on veut réduire le résidu à peu de frais.
     - **Complet** : envelopper `Stream.distinct` par un flux paresseux qui borne chaque élément, et les collecteurs de
       hachage ; tâche à surface large (non triviale pour la paresse du flux).
   - Fichiers touchés : `docs/design/M5.md` (§3.8, §16.2) au minimum ; `forge/rt/Guarded.java`, `forge/sandbox/Instrumenter.kt`,
     `forge/sandbox/SandboxPolicy.kt`, `forge/EvilModulesTest.kt` pour l'option complète ou partielle.

2. **IMPORTANT (ce que voit le modèle) : le Directeur perd `tasks` quand le Journal est indisponible, alors que le
   Forgeron, lui, peut encore travailler.**
   - Où : `tool/task/TaskTools.kt:41` — `anyWorker(env)` = `WorkerRole.entries.any { it.role in env.workerRoles }` ;
     `WorkerRole` ne contient que l'Analyste et l'Éclaireur (M2, M4). `DelegateTool.isAvailable` **et**
     `TasksTool.isAvailable` renvoient `anyWorker(env)` (`:79`, `:126`). Or `Workers.roles`
     (`agent/Workers.kt:86-93`) inclut `Role.FORGER` quand la Forge marche, et `Chapters` pose
     `env.workerRoles = services.workers.roles` (`director/Chapters.kt:156`).
   - Scénario : le Journal tombe (DuckDB verrouillé au démarrage, `DisabledJournal`) mais la Forge marche. `forge_module`
     reste offert (c'est un `ForgeTool`, indépendant du Journal). Le Directeur lance une tâche du Forgeron ; son rapport
     revient en `TASK`. Mais `env.workerRoles = {FORGER}`, et comme `WorkerRole` n'énumère pas le Forgeron, `anyWorker`
     rend faux : `tasks` **et** `delegate` sont cachés. Le Directeur ne peut plus **lister ni annuler** sa tâche du
     Forgeron (ni la voir en file). Signalé par M5-13 et M5-20, resté non corrigé.
   - Preuve : **vérifié** des deux côtés.
   - Correctif proposé : `TasksTool.isAvailable(env)` = `env.workerRoles.isNotEmpty()` (n'importe quel rôle offert, le
     Forgeron compris), et `DelegateTool.isAvailable` reste `anyWorker(env)` (le Forgeron ne passe pas par `delegate`,
     son énumération reste `analyst`, `scout`). Un test de `ToolCatalogTest`/`PromptToolMentionsTest` : Journal absent,
     Forge présente → `tasks` offert, `delegate` non.
   - Fichiers touchés : `tool/task/TaskTools.kt` ; tests `ToolCatalogTest`/`PromptToolMentionsTest`.

3. **MINEUR (ce que voit le modèle ; recette M5-22) : `sdk.md` ne dit pas que `hud` est hors du quota de 30 actes par
   minute, et le vrai Forgeron a ralenti un module pour rien.**
   - Où : `resources/narrator2/forge/sdk.md:227` : « 4 acts a call (refused ones count), 30 a minute ; … `hud`: once a
     second per player. » Le lecteur peut croire que `hud` compte dans les 30. Le code dit le contraire
     (`forge/sdk/ActQueue.kt:196` : `act.kind != Act.Kind.HUD && full(sent, ACTS_PER_MINUTE, …)` — `hud` n'est jamais
     compté dans la minute ; sa seule limite est une ligne par joueur et par seconde).
   - Preuve : **vérifié** (le code et le texte).
   - Correctif proposé : préciser « 30 a minute (**`hud` excepté**) » ou « `hud` is **outside** the 30-a-minute cap:
     once a second per player ». **Contrainte** : `sdk.md` est à ~29 820 / 30 000 caractères (le superviseur signale
     ~180 caractères de marge) ; la retouche doit tenir dans cette marge (retirer un mot ailleurs si besoin).
   - Fichiers touchés : `resources/narrator2/forge/sdk.md` ; `SdkDocTest` vérifie toujours la prose et la taille.

4. **MINEUR (persistance, fins ; cohérence) : une note de fin refusée par la mémoire pour une raison autre que la
   `VoiceGuard` laisse la ligne du canon ouverte jusqu'au redémarrage.**
   - Où : `mechanics/module/ModuleEngine.kt:840-865` (`close`). La première `resolveCanon` est réessayée avec
     `neutralNote(ending)` sur `MemoryRejected` (la correction de M5-16 défaut 2 / M5-LOAD) ; mais ce repli neutre ne
     vise que le refus de la `VoiceGuard` (première/deuxième personne). Si `resolveCanon` lève `MemoryRejected` pour une
     **autre** raison (ligne déjà résolue, règle de suivi, etc.), le second `catch (e: MemoryRejected)` (`:854`) ne fait
     qu'un `WARN` et laisse la ligne **`active`**. Le commentaire dit « la réconciliation la fermera » — mais la
     réconciliation ne tourne qu'au démarrage.
   - Scénario : peu probable (la ligne vient d'être posée `ACTIVE` par `deploy`, et le seul refus réaliste est la
     `VoiceGuard`, couvert). Entre la fin du module et le prochain redémarrage, une loi finie pourrait rester `active`
     au canon du Directeur.
   - Preuve : **vérifié** par lecture ; **raisonné** pour la probabilité (faible).
   - Correctif proposé : à défaut d'un meilleur chemin, tenter une troisième résolution avec un statut sûr, ou résoudre
     par `Source.ENGINE` de façon idempotente qui n'échoue pas sur une ligne déjà fermée ; au minimum, documenter le
     résiduel dans la KDoc de `close` (il est réel mais marginal).
   - Fichiers touchés : `mechanics/module/ModuleEngine.kt` ; test `ModuleEngineTest`.

5. **MINEUR (tests ; stabilité CI) : le GameTest `ArsenalCreaturesGameTests.theParadeFollowsItsPlayer…` est instable.**
   - Où : `src/gametest/…/ArsenalCreaturesGameTests.kt`. Il vérifie que chaque maillon de la parade est « within 3.5
     blocks of the one ahead » pendant que le joueur de test marche. Vu **rouge une fois** par M5-21 (« a chicken passed
     in front of Bob …, each within 3.5 blocks of the one ahead ») et signalé par M5-15 (défauts 2 et la géométrie de
     `moveTo` en jeu, qui s'arrête à ~1 bloc de sa place : la file se tient à 1,2 à 2,4 blocs, parfois jusqu'à 3,1).
   - Preuve : **vérifié** par les passations (M5-15, M5-21).
   - Correctif proposé : élargir la borne (la géométrie de `PathNavigation.moveTo` a une imprécision de ~1 bloc, mesurée
     jusqu'à 3,1 ; 3,5 est trop juste), ou vérifier une propriété plus robuste (la file reste derrière le joueur, pas
     l'écart exact entre deux maillons), ou faire marcher le joueur plus lentement pour laisser le chemin se recalculer.
   - Fichiers touchés : `src/gametest/…/ArsenalCreaturesGameTests.kt`.

6. **MINEUR (concurrence/clarté) : `ModuleEngine.second()` appelle `offAtStart` à chaque seconde tant qu'une
   `forgeHalt` est posée et qu'un déploiement vit, alors que le cas n'arrive qu'au démarrage.**
   - Où : `mechanics/module/ModuleEngine.kt:600` : `store.get().forgeHalt?.let { halt -> if (live.isNotEmpty())
     offAtStart(halt) }`. En marche normale, `halt()` vide `live` tout de suite ; la seule entrée réelle est un
     redémarrage avec une `forgeHalt` pré-posée. Après `offAtStart`, `live` devient vide, donc les secondes suivantes
     sont inoffensives — mais la condition s'évalue à chaque seconde et le nom (`offAtStart`) ne dit pas qu'elle ne vaut
     qu'au démarrage.
   - Preuve : **vérifié** (le flux : `offAtStart` → `finish` → `retire` retire de `live`).
   - Gravité : **mineur**, pas un bogue (idempotent) ; lisibilité et robustesse (si un jour un déploiement pouvait
     naître sous coupe-circuit, `offAtStart` le relancerait chaque seconde). Correctif : ne l'appeler que depuis
     `begin`/`started`, ou garder un drapeau « déjà fait ». Fichiers touchés : `mechanics/module/ModuleEngine.kt`.

7. **FINITION (coût sur le thread serveur) : `ModuleActs.teleport` lit la dimension du joueur jusqu'à trois fois dans son
   chemin de refus.**
   - Où : `forge/sdk/ActQueue.kt:446-451` : `dimensionOf(player)` est appelé dans la condition (`.let { … }`) puis
     **deux fois** dans le message du refus (`${dimensionOf(player)}`). Chaque appel est une recherche
     `server.playerList.getPlayerByName` sur le thread serveur (par le pont du SDK).
   - Preuve : **vérifié**.
   - Gravité : **finition** (trois lectures brèves, seulement sur le chemin d'un refus). Correctif : lire la dimension
     une fois dans une variable. Fichiers touchés : `forge/sdk/ActQueue.kt`.

8. **FINITION (prompts, budget) : `sdk.md` est à ~180 caractères de son plafond de 30 000, ce qui gêne toute correction
   (constat 3) et tout ajout futur d'exemple.**
   - Où : `resources/narrator2/forge/sdk.md` (29 936 selon M5-LOAD, ~29 820 selon le superviseur). M5-13 et M5-15
     signalaient déjà qu'un ajout oblige à retirer l'extrait de créatures. Le budget est serré pour un fichier qui doit
     refléter chaque type et méthode publics du SDK et que la recette (M5-22, éval F1) peut vouloir enrichir.
   - Preuve : **vérifié** (tailles des passations et du superviseur).
   - Correctif proposé : au prochain travail sur `sdk.md`, dégager de la marge (la référence peut condenser les lignes
     une par une ; l'extrait de créatures peut renvoyer à `module_read parade`), ou relever le budget de `PromptsTest`
     après accord (le préfixe est en cache, le coût marginal par tâche est faible). Fichiers touchés :
     `resources/narrator2/forge/sdk.md`, `config/PromptsTest.kt`.

9. **FINITION (cohérence doc/code) : `ARCHITECTURE.md` §10.2 principe 3 dit encore « son fil, qui finit son calcul à la
   priorité minimale ».**
   - Où : `docs/ARCHITECTURE.md:388` : « son fil, qui finit son calcul à la priorité normale » — **corrigé** en fait au
     merge M5-21b (le texte actuel dit « priorité normale »). Vérification : le §10.2 est à jour ; **aucun correctif**,
     noté pour mémoire (M5-LOAD décision 8 et M5-21b l'ont traité). Je le laisse pour tracer que le point a été vérifié.
   - Preuve : **vérifié** (`ARCHITECTURE.md:388` lu).

## Vérifié, sans constat

Ce qui tient à la lecture (les deux côtés lus, bytecode/javadoc compris quand il le faut) :

- **Bac à sable, confidentialité et intégrité du serveur.** La garde (`BytecodeGuard`) applique `SandboxPolicy` à chaque
  type nommé, membre appelé (descripteur compris), amorce, constante, `catch`, supertype, **annotation** (de classe,
  champ, méthode, paramètre, type) et drapeau de méthode ; un nom interdit (`wait`, `finalize`…) est refusé **sur tout
  propriétaire, la classe du module comprise** (`checkMember` l. 102) ; `MethodHandles` de tête est **hors** de
  l'ensemble résoluble du chargeur (`SandboxPolicy.resolvable`, `LINKAGE_BINARY` : seul `MethodHandles$Lookup`), donc
  même un module fabriqué et chargé **sans la garde** ne peut obtenir de `Lookup` ; « à soi » = l'ensemble des noms
  produits par la compilation (`own = bytes.keys`), jamais un préfixe, et le chargeur refuse une classe d'entrée
  `Top$-meter` (`ModuleLoader.checkHolder` : aucune méthode, un seul champ statique `m`). Fichiers, réseau, processus,
  réflexion, threads, natif, `Unsafe`, régulières sont refusés par la garde (et en second rideau par le chargeur).
- **Disponibilité : aucun gel du thread serveur.** Tout le code des modules tourne sur un fil des modules
  (`ModuleThreads`), le thread serveur l'attend au plus son budget + 5 ms puis abandonne (`ModuleHost.call`,
  `ModuleThreads.run`) ; le SDK revient au thread serveur par un pont (`SdkBridge`) et un fil abandonné est refusé à
  chaque entrée. M5-LOAD mesure le travail du module (temps entre deux lectures de l'horloge, bornées à 1 ms ; temps CPU
  pour une étape longue du JDK) et non l'horloge murale : un module sain n'est plus désactivé à tort sous charge. Aucun
  fil de la Forge ne tourne plus à la priorité minimale.
- **Réécriture et compteur.** `Guarded.capacity` borne **tout** constructeur à `int` de tête des types de la liste et
  `ensureCapacity`/`setLength`/`repeat`/`indent`, les tables de hachage comprises (constat 1 de la relecture du design,
  fermé) ; les ponts (`forge$bridge$N`) font passer `list::add` et `List::stream` par `Guarded` et comptent un `tick` ;
  une classe du module qui serait une `Collection`/`Map`/`CharSequence` est refusée par la garde **et** la réécriture ;
  un gestionnaire d'exception qui se couvre lui-même est refusé ; le `Meter` est collant, désarmé il lève, révocable.
- **Frontière du SDK (constat 8 du design).** Tout objet reçu d'un module est opaque : `SdkBridge` répond aux
  `equals`/`hashCode`/`toString` du proxy par l'identité, ne journalise rien du module, et ne l'appelle que dans un appel
  armé (`toCaller`) ; `ActQueue`, `FactQueue`, `Scheduler` gardent les `Consumer`/`Runnable` dans des objets à nous,
  jamais en clé de map ; `RetainedGauge` ne touche à aucune méthode d'un objet du module (parcours par réflexion, jamais
  `hashCode`/`equals`/`toString`), tient un ensemble d'identité (cycles bornés) et a des plafonds de **travail** (200 000
  objets, 32 Mio), plus d'horloge depuis M5-LOAD.
- **Les créatures et le §17.** `spawn`/`claim` excluent les boss et créatures hors d'échelle (`SpawnPolicy.FORBIDDEN` :
  warden, wither, ender dragon, elder guardian, giant) et `creeper`/`breeze` (`ModuleCreatures.FORBIDDEN`) ; une créature
  d'un module est marquée `narrator2` + `narrator2_module_<id>`, dans l'équipe `narrator2_modules` (collision `never`,
  synchronisée au client), sans dégât de chute, ne pousse personne, ne vise que sa cible (`CreatureShield.keepsAway`), ne
  ramasse rien (`MobPickUpGuardMixin`), et **un passant n'est jamais tué** (`ModuleCreatures.allowDeath` le sauve) ; la
  réservation d'un `spawn` est toujours rendue au règlement (`adopt` décrémente `reserved` même si `born` est vide ou si
  l'acte est refusé par une limite). (La cible, elle : décision D1.)
- **Les actes passent tous par le moteur.** Chaque invocation qui agit ouvre un acte du moteur (`ActQueue` →
  `EngineActRunner` → `EngineActions`), avec `already_punished`, `fair_share`, `same_punishment`, la grâce ; 4 actes par
  appel, 30 par minute (`hud` à part), 4 répliques ; `effect wither`/`poison`/`instant_damage`/`levitation` refusés par
  le SDK ; `teleport to_player` jamais écrit ; `place_blocks` dans l'air seulement, `creature_attack` et `place_blocks`
  arment la garde de mort. Les outils refusés à un module le sont par absence de méthode dans le SDK.
- **Persistance, redémarrage, réconciliation.** Un déploiement est une ligne du canon + une entrée
  `MechanicsState.modules` ; `Reconcile` compte les ids des modules (`kept.modules`) et `drop` les retire (sans quoi une
  ligne ouverte serait annulée au démarrage) ; au redémarrage, chaque déploiement est reconstruit hors du thread serveur
  (`Forge.build`), l'empreinte comparée, `start` avec `restarted()` et le `Storage` rendu, les créatures retrouvées dès
  `started` ; un échu pendant l'arrêt finit à la première seconde sans `stop` ; une version qui ne compile plus →
  `restart_failed` et `broken` ; `MechanicsService` garde la partie des modules même avec `DisabledForge` (les
  déploiements de l'état finissent `restart_failed`, créatures rendues, canon résolu).
- **Fins, canon et coupe-circuit.** Les sept fins (`ModuleEnding`) résolvent le canon par `Source.ENGINE` et écrivent une
  seule décision `module_ended` avec `detail.ending` ; une note refusée par la `VoiceGuard` est remplacée par des mots
  neutres (`neutralNote`, constat 4 pour le cas résiduel) ; le coupe-circuit (`halt`) est posé dans l'état **avant**
  d'arrêter quoi que ce soit, finit chaque déploiement `forge_off`, une ligne `MODULE` pour tous, annule la tâche du
  Forgeron, et survit au redémarrage ; un `forge_halt` illisible garde la Forge coupée ; un seul interrupteur à la fois
  (`switching`/`lock`). `/narrator pause` ne touche pas les modules.
- **Cache et arsenal.** Une version n'est compilée qu'**une fois** même quand deux appelants manquent le cache ensemble
  (`ForgeService.construct`/`building`, `CompletableDeferred` par clé, M5-LOAD) ; les écritures de l'arsenal sont
  atomiques (temporaire + `ATOMIC_MOVE`, source puis manifeste), les versions immuables (1 à 20), les liens jamais
  suivis, les chemins bornés au dossier du monde, les noms de périphériques de Windows refusés ; une version `broken` est
  calculée au démarrage, jamais écrite.
- **Vie privée.** `Facts` est le seul traducteur du bus (vérifié par lecture du bytecode de tout le mod dans
  `FactsTest`) ; une prière, un chuchotement et un message privé ne produisent **jamais** de fait ; aucun fait ne porte
  le texte d'un canal non public.
- **Compilateur aveugle.** ECJ ne voit que les paquets exportés de `java.base` et les deux paquets du SDK (durcissement
  M5-02 : même `jdk.internal.misc.Unsafe`, `sun.nio.ch` ne se résolvent plus), sur son fil, une compilation à la fois,
  annulée à 10 s (temps CPU depuis M5-LOAD), bloquée à 60 s ; le message interne d'ECJ n'est jamais rendu au Forgeron.
- **La batterie (`EvilModulesTest`, `EvilModulesGameTests`).** 22 cas plus des classes fabriquées à l'ASM, chacun par le
  vrai pipeline ; chaque refus de la garde est aussi chargé **sans** la garde (défense en profondeur) ; aucun cas ne
  franchit toutes les barrières. C'est la base de non-régression du bac à sable.

## Décisions à prendre

- **D1 — Cohérence : une créature d'un module peut-elle tuer sa cible ?** Aujourd'hui, une créature d'un `spawn` du
  Directeur ou d'une règle ne tue jamais (liée par `CreatureShield.boundTo`, coup fatal → sauvetage sur place, M4-POST,
  M4-LEFTOVERS), mais une créature d'un module **tue sa cible dans le quota de morts** (`CreatureShield.bound` fait une
  exception pour les modules ; `ModuleCreatures.allowDeath` ne sauve que les passants). Un joueur ne peut pas distinguer
  les deux visuellement, et le Directeur, qui « commande les modules », leur donne ainsi plus de pouvoir que son propre
  `spawn`.
  **Recommandation : aligner les modules sur le Directeur — une créature invoquée par le Narrateur ne tue jamais, module
  compris.** Raisons : (a) « une créature invoquée par le Narrateur ne tue jamais » (§17) devient absolu et « jamais
  glauque » est tenu ; (b) un module est un outil du Directeur, il ne doit pas avoir plus de puissance que lui ; (c)
  M4-LEFTOVERS prévoit déjà le correctif (« il suffira de retirer l'exception de `CreatureShield.bound` »). Le gag de
  harcèlement/pression reste (la créature vise, poursuit, frappe), seul le coup fatal devient un sauvetage sur place.
  Coût : une ligne dans `CreatureShield.bound` (ne plus excepter les modules) et l'alignement de
  `ModuleCreatures.allowDeath` sur le modèle « ne tue jamais » (M5-07 écart 1 l'avait laissé paramétrable). *Si* au
  contraire on veut des jeux à enjeu de mort, garder l'exception — mais le ton du projet ne le demande pas.

- **D2 — Le résidu du bac à sable (constat 1) : fermer ou mesurer ?** Recommandation : **mesurer en recette** (le coût
  GC d'un fil perdu à la priorité normale, les abandons d'un module sain) et, à peu de frais, fermer seulement les
  constructeurs de copie et les fabriques immuables au site d'appel ; laisser `Stream.distinct`/les collecteurs au
  chemin de l'hôte (abandon + 2 fils perdus) en v1, à rouvrir si la recette en voit en jeu.

- **D3 — `tasks` sans Journal (constat 2) : montrer `tasks` dès qu'un rôle quelconque (Forgeron compris) peut
  travailler ?** Recommandation : **oui** — `TasksTool.isAvailable` sur `env.workerRoles.isNotEmpty()`, `delegate`
  inchangé.

## Lots de corrections proposés

Chaque lot possède ses fichiers ; **aucun fichier n'est commun à deux lots**. Ils sont tous petits et indépendants ;
l'ordre n'importe pas, ils peuvent tous partir en parallèle.

| Lot | Constats / décisions | Fichiers | Critère |
|---|---|---|---|
| **M5-FIX-A — `tasks` sans Journal** | 2 (D3) | `tool/task/TaskTools.kt` ; tests `ToolCatalogTest`, `PromptToolMentionsTest` | Journal absent, Forge présente : `tasks` offert, `delegate` non ; build vert |
| **M5-FIX-B — `sdk.md` : `hud` hors quota** | 3, 8 | `resources/narrator2/forge/sdk.md` (et `config/PromptsTest.kt` si le budget doit bouger) ; `SdkDocTest` | `sdk.md` dit « `hud` outside the 30-a-minute cap » et tient sous son budget ; `SdkDocTest` vert |
| **M5-FIX-C — créatures des modules** (si D1 = aligner) | D1 | `guard/CreatureShield.kt`, `forge/sdk/CreatureControl.kt` (`ModuleCreatures.allowDeath`) ; GameTest `ModuleActsGameTests` ou `ArsenalGamesGameTests` | une créature d'un module ne tue plus sa cible (sauvetage sur place) ; `ARCHITECTURE.md` §17 mis à jour ; build vert |
| **M5-FIX-D — fins et lisibilité du moteur** | 4, 6, 7 | `mechanics/module/ModuleEngine.kt`, `forge/sdk/ActQueue.kt` ; tests `ModuleEngineTest`, `ActQueueTest` | note de fin toujours résolue (ou résiduel documenté) ; `offAtStart` appelé une seule fois ; `teleport` lit la dimension une fois ; build vert |
| **M5-FIX-E — GameTest instable de la parade** | 5 | `src/gametest/…/ArsenalCreaturesGameTests.kt` | `theParadeFollowsItsPlayer…` vert sur dix lancements sous charge |
| **M5-FIX-F — résidu du bac à sable** (si D2 = fermer en partie) | 1, D2 | `forge/rt/Guarded.java`, `forge/sandbox/Instrumenter.kt`, `forge/sandbox/SandboxPolicy.kt`, `forge/EvilModulesTest.kt`, `docs/design/M5.md` (§3.8) | les constructeurs de copie et fabriques immuables bornés, `DistinctShared` inchangé ou sa famille élargie ; §3.8 à jour ; build et batterie verts |

Ordre conseillé : A, B, D, E partent tout de suite (petits, sûrs). C dépend de la décision D1 ; F de la décision D2. A, B,
C, D, E, F ne partagent aucun fichier et peuvent tourner en parallèle. Aucun ne touche un contrat figé du SDK.
