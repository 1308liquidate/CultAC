package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CapturePacketCopiesTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void copiesRemainDistinctAndOwnedUntilTheFrameIsReleased() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        CapturePacketCopies copies = CapturePacketCopies.of(budget, 0);
        for (boolean direct : new boolean[] {false, true}) {
            ByteBuf source = direct ? PooledByteBufAllocator.DEFAULT.directBuffer(20) : Unpooled.buffer(20);
            try {
                source.writeByte(9).writeInt(42).readerIndex(1);
                source.markReaderIndex();
                byte[] first = copies.copy(source, 0);
                source.setInt(1, 71);
                byte[] second = copies.copy(source, 0);
                assertArrayEquals(new byte[] {0, 0, 0, 42}, first);
                assertArrayEquals(new byte[] {0, 0, 0, 71}, second);
                assertNotSame(first, second);
                assertEquals(1, source.readerIndex());
                assertEquals(5, source.writerIndex());
                assertEquals(1, source.refCnt());
                source.resetReaderIndex();
                assertEquals(1, source.readerIndex());
                copies.clear(0);
                byte[] reused = copies.copy(source, 0);
                assertSame(first, reused);
                copies.clear(0);
            } finally {
                source.release();
            }
        }
        copies.release();
        assertEquals(0, budget.bytes());
    }

    @Test
    void largeAndSaturatedFramesRemainCompleteWithoutUnboundedRetention() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        CapturePacketCopies copies = CapturePacketCopies.of(budget, 0);
        ByteBuf source = Unpooled.buffer(512).writeZero(512);
        try {
            assertNotSame(copies.copy(source, 0), copies.copy(source, 0));
            assertEquals(0, copies.retainedBytes());
            source.writerIndex(256);
            var held = new ArrayList<byte[]>();
            for (int i = 0; i < 2048; i++) {
                source.setInt(0, i);
                held.add(copies.copy(source, 0));
            }
            assertTrue(copies.retainedBytes() <= CapturePacketCopies.MAX_RETAINED_BYTES);
            assertEquals(CapturePacketCopies.POOL_BYTES + copies.retainedBytes(), budget.bytes());
            for (int i = 0; i < held.size(); i++) {
                byte[] bytes = held.get(i);
                assertEquals(i, ((bytes[2] & 255) << 8) | (bytes[3] & 255));
                assertEquals(256, bytes.length);
            }
        } finally {
            source.release();
            copies.release();
        }
        assertEquals(0, budget.bytes());
    }

    @Test
    void tinyPacketBurstsRetainOnlyTheBoundedPrefix() {
        CapturePacketCopies copies = CapturePacketCopies.of(new CapturePacketCopies.Budget(), 0);
        ByteBuf source = Unpooled.buffer(1).writeByte(42);
        try {
            var first = new ArrayList<byte[]>();
            for (int i = 0; i < CapturePacketCopies.MAX_COPIES + 20; i++) first.add(copies.copy(source, 0));
            assertTrue(copies.retainedBytes() <= CapturePacketCopies.MAX_RETAINED_BYTES);
            copies.clear(0);
            for (int i = 0; i < first.size(); i++) {
                byte[] next = copies.copy(source, 0);
                assertArrayEquals(new byte[] {42}, next);
                if (i < CapturePacketCopies.MAX_COPIES) assertSame(first.get(i), next);
                else assertNotSame(first.get(i), next);
            }
        } finally {
            source.release();
            copies.release();
        }
    }

    @Test
    void sizesIdleForThirtySecondsGiveTheirCapacityBack() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        CapturePacketCopies copies = CapturePacketCopies.of(budget, 0);
        ByteBuf small = Unpooled.buffer(8).writeZero(8);
        ByteBuf large = Unpooled.buffer(200).writeZero(200);
        try {
            byte[] kept = copies.copy(small, 0);
            copies.copy(large, 0);
            copies.clear(0);
            long warmed = budget.bytes();
            assertSame(kept, copies.copy(small, 20 * SECOND));
            copies.clear(20 * SECOND);
            assertEquals(warmed, budget.bytes());

            copies.clear(40 * SECOND);

            assertTrue(budget.bytes() < warmed - 200, budget.bytes() + " of " + warmed);
            assertSame(kept, copies.copy(small, 40 * SECOND));
            copies.clear(80 * SECOND);
            assertEquals(CapturePacketCopies.POOL_BYTES, budget.bytes());
            assertEquals(0, copies.retainedBytes());
        } finally {
            small.release();
            large.release();
            copies.release();
        }
        assertEquals(0, budget.bytes());
    }

    @Test
    void aPoolWithoutRoomCopiesIntoFreshArrays() {
        CapturePacketCopies.Budget budget = new CapturePacketCopies.Budget();
        var pools = new ArrayList<CapturePacketCopies>();
        CapturePacketCopies pool;
        while ((pool = CapturePacketCopies.of(budget, 0)) != null) pools.add(pool);
        assertTrue(budget.bytes() <= CapturePacketCopies.PLAYER_BYTES);
        assertNull(CapturePacketCopies.of(budget, 0));
        long rest = CapturePacketCopies.PLAYER_BYTES - budget.bytes();
        assertTrue(budget.take(rest));
        CapturePacketCopies last = pools.getLast();
        ByteBuf source = Unpooled.buffer(4).writeInt(7);
        try {
            byte[] first = last.copy(source, 0);
            last.clear(0);
            byte[] second = last.copy(source, 0);
            assertArrayEquals(new byte[] {0, 0, 0, 7}, first);
            assertArrayEquals(new byte[] {0, 0, 0, 7}, second);
            assertNotSame(first, second);
            assertEquals(0, last.retainedBytes());
            assertEquals(CapturePacketCopies.PLAYER_BYTES, budget.bytes());
        } finally {
            source.release();
            budget.give(rest);
            pools.forEach(CapturePacketCopies::release);
        }
        assertEquals(0, budget.bytes());
    }
}
