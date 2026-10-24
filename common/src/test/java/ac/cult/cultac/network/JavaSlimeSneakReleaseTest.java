package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.4 client walking backwards under a ceiling over a slime floor while tapping sneak. From 1.21.2,
 * LivingEntity#aiStep runs Entity#applyEffectsFromBlocks after travel, whose SlimeBlock#stepOn scales the carried
 * horizontal velocity only when Entity#isSteppingCarefully, the tick's shift key, is up. The landing tick holds shift,
 * so the next tick starts from the unscaled velocity; that tick releases shift but LocalPlayer#aiStep still crouches
 * from the previous tick's shift key, so its input is sneak slowed. The moves are a real client's, translated onto
 * the fixture.
 */
class JavaSlimeSneakReleaseTest {
    private static final ServerboundPlayerInput BACK_SNEAK =
            new ServerboundPlayerInput(false, true, false, false, false, true, true);
    private static final ServerboundPlayerInput BACK_RIGHT_SNEAK =
            new ServerboundPlayerInput(false, true, false, true, false, true, true);
    private static final ServerboundPlayerInput BACK_RIGHT =
            new ServerboundPlayerInput(false, true, false, true, false, false, true);

    @Test
    void releasingSneakAfterASneakingLandingOnSlimeIsSneakSlowedFromTheUnscaledVelocity() throws Exception {
        try (var f = start()) {
            f.input(BACK_RIGHT_SNEAK);
            check(f, "landing", 269.6465377714902, 62.0, 150.38082294404805, 1088.0381F, 29.925932F, true);
            f.input(BACK_RIGHT);
            check(f, "release", 269.65014761664725, 62.0, 150.28420051214866, 1100.0195F, 30.09468F, true);
        }
    }

    @Test
    void walkingSpeedOnTheReleaseTickIsStillSneakSlowed() throws Exception {
        try (var f = start()) {
            f.input(BACK_RIGHT_SNEAK);
            check(f, "landing", 269.6465377714902, 62.0, 150.38082294404805, 1088.0381F, 29.925932F, true);
            f.input(BACK_RIGHT);
            // The release tick's sneaking input (-0.0124, -0.0124 local) at walking speed instead.
            Vec3 sneaking = new Vec3(
                    0.0036098451570296675 - 0.011018111181314445, 0, -0.0966224318993909 + 0.08072297445092386);
            Vec3 walking = new Vec3(0.011018111181314445, 0, -0.08072297445092386).add(sneaking.scale(1 / 0.3));
            f.player.xRot = 1100.0195F;
            f.player.yRot = 30.09468F;
            PredictionResult result =
                    f.tick(recorded(269.6465377714902, 62.0, 150.38082294404805).add(walking), true);
            assertRejected(result, "NoSneakSlow");
        }
    }

    /** The client in the air after the ceiling bounce, sneaking since the previous tick. */
    private static JavaTickFixture start() throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_4);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(
                        new BlockPos(x, FLOOR_Y - 1, z), BlockIds.SLIME_BLOCK.defaultState());
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 2, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        // The tick before the ceiling bounce started on the slime: LivingEntity#travelInAir's 0.8F * 0.91F friction.
        double friction = 0.8F * 0.91F;
        Vec3 bounced = new Vec3(0.02471112436603562, 0.0, -0.11587143459027516);
        f.place(
                recorded(269.6156299346299, 62.200000047683716, 150.5597078138167),
                new Vec3(bounced.x * friction, (0.0 - 0.08) * (double) 0.98F, bounced.z * friction));
        f.player.isSneaking = true;
        JavaTickFixture.setField(
                SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        f.input(BACK_SNEAK);
        check(f, "falling", 269.6344299573542, 62.12160004615784, 150.46952950682223, 1087.9255F, 29.925932F, false);
        return f;
    }

    private static void check(
            JavaTickFixture f, String name, double x, double y, double z, float yaw, float pitch, boolean onGround) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
        PredictionResult result = f.tick(recorded(x, y, z), onGround);
        assertTrue(exact(result), name + ": " + describe(result));
    }

    /** A recorded position moved onto the fixture: the slime top 62.0 becomes FLOOR_Y. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 269.0, y - 62.0 + FLOOR_Y, z - 151.0);
    }
}
