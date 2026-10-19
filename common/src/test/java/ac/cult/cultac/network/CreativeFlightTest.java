package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Relative;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Flight the server grants through player_abilities. LocalPlayer#aiStep adds three times the abilities' flying speed
 * to the delta movement's Y while jump is held, and Player#travel carries 0.6 of the travelled Y without gravity.
 */
class CreativeFlightTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final float FLYING_SPEED = 0.05F;
    private static final double IMPULSE = FLYING_SPEED * 3.0F;
    private static final Vec3 HOVER = new Vec3(0.5, FLOOR_Y + 3, 0.5);
    private static final Vec3 KNOCKBACK = new Vec3(0.21113349203442588, -0.0783739241897089, -0.3397424159189404);
    private static final Set<Relative> ALL_DELTAS = Set.of(Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z);

    @Test
    void aVelocityTakenInFlightWithJumpHeldRisesByTheFlightImpulse() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            double y = 2 + IMPULSE;
            Vec3 at = HOVER.add(0, y, 0);
            assertPredicted(f.tick(at, false));
            assertEquals(0.0, antiKb(f), "the client took the velocity");

            y = y * 0.6 + IMPULSE;
            at = at.add(0, y, 0);
            assertPredicted(f.tick(at, false));

            f.input(NONE);
            y *= 0.6;
            at = at.add(0, y, 0);
            assertPredicted(f.tick(at, false));
        }
    }

    @Test
    void aKnockbackTakenInFlightWithJumpHeldRisesByTheFlightImpulse() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(KNOCKBACK));

            assertPredicted(f.tick(HOVER.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    @Test
    void aVelocityAfterATeleportInFlightWithJumpHeldRisesByTheFlightImpulse() throws Exception {
        try (var f = flying()) {
            f.teleport(HOVER, new RelativeFlag(0));
            f.input(JUMP);
            Vec3 target = HOVER.add(1, 0, 0);
            f.playerPosition(target, Vec3.ZERO, ALL_DELTAS);
            f.pongThrough(f.velocity(new Vec3(0, KNOCKBACK.y, 0)));

            assertPredicted(f.tick(target.add(0, KNOCKBACK.y + IMPULSE, 0), false));
            assertEquals(0.0, antiKb(f), "the client took the velocity");
        }
    }

    @Test
    void aVelocityTakenInFlightWithShiftHeldSinksByTheFlightImpulse() throws Exception {
        try (var f = flying()) {
            f.input(SHIFT);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            assertPredicted(f.tick(HOVER.add(0, 2 - IMPULSE, 0), false));
            assertEquals(0.0, antiKb(f), "the client took the velocity");
        }
    }

    /** The impulse follows the reported keys, so a velocity raised by it while neither key is held flags. */
    @Test
    void anImpulseWithoutItsKeyFlags() throws Exception {
        try (var f = flying()) {
            f.input(NONE);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            f.tick(HOVER.add(0, 2 + IMPULSE, 0), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    @Test
    void sinkingByTheImpulseWhileJumpIsHeldFlags() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            f.tick(HOVER.add(0, 2 - IMPULSE, 0), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** The velocity is required from its proof in flight too, so a client that only rises by the impulse flags. */
    @Test
    void ignoringAVelocityInFlightFlags() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            f.tick(HOVER.add(0, IMPULSE, 0), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** A player hovering at {@link #HOVER} that the server lets fly and sets flying. */
    private static JavaTickFixture flying() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(HOVER, Vec3.ZERO);
        var packet = new ClientboundPlayerAbilities(false, true, true, false, FLYING_SPEED, 0.1F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.isFlying && f.player.canFly, "the abilities did not apply");
        return f;
    }

    private static double antiKb(JavaTickFixture f) {
        return f.player.checkManager.getKnockbackHandler().violations;
    }

    private static void assertPredicted(PredictionResult result) {
        assertTrue(result.getOffset() < 1.0E-5, describe(result));
    }
}
