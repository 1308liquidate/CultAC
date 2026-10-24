package ac.cult.cultac.checks.impl.prediction.stage;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * FireworkRocketEntity#tick: each rocket attached to the local player, while LivingEntity#isFallFlying, sets its
 * deltaMovement to {@code v + look * 0.1 + (look * 1.5 - v) * 0.5} with the player's getLookAngle(). ClientLevel
 * #tickEntities ticks in EntityTickList's insertion order and the local player is added before any rocket, so the
 * rockets tick after the LocalPlayer: the boost lands on the velocity the player's tick stored, with the rotation and
 * the fall flying flag that tick ended with, and the next tick starts from it. Packets handled between the ticks
 * (a server velocity, an explosion, a shared flags update) come after the boost.
 */
public final class FireworkBoost {
    private FireworkBoost() {}

    /**
     * How many attached rockets can have boosted the velocity this tick starts from: none unless the last tick ended
     * fall flying. LivingEntity#travelFallFlying's stopFallFlying on a climbable runs inside that tick, before the
     * rockets tick.
     */
    public static int boostsBefore(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        int rockets = player.compensatedFireworks.getMaxFireworksAppliedPossible();
        if (rockets == 0) return 0;
        SimulationContext last = lastResult == null ? null : lastResult.getSimulationContext();
        boolean fallFlying = last == null ? context.isGliding() : endedFallFlying(last);
        return fallFlying ? rockets : 0;
    }

    private static boolean endedFallFlying(SimulationContext last) {
        // A passenger's travel does not carry its own deltaMovement.
        return last.getVehicle() == null && last.isGliding() && !last.stopsFallFlyingAfterTravel();
    }

    /** getLookAngle() when the rockets tick: the rotation the last tick ended with. */
    public static Vector3dm look(CultPlayer player, SimulationContext context) {
        return ReachUtils.getLook(player, context.getLastXRot(), context.getLastYRot());
    }

    public static Vec3 boost(Vec3 velocity, Vector3dm look) {
        return velocity.add(
                look.getX() * 0.1 + (look.getX() * 1.5 - velocity.x) * 0.5,
                look.getY() * 0.1 + (look.getY() * 1.5 - velocity.y) * 0.5,
                look.getZ() * 0.1 + (look.getZ() * 1.5 - velocity.z) * 0.5);
    }

    /**
     * The carried velocities with 0..N rocket boosts, for a tick that does not glide (the shared flags update that
     * ends the glide, a fluid or a climbable). A gliding tick's boost belongs to ElytraTransform, which orders it with
     * the bubble column and riptide effects around it.
     */
    public static Set<Vec3> carryIntoTravel(
            CultPlayer player, SimulationContext context, PredictionResult lastResult, Set<Vec3> velocities) {
        if (context.getVehicle() != null || glidesThroughElytraTransform(context)) return velocities;
        int boosts = boostsBefore(player, context, lastResult);
        if (boosts == 0) return velocities;
        Vector3dm look = look(player, context);
        Set<Vec3> boosted = new LinkedHashSet<>(velocities.size() * (boosts + 1));
        for (Vec3 velocity : velocities) {
            boosted.add(velocity);
            Vec3 carried = velocity;
            for (int i = 0; i < boosts; i++) {
                carried = boost(carried, look);
                boosted.add(carried);
            }
        }
        return boosted;
    }

    /** Mirrors ElytraTransform#skipsContext. */
    private static boolean glidesThroughElytraTransform(SimulationContext context) {
        return context.usesFallFlyingMovement()
                && (context.getWorldData() == null || !context.getWorldData().mustBeInLiquid());
    }
}
