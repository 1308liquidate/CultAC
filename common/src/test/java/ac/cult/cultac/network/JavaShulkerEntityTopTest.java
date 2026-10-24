package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Shulker#canBeCollidedWith is Entity#isAlive, so Entity#collide stops a player on a shulker's box: Shulker#makeBoundingBox
 * at the currentPeekAmount Shulker#updatePeekAmount has stepped onto the synced peek.
 */
class JavaShulkerEntityTopTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final int SHULKER = 100;
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);

    @Test
    void aPlayerStandingOnAClosedShulkerStaysOnItsTop() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityShulker shulker = shulker(f);
            Vec3 top = new Vec3(0.5, shulker.getPossibleMovementCollisionBoxes().maxY, 0.5);
            assertEquals(FLOOR_Y + 1.0, top.y);
            standOn(f, top);

            PredictionResult still = f.tick(top, true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void aPlayerAboveAClosedShulkerStillFalls() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 above = new Vec3(0.5, shulker(f).getPossibleMovementCollisionBoxes().maxY + 0.1, 0.5);
            f.place(above, Vec3.ZERO);
            f.input(NONE);

            PredictionResult hover = f.tick(above, true);
            assertFalse(exact(hover), describe(hover));
        }
    }

    @Test
    void aSettledPeekRaisesTheTopByThePhysicalPeek() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntityShulker shulker = shulker(f);
            int proof = peek(f, 30);
            f.pongThrough(proof);
            // Shulker#updatePeekAmount steps 0.05 a tick: six ticks reach 0.3.
            for (int tick = 0; tick < 6; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.isEmpty(), "the peek animation must be over");

            // Shulker#getPhysicalPeek(0.3F) = 0.5F - Mth.sin(0.8F * PI) * 0.5F
            double physicalPeek = 0.5F - ac.cult.cultac.utils.math.VanillaMath.sin(0.8F * (float) Math.PI) * 0.5F;
            Vec3 top = new Vec3(0.5, FLOOR_Y + 1.0 + physicalPeek, 0.5);
            assertEquals(
                    top.y,
                    shulker.settledPeekBox(shulker.getPossibleMovementCollisionBoxes(), f.player.getClientVersion())
                            .maxY);
            standOn(f, top);

            PredictionResult still = f.tick(top, true);
            assertTrue(exact(still), describe(still));
        }
    }

    @Test
    void thePeekAnimationOnlyCountsFromTheTickTheClientHasTheData() throws Exception {
        try (var f = new JavaTickFixture()) {
            shulker(f);
            int proof = peek(f, 100);
            for (int tick = 0; tick < 40; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertFalse(f.player.compensatedWorld.openShulkerBoxes.isEmpty(), "unanswered peek is still pending");

            f.pongThrough(proof);
            for (int tick = 0; tick < 19; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertFalse(f.player.compensatedWorld.openShulkerBoxes.isEmpty(), "0 -> 1 takes twenty ticks");
            f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.isEmpty());
        }
    }

    @Test
    void closingFromAPartialPeekWaitsForTheWholeDescent() throws Exception {
        try (var f = new JavaTickFixture()) {
            shulker(f);
            f.pongThrough(peek(f, 100));
            for (int tick = 0; tick < 20; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.isEmpty());

            // 1.0 -> 0.3 takes fourteen ticks, not the six 0 -> 0.3 does.
            f.pongThrough(peek(f, 30));
            for (int tick = 0; tick < 13; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertFalse(f.player.compensatedWorld.openShulkerBoxes.isEmpty());
            assertFalse(
                    f.player.compensatedWorld.openShulkerBoxes.iterator().next().canPushEntities());
            f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertTrue(f.player.compensatedWorld.openShulkerBoxes.isEmpty());
        }
    }

    private static void standOn(JavaTickFixture f, Vec3 top) {
        // LivingEntity#travel's gravity after the last grounded move.
        f.place(top, GROUNDED);
        f.player.onGround = f.player.lastOnGround = true;
        f.input(NONE);
    }

    /** Sends DATA_PEEK_ID {@code peek}; the transaction that proves the client applied it. */
    private static int peek(JavaTickFixture f, int peek) {
        int proof = f.player.lastTransactionSent.get() + 1;
        f.player.compensatedEntities.updateEntityMetadata(
                SHULKER,
                List.of(new EntityMetadata.Entry(WatchableIndexUtil.SHULKER_PEEK, (byte) peek, ByteBuffer.allocate(0))),
                proof);
        return proof;
    }

    /** A closed shulker attached to the floor under (0.5, 0.5). */
    private static PacketEntityShulker shulker(JavaTickFixture f) {
        f.player.compensatedEntities.addEntity(SHULKER, EntityTypeIds.SHULKER, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
        return (PacketEntityShulker) f.player.compensatedEntities.getEntity(SHULKER);
    }
}
