package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A knockback taken in creative flight: LocalPlayer#aiStep adds the flight key impulse
 * ({@code inputYa * abilities.getFlyingSpeed() * 3.0F}) to the velocity before LivingEntity#aiStep's 0.003 cutoff,
 * and Player#travel carries the pre-travel Y times 0.6 into the next tick. Velocities from AntiKB diag bundles
 * 01a15437-0400 and 01a1560f-ebf9.
 */
class CreativeFlightKnockbackTest {
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 START = new Vec3(0.5, 70.0, 0.5);
    private static final double FLIGHT_IMPULSE = (double) (1.0F * 0.05F * 3.0F);

    /** 01a15437-0400 tick 486: jump held while flying, the client moves the velocity's Y plus 0.15F. */
    @Test
    void jumpHeldInFlightAddsTheFlightImpulseToTheKnockback() throws Exception {
        Vec3 knockback = new Vec3(-0.0804492461698102, 0.42000854544344746, -0.48336690471830557);
        try (var f = flying(JUMP)) {
            PredictionResult result = knockbackTick(f, knockback, knockback.add(0, FLIGHT_IMPULSE, 0));
            assertEquals(0.0, result.getOffset(), 1.0E-6, describe(result));
            assertEquals(0.0, f.player.checkManager.getKnockbackHandler().violations, "the client took the velocity");

            // 01a15437-0400 tick 487: jump released; LivingEntity#travelInAir's 0.91 and Player#travel's 0.6.
            f.input(NONE);
            Vec3 next = new Vec3(knockback.x * 0.91F, (knockback.y + FLIGHT_IMPULSE) * 0.6, knockback.z * 0.91F);
            PredictionResult carried = f.tick(position(f).add(next), false);
            assertEquals(0.0, carried.getOffset(), 1.0E-6, describe(carried));
        }
    }

    /** Sneak held while flying subtracts the same impulse. */
    @Test
    void shiftHeldInFlightSubtractsTheFlightImpulse() throws Exception {
        Vec3 knockback = new Vec3(-0.027711652, -0.078373924, -0.418909846);
        try (var f = flying(SHIFT)) {
            PredictionResult result = knockbackTick(f, knockback, knockback.add(0, -FLIGHT_IMPULSE, 0));
            assertEquals(0.0, result.getOffset(), 1.0E-6, describe(result));
            assertEquals(0.0, f.player.checkManager.getKnockbackHandler().violations, "the client took the velocity");
        }
    }

    /** 01a1560f-ebf9: -0.152353 + 0.15F is under LivingEntity#aiStep's 0.003 cutoff, so the client holds its Y. */
    @Test
    void theImpulseAppliesBeforeTheMovementThreshold() throws Exception {
        Vec3 knockback = new Vec3(-0.2834035280473661, -0.1523530488921443, -0.3631813465177318);
        try (var f = flying(JUMP)) {
            PredictionResult result = knockbackTick(f, knockback, new Vec3(knockback.x, 0.0, knockback.z));
            assertEquals(0.0, result.getOffset(), 1.0E-6, describe(result));
            assertEquals(0.0, f.player.checkManager.getKnockbackHandler().violations, "the client took the velocity");
        }
    }

    /** The impulse comes from the keys the tick's PLAYER_INPUT sent: no keys, no impulse. */
    @Test
    void ignoringTheKnockbackVerticallyStillFlags() throws Exception {
        Vec3 knockback = new Vec3(-0.0804492461698102, 0.42000854544344746, -0.48336690471830557);
        try (var f = flying(NONE)) {
            knockbackTick(f, knockback, knockback.add(0, FLIGHT_IMPULSE, 0));
            assertTrue(
                    f.player.checkManager.getKnockbackHandler().violations > 0,
                    "AntiKB violations " + f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    /** A 1.21.2+ client that never sent PLAYER_INPUT holds no keys (LocalPlayer#lastSentInput starts at Input.EMPTY). */
    @Test
    void noPlayerInputSentMeansNoFlightImpulse() throws Exception {
        Vec3 knockback = new Vec3(-0.0804492461698102, 0.42000854544344746, -0.48336690471830557);
        try (var f = flying(null)) {
            knockbackTick(f, knockback, knockback.add(0, FLIGHT_IMPULSE, 0));
            assertTrue(
                    f.player.checkManager.getKnockbackHandler().violations > 0,
                    "AntiKB violations " + f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    /** A 1.21.1 client's shift key is known, so a client not sneaking cannot take the -0.15F impulse. */
    @Test
    void aLegacyClientNotSneakingCannotSinkUnderTheKnockback() throws Exception {
        Vec3 knockback = new Vec3(-0.0804492461698102, 0.42000854544344746, -0.48336690471830557);
        try (var f = flying(null)) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21);
            knockbackTick(f, knockback, knockback.add(0, -FLIGHT_IMPULSE, 0));
            assertTrue(
                    f.player.checkManager.getKnockbackHandler().violations > 0,
                    "AntiKB violations " + f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    /** A 1.21.1 client's jump key is unknown, so it can take the +0.15F impulse. */
    @Test
    void aLegacyClientMayHoldJumpUnderTheKnockback() throws Exception {
        Vec3 knockback = new Vec3(-0.0804492461698102, 0.42000854544344746, -0.48336690471830557);
        try (var f = flying(null)) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21);
            knockbackTick(f, knockback, knockback.add(0, FLIGHT_IMPULSE, 0));
            assertEquals(0.0, f.player.checkManager.getKnockbackHandler().violations, "the client took the velocity");
        }
    }

    private static JavaTickFixture flying(ServerboundPlayerInput keys) throws Exception {
        var f = new JavaTickFixture();
        f.place(START, Vec3.ZERO);
        f.player.canFly = true;
        f.player.isFlying = true;
        f.player.flySpeed = 0.05F;
        if (keys != null) f.input(keys);
        return f;
    }

    private static PredictionResult knockbackTick(JavaTickFixture f, Vec3 knockback, Vec3 movement) {
        int proof = f.velocity(knockback);
        f.pongThrough(proof);
        return f.tick(position(f).add(movement), false);
    }

    private static Vec3 position(JavaTickFixture f) {
        return new Vec3(f.player.x, f.player.y, f.player.z);
    }
}
