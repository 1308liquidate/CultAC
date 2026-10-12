package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.packet.WorldPackets;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class PackedSectionUpdatesTest {
    @Test
    void decodedValuesAndOrderedApplicationMatchTheOriginalSnapshotAtSignedCoordinateLimits() {
        int stateCount = DataTables.defaults().registry().stateCount();
        var random = new Random(329751);
        for (long section : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x7ffffc0000000000L}) {
            for (int count : new int[] {0, 1, 16, 256, 1024}) {
                var buffer = Unpooled.buffer();
                try {
                    buffer.writeLong(section);
                    Wire.writeVarInt(buffer, count);
                    var expected = new ArrayList<WorldPackets.BlockUpdate>(count);
                    for (int i = 0; i < count; i++) {
                        int state = i % 5 == 0 ? (i % 2 == 0 ? -1 : stateCount + 1) : random.nextInt(stateCount);
                        int local = random.nextInt(4096);
                        Wire.writeVarLong(buffer, ((long) state << 12) | local);
                        expected.add(new WorldPackets.BlockUpdate(BlockPos.fromSection(section, local), state));
                    }
                    var packet = ModelWorldValues.sectionUpdates(buffer);
                    assertFalse(buffer.isReadable());
                    // The compact snapshot must outlive the released/reused network frame.
                    buffer.clear();
                    buffer.writeZero(buffer.capacity());
                    var original = new WorldPackets.SectionBlocksUpdate(expected);
                    assertEquals(original, packet);
                    assertEquals(original.hashCode(), packet.hashCode());
                    assertEquals(expected, packet.updates());
                    var applied = new ArrayList<WorldPackets.BlockUpdate>();
                    packet.runUpdates((x, y, z, state) ->
                            applied.add(new WorldPackets.BlockUpdate(new BlockPos(x, y, z), state)));
                    assertEquals(expected, applied);
                    for (int i = 0; i < count; i++) {
                        assertEquals(expected.get(i).position().getX(), packet.x(i));
                        assertEquals(expected.get(i).position().getY(), packet.y(i));
                        assertEquals(expected.get(i).position().getZ(), packet.z(i));
                    }
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void stateMappingRunsOncePerEntryAndDuplicatePositionsKeepWireOrder() {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeLong(-1);
            Wire.writeVarInt(buffer, 3);
            for (int state : new int[] {3, 2, 1}) Wire.writeVarLong(buffer, ((long) state << 12) | 4071);
            var seen = new ArrayList<Integer>();
            IntUnaryOperator mapper = state -> {
                seen.add(state);
                return state + 1;
            };
            var packet = ModelWorldValues.sectionUpdates(buffer, mapper);
            assertEquals(List.of(3, 2, 1), seen);
            var applied = new ArrayList<Integer>();
            packet.runUpdates((x, y, z, state) -> applied.add(state));
            assertEquals(List.of(4, 3, 2), applied);
            assertEquals(
                    packet.updates().get(0).position(), packet.updates().get(2).position());
            assertEquals(List.of(3, 2, 1), seen);
        } finally {
            buffer.release();
        }
    }

    @Test
    void publicListSnapshotRemainsImmutableIncludingEmptyViewsAndIterators() {
        var packet = WorldPackets.SectionBlocksUpdate.readSection(0, 1, () -> 0, IntUnaryOperator.identity());
        var values = packet.updates();
        assertThrows(UnsupportedOperationException.class, () -> values.add(null));
        assertThrows(UnsupportedOperationException.class, () -> values.addAll(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> values.remove(new Object()));
        assertThrows(UnsupportedOperationException.class, () -> values.removeAll(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> values.retainAll(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> values.removeIf(entry -> false));
        assertThrows(UnsupportedOperationException.class, () -> values.sort(null));
        assertThrows(UnsupportedOperationException.class, () -> values.replaceAll(entry -> entry));
        assertThrows(
                UnsupportedOperationException.class, () -> values.iterator().remove());
        assertThrows(
                UnsupportedOperationException.class, () -> values.listIterator().set(values.getFirst()));
        assertThrows(
                UnsupportedOperationException.class, () -> values.subList(0, 0).clear());
        assertThrows(
                UnsupportedOperationException.class, () -> values.subList(0, 0).removeIf(entry -> false));
        assertThrows(UnsupportedOperationException.class, () -> values.set(0, values.getFirst()));
        var input = new ArrayList<>(values);
        var copied = new WorldPackets.SectionBlocksUpdate(input);
        input.clear();
        assertEquals(values, copied.updates());
    }
}
