package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/**
 * The movement input a tick's keys add. KeyboardInput#tick builds the move vector from the keys the
 * client sends in PLAYER_INPUT, LocalPlayer#modifyInput scales it, and LivingEntity#travel adds it through
 * Entity#moveRelative and Entity#getInputVector.
 */
public final class ClientKeyInput {
    private static final List<Boolean> NO = List.of(false);
    private static final List<Boolean> YES = List.of(true);
    private static final List<Boolean> EITHER = List.of(false, true);

    private ClientKeyInput() {}

    /**
     * LivingEntity#moveRelative's addition for {@code keys}: KeyboardInput#tick, LocalPlayer#modifyInput with
     * {@code itemUseMultiplier} and {@code movingSlowlyFactor}, and Entity#getInputVector at {@code speed}.
     * LocalPlayer#aiStep computes crouching from the previous keys before Input#tick. Null when a pose the context does
     * not determine could slow the input.
     */
    public static Vec3 travelInput(
            CultPlayer player,
            SimulationContext context,
            KnownInput keys,
            float itemUseMultiplier,
            float movingSlowlyFactor,
            float speed) {
        // LivingEntity#aiStep clears xxa/zza after applyInput when health <= 0. PLAYER_INPUT still reports the
        // held keys. Use receipt-proven health, not the legacy isDead flag which may precede the health packet.
        if (!player.compensatedEntities.getSelf().actionAlive) return Vec3.ZERO;
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

        Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                new Vec3(keys.strafeImpulse(), 0.0D, keys.forwardImpulse()), itemUseMultiplier, movingSlowlyFactor);
        return inputVector(context, modified, speed);
    }

    /**
     * LocalPlayer#modifyInput's moving-slowly multipliers: LocalPlayer#isMovingSlowly is crouching or
     * Entity#isVisuallyCrawling. Visually crawling is LivingEntity#isVisuallySwimming out of water: a SWIMMING pose, or
     * a FALL_FLYING pose without fall flying. LocalPlayer#aiStep crouches a player whose previous keys held shift, not
     * while Entity#isSwimming, which this tick's Entity#baseTick already updated, and only where the crouching pose
     * fits blocks and entities (Player#canPlayerFitWithinBlocksAndEntitiesWhen); Player#updatePlayerPose sets the pose
     * only after LocalPlayer#aiStep.
     */
    public static float[] movingSlowlyFactors(CultPlayer player, SimulationContext context) {
        float sneaking =
                (float) player.compensatedEntities.getSelf().sneakingSpeed.value();
        WorldData world = context.getWorldData();
        boolean visuallySwimming = context.getPose() == Pose.SWIMMING || context.getPose() == Pose.FALL_FLYING;
        List<Boolean> crawling = visuallySwimming ? not(world.getInWater().getStates()) : NO;

        List<Boolean> crouching = NO;
        if (context.isLastSneaking() && !player.isFlying && context.getVehicle() == null) {
            Vec3 from = context.getStart();
            List<Boolean> fits = player.getClientVersion().isOlderThan(ClientVersion.V_1_14)
                            || player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z)
                    ? YES
                    : player.mayPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z)
                            ? EITHER
                            : NO;
            // WorldData#isSwimming holds whenever the client may swim; only an unknown water state leaves it open.
            List<Boolean> swimming =
                    !world.isSwimming() ? NO : world.getInWater().isDesync() ? EITHER : YES;
            boolean crouches = fits.contains(true) && swimming.contains(false);
            boolean standsUp = fits.contains(false) || swimming.contains(true);
            crouching = crouches ? standsUp ? EITHER : YES : NO;
        }

        boolean slow = crawling.contains(true) || crouching.contains(true);
        boolean full = crawling.contains(false) && crouching.contains(false);
        if (slow && full) return new float[] {sneaking, 1.0F};
        return slow ? new float[] {sneaking} : new float[] {1.0F};
    }

    private static List<Boolean> not(List<Boolean> states) {
        return states.size() == 2 ? EITHER : states.getFirst() ? NO : YES;
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
}
