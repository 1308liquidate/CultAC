package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PalettedSectionCountTest {
    private static final int REGISTRY = 30_000;

    @Test
    void paletteOrderCountsEqualHashCountsAndMalformedSectionsFailIdentically() {
        var random = new SplittableRandom(0x5EC7L);
        int succeeded = 0, failed = 0, reordered = 0;
        for (int round = 0; round < 6000; round++) {
            boolean blocks = random.nextBoolean();
            int size = blocks ? 4096 : 64;
            int minimumBits = blocks ? 4 : 1;
            int maximumLocalBits = blocks ? 8 : 3;
            int wireBits = random.nextInt(round % 10 == 0 ? maximumLocalBits + 3 : maximumLocalBits + 1);
            int bits = wireBits == 0 ? 0 : wireBits <= maximumLocalBits ? Math.max(minimumBits, wireBits) : 15;
            int codes = bits == 0 || wireBits > maximumLocalBits ? 0 : 1 << bits;
            int paletteSize = codes == 0 ? 1 : 1 + random.nextInt(codes + (bits > 4 ? 8 : 0));
            if (bits <= 4) paletteSize = Math.min(paletteSize, codes == 0 ? 1 : codes);
            boolean malformed = round % 7 == 0;
            int limit = round % 5 == 0 ? REGISTRY / 2 : REGISTRY;
            var input = Unpooled.buffer();
            try {
                input.writeByte(wireBits);
                if (wireBits <= maximumLocalBits) {
                    if (bits == 0) Wire.writeVarInt(input, random.nextInt(REGISTRY));
                    else {
                        Wire.writeVarInt(input, paletteSize);
                        for (int i = 0; i < paletteSize; i++) Wire.writeVarInt(input, random.nextInt(REGISTRY));
                    }
                }
                if (bits > 0) {
                    int range =
                            wireBits > maximumLocalBits ? REGISTRY : malformed ? codes : Math.min(paletteSize, codes);
                    int distinct = 1 + random.nextInt(Math.max(1, range));
                    writeWords(input, size, bits, random, range, distinct);
                }
                var section = blocks
                        ? PalettedSection.readBlocks(input, ProtocolVersion.V26_3, REGISTRY)
                        : PalettedSection.readBiomes(input, ProtocolVersion.V26_3, REGISTRY);
                var expected = outcome(section, -1);
                var actual = outcome(section, limit);
                assertEquals(expected.failure, actual.failure, "round " + round);
                if (expected.failure == null) {
                    assertEquals(totals(expected.calls), totals(actual.calls), "round " + round);
                    assertEquals(expected.calls.size(), actual.calls.size(), "round " + round);
                    succeeded++;
                    if (!expected.calls.equals(actual.calls)) reordered++;
                } else {
                    assertEquals(expected.calls, actual.calls, "round " + round);
                    failed++;
                }
            } finally {
                input.release();
            }
        }
        assertTrue(succeeded > 4000, "succeeded " + succeeded);
        assertTrue(failed > 100, "failed " + failed);
        assertTrue(reordered > 1000, "reordered " + reordered);
    }

    private record Outcome(List<String> calls, String failure) {}

    private static Outcome outcome(PalettedSection section, int limit) {
        var calls = new ArrayList<String>();
        PalettedSection.CountConsumer consumer = (state, count) -> calls.add(state + ":" + count);
        try {
            if (limit < 0) section.forEachCount(consumer);
            else section.forEachCountWithin(limit, consumer);
            return new Outcome(calls, null);
        } catch (RuntimeException failure) {
            return new Outcome(calls, failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private static TreeMap<Integer, Integer> totals(List<String> calls) {
        var totals = new TreeMap<Integer, Integer>();
        for (String call : calls) {
            int split = call.indexOf(':');
            totals.merge(
                    Integer.parseInt(call.substring(0, split)),
                    Integer.parseInt(call.substring(split + 1)),
                    Integer::sum);
        }
        return totals;
    }

    private static void writeWords(
            ByteBuf output, int size, int bits, SplittableRandom random, int range, int distinct) {
        int perWord = 64 / bits;
        int[] pool = new int[distinct];
        for (int i = 0; i < distinct; i++) pool[i] = random.nextInt(range);
        int index = 0;
        for (int word = 0; word < (size + perWord - 1) / perWord; word++) {
            long value = 0;
            for (int j = 0; j < perWord && index < size; j++, index++)
                value |= (long) pool[random.nextInt(distinct)] << (j * bits);
            output.writeLong(value);
        }
    }
}
