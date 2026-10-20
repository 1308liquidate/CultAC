package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.PacketChangeGameState;
import ac.cult.cultac.events.packets.PacketPlayerAbilities;
import ac.cult.cultac.events.packets.PacketServerRegistries;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerAbilities;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
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
    private static final ServerboundPlayerInput JUMP_SHIFT =
            new ServerboundPlayerInput(false, false, false, false, true, true, false);
    private static final float FLYING_SPEED = 0.05F;
    private static final double IMPULSE = FLYING_SPEED * 3.0F;
    private static final Vec3 HOVER = new Vec3(0.5, FLOOR_Y + 3, 0.5);
    private static final Vec3 GROUND = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final double JUMP_POWER = 0.42F;
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

    /**
     * LocalPlayer#aiStep runs jumpFromGround when a double-tap turns flight on while on the ground, before the
     * flight input, so a knockback taken on that tick rises by the jump and the impulse.
     */
    @Test
    void aKnockbackOnAGroundedFlightToggleTakesTheTakeoffJumpAndTheImpulse() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            assertTrue(f.player.isFlying, "the toggle did not apply");
            f.input(JUMP);

            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** With shift held too the impulses cancel, and the takeoff jump alone lifts the knockback. */
    @Test
    void aKnockbackOnAGroundedFlightToggleWithShiftHeldTakesTheTakeoffJump() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP_SHIFT);

            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** Only the toggle sends a flight start on an armed grounded press, and it always runs jumpFromGround. */
    @Test
    void omittingTheTakeoffJumpOfAnArmedGroundedToggleFlags() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            f.tick(GROUND.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** The toggle resets Player#jumpTriggerTime, so the next takeoff needs a new arming press. */
    @Test
    void aSecondTakeoffJumpWithoutANewArmingPressFlags() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.abilities(true);
            f.input(JUMP);
            assertPredicted(f.tick(GROUND.add(0, JUMP_POWER + IMPULSE, 0), false));

            grantFlight(f, false);
            f.place(GROUND, Vec3.ZERO);
            f.input(NONE);
            assertPredicted(f.tick(GROUND, true));
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /**
     * MultiPlayerGameMode#setLocalMode lets a creative player fly at the game event, so a press before the
     * player_abilities that follows it already arms the toggle.
     */
    @Test
    void aPressAfterACreativeGameModeArmsTheToggleBeforeItsAbilities() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(GROUND.add(0, 0.3, 0), new Vec3(0, -0.2, 0));
            gameMode(f, 1);
            f.input(JUMP);
            assertPredicted(f.tick(GROUND.add(0, 0.1, 0), false));
            f.input(NONE);
            assertPredicted(f.tick(GROUND, true));
            grantFlight(f, false);

            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);
            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** ClientPacketListener#handleLogin runs MultiPlayerGameMode#setLocalMode, letting a creative player fly. */
    @Test
    void aPressAfterACreativeLoginArmsTheToggleBeforeItsAbilities() throws Exception {
        try (var f = new JavaTickFixture()) {
            login(f, GameMode.CREATIVE);
            relayTheFloor(f);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(GROUND.add(0, 0.3, 0), new RelativeFlag(0));

            assertArmedTakeoffUnderAKnockback(f);
        }
    }

    /** ClientPacketListener#handleRespawn runs MultiPlayerGameMode#setLocalMode on its new LocalPlayer. */
    @Test
    void aPressAfterACreativeRespawnArmsTheToggleBeforeItsAbilities() throws Exception {
        try (var f = new JavaTickFixture()) {
            respawn(f, GameMode.CREATIVE);
            f.player.packetStateData.playerLoadedIntoLevel = true;
            f.teleport(GROUND.add(0, 0.3, 0), new RelativeFlag(0));

            assertArmedTakeoffUnderAKnockback(f);
        }
    }

    /** The first press only arms Player#jumpTriggerTime, so flight turned on with it has no takeoff jump. */
    @Test
    void aTakeoffJumpOnAnUnarmedFirstPressFlags() throws Exception {
        try (var f = flying(GROUND, false)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** LocalPlayer#aiStep toggles only on the jump key going down, so a held jump has no takeoff jump. */
    @Test
    void aTakeoffJumpWithJumpHeldSinceTheArmingPressFlags() throws Exception {
        try (var f = armedOnTheGround(false)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            assertTrue(f.player.isFlying, "the flight start did not apply");

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** Player#jumpTriggerTime runs out 7 ticks after the arming press. */
    @Test
    void aTakeoffJumpAfterTheArmingPressRanOutFlags() throws Exception {
        try (var f = armedOnTheGround(true)) {
            for (int tick = 0; tick < 5; tick++) {
                assertPredicted(f.tick(GROUND, true));
            }
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** LivingEntity#jumpFromGround does nothing without jump strength, so the toggle keeps the knockback. */
    @Test
    void aGroundedFlightToggleWithoutJumpStrengthKeepsTheKnockback() throws Exception {
        try (var f = armedOnTheGround(true)) {
            f.player.compensatedEntities.getSelf().jumpStrength = 0;
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(JUMP);

            assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, KNOCKBACK.y + IMPULSE, KNOCKBACK.z), false));
            assertEquals(0.0, antiKb(f), "the client took the knockback");
        }
    }

    /** Player#isAffectedByFluids is false in flight, so a player already flying on the ground has no jump. */
    @Test
    void aPlayerAlreadyFlyingOnTheGroundCannotJumpUnderAKnockback() throws Exception {
        try (var f = flying(GROUND, true)) {
            f.input(JUMP_SHIFT);
            f.pongThrough(f.velocity(KNOCKBACK));

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** A survival toggle needs the jump press; flight turned on without it takes no takeoff jump. */
    @Test
    void aFlightToggleWithoutAJumpPressTakesNoTakeoffJump() throws Exception {
        try (var f = flying(GROUND, false)) {
            f.pongThrough(f.velocity(KNOCKBACK));
            f.abilities(true);
            f.input(SHIFT);

            f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER - IMPULSE, KNOCKBACK.z), false);
            assertTrue(antiKb(f) > 0, "AntiKB violations " + antiKb(f));
        }
    }

    /** A player hovering at {@link #HOVER} that the server lets fly and sets flying. */
    private static JavaTickFixture flying() throws Exception {
        return flying(HOVER, true);
    }

    /** A player at {@code position} that the server lets fly, set flying with {@code flying}. */
    private static JavaTickFixture flying(Vec3 position, boolean flying) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(position, Vec3.ZERO);
        grantFlight(f, flying);
        return f;
    }

    /**
     * A player the server lets fly that presses jump in the air on the tick before it lands, arming
     * Player#jumpTriggerTime without a jump, and lands releasing it or holding it.
     */
    private static JavaTickFixture armedOnTheGround(boolean releaseOnLanding) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.place(GROUND.add(0, 0.3, 0), new Vec3(0, -0.2, 0));
        grantFlight(f, false);
        f.input(JUMP);
        assertPredicted(f.tick(GROUND.add(0, 0.1, 0), false));
        if (releaseOnLanding) f.input(NONE);
        assertPredicted(f.tick(GROUND, true));
        return f;
    }

    /** A CHANGE_GAME_MODE game event, answered through its ping. */
    private static void gameMode(JavaTickFixture f, int mode) {
        var packet = new ClientboundGameEvent(GameEventType.CHANGE_GAME_MODE, mode);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.GAME_EVENT, packet, false);
        f.player.checkManager.getListener(PacketChangeGameState.class).onGameEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** A player_abilities letting the player fly and setting it flying, answered through its ping. */
    private static void grantFlight(JavaTickFixture f) {
        grantFlight(f, true);
    }

    private static void grantFlight(JavaTickFixture f, boolean flying) {
        var packet = new ClientboundPlayerAbilities(false, flying, true, false, FLYING_SPEED, 0.1F);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PLAYER_ABILITIES, packet, false);
        f.player.checkManager.getListener(PacketPlayerAbilities.class).onPlayerAbilities(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.isFlying == flying && f.player.canFly, "the abilities did not apply");
    }

    /**
     * A reconfiguration and a LOGIN answered through its ping, then the chunks and the player_abilities the new
     * backend sends with it.
     */
    private static void switchBackend(JavaTickFixture f) {
        login(f, GameMode.SURVIVAL);
        grantFlight(f);
    }

    /** A reconfiguration and a LOGIN into {@code mode} answered through its ping, then the chunks around spawn. */
    private static void login(JavaTickFixture f, GameMode mode) {
        var type = ClientboundPackets.FINISH_CONFIGURATION;
        var finish = new PacketSendEvent<>(f.player.user, ConnectionPhase.CONFIGURATION, type, new Opaque(type), false);
        new PacketServerRegistries().onFinishConfiguration(finish, f.player, new Opaque(type));
        finish.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(ItemUseTestSupport.login(f.player, mode));
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) f.player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        }
    }

    /** The fixture's floor in the new level a LOGIN starts. */
    private static void relayTheFloor(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** A RESPAWN into {@code mode} in the same level, answered through its pings. */
    private static void respawn(JavaTickFixture f, GameMode mode) {
        var spawnInfo = new PlayerSpawnInfo(
                ModelRegistryNamesState.defaults().id("minecraft:dimension_type", "minecraft:the_end"),
                f.player.compensatedWorld.getLastClientboundDimension().dimension(),
                mode);
        var packet = new ClientboundRespawn(spawnInfo, (byte) 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, packet, false);
        new PacketPlayerRespawn().onRespawn(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /**
     * From just above the ground: a press in the air arming the toggle, the landing, the server's player_abilities
     * letting the player fly, and a grounded double-tap under a knockback that takes the takeoff jump.
     */
    private static void assertArmedTakeoffUnderAKnockback(JavaTickFixture f) {
        f.place(GROUND.add(0, 0.3, 0), new Vec3(0, -0.2, 0));
        f.input(JUMP);
        assertPredicted(f.tick(GROUND.add(0, 0.1, 0), false));
        f.input(NONE);
        assertPredicted(f.tick(GROUND, true));
        grantFlight(f, false);

        f.pongThrough(f.velocity(KNOCKBACK));
        f.abilities(true);
        f.input(JUMP);
        assertPredicted(f.tick(GROUND.add(KNOCKBACK.x, JUMP_POWER + IMPULSE, KNOCKBACK.z), false));
        assertEquals(0.0, antiKb(f), "the client took the knockback");
    }

    private static double antiKb(JavaTickFixture f) {
        return f.player.checkManager.getKnockbackHandler().violations;
    }

    private static void assertPredicted(PredictionResult result) {
        assertTrue(result.getOffset() < 1.0E-5, describe(result));
    }
}
