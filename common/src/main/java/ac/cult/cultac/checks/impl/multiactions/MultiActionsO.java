package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.checks.type.RotationListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.RotationUpdate;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import org.jetbrains.annotations.NotNull;

// cinematic camera doesn't apply in inventories.
@CheckData(
        name = "MultiActionsO",
        stableKey = "cult.multiactions.inventory_rotation",
        description = "Rotating while in an inventory")
public class MultiActionsO extends Check implements RotationListener, ClientTickEndListener {
    // A boat rider's Rot awaiting the tick end: the client sends it before the vehicle move that carries the turn
    // AbstractBoat#positionRider gave it this tick.
    private boolean boatLookPending;
    private float yawBefore;
    private float pitchBefore;
    private float sentYaw;
    private float sentPitch;

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
        // positionRider runs once per client tick, so only a tick's first Rot can carry a boat's turn.
        if (!boatLookPending && ridesBoat()) {
            boatLookPending = true;
            yawBefore = player.yawBeforeLook;
            pitchBefore = rotationUpdate.getFrom().pitch();
            sentYaw = rotationUpdate.getTo().yaw();
            sentPitch = rotationUpdate.getTo().pitch();
            return;
        }
        flagRotation();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (!boatLookPending) return;
        boatLookPending = false;
        if (!player.boatYawFollowsTurn() || sentYaw != player.yawWithoutLook(yawBefore) || sentPitch != pitchBefore) {
            flagRotation();
        }
    }

    private boolean ridesBoat() {
        PacketEntity vehicle = player.compensatedEntities.getSelf().getRiding();
        return vehicle != null && vehicle.isBoat();
    }

    private void flagRotation() {
        if (flag() && shouldModifyPackets()) {
            player.closeInventorySafely();
        }
    }
}
