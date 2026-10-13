package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ItemUseBurstClockTest {
    private static final long TICK = 50_000_000L;
    private static final long SENT = 10_000_000_000L;

    @Test
    void vanillaTenTickCatchupIsAllowedButCompletionCannotFundThirtyTwoFutureTicks() {
        var clock = new ItemUseBurstClock();
        clock.proof(SENT);
        for (int i = 0; i < 10; i++) assertFalse(clock.tick(SENT, TICK));
        assertTrue(clock.tick(SENT, TICK));
    }

    @Test
    void networkBufferedTicksFitTheTimeSinceTheProofWasSent() {
        var clock = new ItemUseBurstClock();
        clock.proof(SENT);
        for (int i = 0; i < 32; i++) assertFalse(clock.tick(SENT + 32 * TICK, TICK));
    }

    @Test
    void repeatedProofsNeverRenewSpentCatchupCredit() {
        var clock = new ItemUseBurstClock();
        clock.proof(SENT);
        for (int i = 0; i < 10; i++) assertFalse(clock.tick(SENT, TICK));
        clock.proof(SENT);
        assertTrue(clock.tick(SENT, TICK));
    }

    @Test
    void aNewCompletionProofDiscardsTheBudgetFromEarlierSilence() {
        var clock = new ItemUseBurstClock();
        clock.proof(SENT - 32 * TICK);
        assertFalse(clock.tick(SENT - 32 * TICK, TICK));
        clock.proof(SENT);
        for (int i = 0; i < 10; i++) assertFalse(clock.tick(SENT, TICK));
        assertTrue(clock.tick(SENT, TICK));
    }

    @Test
    void slowServerTicksAndMillisecondResidualDoNotProduceFalseBursts() {
        var clock = new ItemUseBurstClock();
        clock.proof(SENT);
        for (int i = 0; i < 10; i++) assertFalse(clock.tick(SENT, TICK * 2));
        assertFalse(clock.tick(SENT + TICK * 2 - 999_999, TICK * 2));
    }

    @Test
    void aSlowerRateKeepsTheTenTicksScheduledBeforePacketProcessing() {
        var clock = new ItemUseBurstClock();
        clock.proof(SENT);
        for (int i = 0; i < 10; i++) assertFalse(clock.tick(SENT, TICK));
        // Minecraft advanced DeltaTracker at 20 TPS, then processed the 5 TPS packet.
        for (int i = 0; i < 10; i++) assertFalse(clock.tick(SENT + 10 * TICK, 4 * TICK));
        assertTrue(clock.tick(SENT + 10 * TICK, 4 * TICK));
    }

    @Test
    void rejectedBurstTicksDoNotPreventLegitimateRecovery() {
        var clock = new ItemUseBurstClock();
        clock.proof(SENT);
        for (int i = 0; i < 10; i++) assertFalse(clock.tick(SENT, TICK));
        for (int i = 0; i < 100; i++) assertTrue(clock.tick(SENT, TICK));
        assertFalse(clock.tick(SENT + TICK, TICK));
    }
}
