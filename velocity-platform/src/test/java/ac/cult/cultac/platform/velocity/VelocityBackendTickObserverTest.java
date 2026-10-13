package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VelocityBackendTickObserverTest {
    @Test
    void readsThatStopInsideAFrameOrItsLengthAreNotBurstEnds() {
        List<Boolean> partial = new ArrayList<>();
        var observer = new VelocityBackendTickObserver(partial::add);
        // Frame of 300 bytes: VarInt 300 = 0xAC 0x02.
        byte[] body = new byte[300];
        observer.scan(Unpooled.wrappedBuffer(new byte[] {(byte) 0xAC}));
        assertTrue(observer.insideFrame(), "inside the length prefix");
        observer.scan(Unpooled.wrappedBuffer(new byte[] {0x02}));
        observer.scan(Unpooled.wrappedBuffer(body, 0, 100));
        assertTrue(observer.insideFrame());
        observer.scan(Unpooled.wrappedBuffer(body, 0, 200));
        assertFalse(observer.insideFrame());
        // Two small frames in one read.
        observer.scan(Unpooled.wrappedBuffer(new byte[] {2, 0, 0, 1, 0}));
        assertFalse(observer.insideFrame());
        observer.scan(Unpooled.wrappedBuffer(new byte[] {3, 0}));
        assertTrue(observer.insideFrame());
    }
}
