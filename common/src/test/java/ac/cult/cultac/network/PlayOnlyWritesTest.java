package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.BlockPredictionAckSender;
import io.netty.util.ReferenceCountUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ClientPacketListener#handleConfigurationStart clears the client level; play corrections wait for login. */
class PlayOnlyWritesTest {
    private static final List<BlockPredictionAckSender.TrackedBlockUpdate> UPDATE =
            List.of(new BlockPredictionAckSender.TrackedBlockUpdate(new BlockPos(0, 64, 0), 1));

    private static int drainOutbound(RecordReceiveFixture f) {
        f.channel.runPendingTasks();
        int written = 0;
        Object message;
        while ((message = f.channel.readOutbound()) != null) {
            written++;
            ReferenceCountUtil.release(message);
        }
        return written;
    }

    @Test
    void correctionsAreWrittenOnlyInPlay() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            drainOutbound(f);

            int transaction = f.player.lastTransactionSent.get();
            BlockPredictionAckSender.sendCorrections(f.player, 3, true, UPDATE, List.of());
            assertTrue(drainOutbound(f) > 0);
            assertEquals(transaction + 1, f.player.lastTransactionSent.get());

            f.phase(ConnectionPhase.CONFIGURATION);
            transaction = f.player.lastTransactionSent.get();
            BlockPredictionAckSender.sendCorrections(f.player, 4, true, UPDATE, List.of());
            assertEquals(0, drainOutbound(f));
            assertEquals(transaction, f.player.lastTransactionSent.get());
        }
    }

    @Test
    void poseResyncIsWrittenOnlyInPlay() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            drainOutbound(f);

            f.player.resyncPose();
            assertTrue(drainOutbound(f) > 0);

            f.phase(ConnectionPhase.CONFIGURATION);
            f.player.resyncPose();
            assertEquals(0, drainOutbound(f));
        }
    }
}
