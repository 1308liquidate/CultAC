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
import ac.cult.cultac.utils.math.CultMath;
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
    // Projectile#leftOwner across the projectile's possible positions: whether some may still be on the owner.
    private boolean mayBeOnOwner = true;
    // Projectile#leftOwner is certainly still false.
    private boolean certainlyOnOwner = true;
    // The client may be off every candidate: the model passed an entity the projectile may have hit, or the client
    // moved it in a way the model can't follow. It makes no entity stops until a server position re-anchors it.
    private boolean diverged;
    // A server position applied since the last client tick, which re-anchors once no older candidate remains.
    private boolean anchoring;
    // The engine's codec base matches the client's. The engine moves its base to each teleport, which the client's
    // doesn't follow.
    private boolean codecBaseKnown = true;
    // The delta movement follows the model: no deflection it couldn't aim.
    private boolean motionKnown = true;
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
     * possible location moves from its own position, and stops only at an entity it certainly hits. A step that may
     * enter an entity before every one it certainly hits diverges the projectile.
     */
    @Override
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        tickCount++;
        anchoring = false;
        super.onMovement(player, tickingReliably);
        if (!modelled()) return;
        Vec3 movement = deltaMovement;
        deltaMovement =
                movement.add(movement.normalize().scale(accelerationPower)).scale(inWater ? liquidInertia : inertia);
        ClientEntityQueries queries = new ClientEntityQueries(player);
        if (deltaMovement.lengthSqr() != 0.0D) {
            Targets candidates = targets(queries);
            if (!diverged && mayPassAHit(player, candidates)) diverge();
            List<Box> targets = diverged ? List.of() : candidates.certain();
            newPacketLocation.offset(from -> stop(player, targets, from).subtract(from));
            if (oldPacketLocation != null)
                oldPacketLocation.offset(from -> stop(player, targets, from).subtract(from));
            if (clientPhysicalPosition != null) clientPhysicalPosition = stop(player, targets, clientPhysicalPosition);
        }
        inWater = clientPhysicalPosition != null && touchesWater(player, clientPhysicalPosition);
        if (!diverged) checkLeftOwner(queries);
    }

    private void diverge() {
        diverged = true;
        anchoring = false;
        certainlyOnOwner = false;
    }

    /**
     * {@code ClientPacketListener#handleEntityPositionSync}, which sets the position and the codec base. A hurting
     * projectile has {@code InterpolationHandler.NO_OP}, so each server position the client applies is set directly.
     */
    public void onPositionSync() {
        codecBaseKnown = true;
        placeExactly();
    }

    /** {@code ClientPacketListener#handleMoveEntity} with a position, decoded against the codec base. */
    public void onMoveEntity() {
        if (codecBaseKnown) placeExactly();
        else diverge();
    }

    /**
     * {@code ClientPacketListener#handleTeleportEntity}, which leaves the codec base. A position relative to the
     * client's own carries any difference along.
     */
    public void onTeleport(boolean absolute) {
        codecBaseKnown = false;
        if (absolute) placeExactly();
        else diverge();
    }

    private void placeExactly() {
        anchoring = diverged;
        anchor();
    }

    @Override
    public void onSecondTransaction() {
        super.onSecondTransaction();
        anchor();
    }

    // Entity#isInWater is the client's last baseTick state, which setPos leaves alone, so a position re-anchors only
    // a projectile whose inertia doesn't depend on it.
    private void anchor() {
        if (!anchoring || oldPacketLocation != null) return;
        anchoring = false;
        if (motionKnown && inertia == liquidInertia) diverged = false;
    }

    // Bedrock clients see Geyser's per-tick absolute moves, which the Geyser bridge already applies. 1.21.1 tests the
    // hit on the old delta, moves, then applies inertia; this models the 1.21.2+ order only.
    private boolean modelled() {
        return getRiding() == null
                && !player.isBedrockMovement()
                && !player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2);
    }

    /**
     * {@code Player#attack} before 26.3: {@code deflectProjectile} aims a {@code #redirectable_projectile} along the
     * attacker's look ({@code ProjectileDeflection#AIM_DEFLECT}) on the client too. {@code WindCharge#deflect} refuses
     * until the projectile has ticked five times. Mounted, the vehicle can turn the player between the attack and the
     * rotation the client sends, so the look is not known and the projectile diverges. The deflection lands in the
     * attack's client tick, whether or not the projectile ticks in it.
     */
    public void onLocalAttack(ClientEntityQueries queries) {
        if (!modelled()
                || !player.getClientVersion().isOlderThan(ClientVersion.V_26_3)
                || !queries.redirectable(this)
                || type == EntityTypeIds.WIND_CHARGE && tickCount < 5) return;
        if (player.compensatedEntities.getSelf().inVehicle()) {
            motionKnown = false;
            diverge();
            return;
        }
        player.compensatedEntities.deflectAlongLookAtTickEnd(this);
    }

    /**
     * {@code ProjectileDeflection#AIM_DEFLECT}: the delta movement becomes the look the local player attacked with,
     * which is the rotation its client tick ends with.
     */
    public void aimAlongLocalLook() {
        deltaMovement = lookAngle(player.xRot, player.yRot);
    }

    /** {@code Entity#calculateViewVector} of the local player's rotation, as {@code Entity#getLookAngle}. */
    private static Vec3 lookAngle(float yaw, float pitch) {
        float pitchRadians = pitch * ((float) Math.PI / 180.0F);
        float yawRadians = -yaw * ((float) Math.PI / 180.0F);
        float cosPitch = CultMath.cos(pitchRadians);
        return new Vec3(
                CultMath.sin(yawRadians) * cosPitch, -CultMath.sin(pitchRadians), CultMath.cos(yawRadians) * cosPitch);
    }

    /**
     * The entities {@code Projectile#canHitEntity} may accept. {@code certain} holds the boxes of those it certainly
     * accepts at one known position; {@code possible} the boxes of those whose candidate positions disagree, that the
     * client deflects the projectile off before 26.3, or that the owner rule may still skip; {@code anywhere} is set
     * when one has no position the engine knows: a rider it doesn't place, one that may be off its candidates
     * ({@link PacketEntity#hasLossyPosition}), or an ender dragon part.
     */
    private record Targets(List<Box> certain, List<Box> possible, boolean anywhere) {}

    private Targets targets(ClientEntityQueries queries) {
        PacketEntity ownerRoot = ownerRoot();
        boolean deflectsOnClient = player.getClientVersion().isOlderThan(ClientVersion.V_26_3);
        List<Box> certain = new ArrayList<>();
        List<Box> possible = new ArrayList<>();
        boolean anywhere = queries.mayHaveDragonParts();
        for (PickCandidates target : queries.projectileTargets(this)) {
            boolean owned = ownerRoot != null && mayBeOnOwner && root(target.entity()) == ownerRoot;
            if (owned && certainlyOnOwner) continue;
            if (target.boxes().isEmpty() || target.entity().hasLossyPosition()) {
                anywhere = true;
                continue;
            }
            if (!owned
                    && target.boxes().size() == 1
                    && target.boxes().getFirst() != null
                    && !(deflectsOnClient && queries.deflects(target.entity(), this))) {
                certain.add(target.boxes().getFirst());
                continue;
            }
            for (Box box : target.boxes()) if (box != null) possible.add(box);
        }
        return new Targets(certain, possible, anywhere);
    }

    /** Whether the step from some possible position may enter a possible target no farther than every certain one. */
    private boolean mayPassAHit(CultPlayer player, Targets targets) {
        if (targets.anywhere()) return true;
        if (targets.possible().isEmpty()) return false;
        List<Vec3> origins = new ArrayList<>();
        newPacketLocation.forEachStepOrigin(origins::add);
        if (oldPacketLocation != null) oldPacketLocation.forEachStepOrigin(origins::add);
        if (clientPhysicalPosition != null) origins.add(clientPhysicalPosition);
        for (Vec3 from : origins) {
            Vec3 to = from.add(blockStep(player, from));
            Box area = searchArea(toBoundingBox(player, positionBox(from)));
            Vec3 possible = nearestHit(targets.possible(), area, from, to);
            if (possible == null) continue;
            Vec3 certain = nearestHit(targets.certain(), area, from, to);
            if (certain == null || from.distanceToSqr(possible) <= from.distanceToSqr(certain)) return true;
        }
        return false;
    }

    /**
     * Where {@code ProjectileUtil#getHitResult} stops from {@code from}: the block clip (or the full step), replaced
     * by the nearest target box, inflated by {@code computeMargin}, that the segment enters.
     */
    private Vec3 stop(CultPlayer player, List<Box> targets, Vec3 from) {
        Vec3 to = from.add(blockStep(player, from));
        Vec3 hit = nearestHit(targets, searchArea(toBoundingBox(player, positionBox(from))), from, to);
        return hit == null ? to : hit;
    }

    /** {@code ProjectileUtil#getEntityHitResult}: the nearest entry into a box in {@code area}, or null. */
    private Vec3 nearestHit(List<Box> boxes, Box area, Vec3 from, Vec3 to) {
        double margin = Math.max(0.0F, Math.min(0.3F, (tickCount - 2) / 20.0F));
        Vec3 nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Box box : boxes) {
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
     * never returns. An owner-tree rider outside the local player's riding tree has no simulated position, and one that
     * may be off its candidates ({@link PacketEntity#hasLossyPosition}) proves nothing, so either may touch anywhere.
     */
    private void checkLeftOwner(ClientEntityQueries queries) {
        if (!mayBeOnOwner) return;
        PacketEntity ownerRoot = ownerRoot();
        if (ownerRoot == null) {
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
        for (PickCandidates entity : tree) mayTouch |= entity.entity().hasLossyPosition();
        boolean mustTouch = true;
        for (Box area : areas) {
            boolean touched = false;
            for (PickCandidates entity : tree) {
                if (entity.entity().hasLossyPosition()) continue;
                boolean everywhere = !entity.boxes().isEmpty();
                for (Box box : entity.boxes()) {
                    boolean touches = box != null && area.intersects(box);
                    mayTouch |= touches;
                    everywhere &= touches;
                }
                touched |= everywhere;
            }
            mustTouch &= touched;
        }
        if (!mayTouch) mayBeOnOwner = false;
        if (!mustTouch) certainlyOnOwner = false;
    }

    private PacketEntity ownerRoot() {
        if (!hasOwner) return null;
        PacketEntity owner = player.compensatedEntities.getEntity(ownerId);
        return owner == null ? null : root(owner);
    }

    private PacketEntity root(PacketEntity entity) {
        return player.compensatedEntities.rootOf(entity);
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
