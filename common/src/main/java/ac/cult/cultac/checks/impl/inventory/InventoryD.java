package ac.cult.cultac.checks.impl.inventory;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.checks.type.BlockBreakListener;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.checks.type.LegacyPacketEventSemantics;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSpectatorAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.BlockBreak;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.grim.grimac.api.storage.verbose.Verbose;

/**
 * A hand action no vanilla client can perform while its inventory is open: an entity interaction, a spectate, a stab,
 * an item use, a block place, a block break or a swing that no drop click follows.
 */
@CheckData(
        name = "InventoryD",
        stableKey = "cult.inventory.hand_action",
        description = "Performed a hand action while in an inventory")
public class InventoryD extends BlockPlaceCheck implements BlockBreakListener, DecodedPacketReceiveListener {
    // Shape index == ACTION_* constant value.
    private static final Verbose V = Verbose.of("action=interact")
            .or("action=attack")
            .or("action=interact_at")
            .or("action=spectate")
            .or("action=stab")
            .or("action=use_item")
            .or("action=place")
            .or("action={digging_lower}")
            .or("action=swing");

    private static final int ACTION_INTERACT = 0;
    private static final int ACTION_ATTACK = 1;
    private static final int ACTION_INTERACT_AT = 2;
    private static final int ACTION_SPECTATE = 3;
    private static final int ACTION_STAB = 4;
    private static final int ACTION_USE_ITEM = 5;
    private static final int ACTION_PLACE = 6;
    private static final int ACTION_BREAK = 7;
    private static final int ACTION_SWING = 8;

    // A swing seen with the inventory open, which only a following drop click explains.
    private boolean swing;

    public InventoryD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        int shape = switch (packet.action()) {
            case INTERACT -> ACTION_INTERACT;
            case ATTACK -> ACTION_ATTACK;
            case INTERACT_AT -> ACTION_INTERACT_AT;
        };
        if (player.openWindow.mustBeOpen()) flagAndCancel(event, V.write(verbose(), shape));
    }

    @CultPacketHandler
    public void onSpectate(
            PacketReceiveEvent<ServerboundSpectatorAction> event,
            CultPlayer player,
            ServerboundSpectatorAction packet) {
        if (player.openWindow.mustBeOpen()) flagAndCancel(event, V.write(verbose(), ACTION_SPECTATE));
    }

    @CultPacketHandler
    public void onStab(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (packet.action() == PlayerAction.STAB && player.openWindow.mustBeOpen()) {
            flagAndCancel(event, V.write(verbose(), ACTION_STAB));
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (player.openWindow.mustBeOpen()) flagAndCancel(event, V.write(verbose(), ACTION_USE_ITEM));
    }

    @Override
    public void onBlockPlace(BlockPlace place) {
        if (player.openWindow.mustBeOpen()
                && flag(V.write(verbose(), ACTION_PLACE))
                && shouldModifyPackets()
                && shouldCancel()) {
            place.resync();
            player.closeInventorySafely();
        }
    }

    @Override
    public void onBlockBreak(BlockBreak blockBreak) {
        if (!player.openWindow.mustBeOpen()) return;

        if (blockBreak.action == PlayerAction.ABORT_DESTROY_BLOCK && player.openWindow.getTicksOpen() == 0) {
            return;
        }

        if (flag(V.write(verbose(), ACTION_BREAK).uint(VerboseCodecs.digging(blockBreak.action)))
                && shouldModifyPackets()) {
            blockBreak.cancel();
            player.closeInventorySafely();
        }
    }

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ServerboundSwing) {
            if (swing) flagSwing();
            if (player.openWindow.mustBeOpen()) swing = true;
        } else if (swing && !LegacyPacketEventSemantics.isAsync(event)) {
            swing = false;
            if (!(event.getPacket() instanceof InventoryClick click) || !isDropClick(click)) flagSwing();
        }
    }

    // The swing detection has run only for players with experimental checks since it was MultiActionsS.
    private void flagSwing() {
        if (player.isExperimentalChecks()) flag(V.write(verbose(), ACTION_SWING));
    }

    private void flagAndCancel(PacketReceiveEvent<?> event, Verbose.Writer verbose) {
        if (flag(verbose) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            player.closeInventorySafely();
        }
    }

    private static boolean isDropClick(InventoryClick click) {
        return switch (click.clickType()) {
            case PICKUP, QUICK_MOVE -> click.slot() == -999;
            case THROW -> true;
            default -> false;
        };
    }
}
