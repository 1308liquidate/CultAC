package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.utils.data.ItemUseBurstClock;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Proven impossible post-use bursts, independent of the desync percentage policy. */
public final class ItemUseBurst extends Check implements CheckListener {
    private final ItemUseBurstClock clock = new ItemUseBurstClock();
    private final Set<Integer> expectedProofs = new HashSet<>();
    private final LongSupplier now;

    public ItemUseBurst(CultPlayer player) {
        this(player, System::nanoTime);
    }

    ItemUseBurst(CultPlayer player, LongSupplier now) {
        super(
                player,
                CheckInfo.builder()
                        .name("ItemUseBurst")
                        .stableKey("cult.movement.item_use_burst")
                        .description("Reported impossible client ticks after an item-use proof")
                        .setback(0)
                        .build());
        this.now = now;
    }

    public void expectProof(int transaction) {
        expectedProofs.add(transaction);
    }

    public void acknowledge(int transaction, long sentAt) {
        if (expectedProofs.remove(transaction)) clock.proof(sentAt);
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.usesModernJavaItemUse() || !player.supportsEndTick() || event.isCancelled()) return;
        boolean frozen =
                player.packetStateData.serverTicksFrozen && player.packetStateData.serverFrozenTickStepsRemaining == 0;
        if (clock.tick(now.getAsLong(), periodNanos(player.packetStateData.serverTickRate, frozen))) {
            if (flag("tick=" + player.packetStateData.acceptedClientTick + ",proof="
                    + player.lastTransactionReceived.get())) {
                setbackIfAboveSetbackVLNonSimulating();
                if (shouldModifyPackets()) {
                    event.setCancelled(true);
                    player.onPacketCancel();
                }
            }
        }
    }

    public void reset() {
        clock.reset();
        expectedProofs.clear();
    }

    public static long periodNanos(float rate, boolean frozen) {
        // TickRateManager#setTickRate -> millisecondsPerTick -> Minecraft#getTickTargetMillis.
        // Preserve the client's long-to-float rounding and minimum rate, rather than 1000/rate.
        long nanos = (long) (1_000_000_000D / Math.max(rate, 1F));
        float millis = frozen ? 50F : Math.max(50F, (float) nanos / 1_000_000F);
        return (long) (millis * 1_000_000D);
    }
}
