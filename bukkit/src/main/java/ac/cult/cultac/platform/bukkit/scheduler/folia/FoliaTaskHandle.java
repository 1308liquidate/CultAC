package ac.cult.cultac.platform.bukkit.scheduler.folia;

import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class FoliaTaskHandle implements TaskHandle {

    private final @NotNull ScheduledTask task;

    @Contract(pure = true)
    public FoliaTaskHandle(@NotNull ScheduledTask task) {
        this.task = Objects.requireNonNull(task, "task");
        RuntimeEnvironment.track(this, task::cancel);
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
