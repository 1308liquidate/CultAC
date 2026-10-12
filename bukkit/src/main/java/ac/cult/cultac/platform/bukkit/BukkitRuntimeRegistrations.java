package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.runtime.RuntimeEnvironment;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

public final class BukkitRuntimeRegistrations {
    private BukkitRuntimeRegistrations() {}

    public static void registerEvents(Listener listener, Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        RuntimeEnvironment.track(listener, () -> HandlerList.unregisterAll(listener));
    }
}
