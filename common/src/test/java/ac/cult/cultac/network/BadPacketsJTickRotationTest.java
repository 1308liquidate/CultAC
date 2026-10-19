package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsJ;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Minecraft#handleKeybinds sends use_item with the rotation the tick's movement then carries. LocalPlayer#sendPosition
 * sends a rotation only when it differs from the one it last sent, so a tick without one keeps that rotation.
 */
class BadPacketsJTickRotationTest {
    private static final int VEHICLE = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final float PITCH = 52.35009765625f;
    // A mouse turning 0.15 degrees each tick.
    private static final float YAW = 98.4052734375f;
    private static final float YAW_AFTER = 98.5552749633789f;

    @Test
    void aMoveTheMountBoundaryCancelsStillSetsItsTicksRotation() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            mountBeforeTheClientSeesIt(f, t);

            t.useItem(YAW_AFTER);
            assertTrue(t.posRot(START, YAW_AFTER).isCancelled(), "the passenger gate let the move through");
            f.tickEnd();
            t.useItem(YAW);
            assertTrue(t.posRot(START, YAW).isCancelled(), "the passenger gate let the move through");
            f.tickEnd();
            assertEquals(0, t.check.getViolations());
        }
    }

    @Test
    void aUseItemAwayFromTheRotationOfACancelledMoveFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            mountBeforeTheClientSeesIt(f, t);

            t.useItem(YAW_AFTER);
            assertTrue(t.posRot(START, YAW).isCancelled(), "the passenger gate let the move through");
            f.tickEnd();
            assertEquals(1, t.check.getViolations());
        }
    }

    @Test
    void useItemsBeforeTheClientHasLoadedHaveNoTickRotation() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            respawnBeforeTheClientLoads(f, t);

            t.useItem(YAW);
            f.tickEnd();
            t.useItem(YAW_AFTER);
            f.tickEnd();
            assertEquals(0, t.check.getViolations());
        }
    }

    @Test
    void theFirstMovingTickAfterLoadingComparesItsUseItem() throws Exception {
        try (var f = new JavaTickFixture()) {
            var t = new Ticks(f);
            respawnBeforeTheClientLoads(f, t);

            t.useItem(YAW);
            f.tickEnd();
            t.useItem(YAW_AFTER);
            t.posRot(START, YAW);
            f.tickEnd();
            assertEquals(1, t.check.getViolations());
        }
    }

    /** The client sent YAW last; then the server's respawn, whose new LocalPlayer has not loaded. */
    private static void respawnBeforeTheClientLoads(JavaTickFixture f, Ticks t) {
        f.place(START, Vec3.ZERO);
        t.posRot(START, YAW);
        f.tickEnd();
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                GameMode.SURVIVAL);
        var packet = new ClientboundRespawn(spawnInfo, (byte) 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertFalse(f.player.packetStateData.playerLoadedIntoLevel, "the respawn did not reach the client");
    }

    /** The client sent YAW last; set_passengers seats it and no ping has returned since. */
    private static void mountBeforeTheClientSeesIt(JavaTickFixture f, Ticks t) {
        f.place(START, Vec3.ZERO);
        t.posRot(START, YAW);
        f.tickEnd();
        f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.ARMOR_STAND, START, 0, 0, 0);
        var packet = new ClientboundSetPassengers(VEHICLE, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        assertTrue(f.player.compensatedEntities.vehicles.hasPlayerPassengerState());
    }

    private static final class Ticks {
        final JavaTickFixture f;
        final BadPacketsJ check;
        int sequence;

        Ticks(JavaTickFixture f) {
            this.f = f;
            check = f.player.checkManager.getListener(BadPacketsJ.class);
        }

        void useItem(float yaw) {
            var packet = new ServerboundUseItem(Hand.MAIN_HAND, ++sequence, yaw, PITCH);
            f.listener.onUseItem(f.event(ServerboundPackets.USE_ITEM, packet), f.player, packet);
        }

        PacketReceiveEvent<ServerboundMovePlayer> posRot(Vec3 to, float yaw) {
            return move(new ServerboundMovePlayer(to.x, to.y, to.z, yaw, PITCH, true, false, true, true));
        }

        private PacketReceiveEvent<ServerboundMovePlayer> move(ServerboundMovePlayer packet) {
            var event = f.event(ServerboundPackets.MOVE_PLAYER, packet);
            f.listener.onMovePlayer(event, f.player, packet);
            return event;
        }
    }
}
