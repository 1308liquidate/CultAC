package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionLifecycle;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.UnsupportedOnVersionException;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.PromiseCombiner;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Prepares and emits ordered packet groups synchronously on the connection event loop. */
public final class CultEncoder extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "cult-encoder";
    private static final String HOST_CONTROL_NAME = NAME + "-host-control";

    private final CultConnection connection;
    private boolean insideBundle;
    // Members of the current physical flush bundle. BundlerInfo accepts at most 4,096.
    private int flushBundleMembers;
    // The latest CultAC ping written into the current physical flush bundle; 0 when there is none.
    private int flushPing;
    private static final int MAX_BUNDLE_PACKETS = 4096;
    // A packet shares the bundle's ping only while the frames prepared around it still fit.
    private static final int SHARE_HEADROOM = 256;
    private Output emission;
    // The output of the outermost write being prepared; its frames precede the packet being expanded.
    private Output processing;
    private boolean removed;

    public CultEncoder(CultConnection connection) {
        this.connection = connection;
    }

    public static void install(CultConnection connection) {
        var pipeline = connection.channel().pipeline();
        String before = pipeline.get("encoder") != null ? "encoder" : "outbound_config";
        pipeline.addBefore(
                connection.owner() == connection.channel().eventLoop() ? null : connection.owner(),
                before,
                NAME,
                new CultEncoder(connection));
        if (connection.owner() != connection.channel().eventLoop()) {
            pipeline.addAfter(NAME, HOST_CONTROL_NAME, new HostControl(pipeline.context(NAME)));
        }
    }

    public static void uninstall(Channel channel) {
        var pipeline = channel.pipeline();
        if (pipeline.get(HOST_CONTROL_NAME) != null) pipeline.remove(HOST_CONTROL_NAME);
        if (pipeline.get(NAME) != null) pipeline.remove(NAME);
    }

    /** The index after the packet ID VarInt, which {@link Wire#peekVarInt} has read in full. */
    private static int afterPacketId(ByteBuf frame) {
        int index = frame.readerIndex();
        while ((frame.getByte(index++) & 0x80) != 0) {}
        return index;
    }

    private static boolean packetMessage(Object message) {
        return message instanceof ByteBuf
                || message instanceof CultWrite
                || message instanceof CultConnection.WriteGroup
                || message instanceof CultConnection.WriteTask;
    }

    /** Paper waits synchronously for protocol tasks on its I/O loop. Only packets may change executor. */
    private static final class HostControl extends ChannelOutboundHandlerAdapter {
        private final ChannelHandlerContext encoder;

        private HostControl(ChannelHandlerContext encoder) {
            this.encoder = encoder;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            if (packetMessage(message)) ctx.write(message, promise);
            else encoder.write(message, promise);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        // Host configuration objects pass through to their original handlers.
        if (!packetMessage(message)) {
            ctx.write(message, promise);
            return;
        }
        if (!connection.packets().translated() && message instanceof ByteBuf frame) {
            try {
                var phase = connection.phase(PacketDirection.CLIENTBOUND);
                var route = connection.dispatcher().get(PacketDirection.CLIENTBOUND, phase, Wire.peekVarInt(frame));
                if (route == null || route.type().codec().forwardsUndecoded(frame, afterPacketId(frame))) {
                    if (connection.packetLoggingActive()) {
                        connection.runInModel(
                                () -> connection.observePacket(PacketDirection.CLIENTBOUND, phase, frame));
                    }
                    writeRaw(ctx, frame, promise);
                    return;
                }
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("outbound packet lookup", failure);
                writeRaw(ctx, frame, promise);
                return;
            }
        }
        boolean[] consumed = {false};
        try {
            connection.runInModel(() -> {
                consumed[0] = true;
                Output output = null;
                try {
                    if (removed || !ctx.channel().isActive()) {
                        new Pending(message, promise).discard(new ClosedChannelException());
                        return;
                    }
                    output = process(ctx, new Pending(message, promise));
                    if (emission == null) emit(ctx, output);
                    else emission.append(output);
                } catch (Throwable failure) {
                    if (output != null) output.discard(failure);
                    promise.tryFailure(failure);
                    CultNetworkManager.reportFailure("outbound packet processing", failure);
                }
            });
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("outbound model context", failure);
            if (!consumed[0]) {
                if (message instanceof ByteBuf frame) writeRaw(ctx, frame, promise);
                else new Pending(message, promise).discard(failure);
            }
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (emission == null) {
            closeFlushBundle(ctx);
            ctx.flush();
        } else {
            // A recursive flush happens after the frames already in emission.
            emission.addLast(new Frame(null, null, null));
        }
    }

    /** A host callback runs after all preceding synchronous writes and their proofs. */
    public void executeAfterWrites(ChannelHandlerContext ctx, Runnable task) {
        if (ctx.handler() != this || !ctx.executor().inEventLoop())
            throw new IllegalStateException("Outbound callbacks require this channel's event loop");
        write(ctx, new CultConnection.WriteTask(java.util.Objects.requireNonNull(task)), ctx.newPromise());
    }

    private Output process(ChannelHandlerContext ctx, Pending pending) {
        var output = new Output();
        Output enclosing = processing;
        processing = output;
        var activeFamilies = new Families();
        // Recursive callbacks finish first and append their complete groups here.
        // The enclosing group's frames have not been appended yet, so these writes
        // precede even its opening delimiter. No retained expansion tree is needed.
        connection.reentrantWriter(
                (message, promise) -> prepare(ctx, new Pending(message, promise), activeFamilies, output));
        try {
            prepare(ctx, pending, activeFamilies, output);
            return output;
        } catch (Throwable failure) {
            output.discard(failure);
            throw failure;
        } finally {
            connection.reentrantWriter(null);
            processing = enclosing;
        }
    }

    private void prepare(ChannelHandlerContext ctx, Pending pending, Families activeFamilies, Output output) {
        // Reentrant writes observe the emitted wire state, not an enclosing
        // group's planned delimiters. I/O cannot emit while this dispatch runs.
        var batch = new Batch(insideBundle);
        ByteBuf fallback = pending.message instanceof ByteBuf frame ? frame.retain() : null;
        try {
            if (pending.message instanceof CultConnection.WriteTask task) {
                // The active writer captures callback-generated packets at this exact
                // queue position, before packets that arrived after the callback.
                task.action().run();
                output.flush |= !output.isEmpty();
                pending.promise.trySuccess();
            } else if (pending.message instanceof CultConnection.WriteGroup group) {
                prepareGroup(ctx, group, pending.promise, activeFamilies, batch);
            } else {
                boolean requested = expandPacket(ctx, pending, activeFamilies, batch);
                // A leading ping opens the flush bundle at emission, which then holds this packet.
                if (!insideBundle
                        && !batch.lead
                        && (requested || batch.output.size() > 1)
                        && connection.clientHasLevel()) wrap(ctx, batch.output);
            }
            output.append(batch.output);
        } catch (Throwable failure) {
            batch.output.discard(failure, fallback == null ? null : pending.promise);
            CultNetworkManager.reportFailure("outbound packet expansion", failure);
            if (fallback != null) {
                PacketType<?> type = fallbackType(fallback);
                output.addLast(new Frame(fallback, pending.promise, type));
                fallback = null;
                connection.placedClientbound(type);
            } else pending.promise.tryFailure(failure);
        } finally {
            if (fallback != null) fallback.release();
        }
    }

    private PacketType<?> fallbackType(ByteBuf frame) {
        try {
            // The frame's ID is in the client's numbering, which differs from the dispatcher's when translated.
            var type = connection
                    .packets()
                    .type(
                            connection.phase(PacketDirection.CLIENTBOUND),
                            PacketDirection.CLIENTBOUND,
                            Wire.peekVarInt(frame));
            var route = type == null ? null : connection.dispatcher().get(type);
            return route == null ? null : route.type();
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("outbound fallback identity", failure);
            return null;
        }
    }

    private void prepareGroup(
            ChannelHandlerContext ctx,
            CultConnection.WriteGroup group,
            ChannelPromise promise,
            Families activeFamilies,
            Batch batch) {
        boolean wrapGroup = group.bundle() && !batch.inside && connection.clientHasLevel();
        var parts = new LinkedHashSet<ChannelPromise>();
        if (wrapGroup) batch.add(delimiter(ctx));
        for (CultWrite write : group.writes()) {
            var part = ctx.newPromise();
            parts.add(part);
            var pending = new Pending(write, part);
            if (group.bundle()) {
                expandPacket(ctx, pending, activeFamilies, batch);
            } else {
                // Unbundled groups give each entry its own automatic bundle scope.
                var entry = new Batch(batch.inside);
                try {
                    boolean requested = expandPacket(ctx, pending, activeFamilies, entry);
                    if (!batch.inside
                            && !entry.lead
                            && (requested || entry.output.size() > 1)
                            && connection.clientHasLevel()) wrap(ctx, entry.output);
                    batch.inside = entry.inside;
                    batch.lead |= entry.lead;
                    batch.output.append(entry.output);
                } catch (Throwable failure) {
                    entry.output.discard(failure);
                    throw failure;
                }
            }
        }
        if (wrapGroup) batch.add(delimiter(ctx));
        batch.output.collectPromises(parts);
        batch.output.addGroup(new GroupCompletion(List.copyOf(parts), promise));
    }

    private boolean expandPacket(ChannelHandlerContext ctx, Pending pending, Families activeFamilies, Batch batch) {
        return expandPacket(ctx, pending, activeFamilies, batch, 0);
    }

    /**
     * Expands only packets. The caller decides whether this expansion needs a bundle. A nonzero {@code leadPing}
     * is the transaction of the CultAC ping in {@code pending}, which opens the flush bundle.
     */
    private boolean expandPacket(
            ChannelHandlerContext ctx, Pending pending, Families activeFamilies, Batch batch, int leadPing) {
        if (!(pending.message instanceof CultWrite write)) {
            return expandFrame(ctx, (ByteBuf) pending.message, pending.promise, false, false, activeFamilies, batch, 0);
        }
        var phase = connection.phase(PacketDirection.CLIENTBOUND);
        ac.cult.cultac.protocol.ProjectedPackets.Encoded encoded;
        try {
            var type = connection.packets().writableType(write.packet());
            if (type.direction() != PacketDirection.CLIENTBOUND) {
                throw new UnsupportedOnVersionException("Not clientbound: " + type);
            }
            // CultAC's own play packets need the client's level; without one they are dropped, never sent.
            if (!type.phases().contains(ConnectionPhase.CONFIGURATION) && !connection.clientHasLevel()) {
                connection.droppedWithoutLevel(type);
                pending.promise.tryFailure(new UnsupportedOnVersionException(type + " needs the client's level"));
                return false;
            }
            encoded = connection.packets().encode(phase, type, write.packet(), ctx.alloc(), connection);
        } catch (Throwable failure) {
            pending.promise.tryFailure(failure);
            CultNetworkManager.reportFailure("authored packet encoding", failure);
            return false;
        }
        if (encoded.frames().isEmpty()) {
            pending.promise.trySuccess();
            return false;
        }
        var parts = encoded.frames().size() > 1
                ? new ArrayList<ChannelPromise>(encoded.frames().size())
                : null;
        boolean requested = false;
        int transferred = 0;
        try {
            for (ByteBuf frame : encoded.frames()) {
                var part = encoded.frames().size() == 1 ? pending.promise : ctx.newPromise();
                if (parts != null) parts.add(part);
                transferred++;
                requested |= expandFrame(ctx, frame, part, write.silent(), true, activeFamilies, batch, leadPing);
            }
            if (encoded.frames().size() > 1)
                batch.output.addGroup(new GroupCompletion(List.copyOf(parts), pending.promise));
            return requested;
        } catch (Throwable failure) {
            for (int i = transferred; i < encoded.frames().size(); i++)
                encoded.frames().get(i).release();
            pending.promise.tryFailure(failure);
            throw failure;
        }
    }

    /** Owns one physical frame; model-derived values cannot replace its network bytes. */
    @SuppressWarnings("unchecked")
    private boolean expandFrame(
            ChannelHandlerContext ctx,
            ByteBuf bytes,
            ChannelPromise promise,
            boolean silent,
            boolean authored,
            Families activeFamilies,
            Batch batch,
            int leadPing) {
        var phase = connection.phase(PacketDirection.CLIENTBOUND);
        boolean requested = false;
        // The route the predicate found for the frame's type, and whether it was skipped as untracked.
        PacketDispatcher.Route[] probed = new PacketDispatcher.Route[2];
        PacketType<?> forwardedType = null;
        try {
            var values = connection.packets().read(phase, PacketDirection.CLIENTBOUND, bytes, connection, type -> {
                var route = connection.dispatcher().get(type);
                if (route == null) return false;
                probed[0] = route;
                if (phase != ConnectionPhase.PLAY || tracked()) return true;
                if (route.send() != null && route.send().hasConnectionHandlers()) return true;
                probed[1] = route;
                return false;
            });
            if (probed[1] != null) forwardedType = probed[1].type();
            for (var value : values) {
                var route = probed[0];
                forwardedType = route.type();
                connection.prepare();
                if (silent || route.send() == null || activeFamilies.contains(route.type())) continue;
                activeFamilies.addLast(route.type());
                try {
                    var type = (PacketType<ClientboundPacket>) route.type();
                    var event = new PacketSendEvent<>(
                            connection.user(), phase, type, (ClientboundPacket) value.packet(), batch.inside);
                    if (authored) event.authored();
                    event.sharedPing(sharedPing(batch));
                    connection.dispatcher().send(event, route.send());
                    if (event.isCancelled()) {
                        promise.trySuccess();
                        return false;
                    }
                    CultWrite leading = event.leadingPing();
                    if (leading != null) {
                        expandPacket(
                                ctx,
                                new Pending(leading, ctx.newPromise()),
                                activeFamilies,
                                batch,
                                event.flushPing());
                    }
                    for (CultWrite write : event.writesBefore()) {
                        expandPacket(ctx, new Pending(write, ctx.newPromise()), activeFamilies, batch);
                    }
                    {
                        if (bytes == null) throw new IllegalStateException("Multiple physical packet identities");
                        if (event.isReplaced()) {
                            var replacement = connection
                                    .packets()
                                    .encode(phase, type, event.getPacket(), ctx.alloc(), connection);
                            bytes.release();
                            bytes = null;
                            appendReplacement(ctx, replacement, promise, type, batch, leadPing);
                        } else {
                            int ping = leadPing != 0 ? leadPing : event.trackedPing();
                            batch.add(new Frame(bytes, promise, type, ping, leadPing != 0));
                            bytes = null;
                        }
                        connection.placedClientbound(type);
                    }
                    for (CultWrite write : event.writesAfter()) {
                        expandPacket(ctx, new Pending(write, ctx.newPromise()), activeFamilies, batch);
                    }
                    batch.output.addTasks(event.tasksAfter());
                    requested |= event.isBundleRequested();
                } finally {
                    activeFamilies.removeLast();
                }
            }
            // Unknown/cancelled model packets still retain their physical representation.
            if (bytes != null) {
                PacketType<?> placed = forwardedType != null ? forwardedType : fallbackType(bytes);
                batch.add(new Frame(bytes, promise, forwardedType, leadPing, leadPing != 0));
                bytes = null;
                connection.placedClientbound(placed);
            }
            return requested;
        } finally {
            if (bytes != null) bytes.release();
        }
    }

    /**
     * Send routes without connection handlers dispatch only to an attached player, so an untracked
     * connection's play values for them would be decoded and discarded; those frames still forward
     * unchanged. The player's model
     * supplies older-wire world values, so decoding them without one cannot succeed.
     */
    private boolean tracked() {
        connection.prepare();
        return connection.player() != null;
    }

    private void appendReplacement(
            ChannelHandlerContext ctx,
            ac.cult.cultac.protocol.ProjectedPackets.Encoded replacement,
            ChannelPromise promise,
            PacketType<?> type,
            Batch batch,
            int leadPing) {
        if (replacement.frames().isEmpty()) {
            promise.trySuccess();
            return;
        }
        var parts = replacement.frames().size() > 1
                ? new ArrayList<ChannelPromise>(replacement.frames().size())
                : null;
        int transferred = 0;
        try {
            for (ByteBuf frame : replacement.frames()) {
                var part = replacement.frames().size() == 1 ? promise : ctx.newPromise();
                if (parts != null) parts.add(part);
                boolean first = transferred == 0;
                batch.add(new Frame(frame, part, first ? type : null, first ? leadPing : 0, first && leadPing != 0));
                transferred++;
            }
            if (replacement.frames().size() > 1)
                batch.output.addGroup(new GroupCompletion(List.copyOf(parts), promise));
        } catch (Throwable failure) {
            for (int i = transferred; i < replacement.frames().size(); i++)
                replacement.frames().get(i).release();
            throw failure;
        }
    }

    private void wrap(ChannelHandlerContext ctx, Output output) {
        output.addFirst(delimiter(ctx));
        output.addLast(delimiter(ctx));
    }

    private Frame delimiter(ChannelHandlerContext ctx) {
        var type = ClientboundPackets.BUNDLE_DELIMITER;
        var encoded = connection
                .packets()
                .encode(
                        connection.phase(PacketDirection.CLIENTBOUND),
                        type,
                        type.opaqueValue(),
                        ctx.alloc(),
                        connection);
        if (encoded.frames().size() != 1) {
            encoded.frames().forEach(ByteBuf::release);
            throw new IllegalStateException("Bundle delimiter must encode one physical frame");
        }
        ByteBuf bytes = encoded.frames().getFirst();
        return new Frame(bytes, ctx.newPromise(), type);
    }

    /**
     * Another bundle, a protocol transition or a new level cannot be inside the flush bundle.
     * ClientPacketListener#handleLogin and #handleRespawn replace the level and render a
     * LevelLoadingScreen frame from the handler, which vanilla never runs inside a bundle.
     */
    private static boolean structural(PacketType<?> type) {
        return type == ClientboundPackets.BUNDLE_DELIMITER
                || type == ClientboundPackets.LOGIN
                || type == ClientboundPackets.RESPAWN
                || ConnectionLifecycle.handles(type);
    }

    /**
     * The ping a packet prepared now can share. Frames prepared earlier and not yet emitted could close or split
     * the flush bundle before this packet, so with any of them pending the packet leads with its own ping.
     */
    private int sharedPing(Batch batch) {
        if (!connection.flushBundleOpen()
                || flushPing == 0
                || flushBundleMembers >= MAX_BUNDLE_PACKETS - SHARE_HEADROOM) return 0;
        boolean pendingFrames = (emission != null && !emission.isEmpty())
                || (processing != null && !processing.isEmpty())
                || !batch.output.isEmpty();
        return pendingFrames ? 0 : flushPing;
    }

    private void writeFrame(ChannelHandlerContext ctx, Frame frame) {
        connection.observePacket(
                PacketDirection.CLIENTBOUND, connection.phase(PacketDirection.CLIENTBOUND), frame.bytes);
        ctx.write(frame.bytes, frame.promise);
        if (frame.type == ClientboundPackets.BUNDLE_DELIMITER) insideBundle = !insideBundle;
        else if (frame.type != null) {
            try {
                connection.forwarded(frame.type, null);
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("outbound protocol transition", failure);
            }
        }
        // A later ping in the bundle proves the same task, so it replaces the one packets share.
        if (frame.ping != 0 && connection.flushBundleOpen()) flushPing = frame.ping;
    }

    /** A frame forwarded without expansion; inside an open flush bundle it is an ordinary member. */
    private void writeRaw(ChannelHandlerContext ctx, ByteBuf frame, ChannelPromise promise) {
        if (connection.flushBundleOpen() && structural(wireType(frame))) closeFlushBundle(ctx);
        countFlushBundleMember(ctx);
        ctx.write(frame, promise);
    }

    /** The model family of a clientbound frame, whether or not CultAC listens to it; null when unknown. */
    private PacketType<?> wireType(ByteBuf frame) {
        try {
            return connection
                    .packets()
                    .type(
                            connection.phase(PacketDirection.CLIENTBOUND),
                            PacketDirection.CLIENTBOUND,
                            Wire.peekVarInt(frame));
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** A leading ping opens the flush bundle, or a new physical bundle when the current one is nearly full. */
    private void openFlushBundle(ChannelHandlerContext ctx) {
        if (connection.flushBundleOpen()) {
            if (flushBundleMembers < MAX_BUNDLE_PACKETS - SHARE_HEADROOM) return;
            writeFrame(ctx, delimiter(ctx));
            writeFrame(ctx, delimiter(ctx));
        } else {
            // Inside another bundle the ping and its packet are still handled in one task.
            if (insideBundle || !connection.clientHasLevel()) return;
            writeFrame(ctx, delimiter(ctx));
            connection.flushBundleOpen(true);
        }
        flushBundleMembers = 0;
        flushPing = 0;
    }

    /** Counts the next member, splitting the physical bundle at the client's limit. */
    private void countFlushBundleMember(ChannelHandlerContext ctx) {
        if (!connection.flushBundleOpen()) return;
        if (flushBundleMembers == MAX_BUNDLE_PACKETS) {
            // Unreachable unless more than the headroom was prepared ahead of a sharing packet.
            writeFrame(ctx, delimiter(ctx));
            writeFrame(ctx, delimiter(ctx));
            flushBundleMembers = 0;
            flushPing = 0;
        }
        ++flushBundleMembers;
    }

    private void closeFlushBundle(ChannelHandlerContext ctx) {
        if (!connection.flushBundleOpen()) return;
        writeFrame(ctx, delimiter(ctx));
        connection.flushBundleOpen(false);
        flushBundleMembers = 0;
        flushPing = 0;
    }

    private void emit(ChannelHandlerContext ctx, Output output) {
        if (removed || !ctx.channel().isActive()) {
            output.discard(new ClosedChannelException());
            return;
        }
        emission = output;
        try {
            do {
                settleGroups(ctx, output);
                while (!output.isEmpty()) {
                    settleGroups(ctx, output);
                    Frame frame = output.removeFirst();
                    if (frame.bytes == null) {
                        closeFlushBundle(ctx);
                        ctx.flush();
                        continue;
                    }
                    if (frame.lead) openFlushBundle(ctx);
                    else if (structural(frame.type)) closeFlushBundle(ctx);
                    countFlushBundleMember(ctx);
                    writeFrame(ctx, frame);
                }
                if (output.flush) {
                    output.flush = false;
                    closeFlushBundle(ctx);
                    ctx.flush();
                }
                if (!output.isEmpty()) continue;
                while (output.hasTasks() && output.isEmpty())
                    CultNetworkManager.runDeferredPacketTask("tasksAfterSend", output.tasks.removeFirst());
            } while (!output.isEmpty() || output.hasTasks() || output.flush);
        } finally {
            emission = null;
        }
    }

    private void settleGroups(ChannelHandlerContext ctx, Output output) {
        if (output.groups == null) return;
        for (GroupCompletion group : output.groups) {
            var combiner = new PromiseCombiner(ctx.executor());
            for (ChannelPromise part : group.parts) combiner.add((Future<?>) part);
            combiner.finish(group.promise);
        }
        output.groups.clear();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        removed = true;
        // Uninstall and rebind happen on live connections. A flush bundle left open on the
        // wire would absorb every later packet, so close it first.
        if (ctx.channel().isActive() && connection.flushBundleOpen()) {
            try {
                closeFlushBundle(ctx);
                ctx.flush();
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("flush bundle close on removal", failure);
            }
        }
        connection.flushBundleOpen(false);
        flushPing = 0;
    }

    private record Pending(Object message, ChannelPromise promise) {
        void discard(Throwable failure) {
            if (message instanceof ByteBuf bytes) bytes.release();
            promise.tryFailure(failure);
        }
    }

    /** {@code ping} is the transaction of a CultAC ping frame; a {@code lead} ping opens the flush bundle. */
    private record Frame(ByteBuf bytes, ChannelPromise promise, PacketType<?> type, int ping, boolean lead) {
        Frame(ByteBuf bytes, ChannelPromise promise, PacketType<?> type) {
            this(bytes, promise, type, 0, false);
        }
    }

    private record GroupCompletion(List<ChannelPromise> parts, ChannelPromise promise) {}

    /** Inline recursion guard for the ordinary one-family dispatch. */
    private static final class Families {
        PacketType<?> first;
        ArrayDeque<PacketType<?>> nested;

        boolean contains(PacketType<?> type) {
            return first == type || nested != null && nested.contains(type);
        }

        void addLast(PacketType<?> type) {
            if (first == null) first = type;
            else {
                if (nested == null) nested = new ArrayDeque<>(2);
                nested.addLast(type);
            }
        }

        void removeLast() {
            if (nested == null || nested.isEmpty()) first = null;
            else nested.removeLast();
        }
    }

    /** Local bundle cursor during expansion; it never changes the emitted wire state. */
    private static final class Batch {
        final Output output = new Output();
        boolean inside;
        // A leading ping opens the flush bundle for this expansion, so it needs no bundle of its own.
        boolean lead;

        Batch(boolean inside) {
            this.inside = inside;
        }

        void add(Frame frame) {
            output.addLast(frame);
            if (frame.type == ClientboundPackets.BUNDLE_DELIMITER) inside = !inside;
            lead |= frame.lead;
        }
    }

    /** Owns only un-emitted frames. Appending transfers ownership without retaining a tree. */
    private static final class Output {
        // Most expansions contain exactly one frame. Allocate a queue only for a second one.
        Frame first;
        ArrayDeque<Frame> tail;

        boolean isEmpty() {
            return first == null;
        }

        int size() {
            return first == null ? 0 : 1 + (tail == null ? 0 : tail.size());
        }

        void addLast(Frame frame) {
            if (first == null) first = frame;
            else {
                if (tail == null) tail = new ArrayDeque<>(4);
                tail.addLast(frame);
            }
        }

        void addFirst(Frame frame) {
            if (first != null) {
                if (tail == null) tail = new ArrayDeque<>(4);
                tail.addFirst(first);
            }
            first = frame;
        }

        Frame removeFirst() {
            Frame result = first;
            first = tail == null ? null : tail.pollFirst();
            return result;
        }

        void collectPromises(java.util.Set<ChannelPromise> parts) {
            if (first != null) parts.add(first.promise);
            if (tail != null) for (Frame frame : tail) parts.add(frame.promise);
        }

        ArrayDeque<Runnable> tasks;
        List<GroupCompletion> groups;
        boolean flush;

        boolean hasTasks() {
            return tasks != null && !tasks.isEmpty();
        }

        void addTasks(List<Runnable> actions) {
            if (actions.isEmpty()) return;
            if (tasks == null) tasks = new ArrayDeque<>(actions.size());
            tasks.addAll(actions);
        }

        void addGroup(GroupCompletion group) {
            if (groups == null) groups = new ArrayList<>(1);
            groups.add(group);
        }

        void append(Output source) {
            if (!source.isEmpty()) {
                if (isEmpty()) {
                    first = source.first;
                    tail = source.tail;
                    source.first = null;
                    source.tail = null;
                } else while (!source.isEmpty()) addLast(source.removeFirst());
            }
            if (source.hasTasks()) {
                if (tasks == null) tasks = source.tasks;
                else tasks.addAll(source.tasks);
            }
            if (source.groups != null && !source.groups.isEmpty()) {
                if (groups == null) groups = source.groups;
                else for (int i = 0; i < source.groups.size(); i++) groups.add(source.groups.get(i));
            }
            flush |= source.flush;
            source.tasks = null;
            source.groups = null;
            source.flush = false;
        }

        void discard(Throwable failure) {
            discard(failure, null);
        }

        void discard(Throwable failure, ChannelPromise preserved) {
            while (!isEmpty()) {
                Frame frame = removeFirst();
                if (frame.bytes != null) {
                    frame.bytes.release();
                    if (frame.promise != preserved) frame.promise.tryFailure(failure);
                }
            }
            if (groups != null) {
                for (GroupCompletion group : groups) {
                    if (group.promise != preserved) group.promise.tryFailure(failure);
                }
                groups.clear();
            }
            if (tasks != null) tasks.clear();
        }
    }
}
