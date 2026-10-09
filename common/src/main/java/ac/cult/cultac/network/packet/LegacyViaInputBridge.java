package ac.cult.cultac.network.packet;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsE;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsG;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsR;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsX;
import ac.cult.cultac.checks.impl.movement.timer.VehicleTimer;
import ac.cult.cultac.checks.impl.vehicle.VehicleB;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.wire.Wire;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ViaChannelHandler;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPipeline;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.State;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.util.List;

/**
 * Preserves the identity of legacy input packets before ViaBackwards maps them
 * onto the 1.21.2+ input bitset. Sneaking must follow these original packets:
 * Via's periodically synthesized on-foot input can arrive after movement.
 * Via runs base protocols before translation, so this uses its protocol API
 * without adding a third Netty handler. Removal clears the weak user binding;
 * Via owns the passive protocol until its connection closes.
 */
public final class LegacyViaInputBridge extends com.viaversion.viaversion.protocol.AbstractSimpleProtocol {

    private static final String PLAYER_COMMAND = "PLAYER_COMMAND";
    private static final String PLAYER_INPUT = "PLAYER_INPUT";

    private java.lang.ref.WeakReference<User> user;

    private LegacyViaInputBridge(User user) {
        this.user = new java.lang.ref.WeakReference<>(user);
    }

    public static void install(User user) {
        if (!ViaVersionUtil.isAvailable()) {
            return;
        }

        Channel channel = (Channel) user.getChannel();
        if (channel == null) {
            return;
        }

        Runnable install = () -> installOnEventLoop(user, channel);
        if (channel.eventLoop().inEventLoop()) {
            install.run();
        } else {
            channel.eventLoop().execute(install);
        }
    }

    public static void remove(User user) {
        if (!ViaVersionUtil.isAvailable()) return;
        Channel channel = (Channel) user.getChannel();
        Runnable remove = () -> {
            var pipeline = viaPipeline(channel);
            if (pipeline != null) {
                var observer = pipeline.getProtocol(LegacyViaInputBridge.class);
                if (observer != null && observer.user.get() == user) observer.user.clear();
            }
        };
        if (channel.eventLoop().inEventLoop()) remove.run();
        else channel.eventLoop().execute(remove);
    }

    private static ProtocolPipeline viaPipeline(Channel channel) {
        if (channel == null) return null;
        var decoder = channel.pipeline().get(Via.getManager().getInjector().getDecoderName());
        return decoder instanceof ViaChannelHandler via
                ? via.connection().getProtocolInfo().getPipeline()
                : null;
    }

    private static void installOnEventLoop(User user, Channel channel) {
        var pipeline = viaPipeline(channel);
        if (pipeline == null || !pipeline.hasNonBaseProtocols()) return;
        var observer = pipeline.getProtocol(LegacyViaInputBridge.class);
        if (observer != null) observer.user = new java.lang.ref.WeakReference<>(user);
        else pipeline.add(new LegacyViaInputBridge(user));
    }

    @Override
    public boolean isBaseProtocol() {
        return true;
    }

    @Override
    public void transform(
            com.viaversion.viaversion.api.protocol.packet.Direction direction,
            State state,
            com.viaversion.viaversion.api.protocol.packet.PacketWrapper wrapper)
            throws com.viaversion.viaversion.exception.CancelException {
        if (direction != com.viaversion.viaversion.api.protocol.packet.Direction.SERVERBOUND || state != State.PLAY)
            return;
        User current = user.get();
        if (current == null) return;
        // Via's public add(baseProtocol) inserts this observer before translation.
        // Peeking its input leaves wrapper values and all other protocols untouched.
        if (!(wrapper instanceof com.viaversion.viaversion.protocol.packet.PacketWrapperImpl frame)) return;
        boolean rejected;
        try {
            rejected = inspect(current, wrapper.user(), wrapper.getId(), frame.getInputBuffer());
        } catch (RuntimeException unknownFrame) {
            return;
        }
        if (rejected) {
            wrapper.cancel();
            throw com.viaversion.viaversion.exception.CancelException.generate();
        }
    }

    private boolean inspect(User user, UserConnection viaConnection, int packetId, ByteBuf original) {
        if (original == null) return false;
        ProtocolInfo protocolInfo = viaConnection.getProtocolInfo();
        if (protocolInfo == null || protocolInfo.getClientState() != State.PLAY) {
            return false;
        }

        CultPlayer player = user.getCultPlayer();
        if (player == null
                || player.isBedrockMovement()
                || protocolInfo.protocolVersion() == null
                || protocolInfo.protocolVersion().getVersion() >= ClientVersion.V_1_21_2.getProtocolVersion()) {
            return false;
        }

        ByteBuf packet = original.duplicate();
        String packetName = originalPacketName(protocolInfo.getPipeline(), packetId);
        if (PLAYER_INPUT.equals(packetName)) {
            // Legacy steer input is exactly two floats followed by one flags byte.
            if (packet.readableBytes() != Float.BYTES * 2 + Byte.BYTES) {
                return false;
            }
            packet.skipBytes(Float.BYTES * 2);
            byte flags = packet.readByte();
            player.checkManager.getCheck(ac.cult.cultac.checks.impl.multiactions.MultiActionsH.class).vehicleJumping =
                    (flags & 1) != 0;
            return handleLegacySteer(player, (flags & 2) != 0);
        }

        if ("CLIENT_COMMAND".equals(packetName) && player.openWindow.clientSendsOpenInventoryPacket) {
            int action = Wire.readVarInt(packet);
            if (!packet.isReadable() && action == 2) {
                if (player.checkManager
                        .getCheck(ac.cult.cultac.checks.impl.multiactions.MultiActionsN.class)
                        .onLegacyOpenInventory()) {
                    player.onPacketCancel();
                    return true;
                }
                player.openWindow.onClientOpenOrCloseWindow(true);
            }
            return false;
        }

        if (!PLAYER_COMMAND.equals(packetName)) {
            return false;
        }

        Wire.readVarInt(packet); // entity id
        int action = Wire.readVarInt(packet);
        Wire.readVarInt(packet); // action data
        if (packet.isReadable() || action < 0 || action > 1) {
            return false;
        }
        return handleLegacySneak(player, action == 0);
    }

    @SuppressWarnings("rawtypes")
    private static String originalPacketName(ProtocolPipeline pipeline, int packetId) {
        if (pipeline == null) {
            return null;
        }

        List<Protocol> protocols = pipeline.pipes();
        int firstTranslation = pipeline.baseProtocolCount();
        if (firstTranslation >= protocols.size()) {
            return null;
        }

        PacketType type = (PacketType)
                protocols.get(firstTranslation).getPacketTypesProvider().unmappedServerboundType(State.PLAY, packetId);
        return type == null ? null : type.getName();
    }

    private static boolean handleLegacySteer(CultPlayer player, boolean sneaking) {
        boolean rejected = player.checkManager.getCheck(VehicleTimer.class).handleLegacySteerVehicle();
        player.checkManager.getCheck(BadPacketsE.class).handleLegacySteerVehicle();
        player.checkManager.getCheck(BadPacketsR.class).handleLegacySteerVehicle();
        rejected = player.checkManager.getCheck(VehicleB.class).handleLegacySteerVehicle() || rejected;
        if (!rejected) {
            player.isSneaking = sneaking;
        }
        return rejected;
    }

    private static boolean handleLegacySneak(CultPlayer player, boolean sneaking) {
        player.checkManager
                .getCheck(ac.cult.cultac.checks.impl.multiactions.MultiActionsM.class)
                .onChange(sneaking);
        boolean rejected = player.checkManager.getCheck(BadPacketsG.class).handleLegacySneak(sneaking);
        player.checkManager.getCheck(BadPacketsX.class).handleLegacySneakAction();
        player.packetOrderProcessor.handleLegacySneakAction();
        if (!rejected) {
            player.isSneaking = sneaking;
        }
        return rejected;
    }
}
