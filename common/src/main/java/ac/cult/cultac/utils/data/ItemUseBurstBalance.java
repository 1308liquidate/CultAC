package ac.cult.cultac.utils.data;

/**
 * Statistical movement policy, never proof of cheating. Repeated unslowed catch-up movement
 * spends credit earned from compliant item-use ticks, rather than receiving a new allowance per use.
 */
public final class ItemUseBurstBalance {
    private static final int MAX_FRAME_TICKS = 10;
    private double balance;
    private double maxCredit = 8;
    private double setbackDebt = 4;
    private double recovery = 0.25;
    private long lastArrival = Long.MIN_VALUE;
    private long completedAt;
    private int remainingTicks;
    private float multiplier = 1;
    private int preparedTick = Integer.MIN_VALUE;
    private float preparedMultiplier = 1;
    private int finalizedTick = Integer.MIN_VALUE;

    public void configure(double maxCredit, double setbackDebt, double percent) {
        if (!Double.isFinite(maxCredit)
                || maxCredit < 0
                || !Double.isFinite(setbackDebt)
                || setbackDebt < 0
                || !Double.isFinite(percent)
                || percent < 0
                || percent > 100) throw new IllegalArgumentException("Invalid item-use burst balance");
        this.maxCredit = maxCredit;
        this.setbackDebt = setbackDebt;
        recovery = percent == 100 ? Double.POSITIVE_INFINITY : percent / (100 - percent);
        balance = Math.max(-maxCredit, Math.min(balance, setbackDebt + MAX_FRAME_TICKS));
    }

    public void complete(long now, float multiplier) {
        if (multiplier >= 1) return;
        completedAt = now;
        remainingTicks = MAX_FRAME_TICKS;
        this.multiplier = multiplier;
        // A release/completion opens an observation window, not a new credit balance.
        // Keep the preceding arrival too: the first tick of each use gets no special refill.
    }

    public float multiplier(int tick, long now, long period) {
        if (preparedTick != tick) {
            preparedTick = tick;
            preparedMultiplier = remainingTicks > 0 && now - completedAt <= MAX_FRAME_TICKS * period ? multiplier : 1;
        }
        return preparedMultiplier;
    }

    public boolean tick(int tick, long now, long period, boolean unslowed, boolean compliant) {
        if (finalizedTick == tick) return false;
        double charge = unslowed ? charge(tick, now, period) : 0;
        finalizedTick = tick;
        if (compliant) balance = Math.max(-maxCredit, balance - recovery);
        balance = Math.min(setbackDebt + MAX_FRAME_TICKS, balance + charge);
        lastArrival = now;
        if (remainingTicks > 0) remainingTicks--;
        return charge > 0 && balance > setbackDebt;
    }

    public boolean wouldSetback(int tick, long now, long period) {
        double charge = finalizedTick == tick ? 0 : charge(tick, now, period);
        return charge > 0 && balance + charge > setbackDebt;
    }

    private double charge(int tick, long now, long period) {
        if (recovery == Double.POSITIVE_INFINITY || multiplier(tick, now, period) >= 1 || lastArrival == Long.MIN_VALUE)
            return 0;
        // Wall time can pay for at most one normally paced arrival. Silence cannot
        // bank permission for a subsequent batch of unslowed movements.
        return Math.max(0, 1 - Math.max(0, now - lastArrival) / (double) period);
    }

    public double balance() {
        return balance;
    }

    public double setbackDebt() {
        return setbackDebt;
    }

    public void reset() {
        balance = 0;
        lastArrival = Long.MIN_VALUE;
        remainingTicks = 0;
        multiplier = preparedMultiplier = 1;
        preparedTick = finalizedTick = Integer.MIN_VALUE;
    }
}
