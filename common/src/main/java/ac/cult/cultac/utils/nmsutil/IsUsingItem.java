package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.ItemUseState;

public final class IsUsingItem {
    private IsUsingItem() {}

    public static boolean isUsingItem(CultPlayer player) {
        return state(player).active();
    }

    public static boolean isSlowDueToUsingItem(CultPlayer player) {
        ItemUseState state = state(player);
        return state.active() && !state.canSprint();
    }

    public static float getUseItemSpeedMultiplier(CultPlayer player) {
        ItemUseState state = state(player);
        return state.active() ? state.speedMultiplier() : 1.0F;
    }

    private static ItemUseState state(CultPlayer player) {
        ItemUseState state = player.platformPlayer == null ? null : player.platformPlayer.getItemUseState();
        return state == null ? ItemUseState.NONE : state;
    }
}
