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
- **Coup nu** : avant son arme, et une fois multiplié par le socle (`mobDamage`), son coup doit prendre au moins **3 PV**
  à travers l'armure et la robustesse du joueur. La formule est celle de `CombatRules`, enchantements de protection mis à
  part, et la difficulté du monde n'est pas comptée (Hard ajoute ×1,5). Le coup nu monte par demi-points et plafonne à
  **10**.
  - Le modificateur s'appelle `narrator2:champion/threat_damage` (`ADD_VALUE`, avant le facteur de l'Armurier). Il
    s'ajoute donc au coup nu sans multiplier l'arme.
  - Il ne sert à rien pour les archers (squelette, pillard), dont les flèches ne lisent pas `attack_damage`.

| Joueur | Meilleure arme | PV plancher | Coup nu |
|---|---|---|---|
| Thawyn (casque et jambières en fer) | hache en bois, 5,6/s | 112 | 3,5 |
| nu | poings, 4/s | 80 | 2,5 |
| épée en fer | 9,6/s | 192 | |
| diamant complet, épée Tranchant V | 16/s | 320 | 8 |

Le journal de log `[Relics] champion for X sized: health 45 → 112 against 5.6 damage a second (wooden_axe); bare blow
3 → 3 against armor 0` le dit à chaque fois. Les PV finaux figurent aussi dans la ligne `CHAMPION` envoyée au Directeur.

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

- `armorer.md` (6 904 caractères pour un budget de 7 000) :
  - la récompense est « à la mesure du joueur et du combat », avec les paliers, `<caps>` à respecter du premier coup,
    et « qui se bat à la hache en bois ne gagne pas une hache en diamant. Une relique modeste peut être la plus drôle » ;
  - le dosage « diamant ×3, fer ×2 » est remplacé par le plancher du code ;
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
  - la difficulté du monde (Hard multiplie par 1,5 les coups des mobs : le plancher est donc prudent en Hard) ;
  - un `/narrator reload` qui change le socle pendant un combat : le rapport des PV se garde, et le plafond n'est pas
    recalculé.
- Le champion du soir aurait quand même pu tuer Thawyn d'un coup et demi. Sur Hard, une épée en fer avec Aura de feu est
  rude pour un joueur à 7 d'armure. Le plancher ne borne pas les coups par le haut : seul le schéma de l'Armurier le
  fait (dégâts ×1,5 au plus).

## 4. Vérifications

(à compléter)
