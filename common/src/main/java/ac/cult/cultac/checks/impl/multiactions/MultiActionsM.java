package ac.cult.cultac.checks.impl.multiactions;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.type.ClientTickEndListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.*;
import ac.cult.cultac.protocol.value.*;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "MultiActionsM",
        stableKey = "cult.multiactions.inventory_sneak",
        description = "Sneaking while in an inventory",
        experimental = true)
public class MultiActionsM extends Check implements CheckListener, ClientTickEndListener {
    public MultiActionsM(CultPlayer player) {
        super(player);
    }

    private static final Verbose V = Verbose.of("tick").or("start").or("stop");
    private boolean wasInputSneaking;

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (packet.action() == PlayerCommandAction.PRESS_SHIFT_KEY) onChange(true);
        else if (packet.action() == PlayerCommandAction.RELEASE_SHIFT_KEY) onChange(false);
    }

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        // Pre-1.21.2 input here is synthesized by Via. The original sneak
        // actions are observed by LegacyViaInputBridge before translation.
        if (player.getClientVersion().isOlderThan(ac.cult.cultac.network.protocol.ClientVersion.V_1_21_2)) return;
        if (packet.shift() != wasInputSneaking) onChange(packet.shift());
        wasInputSneaking = packet.shift();
    }

    public void onChange(boolean sneaking) {
        if ((sneaking || player.openWindow.getTicksOpen() != 0)
                && player.openWindow.mustBeOpen()
                && flag(V.write(verbose(), sneaking ? 1 : 2))
                && shouldModifyPackets()) player.closeInventorySafely();
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.supportsEndTick()
                && !player.packetStateData.lastPacketWasTeleport
                && !player.packetStateData.lastPacketWasOnePointSeventeenDuplicate) onTick();
    }

    @Override
    public void onPlayerTickEnd(PacketReceiveEvent event) {
        if (player.supportsEndTick()) onTick();
    }

    private void onTick() {
        if (MultiActionsC.isVerboseSneaking(player)
                && player.openWindow.mustBeOpen()
                && flag(V.write(verbose(), 0))
                && shouldModifyPackets()) player.closeInventorySafely();
    }
}
