package ac.cult.cultac.platform.velocity;

import io.netty.channel.Channel;
import java.lang.reflect.Method;

/** Velocity has no public backend channel API; resolve the narrow, checked boundary once. */
final class VelocityBackendChannels {
    private static volatile Method connectionMethod;
    private static volatile Method channelMethod;

    private VelocityBackendChannels() {}

    /** The backend channel of a {@code VelocityServerConnection}, or null before it connects. */
    static Channel channel(Object serverConnection) {
        try {
            Method connection = connectionMethod;
            if (connection == null)
                connectionMethod = connection = serverConnection.getClass().getMethod("getConnection");
            Object minecraft = connection.invoke(serverConnection);
            if (minecraft == null) return null;
            Method channel = channelMethod;
            if (channel == null) channelMethod = channel = minecraft.getClass().getMethod("getChannel");
            return (Channel) channel.invoke(minecraft);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            throw new IllegalStateException(
                    "Unsupported Velocity backend; expected VelocityServerConnection.getConnection().getChannel()",
                    failure);
        }
    }
}
