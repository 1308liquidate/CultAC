package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostProtocolConfigurationTest {
    @Test
    void queuedBedrockFramesRespectTheHostsProtocolReadPause() throws Exception {
        var owner = new DefaultEventExecutor();
        var channel = new EmbeddedChannel();
        var received = new ArrayList<Integer>();
        var enteredUnconfiguredProtocol = new AtomicBoolean();
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                var frame = (ByteBuf) message;
                try {
                    if (!channel.config().isAutoRead()) {
                        enteredUnconfiguredProtocol.set(true);
                        return;
                    }
                    Wire.readVarInt(frame);
                    int id = frame.readInt();
                    received.add(id);
                    // Native ProtocolSwapHandler stops reads after a terminal packet.
                    if (id == 1) {
                        channel.config().setAutoRead(false);
                        ctx.pipeline().addBefore(ctx.name(), "inbound_config", new ChannelInboundHandlerAdapter());
                    }
                } finally {
                    frame.release();
                }
            }
        });
        var runtime = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
        var routes = new TestTransportRoutes(runtime);
        var connection = new CultConnection(channel, routes.dispatcher, ignored -> new PacketOwner(owner, null));
        connection.resolveOwner();
        connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
        CultDecoder.install(connection);
        try {
            // Both frames have already crossed the upstream I/O flow handler before
            // the owning movement executor forwards the first to the native decoder.
            for (int id : new int[] {1, 2}) {
                ByteBuf frame = Unpooled.buffer();
                Wire.writeVarInt(frame, runtime.data().packets(ConnectionPhase.CONFIGURATION, PacketDirection.SERVERBOUND)
                        .id("minecraft:pong"));
                frame.writeInt(id);
                channel.writeInbound(frame);
            }
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            channel.runPendingTasks();
            assertFalse(enteredUnconfiguredProtocol.get());
            assertEquals(java.util.List.of(1), received);
            // ByteToMessageDecoder can request another read while its terminal codec
            // is being replaced. That request must not deliver to inbound_config.
            channel.read();
            channel.runPendingTasks();
            assertFalse(enteredUnconfiguredProtocol.get());
            assertEquals(java.util.List.of(1), received);
            channel.pipeline().remove("inbound_config");
            // Installing the next native codec resumes reads; queued frames follow it.
            channel.config().setAutoRead(true);
            channel.read();
            channel.runPendingTasks();
            assertEquals(java.util.List.of(1, 2), received);
        } finally {
            channel.finishAndReleaseAll();
            owner.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            channel.runPendingTasks();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hostProtocolSwitchCompletesOnIoLoopEvenWhenPacketOwnerIsBusy(boolean separateOwner) throws Exception {
        var io = new DefaultEventLoopGroup(1);
        var owner = new DefaultEventExecutor();
        var channel = new LocalChannel();
        var unblockOwner = new CountDownLatch(1);
        var ownerBlocked = new CompletableFuture<Void>();
        var observed = new CompletableFuture<Object>();
        var configurationTask = new Object();
        try {
            io.register(channel).sync();
            channel.eventLoop().submit(() -> {
                channel.pipeline().addLast("decoder", new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                        assertTrue(channel.eventLoop().inEventLoop());
                        observed.complete(message);
                        promise.setSuccess();
                    }
                });
                channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
                var runtime = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));
                var routes = new TestTransportRoutes(runtime);
                var connection = new CultConnection(channel, routes.dispatcher,
                        ignored -> separateOwner ? new PacketOwner(owner, null) : null);
                connection.resolveOwner();
                CultEncoder.install(connection);
            }).get(5, TimeUnit.SECONDS);
            owner.execute(() -> {
                ownerBlocked.complete(null);
                try {
                    unblockOwner.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            });
            ownerBlocked.get(5, TimeUnit.SECONDS);
            // Paper Connection.setupInboundProtocol does this synchronously in beginLogin.
            channel.eventLoop().submit(() -> channel.writeAndFlush(configurationTask).syncUninterruptibly())
                    .get(5, TimeUnit.SECONDS);
            assertSame(configurationTask, observed.get(5, TimeUnit.SECONDS));
        } finally {
            unblockOwner.countDown();
            channel.close().sync();
            owner.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            io.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }
}
