package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/**
 * Entity#deltaMovement across a sneaking edge. Entity#move moves by the delta Player#maybeBackOffFromEdge shortens,
 * while deltaMovement keeps the delta LivingEntity#travel built, so the next tick starts from velocity the position
 * never showed. On a shortened axis that delta holds the input of the keys in the client's latest PLAYER_INPUT, which
 * LocalPlayer#sendChanges sends before the tick's position.
 */
final class SneakEdgeBackoff {
    private static final double STEP = 0.05D;
    // Bounds the 0.05 probe loop; a larger shortened delta keeps the engine's candidates.
    private static final double MAX_PROBED_AXIS = 1.0D;

    private SneakEdgeBackoff() {}

    /**
     * The deltaMovement Entity#move leaves after an edge shortened the accepted move, or null when no zero-flag
     * reality's starting velocity reproduces that move with the client's keys, or several reproduce it differently.
     */
    static Vec3 deltaMovement(PredictionResult result, Vec3 accepted) {
        Vec3 found = deltaMovementFrom(result, accepted);
        List<PredictionResult> realities = result.getRealities();
        for (int i = 0, size = realities == null ? 0 : realities.size(); i < size; i++) {
            PredictionResult reality = realities.get(i);
            if (reality == result || reality.getFlagSeverity() != 0.0) continue;
            Vec3 other = deltaMovementFrom(reality, accepted);
            if (other == null) continue;
            if (found != null && !found.equals(other)) return null;
            found = other;
        }
        return found;
    }

    private static Vec3 deltaMovementFrom(PredictionResult result, Vec3 accepted) {
        CultPlayer player = result.getPlayer();
        SimulationContext context = result.getSimulationContext();
        if (player == null || context == null || !appliesTo(player, result, context)) return null;

        PredVector start = result.getInitialStartingVel();
        Vec3 input = travelInput(player, context, player.packetStateData.knownInput);
        if (input == null) return null;
        Vec3 delta = new Vec3(start.x + input.x, start.y, start.z + input.z);
        if (!(Math.abs(delta.x) <= MAX_PROBED_AXIS) || !(Math.abs(delta.z) <= MAX_PROBED_AXIS)) return null;

        SimpleCollisionBox box = context.getFromActualPose();
        Vec3 backedOff = backOff(player, box, delta, player.getMaxUpStep());
        if (backedOff.x == delta.x && backedOff.z == delta.z) return null;

        // Entity#move skips setPos for a collided move shorter than 1e-7 that the backoff left.
        Vec3 movement = Collisions.collide(player, box, backedOff.x, backedOff.y, backedOff.z);
        double movementLength = movement.lengthSqr();
        boolean moved = movementLength > 1.0E-7 || backedOff.lengthSqr() - movementLength < 1.0E-7;
        Vec3 position = moved ? movement : Vec3.ZERO;
        if (position.distanceToSqr(accepted) > 1.0E-14) return null;

        // Entity#restituteMovementAfterCollisions zeroes each collided axis of a player suppressing bounce.
        return new Vec3(
                CultMath.equal(backedOff.x, movement.x) ? delta.x : 0.0D,
                backedOff.y == movement.y ? delta.y : 0.0D,
                CultMath.equal(backedOff.z, movement.z) ? delta.z : 0.0D);
    }

    private static boolean appliesTo(CultPlayer player, PredictionResult result, SimulationContext context) {
        PredVector start = result.getInitialStartingVel();
        WorldData world = context.getWorldData();
        // 1.21.6 is the earliest checked client with the 1e-7 canFallAtLeast slab and the square-movement correction.
        return player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6)
                && !player.isBedrockMovement()
                && player.packetStateData.clientMovementInputKnown
                // Player#isStayingOnGroundSurface reads the keys this tick's PLAYER_INPUT sent.
                && player.packetStateData.knownInput.shift()
                && context.isSneaking()
                && context.getVehicle() == null
                && !player.isFlying
                && !context.usesFallFlyingMovement()
                && start != null
                && !start.isTickSkip()
                && !start.isJump()
                && !start.isKnockback()
                && !(start.y > 0.0D)
                && world.getLastOnGround() == DesyncStatus.TRUE
                && world.getInWater() == DesyncStatus.FALSE
                && world.getInLava() == DesyncStatus.FALSE
                && world.getClimbingAtStart() == DesyncStatus.FALSE
                && !world.isMightBeInBlock()
                && context.getMaxAttackSlow() == 0
                // A client hiding its sprint state carries less velocity, not more.
                && !player.isSprinting
                && player.sprintState.flagKnown()
                && !player.sprintState.flag()
                && !player.sprintState.modifier()
                && !IsUsingItem.isUsingItem(player)
                && result.getValidMovements() != null
                && result.getValidMovements().getPositionOnlyDelta().lengthSqr() <= 1.0E-14;
    }

    /**
     * LivingEntity#moveRelative's addition for {@code keys} on the ground: KeyboardInput#tick, LocalPlayer#modifyInput
     * and Entity#getInputVector. LocalPlayer#aiStep computes crouching from the previous keys before Input#tick. Null
     * when a pose the context does not determine could slow the input.
     */
    static Vec3 travelInput(CultPlayer player, SimulationContext context, KnownInput keys) {
        Vec3 start = context.getStart();
        SimpleCollisionBox standing = GetBoundingBox.getBoundingBoxFromPosAndSize(
                        start.x,
                        start.y,
                        start.z,
                        Pose.STANDING.width * context.getScale(),
                        Pose.STANDING.height * context.getScale())
                .expand(-SimpleCollisionBox.COLLISION_EPSILON);
        // LocalPlayer#isMovingSlowly: with room to stand, the player crouches exactly when shift was held.
        if (!Collisions.isEmpty(player, standing)) return null;

        float movingSlowlyFactor = context.isLastSneaking()
                ? (float) player.compensatedEntities.getSelf().sneakingSpeed.value()
                : 1.0F;
        Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                new Vec3(keys.strafeImpulse(), 0.0D, keys.forwardImpulse()), movingSlowlyFactor);
        return inputVector(context, modified, context.getMoveRelativeSpeed(player, true, false, false));
    }

    /** Entity#getInputVector. */
    private static Vec3 inputVector(SimulationContext context, Vec3 input, float speed) {
        double lengthSqr = input.lengthSqr();
        if (lengthSqr < 1.0E-7D) return Vec3.ZERO;
        Vec3 movement = (lengthSqr > 1.0D ? input.normalize() : input).scale(speed);
        float yawRadians = context.getXRot() * ((float) Math.PI / 180F);
        float sin = context.getTrig().sin(yawRadians);
        float cos = context.getTrig().cos(yawRadians);
        return new Vec3(movement.x * cos - movement.z * sin, movement.y, movement.z * cos + movement.x * sin);
    }

    /** Player#maybeBackOffFromEdge once its flying, mover, shift and Player#isAboveGround conditions hold. */
    static Vec3 backOff(CultPlayer player, SimpleCollisionBox box, Vec3 delta, float maxDownStep) {
        SimpleCollisionBox probe = new SimpleCollisionBox();
        double deltaX = delta.x;
        double deltaZ = delta.z;
        double stepX = Math.signum(deltaX) * STEP;
        double stepZ = Math.signum(deltaZ) * STEP;
        while (deltaX != 0.0 && canFallAtLeast(player, box, probe, deltaX, 0.0, maxDownStep)) {
            if (Math.abs(deltaX) <= STEP) {
                deltaX = 0.0;
                break;
            }
            deltaX -= stepX;
        }
        while (deltaZ != 0.0 && canFallAtLeast(player, box, probe, 0.0, deltaZ, maxDownStep)) {
            if (Math.abs(deltaZ) <= STEP) {
                deltaZ = 0.0;
                break;
            }
            deltaZ -= stepZ;
        }
        while (deltaX != 0.0 && deltaZ != 0.0 && canFallAtLeast(player, box, probe, deltaX, deltaZ, maxDownStep)) {
            deltaX = Math.abs(deltaX) <= STEP ? 0.0 : deltaX - stepX;
            deltaZ = Math.abs(deltaZ) <= STEP ? 0.0 : deltaZ - stepZ;
        }
        return new Vec3(deltaX, delta.y, deltaZ);
    }

    /** Player#canFallAtLeast: nothing collides in the slab {@code minHeight} below the moved box's feet. */
    private static boolean canFallAtLeast(
            CultPlayer player,
            SimpleCollisionBox box,
            SimpleCollisionBox probe,
            double deltaX,
            double deltaZ,
            double minHeight) {
        probe.set(
                box.minX + 1.0E-7 + deltaX,
                box.minY - minHeight - 1.0E-7,
                box.minZ + 1.0E-7 + deltaZ,
                box.maxX - 1.0E-7 + deltaX,
                box.minY,
                box.maxZ - 1.0E-7 + deltaZ);
        return Collisions.isEmpty(player, probe, box.minY);
    }
}
