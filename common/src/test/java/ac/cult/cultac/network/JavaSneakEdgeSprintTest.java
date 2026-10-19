package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client sprinting toward the end of a stone floor, pressing shift so Player#maybeBackOffFromEdge stops it at
 * the drop while deltaMovement keeps the crouched sprint input, then releasing shift and walking off. The moves are a
 * real client's, translated onto the fixture floor; the drop starts at z = 1.
 */
class JavaSneakEdgeSprintTest {
    private static final ServerboundPlayerInput SPRINT_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void theVelocityTheEdgeHidesIsReleasedWithShift() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -8; x <= 8; x++) {
                for (int z = 1; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            // LivingEntity#travelInAir's ground friction, 0.6F * 0.91F, applied to the previous move.
            double friction = 0.6F * 0.91F;
            f.place(
                    recorded(272.3055689053061, -2.0, -48.99925107598291),
                    new Vec3(0.004762975409164483 * friction, 0.0, 0.1436450745273774 * friction));
            f.input(SPRINT);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            assertExact(f, 717.6444702148438F, 55.13953399658203F, 272.3134079575507, -2.0, -48.793528589822714, true);

            f.input(SPRINT_SNEAK);
            assertExact(f, 717.3444213867188F, 54.989532470703125F, 272.3235976916909, -2.0, -48.70394122033181, true);
            assertExact(f, 717.1943969726562F, 54.989532470703125F, 272.3310329941979, -2.0, -48.70394122033181, true);
            assertExact(f, 715.5443725585938F, 54.08953094482422F, 272.3380650745933, -2.0, -48.70394122033181, true);

            f.input(SPRINT);
            assertExact(f, 713.29443359375F, 52.58952331542969F, 272.34636848651104, -2.0, -48.59479956952958, true);
            assertExact(f, 712.5443115234375F, 51.53951644897461F, 272.36744237810774, -2.078400001525879, -48.408886474298384, false);
        }
    }

    private static void assertExact(
            JavaTickFixture f, float yaw, float pitch, double x, double y, double z, boolean onGround) {
        rotate(f, yaw, pitch);
        PredictionResult result = f.tick(recorded(x, y, z), onGround);
        assertTrue(exact(result), describe(result));
    }

    private static void rotate(JavaTickFixture f, float yaw, float pitch) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
    }

    /** A recorded position moved onto the fixture: its floor top -2 becomes FLOOR_Y and its edge z = -49 becomes 1. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 272.0, y + 2.0 + FLOOR_Y, z + 50.0);
    }
}
