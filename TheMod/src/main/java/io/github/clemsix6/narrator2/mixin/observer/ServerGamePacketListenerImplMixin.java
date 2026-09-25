package io.github.clemsix6.narrator2.mixin.observer;

import io.github.clemsix6.narrator2.observer.ObserverHooks;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Commands typed by players (OBSERVER_API.md §3.14), unsigned or signed, on the server thread. The
 * console, command blocks, functions and the Narrator's own commands never come through here. The text
 * arrives without its {@code /}.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "performUnsignedChatCommand(Ljava/lang/String;)V", at = @At("HEAD"))
    private void narrator2$onUnsignedCommand(String command, CallbackInfo ci) {
        ObserverHooks.onCommand(player, command);
    }

    @Inject(
        method = "performSignedChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandSignedPacket;Lnet/minecraft/network/chat/LastSeenMessages;)V",
        at = @At("HEAD")
    )
    private void narrator2$onSignedCommand(ServerboundChatCommandSignedPacket packet, LastSeenMessages lastSeen, CallbackInfo ci) {
        ObserverHooks.onCommand(player, packet.command());
    }
}
