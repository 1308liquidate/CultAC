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
import ac.cult.cultac.utils.data.ReachInterpolationData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
    // Projectile#leftOwner across the possible client runs: whether some run has left the owner, and whether some
    // run has not.
    private boolean mayHaveLeftOwner;
    private boolean mayBeOnOwner = true;
    // Entity#tickCount, which ClientLevel#tickNonPassenger increments before each tick.
    private int tickCount;
    // Positions of runs that stopped short at an entity whose candidate positions disagree, keyed by the location
    // they branched from. The location itself follows the farthest stop.
    private final Map<ReachInterpolationData, List<Vec3>> shorterStops = new IdentityHashMap<>();

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
     * possible location moves from its own position.
     */
    @Override
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        tickCount++;
        super.onMovement(player, tickingReliably);
        // Bedrock clients see Geyser's per-tick absolute moves, which the Geyser bridge already applies.
        if (getRiding() != null || player.isBedrockMovement()) return;
        // 1.21.1 tests the hit on the old delta, moves, then applies inertia; this models the 1.21.2+ order only.
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) return;
        shorterStops.keySet().removeIf(location -> location != newPacketLocation && location != oldPacketLocation);
        Vec3 movement = deltaMovement;
        deltaMovement =
                movement.add(movement.normalize().scale(accelerationPower)).scale(inWater ? liquidInertia : inertia);
        ClientEntityQueries queries = new ClientEntityQueries(player);
        if (deltaMovement.lengthSqr() != 0.0D) {
            List<PickCandidates> targets = targets(queries);
            step(player, targets, newPacketLocation);
            step(player, targets, oldPacketLocation);
            if (clientPhysicalPosition != null)
                clientPhysicalPosition =
                        stops(player, targets, clientPhysicalPosition).getFirst();
        }
        inWater = clientPhysicalPosition != null && touchesWater(player, clientPhysicalPosition);
        if (clientPhysicalPosition != null) checkLeftOwner(queries, clientPhysicalPosition);
    }

    private void step(CultPlayer player, List<PickCandidates> targets, ReachInterpolationData location) {
        if (location == null) return;
        List<Vec3> branched = new ArrayList<>();
        for (Vec3 from : shorterStops.getOrDefault(location, List.of()))
            for (Vec3 stop : stops(player, targets, from)) addStop(branched, stop);
        location.offset(from -> {
            List<Vec3> stops = stops(player, targets, from);
            for (int i = 1; i < stops.size(); i++) addStop(branched, stops.get(i));
            return stops.getFirst().subtract(from);
        });
        if (branched.isEmpty()) shorterStops.remove(location);
        else shorterStops.put(location, branched);
    }

    private static void addStop(List<Vec3> stops, Vec3 stop) {
        for (Vec3 known : stops)
            if (Double.compare(known.x, stop.x) == 0
                    && Double.compare(known.y, stop.y) == 0
                    && Double.compare(known.z, stop.z) == 0) return;
        stops.add(stop);
    }

    /**
     * {@code Projectile#canHitEntity}'s owner rule over the client's targets: the owner's root vehicle and its
     * passengers are skipped until the projectile has left the owner. Where only some runs have left it, each of
     * those entities may also be skipped.
     */
    private List<PickCandidates> targets(ClientEntityQueries queries) {
        PacketEntity ownerRoot = ownerRoot();
        List<PickCandidates> all = queries.projectileTargets(this);
        if (ownerRoot == null || !mayBeOnOwner) return all;
        List<PickCandidates> targets = new ArrayList<>();
        for (PickCandidates target : all) {
            if (root(target.entity()) != ownerRoot) targets.add(target);
            else if (mayHaveLeftOwner) {
                List<Box> boxes = new ArrayList<>(target.boxes());
                boxes.add(null);
                targets.add(new PickCandidates(target.entity(), boxes));
            }
        }
        return targets;
    }

    /**
     * Every position {@code ProjectileUtil#getHitResult} can stop at from {@code from}, farthest first: the block clip
     * (or the full step), replaced by the nearest entity box, inflated by {@code computeMargin}, that the segment
     * enters. Each target is at one of its candidate boxes, independently of the others.
     */
    private List<Vec3> stops(CultPlayer player, List<PickCandidates> targets, Vec3 from) {
        Vec3 to = from.add(blockStep(player, from));
        Box area = searchArea(from);
        double margin = Math.max(0.0F, Math.min(0.3F, (tickCount - 2) / 20.0F));
        List<Vec3> hits = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        double[] farthest = new double[targets.size()];
        boolean blockStop = true;
        for (int i = 0; i < targets.size(); i++) {
            boolean alwaysHit = !targets.get(i).boxes().isEmpty();
            for (Box box : targets.get(i).boxes()) {
                Optional<Vec3> hit = box == null || !area.intersects(box)
                        ? Optional.empty()
                        : SimpleCollisionBox.clip(
                                box.minX() - margin,
                                box.minY() - margin,
                                box.minZ() - margin,
                                box.maxX() + margin,
                                box.maxY() + margin,
                                box.maxZ() + margin,
                                from,
                                to);
                if (hit.isEmpty()) {
                    alwaysHit = false;
                    farthest[i] = Double.POSITIVE_INFINITY;
                    continue;
                }
                double distance = from.distanceToSqr(hit.get());
                farthest[i] = Math.max(farthest[i], distance);
                hits.add(hit.get());
                distances.add(distance);
            }
            if (targets.get(i).boxes().isEmpty()) farthest[i] = Double.POSITIVE_INFINITY;
            blockStop &= !alwaysHit;
        }
        List<Vec3> stops = new ArrayList<>();
        if (blockStop) stops.add(to);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> distances.get(i)).reversed());
        for (int index : order) {
            double distance = distances.get(index);
            boolean nearest = true;
            for (double other : farthest) nearest &= other >= distance;
            if (nearest) addStop(stops, hits.get(index));
        }
        return stops;
    }

    /** The projectile's box stretched by its delta movement and grown by one. */
    private Box searchArea(Vec3 position) {
        SimpleCollisionBox box = toBoundingBox(
                player, GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, this));
        return new Box(
                box.minX + Math.min(0, deltaMovement.x) - 1.0D,
                box.minY + Math.min(0, deltaMovement.y) - 1.0D,
                box.minZ + Math.min(0, deltaMovement.z) - 1.0D,
                box.maxX + Math.max(0, deltaMovement.x) + 1.0D,
                box.maxY + Math.max(0, deltaMovement.y) + 1.0D,
                box.maxZ + Math.max(0, deltaMovement.z) + 1.0D);
    }

    /**
     * {@code Projectile#checkLeftOwner}: the projectile has left once no pickable entity of the owner's root vehicle
     * and its passengers touches its box stretched by its delta movement and grown by one. It never returns.
     */
    private void checkLeftOwner(ClientEntityQueries queries, Vec3 position) {
        if (!mayBeOnOwner) return;
        PacketEntity ownerRoot = ownerRoot();
        if (ownerRoot == null) {
            mayHaveLeftOwner = true;
            mayBeOnOwner = false;
            return;
        }
        Box area = searchArea(position);
        boolean touching = false;
        boolean mayTouch = false;
        for (PickCandidates entity : queries.pickableEntities()) {
            if (root(entity.entity()) != ownerRoot) continue;
            boolean every = !entity.boxes().isEmpty();
            for (Box box : entity.boxes()) {
                boolean touches = box != null && area.intersects(box);
                mayTouch |= touches;
                every &= touches;
            }
            touching |= every;
        }
        if (!mayTouch) mayBeOnOwner = false;
        if (!touching) mayHaveLeftOwner = true;
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

    @Override
    public SimpleCollisionBox getPossibleCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        return withShorterStops(super.getPossibleCollisionBoxes(out, scratch));
    }

    @Override
    public SimpleCollisionBox getPossibleMovementCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        return withShorterStops(super.getPossibleMovementCollisionBoxes(out, scratch));
    }

    @Override
    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidates() {
        List<SimpleCollisionBox> candidates = super.getPossibleMovementCollisionBoxCandidates();
        if (shorterStops.isEmpty()) return candidates;
        candidates = new ArrayList<>(candidates);
        for (List<Vec3> stops : shorterStops.values()) for (Vec3 stop : stops) candidates.add(positionBox(stop));
        return candidates;
    }

    @Override
    public SimpleCollisionBox getExactMovementCollisionBox() {
        return shorterStops.isEmpty() ? super.getExactMovementCollisionBox() : null;
    }

    private SimpleCollisionBox withShorterStops(SimpleCollisionBox out) {
        for (List<Vec3> stops : shorterStops.values())
            for (Vec3 stop : stops) ReachInterpolationData.combineCollisionBox(out, positionBox(stop), out);
        return out;
    }

    private SimpleCollisionBox positionBox(Vec3 position) {
        return GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, this);
    }

    /** {@code EntityFluidInteraction#update}: water whose top reaches the bottom of the box deflated by 0.001. */
    private boolean touchesWater(CultPlayer player, Vec3 position) {
        SimpleCollisionBox box = toBoundingBox(
                        player,
                        GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, this))
                .expand(-0.001D);
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
