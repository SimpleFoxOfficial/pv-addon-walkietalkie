package com.example.walkietalkie.net;

import com.example.walkietalkie.client.WTClientSounds;
import com.example.walkietalkie.item.WalkieTalkieItem;
import com.example.walkietalkie.net.payload.ConfigureWalkieC2S;
import com.example.walkietalkie.net.payload.SfxVolumeC2S;
import com.example.walkietalkie.net.payload.StaticStateS2C;
import com.example.walkietalkie.net.payload.ToggleWalkieC2S;
import com.example.walkietalkie.registry.WTComponents;
import com.example.walkietalkie.voice.RadioState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class WTPayloads {

    public static void register(RegisterPayloadHandlersEvent event) {
        // Bump the version string if you change a payload's wire format.
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                ConfigureWalkieC2S.TYPE,
                ConfigureWalkieC2S.STREAM_CODEC,
                WTPayloads::handleConfigure);
        registrar.playToServer(
                ToggleWalkieC2S.TYPE,
                ToggleWalkieC2S.STREAM_CODEC,
                WTPayloads::handleToggle);
        registrar.playToServer(
                SfxVolumeC2S.TYPE,
                SfxVolumeC2S.STREAM_CODEC,
                WTPayloads::handleSfxVolume);
        // S2C: tell clients to start/stop the looping static on a frequency.
        // Handler calls WTClientSounds which uses Minecraft client classes — it is
        // only ever *invoked* on the client (playToClient), so the class is only
        // loaded on the client despite the reference living in this common class.
        registrar.playToClient(
                StaticStateS2C.TYPE,
                StaticStateS2C.STREAM_CODEC,
                WTPayloads::handleStaticState);
    }

    private static void handleConfigure(ConfigureWalkieC2S payload, IPayloadContext ctx) {
        // Handlers run off-thread; hop back onto the server thread before touching state.
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            ItemStack stack = sp.getItemInHand(payload.hand());
            if (!(stack.getItem() instanceof WalkieTalkieItem)) return;

            // Clamp to whatever range you decide to expose in the GUI.
            int freq = Math.max(0, Math.min(9999, payload.frequency()));
            stack.set(WTComponents.FREQUENCY.get(), freq);
            stack.set(WTComponents.ENABLED.get(), payload.enabled());

            RadioState.get(sp.server).refreshListeners(sp.server);
        });
    }

    /**
     * Creative-menu counterpart to the survival inventory-click toggle (see
     * {@code WalkieTalkieItem#overrideOtherStackedOnMe}). The creative screen only ever
     * invokes Item click overrides client-side, so the client tells us directly which of
     * its own inventory slots to flip instead of us seeing it through the normal click path.
     */
    private static void handleToggle(ToggleWalkieC2S payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!sp.isCreative()) return; // survival already goes through the authoritative click path

            int slot = payload.containerSlot();
            if (slot < 0 || slot >= sp.getInventory().getContainerSize()) return;

            ItemStack stack = sp.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof WalkieTalkieItem)) return;

            WalkieTalkieItem.togglePower(stack, sp);
        });
    }

    private static void handleSfxVolume(SfxVolumeC2S payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            RadioState.get(sp.server).setSfxVolume(sp.getUUID(), payload.volume());
        });
    }

    /** Only ever called on the logical client — WTClientSounds is never loaded server-side. */
    private static void handleStaticState(StaticStateS2C payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> WTClientSounds.setStatic(payload.frequency(), payload.active()));
    }

    private WTPayloads() {}
}
