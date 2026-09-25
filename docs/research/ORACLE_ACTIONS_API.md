# Oracle et Actions : référence d'API vérifiée pour Minecraft 26.3

Référence pour le jalon M2 (`docs/ARCHITECTURE.md` §5, §6, §9, §17). Elle couvre ce que l'Oracle lit et ce que les Actions font, avec, pour chaque besoin, la classe, la signature exacte, un extrait Kotlin et les pièges.

**Provenance.** Toutes les signatures ont été relevées le 25/09/2026 avec `javap` sur :
- le jar fusionné de Minecraft 26.3 (`~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar`) ;
- Fabric API 0.161.0+26.3 (tous les modules du cache Gradle) ;
- Brigadier 1.3.11 (`com.mojang/brigadier/1.3.11/.../brigadier-1.3.11.jar`), **absent du classpath de `.research/jp.ps1`** : il faut lancer `javap` directement sur ce jar.

Les comportements décrits (ce qui charge un chunk, qui reçoit un paquet, etc.) ont été lus dans le bytecode (`javap -c -p`), jamais supposés. Les guides `.research/porting/primer_26.{1,2,3}.txt` ont servi à repérer les renommages. Tout ce qui n'a pas pu être prouvé ainsi porte la mention **[À VÉRIFIER]**.

Pour reproduire : `powershell -File .research/jp.ps1 -Classes <FQCN> -Pattern "<regex>" [-Private]`.

**Conventions de ce document**
- Les FQCN sont complets à la première mention, puis abrégés sans `net.minecraft.`.
- Les signatures sont données en Java, telles que `javap` les affiche. En Kotlin, `getX()` devient la propriété `x`, et `setX()` son affectation.
- **Nullabilité.** Le jar est `@NullMarked` (JSpecify). Kotlin traite donc tout type non annoté comme non nul et tout type `@Nullable` comme `T?`. Les retours et paramètres `@Nullable` ont été vérifiés un par un dans les `RuntimeVisibleTypeAnnotations` et sont signalés par `→ nullable`.
- Les extraits de code sont en anglais, conformément à `CLAUDE.md`.

---

## 1. Surprises de 26.x à connaître avant d'écrire une ligne

| Ancien réflexe | Réalité 26.3 (vérifiée) | Source |
|---|---|---|
| `ResourceLocation`, `ResourceKey.location()` | `resources.Identifier`, `ResourceKey.identifier()`. `TagKey.location()` existe toujours. | javap |
| `EntityType.ZOMBIE` | Les constantes ont déménagé : `world.entity.EntityTypes.ZOMBIE`. De même, `BlockEntityTypes`. `EntityType.byString` a disparu. | javap, primer 26.2 |
| `Entity.hurtMarked = true` | **Renommé `Entity.syncVelocity`** (champ public). C'est lui qui envoie la vitesse **au joueur lui-même**. Il existe aussi `needsSync`, qui ne l'envoie qu'aux autres joueurs. | javap, primer 26.3, bytecode de `ServerEntity` |
| `level.getDayTime()`, `ServerLevel.setDayTime()` | Supprimés. Le temps passe par des horloges (`world.clock.*`) : `Level.getDefaultClockTime()`, `MinecraftServer.clockManager()`. | javap, primer 26.1 |
| `ServerLevel.setWeatherParameters` | Déplacé : `MinecraftServer.setWeatherParameters(int, int, boolean, boolean)`. La météo est commune au serveur (`WeatherData`). | javap, primer 26.1 |
| `level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)` | `world.level.gamerules.GameRules.get(GameRule<T>)` et `GameRules.KEEP_INVENTORY`. | javap |
| Niveau de permission `int` | `server.permissions.PermissionSet` et `LevelBasedPermissionSet.{ALL, MODERATOR, GAMEMASTER, ADMIN, OWNER}`. | javap |
| `new ServerBossEvent(name, color, overlay)` | `ServerBossEvent(UUID, Component, BossBarColor, BossBarOverlay)` : l'UUID est obligatoire. | javap, primer 26.1 |
| `player.displayClientMessage(c, overlay)` | Scindé en `sendSystemMessage(Component)` et `sendOverlayMessage(Component)`. | javap, primer 26.1 |
| `ChunkPos.x`, `chunkPos.toLong()` | `ChunkPos` est un record : `x()`, `z()`, `pack()`, `ChunkPos.unpack(long)`, `ChunkPos.containing(BlockPos)`. | javap |
| `BlockTags.DIAMOND_ORES` | La constante n'existe plus dans `BlockTags`. Il faut passer par `tags.BlockItemTags.DIAMOND_ORES.block()`. Le tag de données `minecraft:diamond_ores` existe toujours. | javap, jar |
| `player.getServer()` | `ServerPlayer` n'expose plus de `getServer()` public. On passe par `player.level().server` (`ServerLevel.getServer()`). | javap |
| `level.createFireworks(...)` côté serveur | Le corps est vide (`return`) : c'est un effet client. Il faut faire apparaître une `FireworkRocketEntity`. | bytecode |
| `UNBREAKABLE` avec un booléen | `DataComponentType<util.Unit>` : on écrit `stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE)`. | javap |
| Attributs | Nouveaux attributs : `bounciness`, `air_drag_modifier`, `friction_modifier`, `camera_distance`, `name_tag_distance`, `below_name_distance`. La plage de `knockback_resistance` passe à [-2, 1] : une valeur négative amplifie le recul. | bytecode de `Attributes`, primer 26.2 |
| `EntityType.create(level, reason)` | Existe toujours (`→ nullable`). S'y ajoutent `EntitySpawnRequest(reason, ignoreChecks)` et `PostSpawnProcessor<T>`, le nouveau nom du `Consumer<T>` de post-traitement. | javap |
| Livre écrit | `WrittenBookContent(Filterable<String>, String, int, List<Filterable<Component>>, boolean)`. La résolution des composants passe par `network.chat.ResolutionContext`. | javap |
| Biomes de grotte | Un nouveau biome de grotte existe : `Biomes.SULFUR_CAVES`. | javap |
| `Level.getHeight(type, x, z)` | **Ne charge rien** : il renvoie `getMinY()` si le chunk n'est pas chargé. À l'inverse, `Level.getBlockState` **charge ou génère** le chunk de façon synchrone. | bytecode |

---

## 2. Règles transverses : thread, chunks, coût

### 2.1 Thread serveur

- Tout accès au monde passe par `core/ServerThread.kt` (`server.onServerThread { }`).
- **Preuve dans le bytecode.** `ServerChunkCache.getChunkNow(int, int)` renvoie `null` dès que `Thread.currentThread() != mainThread`. Appelé hors du thread serveur, `ServerChunkCache.getChunk(int, int, ChunkStatus, boolean)` fait `CompletableFuture.supplyAsync(..., mainThreadProcessor).join()` : il bloque l'appelant jusqu'au prochain passage du thread serveur.
- `withTimeout` dans `onServerThread` **n'annule pas** la tâche déjà planifiée : elle s'exécutera quand même et bloquera le tick. Un outil de l'Oracle doit donc être borné par construction (rayon plafonné, nombre maximal de résultats), pas seulement par le délai.
- **Budget.** Un tick dure 50 ms. Il faut viser moins de 2 ms par appel à l'Oracle et journaliser la durée (`System.nanoTime()`) de chaque outil.

### 2.2 Ce qui charge un chunk, et ce qui ne le charge pas

| Appel | Chunk absent | Vérifié |
|---|---|---|
| `world.level.Level.getBlockState(BlockPos)`, `getFluidState`, `getBlockEntity` | **Charge ou génère, de façon synchrone** (`getChunk(x, z)` → `FULL`, `require=true`) | bytecode |
| `Level.getHeight(Heightmap$Types, int, int)` | Renvoie `getMinY()` (test `hasChunk`) | bytecode |
| `LevelReader.getBiome(BlockPos)` | `getChunk(..., BIOMES, false)`, sinon `getUncachedNoiseBiome` : calcul par le générateur, sans chargement | bytecode |
| `BlockAndLightGetter.canSeeSky` / `getBrightness` | Lit le moteur de lumière, ne charge pas | bytecode ; la valeur hors zone chargée est [À VÉRIFIER] |
| `server.level.ServerChunkCache.getChunkNow(int, int)` | `null`. **C'est la bonne porte d'entrée.** | bytecode |
| `Level.hasChunkAt(BlockPos)`, `Level.isLoaded(BlockPos)` | Teste sans charger | javap |
| `CollisionGetter.noCollision(Entity, AABB)` | Passe par `getChunkForCollisions`, sans chargement | javap |
| `Level.getEntities(Entity, AABB, Predicate)` | Ne voit que les entités chargées | javap |
| `StructureManager.getAllStructuresAt` / `getStructureWithPieceAt` | `getChunk(..., STRUCTURE_REFERENCES / STRUCTURE_STARTS)` en mode `require` : **peut lire le disque** | bytecode |
| `ChunkGenerator.findNearestMapStructure` | Entrées-sorties disque bloquantes (`StructureCheck` → `scanChunk(...).join()`) et **génération de proto-chunks** jusqu'à `STRUCTURE_STARTS` | bytecode |
| `ServerLevel.findClosestBiome3d` | Aucun chunk ; calcul par le bruit, coûteux en processeur | bytecode de `LocateCommand` |

Utilitaire à écrire une fois pour toutes :

```kotlin
/** Block state only if its chunk is already loaded; never loads or generates. Server thread only. */
fun ServerLevel.stateIfLoaded(pos: BlockPos): BlockState? =
    chunkSource.getChunkNow(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z))
        ?.getBlockState(pos)
```

Signatures : `public LevelChunk getChunkNow(int, int)` → nullable ; `public BlockState LevelChunk.getBlockState(BlockPos)` ; `public static int core.SectionPos.blockToSectionCoord(int)`.

---

## 3. Oracle : lire le monde

### 3.1 Position, dimension, biome

| Besoin | Signature (javap) |
|---|---|
| Niveau du joueur | `public ServerLevel server.level.ServerPlayer.level()` |
| Position | `public BlockPos world.entity.Entity.blockPosition()`, `public Vec3 position()`, `public final Vec3 getEyePosition()` |
| Dimension | `public ResourceKey<Level> Level.dimension()`, puis `public Identifier ResourceKey.identifier()` ; constantes `Level.OVERWORLD`, `Level.NETHER`, `Level.END` |
| Biome | `public default Holder<Biome> world.level.LevelReader.getBiome(BlockPos)`, puis `public default String core.Holder.getRegisteredName()` ou `Optional<ResourceKey<T>> unwrapKey()` |
| Tag de biome | `Holder.is(TagKey<T>)`, `Holder.is(ResourceKey<T>)` ; tags dans `tags.BiomeTags` |

```kotlin
val level = player.level()
val pos = player.blockPosition()
val dimension = level.dimension().identifier().toString()   // "minecraft:overworld"
val biome = level.getBiome(pos).registeredName              // "minecraft:plains"
```

**Pièges**
- Les biomes sont en 3D : sous terre, on trouve `minecraft:lush_caves`, `dripstone_caves`, `deep_dark` ou `sulfur_caves` alors que la surface est une plaine.
- `getBiome` ne charge rien, mais sur un chunk absent il recalcule le biome par le bruit. C'est correct, un peu plus lent.

### 3.2 Le joueur est-il dans une grotte ?

**Briques vérifiées**
- `public default boolean BlockAndLightGetter.canSeeSky(BlockPos)`. D'après le bytecode, c'est exactement `getBrightness(LightLayer.SKY, pos) >= 15`.
- `public default int BlockAndLightGetter.getBrightness(LightLayer, BlockPos)` ; `world.level.LightLayer.{SKY, BLOCK}`.
- `public int Level.getHeight(Heightmap$Types, int, int)` ; `world.level.levelgen.Heightmap$Types.{WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR, WORLD_SURFACE_WG, OCEAN_FLOOR_WG}`.
- `public boolean world.level.dimension.DimensionType.hasSkyLight()`, `hasCeiling()`, `logicalHeight()` ; `Level.dimensionType()`.
- `world.level.biome.Biomes.{DEEP_DARK, LUSH_CAVES, DRIPSTONE_CAVES, SULFUR_CAVES}` (des `ResourceKey<Biome>`).

**Pourquoi `canSeeSky` ne suffit pas**
- Les feuilles atténuent la lumière d'un niveau : sous un arbre, la lumière du ciel tombe à 14 et `canSeeSky` renvoie `false`.
- Dans le Nether et l'End, sans lumière du ciel, `canSeeSky` renvoie toujours `false`.
- Un joueur sous un toit de terre, à deux blocs de la surface, n'est pas « dans une grotte ».

**Heuristique proposée** (seuils [À VÉRIFIER] en jeu, par un GameTest sur des cas construits) :

```kotlin
enum class Enclosure { OPEN_SKY, SHELTERED, UNDERGROUND, CAVE }

private val CAVE_BIOMES = listOf(Biomes.DEEP_DARK, Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.SULFUR_CAVES)

/** Server thread only; reads blocks within 4 blocks of the player, whose chunks are loaded. */
fun enclosure(level: ServerLevel, player: ServerPlayer): Enclosure {
    val head = BlockPos.containing(player.eyePosition)
    val type = level.dimensionType()
    if (type.hasSkyLight()) {
        if (level.canSeeSky(head)) return Enclosure.OPEN_SKY
        // Height of the first free block above the highest solid block, ignoring leaves.
        val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, head.x, head.z)
        val depth = surface - head.y
        val skyLight = level.getBrightness(LightLayer.SKY, head)
        if (depth < 4 || skyLight >= 8) return Enclosure.SHELTERED   // roof, tree canopy, overhang
    }
    val caveBiome = CAVE_BIOMES.any { level.getBiome(head).`is`(it) }
    // Open volume around the head: tunnel (~10 cells) vs cavern (dozens).
    var open = 0
    for (dx in -4..4 step 2) for (dy in -2..2 step 2) for (dz in -4..4 step 2) {
        val state = level.getBlockState(head.offset(dx, dy, dz))
        if (state.isAir || state.liquid()) open++
    }
    return if (caveBiome || open >= 20) Enclosure.CAVE else Enclosure.UNDERGROUND
}
```

- La profondeur sous la surface (`depth`) et `head.y` (en dessous de 0, on est dans la deepslate) sont aussi des faits à remonter tels quels au Narrateur.
- Coût : environ 75 lectures de blocs, dans le chunk du joueur et ses voisins, qui sont chargés : négligeable.
- Dans le Nether et l'End, le verdict ne repose que sur l'ouverture de l'espace. Il faut aussi remonter la dimension, pour que le Narrateur ne parle pas de « grotte » dans le Nether.

### 3.3 Heure, météo, lumière, heightmap

| Besoin | Signature | Notes |
|---|---|---|
| Heure de la dimension | `public long Level.getDefaultClockTime()` | Total des ticks de l'horloge par défaut de la dimension ; `0` s'il n'y en a pas (bytecode : `orElse(0L)`). Heure du jour = `% 24000`, jour = `/ 24000`. |
| Horloge de l'Overworld | `public long Level.getOverworldClockTime()` | |
| Temps de jeu absolu | `public default long LevelAccessor.getGameTime()` | |
| Jour ou nuit | `public boolean Level.isBrightOutside()`, `isDarkOutside()` | |
| Pluie, orage | `public boolean Level.isRaining()`, `isThundering()`, `isRainingAt(BlockPos)`, `precipitationAt(BlockPos)` → `Biome$Precipitation.{NONE, RAIN, SNOW}` | |
| Météo globale | `public WeatherData MinecraftServer.getWeatherData()` : `isRaining()`, `getRainTime()`, `isThundering()`, `getThunderTime()`, `getClearWeatherTime()` | |
| Lumière | `getBrightness(LightLayer.BLOCK / SKY, pos)`, `public default int LevelReader.getMaxLocalRawBrightness(BlockPos)` (lumière du ciel assombrie par l'heure et la météo, plus la lumière des blocs) | Les monstres apparaissent à une lumière de bloc de 0. |
| Heightmap | `Level.getHeight(Heightmap$Types, int, int)`, `public default BlockPos LevelReader.getHeightmapPos(Heightmap$Types, BlockPos)` | Renvoie le Y du premier bloc libre au-dessus du sommet. |

```kotlin
val t = level.defaultClockTime
val timeOfDay = t % 24000; val day = t / 24000
val weather = when { level.isThundering -> "thunder"; level.isRaining -> "rain"; else -> "clear" }
val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.x, pos.z)
```

**Pièges**
- `hasFixedTime()` est vrai dans le Nether et l'End : l'heure n'y a pas de sens.
- La heightmap `MOTION_BLOCKING` compte l'eau et la lave comme bloquantes. Dans le Nether, toutes les heightmaps butent sur le plafond de bedrock.
- Sur un chunk non chargé, `getHeight` renvoie `minY` : il faut tester `hasChunkAt` si la valeur compte.

### 3.4 Structures proches

**Signatures**

```
// world.level.StructureManager (via ServerLevel.structureManager())
public StructureStart getStructureWithPieceAt(BlockPos, TagKey<Structure>)
public StructureStart getStructureWithPieceAt(BlockPos, HolderSet<Structure>)
public StructureStart getStructureWithPieceAt(BlockPos, Predicate<Holder<Structure>>)
public StructureStart getStructureAt(BlockPos, Structure)              // bounding box only, no piece test
public Map<Structure, LongSet> getAllStructuresAt(BlockPos)
// world.level.chunk.ChunkAccess
public Map<Structure, LongSet> getAllReferences()
public StructureStart getStartForStructure(Structure)                  // → nullable
// world.level.levelgen.structure.StructureStart
public static final StructureStart INVALID_START; public boolean isValid(); public BoundingBox getBoundingBox(); public ChunkPos getChunkPos(); public Structure getStructure()
// world.level.chunk.ChunkGenerator (via level.chunkSource.generator)
public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel, HolderSet<Structure>, BlockPos, int, boolean)   // → nullable
// server.level.ServerLevel
public BlockPos findNearestMapStructure(TagKey<Structure>, BlockPos, int, boolean)    // → nullable
public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(Predicate<Holder<Biome>>, BlockPos, int, int, int)   // → nullable
```

Clés et tags : `world.level.levelgen.structure.BuiltinStructures.{VILLAGE_PLAINS, ANCIENT_CITY, FORTRESS, STRONGHOLD, TRIAL_CHAMBERS, ...}` et `tags.StructureTags.{VILLAGE, MINESHAFT, SHIPWRECK, RUINED_PORTAL, OCEAN_RUIN, EYE_OF_ENDER_LOCATED, ...}`. Registre dynamique : `level.registryAccess().lookupOrThrow(Registries.STRUCTURE)`.

**Logique de `/locate`, lue dans le bytecode de `server.commands.LocateCommand`**
- `/locate structure` appelle `generator.findNearestMapStructure(level, holders, pos, 100, false)`.
- `/locate biome` appelle `level.findClosestBiome3d(pred, pos, 6400, 32, 64)`.

Dans `ChunkGenerator.getStructureGeneratingAt`, pour chaque chunk candidat, `structureManager.checkStructurePresence(...)` renvoie l'un de trois résultats :
- `START_NOT_PRESENT` : on passe au suivant ;
- `START_PRESENT` : avec `skipKnownStructures = false`, on renvoie directement `placement.getLocatePos(chunkPos)` ;
- `CHUNK_LOAD_NEEDED` : `level.getChunk(x, z, ChunkStatus.STRUCTURE_STARTS)`, c'est-à-dire **lecture disque, ou génération d'un proto-chunk jusqu'à `STRUCTURE_STARTS`**.

`StructureCheck.tryLoadFromStorage` fait en outre `scanChunk(...).join()`, une lecture disque bloquante, et `StructureCheck` n'est pas thread-safe (des `Long2ObjectMap` non synchronisées). Pour les placements en anneaux concentriques (stronghold), `getRingPositionsFor` attend un `CompletableFuture`.

**Recommandation en trois niveaux**

1. **« Il est dans une structure »** : instantané, puisque le chunk est chargé.
   ```kotlin
   val registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
   val start = level.structureManager().getStructureWithPieceAt(pos) { true }
   val inside: Identifier? = if (start.isValid) registry.getKey(start.structure) else null  // getKey → nullable
   ```
   Le prédicat reçoit un `Holder<Structure>`, par exemple `{ it.is(StructureTags.VILLAGE) }` pour « dans un village ».
2. **« Structures dans la zone chargée »** : zéro entrée-sortie. On parcourt les chunks chargés autour du joueur et on lit leurs références, qui sont en mémoire.
   ```kotlin
   /** Structures referenced by loaded chunks within [chunkRadius]; never loads anything. Server thread only. */
   fun nearbyStructures(level: ServerLevel, center: BlockPos, chunkRadius: Int): Map<Identifier, BlockPos> {
       val registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
       val found = HashMap<Identifier, BlockPos>()
       val c = ChunkPos.containing(center)
       for (cx in c.x() - chunkRadius..c.x() + chunkRadius) for (cz in c.z() - chunkRadius..c.z() + chunkRadius) {
           val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
           for ((structure, starts) in chunk.allReferences) {
               val id = registry.getKey(structure) ?: continue
               val it = starts.iterator()
               while (it.hasNext()) {
                   val startPos = ChunkPos.unpack(it.nextLong())
                   // Exact box when the start chunk is loaded, otherwise the start chunk's centre.
                   val start = level.chunkSource.getChunkNow(startPos.x(), startPos.z())?.getStartForStructure(structure)
                   val where = start?.boundingBox?.center ?: startPos.getMiddleBlockPosition(center.y)
                   val best = found[id]
                   if (best == null || where.distSqr(center) < best.distSqr(center)) found[id] = where
               }
           }
       }
       return found
   }
   ```
   Coût : pour une distance de vue de 10, on lit 441 chunks et quelques maps : moins de 1 ms [À VÉRIFIER].
3. **`locate` lointain** : c'est un outil **lent**, à présenter comme tel au modèle. On le lance sur le thread serveur avec un `searchRadius` réduit : 100 pour `/locate`, **16 à 32 recommandés**. Ce rayon se compte en cellules d'espacement du placement : 34 chunks pour un village, donc 16 cellules représentent environ 8 700 blocs. On mesure la durée et on met le résultat en cache par (dimension, structure, région de 512 blocs), puisque les positions ne dépendent que de la graine.
   ```kotlin
   val holders = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(StructureTags.VILLAGE)  // HolderSet.Named
   val t0 = System.nanoTime()
   val hit = level.chunkSource.generator.findNearestMapStructure(level, holders, pos, 24, false)
   log.info("locate village took ${(System.nanoTime() - t0) / 1_000_000} ms -> ${hit?.first}")
   ```
   - Il est impossible d'appeler cet outil hors du thread serveur : `StructureCheck` n'est pas thread-safe, et `getChunk` y rapatrie de toute façon l'exécution.
   - Le coût va de 1 ms (structure connue et proche) à plusieurs secondes (première recherche, zone non explorée) [À VÉRIFIER : à mesurer].
   - Il faut accepter qu'il crée des proto-chunks `STRUCTURE_STARTS` sur le disque. Ce n'est pas du terrain, mais cela écrit dans les fichiers de région. Pour l'éviter complètement, on se limite aux niveaux 1 et 2.
   - Le résultat est `placement.getLocatePos(chunk)`, c'est-à-dire le coin du chunk de départ, avec Y = 0 [À VÉRIFIER], et non le centre exact de la structure.
   - Pour chercher un biome, `findClosestBiome3d(pred, pos, 1024..2048, 32, 64)` ne charge rien mais coûte cher en processeur. Il faut réduire le rayon de 6 400 utilisé par `/locate`.

### 3.5 Minerais proches : balayage des sections chargées avec test de palette

**Signatures**

```
// world.level.chunk.ChunkAccess (LevelChunk)
public LevelChunkSection[] getSections(); public LevelChunkSection getSection(int)
// world.level.chunk.LevelChunkSection
public boolean hasOnlyAir(); public boolean maybeHas(Predicate<BlockState>); public BlockState getBlockState(int, int, int)
public PalettedContainer<BlockState> getStates()
// world.level.chunk.PalettedContainer<T>
public boolean maybeHas(Predicate<T>); public void count(PalettedContainer$CountConsumer<T>); public PalettedContainer<T> copy()
// world.level.LevelHeightAccessor (Level)
public default int getSectionIndexFromSectionY(int); public default int getMinY(); public default int getMaxY()
// tags
public static final BlockItemTagId tags.BlockItemTags.DIAMOND_ORES;  public TagKey<Block> BlockItemTagId.block()
```

- `LevelChunkSection.maybeHas` interroge la palette. Pour les palettes simple, linéaire et à hachage, cela revient à parcourir 1 à environ 256 entrées. Pour la **palette globale** (section très hétérogène), `GlobalPalette.maybeHas` renvoie **toujours `true`** (bytecode : `iconst_1; ireturn`) : il faut alors un balayage complet. C'est rare en terrain naturel.
- Autres tags de minerais : `BlockItemTags.{COAL_ORES, IRON_ORES, COPPER_ORES, GOLD_ORES, REDSTONE_ORES, LAPIS_ORES, EMERALD_ORES, DIAMOND_ORES, ORES}.block()`. Les débris antiques n'ont pas d'entrée dans `BlockItemTags` : on prend `Blocks.ANCIENT_DEBRIS`, ou `TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("..."))` [À VÉRIFIER : existence d'un tag vanilla].

```kotlin
data class OreHit(val pos: BlockPos, val state: BlockState)

/** Ores within [radius] blocks, from loaded chunks only (never loads or generates). Server thread only. */
fun scanOres(level: ServerLevel, center: BlockPos, radius: Int, ore: TagKey<Block>, maxHits: Int = 32): List<OreHit> {
    val r2 = radius * radius
    val isOre = Predicate<BlockState> { it.`is`(ore) }
    val hits = ArrayList<OreHit>()
    val minSy = SectionPos.blockToSectionCoord(maxOf(center.y - radius, level.minY))
    val maxSy = SectionPos.blockToSectionCoord(minOf(center.y + radius, level.maxY))
    for (cx in SectionPos.blockToSectionCoord(center.x - radius)..SectionPos.blockToSectionCoord(center.x + radius))
        for (cz in SectionPos.blockToSectionCoord(center.z - radius)..SectionPos.blockToSectionCoord(center.z + radius)) {
            val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
            for (sy in minSy..maxSy) {
                val section = chunk.getSection(level.getSectionIndexFromSectionY(sy))
                if (section.hasOnlyAir() || !section.maybeHas(isOre)) continue   // palette test: usually skips the section
                val bx = cx shl 4; val by = sy shl 4; val bz = cz shl 4
                for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                    val state = section.getBlockState(x, y, z)
                    if (!isOre.test(state)) continue
                    val dx = bx + x - center.x; val dy = by + y - center.y; val dz = bz + z - center.z
                    if (dx * dx + dy * dy + dz * dz <= r2) hits += OreHit(BlockPos(bx + x, by + y, bz + z), state)
                }
            }
        }
    return hits.sortedBy { it.pos.distSqr(center) }.take(maxHits)
}
// usage: scanOres(level, player.blockPosition(), 32, BlockItemTags.DIAMOND_ORES.block())
```

**Ordre de grandeur des coûts** (estimation, [À VÉRIFIER] par un micro-benchmark GameTest)

| Rayon | Chunks | Sections testées (±R en Y) | Test de palette | Sections balayées (4 096 lectures, environ 20 à 40 µs) | Total |
|---|---|---|---|---|---|
| 16 | 9 (3×3) | environ 27 | moins de 30 µs | 0 à 5 | **moins de 0,3 ms** |
| 32 | 25 (5×5) | environ 125 | environ 0,1 ms | 0 à 20 en deepslate | **0,1 à 1 ms** |
| 64 | 81 (9×9) | environ 729 | environ 0,5 ms | jusqu'à environ 100 | **1 à 5 ms** |

- Il faut plafonner le rayon à 48 dans l'outil. On peut aussi copier les sections candidates sur le thread serveur (`section.getStates().copy()`) et les balayer sur un autre thread. Lire une section hors du thread serveur sans copie est interdit (`LevelChunkSection.acquire()/release()`, `ThreadingDetector`).
- `PalettedContainer.count(consumer)` donne l'histogramme des blocs d'une section, sans positions. C'est idéal pour les « blocs dominants » d'`area_info` : on n'a pas à boucler sur les 4 096 blocs.
- `BlockState.is(TagKey)` vient de `core.TypedInstance.is(TagKey<T>)`, dont hérite `BlockBehaviour$BlockStateBase`.

### 3.6 Entités proches et regard

```
public List<Entity> Level.getEntities(Entity /*nullable*/, AABB, Predicate<? super Entity>)
public default <T extends Entity> List<T> EntityGetter.getEntitiesOfClass(Class<T>, AABB, Predicate<? super T>)
public List<ServerPlayer> ServerLevel.players()
public static AABB world.phys.AABB.ofSize(Vec3, double, double, double);  public AABB inflate(double)
public HitResult Entity.pick(double, float, boolean)                          // blocks
public static EntityHitResult world.entity.projectile.ProjectileUtil.getEntityHitResult(Entity, Vec3, Vec3, AABB, Predicate<Entity>, double)
public static Identifier EntityType.getKey(EntityType<?>)
```

```kotlin
val box = player.boundingBox.inflate(24.0)
val nearby = level.getEntities(player, box) { it.isAlive }
    .groupingBy { EntityType.getKey(it.type).toString() }.eachCount()
val hostiles = level.getEntitiesOfClass(Monster::class.java, box) { it.isAlive }   // world.entity.monster.Monster
val lookingAt = player.pick(20.0, 1f, false)   // BlockHitResult; HitResult.Type.MISS if nothing
```

**Pièges**
- `getEntities` ne parcourt que les sections d'entités couvertes par l'AABB : le coût croît avec le volume et le nombre d'entités. Il faut plafonner le rayon (32) et le nombre de lignes renvoyées.
- `ServerLevel.getEntities(EntityTypeTest, Predicate)` sans AABB parcourt tout le niveau : à éviter.
- `pick` lit des blocs le long du rayon. Au-delà de la distance de vue, il peut charger des chunks : il faut garder une distance de 20 blocs au plus.

### 3.7 État du joueur

| Donnée | Signature |
|---|---|
| Santé | `public float LivingEntity.getHealth()`, `public final float getMaxHealth()`, `public float getAbsorptionAmount()` |
| Faim | `public FoodData Player.getFoodData()` ; `FoodData.getFoodLevel()`, `getSaturationLevel()` |
| XP | champs publics de `Player` : `int experienceLevel`, `float experienceProgress`, `int totalExperience` |
| Inventaire | `public Inventory Player.getInventory()` ; `Inventory.getNonEquipmentItems()` (`NonNullList<ItemStack>`, 36 cases : 0 à 8 pour la barre, 9 à 35 pour le sac) ; `getContainerSize()` = 36 + 7 (`EQUIPMENT_SLOT_MAPPING` : 36 à 39 pour l'armure, 40 pour la seconde main, 41 pour l'armure du corps, 42 pour la selle) ; `getSelectedSlot()`, `getSelectedItem()` |
| Armure et mains | `public ItemStack LivingEntity.getItemBySlot(EquipmentSlot)` ; `EquipmentSlot.{MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD, BODY, SADDLE}` ; `public int getArmorValue()` |
| Effets actifs | `public Collection<MobEffectInstance> LivingEntity.getActiveEffects()` ; `MobEffectInstance.getEffect()` (`Holder<MobEffect>`), `getDuration()`, `getAmplifier()` ; `hasEffect(Holder)` |
| Mode de jeu | `public GameType ServerPlayer.gameMode()` ; `GameType.{SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR}`, `getName()` |
| Dernier lieu de mort | `public Optional<GlobalPos> Player.getLastDeathLocation()` ; `GlobalPos.dimension()`, `pos()` |
| Point de réapparition | `public ServerPlayer$RespawnConfig ServerPlayer.getRespawnConfig()` → nullable ; `RespawnConfig.respawnData()` (`LevelData$RespawnData` : `dimension()`, `pos()`, `yaw()`, `pitch()`) et `forced()`. S'il est `null`, c'est le point du monde : `MinecraftServer.getRespawnData()` |
| Nom | `public String Player.getPlainTextName()` ; `GameProfile.name()` (authlib 10 : c'est un record) |

```kotlin
fun ItemStack.describe(): String = "${count}x ${typeHolder().registeredName}" + (customName?.let { " \"${it.string}\"" } ?: "")
val armor = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
    .map { player.getItemBySlot(it) }.filterNot { it.isEmpty }.map { it.describe() }
val effects = player.activeEffects.map { "${it.effect.registeredName} ${it.amplifier + 1} (${it.duration / 20}s)" }
val spawn = player.respawnConfig?.respawnData() ?: player.level().server.respawnData
```

### 3.8 Registres et tags : valider les arguments du modèle

```
core.registries.BuiltInRegistries.{ITEM, BLOCK, ENTITY_TYPE}        // DefaultedRegistry
core.registries.BuiltInRegistries.{MOB_EFFECT, ATTRIBUTE, SOUND_EVENT, PARTICLE_TYPE, DATA_COMPONENT_TYPE, GAME_RULE}
core.registries.Registries.{ENCHANTMENT, BIOME, STRUCTURE, DAMAGE_TYPE, WORLD_CLOCK, DIMENSION}   // ResourceKey<Registry<…>> (dynamic)
public abstract Optional<Holder$Reference<T>> Registry.get(Identifier)
public abstract Set<Identifier> Registry.keySet();  public abstract boolean containsKey(Identifier)
public abstract Stream<HolderSet$Named<T>> Registry.getTags();   HolderSet$Named.key() → TagKey<T>
public abstract T Registry.getValue(Identifier)          // → nullable on Registry, but NEVER null on DefaultedRegistry (returns the default)
public abstract Identifier Registry.getKey(T)            // → nullable (non-null on DefaultedRegistry)
public default <E> Registry<E> RegistryAccess.lookupOrThrow(ResourceKey<? extends Registry<? extends E>>)
public default Holder$Reference<T> HolderGetter.getOrThrow(ResourceKey<T>);  getOrThrow(TagKey<T>) → HolderSet$Named<T>
public static Identifier Identifier.tryParse(String)     // → nullable
public default boolean world.flag.FeatureElement.isEnabled(FeatureFlagSet);  Level.enabledFeatures()
public boolean EntityType.canSummon()
```

```kotlin
fun parseId(raw: String): Identifier? = Identifier.tryParse(raw.lowercase())

fun item(level: ServerLevel, raw: String): Holder.Reference<Item>? =
    parseId(raw)?.let { BuiltInRegistries.ITEM.get(it).orElse(null) }
        ?.takeIf { it.value().isEnabled(level.enabledFeatures()) }

fun entityType(level: ServerLevel, raw: String): EntityType<*>? =
    parseId(raw)?.let { BuiltInRegistries.ENTITY_TYPE.get(it).orElse(null) }?.value()
        ?.takeIf { it.canSummon() && it.isEnabled(level.enabledFeatures()) }

fun enchantment(server: MinecraftServer, raw: String): Holder.Reference<Enchantment>? =
    parseId(raw)?.let { server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(it).orElse(null) }

val validItemIds: List<String> = BuiltInRegistries.ITEM.keySet().map(Identifier::toString).sorted()
val itemTags: List<String> = BuiltInRegistries.ITEM.getTags().map { "#" + it.key().location() }.toList()
```

**Pièges**
- **`BuiltInRegistries.ITEM.getValue(unknownId)` renvoie `Items.AIR`, pas `null`**, et de même `BLOCK` renvoie l'air et `ENTITY_TYPE` le cochon. Il faut toujours passer par `get(id)` (`Optional`) ou `containsKey`.
- Les enchantements, biomes, structures et types de dégâts sont des **registres dynamiques**. On les lit via `server.registryAccess()`, jamais via `BuiltInRegistries`. `world.item.enchantment.Enchantments.SHARPNESS` est une `ResourceKey<Enchantment>`, pas une valeur.
- Il faut filtrer les éléments expérimentaux avec `isEnabled(level.enabledFeatures())`.
- Pour éviter le « deviner puis se tromper », les listes (identifiants valides, tags) sont à donner au modèle **en résultat d'outil ou en message d'erreur de validation**, jamais dans le prompt système, qui doit rester stable (`CLAUDE.md`).

---

## 4. Actions : agir sur le monde

### 4.1 Chat, title, subtitle, actionbar, sons

```
public void ServerPlayer.sendSystemMessage(Component)             // chat (system)
public void ServerPlayer.sendOverlayMessage(Component)            // action bar
public void server.players.PlayerList.broadcastSystemMessage(Component, boolean /*overlay*/)
public void ServerCommonPacketListenerImpl.send(Packet<?>)        // player.connection.send(...)
network.protocol.game.ClientboundSetTitlesAnimationPacket(int fadeIn, int stay, int fadeOut)   // ticks
network.protocol.game.ClientboundSetSubtitleTextPacket(Component)
network.protocol.game.ClientboundSetTitleTextPacket(Component)
network.protocol.game.ClientboundSetActionBarTextPacket(Component)
network.protocol.game.ClientboundClearTitlesPacket(boolean resetTimes)
network.protocol.game.ClientboundSoundPacket(Holder<SoundEvent>, SoundSource, double, double, double, float, float, long seed)
public void Level.playSound(Entity /*nullable: excluded player*/, double, double, double, SoundEvent, SoundSource, float, float)
public static SoundEvent sounds.SoundEvent.createVariableRangeEvent(Identifier);  public static <T> Holder<T> Holder.direct(T)
```

```kotlin
fun title(p: ServerPlayer, title: Component, subtitle: Component?, fadeIn: Int = 10, stay: Int = 70, fadeOut: Int = 20) {
    p.connection.send(ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut))
    subtitle?.let { p.connection.send(ClientboundSetSubtitleTextPacket(it)) }
    p.connection.send(ClientboundSetTitleTextPacket(title))          // the title packet triggers the display
}

/** Private sound: only [p] hears it, at their position (same approach as /playsound, read in its bytecode). */
fun privateSound(p: ServerPlayer, id: Identifier, volume: Float = 1f, pitch: Float = 1f) {
    val sound = Holder.direct(SoundEvent.createVariableRangeEvent(id))
    p.connection.send(ClientboundSoundPacket(sound, SoundSource.MASTER, p.x, p.y, p.z, volume, pitch, p.random.nextLong()))
}
// Public sound heard by everyone nearby:
level.playSound(null, x, y, z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1f, 1f)
```

**Pièges**
- Il faut envoyer le sous-titre **avant** le titre : c'est le paquet de titre qui déclenche l'affichage.
- `SoundEvents.X` a un type variable : `SoundEvent` (`PLAYER_LEVELUP`, `WITHER_SPAWN`…) ou `Holder$Reference<SoundEvent>` (`AMBIENT_CAVE`, `NOTE_BLOCK_BELL`…). Pour un identifiant fourni par le modèle, on utilise `Holder.direct(SoundEvent.createVariableRangeEvent(id))`. Un identifiant inconnu du client est simplement silencieux, il faut donc le valider avec `BuiltInRegistries.SOUND_EVENT.containsKey(id)`.
- Les textes vus par les joueurs sont écrits par Claude. Le code ne fait que construire des `Component` (`Component.literal`, `withStyle(ChatFormatting…)`, `withColor(int)`).

### 4.2 Bossbar (`ServerBossEvent`)

```
server.level.ServerBossEvent(UUID, Component, BossEvent$BossBarColor, BossEvent$BossBarOverlay)
public void setProgress(float); setName(Component); setColor(..); setOverlay(..); setVisible(boolean)
public void addPlayer(ServerPlayer); removePlayer(ServerPlayer); removeAllPlayers(); Collection<ServerPlayer> getPlayers()
public BossEvent setDarkenScreen(boolean); setPlayBossMusic(boolean); setCreateWorldFog(boolean)
BossEvent$BossBarColor.{PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE}
BossEvent$BossBarOverlay.{PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20}
// persisted alternative (the /bossbar ones): MinecraftServer.getCustomBossEvents(): server.bossevents.CustomBossEvents
public CustomBossEvent create(RandomSource, Identifier, Component); get(Identifier) → nullable; remove(CustomBossEvent)
```

```kotlin
val bar = ServerBossEvent(UUID.randomUUID(), Component.literal("..."), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_10)
participants.forEach(bar::addPlayer)
bar.progress = (remaining.toFloat() / total).coerceIn(0f, 1f)   // setProgress stores the value as is (bytecode): clamp it yourself
// end of challenge:
bar.removeAllPlayers()
```

**Pièges**
- Une `ServerBossEvent` vit **en mémoire seulement**. Au redémarrage, elle disparaît. Il faut la recréer depuis l'état du moteur (défis) et réajouter les joueurs à la connexion (`ServerPlayerEvents.JOIN`).
- À la réapparition, `PlayerList.respawn` crée un **nouveau** `ServerPlayer`, comme le montre le bytecode (`new ServerPlayer` puis `restoreFrom`). La barre garde une référence à l'ancien objet. Dans `ServerPlayerEvents.AFTER_RESPAWN(old, new, alive)`, il faut faire `removePlayer(old)` puis `addPlayer(new)`. Que le client conserve l'overlay à travers le paquet de réapparition reste [À VÉRIFIER] ; la manœuvre est sûre dans tous les cas.
- `CustomBossEvents` est persistant (données sauvegardées) et suit les connexions par UUID. En revanche, il est visible et modifiable par `/bossbar` et apparaît dans les suggestions des opérateurs. Pour le Narrateur, on préfère des `ServerBossEvent` pilotées par le moteur.

### 4.3 Livre écrit (`written_book` en 26.3)

```
public static final DataComponentType<WrittenBookContent> core.component.DataComponents.WRITTEN_BOOK_CONTENT
world.item.component.WrittenBookContent(Filterable<String> title, String author, int generation, List<Filterable<Component>> pages, boolean resolved)
  constants: PAGE_LENGTH = 32767, TITLE_LENGTH = 16, TITLE_MAX_LENGTH = 32, MAX_GENERATION = 3  (codec: title ≤ 32)
public static <T> Filterable<T> server.network.Filterable.passThrough(T)
public void ServerPlayer.openItemGui(ItemStack, InteractionHand)
public static boolean WrittenBookContent.resolveForItem(ItemStack, ResolutionContext, HolderLookup$Provider)
WritableBookContent.MAX_PAGES = 100
```

```kotlin
fun writtenBook(title: String, author: String, pages: List<Component>): ItemStack =
    ItemStack(Items.WRITTEN_BOOK).apply {
        set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent(
            Filterable.passThrough(title.take(32)), author, 0,
            pages.take(100).map { Filterable.passThrough(it) }, true))   // resolved = true: no selector/score resolution needed
    }
```

**Pièges**
- Le titre ne doit pas dépasser 32 caractères : au-delà, le codec rejette le composant, donc la sauvegarde et la synchronisation échouent.
- `resolved = true` pour du texte brut. Avec `false`, le texte est résolu à l'ouverture (`openItemGui` appelle `ResolutionContext.create(createCommandSourceStack())`).
- `openItemGui` envoie `ClientboundOpenBookPacket(hand)` : le client ouvre le livre **tenu dans cette main**. Pour « forcer la lecture », il faut donc d'abord placer le livre en main, puis l'ouvrir.

### 4.4 Effets (`MobEffectInstance`)

```
world.effect.MobEffectInstance(Holder<MobEffect>, int duration, int amplifier, boolean ambient, boolean visible, boolean showIcon)
  INFINITE_DURATION = -1, MIN_AMPLIFIER = 0, MAX_AMPLIFIER = 255
public final boolean LivingEntity.addEffect(MobEffectInstance);  public boolean addEffect(MobEffectInstance, Entity /*nullable source*/)
public boolean removeEffect(Holder<MobEffect>);  public boolean removeAllEffects()
world.effect.MobEffects.{SPEED, SLOWNESS, HASTE, MINING_FATIGUE, JUMP_BOOST, NAUSEA, REGENERATION, RESISTANCE, BLINDNESS, NIGHT_VISION, GLOWING, LEVITATION, SLOW_FALLING, DARKNESS, ...}   // Holder<MobEffect>
```

```kotlin
val effect = parseId(raw)?.let { BuiltInRegistries.MOB_EFFECT.get(it).orElse(null) } ?: return error("unknown effect")
player.addEffect(MobEffectInstance(effect, seconds * 20, (level - 1).coerceIn(0, 4), false, true, true))
```

**Pièges**
- La durée s'exprime en ticks.
- `addEffect` ne remplace un effet existant que s'il est plus fort ou plus long (`MobEffectInstance.update`). Pour imposer un effet, il faut d'abord appeler `removeEffect`.
- Les effets sont perdus à la mort, puisque le joueur est recréé.
- Il faut plafonner l'amplificateur : `LEVITATION 255` envoie le joueur dans l'espace.

### 4.5 Attributs (`AttributeModifier`)

```
world.entity.ai.attributes.AttributeModifier(Identifier id, double amount, AttributeModifier$Operation op)   // record
AttributeModifier$Operation.{ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL}
public AttributeInstance LivingEntity.getAttribute(Holder<Attribute>)       // → nullable
// world.entity.ai.attributes.AttributeInstance
public void addTransientModifier(AttributeModifier)          // throws IllegalArgumentException "Modifier is already applied on this attribute!" if id exists
public void addOrUpdateTransientModifier(AttributeModifier)
public void addPermanentModifier(AttributeModifier);  public void addOrReplacePermanentModifier(AttributeModifier)
public boolean removeModifier(Identifier);  public boolean hasModifier(Identifier);  public AttributeModifier getModifier(Identifier)
public double getValue();  public double getBaseValue();  public void setBaseValue(double)
```

**Opérations** : `ADD_VALUE` ajoute au socle ; `ADD_MULTIPLIED_BASE` ajoute `socle × montant` ; `ADD_MULTIPLIED_TOTAL` multiplie le total par `(1 + montant)`. Le résultat est ensuite borné par `RangedAttribute.sanitizeValue` à [min, max].

**Persistance, vérifiée dans le bytecode**

| Situation | Modificateur *transient* | Modificateur *permanent* |
|---|---|---|
| Déconnexion ou redémarrage | **Perdu** : `AttributeInstance.pack()` n'écrit que `permanentModifiers` | Sauvegardé dans les données du joueur (tag `attributes`) |
| Mort, puis réapparition (`restoreFrom(old, false)`) | Perdu | **Perdu** : seul `assignBaseValues` est copié |
| Sortie de l'End (`restoreFrom(old, true)`) | Perdu | Conservé (`assignPermanentModifiers`) |

**Recommandation.** On applique toujours des modificateurs **transient** et on garde notre propre registre persistant, en DuckDB ou en `SavedData`, avec les colonnes `uuid, attribute, modifierId, amount, operation, expiresAt`.
- `ServerPlayerEvents.JOIN` et `AFTER_RESPAWN` : on réapplique ce qui n'a pas expiré.
- Tâche périodique (une fois par seconde) : on retire ce qui a expiré, et on le supprime du registre.
- Avantage : un crash ou une coupure ne laisse **jamais** de modificateur fantôme dans les données du joueur. Le rattrapage se limite à « réappliquer ».
- Sécurité supplémentaire : à la connexion, on retire tout modificateur dont l'identifiant a pour espace de noms `narrator2` et qui ne figure pas dans le registre.
- La durée peut se compter en temps réel ou en temps joué : c'est un choix de conception à trancher dans M4.

```kotlin
/** Applies a temporary modifier; the engine's store handles expiry and re-application. */
fun applyModifier(player: ServerPlayer, attribute: Holder<Attribute>, key: String, amount: Double, op: AttributeModifier.Operation): Boolean {
    val instance = player.getAttribute(attribute) ?: return false        // players have every attribute below
    val id = Identifier.fromNamespaceAndPath("narrator2", "mod/$key")      // path: [a-z0-9/._-]
    instance.addOrUpdateTransientModifier(AttributeModifier(id, amount, op))
    return true
}
fun removeModifier(player: ServerPlayer, attribute: Holder<Attribute>, key: String) {
    player.getAttribute(attribute)?.removeModifier(Identifier.fromNamespaceAndPath("narrator2", "mod/$key"))
}
// tiny player for 5 minutes: applyModifier(p, Attributes.SCALE, "punish_scale", -0.75, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
```

**Attributs du §6.2 : noms réels dans le registre 26.3.** Tous sont en `minecraft:<nom>` (`Identifier.withDefaultNamespace`), tous sont présents sur le joueur (`LivingEntity.createLivingAttributes` et `Player.createAttributes`) et, sauf mention contraire, synchronisés avec le client. Les valeurs sont lues dans le `<clinit>` de `Attributes`.

| §6.2 | Constante (`Holder<Attribute>`) | Id registre | Défaut | Min | Max | Remarque |
|---|---|---|---|---|---|---|
| scale | `Attributes.SCALE` | `minecraft:scale` | 1 | 0,0625 | 16 | Appelle `refreshDimensions()`. Grandir sous un plafond fait suffoquer. |
| gravity | `GRAVITY` | `minecraft:gravity` | 0,08 | -1 | 1 | Une valeur négative fait s'envoler le joueur, **dangereux**. |
| jump_strength | `JUMP_STRENGTH` | `minecraft:jump_strength` | 0,42 | 0 | 32 | |
| movement_speed | `MOVEMENT_SPEED` | `minecraft:movement_speed` | 0,7 (socle du joueur : **0,1**) | 0 | 1024 | À multiplier (`ADD_MULTIPLIED_BASE`) plutôt qu'à additionner. |
| block_break_speed | `BLOCK_BREAK_SPEED` | `minecraft:block_break_speed` | 1 | 0 | 1024 | 0 : le joueur ne peut plus miner. |
| safe_fall_distance | `SAFE_FALL_DISTANCE` | `minecraft:safe_fall_distance` | 3 | -1024 | 1024 | |
| step_height | `STEP_HEIGHT` | `minecraft:step_height` | 0,6 | 0 | 10 | |
| block_interaction_range | `BLOCK_INTERACTION_RANGE` | `minecraft:block_interaction_range` | 4,5 | 0 | 64 | |
| entity_interaction_range | `ENTITY_INTERACTION_RANGE` | `minecraft:entity_interaction_range` | 3 | 0 | 64 | |
| max_health | `MAX_HEALTH` | `minecraft:max_health` | 20 | 1 | 1024 | Si le maximum baisse, la santé est ramenée au maximum (`onAttributeUpdated`). Si on retire la baisse, elle **ne remonte pas**. |
| attack_speed | `ATTACK_SPEED` | `minecraft:attack_speed` | 4 | 0 | 1024 | |
| knockback_resistance | `KNOCKBACK_RESISTANCE` | `minecraft:knockback_resistance` | 0 | **-2** | 1 | **Non synchronisé.** Une valeur négative amplifie le recul (nouveau en 26.2). |
| oxygen_bonus | `OXYGEN_BONUS` | `minecraft:oxygen_bonus` | 0 | 0 | 1024 | |
| burning_time | `BURNING_TIME` | `minecraft:burning_time` | 1 | 0 | 1024 | Multiplicateur de la durée de combustion. |
| *(bonus)* fall_damage_multiplier | `FALL_DAMAGE_MULTIPLIER` | `minecraft:fall_damage_multiplier` | 1 | 0 | 100 | Utile pour protéger un joueur « lancé ». |
| *(bonus)* bounciness | `BOUNCINESS` | `minecraft:bounciness` | 0 | 0 | 1 | Nouveau en 26.2 : rebond sur collision. |
| *(bonus)* friction_modifier | `FRICTION_MODIFIER` | `minecraft:friction_modifier` | 1 | 0 | 2048 | Nouveau en 26.2. |
| *(bonus)* air_drag_modifier | `AIR_DRAG_MODIFIER` | `minecraft:air_drag_modifier` | 1 | 0 | 2048 | Nouveau en 26.2. |
| *(bonus)* camera_distance | `CAMERA_DISTANCE` | `minecraft:camera_distance` | 4 | 0 | 32 | Distance de la caméra à la troisième personne [À VÉRIFIER : sémantique non documentée dans les guides]. |
| *(bonus)* movement_efficiency, water_movement_efficiency, explosion_knockback_resistance, mining_efficiency, submerged_mining_speed, sneaking_speed, luck, max_absorption, armor, armor_toughness, attack_damage (socle du joueur : 1), attack_knockback, sweeping_damage_ratio, name_tag_distance, below_name_distance | noms identiques en majuscules | `minecraft:<nom>` | | | | `flying_speed` n'est **pas** présent sur le joueur (son vol passe par `Abilities`). |

### 4.6 Téléportation

```
public boolean ServerPlayer.teleportTo(ServerLevel, double, double, double, Set<Relative>, float yRot, float xRot, boolean resetCamera)
public ServerPlayer ServerPlayer.teleport(TeleportTransition)                       // cross-dimension, full control
public void ServerPlayer.teleportTo(double, double, double)                         // same level
world.level.portal.TeleportTransition(ServerLevel, Vec3 pos, Vec3 delta, float yRot, float xRot, Set<Relative>, TeleportTransition$PostTeleportTransition)
TeleportTransition.{DO_NOTHING, PLAY_PORTAL_SOUND, PLACE_PORTAL_TICKET}
world.entity.Relative.{X, Y, Z, Y_ROT, X_ROT, DELTA_X, DELTA_Y, DELTA_Z, ROTATE_DELTA};  Relative.ALL, ROTATION, DELTA  (Set<Relative>)
public static boolean Level.isInSpawnableBounds(BlockPos);  public boolean WorldBorder.isWithinBounds(BlockPos)   // level.worldBorder
public CompletableFuture<?> ServerChunkCache.addTicketAndLoadWithRadius(TicketType, ChunkPos, int)
server.level.TicketType.PORTAL   // timeout 300 ticks, flags 15 (persist | loading | simulation | keep dimension active)
public default boolean CollisionGetter.noCollision(Entity, AABB)
public boolean EntityType.isBlockDangerous(BlockState)   // fire, magma, lava…, wither rose, sweet berry bush, cactus, powder snow
public static Vec3 world.entity.vehicle.DismountHelper.findSafeDismountLocation(EntityType<?>, CollisionGetter, BlockPos, boolean)   // → nullable
```

Dans `ServerPlayer.teleportTo(ServerLevel, …, boolean resetCamera)`, le bytecode montre que le joueur est réveillé s'il dort (`stopSleepInBed`), que la caméra est réinitialisée si `resetCamera` vaut `true`, puis que `Player.teleportTo` est appelé ; celui-ci gère le changement de dimension. Les `Relative` rendent les composantes relatives : on passe `Relative.ROTATION` pour conserver le regard du joueur, et `emptySet()` pour une téléportation absolue.

**Atterrissage sûr**, à partir de primitives vérifiées :

```kotlin
/** Finds a safe standing spot in column (x, z), in a LOADED chunk. Returns the feet position or null. Server thread only. */
fun safeLanding(level: ServerLevel, player: ServerPlayer, x: Int, z: Int, preferY: Int? = null): Vec3? {
    if (!level.hasChunkAt(BlockPos(x, 0, z))) return null
    val type = level.dimensionType()
    val top = if (type.hasCeiling()) minOf(preferY ?: 100, type.logicalHeight() - 2)          // Nether: scan below the roof
              else level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
    val cursor = BlockPos.MutableBlockPos()
    for (y in top downTo level.minY + 1) {
        val feet = cursor.set(x, y, z).immutable()
        val floor = level.getBlockState(feet.below())
        if (floor.isAir || floor.liquid() || player.type.isBlockDangerous(floor)) continue
        if (!floor.isFaceSturdy(level, feet.below(), Direction.UP)) continue
        val spot = Vec3.atBottomCenterOf(feet)
        val box = player.getDimensions(player.pose).makeBoundingBox(spot)          // honours the scale attribute
        if (level.noCollision(player, box) && !level.containsAnyLiquid(box) && level.worldBorder.isWithinBounds(feet)) return spot
    }
    return null
}

/** Teleports without ever loading the target chunk on the server thread: loads it asynchronously first. */
fun teleportSafely(server: MinecraftServer, player: ServerPlayer, target: ServerLevel, x: Int, z: Int, onDone: (Boolean) -> Unit) {
    target.chunkSource.addTicketAndLoadWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos(x, 0, z)), 1)
        .thenRunAsync({
            val spot = safeLanding(target, player, x, z)
            onDone(spot != null && player.teleportTo(target, spot.x, spot.y, spot.z, Relative.ROTATION, 0f, 0f, true))
        }, server)
}
```

**Pièges**
- `getBlockState` sur une colonne non chargée **génère** le chunk sur le thread serveur, ce qui prend de 10 à plus de 100 ms en terrain neuf. D'où le chargement asynchrone par ticket, puis la téléportation. Le ticket `PORTAL` expire après 300 ticks.
- `addTicketAndLoadWithRadius` lève une exception si le type de ticket ne charge pas, ou s'il peut expirer une fois déchargé (lu dans le bytecode) : `PORTAL` et `ENDER_PEARL` conviennent, `UNKNOWN` non.
- Le thread sur lequel se termine le futur n'est pas établi [À VÉRIFIER], d'où `thenRunAsync(..., server)`.
- Dans le Nether, toutes les heightmaps donnent le plafond de bedrock : on balaie vers le bas depuis `logicalHeight`.
- Un joueur sur une monture : on ne sait pas encore si `teleportTo` le fait descendre ou emmène la monture [À VÉRIFIER]. Dans le doute, on appelle `player.stopRiding()` avant.
- Il faut toujours vérifier `Level.isInSpawnableBounds` et la bordure du monde.
- `Relative` signifie « relatif à la position actuelle ». Pour garder la vitesse, on ajoute `Relative.DELTA`.

### 4.7 Propulsion

```
public void Entity.setDeltaMovement(Vec3);  public void setDeltaMovement(double, double, double);  public void addDeltaMovement(Vec3)
public boolean Entity.syncVelocity    // public field, was `hurtMarked`
public boolean Entity.needsSync       // public field; set by push(double, double, double)
network.protocol.game.ClientboundSetEntityMotionPacket(Entity)  /  (int, Vec3)
```

`ServerEntity.sendChanges` (bytecode) :
- si `needsSync` : il envoie `ClientboundSetEntityMotionPacket` via `sendToTrackingPlayers`, **c'est-à-dire aux autres joueurs uniquement** ;
- si `syncVelocity` : il le remet à `false` et envoie `ClientboundSetEntityMotionPacket(entity)` via `sendToTrackingPlayersAndSelf`, **joueur compris**.

Le mouvement d'un joueur est calculé par son client : seule la seconde voie le propulse vraiment. `Entity.markHurt()`, qui est protégé, fait exactement `syncVelocity = true`.

```kotlin
fun launch(player: ServerPlayer, velocity: Vec3) {
    player.setDeltaMovement(velocity)
    player.syncVelocity = true                                                  // sent at the next entity tracker tick
    // or immediately: player.connection.send(ClientboundSetEntityMotionPacket(player))
    // Avoid the fall-damage kill: temporary fall_damage_multiplier = 0 (or SLOW_FALLING) for ~10 s.
}
```

**Pièges**
- **Dégâts de chute** à la retombée : il faut les neutraliser temporairement, puis les rétablir.
- **Anti-triche.** `ServerGamePacketListenerImpl` rejette un mouvement si `distance² − |deltaMovement serveur|² > 100 × paquets`, ou 300 en élytre (« moved too quickly »). La vitesse imposée côté serveur est donc tolérée dans une certaine mesure. On reste sous environ 3 à 4 blocs par tick par composante [À VÉRIFIER en jeu].
- Pour les mobs, `needsSync` suffit, mais `syncVelocity` fonctionne aussi.

### 4.8 Donner et prendre des items, composants

```
world.item.ItemStack(ItemLike, int);  ItemStack(Holder<Item>, int);  ItemStack(Holder<Item>, int, DataComponentPatch)
public <T> T set(DataComponentType<T>, T /*nullable*/)  → nullable (previous value);  public <T> T remove(DataComponentType<? extends T>)
public void enchant(Holder<Enchantment>, int);  public ItemEnchantments getEnchantments()
DataComponents.CUSTOM_NAME: DataComponentType<Component>;  ITEM_NAME: <Component>
DataComponents.LORE: <world.item.component.ItemLore>        ItemLore(List<Component>)  MAX_LINES = 256
DataComponents.ENCHANTMENTS / STORED_ENCHANTMENTS: <world.item.enchantment.ItemEnchantments>
  ItemEnchantments.EMPTY;  ItemEnchantments$Mutable(ItemEnchantments).set(Holder<Enchantment>, int); toImmutable()
DataComponents.UNBREAKABLE: <util.Unit>   (Unit.INSTANCE)
DataComponents.CUSTOM_DATA: <world.item.component.CustomData>   CustomData.of(CompoundTag), copyTag()
DataComponents.ENCHANTMENT_GLINT_OVERRIDE: <Boolean>;  RARITY: <world.item.Rarity>;  DAMAGE, MAX_DAMAGE: <Integer>
DataComponents.FIREWORKS, WRITTEN_BOOK_CONTENT, TOOLTIP_DISPLAY, ATTRIBUTE_MODIFIERS, DYED_COLOR, POTION_CONTENTS, ...
public boolean Inventory.add(ItemStack)                        // shrinks the passed stack by what was inserted
public ItemEntity LivingEntity.createItemStackToDrop(ItemStack, boolean, boolean)   → nullable
public void ItemEntity.setNoPickUpDelay();  public void setTarget(UUID)
public int Inventory.clearOrCountMatchingItems(Predicate<ItemStack>, boolean countOnly, int maxCount /* -1 = all */, Container craftSlots)
public ItemStack Inventory.removeItem(int slot, int count);  getItem(int);  setItem(int, ItemStack)
public AbstractContainerMenu Player.containerMenu (field);  public void broadcastChanges()
```

Pour obtenir un `Holder<Enchantment>` :

```kotlin
val enchantments = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)   // Registry<Enchantment>
val sharpness: Holder.Reference<Enchantment> = enchantments.getOrThrow(Enchantments.SHARPNESS)   // ResourceKey
val fromModel: Holder.Reference<Enchantment>? = enchantments.get(Identifier.parse("minecraft:mending")).orElse(null)
```

Construire un objet à partir du JSON du modèle :

```kotlin
fun buildItem(server: MinecraftServer, spec: ItemSpec): ItemStack {
    val item = BuiltInRegistries.ITEM.get(spec.id).orElseThrow()
    val stack = ItemStack(item, spec.count.coerceIn(1, item.value().defaultMaxStackSize))
    spec.name?.let { stack.set(DataComponents.CUSTOM_NAME, Component.literal(it).withStyle { s -> s.withItalic(false) }) }
    if (spec.lore.isNotEmpty()) stack.set(DataComponents.LORE, ItemLore(spec.lore.take(8).map(Component::literal)))
    if (spec.unbreakable) stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE)
    val registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
    val mutable = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
    spec.enchantments.forEach { (id, lvl) -> mutable.set(registry.get(id).orElseThrow(), lvl.coerceIn(1, 10)) }
    stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable())
    CustomData.set(DataComponents.CUSTOM_DATA, stack, CompoundTag().apply { putString("narrator2", spec.tag) })  // trace the gift
    return stack
}

/** Same flow as /give (read in GiveCommand's bytecode): insert, drop the rest owned by the player, sync. */
fun give(player: ServerPlayer, stack: ItemStack) {
    val rest = stack.copy()
    player.inventory.add(rest)
    if (!rest.isEmpty) player.createItemStackToDrop(rest, false, false)?.let {
        it.setNoPickUpDelay(); it.setTarget(player.uuid); player.level().addFreshEntity(it)
    }
    player.containerMenu.broadcastChanges()
}
```

Vérifiés par javap : `public int Item.getDefaultMaxStackSize()` et `public default int ItemInstance.getMaxStackSize()`, que `ItemStack` hérite.

**Prendre (confisquer) de façon traçable**, conformément au §17 : un objet n'est jamais perdu définitivement.

```kotlin
fun confiscate(player: ServerPlayer, match: (ItemStack) -> Boolean, max: Int): List<ItemStack> {
    val inv = player.inventory
    val taken = ArrayList<ItemStack>()
    var left = max
    for (slot in 0 until inv.containerSize) {
        if (left <= 0) break
        val s = inv.getItem(slot)
        if (s.isEmpty || !match(s)) continue
        val removed = inv.removeItem(slot, minOf(left, s.count))
        left -= removed.count; taken += removed
    }
    player.containerMenu.broadcastChanges()
    return taken   // persist with ItemStack.CODEC before returning (see §5.1) so it can be given back
}
```

Sérialisation durable d'un `ItemStack` : `ItemStack.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)`, puis `CompoundTag.toString()` (SNBT). Pour relire : `TagParser.parseCompoundFully(snbt)`, puis `ItemStack.CODEC.parse(...)`.

**Pièges**
- Un `CUSTOM_NAME` s'affiche en **italique** par défaut : `withItalic(false)`.
- Le lore a un style violet italique par défaut (`ItemLore.LORE_STYLE`).
- `Inventory.add` **modifie** le stack passé : il faut travailler sur une copie.
- Les niveaux d'enchantement ne sont pas bornés par le codec, mais un niveau absurde est de mauvais goût : on plafonne.
- Tant que `containerMenu.broadcastChanges()` n'est pas appelé, le client ne voit rien.
- La case du curseur (`containerMenu.getCarried()`) et la grille d'artisanat ne sont pas dans `Inventory` : `confiscate` les ignore.

### 4.9 Invoquer des entités

```
public T EntityType.create(Level, EntitySpawnReason)  → nullable
public T EntityType.spawn(ServerLevel, BlockPos, EntitySpawnReason)  → nullable    // does finalizeSpawn + add
public SpawnGroupData Mob.finalizeSpawn(ServerLevelAccessor, DifficultyInstance, EntitySpawnReason, SpawnGroupData /*nullable*/)  → nullable
public boolean ServerLevel.tryAddFreshEntityWithPassengers(Entity);  public boolean addFreshEntity(Entity)
public void Entity.snapTo(double, double, double, float, float);  setCustomName(Component /*nullable*/);  setCustomNameVisible(boolean);  addTag(String)
public void LivingEntity.setItemSlot(EquipmentSlot, ItemStack);  public void Mob.setDropChance(EquipmentSlot, float);  setPersistenceRequired()
public void Mob.setTarget(LivingEntity /*nullable*/);  public boolean Entity.startRiding(Entity, boolean force, boolean)
world.entity.EntitySpawnReason.{COMMAND, TRIGGERED, EVENT, MOB_SUMMONED, ...}
world.entity.EntityTypes.{ZOMBIE, SKELETON_HORSE, CHICKEN, SLIME, LIGHTNING_BOLT, FIREWORK_ROCKET, TNT, ...}
```

```kotlin
/** Spawns a named, equipped, persistent mob that targets [victim]; mirrors SummonCommand.createEntity. */
fun summon(level: ServerLevel, type: EntityType<*>, at: Vec3, name: String?, victim: ServerPlayer?): Entity? {
    if (!Level.isInSpawnableBounds(BlockPos.containing(at))) return null
    val e = type.create(level, EntitySpawnReason.COMMAND) ?: return null
    e.snapTo(at.x, at.y, at.z, level.random.nextFloat() * 360f, 0f)
    if (e is Mob) {
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()), EntitySpawnReason.COMMAND, null)
        e.setPersistenceRequired()
        e.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.CARVED_PUMPKIN)); e.setDropChance(EquipmentSlot.HEAD, 0f)
        e.getAttribute(Attributes.MAX_HEALTH)?.baseValue = 30.0; e.health = e.maxHealth
        victim?.let { e.target = it }
    }
    name?.let { e.customName = Component.literal(it); e.isCustomNameVisible = true }
    e.addTag("narrator2")                                        // persisted "Tags": lets us find/clean them after a restart
    return if (level.tryAddFreshEntityWithPassengers(e)) e else null
}
// mount: rider.startRiding(mount, true, true)
```

**Pièges**
- Le mode paisible (`Difficulty.PEACEFUL`) : on teste `type.isAllowedInPeaceful()`, comme `SummonCommand`.
- `finalizeSpawn` peut équiper l'entité au hasard (zombies) : on écrase l'équipement **après** l'appel.
- `setTarget` n'a qu'un effet partiel sur les mobs pilotés par un `Brain` (piglins, hoglins, warden, villageois…), qui lisent la mémoire `ATTACK_TARGET` [À VÉRIFIER : quels mobs l'ignorent].
- Le plafond du nombre d'entités (§6.3) est à appliquer par le moteur, en comptant les entités portant le tag `narrator2`.

### 4.10 Monde : explosions, éclairs, feux d'artifice, particules, météo, heure

**Explosions**

```
public void Level.explode(Entity /*nullable*/, double, double, double, float radius, Level$ExplosionInteraction)          // fire = false
public void Level.explode(Entity /*nullable*/, double, double, double, float radius, boolean fire, Level$ExplosionInteraction)
public void Level.explode(Entity, DamageSource, ExplosionDamageCalculator, double, double, double, float, boolean, Level$ExplosionInteraction)  // params 0-2 nullable
Level$ExplosionInteraction.{NONE, BLOCK, MOB, TNT, TRIGGER}
world.level.SimpleExplosionDamageCalculator(boolean explodesBlocks, boolean damagesEntities, Optional<Float> knockbackMultiplier, Optional<HolderSet<Block>> immuneBlocks)
```

Correspondance lue dans `ServerLevel.explode` :
- `NONE` → `KEEP` ;
- `BLOCK` → `DESTROY` ou `DESTROY_WITH_DECAY` (règle `BLOCK_EXPLOSION_DROP_DECAY`) ;
- `MOB` → `KEEP` si `MOB_GRIEFING` est faux ;
- `TNT` → règle `TNT_EXPLOSION_DROP_DECAY` ;
- `TRIGGER` → `TRIGGER_BLOCK`.

`KEEP` n'affecte ni les blocs ni les entités « blocs » (`shouldAffectBlocklikeEntities = false`, lu dans le `<clinit>`). L'explosion blesse et repousse tout de même les entités, et le paquet d'effet part vers les joueurs.

```kotlin
level.explode(null, x, y, z, radius.coerceAtMost(4f), Level.ExplosionInteraction.NONE)   // hurts, never breaks blocks
```

**Éclairs**

```kotlin
val bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED) ?: return
bolt.snapTo(Vec3.atBottomCenterOf(pos))
bolt.setVisualOnly(true)            // no fire, no damage, no creeper/pig conversion: the "warning" bolt
level.addFreshEntity(bolt)
```

Signatures : `public void LightningBolt.setVisualOnly(boolean)` et `public void setCause(ServerPlayer /*nullable*/)`. Un éclair non visuel enflamme les blocs, blesse, et transforme cochons, villageois et creepers.

**Feux d'artifice**

`Level.createFireworks(...)` est **vide côté serveur**. On fait apparaître une fusée :

```
world.entity.projectile.FireworkRocketEntity(Level, double, double, double, ItemStack)
world.item.component.Fireworks(int flightDuration, List<FireworkExplosion>)   MAX_EXPLOSIONS = 256
world.item.component.FireworkExplosion(FireworkExplosion$Shape, IntList colors, IntList fadeColors, boolean trail, boolean twinkle)
FireworkExplosion$Shape.{SMALL_BALL, LARGE_BALL, STAR, CREEPER, BURST}
```

```kotlin
val rocket = ItemStack(Items.FIREWORK_ROCKET).apply {
    set(DataComponents.FIREWORKS, Fireworks(1, listOf(FireworkExplosion(FireworkExplosion.Shape.STAR, IntArrayList.of(0xFFD700), IntArrayList(), true, true))))
}
level.addFreshEntity(FireworkRocketEntity(level, x, y, z, rocket))
```

Piège : une fusée qui porte des explosions **blesse** les entités proches quand elle éclate [À VÉRIFIER : rayon et dégâts]. On la fait éclater à au moins 5 blocs des joueurs.

**Particules**

```
public <T extends ParticleOptions> int ServerLevel.sendParticles(T, double x, double y, double z, int count, double dx, double dy, double dz, double speed)
public <T extends ParticleOptions> int sendParticles(T, boolean overrideLimiter, boolean alwaysShow, double, double, double, int, double, double, double, double)
public <T extends ParticleOptions> boolean sendParticles(ServerPlayer, T, boolean overrideLimiter, boolean alwaysShow, double, double, double, int, double, double, double, double)
core.particles.ParticleTypes.{HEART, ANGRY_VILLAGER, FLAME, SOUL_FIRE_FLAME, END_ROD, ENCHANT, WITCH, CLOUD, LARGE_SMOKE, FIREWORK, EXPLOSION_EMITTER, SCULK_SOUL, ...}  // SimpleParticleType
core.particles.DustParticleOptions(int rgb, float scale)
```

```kotlin
level.sendParticles(ParticleTypes.HEART, p.x, p.y + 2, p.z, 12, 0.6, 0.4, 0.6, 0.02)
```

- Portée d'envoi, lue dans le bytecode : 32 blocs, ou 512 si `overrideLimiter` vaut `true`.
- Seuls les `SimpleParticleType` s'utilisent sans options. Les autres (`DRAGON_BREATH` → `PowerParticleOption`, poussière, blocs…) demandent leur type d'options : on limite l'outil à une liste blanche.
- Il faut plafonner `count` (par exemple à 200).

**Météo**

```
public void MinecraftServer.setWeatherParameters(int clearTime, int weatherTime, boolean raining, boolean thundering)
```

Valeurs lues dans `WeatherCommand` :
- `clear` → `(d, 0, false, false)` ;
- `rain` → `(0, d, true, false)` ;
- `thunder` → `(0, d, true, true)`.

Les durées sont en ticks. La météo est **commune à tout le serveur**.

**Heure**

```
public ServerClockManager MinecraftServer.clockManager()
public void ServerClockManager.setTotalTicks(Holder<WorldClock>, long);  addTicks(Holder<WorldClock>, int);  setPaused(..., boolean);  setRate(..., float)
public ServerClockManager$MoveResult moveToTimeMarker(Holder<WorldClock>, ResourceKey<ClockTimeMarker>)   // NO_TIME_MARKER_FOUND / NOT_MOVED / MOVED
world.clock.WorldClocks.{OVERWORLD, THE_END}   world.clock.ClockTimeMarkers.{DAY, NOON, NIGHT, MIDNIGHT, WAKE_UP_FROM_SLEEP, ROLL_VILLAGE_SIEGE}
```

```kotlin
val clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD)
server.clockManager().moveToTimeMarker(clock, ClockTimeMarkers.NIGHT)        // like "/time set night"
```

- `ServerClockManager.modifyClock` diffuse `ClientboundSetTimePacket` à tous les joueurs et invalide le cache des attributs d'environnement : aucune synchronisation manuelle n'est nécessaire.
- Horloge de la dimension : `level.dimensionType().defaultClock()` (`Optional<Holder<WorldClock>>`).

### 4.11 Commandes : analyser sans exécuter, usage, exécution capturée

**Signatures**

```
public Commands MinecraftServer.getCommands();  public CommandDispatcher<CommandSourceStack> Commands.getDispatcher()
public static String Commands.trimOptionalPrefix(String)
public static <S> CommandSyntaxException Commands.getParseException(ParseResults<S>)  → nullable (null ⇔ reader fully consumed)
public void Commands.performCommand(ParseResults<CommandSourceStack>, String)
// Brigadier 1.3.11 (javap on brigadier-1.3.11.jar)
public ParseResults<S> CommandDispatcher.parse(String, S)
public String[] CommandDispatcher.getAllUsage(CommandNode<S>, S, boolean restricted)
public Map<CommandNode<S>, String> CommandDispatcher.getSmartUsage(CommandNode<S>, S)
public RootCommandNode<S> getRoot();  public CommandNode<S> CommandNode.getChild(String);  public CommandNode<S> findNode(Collection<String>)
ParseResults: getContext(): CommandContextBuilder<S>; getReader(): ImmutableStringReader; getExceptions(): Map<CommandNode<S>, CommandSyntaxException>
CommandContextBuilder: getNodes(): List<ParsedCommandNode<S>>; getChild(); getLastChild(); build(String): CommandContext<S>
public static <S> Optional<ContextChain<S>> context.ContextChain.tryFlatten(CommandContext<S>)
CommandSyntaxException: getMessage(); getRawMessage(): Message; getInput(); getCursor(); getContext()
CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(ImmutableStringReader)
public String LiteralCommandNode.getLiteral()
// net.minecraft.commands
public interface CommandSource { void sendSystemMessage(Component); boolean acceptsSuccess(); boolean acceptsFailure(); boolean shouldInformAdmins(); }
CommandSourceStack(CommandSource, Vec3, Vec2, ServerLevel, PermissionSet, Component, MinecraftServer)
CommandSourceStack(CommandSource, Vec3, Vec2, ServerLevel, PermissionSet, MinecraftServer, Entity /*non-null*/)
withSource(CommandSource), withEntity(Entity), withPosition(Vec3), withLevel(ServerLevel), withPermission(PermissionSet), withCallback(CommandResultCallback), withSuppressedOutput()
public interface CommandResultCallback { void onResult(boolean success, int result); }
server.permissions.LevelBasedPermissionSet.{ALL, MODERATOR, GAMEMASTER, ADMIN, OWNER}
public static ComponentUtils.fromMessage(com.mojang.brigadier.Message): Component
```

**Ce que fait vraiment le moteur de commandes**, lu dans le bytecode :
- `Commands.getParseException(parse)` renvoie `null` si le lecteur a tout consommé. Sinon, il renvoie l'unique exception de nœud, ou « commande inconnue » si rien n'a été reconnu, ou « argument inconnu ».
- `performCommand` appelle `finishParsing`. Celui-ci revalide, puis construit la chaîne `ContextChain.tryFlatten(context.build(cmd))`. **Une commande incomplète** (`/give Steve`) passe `getParseException` mais échoue ici, avec `dispatcherUnknownCommand`. Il faut donc reproduire les deux tests.
- Toutes les erreurs, de syntaxe comme d'exécution, sont envoyées par `source.sendFailure(...)`. Rien n'est levé vers l'appelant, d'où l'importance de la capture.
- `CommandSourceStack.sendSuccess(supplier, true)` n'appelle `broadcastToAdmins` que si `source.shouldInformAdmins()` est vrai **et** que la source n'est pas silencieuse. `broadcastToAdmins` envoie alors le message gris « [Narrator: …] » à tous les opérateurs, si la règle `SEND_COMMAND_FEEDBACK` est active. Une `CommandSource` qui répond `false` **supprime entièrement ce spam**.
- `withSuppressedOutput()` met `silent = true`, ce qui coupe **aussi** notre capture. Il ne faut pas l'utiliser.
- Les nœuds dont la condition (`requires`) échoue pour la source sont invisibles à l'analyse. Avec `LevelBasedPermissionSet.GAMEMASTER`, les commandes des niveaux 3 et 4 (`op`, `deop`, `ban*`, `kick`, `whitelist`, `stop`, `save-*`, `publish`, `transfer`, `debug`, `perf`), vérifiées dans leur bytecode, apparaissent comme « commande inconnue ». C'est un premier garde-fou gratuit.

**Source capturante**

```kotlin
/** Captures every line a command sends back; never informs operators. */
class CapturingSource : CommandSource {
    val lines = mutableListOf<String>()
    override fun sendSystemMessage(message: Component) { lines += message.string }
    override fun acceptsSuccess() = true
    override fun acceptsFailure() = true
    override fun shouldInformAdmins() = false
}

fun narratorSource(server: MinecraftServer, capture: CapturingSource, anchor: ServerPlayer?, callback: CommandResultCallback): CommandSourceStack {
    val level = anchor?.level() ?: server.overworld()
    val base = CommandSourceStack(capture, anchor?.position() ?: Vec3.atLowerCornerOf(server.respawnData.pos()), Vec2.ZERO,
        level, LevelBasedPermissionSet.GAMEMASTER, Component.literal("Narrator"), server)
    return base.withCallback(callback)   // "~ ~ ~" resolve around the anchor player; no entity => "@s" fails, by design
}
```

**Analyser sans exécuter**

```kotlin
data class CommandCheck(val ok: Boolean, val error: String? = null, val cursor: Int = -1, val usage: List<String> = emptyList())

fun check(server: MinecraftServer, source: CommandSourceStack, raw: String): CommandCheck {
    val dispatcher = server.commands.dispatcher
    val cmd = Commands.trimOptionalPrefix(raw)
    val parse = dispatcher.parse(cmd, source)
    val error = Commands.getParseException(parse)
        ?: if (ContextChain.tryFlatten(parse.context.build(cmd)).isEmpty)
               CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(parse.reader) else null
    if (error == null) return CommandCheck(ok = true)
    // Usage from the deepest node reached, like /help does.
    val deepest = parse.context.lastChild.nodes.lastOrNull()?.node
    val prefix = cmd.substring(0, parse.context.lastChild.range.end).trim()
    val usage = if (deepest == null) emptyList()
                else dispatcher.getSmartUsage(deepest, source).values.map { "/$prefix $it" }.take(20)
    return CommandCheck(false, ComponentUtils.fromMessage(error.rawMessage).string, error.cursor, usage)
}
```

`range.end` correspond à `public int StringRange.getEnd()` (Brigadier), vérifié par javap.

- `getCursor()` et `getInput()` donnent la position exacte : on peut renvoyer au modèle `input.substring(0, cursor) + "<--[HERE]"`, ce que fait le jeu.
- **`command_help`** : `dispatcher.getSmartUsage(dispatcher.root.getChild("give")!!, source)` donne l'arbre compact, comme `/help give`. `getAllUsage(node, source, true)` donne toutes les variantes, mais peut produire des milliers de lignes pour `execute` : il faut plafonner.

**Liste noire robuste**, qui traverse `execute … run …` :

```kotlin
/** First literal of every chained segment: "execute as @a run fill ..." -> ["execute", "fill"]. */
fun segmentHeads(parse: ParseResults<CommandSourceStack>): List<String> {
    val heads = ArrayList<String>()
    var ctx: CommandContextBuilder<CommandSourceStack>? = parse.context
    while (ctx != null) {
        (ctx.nodes.firstOrNull()?.node as? LiteralCommandNode<*>)?.let { heads += it.literal }
        ctx = ctx.child
    }
    return heads
}
```

Le premier littéral de chaque segment est un nom de commande, puisque `run` redirige vers la racine. On refuse donc si l'un d'eux appartient à la liste noire. En niveau 2, restent **à interdire explicitement** : `fill`, `setblock`, `clone`, `kill`, `gamerule`, `difficulty`, `reload`, `forceload`, `worldborder`, `function`, `data`, `item`, `place`, `spreadplayers`… La liste définitive est à arrêter en M2, avec les garde-fous du §6.3.

**Exécuter en capturant**

```kotlin
fun run(server: MinecraftServer, anchor: ServerPlayer?, raw: String): Pair<Boolean, List<String>> {
    val capture = CapturingSource()
    var success = false
    val source = narratorSource(server, capture, anchor) { ok, _ -> success = ok }
    val cmd = Commands.trimOptionalPrefix(raw)
    val parse = server.commands.dispatcher.parse(cmd, source)   // parse with the SAME source you execute with
    // ... check(...) and segmentHeads(...) first ...
    server.commands.performCommand(parse, cmd)
    return success to capture.lines
}
```

**Pièges**
- `performCommand` passe par `Commands.executeCommandInContext`. S'il n'y a pas de contexte d'exécution courant (`CURRENT_EXECUTION_CONTEXT`, un `ThreadLocal`), il en crée un et exécute **immédiatement**. Mais s'il est appelé **depuis** une commande en cours, par exemple depuis le gestionnaire de `/narrator`, la commande est **mise en file** : le rappel n'aura pas encore été appelé au retour. Les outils du Narrateur ne tournent pas dans une commande : il faut préserver ce point.
- Les règles `MAX_COMMAND_SEQUENCE_LENGTH` et `MAX_COMMAND_FORKS` limitent `execute`.
- Il faut analyser avec la source qui exécutera : permissions, position et entité changent le résultat de l'analyse.
- Les sélecteurs (`@a`, `@e[...]`) ne sont évalués qu'à l'exécution : « aucune entité trouvée » n'est pas une erreur d'analyse.

---

## 5. Garde-fous

### 5.1 Modifier temporairement des blocs, puis les restaurer

```
public boolean Level.setBlock(BlockPos, BlockState, int flags)   // LevelWriter.setBlock(pos, state, flags)
Block.UPDATE_NEIGHBORS = 1, UPDATE_CLIENTS = 2, UPDATE_INVISIBLE = 4, UPDATE_IMMEDIATE = 8, UPDATE_KNOWN_SHAPE = 16,
UPDATE_SUPPRESS_DROPS = 32, UPDATE_MOVE_BY_PISTON = 64, UPDATE_SKIP_SHAPE_UPDATE_ON_WIRE = 128,
UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS = 256, UPDATE_SKIP_ON_PLACE = 512, UPDATE_ALL = 3, UPDATE_SKIP_ALL_SIDEEFFECTS = 816
public final CompoundTag BlockEntity.saveWithFullMetadata(HolderLookup$Provider)
public final void BlockEntity.loadWithComponents(ValueInput)
public static BlockEntity BlockEntity.loadStatic(BlockPos, BlockState, CompoundTag, HolderLookup$Provider)  → nullable
public static ValueInput world.level.storage.TagValueInput.create(ProblemReporter, HolderLookup$Provider, CompoundTag)
util.ProblemReporter.DISCARDING
public static CompoundTag nbt.NbtUtils.writeBlockState(BlockState);  public static BlockState readBlockState(HolderGetter<Block>, CompoundTag)
public static CompoundTag nbt.TagParser.parseCompoundFully(String)   // CompoundTag.toString() gives SNBT
```

Le bytecode de `LevelChunk.setBlockState` montre que, sans `UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS` (256), `BlockEntity.preRemoveSideEffects` s'exécute. Concrètement, **un coffre remplacé répand son contenu par terre**, et la restauration le dupliquerait. `UPDATE_SKIP_ON_PLACE` (512) évite que la pose déclenche des effets (TNT, portails…).

```kotlin
private const val TEMP_FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_SKIP_ALL_SIDEEFFECTS   // 2 | 816: no drops, no neighbour shape updates, no BE side effects, no onPlace

data class BlockSnapshot(val pos: BlockPos, val stateSnbt: String, val blockEntitySnbt: String?)

fun snapshot(level: ServerLevel, pos: BlockPos): BlockSnapshot? {
    val state = level.stateIfLoaded(pos) ?: return null            // §2.2: never load a chunk to snapshot it
    val be = level.getBlockEntity(pos)?.saveWithFullMetadata(level.registryAccess())
    return BlockSnapshot(pos.immutable(), NbtUtils.writeBlockState(state).toString(), be?.toString())
}

fun restore(level: ServerLevel, snap: BlockSnapshot): Boolean {
    if (!level.hasChunkAt(snap.pos)) return false                  // retry on ServerChunkEvents.CHUNK_LOAD
    val state = NbtUtils.readBlockState(level.registryAccess().lookupOrThrow(Registries.BLOCK), TagParser.parseCompoundFully(snap.stateSnbt))
    level.setBlock(snap.pos, state, TEMP_FLAGS)
    snap.blockEntitySnbt?.let { snbt ->
        level.getBlockEntity(snap.pos)?.let { be ->
            be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), TagParser.parseCompoundFully(snbt)))
            be.setChanged()
        }
    }
    return true
}
```

**Pièges**
- On persiste les instantanés **avant** de modifier, en DuckDB ou `SavedData`, pour qu'un crash n'empêche jamais la restauration.
- On les rejoue au démarrage et au chargement du chunk (`ServerChunkEvents.CHUNK_LOAD`, via `onChunkLoad(ServerLevel, LevelChunk, boolean)`).
- On capture **toute** la zone touchée, y compris les blocs attachés (torches, rails, portes en deux parties). Avec `UPDATE_KNOWN_SHAPE`, compris dans les 816, les voisins ne sont pas recalculés : ils ne tombent pas, mais peuvent rester visuellement incohérents jusqu'à la restauration [À VÉRIFIER].
- Ne jamais modifier un bloc d'un chunk non chargé.
- La lumière est recalculée par `setBlock` (moteur de lumière) [À VÉRIFIER : avec les drapeaux 818].

### 5.2 Plafonner les explosions

- L'outil borne `radius` (par exemple 4 au chaos 3) et impose `Level.ExplosionInteraction.NONE` et `fire = false`. Aucun bloc n'est touché (`KEEP`), pas même les entités « blocs » comme les tableaux ou les cadres.
- **Pas d'explosion destructrice.** Avec `DESTROY`, les blocs cassés **lâchent leurs objets** avant toute restauration, ce qui duplique les objets.
- Les entités restent affectées. Une explosion `KEEP` détruit peut-être les `ItemEntity` au sol [À VÉRIFIER]. Les cristaux de l'End explosent en retour, en `DESTROY` : on exclut les zones de l'End, ou on filtre.
- `SimpleExplosionDamageCalculator(false, true, Optional.of(0.5f), Optional.empty())` permet de réduire le recul, avec la surcharge `explode(Entity, DamageSource, ExplosionDamageCalculator, …)`.

### 5.3 Mort causée par le Narrateur : garder le joueur et son inventaire

**Le chemin vanilla**, lu dans le bytecode :
1. `LivingEntity.hurtServer` reçoit des dégâts mortels. Fabric intercepte `isDeadOrDying()` pour `ServerLivingEntityEvents.ALLOW_DEATH`, **avant** le totem.
2. `ServerPlayer.die` appelle `dropAllDeathLoot`, puis `Player.dropEquipment(ServerLevel)`. Celui-ci teste `GameRules.get(KEEP_INVENTORY)` ; si la règle est fausse, il appelle `destroyVanishingCursedItems()` puis `inventory.dropAll()`.
3. L'XP lâchée vient de `Player.getBaseExperienceReward(ServerLevel)`, qui teste lui aussi `KEEP_INVENTORY` et renvoie 0 si la règle est vraie.
4. À la réapparition, `PlayerList.respawn` crée un nouveau `ServerPlayer`, puis appelle `restoreFrom(old, false)`. Ce dernier teste `KEEP_INVENTORY` et, si la règle est vraie, appelle `transferInventoryXpAndScore(old)`.

**Existe-t-il un point propre dans Fabric API ? Non.** `fabric-entity-events-v1` 6.0.4 n'offre que `ALLOW_DAMAGE`, `AFTER_DAMAGE`, `ALLOW_DEATH`, `AFTER_DEATH`, `COPY_FROM`, `AFTER_RESPAWN`, `JOIN` et `LEAVE` : aucun événement ne permet d'annuler le lâcher d'inventaire à la mort. Et basculer la règle globale `keepInventory` toucherait tous les joueurs.

**Mixin proposé : un `keepInventory` par joueur**, avec MixinExtras 0.5.5, embarqué dans Fabric Loader 0.19.5 (`META-INF/jars/mixinextras-fabric-0.5.5.jar`). Il intercepte le seul appel à `GameRules.get(GameRule)` présent dans chacune des trois méthodes, ce qui a été vérifié.

```java
// src/main/java/io/github/clemsix6/narrator2/mixin/PlayerKeepInventoryMixin.java
package io.github.clemsix6.narrator2.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.clemsix6.narrator2.guard.DeathGuard;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Treats keepInventory as true for players protected by the Narrator's death guard (drops and XP). */
@Mixin(Player.class)
abstract class PlayerKeepInventoryMixin {
    @WrapOperation(
        method = {"dropEquipment", "getBaseExperienceReward"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object narrator2$keepInventory(GameRules rules, GameRule<?> rule, Operation<Object> original) {
        if (rule == GameRules.KEEP_INVENTORY && DeathGuard.keepsInventory(((Player) (Object) this).getUUID())) {
            return Boolean.TRUE;
        }
        return original.call(rules, rule);
    }
}
```

```java
// src/main/java/io/github/clemsix6/narrator2/mixin/ServerPlayerKeepInventoryMixin.java
@Mixin(ServerPlayer.class)
abstract class ServerPlayerKeepInventoryMixin {
    /** restoreFrom(old, false) copies inventory, XP and score when keepInventory is true. */
    @WrapOperation(
        method = "restoreFrom",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/gamerules/GameRules;get(Lnet/minecraft/world/level/gamerules/GameRule;)Ljava/lang/Object;"))
    private Object narrator2$keepInventory(GameRules rules, GameRule<?> rule, Operation<Object> original) {
        if (rule == GameRules.KEEP_INVENTORY && DeathGuard.keepsInventory(((ServerPlayer) (Object) this).getUUID())) {
            return Boolean.TRUE;
        }
        return original.call(rules, rule);
    }
}
```

Mise en place :
- Dans `narrator2.mixins.json`, déclarer `"mixins": ["PlayerKeepInventoryMixin", "ServerPlayerKeepInventoryMixin"]`. `defaultRequire: 1` fait échouer le démarrage si la cible disparaît : c'est voulu.
- `DeathGuard` est un `object` Kotlin avec `@JvmStatic fun keepsInventory(uuid: UUID): Boolean`.
- Dans `restoreFrom`, `this` est le **nouveau** joueur : on identifie donc par UUID, qui est conservé.

**Cycle de vie de `DeathGuard`**
1. Il est **armé** (UUID et échéance) juste avant une action potentiellement mortelle du Narrateur : kill direct, explosion, éclair non visuel, mobs invoqués sur la cible. La fenêtre couvre la durée du châtiment.
2. `ServerLivingEntityEvents.AFTER_DEATH` : si le joueur est armé, on le marque « mort protégée ». On journalise la cause et on décompte le quota « un kill direct par heure » du §17.
3. `ServerPlayerEvents.AFTER_RESPAWN(old, new, alive)` : on **désarme**, puis on réapplique modificateurs, barres de boss, etc. (§4.2, §4.5).
4. **Persister la marque « mort protégée » jusqu'à la réapparition.** L'inventaire n'a pas été lâché : il est sauvegardé avec le joueur mort. Si le serveur redémarre avant la réapparition et que la marque a été perdue, `restoreFrom` ne copie pas l'inventaire, **et il est perdu**.

Effets de bord, identiques à ceux de `keepInventory` en vanilla :
- les objets à malédiction de disparition sont **conservés** ;
- l'XP est conservée et aucune orbe n'est lâchée.

Le message de mort part normalement, sauf si la règle `SHOW_DEATH_MESSAGES` est désactivée.

**Alternative sans mixin**, moins sûre :
- dans `ALLOW_DEATH` (joueur armé), on sérialise et **persiste** inventaire et XP (`ItemStack.CODEC`), on vide l'inventaire et on met `experienceLevel` et `experienceProgress` à 0, puis on autorise la mort ;
- dans `COPY_FROM` ou `AFTER_RESPAWN`, on restitue.

Ses défauts : une fenêtre de crash, des interactions avec les autres mods, et un ordre des écouteurs non garanti.

**« Garder le joueur » au sens de ne pas le laisser mourir** : `ALLOW_DEATH` renvoie `false`. L'écouteur doit alors remettre de la vie (`player.health = 1f`), faute de quoi le joueur resterait à 0 [À VÉRIFIER : comportement exact de Fabric].

**Tuer « proprement »** : `player.hurtServer(level, level.damageSources().magic(), Float.MAX_VALUE)` ou `player.kill(level)`. D'après le bytecode, `LivingEntity.kill` passe par `hurtServer(genericKill)`. Les `DamageSources` disponibles incluent `magic()`, `genericKill()`, `lightningBolt()`, `fellOutOfWorld()`… Un message de mort personnalisé exigerait un `DamageType` enregistré en données : on s'en passe, et le Narrateur commente lui-même la mort.

### 5.4 Reprise après mort, reconnexion et redémarrage

| Événement (Fabric API, signatures vérifiées) | À faire |
|---|---|
| `ServerPlayerEvents.JOIN`, `join(ServerPlayer)` | Réappliquer les modificateurs non expirés, réajouter aux barres de boss, nettoyer les modificateurs `narrator2:*` orphelins |
| `ServerPlayerEvents.AFTER_RESPAWN`, `afterRespawn(ServerPlayer old, ServerPlayer new, boolean alive)` | Nouvel objet joueur : tout réappliquer. `alive = true` à la sortie de l'End |
| `ServerPlayerEvents.COPY_FROM`, `copyFromPlayer(ServerPlayer old, ServerPlayer new, boolean alive)` | Copier un état attaché au joueur, le cas échéant |
| `ServerLivingEntityEvents.ALLOW_DEATH`, `allowDeath(LivingEntity, DamageSource, float)`, et `AFTER_DEATH` | `DeathGuard` (§5.3), journal |
| `ServerChunkEvents.CHUNK_LOAD`, `onChunkLoad(ServerLevel, LevelChunk, boolean)` | Rejouer les restaurations de blocs en attente |
| `ServerTickEvents.END_SERVER_TICK` | Expiration des modificateurs, avancement des barres |

---

## 6. Mixins nécessaires

| Besoin | Mixin ? | Détail |
|---|---|---|
| Garder l'inventaire et l'XP lors d'une mort causée par le Narrateur | **Oui**, deux petits `@WrapOperation` | §5.3. Aucune API Fabric ne couvre ce besoin. |
| Tout le reste de l'Oracle et des Actions M2 | Non | Tout passe par des API publiques : champs publics (`syncVelocity`, `connection`, `containerMenu`), paquets, registres et événements Fabric. |
| *(éventuel, M1 ou M4)* Savoir qui a posé un bloc | Hors périmètre | Relève de l'Observateur. |

`Entity.markHurt()` est protégé, mais inutile : le champ `syncVelocity` est public.

---

## 7. Points [À VÉRIFIER]

1. Les seuils de l'heuristique de grotte (§3.2) : à valider par un GameTest sur des cas construits (sous un arbre, sous un toit, tunnel 1×2, caverne, ravin, Nether).
2. Les temps réels de `scanOres` (§3.5), de `nearbyStructures` et de `findNearestMapStructure` (§3.4) : micro-benchmarks GameTest, journalisation des durées.
3. `findNearestMapStructure` : position renvoyée (Y), et volume de proto-chunks créés par recherche.
4. Valeur de `getBrightness` et de `canSeeSky` hors des chunks chargés.
5. Conservation de l'overlay des barres de boss côté client après une réapparition (§4.2).
6. Seuils de l'anti-triche lors d'une propulsion (§4.7).
7. Sémantique de l'attribut `camera_distance`.
8. Dégâts et rayon d'une fusée qui éclate (§4.10). Destruction des `ItemEntity` par une explosion `KEEP` (§5.2).
9. Thread de complétion de `addTicketAndLoadWithRadius`, et comportement d'une téléportation de joueur monté (§4.6).
10. Réponse des mobs à `Brain` à `setTarget` (§4.9).
11. Comportement exact de `ALLOW_DEATH = false` : santé à remettre par l'écouteur (§5.3).
12. Rendu des blocs voisins pendant une modification temporaire avec les drapeaux 818, et recalcul de la lumière (§5.1).
13. Accès aux composantes des records Java depuis Kotlin (`chunkPos.x()`) : les extraits utilisent la forme méthode, qui est sûre.
14. Les extraits Kotlin n'ont pas été compilés (Gradle interdit pour cette mission) : les signatures sont vérifiées, mais les imports, les conversions SAM (`Predicate`, `CommandResultCallback`) et les ambiguïtés de surcharge restent à confirmer à la compilation.

---

## 8. Table récapitulative

| Besoin | API (26.3, vérifiée) | Piège principal |
|---|---|---|
| Position, dimension | `Entity.blockPosition()`, `Level.dimension().identifier()` | `ResourceKey.location()` n'existe plus |
| Biome | `LevelReader.getBiome(pos).registeredName` | Biomes 3D : sous terre, on trouve des biomes de grotte, dont `sulfur_caves` |
| Grotte | `canSeeSky`, `getBrightness(SKY)`, `getHeight(MOTION_BLOCKING_NO_LEAVES)`, volume d'air | `canSeeSky` est faux sous les feuilles, et toujours faux dans le Nether et l'End |
| Heure | `Level.getDefaultClockTime()`, `ServerClockManager` | `getDayTime` et `setDayTime` sont supprimés : le temps passe par des horloges |
| Météo | `Level.isRaining/isThundering`, `MinecraftServer.setWeatherParameters` | Commune au serveur |
| Lumière, heightmap | `getBrightness`, `getMaxLocalRawBrightness`, `Level.getHeight` | `getHeight` renvoie `minY` si le chunk est absent |
| Lecture de blocs | `ServerChunkCache.getChunkNow` → `LevelChunk.getBlockState` | `Level.getBlockState` **génère** le chunk de façon synchrone |
| Structure au joueur | `StructureManager.getStructureWithPieceAt(pos, pred)` | Renvoie `INVALID_START`, pas `null` |
| Structures proches | `ChunkAccess.getAllReferences()` sur les chunks chargés | Coordonnées approximatives si le chunk de départ n'est pas chargé |
| Locate lointain | `ChunkGenerator.findNearestMapStructure(level, set, pos, r, false)` | Entrées-sorties bloquantes et proto-chunks, sur le thread serveur uniquement |
| Minerais | `LevelChunkSection.maybeHas` puis `getBlockState(x, y, z)` ; `BlockItemTags.DIAMOND_ORES.block()` | Palette globale : `maybeHas` renvoie toujours `true` ; plafonner le rayon à 48 |
| Entités proches | `Level.getEntities(e, AABB, pred)`, `getEntitiesOfClass` | Coût proportionnel au volume ; ne pas utiliser la variante sans AABB |
| État du joueur | `health`, `foodData`, `experienceLevel`, `inventory`, `activeEffects`, `gameMode()`, `lastDeathLocation`, `respawnConfig` | `respawnConfig` est nullable ; il n'y a pas de `player.getServer()` |
| Registres | `BuiltInRegistries.X.get(Identifier)` ; `registryAccess().lookupOrThrow(Registries.ENCHANTMENT)` | `DefaultedRegistry.getValue` renvoie l'air ou le cochon, jamais `null` |
| Chat, actionbar | `sendSystemMessage`, `sendOverlayMessage` | `displayClientMessage` a été scindé |
| Title | Paquets `ClientboundSetTitlesAnimation`, `SetSubtitleText`, puis `SetTitleText` | Le sous-titre doit partir avant le titre |
| Son | `ClientboundSoundPacket(Holder.direct(...))`, `Level.playSound(null, ...)` | Un identifiant inconnu est silencieux : valider |
| Bossbar | `ServerBossEvent(UUID, …)` | En mémoire seulement ; nouvel objet joueur à la réapparition |
| Livre | `WrittenBookContent(passThrough(title ≤ 32), author, 0, pages, true)` | Il faut le livre en main pour que `openItemGui` l'ouvre |
| Effets | `MobEffectInstance(Holder, ticks, amp, …)`, `addEffect` | Pas de remplacement par un effet plus faible ; perdus à la mort |
| Attributs | `AttributeModifier(Identifier, amount, op)`, `addOrUpdateTransientModifier` | Transient perdu à la déconnexion, **tous** perdus à la mort : réappliquer depuis notre registre |
| Téléportation | `ServerPlayer.teleportTo(level, x, y, z, Set<Relative>, yRot, xRot, resetCamera)` | Charger d'abord la cible en asynchrone (ticket `PORTAL`) ; plafond de bedrock dans le Nether |
| Propulsion | `setDeltaMovement`, puis `syncVelocity = true` | `hurtMarked` s'appelle désormais `syncVelocity` ; `needsSync` n'atteint pas le joueur lui-même |
| Donner un item | `DataComponents.CUSTOM_NAME/LORE/ENCHANTMENTS/UNBREAKABLE`, `Inventory.add`, `createItemStackToDrop` | `UNBREAKABLE` est un `Unit` ; nom en italique par défaut ; `add` modifie le stack |
| Prendre un item | `Inventory.removeItem(slot, n)`, `ItemStack.CODEC` | Persister avant de retirer ; appeler `broadcastChanges()` |
| Enchantement | `registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.X)` | Registre dynamique, pas `BuiltInRegistries` |
| Invocation | `EntityTypes.X.create(level, reason)` → `snapTo` → `finalizeSpawn` → `tryAddFreshEntityWithPassengers` | Les constantes sont dans `EntityTypes` ; `create` est nullable |
| Explosion | `Level.explode(null, x, y, z, r, ExplosionInteraction.NONE)` | `DESTROY` lâche des objets, donc duplication à la restauration |
| Éclair | `EntityTypes.LIGHTNING_BOLT.create` et `setVisualOnly(true)` | Sinon : feu et conversions |
| Feux d'artifice | `FireworkRocketEntity(level, x, y, z, stack avec FIREWORKS)` | `Level.createFireworks` est vide côté serveur ; l'éclatement blesse |
| Particules | `ServerLevel.sendParticles(...)` | Portée de 32 blocs, sauf `overrideLimiter` |
| Analyse de commande | `dispatcher.parse`, `Commands.getParseException`, `ContextChain.tryFlatten` | Une commande incomplète passe `getParseException` |
| Usage | `getSmartUsage(node, source)`, `getAllUsage(node, source, true)` | `getAllUsage(execute)` produit des milliers de lignes |
| Exécution capturée | `CommandSource` maison (`shouldInformAdmins = false`), `withCallback`, `performCommand` | `withSuppressedOutput` coupe la capture ; exécution différée si l'appel vient d'une commande |
| Restauration de blocs | `saveWithFullMetadata`, `setBlock(..., UPDATE_CLIENTS or UPDATE_SKIP_ALL_SIDEEFFECTS)`, `loadWithComponents` | Sans le drapeau 256, un coffre remplacé répand son contenu |
| Mort protégée | Mixins `@WrapOperation` sur `GameRules.get(KEEP_INVENTORY)` | Aucun événement Fabric ; persister la marque jusqu'à la réapparition |
