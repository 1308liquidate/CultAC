package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A piston shoves a player west onto a second piston, which then shoves it up, in the same block-entity pass; the next
 * travel lands on the second piston's head and steps onto the first piston's head (GitHub issue 140).
 */
class JavaChainedPistonStepTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final double FALL = 0.0784000015258789;

    /**
     * A 26.1 client reports a block-entity pass's shoves before its next travel. The west piston's second pass
     * (PistonMovingBlockEntity#moveCollidedEntities) shoves the player 0.49 + 0.01 west into the column of the up
     * piston, whose first pass in the same Level#tickBlockEntities shoves it 0.5 + 0.01 up. Entity#move(PISTON)
     * leaves deltaMovement alone, so the next travel falls 0.01 onto the up piston's half-extended head, collides
     * east with the west piston's head and Entity#collide steps 0.5 onto that head: 0.51 + 0.49 up in one packet.
     */
    @Test
    void theTravelAfterAChainedShoveStepsFromWhereTheShovesLeftThePlayer() throws Exception {
        assertExact(chainedShove(0.0));
    }

    /** The shove does not raise the step: the travel still steps no more than maxUpStep. */
    @Test
    void theShoveDoesNotRaiseTheTravelsStepLimit() throws Exception {
        PredictionResult overStep = chainedShove(0.2);
        assertFalse(exact(overStep), describe(overStep));
    }

    /** The prediction for the packet after the chained shove, reported {@code extraY} above the vanilla position. */
    private static PredictionResult chainedShove(double extraY) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            BlockPos westPiston = new BlockPos(2, FLOOR_Y, 0);
            int westState = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "west");
            BlockPos upPiston = new BlockPos(0, FLOOR_Y - 1, 0);
            int upState = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "up");
            f.player.compensatedWorld.updateBlock(westPiston, westState);
            f.player.compensatedWorld.updateBlock(upPiston, upState);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            // Box x 0.89..1.49: clear of the west head's first movement area, x 1.5..2.0.
            Vec3 position = new Vec3(1.19, FLOOR_Y, 0.5);
            f.player.xRot = -90.0F; // facing +X
            f.place(position, new Vec3(0, -FALL, 0));

            blockEvent(f, westPiston, 4, westState);
            f.input(NONE);
            assertExact(f.tick(position, true));

            blockEvent(f, upPiston, 1, upState);
            f.input(NONE);
            assertExact(f.tick(position, true));

            // The west head's movement area at progress 0.5 is x 1.0..1.5: 0.49 deep, so 0.5 west. The up head's at
            // progress 0 is y 64.0..64.5: 0.5 deep, so 0.51 up.
            double west = -(Math.min(position.x + 0.3 - 1.0, 0.5) + 0.01);
            double up = 0.5 + 0.01;
            // LivingEntity#travel in the air: Player#getFlyingSpeed times the 0.98F impulse, facing +X.
            double travelX = (double) 0.98F * (double) 0.02F;
            // The fall stops 0.01 down on the head top at 64.5, and the step candidate 0.5 reaches the west head's
            // top at 65.0.
            double travelY = -0.01 + 0.5;
            f.input(FORWARD);
            position = position.add(new Vec3(west + travelX, up + travelY + extraY, 0));
            return f.tick(position, true);
        }
    }

    /** An extending piston's block event; {@code direction} is Direction#get3DDataValue of its facing. */
    private static void blockEvent(JavaTickFixture f, BlockPos pos, int direction, int state) {
        var packet = new ClientboundBlockEvent(pos, 0, direction, REGISTRY.blockIndex(state));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.BLOCK_EVENT, packet, false);
        new PacketBlockAction().onBlockEvent(event, f.player, packet);
        new PacketWorldReaderTwentySix().handleBlockEvent(f.player, event, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
