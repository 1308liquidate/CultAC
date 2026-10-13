package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.utils.data.ClientItemUseState;

/** Evidence captured at travel, not a later read of server state. Offsets are in blocks. */
public record ItemUseTickResult(ClientItemUseState.Snapshot state, double legalOffset, double slowedOffset) {
    public static final double NUMERIC_EPSILON = 1.0E-5;

    public boolean deterministic(double threshold) {
        return state.legalMultiplier() < 1 && legalOffset > Math.max(NUMERIC_EPSILON, threshold);
    }

    public boolean desynced(double threshold) {
        return state.required()
                && legalOffset <= Math.max(NUMERIC_EPSILON, threshold)
                && slowedOffset > Math.max(NUMERIC_EPSILON, threshold);
    }
}
