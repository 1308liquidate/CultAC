package ac.cult.reload.integrations;

import io.netty.channel.Channel;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.session.GeyserSession;

/** Resolves native transport identity once at admission, independently of engine generations. */
public final class GeyserConnections {
    private GeyserConnections() {}

    public static Object upstream(Object connection) {
        if (!(connection instanceof Channel channel)) return null;
        try {
            for (var connectionInfo : GeyserApi.api().onlineConnections()) {
                if (!(connectionInfo instanceof GeyserSession session) || session.getDownstream() == null) continue;
                Channel downstream = session.getDownstream().getSession().getChannel();
                if (downstream != null
                        && downstream.localAddress() != null
                        && channel.unsafe() != null
                        && downstream.localAddress().equals(channel.unsafe().remoteAddress())) return session;
            }
        } catch (NoClassDefFoundError | IllegalStateException unavailable) {
            // Geyser is optional and can still be initializing its dummy status transport.
        }
        return null;
    }
}
