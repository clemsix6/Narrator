# Passation M7-LOOSEN : des garde-fous desserrés, un Narrateur plus sévère

Passations lues : M7-FIX, M7-BALANCE, et celles de M4 sur les châtiments. **En cas de contradiction, le code et cette
passation font foi.**

Le propriétaire, le 22/10 au soir : « non mais tes guardrails sont trop stricts en fait ». Sur son serveur, un
`take_item` refusé `already_punished` (« Thawyn is already being punished »), et un champion rendu inoffensif 5 min par
le bouclier d'un sauvetage. La confiscation toujours temporaire est l'affaire de M7-REVOKE : je n'ai touché ni à
`take_item` ni à `ItemLedger`.

## 1. La nouvelle table (`guard/Caps.kt`)

Niveaux 0 à 2 inchangés (le réglage doux), sauf la nouvelle colonne des châtiments par heure.

| Plafond | 0 | 1 | 2 | **3** | 4 | 5 |
|---|---|---|---|---|---|---|
| Effet ou attribut nuisible, durée max (s) | – | 60 | 120 | **600** (300) | 900 (600) | 1 800 (900) |
| Niveau max d'un effet nuisible | – | 1 | 1 | **3** (2) | 4 (3) | 5 (4) |
| Invocations par action | – | 2 | 4 | **10** (6) | 14 (10) | 20 (12) |
| Créatures du Narrateur vivantes | – | 4 | 8 | **24** (16) | 32 (24) | 48 (32) |
| Puissance d'explosion | – | 1 | 2 | **4** (3) | 4 | 4 |
| Foudre (cœurs) | – | 1 | 2 | **4** (3) | 5 (4) | 5 |
| Propulsion | – | 1 | 1,5 | **2,5** (2) | 3 (2,5) | 3 |
| Téléportation (blocs) | – | 50 | 200 | **3 000** (1 000) | 5 000 (3 000) | 10 000 (5 000) |
| Objets confisqués par action | – | 8 | 16 | **64** (32) | 128 (64) | 128 |
| Morts causées par heure jouée | 0 | 0 | 0 | **2** (1) | 3 (1) | 5 (2) |
| Sévérité max de `punish` | – | 2 | 3 | **5** (4) | 5 | 5 |
| Repos d'une catégorie (min) | – | 60 | 45 | **15** (30) | 10 (20) | 5 (10) |
| Châtiments d'un joueur par heure (`punishmentsPerHour`, nouveau) | 0 | 4 | 6 | **8** (4) | 12 (4) | 20 (4) |

Entre parenthèses, l'ancienne valeur. Le serveur du propriétaire est au chaos 3.

**Schémas des outils** : `apply_effect.seconds` et `set_attribute.seconds` montent à 1 800 (au lieu de 900),
`spawn.count` à 20 (au lieu de 12). Sans ça, les plafonds du niveau 5 étaient inatteignables. Les effets bénéfiques
restent à 900 s (`Caps.BENEFICIAL_SECONDS`) : le code refuse au-delà. Les schémas de l'explosion (4), de la foudre
(5 cœurs), de la propulsion (3) et de `take_item` (128, à M7-REVOKE) ne changent pas. Les modules de la Forge et les
lois gardent leurs propres bornes (900 s, `Spawn.of` 1 à 12).

## 2. Ce qui est assoupli

- **Les punitions s'empilent** (`TargetingPolicy`). Le refus « une punition opposée par un autre acte court encore »
  n'existe plus. L'épisode du nouvel acte prend la place de l'ancien, avec l'échéance la plus tardive (`Episodes.open`,
  et l'épisode sauvé sur le joueur dans `GuardsImpl.hostile`). `already_punished` ne sert plus qu'à un second `punish`
  du **même** acte contre le même joueur (constat 16 de M4, gardé : un acte compose une punition du catalogue, il en
  empile d'autres avec les outils bruts ou au tour suivant). Message : « … compose it with the other tools, or punish
  again in another turn. »
- **Le plafond par heure dépend du chaos** : `Caps.punishmentsPerHour` remplace `TargetingPolicy.MAX_PUNISHMENTS_PER_HOUR`
  (supprimé). Compte comme nouvelle punition toute action hostile qui ne prolonge pas l'épisode de **son** acte : celle
  d'un autre acte, empilée, compte donc. `Guards.pacing` donne ce plafond, et la ligne `Pacing` de la situation l'affiche
  (`punished this hour Bartok 2/8`).
- **Une minute de répit** au lieu de cinq : `TargetingPolicy.NO_PILE_ON_MINUTES = 1`, et donc `Guards.RESCUE_SHIELD_MS`
  (le bouclier d'un sauvetage : la règle du « passant » des modules, le sauvetage au-delà du quota, le coup fatal d'un
  duel, celui des créatures d'une punition). Les créatures du Narrateur et ses explosions épargnent un joueur mort ou
  sauvé pendant une minute seulement. `SituationReport.NO_PILE_ON_MS` lit désormais la constante de `TargetingPolicy`
  au lieu de recopier 5 min.
- **Le prompt `director.md`** (34 989 caractères avant la fusion de M7-REVOKE, contre 34 998 ; 34 955 après, la
  clause « aucune perte définitive d'objet » étant retirée par M7-REVOKE) :
  - « La proportion » : plus de « bref (quelques minutes) » ni de « tuer est exceptionnel » ; une faute grave ou répétée
    appelle une peine lourde, les peines s'empilent, tuer « se mérite » ;
  - « Pas d'acharnement » devient « Pas d'acharnement gratuit » : la taquinerie pour qui vient de mourir ou enchaîne les
    malheurs sans les chercher, une punition de plus pour qui récidive ;
  - la carotte et le bâton : « un joueur qui s'assagit après une série de punitions mérite ensuite autre chose » (au
    lieu de « si tes dernières interventions sont toutes des punitions, la suivante devrait être autre chose ») ;
  - les refus du moteur : « mort depuis moins d'une minute », et plus de « déjà puni par un tour précédent ».
- Textes alignés : descriptions de `punish` (« already punished by this turn … punishments of different turns stack »)
  et de `deploy_module` (« too often » au lieu de « already punished »), `forger.md` (« joueur trop souvent puni »), le
  KDoc du SDK (`Acts.java`) et le texte simulé de `gravity_storm.java`.

## 3. Ce qui reste absolu (non touché)

- Aucune destruction de construction : `BlockRestorer` seul chemin d'écriture, dans l'air, temporaire ; les explosions
  du Narrateur ne touchent aucun bloc (`ExplosionGuard`), et leur puissance reste à 4 au plus.
- `CommandPolicy` inchangé : `stop`, `op`, `ban`… interdits.
- Les joueurs protégés par un opérateur, la grâce, les duels (sans mort, sans action hostile contre un duelliste).
- Les plafonds d'entités (48 vivantes au plus au chaos 5) et d'explosions.
- Le quota de morts (garde de mort, inventaire gardé, sauvetage au-delà), avec les nouvelles valeurs.
- Le délai par catégorie (`same_punishment`), raccourci.

## 4. Pour les tâches suivantes

- **`take_item` permanent (M7-REVOKE) sur un objet qu'un autre tour a déjà confisqué** : `already_punished` ne le
  refuse plus. Mais les deux confiscations sont de la catégorie `inventory` : tant que celle-ci se repose (15 min au
  chaos 3, 10 au chaos 4, 5 au chaos 5, depuis la première confiscation), un autre tour reçoit `same_punishment`. Le
  même tour, lui, passe toujours. Si le propriétaire veut pouvoir rendre définitive, plus tard, une confiscation en
  cours, il faudra exempter de ce délai une saisie qui ne prend que des objets déjà tenus (par exemple en la passant en
  catégorie `other`). Je ne l'ai pas fait : `take_item` n'était pas dans mon périmètre.

- **Retrait d'un `punish` qui n'a rien fait** (`Episodes.withdraw`) : si un autre acte a empilé une punition entre-temps,
  rien n'est remis en arrière (on n'efface pas ses traces) ; le `punish` raté reste alors compté, et l'acte peut
  punir de nouveau. `withdraw` rend `false`, et aucune décision `guard withdrawn` n'est écrite.
- **Cas limite connu** : si l'acte A punit, puis l'acte B empile, puis A agit encore contre le même joueur (un tour du
  Directeur interrompu par un déclenchement de règle), l'action de A ouvre un nouvel épisode et compte une punition de
  plus. Rare, et au pire plus prudent.
- Les situations figées du rejeu (`test/resources/narrator2/replay/*.json`) montrent encore `0/4` et « until … (died) »
  à 5 min : ce sont des archives, non modifiées.

## 5. Vérifications

- Tests unitaires : `CapsTest` (la nouvelle table ; les plafonds restent dans les schémas des outils),
  `DeathQuotaTest` (2, 3, 5 morts), `TargetingPolicyTest` (deux punitions de deux tours qui s'empilent, l'échéance la
  plus tardive ; le plafond de l'heure à 8 au chaos 3, 12 au chaos 4, 20 au chaos 5, 4 au chaos 1 ; une minute de
  répit ; un retrait qui laisse la punition empilée), `SituationReportTest`, `ModuleToolsTest`, et les tests figés sur
  les anciennes valeurs du chaos 3.
- GameTests adaptés : une seconde punition d'un autre acte est désormais refusée par `same_punishment` quand elle frappe
  la même catégorie (explosion, créatures, gravité de `gravity_storm`, `slow_hands` du gage d'un défi, `tree_top`), et
  acceptée sinon (`GuardPolicyGameTests.oneTurnOnePunishmentAndAnotherStacksThroughTheGuards`, `PlayerActionGameTests`).
- Build : voir le rapport de la tâche.
