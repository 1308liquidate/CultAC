package ac.cult.cultac.companion.bridge;

import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/** A main-thread task queued in the server's packet queue, so it runs in order with its connection's game packets. */
record OrderedTask(Runnable task) implements Packet<ServerCommonPacketListener> {
    // Servers before 1.21.9 have no PacketProcessor; they queue each game packet as a task on the server itself.
    private static final @Nullable MethodHandle PACKET_PROCESSOR;
    private static final @Nullable MethodHandle SCHEDULE;

    static {
        MethodHandle processor = null;
        MethodHandle schedule = null;
        try {
            Method getter = MinecraftServer.class.getMethod("packetProcessor");
            Method scheduleIfPossible =
                    getter.getReturnType().getMethod("scheduleIfPossible", PacketListener.class, Packet.class);
            processor = MethodHandles.publicLookup()
                    .unreflect(getter)
                    .asType(methodType(Object.class, MinecraftServer.class));
            schedule = MethodHandles.publicLookup()
                    .unreflect(scheduleIfPossible)
                    .asType(methodType(void.class, Object.class, PacketListener.class, Packet.class));
        } catch (NoSuchMethodException serverTasks) {
        } catch (IllegalAccessException failure) {
            throw new ExceptionInInitializerError(failure);
        }
        PACKET_PROCESSOR = processor;
        SCHEDULE = schedule;
    }

    /** Call on the connection's event loop; false when the listener takes no common packets or the queue is closed. */
    static boolean schedule(MinecraftServer server, PacketListener listener, Runnable task) {
        if (PACKET_PROCESSOR != null) return schedule(invoke(PACKET_PROCESSOR, server), listener, task);
        if (!(listener instanceof ServerCommonPacketListener)) return false;
        try {
            server.executeIfPossible(task);
            return true;
        } catch (RejectedExecutionException closed) {
            return false;
        }
    }

    /** {@link #schedule(MinecraftServer, PacketListener, Runnable)} on a server's {@code PacketProcessor}. */
    static boolean schedule(Object processor, PacketListener listener, Runnable task) {
        if (!(listener instanceof ServerCommonPacketListener)) return false;
        try {
            SCHEDULE.invokeExact(processor, listener, (Packet<?>) new OrderedTask(task));
            return true;
        } catch (RejectedExecutionException closed) {
            return false;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object invoke(MethodHandle getter, MinecraftServer server) {
        try {
            return (Object) getter.invokeExact(server);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    // PacketProcessor queues only typed game packets; this type is never encoded.
    @Override
    public PacketType<ServerboundCustomPayloadPacket> type() {
        return CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD;
    }

    @Override
    public void handle(ServerCommonPacketListener listener) {
        task.run();
    }
}
