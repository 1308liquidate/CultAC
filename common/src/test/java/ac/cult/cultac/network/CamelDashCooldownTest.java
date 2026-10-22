package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.WorldPackets.TimeUpdate;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ClientLevel#tickEntities runs Camel#tick for every camel, so a camel's 55-tick dash cooldown runs down while nobody
 * rides it (CultAC issue #96, fourth capture). Camel#getRiddenSpeed adds the sprint bonus once the cooldown is zero.
 */
class CamelDashCooldownTest {
    private static final int CAMEL = 124;
    private static final long GAME_TIME = 1090559L;
    /** Camel DASH and LAST_POSE_CHANGE_TICK in the 26.3 metadata layout. */
    private static final int DASH = 19;
    private static final int LAST_POSE_CHANGE_TICK = 20;
    /** Camel#executeRidersJump and Camel#onSyncedDataUpdated's dash cooldown. */
    private static final int DASH_COOLDOWN = 55;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 ON_FOOT = new Vec3(4.5, FLOOR_Y, 0.5);
    /** Camel#getRiddenSpeed: movement_speed 0.09 plus 0.1 for a sprinting controller with no dash cooldown. */
    private static final float SPEED = 0.09F + 0.1F;
    /** LivingEntity#travelInAir: a grounded camel's (0 - 0.08) * 0.98F. */
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#moveRelative for the ridden input's 0.98F on 0.6-friction ground. */
    private static final double GROUND_INPUT = (double) 0.98F * (SPEED * (0.21600002F / (0.6F * 0.6F * 0.6F)));
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void anUnriddenCamelsDashCooldownRunsDown() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.applyClientTime(new TimeUpdate(GAME_TIME, Map.of()));
            f.place(ON_FOOT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(CAMEL, EntityTypeIds.CAMEL, START, 0, 0, 0);
            PacketEntityCamel camel = (PacketEntityCamel) f.player.compensatedEntities.getEntity(CAMEL);
            camel.hasSaddle = true;
            camel.onGround = true;
            metadata(f, false);
            assertExact(f.tick(ON_FOOT, true));

            metadata(f, true);
            for (int tick = 0; tick < DASH_COOLDOWN; tick++) assertExact(f.tick(ON_FOOT, true));

            mount(f);
            double velocity = GROUND_INPUT;
            double z = START.z + velocity;
            assertExact(ride(f, new Vec3(START.x, START.y, z)));
            velocity = velocity * GROUND_FRICTION + GROUND_INPUT;
            z += velocity;
            assertExact(ride(f, new Vec3(START.x, START.y, z)));
        }
    }

    /** The server's camel DASH and a standing LAST_POSE_CHANGE_TICK, delivered. */
    private static void metadata(JavaTickFixture f, boolean dashing) {
        f.player.compensatedEntities.updateEntityMetadata(
                CAMEL,
                List.of(
                        new EntityMetadata.Entry(DASH, dashing, ByteBuffer.allocate(0)),
                        new EntityMetadata.Entry(LAST_POSE_CHANGE_TICK, GAME_TIME - 1000L, ByteBuffer.allocate(0))));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The rider boards the still camel holding forward and sprint. */
    private static void mount(JavaTickFixture f) {
        f.setPassengers(CAMEL, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(FORWARD_SPRINT);
        var command = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
        f.tickEnd();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0.0, GROUNDED_Y, 0.0));
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    /** One client tick facing +z: the rider's Rot, the camel's grounded move and the tick end. */
    private static PredictionResult ride(JavaTickFixture f, Vec3 to) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, 0F, 0F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), 0F, 0F, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }

    private static void assertExact(PredictionResult result) {
        StringBuilder report = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            report.append(' ').append(flag.getVerbose().getString());
        }
        assertTrue(exact(result), report.toString());
    }
}
