package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.TrigHandler;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.util.ArrayList;
import java.util.List;

/**
 * TridentItem#releaseUsing runs in Minecraft#handleKeybinds, before the tick's LivingEntity#aiStep. {@link #release}
 * adds its push to the velocities the tick starts from; its grounded Entity#move is position only, so the handlers
 * before this one aim at the target without it and this one adds it.
 */
public class Riptide implements UncertaintyHandler {
    public static final String PUSH = "riptide push";
    public static final String GROUND_LIFT = "riptide ground lift";
    private static final float LIFT = 1.1999999F;

    @Override
    public boolean skipsContext(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
        return context.getRiptideLevel() == 0;
    }

    @Override
    public PredVector handleUncertainty(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            PredVector start,
            Vec3 end) {
        return handleMovementTrace(player, valid, result, context, lastContext, MovementTrace.start(start), end)
                .position();
    }

    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastContext,
            MovementTrace trace,
            Vec3 end) {
        if (context.getRiptideLevel() == 0) return trace;
        PredVector start = trace.position();
        if (!lifted(context, start)) return trace;
        Vec3 lift = new Vec3(0, context.getRiptideGroundLift(), 0);
        return trace.withPosition(start.add(lift, GROUND_LIFT)).addPositionOnlyDelta(lift);
    }

    /** The target of the handlers before this one: {@code end} without the grounded release's position-only move. */
    static Vec3 movementTarget(SimulationContext context, PredVector velocity, Vec3 end) {
        return lifted(context, velocity) ? end.subtract(0, context.getRiptideGroundLift(), 0) : end;
    }

    private static boolean lifted(SimulationContext context, PredVector velocity) {
        return velocity.hasReason(GROUND_LIFT) && context.getRiptideLevel() != 0;
    }

    /** Whether {@code velocity} carries this tick's push, which leaves the tick's LivingEntity#aiStep airborne. */
    public static boolean pushed(SimulationContext context, PredVector velocity) {
        return pushOf(context, velocity) != null;
    }

    /** This tick's push on {@code velocity}, or null without one. */
    static Pushed pushOf(SimulationContext context, PredVector velocity) {
        return context.getRiptideLevel() == 0 ? null : velocity.firstInLineage(Pushed.class);
    }

    /** A velocity carrying this tick's push, and the velocity the push was added to. */
    static final class Pushed extends PredVector {
        final PredVector carried;
        final Vec3 push;

        private Pushed(PredVector carried, Vec3 push) {
            super(carried.add(push), carried, PUSH);
            this.carried = carried;
            this.push = push;
        }
    }

    /**
     * The velocities TridentItem#releaseUsing leaves: Entity#push along the rotation this tick's move packet sends,
     * then for a player on ground Entity#move(SELF, (0, 1.1999999F, 0)).
     */
    public static List<PredVector> release(CultPlayer player, SimulationContext context, List<PredVector> velocities) {
        if (context.getRiptideLevel() == 0 || context.getVehicle() != null) return velocities;
        float strength = 3.0F * ((1.0F + context.getRiptideLevel()) / 4.0F);
        List<Vec3> pushes = new ArrayList<>(2);
        for (int fastMath = 0; fastMath < 2; fastMath++) {
            Vec3 push = push(context.getTrig(), context.getXRot(), context.getYRot(), strength);
            if (!pushes.contains(push)) pushes.add(push);
            context.getTrig().toggleShitMath();
        }
        List<Boolean> onGround = context.getWorldData().getCarriedOnGround().getStates();
        if (onGround.contains(true)) context.setRiptideGroundLift(groundLift(player, context));

        List<PredVector> released = new ArrayList<>();
        for (Vec3 push : pushes) {
            for (PredVector velocity : velocities) {
                PredVector pushed = new Pushed(velocity, push);
                if (onGround.contains(false)) released.add(pushed);
                if (!onGround.contains(true)) continue;
                for (Vec3 moved : afterLift(player, context, pushed)) {
                    released.add(pushed.with(moved, GROUND_LIFT));
                }
            }
        }
        return released;
    }

    private static Vec3 push(TrigHandler trig, float yRot, float xRot, float strength) {
        float xd = -trig.sin(yRot * (float) (Math.PI / 180.0)) * trig.cos(xRot * (float) (Math.PI / 180.0));
        float yd = -trig.sin(xRot * (float) (Math.PI / 180.0));
        float zd = trig.cos(yRot * (float) (Math.PI / 180.0)) * trig.cos(xRot * (float) (Math.PI / 180.0));
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        xd *= strength / dist;
        yd *= strength / dist;
        zd *= strength / dist;
        return new Vec3(xd, yd, zd);
    }

    private static double groundLift(CultPlayer player, SimulationContext context) {
        double lift = Collisions.collide(player, context.getFromActualPose(), 0, LIFT, 0).y;
        // Entity#move keeps the position for a collided movement whose squared length is at most 1.0E-7.
        return lift * lift > 1.0E-7 ? lift : 0;
    }

    /**
     * The velocity after the lift's Entity#move: a lift that collides runs Block#updateEntityMovementAfterFallOn of
     * the block at Entity#getOnPosLegacy, then LivingEntity#getBlockSpeedFactor scales X and Z.
     */
    private static List<Vec3> afterLift(CultPlayer player, SimulationContext context, Vec3 velocity) {
        double lift = context.getRiptideGroundLift();
        Vec3 position = context.getStart().add(0, lift, 0);
        List<Vec3> moved = new ArrayList<>(2);
        if (lift == LIFT) {
            moved.add(velocity);
        } else {
            int state = player.compensatedWorld.getBlockStateIdAt(
                    CultMath.floor(position.x), CultMath.floor(position.y - 0.2F), CultMath.floor(position.z));
            boolean slime = BlockIds.is(state, BlockIds.SLIME_BLOCK);
            boolean bed = ClientBlockProperties.isBed(state);
            moved.add(new Vec3(velocity.x, 0, velocity.z));
            if (slime || bed) {
                moved.add(
                        velocity.y < 0
                                ? new Vec3(velocity.x, -velocity.y * (slime ? 1.0F : 0.66F), velocity.z)
                                : velocity);
            }
        }
        // Entity#checkSupportingBlock clears the supporting block of a move that ends off the ground.
        float speedFactor = CultMath.lerp(
                context.getMovementEfficiency(),
                BlockProperties.getBlockSpeedFactor(player, new MainSupportingBlockData(null, false), position),
                1.0F);
        if (speedFactor == 1.0F) return moved;
        List<Vec3> slowed = new ArrayList<>(moved.size());
        for (Vec3 vector : moved) slowed.add(vector.multiply(speedFactor, 1.0, speedFactor));
        return slowed;
    }
}
