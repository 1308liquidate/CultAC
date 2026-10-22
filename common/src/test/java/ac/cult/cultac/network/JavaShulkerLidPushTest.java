package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketBlockAction;
import ac.cult.cultac.events.packets.worldreader.PacketWorldReaderTwentySix;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaShulkerLidPushTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final double FALL = 0.0784000015258789;
    private static final BlockPos BOX = new BlockPos(0, FLOOR_Y, 0);

    /**
     * A 26.1 client standing on a shulker box while it opens. Each ShulkerBoxBlockEntity#updateAnimation after
     * LocalPlayer#sendPosition raises the progress by 0.1F and moves the player up by the height of
     * Shulker#getProgressDeltaAabb plus 0.01, while the lid it lands on is ShulkerBoxBlockEntity#getBoundingBox at half
     * the progress. A teleport lifts the player above the lid, so a shove is followed by a fall past the lid.
     */
    @Test
    void aShovedPlayerFallingPastAnOpeningShulkerLidIsNotOnGround() throws Exception {
        rideOpeningLid(6);
    }

    private static void rideOpeningLid(int ticks) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
            int box = REGISTRY.block("minecraft:shulker_box").defaultState();
            f.player.compensatedWorld.updateBlock(BOX, box);
            double y = lidTop(0.0F);
            f.place(new Vec3(0.5, y, 0.5), new Vec3(0, -FALL, 0));
            f.player.onGround = f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, y, 0.5), true));

            blockEvent(f, BOX, 1, 1, box);
            float progress = 0.0F;
            double velocity = -FALL;
            for (int tick = 0; tick < ticks; tick++) {
                if (tick == 2) {
                    y = BOX.getY() + 1.33;
                    velocity = 0.0;
                    f.teleport(new Vec3(0.5, y, 0.5), new RelativeFlag(0));
                }
                double lid = lidTop(progress);
                boolean onGround = y + velocity <= lid;
                if (onGround) {
                    y = lid;
                    velocity = -FALL;
                } else {
                    y += velocity;
                    velocity = (velocity - 0.08) * (double) 0.98F;
                }
                f.input(NONE);
                assertExact(f.tick(new Vec3(0.5, y, 0.5), onGround));

                if (progress < 1.0F) {
                    float progressOld = progress;
                    progress = Math.min(progress + 0.1F, 1.0F);
                    double bottom = pushTop(progressOld);
                    double top = pushTop(progress);
                    if (y < top && y + (double) 1.8F > bottom) y += top - bottom + 0.01;
                }
            }
        }
    }

    /** Shulker#getProgressAabb at ShulkerBoxBlockEntity#getBoundingBox's half progress, at the box's bottom center. */
    private static double lidTop(float progress) {
        return 1.0 + (double) (0.5F * progress) + BOX.getY();
    }

    /** The top of Shulker#getProgressDeltaAabb for {@code progress}, at the box's bottom center. */
    private static double pushTop(float progress) {
        return 1.0 + (double) progress + BOX.getY();
    }

    private static void blockEvent(JavaTickFixture f, BlockPos pos, int action, int parameter, int state) {
        var packet = new ClientboundBlockEvent(pos, action, parameter, REGISTRY.blockIndex(state));
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
