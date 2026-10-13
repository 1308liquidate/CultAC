package ac.cult.cultac.protocol.codec.connection;

import ac.cult.cultac.protocol.ProtocolContext;
import ac.cult.cultac.protocol.WritablePacketCodec;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundContainerClose;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundContainerClose;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;

/** Both directions write FriendlyByteBuf#writeContainerId, a VarInt, in every supported release. */
public final class ContainerCloseCodecs {
    private ContainerCloseCodecs() {}

    public static final class Clientbound implements WritablePacketCodec<ClientboundContainerClose> {
        @Override
        public ClientboundContainerClose read(ByteBuf input, ProtocolContext context) {
            return new ClientboundContainerClose(Wire.readVarInt(input));
        }

        @Override
        public void write(ByteBuf output, ProtocolContext context, ClientboundContainerClose packet) {
            Wire.writeVarInt(output, packet.containerId());
        }
    }

    public static final class Serverbound implements WritablePacketCodec<ServerboundContainerClose> {
        @Override
        public ServerboundContainerClose read(ByteBuf input, ProtocolContext context) {
            return new ServerboundContainerClose(Wire.readVarInt(input));
        }

        @Override
        public void write(ByteBuf output, ProtocolContext context, ServerboundContainerClose packet) {
            Wire.writeVarInt(output, packet.containerId());
        }
    }
}
