# L'Observateur — référence API vérifiée (Minecraft 26.3, Fabric API 0.161.0+26.3)

Référence pour le jalon **M1** (`docs/ARCHITECTURE.md` §4 et §16) : comment capter chaque interaction
des joueurs côté serveur, avec des signatures vérifiées. Elle complète `docs/MEMOIRE.md` §3.4 (stats de
la fiche joueur). Rédigée le 25/09/2026.

**Rien ici n'est écrit de mémoire.** Les signatures viennent de `javap` sur le même classpath que
`.research/jp.ps1` : `minecraft-merged.jar` 26.3, tous les modules de Fabric API 0.161.0+26.3 et Fabric
Loader 0.19.5. Pour savoir *où* un événement Fabric se déclenche, les annotations `@Inject` des mixins
d'implémentation de Fabric ont été lues avec `javap -v`. L'ordre des appels dans le code vanilla a été
vérifié avec `javap -c`, et les appelants d'une méthode ont été retrouvés en cherchant dans le pool de
constantes de toutes les classes du jar. Les exceptions sont signalées : Codec DFU 10.0.21 et fastutil
8.5.18, vérifiés sur leurs propres jars.
Les extraits de code n'ont **pas** été compilés. Ils montrent les bons noms et les bons types, mais leur
syntaxe Kotlin reste **[À VÉRIFIER]** à la première compilation.

Notation : les méthodes sont écrites `Classe.méthode(params)` et les descripteurs JVM sont donnés pour
toutes les cibles de mixin. `@Nullable` désigne l'annotation JSpecify réellement présente dans le
bytecode : le jar 26.3 et Fabric API sont annotés, donc Kotlin voit ces types comme nullables.

---

## 1. Règles transverses (à lire avant tout)

### 1.1 Thread serveur, file vers le Journal
- Tous les points d'entrée listés ici s'exécutent **sur le thread serveur**. C'est vérifié pour le chat :
  `ServerGamePacketListenerImpl.tryHandleChat` passe par `MinecraftServer.execute`, et la `FutureChain`
  du chat est construite avec le serveur comme `Executor` (`thenAcceptAsync(consumer, executor)`).
- Un hook construit un **enregistrement immuable** (types simples : chaînes, nombres, UUID) et le pousse
  dans une file non bloquante (`offer`, jamais `put`) vidée par l'écrivain DuckDB toutes les 1 à 2 s
  (§4.2 de l'architecture). Si la file est pleine, on perd l'événement, on incrémente un compteur et on
  logue une fois. **Aucune attente, aucun accès à la base sur le thread serveur.**
- On ne garde **jamais** de référence à un `ItemStack`, une `Entity`, un `ServerPlayer` ou un
  `BlockState` au-delà du hook. Les piles sont modifiées juste après : `BlockItem.place` appelle
  `ItemStack.consume` après le déclencheur, et `Consumable.onConsume` fait de même. De plus, le
  `ServerPlayer` est **remplacé** à chaque réapparition : `PlayerList.respawn` construit un nouveau
  `ServerPlayer`. Tout est donc indexé par UUID.

### 1.2 Exceptions
Une exception qui sort d'un hook casse l'action vanilla : injectée en tête de `PlayerAdvancements.award`,
elle empêche le progrès, et levée dans un listener de `CHAT_MESSAGE`, elle bloque la diffusion du message.
Chaque point d'entrée **capture ses propres exceptions** et les logue avec `Log.of("Observer")`.

### 1.3 Joueurs factices, joueurs de test, client
- `net.fabricmc.fabric.api.entity.FakePlayer extends ServerPlayer` sert aux mods qui cassent ou posent
  des blocs. Les événements Fabric se déclenchent pour eux : on filtre avec `player is FakePlayer`.
  `FakePlayer.awardStat(Stat, int)` est redéfinie à vide, donc un mixin sur `ServerPlayer.awardStat`
  ne voit **jamais** les joueurs factices.
- Les joueurs de GameTest ne sont **pas** filtrés, pour que les tests en jeu puissent exercer
  l'Observateur. Aides disponibles dans `GameTestHelper` :
  `makeMockServerPlayerInLevel(): ServerPlayer`, `placeAt(Player, ItemStack, BlockPos, Direction)`,
  `useBlock(BlockPos, Player)` et `useItemOnBlock(Player, Item, BlockPos)`.
- Le jar est *merged* (client et serveur). Un mixin sur une classe commune (`ItemStack`, `Projectile`,
  `AnvilMenu`…) s'appliquerait aussi au client intégré en solo. On garde donc toujours
  `player is ServerPlayer` ou `!level().isClientSide()`, et on ne référence jamais `net.minecraft.client.*`.

### 1.4 Identifiants (noms 26.3)
| Besoin | Appel vérifié |
|---|---|
| Bloc | `BuiltInRegistries.BLOCK.getKey(block): Identifier` → `"minecraft:stone"`, ou `state.typeHolder().getRegisteredName()` |
| Objet d'une pile | `stack.typeHolder().getRegisteredName(): String` |
| Type d'entité | `EntityType.getKey(entity.getType()): Identifier` (statique) |
| Type de dégâts | `source.typeHolder().getRegisteredName()` (`typeHolder(): Holder<DamageType>`) |
| Dimension | `level.dimension().identifier()` (`ResourceKey.identifier()`, anciennement `location()`) |
| Biome | `level.getBiome(pos).getRegisteredName()` (`Holder<Biome>`) |
| Menu | `BuiltInRegistries.MENU.getKey(menu.getType())` (**jamais** sur `InventoryMenu`, cf. §3.6) |
| Stat | `stat.getName()` (hérité d'`ObjectiveCriteria`) → `"minecraft.mined:minecraft.stone"` |

### 1.5 Arrêt du serveur
`ServerLifecycleEvents.SERVER_STOPPING` se déclenche **en tête** de `MinecraftServer.stopServer`. Le
serveur appelle ensuite `PlayerList.saveAll()` puis `PlayerList.removeAll()`, qui déconnecte chaque
joueur (`ServerGamePacketListenerImpl.disconnect` → `onDisconnect` → `removePlayerFromWorld` →
`PlayerList.remove`), ce qui déclenche `ServerPlayerEvents.LEAVE`. `SERVER_STOPPED` se déclenche en
**queue** de `stopServer`. **Le Journal doit donc être vidé et fermé sur `SERVER_STOPPED`**, sinon les
fins de session sont perdues. Aujourd'hui, `Narrator2.kt` appelle `NarratorRuntime.stop()` sur
`SERVER_STOPPING` : le Journal ne doit pas suivre ce modèle.

### 1.6 Doublons entre sources
Plusieurs sources voient la même action. Règle : **une seule source par type d'événement du Journal**.

| Action | Source retenue | Sources ignorées |
|---|---|---|
| Bloc cassé | `PlayerBlockBreakEvents.AFTER` | stat `BLOCK_MINED`, stat `ITEM_USED` de l'outil |
| Bloc posé | déclencheur `PLACED_BLOCK` | stat `ITEM_USED` du bloc |
| Artisanat, cuisson, forge, tailleur | `ItemStack.onCraftedBy` | stat `ITEM_CRAFTED` |
| Échange | `AbstractVillager.notifyTrade` | stat `TRADED_WITH_VILLAGER`, `onCraftedBy` dans un `MerchantMenu` |
| Kill et mort | `ServerLivingEntityEvents.AFTER_DEATH` | `AFTER_KILLED_OTHER_ENTITY`, stats `MOB_KILLS`, `PLAYER_KILLS`, `ENTITY_KILLED`, `ENTITY_KILLED_BY`, `DEATHS` |
| Dégâts | `ServerLivingEntityEvents.AFTER_DAMAGE` | stats `DAMAGE_*` |
| Pêche, élevage, enchantement | déclencheurs dédiés | stats `FISH_CAUGHT`, `ANIMALS_BRED`, `ENCHANT_ITEM` |
| Chat | `CHAT_MESSAGE` filtré sur `ChatType.CHAT` | `/say` et `/me`, qui passent par le hook de commandes |

Les stats vanilla restent la source des **totaux** (§5).

### 1.7 Squelette commun
Les extraits des sections suivantes s'appuient sur ce squelette. Les noms sont indicatifs, à créer en M1
sous `io.github.clemsix6.narrator2.observer`. Les mixins Java appellent un objet Kotlin, `ObserverHooks`,
à travers des méthodes `@JvmStatic`.

```kotlin
/** One journal row, built on the server thread from plain values only (never game objects). */
data class JournalEvent(
    val type: String,               // "block_break", "block_place", "death", ...
    val playerUuid: UUID?,
    val playerName: String?,
    val dim: String,
    val x: Int, val y: Int, val z: Int,
    val subject: String? = null,    // block, item, entity, advancement, stat...
    val amount: Double? = null,     // count, damage, XP...
    val other: String? = null,      // tool, weapon, killer, victim...
    val detail: Map<String, Any?> = emptyMap(),
    val gameTime: Long,
    val ts: Long = System.currentTimeMillis(),
)

fun ServerPlayer.event(
    type: String,
    pos: BlockPos = blockPosition(),
    subject: String? = null,
    amount: Double? = null,
    other: String? = null,
    detail: Map<String, Any?> = emptyMap(),
) = JournalEvent(
    type, uuid, plainTextName, level().dimension().identifier().toString(),
    pos.x, pos.y, pos.z, subject, amount, other, detail, level().gameTime,
)

val Block.id: String get() = BuiltInRegistries.BLOCK.getKey(this).toString()
val ItemStack.id: String get() = typeHolder().registeredName
val Entity.typeId: String get() = EntityType.getKey(type).toString()

object ObserverHooks {
    private val log = Log.of("Observer")

    /** Every hook runs inside this guard: an exception must never break vanilla code. */
    private inline fun guard(hook: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { log.error("Hook $hook failed", e) }
    }

    @JvmStatic
    fun onStat(player: ServerPlayer, stat: Stat<*>, amount: Int) = guard("stat") { /* §5 */ }
    // ... one @JvmStatic entry point per mixin
}
```

`Journal.offer(event)` désigne la file non bloquante.

---

## 2. Surprises de 26.x (26.1 à 26.3) par rapport aux habitudes 1.21.x

Toutes ces surprises ont été vérifiées dans le jar. La version exacte d'introduction n'est donnée que
lorsque l'un des primers de `.research/porting/` la mentionne.

- **Fabric API — nouveaux événements joueur** : `ServerPlayerEvents.JOIN` (`onJoin(ServerPlayer)`, au
  `RETURN` de `PlayerList.placeNewPlayer`) et `ServerPlayerEvents.LEAVE` (`onLeave(ServerPlayer)`, en tête
  de `PlayerList.remove`). Ils sont plus simples que `ServerPlayConnectionEvents`. `ServerPlayerEvents.ALLOW_DEATH`
  est déprécié au profit de `ServerLivingEntityEvents.ALLOW_DEATH`. Sont aussi disponibles `BlockEvents`
  (`USE_ITEM_ON`, `USE_WITHOUT_ITEM`) et `ItemEvents` (`USE`, `USE_ON`), dans le module
  `fabric-events-interaction-v0`. Ils se déclenchent avant l'action et l'Observateur n'en a pas besoin.
- **Renommages Fabric** (guide 26.1) : `ServerWorldEvents` devient `ServerLevelEvents`,
  `ServerEntityWorldChangeEvents` devient `ServerEntityLevelChangeEvents` (`AFTER_PLAYER_CHANGE_LEVEL`,
  méthode `afterChangeLevel`), et `END_WORLD_TICK` devient `END_LEVEL_TICK`.
- **`TypedInstance<T>`** (`net.minecraft.core`) : `BlockState` et les piles l'implémentent, avec
  `typeHolder()` et `is(T)`, `is(TagKey<T>)`, `is(Holder<T>)`, `is(ResourceKey<T>)` et `is(HolderSet<T>)`.
  `state.is(Blocks.NETHER_PORTAL)` passe donc par `TypedInstance.is(T)`.
- **`ItemInstance`** (26.1) : interface commune à `ItemStack` et au nouveau `ItemStackTemplate`.
  `ItemUsedOnLocationTrigger.trigger` et `AnyBlockInteractionTrigger.trigger` prennent désormais un
  `ItemInstance`, et `DisplayInfo.icon()` renvoie un `ItemStackTemplate`.
- **`Prediction`** (26.3), enum `PREDICTED` / `SERVER_ONLY` : `LivingEntity.drop(ItemStack, boolean, Prediction)`
  remplace l'ancien booléen « aléatoire ». Le `boolean` restant signifie « conserver le lanceur » :
  il vaut `true` pour un lancer volontaire, et c'est lui qui déclenche la stat `ITEM_DROPPED`.
- **Lits** (26.3) : nouvelle classe `AbstractBedBlock`, dont héritent `BedBlock` et le nouveau
  `StrawBedBlock` (lit de paille). `ServerPlayer.startSleepInBed(AbstractBedBlock, BlockState, BedRule, BlockPos)`
  en tient compte. `BedRule` (`net.minecraft.world.attribute`) décide `canSleep` et `canSetSpawn` selon la
  dimension. La stat `SLEEP_IN_STRAW_BED` est nouvelle.
- **Point de réapparition** : `ServerPlayer.setRespawnPosition(@Nullable RespawnConfig, boolean)`, avec
  `ServerPlayer.RespawnConfig(LevelData.RespawnData respawnData, boolean forced)` et
  `LevelData.RespawnData(GlobalPos globalPos, float yaw, float pitch)`.
- **`DisplayInfo` est un record** (26.3) : `title()`, `description()`, `type()`, `announceToChat()`
  (anciennement `announceChat`) et `hidden()`.
- **`ChunkPos` est un record** : `x()`, `z()` et `pack()`. `canSeeSky(BlockPos)` vit maintenant dans
  `BlockAndLightGetter` (26.1). `Level.getDayTime()` a disparu au profit de `getOverworldClockTime()`,
  `getDefaultClockTime()`, `clockManager()`, `isBrightOutside()` et `isDarkOutside()` (horloges du monde, 26.1).
- **Paquets déplacés** : `net.minecraft.world.entity.npc.villager.{AbstractVillager, Villager, VillagerData}`,
  `…animal.equine.AbstractHorse`, `…projectile.arrow.AbstractArrow`,
  `…projectile.hurtingprojectile.AbstractHurtingProjectile`,
  `…projectile.throwableitemprojectile.ThrownEnderpearl` et `…monster.cubemob.{Slime, MagmaCube}`.
  S'y ajoute le nouveau mob `SulfurCube`, apparu dans les primers 26.2 et 26.3.
- **Projectiles** : le point d'entrée d'un impact s'appelle `Projectile.hitTargetOrDeflectSelf(HitResult)`.
- **Hiérarchie des joueurs** : `Player extends Avatar extends LivingEntity`, où `Avatar` est une nouvelle
  classe intermédiaire. `Player` implémente aussi `ContainerUser`.
- **Règles de jeu** : `GameRules.get(GameRule)`, par exemple `GameRules.SHOW_DEATH_MESSAGES` dans `ServerPlayer.die`.
- **Registre** : `ResourceKey.identifier()` remplace `location()`, et la classe s'appelle `Identifier`,
  plus `ResourceLocation`.
- **Menus** : `AbstractContainerMenu.getType()` **lève** `UnsupportedOperationException` quand le menu n'a
  pas de type. C'est le cas d'`InventoryMenu`.
- **Stats** : nouvelles stats de distance `HAPPY_GHAST_ONE_CM` et `NAUTILUS_ONE_CM`, et nouvelles stats
  de dégâts `DAMAGE_DEALT_ABSORBED` et `DAMAGE_DEALT_RESISTED`. `ServerPlayer.openNautilusInventory`
  complète `openHorseInventory`.
- **Pas de décompilateur ni de sources** dans le cache Gradle. Les sources Fabric API du scratchpad ont
  seulement servi à lire la Javadoc.

---

## 3. Interactions

Chaque fréquence est donnée **par joueur actif et par heure de jeu**. Ce sont des estimations **[À VÉRIFIER]**
à mesurer en M1 à partir des compteurs du Journal.

### 3.1 Blocs cassés

**Mécanisme** : événement Fabric.
```java
// net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents  (fabric-events-interaction-v0 5.3.6)
Event<After> AFTER;
void afterBlockBreak(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity);
// also: BEFORE (boolean beforeBlockBreak(...), cancellable), CANCELED
```
L'événement vient de `ServerPlayerGameModeMixin.onBlockBroken` (Fabric), injecté dans
`ServerPlayerGameMode.destroyBlock(BlockPos)Z` à l'appel de
`Block.destroy(LevelAccessor, BlockPos, BlockState)V`. Il n'est donc atteint que si `ServerLevel.removeBlock`
a renvoyé `true`, **avant** l'usure de l'outil (`ItemStack.mineBlock`) et **avant** les drops
(`Block.playerDestroy`). Il se déclenche aussi en créatif.

**Données** : bloc (`state` = état **avant** la casse), position, dimension, outil (`player.mainHandItem`,
encore intact), mode créatif (`isCreative`), outil adapté (`player.hasCorrectToolForDrops(state)`), et
contenu du `BlockEntity` si utile (coffre cassé).

**Pièges**
- Il n'y a qu'un appel par bloc cassé par le joueur. Les blocs retirés par mise à jour de voisinage
  (moitié haute d'une porte ou d'un lit, colonne de bambou ou de canne à sucre, sable qui tombe)
  **ne déclenchent pas** l'événement. Pour l'index des constructions (§6), on nettoie aussi l'autre moitié
  des blocs doubles.
- Il ne se déclenche ni pour les explosions (§3.18), ni pour les pistons, les fluides, le feu ou les endermen.
- La stat `BLOCK_MINED` est aussi attribuée, dans `Block.playerDestroy` et seulement en survie : on
  l'ignore dans le hook de stats pour éviter le doublon.
- On filtre les `FakePlayer`.

**Fréquence** : 300 à 3 000 par heure, c'est la source la plus bavarde avec les positions.

```kotlin
PlayerBlockBreakEvents.AFTER.register { level, player, pos, state, blockEntity ->
    val sp = player as? ServerPlayer ?: return@register
    if (sp is FakePlayer) return@register
    Journal.offer(sp.event("block_break", pos,
        subject = state.block.id,
        other = sp.mainHandItem.id,
        detail = mapOf("creative" to sp.isCreative, "correct_tool" to sp.hasCorrectToolForDrops(state))))
    PlacedBlocks.onBroken(sp.level(), pos, state)   // §6
}
```

### 3.2 Blocs posés, seaux d'eau ou de lave, feu allumé

**Mécanisme** : **mixin**. Aucun événement Fabric ne convient. `UseBlockCallback`,
`BlockEvents.USE_ITEM_ON` et `ItemEvents.USE_ON` se déclenchent **avant** l'action, sans en connaître le
résultat, et `UseBlockCallback` jusqu'à deux fois par clic (main principale, puis secondaire si la
première renvoie `PASS`). Un `RETURN` sur `BlockItem.place` donnerait une position peu fiable, car la
méthode remplace le contexte par `updatePlacementContext` (échafaudages), et il ne couvrirait ni les
seaux ni le briquet.

Cible retenue : le déclencheur d'advancement `PLACED_BLOCK`, appelé exactement après une pose réussie.
```
Cible      : net.minecraft.advancements.triggers.ItemUsedOnLocationTrigger
Méthode    : trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemInstance;)V
Injection  : @At("HEAD"), filtrer this == CriteriaTriggers.PLACED_BLOCK
```
La même classe sert aussi à `ITEM_USED_ON_BLOCK` et `ALLAY_DROP_ITEM_ON_BLOCK`, d'où le filtre sur `this`.
Les appelants de `PLACED_BLOCK` ont été vérifiés dans tout le jar : il y en a exactement trois.
- `BlockItem.place(BlockPlaceContext)` : après `placeBlock`, `updateCustomBlockEntityTag` et
  `Block.setPlacedBy`, et seulement si le bloc lu à la position est bien le bloc posé. La pile n'a pas
  encore été consommée.
- `BucketItem.use(Level, Player, InteractionHand)` : après `emptyContents(LivingEntity, Level, BlockPos, BlockHitResult)`,
  avec la position où le fluide a été versé. C'est la **pose d'eau ou de lave**. La pile passée est le
  seau plein.
- `FlintAndSteelItem.useOn(UseOnContext)` : après la pose du feu, c'est le **feu allumé**. Il n'y a
  **pas** d'appel quand le briquet allume un feu de camp ou une bougie : cette branche ne fait que
  `setBlock` et émettre `BLOCK_CHANGE`.

**Données** : `ServerPlayer`, position exacte et objet utilisé (`ItemInstance` à caster en `ItemStack`).
L'état **posé** se lit avec `player.level().getBlockState(pos)`, puisque le bloc est déjà en place au
moment du déclencheur.

**Pièges**
- **Portail du Nether** : le feu posé dans un cadre d'obsidienne crée le portail **de façon synchrone**.
  `BaseFireBlock.onPlace` appelle `PortalShape.findEmptyPortalShape` puis `createPortalBlocks`. Au moment
  du déclencheur, le bloc à `pos` est donc déjà `minecraft:nether_portal`, et c'est ainsi qu'on détecte
  « portail allumé par X ».
- Blocs doubles (porte, lit, grande fleur) : seule la position cliquée est passée. `setPlacedBy` a
  posé l'autre moitié, qu'on déduit de l'état : propriété `DoubleBlockHalf`, ou `BedPart` avec `FACING`.
- Pose par remplacement (neige en couches, bougies, cornichons de mer, dalle qui devient double) : même
  position, l'état change. On la journalise comme une pose.
- Non couverts : `FireChargeItem`, qui fait `setBlockAndUpdate` sans déclencheur, `/setblock`, les
  distributeurs (pas de joueur), les endermen, les golems de neige et les allumages de feux de camp.
  Si ces cas comptent, l'alternative générique est le §3.21.
- En créatif, la pile n'est pas consommée. Peu importe : on lit le type en tête du hook.

**Fréquence** : 100 à 2 000 par heure en construction, et de 0 à 50 par heure pour les seaux et le feu.

```java
package io.github.clemsix6.narrator2.mixin;

@Mixin(ItemUsedOnLocationTrigger.class)
abstract class ItemUsedOnLocationTriggerMixin {
    @Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemInstance;)V",
            at = @At("HEAD"))
    private void narrator2$onPlaced(ServerPlayer player, BlockPos pos, ItemInstance item, CallbackInfo ci) {
        if ((Object) this == CriteriaTriggers.PLACED_BLOCK && item instanceof ItemStack stack) {
            ObserverHooks.onBlockPlaced(player, pos.immutable(), stack);
        }
    }
}
```
```kotlin
@JvmStatic
fun onBlockPlaced(player: ServerPlayer, pos: BlockPos, stack: ItemStack) = guard("place") {
    if (player is FakePlayer) return@guard
    val placed = player.level().getBlockState(pos)
    val type = when {
        stack.item is BucketItem -> "fluid_place"                         // water, lava, powder snow is a BlockItem
        stack.item is FlintAndSteelItem -> if (placed.`is`(Blocks.NETHER_PORTAL)) "portal_lit" else "fire_lit"
        else -> "block_place"
    }
    Journal.offer(player.event(type, pos, subject = placed.block.id, other = stack.id))
    if (type == "block_place") PlacedBlocks.mark(player.level(), pos, placed)   // §6
}
```

### 3.3 Dégâts infligés et subis

**Mécanisme** : événement Fabric.
```java
// net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents  (fabric-entity-events-v1 6.0.4)
Event<AfterDamage> AFTER_DAMAGE;
void afterDamage(LivingEntity entity, DamageSource source, float baseDamageTaken, float damageTaken, boolean blocked);
Event<AllowDamage> ALLOW_DAMAGE;   // boolean allowDamage(LivingEntity, DamageSource, float), before mitigation
```
`AFTER_DAMAGE` est injecté en `TAIL` de `LivingEntity.hurtServer(ServerLevel, DamageSource, float)Z`,
seulement si `!isDeadOrDying()`. Pour un joueur, la chaîne d'appels vérifiée est `ServerPlayer.hurtServer`
→ `Player.hurtServer` → `super` (`Avatar`, qui ne redéfinit pas la méthode) → `LivingEntity.hurtServer`.

**Données**
- Victime : entité (UUID, type) et `entity.health` après le coup.
- Type de dégâts : `source.typeHolder().registeredName`, par exemple `minecraft:mob_attack`,
  `minecraft:arrow`, `minecraft:fall`, `minecraft:lava`.
- Attaquant : `source.entity`, `@Nullable`. Pour une flèche, c'est le tireur.
- Projectile : `source.directEntity`, `@Nullable`.
- Arme : `source.weaponItem`, `@Nullable`.
- Origine : `source.sourcePosition`.
- Montants et `blocked`, vrai si le bouclier a paré.

**Pièges**
- **Pas d'appel quand le coup est mortel** : la mort passe par `AFTER_DEATH` (§3.4).
- Pas d'appel quand `hurtServer` sort tôt : entité invulnérable (créatif), temps de récupération, feu
  sur une entité résistante au feu.
- `damageTaken` est calculé **avant** l'armure et les enchantements : ce n'est pas la vie perdue. Pour la
  vie réelle, on journalise `health` après le coup. La stat `DAMAGE_TAKEN` reste disponible en total :
  `Player.actuallyHurt` y ajoute `Math.round(dégâts réels × 10)`, ce qui a été vérifié.
- L'événement se déclenche pour **toutes** les entités vivantes, y compris les combats entre mobs et les
  fermes. On filtre d'abord : la victime est un `ServerPlayer`, **ou** `source.entity` est un `ServerPlayer`.
- Les dégâts continus (feu, lave, noyade, faim, poison, wither, cactus, gel) tombent une à deux fois par
  seconde. On les **fusionne** par (joueur, type de dégâts) sur une fenêtre de 5 s : une ligne avec la
  somme et le nombre de coups.

**Fréquence** : 50 à 600 par heure brutes, 30 à 200 par heure après fusion.

```kotlin
ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, base, taken, blocked ->
    val attacker = source.entity
    when {
        entity is ServerPlayer && entity !is FakePlayer ->
            DamageCoalescer.taken(entity, source.typeHolder().registeredName, attacker?.typeId, taken, blocked)
        attacker is ServerPlayer && attacker !is FakePlayer ->
            Journal.offer(attacker.event("damage_dealt", entity.blockPosition(),
                subject = entity.typeId, amount = taken.toDouble(),
                other = source.weaponItem?.id,
                detail = mapOf("type" to source.typeHolder().registeredName, "victim_health" to entity.health)))
    }
}
```

### 3.4 Kills (mobs et joueurs), morts du joueur avec la cause

**Mécanisme** : événement Fabric, avec un petit mixin optionnel pour récupérer le message de mort complet.
```java
// ServerLivingEntityEvents
Event<AfterDeath> AFTER_DEATH;   void afterDeath(LivingEntity entity, DamageSource damageSource);
Event<AllowDeath> ALLOW_DEATH;   boolean allowDeath(LivingEntity, DamageSource, float);  // before totems
// net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents
Event<AfterKilledOtherEntity> AFTER_KILLED_OTHER_ENTITY;
void afterKilledOtherEntity(ServerLevel level, Entity entity /*killer*/, LivingEntity killedEntity, DamageSource source);
```
Où `AFTER_DEATH` se déclenche :
- pour un **joueur**, via `ServerPlayerMixin.notifyDeath` (Fabric), en `TAIL` de
  `ServerPlayer.die(DamageSource)V`. `ServerPlayer.die` n'appelle **pas** `super.die`, il n'y a donc pas
  de doublon ;
- pour un **mob**, via `LivingEntityMixin.notifyDeath`, à l'appel de `Level.broadcastEntityEvent` dans
  `LivingEntity.die`, protégé par `!isRemoved() && !dead`.

`AFTER_KILLED_OTHER_ENTITY` enveloppe `Entity.killedEntity(...)` : `LivingEntity.die` pour les mobs,
`ServerPlayer.die` à l'appel de `getKillCredit()` pour les joueurs. Le tueur vaut `source.getEntity()`
et **peut être un mob**.

**Recommandation** : un seul listener `AFTER_DEATH`.
- Si la victime est un `ServerPlayer`, on écrit une ligne `death`.
- Si le tueur (`source.entity`, sinon `victim.killCredit`) est un `ServerPlayer`, on écrit une ligne `kill`.
- En PvP, on écrit les deux.

`AFTER_KILLED_OTHER_ENTITY` n'est pas utilisé. `LivingEntity.getKillCredit()`, `@Nullable`, attribue
les morts indirectes : un joueur poussé dans la lave peu après un coup.

**Message de mort** : à la fin de `ServerPlayer.die`, `CombatTracker.recheckStatus()` **vide** les
entrées du combat quand l'entité n'est plus vivante. Au moment d'`AFTER_DEATH`, `getDeathMessage()`
renvoie donc un message appauvri. Deux solutions :
- mixin en tête de `ServerPlayer.die` : `die(Lnet/minecraft/world/damagesource/DamageSource;)V`,
  `@At("HEAD")`, `getCombatTracker().getDeathMessage().getString()`. Le texte est en anglais, dans la
  langue du serveur ;
- ou `source.getLocalizedDeathMessage(player)`, sans le contexte « en fuyant X ».

La conservation des entrées en tête de `die` est **[À VÉRIFIER]** en jeu.

**Données** : cause (type de dégâts), tueur (type, UUID, nom), arme (`source.weaponItem`), position,
niveau d'XP et règle `keepInventory` (`level.getGameRules().get(GameRules.KEEP_INVENTORY)`, une
`GameRule<Boolean>`). Pour un kill : type de la victime, nom si elle en a un, animal apprivoisé ou non.

**Pièges**
- `AFTER_DEATH` couvre **toutes** les morts du monde, fermes à mobs comprises : on filtre d'abord.
- Un totem d'immortalité annule la mort : `ALLOW_DEATH` se déclenche, `AFTER_DEATH` non.
- Après la mort, l'instance de `ServerPlayer` sera remplacée (§3.15).

**Fréquence** : de 0 à 5 morts par heure, de 20 à 300 kills par heure.

```kotlin
ServerLivingEntityEvents.AFTER_DEATH.register { victim, source ->
    val killer = (source.entity ?: victim.killCredit) as? ServerPlayer
    if (victim is ServerPlayer && victim !is FakePlayer) {
        Journal.offer(victim.event("death",
            subject = source.typeHolder().registeredName,
            other = source.entity?.let { (it as? ServerPlayer)?.plainTextName ?: it.typeId },
            detail = mapOf("message" to DeathMessages.take(victim.uuid),   // captured by the die() HEAD mixin
                           "xp_level" to victim.experienceLevel)))
    }
    if (killer != null && killer !is FakePlayer && killer !== victim) {
        Journal.offer(killer.event("kill", victim.blockPosition(),
            subject = victim.typeId, other = source.weaponItem?.id,
            detail = mapOf("pvp" to (victim is ServerPlayer), "named" to victim.hasCustomName())))
    }
}
```

### 3.5 Nourriture et potions consommées (fin d'utilisation d'un objet)

**Mécanisme** : **mixin**. Il n'existe pas d'événement Fabric de fin d'utilisation. Depuis 1.21.2, la
consommation passe par le composant `minecraft:consumable`, et `Consumable.onConsume(Level, LivingEntity, ItemStack)`
est le point unique : nourriture, potions, lait, miel, ragoûts, fiole sinistre. Il est appelé depuis
`ItemStack.finishUsingItem`, lui-même appelé par `LivingEntity.completeUsingItem()` à la fin de
l'utilisation. `onConsume` attribue `ITEM_USED`, appelle **`CriteriaTriggers.CONSUME_ITEM.trigger`**,
applique ensuite les effets (`ConsumableListener`, puis `onConsumeEffects`) et consomme enfin la pile.
```
Cible      : net.minecraft.advancements.triggers.ConsumeItemTrigger
Méthode    : trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V
Injection  : @At("HEAD")   (seul appelant : Consumable.onConsume, vérifié)
```
`LivingEntity.completeUsingItem()` n'est pas retenu : la méthode est `protected`, redéfinie par
`ServerPlayer`, et reçoit aussi les usages qui ne consomment rien.

**Données** : objet, nombre d'unités **avant** consommation, aliment
(`stack.get(DataComponents.FOOD)` → `FoodProperties.nutrition()`, `saturation()`), potion
(`stack.get(DataComponents.POTION_CONTENTS)` → `potion()`, `getAllEffects()`), ainsi que la faim et la vie
du joueur **avant** effet (`foodData.foodLevel`, `health`).

**Pièges** : la pile est réduite juste après, il faut copier les valeurs. Le gâteau est un bloc et n'est
pas couvert ici : il apparaît via la stat `EAT_CAKE_SLICE` (§5). Les potions jetables ne sont pas des
consommations. Le fruit de chorus consomme puis téléporte.

**Fréquence** : 10 à 40 par heure.

```java
@Mixin(ConsumeItemTrigger.class)
abstract class ConsumeItemTriggerMixin {
    @Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void narrator2$onConsume(ServerPlayer player, ItemStack stack, CallbackInfo ci) {
        ObserverHooks.onConsumed(player, stack);
    }
}
```

### 3.6 Artisanat, four, forge, tailleur de pierre

**Mécanisme** : **mixin** sur `ItemStack.onCraftedBy`. C'est la méthode qui attribue `ITEM_CRAFTED` et
appelle `Item.onCraftedBy`.
```
Cible      : net.minecraft.world.item.ItemStack
Méthode    : onCraftedBy(Lnet/minecraft/world/entity/player/Player;I)V     (this = résultat, int = quantité)
Injection  : @At("HEAD"), garder si amount > 0 && player instanceof ServerPlayer
```
Appelants vérifiés, et pourquoi le cas de la touche Maj est couvert :
- `ResultSlot.checkTakeAchievements(ItemStack)` (établi 3×3 et grille 2×2 de l'inventaire) est appelée
  par `onTake` (clic) et par `onQuickCraft(ItemStack, int)` (Maj+clic, via
  `Slot.onQuickCraft(ItemStack, ItemStack)` dans `CraftingMenu.quickMoveStack` et
  `InventoryMenu.quickMoveStack`). Le compteur `removeCount` est remis à 0 après chaque attribution :
  pas de double comptage entre `onQuickCraft` et l'`onTake` qui suit.
- `FurnaceResultSlot.checkTakeAchievements` couvre le four, le haut fourneau et le fumoir, au clic comme
  au Maj+clic, puis `AbstractFurnaceBlockEntity.awardUsedRecipesAndPopExperience`.
- `SmithingMenu.onTake` couvre la table de forgeron.
- `StonecutterMenu$2.onTake` couvre le tailleur de pierre.
- `MerchantResultSlot.checkTakeAchievements` couvre les **échanges**. Cet appel se fait **sans** test
  `removeCount > 0` : il peut arriver avec 0. En vanilla, un échange compte donc comme « fabriqué ».
  On l'ignore ici (menu `MerchantMenu`) et on passe par §3.8.
- Non couverts : la table de cartographie (`Item.onCraftedBy` seulement, sans la pile), le fabricant
  automatique (`onCraftedBySystem`, sans joueur), l'enclume, la meule, le métier à tisser et la table
  d'enchantement (§3.7).

**Données** : objet, quantité et poste, déduit de `player.containerMenu` :

| Classe du menu | Poste |
|---|---|
| `InventoryMenu` | inventaire |
| `CraftingMenu` | établi |
| `FurnaceMenu`, `BlastFurnaceMenu`, `SmokerMenu` | four, haut fourneau, fumoir |
| `SmithingMenu` | forge |
| `StonecutterMenu` | tailleur |

On teste la classe au lieu d'appeler `getType()`, qui **lève une exception** sur `InventoryMenu`.
La recette est optionnelle : mixin sur
`RecipeCraftedTrigger.trigger(ServerPlayer, ResourceKey<Recipe<?>>, List<ItemStack>)`, appelé par
`ServerPlayer.triggerRecipeCrafted` (établi, forge, tailleur, four).

**Pièges**
- **Maj+clic** : `AbstractContainerMenu.doClick` répète `quickMoveStack` tant que le résultat reste le
  même objet, soit un appel par itération (16 fois 4 planches). On **fusionne** les appels de même
  (joueur, objet, poste) dans le même tick.
- Le grand nombre d'appels de `ItemStack.onCraftedBy` est sans conséquence : le filtre
  `player instanceof ServerPlayer` en tête élimine le client.

**Fréquence** : 20 à 150 par heure après fusion.

```java
@Mixin(ItemStack.class)
abstract class ItemStackCraftMixin {
    @Inject(method = "onCraftedBy(Lnet/minecraft/world/entity/player/Player;I)V", at = @At("HEAD"))
    private void narrator2$onCrafted(Player player, int amount, CallbackInfo ci) {
        if (amount > 0 && player instanceof ServerPlayer sp) ObserverHooks.onCrafted(sp, (ItemStack) (Object) this, amount);
    }
}
```
```kotlin
@JvmStatic
fun onCrafted(player: ServerPlayer, result: ItemStack, amount: Int) = guard("craft") {
    val station = when (player.containerMenu) {
        is InventoryMenu -> "inventory"
        is CraftingMenu -> "crafting_table"
        is BlastFurnaceMenu -> "blast_furnace"
        is SmokerMenu -> "smoker"
        is FurnaceMenu -> "furnace"
        is SmithingMenu -> "smithing_table"
        is StonecutterMenu -> "stonecutter"
        is MerchantMenu -> return@guard            // trades: see notifyTrade
        else -> "other"
    }
    CraftCoalescer.add(player, result.id, amount, station)   // flushes one row per (player, item, station, tick)
}
```

### 3.7 Enclume, meule, table d'enchantement

**Enclume**, par **mixin** :
```
Cible      : net.minecraft.world.inventory.AnvilMenu
Méthode    : onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V   (protected)
Injection  : @At("HEAD")
```
En tête, les entrées sont encore présentes : `((AnvilMenu)(Object)this).getSlot(0).getItem()` et
`getSlot(1).getItem()`. Le coût se lit avec `getCost()`, en niveaux, et la pile passée en paramètre est
le résultat. Un renommage se repère à la présence de `DataComponents.CUSTOM_NAME` sur le résultat. Le
champ `itemName` est privé à `AnvilMenu` et n'est pas nécessaire. `access`, la position du bloc, est un
champ protégé d'`ItemCombinerMenu` : un `@Shadow` depuis un mixin d'`AnvilMenu` exigerait que le mixin
hérite de la superclasse. La position du joueur suffit donc.

**Meule**, par **mixin** sur une classe anonyme **[À VÉRIFIER : fragile]**. En 26.3, le slot de sortie
est `GrindstoneMenu$4`, vérifié par ses méthodes `getExperienceAmount` et `getExperienceFromItem`.
```
Cible      : @Mixin(targets = "net.minecraft.world.inventory.GrindstoneMenu$4")
Méthode    : onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V
Injection  : @At("HEAD")  (la pile = objet désenchanté ou réparé)
```
Le numéro de la classe anonyme peut changer à la moindre mise à jour. Un test GameTest doit couvrir ce
mixin, et `injectors.defaultRequire: 1` fait échouer le démarrage s'il ne trouve plus sa cible, ce qui
est voulu. Il n'existe aucun déclencheur ni aucune stat au moment du retrait : la stat
`INTERACT_WITH_GRINDSTONE` est donnée à l'ouverture.

**Table d'enchantement**, par **mixin** sur le déclencheur :
```
Cible      : net.minecraft.advancements.triggers.EnchantedItemTrigger
Méthode    : trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;I)V
Injection  : @At("HEAD")   (seul appelant : lambda de EnchantmentMenu.clickMenuButton)
```
Il est appelé **après** `ItemStack.enchant` et la stat `ENCHANT_ITEM`. La pile est l'objet enchanté :
les enchantements sont dans `DataComponents.ENCHANTMENTS`, ou `STORED_ENCHANTMENTS` pour un livre.
L'entier est le coût en niveaux (1 à 3). `ServerPlayer.onEnchantmentPerformed(ItemStack, int)` est
appelée **avant** l'enchantement et ne convient donc pas.

**Fréquence** : moins de 10 par heure pour les trois postes.

### 3.8 Échanges avec les villageois

**Mécanisme** : **mixin**. Il n'y a pas d'événement Fabric. `TradeTrigger.trigger(ServerPlayer, AbstractVillager, ItemStack)`
ne donne pas les coûts.
```
Cible      : net.minecraft.world.entity.npc.villager.AbstractVillager
Méthode    : notifyTrade(Lnet/minecraft/world/item/trading/MerchantOffer;)V
Injection  : @At("HEAD")
```
L'appelant est `MerchantResultSlot.onTake`, après un `MerchantOffer.take(...)` réussi : un appel par
échange conclu, y compris à chaque échange d'un Maj+clic. La méthode appelle ensuite `increaseUses`,
`rewardTradeXp` et `CriteriaTriggers.TRADE`. Elle couvre aussi le **marchand ambulant**.

**Données**
- Joueur : `getTradingPlayer()`, `@Nullable Player`.
- Offre : `getCostA()`, `getCostB()` (prix ajusté), `getResult()`, `getUses()` avant incrément,
  `getMaxUses()`, `getXp()`.
- Villageois : type, UUID, `(this as? Villager)?.villagerData` → `profession()` (`Holder`), `level()`
  et `type()`.

**Pièges** : `ItemStack.onCraftedBy` se déclenche aussi pour un échange (§3.6), et on l'ignore là-bas.
La stat `TALKED_TO_VILLAGER` est attribuée à l'ouverture du menu. Les coûts sont des piles : on copie
l'objet et la quantité en tête du hook.

**Fréquence** : 0 à 100 par heure.

```java
@Mixin(AbstractVillager.class)
abstract class AbstractVillagerMixin {
    @Inject(method = "notifyTrade(Lnet/minecraft/world/item/trading/MerchantOffer;)V", at = @At("HEAD"))
    private void narrator2$onTrade(MerchantOffer offer, CallbackInfo ci) {
        AbstractVillager self = (AbstractVillager) (Object) this;
        if (self.getTradingPlayer() instanceof ServerPlayer sp) ObserverHooks.onTrade(sp, self, offer);
    }
}
```

### 3.9 Ouverture de conteneurs

**Mécanisme** : **mixin**. Aucun événement Fabric n'existe, et `UseBlockCallback` ne sait pas si un menu
s'est ouvert.
```
Cible      : net.minecraft.server.level.ServerPlayer
Méthode    : openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;     (@Nullable MenuProvider)
Injection  : @At("RETURN"), garder si cir.getReturnValue().isPresent()
```
Au retour, `containerMenu` est le nouveau menu. La méthode a déjà envoyé `ClientboundOpenScreenPacket` et
appelé `initMenu`. Elle couvre les coffres, tonneaux, boîtes de Shulker, fours, entonnoirs, distributeurs,
alambics, balises, établis, enclumes, villageois (`MerchantMenu`) et les wagonnets ou bateaux à coffre.

**Données**
- Type de menu : `BuiltInRegistries.MENU.getKey(menu.getType())`, par exemple `minecraft:generic_9x3`,
  `generic_9x6` (grand coffre), `furnace`, `crafting` ou `merchant`.
- Titre : `provider.displayName.string`.
- Position :
  - si `provider` est un `BlockEntity`, `blockPos` ;
  - si c'est une `Entity`, l'entité elle-même ;
  - sinon, la dernière cible de `UseBlockCallback` ou `UseEntityCallback` **du même tick** (voir
    l'extrait). C'est le cas du grand coffre, dont le fournisseur est une classe anonyme de `ChestBlock`,
    et des blocs qui utilisent `SimpleMenuProvider` : établi, enclume, meule, table de forgeron, tailleur
    de pierre et **coffre de l'Ender**. Les villageois passent par `Merchant`, qui utilise aussi
    `SimpleMenuProvider`. Tous ces cas ont été vérifiés.

**Bonus « coffre de structure ouvert pour la première fois »** : `LootTableTrigger.trigger(ServerPlayer, ResourceKey<LootTable>)`.
Ses appelants sont `RandomizableContainer`, `ContainerEntity` et `BrushableBlockEntity` (archéologie).

**Pièges**
- Les inventaires de cheval et de nautile passent par `openHorseInventory` et `openNautilusInventory`,
  pas par `openMenu`.
- Un spectateur peut ouvrir un coffre sans le modifier : on journalise avec l'indicateur `spectator`.
- Les golems de cuivre ouvrent des coffres par `ContainerOpenersCounter`, sans `openMenu` : ils ne sont
  pas concernés.
- Les stats `OPEN_CHEST`, `OPEN_BARREL` et consorts sont des doublons.
- `UseBlockCallback` peut être appelé deux fois par clic (main principale, puis secondaire).
- L'alternative sans heuristique est `GameEvent.CONTAINER_OPEN` (§3.21), émis avec la position exacte
  par `ContainerOpenersCounter` (coffre, tonneau, coffre de l'Ender), `ShulkerBoxBlockEntity`, les
  bateaux à coffre et les wagonnets à coffre, mais pas par les fours ni les entonnoirs.

**Fréquence** : 20 à 150 par heure.

```kotlin
/** Remembers the last right-clicked block per player, to locate menus opened by non-BlockEntity providers. */
UseBlockCallback.EVENT.register { player, level, hand, hit ->
    if (!level.isClientSide) LastUse.remember(player.uuid, hit.blockPos, level.gameTime)
    InteractionResult.PASS   // never interfere
}
```

### 3.10 Objets ramassés et jetés

**Mécanisme** : le **hook de stats** (§5), mixin sur `ServerPlayer.awardStat`, filtré sur
`Stats.ITEM_PICKED_UP` et `Stats.ITEM_DROPPED`. Aucun événement Fabric n'existe pour ces actions.

Sémantique vérifiée dans le bytecode :
- **Ramassage** : `ItemEntity.playerTouch(Player)` → `Inventory.add(stack)` → `Player.take(entity, n)` →
  `awardStat(ITEM_PICKED_UP.get(item), n)` → `onItemPickup`. `n` est la quantité **d'origine** de l'entité.
- **Lancer** : `ServerPlayer.drop(ItemStack, boolean, Prediction)` attribue `ITEM_DROPPED`, avec la
  quantité de l'`ItemEntity` créée, et `DROP` **seulement si** le booléen vaut `true`, ce qui signifie
  « conserver le lanceur ». Ce booléen vaut `true` pour la touche Q (`drop(boolean)` →
  `drop(stack, true, PREDICTED)`) et pour le clic hors de l'inventaire (`AbstractContainerMenu.doClick`).
  Il vaut `false` pour les objets qui débordent d'un inventaire plein.
- Les objets perdus à la mort ne passent **pas** par `drop` : `Inventory.dropAll` utilise
  `createItemStackToDrop` puis `addFreshEntity`. Il n'y a donc pas de stat, et ils appartiennent à
  l'événement `death`.

**Données** : objet, quantité et position du joueur. La stat ne donne pas la position de l'entité.

**Pièges**
- **Surcomptage vanilla** : `Inventory.add` renvoie `true` pour un ajout **partiel**
  (`stack.getCount() < original`), mais la stat reçoit la quantité d'origine. Un inventaire presque plein
  surcompte donc. Pour une quantité exacte, un mixin sur
  `ItemEntity.playerTouch(Lnet/minecraft/world/entity/player/Player;)V` à l'appel de
  `Player.take(Entity;I)V`, avec `@Local(ordinal = 0) int count` (MixinExtras 0.5.5), calcule
  `count − getItem().getCount()`. C'est optionnel.
- Les flèches et tridents ramassés (`AbstractArrow.playerTouch` → `tryPickup`) n'ont **pas** de stat
  `ITEM_PICKED_UP`. L'expérience non plus.
- Le volume brut est élevé : chaque bloc miné donne un ramassage. On **agrège** par (joueur, objet), et
  on vide le tampon toutes les 30 s ainsi qu'au changement de dimension ou à la déconnexion.

**Fréquence** : 500 à 3 000 ramassages bruts par heure, soit 60 à 120 lignes après agrégation. De 5 à 50
lancers par heure.

### 3.11 Progrès (advancements) obtenus

**Mécanisme** : **mixin**. `fabric-advancement-api-v1` ne propose que `AdvancementEvents.Loaded`,
`Modify` et `Replace`, qui concernent le chargement : aucun événement ne signale l'obtention.
```
Cible      : net.minecraft.server.PlayerAdvancements
Méthode    : award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z
Injection  : @At(value = "INVOKE", target = "Lnet/minecraft/advancements/AdvancementRewards;grant(Lnet/minecraft/server/level/ServerPlayer;)V")
Shadow     : @Shadow private ServerPlayer player;
```
Cette injection est atteinte **exactement une fois**, quand le progrès passe de « non fait » à « fait »
(`!wasDone && progress.isDone()`), juste avant l'attribution des récompenses et l'annonce dans le chat.

**Données** : `holder.id()` (`Identifier`) et `holder.value().display()` (`Optional<DisplayInfo>`).
`DisplayInfo` est un record : `title()`, `description()`, `type()` (TASK, GOAL, CHALLENGE),
`announceToChat()`, `hidden()` et `icon()` (`ItemStackTemplate`).

**Pièges**
- Les déblocages de recettes sont des progrès (`minecraft:recipes/...`) **sans** `display()`, très
  fréquents en début de partie : on les filtre.
- `/advancement grant` passe aussi par là : on peut le marquer grâce au hook de commandes.
- Le `PlayerAdvancementsMixin` de Fabric annule `award` en tête pour les joueurs factices, sans conflit
  avec notre injection.

**Fréquence** : 0 à 10 par heure après filtre.

```java
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
    @Shadow private ServerPlayer player;

    @Inject(method = "award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/AdvancementRewards;grant(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void narrator2$onCompleted(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (holder.value().display().isPresent()) ObserverHooks.onAdvancement(player, holder);
    }
}
```

### 3.12 Changement de dimension et portails

**Mécanisme** : événement Fabric.
```java
// net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
Event<AfterPlayerChange> AFTER_PLAYER_CHANGE_LEVEL;
void afterChangeLevel(ServerPlayer player, ServerLevel origin, ServerLevel destination);
```
Il est injecté en `TAIL` de `ServerPlayer.triggerDimensionChangeTriggers(ServerLevel)`, méthode privée
appelée par `ServerPlayer.teleport(TeleportTransition)` quand le niveau change : portails, `/tp` vers une
autre dimension. Une passerelle de l'End téléporte à l'intérieur de l'End, sans changer de niveau : elle
n'apparaît pas ici, mais comme un saut de téléportation dans l'échantillonnage (§4).

**Type de portail** : pendant ce `teleport`, `player.portalProcess` (champ public `@Nullable`
`PortalProcessor`) est encore renseigné. `Entity.handlePortal` appelle `teleportToPortalDestination`
avant de le remettre à zéro. On en tire :
- `isSamePortal(Blocks.NETHER_PORTAL as Portal)`, et de même pour `END_PORTAL` et `END_GATEWAY`, dont les
  blocs implémentent `net.minecraft.world.level.block.Portal` ;
- `entryPosition`.

Si `portalProcess` est nul, c'est une téléportation. **[À VÉRIFIER]** en jeu : un `/tp` lancé debout
dans un portail donnerait un faux positif.

**Piège majeur, la sortie de l'End** : le portail de sortie (générique de fin) passe par
`PlayerList.respawn(player, alive = true, …)` et crée un **nouveau** `ServerPlayer`. `AFTER_PLAYER_CHANGE_LEVEL`
n'est **pas** déclenché. Il faut l'observer dans `ServerPlayerEvents.AFTER_RESPAWN` avec `alive == true`
(§3.15) et le journaliser comme changement de dimension.

**Création de portails**
- Portail du Nether allumé : §3.2. L'état à la position vaut `nether_portal` au moment du déclencheur
  `PLACED_BLOCK` du briquet.
- Portail de l'End activé : **mixin** sur
  `EnderEyeItem.useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;`
  à l'appel de `Lnet/minecraft/world/level/Level;globalLevelEvent(ILnet/minecraft/core/BlockPos;I)V`.
  Cet appel n'a lieu que si le cadre est complet, après la pose des blocs `END_PORTAL`. Le joueur est
  `context.getPlayer()`.
- Bonus : `UsedEnderEyeTrigger.trigger(ServerPlayer, BlockPos)` signale un œil de l'Ender **lancé**
  (recherche du fort).

**Données** : origine, destination, positions avant et après (`TeleportTransition.position()` si l'on
passe par un mixin), type de portail.

**Fréquence** : moins de 10 par heure.

```kotlin
ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, origin, destination ->
    if (player is FakePlayer) return@register
    val portal = player.portalProcess?.let { p ->
        when {
            p.isSamePortal(Blocks.NETHER_PORTAL as Portal) -> "nether_portal"
            p.isSamePortal(Blocks.END_PORTAL as Portal) -> "end_portal"
            p.isSamePortal(Blocks.END_GATEWAY as Portal) -> "end_gateway"
            else -> "portal"
        }
    }
    Journal.offer(player.event("dimension_change",
        subject = destination.dimension().identifier().toString(),
        other = origin.dimension().identifier().toString(),
        detail = mapOf("via" to (portal ?: "teleport"))))
}
```

### 3.13 Sommeil (début et fin) et point de réapparition

**Mécanisme** : événements Fabric pour le sommeil, **mixin** pour le point de réapparition.
```java
// net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
Event<StartSleeping> START_SLEEPING;  void onStartSleeping(LivingEntity entity, BlockPos sleepingPos);
Event<StopSleeping>  STOP_SLEEPING;   void onStopSleeping(LivingEntity entity, BlockPos sleepingPos);
Event<AllowSettingSpawn> ALLOW_SETTING_SPAWN;  boolean allowSettingSpawn(Player player, BlockPos sleepingPos);
```
- `START_SLEEPING` est injecté au `RETURN` de `LivingEntity.startSleeping(BlockPos)`. Pour un joueur,
  cela n'arrive qu'une fois `ServerPlayer.startSleepInBed(...)` réussi.
- `STOP_SLEEPING` est injecté en tête de `LivingEntity.stopSleeping()`. Les causes possibles : le matin
  (le serveur réveille tout le monde), le joueur qui quitte le lit, des dégâts (`hurtServer` appelle
  `stopSleeping`) ou la mort.
- **Les villageois dorment aussi** : on filtre sur `ServerPlayer`.
- Pour distinguer « nuit passée » de « réveillé », on regarde `level.isBrightOutside()` au moment de
  l'arrêt **[À VÉRIFIER]**.

**Point de réapparition** : mixin.
```
Cible      : net.minecraft.server.level.ServerPlayer
Méthode    : setRespawnPosition(Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;Z)V
Injection  : @At("HEAD"), comparer avec getRespawnConfig() (@Nullable) via RespawnConfig.isSamePosition
```
Appelants vérifiés : `ServerPlayer.startSleepInBed`, qui définit le point **même quand le sommeil
échoue** (le jour, par exemple), `RespawnAnchorBlock` et `SetSpawnCommand`. Un argument `null`
signifie « point effacé ». `ALLOW_SETTING_SPAWN` (Fabric) n'est pas retenu : il ne concerne que les lits
et se déclenche avant la décision.

**Données** : position du lit et type de lit (`BedBlock` ou `StrawBedBlock`), dimension, et
`RespawnConfig.respawnData().pos()`, `dimension()`, `forced()`.

**Pièges** : `setRespawnPosition` est rappelée à chaque usage du même lit, d'où la comparaison.
L'événement `START_SLEEPING` n'est pas déclenché quand le sommeil est refusé (monstres proches, jour,
Nether, où le lit explose selon `BedRule`).

**Fréquence** : moins de 5 par heure.

### 3.14 Chat et commandes

**Chat**, par événement Fabric :
```java
// net.fabricmc.fabric.api.message.v1.ServerMessageEvents  (fabric-message-api-v1 7.0.10)
Event<ChatMessage> CHAT_MESSAGE;
void onChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params);
```
Il est injecté en tête de
`PlayerList.broadcastChatMessage(PlayerChatMessage, ServerPlayer, ChatType$Bound)V`, sur le thread
serveur (§1.1). Texte brut : `message.signedContent()`. Texte décoré : `message.decoratedContent().string`.
Pour ne garder que le vrai chat, on filtre `params.chatType().is(ChatType.CHAT)` : `/say` et `/me`
déclenchent aussi `CHAT_MESSAGE` après `COMMAND_MESSAGE`, d'après la Javadoc de Fabric. Les seuls
appelants de la version `CommandSourceStack` de `broadcastChatMessage` sont `SayCommand` et
`EmoteCommands`, ce qui a été vérifié. `/msg` et `/teammsg` ne diffusent rien et **n'apparaissent pas** ici.

**Commandes**, par **mixin** : aucun événement Fabric ne signale l'exécution d'une commande.
```
Cible      : net.minecraft.server.network.ServerGamePacketListenerImpl
Méthodes   : performUnsignedChatCommand(Ljava/lang/String;)V                                    (privée)
             performSignedChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket;Lnet/minecraft/network/chat/LastSeenMessages;)V  (privée)
Injection  : @At("HEAD") ; texte = paramètre String, ou packet.command() ; joueur = champ public `player`
```
Ces deux méthodes ne voient que les commandes **tapées par un joueur**, sans la console, les blocs de
commande, les fonctions ni les commandes du Narrateur. Elles passent par `tryHandleChat` puis
`MinecraftServer.execute`, donc sur le thread serveur. Le texte arrive sans le `/` initial.
L'alternative `Commands.performCommand(ParseResults, String)` voit **toutes** les sources.

**Pièges**
- Une commande invalide est journalisée quand même : c'est une tentative.
- On classe `msg`, `tell`, `w`, `teammsg` et `tm` en `whisper` pour la table `chat`.
- Les commandes du mod (`/narrator …`, canal privé compris) apparaissent aussi ici, ce qui est utile.
- Confidentialité : une liste d'exclusion configurable de racines de commande (`login`, `register`,
  vides par défaut sur ce serveur vanilla).

**Fréquence** : 10 à 100 messages par heure, 0 à 50 commandes par heure.

```kotlin
ServerMessageEvents.CHAT_MESSAGE.register { message, sender, params ->
    if (!params.chatType().`is`(ChatType.CHAT)) return@register   // /say and /me come from the command hook
    ChatLog.offer(sender, kind = "chat", text = message.signedContent())
}
```

### 3.15 Connexion, déconnexion, réapparition

**Mécanisme** : événements Fabric.
```java
// net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
Event<Join> JOIN;            void onJoin(ServerPlayer player);    // RETURN of PlayerList.placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)
Event<Leave> LEAVE;          void onLeave(ServerPlayer player);   // HEAD of PlayerList.remove(ServerPlayer), before saving
Event<AfterRespawn> AFTER_RESPAWN;  void afterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive);
                             // TAIL of PlayerList.respawn(ServerPlayer, boolean, Entity$RemovalReason)
```
`ServerPlayConnectionEvents.JOIN` et `DISCONNECT` existent toujours, avec les signatures
`onPlayReady(ServerGamePacketListenerImpl, PacketSender, MinecraftServer)` et
`onPlayDisconnect(ServerGamePacketListenerImpl, MinecraftServer)`. `ServerPlayerEvents` est plus direct.

**Données** : session (UUID, nom, heure), position et dimension à la connexion, et snapshot des stats
à la déconnexion (§5). Pour une réapparition : position, dimension, `alive`.

**Pièges**
- `PlayerList.respawn` crée un **nouveau** `ServerPlayer` : on indexe par UUID, jamais par instance.
- `alive == true` correspond à la sortie de l'End (§3.12), pas à une mort.
- `LEAVE` se déclenche aussi après un kick, un ban, un délai réseau dépassé, et à l'arrêt du serveur
  **après** `SERVER_STOPPING` (§1.5).
- `ServerPlayer.getIpAddress()` existe, mais **on ne stocke pas l'IP**.

**Fréquence** : moins de 10 par heure.

### 3.16 Pêche, élevage, apprivoisement

Les trois passent par des **mixins en tête de déclencheurs d'advancement**. Il n'existe pas d'événement
Fabric. Ces déclencheurs sont typés `ServerPlayer`, appelés uniquement côté serveur, et se déclenchent
même si aucun advancement n'écoute : le test des écouteurs se fait à l'intérieur de `trigger`.

| Interaction | Cible (`net.minecraft.advancements.triggers.*`) | Descripteur de `trigger` | Appelants vérifiés |
|---|---|---|---|
| Pêche | `FishingRodHookedTrigger` | `(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/projectile/FishingHook;Ljava/util/Collection;)V` | `FishingHook.retrieve(ItemStack)`, deux chemins : entité accrochée (`pullEntity`, butin **vide**) et prise (butin tiré de `BuiltInLootTables.FISHING`) |
| Élevage | `BredAnimalsTrigger` | `(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/animal/Animal;Lnet/minecraft/world/entity/AgeableMob;)V`, le 4e argument étant `@Nullable` | `Animal.finalizeSpawnChildFromBreeding`, `Fox$FoxBreedGoal`, `Turtle$TurtleBreedGoal` (petit nul : œuf) |
| Apprivoisement | `TameAnimalTrigger` | `(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/animal/Animal;)V` | `TamableAnimal.tame(Player)` (loup, chat, perroquet…) et `AbstractHorse.tameWithName(Player)` (cheval, âne, lama…) |

**Données** : canne et butin (copier objet et quantité), entité accrochée (`hook.hookedIn`, privé, ou
butin vide), parents, petit et type d'animal, animal apprivoisé.

**Pièges**
- La stat `FISH_CAUGHT` n'est attribuée que si l'objet est dans `#fishes` : on ne s'y fie pas.
- Pour l'élevage, le joueur est la « cause d'amour » (`Animal.getLoveCause()`), qui n'est pas
  forcément à côté.

**Fréquence** : pêche de 0 à 60 par heure, élevage de 0 à 50 par heure, apprivoisement rare.

```java
@Mixin(FishingRodHookedTrigger.class)
abstract class FishingRodHookedTriggerMixin {
    @Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/projectile/FishingHook;Ljava/util/Collection;)V",
            at = @At("HEAD"))
    private void narrator2$onFish(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> loot, CallbackInfo ci) {
        ObserverHooks.onFishing(player, rod, hook, loot);   // loot.isEmpty() => an entity was reeled in
    }
}
```

### 3.17 Projectiles qui touchent

**Mécanisme** : **mixin**. Il n'existe pas d'événement Fabric.
```
Cible      : net.minecraft.world.entity.projectile.Projectile
Méthode    : hitTargetOrDeflectSelf(Lnet/minecraft/world/phys/HitResult;)Lnet/minecraft/world/entity/projectile/ProjectileDeflection;  (protected)
Injection  : @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/Projectile;onHit(Lnet/minecraft/world/phys/HitResult;)V")
```
Cette injection n'est atteinte que si le projectile n'a **pas** été dévié : l'entité touchée renvoie
un `ProjectileDeflection` autre que `NONE` (via `Entity.deflection(Projectile)`), ou le projectile
rebondit sur la bordure du monde. La méthode n'est redéfinie par **aucune** sous-classe : c'est
vérifié pour `AbstractArrow`, `ThrowableProjectile`, `AbstractHurtingProjectile`,
`FireworkRocketEntity`, `FishingHook`, `LlamaSpit` et `ShulkerBullet`, qui se contentent de l'appeler.

**Données**
- Tireur : `getOwner()`, `@Nullable`.
- Type de projectile : flèche, trident, boule de neige, perle, bourrasque, feu d'artifice…
- Arme : `AbstractArrow.getWeaponItem()`.
- Impact : `EntityHitResult.getEntity()`, ou `BlockHitResult.getBlockPos()` et `getDirection()`.

**Filtre** : le tireur est un `ServerPlayer`, **ou** l'entité touchée est un `ServerPlayer`.

**Pièges**
- Une flèche perçante appelle la méthode plusieurs fois.
- L'impact sur une entité est suivi de l'événement de dégâts (§3.3) : les deux lignes décrivent des
  choses différentes.
- On exclut `FishingHook` (pêche, §3.16).
- En solo, la méthode tourne aussi côté client : on teste `level().isClientSide()`.

**Fréquence** : 0 à 500 par heure pour un archer, plus les tirs de squelettes reçus.

### 3.18 Explosions : auteur, lieu, blocs détruits

**Mécanisme** : **mixin**. Il n'existe pas d'événement Fabric.
```
Cible      : net.minecraft.world.level.ServerExplosion
Méthodes   : explode()I                            @At("RETURN")  → résumé (renvoie le nombre de positions calculées)
             interactWithBlocks(Ljava/util/List;)V @At("HEAD")    → List<BlockPos> encore en place (privée)
```
`explode()` appelle `calculateExplodedPositions()`, `hurtEntities()`, puis `interactWithBlocks(list)`
seulement si `interactsWithBlocks()`, puis `createFire(list)` s'il y a du feu.

**Données**
- Centre et rayon : `center()`, `radius()`.
- Cause directe : `getDirectSourceEntity()`, `@Nullable` (creeper, TNT amorcée, boule de feu, bourrasque).
- Auteur : `getIndirectSourceEntity()`, `@Nullable LivingEntity` (le joueur qui a allumé la TNT, le ghast).
- `getDamageSource()` et `getBlockInteraction()` (`Explosion.BlockInteraction` : KEEP, DESTROY,
  DESTROY_WITH_DECAY, TRIGGER_BLOCK).
- `getHitPlayers()`, `isSmall()`, `level()`.

En tête d'`interactWithBlocks`, on lit l'état des blocs de la liste pour compter les blocs détruits et
**croiser avec l'index des blocs posés** (§6).

**Filtre** : l'auteur est un `ServerPlayer`, **ou** un joueur se trouve à moins de 32 blocs, **ou** un
bloc posé par un joueur est touché.

**Pièges**
- La TNT en chaîne produit des dizaines d'explosions dans le même tick : on les fusionne par tick et
  par zone.
- Les bourrasques et les breezes produisent de petites explosions `TRIGGER_BLOCK` très fréquentes.
- La liste est **mélangée** (`Util.shuffle`), et c'est `BlockState.onExplosionHit` qui décide du sort de
  chaque bloc : on parle de « blocs touchés ».
- Avec `mobGriefing=false`, les creepers n'interagissent pas avec les blocs et `interactWithBlocks`
  n'est pas appelée.
- L'allumage de TNT (`TntBlock.prime(Level, BlockPos, LivingEntity, ItemStack)`) émet
  `GameEvent.PRIME_FUSE` avec le joueur pour source (§3.21).

**Fréquence** : 0 à 30 par heure.

### 3.19 Montures et véhicules

**Mécanisme** : **mixins** sur `ServerPlayer`. Il n'existe pas d'événement Fabric.
`StartRidingTrigger.trigger(ServerPlayer)` ne donne pas le véhicule.
```
Montée     : startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z   @At("RETURN"), garder si cir.getReturnValueZ()
Descente   : removeVehicle()V                                       @At("HEAD"), garder si getVehicle() != null
```
`Entity.startRiding(Entity)`, qui est `final`, appelle la version à trois arguments. `Entity.stopRiding()`
appelle `removeVehicle()`.

**Données** : type de véhicule (bateau, wagonnet, cheval, cochon, arpenteur, dromadaire, ghast joyeux,
nautile…), UUID, propriétaire si l'animal est apprivoisé, position.

**Pièges** : `removeVehicle` est aussi appelée à la mort, au changement de dimension (quand
`TeleportTransition.asPassenger()` est faux), à la déconnexion et à la téléportation. On note alors la
cause à partir du contexte, par exemple une mort ou un changement de dimension survenu dans le même tick.

**Fréquence** : 0 à 50 par heure.

### 3.20 Seaux, feu, portails : récapitulatif
- Eau ou lave versée, feu allumé, portail du Nether allumé : §3.2, déclencheur `PLACED_BLOCK`.
- Fluide ou poisson récupéré dans un seau : `FilledBucketTrigger.trigger(ServerPlayer, ItemStack)`, en
  tête, optionnel.
- Portail de l'End : §3.12. Voyages : §3.12.

### 3.21 Option générique : les événements de jeu (vibrations)
`ServerLevel.gameEvent(Holder<GameEvent>, Vec3, GameEvent.Context)` est l'implémentation unique côté
serveur. Toutes les surcharges par défaut de `LevelAccessor.gameEvent(...)` y aboutissent.
`GameEvent.Context` est un record : `sourceEntity()` et `affectedState()`, tous deux `@Nullable`.
```
Cible      : net.minecraft.server.level.ServerLevel
Méthode    : gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V
Injection  : @At("HEAD"), tester d'abord context.sourceEntity() instanceof ServerPlayer
```
Cette cible est utile pour ce que les déclencheurs ne voient pas :
- `BLOCK_PLACE` émis par `FireChargeItem` ;
- `CONTAINER_OPEN` avec la position exacte ;
- `PRIME_FUSE` (TNT allumée par un joueur) ;
- `ENTITY_MOUNT` et `ENTITY_DISMOUNT`, `SHEAR`, `FLUID_PICKUP`.

On compare les événements avec `event.is(GameEvent.X)`, les constantes étant des `Holder.Reference<GameEvent>`,
et la position s'obtient par `BlockPos.containing(vec)`.

**Coût** : c'est un **chemin très chaud**, appelé à chaque pas de chaque entité (`STEP`), puis `SWIM`,
`FLAP`, `HIT_GROUND`… Le premier test doit être l'`instanceof`. Mixin alloue un `CallbackInfo` par appel,
ce qui est négligeable mais à mesurer **[À VÉRIFIER]**. Cette cible n'est **pas** nécessaire pour M1 :
elle reste optionnelle.

---

## 4. Déplacements : échantillonnage

L'écoute des mouvements ne sert pas : chaque paquet de mouvement arrive plusieurs fois par seconde et
par joueur. On fait un **sondage** dans `ServerTickEvents.END_SERVER_TICK` (`onEndTick(MinecraftServer)`).

**Cadence** : chaque joueur est examiné une fois par seconde, en étalant la charge
(`tick % 20 == index % 20`). Chaque examen est bon marché : position, dimension, biome et véhicule.

**Écriture d'une ligne `positions`** (§4.2 de l'architecture), si l'une de ces conditions est vraie :

| Déclencheur | Seuil proposé |
|---|---|
| Première mesure après la connexion ou la réapparition | toujours |
| Changement de dimension | toujours, immédiatement |
| Distance depuis le dernier point écrit | ≥ 16 blocs à l'horizontale **ou** ≥ 8 blocs à la verticale |
| Temps écoulé | ≥ 60 s **et** au moins 2 blocs parcourus |
| Pulsation d'inactivité | toutes les 5 min, marquée `idle` (présence) |
| Saut de téléportation | ≥ 64 blocs d'un examen à l'autre, sans changement de dimension → ligne et événement `teleport` |
| Changement de véhicule | toujours |

**Plafond** : au plus une ligne toutes les 3 s par joueur. Un vol en élytre (30 blocs par seconde ou
plus) donne ainsi au plus 1 200 lignes par heure.

**Événements dérivés** (table `events`), calculés sur les mêmes mesures avec hystérésis :
- **Biome** : `level.getBiome(pos)`, qui lit le biome stocké dans le tronçon. Un nouveau biome doit être
  stable sur 3 examens consécutifs (3 s). On marque la première visite à partir de l'ensemble des biomes
  déjà vus par le joueur, chargé à la connexion.
- **Structure** : examen toutes les 5 s, ou dès que le tronçon change, avec
  `level.structureManager().getStructureWithPieceAt(pos, Predicate<Holder<Structure>>)`. On garde le
  résultat si `start.isValid()`, et l'identifiant se lit avec
  `level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(start.structure)`. On journalise
  l'entrée et la sortie.
- **Sous terre** : `!level.canSeeSky(pos)` (`BlockAndLightGetter`) et `y` inférieur au niveau de la mer
  moins 8, stable pendant 10 s.
- **Vie critique** : vie ≤ 4, avec réarmement à ≥ 8.
- **Inactivité** : `ServerPlayer.getLastActionTime()`, horodatage en millisecondes
  (`resetLastActionTime` fait `Util.getMillis()`) mis à jour par les actions du joueur **[À VÉRIFIER :
  lesquelles]**, et aucun déplacement de plus de 2 blocs depuis 5 min.

Le changement de tronçon (`chunkPosition()`, record `ChunkPos`) ne déclenche **pas** d'écriture : il est
redondant avec le seuil de 16 blocs. Il sert seulement à déclencher l'examen des structures.

**« Le joueur est allé de A à B »** : on ne stocke pas de trajets. On les reconstruit en SQL : un segment
commence quand le joueur quitte un point d'arrêt (plus de 60 s dans un rayon de 8 blocs), avec
`LAG`/`LEAD` sur `positions`, et se termine au point d'arrêt suivant. On peut ajouter une macro
`trips(player, since)`.

**Volume** : à pied (4,3 blocs par seconde), on atteint le seuil toutes les 3,7 s, soit au plus environ
970 lignes par heure en marche continue. Une partie réelle mélange base, minage et trajets : 150 à
600 lignes par heure et par joueur. Pour 5 joueurs pendant 3 h par jour, cela fait moins de 10 000 lignes
par jour. **Coût serveur** : une consultation de biome et une de lumière par joueur et par seconde, plus
une recherche de structure toutes les 5 s, ce qui est négligeable.

---

## 5. Statistiques vanilla

### 5.1 Le hook générique
```
Cible      : net.minecraft.server.level.ServerPlayer
Méthode    : awardStat(Lnet/minecraft/stats/Stat;I)V
Injection  : @At("HEAD")
```
**Tous** les chemins y aboutissent : `Player.awardStat(Identifier)`, `awardStat(Identifier, int)` et
`awardStat(Stat)` délèguent à `awardStat(Stat, int)`. Cette méthode fait
`ServerStatsCounter.increment(player, stat, n)`, qui appelle `setValue` et marque la stat `dirty`, puis
met à jour les objectifs du tableau des scores. `FakePlayer` la redéfinit à vide.

**Volume brut** : 60 à 100 appels par seconde et par joueur. `ServerPlayer.doTick()` attribue à chaque tick
`PLAY_TIME`, `TOTAL_WORLD_TIME`, `TIME_SINCE_DEATH`, `TIME_SINCE_REST` et `CROUCH_TIME` (accroupi), et
`checkMovementStatistics` attribue les `*_ONE_CM`. Le premier test doit donc être une recherche dans un
**ensemble d'identité** de `Stat` à ignorer, résolu une fois au démarrage (`Stats.CUSTOM.get(Stats.WALK_ONE_CM)`…).

| Catégorie | Stats | Traitement |
|---|---|---|
| Ignorées (bruit ou doublon) | tous les `*_ONE_CM`, `PLAY_TIME`, `TOTAL_WORLD_TIME`, `TIME_SINCE_DEATH`, `TIME_SINCE_REST`, `CROUCH_TIME`, `JUMP`, `DAMAGE_*`, `DEATHS`, `MOB_KILLS`, `PLAYER_KILLS`, `LEAVE_GAME`, types `BLOCK_MINED`, `ITEM_USED`, `ITEM_CRAFTED`, `ENTITY_KILLED`, `ENTITY_KILLED_BY`, `TRADED_WITH_VILLAGER`, `ANIMALS_BRED`, `FISH_CAUGHT`, `ENCHANT_ITEM` | rien, ou totaux seulement (§5.2) |
| Agrégées | `ITEM_PICKED_UP`, `ITEM_DROPPED` (§3.10) | somme par (joueur, stat) vidée toutes les 30 s |
| Journalisées une par une (`type = "stat"`, `subject = stat.getName()`) | `ITEM_BROKEN` (outil cassé !), `TALKED_TO_VILLAGER`, `EAT_CAKE_SLICE`, `FILL_CAULDRON`, `USE_CAULDRON`, `CLEAN_ARMOR`, `CLEAN_BANNER`, `CLEAN_SHULKER_BOX`, les `INTERACT_WITH_*`, `INSPECT_*`, `PLAY_NOTEBLOCK`, `TUNE_NOTEBLOCK`, `POT_FLOWER`, `TRIGGER_TRAPPED_CHEST`, `PLAY_RECORD`, `BELL_RING`, `RAID_TRIGGER`, `RAID_WIN`, `TARGET_HIT`, `SLEEP_IN_BED`, `SLEEP_IN_STRAW_BED`, `OPEN_*`, `DROP` | une ligne, à la position du joueur |

Ce tableau couvre d'un coup les interactions « diverses » que la liste de §3 ne détaille pas. La stat ne
donne pas la position du bloc : on utilise la position du joueur, qui est à moins de 5 blocs.
**Fréquence résiduelle** : 50 à 200 lignes par heure.

```java
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Inject(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At("HEAD"))
    private void narrator2$onStat(Stat<?> stat, int amount, CallbackInfo ci) {
        ObserverHooks.onStat((ServerPlayer) (Object) this, stat, amount);
    }
    // openMenu, setRespawnPosition, startRiding, removeVehicle and die can share this mixin class.
}
```

### 5.2 Lecture des totaux (`ServerStatsCounter`)
- Joueur connecté : `player.getStats(): ServerStatsCounter`, équivalent à
  `server.getPlayerList().getPlayerStats(Player)`. On lit une stat avec
  `StatsCounter.getValue(Stat<?>): Int` ou `getValue(StatType<T>, T): Int`, par exemple
  `getValue(Stats.BLOCK_MINED, Blocks.DIAMOND_ORE)` ou `getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM))`.
- Instantané complet : la table est le champ **protégé** `StatsCounter.stats`
  (`Object2IntMap<Stat<?>>`). On y accède avec un **accesseur** :
  `@Mixin(StatsCounter.class) interface StatsCounterAccessor { @Accessor("stats") Object2IntMap<Stat<?>> narrator2$stats(); }`.
  Sans mixin, on peut parcourir `BuiltInRegistries.STAT_TYPE`, chaque `StatType` étant un
  `Iterable<Stat<T>>` limité aux stats déjà créées. Le champ privé `dirty` n'est pas utilisable : il est
  vidé par `sendStats` quand le client ouvre l'écran des stats.
- **Fréquence** : toutes les **5 minutes** pour chaque joueur connecté, plus à `LEAVE` (§3.15). La copie
  de la table se fait sur le thread serveur, avec quelques centaines d'entrées par joueur, en moins
  d'une milliseconde. Le calcul du delta et l'écriture se font hors thread. On n'écrit que les valeurs
  modifiées : `stat_totals(player_uuid, stat, value, updated_at)`, par mise à jour ou insertion, plus
  éventuellement `stat_deltas`. La fiche joueur (MEMOIRE.md §3.4) se recalcule « toutes les quelques
  minutes » : ce rythme suffit.
- **Joueurs hors ligne et initialisation d'un monde existant** : on lit
  `world/stats/<uuid>.json`, dont le chemin est donné par
  `server.getWorldPath(LevelResource.PLAYER_STATS_DIR)`. Le format est `{"stats": {"minecraft:mined": {"minecraft:stone": 123}, "minecraft:custom": {...}}, "DataVersion": N}`.
  Les clés `stats` et `DataVersion` ont été vérifiées dans le bytecode, la structure imbriquée est
  **[À VÉRIFIER]** sur un fichier réel. Cette lecture de fichier se fait hors thread serveur.
- Totaux utiles à la fiche : blocs minés par type (`minecraft:mined`), kills par type
  (`minecraft:killed`), morts par type de tueur (`minecraft:killed_by`), distances (somme des
  `*_one_cm` divisée par 100 pour obtenir des blocs), `play_time` (en ticks, divisé par 20), `deaths`,
  `mob_kills`, `player_kills`, `traded_with_villager`, `animals_bred`, `fish_caught` et objets utilisés
  (`minecraft:used`). La somme des `minecraft:used` sur les objets-blocs estime les **blocs posés** d'un
  monde existant (§6).
- Sauvegarde vanilla : `ServerStatsCounter.save()` est appelée à la sauvegarde du joueur (sauvegarde
  automatique et déconnexion). On ne l'appelle pas soi-même.

---

## 6. Blocs posés par les joueurs : index des constructions

**Rien n'existe en vanilla.** Aucun bloc ni tronçon ne mémorise qui l'a posé. La recherche de
`placer`, `placedBy` ou `playerPlaced` dans le jar ne trouve que des outils de génération du monde
(`FeaturePlacer`). `Block.setPlacedBy` n'est qu'un rappel au moment de la pose, et `GameEvent.BLOCK_PLACE`
un signal éphémère. L'approche est donc **confirmée** : l'Observateur journalise les poses (§3.2) et un
index spatial répond à « ce bloc a-t-il été posé par un joueur ? ».

### 6.1 Implémentation recommandée : une pièce jointe de tronçon (Fabric Data Attachment API)
`fabric-data-attachment-api-v1` injecte l'interface `AttachmentTarget` dans `ChunkAccess`, `Entity`,
`BlockEntity` et `Level`. Le fichier `.classtweaker` du module déclare
`transitive-inject-interface net/minecraft/world/level/chunk/ChunkAccess net/fabricmc/fabric/api/attachment/v1/AttachmentTarget`.
Une pièce jointe **persistante** est encodée dans `SerializableChunkData.copyOf`, sur le thread serveur,
et stockée dans le NBT du tronçon.

**Piège vérifié dans le bytecode** : `setAttached`, dans `AttachmentTargetsMixin`, n'appelle
`fabric_markChanged` (qui fait `markUnsaved()`) **que si `!Objects.equals(ancien, nouveau)`**.
`modifyAttached`, qui fait `getAttached` puis `setAttached`, a la même limite. Si l'on modifie
l'ensemble **en place** et qu'on renvoie la même instance, le tronçon n'est **pas** marqué comme modifié
et la modification peut être perdue. On modifie donc en place **puis** on appelle `chunk.markUnsaved()`,
qui est publique dans `ChunkAccess`.

```kotlin
object PlacedBlocks {
    /** Player-placed positions of one chunk, packed as ((y - minY) shl 8) or (z shl 4) or x. */
    val TYPE: AttachmentType<IntOpenHashSet> =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "placed_blocks")) { builder ->
            builder.persistent(Codec.INT_STREAM.xmap({ IntOpenHashSet(it.toArray()) }, { it.intStream() }))
        }

    private fun pack(level: Level, pos: BlockPos) =
        ((pos.y - level.minY) shl 8) or ((pos.z and 15) shl 4) or (pos.x and 15)

    fun mark(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val chunk = level.getChunkAt(pos)
        val set = chunk.getAttached(TYPE) ?: IntOpenHashSet().also { chunk.setAttached(TYPE, it) }
        // + the other half of doors, beds and tall plants (from DoubleBlockHalf / BedPart)
        if (set.add(pack(level, pos))) {
            // setAttached only marks the chunk when the value is not equals() to the old one:
            // an in-place mutation must mark it explicitly or it may never be saved.
            chunk.markUnsaved()
        }
    }

    fun isPlayerPlaced(level: ServerLevel, pos: BlockPos): Boolean =
        level.getChunkAt(pos).getAttached(TYPE)?.contains(pack(level, pos)) == true
}
```
Signatures vérifiées :
- `AttachmentRegistry.create(Identifier, Consumer<Builder<A>>)` et `Builder.persistent(Codec<A>)` ;
- `AttachmentTarget.getAttached(AttachmentType<A>)`, `@Nullable`, et
  `setAttached(AttachmentType<A>, @Nullable A)` ;
- `ChunkAccess.markUnsaved()`, `Level.getChunkAt(BlockPos): LevelChunk` et `LevelHeightAccessor.getMinY()` ;
- DFU `Codec.INT_STREAM` et fastutil `IntCollection.intStream()`.

Les retraits suivent le même schéma : `set.remove(...)`, puis `markUnsaved()`.

**Règles de mise à jour**
- Pose par un joueur (§3.2) : on **ajoute** la position, et l'autre moitié pour les blocs doubles.
- Casse par un joueur (§3.1) : on **retire** la position. Si la casse est le fait d'un autre joueur que
  le poseur, c'est un événement `grief` intéressant pour le Narrateur.
- Destruction naturelle (explosion, feu, lave, piston, endermen) : l'entrée **reste**. Avant d'agir sur
  une position, le moteur vérifie donc aussi que le bloc n'est pas de l'air. Le bloc d'origine est dans
  le Journal si l'on veut le restaurer.
- Transformations sur place (oxydation du cuivre, cultures, bûche écorcée) : la position reste « posée
  par un joueur », ce qui est l'effet voulu.
- Limite : un bloc déplacé par un **piston** perd sa marque. Un mixin sur
  `PistonBaseBlock.moveBlocks` pourrait décaler l'index **[À VÉRIFIER]**, mais c'est hors M1.

**Avantages** : l'index est persistant sans code d'entrée-sortie, sauvegardé avec le monde (ses
sauvegardes le couvrent), chargé et déchargé avec les tronçons, et ne coûte **aucune mémoire** globale.
**Limite** : on ne peut interroger que les tronçons chargés sur le thread serveur, ce qui suffit puisque
le moteur n'agit que près des joueurs. Les questions historiques (« qui a bâti ici ? ») passent par le
Journal : une vue SQL qui garde le dernier événement `block_place` ou `block_break` par position.

**[À VÉRIFIER]** à la première compilation :
- que Kotlin voit bien `getAttached` et `setAttached` sur `LevelChunk`, grâce à l'injection
  d'interface appliquée par Loom au jar de compilation ;
- la persistance après un redémarrage (poser, sauvegarder, recharger le tronçon), avec un GameTest.

### 6.2 Volume estimé
- Rythme de pose : 200 à 1 500 blocs par heure pour un joueur qui construit, 50 à 200 en exploration ou
  en minage. Hypothèse pour un petit serveur de 5 joueurs pendant 2 à 3 h par jour : **5 000 à 15 000
  poses par jour**, soit **2 à 5 millions par an**. Pour un monde existant, on mesure au lieu d'estimer
  en sommant `minecraft:used` sur les objets-blocs dans `world/stats/*.json`.
- **Journal** : une ligne par pose et par casse de bloc marqué, soit environ 20 à 40 octets compressés
  en colonnes dans DuckDB, donc **50 à 200 Mo par an**.
- **Index** : 4 octets par bloc dans le NBT, compressés avec la région, soit 8 à 20 Mo par an répartis
  dans les fichiers de région. En mémoire, seuls les tronçons chargés comptent : une base dense de
  5 000 blocs dans un tronçon tient dans un `IntOpenHashSet` d'environ 27 Ko.
- Ces chiffres sont des ordres de grandeur **[À VÉRIFIER]** à mesurer après une semaine de jeu.

### 6.3 Alternative écartée
Un index global en mémoire (`Long2ObjectOpenHashMap<LongOpenHashSet>` par dimension et tronçon) chargé
depuis DuckDB au démarrage demanderait environ 11 à 16 octets par bloc, soit 60 Mo pour 4 millions de
blocs. Il ferait aussi dépendre le démarrage de la base et imposerait une resynchronisation. La pièce
jointe de tronçon évite ces trois problèmes.

---

## 7. Liste des mixins

Toutes les classes sont dans `io.github.clemsix6.narrator2.mixin` (`narrator2.mixins.json`, section
`"mixins"` car il s'agit de code serveur, `defaultRequire: 1`). Mixin 0.8.7 (sponge-mixin 0.17.4) et
MixinExtras 0.5.5 sont fournis par Fabric Loader 0.19.5.

| # | Classe cible | Méthode (descripteur) | Point d'injection | Pour | Indispensable ? |
|---|---|---|---|---|---|
| 1 | `ServerPlayer` | `awardStat(Lnet/minecraft/stats/Stat;I)V` | HEAD | §5, §3.10 | oui |
| 2 | `ItemUsedOnLocationTrigger` | `trigger(…ServerPlayer;…BlockPos;…ItemInstance;)V` | HEAD, `this == PLACED_BLOCK` | §3.2 | oui |
| 3 | `ConsumeItemTrigger` | `trigger(…ServerPlayer;…ItemStack;)V` | HEAD | §3.5 | oui |
| 4 | `ItemStack` | `onCraftedBy(Lnet/minecraft/world/entity/player/Player;I)V` | HEAD | §3.6 | oui |
| 5 | `AnvilMenu` | `onTake(…Player;…ItemStack;)V` | HEAD | §3.7 | oui |
| 6 | `GrindstoneMenu$4` (anonyme) | `onTake(…Player;…ItemStack;)V` | HEAD | §3.7 | oui, fragile |
| 7 | `EnchantedItemTrigger` | `trigger(…ServerPlayer;…ItemStack;I)V` | HEAD | §3.7 | oui |
| 8 | `AbstractVillager` | `notifyTrade(Lnet/minecraft/world/item/trading/MerchantOffer;)V` | HEAD | §3.8 | oui |
| 9 | `ServerPlayer` | `openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;` | RETURN | §3.9 | oui |
| 10 | `PlayerAdvancements` | `award(…AdvancementHolder;Ljava/lang/String;)Z` | INVOKE `AdvancementRewards.grant` | §3.11 | oui |
| 11 | `ServerPlayer` | `setRespawnPosition(Lnet/minecraft/server/level/ServerPlayer$RespawnConfig;Z)V` | HEAD | §3.13 | oui |
| 12 | `ServerGamePacketListenerImpl` | `performUnsignedChatCommand(Ljava/lang/String;)V` et `performSignedChatCommand(…)V` | HEAD | §3.14 | oui |
| 13 | `FishingRodHookedTrigger`, `BredAnimalsTrigger`, `TameAnimalTrigger` | `trigger(…)` | HEAD | §3.16 | oui |
| 14 | `Projectile` | `hitTargetOrDeflectSelf(…HitResult;)…ProjectileDeflection;` | INVOKE `Projectile.onHit` | §3.17 | oui |
| 15 | `ServerExplosion` | `explode()I` et `interactWithBlocks(Ljava/util/List;)V` | RETURN et HEAD | §3.18 | oui |
| 16 | `ServerPlayer` | `startRiding(…Entity;ZZ)Z` et `removeVehicle()V` | RETURN et HEAD | §3.19 | oui |
| 17 | `ServerPlayer` | `die(Lnet/minecraft/world/damagesource/DamageSource;)V` | HEAD | §3.4, message complet | recommandé |
| 18 | `EnderEyeItem` | `useOn(…UseOnContext;)…InteractionResult;` | INVOKE `Level.globalLevelEvent(ILBlockPos;I)V` | §3.12 | recommandé |
| 19 | `StatsCounter` (accesseur) | champ `stats` | `@Accessor` | §5.2 | recommandé |
| 20 | `LootTableTrigger`, `RecipeCraftedTrigger`, `FilledBucketTrigger` | `trigger(…)` | HEAD | bonus | optionnel |
| 21 | `ServerLevel` | `gameEvent(…Holder;…Vec3;…GameEvent$Context;)V` | HEAD | §3.21 | optionnel |
| 22 | `ItemEntity` | `playerTouch(…Player;)V` | INVOKE `Player.take` + `@Local` | ramassage exact | optionnel |

Compatibilité avec Fabric API : ses propres mixins ciblent aussi `ServerPlayer.die`
(`getKillCredit`, `TAIL`), `PlayerAdvancements.award` (`HEAD`), `LivingEntity.hurtServer` et
`PlayerList`. Nos injections sont des `@Inject` non annulants à d'autres points, sans conflit connu.

---

## 8. Points [À VÉRIFIER]

1. La syntaxe Kotlin de tous les extraits : conversions SAM, types des lambdas de `modifyAttached`,
   accès aux composants de records Java. Rien n'a été compilé.
2. `GrindstoneMenu$4` : le numéro de la classe anonyme est vérifié en 26.3 mais fragile. Il faut un
   GameTest.
3. `player.portalProcess` encore renseigné pendant `AFTER_PLAYER_CHANGE_LEVEL` lors d'un voyage par
   portail : c'est déduit de l'ordre du bytecode et doit être testé en jeu, y compris le cas d'un `/tp`
   lancé depuis un portail.
4. La cause du réveil (`isBrightOutside()` au moment de `STOP_SLEEPING`).
5. Le message de mort complet lu en tête de `ServerPlayer.die`, avant `recheckStatus`.
6. La pièce jointe de tronçon : visibilité de l'interface injectée depuis Kotlin, et persistance après
   un redémarrage. Le piège du marquage « non sauvegardé » est vérifié et contourné par `markUnsaved()`.
7. La structure imbriquée de `world/stats/<uuid>.json` : seules les clés `stats` et `DataVersion` sont
   vérifiées.
8. `ServerPlayer.getLastActionTime()` comme mesure d'inactivité : la valeur est bien en millisecondes
   (`resetLastActionTime` fait `Util.getMillis()`), mais il reste à vérifier quels paquets la remettent
   à zéro.
9. Le surcoût du mixin `ServerLevel.gameEvent` s'il est retenu (chemin très chaud).
10. Toutes les fréquences et volumes (§3, §4, §6.2) : à mesurer avec les compteurs du Journal
    (événements par seconde, par type).
11. `BLOCK_PLACE` de `FireChargeItem` : l'entité source (`context.getPlayer()`) est vérifiée, mais le
    cas du feu de camp n'a pas été testé.
12. Le décalage de l'index par les pistons (hors M1).

---

## 9. Table récapitulative

Fréquence par joueur actif et par heure de jeu, en estimation.
Classes : **très rare** (moins de 1), **rare** (1 à 10), **moyenne** (10 à 100), **forte** (100 à 1 000),
**très forte** (plus de 1 000).

| Interaction | Mécanisme | Classe et méthode | Coût estimé |
|---|---|---|---|
| Bloc cassé | Événement Fabric | `PlayerBlockBreakEvents.AFTER` ← `ServerPlayerGameMode.destroyBlock` | forte à très forte (300 à 3 000) |
| Bloc posé | Mixin sur déclencheur | `ItemUsedOnLocationTrigger.trigger` (`PLACED_BLOCK`) ← `BlockItem.place` | forte (100 à 2 000) |
| Eau ou lave versée | Mixin sur déclencheur | idem ← `BucketItem.use` | rare à moyenne |
| Feu allumé, portail du Nether allumé | Mixin sur déclencheur | idem ← `FlintAndSteelItem.useOn` | rare |
| Dégâts infligés et subis | Événement Fabric | `ServerLivingEntityEvents.AFTER_DAMAGE` ← `LivingEntity.hurtServer` | moyenne à forte (50 à 600, 30 à 200 après fusion) |
| Kills | Événement Fabric | `ServerLivingEntityEvents.AFTER_DEATH` ← `LivingEntity.die` | moyenne à forte (20 à 300) |
| Mort du joueur et cause | Événement Fabric + mixin | `AFTER_DEATH` ← `ServerPlayer.die` ; `ServerPlayer.die` HEAD | rare (0 à 5) |
| Nourriture et potions | Mixin sur déclencheur | `ConsumeItemTrigger.trigger` ← `Consumable.onConsume` | moyenne (10 à 40) |
| Artisanat (y compris Maj+clic) | Mixin | `ItemStack.onCraftedBy` ← `ResultSlot` | moyenne (20 à 150 après fusion) |
| Four, haut fourneau, fumoir | Mixin | `ItemStack.onCraftedBy` ← `FurnaceResultSlot` | moyenne |
| Forge, tailleur de pierre | Mixin | `ItemStack.onCraftedBy` ← `SmithingMenu.onTake`, `StonecutterMenu$2.onTake` | rare |
| Enclume | Mixin | `AnvilMenu.onTake` | rare |
| Meule | Mixin (classe anonyme) | `GrindstoneMenu$4.onTake` | très rare |
| Table d'enchantement | Mixin sur déclencheur | `EnchantedItemTrigger.trigger` | rare |
| Échange avec un villageois | Mixin | `AbstractVillager.notifyTrade` | 0 à moyenne |
| Ouverture de conteneur | Mixin + `UseBlockCallback` | `ServerPlayer.openMenu` (RETURN) | moyenne à forte (20 à 150) |
| Objets ramassés | Mixin (hook de stats) | `ServerPlayer.awardStat` (`ITEM_PICKED_UP`) ← `ItemEntity.playerTouch` | très forte brute, moyenne agrégée (60 à 120) |
| Objets jetés | Mixin (hook de stats) | `ServerPlayer.awardStat` (`ITEM_DROPPED`) ← `ServerPlayer.drop` | rare à moyenne (5 à 50) |
| Progrès | Mixin | `PlayerAdvancements.award` → `AdvancementRewards.grant` | rare (0 à 10) |
| Changement de dimension | Événement Fabric | `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL` ← `ServerPlayer.teleport` | rare |
| Sortie de l'End | Événement Fabric | `ServerPlayerEvents.AFTER_RESPAWN` (`alive = true`) | très rare |
| Portail de l'End activé | Mixin | `EnderEyeItem.useOn` → `Level.globalLevelEvent` | très rare |
| Sommeil (début et fin) | Événement Fabric | `EntitySleepEvents.START_SLEEPING` et `STOP_SLEEPING` | rare |
| Point de réapparition | Mixin | `ServerPlayer.setRespawnPosition` | rare |
| Chat | Événement Fabric | `ServerMessageEvents.CHAT_MESSAGE` ← `PlayerList.broadcastChatMessage` | moyenne (10 à 100) |
| Commandes | Mixin | `ServerGamePacketListenerImpl.performUnsignedChatCommand` et `performSignedChatCommand` | 0 à moyenne |
| Connexion | Événement Fabric | `ServerPlayerEvents.JOIN` ← `PlayerList.placeNewPlayer` | très rare |
| Déconnexion | Événement Fabric | `ServerPlayerEvents.LEAVE` ← `PlayerList.remove` | très rare |
| Réapparition | Événement Fabric | `ServerPlayerEvents.AFTER_RESPAWN` ← `PlayerList.respawn` | rare |
| Pêche | Mixin sur déclencheur | `FishingRodHookedTrigger.trigger` ← `FishingHook.retrieve` | 0 à moyenne |
| Élevage | Mixin sur déclencheur | `BredAnimalsTrigger.trigger` ← `Animal.finalizeSpawnChildFromBreeding` | 0 à moyenne |
| Apprivoisement | Mixin sur déclencheur | `TameAnimalTrigger.trigger` ← `TamableAnimal.tame`, `AbstractHorse.tameWithName` | très rare |
| Projectiles qui touchent | Mixin | `Projectile.hitTargetOrDeflectSelf` → `onHit` | 0 à forte (archers) |
| Explosions | Mixin | `ServerExplosion.explode` (RETURN) et `interactWithBlocks` (HEAD) | 0 à moyenne |
| Montures et véhicules | Mixin | `ServerPlayer.startRiding` (RETURN) et `removeVehicle` (HEAD) | 0 à moyenne |
| Déplacements | Sondage | `ServerTickEvents.END_SERVER_TICK`, un examen par seconde | forte (150 à 600 lignes, plafond 1 200) |
| Biome, structure, sous terre | Dérivés du sondage | `getBiome`, `StructureManager.getStructureWithPieceAt`, `canSeeSky` | rare à moyenne |
| Interactions diverses | Mixin (hook de stats) | `ServerPlayer.awardStat`, stats personnalisées filtrées | moyenne (50 à 200) |
| Totaux vanilla | Lecture périodique | `ServerStatsCounter` via l'accesseur `StatsCounter.stats`, toutes les 5 min et à `LEAVE` | environ 12 instantanés par heure, delta seulement |
| Index des blocs posés | Pièce jointe de tronçon | `AttachmentRegistry.create` + `getAttached`, `setAttached` et `ChunkAccess.markUnsaved` | suit « bloc posé » et « bloc cassé », sans mémoire globale |

**Total** : environ 2 000 à 8 000 lignes par heure et par joueur actif. Pour 5 joueurs pendant 3 h par
jour, cela fait 30 000 à 120 000 lignes par jour, cohérent avec « quelques centaines de milliers de
lignes par jour » (§4.2 de l'architecture).
