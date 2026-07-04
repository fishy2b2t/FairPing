package net.fairping.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.fairping.FairPingState;

public class FairPingClient implements ClientModInitializer {

    private static boolean chatAnnounce = true;

    @Override
    public void onInitializeClient() {

        ClientTickEvents.END_CLIENT_TICK.register(client ->
            FairPingState.getInstance().tick(client)
        );

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            FairPingState.getInstance().setOff()
        );

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            LiteralCommandNode<FabricClientCommandSource> root = dispatcher.register(
                ClientCommandManager.literal("fairping")

                    .then(ClientCommandManager.literal("add")
                        .then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(0))
                            .executes(ctx -> {
                                int ms = IntegerArgumentType.getInteger(ctx, "ms");
                                FairPingState state = FairPingState.getInstance();
                                if (ms == 0) {
                                    if (state.isOff()) { local("FairPing already off."); return 0; }
                                    state.setOff();
                                    respond("FairPing disabled.");
                                } else {
                                    if (state.isAdd(ms)) { local("Already adding " + ms + "ms."); return 0; }
                                    state.setAdd(ms);
                                    respond("Adding " + ms + "ms delay.");
                                }
                                return 1;
                            })
                        )
                    )

                    .then(ClientCommandManager.literal("total")
                        .then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(0))
                            .executes(ctx -> {
                                int ms = IntegerArgumentType.getInteger(ctx, "ms");
                                FairPingState state = FairPingState.getInstance();
                                if (ms == 0) {
                                    if (state.isOff()) { local("FairPing already off."); return 0; }
                                    state.setOff();
                                    respond("FairPing disabled.");
                                } else {
                                    if (state.isTotal(ms)) { local("Already targeting " + ms + "ms total."); return 0; }
                                    state.setTotal(ms);
                                    respond("Targeting " + ms + "ms total.");
                                }
                                return 1;
                            })
                        )
                    )

                    .then(ClientCommandManager.literal("off")
                        .executes(ctx -> {
                            FairPingState state = FairPingState.getInstance();
                            if (state.isOff()) { local("FairPing already off."); return 0; }
                            state.setOff();
                            respond("FairPing disabled.");
                            return 1;
                        })
                    )

                    .then(ClientCommandManager.literal("status")
                        .executes(ctx -> {
                            local(FairPingState.getInstance().statusMessage());
                            return 1;
                        })
                    )

                    .then(ClientCommandManager.literal("chat")
                        .then(ClientCommandManager.literal("on")
                            .executes(ctx -> {
                                chatAnnounce = true;
                                local("FairPing: chat announce on.");
                                return 1;
                            })
                        )
                        .then(ClientCommandManager.literal("off")
                            .executes(ctx -> {
                                chatAnnounce = false;
                                local("FairPing: chat announce off.");
                                return 1;
                            })
                        )
                    )
            );

            dispatcher.register(ClientCommandManager.literal("fp").redirect(root));
        });
    }

    private static void respond(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        if (chatAnnounce && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage("[FairPing] " + msg);
        } else if (client.player != null) {
            client.player.sendMessage(Text.literal("[FairPing] " + msg), false);
        }
    }

    private static void local(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null)
            client.player.sendMessage(Text.literal(msg), false);
    }
}
