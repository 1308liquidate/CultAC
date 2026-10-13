package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.movement.timer.ItemUseBurst;
import ac.cult.cultac.checks.impl.prediction.ItemUseBurstResult;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ItemUseBurstBalance;
import ac.cult.cultac.utils.data.ItemUseDesyncHistory;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.Locale;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Movement policy for legal but persistently desynchronized use. Never calls Check.flag. */
public final class ItemUseDesync extends CultProcessor implements PostPredictionListener, ClientTickEndListener {
    private final ItemUseDesyncHistory history = new ItemUseDesyncHistory();
    private final ItemUseBurstBalance burstBalance = new ItemUseBurstBalance();
    private final LongSupplier now;
    private int pendingTick = -1;
    private Boolean pending;
    private int pendingBurstTick = -1;
    private boolean pendingBurst;
    private boolean pendingBurstSetback;

    public ItemUseDesync(CultPlayer player) {
        this(player, System::nanoTime);
    }

    ItemUseDesync(CultPlayer player, LongSupplier now) {
        super(player);
        this.now = now;
    }

    @Override
    public void onReload(ConfigManager config) {
        history.configure(
                config.getIntElse("cult.prediction.item-use-desync.history-ticks", 100),
                config.getDoubleElse("cult.prediction.item-use-desync.max-percent", 20));
        burstBalance.configure(
                config.getDoubleElse("cult.prediction.item-use-burst-balance.max-credit-ticks", 8),
                config.getDoubleElse("cult.prediction.item-use-burst-balance.setback-debt-ticks", 4),
                config.getDoubleElse("cult.prediction.item-use-desync.max-percent", 20));
    }

    /** Called only on the ordered packet stream, before the use state is cleared. */
    public void onUseCompleted() {
        burstBalance.complete(now.getAsLong(), player.clientItemUse.obligationMultiplier(player.getClientVersion()));
    }

    public float postUseMultiplier() {
        return burstBalance.multiplier(player.packetStateData.acceptedClientTick, now.getAsLong(), period());
    }

    private long period() {
        boolean frozen =
                player.packetStateData.serverTicksFrozen && player.packetStateData.serverFrozenTickStepsRemaining == 0;
        return ItemUseBurst.periodNanos(player.packetStateData.serverTickRate, frozen);
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (!player.usesModernJavaItemUse() || complete.isTeleport() || complete.isExempt()) return;
        var evidence = complete.getPredictionResult().getItemUseResult();
        double threshold = player.checkManager.getListener(NoSlow.class).threshold();
        collectBurst(complete.getPredictionResult(), threshold);
        if (evidence == null || !evidence.state().required() || evidence.deterministic(threshold)) return;
        boolean alreadyDesynced = pendingTick == evidence.state().tick() && Boolean.TRUE.equals(pending);
        pendingTick = evidence.state().tick();
        pending = alreadyDesynced || evidence.desynced(threshold);
        // Decide before SetbackTeleportUtil can promote this movement to its rollback
        // anchor. The accounting closes at tick-end and survives movement setbacks.
        if (pending && !alreadyDesynced && history.wouldExceed(true) && !player.noSetbackPermission)
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
    }

    private void collectBurst(PredictionResult result, double threshold) {
        double legal = Double.POSITIVE_INFINITY;
        double slowed = Double.POSITIVE_INFINITY;
        for (var reality : result.getRealities()) {
            var evidence = reality.getItemUseBurstResult();
            if (evidence == null) return;
            legal = Math.min(legal, evidence.legalOffset());
            slowed = Math.min(slowed, evidence.slowedOffset());
        }
        int tick = player.packetStateData.acceptedClientTick;
        if (pendingBurstTick != tick) pendingBurstSetback = false;
        pendingBurst =
                (pendingBurstTick == tick && pendingBurst) || new ItemUseBurstResult(legal, slowed).unslowed(threshold);
        pendingBurstTick = tick;
        // Correct while the rollback anchor still precedes this movement. Accounting
        // remains once per finalized tick, with no flag or artificial prediction failure.
        if (pendingBurst
                && !pendingBurstSetback
                && !player.noSetbackPermission
                && burstBalance.wouldSetback(tick, now.getAsLong(), period())) {
            pendingBurstSetback = true;
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
        }
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (!player.usesModernJavaItemUse()) return;
        int tick = player.packetStateData.acceptedClientTick;
        boolean finalized = pending != null && pendingTick == tick;
        if (finalized) history.add(pending);
        var debug = player.checkManager.getDebugHandler();
        boolean output = debug.hasItemSlowListeners();
        double before = output ? burstBalance.balance() : 0;
        boolean setback = burstBalance.tick(
                tick, now.getAsLong(), period(), pendingBurstTick == tick && pendingBurst, finalized && !pending);
        if (setback && !pendingBurstSetback && !player.noSetbackPermission)
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
        if (output) {
            boolean reward = finalized && !pending;
            double after = burstBalance.balance();
            boolean requested = pendingBurstSetback || setback && !player.noSetbackPermission;
            if (reward || before != after || requested) {
                double creditBefore = before == 0 ? 0 : -before;
                double creditAfter = after == 0 ? 0 : -after;
                double delta = creditAfter - creditBefore;
                debug.relayItemSlow(() -> Component.text(
                        String.format(
                                Locale.ROOT,
                                "tick=%d %s %+.3f (%s) balance=%.3f -> %.3f setbackBelow=%.3f%s",
                                tick,
                                reward ? "REWARD" : "DEBIT",
                                delta,
                                reward ? "slowed tick" : "unslowed burst",
                                creditBefore,
                                creditAfter,
                                -burstBalance.setbackDebt(),
                                requested ? " setback=requested" : ""),
                        reward ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
        }
        pending = null;
        pendingBurst = false;
        pendingBurstSetback = false;
    }

    public void reset() {
        pending = null;
        pendingTick = -1;
        pendingBurst = false;
        pendingBurstTick = -1;
        pendingBurstSetback = false;
        history.clear();
        burstBalance.reset();
    }

    public ItemUseDesyncHistory history() {
        return history;
    }

    public double burstDebt() {
        return burstBalance.balance();
    }

    public String describeBurstBalance() {
        double balance = burstBalance.balance() == 0 ? 0 : -burstBalance.balance();
        return String.format(Locale.ROOT, "balance=%.3f setbackBelow=%.3f", balance, -burstBalance.setbackDebt());
    }
}
