package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import org.junit.Test;

/** Teleports that only the client's accept removes stay bounded for a client that withholds its accepts. */
public class PendingTeleportRetentionTest {
    @Test
    public void withheldTeleportsKeepTheNewest() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        player.movementPlatform = MovementPlatform.JAVA;
        try {
            var teleports = player.getSetbackTeleportUtil();
            int cap = intConstant("MAX_PENDING_JAVA_TELEPORTS");
            int count = cap + 16;
            // An entity-to-self teleport has no ID; its own echo removes it.
            teleports.addImmediatePlayerTeleport(new Vec3(0, 64, 0), Vec3.ZERO, new RelativeFlag(0), 10, 0, 0, 0, 0);
            for (int id = 1; id <= count; id++) {
                teleports.addSentTeleport(new Vec3(id, 64, 0), 10 + id, new RelativeFlag(0), true, id);
            }

            assertEquals(cap + 1, teleports.pendingTeleports.size());
            assertTrue(teleports.pendingTeleports.peekFirst().isPositionOnly());
            TeleportData oldest = teleports.pendingTeleports.stream()
                    .filter(pending -> !pending.isPositionOnly())
                    .findFirst()
                    .orElseThrow();
            assertEquals(count - cap + 1, oldest.getTeleportId());
            assertEquals(count, teleports.pendingTeleports.peekLast().getTeleportId());
            assertEquals(count, teleports.getRequiredSetBack().getTeleportData().getTeleportId());

            // The newest is the one Paper honours, and accepting it still completes the setback.
            player.lastTransactionReceived.set(10 + count);
            assertTrue(
                    teleports.checkExactJavaTeleportQueue(count, 64, 0, count).isTeleport());
            assertTrue(teleports.pendingTeleports.isEmpty());
            assertFalse(teleports.isPendingSetback());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static int intConstant(String name) throws Exception {
        Field field = SetbackTeleportUtil.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
