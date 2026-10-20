package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.RotationListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import org.jetbrains.annotations.NotNull;

// cinematic camera doesn't apply in inventories.
@CheckData(
        name = "MultiActionsO",
        stableKey = "cult.multiactions.inventory_rotation",
        description = "Rotating while in an inventory",
        experimental = true)
public class MultiActionsO extends Check implements RotationListener {

    public MultiActionsO(CultPlayer player) {
        super(player);
    }

    @Override
    public void process(@NotNull RotationUpdate rotationUpdate) {
        if (player.packetStateData.lastPacketWasTeleport) return;
        // LocalPlayer#sendChanges sends a passenger's Rot every tick, changed or not, where sendPosition only sends a
        // Rot that changed. A look that did not change is not a rotation.
        if (rotationUpdate.getFrom().equals(rotationUpdate.getTo())) return;
        if (!player.openWindow.mustBeOpen() || player.openWindow.getTicksOpen() == 0) return;
        if (flag() && shouldModifyPackets()) {
            player.closeInventorySafely();
        }
    }
}
