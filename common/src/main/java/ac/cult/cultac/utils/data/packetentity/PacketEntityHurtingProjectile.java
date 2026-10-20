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
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.NativeBlockCollisionHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * An {@code AbstractHurtingProjectile} (fireballs, wind charges), which the client moves along its delta movement every
 * tick between server positions. Each packet position the client may be at holds every state the client's projectile
 * can have reached from it; a server position replaces them all.
 */
public final class PacketEntityHurtingProjectile extends PacketEntity {
    private final CultPlayer player;
    // AbstractHurtingProjectile#accelerationPower, which only projectile_power changes after spawn.
    public double accelerationPower;
    private final float inertia;
    private final float liquidInertia;
    // Projectile#recreateFromPacket sets the owner only when the add_entity data names an entity the client has.
    private final boolean hasOwner;
    private final int ownerId;
    // Entity#tickCount, which ClientLevel#tickNonPassenger increments before each tick.
    private int tickCount;
    private @Nullable ReachInterpolationData newSnapshot;
    private @Nullable ReachInterpolationData oldSnapshot;
    private List<Branch> newBranches = List.of();
    private List<Branch> oldBranches = List.of();
    // The delta movement last written to deltaMovement here; any other value there is a packet's.
    private @Nullable Vec3 publishedDelta;

    /**
     * One state the client's projectile can be in. A null position is a state the engine does not bound: {@code region}
     * holds every position it can be at and {@code speed} bounds the length of its delta movement, which is null once
     * unknown. A known position with a null delta moves {@code speed} along a direction the engine does not know.
     * {@code anyLastDeflector} means {@code Projectile#lastDeflectedBy} may be any entity.
     */
    private record Branch(
            @Nullable Vec3 position,
            @Nullable Box region,
            @Nullable Vec3 delta,
            double speed,
            boolean inWater,
            boolean leftOwner,
            @Nullable PacketEntity lastDeflectedBy,
            boolean anyLastDeflector) {
        static Branch unbounded(Box region, @Nullable Vec3 delta, double speed) {
            return new Branch(null, region, delta, speed, false, false, null, true);
        }

        boolean bounded() {
            return position != null;
        }

        Branch at(Vec3 position, boolean inWater, boolean leftOwner) {
            return new Branch(position, null, delta, speed, inWater, leftOwner, lastDeflectedBy, anyLastDeflector);
        }

        Branch moving(@Nullable Vec3 delta, double speed) {
            return new Branch(position, region, delta, speed, inWater, leftOwner, lastDeflectedBy, anyLastDeflector);
        }

        Branch deflectedBy(PacketEntity entity, @Nullable Vec3 delta) {
            return new Branch(
                    position, region, delta, delta == null ? speed : delta.length(), inWater, leftOwner, entity, false);
        }
    }

    // A stop of one step: a position (or, hitting an unplaced entity, a region), and the entity box it hit.
    private record Outcome(
            @Nullable Vec3 position,
            @Nullable Box region,
            @Nullable PacketEntity target,
            int box) {}

    // A hit on a redirectable projectile, which Projectile#onHit aims along the owner's look.
    private record Redirect(PacketEntityHurtingProjectile target, int candidate) {}

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
     * Bedrock clients see Geyser's per-tick absolute moves, which the Geyser bridge already applies; 1.21.1 tests the
     * hit on the old delta, moves, then applies inertia, and this models the 1.21.2+ order only.
     */
    private boolean modelled() {
        return !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && getRiding() == null;
    }

    /**
     * Takes the packets applied since the last call: a written delta movement moves every state, a new packet
     * position starts every state there, and a packet position the client can no longer be at drops its states.
     */
    private void sync() {
        if (!modelled()) {
            newSnapshot = null;
            oldSnapshot = null;
            newBranches = List.of();
            oldBranches = List.of();
            publishedDelta = null;
            return;
        }
        if (deltaMovement != publishedDelta) {
            Vec3 delta = deltaMovement;
            newBranches = merge(moved(newBranches, delta));
            oldBranches = merge(moved(oldBranches, delta));
            publishedDelta = delta;
        }
        if (newPacketLocation != newSnapshot) {
            List<Branch> sources = new ArrayList<>(newBranches);
            sources.addAll(oldBranches);
            if (newSnapshot == null)
                sources.add(new Branch(
                        newPacketLocation.targetPosition(),
                        null,
                        deltaMovement,
                        deltaMovement.length(),
                        false,
                        false,
                        null,
                        false));
            List<Branch> kept = oldPacketLocation == null
                    ? null
                    : oldPacketLocation == newSnapshot
                            ? newBranches
                            : oldPacketLocation == oldSnapshot ? oldBranches : null;
            oldSnapshot = kept == null ? null : oldPacketLocation;
            oldBranches = kept == null ? List.of() : kept;
            newSnapshot = newPacketLocation;
            newBranches = seeds(sources, newPacketLocation.targetPosition());
        } else if (oldPacketLocation != oldSnapshot) {
            oldSnapshot = null;
            oldBranches = List.of();
        }
    }

    private static List<Branch> moved(List<Branch> branches, Vec3 delta) {
        List<Branch> moved = new ArrayList<>(branches.size());
        for (Branch branch : branches) moved.add(branch.moving(delta, delta.length()));
        return moved;
    }

    /** Each state set onto {@code position}; a state the engine does not bound keeps none of its own besides motion. */
    private static List<Branch> seeds(List<Branch> sources, Vec3 position) {
        List<Branch> seeds = new ArrayList<>();
        for (Branch source : sources) {
            if (source.bounded()) {
                seeds.add(source.at(position, source.inWater(), source.leftOwner()));
                continue;
            }
            for (boolean inWater : new boolean[] {false, true})
                for (boolean leftOwner : new boolean[] {false, true})
                    seeds.add(
                            new Branch(position, null, source.delta(), source.speed(), inWater, leftOwner, null, true));
        }
        return merge(seeds);
    }

    /**
     * {@code AbstractHurtingProjectile#tick} on 1.21.2+ clients, for each state: {@code applyInertia}, then
     * {@code setPos} to the hit of {@code ProjectileUtil#getHitResultOnMoveVector} or one delta movement on, then
     * {@code checkLeftOwner}, the water state, and {@code hitTargetOrDeflectSelf}. Until the client proves it has the
     * newest packet position, it may set the projectile there before any tick.
     */
    @Override
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        tickCount++;
        super.onMovement(player, tickingReliably);
        sync();
        if (!modelled()) return;
        if (oldSnapshot != null) {
            List<Branch> branches = new ArrayList<>(newBranches);
            branches.addAll(seeds(oldBranches, newSnapshot.targetPosition()));
            newBranches = merge(branches);
        }
        ClientEntityQueries queries = new ClientEntityQueries(player);
        List<PickCandidates> targets = queries.projectileTargets(this);
        PacketEntity owner = owner();
        PacketEntity ownerRoot = owner == null ? null : root(owner);
        List<PickCandidates> ownerTree = null;
        if (ownerRoot != null) {
            ownerTree = new ArrayList<>();
            for (PickCandidates entity : queries.pickableEntities())
                if (root(entity.entity()) == ownerRoot) ownerTree.add(entity);
        }
        List<Redirect> redirects = new ArrayList<>();
        List<Branch> steppedNew = new ArrayList<>();
        List<Branch> steppedOld = new ArrayList<>();
        for (Branch branch : newBranches) step(queries, targets, ownerRoot, ownerTree, branch, steppedNew, redirects);
        for (Branch branch : oldBranches) step(queries, targets, ownerRoot, ownerTree, branch, steppedOld, redirects);
        boolean certain = steppedNew.size() + steppedOld.size() == 1 && redirects.size() == 1;
        newBranches = merge(steppedNew);
        oldBranches = merge(steppedOld);
        if (owner != null) {
            Vec3 look = owner == player.compensatedEntities.getSelf() ? lookAngle(player.xRot, player.yRot) : null;
            for (Redirect redirect : redirects) redirect.target().redirect(redirect.candidate(), look, certain);
        }
        publish();
    }

    private void step(
            ClientEntityQueries queries,
            List<PickCandidates> targets,
            @Nullable PacketEntity ownerRoot,
            @Nullable List<PickCandidates> ownerTree,
            Branch branch,
            List<Branch> stepped,
            List<Redirect> redirects) {
        if (!branch.bounded()) {
            double speed = speedBound(queries, branch.speed());
            stepped.add(Branch.unbounded(branch.region().inflate(speed), null, speed));
            return;
        }
        if (branch.delta() == null) {
            double speed = (branch.speed() + accelerationPower) * Math.max(inertia, liquidInertia);
            stepped.add(Branch.unbounded(pointBox(branch.position()).inflate(speed), null, speed));
            return;
        }
        Vec3 movement = branch.delta();
        Vec3 delta = movement.add(movement.normalize().scale(accelerationPower))
                .scale(branch.inWater() ? liquidInertia : inertia);
        List<Outcome> outcomes = delta.lengthSqr() == 0.0D
                ? List.of(new Outcome(branch.position(), null, null, -1))
                : stops(targets(targets, ownerRoot, branch), branch.position(), delta);
        for (Outcome outcome : outcomes) {
            if (outcome.position() == null) {
                stepped.add(Branch.unbounded(outcome.region(), null, delta.length()));
                continue;
            }
            Branch moved = branch.moving(delta, delta.length());
            boolean inWater = touchesWater(outcome.position());
            for (boolean leftOwner :
                    branch.leftOwner() ? new boolean[] {true} : leftOwner(ownerTree, outcome.position(), delta)) {
                Branch at = moved.at(outcome.position(), inWater, leftOwner);
                if (outcome.target() == null) stepped.add(at);
                else hit(queries, at, outcome, stepped, redirects);
            }
        }
    }

    /**
     * {@code Projectile#hitTargetOrDeflectSelf} before 26.3, where {@code Projectile#deflect} also runs on the client:
     * an entity whose {@code deflection} is not {@code NONE} reverses and halves the motion unless it deflected the
     * projectile last, and any other hit runs {@code onHit}, which aims a hit redirectable projectile. On 26.3 both are
     * server-side.
     */
    private void hit(
            ClientEntityQueries queries,
            Branch branch,
            Outcome outcome,
            List<Branch> stepped,
            List<Redirect> redirects) {
        stepped.add(branch);
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) return;
        PacketEntity target = outcome.target();
        if (queries.deflects(target, this)) {
            // ProjectileDeflection.REVERSE; WindCharge#deflect refuses while its noDeflectTicks last.
            if (!deflectable(tickCount - 1)) return;
            Vec3 reversed = branch.delta().scale(-0.5D);
            if (branch.anyLastDeflector()) {
                stepped.set(stepped.size() - 1, branch.deflectedBy(target, branch.delta()));
                stepped.add(branch.deflectedBy(target, reversed));
            } else if (target != branch.lastDeflectedBy()) {
                stepped.set(stepped.size() - 1, branch.deflectedBy(target, reversed));
            }
            return;
        }
        if (target instanceof PacketEntityHurtingProjectile projectile && queries.redirectable(target))
            redirects.add(new Redirect(projectile, outcome.box()));
    }

    /**
     * {@code ProjectileDeflection.AIM_DEFLECT} from another projectile's {@code onHit} on the state at
     * {@code candidate}, an index into {@link #getPossibleMovementCollisionBoxCandidates()}: the motion becomes the
     * owner's look, null when the engine does not know it. A hit not certain to have happened adds the aimed state.
     */
    private void redirect(int candidate, @Nullable Vec3 look, boolean certain) {
        sync();
        if (!modelled() || !deflectable(tickCount)) return;
        boolean old = candidate < bounded(oldBranches);
        List<Branch> branches = new ArrayList<>(old ? oldBranches : newBranches);
        int index = -1;
        for (int i = 0, seen = old ? 0 : bounded(oldBranches); i < branches.size(); i++) {
            if (!branches.get(i).bounded()) continue;
            if (seen++ == candidate) {
                index = i;
                break;
            }
        }
        if (index < 0) return;
        Branch aimed = branches.get(index).moving(look, look == null ? 1.0D : look.length());
        if (certain && newBranches.size() + oldBranches.size() == 1) branches.set(index, aimed);
        else branches.add(aimed);
        if (old) oldBranches = merge(branches);
        else newBranches = merge(branches);
    }

    // WindCharge#noDeflectTicks counts down from 5 after each of its ticks.
    private boolean deflectable(int completedTicks) {
        return type != EntityTypeIds.WIND_CHARGE || completedTicks >= 5;
    }

    /** {@code Entity#calculateViewVector} of the local player's rotation. */
    private static Vec3 lookAngle(float yaw, float pitch) {
        float pitchRadians = pitch * ((float) Math.PI / 180.0F);
        float yawRadians = -yaw * ((float) Math.PI / 180.0F);
        float cosPitch = CultMath.cos(pitchRadians);
        return new Vec3(
                CultMath.sin(yawRadians) * cosPitch, -CultMath.sin(pitchRadians), CultMath.cos(yawRadians) * cosPitch);
    }

    /**
     * The longest delta movement a state the engine does not bound can have after a tick: inertia never grows it
     * past the acceleration, and an aim sets it to a unit look.
     */
    private double speedBound(ClientEntityQueries queries, double speed) {
        double aimed = queries.redirectable(this) ? Math.max(speed, 1.0D) : speed;
        return (aimed + accelerationPower) * Math.max(inertia, liquidInertia);
    }

    private void publish() {
        Branch first = null;
        for (Branch branch : newBranches) {
            if (branch.bounded()) {
                first = branch;
                break;
            }
        }
        if (first == null)
            for (Branch branch : oldBranches) {
                if (branch.bounded()) {
                    first = branch;
                    break;
                }
            }
        clientPhysicalPosition = first == null ? newSnapshot.targetPosition() : first.position();
        clientPhysicalPositionExact = first != null && newBranches.size() + oldBranches.size() == 1;
        if (first != null && first.delta() != null) deltaMovement = first.delta();
        publishedDelta = deltaMovement;
    }

    /**
     * {@code Projectile#canHitEntity}'s owner rule over the client's targets: until the projectile has left its owner,
     * it skips the owner's root vehicle and its passengers. Ender dragon parts are their own root vehicles.
     */
    private static List<PickCandidates> targets(
            List<PickCandidates> all, @Nullable PacketEntity ownerRoot, Branch branch) {
        if (ownerRoot == null || branch.leftOwner()) return all;
        List<PickCandidates> targets = new ArrayList<>();
        for (PickCandidates target : all)
            if (target.entity() == null || root(target.entity()) != ownerRoot) targets.add(target);
        return targets;
    }

    /**
     * Every stop {@code ProjectileUtil#getHitResult} can make from {@code from}: the block clip (or the full step), or
     * the nearest entity box, inflated by {@code computeMargin}, that the segment enters. Each target is at one of its
     * candidate boxes, independently of the others; an unplaced target may stop it anywhere on the segment.
     */
    private List<Outcome> stops(List<PickCandidates> targets, Vec3 from, Vec3 delta) {
        Vec3 to = from.add(blockStep(from, delta));
        Box area = searchArea(from, delta);
        double margin = Math.max(0.0F, Math.min(0.3F, (tickCount - 2) / 20.0F));
        List<Outcome> hits = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        double[] farthest = new double[targets.size()];
        boolean blockStop = true;
        boolean unplaced = false;
        for (int i = 0; i < targets.size(); i++) {
            PickCandidates target = targets.get(i);
            unplaced |= target.unplaced();
            boolean alwaysHit = !target.boxes().isEmpty() && !target.unplaced();
            if (target.boxes().isEmpty() || target.unplaced()) farthest[i] = Double.POSITIVE_INFINITY;
            for (int j = 0; j < target.boxes().size(); j++) {
                Box box = target.boxes().get(j);
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
                hits.add(new Outcome(hit.get(), null, target.entity(), j));
                distances.add(distance);
            }
            blockStop &= !alwaysHit;
        }
        List<Outcome> stops = new ArrayList<>();
        if (blockStop) stops.add(new Outcome(to, null, null, -1));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> distances.get(i)).reversed());
        for (int index : order) {
            double distance = distances.get(index);
            boolean nearest = true;
            for (double other : farthest) nearest &= other >= distance;
            if (nearest) stops.add(hits.get(index));
        }
        if (unplaced)
            stops.add(new Outcome(
                    null,
                    new Box(
                            Math.min(from.x, to.x),
                            Math.min(from.y, to.y),
                            Math.min(from.z, to.z),
                            Math.max(from.x, to.x),
                            Math.max(from.y, to.y),
                            Math.max(from.z, to.z)),
                    null,
                    -1));
        return stops;
    }

    /** The projectile's box stretched by {@code delta} and grown by one. */
    private Box searchArea(Vec3 position, Vec3 delta) {
        SimpleCollisionBox box = toBoundingBox(player, positionBox(position));
        return new Box(
                box.minX + Math.min(0, delta.x) - 1.0D,
                box.minY + Math.min(0, delta.y) - 1.0D,
                box.minZ + Math.min(0, delta.z) - 1.0D,
                box.maxX + Math.max(0, delta.x) + 1.0D,
                box.maxY + Math.max(0, delta.y) + 1.0D,
                box.maxZ + Math.max(0, delta.z) + 1.0D);
    }

    /**
     * {@code Projectile#checkLeftOwner} at {@code position}: whether the projectile may still be on its owner, and
     * whether it may have left: it leaves once no pickable entity of the owner's root vehicle and its passengers
     * touches its box stretched by its delta movement and grown by one.
     */
    private boolean[] leftOwner(@Nullable List<PickCandidates> ownerTree, Vec3 position, Vec3 delta) {
        if (ownerTree == null) return new boolean[] {true};
        Box area = searchArea(position, delta);
        boolean touching = false;
        boolean mayTouch = false;
        for (PickCandidates entity : ownerTree) {
            mayTouch |= entity.unplaced();
            boolean every = !entity.boxes().isEmpty() && !entity.unplaced();
            for (Box box : entity.boxes()) {
                boolean touches = box != null && area.intersects(box);
                mayTouch |= touches;
                every &= touches;
            }
            touching |= every;
        }
        if (touching) return new boolean[] {false};
        return mayTouch ? new boolean[] {false, true} : new boolean[] {true};
    }

    private @Nullable PacketEntity owner() {
        return hasOwner ? player.compensatedEntities.getEntity(ownerId) : null;
    }

    private static PacketEntity root(PacketEntity entity) {
        while (entity.getRiding() != null) entity = entity.getRiding();
        return entity;
    }

    /** States that differ in nothing collapse into one; every state the engine does not bound collapses into one. */
    private static List<Branch> merge(List<Branch> branches) {
        List<Branch> merged = new ArrayList<>(branches.size());
        Branch unbounded = null;
        for (Branch branch : branches) {
            if (!branch.bounded()) {
                unbounded = unbounded == null
                        ? branch
                        : Branch.unbounded(
                                union(unbounded.region(), branch.region()),
                                null,
                                Math.max(unbounded.speed(), branch.speed()));
                continue;
            }
            boolean known = false;
            for (Branch other : merged) known |= same(other, branch);
            if (!known) merged.add(branch);
        }
        if (unbounded != null) merged.add(unbounded);
        return merged;
    }

    private static boolean same(Branch one, Branch two) {
        return same(one.position(), two.position())
                && same(one.delta(), two.delta())
                && Double.compare(one.speed(), two.speed()) == 0
                && one.inWater() == two.inWater()
                && one.leftOwner() == two.leftOwner()
                && one.lastDeflectedBy() == two.lastDeflectedBy()
                && one.anyLastDeflector() == two.anyLastDeflector();
    }

    private static boolean same(@Nullable Vec3 one, @Nullable Vec3 two) {
        if (one == null || two == null) return one == two;
        return Double.compare(one.x, two.x) == 0
                && Double.compare(one.y, two.y) == 0
                && Double.compare(one.z, two.z) == 0;
    }

    private static Box union(Box one, Box two) {
        return new Box(
                Math.min(one.minX(), two.minX()),
                Math.min(one.minY(), two.minY()),
                Math.min(one.minZ(), two.minZ()),
                Math.max(one.maxX(), two.maxX()),
                Math.max(one.maxY(), two.maxY()),
                Math.max(one.maxZ(), two.maxZ()));
    }

    private static Box pointBox(Vec3 position) {
        return new Box(position.x, position.y, position.z, position.x, position.y, position.z);
    }

    private static int bounded(List<Branch> branches) {
        int count = 0;
        for (Branch branch : branches) if (branch.bounded()) count++;
        return count;
    }

    /**
     * The box at the state's position, grown for reach queries as the packet position's own boxes are; a state the
     * engine does not bound spans its region.
     */
    private SimpleCollisionBox box(ReachInterpolationData snapshot, Branch branch, boolean movement) {
        SimpleCollisionBox box;
        if (branch.bounded()) box = positionBox(branch.position());
        else {
            Box region = branch.region();
            box = ReachInterpolationData.combineCollisionBox(
                    positionBox(new Vec3(region.minX(), region.minY(), region.minZ())),
                    positionBox(new Vec3(region.maxX(), region.maxY(), region.maxZ())));
        }
        return movement ? box : snapshot.expandForReach(box);
    }

    private SimpleCollisionBox combined(SimpleCollisionBox out, boolean movement) {
        boolean first = true;
        for (int i = 0; i < 2; i++) {
            ReachInterpolationData snapshot = i == 0 ? oldSnapshot : newSnapshot;
            for (Branch branch : i == 0 ? oldBranches : newBranches) {
                SimpleCollisionBox box = box(snapshot, branch, movement);
                if (first) out.set(box);
                else ReachInterpolationData.combineCollisionBox(out, box, out);
                first = false;
            }
        }
        return out;
    }

    @Override
    public SimpleCollisionBox getPossibleCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        sync();
        if (newBranches.isEmpty()) return super.getPossibleCollisionBoxes(out, scratch);
        return combined(out, false);
    }

    @Override
    public SimpleCollisionBox getPossibleMovementCollisionBoxes(SimpleCollisionBox out, SimpleCollisionBox scratch) {
        sync();
        if (newBranches.isEmpty()) return super.getPossibleMovementCollisionBoxes(out, scratch);
        return combined(out, true);
    }

    /** One box per state whose position the engine bounds, those of the older packet position first. */
    @Override
    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxCandidates() {
        sync();
        if (newBranches.isEmpty()) return super.getPossibleMovementCollisionBoxCandidates();
        List<SimpleCollisionBox> candidates = new ArrayList<>();
        for (Branch branch : oldBranches) if (branch.bounded()) candidates.add(box(oldSnapshot, branch, true));
        for (Branch branch : newBranches) if (branch.bounded()) candidates.add(box(newSnapshot, branch, true));
        return candidates;
    }

    @Override
    public List<SimpleCollisionBox> getPossibleMovementCollisionBoxGroups() {
        sync();
        if (newBranches.isEmpty()) return super.getPossibleMovementCollisionBoxGroups();
        List<SimpleCollisionBox> groups = new ArrayList<>();
        for (Branch branch : oldBranches) groups.add(box(oldSnapshot, branch, true));
        for (Branch branch : newBranches) groups.add(box(newSnapshot, branch, true));
        return groups;
    }

    @Override
    public SimpleCollisionBox getExactMovementCollisionBox() {
        sync();
        if (newBranches.isEmpty()) return super.getExactMovementCollisionBox();
        if (newBranches.size() + oldBranches.size() != 1
                || !newBranches.getFirst().bounded()) return null;
        return box(newSnapshot, newBranches.getFirst(), true);
    }

    @Override
    public boolean hasUnboundedPosition() {
        sync();
        return bounded(newBranches) != newBranches.size() || bounded(oldBranches) != oldBranches.size();
    }

    private SimpleCollisionBox positionBox(Vec3 position) {
        return GetBoundingBox.getPacketEntityBoundingBox(player, position.x, position.y, position.z, this);
    }

    /** {@code EntityFluidInteraction#update}: water whose top reaches the bottom of the box deflated by 0.001. */
    private boolean touchesWater(Vec3 position) {
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

    private Vec3 blockStep(Vec3 from, Vec3 delta) {
        // ClipContext.Block.COLLIDER with CollisionContext.of(this projectile).
        EntityCollisionContext context = EntityCollisionContext.position(from.y);
        BlockHit hit = player.compensatedWorld
                .geometry()
                .clipBlocks(
                        from,
                        from.add(delta),
                        (state, pos) -> NativeBlockCollisionHelper.getCollisionShape(
                                player, state, pos, from.y, null, context));
        if (hit == null) return delta;
        return new Vec3(hit.location().x(), hit.location().y(), hit.location().z()).subtract(from);
    }
}
