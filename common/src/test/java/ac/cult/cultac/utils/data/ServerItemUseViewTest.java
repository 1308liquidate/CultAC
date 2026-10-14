package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerItemUseViewTest {
    private static final long TICK = 50_000_000L;
    private static final ServerItemUseView.Evidence SLOWED = multiplier -> 0;
    private static final ServerItemUseView.Evidence UNSLOWED = multiplier -> multiplier < 1 ? 0.05 : 0;
    private static final ServerItemUseView.Profile FOOD =
            new ServerItemUseView.Profile(0.2F, 32, "minecraft:golden_apple");
    private static final ServerItemUseView.Profile CROSSBOW =
            new ServerItemUseView.Profile(0.2F, 72000, "minecraft:crossbow");

    private static final class Harness {
        final List<ServerItemUseView.Outcome> finished = new ArrayList<>();
        final List<ServerItemUseView.Outcome> progress = new ArrayList<>();
        ServerItemUseView.Profile profile = FOOD;
        boolean settle;
        long now;
        long clock;
        final List<String> trace = new ArrayList<>();
        final ServerItemUseView view = new ServerItemUseView(
                (offhand, captured) -> profile,
                finished::add,
                outcome -> {
                    progress.add(outcome);
                    return settle;
                },
                message -> trace.add(message.get()));

        Harness() {
            view.threshold(1.0E-3);
            view.period(TICK);
        }

        void ticks(int count, ServerItemUseView.Evidence evidence, boolean clockFollows) {
            for (int i = 0; i < count; i++) {
                now += TICK;
                if (clockFollows) clock = now - TICK / 2;
                view.tickEnd(evidence, clock, now);
            }
        }

        void start(int sequence) {
            view.use(false, sequence, now, List.of());
            view.serverFlags(true, false, 1, now + 1_000_000L);
        }

        boolean traced(String fragment) {
            return trace.stream().anyMatch(line -> line.contains(fragment));
        }

        ServerItemUseView.Outcome last() {
            return finished.getLast();
        }
    }

    @Test
    void legitimateConsumptionPaysTheServerUseAndOnlyItsTailIsCredit() {
        var h = new Harness();
        h.start(1);
        h.ticks(32, SLOWED, true);
        h.view.serverComplete(2, h.now, TICK);
        // LocalPlayer stays slowed until it processes entity event 9 and its proof.
        h.ticks(2, SLOWED, true);
        assertTrue(h.finished.isEmpty());
        h.view.acknowledge(2);
        assertEquals(32, h.last().expected(), 1e-9);
        assertEquals(34, h.last().ticks());
        assertEquals(0, h.last().unslowed());
        assertEquals(-2, h.last().debt(), 1e-9);
        assertFalse(h.view.tracking());
    }

    @Test
    void stallingThroughConsumptionLeavesTheWholeServerUseUnpaid() {
        var h = new Harness();
        h.start(1);
        h.now += 32 * TICK;
        h.view.serverComplete(2, h.now, TICK);
        h.view.acknowledge(2);
        assertEquals(32, h.last().expected(), 1e-9);
        assertEquals(0, h.last().ticks());
        assertEquals(32, h.last().debt(), 1e-9);
    }

    @Test
    void theConfiguredChargeScalesTheExpectedTicks() {
        var h = new Harness();
        h.view.charge(0.95);
        h.start(1);
        h.now += 40 * TICK;
        h.view.serverComplete(2, h.now, TICK);
        h.view.acknowledge(2);
        assertEquals(38, h.last().expected(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> h.view.charge(-0.1));
    }

    @Test
    void halfRateTickingPaysHalfTheUse() {
        var h = new Harness();
        h.start(1);
        for (int i = 0; i < 16; i++) {
            h.now += 2 * TICK;
            h.view.tickEnd(SLOWED, 0, h.now);
        }
        h.view.serverComplete(2, h.now, TICK);
        h.view.acknowledge(2);
        assertEquals(16, h.last().debt(), 1e-9);
    }

    @Test
    void unslowedTicksDuringAServerUseAreNotPayment() {
        // The client model can believe it is not using (a desynchronized item); the server still is.
        var h = new Harness();
        h.start(1);
        h.ticks(32, UNSLOWED, true);
        h.view.serverComplete(2, h.now, TICK);
        h.view.acknowledge(2);
        assertEquals(32, h.last().unslowed());
        assertEquals(32, h.last().debt(), 1e-9);
    }

    @Test
    void crossbowChargeIsMeasuredUntilTheReleaseArrives() {
        var cheat = new Harness();
        cheat.profile = CROSSBOW;
        cheat.start(1);
        cheat.now += 25 * TICK;
        cheat.view.release(cheat.now);
        assertEquals(25, cheat.last().debt(), 1e-9);

        var legit = new Harness();
        legit.profile = CROSSBOW;
        legit.start(1);
        legit.ticks(25, SLOWED, true);
        legit.view.release(legit.now);
        assertEquals(0, legit.last().debt(), 1e-9);
    }

    @Test
    void anAcknowledgementWithoutTheFlagRejectsTheUse() {
        var h = new Harness();
        h.view.use(false, 5, h.now, List.of());
        h.ticks(3, UNSLOWED, true);
        h.view.serverAck(5);
        assertFalse(h.view.tracking());
        // A later plugin start is bounded by its own observation, not the rejected packet.
        h.now += 10 * TICK;
        h.view.serverFlags(true, false, 1, h.now);
        h.view.serverFlags(false, false, ServerItemUseView.NO_PROOF, h.now + 4 * TICK);
        assertEquals(4, h.last().expected(), 1e-9);
        assertEquals(0, h.last().ticks());
    }

    @Test
    void anEarlyCompletionEventCannotEndTheCurrentUse() {
        var h = new Harness();
        h.start(1);
        h.ticks(2, SLOWED, true);
        // Far before duration - 1 server ticks: this event belongs to an earlier use.
        h.view.serverComplete(9, h.now, TICK);
        h.view.acknowledge(9);
        assertTrue(h.finished.isEmpty());
        assertNotNull(h.view.activeItem(false));
        h.ticks(30, SLOWED, true);
        h.view.serverComplete(10, h.now, TICK);
        h.view.acknowledge(10);
        assertEquals(1, h.finished.size());
        assertEquals(32, h.last().ticks());
    }

    @Test
    void theClearFollowingAStopNeverEndsTheNextUse() {
        var h = new Harness();
        h.profile = CROSSBOW;
        h.start(1);
        h.ticks(25, SLOWED, true);
        h.view.release(h.now);
        assertEquals(1, h.finished.size());
        // The release's own flag clear, observed after the next use packet arrived.
        h.view.use(false, 2, h.now, List.of());
        h.view.serverFlags(false, false, 3, h.now + 1);
        assertEquals(1, h.finished.size());
        h.view.serverFlags(true, false, 4, h.now + 2);
        h.ticks(10, SLOWED, true);
        assertNotNull(h.view.activeItem(false));
        h.view.release(h.now);
        assertEquals(2, h.finished.size());
        assertEquals(10, h.last().ticks());
    }

    @Test
    void aStartMergedWithThePreviousStopConsumesNoLaterClear() {
        var h = new Harness();
        h.start(1);
        h.ticks(32, SLOWED, true);
        h.view.serverComplete(2, h.now, TICK);
        // ServerPlayer#completeUsingItem and the next use ran before one level tick: only the
        // flag set is sent, so the next clear belongs to the new use.
        h.view.use(false, 2, h.now, List.of());
        assertEquals(1, h.finished.size());
        h.view.serverFlags(true, false, 3, h.now + 1);
        h.ticks(4, SLOWED, true);
        h.view.serverFlags(false, false, 4, h.now);
        h.view.acknowledge(4);
        assertEquals(2, h.finished.size());
        assertEquals(4, h.last().ticks());
    }

    @Test
    void anUnfinishedUseIsSettledAgainstTheProvenClientClock() {
        var h = new Harness();
        h.profile = new ServerItemUseView.Profile(0.2F, 72000, "minecraft:shield");
        h.start(1);
        // Transactions keep being acknowledged while no client tick is claimed.
        h.clock = h.now + 20 * TICK;
        h.settle = true;
        h.view.tickEnd(SLOWED, h.clock, h.now);
        assertEquals(1, h.progress.size());
        assertEquals(20, h.progress.getLast().expected(), 1e-9);
        assertEquals(1, h.progress.getLast().ticks());
        // Settled debt is not charged again at the end.
        h.view.release(h.clock);
        assertEquals(0, h.last().expected(), 1e-9);
        assertEquals(0, h.last().ticks());
    }

    @Test
    void aSlotSwitchEndsMainHandUseAndItsLastLocalTickIsNotDebt() {
        var h = new Harness();
        h.view.selectSlot(0, 0, h.now);
        h.start(1);
        h.ticks(10, SLOWED, true);
        h.ticks(1, UNSLOWED, true);
        h.view.selectSlot(0, 0, h.now);
        assertTrue(h.finished.isEmpty());
        h.view.selectSlot(4, 0, h.now);
        assertEquals(11, h.last().ticks());
        assertEquals(0, h.last().unslowed());
    }

    @Test
    void offhandUseSurvivesSlotSwitchesButNotHandSwaps() {
        var h = new Harness();
        h.view.use(true, 1, h.now, List.of());
        h.view.serverFlags(true, true, 1, h.now);
        h.ticks(3, SLOWED, true);
        h.view.selectSlot(3, 0, h.now);
        assertTrue(h.finished.isEmpty());
        h.view.swapHands(h.now);
        assertEquals(3, h.last().ticks());
    }

    @Test
    void aStopBeforeTheConfirmingFlagClosesTheUseAtThatPacket() {
        var h = new Harness();
        h.profile = CROSSBOW;
        h.view.use(false, 1, h.now, List.of());
        h.ticks(5, SLOWED, true);
        h.view.release(h.now);
        h.ticks(3, UNSLOWED, true);
        h.view.serverFlags(true, false, 1, h.now);
        assertEquals(5, h.last().ticks());
        assertEquals(0, h.last().unslowed());
        assertEquals(5, h.last().expected(), 1e-9);
        // Its clear was owed and is consumed.
        h.view.serverFlags(false, false, 2, h.now);
        assertEquals(1, h.finished.size());
    }

    /** Ticks where the client view holds (or lost) the used item before each tick. */
    private static void ticks(Harness h, int count, boolean holds, ServerItemUseView.Evidence evidence) {
        for (int i = 0; i < count; i++) {
            h.view.clientHolds(holds);
            h.ticks(1, evidence, true);
        }
    }

    @Test
    void aLocalStopConfirmedByTheServerEndsTheUseAfterTheLastHeldTick() {
        var h = new Harness();
        h.start(1);
        ticks(h, 6, true, SLOWED);
        // A click moved the apple out: the client is unslowed until the server's clear.
        ticks(h, 3, false, UNSLOWED);
        assertTrue(h.finished.isEmpty());
        h.view.serverFlags(false, false, 5, h.now);
        assertEquals(6, h.last().ticks());
        assertEquals(0, h.last().unslowed());
        assertEquals(6, h.last().expected(), 1e-9);
        assertFalse(h.view.tracking());
    }

    @Test
    void theServerSendingTheItemBackRefutesALocalStop() {
        var h = new Harness();
        h.start(1);
        ticks(h, 6, true, SLOWED);
        ticks(h, 2, false, UNSLOWED);
        // handleContainerClick corrected the rejected claim; the client does not resume using.
        h.view.serverKeepsItem("server sent slot");
        ticks(h, 24, false, UNSLOWED);
        h.view.serverComplete(2, h.now, TICK);
        h.view.acknowledge(2);
        assertEquals(32, h.last().ticks());
        assertEquals(26, h.last().unslowed());
        assertEquals(26, h.last().debt(), 1e-9);
        assertTrue(h.traced("t=6 client view lost minecraft:golden_apple: tentative local stop at +300.0ms"));
        assertTrue(h.traced("server sent slot: server keeps minecraft:golden_apple, local stop refuted"));
        assertTrue(h.traced("proof 2 acknowledged"));
    }

    @Test
    void aRefutedLocalStopCannotBeConfirmedByALaterStop() {
        var h = new Harness();
        h.profile = new ServerItemUseView.Profile(0.2F, 72000, "minecraft:shield");
        h.start(1);
        ticks(h, 4, true, SLOWED);
        ticks(h, 1, false, UNSLOWED);
        h.view.serverKeepsItem("server sent slot");
        ticks(h, 40, false, UNSLOWED);
        h.view.serverFlags(false, false, 7, h.now);
        h.view.acknowledge(7);
        assertEquals(45, h.last().ticks());
        assertEquals(41, h.last().unslowed());
    }

    @Test
    void completionRefutesALocalStop() {
        var h = new Harness();
        h.start(1);
        ticks(h, 2, true, SLOWED);
        ticks(h, 30, false, UNSLOWED);
        h.view.serverComplete(2, h.now, TICK);
        h.view.acknowledge(2);
        assertEquals(30, h.last().unslowed());
    }

    @Test
    void aClientThatNeverHeldTheUsedItemCannotStopLocally() {
        // A client-view inventory desynchronized before the use: the server uses the item regardless.
        var h = new Harness();
        h.start(1);
        ticks(h, 32, false, UNSLOWED);
        h.view.serverFlags(false, false, 3, h.now);
        h.view.acknowledge(3);
        assertEquals(32, h.last().unslowed());
        assertEquals(32, h.last().debt(), 1e-9);
    }

    @Test
    void regainingTheItemClientSideRefutesALocalStop() {
        var h = new Harness();
        h.start(1);
        ticks(h, 3, true, SLOWED);
        ticks(h, 1, false, UNSLOWED);
        ticks(h, 5, true, UNSLOWED);
        h.view.serverFlags(false, false, 4, h.now);
        h.view.acknowledge(4);
        assertEquals(9, h.last().ticks());
        assertEquals(6, h.last().unslowed());
    }

    @Test
    void aSlotSwitchAfterAConfirmedLocalStopKeepsTheEarlierEnd() {
        var h = new Harness();
        h.view.selectSlot(0, 0, h.now);
        h.start(1);
        ticks(h, 5, true, SLOWED);
        ticks(h, 2, false, UNSLOWED);
        h.view.selectSlot(3, 0, h.now);
        assertEquals(5, h.last().ticks());
        assertEquals(0, h.last().unslowed());
        // Its clear was owed and is consumed.
        h.view.serverFlags(false, false, 9, h.now);
        assertEquals(1, h.finished.size());
    }

    @Test
    void aSlowerTickRateLowersTheExpectation() {
        var h = new Harness();
        h.start(1);
        h.view.period(2 * TICK);
        h.now += 64 * TICK;
        h.view.serverComplete(2, h.now, 2 * TICK);
        h.view.acknowledge(2);
        assertEquals(32, h.last().expected(), 1e-9);
    }

    @Test
    void aUseWithoutSlowdownIsNeverCharged() {
        var h = new Harness();
        h.profile = new ServerItemUseView.Profile(1F, 72000, "minecraft:iron_spear");
        h.start(1);
        h.now += 40 * TICK;
        h.view.release(h.now);
        assertTrue(h.finished.isEmpty());
    }

    @Test
    void usePacketsWhileTheServerIsUsingAreIgnored() {
        var h = new Harness();
        h.start(1);
        h.ticks(4, SLOWED, true);
        h.view.use(true, 2, h.now, List.of());
        h.view.serverAck(2);
        h.ticks(28, SLOWED, true);
        h.view.serverComplete(3, h.now, TICK);
        h.view.acknowledge(3);
        assertEquals(32, h.last().ticks());
    }
}
