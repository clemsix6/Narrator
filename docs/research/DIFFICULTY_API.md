# Couche de difficulté permanente : référence d'API vérifiée pour Minecraft 26.3

Note de recherche pour la couche de difficulté demandée par le propriétaire le 22/10/2026 : un socle permanent,
indépendant de Claude (il marche sans clé ni tunnel), réglable dans `config/narrator2/narrator2.json`, entièrement
côté serveur (clients vanilla). Les caprices du Narrateur (`create_rule` avec `mob_attribute`, `spawn`…) s'ajoutent
par-dessus. Quatre volets : mobs plus durs, minerais plus rares, régénération plus lente, loots plus originaux (la
mécanique seulement, le contenu viendra plus tard).

**Provenance.** Signatures relevées le 22/10/2026 avec `javap` (`-p`, `-c` pour le bytecode) sur le jar fusionné de
Minecraft 26.3 (`~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar`) et Fabric API 0.161.0+26.3 (tous les modules
du cache Gradle), comme le fait `.research/jp.ps1`. Les données de génération (JSON de `placed_feature`, `feature`,
`material_rule`, `density_function`, `loot_table`) ont été lues dans le même jar. Le code du mod a été lu sur `main`
(`eb9e3b5`). Ce qui n'a été ni lu dans le bytecode ni mesuré porte la mention **[À VÉRIFIER]**.

**Mesuré** : la réduction des minerais (filons et grandes veines) et la surcharge statique d'un JSON de génération
depuis le jar d'un mod, par un spike dans `.research/spikes/difficulty/` (non versionné, §3.7). **Non mesuré** : les
mobs, la régénération et les loots (vérifiés seulement par javap et lecture du bytecode).

Pour reproduire : `powershell -File .research/jp.ps1 -Classes <FQCN> -Pattern "<regex>" [-Private]`.

**Conventions** : signatures en Java telles que `javap` les affiche, FQCN abrégés sans `net.minecraft.` après la
première mention ; extraits de code en anglais (`CLAUDE.md`).

---

## 0. Recommandations en bref

| Volet | Recommandation | Mécanisme |
|---|---|---|
| PV des mobs | Modificateur d'attribut **permanent** `narrator2:difficulty/max_health` (`ADD_MULTIPLIED_TOTAL`, +0,5) posé à `ENTITY_LOAD` sur tout `Enemy`, vie remise à l'échelle | Fabric API + attribut vanilla, aucun mixin |
| Dégâts des mobs | Un mixin `@ModifyVariable` en tête de `ServerPlayer.hurtServer` : ×1,25 si `source.getEntity()` est un `Enemy` | 1 mixin, couvre flèches, explosions, potions, boules de feu |
| Boss | Réglages à part (`bossHealth`, `bossDamage`), **1,0 par défaut** ; décision du propriétaire | même code |
| Minerais (filons) | Un mixin `@WrapOperation` sur l'appel `Feature.place` de `FeaturePlacer.place` : chaque placement d'un filon de minerai est sauté avec une probabilité 1 − f | 1 mixin, réglable par minerai ; **mesuré** : −50 % de filons, taille inchangée |
| Minerais (grandes veines) | Un mixin sur `OreVeinRule.compile` qui ne garde la veine que dans une part f de grandes cellules | 1 mixin ; **mesuré** : −45 % de minerai de veine |
| Régénération | `@ModifyConstant` sur les intervalles 10 et 80 ticks de `FoodData.tick` (ordinal 0 pour 80) | 1 mixin ; même coût en nourriture par PV |
| Loots | `LootTableEvents.MODIFY_DROPS` (à l'exécution, filtré par clé de table et contexte) | Fabric API, aucun mixin |
| Champions | Marqueur persistant à `ENTITY_LOAD` (apparition naturelle), nom, équipement, PV ; relique à `AFTER_DEATH` | Fabric API |
| Configuration | Section `difficulty` de `NarratorConfig`, lue **à `SERVER_STARTING`** dans un porteur statique, indépendant du runtime | — |
| Narrateur | Un attribut `difficulty="…"` sur la ligne `<world …/>` du briefing (code, pas prompt) | — |

---

## 1. Mobs

### 1.1 Qui est « hostile »

L'interface `world.entity.monster.Enemy` est le bon critère : c'est ce que le jeu utilise lui-même pour
« créature hostile ». Hiérarchie relevée par javap :

- implémentent `Enemy` via `monster.Monster` : zombies (et noyés, momies, zombies-villageois, piglins zombifiés),
  squelettes (et vagabonds, bogged, `Parched`, wither squelettes), araignées, creepers, blazes, endermen, endermites,
  silverfish, gardiens et **gardien ancien**, vex, brises, `Creaking`, zoglins, piglins et piglins brutes
  (`AbstractPiglin extends Monster`), **warden**, **wither** (`WitherBoss extends Monster`), tous les pillards
  (`raid.Raider extends PatrollingMonster extends Monster` : pillards, vindicateurs, évocateurs, illusionnistes,
  sorcières, ravageurs) ;
- implémentent `Enemy` directement : `boss.enderdragon.EnderDragon`, `Phantom`, `Ghast`, `Shulker`, `Slime`,
  `MagmaCube`, `hoglin.Hoglin` ;
- **ne l'implémentent pas** : `cubemob.SulfurCube` (nouveau en 26.x), `Strider`, golems, loups, ours, abeilles.

Les neutres qui implémentent `Enemy` (endermen, piglins zombifiés, piglins, araignées le jour) sont traités comme
hostiles : leurs coups, quand ils frappent, sont ceux d'un mob hostile. Si le propriétaire veut les épargner, une liste
d'exclusion par type dans la configuration suffit.

### 1.2 Comment les modificateurs d'attribut persistent

`world.entity.ai.attributes.AttributeInstance` (javap) :

```
public void addPermanentModifier(AttributeModifier);            // lève une exception si l'identifiant existe déjà
public void addOrReplacePermanentModifier(AttributeModifier);   // idempotent
public void addTransientModifier(AttributeModifier);
public void addOrUpdateTransientModifier(AttributeModifier);
public AttributeModifier getModifier(Identifier);                // → nullable
public boolean hasModifier(Identifier);
public boolean removeModifier(Identifier);
public double getValue();
public AttributeInstance$Packed pack();
```

`AttributeModifier` est un record : `AttributeModifier(Identifier id, double amount, AttributeModifier$Operation op)`,
avec `ADD_VALUE`, `ADD_MULTIPLIED_BASE`, `ADD_MULTIPLIED_TOTAL`.

**Calcul (bytecode de `calculateValue`)** : `(base + Σ ADD_VALUE) × (1 + Σ ADD_MULTIPLIED_BASE) × Π(1 + ADD_MULTIPLIED_TOTAL)`,
puis `Attribute.sanitizeValue` (borne de l'attribut : 1024 pour `max_health`). Les `ADD_MULTIPLIED_TOTAL` se
**multiplient** entre eux : un socle à +0,5 et une règle du Narrateur à ×3 donnent ×4,5.

**Persistance (bytecode)** : `AttributeInstance.pack()` n'écrit que la valeur de base et les modificateurs
**permanents** (`permanentModifiers`) ; les transitoires sont perdus au déchargement. `LivingEntity.readAdditionalSaveData`
lit `attributes` **avant** `Health` (puis `setHealth(getFloatOr("Health", getMaxHealth()))`) : une créature rechargée
retrouve son maximum relevé avant que sa vie soit relue, donc ses 30 PV ne sont pas tronqués à 20.

Conséquence : un modificateur **permanent** à identifiant fixe (`narrator2:difficulty/max_health`) est idempotent par
construction. Une créature déjà traitée le porte dans ses données ; il suffit de tester sa présence et son montant.

### 1.3 Les événements Fabric API 26.3

`net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents` (javap) :

```
public static final Event<ServerEntityEvents$Load> ENTITY_LOAD;        // void onLoad(Entity, ServerLevel)
public static final Event<ServerEntityEvents$AllowLoad> ALLOW_LOAD;    // boolean onAllowLoad(Entity, ServerLevel, EntitySpawnReason, boolean)
public static final Event<ServerEntityEvents$Unload> ENTITY_UNLOAD;
public static final Event<ServerEntityEvents$EquipmentChange> EQUIPMENT_CHANGE;
```

Nouveau et utile : l'interface `EntityLoadData`, **injectée dans `Entity`** par le `classtweaker` du module
(`transitive-inject-interface net/minecraft/world/entity/Entity …/EntityLoadData`) :

```
public default EntitySpawnReason spawnReason();   // posé au RETURN de EntityType.create(Level, EntitySpawnRequest)
public default boolean isLoadedFromDisk();        // posé en tête de PersistentEntitySectionManager.addEntity
```

En Kotlin : `entity.spawnReason()` et `entity.isLoadedFromDisk()` directement (Loom applique l'injection). La raison
n'est pas sauvegardée : une créature rechargée n'en a plus (`null` ou `LOAD`) **[À VÉRIFIER]** — on n'en a besoin que
pour les champions (§4.5).

`ENTITY_LOAD` couvre les deux cas qui nous intéressent : apparition (`addFreshEntity`, après `finalizeSpawn`) et
chargement d'un chunk depuis le disque. C'est le crochet que le mod utilise déjà deux fois (`guard/GuardModule.kt`
pour les créatures du Narrateur, `mechanics/MechanicsHooks.kt` pour le déclencheur `mob_spawn` des règles).

**Piège** : ces deux crochets passent par `NarratorRuntime.current`, nul avant `SERVER_STARTED` et pendant
`/narrator restart`. Les créatures des chunks chargés avant `SERVER_STARTED` (zone d'apparition) ne sont pas vues
(`MechanicsHooks.kt` le dit). Le socle de difficulté doit donc avoir **son propre crochet, sur un porteur statique
rempli à `SERVER_STARTING`** (§5.3), jamais sur le runtime.

### 1.4 Les PV : recommandation

```kotlin
/** The difficulty's own modifier: never `narrator2:rule/…` nor `narrator2:module/…` (see §1.7). */
private val HEALTH_ID = Identifier.fromNamespaceAndPath("narrator2", "difficulty/max_health")

/** ENTITY_LOAD of a hostile creature: born or loaded, with or without the modifier. Server thread. */
fun applyHealth(mob: Mob, factor: Double) {
    val instance = mob.getAttribute(Attributes.MAX_HEALTH) ?: return
    val before = mob.maxHealth
    val had = instance.getModifier(HEALTH_ID)
    when {
        factor == 1.0 -> if (had != null) instance.removeModifier(HEALTH_ID) else return
        had?.amount() == factor - 1 -> return                       // already right: loaded from disk
        else -> instance.addOrReplacePermanentModifier(AttributeModifier(HEALTH_ID, factor - 1, Operation.ADD_MULTIPLIED_TOTAL))
    }
    mob.health = (mob.health * mob.maxHealth / before)              // same share of health: full stays full
}
```

- **Remise à l'échelle proportionnelle** plutôt que « vie = maximum » : une créature fraîche (vie = ancien maximum)
  finit au nouveau maximum ; une créature blessée garde sa proportion ; un changement de facteur dans la configuration
  (×1,5 → ×2) ne la soigne pas. Pas besoin de `spawnReason()` pour distinguer apparition et chargement.
- `setHealth` borne déjà à `getMaxHealth()` (méthode `final`).
- **Slimes et cubes de magma** : `AbstractCubeMob.setSize(int, boolean)` réécrit la **base** de `max_health` puis la vie ;
  notre modificateur s'applique par-dessus la nouvelle base. Les petits slimes d'une division sont de nouvelles entités :
  `ENTITY_LOAD` les traite.
- **Conversions** (zombie → noyé, villageois → zombie-villageois, piglin → piglin zombifié) : `ConversionType.convert`
  ne copie **pas** les attributs (bytecode : aucun appel à `getAttributes`). La nouvelle créature passe par
  `ENTITY_LOAD` et reçoit le socle si elle est hostile ; un zombie-villageois guéri redevient un villageois sans
  modificateur.
- **Wither** : `makeInvulnerable` le fait naître au tiers de sa vie, qu'il remonte en se soignant ; la remise à
  l'échelle proportionnelle garde ce tiers, il remonte ensuite jusqu'au nouveau maximum **[À VÉRIFIER en jeu]**.
- **Ordre des écouteurs** : le socle doit passer **avant** le `mob_spawn` des règles (phase Fabric dédiée, comme
  `BEFORE_GUARDS` dans `MechanicsHooks.kt`), pour que les règles voient la créature déjà endurcie.

### 1.5 Les dégâts : couvrir tout ce qu'inflige un mob hostile

`attack_damage` ne sert qu'au corps à corps (`Mob.doHurtTarget`). Flèches, explosions, potions, boules de feu, crocs
de l'évocateur, rayon du gardien, souffle du dragon, onde du warden calculent leurs dégâts eux-mêmes. Il faut donc
agir **là où arrivent tous les dégâts** : `hurtServer`.

**Fabric ne permet pas de modifier le montant.** `ServerLivingEntityEvents` (javap) n'a que `ALLOW_DAMAGE`
(`boolean allowDamage(LivingEntity, DamageSource, float)` : annuler ou laisser passer), `AFTER_DAMAGE`
(`void afterDamage(LivingEntity, DamageSource, float, float, boolean)` : après coup), `ALLOW_DEATH`, `AFTER_DEATH` et
`MOB_CONVERSION`. Il faut un mixin.

Le chemin d'un coup sur un joueur, lu dans le bytecode :

```
ServerPlayer.hurtServer(ServerLevel, DamageSource, float)       // invulnérabilité, PvP (canHarmPlayer), puis super
 └ Player.hurtServer(ServerLevel, DamageSource, float)          // si source.scalesWithDifficulty() :
   │                                                            //   PEACEFUL → 0 ; EASY → min(a/2 + 1, a) ; HARD → a × 3/2
   └ Avatar/LivingEntity.hurtServer(ServerLevel, DamageSource, float)
       ├ [Fabric ALLOW_DAMAGE, injecté avant isSleeping()]       // le bouclier des créatures du Narrateur (CreatureShield)
       ├ armure, enchantements, absorption (getDamageAfterArmorAbsorb, getDamageAfterMagicAbsorb)
       └ actuallyHurt(ServerLevel, DamageSource, float)
```

**Recommandation** : un mixin Java sur `ServerPlayer`, en tête de `hurtServer` :

```java
@Mixin(ServerPlayer.class)
public abstract class DifficultyDamageMixin {
    @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
    private float narrator2$hostileDamage(float amount, ServerLevel level, DamageSource source) {
        return DifficultyHooks.scaleDamage(source, amount);   // × factor when source.getEntity() is an Enemy
    }
}
```

- Le multiplicateur s'applique **avant** l'échelle de difficulté vanilla (Hard ×1,5 sur les sources qui s'y prêtent),
  avant l'armure et avant `ALLOW_DAMAGE` : le bouclier des créatures du Narrateur voit le montant final.
- **Critère** : `source.getEntity() is Enemy` (l'auteur, pas le projectile). `DamageSource.getEntity()` et
  `getDirectEntity()` vérifiés. Couverts ainsi : flèche (auteur = tireur), boule de feu (ghast, blaze), crâne de wither,
  explosion d'un creeper (auteur = creeper), potion de sorcière (`indirectMagic(potion, sorcière)`), crocs (auteur =
  évocateur), vex, balle de shulker, nuage du dragon (auteur = dragon), onde du warden.
- **Non couverts**, faute d'auteur dans la source : les dégâts des **effets** (poison de l'araignée bleue, wither du
  wither squelette, `magic`/`wither` tick par tick). Acceptable : ils dépendent de la durée de l'effet, pas d'un coup.
- **Joueurs seulement** par défaut (villageois, loups, golems gardent les dégâts vanilla). Une option `players` →
  `living` serait un mixin sur `LivingEntity.hurtServer` avec le même corps ; à n'ajouter que sur demande.
- Ne **pas** toucher `attack_damage` en plus : le corps à corps compterait deux fois.
- Le PvP n'est pas touché (l'auteur est un joueur).
- Le mod n'a aucun mixin sur `hurtServer` aujourd'hui (grep) ; MixinExtras est déjà utilisé (`WrapOperation` dans
  `guard/` et `policy/`), mais `@ModifyVariable` suffit ici.

**[À VÉRIFIER]** : `@ModifyVariable(argsOnly = true)` sans `ordinal` cible le seul paramètre `float` ; en jeu, un
GameTest du genre « un zombie frappe un joueur sans armure, dégâts × 1,25 » le confirmerait.

### 1.6 Les boss

Wither (`boss.wither.WitherBoss`), dragon (`boss.enderdragon.EnderDragon`), warden (`monster.warden.Warden`), gardien
ancien (`monster.ElderGuardian`) sont tous des `Enemy`. Sans traitement à part, le warden passe de 500 à 750 PV et ses
coups (déjà mortels) de +25 %.

**Recommandation** : deux réglages à part, `bossHealth` et `bossDamage`, **1,0 par défaut** (comme en vanilla), et
c'est au propriétaire de trancher. La barre de boss du wither et du dragon suit `health / maxHealth` : elle reste juste.
Le dragon renaît par `EndDragonFight` en `addFreshEntity`, donc par `ENTITY_LOAD` ; ses phases ne dépendent pas d'un
nombre fixe de PV **[À VÉRIFIER]**.

### 1.7 Cohabitation avec `mob_attribute` des règles du Narrateur

Lu dans `mechanics/rule/RuleOnlyTools.kt` :

- `MobAttributeTool.apply` pose `AttributeModifier(owner.modifier(key), multiply - 1, ADD_MULTIPLIED_TOTAL)` par
  `addOrReplacePermanentModifier`, avec l'identifiant `narrator2:rule/<id>/<attribut>` (ou `narrator2:module/<id>/…`
  pour un module de la Forge, `MarkOwner`). Le retrait (`RuleEngine.kt` l. 900, `CreatureControl.kt` l. 686) est
  **par identifiant** : il n'emporte jamais un autre modificateur.
- Les bornes des règles (`bounds` : `max_health` de 1 à 5…) se calculent sur les seuls modificateurs des
  propriétaires : `MarkOwner.isOwners(id, path)` exige l'espace `narrator2` **et** un chemin qui commence par `rule/`
  ou `module/`. Un identifiant `narrator2:difficulty/max_health` n'est donc **jamais compté** dans ces bornes, ni retiré
  par le moteur.
- `spawn` (`tool/action/WorldActionTools.kt`) pose `narrator2:spawn/<attribut>` par `addPermanentModifier` **avant**
  `addFreshEntity` ; le socle passe ensuite à `ENTITY_LOAD`. Les facteurs se multiplient (§1.2).
- `NarratorMobs.noReinforcements` vide les modificateurs de `spawn_reinforcements` seulement.

Ils ne se marchent donc pas dessus, à une condition : **le chemin du socle ne doit jamais commencer par `rule/`,
`module/` ou `spawn/`**. Effet voulu (« socle sur lequel ses caprices s'ajoutent ») : un creeper sous caféine d'une loi
garde le ×1,5 de PV du socle. Les créatures du Narrateur reçoivent aussi le socle (PV et dégâts) ; leur coup fatal
reste un sauvetage (`CreatureShield`, §17.4), donc le socle ne change pas les garanties. Décision possible : exclure les
créatures invoquées par le Narrateur (`CreatureShield.isSummoned`) du socle de dégâts, pour que ses calibrages restent
ceux qu'il voit ; je ne le recommande pas (plus de cas, aucun risque supplémentaire).

---

## 2. Minerais

### 2.1 Ce qui a changé en 26.3 (important)

Lu dans le jar et confirmé par `.research/porting/primer2_26.3.txt` :

- **`ConfiguredFeature` n'existe plus comme tel** : `levelgen.feature.Feature` est devenue une **interface**
  (`boolean place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)`), et le registre `worldgen/feature`
  (242 JSON) contient ce qu'on appelait les features configurées. `levelgen.placement.PlacedFeature` est un record
  `PlacedFeature(Holder<Feature> feature, List<PlacementModifier> placement)`.
- **Les modificateurs de placement poussent au lieu de produire un flux** :
  `void modify(PlacementContext, RandomSource, BlockPos, Consumer<BlockPos>)`. `CountPlacement` est un
  `RepeatingPlacement` (`int count(RandomSource, BlockPos)`), `RarityFilter` un `PlacementFilter` (`int chance()`).
- `PlacedFeature.place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)` délègue à
  `levelgen.placement.FeaturePlacer`, qui a `place(PlacedFeature, RandomSource, BlockPos)`,
  `placeWithBiomeCheck(PlacedFeature, RandomSource, BlockPos)` et la méthode privée
  `place(PlacedFeature, RandomSource, BlockPos, boolean)` : celle-ci applique tous les modificateurs, puis appelle
  `Feature.place(...)` pour chaque position (deux sites d'appel : sans modificateur, et dans la boucle).
- **`OreVeinifier` a disparu** (« OreVeinifier replaced by OreVeinRule ») : les grandes veines de cuivre et de fer
  sont devenues des **règles de matière** (ex-« surface rules »), un registre de données du monde :
  `levelgen.material.rule.OreVeinRule`, record `(BlockState oreBlock, BlockState rawOreBlock, BlockState fillerBlock,
  float rawOreChance, DensityFunction density, DensityFunction richness, DensityFunction fillerGap)`, avec
  `RuleEvaluator compile(MaterialRuleContext)`. `material.rule.RuleEvaluator` : `BlockState tryApply(int, int, int)`.
  `NoiseGeneratorSettings.oreVeinsEnabled` est fondu dans la règle de matière.

### 2.2 Inventaire des minerais (JSON du jar)

Filons (`placed_feature` → `feature`, étape `UNDERGROUND_ORES`) :

| Minerai | `placed_feature` (placement) → `feature` |
|---|---|
| Charbon | `ore_coal_upper` (count 30) → `ore_coal` ; `ore_coal_lower` (count 20) → `ore_coal_buried` |
| Fer | `ore_iron_upper` (count 90), `ore_iron_middle` (count 10) → `ore_iron` ; `ore_iron_small` (count 10) → `ore_iron_small` |
| Cuivre | `ore_copper` (count 16) → `ore_copper_small` ; `ore_copper_large` (count 16, grottes de spéléothèmes) → `ore_copper_large` |
| Or | `ore_gold` (count 4), `ore_gold_lower` (count 0–1) → `ore_gold_buried` ; **bonus des badlands** `ore_gold_extra` (count 50) → `ore_gold` |
| Redstone | `ore_redstone` (count 4), `ore_redstone_lower` (count 8) → `ore_redstone` |
| Lapis | `ore_lapis` (count 2) → `ore_lapis` ; `ore_lapis_buried` (count 4) → `ore_lapis_buried` |
| Diamant | `ore_diamond` (count 7) → `ore_diamond_small` ; `ore_diamond_medium` (count 2) → `ore_diamond_medium` ; `ore_diamond_large` (rarity 1/9) → `ore_diamond_large` ; `ore_diamond_buried` (count 4) → `ore_diamond_buried` |
| Émeraude | `ore_emerald` (count 100, montagnes) → `ore_emerald` |
| Or du Nether | `ore_gold_nether` (count 10), `ore_gold_deltas` (count 20) → `ore_nether_gold` |
| Quartz | `ore_quartz_nether` (count 16), `ore_quartz_deltas` (count 32) → `ore_quartz` |
| Débris antiques | `ore_ancient_debris_large` (1 par chunk) → `ore_ancient_debris_large` ; `ore_debris_small` (1 par chunk) → `ore_ancient_debris_small` |

Les autres `ore_*` (andésite, diorite, granite, tuf, terre, gravier, argile, infestée, magma, sable des âmes,
blackstone) ne sont pas des minerais et ne sont pas touchés. Le soufre de 26.x (`sulfur_*`) n'est pas un minerai.

Grandes veines (`worldgen/material_rule/overworld.json`, une `sequence`) : `bedrock_floor`, puis
`overworld/copper_ore_vein` et `overworld/iron_ore_vein` (`"type": "minecraft:ore_vein"`), puis la surface et le
sous-sol. Densité : `density_function/overworld/ore_vein/{copper_density,iron_density,mask,toggle,richness,gap}.json`.

Lecture du bytecode de `OreVeinRule.compile` : hors veine (`density ≤ 0`) la règle rend `null` et la séquence passe à
la suivante (pierre normale) ; dans la veine, un tirage décide trou ou matière (`VEIN_SOLIDNESS`), puis
`richness` (de 0,1 à 0,3 d'après `richness.json`) donne la probabilité d'un minerai plutôt que du remplissage (granite
pour le cuivre, tuf pour le fer), avec `raw_ore_chance` 0,02 pour un bloc brut.

### 2.3 Les options pour réduire le nombre de filons sans changer leur taille

| Option | Comment | Réglable par la configuration | Verdict |
|---|---|---|---|
| A. Surcharge statique de JSON dans le jar | `data/minecraft/worldgen/placed_feature/ore_diamond.json` etc. dans les ressources du mod, avec un `count` réduit ou un `rarity_filter` ajouté | **Non** (valeurs figées au build) | Marche (**mesuré**, §3.7) |
| B. API de modification des biomes de Fabric | `BiomeModifications.create(id).add(REPLACEMENTS, all(), ctx -> { removeFeature(vanilla); addFeature(nôtre) })` | Partiellement : nos `placed_feature` doivent quand même exister en JSON | Lourd, et change l'ordre des features (donc leurs graines) |
| C. Réécriture du registre dynamique au chargement | `DynamicRegistrySetupCallback` + `RegistryEntryAddedCallback` | — | Impossible proprement : les valeurs sont des records immuables d'un registre qu'on ne remplace pas |
| D. **Mixin sur `FeaturePlacer`** | `@WrapOperation` sur l'appel `Feature.place` de `place(PlacedFeature, RandomSource, BlockPos, boolean)` : sauter un placement avec une probabilité 1 − f | **Oui**, par minerai | **Recommandé**, mesuré |

Signatures Fabric vérifiées pour B et C :

```
BiomeModifications.create(Identifier) → BiomeModification
BiomeModificationContext$GenerationSettingsContext.removeFeature(GenerationStep$Decoration, ResourceKey<PlacedFeature>) → boolean
BiomeModificationContext$GenerationSettingsContext.addFeature(GenerationStep$Decoration, ResourceKey<PlacedFeature>)
DynamicRegistrySetupCallback.EVENT ; void onRegistrySetup(DynamicRegistryView)
DynamicRegistryView.registerEntryAdded(ResourceKey<? extends Registry<? extends T>>, RegistryEntryAddedCallback<T>)
ResourceLoader.registerBuiltinPack(Identifier, ModContainer, PackActivationType) → boolean   // fabric-resource-loader-v1
```

**L'option D, telle que mesurée** (`.research/spikes/difficulty/src/spike/difficulty/mixin/FeaturePlacerMixin.java`) :

```java
@Mixin(FeaturePlacer.class)
public abstract class FeaturePlacerMixin {
    @WrapOperation(
        method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),
        require = 1, allow = 2)
    private boolean thinOres(Feature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos,
                             Operation<Boolean> original, @Local(argsOnly = true) PlacedFeature placed) {
        String path = placed.feature().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        double factor = OreFactors.factor(path);                          // 1.0 for anything but an ore feature
        if (factor < 1.0 && OreFactors.draw(level.getSeed(), pos.getX(), pos.getY(), pos.getZ(), path.hashCode()) >= factor) {
            return false;                                                 // this blob is not placed
        }
        return original.call(feature, level, generator, random, pos);
    }
}
```

- **Identification** par la clé de la feature (`Holder.unwrapKey()`, `ResourceKey.identifier()`), sans registre :
  thread-safe (la génération tourne sur les fils de génération du monde, pas sur le thread serveur) et sans table à
  construire. La table de correspondance clé → minerai est celle du §2.2 ; `ore_gold` (feature) n'est utilisé que par
  le bonus des badlands, `ore_gold_buried` par l'or normal : on peut régler le bonus à part.
- **Tirage déterministe** par hachage de la graine du monde, de la position et de la feature : il ne consomme pas le
  `RandomSource` du chunk, donc les filons gardés sont placés aux positions vanilla. `WorldGenLevel.getSeed()` vérifié.
- Le multiplicateur s'applique **par placement**, donc aussi au `rarity_filter` des grands diamants et au compte
  `uniform 0–1` de l'or profond : « moins de filons » partout, sans toucher à leur forme (`size`, `discard_chance_on_air_exposure`).
- La configuration doit être **lue avant la génération** des premiers chunks (§5.3).

### 2.4 Les grandes veines

Options :

1. **Mixin sur `OreVeinRule.compile`** (mesuré) : envelopper l'évaluateur rendu au `RETURN`, et rendre `null` (pierre
   normale) dans les cellules de `cell`³ blocs écartées par un tirage déterministe, avec une probabilité 1 − f. La veine
   n'existe pas du tout dans une cellule écartée ; dans une cellule gardée, elle est vanilla. **Piège** : une veine
   qui traverse une frontière de cellule est coupée net. Avec des cellules de 32 blocs, c'est fréquent (les veines sont
   des rubans de plusieurs dizaines de blocs) ; des cellules de 64 à 96 blocs coupent moins mais rendent le résultat
   plus irrégulier d'une région à l'autre. Pour une version propre, tirer la cellule avec la graine du monde par
   `MaterialRuleContext.getOrCreateRandomFactory(Identifier)` (vu dans le bytecode de `compile`) plutôt qu'avec une
   graine fixe comme le spike.
2. **Réduire `richness`** (mixin sur `compile`, ou surcharge de `density_function/overworld/ore_vein/richness.json`
   avec un `mul`) : même enveloppe de granite ou de tuf, moins de minerai dedans. C'est « des veines aussi grandes mais
   plus pauvres », pas « moins de veines ».
3. **Désactiver les veines** (`spike.vein = 0`) : le plus simple ; elles ne pèsent presque rien (§3.7 : 1,3 % du cuivre
   et du fer de la zone mesurée).

**Recommandation** : l'option 1 avec des cellules de 64 blocs, réglée par le même facteur que le cuivre et le fer, ou
l'option 3 si le propriétaire préfère la simplicité. Le spike n'a pas mesuré la forme des veines coupées (§3.7).

### 2.5 « Pas de datapacks » : surcharge statique de JSON contre code réglable

La règle vient de la v1 (le modèle écrivait des commandes de datapack de mémoire, `ARCHITECTURE.md` §1 ;
`design/M6.md` §8.1 la lit « au plus juste » comme visant le moteur du mod). Comparaison honnête :

| | JSON de génération embarqués dans le jar | Code (mixins) réglable par la configuration |
|---|---|---|
| Ce que c'est | Des ressources `data/minecraft/worldgen/...` dans le jar ; Fabric charge les ressources du mod comme un pack de données intégré, au-dessus de vanilla. Aucune commande, aucun dossier `datapacks/`, rien que le modèle puisse écrire. | Deux mixins (`FeaturePlacer`, `OreVeinRule`) et une table clé → minerai |
| Fonctionne | **Oui, mesuré** : `ore_diamond_buried.json` surchargé dans le jar du spike a fait passer les diamants de 13 500 à 8 493 blocs (le quart « enfoui » supprimé) | **Oui, mesuré** (§3.7) |
| Réglage | Figé au build : changer un facteur = recompiler | `narrator2.json`, par minerai, au redémarrage |
| Granularité | Toute la syntaxe vanilla (count, rarity, hauteur…) | Un facteur par minerai (et par veine) |
| Fragilité aux mises à jour | Copie de 30 fichiers à resynchroniser à chaque version (un `count` vanilla qui change est écrasé sans bruit) | Deux points d'injection (`require = 1` : une signature qui change casse le démarrage, bruyamment) |
| Lisibilité | Lisible par un joueur de Minecraft | Lisible par un développeur |
| Autres mods | Un autre mod qui surcharge le même fichier gagne ou perd selon l'ordre des packs | S'applique à tout `placed_feature` dont la feature est un minerai connu, y compris celles d'un pack de données |
| Risque « v1 » | Aucun : le modèle n'y touche pas | Aucun |

**Recommandation** : le code. Le propriétaire veut un réglage dans `narrator2.json` ; seul le code le donne sans
recompiler. Si le superviseur préférait des JSON, ce serait sans risque du point de vue de la règle de la v1, mais
il faudrait renoncer au réglage par la configuration.

### 2.6 Seuls les chunks générés ensuite

Vérifié par la structure du code : les deux mixins n'agissent que pendant la génération (`FeaturePlacer` à l'étape
des features, `OreVeinRule` à l'étape de la matière). Un chunk déjà sauvegardé n'est jamais regénéré. Le propriétaire
a précisé (22/10/2026) qu'on recréera le monde : **aucune rétro-génération ni adaptation des chunks existants n'est à
prévoir**. Il suffit que les réglages s'appliquent aux mondes neufs et aux chunks générés ensuite.

Conséquence d'un changement de réglage en cours de partie : frontière nette entre anciens et nouveaux chunks. C'est
pourquoi les facteurs de génération doivent être **figés pour la durée d'un lancement du serveur** (lus à
`SERVER_STARTING`, jamais relus par `/narrator reload`).

### 2.7 Mesures (spike)

**Montage** (`.research/spikes/difficulty/`, non versionné) : un petit mod Fabric en Java compilé par `javac` contre le
serveur de production de `spikes/forge/prodserver` (copié dans `difficulty/server/`, Fabric API 0.161.0+26.3), avec les
deux mixins du §2.3 et du §2.4 et un compteur. Au `SERVER_STARTED`, le compteur génère (FULL) un carré de chunks autour
de (0, 0) avec un anneau de marge (pour que les features des voisins soient posées), compte chaque bloc de minerai du
carré intérieur et ses amas 6-connexes, écrit `spike-result-<label>.txt` puis arrête le serveur. Monde neuf à chaque
fois, graine `20261022`, Overworld 24 × 24 chunks (−192..191), Nether 16 × 16 chunks, y de −64 à 320 (Overworld).
Facteurs passés par propriétés système (`-Dspike.ore`, `-Dspike.vein`, `-Dspike.cell=32`). Scripts : `build.sh`,
`run.sh <label> <ore> <vein> [cell]`. EULA acceptée pour ce serveur jetable ; chaque serveur s'est arrêté seul.

**Filons, facteur 0,5 (veines intactes)** :

| Minerai | Blocs vanilla → réduit | Amas vanilla → réduit | Taille moyenne d'un amas |
|---|---|---|---|
| Charbon | 35 420 → 16 900 | 2 038 → 979 | 17,38 → 17,26 |
| Fer (pierre) | 28 067 → 13 843 | 6 834 → 3 354 | 4,11 → 4,13 |
| Fer (deepslate) | 13 772 → 7 228 | 5 178 → 2 750 | 2,66 → 2,63 |
| Cuivre | 34 818 → 17 013 | 5 502 → 2 877 | 6,33 → 5,91 |
| Or (deepslate) | 12 174 → 6 120 | 2 326 → 1 135 | 5,23 → 5,39 |
| Redstone (deepslate) | 18 721 → 9 373 | 4 494 → 2 299 | 4,17 → 4,08 |
| Lapis (deepslate) | 7 362 → 3 858 | 2 244 → 1 121 | 3,28 → 3,44 |
| Diamant (deepslate) | 13 270 → 6 853 | 4 205 → 2 130 | 3,16 → 3,22 |
| Or du Nether | 4 548 → 2 236 | 1 119 → 577 | 4,06 → 3,88 |
| Quartz | 12 600 → 6 223 | 2 199 → 1 137 | 5,73 → 5,47 |
| Débris antiques | 378 → 204 | 283 → 145 | 1,34 → 1,41 |
| **Tous (hors blocs bruts)** | **194 330 → 96 777 (×0,498)** | **39 926 → 20 279 (×0,508)** | **4,87 → 4,77** |

Le nombre d'amas est divisé par deux et leur taille moyenne ne bouge pas à 2 % près (les amas vanilla qui se touchent
fusionnent un peu plus souvent quand il y en a deux fois plus). Aucune émeraude dans la zone (pas de montagne) : son
cas n'est pas mesuré, mais il passe par le même code. Temps de génération identique au bruit près (9,1 à 10,1 s pour
676 chunks de l'Overworld, 4,5 à 5,0 s pour 324 du Nether, dans les cinq lancements) : pas de surcoût mesurable.

**Grandes veines** (filons désactivés, `ore = 0`, pour isoler les veines) :

| | Vanilla (`vein = 1`) | `vein = 0,5`, cellules de 32 |
|---|---|---|
| Cuivre en veine | 575 blocs | 337 |
| Fer (deepslate) en veine | 726 blocs | 382 |
| Blocs bruts (cuivre + fer) | 8 + 16 | 5 + 9 |
| **Total** | **1 325** | **733 (×0,55)** |

Dans cette zone, les veines ne pèsent que 1,3 % du cuivre et du fer. La **forme** des veines coupées par les
cellules n'est pas mesurée (leurs minerais sont épars : amas moyens de 1,3 à 1,5 bloc, la mesure par amas n'a pas de
sens ici).

**Surcharge statique** : avec `data/minecraft/worldgen/placed_feature/ore_diamond_buried.json` dans le jar du spike
(un `rarity_filter` de 1 sur 1 000 000 ajouté), les diamants passent de 13 270 + 230 à 8 416 + 77 blocs : un JSON de
génération embarqué dans le jar d'un mod surcharge bien vanilla, sans aucun datapack installé.

**Limites** : une seule graine et une seule zone ; 576 chunks de l'Overworld, ce qui sous-échantillonne les
biomes (pas de montagne, pas de badlands, pas de grotte de spéléothèmes visible dans les chiffres).

---

## 3. Régénération naturelle

### 3.1 Comment elle marche en 26.3 (bytecode de `world.food.FoodData.tick(ServerPlayer)`)

```
public void tick(ServerPlayer);
public void addExhaustion(float);          // plafonnée à 40
public int getFoodLevel();  public float getSaturationLevel();
```

1. **Épuisement** : au-delà de 4, retire 4, puis 1 de saturation, ou à défaut 1 de faim (sauf en paisible).
2. **Régénération rapide** : si la gamerule est vraie, saturation > 0, joueur blessé (`isHurt`) et faim ≥ 20 :
   `tickTimer` +1 ; à **10 ticks**, `heal(min(saturation, 6) / 6)` et `addExhaustion(min(saturation, 6))`.
3. **Régénération lente** : sinon, si la gamerule est vraie, faim ≥ 18 et joueur blessé : à **80 ticks**, `heal(1)`
   et `addExhaustion(6)`.
4. **Famine** : sinon, si faim ≤ 0 : à **80 ticks**, `hurtServer(starve, 1)` (jusqu'à 10 PV en facile, 1 PV en
   normal, jusqu'à la mort en difficile).
5. Sinon `tickTimer = 0`.

La gamerule s'appelle **`natural_health_regeneration`** (identifiant enregistré, bytecode de `GameRules`), constante
`world.level.gamerules.GameRules.NATURAL_HEALTH_REGENERATION` (`GameRule<Boolean>`). Les commandes `gamerule` sont
déjà interdites au Narrateur (`design/handoff/T09b.md`).

En paisible, `ServerPlayer.tickRegeneration()` soigne à part (1 PV toutes les 20 ticks, même gamerule) : hors sujet
pour un serveur difficile, non touché.

### 3.2 Recommandation : allonger les intervalles

```java
@Mixin(FoodData.class)
public abstract class SlowRegenerationMixin {
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 10))
    private int narrator2$fastInterval(int ticks) { return DifficultyHooks.regenTicks(ticks); }   // round(ticks × factor)

    @ModifyConstant(method = "tick", constant = @Constant(intValue = 80, ordinal = 0))
    private int narrator2$slowInterval(int ticks) { return DifficultyHooks.regenTicks(ticks); }
}
```

- `bipush 10` n'apparaît qu'une fois dans `tick` ; `bipush 80` deux fois, dans l'ordre du bytecode : **ordinal 0** pour
  la régénération lente (offset 214), **ordinal 1** pour la famine (offset 259), qu'on ne touche pas.
- **Même coût par PV** : l'épuisement par soin ne change pas, seul le rythme ralentit. Un facteur 2 donne un cœur toutes
  les 8 s en régénération lente et un soin rapide toutes les 1 s.
- **Alternative écartée** : réduire le montant de `heal` (`@WrapOperation` sur `ServerPlayer.heal(F)` dans `tick`)
  garderait le rythme mais doublerait la nourriture par PV : ça change l'économie de la nourriture, pas seulement la
  vitesse.
- **Rien d'autre n'est touché** : les potions et la balise (effet Régénération, `MobEffect`), les pommes dorées
  (Absorption et Régénération), le totem (`DeathProtection` et `setHealth`) et la nourriture elle-même ne passent pas
  par `FoodData.tick`. Les deux seuls `heal` de `tick` sont la régénération naturelle.
- `@ModifyConstant` est sensible au bytecode exact : `defaultRequire = 1` des configurations de mixins du mod fera
  échouer le démarrage si une version change ces constantes.

---

## 4. Loots

### 4.1 Les événements de Fabric API 26.3 (`fabric-loot-api-v3`)

```
LootTableEvents.REPLACE      : LootTable replaceLootTable(ResourceKey<LootTable>, LootTable, LootTableSource, HolderLookup$Provider)
LootTableEvents.MODIFY       : void modifyLootTable(ResourceKey<LootTable>, LootTable$Builder, LootTableSource, HolderLookup$Provider)
LootTableEvents.ALL_LOADED   : void onLootTablesLoaded(ResourceManager, Registry<LootTable>)
LootTableEvents.MODIFY_DROPS : void modifyLootTableDrops(Holder<LootTable>, LootContext, List<ItemStack>)
LootTableSource              : VANILLA, MOD, DATA_PACK, REPLACED ; boolean isBuiltin()
FabricLootTableBuilder       : pool(LootPool), pools(Collection), apply(LootItemFunction), modifyPools(Consumer<LootPool$Builder>)
FabricLootPoolBuilder        : add(LootPoolEntryContainer), when(LootItemCondition), apply(LootItemFunction)
```

- `MODIFY` et `REPLACE` agissent **au chargement des tables** (démarrage, `/reload`) : on ajoute une `LootPool` faite
  d'entrées et de fonctions vanilla (`set_components`, `set_name`, `set_lore`…). Statique.
- `MODIFY_DROPS` agit **à chaque tirage** : le mixin de Fabric enveloppe `LootTable.getRandomItemsRaw(LootContext,
  Consumer)` (javap du `LootTableMixin`). On reçoit la table (`holder.unwrapKey()`), le contexte (`getLevel()`,
  `getRandom()`, `getLuck()`, `getOptional(ContextKey)`) et la liste des objets, qu'on peut compléter ou modifier.

**Recommandation : `MODIFY_DROPS`**. Toute la logique des reliques est du code (tirage, rareté, rationnement par
joueur, exclusion d'une relique déjà sortie, réglage par la configuration relu par `/narrator reload`), ce qu'une pool
statique ne sait pas faire. Les coffres ne sont tirés qu'à la première ouverture (le tirage passe par cette méthode),
donc un réglage changé vaut aussi pour les coffres déjà générés et pas encore ouverts.

**Filtrer par source** :
- **Coffres de structures** : chemin de la clé commençant par `chests/` (61 tables vanilla : `chests/simple_dungeon`,
  `chests/ancient_city`, `chests/village/…`, `chests/trial_chambers/…`). À exclure explicitement :
  `chests/spawn_bonus_chest` et les récompenses de coffre-fort `chests/trial_chambers/reward*` (si le propriétaire le
  veut). Les tonneaux (`barrels/`), l'archéologie (`archaeology/`) et la pêche (`gameplay/fishing…`) sont d'autres
  préfixes.
- **Drops de mobs** : préfixe `entities/` (111 tables) ; la créature est `LootContextParams.THIS_ENTITY`, le joueur
  `LAST_DAMAGE_PLAYER` (nul si personne ne l'a frappé), la source `DAMAGE_SOURCE`, le tueur `ATTACKING_ENTITY`.
- **Piège** : `getRandomItemsRaw` est aussi appelé pour les tables **imbriquées** (entrée `loot_table`) ; le filtre par
  clé l'absorbe, mais il ne faut pas compter un tirage par appel.
- Les tables de blocs (`blocks/`, 1202) sont tirées à chaque bloc cassé : le filtre doit sortir au plus vite.

### 4.2 Les composants d'objet de 26.3 (vérifiés dans `core.component.DataComponents`)

| Composant | Type (javap) | Usage pour une relique | Client vanilla |
|---|---|---|---|
| `CUSTOM_NAME` | `Component` | nom de la relique (non italique) | affiché |
| `ITEM_NAME` | `Component` | nom « par défaut », non renommable | affiché |
| `LORE` | `ItemLore` | l'histoire | affiché |
| `RARITY` | `world.item.Rarity` (`COMMON`, `UNCOMMON`, `RARE`, `EPIC`) | couleur du nom | affiché |
| `ENCHANTMENT_GLINT_OVERRIDE` | `Boolean` | reflet sans enchantement | affiché |
| `ENCHANTMENTS` / `STORED_ENCHANTMENTS` | `ItemEnchantments` | — | affichés |
| `ATTRIBUTE_MODIFIERS` | `ItemAttributeModifiers` | **double tranchant** : +dégâts en main, −PV max | affichés (« En main : … »), effet appliqué par le serveur |
| `UNBREAKABLE` | `util.Unit` | — | affiché |
| `DEATH_PROTECTION` | `DeathProtection(List<ConsumeEffect>)` | « totem » maison (effets à la place de celui du totem) | animation du totem **[À VÉRIFIER]** |
| `CONSUMABLE` | `Consumable(float, ItemUseAnimation, Holder<SoundEvent>, boolean, List<ConsumeEffect>)` | consommables étranges | animation et son côté client |
| `FOOD` | `FoodProperties` | nourriture | — |
| `USE_COOLDOWN` | `UseCooldown(float)` ou `(float, Optional<Identifier>)` | relique à recharge | barre de recharge affichée |
| `USE_REMAINDER` | `UseRemainder` | ce qui reste après usage | — |
| `ITEM_MODEL` | `Identifier` | apparence d'un autre objet **vanilla** (une relique qui ressemble à une baguette de blaze) | affiché si le modèle existe dans le client vanilla ; un modèle à nous exigerait un pack de ressources |
| `CUSTOM_MODEL_DATA`, `TOOLTIP_STYLE` | — | — | **sans effet** sans pack de ressources |
| `EQUIPPABLE` | `equipment.Equippable` (`builder(EquipmentSlot)`, `setAsset`, `setEquipSound`…) | porter un objet sur la tête | rendu seulement avec un `asset` vanilla |
| `TOOLTIP_DISPLAY` | `TooltipDisplay(boolean, SequencedSet<DataComponentType<?>>)` | cacher des lignes (`withHidden`) | respecté |
| `DAMAGE_RESISTANT`, `WEAPON`, `TOOL`, `ATTACK_RANGE`, `BLOCKS_ATTACKS`, `MAX_DAMAGE`, `MAX_STACK_SIZE`, `REPAIRABLE`, `ENCHANTABLE`, `GLIDER` | — | comportements | vanilla |
| `WRITTEN_BOOK_CONTENT` | `WrittenBookContent` | livres trouvés dans les coffres | affiché (déjà utilisé par `give_book`) |
| `CUSTOM_DATA` | `CustomData` | marqueur de relique (identifiant, version) | invisible |

Détails vérifiés :
- `ItemAttributeModifiers.builder().add(Holder<Attribute>, AttributeModifier, EquipmentSlotGroup[, Display]).build()` ;
  `EquipmentSlotGroup` : `ANY`, `MAINHAND`, `OFFHAND`, `HAND`, `FEET`, `LEGS`, `CHEST`, `HEAD`, `ARMOR`, `BODY`,
  `SADDLE` ; `ItemAttributeModifiers$Display` : `attributeModifiers()`, `hidden()`, `override(Component)` (une ligne à
  nous à la place du calcul).
- Effets à la consommation (`world.item.consume_effects`) : `ApplyStatusEffectsConsumeEffect(MobEffectInstance[, float])`
  ou `(List<MobEffectInstance>[, float])` ; `RemoveStatusEffectsConsumeEffect(Holder<MobEffect>)` ou `(HolderSet)` ;
  `ClearAllStatusEffectsConsumeEffect.INSTANCE` ; `TeleportRandomlyConsumeEffect()` ou `(float, boolean)` ;
  `PlaySoundConsumeEffect(Holder<SoundEvent>)`. `Consumable.builder()` : `consumeSeconds`, `animation`, `sound`,
  `soundAfterConsume`, `hasConsumeParticles`, `onConsume(ConsumeEffect)`.
- Tous ces composants font partie du protocole vanilla et voyagent avec l'objet : un client sans mod les comprend. Ce
  qui est marqué « affiché » est le comportement connu du client vanilla, **pas testé en jeu ici**.

### 4.3 Réutiliser `ItemSpecs` (le code de `give_item`)

`tool/action/ItemSpecs.kt` : `ItemSpecs.resolve(registries, features, raw, count, name, lore, enchantments,
unbreakable)` vérifie l'objet (registre, activé dans le monde, liste des interdits), la taille de pile, nettoie les
textes et résout les enchantements (un niveau au-dessus du maximum vanilla au plus) ; `ItemSpecs.build(spec, turnId)`
pose `CUSTOM_NAME` (non italique), `LORE`, `ENCHANTMENTS` (ou `STORED_ENCHANTMENTS` sur un livre), `UNBREAKABLE` et le
marqueur `CUSTOM_DATA {narrator2: {kind: "item", turn: …}}`.

**Réutilisable en partie** : la résolution d'identifiants (avec les suggestions), le nettoyage des textes et la pose des
quatre composants servent tels quels à un catalogue de reliques. **Il manque** ce qui fait une relique :
`ATTRIBUTE_MODIFIERS`, `RARITY`, `ENCHANTMENT_GLINT_OVERRIDE`, `DEATH_PROTECTION`, `CONSUMABLE`, `USE_COOLDOWN`,
`ITEM_MODEL`. Et deux choses n'y ont pas leur place : le rationnement des objets précieux (`preciousness`, pour les dons
du Narrateur) et le plafond d'enchantement « un gag, pas une arme ». Recommandation : un `RelicSpec` à part, décrit en
JSON (catalogue dans les ressources, contenu à venir), qui appelle `ItemSpecs.build` pour la base puis ajoute ses
composants ; marqueur `CUSTOM_DATA {narrator2: {kind: "relic", id: …}}`, **pas** `kind: "item"`, pour qu'une relique
trouvée ne soit jamais prise pour un don du Narrateur.

### 4.4 Livres trouvés dans les coffres

`WrittenBookContent(Filterable<String>, String, int, List<Filterable<Component>>, boolean)` (déjà documenté dans
`ORACLE_ACTIONS_API.md` §4.3 et utilisé par `give_book`). Ajoutés par `MODIFY_DROPS` aux tables `chests/…`.

### 4.5 Le mob « champion »

Faisable entièrement avec l'API existante :

| Besoin | API vérifiée |
|---|---|
| Choisir à l'apparition naturelle | `ENTITY_LOAD` ; `entity.spawnReason() == EntitySpawnReason.NATURAL` (ou `SPAWNER`, au choix) et `!entity.isLoadedFromDisk()` (§1.3) ; tirage avec la probabilité de la configuration |
| Marqueur persistant | une pièce jointe persistante Fabric (comme `NarratorMob.TYPE`), ou une étiquette `Entity.addTag(String)` / `entityTags()` (sauvegardée) |
| Nom affiché | `Entity.setCustomName(Component)`, `setCustomNameVisible(boolean)` |
| Équipement | `LivingEntity.setItemSlot(EquipmentSlot, ItemStack)` ; `Mob.setDropChance(EquipmentSlot, float)`, `Mob.setGuaranteedDrop(EquipmentSlot)` |
| PV | un deuxième modificateur permanent `narrator2:difficulty/champion_health`, même méthode qu'au §1.4 |
| Relique garantie | `ServerLivingEntityEvents.AFTER_DEATH` (`void afterDeath(LivingEntity, DamageSource)`) + `Entity.spawnAtLocation(ServerLevel, ItemStack)` ; ou `MODIFY_DROPS` sur sa table `entities/…` quand `THIS_ENTITY` porte le marqueur |
| Lueur | `Entity.setGlowingTag(boolean)` (visible de tous les clients) |

Pièges :
- **Le nom n'empêche pas la disparition** : `Mob.checkDespawn` ne regarde que `isPersistenceRequired()`,
  `requiresCustomPersistence()` (passager ou tenu en laisse) et la distance (bytecode) ; c'est l'étiquette de nom qui
  appelle `setPersistenceRequired()`. Un champion peut donc disparaître comme tout mob, sans s'accumuler : c'est le bon
  comportement. `Mob.setPersistenceRequired()` le garderait.
- **Relique seulement si un joueur le tue** : tester `source.getEntity()` (ou `LAST_DAMAGE_PLAYER`) ; un champion tué
  par la lave ne donne rien.
- Une relique en équipement garanti (`setGuaranteedDrop`) tombe intacte (`DropChances.isPreserved`) mais le champion
  s'en sert : on peut préférer un drop à `AFTER_DEATH`.
- Les créatures du Narrateur (`NarratorMobs.isNarrators`) ne doivent pas devenir champions.

---

## 5. Liens avec le reste du mod

### 5.1 Ce que le Narrateur apprend des réglages

Les prompts sont pleins ; l'information doit venir du code. Deux lieux possibles :
- la **situation** (`director/SituationReport.kt`), écrite à **chaque** réveil : coûteux pour une information qui ne
  change pas ;
- le **briefing** (`director/Briefing.kt`), réécrit à chaque chapitre et relu du cache entre-temps, dont la ligne
  `<world day=… time=… clock=… weather=…/>` (`worldLine`) décrit déjà le monde.

**Recommandation** : un attribut de plus sur cette ligne, en anglais, rien quand tout vaut 1 :
`<world … difficulty="hostile mobs: health x1.5, damage x1.25; ores x0.5; natural regeneration x0.5"/>`. Il dit au
Directeur que ses invocations sont déjà endurcies et pourquoi les joueurs meurent plus, sans une ligne de prompt.
Les résultats de `spawn` peuvent rester tels quels (ils citent ses facteurs à lui).

### 5.2 Le quota de morts des garde-fous

Lu dans `guard/DeathGuard.kt` et `guard/Peril.kt` : le quota (« le Narrateur tue au plus une fois par heure jouée »)
ne compte **que les morts attribuées au Narrateur** (`attributed`) : coup d'une de ses créatures ou de ce qu'elle
possède (`CreatureShield.isNarratorsDoing`), ou mort d'une cause couverte par un **péril armé** par une de ses actions
pendant sa durée. Une mort naturelle (un zombie ordinaire, la lave sans péril armé) ne compte pas et n'est pas
empêchée. Un monde plus dur **ne sature donc pas** le quota en temps normal.

Deux effets de bord à connaître :
- `Peril.ANY` (lenteur, cécité, moins de PV, téléportation) couvre **toute** cause pendant son armement : un zombie
  ordinaire, plus fort avec le socle, qui tue un joueur affaibli par le Narrateur est compté. Au-delà du quota, le joueur
  est **sauvé** : un monde plus dur fait donc plus souvent jouer le sauvetage pendant ces fenêtres. Pas un problème,
  mais une raison de garder les durées d'armement courtes.
- « Pas d'acharnement » : un joueur mort dans les 5 dernières minutes n'est pas ciblé. Plus de morts naturelles, c'est
  plus de joueurs momentanément hors de portée des châtiments : le Narrateur agira un peu moins.

### 5.3 Brancher la configuration

`config/NarratorConfig.kt` : `NarratorConfig` agrège des sections `@Serializable` à défauts ; `ConfigStore.load(dir)`
fusionne clé par clé avec les défauts (une section absente prend ses défauts sans réécrire le fichier) et valide dans
`problems(config)` (une phrase anglaise par valeur invalide). Une nouvelle section `difficulty` s'y ajoute sans rien
casser. Proposition (structure libre, à ajuster) :

```json
"difficulty": {
  "enabled": true,
  "mobs":   { "health": 1.5, "damage": 1.25, "bossHealth": 1.0, "bossDamage": 1.0, "excluded": [] },
  "ores":   { "factor": 0.5, "perOre": {}, "veins": 0.5 },
  "regeneration": { "slowdown": 2.0 },
  "loot":   { "enabled": false }
}
```

(`perOre` : `{"diamond": 0.3, "ancient_debris": 0.5}` par-dessus `factor` ; `veins` : facteur des grandes veines ;
`slowdown` : multiplicateur des intervalles, 2 = deux fois plus lent.) Bornes à valider : facteurs de 0 à 4 (0 = aucun
minerai), `slowdown` de 1 à 10.

**Le piège du moment de lecture** (vérifié) : le runtime lit la configuration à `SERVER_STARTED`
(`Narrator2.kt` → `NarratorRuntime.start` → `SettingsLoader.load`). Or Fabric déclenche `SERVER_STARTING` avant
`MinecraftServer.initServer()` et `SERVER_STARTED` après (javap du `MinecraftServerMixin` du module de cycle de vie) :
**entre les deux, `initServer` charge les mondes et génère la zone d'apparition**, et ses créatures sont chargées. La
couche de difficulté doit donc :
- lire sa section à **`SERVER_STARTING`** (`ConfigStore.load(NarratorRuntime.configDir).difficulty`, lecture de fichier
  permise au démarrage), dans un porteur statique `@Volatile` que lisent les mixins et les crochets ;
- en cas de configuration invalide, prendre les **défauts de la section** (le serveur reste difficile) et le
  journaliser, comme `SettingsLoader.fallback` ;
- ne jamais dépendre de `NarratorRuntime.current` (nul avant `SERVER_STARTED` et pendant `/narrator restart`) ni de
  la clé LLMGW ;
- **figer les facteurs de génération** pour tout le lancement ; laisser `/narrator reload` mettre à jour les facteurs
  des mobs, de la régénération et des loots (ils ne touchent rien de permanent, sinon les modificateurs, recalculés à
  `ENTITY_LOAD`).

Lecture concurrente : les mixins de génération tournent sur les fils de génération ; un objet de configuration
immuable publié par une référence `@Volatile` suffit.

---

## 6. Points [À VÉRIFIER]

1. `@ModifyVariable(argsOnly = true)` sur `ServerPlayer.hurtServer` : un GameTest (zombie, squelette, creeper, sorcière
   contre un joueur sans armure) mesurera le ×1,25 par type de source.
2. Wither et dragon avec ×1,5 de PV : remontée de la vie du wither, phases du dragon.
3. `spawnReason()` d'une créature rechargée (champions seulement).
4. Affichage par un client vanilla des composants marqués « affiché » (§4.2), et animation de `DEATH_PROTECTION`.
5. Forme des grandes veines coupées par les cellules (§2.4) ; émeraudes, or des badlands et cuivre des grottes de
   spéléothèmes (absents de la zone mesurée).

---

## 7. Décisions à prendre

1. **Code ou JSON pour les minerais** (§2.5) : recommandation code, réglable dans `narrator2.json`.
2. **Grandes veines** : réduites par cellules (64 blocs), désactivées, ou laissées vanilla ?
3. **Boss** : facteurs à part, 1,0 par défaut (recommandé), ou les mêmes que les autres hostiles ?
4. **Neutres** (endermen, piglins zombifiés, piglins) : traités comme hostiles (recommandé) ou exclus ?
5. **Dégâts** : aux joueurs seulement (recommandé) ou à toute créature (villageois, animaux apprivoisés) ?
6. **Régénération** : intervalles allongés (même coût par PV, recommandé) ou soins réduits (plus de nourriture par PV) ?
7. **Créatures du Narrateur** : reçoivent le socle (recommandé) ou en sont exclues ?
8. **Coffres** : le coffre bonus et les récompenses des coffres-forts reçoivent-ils des reliques ?
