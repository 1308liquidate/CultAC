package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;

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
     * Whether LocalPlayer#updateAutoJump can have set autoJumpTime in the client tick {@code clientTick}. A judged
     * move gives its outcome; an unjudged one runs the geometry on its reported movement from wherever the player
     * can be on the ground; a tick that sent no position runs it on the input from the last position.
     */
    public boolean canFireIn(int clientTick) {
        if (movedClientTick == clientTick) return possible;
        PredictionResult last = player.checkManager.getSimulationProcessor().getLastPrediction();
        // LocalPlayer#sendPosition withholds only a position within the threshold of the last one it sent, and
        // sends StatusOnly when onGround changes.
        return canStandOnGround(player.getMovementThreshold())
                && canFire(
                        Vec3.ZERO,
                        player.compensatedEntities.getJumpAmplifier(),
                        last == null
                                ? new MainSupportingBlockData(null, false)
                                : last.getSimulationContext().getWorldData().getMainSupportingBlockPos());
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
        // An unjudged move's candidate collisions are unproven; a clipped descent leaves the player resting on a
        // surface, and the attempted vertical movement is what the engine doesn't know.
        PredictionResult result = complete.getPredictionResult();
        possible = judged
                ? queued
                : canStandOnGround(1.0E-7)
                        && canFire(
                                result.getTarget(),
                                result.getSimulationContext().getJumpAmplifier(),
                                result.getSimulationContext().getWorldData().getMainSupportingBlockPos());
    }

    boolean schedulesJump(PredictionResult result) {
        return JavaFallDistance.landsOnClip(
                        result.getCollideAxisData().getYNeg(),
                        result.getTarget().y,
                        result.getSimulationContext().getLastStuckSpeed().y)
                && canFire(
                        result.getTarget(),
                        result.getSimulationContext().getJumpAmplifier(),
                        result.getSimulationContext().getWorldData().getMainSupportingBlockPos());
    }

    // A surface within depth below the player's box.
    private boolean canStandOnGround(double depth) {
        SimpleCollisionBox box = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z);
        return Collisions.collide(player, box, 0, -depth, 0).y != -depth;
    }

    // LocalPlayer#canAutoJump's remaining prerequisites and updateAutoJump's geometry for a move of {@code move}
    // ending at the player's position.
    private boolean canFire(Vec3 move, Integer amplifier, MainSupportingBlockData support) {
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
        float speed = (float) (player.compensatedEntities.getPlayerMovementSpeed()
                * (player.sprintState.modifier() ? 1.0D + (double) 0.3F : 1.0D));
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
