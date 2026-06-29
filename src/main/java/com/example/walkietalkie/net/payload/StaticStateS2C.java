package com.example.walkietalkie.net.payload;

import com.example.walkietalkie.WalkieTalkieMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from the server to every listener on a walkie frequency when someone
 * starts ({@code active=true}) or stops ({@code active=false}) transmitting.
 * The client uses this to start/stop the looping analog static sound via
 * {@link com.example.walkietalkie.client.WTClientSounds}.
 */
public record StaticStateS2C(int frequency, boolean active) implements CustomPacketPayload {

    public static final Type<StaticStateS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WalkieTalkieMod.MOD_ID, "static_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StaticStateS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StaticStateS2C::frequency,
                    ByteBufCodecs.BOOL,    StaticStateS2C::active,
                    StaticStateS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
