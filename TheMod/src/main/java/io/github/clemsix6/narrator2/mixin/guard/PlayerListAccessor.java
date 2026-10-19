package io.github.clemsix6.narrator2.mixin.guard;

import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The storage of the players' files, for {@code ItemLedger}: a stake handed over to its winner writes the two
 * players' {@code .dat} files at once, one after the other (M4 §5.6), and nobody else's. The field is private, and
 * {@code PlayerList.save(ServerPlayer)} is protected and writes the statistics and advancements too.
 */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("playerIo")
    PlayerDataStorage narrator2$playerIo();
}
