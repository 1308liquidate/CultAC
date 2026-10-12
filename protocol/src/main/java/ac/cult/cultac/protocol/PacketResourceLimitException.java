package ac.cult.cultac.protocol;

/**
 * A packet's structure exceeds the decoder's resource budget. Unlike
 * {@link MalformedPacketException}, the frame may be well formed; decoding it would cost more
 * stack or heap than any vanilla packet needs.
 */
public final class PacketResourceLimitException extends RuntimeException {
    public PacketResourceLimitException(String message) {
        super(message);
    }

    public PacketResourceLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
