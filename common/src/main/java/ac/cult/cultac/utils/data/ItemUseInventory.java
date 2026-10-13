package ac.cult.cultac.utils.data;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.Inventory;
import java.util.ArrayList;
import java.util.List;

/** Compensated, client-visible stacks used by both use prediction and metadata callbacks. */
public final class ItemUseInventory {
    private ItemUseInventory() {}

    public static List<SimItemStack> hotbar(CultPlayer player) {
        var result = new ArrayList<SimItemStack>(9);
        var storage = player.getInventory().inventory.getInventoryStorage();
        for (int slot = 0; slot < 9; slot++) {
            SimItemStack stack = storage.getItem(Inventory.HOTBAR_OFFSET + slot);
            result.add(stack == null ? SimItemStack.EMPTY : stack);
        }
        return result;
    }

    public static ClientItemUseState.Snapshot prepare(CultPlayer player) {
        var cached = player.clientItemUse.preparedAt(player.packetStateData.acceptedClientTick);
        if (cached != null) return cached;
        return player.clientItemUse.prepare(
                player.packetStateData.acceptedClientTick,
                player.lastTransactionReceived.get(),
                player.getClientVersion(),
                player.getInventory().getHandItem(Hand.MAIN_HAND),
                player.getInventory().getHandItem(Hand.OFF_HAND),
                hotbar(player));
    }

    public static void metadata(CultPlayer player, byte flags) {
        if ((flags & 1) == 0) player.getItemUseDesync().onUseCompleted();
        player.clientItemUse.serverFlags(
                (flags & 1) != 0,
                (flags & 2) != 0 ? Hand.OFF_HAND : Hand.MAIN_HAND,
                player.getInventory().getHandItem(Hand.MAIN_HAND),
                player.getInventory().getHandItem(Hand.OFF_HAND),
                hotbar(player));
    }
}
