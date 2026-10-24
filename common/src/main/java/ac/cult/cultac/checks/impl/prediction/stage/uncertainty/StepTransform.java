package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;

public class StepTransform implements UncertaintyHandler {
    /**
     * Piston and shulker shoves are their own Entity#move calls (MoverType.PISTON/SHULKER), outside LivingEntity#travel.
     * The travel's Entity#collide steps by at most maxUpStep from where it starts, so the step bounds the travel's own
     * movement, and the shove displacement CollisionModifier already re-applied is added on top of it.
     */
    @Override
    public MovementTrace handleMovementTrace(
            CultPlayer player,
            ValidMovements valid,
            PredictionResult result,
            SimulationContext context,
            PredictionResult lastResult,
            MovementTrace trace,
            Vec3 end) {
        Vec3 shove = trace.collisionBaseOffset();
        if (shove.lengthSqr() <= 1.0E-14) {
            return UncertaintyHandler.super.handleMovementTrace(player, valid, result, context, lastResult, trace, end);
        }
        PredVector position = trace.position();
        PredVector travel = position.with(position.subtract(shove), "external movement collision base removed");
        PredVector stepped = handleUncertainty(player, valid, result, context, lastResult, travel, end.subtract(shove));
        if (stepped == travel) return trace;
        return trace.withPosition(stepped.add(shove, "external movement collision base"));
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
        // The player isn't allowed to step here
        if (!valid.isCanStep()) return start;

        double maxUpStep = start.maxUpStep(player);
        if (maxUpStep <= 0.0F) {
            return start;
        }

        PredVector.StepCandidate step = start.stepCandidate(end, result.getCollideAxisData());
        PredVector candidateStep = step.exact();
        if (candidateStep != null) {
            return candidateStep.distanceToSqr(end) < start.distanceToSqr(end) ? candidateStep : start;
        }
        if (!step.boundedFallback()) {
            return start;
        }

        return boundedCandidate(
                        start, end, maxUpStep, result.getSimulationContext().getTargetScalarVert(), "step")
                .markBoundedStep();
    }

    public static PredVector boundedCandidate(
            PredVector start, Vec3 end, double maxUpStep, double verticalScale, String reason) {
        double clampedStep = CultMath.clamp(end.y, Math.min(0, start.y), maxUpStep * verticalScale);
        return start.withY(clampedStep, reason);
    }
}
