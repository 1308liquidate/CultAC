package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionIdTest {
    private static final int FRESH = Short.MIN_VALUE - 1;

    /** Random sends, writes and pongs, known and unknown; every in-flight id stays taken and nothing else does. */
    @Test
    void inFlightIdsAreRejectedThroughEverySendAndAcknowledgement() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        player.user.bind(mock(PlatformPlayer.class));
        try {
            var random = new Random(0x1D5L);
            var listener = new PacketPingListener();
            Map<Integer, CultPlayer.TrackedTransaction> pending = new HashMap<>();
            List<Integer> sent = new ArrayList<>();
            Set<Integer> answered = new LinkedHashSet<>();
            int probes = 0;
            for (int step = 0; step < 20_000; step++) {
                int action = random.nextInt(10);
                if (action < 4 || pending.isEmpty() && sent.isEmpty()) {
                    var transaction = player.createTrackedTransactionPacketForDeferredSend();
                    assertNotNull(transaction);
                    pending.put(transaction.id(), transaction);
                } else if (action < 7 && !pending.isEmpty()) {
                    int id = List.copyOf(pending.keySet()).get(random.nextInt(pending.size()));
                    player.markTrackedTransactionPacketSent(pending.remove(id));
                    sent.add(id);
                } else {
                    int id;
                    int pick = random.nextInt(3);
                    if (pick == 0 && !sent.isEmpty()) id = sent.get(random.nextInt(sent.size()));
                    else if (pick == 1 && !pending.isEmpty())
                        id = List.copyOf(pending.keySet()).get(0);
                    else id = FRESH - 1 - random.nextInt(1000);
                    pong(player, listener, id);
                    int index = sent.indexOf(id);
                    if (index >= 0) {
                        answered.addAll(sent.subList(0, index + 1));
                        sent.subList(0, index + 1).clear();
                    }
                }
                if (step % 7 == 0) {
                    var candidates = new ArrayList<Integer>();
                    candidates.addAll(pending.keySet());
                    candidates.addAll(sent);
                    candidates.addAll(answered);
                    Collections.shuffle(candidates, random);
                    var script = candidates.subList(0, Math.min(candidates.size(), 64));
                    int expected = FRESH;
                    for (int id : script) {
                        if (!pending.containsKey(id) && !sent.contains(id)) {
                            expected = id;
                            break;
                        }
                    }
                    var words = new ArrayList<>(script);
                    words.add(FRESH);
                    int[] next = {0};
                    assertEquals(expected, player.nextTransactionId(() -> words.get(next[0]++)), "step " + step);
                    probes++;
                }
            }
            assertTrue(probes > 2000);
        } finally {
            OfflinePlayers.close(player);
        }
    }

    private static void pong(CultPlayer player, PacketPingListener listener, int id) {
        var pong = new ServerboundPong(id);
        listener.onPong(
                new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.PONG, pong),
                player,
                pong);
    }
}
