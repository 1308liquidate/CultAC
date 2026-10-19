package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.BuildableCommand;
import ac.cult.cultac.platform.api.command.PlayerSelector;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

public class CultProfile implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(BuildableCommand.root(commandManager)
                .literal("profile")
                .permission("cult.profile")
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleProfile));
    }

    private void handleProfile(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector target = context.get("target");

        PlatformPlayer targetPlatformPlayer =
                Objects.requireNonNull(target.getSinglePlayer().getPlatformPlayer(), "targetPlatformPlayer");

        CultPlayer cultPlayer = CultAPI.INSTANCE.getPlayerDataManager().getPlayer(targetPlatformPlayer.getUniqueId());
        if (cultPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(
                    sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        for (String message : CultAPI.INSTANCE.getConfigManager().getConfig().getStringList("profile")) {
            final Component component = MessageUtil.miniMessage(message);
            sender.sendMessage(MessageUtil.replacePlaceholders(cultPlayer, component));
        }
    }
}
