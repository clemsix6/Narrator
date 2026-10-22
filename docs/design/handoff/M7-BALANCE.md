# Passation M7-BALANCE : des champions qui menacent, des reliques à la mesure du joueur

Passations lues : M7-RELICS, M7-FIX, M7-BASE, M7-RELOAD. **En cas de contradiction, le code et cette passation font
foi.**

## 1. Ce qui s'est passé le 22/10 au soir

Retour du propriétaire : un champion lancé sur Thawyn ne l'attaquait pas, est mort en quelques coups de hache en bois,
et a rapporté une hache Tranchant IV en tout début de partie.

Sources : `logs/latest.log` du serveur de jeu, les transcriptions `armorer-3db7dc45` (champion) et `armorer-d1baef57`
(relique), et une **copie** de la base (tables `champions` et `damage`). Rien n'a été écrit dans le dossier du serveur.

**Le champion.** `/narrator champion Thawyn` sans consigne, lancé par clemsix6. L'Armurier a choisi un husk « Lumignon,
Syndic des Torches » : vie ×2, dégâts ×1,1, vitesse ×1,1, résistance au feu, épée en fer avec Aura de feu I, casque en
cuir Protection II et une torche. Avec le socle à ×1,5, il avait **60 PV** (20 × 2 × 1,5). La cible était bien Thawyn :
`spawn` reçoit `near = Thawyn` et `target = true`, et non l'opérateur. Thawyn portait un casque et des jambières en fer,
des escaliers en main, et une hache en bois dans l'inventaire. L'Armurier a appliqué la consigne du prompt : « un joueur
en fer, la vie x2 et pas plus ».

**Le combat**, d'après la table `damage` :

| Heure (Journal) | Quoi |
|---|---|
| 19:12:19 | le champion apparaît à 10 blocs |
| 19:12:24 à 19:12:31 | 7 coups de hache en bois de Thawyn : 41,7 dégâts en 7 s |
| ~19:12:31 | **un seul coup du husk : 16,5** (épée en fer, difficulté Hard ×1,5, socle ×1,25). Thawyn tombe en vie critique et brûle (Aura de feu). |
| 19:12:32 (log) | la brûlure est fatale. Les huit poules du module « parade » (C-30) sont à côté, donc la règle du « passant » des modules s'applique : **sauvetage** au point de réapparition, 6 PV, et « nothing hostile against them for 5m » |
| 19:12:36 | un coup de Thawyn (3) |
| 19:13:33 à 19:13:35 | 3 coups de Thawyn (dont un critique) et 1 de clemsix6 : le husk meurt |

**Pourquoi il n'attaquait plus.** Le sauvetage pose un bouclier de 5 minutes. Pendant ce temps,
`CreatureShield.keepsAway` rend le joueur invalide comme cible pour les créatures du Narrateur, et `allowsDamage`
refuse leurs coups. Le champion est donc resté planté, inoffensif, et Thawyn l'a achevé sans risque. Il avait pourtant
attaqué au début : sa seule frappe a failli tuer Thawyn. Au total, Thawyn a porté 11 coups en environ 10 s de vrai
combat, contre 60 PV.

**La relique.** L'Armurier a vu `<player>` (hache en bois, casque et jambières en fer, 6/20 PV) et le combat (1 min
17 s, 65,75 dégâts pris, 16,5 infligés). Aucune règle ne liait la récompense au niveau du joueur, et le prompt disait
seulement « pense à ce qu'il utilise ». Il a donc forgé une hache en fer Tranchant IV, Solidité III, Efficacité III, +2
dégâts.

**Trois causes**, donc :
1. un champion qui reste debout mais inoffensif quand son joueur est épargné ;
2. 60 PV face à une hache en bois, soit environ 10 s de coups ;
3. aucun plafond de récompense lié au joueur.

## 2. Ce qui change

### Un champion attaque toujours son joueur (`RelicsService.onSecond`, crochet `END_SERVER_TICK`)

Chaque seconde, pour chaque champion debout dont le joueur est en ligne, en survie et dans la même dimension :
- si le Narrateur épargne ce joueur (`CreatureShield.spares` : sauvé d'une mort, protégé, en grâce, en duel, mort
  récente), le champion est **renvoyé** (`NarratorMobs.retire`). Le Directeur reçoit « … is gone, unslain: its player
  is spared now (…): a champion never stands harmless ». Il n'y a pas de relique ;
- sinon, si sa cible n'est pas son joueur, il le reprend pour cible (`mob.target = player`).

C'est la cause du bug : on garde le bouclier du sauvetage, qui est une garantie de §17, et on retire le champion plutôt
que de le laisser debout. L'autre option, laisser le champion ignorer le bouclier, aurait cassé « rien d'hostile contre
un joueur sauvé pendant 5 min ».

### Le plancher de menace (`relics/Threat.kt`)

Une fois le champion habillé, son joueur en ligne (`dress` → `Threat.size`) :
- **PV** : au moins **20 s** de coups de la meilleure arme que le joueur porte, et au plus **60 s** de ces coups, avec un
  plafond absolu de **400 PV**.
  - Le calcul : dégâts × vitesse d'attaque, d'après le composant `attribute_modifiers` de l'objet sur la base du joueur
    (1 et 4), avec les enchantements contre ce champion (`EnchantmentHelper.modifyDamage`, donc Châtiment contre un
    mort-vivant). Un arc ou une arbalète compte pour 6 dégâts/s, et les poings pour 4.
  - L'inventaire entier compte : armure, main secondaire et sac.
  - Le socle de difficulté (×1 à ×10) est déjà dans les PV mesurés. Le plancher porte sur le total : avec un gros socle,
    le champion ne monte pas plus haut, et le plafond le ramène si besoin. Au socle ×10, un zombie à 200 PV contre des
    poings est ramené à 240 PV au plus.
  - Le modificateur s'appelle `narrator2:champion/threat_health` (`ADD_MULTIPLIED_TOTAL`, positif ou négatif), et le
    champion repart avec sa vie pleine.
- **Les coups.** Ils sont comptés une fois multipliés par le socle (`mobDamage`) et par la difficulté du monde (Hard
  ×1,5, comme `Player.hurtServer`), puis réduits par l'armure, la robustesse et les points de Protection du joueur. Les
  formules sont celles de `CombatRules` (`getDamageAfterAbsorb`, puis `getDamageAfterMagicAbsorb`).
  - **Plancher** : le coup nu, avant l'arme, prend au moins **3 PV**. Il monte par demi-points et plafonne à **10**.
  - **Plafond** : le coup entier, arme comprise, prend au plus **5 PV**. Il descend par demi-points.
  - Pourquoi ce plafond : le soir du bug, un seul coup du husk (épée en fer, Hard) a pris 15,6 des 18 PV de Thawyn. Et
    dans le premier essai réel, une épée en pierre sur un joueur nu prenait 8,75 PV, soit deux coups pour le tuer face à
    un champion qui tient 20 s : un tel combat n'est pas gagnable.
  - Un seul modificateur, `narrator2:champion/threat_damage` (`ADD_VALUE`), porte les deux. Il est ajouté avant le
    facteur de l'Armurier, qui reste le multiplicateur, et il peut être négatif. L'arme compte pour sa valeur
    `attribute_modifiers`. Ses enchantements (Tranchant, Aura de feu) ne comptent pas.
  - Il ne sert à rien pour les archers (squelette, pillard), dont les flèches ne lisent pas `attack_damage`.

| Joueur (socle ×1,25, difficulté Normal) | Meilleure arme | PV plancher | Coup nu au moins |
|---|---|---|---|
| Thawyn (casque et jambières en fer) | hache en bois, 5,6/s | 112 | 3,5 |
| nu | poings, 4/s | 80 | 2,5 |
| épée en fer | 9,6/s | 192 | |
| diamant complet sans Protection, épée Tranchant V | 16/s | 320 | 8 |

Le coup du champion de Thawyn, recalculé en Hard : 8,8 × 1,875 donnait 15,6 PV à travers 7 d'armure. Le plafond le
ramène à 2,8 × 1,875, soit 4,3 PV.

Le log le dit à chaque fois, par exemple : `[Relics] champion for X sized: health 45 → 112 against 5.6 damage a second
(wooden_axe); blow 7 → 4 with its weapon, taking 5 through armor 0 and protection 0 (factor 1.25)`. Les PV finaux
figurent aussi dans la ligne `CHAMPION` envoyée au Directeur.

### Les paliers et les plafonds des reliques (`RelicSpecs.kt` : `Tier`, `RelicCaps`, `RelicRules.withinCaps`)

**Le palier du joueur** (`Threat.tierOf`, puis `Tier.ofPlayer`) vient des objets qu'il porte, inventaire compris :
- les outils et les armes, repérés par les tags `swords`, `axes`, `pickaxes`, `shovels`, `hoes` et `spears`, comptent
  pour leur matériau ;
- l'armure (`*_armor`) compte pour son matériau moins un cran, parce qu'on en trouve tôt dans les coffres alors qu'une
  pioche se fabrique ;
- on prend le maximum des deux, et bois au minimum.

Matériaux : bois (`wooden_`, `leather_`, arc), pierre (`stone_`, `golden_`, `copper_`, bouclier), fer (`iron_`,
`chainmail_`, arbalète), diamant (`diamond_`, trident, masse), netherite. Un test vérifie que chaque outil, arme ou
pièce d'armure du jeu a un matériau, sauf la carapace de tortue, comptée comme diamant.

**Les progrès** (advancements) **ne comptent pas.** Ils ne feraient que monter le palier. L'inventaire, lui, dit ce que
le joueur manie, et il ne peut pas être exploité : ranger son bon équipement donne une relique plus modeste.

**Les plafonds** se lisent par « marche » : le palier du joueur + 1, ou + 0 après un combat trop facile.

| Marche | Base au plus | Enchantements (nombre et niveau) | Bonus d'attribut | Incassable |
|---|---|---|---|---|
| 0 (bois, combat facile) | bois | 1, niveau I | 50 % du plafond | non |
| 1 (bois) | pierre | 2, niveau II | 50 % | non |
| 2 (pierre) | fer | 2, niveau II | 50 % | non |
| 3 (fer) | diamant | 3, niveau III | 75 % | non |
| 4 (diamant) | diamant | 4, niveau IV | 100 % | oui |
| 5 (netherite) | diamant | 5, niveau V | 100 % | oui |

Thawyn est au palier pierre. Avec un combat normal, il aurait eu au mieux un objet en fer à deux enchantements de niveau
II ; avec son combat trop facile, un objet en pierre. Sa hache réelle relève du palier diamant (`RelicRules.fittingTier`).

**Le combat trop facile** (`RelicRules.trivial`) : tué moins de **10 s** après le premier coup d'un joueur (suivi par
`LiveChampion.firstBlowAt`), ou le champion a infligé moins de **4 PV** à son tueur. La ligne `<fight>` dit maintenant
« fought 12s from the first blow ».

**La validation.** `create_relic` dans une tâche de relique refuse avec le détail, par exemple : `too strong for a
stone-tier player, after a fight that was too easy: base: stone at most (iron_axe is iron); 3 enchantments: 2 at most;
enchantments up to level 2 (sharpness 4, unbreaking 3, efficiency 3); caps: base stone at most (…); …`. L'Armurier
corrige ensuite.

**La demande** donne le palier avant tout appel : `<player tier="stone">` et `<caps tier="stone" fight="too easy: …">base
stone at most (wooden_, leather_, bow, stone_, golden_, copper_, shield); 2 enchantments at most, level II at most;
attribute bonuses up to 50 % of their cap; never unbreakable</caps>`. La tâche de champion reçoit aussi `<player
tier="…">`.

### Les reliques des coffres

- `RelicDraft.tier` (optionnel) est nouveau. En réassort, chaque relique **nomme son palier** et reste dans les plafonds
  de ce palier (marche palier + 1). Le réassort demande les paliers qui manquent (`<relic_tiers>`, avec les plafonds de
  chacun). Une relique sans `tier`, d'un palier non demandé ou trop forte est refusée avec la raison.
- `relics.relicStock` (5) est réparti sur les cinq paliers, du plus bas au plus haut (`RelicsService.tierTargets` : 5
  donne 1 par palier, 7 donne 2-2-1-1-1). Chaque palier est sa propre réserve : le réassort part dès qu'un palier passe
  sous la moitié de sa cible, c'est-à-dire dès qu'il est vide quand la cible vaut 1.
- **Tirage** : une relique ne tombe que si le palier de l'ouvreur est le sien à un cran près. La relique tirée est
  la plus ancienne qui convient. Sinon, rien ne tombe, et rien n'est pris dans la réserve.
- Le palier est lu dans le brouillon, donc **sans changement de table**. Une relique faite avant M7-BALANCE reçoit au
  chargement le plus bas palier qu'elle respecte (`fittingTier`). Les 5 reliques déjà en réserve sur le serveur de jeu
  seront ainsi classées, puis le réassort remplira les paliers vides.
- Un tueur parti avant sa relique : elle va dans la réserve, marquée de son palier.

### Les textes

- `armorer.md` (6 911 caractères pour un budget de 7 000) :
  - la récompense est « à la mesure du joueur et du combat », avec les paliers, `<caps>` à respecter du premier coup,
    et « qui se bat à la hache en bois ne gagne pas une hache en diamant. Une relique modeste peut être la plus drôle » ;
  - le dosage « diamant ×3, fer ×2 » est remplacé par ce que garantit le code : une vingtaine de secondes de combat, et
    « des coups qui comptent sans l'achever d'un seul » ;
  - un mort-vivant sans casque brûle de jour (sauf le husk) ;
  - incassable seulement quand `<caps>` le permet.
- Descriptions des outils : `summon_champion` (taillé pour le joueur, part s'il est sauvé d'une mort, relique à la
  mesure) et `create_relic` (plafonds du palier, `<caps>`, `tier` en réassort).
- `director.md` n'a pas été touché : la description de `summon_champion` suffit.
- `ARCHITECTURE.md` §9.7 : un paragraphe « L'équilibre ».

## 3. Ce que les tâches suivantes doivent savoir

- `RelicsService.stand(caller, player, draft)` fait tenir un champion sans passer par l'Armurier : vérification, `spawn`,
  habillage, dimensionnement, fiche et ligne au Directeur. `createChampion` l'appelle. Le GameTest `ChampionGameTests`
  s'en sert, et un futur outil d'opérateur pourrait aussi.
- **Non couvert** :
  - le coup des archers (voir plus haut) ;
  - les enchantements de l'arme du champion : Tranchant ajoute un peu au plafond, et Aura de feu ajoute sa brûlure
    (c'est elle qui a fini Thawyn) ;
  - la difficulté Easy (le jeu y divise les coups, donc le plancher y est plus doux) ;
  - un `/narrator reload` qui change le socle pendant un combat : le rapport des PV se garde, et le plafond n'est pas
    recalculé ;
  - l'équipement que le joueur change après l'apparition : le dimensionnement se fait une fois, à l'habillage.

## 4. Vérifications

- **Tests unitaires** `RelicBalanceTest` (10 tests) :
  - le palier, dont le cas Thawyn (pierre), et un matériau pour chaque outil, arme ou pièce d'armure du registre ;
  - les plafonds par marche, avec le texte de `describe` ;
  - la hache de Thawyn refusée avec chaque plafond qu'elle dépasse, et son vrai palier (diamant) ;
  - le champ `tier` du schéma ;
  - les cibles par palier ;
  - les PV : plancher, plafond d'une minute, 400 PV au plus ;
  - le coup nu, l'armure et la Protection selon les chiffres de `CombatRules` ;
  - le plafond du coup entier (l'essai réel 8,75 → 5, le soir de Thawyn 15,6 → 4,3) ;
  - le combat trop facile.
- **GameTest** `ChampionGameTests.aChampionGoesForItsPlayerUntilItEnds`, avec un joueur simulé tenant une hache en bois
  et un husk ×1,5 tenant une épée en pierre :
  - le champion vise son joueur dès qu'il apparaît ;
  - il a 112 PV et sa vie pleine ;
  - le joueur reçoit ses coups, 5 PV au plus chacun ;
  - privé de cible, il la reprend ;
  - le joueur protégé (sauvetage sur place), il part.
- **Build complet** (`.\gradlew.bat build --continue`) après fusion de `main` (`5a9708a`, M7-CLARITY) : **2 331 tests
  unitaires, 0 échec, 0 ignoré**, et « **All 408 required tests passed** » pour les GameTests, en 9 min 49 s.
- **Essais réels** (`.\gradlew.bat liveGameTest "-Pnarrator2.live.filter=narrator2-gametest:live_relics_*"`, GameTest
  `LiveRelicsGameTests` adapté). Le joueur simulé n'a qu'une hache en bois. Le tunnel écoutait déjà sur 8088 (PID 2672,
  pas le mien) : je ne l'ai ni ouvert ni fermé. Je n'ai lancé aucun `runServer`. Le rapport et les transcriptions sont
  archivés dans `.research/m7-live/m7-balance/`.
  - **Premier essai** (avant le plafond du coup) :
    - réassort d'une relique par palier ;
    - champion husk à 54 → 112 PV ;
    - premier coup : le joueur tombe à 11,25 PV (épée en pierre, 8,75 par coup) ;
    - 9 coups de hache à pleine charge en 12 s : 63 dégâts, le champion reste à 53/112 ;
    - relique : hache en pierre Tranchant II, Efficacité II, +1 dégât ;
    - coffre : la relique du palier bois ;
    - coût **0,586 $**.

    J'ai alors ajouté le plafond du coup.
  - **Second essai** (code final) :
    - champion « Gaston, Allumeur Syndiqué », husk ×1,8 avec épée en bois Aura de feu I : 54 → **112 PV**, coup 6,6 →
      **3,6** (4,5 PV pris) ;
    - premier coup : le joueur tombe à 15,5 ;
    - 9 coups de hache en 12 s : 63 dégâts, champion à **51/112**, toujours debout ;
    - combat de 11 s depuis le premier coup, 31,5 dégâts infligés au joueur, donc pas trop facile, et plafonds du palier
      bois ;
    - relique : « La Hache Briseuse de Grève », **hache en pierre Tranchant II, Efficacité II**, +20 % de vitesse de
      minage, chance −1 ;
    - coffre ouvert par ce joueur : la relique du palier bois (« Pelle du Premier Trou Pour Dormir », pelle en pierre
      Efficacité II, Solidité I). Les reliques des autres paliers restent en réserve ;
    - coût **0,523 $**.
  - **Coût total : ≈ 1,11 $**. Un seul refus de l'Armurier dans les deux essais, et il n'avait rien à voir avec les
    plafonds (Butin sur une masse).
- **La clé** n'apparaît nulle part : une recherche exacte, faite sans l'afficher, ne la trouve ni dans `TheMod/src`, ni
  dans `docs/`, ni dans l'archive.

## 5. Exemples réels

**Réserve préparée par palier** (second essai) :

| Palier | Relique |
|---|---|
| bois | « Pelle du Premier Trou Pour Dormir » : pelle en pierre Efficacité II, Solidité I |
| pierre | « Pioche du Diamant Qui Était du Lapis » : pioche en fer Fortune II, Efficacité I |
| fer | « Bouclier Anti-Flèche-Dans-Le-Dos » : Solidité III, Raccommodage, armure +2 |
| diamant | « Bottes du Saut de Foi Mal Calculé » : bottes en diamant Chute amortie IV, Protection IV, incassables |
| netherite | « Masse de la Chute Enfin Utile » : Densité V, Rafale de vent III, Aura de feu II, incassable |

**Relique d'un joueur en bois** (premier essai), « L'Éteignoir Repenti », hache en pierre Tranchant II, Efficacité II :
« Taillée dans la pierre du tombeau de Lumignon. / Elle a éteint des torches, puis le veilleur. Belle carrière. / Le
Narrateur conseille de les rallumer. Il n'y croit pas. »

## 6. À trancher

1. **Les seuils** sont des constantes du code (`Threat`, `RelicRules`), pas des réglages :
   - 20 s et 60 s de combat, 400 PV au plus ;
   - un coup entre 3 et 5 PV ;
   - un combat trop facile en dessous de 10 s ou de 4 PV ;
   - les plafonds par marche.

   On peut les passer dans la section `relics` si le propriétaire veut les régler.
2. **Le serveur de jeu** a encore `"championMinutes": 10` si son fichier date de M7-RELICS (voir M7-FIX §3). Ses
   5 reliques en réserve seront classées au prochain démarrage, puis le réassort complétera les paliers vides (≈ 0,1 à
   0,2 $).
3. **La règle du « passant » des modules** a sauvé Thawyn d'une brûlure causée par le champion, parce que des poules
   d'un module étaient à côté. Désormais, le champion part. Faut-il plutôt attribuer ces morts au champion (et donc au
   quota) ? Je n'y ai pas touché.
