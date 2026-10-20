package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
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

            Vec3 at = HOVER.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z);
            assertPredicted(f.tick(at, false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");

            // LivingEntity#travelInAir's 0.91 horizontally, Player#travel's 0.6 vertically.
            f.input(NONE);
            Vec3 next = new Vec3(KNOCKBACK.x * 0.91F, (KNOCKBACK.y + IMPULSE) * 0.6, KNOCKBACK.z * 0.91F);
            assertPredicted(f.tick(at.add(next), false));
        }
    }

    /** -0.1523530489 + 0.15F is under LivingEntity#aiStep's 0.003 cutoff, so the client holds its Y. */
    @Test
    void theImpulseAppliesBeforeTheMovementThreshold() throws Exception {
        Vec3 knockback = new Vec3(-0.2834035280473661, -0.1523530488921443, -0.3631813465177318);
        try (var f = flying()) {
            f.input(JUMP);
            f.pongThrough(f.velocity(knockback));

            assertPredicted(f.tick(HOVER.add(knockback.x, 0, knockback.z), false));
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

    /** A client that never sent player_input holds no keys: LocalPlayer#lastSentInput starts at Input.EMPTY. */
    @Test
    void anImpulseBeforeAnyPlayerInputFlags() throws Exception {
        try (var f = flying()) {
            f.pongThrough(f.velocity(KNOCKBACK));

            f.tick(HOVER.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z), false);
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

    /**
     * The LocalPlayer a LOGIN after a reconfiguration creates holds no keys and sends player_input only once they
     * change, so jump held before the switch adds no impulse after it.
     */
    @Test
    void jumpHeldBeforeABackendSwitchAddsNoImpulseAfterIt() throws Exception {
        try (var f = flying()) {
            f.input(JUMP);
            assertPredicted(f.tick(HOVER.add(0, IMPULSE, 0), false));

            switchBackend(f);
            f.teleport(HOVER, new RelativeFlag(0));
            f.pongThrough(f.velocity(new Vec3(0, 2, 0)));

            assertPredicted(f.tick(HOVER.add(0, 2, 0), false));
            assertEquals(0.0, antiKb(f), "the client took the velocity");
        }
    }

    /** A player hovering at {@link #HOVER} that the server lets fly and sets flying. */
    private static JavaTickFixture flying() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(HOVER, Vec3.ZERO);
        grantFlight(f);
        return f;
    }

    /** A player_abilities letting the player fly and setting it flying, answered through its ping. */
    private static void grantFlight(JavaTickFixture f) {
        var packet = new ClientboundPlayerAbilities(false, true, true, false, FLYING_SPEED, 0.1F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.isFlying && f.player.canFly, "the abilities did not apply");
    }

    /**
     * A reconfiguration and a LOGIN answered through its ping, then the chunks and the player_abilities the new
     * backend sends with it.
     */
    private static void switchBackend(JavaTickFixture f) {
        var type = ClientboundPackets.FINISH_CONFIGURATION;
        var finish = new PacketSendEvent<>(f.player.user, ConnectionPhase.CONFIGURATION, type, new Opaque(type), false);
        new PacketServerRegistries().onFinishConfiguration(finish, f.player, new Opaque(type));
        finish.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(ItemUseTestSupport.login(f.player, GameMode.SURVIVAL));
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) f.player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        }
        grantFlight(f);
    }

    private static double antiKb(JavaTickFixture f) {
        return f.player.checkManager.getKnockbackHandler().violations;
    }

    private static void assertPredicted(PredictionResult result) {
        assertTrue(result.getOffset() < 1.0E-5, describe(result));
    }
}
