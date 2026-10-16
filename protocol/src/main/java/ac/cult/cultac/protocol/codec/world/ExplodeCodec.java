/*
 * Layout adapted from PacketEvents WrapperPlayServerExplosion,
 * revision 5da85d7ad888f69732e0726aad8dde8f0e0ef4c1.
 * Copyright (C) 2022 retrooper and contributors; GPL-3.0-or-later.
 * https://github.com/retrooper/packetevents
 */
package ac.cult.cultac.protocol.codec.world;

import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundExplode;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

public final class ExplodeCodec implements WritablePacketCodec<ClientboundExplode> {
    @Override
    public ClientboundExplode read(ByteBuf input, ProtocolContext context) {
        Vec3d center = Wire.readVec3(input);
        if (context.version().atLeast(ProtocolVersion.V1_21_9)) {
            input.readFloat(); // Radius, introduced in V1_21_9.
            input.readInt(); // Block count.
        }
        Vec3d knockback = input.readBoolean() ? Wire.readVec3(input) : Vec3d.ZERO;
        return new ClientboundExplode(center, knockback);
    }

    /** Authored explosions carry vanilla TNT effects; nothing beyond knockback affects movement. */
    @Override
    public void write(ByteBuf output, ProtocolContext context, ClientboundExplode packet) {
        boolean blockEffects = context.version().atLeast(ProtocolVersion.V1_21_9);
        Wire.writeVec3(output, packet.center());
        if (blockEffects) output.writeFloat(4).writeInt(0);
        boolean knockback = !packet.knockback().equals(Vec3d.ZERO);
        output.writeBoolean(knockback);
        if (knockback) Wire.writeVec3(output, packet.knockback());
        // ParticleTypes.STREAM_CODEC: minecraft:explosion is a simple particle without options.
        Wire.writeVarInt(
                output,
                ModelRegistryData.load(context.version())
                        .registry("minecraft:particle_type")
                        .id("minecraft:explosion"));
        // SoundEvent.STREAM_CODEC holder: 0 selects a direct sound, then an absent fixed range.
        Wire.writeVarInt(output, 0);
        Wire.writeString(output, "minecraft:entity.generic.explode", 32767);
        output.writeBoolean(false);
        if (blockEffects) Wire.writeVarInt(output, 0); // Empty block particle WeightedList.
        if (context.version().atLeast(ProtocolVersion.V26_3)) output.writeBoolean(true); // playSound
    }

    // The unconsumed suffix remains in the original forwarded packet.
    @Override
    public boolean readsEntirePayload() {
        return false;
    }
}
