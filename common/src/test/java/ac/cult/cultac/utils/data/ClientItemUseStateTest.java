package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.ItemUtil;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientItemUseStateTest {
    @Test
    void completionRequiresTheSameOffhandStackObjectUntilTheNextEntityTick() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food);
        assertTrue(state.canCompleteOffhand(food));
        var replacement = food.copy();
        assertFalse(state.canCompleteOffhand(replacement));
        state.prepare(0, 3, ClientVersion.V_1_21_2, SimItemStack.EMPTY, replacement, hotbar(SimItemStack.EMPTY));
        assertTrue(state.canCompleteOffhand(replacement));
    }

    private static SimItemStack food() {
        return ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
    }

    private static List<SimItemStack> hotbar(SimItemStack stack) {
        return Collections.nCopies(9, stack);
    }

    @Test
    void everySupportedClientKeepsConsumptionActiveUntilTheServerCompletion() {
        for (ClientVersion version : ClientVersion.values()) {
            if (version.isOlderThan(ClientVersion.V_1_21_2)) continue;
            var state = new ClientItemUseState();
            var food = food();
            state.start(Hand.OFF_HAND, food);
            for (int tick = 0; tick < 100; tick++) {
                var snapshot = state.prepare(tick, 4, version, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY));
                assertEquals(0.2F, snapshot.legalMultiplier(), 1e-6);
                assertTrue(snapshot.required());
                assertTrue(snapshot.definitelyUsing());
            }
            state.release(); // Player.handleEntityEvent(9), not a guessed client duration.
            assertFalse(state.prepare(100, 5, version, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY))
                    .required());
        }
    }

    @Test
    void unreportedDifferentItemCanStopLocallyWithoutForgivingTheUseObligation() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.MAIN_HAND, food);
        var snapshot = state.prepare(
                0, 3, ClientVersion.V_1_21_2, food, SimItemStack.EMPTY, List.of(food, SimItemStack.EMPTY));
        assertEquals(1, snapshot.legalMultiplier());
        assertEquals(0.2F, snapshot.requiredMultiplier());
        assertTrue(snapshot.required());
        assertFalse(snapshot.definitelyUsing());
        // The silently stopped candidate survives after scroll restores the original slot.
        assertEquals(
                1,
                state.prepare(1, 3, ClientVersion.V_1_21_2, food, SimItemStack.EMPTY, hotbar(food))
                        .legalMultiplier());
    }

    @Test
    void sameItemSlotChangeOnlyClosesTheServerMainHandObligation() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.MAIN_HAND, food);
        state.claimedSlotChanged();
        var snapshot = state.prepare(0, 3, ClientVersion.V_1_21_2, food, SimItemStack.EMPTY, hotbar(food));
        assertTrue(snapshot.definitelyUsing());
        assertEquals(0.2F, snapshot.legalMultiplier());
        assertFalse(snapshot.required());
    }

    @Test
    void mainHandSlotChangesDoNotReleaseOffhandUse() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food);
        state.claimedSlotChanged();
        assertTrue(state.prepare(0, 3, ClientVersion.V_1_21_2, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY))
                .required());
    }

    @Test
    void repeatedMetadataDoesNotReplaceAnAlreadyActiveLocalHand() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food);
        state.serverFlags(true, Hand.MAIN_HAND, food, food, hotbar(food));
        assertEquals(Hand.OFF_HAND, state.hand());
        assertTrue(state.prepare(0, 3, ClientVersion.V_1_21_2, food, food, hotbar(food))
                .definitelyUsing());
    }

    @Test
    void sprintPermissionIsIndependentOfTheSpeedMultiplierAndVersion() {
        var food = food();
        food.components(food.components()
                .with(
                        "minecraft:use_effects",
                        JsonParser.parseString("{\"can_sprint\":true,\"speed_multiplier\":0.1}")));
        var state = new ClientItemUseState();
        state.start(Hand.OFF_HAND, food);
        var modern = state.prepare(0, 1, ClientVersion.V_1_21_11, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY));
        assertTrue(modern.canSprint());
        assertEquals(0.1F, modern.legalMultiplier());
        var legacy = state.prepare(1, 1, ClientVersion.V_1_21_2, SimItemStack.EMPTY, food, hotbar(SimItemStack.EMPTY));
        assertFalse(legacy.canSprint());
        assertEquals(0.2F, legacy.legalMultiplier());
    }

    @Test
    void releaseAfterMovementCannotRetroactivelyClearTheTravelSnapshot() {
        var state = new ClientItemUseState();
        var food = food();
        state.start(Hand.OFF_HAND, food);
        var before = state.prepare(0, 1, ClientVersion.V_1_21_2, food, food, hotbar(food));
        state.release();
        assertSame(before, state.prepare(0, 2, ClientVersion.V_1_21_2, food, food, hotbar(food)));
        assertFalse(state.prepare(1, 2, ClientVersion.V_1_21_2, food, food, hotbar(food))
                .required());
    }

    @Test
    void brushCallbackCanReleaseWithoutSendingTheGameModeReleasePacket() {
        var brush = ItemUtil.modelItems().stack("minecraft:brush", 1);
        var state = new ClientItemUseState();
        state.start(Hand.OFF_HAND, brush);
        var snapshot = state.prepare(0, 1, ClientVersion.V_1_21_2, food(), brush, hotbar(food()));
        assertEquals(1, snapshot.legalMultiplier());
        assertTrue(snapshot.required());
    }
}
