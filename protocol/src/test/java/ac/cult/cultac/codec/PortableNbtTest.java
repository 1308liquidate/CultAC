package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.*;
import ac.cult.blocksim.data.nbt.*;
import ac.cult.cultac.protocol.WireValueDecoder.NbtFactory;
import ac.cult.shaded.vialib.api.type.Types;
import ac.cult.shaded.vialib.nbt.tag.*;
import io.netty.buffer.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PortableNbtTest {
    static final NbtFactory<NbtValue> MODEL = new NbtFactory<>() {
        public NbtValue read(byte[] bytes) { return BinaryNbt.read(bytes); }
        public NbtValue number(int type, Number value) {
            return new NbtValue.Numeric(NbtValue.Kind.values()[type - 1], value);
        }
        public NbtValue text(String value) { return new NbtValue.Text(value); }
        public NbtValue array(int type, List<Long> values) {
            return new NbtValue.PrimitiveArray(type == 7 ? NbtValue.Kind.BYTE_ARRAY
                    : type == 11 ? NbtValue.Kind.INT_ARRAY : NbtValue.Kind.LONG_ARRAY, values);
        }
        public NbtValue sequence(List<NbtValue> values) { return new NbtValue.Sequence(values); }
        public NbtValue compound(Map<String, NbtValue> values) { return new NbtValue.Compound(values); }
    };

    @Test
    void directTreeMatchesSerializedTreeIncludingClientNormalization() {
        var tag = new CompoundTag();
        tag.put("byte", new ByteTag((byte) -128)); tag.put("short", new ShortTag((short) 32767));
        tag.put("int", new IntTag(Integer.MIN_VALUE)); tag.put("long", new LongTag(Long.MAX_VALUE));
        tag.put("float", new FloatTag(-0F)); tag.put("double", new DoubleTag(-0D));
        tag.put("nan", new FloatTag(Float.NaN)); tag.putString("text", "nul\0surrogate\ud800 unicode\u03bb");
        tag.put("bytes", new ByteArrayTag(new byte[] {-1, 0, 127}));
        tag.put("ints", new IntArrayTag(new int[] {Integer.MIN_VALUE, 1000}));
        tag.put("longs", new LongArrayTag(new long[] {Long.MIN_VALUE, Long.MAX_VALUE}));
        var marker = new CompoundTag(); marker.put("", new IntTag(17));
        var nestedMarker = new CompoundTag(); nestedMarker.put("", marker);
        tag.put("list", new ListTag<>(List.of(marker, nestedMarker, new CompoundTag())));
        var input = Unpooled.buffer();
        try {
            Types.COMPOUND_TAG.write(input, tag);
            assertEquals(BinaryNbt.read(ByteBufUtil.getBytes(input)), PortableNbt.read(tag, MODEL));
            var owned = PortableNbt.read(tag, MODEL);
            tag.putInt("int", 0); tag.getByteArrayTag("bytes").getValue()[0] = 0;
            assertNotEquals(owned, PortableNbt.read(tag, MODEL));
        } finally { input.release(); }
    }
}
