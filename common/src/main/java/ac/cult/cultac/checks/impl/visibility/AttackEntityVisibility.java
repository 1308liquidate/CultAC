package ac.cult.cultac.checks.impl.visibility;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

/** Formerly AttackMobVisibility; the stable key keeps its recorded history. */
@CheckData(
        name = "AttackEntityVisibility",
        stableKey = "cult.attack.mob.visibility",
        description = "Attacked a non-player entity through blocks")
public final class AttackEntityVisibility extends VisibilityCheck {
    public AttackEntityVisibility(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, visibility);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet.action() != InteractAction.ATTACK) return;
        var entity = player.compensatedEntities.getEntity(packet.entityId());
        if (entity == null || entity.type == EntityTypeIds.PLAYER) return;
        if (attackHidden(entity)) reject(event, type(entity));
    }
}
