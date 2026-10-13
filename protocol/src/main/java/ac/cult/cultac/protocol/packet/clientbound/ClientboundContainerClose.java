package ac.cult.cultac.protocol.packet.clientbound;

public record ClientboundContainerClose(int containerId) implements ClientboundPacket {}
