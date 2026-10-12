package ac.cult.cultac.command;

import ac.cult.cultac.platform.api.sender.Sender;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;

/** The persistent plugin owns native roots; generations only parse and execute their own commands. */
public final class IsolatedCommandManager extends CommandManager<Sender> {
    public IsolatedCommandManager() {
        super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
    }

    @Override
    public boolean hasPermission(Sender sender, String permission) {
        return sender.hasPermission(permission);
    }
}
