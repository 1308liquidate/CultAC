package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.protocol.ClientVersion;
import org.junit.jupiter.api.Test;

class ItemUseInputTest {
    @Test
    void sneakingAndItemUseBothScaleInputIncludingCustomEffects() {
        for (var version : new ClientVersion[] {
            ClientVersion.V_1_21_2,
            ClientVersion.V_1_21_4,
            ClientVersion.V_1_21_5,
            ClientVersion.V_1_21_11,
            ClientVersion.V_26_3
        }) {
            float axis = version.isOlderThan(ClientVersion.V_1_21_5) ? (0.3F * 0.8F) * 0.98F : (0.98F * 0.8F) * 0.3F;
            assertEquals(0, ItemUseInput.offset(axis, axis, 1, 0.8F, 0.3F, version), 1e-8);
            assertTrue(ItemUseInput.offset(0.98F * 0.3F, 0, 1, 0.8F, 0.3F, version) > 0.05);
        }
    }

    @Test
    void legacyItemUseDiagonalsAreNotNormalizedBeforeTheSmallInputScale() {
        double axis = (0.98F * 0.2F) * 0.1F;
        assertEquals(0, ItemUseInput.offset(axis, axis, 0.1F, 0.2F), 1e-8);
        assertTrue(ItemUseInput.offset(0.098, 0, 0.1F, 0.2F) > 0.07);
    }

    @Test
    void squareAndCircleProjectionReturnsTheClosestIntersection() {
        double axis = 0.98F;
        double other = Math.sqrt(1 - axis * axis);
        double expected = Math.hypot(10 - axis, 0.5 - other);
        assertEquals(expected, ItemUseInput.offset(10, 0.5, 1, 1), 1e-10);
        assertEquals(0, ItemUseInput.offset(1 / Math.sqrt(2), 1 / Math.sqrt(2), 1, 1), 1e-8);
    }

    @Test
    void noInputAndZeroMovementSpeedNeedNoItemUseExemption() {
        assertEquals(0, ItemUseInput.offset(0, 0, 0, 0));
        assertEquals(0.1, ItemUseInput.offset(0.1, 0, 0, 0));
    }
}
