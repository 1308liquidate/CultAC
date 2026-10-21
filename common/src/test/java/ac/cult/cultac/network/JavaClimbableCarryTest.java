package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client walks forward off a vine faster than the climb allows. LivingEntity#moveRelative adds the input to
 * deltaMovement and LivingEntity#handleOnClimbable then clamps it to 0.15 horizontally, both before Entity#move, so the
 * tick off the vine starts from the clamped deltaMovement.
 */
class JavaClimbableCarryTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final float YAW = -90.0F;
    private static final double START_X = 0.4;
    private static final double CLIMB_CLAMP = 0.15F;
    // LivingEntity#travelInAir's air friction and gravity.
    private static final double AIR_FRICTION = 0.91F;
    private static final double FALL = -0.08 * 0.98F;

    @Test
    void theTickOffAClimbableStartsFromTheClampedDeltaMovement() throws Exception {
        try (JavaTickFixture f = onAVine()) {
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, YAW);
            PredictionResult climbing = climb(f, input, CLIMB_CLAMP);
            assertTrue(exact(climbing), describe(climbing));
            PredictionResult off = stepOff(f, input, CLIMB_CLAMP, CLIMB_CLAMP * AIR_FRICTION + input.x);
            assertTrue(exact(off), describe(off));
        }
    }

    @Test
    void theTickOffAClimbableDoesNotStartFromTheUnclampedDeltaMovement() throws Exception {
        try (JavaTickFixture f = onAVine()) {
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, YAW);
            climb(f, input, CLIMB_CLAMP);
            PredictionResult off = stepOff(f, input, CLIMB_CLAMP, START_X * AIR_FRICTION + input.x);
            assertFalse(exact(off), describe(off));
        }
        try (JavaTickFixture f = onAVine()) {
            Vec3 input = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, YAW);
            climb(f, input, CLIMB_CLAMP + input.x);
            PredictionResult off =
                    stepOff(f, input, CLIMB_CLAMP + input.x, (START_X + input.x) * AIR_FRICTION + input.x);
            assertFalse(exact(off), describe(off));
        }
    }

    /** Stands in a vine one block above the floor, moving along +X at {@link #START_X} and holding forward. */
    private static JavaTickFixture onAVine() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 1, 0), BlockIds.VINE.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
        f.place(new Vec3(0.9, FLOOR_Y + 1, 0.5), new Vec3(START_X, 0.0, 0.0));
        f.input(FORWARD);
        return f;
    }

    /** The climbing tick, moving {@code climbX} along +X. */
    private static PredictionResult climb(JavaTickFixture f, Vec3 input, double climbX) {
        return f.tick(new Vec3(0.9 + climbX, FLOOR_Y + 1, 0.5 + input.z), false);
    }

    /** The tick off the vine after a climbing tick of {@code climbX}, moving {@code offX} along +X. */
    private static PredictionResult stepOff(JavaTickFixture f, Vec3 input, double climbX, double offX) {
        double offZ = input.z * AIR_FRICTION + input.z;
        return f.tick(new Vec3(0.9 + climbX + offX, FLOOR_Y + 1 + FALL, 0.5 + input.z + offZ), false);
    }
}
