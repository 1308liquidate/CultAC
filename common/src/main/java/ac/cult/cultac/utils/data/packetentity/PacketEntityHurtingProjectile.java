package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.interaction.BlockHit;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries.PickCandidates;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An {@code AbstractHurtingProjectile} (fireballs, wind charges), which the client moves along its delta movement every
 * tick between server positions.
 */
public final class PacketEntityHurtingProjectile extends PacketEntity {
    private final CultPlayer player;
    // AbstractHurtingProjectile#accelerationPower, which only projectile_power changes after spawn.
    public double accelerationPower;
    private final float inertia;
    private final float liquidInertia;
    // Entity#isInWater: wasTouchingWater, which baseTick refreshes after each move.
    private boolean inWater;
    // Projectile#recreateFromPacket sets the owner only when the add_entity data names an entity the client has.
    private final boolean hasOwner;
    private final int ownerId;
    // Projectile#leftOwner across the projectile's possible positions: whether some has left the owner, and whether
    // some may still be on it.
    private boolean mayHaveLeftOwner;
    private boolean mayBeOnOwner = true;
    // Entity#tickCount, which ClientLevel#tickNonPassenger increments before each tick.
    private int tickCount;

    public PacketEntityHurtingProjectile(CultPlayer player, int id, int type, Vec3 position, int data) {
        super(player, id, type, position.x, position.y, position.z);
        this.player = player;
        boolean windCharge = isWindCharge(type);
        // AbstractWindCharge's constructors, getInertia and getLiquidInertia; AbstractHurtingProjectile's otherwise.
        accelerationPower = windCharge ? 0.0D : 0.1D;
        inertia = windCharge ? 1.0F : 0.95F;
        liquidInertia = windCharge ? inertia : 0.8F;
        hasOwner = player.compensatedEntities.getEntity(data) != null;
        ownerId = data;
    }

    public static boolean isHurtingProjectile(int type) {
        return isWindCharge(type)
                || type == EntityTypeIds.FIREBALL
                || type == EntityTypeIds.SMALL_FIREBALL
                || type == EntityTypeIds.DRAGON_FIREBALL;
    }

    private static boolean isWindCharge(int type) {
        return type == EntityTypeIds.WIND_CHARGE || type == EntityTypeIds.BREEZE_WIND_CHARGE;
    }

    /**
     * {@code AbstractHurtingProjectile#tick} on 1.21.2+ clients: {@code applyInertia}, then {@code setPos} to the hit
     * of {@code ProjectileUtil#getHitResultOnMoveVector} or one delta movement on, then {@code checkLeftOwner}. Each
     * possible location moves from its own position, and stops only at an entity it certainly hits.
     */
    @Override
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        tickCount++;
        super.onMovement(player, tickingReliably);
        if (!modelled()) return;
        Vec3 movement = deltaMovement;
        deltaMovement =
                movement.add(movement.normalize().scale(accelerationPower)).scale(inWater ? liquidInertia : inertia);
        ClientEntityQueries queries = new ClientEntityQueries(player);
        if (deltaMovement.lengthSqr() != 0.0D) {
            List<Box> targets = certainTargets(queries);
            newPacketLocation.offset(from -> stop(player, targets, from).subtract(from));
            if (oldPacketLocation != null)
                oldPacketLocation.offset(from -> stop(player, targets, from).subtract(from));
            if (clientPhysicalPosition != null) clientPhysicalPosition = stop(player, targets, clientPhysicalPosition);
        }
        inWater = clientPhysicalPosition != null && touchesWater(player, clientPhysicalPosition);
        checkLeftOwner(queries);
    }

    // Bedrock clients see Geyser's per-tick absolute moves, which the Geyser bridge already applies. 1.21.1 tests the
    // hit on the old delta, moves, then applies inertia; this models the 1.21.2+ order only.
    private boolean modelled() {
        return getRiding() == null
                && !player.isBedrockMovement()
                && !player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2);
    }

    /**
     * The boxes of the entities {@code Projectile#canHitEntity} certainly accepts at one known position: a target
     * whose candidate positions disagree, or whose owner rule depends on a {@code leftOwner} the engine does not know,
     * is left out. Before 26.3 the client deflects the projectile off a {@code deflects_projectiles} target, so those
     * are left out too.
     */
    private List<Box> certainTargets(ClientEntityQueries queries) {
        PacketEntity ownerRoot = ownerRoot();
        boolean deflectsOnClient = player.getClientVersion().isOlderThan(ClientVersion.V_26_3);
        List<Box> targets = new ArrayList<>();
        for (PickCandidates target : queries.projectileTargets(this)) {
            if (target.boxes().size() != 1 || target.boxes().getFirst() == null) continue;
            if (ownerRoot != null && mayBeOnOwner && root(target.entity()) == ownerRoot) continue;
            if (deflectsOnClient && queries.deflects(target.entity(), this)) continue;
            targets.add(target.boxes().getFirst());
        }
        return targets;
    }

    /**
     * Where {@code ProjectileUtil#getHitResult} stops from {@code from}: the block clip (or the full step), replaced
     * by the nearest target box, inflated by {@code computeMargin}, that the segment enters.
     */
    private Vec3 stop(CultPlayer player, List<Box> targets, Vec3 from) {
        Vec3 to = from.add(blockStep(player, from));
        Box area = searchArea(toBoundingBox(player, positionBox(from)));
        double margin = Math.max(0.0F, Math.min(0.3F, (tickCount - 2) / 20.0F));
        Vec3 nearest = to;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Box box : targets) {
            if (!area.intersects(box)) continue;
            Optional<Vec3> hit = SimpleCollisionBox.clip(
                    box.minX() - margin,
                    box.minY() - margin,
                    box.minZ() - margin,
                    box.maxX() + margin,
                    box.maxY() + margin,
                    box.maxZ() + margin,
                    from,
                    to);
            if (hit.isEmpty()) continue;
            double distance = from.distanceToSqr(hit.get());
            if (distance < nearestDistance) {
                nearest = hit.get();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** {@code box} stretched by the delta movement and grown by one. */
    private Box searchArea(SimpleCollisionBox box) {
        return new Box(
                box.minX + Math.min(0, deltaMovement.x) - 1.0D,
                box.minY + Math.min(0, deltaMovement.y) - 1.0D,
                box.minZ + Math.min(0, deltaMovement.z) - 1.0D,
                box.maxX + Math.max(0, deltaMovement.x) + 1.0D,
                box.maxY + Math.max(0, deltaMovement.y) + 1.0D,
                box.maxZ + Math.max(0, deltaMovement.z) + 1.0D);
    }

    /**
     * {@code Projectile#checkLeftOwner} at each possible position: the projectile has left once no pickable entity of
     * the owner's root vehicle and its passengers touches its box stretched by its delta movement and grown by one. It
     * never returns. An owner-tree rider outside the local player's riding tree has no simulated position, so it may
     * touch anywhere.
     */
    private void checkLeftOwner(ClientEntityQueries queries) {
        if (!mayBeOnOwner) return;
        PacketEntity ownerRoot = ownerRoot();
        if (ownerRoot == null) {
            mayHaveLeftOwner = true;
            mayBeOnOwner = false;
            return;
        }
        List<Box> areas = new ArrayList<>();
        for (SimpleCollisionBox box : getPossibleMovementCollisionBoxCandidates())
            areas.add(searchArea(toBoundingBox(player, box)));
        if (clientPhysicalPosition != null)
            areas.add(searchArea(toBoundingBox(player, positionBox(clientPhysicalPosition))));
        List<PickCandidates> tree = new ArrayList<>();
        for (PickCandidates entity : queries.pickableEntities())
            if (root(entity.entity()) == ownerRoot) tree.add(entity);
        boolean mayTouch = false;
        for (PacketEntity entity : player.compensatedEntities.entityMap.values())
            mayTouch |= root(entity) == ownerRoot && player.compensatedEntities.isUntrackedRider(entity);
        boolean touchingEverywhere = true;
        for (Box area : areas) {
            boolean touching = false;
            for (PickCandidates entity : tree) {
                boolean every = !entity.boxes().isEmpty();
                for (Box box : entity.boxes()) {
                    boolean touches = box != null && area.intersects(box);
                    mayTouch |= touches;
                    every &= touches;
                }
                touching |= every;
            }
            touchingEverywhere &= touching;
        }
        if (!mayTouch) mayBeOnOwner = false;
        if (!touchingEverywhere) mayHaveLeftOwner = true;
    }

    private PacketEntity ownerRoot() {
        if (!hasOwner) return null;
        PacketEntity owner = player.compensatedEntities.getEntity(ownerId);
        return owner == null ? null : root(owner);
    }

    private static PacketEntity root(PacketEntity entity) {
        while (entity.getRiding() != null) entity = entity.getRiding();
        return entity;
    }

    /**
     * The client's projectile moves only when it ticks or a packet places it, so it is at one of its candidate
     * positions, never between two of them.
     */
    @Override
    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxGroups() {
        if (getRiding() != null) return super.getPossibleMovementCollisionBoxGroups();
        return getPossibleMovementCollisionBoxCandidates();
    }

    private SimpleCollisionBox positionBox(Vec3 position) {
        return GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, this);
    }

    /** {@code EntityFluidInteraction#update}: water whose top reaches the bottom of the box deflated by 0.001. */
    private boolean touchesWater(CultPlayer player, Vec3 position) {
        SimpleCollisionBox box = toBoundingBox(player, positionBox(position)).expand(-0.001D);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX); x < Math.ceil(box.maxX); x++) {
            for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
                for (int z = (int) Math.floor(box.minZ); z < Math.ceil(box.maxZ); z++) {
                    pos.set(x, y, z);
                    ClientFluidQueries.Sample fluid =
                            ClientFluidQueries.sample(player, player.compensatedWorld.getBlockStateIdAt(pos), pos);
                    if (fluid.water() && y + fluid.height() >= box.minY) return true;
                }
            }
        }
        return false;
    }

    private Vec3 blockStep(CultPlayer player, Vec3 from) {
        // ClipContext.Block.COLLIDER with CollisionContext.of(this projectile).
        EntityCollisionContext context = EntityCollisionContext.position(from.y);
        BlockHit hit = player.compensatedWorld
                .geometry()
                .clipBlocks(
                        from,
                        from.add(deltaMovement),
                        (state, pos) -> NativeBlockCollisionHelper.getCollisionShape(
                                player, state, pos, from.y, null, context));
        if (hit == null) return deltaMovement;
        return new Vec3(hit.location().x(), hit.location().y(), hit.location().z()).subtract(from);
    }
}
