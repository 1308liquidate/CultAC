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
    private int flushWavePackets;
    // A Java bundle accepts at most 4,096 members, including the trailing proof.
    private static final int MAX_FLUSH_WAVE_PACKETS = 4095;
    private Output emission;
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
        // Only protocol/bundle structure can interrupt a flush group; no packet-name denylist.
        // During emission, queued wave frames precede this write; the emit loop closes the wave in order.
        if (emission == null && connection.pendingFlushProof() != null && structuralWrite(message))
            finishFlushWave(ctx);
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
            finishFlushWave(ctx);
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
                // An explicit per-packet bundle remains necessary when no flush proof exists.
                // A pending flush proof puts ordinary updates in the persistent outbound group.
                if (!insideBundle
                        && connection.pendingFlushProof() == null
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
                    if (!batch.inside && (requested || entry.output.size() > 1) && connection.clientHasLevel())
                        wrap(ctx, entry.output);
                    batch.inside = entry.inside;
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

    /** Expands only packets. The caller decides whether this expansion needs a bundle. */
    private boolean expandPacket(ChannelHandlerContext ctx, Pending pending, Families activeFamilies, Batch batch) {
        if (!(pending.message instanceof CultWrite write)) {
            return expandFrame(ctx, (ByteBuf) pending.message, pending.promise, false, false, activeFamilies, batch);
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
                requested |= expandFrame(ctx, frame, part, write.silent(), true, activeFamilies, batch);
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
            Batch batch) {
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
                    connection.dispatcher().send(event, route.send());
                    if (event.isCancelled()) {
                        promise.trySuccess();
                        return false;
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
                            appendReplacement(ctx, replacement, promise, type, batch);
                        } else {
                            batch.add(new Frame(bytes, promise, type));
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
                batch.add(new Frame(bytes, promise, forwardedType));
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
            Batch batch) {
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
                batch.add(new Frame(frame, part, transferred == 0 ? type : null));
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
     * Bundle markers, independently tracked pings and protocol transitions are structural. A frame
     * whose family the model does not know is treated as structural too.
     */
    private static boolean structural(PacketType<?> type) {
        return type == null
                || type == ClientboundPackets.BUNDLE_DELIMITER
                || type == ClientboundPackets.PING
                || ConnectionLifecycle.handles(type);
    }

    /** Frames without a dispatcher route carry no type; classify them by their wire family instead. */
    private boolean structuralFrame(Frame frame) {
        return structural(frame.type != null ? frame.type : wireType(frame.bytes));
    }

    /** The model family of a clientbound frame, whether or not Cult listens to it; null when unknown. */
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

    private boolean structuralWrite(Object message) {
        try {
            if (message instanceof ByteBuf frame) return structural(wireType(frame));
            if (message instanceof CultWrite write)
                return structural(connection.packets().writableType(write.packet()));
            // Explicit write groups are separate causal groups with their own bundle semantics.
            return message instanceof CultConnection.WriteGroup;
        } catch (RuntimeException failure) {
            return true;
        }
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
    }

    /** A frame forwarded without expansion; inside an open flush bundle it is an ordinary member. */
    private void writeRaw(ChannelHandlerContext ctx, ByteBuf frame, ChannelPromise promise) {
        countFlushWaveMember(ctx);
        ctx.write(frame, promise);
    }

    /** A flush bundle opens only while a proof is reserved; closing it places that proof. */
    private void startFlushWave(ChannelHandlerContext ctx) {
        if (connection.flushBundleOpen() || insideBundle || !connection.clientHasLevel()) return;
        writeFrame(ctx, delimiter(ctx));
        connection.flushBundleOpen(true);
        flushWavePackets = 0;
    }

    /** Counts the next member, splitting the physical bundle without acknowledging the shared proof early. */
    private void countFlushWaveMember(ChannelHandlerContext ctx) {
        if (!connection.flushBundleOpen()) return;
        if (flushWavePackets >= MAX_FLUSH_WAVE_PACKETS) {
            writeFrame(ctx, delimiter(ctx));
            writeFrame(ctx, delimiter(ctx));
            flushWavePackets = 0;
        }
        ++flushWavePackets;
    }

    /** The reserved ping is the last bundle member before the actual outbound flush. */
    private void finishFlushWave(ChannelHandlerContext ctx) {
        var proof = connection.takeFlushProof();
        if (proof != null) {
            Output group = process(ctx, new Pending(proof.packet(), ctx.newPromise()));
            settleGroups(ctx, group);
            while (!group.isEmpty()) {
                Frame frame = group.removeFirst();
                if (frame.bytes == null) throw new IllegalStateException("Flush proof recursively flushed");
                writeFrame(ctx, frame);
            }
            while (group.hasTasks())
                CultNetworkManager.runDeferredPacketTask("flush proof tasks", group.tasks.removeFirst());
            var player = connection.player();
            if (player != null) player.markTrackedTransactionPacketSent(proof);
        }
        if (connection.flushBundleOpen()) {
            writeFrame(ctx, delimiter(ctx));
            connection.flushBundleOpen(false);
            flushWavePackets = 0;
        }
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
                        finishFlushWave(ctx);
                        ctx.flush();
                        continue;
                    }
                    if (connection.pendingFlushProof() != null) {
                        if (structuralFrame(frame)) finishFlushWave(ctx);
                        else startFlushWave(ctx);
                    }
                    countFlushWaveMember(ctx);
                    writeFrame(ctx, frame);
                }
                if (output.flush) {
                    output.flush = false;
                    finishFlushWave(ctx);
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
        // wire would absorb every later packet, so place its proof and close it first.
        if (ctx.channel().isActive()) {
            try {
                finishFlushWave(ctx);
                ctx.flush();
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("flush bundle close on removal", failure);
            }
        }
        connection.takeFlushProof();
        connection.flushBundleOpen(false);
    }

    private record Pending(Object message, ChannelPromise promise) {
        void discard(Throwable failure) {
            if (message instanceof ByteBuf bytes) bytes.release();
            promise.tryFailure(failure);
        }
    }

    private record Frame(ByteBuf bytes, ChannelPromise promise, PacketType<?> type) {}

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

        Batch(boolean inside) {
            this.inside = inside;
        }

        void add(Frame frame) {
            output.addLast(frame);
            if (frame.type == ClientboundPackets.BUNDLE_DELIMITER) inside = !inside;
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
