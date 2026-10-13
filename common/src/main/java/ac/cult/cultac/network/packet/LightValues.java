package ac.cult.cultac.network.packet;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** An immutable light snapshot. Layers own their bytes and never retain a network frame. */
public final class LightValues {
    private final BitSet skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask;
    private final List<Layer> sky, block;

    public LightValues(
            BitSet skyYMask,
            BitSet blockYMask,
            BitSet emptySkyYMask,
            BitSet emptyBlockYMask,
            List<byte[]> skyUpdates,
            List<byte[]> blockUpdates) {
        this(layers(skyUpdates), layers(blockUpdates), skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask);
    }

    private LightValues(
            List<Layer> sky,
            List<Layer> block,
            BitSet skyYMask,
            BitSet blockYMask,
            BitSet emptySkyYMask,
            BitSet emptyBlockYMask) {
        this.skyYMask = (BitSet) skyYMask.clone();
        this.blockYMask = (BitSet) blockYMask.clone();
        this.emptySkyYMask = (BitSet) emptySkyYMask.clone();
        this.emptyBlockYMask = (BitSet) emptyBlockYMask.clone();
        this.sky = List.copyOf(sky);
        this.block = List.copyOf(block);
    }

    public static LightValues ofLayers(
            BitSet skyYMask,
            BitSet blockYMask,
            BitSet emptySkyYMask,
            BitSet emptyBlockYMask,
            List<Layer> sky,
            List<Layer> block) {
        return new LightValues(sky, block, skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask);
    }

    private static List<Layer> layers(List<byte[]> updates) {
        var result = new ArrayList<Layer>(updates.size());
        for (byte[] bytes : updates) {
            if (bytes.length == 2048 && (bytes[0] & 15) == ((bytes[0] & 255) >>> 4)) {
                int i = 1;
                while (i < bytes.length && bytes[i] == bytes[0]) i++;
                if (i == bytes.length) {
                    result.add(Layer.UNIFORM[bytes[0] & 15]);
                    continue;
                }
            }
            result.add(new Layer(bytes.clone(), 0));
        }
        return result;
    }

    public BitSet skyYMask() {
        return (BitSet) skyYMask.clone();
    }

    public BitSet blockYMask() {
        return (BitSet) blockYMask.clone();
    }

    public BitSet emptySkyYMask() {
        return (BitSet) emptySkyYMask.clone();
    }

    public BitSet emptyBlockYMask() {
        return (BitSet) emptyBlockYMask.clone();
    }

    public List<byte[]> skyUpdates() {
        return sky.stream().map(Layer::copyBytes).toList();
    }

    public List<byte[]> blockUpdates() {
        return block.stream().map(Layer::copyBytes).toList();
    }

    public boolean hasUpdate(boolean skyLight, int section) {
        return (skyLight ? skyYMask : blockYMask).get(section);
    }

    public boolean isEmpty(boolean skyLight, int section) {
        return (skyLight ? emptySkyYMask : emptyBlockYMask).get(section);
    }

    public Layer update(boolean skyLight, int index) {
        return (skyLight ? sky : block).get(index);
    }

    /** Immutable nibble storage, shared safely between the packet and compensated state. */
    public static final class Layer {
        private static final Layer[] UNIFORM = new Layer[16];

        static {
            for (int i = 0; i < UNIFORM.length; i++) UNIFORM[i] = new Layer(null, i);
        }

        private final byte[] data;
        private final int uniform;

        private Layer(byte[] data, int uniform) {
            this.data = data;
            this.uniform = uniform;
        }

        public static Layer empty() {
            return UNIFORM[0];
        }

        /** Reads owned bytes only for nonuniform layers; inspection never advances the frame. */
        public static Layer read(ByteBuf input, int length) {
            if (length == 2048) {
                int start = input.readerIndex();
                int first = input.getUnsignedByte(start);
                if ((first & 15) == first >>> 4) {
                    long repeated = (first & 255L) * 0x0101010101010101L;
                    int offset = 0;
                    while (offset < length && input.getLong(start + offset) == repeated) offset += Long.BYTES;
                    if (offset == length) {
                        input.skipBytes(length);
                        return UNIFORM[first & 15];
                    }
                }
            }
            byte[] bytes = new byte[length];
            input.readBytes(bytes);
            return new Layer(bytes, 0);
        }

        public void validate() {
            if (data != null && data.length != 2048)
                throw new IllegalArgumentException("Light layer must contain 2048 bytes");
        }

        public int get(int x, int y, int z) {
            if (data == null) return uniform;
            int index = (y << 8) | (z << 4) | x;
            return (data[index >> 1] >>> ((index & 1) * 4)) & 15;
        }

        private byte[] copyBytes() {
            if (data != null) return data.clone();
            byte[] bytes = new byte[2048];
            if (uniform != 0) Arrays.fill(bytes, (byte) (uniform | uniform << 4));
            return bytes;
        }
    }
}
