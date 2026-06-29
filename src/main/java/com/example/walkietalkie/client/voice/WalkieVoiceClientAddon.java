package com.example.walkietalkie.client.voice;

import com.example.walkietalkie.client.WTClientSounds;
import com.example.walkietalkie.client.WTClientHooks;
import com.example.walkietalkie.item.WalkieTalkieItem;
import com.example.walkietalkie.net.payload.SfxVolumeC2S;
import com.example.walkietalkie.voice.WalkieVoiceServerAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import su.plo.config.entry.BooleanConfigEntry;
import su.plo.config.entry.DoubleConfigEntry;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.audio.capture.ClientActivation;
import su.plo.voice.api.client.config.addon.AddonConfig;
import su.plo.voice.api.client.event.audio.capture.ClientActivationRegisteredEvent;
import su.plo.voice.api.client.event.audio.capture.ClientActivationUnregisteredEvent;
import su.plo.voice.api.event.EventSubscribe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Client bridge: drives the "walkie_talkie" ClientActivation while the player
 * holds right-click on an enabled walkie-talkie past the hold threshold, and
 * owns the addon's own settings block in PV's Addons menu (the same
 * {@code AddonConfig} mechanism pv-addon-soundphysics and other PV addons use).
 *
 * The activation's PV "type" defaults to Voice (see {@link #setDefaultToVoice}),
 * which means PV's engine will activate it from raw mic volume alone, with no
 * idea whether the radio item is even being held. We are the ones who know
 * that, so we gate it ourselves with {@link ClientActivation#setDisabled(boolean)}
 * -- a real, public, supported API -- flipping it on only while the item is
 * actually being held+used past the hold threshold. Disabled, PV always
 * returns NOT_ACTIVATED no matter how loud you talk.
 */
@Addon(
        id = "wt-addon-client",
        name = "Walkie Talkie (client)",
        version = "1.0.5",
        authors = {"SimpleFox"}
)
public final class WalkieVoiceClientAddon implements AddonInitializer {

    /** Set once during onAddonInitialize; lets WTClientSounds read the slider value. */
    private static WalkieVoiceClientAddon INSTANCE;

    public static float getSfxVolume() {
        WalkieVoiceClientAddon inst = INSTANCE;
        if (inst == null || inst.sfxVolumeEntry == null) return 0.3F;
        return inst.sfxVolumeEntry.value().floatValue();
    }

    @InjectPlasmoVoice
    private PlasmoVoiceClient voiceClient;

    private boolean transmitting = false;
    private ClientActivation walkieActivation;

    private AddonConfig config;
    private BooleanConfigEntry voiceByDefaultEntry;
    private DoubleConfigEntry sfxVolumeEntry;

    @Override
    public void onAddonInitialize() {
        INSTANCE = this;
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);

        // The addon's own block in PV's Addons settings menu. Backed by PV's normal
        // client config (config/plasmovoice/client.toml, "addons" section) -- same
        // mechanism other PV addons (e.g. pv-addon-soundphysics) use for their config.
        this.config = voiceClient.getAddonConfig(this);
        config.clear();
        this.voiceByDefaultEntry = config.addToggle(
                "voice-default",
                McTextComponent.translatable("config.walkietalkie.voice_default"),
                McTextComponent.translatable("config.walkietalkie.voice_default.tooltip"),
                true
        );
        this.sfxVolumeEntry = config.addVolumeSlider(
                "sfx-volume",
                McTextComponent.translatable("config.walkietalkie.sfx_volume"),
                McTextComponent.translatable("config.walkietalkie.sfx_volume.tooltip"),
                "%",
                0.3D, 0.0D, 1.0D
        );
        // 60% default so the toggle/talk clicks don't blast players the moment they pick
        // one up; report it (and any later change) to the server, which is what actually
        // plays these sounds and needs to know how loud to make them.
        sfxVolumeEntry.addChangeListener(volume -> sendSfxVolume());
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        WTClientSounds.stopAll(); // clear any stale loops from a prior session
        sendSfxVolume();
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WTClientSounds.stopAll();
    }

    private void sendSfxVolume() {
        if (sfxVolumeEntry == null) return;
        PacketDistributor.sendToServer(new SfxVolumeC2S(sfxVolumeEntry.value().floatValue()));
    }

    @EventSubscribe
    public void onActivationRegistered(ClientActivationRegisteredEvent event) {
        ClientActivation activation = event.getActivation();
        if (!WalkieVoiceServerAddon.ACTIVATION_NAME.equals(activation.getName())) return;

        this.walkieActivation = activation;
        this.transmitting = false;

        // Safe default: not holding the item yet, so it shouldn't be live yet either.
        // onClientTick re-evaluates every tick and takes over from here.
        activation.setDisabled(true);

        if (voiceByDefaultEntry != null
                && voiceByDefaultEntry.value()
                && activation.getType() != ClientActivation.Type.VOICE) {
            setDefaultToVoice(activation);
        }
    }

    @EventSubscribe
    public void onActivationUnregistered(ClientActivationUnregisteredEvent event) {
        if (event.getActivation() == walkieActivation) {
            this.walkieActivation = null;
        }
    }

    /**
     * PV creates a brand-new per-activation config entry (type = Push-to-Talk) the
     * first time a player ever sees this activation, requiring a separate PTT key on
     * top of holding the item -- which defeats the point of a press-to-talk radio.
     * Nudge it to Voice once, unless the player has since picked something else
     * themselves in PV's own Activation settings.
     *
     * PV doesn't expose a public setter for this (ClientActivation only has a
     * getter) -- it's meant to be the player's own choice. We reach into the
     * private per-activation config entry the same way PV's own code does
     * internally (see VoiceClientActivationManager#register, which does the
     * equivalent for the built-in proximity activation), but via reflection so a
     * PV update that renames things can't break the build.
     */
    private void setDefaultToVoice(ClientActivation activation) {
        try {
            Field field = activation.getClass().getDeclaredField("configType");
            field.setAccessible(true);
            Object configType = field.get(activation);

            Method isDefault = configType.getClass().getMethod("isDefault");
            if (!(boolean) isDefault.invoke(configType)) return; // player already chose something

            for (Method setter : configType.getClass().getMethods()) {
                if (setter.getName().equals("set") && setter.getParameterCount() == 1) {
                    setter.invoke(configType, ClientActivation.Type.VOICE);
                    break;
                }
            }
        } catch (Exception ignored) {
            // PV internals renamed/changed -- players can still flip it to Voice
            // themselves in PV's Activation settings.
        }
    }

    private void onClientTick(ClientTickEvent.Post event) {
        if (walkieActivation == null) return;

        LocalPlayer player = Minecraft.getInstance().player;
        boolean shouldTransmit = player != null && wantsToTransmit(player);

        if (shouldTransmit != transmitting) {
            transmitting = shouldTransmit;
            // Disabled = PV always reports NOT_ACTIVATED, regardless of mic volume.
            // This is what keeps Voice-type detection scoped to "item is held" instead
            // of firing (and showing the activation icon) any time you talk at all.
            walkieActivation.setDisabled(!shouldTransmit);
        }
    }

    private boolean wantsToTransmit(LocalPlayer player) {
        if (!player.isUsingItem()) return false;
        ItemStack using = player.getUseItem();
        if (!(using.getItem() instanceof WalkieTalkieItem)) return false;
        if (!WalkieTalkieItem.isEnabled(using)) return false;
        return player.getTicksUsingItem() >= WalkieTalkieItem.HOLD_THRESHOLD;
    }
}
