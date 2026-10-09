package ac.cult.cultac.protocol.packet.clientbound;

import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import java.util.Objects;

public record ClientboundRespawn(PlayerSpawnInfo spawnInfo, byte dataToKeep) implements ClientboundPacket {
    // ClientboundRespawnPacket#KEEP_ATTRIBUTE_MODIFIERS / KEEP_ENTITY_DATA
    public static final byte KEEP_ATTRIBUTE_MODIFIERS = 1;
    public static final byte KEEP_ENTITY_DATA = 2;

    public ClientboundRespawn {
        Objects.requireNonNull(spawnInfo);
    }

    public boolean shouldKeep(byte mask) {
        return (dataToKeep & mask) != 0;
    }
}
