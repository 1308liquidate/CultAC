package ac.cult.cultac.network.logging;

import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import io.netty.buffer.ByteBuf;

/** Connection-local, synchronous observers borrow frames and never control delivery. */
public interface PacketLogObserver {
    PacketLogObserver NONE = new PacketLogObserver() {};

    default boolean active() {
        return false;
    }

    default void packet(PacketDirection direction, ConnectionPhase phase, ByteBuf frame) {}

    default void identity(User.Profile profile) {}

    default void stop(String reason) {}
}
