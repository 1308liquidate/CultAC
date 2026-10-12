package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.runtime.EngineRuntime;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.reload.api.*;
import io.netty.channel.Channel;
import java.util.concurrent.CompletionStage;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperRuntimeEntrypoint implements RuntimeEntrypoint {
    @Override
    public RuntimeInstance create(HostContext context) {
        RuntimeEnvironment.initialize(context);
        return new EngineRuntime() {
            private final CultACBukkitLoaderPlugin platform =
                    new CultACBukkitLoaderPlugin((JavaPlugin) context.plugin());

            public void load() {
                platform.onLoad();
            }

            public void start() {
                platform.onEnable();
            }

            public void attach(Object connection, Object player) {
                platform.inject((Channel) connection);
            }

            protected CompletionStage<Void> disconnectChannel(Channel connection) {
                return platform.disconnect(connection);
            }

            public CompletionStage<Void> close() {
                platform.onDisable();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            protected Sender sender(Object source) {
                return platform.getBukkitSenderFactory().map((CommandSender) source);
            }
        };
    }
}
