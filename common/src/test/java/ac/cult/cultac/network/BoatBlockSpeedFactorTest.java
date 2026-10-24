package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AbstractBoat#tick moves the boat through Entity#move, which ends by multiplying the carried deltaMovement by
 * Entity#getBlockSpeedFactor. A boat on soul sand or honey (speed factor 0.4F) therefore carries only 0.4 of its move
 * into the next tick's AbstractBoat#floatBoat land friction and AbstractBoat#controlBoat acceleration.
 */
class BoatBlockSpeedFactorTest {
    private static final int BOAT = 100;
    private static final int TICKS = 12;
    private static final int SETTLE_TICKS = 6;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    // Soul sand's collision shape is 14/16 tall, honey's 15/16.
    private static final double SOUL_SAND_TOP = FLOOR_Y - 0.125;
    private static final double HONEY_TOP = FLOOR_Y - 0.0625;
    // Block's default friction, which AbstractBoat#getGroundFriction averages to the boat's land friction.
    private static final float LAND_FRICTION = 0.6F;
    private static final float SPEED_FACTOR = 0.4F;

    @Test
    void aBoatDrivenOnSoulSandIsExact() throws Exception {
        drive(BlockIds.SOUL_SAND, SOUL_SAND_TOP, SPEED_FACTOR, true);
    }

    @Test
    void aBoatDrivenOnHoneyIsExact() throws Exception {
        drive(BlockIds.HONEY_BLOCK, HONEY_TOP, SPEED_FACTOR, true);
    }

    @Test
    void aBoatIgnoringTheSoulSandSlowdownIsRejected() throws Exception {
        drive(BlockIds.SOUL_SAND, SOUL_SAND_TOP, 1.0F, false);
    }

    @Test
    void aBoatIgnoringTheHoneySlowdownIsRejected() throws Exception {
        drive(BlockIds.HONEY_BLOCK, HONEY_TOP, 1.0F, false);
    }

    private static void drive(BlockDefinition block, double top, float speedFactor, boolean legal)
            throws Exception {
        try (var f = mount(block, top)) {
            Vec3 position = new Vec3(0.5, top, 0.5);
            // The tick before the keys reach the boat: LocalPlayer#rideTick copies them after the root boat tick.
            f.input(FORWARD);
            PredictionResult staged = f.rideTick(position, true);
            assertTrue(JavaTickFixture.exact(staged), "staged: " + JavaTickFixture.describe(staged));

            double carried = 0.0;
            for (int tick = 0; tick < TICKS; tick++) {
                // AbstractBoat#floatBoat ON_LAND, then AbstractBoat#controlBoat's forward 0.04F along yaw 0.
                double velocity = carried * LAND_FRICTION + (double) (1.0F * 0.04F);
                position = position.add(0, 0, velocity);
                PredictionResult result = f.rideTick(position, true);
                if (!legal && tick > 0) {
                    // The first move is identical; the carried slowdown shows from the next one on.
                    JavaTickFixture.assertRejected(result, "VehicleOffset");
                    return;
                }
                assertTrue(JavaTickFixture.exact(result), "tick " + tick + ": " + JavaTickFixture.describe(result));
                // Entity#move: deltaMovement.multiply(getBlockSpeedFactor(), 1.0, getBlockSpeedFactor()).
                carried = velocity * speedFactor;
            }
        }
    }

    private static JavaTickFixture mount(BlockDefinition block, double top) throws Exception {
        var f = new JavaTickFixture();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 5; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), block.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        Vec3 seat = new Vec3(0.5, top, 0.5);
        f.place(seat, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, seat, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(NONE);
        // Sit still past CompensatedVehicleState's mount window so every later offset is judged.
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            PredictionResult settle = f.rideTick(seat, true);
            assertTrue(JavaTickFixture.exact(settle), "settle " + tick + ": " + JavaTickFixture.describe(settle));
        }
        return f;
    }
}
