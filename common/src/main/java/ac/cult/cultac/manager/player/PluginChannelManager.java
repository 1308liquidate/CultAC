package ac.cult.cultac.manager.player;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.exploit.ExploitC;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;

@Getter
public class PluginChannelManager extends CultProcessor implements CheckListener {
    public PluginChannelManager(CultPlayer cultPlayer) {
        super(cultPlayer);
    }

    private final Set<String> registeredChannels = new HashSet<>();
    private String brand = null;

    @CultPacketHandler
    public void onCustomPayload(
            PacketReceiveEvent<ServerboundCustomPayload> event, CultPlayer player, ServerboundCustomPayload packet) {
        if (event.isCancelled()) {
            return;
        }
        String channel = packet.channel();
        if (!"minecraft:brand".equals(channel)) {
            handleChannelRegistered(channel);
        }
        handle(channel, event.getPacket().data(), event);
    }

    public void handle(String channelName, byte[] payload, PacketReceiveEvent event) {
        if (SmoketestControlBridge.enabled()) {
            if (SmoketestControlBridge.handle(player, channelName, payload)) {
                return;
            }
            if (SmoketestSnapshotBridge.handle(player, channelName, payload)) {
                return;
            }
            if (SmoketestBlockEvidenceBridge.handle(player, channelName, payload)) {
                return;
            }
        }

        final int length = payload.length;
        // check if the data sent is too large
        if (length > 8192) {
            player.checkManager.getListener(ExploitC.class).flag("Plugin message data too large: " + length);
            event.setCancelled(true);
            player.onPacketCancel();
            return;
        }
        // handle the brand
        if (("minecraft:brand".equals(channelName) || "MC|Brand".equals(channelName)) && payload.length > 0) {
            if (this.brand != null) {
                // TODO: Brand spoof check again
                // player.checkManager.getListener(BrandSpoofA.class).flag("Sent multiple brands");
                return;
            }
            updateBrand(readBrandPayload(payload));
        }
    }

    public void handleChannelRegistered(String channel) {
        if (channel == null || channel.isBlank() || registeredChannels.contains(channel)) {
            return;
        }
        int total = registeredChannels.size() + 1;
        if (total > 96) {
            player.checkManager.getListener(ExploitC.class).flag("Too many plugin channels registered: " + total);
            return;
        }
        registeredChannels.add(channel);
    }

    private void updateBrand(String brand) {
        if (brand == null || brand.isBlank() || this.brand != null) {
            return;
        }
        this.brand = CultAPI.INSTANCE.getConfigManager().simplifyBrand(brand);
    }

    private String readBrandPayload(byte[] data) {
        byte[] minusLength = new byte[data.length - 1];
        System.arraycopy(data, 1, minusLength, 0, minusLength.length);
        return new String(minusLength);
    }
}
