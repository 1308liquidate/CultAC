package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import org.junit.jupiter.api.Test;

/** One FireworkRocketEntity boosts its attached glider once per tick, however often its metadata is re-sent. */
class CompensatedFireworksTest {
    @Test
    void reSentAttachedMetadataCountsTheRocketOnce() {
        CompensatedFireworks fireworks = new CompensatedFireworks(mock(CultPlayer.class));
        fireworks.addNewFirework(7);
        fireworks.addNewFirework(7);
        fireworks.addNewFirework(8);

        assertEquals(2, fireworks.getMaxFireworksAppliedPossible());

        fireworks.removeFirework(7);
        fireworks.onPredictionComplete(mock(PredictionComplete.class));

        assertEquals(1, fireworks.getMaxFireworksAppliedPossible());
    }
}
