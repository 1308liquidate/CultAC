package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.impl.prediction.ItemUseBurstResult;
import ac.cult.cultac.checks.impl.prediction.ItemUseTickResult;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;

/** Evaluates the existing collision/velocity candidate; it does not run another simulation. */
public final class ItemUseAnalyzer implements EngineCheck {
    @Override
    public void handleResult(CultPlayer player, PredictionResult result, PredictionResult lastResult) {
        var context = result.getSimulationContext();
        var state = context.getItemUse();
        if (!player.usesModernJavaItemUse() || context.getVehicle() != null || context.usesFallFlyingMovement()) return;
        if (state.required() || state.legalMultiplier() < 1) {
            double legal = ItemUseInput.offset(result, state.legalMultiplier());
            double slowed = state.legalMultiplier() == state.requiredMultiplier()
                    ? legal
                    : ItemUseInput.offset(result, state.requiredMultiplier());
            result.setItemUseResult(new ItemUseTickResult(state, legal, slowed));
        } else if (context.getPostUseMultiplier() < 1) {
            // Reuse this collision candidate. This bound is evidence for a probable
            // burst only; it does not change the legal client movement or NoSlow flags.
            result.setItemUseBurstResult(new ItemUseBurstResult(
                    ItemUseInput.offset(result, 1), ItemUseInput.offset(result, context.getPostUseMultiplier())));
        }
    }
}
