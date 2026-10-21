package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A rider saddles the donkey it sits on. AbstractHorse#getControllingPassenger turns to the rider, so the root becomes
 * Entity#isLocalInstanceAuthoritative and LocalPlayer#tick starts sending its ServerboundMoveVehiclePacket. Entity#commonTick
 * still runs InterpolationHandler#interpolate for the root before LivingEntity#aiStep reaches travelRidden, so the
 * server path it was following keeps moving it until its steps run out.
 */
class SaddleControlSwitchTest {
    private static final int DONKEY = 100;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    /** 600/4096 blocks along x, interpolated over the donkey's three-tick update interval. */
    private static final short STEP = 600;

    private static final double PATH = STEP / 4096.0;

    @Test
    void theRootFinishesTheServerPathItWasFollowing() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            move(f, STEP);
            f.rideTick();
            saddle(f);

            assertFinishesPath(f, 2);
            assertEquals(0, badPackets.violations);
        }
    }

    /** The client ticks once more before it reads the set_equipment; that tick it still rides passively. */
    @Test
    void aTickBeforeTheClientReadsTheSaddleStillInterpolates() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            move(f, STEP);
            f.player.sendTransactionAndGetId();
            f.pongThrough(f.player.lastTransactionSent.get());
            sendSaddle(f);
            f.rideTick();
            f.player.sendTransactionAndGetId();
            f.pongThrough(f.player.lastTransactionSent.get());

            assertFinishesPath(f, 2);
            assertEquals(0, badPackets.violations);
        }
    }

    /** Controlled ticks from interpolation step {@code first} of 3: no ridden input, so the path alone moves it. */
    private static void assertFinishesPath(JavaTickFixture f, int first) {
        for (int step = first; step <= 3; step++) {
            PredictionResult result = f.rideTick(START.add(PATH * step / 3, 0, 0), true);
            assertTrue(exact(result), "step " + step + ": " + describe(result));
        }
        PredictionResult settled = f.rideTick(START.add(PATH, 0, 0), true);
        assertTrue(exact(settled), describe(settled));
    }

    @Test
    void aStandingRootStartsFromWhereItStands() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            saddle(f);

            for (int tick = 0; tick < 3; tick++) {
                PredictionResult result = f.rideTick(START, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
            assertEquals(0, badPackets.violations);
        }
    }

    /** An unsaddled donkey under the rider, mounted through set_passengers, after one passive ridden tick. */
    private static JavaTickFixture ridden() throws Exception {
        var f = new JavaTickFixture();
        f.place(START.add(0, 1, 0), Vec3.ZERO);
        send(
                f,
                ClientboundPackets.ADD_ENTITY,
                new ClientboundAddEntity(DONKEY, "minecraft:donkey", new Vec3d(START.x, START.y, START.z), 0, 0, 0));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.setPassengers(DONKEY, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        f.rideTick();
        return f;
    }

    private static void move(JavaTickFixture f, short x) {
        send(
                f,
                ClientboundPackets.MOVE_ENTITY,
                new ClientboundMoveEntity(
                        DONKEY, new EntityDelta.Linear(x, (short) 0, (short) 0), 0, 0, true, true, false));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void saddle(JavaTickFixture f) {
        sendSaddle(f);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void sendSaddle(JavaTickFixture f) {
        var item = DataTables.defaults().items().stream()
                .filter(definition -> definition.key().equals("minecraft:saddle"))
                .findFirst()
                .orElseThrow();
        var packet = new InventoryPackets.Equipment(
                DONKEY,
                List.of(new InventoryPackets.EquipmentEntry(
                        EquipmentSlot.SADDLE, new SimItemStack(item, 1, Components.EMPTY))));
        send(f, equipmentType(), packet);
    }

    @SuppressWarnings("unchecked")
    private static PacketType<InventoryPackets.Equipment> equipmentType() {
        return (PacketType<InventoryPackets.Equipment>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == InventoryPackets.Equipment.class)
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Check> T enabled(JavaTickFixture f, Class<T> type) {
        T check = f.player.checkManager.getCheck(type);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }

    @SuppressWarnings("unchecked")
    private static <T> void send(JavaTickFixture f, PacketType<T> type, T packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        var replication = f.player.packetEntityReplication;
        Object raw = packet;
        if (raw instanceof ClientboundAddEntity add) {
            replication.onAddEntity((PacketSendEvent<ClientboundAddEntity>) (PacketSendEvent<?>) event, f.player, add);
        } else if (raw instanceof ClientboundMoveEntity move) {
            replication.onMoveEntity(
                    (PacketSendEvent<ClientboundMoveEntity>) (PacketSendEvent<?>) event, f.player, move);
        } else {
            replication.onSetEquipment(
                    (PacketSendEvent<InventoryPackets.Equipment>) (PacketSendEvent<?>) event,
                    f.player,
                    (InventoryPackets.Equipment) raw);
        }
        event.tasksAfter().forEach(Runnable::run);
    }
}
