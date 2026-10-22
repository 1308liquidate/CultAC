package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.EntityPose;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Camel#refuseToMove holds a sitting or transitioning camel in place, and Camel#tickRidden stands it up on the
 * controlling client, which the server may never echo with its own LAST_POSE_CHANGE_TICK.
 */
class JavaCamelPoseTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final int CAMEL = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    /** Camel#isInPoseTransition: standing up takes 52 ticks. */
    private static final int STAND_UP_TICKS = 52;

    @Test
    void aSittingCamelDoesNotMoveOnItsRidersInput() throws Exception {
        try (var f = sitting()) {
            f.input(FORWARD);
            // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
            assertTrue(exact(f.rideTick(SEAT, true)));
            for (int tick = 0; tick < STAND_UP_TICKS; tick++) {
                PredictionResult still = f.rideTick(SEAT, true);
                assertTrue(exact(still), "tick " + tick + ": " + describe(still));
            }
        }
    }

    @Test
    void theRidersClientStandsTheCamelUpAndThenItMoves() throws Exception {
        try (var f = sitting()) {
            PacketEntityCamel camel = camel(f);
            f.input(FORWARD);
            assertTrue(exact(f.rideTick(SEAT, true)));
            long standUpTime = f.player.compensatedWorld.clientGameTime();
            assertTrue(exact(f.rideTick(SEAT, true)));

            assertFalse(camel.isCamelSitting());
            assertEquals(EntityPose.STANDING, camel.actionPose);
            assertEquals(standUpTime, camel.lastPoseChangeTick);

            while (camel.refuseToMove(f.player.compensatedWorld.clientGameTime())) {
                assertTrue(exact(f.rideTick(SEAT, true)));
            }
            PredictionResult still = f.rideTick(SEAT, true);
            assertFalse(exact(still), describe(still));
        }
    }

    @Test
    void aStandingCamelMovesOnItsRidersInput() throws Exception {
        try (var f = ridden()) {
            f.input(FORWARD);
            assertTrue(exact(f.rideTick(SEAT, true)));
            PredictionResult still = f.rideTick(SEAT, true);
            assertFalse(exact(still), describe(still));
        }
    }

    /** A saddled camel that sat down long enough ago to have finished its 40-tick sitting transition. */
    private static JavaTickFixture sitting() throws Exception {
        JavaTickFixture f = ridden();
        long sat = f.player.compensatedWorld.clientGameTime();
        var layout = WatchableIndexUtil.forModel(f.player.user.getCultConnection());
        setEntityData(
                f,
                CAMEL,
                List.of(
                        entry(WatchableIndexUtil.ENTITY_POSE, EntityPose.SITTING),
                        // Camel defines LAST_POSE_CHANGE_TICK directly after DASH; negative while sitting.
                        entry(layout.camelDash() + 1, -(sat + 1))));
        PacketEntityCamel camel = camel(f);
        assertTrue(camel.isCamelSitting());
        while (camel.isInPoseTransition(f.player.compensatedWorld.clientGameTime())) {
            assertTrue(exact(f.rideTick(SEAT, true)));
        }
        return f;
    }

    /** A saddled camel at {@link #SEAT} mounted through set_passengers, after one stationary ridden tick. */
    private static JavaTickFixture ridden() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        // A level well past the 52 ticks in which a camel spawned with LAST_POSE_CHANGE_TICK 0 is still standing up.
        f.player.compensatedWorld.applyClientTime(new WorldPackets.TimeUpdate(24_000L, Map.of()));
        f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, SEAT, 0, 0, 0);
        camel(f).hasSaddle = true;
        var packet = new ClientboundSetPassengers(CAMEL, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(exact(f.rideTick(SEAT, true)));
        return f;
    }

    private static PacketEntityCamel camel(JavaTickFixture f) {
        return (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
    }

    private static EntityMetadata.Entry entry(int index, Object value) {
        return new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0));
    }

    private static void setEntityData(JavaTickFixture f, int entityId, List<EntityMetadata.Entry> entries) {
        var packet = new EntityMetadata(entityId, entries);
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        f.player.packetEntityReplication.onSetEntityData(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
