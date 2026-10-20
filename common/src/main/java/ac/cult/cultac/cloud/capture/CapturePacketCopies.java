package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.network.FrameCopies;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Private packet arrays owned by one capture frame until it is sent, dropped or reset. Reusable capacity, bookkeeping
 * included, is paid from the player's {@link Budget}, which draws on one budget for the process; a copy without room
 * is a fresh array.
 */
final class CapturePacketCopies {
    static final int MAX_PACKET_BYTES = 256;
    static final int MAX_COPIES = 128;
    /** Reusable capacity of one pool: its arrays and their size buckets. */
    static final int MAX_RETAINED_BYTES = 8 * 1024;
    /** Reusable capacity of one player's pools, bookkeeping included. */
    static final long PLAYER_BYTES = 64 * 1024;
    /** Reusable capacity of every pool in the process, bookkeeping included. */
    static final long PROCESS_BYTES = 64L * 1024 * 1024;
    /** A size unused this long gives its capacity back when its frame is released. */
    static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** A pool's own bookkeeping: the object and its three per-size index arrays. */
    static final int POOL_BYTES = 32 + 3 * referencesBytes(MAX_PACKET_BYTES + 1);

    /** A size's last use is kept in units of 2^20 ns, which wrap only after about 25 days. */
    private static final int STAMP_SHIFT = 20;

    private static final int IDLE_STAMPS = (int) (IDLE_NANOS >> STAMP_SHIFT);
    private static final AtomicLong processBytes = new AtomicLong();

    private final Budget budget;
    private final byte[][][] sizes = new byte[MAX_PACKET_BYTES + 1][][];
    private final int[] used = new int[MAX_PACKET_BYTES + 1];
    private final int[] lastUsed = new int[MAX_PACKET_BYTES + 1];
    private int retainedBytes;
    private int retainedCopies;
    private long lastUse;

    private CapturePacketCopies(Budget budget, long nanos) {
        this.budget = budget;
        this.lastUse = nanos;
    }

    /** A pool whose bookkeeping {@code budget} pays for; null when it has no room. */
    static CapturePacketCopies of(Budget budget, long nanos) {
        return budget.take(POOL_BYTES) ? new CapturePacketCopies(budget, nanos) : null;
    }

    byte[] copy(ByteBuf source, long nanos) {
        int length = source.readableBytes();
        if (length > MAX_PACKET_BYTES) return FrameCopies.bytes(source);
        lastUse = nanos;
        int index = used[length];
        byte[][] bucket = sizes[length];
        byte[] bytes;
        if (bucket != null && index < bucket.length && bucket[index] != null) {
            bytes = bucket[index];
        } else {
            int slots = bucket == null ? 4 : index == bucket.length ? bucket.length * 2 : bucket.length;
            int cost =
                    arrayBytes(length) + referencesBytes(slots) - (bucket == null ? 0 : referencesBytes(bucket.length));
            if (retainedCopies == MAX_COPIES || retainedBytes + cost > MAX_RETAINED_BYTES || !budget.take(cost))
                return FrameCopies.bytes(source);
            if (bucket == null) sizes[length] = bucket = new byte[slots][];
            else if (slots != bucket.length) sizes[length] = bucket = Arrays.copyOf(bucket, slots);
            bucket[index] = bytes = new byte[length];
            retainedBytes += cost;
            retainedCopies++;
        }
        used[length] = index + 1;
        lastUsed[length] = stamp(nanos);
        FrameCopies.copyInto(source, bytes);
        return bytes;
    }

    /**
     * The frame that owned the copies is done with them, so they may be reused; sizes unused for {@link #IDLE_NANOS}
     * at {@code nanos} give their capacity back.
     */
    void clear(long nanos) {
        int now = stamp(nanos);
        for (int length = 0; length <= MAX_PACKET_BYTES; length++) {
            byte[][] bucket = sizes[length];
            if (bucket == null) continue;
            used[length] = 0;
            if (now - lastUsed[length] <= IDLE_STAMPS) continue;
            int freed = referencesBytes(bucket.length);
            for (byte[] bytes : bucket) {
                if (bytes == null) continue;
                freed += arrayBytes(length);
                retainedCopies--;
            }
            sizes[length] = null;
            retainedBytes -= freed;
            budget.give(freed);
        }
    }

    /** Gives every byte of the pool back; the frame that owned it is gone, and the pool is not used again. */
    void release() {
        Arrays.fill(sizes, null);
        budget.give(retainedBytes + (long) POOL_BYTES);
        retainedBytes = 0;
        retainedCopies = 0;
    }

    long lastUse() {
        return lastUse;
    }

    int retainedBytes() {
        return retainedBytes;
    }

    static long processBytes() {
        return processBytes.get();
    }

    private static int stamp(long nanos) {
        return (int) (nanos >> STAMP_SHIFT);
    }

    private static int arrayBytes(int length) {
        return align(16 + length);
    }

    private static int referencesBytes(int count) {
        return align(16 + 4 * count);
    }

    private static int align(int bytes) {
        return (bytes + 7) & ~7;
    }

    /** One player's share of the reusable capacity; used only on its stream's event loop. */
    static final class Budget {
        private long bytes;

        boolean take(long amount) {
            if (bytes + amount > PLAYER_BYTES) return false;
            if (processBytes.addAndGet(amount) > PROCESS_BYTES) {
                processBytes.addAndGet(-amount);
                return false;
            }
            bytes += amount;
            return true;
        }

        void give(long amount) {
            bytes -= amount;
            processBytes.addAndGet(-amount);
        }

        long bytes() {
            return bytes;
        }
    }
}
