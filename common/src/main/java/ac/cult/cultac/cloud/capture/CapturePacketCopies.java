package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.network.FrameCopies;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;

/** Private packet arrays owned by one capture frame until it is sent, dropped or reset. */
final class CapturePacketCopies {
    static final int MAX_PACKET_BYTES = 256;
    static final int MAX_COPIES = 256;
    static final int MAX_RETAINED_BYTES = 32 * 1024;
    private final byte[][][] sizes = new byte[MAX_PACKET_BYTES + 1][][];
    private final int[] used = new int[MAX_PACKET_BYTES + 1];
    private int retainedBytes;
    private int retainedCopies;

    byte[] copy(ByteBuf source) {
        int length = source.readableBytes();
        if (length > MAX_PACKET_BYTES) return FrameCopies.bytes(source);
        int index = used[length];
        byte[][] bucket = sizes[length];
        byte[] bytes;
        if (bucket != null && index < bucket.length && bucket[index] != null) {
            bytes = bucket[index];
        } else {
            int cost = length + 24;
            if (retainedCopies == MAX_COPIES || retainedBytes + cost > MAX_RETAINED_BYTES)
                return FrameCopies.bytes(source);
            if (bucket == null) sizes[length] = bucket = new byte[4][];
            else if (index == bucket.length) sizes[length] = bucket = Arrays.copyOf(bucket, bucket.length * 2);
            bucket[index] = bytes = new byte[length];
            retainedBytes += cost;
            retainedCopies++;
        }
        used[length] = index + 1;
        FrameCopies.copyInto(source, bytes);
        return bytes;
    }

    void clear() {
        Arrays.fill(used, 0);
    }

    int retainedBytes() {
        return retainedBytes;
    }
}
