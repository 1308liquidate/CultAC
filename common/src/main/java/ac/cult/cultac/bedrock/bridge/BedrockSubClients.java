package ac.cult.cultac.bedrock.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;

/**
 * BedrockPacketWrapper's sub-client ids. Cloudburst Protocol 29bd1453 (3.0.0.Beta14) narrowed their
 * getters from int to byte, so a direct call links against only one side of that change.
 */
final class BedrockSubClients {
    private static final MethodHandle SENDER = getter("getSenderSubClientId");
    private static final MethodHandle TARGET = getter("getTargetSubClientId");

    private BedrockSubClients() {}

    static int sender(BedrockPacketWrapper wrapper) {
        return invoke(SENDER, wrapper);
    }

    static int target(BedrockPacketWrapper wrapper) {
        return invoke(TARGET, wrapper);
    }

    private static MethodHandle getter(String name) {
        var lookup = MethodHandles.publicLookup();
        var widened = MethodType.methodType(int.class, BedrockPacketWrapper.class);
        for (Class<?> type : new Class<?>[] {int.class, byte.class}) {
            try {
                return lookup.findVirtual(BedrockPacketWrapper.class, name, MethodType.methodType(type))
                        .asType(widened);
            } catch (NoSuchMethodException ignored) {
            } catch (IllegalAccessException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        throw new ExceptionInInitializerError("BedrockPacketWrapper has no int or byte " + name);
    }

    private static int invoke(MethodHandle getter, BedrockPacketWrapper wrapper) {
        try {
            return (int) getter.invokeExact(wrapper);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
