package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import ac.cult.cultac.bedrock.prediction.geometry.BedrockActorBox;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockEntityMove;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMath;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;

/**
 * The rebound of a riptide spin attack off the mobs it hits. A spinning Bedrock actor scales its velocity by -0.2 for
 * every mob it hits in the tick, so the velocity it carries into the next tick, and reports as its delta, is the
 * travel's end velocity scaled by -0.2 per hit. Like Java's LivingEntity#checkAutoSpinAttack it runs once the travel
 * has moved the actor and searches the boxes it held before and after that travel, at the size the spin gives it.
 */
final class BedrockSpinAttackMovement {
    private static final float REBOUND = -0.2F;

    private BedrockSpinAttackMovement() {}

    static BedrockPostMoveResult apply(
            BedrockPostMoveContext context, BedrockPostMoveResult effects, BedrockEntityMove.Result move) {
        int hits = hits(context, move);
        if (hits == 0) return effects;
        return effects.withSpinAttackRebound(
                rebound(effects.velocity(), hits),
                effects.nonHopVelocity() == null ? null : rebound(effects.nonHopVelocity(), hits));
    }

    /** The float velocity scaled by -0.2 once per hit. */
    static Vec3d rebound(Vec3d velocity, int hits) {
        for (int hit = 0; hit < hits; hit++) {
            velocity = new Vec3d(
                    BedrockMath.f(velocity.x() * REBOUND),
                    BedrockMath.f(velocity.y() * REBOUND),
                    BedrockMath.f(velocity.z() * REBOUND));
        }
        return velocity;
    }

    static int hits(BedrockPostMoveContext context, BedrockEntityMove.Result move) {
        if (!context.plan().frame().riptide().spinActive()) return 0;
        var targets = context.context().entityContactState();
        if (targets.passenger() || targets.spinAttackTargets().isEmpty()) return 0;
        BedrockMovementState current = context.current();
        if (current.isVehicle()) return 0;
        WorldCollisionBox start = current.collisionBox(context.movementDimensions());
        WorldCollisionBox end = move.collisionBox() != null
                ? move.collisionBox()
                : BedrockActorBox.create(move.position(), context.movementDimensions(), current.coordinateFrame());
        return targets.spinAttackHits(union(start, end));
    }

    private static WorldCollisionBox union(WorldCollisionBox a, WorldCollisionBox b) {
        return new WorldCollisionBox(
                Math.min(a.minX(), b.minX()),
                Math.min(a.minY(), b.minY()),
                Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()),
                Math.max(a.maxY(), b.maxY()),
                Math.max(a.maxZ(), b.maxZ()));
    }
}
