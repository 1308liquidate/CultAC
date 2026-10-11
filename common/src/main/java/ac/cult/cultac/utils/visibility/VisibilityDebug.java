package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** A single watched placement, owned and updated on the player's packet executor. */
public final class VisibilityDebug {
    private static final double RADIUS = 10;
    private final CultPlayer player;
    private final ClientVisibility visibility;
    private boolean enabled;
    private BlockPos watched;
    private String dimension;
    private ClientVisibility.BlockVisibility lastVisibility;

    public VisibilityDebug(CultPlayer player) {
        this.player = player;
        visibility = new ClientVisibility(player);
    }

    public void toggle() {
        enabled = !enabled;
        watched = null;
        dimension = null;
        lastVisibility = null;
        message(
                enabled
                        ? "Enabled. Place a block to watch within 10 blocks; run /grim debug visibility again to stop."
                        : "Disabled.",
                NamedTextColor.GRAY);
    }

    public void onBlockPlaced(BlockPos position) {
        if (!enabled || watched != null) return;
        watched = position.immutable();
        dimension = player.compensatedWorld.getVisibleDimension();
        message("Watching block at " + coordinates() + ".", NamedTextColor.GRAY);
    }

    public void update() {
        if (!enabled || watched == null || !Objects.equals(dimension, player.compensatedWorld.getVisibleDimension()))
            return;
        var current = visibility.blockVisibility(watched, RADIUS);
        if (current == ClientVisibility.BlockVisibility.OUT_OF_RANGE || current == lastVisibility) return;
        lastVisibility = current;
        boolean visible = current == ClientVisibility.BlockVisibility.VISIBLE;
        message(
                "Block at " + coordinates() + " is " + (visible ? "visible" : "occluded") + ".",
                visible ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private String coordinates() {
        return watched.x() + ", " + watched.y() + ", " + watched.z();
    }

    private void message(String text, NamedTextColor color) {
        player.sendMessage(Component.text("[visibility] ", NamedTextColor.AQUA).append(Component.text(text, color)));
    }
}
