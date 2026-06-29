package com.example.walkietalkie.client;

import com.example.walkietalkie.client.voice.WalkieVoiceClientAddon;
import com.example.walkietalkie.registry.WTSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages the per-frequency looping static SoundInstances that play whenever
 * someone is transmitting on a frequency this player can hear.
 *
 * Only called from the logical-client thread (packet handler enqueueWork or
 * NeoForge client event).
 *
 * NOTE: This class uses Minecraft client classes (SoundManager, SimpleSoundInstance)
 * and must never be loaded on a dedicated server. It is only ever referenced from
 * {@link com.example.walkietalkie.net.WTPayloads#handleStaticState}, which is a
 * {@code playToClient} handler and therefore only invoked on the client.
 */
public final class WTClientSounds {

    /** freq → currently playing looping static instance */
    private static final Map<Integer, SoundInstance> activeSounds = new HashMap<>();

    /**
     * Start or stop the looping static for the given frequency.
     * Safe to call multiple times; starting an already-playing frequency is a no-op,
     * and stopping one that isn't playing is a no-op.
     */
    public static void setStatic(int frequency, boolean active) {
        var sm = Minecraft.getInstance().getSoundManager();
        if (active) {
            if (activeSounds.containsKey(frequency)) return; // already looping

            float volume = WalkieVoiceClientAddon.getSfxVolume();

            // Non-positional looping sound: Attenuation.NONE + relative=true means
            // "plays at full volume regardless of where the transmitter is standing."
            // This is correct for a radio — distance is irrelevant.
            var instance = new SimpleSoundInstance(
                    WTSounds.RADIO_STATIC.get().getLocation(),
                    SoundSource.PLAYERS,
                    volume, 1.0F,
                    RandomSource.create(),
                    true,                           // looping
                    0,                              // delay ticks
                    SoundInstance.Attenuation.NONE,
                    0.0, 0.0, 0.0,
                    true                            // relative to camera
            );
            sm.play(instance);
            activeSounds.put(frequency, instance);
        } else {
            SoundInstance instance = activeSounds.remove(frequency);
            if (instance != null) sm.stop(instance);
        }
    }

    /** Stop every active static loop (called on disconnect / login). */
    public static void stopAll() {
        var sm = Minecraft.getInstance().getSoundManager();
        activeSounds.values().forEach(sm::stop);
        activeSounds.clear();
    }

    private WTClientSounds() {}
}
