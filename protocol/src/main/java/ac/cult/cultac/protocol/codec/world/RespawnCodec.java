package ac.cult.cultac.protocol.codec.world;

import ac.cult.cultac.protocol.PacketCodec;
import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import io.netty.buffer.ByteBuf;

public final class RespawnCodec implements PacketCodec<ClientboundRespawn> {
    @Override
    public ClientboundRespawn read(ByteBuf input, ProtocolContext context) {
        PlayerSpawnInfo spawnInfo = SpawnInfoCodec.read(input, context.version().atLeast(ProtocolVersion.V26_3));
        return new ClientboundRespawn(spawnInfo, input.readByte());
    }

    @Override
    public boolean readsEntirePayload() {
        return false;
    }
}
