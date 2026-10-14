package ac.cult.cultac.utils.data;

/**
 * Slowed client ticks paid against the real time the server spent using items, in client ticks.
 * Positive is debt. Credit only comes from slowed ticks the client actually claimed beyond the
 * server's use (its acknowledgement tail), and is capped so it cannot bank a skip allowance.
 */
public final class ItemUseBurstBalance {
    private double maxCredit = 2;
    private double maxDebt = 11;
    private double balance;

    public void configure(double maxCredit, double maxDebt) {
        if (!Double.isFinite(maxCredit) || maxCredit < 0 || !Double.isFinite(maxDebt) || maxDebt < 0)
            throw new IllegalArgumentException("Invalid item-use burst balance");
        this.maxCredit = maxCredit;
        this.maxDebt = maxDebt;
        balance = Math.max(-maxCredit, balance);
    }

    /** A finished use. Returns true when the debt exceeded the limit; the flag settles it. */
    public boolean add(double debt) {
        balance = Math.max(-maxCredit, balance + debt);
        if (balance <= maxDebt) return false;
        balance = 0;
        return true;
    }

    /** An unfinished use's proven debt. Returns true, settling it, only when it already exceeds the limit. */
    public boolean settleIfExceeded(double provisionalDebt) {
        if (balance + provisionalDebt <= maxDebt) return false;
        balance = 0;
        return true;
    }

    public double balance() {
        return balance;
    }

    public double maxDebt() {
        return maxDebt;
    }

    public void reset() {
        balance = 0;
    }
}
