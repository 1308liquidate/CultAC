package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ItemUseBurstBalanceTest {
    private static final long PERIOD = 50_000_000L;
    private long now = 10_000_000_000L;
    private int tick;

    private void compliant(ItemUseBurstBalance balance, int count) {
        for (int i = 0; i < count; i++) {
            now += PERIOD;
            assertFalse(balance.tick(tick++, now, PERIOD, false, true));
        }
    }

    private boolean partialBurst(ItemUseBurstBalance balance, int count, boolean unslowed) {
        now += count * PERIOD;
        balance.complete(now, 0.2F);
        boolean corrected = false;
        for (int i = 0; i < count; i++) corrected |= balance.tick(tick++, now, PERIOD, unslowed, false);
        return corrected;
    }

    @Test
    void repeatedEightTickPartialBurstsAccumulateAcrossUses() {
        var balance = new ItemUseBurstBalance();
        for (int use = 1; use <= 5; use++) {
            compliant(balance, 24);
            assertEquals(use == 5, partialBurst(balance, 8, true));
            // The preceding silence pays for one paced arrival, leaving seven
            // burst ticks; 24 compliant ticks earn six ticks of credit.
            assertEquals(use, balance.balance(), 1e-9);
        }
    }

    @Test
    void occasionalCatchupAndNormallyPacedPostUseMovementDoNotSetBack() {
        var balance = new ItemUseBurstBalance();
        compliant(balance, 24);
        assertFalse(partialBurst(balance, 8, true));
        assertEquals(1, balance.balance(), 1e-9);
        compliant(balance, 32);
        balance.complete(now, 0.8F);
        for (int i = 0; i < 10; i++) {
            now += PERIOD;
            assertFalse(balance.tick(tick++, now, PERIOD, true, false));
        }
        assertEquals(-7, balance.balance(), 1e-9);
    }

    @Test
    void batchedMovementWhichFitsTheSlowedBoundDoesNotSpendCredit() {
        var balance = new ItemUseBurstBalance();
        assertFalse(partialBurst(balance, 8, false));
        assertEquals(0, balance.balance());
    }

    @Test
    void idleTimeAndAnotherCompletionDoNotRefillTheBalance() {
        var balance = new ItemUseBurstBalance();
        assertTrue(partialBurst(balance, 8, true));
        double debt = balance.balance();
        now += 60_000 * PERIOD;
        balance.complete(now, 0.2F);
        assertFalse(balance.tick(tick++, now, PERIOD, false, false));
        assertEquals(debt, balance.balance());
        assertTrue(balance.tick(tick++, now, PERIOD, true, false));
        assertEquals(debt + 1, balance.balance());
    }

    @Test
    void creditIsCappedAndReloadPreservesDebt() {
        var balance = new ItemUseBurstBalance();
        compliant(balance, 1000);
        assertEquals(-8, balance.balance());
        balance.configure(2, 4, 20);
        assertEquals(-2, balance.balance());
        assertTrue(partialBurst(balance, 8, true));
        double debt = balance.balance();
        balance.configure(2, 3, 20);
        assertEquals(debt, balance.balance());
        balance.reset();
        assertEquals(0, balance.balance());
    }

    @Test
    void completionCannotChangeAnAlreadyPreparedTravelTick() {
        var balance = new ItemUseBurstBalance();
        assertEquals(1, balance.multiplier(tick, now, PERIOD));
        balance.complete(now, 0.8F);
        assertEquals(1, balance.multiplier(tick, now, PERIOD));
        assertEquals(0.8F, balance.multiplier(++tick, now, PERIOD));
        for (int i = 0; i < 10; i++) balance.tick(tick++, now, PERIOD, false, false);
        assertEquals(1, balance.multiplier(tick, now, PERIOD));
        balance.complete(now, 0.2F);
        now += 11 * PERIOD;
        assertEquals(1, balance.multiplier(++tick, now, PERIOD));
    }

    @Test
    void oneClientTickCannotBeDebitedTwice() {
        var balance = new ItemUseBurstBalance();
        partialBurst(balance, 4, true);
        double debt = balance.balance();
        assertFalse(balance.tick(tick - 1, now, PERIOD, true, false));
        assertEquals(debt, balance.balance());
    }

    @Test
    void fullPercentageDisablesStatisticalDebt() {
        var balance = new ItemUseBurstBalance();
        balance.configure(8, 4, 100);
        for (int use = 0; use < 10; use++) assertFalse(partialBurst(balance, 8, true));
        assertEquals(0, balance.balance());
    }
}
