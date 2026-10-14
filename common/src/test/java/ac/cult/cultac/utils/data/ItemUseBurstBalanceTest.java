package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ItemUseBurstBalanceTest {
    @Test
    void oneLateFrameAtTheEndOfAUseDoesNotFlag() {
        var balance = new ItemUseBurstBalance();
        // Minecraft#runTick runs at most 10 due ticks after the stop packet, plus rounding.
        assertFalse(balance.add(10.9));
        assertEquals(10.9, balance.balance(), 1e-9);
    }

    @Test
    void repeatedUnpaidUsesAccumulateUntilFlagged() {
        var balance = new ItemUseBurstBalance();
        assertFalse(balance.add(6));
        assertTrue(balance.add(6));
        // The flag settles the debt it reported.
        assertEquals(0, balance.balance(), 1e-9);
    }

    @Test
    void surplusSlowedTicksRepayDebtButCreditIsCapped() {
        var balance = new ItemUseBurstBalance();
        assertFalse(balance.add(8));
        assertFalse(balance.add(-3));
        assertEquals(5, balance.balance(), 1e-9);
        for (int i = 0; i < 20; i++) balance.add(-3);
        assertEquals(-2, balance.balance(), 1e-9);
        // Banked credit cannot pay for a skipped use.
        assertTrue(balance.add(14));
    }

    @Test
    void provisionalDebtIsSettledOnlyWhenItAlreadyExceeds() {
        var balance = new ItemUseBurstBalance();
        balance.add(5);
        assertFalse(balance.settleIfExceeded(6));
        assertEquals(5, balance.balance(), 1e-9);
        assertTrue(balance.settleIfExceeded(6.5));
        assertEquals(0, balance.balance(), 1e-9);
    }

    @Test
    void reloadPreservesDebtAndClampsCredit() {
        var balance = new ItemUseBurstBalance();
        balance.add(-2);
        balance.configure(1, 20);
        assertEquals(-1, balance.balance(), 1e-9);
        balance.add(15);
        assertFalse(balance.add(5.5));
        assertThrows(IllegalArgumentException.class, () -> balance.configure(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> balance.configure(1, Double.NaN));
    }
}
