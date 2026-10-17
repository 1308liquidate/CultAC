package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaCobwebMovementTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final double FALL = 0.0784000015258789;

    /**
     * Entity#move skips setPos when the collided movement is at most 1e-7 long, measured on the whole delta that
     * LivingEntity#travel built with moveRelative's input. A web landing whose vertical clip alone is under that
     * bound still moves when the player walks.
     */
    @Test
    void aWebLandingWithInputKeepsItsTinyVerticalClip() throws Exception {
        try (var f = new JavaTickFixture()) {
            web(f, new BlockPos(0, FLOOR_Y, 0));
            double gap = 2.0E-4;
            f.place(new Vec3(0.5, FLOOR_Y + gap + FALL, 0.5), new Vec3(0, -FALL, 0));

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + gap, 0.5), false));
            f.input(FORWARD);
            // LivingEntity#moveRelative at the flying speed, then the web's 0.25 horizontal stuck multiplier.
            double walked = (double) 0.98F * 0.02F * 0.25;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + walked), true));
        }
    }

    /**
     * Entity#checkInsideBlocks steps the axes in Direction#axisStepOrder of the delta Entity#move recorded, which
     * includes moveRelative's input. Carried X drift with a larger Z input steps Z first, clear of a web the X-first
     * walk would cross at its corner, so the next move is not slowed.
     */
    @Test
    void theInsideBlockWalkOrdersItsAxesByTheInputAppliedDelta() throws Exception {
        try (var f = new JavaTickFixture()) {
            // Corner of the box at x 0.995 -> 1.005 enters the web column only while z still starts below 1.
            web(f, new BlockPos(1, FLOOR_Y + 1, 0));
            double drift = 0.01;
            Vec3 start = new Vec3(0.695, FLOOR_Y + 1, 1.29);
            f.place(start, new Vec3(drift, 0, 0));

            f.input(FORWARD);
            double walked = (double) 0.98F * 0.02F;
            Vec3 first = start.add(new Vec3(drift, 0, walked));
            assertExact(f.tick(first, false));
            f.input(NONE);
            // LivingEntity#travel: air friction 0.91 horizontally, gravity then 0.98 vertically.
            Vec3 second = first.add(new Vec3(drift * 0.91F, -FALL, walked * 0.91F));
            assertExact(f.tick(second, false));
        }
    }

    private static void web(JavaTickFixture f, BlockPos pos) {
        f.player.compensatedWorld.updateBlock(pos, BlockIds.COBWEB.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
