package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;

/**
 * The server's inventory as this connection has observed it: contents the server sent, plus the
 * click claims the server accepted silently. Vanilla's container synchronizer only corrects slots
 * where its contents differ from what it believes the client holds, so a click Cult cancels or a
 * rejected local prediction is never corrected by the server. Resending this view is the
 * correction. {@code null} entries are unknown and are never resent. Owner-thread only.
 */
final class ServerInventoryView {
    private final SimItemStack[] playerSlots = new SimItemStack[Inventory.STORAGE_SIZE];
    private SimItemStack[] containerSlots = new SimItemStack[0];
    private SimItemStack carried;
    private int playerStateId;
    private int containerStateId;
    private int pendingPackets;

    void playerSlot(int storageSlot, SimItemStack item) {
        if (storageSlot >= 0 && storageSlot < playerSlots.length)
            playerSlots[storageSlot] = item == null ? null : ItemUtil.copy(item);
    }

    SimItemStack playerSlot(int storageSlot) {
        if (storageSlot < 0 || storageSlot >= playerSlots.length) return null;
        SimItemStack item = playerSlots[storageSlot];
        return item == null ? null : ItemUtil.copy(item);
    }

    void containerOpened(int slots) {
        containerSlots = new SimItemStack[Math.max(0, slots)];
        containerStateId = 0;
        carried = null;
    }

    void containerClosed() {
        containerSlots = new SimItemStack[0];
        containerStateId = 0;
        // The server returns the carried stack to the inventory or drops it; its updates follow.
        carried = null;
    }

    void containerSlot(int menuSlot, SimItemStack item) {
        if (menuSlot >= 0 && menuSlot < containerSlots.length)
            containerSlots[menuSlot] = item == null ? null : ItemUtil.copy(item);
    }

    SimItemStack containerSlot(int menuSlot) {
        if (menuSlot < 0 || menuSlot >= containerSlots.length) return null;
        SimItemStack item = containerSlots[menuSlot];
        return item == null ? null : ItemUtil.copy(item);
    }

    void carried(SimItemStack item) {
        carried = item == null ? null : ItemUtil.copy(item);
    }

    SimItemStack carried() {
        return carried == null ? null : ItemUtil.copy(carried);
    }

    void playerStateId(int stateId) {
        playerStateId = stateId;
    }

    int playerStateId() {
        return playerStateId;
    }

    void containerStateId(int stateId) {
        containerStateId = stateId;
    }

    int containerStateId() {
        return containerStateId;
    }

    /** An inventory packet was sent and has not yet been applied to the client model. */
    void packetSent() {
        pendingPackets++;
    }

    void packetApplied() {
        if (pendingPackets > 0) pendingPackets--;
    }

    /** True when the client model has applied every inventory packet already sent. */
    boolean settled() {
        return pendingPackets == 0;
    }
}
