package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Server block updates that change a fluid around the player, applied at the ping proving them.
 * ClientPacketListener handles the update in Minecraft#runAllTasks between client ticks, so every tick before the pong
 * moves in the old fluid and every tick after it in the new one; the engine keeps no fluid state open across the pong.
 */
class JavaProvenFluidUpdateTest {
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final int SURFACE_Y = FLOOR_Y + 2;

    /**
     * A sprint-swimmer on the floor of a deep pool the server drains. Entity#updateSwimming keeps the swim while the
     * client still has the water, and ends it in the first tick after the pong, when the sprinter is dry. That tick's
     * aiStep still sees last tick's swimming pose out of water, so LocalPlayer#isMovingSlowly crawls the input, and
     * Player#updatePlayerPose stands the player up at its end.
     */
    @Test
    void aDrainedPoolEndsTheSwimAtTheProvingPong() throws Exception {
        try (var f = pool()) {
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            Vec3[] state = {new Vec3(0.5, FLOOR_Y, -6.0), Vec3.ZERO};
            f.place(state[0], Vec3.ZERO);
            assertExact(f.tick(state[0], true), "standing");

            command(f, PlayerCommandAction.START_SPRINTING);
            f.input(FORWARD_SPRINT);
            for (int tick = 1; tick <= 3; tick++) {
                waterTick(f, state, yaw, "swim tick " + tick);
                assertEquals(tick > 1, f.player.swimState.swimming(), "swim tick " + tick);
            }
            assertEquals(Pose.SWIMMING, f.player.pose);

            int drain = f.player.lastTransactionSent.incrementAndGet();
            setPool(f, BlockIds.AIR.defaultState(), drain);
            for (int tick = 0; tick < 2; tick++) {
                String where = "held pong tick " + tick;
                waterTick(f, state, yaw, where);
                assertTrue(f.player.swimState.swimming(), where + ": the client still has the water");
                assertEquals(Pose.SWIMMING, f.player.pose, where);
            }

            f.pongThrough(drain);
            assertFalse(
                    f.player.compensatedWorld.hasRecentClientWaterChanges(poolBox()),
                    "the proven drain left the water timing open");
            for (int tick = 0; tick < 3; tick++) {
                String where = "dry tick " + tick;
                // Entity#isVisuallyCrawling: the SWIMMING pose while not in water; Attributes#SNEAKING_SPEED is 0.3.
                groundTick(f, state, yaw, tick == 0 ? 0.3F : 1.0F, where);
                assertFalse(f.player.swimState.swimming(), where + ": the swim outlived the water");
                assertEquals(Pose.STANDING, f.player.pose, where);
            }
        }
    }

    /** A ping written before the update only bounds it from below, so the fluid timing it changes stays open. */
    @Test
    void anUpdateBehindAnEarlierPingKeepsTheWaterTimingOpen() throws Exception {
        try (var f = pool()) {
            int earlier = f.player.lastTransactionSent.incrementAndGet();
            f.player.compensatedWorld.queueServerBlockUpdate(
                    0, FLOOR_Y, 0, BlockIds.AIR.defaultState(), earlier, false);
            f.pongThrough(earlier);
            assertTrue(f.player.compensatedWorld.hasRecentClientWaterChanges(poolBox()));
        }
    }

    // LivingEntity#travelInWater on the floor at a level look: speed 0.02F and the sprinting 0.9F slowDown.
    private static void waterTick(JavaTickFixture f, Vec3[] state, float yaw, String where) {
        Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        assertExact(f.tick(state[0], true), where);
        state[1] = new Vec3(delta.x * 0.9F, 0.0, delta.z * 0.9F);
    }

    // LivingEntity#travelInAir on stone: the sprinting getSpeed() and 0.6F * 0.91F friction.
    private static void groundTick(JavaTickFixture f, Vec3[] state, float yaw, float movingSlowly, String where) {
        float sprintSpeed = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
        Vec3 delta = state[1].add(ClientMoveInput.of(f, FORWARD_SPRINT, movingSlowly, sprintSpeed, yaw));
        state[0] = state[0].add(delta.x, 0.0, delta.z);
        assertExact(f.tick(state[0], true), where);
        double friction = 0.6F * 0.91F;
        state[1] = new Vec3(delta.x * friction, 0.0, delta.z * friction);
    }

    private static void assertExact(PredictionResult result, String where) {
        assertTrue(JavaTickFixture.exact(result), where + ": " + describe(result));
    }

    private static JavaTickFixture pool() throws Exception {
        var f = new JavaTickFixture();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= SURFACE_Y; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        // The fixture's own pool is written outside a proof; let its window close.
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    /** The server's block updates for every pool block, each proven by {@code transaction}. */
    private static void setPool(JavaTickFixture f, int state, int transaction) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= SURFACE_Y; y++) {
                    f.player.compensatedWorld.queueServerBlockUpdate(x, y, z, state, transaction, true);
                }
            }
        }
    }

    private static SimpleCollisionBox poolBox() {
        return new SimpleCollisionBox(-8, FLOOR_Y, -8, 9, SURFACE_Y + 1, 9);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }
}
