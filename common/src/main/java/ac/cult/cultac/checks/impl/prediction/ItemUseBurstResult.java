package ac.cult.cultac.checks.impl.prediction;

/** Post-completion comparison for the statistical policy; it never declares use mandatory. */
public record ItemUseBurstResult(double legalOffset, double slowedOffset) {
    public boolean unslowed(double threshold) {
        return legalOffset <= threshold && slowedOffset > threshold;
    }
}
