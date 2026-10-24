package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.VanillaMath;

public class PacketEntityShulker extends PacketEntity {
    public Direction facing = Direction.DOWN;

    /**
     * The last DATA_PEEK_ID the client was sent, as Shulker#getRawPeekAmount. Once no peek animation is pending,
     * Shulker#updatePeekAmount has moved currentPeekAmount onto exactly {@code rawPeek * 0.01F}.
     */
    public int rawPeek = 0;

    public PacketEntityShulker(CultPlayer player, int entityId, int type, double x, double y, double z) {
        super(player, entityId, type, x, y, z);
    }

    /**
     * Shulker#makeBoundingBox with currentPeekAmount settled on {@link #rawPeek}, written into {@code closed}, the
     * closed box at the shulker's position. Shulker#getProgressAabb expands the scale-sized box towards the opposite
     * of the attach face by Shulker#getPhysicalPeek times the scale.
     */
    public SimpleCollisionBox settledPeekBox(SimpleCollisionBox closed, ClientVersion version) {
        float physicalPeek = physicalPeek(rawPeek * 0.01F, version);
        if (physicalPeek == 0.0F) return closed;
        float scale = (float) (closed.maxY - closed.minY);
        Direction direction = facing.getOppositeFace();
        double amount = (double) physicalPeek * scale;
        return closed.expandToCoordinate(
                direction.getModX() * amount, direction.getModY() * amount, direction.getModZ() * amount);
    }

    /** Shulker#getPhysicalPeek. */
    static float physicalPeek(float peek, ClientVersion version) {
        return 0.5F - VanillaMath.sin(version, (0.5F + peek) * (float) Math.PI) * 0.5F;
    }

    /**
     * The client ticks Shulker#updatePeekAmount takes to move currentPeekAmount from {@code fromRaw * 0.01F} onto
     * {@code toRaw * 0.01F}, stepping 0.05F and clamping onto the target.
     */
    public static int peekAnimationTicks(int fromRaw, int toRaw) {
        float current = fromRaw * 0.01F;
        float target = toRaw * 0.01F;
        int ticks = 0;
        while (current != target) {
            current = current > target
                    ? Math.max(target, Math.min(1.0F, current - 0.05F))
                    : Math.max(0.0F, Math.min(target, current + 0.05F));
            ticks++;
        }
        return ticks;
    }
}
