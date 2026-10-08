package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InventoryCorrectionEncodingTest {
    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void cancelledPlacementCanRestoreEitherHandOnEveryWire(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = RecordReceiveFixture.olderHost(version)) {
            for (int slot : new int[] {36, 44, 45}) {
                var item = OfflineCultTestBootstrap.item("minecraft:diamond_pickaxe", 1);
                item.setComponent(
                        "minecraft:damage",
                        new ac.cult.blocksim.data.nbt.NbtValue.Numeric(
                                ac.cult.blocksim.data.nbt.NbtValue.Kind.INT, 123));
                var correction = new InventoryPackets.Slot(0, 257, slot, item);
                var type = fixture.connection.packets().writableType(correction);
                var encoded = fixture.connection
                        .packets()
                        .encode(
                                ConnectionPhase.PLAY,
                                type,
                                correction,
                                UnpooledByteBufAllocator.DEFAULT,
                                fixture.connection);
                assertEquals(1, encoded.frames().size());
                var frame = encoded.frames().getFirst();
                try {
                    var header = frame.duplicate();
                    assertEquals(
                            fixture.data
                                    .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                                    .id("minecraft:container_set_slot"),
                            Wire.readVarInt(header));
                    assertEquals(0, Wire.readVarInt(header));
                    assertEquals(257, Wire.readVarInt(header));
                    assertEquals(slot, header.readShort());
                    var decoded = fixture.connection
                            .packets()
                            .read(
                                    ConnectionPhase.PLAY,
                                    PacketDirection.CLIENTBOUND,
                                    frame,
                                    fixture.connection,
                                    ignored -> true);
                    var received = (InventoryPackets.Slot) decoded.getFirst().packet();
                    assertEquals(correction.windowId(), received.windowId());
                    assertEquals(correction.stateId(), received.stateId());
                    assertEquals(correction.slot(), received.slot());
                    assertEquals(item.itemKey(), received.item().itemKey());
                    assertEquals(item.count(), received.item().count());
                    assertEquals(123, received.item().components().integer("minecraft:damage", 0));
                } finally {
                    frame.release();
                }
            }
        }
    }
}
