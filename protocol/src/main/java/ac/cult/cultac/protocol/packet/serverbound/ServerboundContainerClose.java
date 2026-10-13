package ac.cult.cultac.protocol.packet.serverbound;

public record ServerboundContainerClose(int containerId) implements ServerboundPacket {}
