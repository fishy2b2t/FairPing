package net.fairping.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.text.Text;
import net.fairping.FairPingState;
import net.fairping.bridge.FairPingConnectionBridge;
import net.fairping.handler.PacketDelayHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ConnectionMixin implements FairPingConnectionBridge {

    @Shadow private Channel channel;
    @Shadow @Final private NetworkSide side;

    @Unique private PacketDelayHandler fairping$handler;
    @Unique private int    fairping$ticks      = 0;
    @Unique private boolean fairping$inPlay    = false;
    @Unique private boolean fairping$reconfig  = false;
    @Unique private NetworkPhase fairping$lastPhase = null;

    @Unique
    private boolean fairping$clientbound() {
        return side == NetworkSide.CLIENTBOUND;
    }

    @Inject(method = "addFlowControlHandler", at = @At("RETURN"), require = 0)
    private void fairping$flowControl(ChannelPipeline pipeline, CallbackInfo ci) {
        if (!fairping$clientbound()) return;
        try { fairping$install(pipeline); } catch (Exception ignored) {}
    }

    @Inject(method = "channelActive", at = @At("TAIL"), require = 0)
    private void fairping$active(io.netty.channel.ChannelHandlerContext ctx, CallbackInfo ci) {
        if (!fairping$clientbound()) return;
        try { fairping$install(ctx.pipeline()); } catch (Exception ignored) {}
    }

    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), require = 0)
    private void fairping$disconnect(Text reason, CallbackInfo ci) {
        if (!fairping$clientbound()) return;
        FairPingState.getInstance().setOff();
        fairping$inPlay = false;
        if (fairping$handler != null) fairping$handler.setEnabled(false);
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void fairping$tick(CallbackInfo ci) {
        if (!fairping$clientbound() || channel == null || !channel.isOpen()) return;
        if (fairping$ticks++ % 20 != 0) return;
        ChannelPipeline pl = channel.pipeline();
        if (pl.get(PacketDelayHandler.NAME) == null) {
            try { fairping$install(pl); } catch (Exception ignored) {}
        }
    }

    @Inject(method = "transitionInbound", at = @At("HEAD"), require = 0)
    private void fairping$transitionIn(NetworkState<?> state, PacketListener listener, CallbackInfo ci) {
        if (fairping$clientbound()) fairping$phase(state.id());
    }

    @Inject(method = "transitionOutbound", at = @At("HEAD"), require = 0)
    private void fairping$transitionOut(NetworkState<?> state, CallbackInfo ci) {
        if (fairping$clientbound()) fairping$phase(state.id());
    }

    @Unique
    private void fairping$phase(NetworkPhase phase) {
        if (phase == NetworkPhase.PLAY) {
            fairping$enterPlay(!fairping$reconfig);
            fairping$reconfig = false;
        } else {
            if (phase == NetworkPhase.CONFIGURATION && fairping$lastPhase == NetworkPhase.PLAY)
                fairping$reconfig = true;
            fairping$leavePlay(phase != NetworkPhase.CONFIGURATION);
        }
        fairping$lastPhase = phase;
    }

    @Override
    public void fairping$onPlayPhaseEntered() {
        if (fairping$clientbound()) fairping$enterPlay(true);
    }

    @Unique
    private void fairping$enterPlay(boolean reset) {
        if (reset) {
            fairping$inPlay = true;
            FairPingState.getInstance().onNewPlaySession();
        }
        if (fairping$handler != null) fairping$handler.setEnabled(true);
    }

    @Unique
    private void fairping$leavePlay(boolean suspend) {
        if (fairping$handler != null) fairping$handler.setEnabled(false);
        if (fairping$inPlay && suspend) {
            FairPingState.getInstance().onProtocolChange();
            fairping$inPlay = false;
        }
    }

    @Unique
    private boolean fairping$install(ChannelPipeline pipeline) {
        if (pipeline == null) return false;
        PacketDelayHandler existing = (PacketDelayHandler) pipeline.get(PacketDelayHandler.NAME);
        if (existing != null) { fairping$handler = existing; return false; }
        if (fairping$handler == null) fairping$handler = new PacketDelayHandler();
        String anchor = fairping$anchor(pipeline);
        if (anchor != null) pipeline.addBefore(anchor, PacketDelayHandler.NAME, fairping$handler);
        else pipeline.addLast(PacketDelayHandler.NAME, fairping$handler);
        return true;
    }

    @Unique
    private String fairping$anchor(ChannelPipeline pipeline) {
        io.netty.channel.ChannelHandlerContext ctx = pipeline.context((ChannelHandler)(Object)this);
        if (ctx != null) return ctx.name();
        if (pipeline.get("packet_handler") != null) return "packet_handler";
        return null;
    }
}
