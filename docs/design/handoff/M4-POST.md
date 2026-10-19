# Passation M4-POST : les retouches de la recette de M4

Ce lot corrige les constats 1 à 5 de la recette réelle de M4 (`M4-24.md`), selon les décisions du superviseur, et rend
`MechanicsEndToEndTests` indépendant de l'ordre des lots de GameTests (signalé par M5-01). **En cas de contradiction
avec `M4.md` ou une passation antérieure, le code et cette passation font foi.**

Branche `worktree-agent-a9b412ae1ca4d6ce8`, `main` (M5-01, `2126ffc`) fusionné.

## Ce qui a changé

| Fichier | Changement |
|---|---|
| `guard/NarratorMobs.kt` | `NarratorMob.punishment` (champ optionnel `punishment` de la pièce jointe `narrator2:mob`) ; `mark(…, punishment)` ; `retirePunishments(player, now, why)` ; `retireFor` et lui partagent `retireWhere` |
| `guard/CreatureShield.kt` | `boundTo`, `punishmentOf` ; `keepsAway`, `allowsDamage` et `blocksEffect` écartent les autres joueurs des créatures d'un châtiment |
| `guard/GuardsImpl.kt` | `allowDeath` (le coup fatal d'une créature d'un châtiment devient un sauvetage sur place) ; `onDeath` et `onLeave` renvoient les créatures des châtiments du joueur ; `activeOn` ne liste plus les offrandes |
| `guard/DeathGuard.kt` | `rescue(…, inPlace)` : le sauvetage sans déplacement (`IN_PLACE`, « kept where they stood ») |
| `guard/GuardModule.kt` | `ALLOW_DEATH` appelle `GuardsImpl.allowDeath` (une ligne) |
| `tool/action/WorldActionTools.kt` | `SpawnTool.run(ctx, args, punishment)` ; `enter(…, punishment)` |
| `tool/action/PunishTools.kt` | les étapes `spawn` passent leur châtiment ; description de `punish` |
| `tool/mechanics/RuleTools.kt`, `mechanics/MechanicsTypes.kt` | description de `create_rule` ; `@Doc` de `when.entity`, `block`, `item` |
| `reflex/HeuristicReflexes.kt`, `resources/narrator2/reflex/heuristics_fr.txt` | catégorie `BOREDOM` (`[boredom]`) et `bored(text)` |
| `director/WakeScheduler.kt`, `director/Director.kt` | une ligne d'ennui vaut `BORED_WORTH` (0,5) au battement |
| `tool/speech/SpeechLimiter.kt` | `forgetTheMinute()` |
| `prompts/director.md` | deux phrases, une coupe (voir plus bas) |
| `docs/ARCHITECTURE.md` §17 | une phrase : un châtiment du catalogue ne tue jamais |
| tests | `PunishmentCreaturesGameTests` (nouveau, une ligne dans `fabric.mod.json`) ; `GuardFavorGameTests`, `MechanicsEndToEndTests` (une ligne chacun) ; `WakeSchedulerTest`, `HeuristicReflexesTest`, `SpeechLimiterTest`, `MechanicsContractsTest` (part des arguments : 9 400) ; échantillon `boredom` dans `chat-triage-eval.json` |

## Constat 1 : les créatures d'un châtiment ne tuent jamais

### Le marquage

`punish` passe son châtiment à `spawn` (`SpawnTool.run(ctx, args, punishment)`, une surcharge interne : le schéma de
`spawn` ne change pas). Chaque créature (et sa monture ou son cavalier) porte alors `NarratorMob.punishment`
(`guests`, `slime_rain`), sauvé avec elle ; une conversion ou une division (`NarratorMobs.inherit`) le transmet. Le
`spawn` du Directeur, lui, ne marque rien : ses créatures se comportent comme avant (voir « Pour la suite »).

### Le ciblage et les coups

`CreatureShield.boundTo(entity)` rend la marque d'une créature d'un châtiment (ou de ce qu'elle possède). Pour un autre
joueur que le sien :
- **ciblage** (`keepsAway`, appelé par `MobTargetGuardMixin` et `TargetingConditionsGuardMixin`) : ce n'est pas une
  cible valable, ni par `setTarget`, ni par les buts de recherche, ni par la vengeance (`HurtByTargetGoal`). Un passant
  qui frappe un invité n'est pas poursuivi ;
- **dégâts** (`allowsDamage`, `ALLOW_DAMAGE`) : tout coup est refusé, même un coup destiné au joueur puni qui toucherait
  un autre ;
- **effets** (`blocksEffect`) : refusés de même.

### La mort refusée

`GuardsImpl.allowDeath` passe avant la garde de mort (`ALLOW_DEATH`, après les duels du moteur) : si le coup fatal vient
d'une créature d'un châtiment (`CreatureShield.punishmentOf(source)`), les créatures des châtiments du joueur sont
renvoyées, puis la mort est refusée et devient un **sauvetage sur place** (`DeathGuard.rescue(…, inPlace = true)`) :
- le joueur reste où il est (le danger, les créatures, est parti), avec 3 cœurs, ses effets et modificateurs nuisibles
  levés, le feu éteint, de l'air, pas de dégâts de chute 30 s, et le **bouclier de 5 min** (`no_pile_on`) ;
- rien n'est compté dans le quota, la marque de mort protégée n'est pas posée, rien n'est perdu (il n'y a pas de mort) ;
- décision `death_guard rescued` : `punish guests: a fatal blow of its creatures, 3 minecraft:zombie sent away; a
  punishment never kills: kept where they stood (x …), fire put out, air given, 6.0 health, …`.

**Décision : sur place plutôt qu'au point de réapparition.** Le sauvetage habituel ramène le joueur chez lui parce que
le danger est là où il se trouve (lave, vide, noyade). Ici le danger, ce sont les créatures, et elles sont parties :
renvoyer chez lui un joueur qui explorait serait une punition de plus. Le reste du sauvetage est le même.

Ce qui ne change pas : une mort d'une autre cause pendant le châtiment (le joueur fuit dans la lave) reste attribuée par
la garde armée (`Peril.ANY`, 2 min pour `guests`) : comptée dans le quota, inventaire gardé, sauvetage au-delà. Le
superviseur a tranché « de leurs coups » ; à revoir si l'on veut qu'aucune mort pendant un châtiment du catalogue ne
compte.

### Les créatures disparaissent

`NarratorMobs.retirePunishments` renvoie (comme à leur échéance : cavaliers posés, objets confiés rendus) les
créatures des châtiments chargées du joueur :
- à sa **mort**, quelle qu'en soit la cause (`GuardsImpl.onDeath`) ;
- à sa **déconnexion** (`GuardsImpl.onLeave`) ;
- au **coup fatal refusé** (ci-dessus).

À la **fin de l'épisode**, rien de nouveau : leur durée de vie est celle du châtiment (2 min pour `guests`, 1 min pour
`slime_rain`), et un pardon les renvoie déjà. Une créature dans un tronçon déchargé part à son heure, comme avant.

### Vérifications (constat 1)

GameTests `PunishmentCreaturesGameTests` (lot par défaut) :
- `theGuestsKillNobodyStandingTwentyBlocksAway` : **le scénario de la recette**, dans `oracle_field` (32 × 32, ciel
  ouvert) : `guests` sévérité 2 sur Pistache, deux joueurs immobiles à 20 blocs, 30 s de jeu, chaque joueur « tické »
  comme par un client (recharge entre deux coups, soins). Aucun invité ne vise un passant, aucun passant n'est touché ni
  ne meurt ; les invités atteignent Pistache, qui est sauvé, pas tué, et rien n'est compté.
- `aGuestsFatalBlowIsARescueAndAPasserByIsNeverItsTarget` : coup par coup. `setTarget(passant)` est refusé, le coup
  d'un invité sur un passant ne fait rien ; le coup fatal sur Pistache (1 PV) : vivant, 6 PV, au même endroit, ses
  5 diamants, ni mort ni compte, un bouclier, les invités partis.
- `theGuestsGoWithThePunishmentAndWithTheirPlayer` : partis à la fin du châtiment (horloge des garde-fous du banc), à la
  mort de leur joueur (un `/kill`), à sa déconnexion ; ceux des autres restent.

**Les tests échouent sans le correctif** (sonde, non commitée) : avec `boundTo` neutralisé et sans renvoi des
créatures, les trois échouent (« no guest ever went for a passer-by: expected [], got [tp_…, tp_…] », « a passer-by is
no target », « gone with their player's death ») ; avec le seul renvoi actif, le scénario échoue sur « Pistache alive ».

## Constat 2 : une offrande n'est plus un « effet actif »

`GuardsImpl.activeOn` ne liste plus les entrées `offering` du registre : ce n'est pas un effet du Narrateur, mais le don
d'un joueur qui attend sa réponse, et la ligne `Offerings: O-… (answer by …)` le dit déjà. La situation, le briefing,
`inspect_player` et le résumé de `/narrator guards` ne la montrent donc plus parmi les effets ; `/narrator guards
<joueur>` la montre toujours, avec sa ligne de registre. Une mise (`stake`) et un dû (`owed`) restent listés à part, sous
leur but (non tranché : voir « Pour la suite »).

Test : `GuardFavorGameTests.theSameKindOfPunishmentRestsBetweenTwoTurns` (une offrande dans `pacing.offerings`, aucune
dans `activeOn`).

## Constats 3 et 4 : `create_rule`

- `when.block` et `when.item` ont `@Doc("id or #tag")`, et `when.entity` finit par « : id or #tag. » (le moteur
  accepte déjà les tags pour ces trois champs, `RuleIdKind`).
- La description dit que `mob_effect` et `mob_attribute` refusent sur une créature « levitation, jump_boost nor a
  lighter gravity: a creature never falls » (ce que refusent `MobEffectTool.FORBIDDEN` et `MobAttributeTool.bounds` ;
  le L1 de la recette avait perdu deux pas sur la lévitation puis la gravité).
- La description de `punish` dit que `guests` et `slime_rain` sont des « creatures after that player alone, who never
  kill ».

Budget des sept définitions du Directeur (`ToolCatalogTest`) : **13 328 / 14 000** (`create_rule` 3 522 → 3 664,
`punish` 1 385 → 1 440 ; `propose_duel` était déjà à 2 195 avant ce lot).

## Constat 5 : l'ennui réveille au battement

Le résumé d'activité que lit Haiku au battement ne contient pas le chat : « Moi je m'ennuie un peu, on fait quoi
après ? » ne valait qu'une ligne de bavardage (0,125). **Décision : l'heuristique, pas le prompt.** Une catégorie
`[boredom]` de `heuristics_fr.txt` (« je m'ennuie », « on s'ennuie », « on fait quoi », « c'est mort ici », « y a rien à
faire », « jsp quoi faire », « il se passe rien », « on se fait chier », « trop calme »…, à la première personne ou avec
« on » : « ça m'ennuie de… » et « tu m'ennuies » ne comptent pas). `WakeScheduler.salience` compte une ligne de chat
entre joueurs qui en contient une à `BORED_WORTH` = 0,5, le seuil : **une seule réveille le Directeur au battement**,
quel que soit le verdict de Haiku sur l'activité. La décision du battement le dit : `worth 0.75 by 3 salient items
waiting (1 saying a player is bored) (reflex:claude-haiku-5-5 said 0.15)`. Rien ne change hors des battements : une
ligne d'ennui ne réveille pas le Directeur sur-le-champ, et une ligne adressée réveillait déjà.

`reflex.md` est inchangé : aucun appel réel n'a été fait.

Mesure sur l'échantillon d'éval des réflexes (`chat-triage-eval.json`, nouvel échantillon `boredom` de 16 lignes,
10 d'ennui et 6 pièges ; `LiveReflexEvalTest` ne le lit pas) : **16/16 justes, et aucune des 52 lignes de l'éval n'est
prise pour de l'ennui** (`HeuristicReflexesTest`, *boredom is told on the evaluation set, without a false alarm*). La
soirée de la recette est rejouée par `WakeSchedulerTest`, *a player who says they are bored makes the heartbeat worth a
wake-up* : 0,75 au lieu de 0,38.

## `director.md` : 33 986 / 34 000

Les lignes 1 à 54 (la voix) sont intactes.
- « Une offrande » : « Tu ne réclames jamais d'offrande, **pas même par allusion**, et tu ne punis jamais… ».
- Après « Pour un châtiment, agis d'abord… » : « Pas de vengeance programmée pour la fin d'une protection (mort récente,
  grâce, sauvetage) : aucun châtiment calé sur son expiration. » La « mort récente » couvre le cas de la recette (le
  rappel R-4 calé sur la fin des 5 minutes après la mort de Pistache).
- Coupe, dans « La situation » : « et le nombre de morts que tu as encore le droit de causer à chacun » devient « et les
  morts que tu peux encore causer à chacun ».

## `MechanicsEndToEndTests` et l'ordre des lots

Le Directeur du serveur parle par la limite de parole du serveur (`ToolLimits.speech`), commune à tous les lots : quand
le lot `director` passait juste avant, il avait dit ses lignes de la minute et les félicitations du test étaient
refusées (`DENIED (speech_limit): 8 lines already in the last minute`). Le test ne peut pas avoir son propre limiteur (il
joue le vrai Directeur du serveur) : il **vide la fenêtre de la minute** au départ,
`runtime.toolLimits.speech.forgetTheMinute()`, une fois le Directeur réveillé et calme. `forgetTheMinute` oublie les
lignes et les sons de la minute glissante, comme un redémarrage ; les délais par joueur et les comptes par tour restent.
La limite de production ne change pas (aucun appelant en production). Test : `SpeechLimiterTest`, *forgetting the minute
frees its lines and sounds, not a turn's count*.

Preuves (filtre `JAVA_TOOL_OPTIONS=-Dfabric-api.gametest.filter=…`) :

| Passe | Contenu | Ordre des lots | Résultat |
|---|---|---|---|
| sonde A (non commitée) | `mechanics_end_to_end_tests_*`, la fenêtre remplie de 8 lignes juste avant `forgetTheMinute` | – | vert |
| sonde B (non commitée) | la même, **sans** `forgetTheMinute` | – | rouge, l'échec de M5-01 : `say … DENIED (speech_limit): 8 lines already in the last minute`, puis « timed out waiting for: the congratulations » |
| 1 | `*_a_cha*_the_director*` (le test de bout en bout du lot `director` et celui-ci) | **`director` juste avant `mechanics_e2e`** (5 répliques du Directeur dans la minute) | vert |
| 2 | le même filtre | `mechanics_e2e` puis `director` | vert |
| 3 | `build` complet | `rules` … `mechanics_e2e` … `director` | GameTests verts (358) ; un test unitaire rouge, sans rapport (`MechanicsContractsTest`, voir « Vérifications ») |
| 4 | `build` complet, final | `mechanics_commands`, `mechanics_e2e`, `director`… | vert |

Le filtre `*_a_cha*_the_director*` ne retient que `director_end_to_end_tests_a_chat_wakes_the_director_…` et
`mechanics_end_to_end_tests_a_challenge_alaw_…_through_the_director` : deux lots seulement, dans un ordre tiré au sort,
pour obtenir l'ordre `director` → `mechanics_e2e` une fois sur deux.

## Pour la suite

- Le `spawn` du Directeur (créatures envoyées sur un joueur, hors catalogue) garde l'ancien comportement : ses créatures
  peuvent viser et blesser un passant, et leur coup fatal est une mort du Narrateur (dans le quota). Pour les lier aussi
  à leur joueur, il suffit de passer une marque à `enter` depuis `SpawnTool.run` (le `player` est déjà là).
- Une mise (`stake`) ou un dû (`owed`) apparaît encore dans « Your active effects », sans son but dans la ligne de la
  situation (`Bartok 2x minecraft:emerald until …`) : à retirer comme les offrandes si le Directeur s'y trompe.
- Une mort d'une autre cause pendant un châtiment du catalogue reste comptée (voir constat 1).
- `forgetTheMinute` est un appui pour les GameTests qui jouent le Directeur du serveur ; un autre test de bout en bout
  qui fait parler ce Directeur devrait l'appeler de même.

## Vérifications

- `git merge main` (M5-01, `2126ffc`) au départ, en avance rapide ; `main` n'a pas bougé depuis.
- **`.\gradlew.bat build` : vert**, **1 554 tests unitaires** (0 échec, 0 ignoré) et **« All 358 required tests
  passed »** (355 + les 3 de `PunishmentCreaturesGameTests`).
- Un premier `build` a buté sur `MechanicsContractsTest` : les arguments des sept outils prennent 9 327 caractères pour
  une part de 9 300 (les `@Doc` des tags). La part passe à 9 400 ; le budget qui compte, celui des définitions entières
  (14 000, `ToolCatalogTest`), est tenu à 13 328.
- Tests ajoutés ou changés : `PunishmentCreaturesGameTests` (3), `GuardFavorGameTests` (une assertion),
  `HeuristicReflexesTest` (*boredom is told on the evaluation set, without a false alarm* ; le numéro de ligne d'une
  section inconnue suit le nombre de catégories), `WakeSchedulerTest` (*a player who says they are bored makes the
  heartbeat worth a wake-up*), `SpeechLimiterTest` (*forgetting the minute…*), `MechanicsContractsTest` (la part).
- Sondes (non commitées, retirées) : sans le lien des créatures à leur joueur, les trois GameTests du constat 1
  échouent ; sans `forgetTheMinute` et la minute pleine, `MechanicsEndToEndTests` échoue comme chez M5-01.
- `director.md` : 33 986 caractères (`PromptsTest`), lignes 1 à 54 identiques.
- Aucun appel réel à Claude (ni `liveTest`, ni `LiveReflexEvalTest` : `reflex.md` n'a pas changé) ; pas de `runServer`.
- Pas de nouvelle signature de Minecraft (seulement `Mob.doHurtTarget`, `DamageSources.genericKill`/`mobAttack` et
  `LivingEntity.damageCooldownTime` dans les tests, vérifiés avec `.research/jp.ps1`).
