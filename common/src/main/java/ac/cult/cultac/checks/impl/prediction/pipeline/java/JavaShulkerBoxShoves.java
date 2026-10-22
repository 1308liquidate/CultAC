package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.ShulkerBoxShove;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ShulkerBoxBlockEntity#moveCollidedEntities on the local player during Level#tickBlockEntities. Before 26.3 that pass
 * runs after LocalPlayer#sendPosition, so each shove is an Entity#move(MoverType.SHULKER_BOX) between two movement
 * packets: it moves the start of the next travel and rewrites the state that travel reads.
 */
public final class JavaShulkerBoxShoves {
    private JavaShulkerBoxShoves() {}

    /** The client's state after the pass. */
    public record Moved(
            Vec3 position,
            boolean onGround,
            MainSupportingBlockData support,
            JavaPredictionCarry carry,
            Set<Vec3> velocities) {}

    /** Null when no lid's area reaches the player. */
    public static Moved apply(
            CultPlayer player,
            List<ShulkerBoxShove> shoves,
            Vec3 position,
            boolean onGround,
            MainSupportingBlockData support,
            JavaPredictionCarry carry,
            Set<Vec3> velocities,
            PredictionResult lastPrediction) {
        boolean moved = false;
        for (ShulkerBoxShove shove : shoves) {
            SimpleCollisionBox box =
                    GetBoundingBox.getCollisionBoxForPlayer(player, position.x, position.y, position.z);
            if (!shove.reaches(box)) continue;
            moved = true;
            Vec3 movement = shove.movement();
            if (player.gamemode == GameMode.SPECTATOR) {
                // Player#tick sets noPhysics for spectators; Entity#move then only calls setPos.
                position = position.add(movement);
                continue;
            }

            // Entity#move scales every MoverType except PISTON by a pending stuckSpeedMultiplier,
            // then clears it and zeroes deltaMovement.
            Vec3 stuck = carry == null || carry.states().isEmpty()
                    ? null
                    : carry.states().getFirst().stuckSpeed();
            if (stuck != null && stuck.lengthSqr() > 1.0E-7) movement = movement.multiply(stuck);
            else stuck = null;

            Vec3 collided = Collisions.collide(player, box, movement.x, movement.y, movement.z, onGround);
            double collidedSqr = collided.lengthSqr();
            if (collidedSqr > 1.0E-7 || movement.lengthSqr() - collidedSqr < 1.0E-7) {
                position = position.add(collided);
            }

            boolean collidedX = !CultMath.equal(movement.x, collided.x);
            boolean collidedZ = !CultMath.equal(movement.z, collided.z);
            // The local player is authoritative: verticalCollision is recomputed even for a horizontal shove.
            boolean verticalCollision = movement.y != collided.y;
            onGround = verticalCollision && movement.y < 0.0;
            // Entity#checkSupportingBlock clears mainSupportingBlockPos off the ground.
            support = onGround ? support : new MainSupportingBlockData(null, false);
            boolean water = lastPrediction != null
                    && lastPrediction.getSimulationContext().getWorldData().getInWater() != DesyncStatus.FALSE;

            float speedFactor = speedFactor(player, lastPrediction, support, position);
            Velocity velocity = new Velocity(stuck != null, collidedX, collidedZ, verticalCollision, speedFactor);
            Set<Vec3> nextVelocities = new LinkedHashSet<>();
            for (Vec3 vector : velocities) nextVelocities.add(velocity.apply(vector));
            velocities = nextVelocities;
            if (carry != null) {
                List<JavaPredictionCarry.State> states =
                        new ArrayList<>(carry.states().size());
                for (JavaPredictionCarry.State state : carry.states()) {
                    JavaPredictionCarry.State next = new JavaPredictionCarry.State(
                            velocity.apply(state.velocity()),
                            fallDistance(state.fallDistance(), collided.y, onGround, water),
                            stuck != null ? null : state.stuckSpeed());
                    if (!states.contains(next)) states.add(next);
                }
                carry = new JavaPredictionCarry(
                        carry.actor(), fallDistance(carry.fallDistance(), collided.y, onGround, water), states);
            }
        }
        return moved ? new Moved(position, onGround, support, carry, velocities) : null;
    }

    /** Entity#checkFallDamage after the move. */
    private static double fallDistance(double distance, double movedY, boolean onGround, boolean water) {
        if (!water && movedY < 0.0) distance -= movedY;
        return onGround ? 0.0 : distance;
    }

    /** LivingEntity#getBlockSpeedFactor at the moved position. */
    private static float speedFactor(
            CultPlayer player, PredictionResult lastPrediction, MainSupportingBlockData support, Vec3 position) {
        float factor = BlockProperties.getBlockSpeedFactor(player, support, position);
        return lastPrediction == null
                ? factor
                : CultMath.lerp(lastPrediction.getSimulationContext().getMovementEfficiency(), factor, 1.0F);
    }

    /** Entity#move's writes to deltaMovement, in order. */
    private record Velocity(
            boolean stuck, boolean collidedX, boolean collidedZ, boolean verticalCollision, float speedFactor) {
        Vec3 apply(Vec3 velocity) {
            if (stuck) velocity = Vec3.ZERO;
            double x = collidedX ? 0.0 : velocity.x;
            double z = collidedZ ? 0.0 : velocity.z;
            // Block#updateEntityMovementAfterFallOn for the block under the player.
            double y = verticalCollision ? 0.0 : velocity.y;
            return new Vec3(x * speedFactor, y, z * speedFactor);
        }
    }
}
