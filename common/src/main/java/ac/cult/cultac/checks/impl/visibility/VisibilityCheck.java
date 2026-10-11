package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

abstract class VisibilityCheck extends Check implements CheckListener {
    protected final ClientVisibilityProcessor visibility;

    protected VisibilityCheck(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player);
        this.visibility = visibility;
    }

    protected void reject(PacketReceiveEvent<?> event, String detail) {
        if (flag(detail) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
