package com.example.walkietalkie.item;

import com.example.walkietalkie.client.WTClientHooks;
import com.example.walkietalkie.net.payload.StaticStateS2C;
import com.example.walkietalkie.net.payload.ToggleWalkieC2S;
import com.example.walkietalkie.registry.WTComponents;
import com.example.walkietalkie.registry.WTSounds;
import com.example.walkietalkie.voice.RadioState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * One item, three gestures, all derived from how long right-click is held:
 *
 *   • SHIFT + right-click  -> open the config screen (frequency + settings).
 *   • quick tap            -> toggle the radio on/off.
 *   • hold (> threshold)   -> push-to-talk on the current frequency.
 *
 * Plus a fourth, inventory-only gesture, the same way bundles handle clicks in a GUI
 * slot: right-clicking the walkie-talkie while it's sitting in a slot (cursor empty)
 * also toggles power, without needing to hold it first.
 *
 * The "talk" gesture only marks server-side intent + frequency. The actual microphone
 * capture is started client-side by the Plasmo Voice client bridge while the item is
 * being used (see WalkieVoiceClientAddon).
 */
public class WalkieTalkieItem extends Item {

    /** Ticks the button must be held before it counts as "talk" rather than "tap". */
    public static final int HOLD_THRESHOLD = 6; // ~0.3s
    private static final int MAX_USE = 72000;   // effectively "hold forever", like a bow

    public WalkieTalkieItem(Properties properties) {
        super(properties);
    }

    public static int frequencyOf(ItemStack stack) {
        return stack.getOrDefault(WTComponents.FREQUENCY.get(), 0);
    }

    public static boolean isEnabled(ItemStack stack) {
        return stack.getOrDefault(WTComponents.ENABLED.get(), Boolean.FALSE);
    }

    /** Flips power and reports it, shared by the quick-tap gesture, the inventory click,
     *  and the creative-mode toggle payload handler. */
    public static void togglePower(ItemStack stack, ServerPlayer sp) {
        boolean now = !isEnabled(stack);
        stack.set(WTComponents.ENABLED.get(), now);
        RadioState.get(sp.server).refreshListeners(sp.server);
        sp.displayClientMessage(
                Component.translatable(now ? "msg.walkietalkie.on" : "msg.walkietalkie.off")
                        .withStyle(now ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                true);

        // Physical button click -- positional, audible to the player and anyone standing
        // nearby, the same as any other handheld item sound. Volume follows the toggling
        // player's own SFX slider; nearby bystanders just hear "how loud the click is",
        // same as a real device.
        SoundEvent click = (now ? WTSounds.TOGGLE_ON : WTSounds.TOGGLE_OFF).get();
        float volume = RadioState.get(sp.server).sfxVolumeOf(sp.getUUID());
        sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), click, SoundSource.PLAYERS, volume, 1.0F);
    }

    /**
     * PTT key click, sent directly to every player currently listening on {@code frequency}
     * (per {@link RadioState#listenersFor}) via {@link ServerPlayer#playNotifySound}, NOT a
     * positional sound -- the radio has cross-dimensional range, so "can hear it" means "has
     * an enabled walkie tuned to this frequency", not "is standing nearby". Each listener
     * hears it at their own SFX volume preference, not the speaker's.
     */
    private static void notifyFrequency(MinecraftServer server, int frequency, SoundEvent sound) {
        RadioState state = RadioState.get(server);
        for (UUID uuid : state.listenersFor(frequency)) {
            ServerPlayer listener = server.getPlayerList().getPlayer(uuid);
            if (listener != null) {
                listener.playNotifySound(sound, SoundSource.PLAYERS, state.sfxVolumeOf(uuid), 1.0F);
            }
        }
    }

    /**
     * Broadcasts a looping-static start/stop packet to every player listening on
     * {@code frequency}. Each listener starts or stops the analog static sound on
     * their own client via {@link com.example.walkietalkie.client.WTClientSounds}.
     */
    public static void broadcastStaticState(MinecraftServer server, int frequency, boolean active) {
        StaticStateS2C packet = new StaticStateS2C(frequency, active);
        RadioState state = RadioState.get(server);
        for (UUID uuid : state.listenersFor(frequency)) {
            ServerPlayer listener = server.getPlayerList().getPlayer(uuid);
            if (listener != null) {
                PacketDistributor.sendToPlayer(listener, packet);
            }
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // SHIFT -> config screen, do NOT begin "using".
        if (player.isSecondaryUseActive()) {
            if (level.isClientSide) {
                // Reached only on the client; class is never loaded on a dedicated server.
                WTClientHooks.openConfigScreen(hand);
            }
            return InteractionResultHolder.success(stack);
        }

        // Otherwise begin "using"; release decides tap-vs-hold.
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return MAX_USE;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE; // don't raise the item like food/bow
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        int elapsed = getUseDuration(stack, entity) - remainingUseDuration;

        // The instant we cross from "tap" into "talk", record server-side that this
        // player is transmitting + on which frequency. The client bridge independently
        // sees isUsingItem() and starts mic capture.
        if (elapsed == HOLD_THRESHOLD
                && !level.isClientSide
                && entity instanceof ServerPlayer sp
                && isEnabled(stack)) {
            int freq = frequencyOf(stack);
            RadioState.get(sp.server).startTransmitting(sp, freq);
            notifyFrequency(sp.server, freq, WTSounds.TALK_START.get());
            broadcastStaticState(sp.server, freq, true);
        }
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (level.isClientSide || !(entity instanceof ServerPlayer sp)) {
            return;
        }

        int elapsed = getUseDuration(stack, entity) - timeLeft;
        if (elapsed < HOLD_THRESHOLD) {
            togglePower(stack, sp); // QUICK TAP -> toggle power.
        } else {
            // Was talking -> stop.
            int freq = frequencyOf(stack);
            RadioState.get(sp.server).stopTransmitting(sp);
            notifyFrequency(sp.server, freq, WTSounds.TALK_STOP.get());
            broadcastStaticState(sp.server, freq, false);
        }
    }

    /**
     * Bundle-style inventory interaction: right-clicking an empty cursor onto the
     * walkie-talkie while it's sitting in a slot toggles power, the same way an empty
     * cursor right-clicked onto a bundle extracts an item. Returning {@code true} stops
     * the menu from also running the default pickup/swap for this click.
     *
     * Survival container screens call this on both the predicting client and the
     * authoritative server, so gating on ServerPlayer there is enough -- the server's
     * mutation reaches the client via normal slot sync. The creative inventory screen is
     * the odd one out: per NeoForge/vanilla, item click overrides in the creative menu
     * only ever fire client-side, with no matching server call to piggyback on. So for a
     * creative player we mutate locally for instant feedback and explicitly tell the
     * server which of its own inventory slots to flip via {@link ToggleWalkieC2S}.
     */
    @Override
    public boolean overrideOtherStackedOnMe(
            ItemStack stack,
            ItemStack carried,
            Slot slot,
            ClickAction action,
            Player player,
            SlotAccess carriedSlotAccess
    ) {
        if (action != ClickAction.SECONDARY || !carried.isEmpty() || !slot.allowModification(player)) {
            return false;
        }
        if (player instanceof ServerPlayer sp) {
            togglePower(stack, sp);
        } else if (player.isCreative()) {
            // Per the method doc above, creative-menu clicks only reach us on the
            // logical client (never as a ServerPlayer), so sendToServer is safe here.
            stack.set(WTComponents.ENABLED.get(), !isEnabled(stack));
            PacketDistributor.sendToServer(new ToggleWalkieC2S(slot.getContainerSlot()));
        }
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.walkietalkie.frequency", frequencyOf(stack))
                .withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable(isEnabled(stack) ? "tooltip.walkietalkie.on" : "tooltip.walkietalkie.off")
                .withStyle(isEnabled(stack) ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
    }
}
