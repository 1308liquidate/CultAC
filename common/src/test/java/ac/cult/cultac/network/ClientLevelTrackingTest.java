package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The client's level follows the packets placed in its stream, whatever the client's packet numbering. */
class ClientLevelTrackingTest {
    /** A proxy client older than the model: its frame IDs are not the dispatcher's. */
    private static final ProtocolVersion CLIENT = ProtocolVersion.V1_21_11;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aProxyServerSwitchEndsWithALevelAndAuthoredPlayPacketsAreSent(boolean tracked) {
        try (var fixture = RecordReceiveFixture.olderHost(CLIENT, tracked)) {
            var logins = new ArrayList<ClientboundLogin>();
            fixture.registrar.register(batch -> batch.sendConnection(
                    ClientboundPackets.LOGIN, PacketListenerPriority.NORMAL, (event, packet) -> logins.add(packet)));
            assertTrue(fixture.connection.clientHasLevel());

            send(fixture, frame(fixture, ConnectionPhase.PLAY, "start_configuration"));
            assertEquals(ConnectionPhase.CONFIGURATION, fixture.connection.phase(PacketDirection.CLIENTBOUND));
            assertFalse(fixture.connection.clientHasLevel());
            assertFalse(authored(fixture), "configuration drops play packets");

            send(fixture, frame(fixture, ConnectionPhase.CONFIGURATION, "finish_configuration"));
            assertEquals(ConnectionPhase.PLAY, fixture.connection.phase(PacketDirection.CLIENTBOUND));
            assertFalse(fixture.connection.clientHasLevel(), "no level before the play login");
            assertFalse(authored(fixture));

            var login =
                    frame(fixture, ConnectionPhase.PLAY, "login").writeInt(7).writeBoolean(false);
            Wire.writeVarInt(login, 1);
            Wire.writeString(login, "minecraft:overworld", 32767);
            Wire.writeVarInt(login, 100);
            Wire.writeVarInt(login, 10);
            Wire.writeVarInt(login, 10);
            login.writeBoolean(false).writeBoolean(true).writeBoolean(false);
            send(fixture, spawnInfo(login, "minecraft:overworld"));
            assertEquals("minecraft:overworld", logins.getFirst().spawnInfo().dimension());
            assertTrue(fixture.connection.clientHasLevel());
            // The proxy resets the tab list after a switch; on this client its ID is the model's start_configuration.
            send(fixture, frame(fixture, ConnectionPhase.PLAY, "tab_list"));
            assertTrue(fixture.connection.clientHasLevel());
            assertTrue(authored(fixture), "an authored play packet reaches the client");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:overworld", "minecraft:the_nether"})
    void aRespawnKeepsTheLevel(String dimension) {
        try (var fixture = RecordReceiveFixture.olderHost(CLIENT)) {
            var respawns = new ArrayList<ClientboundRespawn>();
            fixture.registrar.register(batch -> batch.sendConnection(
                    ClientboundPackets.RESPAWN,
                    PacketListenerPriority.NORMAL,
                    (event, packet) -> respawns.add(packet)));
            assertTrue(fixture.connection.clientHasLevel());

            send(
                    fixture,
                    spawnInfo(frame(fixture, ConnectionPhase.PLAY, "respawn"), dimension)
                            .writeByte(0));
            assertEquals(dimension, respawns.getFirst().spawnInfo().dimension());
            assertTrue(fixture.connection.clientHasLevel());
            send(fixture, frame(fixture, ConnectionPhase.PLAY, "tab_list"));
            assertTrue(fixture.connection.clientHasLevel());
            assertTrue(authored(fixture));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void configurationDropsAuthoredPlayPackets(boolean tracked) {
        try (var fixture = RecordReceiveFixture.olderHost(CLIENT, tracked)) {
            send(fixture, frame(fixture, ConnectionPhase.PLAY, "start_configuration"));
            send(fixture, frame(fixture, ConnectionPhase.CONFIGURATION, "select_known_packs"));
            assertFalse(fixture.connection.clientHasLevel());
            ChannelFuture write =
                    fixture.connection.write(new CultWrite(new ClientboundEntityMotion(1, new Vec3d(0, 0, 0)), false));
            fixture.channel.runPendingTasks();
            assertFalse(write.isSuccess());
            assertEquals(0, drain(fixture));
        }
    }

    private static ByteBuf frame(RecordReceiveFixture fixture, ConnectionPhase phase, String name) {
        int id = fixture.data.packets(phase, PacketDirection.CLIENTBOUND).id("minecraft:" + name);
        assertTrue(id >= 0, name);
        ByteBuf frame = Unpooled.buffer();
        Wire.writeVarInt(frame, id);
        return frame;
    }

    /** CommonPlayerSpawnInfo as this client reads it, in {@code dimension} of the overworld's type. */
    private static ByteBuf spawnInfo(ByteBuf frame, String dimension) {
        Wire.writeVarInt(frame, 0);
        Wire.writeString(frame, dimension, 32767);
        frame.writeLong(0).writeByte(0).writeByte(-1).writeBoolean(false).writeBoolean(false);
        frame.writeBoolean(false);
        Wire.writeVarInt(frame, 0);
        Wire.writeVarInt(frame, 63);
        return frame;
    }

    private static void send(RecordReceiveFixture fixture, ByteBuf frame) {
        assertTrue(fixture.channel.writeAndFlush(frame).isSuccess());
        assertEquals(1, drain(fixture));
    }

    private static boolean authored(RecordReceiveFixture fixture) {
        ChannelFuture write =
                fixture.connection.write(new CultWrite(new ClientboundEntityMotion(1, new Vec3d(0, 0, 0)), false));
        fixture.channel.runPendingTasks();
        return write.isSuccess() && drain(fixture) == 1;
    }

    private static int drain(RecordReceiveFixture fixture) {
        int frames = 0;
        Object written;
        while ((written = fixture.channel.readOutbound()) != null) {
            ReferenceCountUtil.release(written);
            frames++;
        }
        return frames;
    }
}
