package ac.cult.cultac.platform.bukkit.scheduler.bukkit;

import ac.cult.cultac.platform.api.scheduler.RegionScheduler;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.utils.math.Location;
import ac.grim.grimac.api.plugin.GrimPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;

public class BukkitRegionScheduler implements RegionScheduler {

    private final BukkitScheduler bukkitScheduler = Bukkit.getScheduler();

    @Override
    public void execute(
            @NotNull GrimPlugin plugin, @NotNull PlatformWorld world, int chunkX, int chunkZ, @NotNull Runnable task) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        new BukkitTaskHandle(bukkitScheduler.runTask(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }

    @Override
    public void execute(@NotNull GrimPlugin plugin, @NotNull Location location, @NotNull Runnable task) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        new BukkitTaskHandle(bukkitScheduler.runTask(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }

    @Override
    public TaskHandle run(
            @NotNull GrimPlugin plugin, @NotNull PlatformWorld world, int chunkX, int chunkZ, @NotNull Runnable task) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(bukkitScheduler.runTask(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }

    @Override
    public TaskHandle run(@NotNull GrimPlugin plugin, @NotNull Location location, @NotNull Runnable task) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(bukkitScheduler.runTask(CultACBukkitLoaderPlugin.LOADER, guarded_task));
    }

    @Override
    public TaskHandle runDelayed(
            @NotNull GrimPlugin plugin,
            @NotNull PlatformWorld world,
            int chunkX,
            int chunkZ,
            @NotNull Runnable task,
            long delayTicks) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(
                bukkitScheduler.runTaskLater(CultACBukkitLoaderPlugin.LOADER, guarded_task, delayTicks));
    }

    @Override
    public TaskHandle runDelayed(
            @NotNull GrimPlugin plugin, @NotNull Location location, @NotNull Runnable task, long delayTicks) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(
                bukkitScheduler.runTaskLater(CultACBukkitLoaderPlugin.LOADER, guarded_task, delayTicks));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull GrimPlugin plugin,
            @NotNull PlatformWorld world,
            int chunkX,
            int chunkZ,
            @NotNull Runnable task,
            long initialDelayTicks,
            long periodTicks) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(bukkitScheduler.runTaskTimer(
                CultACBukkitLoaderPlugin.LOADER, guarded_task, initialDelayTicks, periodTicks));
    }

    @Override
    public TaskHandle runAtFixedRate(
            @NotNull GrimPlugin plugin,
            @NotNull Location location,
            @NotNull Runnable task,
            long initialDelayTicks,
            long periodTicks) {
        final Runnable guarded_task = ac.cult.cultac.runtime.RuntimeEnvironment.guard(task);

        return new BukkitTaskHandle(bukkitScheduler.runTaskTimer(
                CultACBukkitLoaderPlugin.LOADER, guarded_task, initialDelayTicks, periodTicks));
    }
}
