package ac.cult.cultac.utils.data;

/** Source-derived post-proof tick budget. A proof cannot make earlier silence spendable again. */
public final class ItemUseBurstClock {
    private static final int MAX_FRAME_TICKS = 10; // Minecraft.runTick's Math.min(10, elapsedTicks).
    private long balance = Long.MIN_VALUE;
    private long pendingProof = Long.MIN_VALUE;
    private long previousPeriod;

    public void proof(long sentAt) {
        pendingProof = Math.max(pendingProof, sentAt);
    }

    public boolean tick(long now, long period) {
        // runTick calculates elapsedTicks BEFORE processing the rate packet.
        // A slower confirmed rate can therefore follow at most ten ticks already
        // scheduled at the old rate. Align that one batch; never reset the clock.
        if (balance != Long.MIN_VALUE && previousPeriod > 0 && period > previousPeriod)
            balance -= MAX_FRAME_TICKS * (period - previousPeriod);
        previousPeriod = period;
        if (pendingProof != Long.MIN_VALUE) {
            balance = Math.max(balance, pendingProof - MAX_FRAME_TICKS * period);
            pendingProof = Long.MIN_VALUE;
        }
        if (balance == Long.MIN_VALUE) return false;
        // DeltaTracker uses Util.getMillis, so its fractional tick boundary may
        // precede this nanosecond clock by less than one millisecond.
        long next = balance + period;
        if (next > now + 999_999L) return true;
        balance = next;
        return false;
    }

    public void reset() {
        balance = pendingProof = Long.MIN_VALUE;
        previousPeriod = 0;
    }
}
