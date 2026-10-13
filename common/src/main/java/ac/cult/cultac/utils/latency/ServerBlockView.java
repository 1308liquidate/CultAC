package ac.cult.cultac.utils.latency;

import ac.cult.cultac.protocol.value.BlockPos;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * The block states the server has most recently sent, ahead of the client acknowledging them.
 *
 * <p>{@link CompensatedWorld} applies server packets only once the client has processed them. A
 * resync written now is ordered after every packet already sent, so it must carry the newest sent
 * value rather than the acknowledged one. This overlay holds sent block updates until their proof
 * applies them to the compensated world. Columns or worlds whose replacement is still in flight are
 * unknown: the fresh chunk or world already resynchronizes those positions. Owner-thread only.
 */
final class ServerBlockView {
    static final int UNKNOWN = Integer.MIN_VALUE;

    private final Long2IntOpenHashMap sentStates = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap sentIds = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap pendingColumns = new Long2IntOpenHashMap();
    private int pendingWorldChanges;
    private long nextId;

    long sent(long position, int state) {
        long id = ++nextId;
        sentStates.put(position, state);
        sentIds.put(position, id);
        return id;
    }

    void applied(long position, long id) {
        if (sentIds.get(position) != id) return;
        sentIds.remove(position);
        sentStates.remove(position);
    }

    /** A chunk load or unload was sent; it supersedes every earlier update in the column. */
    void columnSent(int chunkX, int chunkZ) {
        pendingColumns.addTo(CompensatedWorld.chunkPositionToLong(chunkX, chunkZ), 1);
        var iterator = sentIds.long2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            long position = iterator.next().getLongKey();
            if (inColumn(position, chunkX, chunkZ)) {
                iterator.remove();
                sentStates.remove(position);
            }
        }
    }

    private static boolean inColumn(long position, int chunkX, int chunkZ) {
        BlockPos pos = BlockPos.of(position);
        return pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ;
    }

    void columnApplied(int chunkX, int chunkZ) {
        long column = CompensatedWorld.chunkPositionToLong(chunkX, chunkZ);
        if (pendingColumns.addTo(column, -1) <= 1) pendingColumns.remove(column);
    }

    /** A login or dimension change was sent; nothing earlier describes the next world. */
    void worldChangeSent() {
        pendingWorldChanges++;
        sentStates.clear();
        sentIds.clear();
    }

    void worldChangeApplied() {
        if (pendingWorldChanges > 0) pendingWorldChanges--;
    }

    boolean isUnknown(int chunkX, int chunkZ) {
        return pendingWorldChanges > 0
                || pendingColumns.containsKey(CompensatedWorld.chunkPositionToLong(chunkX, chunkZ));
    }

    /** The newest sent but unapplied state, or {@link #UNKNOWN} when the compensated world is current. */
    int sentState(long position) {
        return sentStates.getOrDefault(position, UNKNOWN);
    }
}
