# Passation M7-BASE : le socle de difficulté permanent

Référence technique : `docs/research/DIFFICULTY_API.md`. Les décisions du superviseur tranchent ses questions
ouvertes (§7). **En cas de contradiction, le code et cette passation font foi.**

## Ce qui est en place

Un socle indépendant de Claude : il marche sans clé, sans tunnel et sans runtime du Narrateur. Tout est côté serveur
(clients vanilla).

| Fichier | Rôle |
|---|---|
| `difficulty/DifficultyConfig.kt` (nouveau) | la section `difficulty` et sa validation (`problems()`, appelée par `ConfigStore.problems`) |
| `difficulty/Difficulty.kt` (nouveau) | le porteur statique (`@Volatile config`) et tout le comportement : PV, dégâts, régénération, minerais, coffres, résumé du briefing |
| `difficulty/DifficultyHooks.kt` (nouveau) | `registerDifficultyHooks()` : `SERVER_STARTING`, `ENTITY_LOAD`, `MODIFY_DROPS` |
| `mixin/difficulty/ServerPlayerDamageMixin.java` | `@ModifyVariable` en tête de `ServerPlayer.hurtServer` |
| `mixin/difficulty/FoodDataRegenerationMixin.java` | `@ModifyConstant` sur 10 et le premier 80 de `FoodData.tick` |
| `mixin/difficulty/FeaturePlacerOreMixin.java` | `@WrapOperation` sur les deux appels `Feature.place` de `FeaturePlacer.place(…, boolean)` (`require = 2`) |
| `narrator2.difficulty.mixins.json` | les trois mixins ; ajouté à la liste `mixins` de `fabric.mod.json` |
| `Narrator2.kt` | une ligne : `registerDifficultyHooks()` |
| `config/NarratorConfig.kt` | le champ `difficulty` (après `chaos`) et une ligne dans `problems` |
| `director/Briefing.kt` | l'attribut `difficulty="…"` de la ligne `<world/>` |

### Ce que fait le socle

1. **PV des mobs** : à `ENTITY_LOAD` (phase `narrator2:difficulty_before_rules`, avant la phase par défaut, donc avant
   le `mob_spawn` des règles), toute créature `Enemy` hors boss reçoit le modificateur permanent
   `narrator2:difficulty/max_health` (`ADD_MULTIPLIED_TOTAL`, `mobHealth - 1`). La vie garde la même proportion : une
   créature neuve est pleine. Idempotent (une créature rechargée a déjà le bon montant). Socle coupé ou facteur 1 : le
   modificateur est retiré au chargement suivant.
2. **Dégâts** : ×`mobDamage` sur tout ce que reçoit un joueur quand `source.getEntity()` est un `Enemy` hors boss
   (flèches, explosions, potions comprises), avant l'échelle de difficulté vanilla, l'armure et `ALLOW_DAMAGE`. Les
   effets (poison, wither) ne sont pas couverts.
3. **Boss** (wither, dragon, warden, gardien ancien : `Difficulty.isBoss`) : ni PV ni dégâts, sans réglage.
4. **Minerais** : chaque placement d'un filon est sauté avec la probabilité `1 - facteur`, par un tirage déterministe
   (graine du monde, position, feature) qui ne consomme pas l'aléa du chunk. La table feature → minerai est
   `Difficulty.ORE_FEATURES` (clés du registre `worldgen/feature`, espace `minecraft` seulement). **Les grandes veines
   restent vanilla** (aucun mixin sur `OreVeinRule`).
5. **Régénération** : les intervalles 10 et 80 ticks de `FoodData.tick` sont multipliés par `regenerationSlowdown`
   (arrondi) ; même coût en nourriture par PV ; la famine n'est pas touchée.
6. **Coffres** : `MODIFY_DROPS` sur les tables dont le chemin commence par `chests/` (coffres-forts des trial chambers
   compris) : chaque diamant et chaque pomme dorée enchantée est gardé avec sa probabilité (tirage avec l'aléa du
   contexte de loot). Aucune table `chests/` vanilla n'en imbrique une autre (vérifié dans le jar) : rien n'est réduit
   deux fois.
7. **Briefing** : `<world day=… weather=… difficulty="hostile mobs health x1.5, damage to players x1.25 (bosses vanilla);
   ores diamond/emerald/gold x0.5, iron/copper x0.75; natural regeneration x0.5; chests diamonds x0.5, enchanted golden
   apples x0.5"/>`, écrit par le code ; rien quand le socle est coupé, tout vanilla ou pas encore lu (tests unitaires).
   Aucun prompt n'a été touché.

## La section de configuration

```json
"difficulty": {
  "enabled": true,
  "mobHealth": 1.5,
  "mobDamage": 1.25,
  "ores": {
    "diamond": 0.5, "emerald": 0.5, "gold": 0.5,
    "iron": 0.75, "copper": 0.75,
    "coal": 1.0, "redstone": 1.0, "lapis": 1.0, "quartz": 1.0, "ancient_debris": 1.0
  },
  "regenerationSlowdown": 2.0,
  "chestDiamonds": 0.5,
  "chestEnchantedGoldenApples": 0.5
}
```

- `gold` couvre l'or normal (`ore_gold_buried`), le bonus des badlands (`ore_gold`) et l'or du Nether
  (`ore_nether_gold`) ; `diamond` ses quatre features ; `copper` aussi les grottes de spéléothèmes.
- Bornes : `mobHealth` 0,1 à 10 ; `mobDamage` 0 à 10 ; chaque minerai 0 à 1 (le mécanisme ne fait que retirer des
  filons) ; `regenerationSlowdown` 1 à 10 ; les deux probabilités des coffres 0 à 1. Une clé de minerai inconnue est
  ignorée avec un avertissement (fusion clé par clé de `ConfigStore`, rien à ajouter à `FREE_FORM`).
- **Lue à `SERVER_STARTING` seulement** (`Difficulty.load`), avant le chargement des mondes et la génération de la zone
  d'apparition, dans un porteur statique, jamais via `NarratorRuntime.current`. Une configuration illisible ou
  invalide (n'importe quelle section) donne **les défauts de la section** (le monde reste difficile), avec un `ERROR`
  `[Difficulty]`. `/narrator reload` ne la relit pas : un changement demande un redémarrage du serveur.
- Ligne de démarrage : `[Difficulty] on: <résumé>` ou `[Difficulty] off: …`. En `DEBUG` (`logging.levels`
  `{"Difficulty": "DEBUG"}`, actif à partir de `SERVER_STARTED`) : chaque créature endurcie et chaque coffre réduit.

## Écarts par rapport à la note

- Pas de réglages `bossHealth` / `bossDamage` ni de liste d'exclusion : les boss sont exclus en dur (décision 1).
- Pas de mixin sur les grandes veines (décision 3).
- Structure de la section plus plate que la proposition du §5.3 (`mobHealth`, `mobDamage`, `ores`,
  `regenerationSlowdown`, `chestDiamonds`, `chestEnchantedGoldenApples`) ; `ores` liste tous les minerais avec leur
  facteur au lieu d'un `factor` global et d'un `perOre`.
- Pas de relecture par `/narrator reload` (le §5.3 la proposait pour les mobs, la régénération et les loots) : tout est
  figé pour le lancement, plus simple.

## Ce que les tâches suivantes doivent savoir

- Les créatures du Narrateur reçoivent le socle sans cas particulier : une punition de slimes de taille 1 donne des
  slimes à 1,5 PV, et ses calibrages de dégâts sont ×1,25 contre les joueurs. Ses facteurs (`spawn`, `mob_attribute`)
  se multiplient avec le socle.
- Le socle est actif dans les GameTests (la configuration semée ne le coupe pas) : un test qui mesure la vie d'une
  créature hostile ou les dégâts d'un coup hostile doit en tenir compte (`Difficulty.config`).
- M7-RELICS : mon écouteur `MODIFY_DROPS` est dans la phase par défaut et ne touche que les diamants et les pommes
  dorées enchantées des tables `chests/`.

## Fichiers partagés touchés en dehors du paquet

- `core/Log.kt` : le tag `Log.difficulty` (`Difficulty`), déclaré avec les autres ; `LogTest` liste désormais
  `Difficulty` parmi les sous-systèmes connus.
- `ConfigStoreTest` : `difficulty` parmi les clés écrites au premier lancement.
- `PunishGameTests.slimeRainFallsThenGoesAway` : les slimes de taille 1 du Narrateur ont `1 × mobHealth` PV (le socle
  s'applique à ses créatures, décision 1).
- `src/gametest/resources/fabric.mod.json` : la classe `DifficultyGameTests`.

## Vérifications

- Tests unitaires `DifficultyTest` : table feature → minerai et facteurs par défaut, tirage déterministe (part gardée
  0,5 ± 0,02 sur 20 000 tirages), résumé du briefing, fusion et validation de la section.
- GameTest `DifficultyGameTests` : un zombie neuf a 30 PV pleins et le modificateur, une vache 10, un warden 500 ;
  un coup de zombie sur un joueur fait ×1,25 le même coup d'une vache (le `@ModifyVariable`, point 1 du §6 de la note).
- `build` vert (unitaires et GameTests).
- Démarrage réel (`runServer`, port 25601, RCON) : ligne `[Difficulty] on: …` avant le chargement du monde, mixins
  appliqués sans erreur, 81 chunks générés par `forceload` sans erreur. Par RCON : squelette, creeper, wither squelette
  30 PV, enderman 60, vache 10, gardien ancien 80 ; un creeper chargé depuis le disque sans modificateur le reçoit et
  remonte à 30 ; `loot spawn` sur `chests/end_city_treasure` et `chests/buried_treasure` : diamants 4 → 2, 2 → 2,
  2 → 1 dans les journaux.
- Non mesuré à nouveau (la note l'a fait) : la réduction des filons. Non testé en jeu : la régénération (le mixin
  s'applique, sinon le démarrage échouerait avec `defaultRequire = 1`).
- **26.3 n'a plus de chunks d'apparition chargés en permanence** : une créature invoquée par la console dans un chunk
  non chargé n'existe pas pour `@e` et ne passe par `ENTITY_LOAD` qu'au chargement du chunk (`forceload`).
