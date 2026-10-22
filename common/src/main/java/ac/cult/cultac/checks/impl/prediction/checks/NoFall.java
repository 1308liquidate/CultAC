package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.NoFallPseudo;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.NumFormatter;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;

public class NoFall implements EngineCheck {
    private static final VanillaStepProof STEP_PROOF = new VanillaStepProof();

    @Override
    public void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        BedrockPredictionResult bedrockResult = result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult != null) {
            handleBedrockResult(player, result, bedrockResult.verticalCollisionVerdict());
            return;
        }

        CollideAxisData.CollideResult downCollide = result.getCollideAxisData().getYNeg();
        boolean canCollideDown = downCollide != null && downCollide.isLikelyCollide();

        if (result.getSimulationContext().getVehicle() != null) return;

        boolean flaggedNoFall = false;
        final NoFallPseudo noFallPseudo = player.checkManager.getListener(NoFallPseudo.class);

        if (!canCollideDown
                && result.getSimulationContext().isOnGround()
                && !STEP_PROOF.canStandAtDestination(player, result)) {
            result.addFlag(noFallPseudo, () -> "true", 0.1);
            flaggedNoFall = true;
        }

        SimpleCollisionBox valid = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        double stuckSpeedY = result.getSimulationContext().getLastStuckSpeed().y;
        // Every Entity#move sets onGround from its own collision, so the travel lands by its own displacement:
        // the reported movement minus the position-only shove.
        double travelY = result.getTarget().y - result.getPositionOnlyDelta().y / stuckSpeedY;
        // The player is off the ground and can collide to the ground
        if (!result.getSimulationContext().isOnGround()
                && canCollideDown
                && valid.maxY + 0.001 < travelY // And their valid Y is below their actual movement
                && downCollide.inMovementSpace(stuckSpeedY) + 0.001 >= travelY) { // And they recovered by colliding
            result.addFlag(noFallPseudo, () -> "false", 0.1);
            flaggedNoFall = true;
        }

        double validY = result.getAcceptedClosestToTarget().y;
        double targetY = result.getTarget().y;
        // > 0 resets fall damage, we must protect against this
        if (validY <= 0 && targetY > 0 && !result.getSimulationContext().isOnGround()) {
            result.addFlag(
                    noFallPseudo,
                    () -> NumFormatter.formatNumberStandard(targetY) + " > "
                            + NumFormatter.formatNumberStandard(validY),
                    0.1);
            flaggedNoFall = true;
        }

        if (flaggedNoFall) {
            result.setDesiredOnGround(canCollideDown);
        }
    }

    private static void handleBedrockResult(
            CultPlayer player, PredictionResult result, BedrockVerticalCollisionVerdict verdict) {
        if (verdict == BedrockVerticalCollisionVerdict.MANUFACTURED_COLLISION) {
            result.addFlag(player.checkManager.getListener(NoFallPseudo.class), () -> "manufactured_collision", 0.1D);
        } else if (verdict == BedrockVerticalCollisionVerdict.MANUFACTURED_NON_COLLISION) {
            result.addFlag(
                    player.checkManager.getListener(NoFallPseudo.class), () -> "manufactured_non_collision", 0.1D);
        }
    }
}
