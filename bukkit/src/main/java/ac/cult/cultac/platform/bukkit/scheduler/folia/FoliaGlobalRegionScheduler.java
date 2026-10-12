package ac.cult.cultac.platform.bukkit.scheduler.folia;

import ac.cult.cultac.platform.api.scheduler.GlobalRegionScheduler;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.grim.grimac.api.plugin.GrimPlugin;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

public class FoliaGlobalRegionScheduler implements GlobalRegionScheduler {

    private final io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalRegionScheduler =
            Bukkit.getGlobalRegionScheduler();

    @Override
    public void execute(@NotNull GrimPlugin plugin, @NotNull Runnable task) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        globalRegionScheduler.execute(CultACBukkitLoaderPlugin.LOADER, guarded_task);
    }

    @Override
    public TaskHandle run(@NotNull GrimPlugin plugin, @NotNull Runnable task) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new FoliaTaskHandle(globalRegionScheduler.run(
                CultACBukkitLoaderPlugin.LOADER, ac.cult.reload.host.Callbacks.run(guarded_task)));
    }

    @Override
    public TaskHandle runDelayed(@NotNull GrimPlugin plugin, @NotNull Runnable task, long delay) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new FoliaTaskHandle(globalRegionScheduler.runDelayed(
                CultACBukkitLoaderPlugin.LOADER, ac.cult.reload.host.Callbacks.run(guarded_task), delay));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull GrimPlugin plugin, @NotNull Runnable task, long initialDelayTicks, long periodTicks) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new FoliaTaskHandle(globalRegionScheduler.runAtFixedRate(
                CultACBukkitLoaderPlugin.LOADER,
                ac.cult.reload.host.Callbacks.run(guarded_task),
                initialDelayTicks,
                periodTicks));
    }

    @Override
    public void cancel(@NotNull GrimPlugin plugin) {
        if (ac.cult.cultac.runtime.RuntimeEnvironment.host() == null)
            globalRegionScheduler.cancelTasks(CultACBukkitLoaderPlugin.LOADER);
        else ac.cult.cultac.runtime.RuntimeEnvironment.host().resources().close();
    }
}
