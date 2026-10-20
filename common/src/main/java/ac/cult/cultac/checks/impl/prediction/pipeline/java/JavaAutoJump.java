package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientSprintState;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Source-proven automatic input: LocalPlayer#move/updateAutoJump, then next tick's aiStep/makeJump. */
public final class JavaAutoJump extends CultProcessor implements PostPredictionListener {
    private boolean queued;
    private boolean jumpedThisTick;
    private boolean possible;
    private int movedClientTick = -1;

    public JavaAutoJump(CultPlayer player) {
        super(player);
    }

    public void reset() {
        queued = jumpedThisTick = false;
    }

    public boolean jumpedThisTick() {
        return jumpedThisTick;
    }

    /**
     * Whether LocalPlayer#updateAutoJump can have set autoJumpTime in the client tick {@code clientTick}: the outcome
     * of the move the simulation ran for that tick, or false when it ran none.
     */
    public boolean canFireIn(int clientTick) {
        return movedClientTick == clientTick && possible;
    }

    public boolean hasManualMovementInput() {
        var input = player.packetStateData.knownInput;
        return input.movingNoJump() || input.jump() && !jumpedThisTick;
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (player.isBedrockMovement() || complete.isTeleport()) return;
        jumpedThisTick = queued;
        boolean judged = !complete.isExempt() && !complete.getPredictionResult().hasEffectiveFlags();
        queued = judged && schedulesJump(complete.getPredictionResult());
        movedClientTick = player.packetStateData.acceptedClientTick;
        PredictionResult result = complete.getPredictionResult();
        possible = judged ? queued : canFireAfter(result, 1.0E-7);
    }

    /** A client tick that sent no position, simulated from the player's position under the movement exemption. */
    public void onExemptNoPositionTick(PredictionResult result) {
        if (player.isBedrockMovement()) return;
        movedClientTick = player.packetStateData.acceptedClientTick;
        // LocalPlayer#sendPosition withholds a position within the threshold of the last one it sent.
        possible = canFireAfter(result, player.getMovementThreshold());
    }

    boolean schedulesJump(PredictionResult result) {
        return JavaFallDistance.landsOnClip(
                        result.getCollideAxisData().getYNeg(),
                        result.getTarget().y,
                        result.getSimulationContext().getLastStuckSpeed().y)
                && canFire(
                        result.getTarget(),
                        sprintOutcome(result),
                        result.getSimulationContext().getJumpAmplifier(),
                        result.getSimulationContext().getWorldData().getMainSupportingBlockPos());
    }

    private boolean canFireAfter(PredictionResult result, double tolerance) {
        Vec3 moved = groundedMove(result, tolerance);
        return moved != null
                && canFire(
                        moved,
                        sprintOutcome(result),
                        result.getSimulationContext().getJumpAmplifier(),
                        result.getSimulationContext().getWorldData().getMainSupportingBlockPos());
    }

    /**
     * The position change of an unjudged move that can have ended on the ground, or null. The attempted movement is
     * one the move's simulation explains: a candidate's collision-ignored extents, at their bounds and at the
     * reported movement on X and Z, and at their lowest on Y. Entity#collide runs on it from the start box, with
     * every ground state the engine allows for the step; Entity#move grounds a downward attempt that collided
     * vertically, and its setPos guard keeps a collided movement within 1.0E-7 squared in place when the collision
     * removed at least that much. The position change is within {@code tolerance} of the reported movement.
     */
    private Vec3 groundedMove(PredictionResult result, double tolerance) {
        if (!result.getFlags().isEmpty()) return null;
        Vec3 target = result.getTarget();
        List<PredictionResult> candidates = new ArrayList<>();
        candidates.add(result);
        for (PredictionResult reality : result.getRealities()) {
            if (reality.getFlagSeverity() == 0) candidates.add(reality);
        }
        for (PredictionResult candidate : candidates) {
            if (candidate.getValidMovements() == null) continue;
            SimpleCollisionBox extents = candidate.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
            if (extents == null) continue;
            SimulationContext context = candidate.getSimulationContext();
            Vec3 stuck = candidate.getInitialStartingVel().collisionStuckSpeedMultiplier(context);
            if (stuck == null) stuck = new Vec3(1, 1, 1);
            double y = Math.min(candidate.getInitialStartingVel().y, extents.minY) * stuck.y;
            if (!(y < 0)) continue;
            SimpleCollisionBox from = context.getFromActualPose();
            var lastOnGround = context.getLastOnGround();
            for (double x : new double[] {target.x, extents.minX * stuck.x, extents.maxX * stuck.x}) {
                for (double z : new double[] {target.z, extents.minZ * stuck.z, extents.maxZ * stuck.z}) {
                    List<Collisions.Axis> order = Math.abs(x) < Math.abs(z)
                            ? List.of(Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X)
                            : List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z);
                    for (boolean onGround : new boolean[] {true, false}) {
                        if (onGround ? !lastOnGround.determineOptimistically() : lastOnGround.determinePessimistically())
                            continue;
                        Vec3 collided = Collisions.collideWithAdditionalCollisionBoxes(
                                player, from, x, y, z, order, true, Collections.emptyList(), onGround);
                        if (collided.y == y) continue;
                        double collidedSqr = collided.lengthSqr();
                        boolean kept = collidedSqr <= 1.0E-7 && x * x + y * y + z * z - collidedSqr >= 1.0E-7;
                        Vec3 moved = kept ? Vec3.ZERO : collided;
                        if (moved.distanceTo(target) <= tolerance) return moved;
                    }
                }
            }
        }
        return null;
    }

    private static ClientSprintState.Outcome sprintOutcome(PredictionResult result) {
        PredVector start = result.getInitialStartingVel();
        return start == null ? ClientSprintState.Outcome.ANY : start.getSprintOutcome();
    }

    // LocalPlayer#canAutoJump's remaining prerequisites and updateAutoJump's geometry for a move of {@code move}
    // ending at the player's position.
    private boolean canFire(
            Vec3 move, ClientSprintState.Outcome sprint, Integer amplifier, MainSupportingBlockData support) {
        if (player.isSneaking
                || player.inVehicle()
                || BlockProperties.onHoneyBlock(player, support, new Vec3(player.x, player.y, player.z)))
            return false;
        var keys = player.packetStateData.knownInput;
        Vec3 input = new Vec3(
                (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0),
                0,
                (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0));
        if (input.horizontalDistanceSqr() == 0) return false;
        float yaw = player.xRot * ((float) Math.PI / 180.0F);
        float sin = player.trigHandler.sin(yaw), cos = player.trigHandler.cos(yaw);
        float speed = (float) player.compensatedEntities.getMaxPlayerMovementSpeed(false, sprint);
        Vec3 begin = new Vec3(player.x, player.y, player.z);
        float dx = (float) move.x, dz = (float) move.z;
        Vec3 end = begin.add(dx, 0, dz), diff = new Vec3(dx, 0, dz);
        float distanceSq = (float) diff.lengthSqr();
        if (distanceSq <= 0.001F) {
            double length = Math.sqrt(input.horizontalDistanceSqr());
            if (length > 1) input = input.scale(1 / length);
            float ix = speed * (float) input.x, iz = speed * (float) input.z;
            diff = new Vec3(ix * cos - iz * sin, 0, iz * cos + ix * sin);
            distanceSq = (float) diff.horizontalDistanceSqr();
            if (distanceSq <= 0.001F) return false;
        }
        float inverse = org.joml.Math.invsqrt(distanceSq);
        Vec3 direction = diff.scale(inverse);
        float pitchCos = player.trigHandler.cos(player.yRot * ((float) Math.PI / 180.0F));
        if ((float) (player.trigHandler.sin(-yaw) * pitchCos * direction.x
                        + player.trigHandler.cos(-yaw) * pitchCos * direction.z)
                < -0.15F) return false;
        int ceilingX = (int) Math.floor(player.x),
                ceilingY = (int) Math.floor(player.boundingBox.maxY),
                ceilingZ = (int) Math.floor(player.z);
        if (!JavaAutoJumpGeometry.block(player, ceilingX, ceilingY, ceilingZ).isEmpty()
                || !JavaAutoJumpGeometry.block(player, ceilingX, ++ceilingY, ceilingZ)
                        .isEmpty()) return false;
        float jumpHeight = 1.2F;
        if (amplifier != null) jumpHeight += (amplifier + 1) * 0.75F;
        float lookAhead = Math.max(speed * 7.0F, 1.0F / inverse);
        float width = player.pose.width * player.getScale(), height = player.getBukkitHeight();
        Vec3 far = end.add(direction.scale(lookAhead));
        var query = new SimpleCollisionBox(begin.x, begin.y, begin.z, far.x, far.y + height, far.z)
                .sort()
                .inflate(width, 0, width);
        Vec3 offset = new Vec3(-direction.z, 0, direction.x).scale(width * 0.5F);
        Vec3 lowBegin = begin.add(0, 0.51F, 0), lowEnd = far.add(0, 0.51F, 0);
        for (var box : JavaAutoJumpGeometry.collisions(player, query)) {
            if (!JavaAutoJumpGeometry.intersects(box, lowBegin.subtract(offset), lowEnd.subtract(offset))
                    && !JavaAutoJumpGeometry.intersects(box, lowBegin.add(offset), lowEnd.add(offset))) continue;
            float obstacleHeight = (float) box.maxY;
            Vec3 center = box.getCenter();
            int x = (int) Math.floor(center.x), y = (int) Math.floor(center.y), z = (int) Math.floor(center.z);
            for (int steps = 1; steps < jumpHeight; steps++) {
                var above = JavaAutoJumpGeometry.block(player, x, y + steps, z);
                if (!above.isEmpty()) {
                    // VoxelShape.max returns a local coordinate before the client's float promotion.
                    double max = Double.NEGATIVE_INFINITY;
                    for (var part : above) max = Math.max(max, part.maxY - (y + steps));
                    obstacleHeight = (float) max + y + steps;
                    if (obstacleHeight - player.y > jumpHeight) return false;
                }
                if (steps > 1
                        && !JavaAutoJumpGeometry.block(player, ceilingX, ++ceilingY, ceilingZ)
                                .isEmpty()) return false;
            }
            float rise = (float) (obstacleHeight - player.y);
            return rise > 0.5F && rise <= jumpHeight;
        }
        return false;
    }
}
