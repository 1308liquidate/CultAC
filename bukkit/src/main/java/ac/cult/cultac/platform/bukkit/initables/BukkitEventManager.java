package ac.cult.cultac.platform.bukkit.initables;

import ac.cult.cultac.events.bukkit.QuitEvent;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;

public class BukkitEventManager implements StartableInitable {
    public void start() {
        ac.cult.cultac.platform.bukkit.BukkitRuntimeRegistrations.registerEvents(
                new QuitEvent(), CultACBukkitLoaderPlugin.LOADER);
    }
}
