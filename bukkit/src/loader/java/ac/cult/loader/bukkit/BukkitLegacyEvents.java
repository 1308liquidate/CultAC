package ac.cult.loader.bukkit;

import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;

@SuppressWarnings("removal")
final class BukkitLegacyEvents implements AutoCloseable {
    private final EventBus eventBus;
    private final GrimPlugin plugin;

    BukkitLegacyEvents(GrimAbstractAPI api, Object nativePlugin) {
        eventBus = api.getEventBus();
        plugin = api.getGrimPlugin(nativePlugin);
        // Bridge Cult events → legacy Bukkit Event API so pre-1.3 plugins that
        // listened for ac.grim.grimac.api.events.* Bukkit events keep working.
        // Typed channel subscriptions here are plugin-bound so they go away if
        // CultAC itself is disabled.

        eventBus.get(ac.grim.grimac.api.event.events.GrimJoinEvent.class).onJoin(plugin, (user) -> {
            Bukkit.getPluginManager().callEvent(new ac.grim.grimac.api.events.GrimJoinEvent(user));
        });

        eventBus.get(ac.grim.grimac.api.event.events.GrimQuitEvent.class).onQuit(plugin, (user) -> {
            Runnable notifyQuit =
                    () -> Bukkit.getPluginManager().callEvent(new ac.grim.grimac.api.events.GrimQuitEvent(user));
            if (Bukkit.isPrimaryThread()) {
                CompletableFuture.runAsync(notifyQuit).join();
            } else {
                notifyQuit.run();
            }
        });

        eventBus.get(ac.grim.grimac.api.event.events.GrimReloadEvent.class).onReload(plugin, (success) -> {
            Bukkit.getPluginManager().callEvent(new ac.grim.grimac.api.events.GrimReloadEvent(success));
        });

        eventBus.subscribe(
                plugin,
                ac.grim.grimac.api.event.events.FlagEvent.class,
                event -> {
                    ac.grim.grimac.api.events.FlagEvent bukkitEvent = new ac.grim.grimac.api.events.FlagEvent(
                            event.getUser(), event.getCheck(), event::getVerbose);
                    Bukkit.getPluginManager().callEvent(bukkitEvent);
                    event.setCancelled(event.isCancelled() || bukkitEvent.isCancelled());
                },
                0,
                false,
                BukkitLegacyEvents.class);

        eventBus.get(ac.grim.grimac.api.event.events.CommandExecuteEvent.class)
                .onCommandExecute(plugin, (user, check, verbose, command, cancelled) -> {
                    ac.grim.grimac.api.events.CommandExecuteEvent bukkitEvent =
                            new ac.grim.grimac.api.events.CommandExecuteEvent(user, check, verbose, command);
                    Bukkit.getPluginManager().callEvent(bukkitEvent);
                    return cancelled || bukkitEvent.isCancelled();
                });

        eventBus.get(ac.grim.grimac.api.event.events.CompletePredictionEvent.class)
                .onCompletePrediction(plugin, (user, check, offset, cancelled) -> {
                    // Legacy Bukkit event has a verbose field that the new channel event does not; pass empty.
                    ac.grim.grimac.api.events.CompletePredictionEvent bukkitEvent =
                            new ac.grim.grimac.api.events.CompletePredictionEvent(user, check, "", offset);
                    Bukkit.getPluginManager().callEvent(bukkitEvent);
                    return cancelled || bukkitEvent.isCancelled();
                });
    }

    @Override
    public void close() {
        eventBus.unregisterAllListeners(plugin);
    }
}
