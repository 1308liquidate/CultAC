package ac.cult.cultac.platform.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.function.Consumer;

/**
 * Reports where the backend's bursts end, at the head of the backend channel.
 *
 * <p>Velocity's backend connection is unencrypted, so its raw bytes are VarInt-length-prefixed
 * frames. A read that stops inside a frame is a split burst, not the end of one. Velocity flushes
 * the client from {@code BackendPlaySessionHandler#readCompleted}; this handler runs first, so work
 * done on the connection owner here is written before that flush.
 */
final class VelocityBackendTickObserver extends ChannelInboundHandlerAdapter {
    static final String NAME = "cult-backend-ticks";
    private static final int MAX_LENGTH_SHIFT = 21;

    private final Consumer<Boolean> burstEnd;
    private int remaining;
    private int length;
    private int shift;

    VelocityBackendTickObserver(Consumer<Boolean> burstEnd) {
        this.burstEnd = burstEnd;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) scan(buf);
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        try {
            burstEnd.accept(insideFrame());
        } finally {
            ctx.fireChannelReadComplete();
        }
    }

    boolean insideFrame() {
        return remaining > 0 || shift > 0;
    }

    void scan(ByteBuf buf) {
        int index = buf.readerIndex();
        int end = buf.writerIndex();
        while (index < end) {
            if (remaining > 0) {
                int skipped = Math.min(remaining, end - index);
                remaining -= skipped;
                index += skipped;
                continue;
            }
            byte next = buf.getByte(index++);
            length |= (next & 0x7F) << shift;
            if ((next & 0x80) != 0) {
                shift += 7;
                // Velocity's frame decoder rejects longer prefixes; stop tracking rather than guess.
                if (shift > MAX_LENGTH_SHIFT) reset();
                continue;
            }
            remaining = length;
            length = 0;
            shift = 0;
        }
    }

    private void reset() {
        remaining = 0;
        length = 0;
        shift = 0;
    }
}
