package ac.cult.cultac.manager.tick;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackendTickEstimatorTest {
    private static final long TICK = 50_000_000L;

    private static final class Fixture {
        int fired;
        long period = TICK;
        final List<long[]> timers = new ArrayList<>(); // {delay, cancelled}
        final List<Runnable> tasks = new ArrayList<>();
        final BackendTickEstimator estimator = new BackendTickEstimator(
                () -> fired++,
                (task, delay) -> {
                    long[] handle = {delay, 0};
                    timers.add(handle);
                    tasks.add(task);
                    return () -> handle[1] = 1;
                },
                () -> period);

        /** The most recently scheduled timer, unless it was cancelled. */
        Runnable liveTimer() {
            if (timers.isEmpty() || timers.getLast()[1] != 0) return null;
            return tasks.getLast();
        }

        long enterTimerMode() {
            long now = 0;
            estimator.onGameTime(0, now);
            for (int window = 1; window <= 2; window++) {
                for (int tick = 0; tick < 20; tick += 2) {
                    estimator.onEntityTracked(7, now);
                    estimator.onEntityTracked(7, now);
                    now += 2 * TICK;
                    estimator.onBurstEnd(false, now);
                }
                estimator.onGameTime(window * 20L, now);
            }
            assertEquals(BackendTickEstimator.Mode.TIMER, estimator.mode());
            estimator.resetTickEndStats();
            return now;
        }
    }

    @Test
    void everyCompleteBurstEndsATickAndASplitReadDoesNot() {
        var f = new Fixture();
        f.estimator.onBurstEnd(true, 0);
        assertEquals(0, f.fired);
        f.estimator.onBurstEnd(false, 1);
        f.estimator.onBurstEnd(false, TICK);
        assertEquals(2, f.fired);
        assertEquals(BackendTickEstimator.Mode.FLUSH, f.estimator.mode());
    }

    @Test
    void aSecondEntityWaveOrTimeSyncInOneBurstSplitsTheMergedTick() {
        var f = new Fixture();
        f.estimator.onEntityTracked(1, 0);
        f.estimator.onEntityTracked(2, 0);
        assertEquals(0, f.fired);
        f.estimator.onEntityTracked(1, 0);
        assertEquals(1, f.fired);
        // The time synchronization starts a tick; the previous wave belongs to the last one.
        f.estimator.onGameTime(100, 0);
        assertEquals(2, f.fired);
        f.estimator.onGameTime(120, 0);
        assertEquals(2, f.fired);
    }

    @Test
    void tickEndsWithoutTrackingEvidenceDoNotInflateTheHitRate() {
        var f = new Fixture();
        f.estimator.onBurstEnd(false, 0);
        assertEquals(0, f.estimator.tickEndStats().observedWaves());
        assertTrue(Double.isNaN(f.estimator.tickEndStats().hitPercent()));
        assertTrue(Double.isNaN(f.estimator.tickEndStats().missPercent()));

        f.estimator.onEntityTracked(1, 1);
        f.estimator.onEntityTracked(2, 2);
        f.estimator.onBurstEnd(true, 3);
        assertEquals(0, f.estimator.tickEndStats().observedWaves(), "a partial read cannot finish the wave");
        f.estimator.onBurstEnd(false, TICK);
        f.estimator.onBurstEnd(false, 2 * TICK);

        assertEquals(new BackendTickEstimator.TickEndStats(1, 0, 3), f.estimator.tickEndStats());
        assertEquals(100, f.estimator.tickEndStats().hitPercent());
        assertEquals(0, f.estimator.tickEndStats().missPercent());
    }

    @Test
    void lateCorrectiveSplitsCountMissedWavesWithoutCountingTheCorrectionAsAHit() {
        var f = new Fixture();
        for (int tick = 0; tick < 3; tick++) {
            f.estimator.onEntityTracked(1, tick * TICK);
            f.estimator.onEntityTracked(2, tick * TICK);
        }
        f.estimator.onBurstEnd(false, 3 * TICK);

        var stats = f.estimator.tickEndStats();
        assertEquals(new BackendTickEstimator.TickEndStats(1, 2, 3), stats);
        assertEquals(3, f.fired, "diagnostics do not change corrective tick ends");
        assertEquals(100D / 3, stats.hitPercent(), 1e-9);
        assertEquals(200D / 3, stats.missPercent(), 1e-9);
    }

    @Test
    void aTimeSyncDetectsAnUnendedWaveEvenWithoutARepeatedEntity() {
        var f = new Fixture();
        f.estimator.onEntityTracked(1, 0);
        f.estimator.onGameTime(100, TICK);
        f.estimator.onGameTime(120, 21 * TICK);
        assertEquals(new BackendTickEstimator.TickEndStats(0, 1, 1), f.estimator.tickEndStats());

        f.estimator.onEntityTracked(2, 22 * TICK);
        f.estimator.onBurstEnd(false, 23 * TICK);
        assertEquals(new BackendTickEstimator.TickEndStats(1, 1, 2), f.estimator.tickEndStats());
    }

    @Test
    void timerModeKeepsUnendedWavesAcrossBurstsThatDoNotFireATickEnd() {
        var f = new Fixture();
        long now = f.enterTimerMode();
        int before = f.fired;
        f.estimator.onEntityTracked(1, now);
        // Far from the timer deadline: the estimator clears its burst wave but emits no tick end.
        f.estimator.onBurstEnd(false, now + 1_000_000L);
        f.estimator.onEntityTracked(1, now + 2_000_000L);
        assertEquals(before, f.fired);
        assertEquals(new BackendTickEstimator.TickEndStats(0, 1, 0), f.estimator.tickEndStats());

        f.liveTimer().run();
        assertEquals(new BackendTickEstimator.TickEndStats(1, 1, 1), f.estimator.tickEndStats());
        assertEquals(50, f.estimator.tickEndStats().hitPercent());
        f.liveTimer().run();
        assertEquals(new BackendTickEstimator.TickEndStats(1, 1, 2), f.estimator.tickEndStats());
    }

    @Test
    void timeSyncAlsoDetectsTimerModeWavesClearedByTheBurstEstimator() {
        var f = new Fixture();
        long now = f.enterTimerMode();
        f.estimator.onEntityTracked(1, now);
        f.estimator.onBurstEnd(false, now + 1_000_000L);
        f.estimator.onGameTime(60, now + 20 * TICK);
        assertEquals(new BackendTickEstimator.TickEndStats(0, 1, 0), f.estimator.tickEndStats());
    }

    @Test
    void resettingDiagnosticsDoesNotResetEstimationOrItsPendingTimer() {
        var f = new Fixture();
        long now = f.enterTimerMode();
        f.estimator.onEntityTracked(1, now);
        var timer = f.liveTimer();
        int before = f.fired;
        f.estimator.resetTickEndStats();
        assertSame(timer, f.liveTimer());
        assertEquals(BackendTickEstimator.Mode.TIMER, f.estimator.mode());
        assertEquals(before, f.fired);

        // The pre-reset unfinished wave is not part of the new measurement.
        timer.run();
        assertEquals(new BackendTickEstimator.TickEndStats(0, 0, 1), f.estimator.tickEndStats());
        f.estimator.onEntityTracked(1, now + TICK);
        f.liveTimer().run();
        assertEquals(new BackendTickEstimator.TickEndStats(1, 0, 2), f.estimator.tickEndStats());
    }

    @Test
    void frequentMergesSwitchToAPhaseLockedTimerAndCleanWindowsSwitchBack() {
        var f = new Fixture();
        long now = 0;
        long gameTime = 0;
        f.estimator.onGameTime(gameTime, now);
        for (int window = 0; window < 2; window++) {
            for (int tick = 0; tick < 20; tick += 2) {
                // Two ticks per burst: every second tick needs a merge split.
                f.estimator.onEntityTracked(7, now);
                f.estimator.onEntityTracked(7, now);
                now += 2 * TICK;
                f.estimator.onBurstEnd(false, now + 3_000_000L);
            }
            gameTime += 20;
            f.estimator.onGameTime(gameTime, now);
        }
        assertEquals(BackendTickEstimator.Mode.TIMER, f.estimator.mode());
        Runnable timer = f.liveTimer();
        assertNotNull(timer);
        int before = f.fired;
        timer.run();
        assertEquals(before + 1, f.fired);
        assertNotNull(f.liveTimer());

        for (int window = 0; window < 3; window++) {
            for (int tick = 0; tick < 20; tick++) {
                f.estimator.onEntityTracked(7, now);
                now += TICK;
                f.estimator.onBurstEnd(false, now);
            }
            gameTime += 20;
            f.estimator.onGameTime(gameTime, now);
        }
        assertEquals(BackendTickEstimator.Mode.FLUSH, f.estimator.mode());
        assertNull(f.liveTimer());
    }

    @Test
    void anObservedTickEndReplacesTheTimerFiringInsteadOfDoublingIt() {
        var f = new Fixture();
        long now = 0;
        long gameTime = 0;
        f.estimator.onGameTime(gameTime, now);
        for (int window = 0; window < 2; window++) {
            for (int tick = 0; tick < 20; tick += 2) {
                f.estimator.onEntityTracked(7, now);
                f.estimator.onEntityTracked(7, now);
                now += 2 * TICK;
                f.estimator.onBurstEnd(false, now);
            }
            gameTime += 20;
            f.estimator.onGameTime(gameTime, now);
        }
        assertEquals(BackendTickEstimator.Mode.TIMER, f.estimator.mode());
        int before = f.fired;
        int timers = f.timers.size();
        // The timer is phase-locked to the burst ends, so the next one is due one tick later.
        assertEquals(TICK, f.timers.get(timers - 1)[0]);
        f.estimator.onBurstEnd(false, now + TICK);
        assertEquals(before + 1, f.fired);
        assertEquals(1, f.timers.get(timers - 1)[1], "the replaced timer is cancelled");
    }

    @Test
    void aFrozenBackendNeverFiresTheTimer() {
        var f = new Fixture();
        long now = 0;
        long gameTime = 0;
        f.estimator.onGameTime(gameTime, now);
        for (int window = 0; window < 2; window++) {
            for (int tick = 0; tick < 20; tick += 2) {
                f.estimator.onEntityTracked(7, now);
                f.estimator.onEntityTracked(7, now);
                now += 2 * TICK;
            }
            gameTime += 20;
            f.period = window == 1 ? 0 : TICK;
            f.estimator.onGameTime(gameTime, now);
        }
        int before = f.fired;
        f.liveTimer().run();
        assertEquals(before, f.fired);
        f.estimator.close();
        assertEquals(1, f.timers.getLast()[1], "closing cancels the pending re-check");
    }
}
