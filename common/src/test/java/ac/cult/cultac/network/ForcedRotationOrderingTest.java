package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerRotation;
import ac.cult.cultac.protocol.value.Vec3d;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ForcedRotationOrderingTest {
    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_9", "V1_21_11", "V26_1", "V26_2", "V26_3"})
    void relativeRotationUsesThePrecedingOrderedEcho(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(version)) {
            register(fixture);
            fixture.player.xRot = fixture.player.yRot = 0;
            send(fixture, new ClientboundPlayerRotation(90, false, 30, false));
            send(fixture, new ClientboundPlayerRotation(45, true, 80, true));
            var teleports = fixture.player.getSetbackTeleportUtil();
            assertTrue(teleports.checkRotationTeleportQueue(90, 30).isTeleport());
            fixture.player.xRot = 90;
            fixture.player.yRot = 30;
            var response = teleports.checkRotationTeleportQueue(135, 90);
            assertTrue(response.isTeleport(), "Vanilla applies each relative rotation at receipt, then clamps pitch");
            assertEquals(135, response.getTeleportData().getFinalYaw());
            assertEquals(90, response.getTeleportData().getFinalPitch());
            assertTrue(teleports.pendingTeleports.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_9", "V1_21_11", "V26_1", "V26_2", "V26_3"})
    void relativeRotationUsesMovementReportedBeforeItsEcho(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(version)) {
            register(fixture);
            fixture.player.xRot = fixture.player.yRot = 0;
            send(fixture, new ClientboundPlayerRotation(45, true, -15, true));
            fixture.player.xRot = 30;
            fixture.player.yRot = 20;
            var teleports = fixture.player.getSetbackTeleportUtil();
            assertFalse(teleports.checkRotationTeleportQueue(45, -15).isTeleport());
            assertEquals(1, teleports.pendingTeleports.size());
            var response = teleports.checkRotationTeleportQueue(75, 5);
            assertTrue(response.isTeleport());
            assertEquals(30, response.getTeleportData().getSourceYaw());
            assertEquals(20, response.getTeleportData().getSourcePitch());
        }
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void absoluteRotationStillRequiresItsExactTarget(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(version)) {
            register(fixture);
            send(fixture, new ClientboundPlayerRotation(90, false, 30, false));
            fixture.player.xRot = 45;
            fixture.player.yRot = 15;
            var teleports = fixture.player.getSetbackTeleportUtil();
            assertFalse(teleports.checkRotationTeleportQueue(135, 45).isTeleport());
            assertEquals(1, teleports.pendingTeleports.size());
            assertTrue(teleports.checkRotationTeleportQueue(90, 30).isTeleport());
            assertTrue(teleports.pendingTeleports.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_9", "V1_21_11", "V26_1", "V26_2", "V26_3"})
    void aRotationInAFlushBundleIsProvenByAPingAfterItsEcho(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(version)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            register(fixture);
            var flushProof = new AtomicInteger();
            fixture.registrar.register(routes -> routes.send(
                    ClientboundPackets.ENTITY_MOTION,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> flushProof.set(player.appendFlushProofTransaction(event))));
            // A flush closes the flush bundle; the watchdog's would land between the writes.
            fixture.player.nettyScheduler.removeScheduler();
            fixture.channel.runPendingTasks();
            fixture.channel.write(new CultWrite(new ClientboundEntityMotion(1, new Vec3d(0, 0, 0)), false));
            fixture.channel.write(new CultWrite(new ClientboundPlayerRotation(90, false, 30, false), false));
            assertTrue(fixture.connection.flushBundleOpen());
            var teleports = fixture.player.getSetbackTeleportUtil();
            assertEquals(1, teleports.pendingTeleports.size(), "The rotation was handled");
            // ClientPacketListener#handleRotatePlayer sends Rot after the bundle's earlier ping is answered.
            assertTrue(flushProof.get() > 0, "The motion opens the flush bundle with a ping");
            int rotationProof = teleports.pendingTeleports.peek().getTransaction();
            assertTrue(rotationProof > flushProof.get(), "The rotation needs a ping after its echo");
            assertEquals(fixture.player.lastTransactionSent.get(), rotationProof);
            fixture.channel.flush();
            fixture.channel.releaseOutbound();
        }
    }

    private static void register(RecordReceiveFixture fixture) {
        var listener = new PacketServerTeleport();
        fixture.registrar.register(routes -> routes.send(
                ClientboundPackets.PLAYER_ROTATION, PacketListenerPriority.LOW, listener::onPlayerRotation));
    }

    private static void send(RecordReceiveFixture fixture, ClientboundPlayerRotation packet) {
        assertTrue(fixture.channel.writeOutbound(new CultWrite(packet, false)));
    }
}
