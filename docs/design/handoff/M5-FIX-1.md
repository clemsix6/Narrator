# Passation M5-FIX-1 : les lots A à E de la relecture de M5

Lots A, B, C, D et E de `reviews/M5_REVIEW.md` (constats 2 à 8, décisions D1 et D3 du superviseur), regroupés en un
seul lot. Branche `worktree-agent-ad8be9e6b84f48dd7`. **En cas de contradiction avec `M5.md`, `M4-LEFTOVERS.md`,
`M5-07.md` ou `M5-11.md`, le code et cette passation font foi** (`M5.md` a un encart « Passation M5-FIX-1 » au §4.5,
`ARCHITECTURE.md` §17 est corrigé).

## Ce qui a changé

| Fichier | Changement | Lot |
|---|---|---|
| `tool/task/TaskTools.kt` | `TasksTool.isAvailable` = `env.workerRoles.isNotEmpty()` (le Forgeron compris) ; `delegate` inchangé | A |
| `resources/narrator2/forge/sdk.md` | `hud` hors des 30 actes par minute ; les créatures ne tuent jamais ; deux doublons retirés | B, C |
| `forge/sdk/CreatureControl.kt` | `ModuleCreatures.allowDeath` : le coup fatal d'une créature d'un module est un sauvetage sur place ; `CreatureControl.take` retire la marque `punishment` d'une créature de `spawn` | C |
| `guard/CreatureShield.kt` | plus d'exception pour les modules dans `bound` | C |
| `guard/Guards.kt`, `guard/GuardsImpl.kt`, `testing/FakeGuards.kt` | `rescue(player, why, inPlace = false)` | C |
| `guard/NarratorMobs.kt` | KDoc de `NarratorMob.punishment` seulement | C |
| `forge/api/Creature.java`, `forge/api/Creatures.java` | Javadoc seulement : les coups ne tuent jamais | C |
| `mechanics/module/ModuleEngine.kt` | `close`/`resolve` : une ligne du canon refusée est relue, puis fermée ; `haltAtStart` : `offAtStart` une seule fois | D |
| `forge/sdk/ActQueue.kt` | `ModuleActs.teleport` lit la dimension du joueur une fois | D |
| `docs/ARCHITECTURE.md` §17, `docs/design/M5.md` §4.5 (encart) | la règle « une créature du Narrateur ne tue jamais » vaut pour les modules | C |
| Tests | voir « Vérifications » | A–E |

## A. `tasks` sans Journal (constat 2, D3)

`tasks` est offert dès qu'un rôle de travailleur est branché (`ToolEnv.workerRoles` non vide), le Forgeron compris : le
Journal tombé et la Forge en marche, le Directeur liste et annule encore les tâches du Forgeron. `delegate` reste offert
seulement quand l'Analyste ou l'Éclaireur l'est : son énumération reste `analyst`, `scout`. Les prompts ne changent pas
(`director.md` nomme déjà `tasks`). Test : `TaskToolsTest` › *with the Journal down and the Forge up, tasks lists the
forger's tasks and delegate is not shown* (l'environnement que donne `Workers.roles` sans Journal : `{forger}`).

## B. `sdk.md` : `hud` hors quota (constats 3 et 8)

§4 : « 4 acts a call (refused ones count), 30 a minute (not `hud`, capped at once a second per player); `say`, `sayTo`,
`title`: 4 a minute… ». Pour la place, deux doublons retirés : « a bad argument throws `ForgeRefusal` » au §6 (le §2 le
dit pour tout le SDK), et « acts asked in `stop` are dropped » au §9 (dit au §2 et deux lignes plus haut au §9). Taille :
**29 909** caractères (29 936 avant), avec la phrase de `hud` et celle des créatures (lot C) en plus. Il reste
**91 caractères** de marge : un ajout plus long devra encore retirer quelque chose, ou faire relever le budget de
`PromptsTest` (30 000) après accord (constat 8).

## C. Les créatures des modules ne tuent jamais (D1)

### Ce qui est fait

- **`ModuleCreatures.allowDeath`** (le crochet `ALLOW_DEATH` du moteur, avant celui des garde-fous) : le coup fatal d'une
  créature d'un module (ou de sa flèche, sa potion, ses crocs : `behind`), **sur sa cible comme sur n'importe qui**, est
  refusé et devient un **sauvetage sur place** (`Guards.rescue(…, inPlace = true)`, le `DeathGuard.rescue` en place de
  M4-POST) : le joueur reste où il est, 3 cœurs, rien de perdu, rien compté au quota, bouclier de 5 minutes. La décision
  `death_guard rescued` dit `a fatal blow of C-70's zombie (mob_attack): the Narrator's creatures never kill`. La
  créature reste au module : elle vise, poursuit et frappe toujours ; le bouclier la tient à l'écart du joueur sauvé
  pendant 5 minutes (`CreatureShield.spares`), puis elle peut reprendre si elle le vise encore.
- **Un seul chemin** : `ModuleCreatures.allowDeath` décide pour toute créature d'un module, invoquée ou réclamée (une
  réclamée n'a pas de marque `narrator2:mob` : `CreatureShield.boundTo` ne pouvait pas la couvrir). Ce qui ne change pas :
  une mort d'**une autre cause** (lave, chute) à 16 blocs d'une créature d'un module est un sauvetage hors quota pour un
  passant, et va à la garde de mort, armée par l'attaque, pour une cible (comme pour les créatures de `spawn` du
  Directeur, M4-LEFTOVERS). `creature_attack` arme toujours la garde de mort, pour ces morts-là.
- **`CreatureShield.bound` n'a plus d'exception pour les modules.** La marque est retirée à la source : quand un module
  fait sienne une créature (`CreatureControl.take`, donc `adopt`, `claim`, `adoptLoaded`), une marque `punishment`
  (`spawn`, posée par l'outil `spawn` sur une créature hostile envoyée sur un joueur) est effacée de sa pièce jointe
  `narrator2:mob`.

### Décision : pourquoi pas « retirer l'exception » au sens littéral

Retirer seulement la condition `!ModuleCreatures.isModules(entity)` de `CreatureShield.bound` aurait **lié** au joueur
près duquel elle est née toute créature qu'un `spawn` d'un module invoque (`NarratorMob.punishment = spawn`, M4-LEFTOVERS) :
- `creature_attack` n'aurait plus pu l'envoyer sur un autre joueur : `CreatureShield.keepsAway` l'écarte de tout joueur
  autre que le sien, l'outil répond `FAILED: the zombie would not take Bob as its target` (régression de « elle vise ») ;
- elle serait partie au départ ou à la mort de ce joueur (`NarratorMobs.retirePunishments`), sous les yeux du module ;
- et une créature **réclamée** aurait toujours pu tuer (pas de pièce jointe, donc jamais liée).

J'ai donc gardé le résultat demandé (plus d'exception dans `CreatureShield`, une seule règle pour les modules) en
déplaçant la vérité dans les données : une créature du module n'est plus la punition d'un joueur, sa pièce jointe le
dit. Entre l'invocation et l'adoption (au plus un tick), elle reste liée à son joueur, comme avant : son coup fatal
serait alors un sauvetage par le chemin de M4-POST. Rien d'autre ne lit `punishment` (vérifié : `CreatureShield.bound`
seul ; `NarratorMobs` passe par `boundTo`).

- **Paramétrage supprimé** : il n'y avait pas de drapeau dans le code ; le seul « réglage » était la branche « la mort de
  la cible va à la garde de mort » pour un coup de la créature (M5-07, écart 2), remplacée par le sauvetage. Pas de code
  mort : `isAimedAt` sert toujours pour les morts d'une autre cause.
- **`Guards.rescue` gagne `inPlace`** (défaut `false`, les appels des duels ne changent pas).

### Tests

- GameTest `ModuleActsGameTests.aModulesCreatureIsBoundToNobodyAndNeverKills` (lot `forge`), qui **remplace**
  `PunishmentCreaturesGameTests.aModulesCreatureIsBoundToNobody` (M4-LEFTOVERS, adapté et déplacé là où vit le vrai
  chemin d'un module) : un zombie marqué par `spawn` comme punition d'Ann est lié à Ann ; adopté par le module
  (`CreatureControl.adopt`), il n'est lié à personne ; `creature_attack` l'envoie sur **Bob** (un autre joueur) ; un coup
  qui ne tue pas blesse ; puis, Bob à 1 PV, le coup fatal porté par `doHurtTarget` passe par **les vrais crochets du
  serveur** (le moteur des mécaniques du serveur, puis `ModuleCreatures.allowDeath`) : refusé, Bob sauvé sur place (6 PV,
  même position, diamants gardés, ni mort ni compte, bouclier) ; le zombie reste au module, et reste quand Ann part.
- `ModuleActsGameTests.aModulesCreatureAimsAtItsTargetOnlyAndNeverKillsABystander` : l'assertion « its target's death
  is the death guard's » (un coup du zombie) devient « its target's death of another cause is the death guard's » (la
  lave) : le coup, lui, est maintenant sauvé (test ci-dessus).

## D. Fins et lisibilité du moteur (constats 4, 6, 7)

- **Une fin refusée par la mémoire** (`ModuleEngine.resolve`, appelé par `close`). Le premier `resolveCanon` (le statut
  de la fin pour une loi ou un plan, la note du module) refusé, le moteur **relit la ligne** (`MemoryStore.canon`) :
  - fermée déjà (un opérateur l'a close) ou absente : rien à fermer, une ligne `INFO`, plus de `WARN` ni de second essai
    voué à l'échec ;
  - encore ouverte : un second `resolveCanon` avec les mots neutres du moteur (`neutralNote`, M5-LOAD) **et le statut de
    la fin pour le genre de la ligne elle-même** (`law` → `retired`, sinon celui d'un plan). Cela couvre la `VoiceGuard`
    (le cas de M5-LOAD, inchangé) et le cas restant du constat 4 : un statut que le genre de la ligne refuse.
  - Reste ouvert seulement : un second refus inconnu ou une mémoire qui ne peut pas écrire (`JournalUnavailable`…). Le
    message le dit désormais exactement : « the reconciliation of the next start closes it » (elle ne tourne qu'au
    démarrage). Une nouvelle tentative plus tard n'a pas été ajoutée (pas de cas connu).
- **`offAtStart` une seule fois** : `begin` note le coupe-circuit trouvé au démarrage (`haltAtStart`, seulement s'il y a
  des déploiements dans l'état) ; la première seconde l'applique et l'efface. `second()` ne lit plus `forgeHalt` à chaque
  seconde. Le moment ne change pas (la première seconde). Effet de bord voulu : si un opérateur rallumait la Forge avant
  la première seconde, ces déploiements, qui n'ont pas été reconstruits, finissent `forge_off` au lieu d'attendre leur
  échéance sans tourner.
- **`teleport`** : la dimension du joueur est lue une fois, et seulement quand il y a un lieu.

Tests (`ModuleEngineTest`, la fixture `ModuleFixture` refuse maintenant, comme le vrai store, une ligne déjà close et un
statut que son genre refuse) :
- *a status the row's kind refuses still closes its canon, with the ending's status for that kind* : un plan dont la
  ligne est une loi (`fulfilled` refusé) finit `retired` avec les mots du moteur ;
- *a canon row closed before its module ends stays as it was, without a warning* ;
- *with the Forge switched off as the server starts…* : trois secondes, une seule ligne `MODULE`, une seule décision
  `module_ended` ;
- *a reason in the first or second person…* : le texte du `WARN` a changé (« refused its end »).
- `ActQueueTest` › *a module moves a player within their own dimension only* : les lectures de dimension sont
  comptées (deux déplacements → deux lectures, aucun sans lieu).

## E. La parade instable (constat 5)

### La cause, mesurée

Une sonde (non commitée, retirée) relevait la file à **chaque tick** pendant 8 s après l'arrêt de Bob : la position de
chaque poule par rapport à lui, la file « gloutonne » du test (`line` : la poule la plus proche de Bob, puis la plus
proche de celle-ci…) et la meilleure file (le meilleur ordre des trois poules). 8 lancements, dont 7 sous 32 fils de
calcul (méthode FLAKY-4) et les builds d'autres agents (un lancement a duré 13 min) :

| | file gloutonne > 3,5 entre t = 100 et 160 ticks | meilleure file > 3,5 | pire meilleure file |
|---|---|---|---|
| 7 lancements | 3 lancements : **20**, 8 et 2 ticks d'affilée | 1 lancement : **7** ticks d'affilée | 3,98 (sinon 1,6 à 3,5) |

Deux causes, aucune n'étant la machine (le serveur de GameTest tique sans frein ; tout est compté en ticks) :
1. **L'ordre glouton** : après l'arrêt, une poule se tient souvent un pas **devant** Bob (son « propre côté » dans le
   module, ou une flânerie). La plus proche de Bob est prise d'abord, la dernière liaison traverse alors toute la file :
   dans un lancement, la file gloutonne était rompue 20 ticks de suite quand la meilleure ne l'était jamais.
2. **La flânerie des poules** (`RandomStrollGoal`) : entre deux pas de la parade (10 ticks), une poule à moins de deux
   blocs de celle qui la précède ne reçoit pas de `moveTo` et peut s'éloigner ; le pas suivant la ramène. La file se
   rompt donc parfois quelques ticks (7 au plus mesuré), puis se referme. À l'arrêt, une poule peut aussi rester 2,4
   blocs à l'écart pendant des secondes (`moveTo` s'arrête un bloc avant sa place) : 3,5 est donc juste, pas trop large.

Le test d'avant jugeait **un seul tick** (t ≈ 100) avec la file gloutonne : il échouait quand ce tick tombait dans une
rupture, environ 6 % des ticks de la fenêtre dans ces mesures.

### La correction (le test seul ; le module et le moteur ne changent pas)

- **La meilleure file** (`file`) : sur les 6 ordres des poules, celui dont la plus longue liaison (Bob → première →
  suivante…) est la plus courte. Quel que soit l'ordre que le module tient (celui où elles sont devenues siennes, que le
  test ne voit pas), sa file a une liaison au moins aussi longue : la propriété reste « en file indienne, chacune à
  3,5 blocs au plus de celle qui la précède ».
- **Surveillée à chaque tick pendant 3 s** (`WATCH_TICKS` = 60, six pas de la parade) après les 5 s où elle se resserre
  (`SETTLE_TICKS`, inchangé), au lieu d'un seul instant : la file doit tenir, **chaque rupture réparée en moins d'une
  seconde** (`MEND_TICKS` = 20 ticks, deux pas de la parade ; 7 mesurés au plus). C'est plus exigeant qu'avant sur la
  durée (60 ticks jugés au lieu d'un) et cela prouve toujours que la file suit Bob : les trois mêmes poules, chacune plus
  près de l'arrivée de Bob que de son départ (inchangé), et en file derrière lui pendant trois secondes.
- L'attente « une nouvelle poule dans la file » (après la mort d'une poule) prend la même meilleure file.
- La ligne `M5-15 in the server: parade…` donne la plus longue liaison sur la fenêtre, les ticks rompus et la plus longue
  rupture : à suivre si la parade venait à changer.

Les autres pistes de la relecture : élargir la borne aurait affaibli la propriété à chaque tick sans traiter l'ordre
glouton ; faire marcher Bob plus lentement ne change rien à l'après-marche, où naissent les ruptures.

## Pour la suite

- Une créature d'un module qui tue par une autre cause (pousser dans la lave n'est pas possible : elle ne pousse
  personne ; une chute en fuyant l'est) passe toujours par la garde de mort de sa cible : c'est la même règle que pour
  les créatures de `spawn` du Directeur.
- Après un sauvetage sur place, la créature garde sa visée (60 s pour `creature_attack`, la durée de vie pour `spawn`) ;
  le bouclier de 5 minutes l'écarte du joueur. Un module qui guette `PlayerDied` de sa cible n'en verra jamais par le
  coup de ses créatures : `sdk.md` le dit (« never kill (a fatal blow is a rescue) »).
- `sdk.md` : 91 caractères de marge. Le point 3 de `M5-22.md` (« `sdk.md` ne dit pas que `hud` n'entre pas dans les 30
  actes par minute… transmis à M5-FIX-2 ») est réglé ici (lot B).
- `PunishmentCreaturesGameTests` perd un test (déplacé dans `ModuleActsGameTests`) : le nombre de GameTests du lot par
  défaut baisse d'un, celui du lot `forge` monte d'un.

## Vérifications

- **Sondes** (non commitées, retirées ; chacune fait échouer le test qui la vise, puis le code est rétabli) :
  - C1, l'ancienne règle (le coup fatal sur la cible va à la garde de mort) : `aModulesCreatureIsBoundToNobodyAndNeverKills`
    rouge, « the fatal blow is refused », Bob mort et compté (`counted … a death caused by the Narrator… 1 of 1`) ;
  - C2, le retrait littéral de l'exception (pas de marque effacée à l'adoption) : rouge, `creature_attack` répond
    `FAILED: the zombie would not take … as its target` ;
  - A (`tasks` sur `anyWorker`) : *with the Journal down and the Forge up…* rouge ;
  - D (l'ancien second essai, sans relecture de la ligne) : *a status the row's kind refuses…* et *a canon row closed
    before its module ends…* rouges ; `teleport` qui relit la dimension : *a module moves a player…* rouge ;
  - E : la sonde de mesure du §E (la file gloutonne y était rompue jusqu'à 20 ticks de suite).
- **La parade sous charge** (méthode FLAKY-4 : 32 fils de calcul sur 16 processeurs logiques, plus les builds des autres
  agents ; un lancement `runGameTest` filtré par lancement, `JAVA_TOOL_OPTIONS=-Dfabric-api.gametest.filter=
  narrator2-gametest:arsenal_creatures_game_tests_the_parade*`), sur le code final fusionné avec `main` (CLAUDE-FALLBACK) :
  **10 lancements sur 10 verts** (97 à 476 s chacun). Dans la fenêtre surveillée (68 à 84 ticks), la plus longue liaison
  allait de 1,2 à 3,4 blocs, et la file n'a été rompue à aucun tick.
- Les GameTests touchés, lancés seuls avant la fusion : `ModuleActsGameTests` 8/8, `PunishmentCreaturesGameTests` 4/4,
  la parade 1/1. La décision du sauvetage par les vrais crochets du serveur : `rescued …: a fatal blow of C-990708's
  zombie (mob_attack): the Narrator's creatures never kill: kept where they stood (…), …, 6.0 health, …; not counted
  against the Narrator's quota`.
- Tests unitaires ciblés (`TaskToolsTest`, `ModuleEngineTest`, `ActQueueTest`, `SdkDocTest`, `PromptsTest`,
  `ModuleBookTest`, `PromptToolMentionsTest`, `ToolCatalogTest`, `CreatureControlTest`, `MechanicsServiceTest`,
  `WorkersTest`) : 116 verts.
- `git merge main` deux fois (CLAUDE-FALLBACK, puis M5-22), sans conflit.
- **`.\gradlew.bat build --continue`** sur l'état final (fusionné avec M5-22) : **vert** en 5 min 19 s, **1 956 tests
  unitaires** (0 échec, 0 ignoré) et **« All 393 required tests passed »** (GameTests) ; la parade y avait une plus
  longue liaison de 2,2 à 3,4 blocs sur 62 ticks, jamais rompue.
- Aucun appel réel à Claude ; pas de `runServer`. Journaux de build sous des noms propres à la tâche (`m5-fix1-*`).
