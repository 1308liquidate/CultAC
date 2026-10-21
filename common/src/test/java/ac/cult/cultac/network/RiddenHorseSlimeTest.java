package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A rider turning a horse that walks forward over slime, replayed from a 26.3 client's capture: four consecutive
 * horse positions, the rider's yaw and pitch of the last two moves, and the forward key the rider held throughout.
 */
class RiddenHorseSlimeTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final int HORSE = 100;
    // The captured horse's movement_speed: the float whose LivingEntity#travelRidden input reproduces every
    // captured move over the slime to 1.2E-9.
    private static final float SPEED = 0.14517796F;
    // LivingEntity#travelInAir: a grounded horse's next vertical delta, (0 - 0.08) * 0.98F.
    private static final double GROUNDED_Y = -0.0784000015258789;
    // LivingEntity#travelInAir: slime's 0.8F friction times the 0.91F air drag.
    private static final double SLIME_FRICTION = 0.8F * 0.91F;

    /**
     * The z the horse carries into the last move, 0.728 * -0.0087878 = -0.0063975, is above LivingEntity#aiStep's
     * 0.003 cutoff, but SlimeBlock#stepOn scales it by 0.41568 to -0.0026593 at the end of the move before, so the
     * client zeroes it and moves z = +0.0328116, the ridden input alone. It flagged VehicleOffset (0, 0, 0.006398).
     */
    @Test
    void theSlimeScaledCarriedAxisFallsUnderTheMovementThreshold() throws Exception {
        assertCarriesStepOn(new Scenario(
                List.of(
                        new Vec3(249.6605715004234D, 64.0D, 157.04957381788498D),
                        new Vec3(249.5854137032814D, 64.0D, 157.00922759520216D),
                        new Vec3(249.50151851508951D, 64.0D, 157.00043980177918D),
                        new Vec3(249.42441414954072D, 64.0D, 157.03325141497942D)),
                new float[] {-273.20578F, -302.39413F},
                new float[] {10.09855F, 9.293347F}));
    }

    /**
     * The ridden input (+0.0195908) opposes the carried x (0.728 * -0.0127028 = -0.0092476). SlimeBlock#stepOn scales
     * only that carried x, to -0.0038441, so the client moves x = +0.0157467, past the unscaled sum +0.0103432. It
     * flagged VehicleOffset (0.005404, 0, 0).
     */
    @Test
    void slimeScalesTheCarriedVelocityAndNotTheRiddenInput() throws Exception {
        assertCarriesStepOn(new Scenario(
                List.of(
                        new Vec3(248.4546638915012D, 64.0D, 157.8044185622659D),
                        new Vec3(248.4365760348503D, 64.0D, 157.72024903504146D),
                        new Vec3(248.42387324747585D, 64.0D, 157.6339592439466D),
                        new Vec3(248.43961997528717D, 64.0D, 157.54981743011976D)),
                new float[] {-186.78123F, -161.3504F},
                new float[] {7.716491F, 8.08554F}));
    }

    /**
     * Seeds the velocity the client carries into the third position's move, rides the last two moves, and requires
     * each to predict exactly and the velocity carried between them to be the client's.
     */
    private static void assertCarriesStepOn(Scenario scenario) throws Exception {
        try (var f = new JavaTickFixture()) {
            List<Vec3> positions = scenario.positions();
            mountOnSlime(f, positions.get(1));
            f.player
                    .checkManager
                    .getSimulationProcessor()
                    .seedStartingVelocity(carried(positions.get(0), positions.get(1)));
            // AbstractHorse#tick runs before LocalPlayer#rideTick hands it this tick's keys.
            f.input(FORWARD);
            f.tickEnd();

            PredictionResult first = ride(f, positions.get(2), scenario.yaws()[0], scenario.pitches()[0]);
            assertTrue(exact(first), explain(first));
            Set<Vec3> starting =
                    Set.copyOf(f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());

            PredictionResult second = ride(f, positions.get(3), scenario.yaws()[1], scenario.pitches()[1]);
            assertTrue(exact(second), explain(second));
            Vec3 carried = carried(positions.get(1), positions.get(2));
            assertTrue(
                    starting.stream().anyMatch(velocity -> velocity.distanceToSqr(carried) < 1.0E-18),
                    "carried " + starting + ", the client carries " + carried);
        }
    }

    /**
     * The velocity a horse grounded on slime carries out of the move {@code from} to {@code to}: LivingEntity#travelInAir's
     * friction and gravity, then Entity#applyEffectsFromBlocks' SlimeBlock#stepOn scale of 0.4 + |dy| * 0.2.
     */
    private static Vec3 carried(Vec3 from, Vec3 to) {
        double scale = 0.4 + Math.abs(GROUNDED_Y) * 0.2;
        return new Vec3((to.x - from.x) * SLIME_FRICTION * scale, GROUNDED_Y, (to.z - from.z) * SLIME_FRICTION * scale);
    }

    private static String explain(PredictionResult result) {
        StringBuilder text = new StringBuilder(describe(result));
        for (PredictionResult.Flag flag : result.getFlags())
            text.append(' ').append(flag.getVerbose().getString());
        return text.toString();
    }

    private static PredictionResult ride(JavaTickFixture f, Vec3 to, float yaw, float pitch) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
        f.input(FORWARD);
        return f.rideTick(to, true);
    }

    /** The captured 5x5 slime patch, and a saddled horse at {@code start} on it with the player riding. */
    private static void mountOnSlime(JavaTickFixture f, Vec3 start) {
        for (int cx = 14; cx <= 16; cx++) {
            for (int cz = 8; cz <= 10; cz++) f.player.compensatedWorld.ensureValidationChunkLoaded(cx, cz);
        }
        for (int x = 247; x <= 251; x++) {
            for (int z = 155; z <= 159; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), BlockIds.SLIME_BLOCK.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0, 0, 0);
        PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
        horse.hasSaddle = true;
        horse.isTame = true;
        horse.movementSpeedAttribute = SPEED;
        horse.onGround = true;
        f.setPassengers(HORSE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
    }

    private record Scenario(List<Vec3> positions, float[] yaws, float[] pitches) {}
}
