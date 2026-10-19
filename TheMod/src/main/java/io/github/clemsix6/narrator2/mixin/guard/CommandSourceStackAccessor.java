package io.github.clemsix6.narrator2.mixin.guard;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Who sent a command in the first place: the player who typed it, a command block, the console. {@code execute as}
 * changes the stack's entity, never this. {@code /bet}, {@code /offer} and {@code /duel} act only for the player who
 * sends them themselves ({@code command/OwnPlayer.kt}). The field is private, without a getter in 26.3.
 */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {
    @Accessor("source")
    CommandSource narrator2$source();
}
