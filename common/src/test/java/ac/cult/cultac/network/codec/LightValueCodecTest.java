package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.packet.LightValues;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class LightValueCodecTest {
    @Test
    void decodedLayersOwnBytesAndPreserveAllNibblesForBothMaskFormats() {
        for (var version : List.of(ProtocolVersion.V26_2, ProtocolVersion.V26_3)) {
            var input = Unpooled.buffer();
            try {
                for (int mask = 0; mask < 4; mask++) {
                    Wire.writeVarInt(input, mask == 0 ? 1 : 0);
                    if (mask == 0) {
                        if (version == ProtocolVersion.V26_3) input.writeByte(1);
                        else input.writeLong(1);
                    }
                }
                Wire.writeVarInt(input, 1);
                Wire.writeVarInt(input, 2048);
                byte[] bytes = new byte[2048];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
                input.writeBytes(bytes);
                Wire.writeVarInt(input, 0);
                var values = LightValueCodec.read(input, version);
                assertFalse(input.isReadable());
                input.setZero(0, input.writerIndex());
                for (int i = 0; i < 4096; i++) {
                    assertEquals(
                            (bytes[i >> 1] >>> ((i & 1) * 4)) & 15,
                            values.update(true, 0).get(i & 15, i >> 8, (i >> 4) & 15));
                }
                values.skyYMask().clear();
                values.skyUpdates().getFirst()[0] = 99;
                assertTrue(values.hasUpdate(true, 0));
                assertArrayEquals(bytes, values.skyUpdates().getFirst());
            } finally {
                input.release();
            }
        }
    }

    @Test
    void uniformLayersShareOnlyImmutableConstantStorage() {
        for (int value = 0; value < 16; value++) {
            var input = Unpooled.buffer(2048);
            try {
                byte[] bytes = new byte[2048];
                Arrays.fill(bytes, (byte) (value | value << 4));
                input.writeBytes(bytes);
                var layer = LightValues.Layer.read(input, 2048);
                assertEquals(2048, input.readerIndex());
                input.readerIndex(0);
                assertSame(layer, LightValues.Layer.read(input, 2048));
                input.setZero(0, 2048);
                assertEquals(value, layer.get(15, 15, 15));
            } finally {
                input.release();
            }
        }
    }

    @Test
    void callerMutationCannotAlterSnapshots() {
        var mask = new BitSet();
        mask.set(0);
        byte[] bytes = new byte[2048];
        bytes[0] = 0x32;
        var values = new LightValues(mask, new BitSet(), new BitSet(), new BitSet(), List.of(bytes), List.of());
        mask.clear();
        bytes[0] = 0;
        assertTrue(values.hasUpdate(true, 0));
        assertEquals(2, values.update(true, 0).get(0, 0, 0));
        assertEquals(3, values.update(true, 0).get(1, 0, 0));
    }
}
