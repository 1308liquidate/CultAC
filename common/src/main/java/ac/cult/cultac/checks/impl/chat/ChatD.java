package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChat;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundChatCommandSigned;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.cult.cultac.protocol.value.ChatVisibility;
import ac.cult.cultac.protocol.value.ClientInformation;

@CheckData(name = "ChatD", stableKey = "cult.exploit.chat_while_hidden", description = "Chatting while chat is hidden")
public class ChatD extends Check implements CheckListener {
    private ClientInformation information;

    public ChatD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onChatMessage(PacketReceiveEvent<ServerboundChat> event, CultPlayer player, ServerboundChat packet) {
        check(event);
    }

    @CultPacketHandler
    public void onChatCommandUnsigned(
            PacketReceiveEvent<ServerboundChatCommandSigned> event,
            CultPlayer player,
            ServerboundChatCommandSigned packet) {
        check(event);
    }

    @CultPacketHandler
    public void onChatCommand(
            PacketReceiveEvent<ServerboundChatCommand> event, CultPlayer player, ServerboundChatCommand packet) {
        check(event);
    }

    private void check(PacketReceiveEvent event) {
        if (information != null
                && information.chatVisibility() == ChatVisibility.HIDDEN
                && flag()
                && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler
    public void onClientInformation(
            PacketReceiveEvent<ServerboundClientInformation> event,
            CultPlayer player,
            ServerboundClientInformation packet) {
        observe(packet.information());
    }

    public void observe(ClientInformation information) {
        this.information = information;
    }

    public ClientInformation information() {
        return information;
    }
}
