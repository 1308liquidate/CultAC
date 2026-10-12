package ac.cult.cultac.platform.bukkit.scheduler.bukkit;

import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import java.util.Objects;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class BukkitTaskHandle implements TaskHandle {

    private final @NotNull BukkitTask task;

    @Contract(pure = true)
    public BukkitTaskHandle(@NotNull BukkitTask task) {
        this.task = Objects.requireNonNull(task, "task");
        ac.cult.cultac.runtime.RuntimeEnvironment.track(this, task::cancel);
    }

    @Override
    public boolean isSync() {
        return task.isSync();
    }

    @Override
    public boolean isCancelled() {
        return task.isCancelled();
    }

    @Override
    public void cancel() {
        task.cancel();
    }
}
