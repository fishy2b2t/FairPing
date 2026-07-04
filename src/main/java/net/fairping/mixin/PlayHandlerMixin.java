package net.fairping.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.fairping.bridge.FairPingConnectionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class PlayHandlerMixin {

    @Shadow
    public abstract ClientConnection getConnection();

    @Inject(method = "onGameJoin", at = @At("TAIL"), require = 0)
    private void fairping$onJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        ClientConnection conn = getConnection();
        if (conn instanceof FairPingConnectionBridge bridge)
            bridge.fairping$onPlayPhaseEntered();
    }
}
