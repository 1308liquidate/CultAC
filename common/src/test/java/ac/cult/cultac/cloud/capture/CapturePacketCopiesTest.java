package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CapturePacketCopiesTest {
    @Test
    void copiesRemainDistinctAndOwnedUntilTheFrameIsReleased() {
        CapturePacketCopies copies = new CapturePacketCopies();
        for (boolean direct : new boolean[] {false, true}) {
            ByteBuf source = direct ? PooledByteBufAllocator.DEFAULT.directBuffer(20) : Unpooled.buffer(20);
            try {
                source.writeByte(9).writeInt(42).readerIndex(1);
                source.markReaderIndex();
                byte[] first = copies.copy(source);
                source.setInt(1, 71);
                byte[] second = copies.copy(source);
                assertArrayEquals(new byte[] {0, 0, 0, 42}, first);
                assertArrayEquals(new byte[] {0, 0, 0, 71}, second);
                assertNotSame(first, second);
                assertEquals(1, source.readerIndex());
                assertEquals(5, source.writerIndex());
                assertEquals(1, source.refCnt());
                source.resetReaderIndex();
                assertEquals(1, source.readerIndex());
                copies.clear();
                byte[] reused = copies.copy(source);
                assertSame(first, reused);
                copies.clear();
            } finally {
                source.release();
            }
        }
    }

    @Test
    void largeAndSaturatedFramesRemainCompleteWithoutUnboundedRetention() {
        CapturePacketCopies copies = new CapturePacketCopies();
        ByteBuf source = Unpooled.buffer(512).writeZero(512);
        try {
            assertNotSame(copies.copy(source), copies.copy(source));
            assertEquals(0, copies.retainedBytes());
            source.writerIndex(256);
            var held = new ArrayList<byte[]>();
            for (int i = 0; i < 2048; i++) {
                source.setInt(0, i);
                held.add(copies.copy(source));
            }
            assertTrue(copies.retainedBytes() <= CapturePacketCopies.MAX_RETAINED_BYTES);
            for (int i = 0; i < held.size(); i++) {
                byte[] bytes = held.get(i);
                assertEquals(i, ((bytes[2] & 255) << 8) | (bytes[3] & 255));
                assertEquals(256, bytes.length);
            }
        } finally {
            source.release();
        }
    }

    @Test
    void tinyPacketBurstsRetainOnlyTheBoundedPrefix() {
        CapturePacketCopies copies = new CapturePacketCopies();
        ByteBuf source = Unpooled.buffer(1).writeByte(42);
        try {
            var first = new ArrayList<byte[]>();
            for (int i = 0; i < CapturePacketCopies.MAX_COPIES + 20; i++) first.add(copies.copy(source));
            assertEquals(CapturePacketCopies.MAX_COPIES * 25, copies.retainedBytes());
            copies.clear();
            for (int i = 0; i < first.size(); i++) {
                byte[] next = copies.copy(source);
                assertArrayEquals(new byte[] {42}, next);
                if (i < CapturePacketCopies.MAX_COPIES) assertSame(first.get(i), next);
                else assertNotSame(first.get(i), next);
            }
        } finally {
            source.release();
        }
    }
}
