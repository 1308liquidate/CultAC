package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.BoatPseudo;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AbstractBoat#controlBoat turns a driven boat by deltaRotation, which left and right input change by one degree a
 * tick and AbstractBoat#floatBoat damps by the status friction, and LocalPlayer#sendChanges sends the turned yaw in
 * ServerboundMoveVehiclePacket#fromEntity. A boat yaw the controls cannot reach is flagged.
 */
class BoatTurnRateTest {
    private static final int BOAT = 100;
    private static final int SETTLE_TICKS = 6;
    private static final int TICKS = 10;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_LEFT =
            new ServerboundPlayerInput(true, false, true, false, false, false, false);
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    // AbstractBoat#getGroundFriction sums, in float, the 0.6F friction of the nine stone blocks under the boat's
    // 1.375 wide box and divides by their count.
    private static final float LAND_FRICTION = landFriction(9, 0.6F);

    private static float landFriction(int blocks, float friction) {
        float sum = 0.0F;
        for (int i = 0; i < blocks; i++) sum += friction;
        return sum / blocks;
    }

    @Test
    void aBoatTurningAtItsControlsRateIsExact() throws Exception {
        try (var f = mount()) {
            drive(f, LEFT, 0.005F);
            assertEquals(0, boatChecks(f).getViolations());
        }
    }

    @Test
    void aBoatTurningWhileItPaddlesForwardIsExact() throws Exception {
        try (var f = mount()) {
            drive(f, FORWARD_LEFT, 0.04F);
            assertEquals(0, boatChecks(f).getViolations());
        }
    }

    @Test
    void aBoatSnappedToAnotherYawIsFlagged() throws Exception {
        try (var f = mount()) {
            // No input leaves deltaRotation at 0; the rider's own look may turn within 105 degrees of the boat.
            f.player.xRot = 90.0F;
            PredictionResult result = f.rideTick(SEAT, true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            assertEquals(1, boatChecks(f).getViolations());
        }
    }

    @Test
    void aBoatTurningFasterThanItsControlsIsFlagged() throws Exception {
        try (var f = mount()) {
            f.input(LEFT);
            f.rideTick(SEAT, true);
            // The first turned tick reaches deltaRotation -1; a client turning by -10 skips the damped build-up.
            f.player.xRot = -10.0F;
            f.rideTick(new Vec3(SEAT.x, SEAT.y, SEAT.z), true);
            assertTrue(boatChecks(f).getViolations() >= 1);
        }
    }

    /** Holds {@code input} for {@link #TICKS} ticks, sending the boat's vanilla yaw and position each tick. */
    private static void drive(JavaTickFixture f, ServerboundPlayerInput input, float acceleration) {
        // The tick before the keys reach the boat: LocalPlayer#rideTick copies them after the root boat tick.
        f.input(input);
        PredictionResult staged = f.rideTick(SEAT, true);
        assertTrue(JavaTickFixture.exact(staged), "staged: " + JavaTickFixture.describe(staged));

        float yaw = 0.0F;
        float deltaRotation = 0.0F;
        double velocityX = 0.0;
        double velocityZ = 0.0;
        Vec3 position = SEAT;
        for (int tick = 0; tick < TICKS; tick++) {
            // AbstractBoat#floatBoat ON_LAND damps the motion and deltaRotation, then controlBoat turns and thrusts.
            deltaRotation *= LAND_FRICTION;
            deltaRotation--;
            yaw += deltaRotation;
            velocityX = velocityX * LAND_FRICTION
                    + f.player.trigHandler.sin(-yaw * ((float) Math.PI / 180F)) * acceleration;
            velocityZ =
                    velocityZ * LAND_FRICTION + f.player.trigHandler.cos(yaw * ((float) Math.PI / 180F)) * acceleration;
            position = position.add(velocityX, 0, velocityZ);
            // AbstractBoat#positionRider turns the rider with the boat.
            f.player.xRot = yaw;
            PredictionResult result = f.rideTick(position, true);
            assertTrue(JavaTickFixture.exact(result), "tick " + tick + ": " + JavaTickFixture.describe(result));
        }
    }

    private static Check boatChecks(JavaTickFixture f) {
        return f.player.checkManager.getListener(BoatPseudo.class);
    }

    private static JavaTickFixture mount() throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(NONE);
        // Sit still past CompensatedVehicleState's mount window so every later tick is judged.
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            PredictionResult settle = f.rideTick(SEAT, true);
            assertTrue(JavaTickFixture.exact(settle), "settle " + tick + ": " + JavaTickFixture.describe(settle));
        }
        return f;
    }
}
