package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ItemUseDesyncHistoryTest {
    @Test
    void exactTwentyPercentIsAllowedButTheNextDesyncExceedsIt() {
        var history = new ItemUseDesyncHistory();
        for (int tick = 0; tick < 80; tick++) history.add(false);
        for (int tick = 0; tick < 20; tick++) {
            assertFalse(history.wouldExceed(true));
            history.add(true);
        }
        assertEquals(100, history.size());
        assertEquals(20, history.desynced());
        assertTrue(history.wouldExceed(true));
    }

    @Test
    void earlyUseDoesNotGetAnInventedFullWindowOfCompliantTicks() {
        var history = new ItemUseDesyncHistory();
        assertTrue(history.wouldExceed(true));
        for (int tick = 0; tick < 4; tick++) history.add(false);
        assertFalse(history.wouldExceed(true));
        history.add(true);
        assertTrue(history.wouldExceed(true));
    }

    @Test
    void reloadAndWindowResizeRetainTheRecentHistory() {
        var history = new ItemUseDesyncHistory();
        for (int tick = 0; tick < 80; tick++) history.add(false);
        for (int tick = 0; tick < 20; tick++) history.add(true);
        history.configure(50, 20);
        assertEquals(20, history.desynced());
        assertEquals(50, history.size());
        assertTrue(history.wouldExceed(true));
        history.configure(100, 50);
        assertFalse(history.wouldExceed(true));
    }
}
