package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "MultiActionsH",
        stableKey = "cult.multiactions.inventory_move",
        description = "Moving while in an inventory",
        experimental = true)
public class MultiActionsH extends Check implements PostPredictionListener, ClientTickEndListener {
    public MultiActionsH(CultPlayer player) {
        super(player);
    }

    public boolean vehicleJumping;

    private ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump autoJump() {
        return player.checkManager.getListener(ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaAutoJump.class);
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (player.supportsEndTick()
                && player.openWindow.mustBeOpen()
                && autoJump().hasManualMovementInput()
                && flag()
                && shouldModifyPackets()) {
            player.closeInventorySafely();
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (player.supportsEndTick() || !player.openWindow.mustBeOpen() || complete.isTeleport() || complete.isExempt())
            return;
        PredictionResult result = complete.getPredictionResult();
        if (result.hasEffectiveFlags()) return;
        // The Java 3.0 runner derives minimum legal input for each collision
        // candidate. A valid zero-input candidate wins, as in upstream's sorter.
        for (PredictionResult reality : result.getRealities()) {
            if (!reality.hasEffectiveFlags()
                    && !reality.getInitialStartingVel().isJump()
                    && reality.getInputVectorAndVerticals().horizontalDistanceSqr() == 0) return;
        }
        boolean moving = result.getInputVectorAndVerticals().horizontalDistanceSqr() != 0;
        boolean jumping = player.inVehicle()
                ? vehicleJumping
                : result.getInitialStartingVel().isJump();
        if ((moving || jumping && !autoJump().jumpedThisTick()) && flag() && shouldModifyPackets())
            player.closeInventorySafely();
    }
}
