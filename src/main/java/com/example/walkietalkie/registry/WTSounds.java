package com.example.walkietalkie.registry;

import com.example.walkietalkie.WalkieTalkieMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Sound events for the walkie-talkie. Actual files + variants are wired up in
 * {@code assets/walkietalkie/sounds.json}, currently all pointing at the "style2mtp"
 * sound pack:
 *
 *   • TOGGLE_ON  / TOGGLE_OFF -> power button click, played once per toggle.
 *   • TALK_START / TALK_STOP  -> PTT key click, played to everyone listening on the
 *                                 same frequency every time someone keys up/down.
 */
public final class WTSounds {

    public static final DeferredRegister<SoundEvent> REGISTER =
            DeferredRegister.create(Registries.SOUND_EVENT, WalkieTalkieMod.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> TOGGLE_ON = register("toggle_on");
    public static final DeferredHolder<SoundEvent, SoundEvent> TOGGLE_OFF = register("toggle_off");
    public static final DeferredHolder<SoundEvent, SoundEvent> TALK_START = register("talk_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> TALK_STOP = register("talk_stop");
    /** Looping analog static — plays to all frequency listeners while someone is transmitting. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RADIO_STATIC = register("radio_static");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(WalkieTalkieMod.MOD_ID, name);
        return REGISTER.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }

    private WTSounds() {}
}
