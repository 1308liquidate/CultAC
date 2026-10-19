package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;

@CheckData(
        name = "BadPacketsJ",
        stableKey = "cult.badpackets.use_item_rotation_mismatch",
        description = "Rotation in use item packet did not match tick rotation")
public class BadPacketsJ extends Check implements DecodedPacketReceiveListener {
    private float yaw;
    private float pitch;
    private int rotations;

    public BadPacketsJ(CultPlayer player) {
        super(player);
    }

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
        }
    }

    @CultPacketHandler
    public void onUseItem(PacketReceiveEvent<ServerboundUseItem> event, CultPlayer player, ServerboundUseItem packet) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        final float yaw = packet.yaw();
        final float pitch = packet.pitch();

        if (this.rotations > 0 && (this.yaw != yaw || this.pitch != pitch)) {
            while (this.rotations-- > 0) flag();
            this.rotations = 0;
        }

        if (this.rotations != Integer.MAX_VALUE) this.rotations++;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    // isTickPacket: movement packets count unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        if (this.rotations > 0 && !player.packetStateData.lastPacketWasTeleport) {
            onTickPacket(player.xRot, player.yRot);
        }
    }

    // isTickPacket: tick end closes the client tick, also for a movement packet cancelled before this check saw it
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.cameraEntity.isSelf()) {
            this.rotations = 0;
            return;
        }

        if (this.rotations > 0) {
            // The client sends no tick movement, so no rotation, until ClientPacketListener#hasClientLoaded
            if (CheckManagerListener.localPlayerTicked(player)) {
                // LocalPlayer#sendPosition sends no rotation while the player's equals the last one it sent
                onTickPacket(player.packetStateData.lastSentTickYaw, player.packetStateData.lastSentTickPitch);
            } else {
                this.rotations = 0;
            }
        }
    }

    private void onTickPacket(float tickYaw, float tickPitch) {
        if (!Float.isNaN(tickYaw) && (this.yaw != tickYaw || this.pitch != tickPitch)) {
            while (this.rotations-- > 0) flag();
        }
        this.rotations = 0;
    }
}
