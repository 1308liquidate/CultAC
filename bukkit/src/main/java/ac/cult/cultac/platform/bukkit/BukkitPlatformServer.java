package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.protocol.util.SpigotReflectionUtil;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.sender.Sender;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public class BukkitPlatformServer implements PlatformServer {

    @Override
    public String getPlatformImplementationString() {
        return Bukkit.getVersion();
    }

    @Override
    public void dispatchCommand(Sender sender, String command) {
        CommandSender commandSender =
                CultACBukkitLoaderPlugin.INSTANCE.getBukkitSenderFactory().reverse(sender);
        Bukkit.dispatchCommand(commandSender, command);
    }

    @Override
    public Sender getConsoleSender() {
        return CultACBukkitLoaderPlugin.INSTANCE.getBukkitSenderFactory().map(Bukkit.getConsoleSender());
    }

    @Override
    public void registerOutgoingPluginChannel(String name) {
        CultACBukkitLoaderPlugin.LOADER
                .getServer()
                .getMessenger()
                .registerOutgoingPluginChannel(CultACBukkitLoaderPlugin.LOADER, name);
    }

    @Override
    public void forwardAlert(String message) {
        ac.cult.cultac.events.packets.ProxyAlertMessenger.sendPluginMessage(message);
    }

    @Override
    public boolean isProxyForwardingEnabled() {
        return configBoolean("spigot.yml", "settings.bungeecord")
                || configBoolean("paper.yml", "settings.velocity-support.enabled")
                || configBoolean("config/paper-global.yml", "proxies.velocity.enabled");
    }

    private static boolean configBoolean(String file, String key) {
        var path = new java.io.File(file);
        return path.isFile()
                && org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(path)
                        .getBoolean(key);
    }

    @Override
    public void applyValidationBlock(
            ac.cult.cultac.platform.api.player.PlatformPlayer player,
            int x,
            int y,
            int z,
            String state,
            boolean packetOnly,
            Runnable applied) {
        BukkitValidationBlocks.apply(player, x, y, z, state, packetOnly, applied);
    }

    @Override
    public double getTPS() {
        // Folia throws UnsupportedOperationException on calling getTPS(), there is no API for getting TPS on Folia
        if (CultAPI.INSTANCE.getPlatform() == Platform.FOLIA) {
            return Double.NaN;
        }
        return SpigotReflectionUtil.getTPS();
    }
}
