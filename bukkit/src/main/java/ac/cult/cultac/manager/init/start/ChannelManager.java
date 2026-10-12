package ac.cult.cultac.manager.init.start;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

public class ChannelManager implements StartableInitable, PluginMessageListener {

    private final Set<String> CHANNELS = ImmutableSet.of(
            "lunarclient:pm", "fml:handshake", "fml:play", "feather:client", "ac:handshake", "badlion:modapi");

    @Override
    public void start() {
        for (String channel : CHANNELS) {
            registerChannel(channel);
        }
    }

    private void registerChannel(String channelName) {
        final Messenger messenger = ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin.LOADER
                .getServer()
                .getMessenger();
        messenger.registerOutgoingPluginChannel(
                ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin.LOADER, channelName);
        messenger.registerIncomingPluginChannel(
                ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin.LOADER, channelName, this);
        ac.cult.cultac.runtime.RuntimeEnvironment.track(
                this,
                () -> messenger.unregisterIncomingPluginChannel(
                        ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin.LOADER, channelName, this));
    }

    @Override
    public void onPluginMessageReceived(
            @NotNull String channelName, @NotNull Player bukkitPlayer, @NotNull byte[] data) {}
}
