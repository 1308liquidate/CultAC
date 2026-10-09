package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientSprintStateTest {
    private static ClientSprintState sprinting() {
        ClientSprintState state = new ClientSprintState();
        // LocalPlayer#aiStep -> setSprinting(true), then START_SPRINTING.
        state.onClientTick(true);
        assertTrue(state.flag());
        assertTrue(state.modifier());
        return state;
    }

    @Test
    void silentResprintAfterServerResetRestoresModifier() {
        ClientSprintState state = sprinting();
        // Server-side Player#causeExtraKnockback echoes sprint=false in entity data and attributes.
        state.onServerFlag(false);
        state.onServerModifier(false);
        assertFalse(state.flag());
        // aiStep re-sprints; wasSprinting is still true, so no START_SPRINTING is sent.
        state.onClientTick(true);
        assertTrue(state.flag());
        assertTrue(state.modifier());
    }

    @Test
    void staleServerStartIsStoppedByAiStep() {
        ClientSprintState state = sprinting();
        state.onClientTick(false);
        assertFalse(state.modifier());
        // The server's echo of the earlier START_SPRINTING arrives after the client stopped.
        state.onServerFlag(true);
        state.onServerModifier(true);
        // shouldStopRunSprinting -> setSprinting(false); wasSprinting is already false.
        state.onClientTick(false);
        assertFalse(state.flag());
        assertFalse(state.modifier());
    }

    @Test
    void attributeOnlyUpdateKeepsFlagUntilNextTransition() {
        ClientSprintState state = sprinting();
        // A movement_speed sync while the server believes the player is not sprinting.
        state.onServerModifier(false);
        state.onClientTick(true);
        assertTrue(state.flag());
        assertFalse(state.modifier());

        state.onClientTick(false);
        state.onClientTick(true);
        assertTrue(state.modifier());
    }

    @Test
    void attributeOnlyModifierWithoutSprintFlag() {
        ClientSprintState state = new ClientSprintState();
        state.onServerModifier(true);
        state.onClientTick(false);
        assertFalse(state.flag());
        assertTrue(state.modifier());
    }

    @Test
    void attackResetThatAiStepResprintedRestoresModifier() {
        ClientSprintState state = sprinting();
        // A movement_speed sync while the server believes the player is not sprinting.
        state.onServerModifier(false);
        state.onClientTick(true);
        assertFalse(state.modifier());
        // Player#causeExtraKnockback -> setSprinting(false), then aiStep re-sprints without a packet.
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        state.onClientTick(true);
        assertTrue(state.flag());
        assertTrue(state.modifier());
    }

    @Test
    void attackWithoutResprintEndsWithoutModifier() {
        ClientSprintState state = sprinting();
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        // The reset was not re-sprinted, so LocalPlayer sent STOP_SPRINTING.
        state.onClientTick(false);
        assertFalse(state.flag());
        assertFalse(state.modifier());
        // The attack only bounds the tick it happened in.
        state.onServerModifier(true);
        state.onClientTick(false);
        assertTrue(state.modifier());
    }

    @Test
    void attackThatCannotResetSprintKeepsDesyncedModifier() {
        ClientSprintState state = sprinting();
        state.onServerModifier(false);
        state.onClientTick(true);
        // A mob hit or a weak hit never reaches Player#causeExtraKnockback's setSprinting(false).
        state.onAttack(ClientSprintState.AttackReset.NONE);
        state.onClientTick(true);
        assertTrue(state.flag());
        assertFalse(state.modifier());
    }

    @Test
    void certainResetFollowsTheClientExactly() {
        ClientSprintState state = sprinting();
        state.onServerModifier(false);
        state.onClientTick(true);
        state.onAttack(ClientSprintState.AttackReset.CERTAIN);
        assertTrue(state.flagKnown());
        assertFalse(state.flag());
        // aiStep re-sprinted without a packet: setSprinting(true) re-added the modifier.
        state.onClientTick(true);
        assertTrue(state.modifier());
    }

    @Test
    void possibleResetMakesLaterAttacksUncertain() {
        ClientSprintState state = sprinting();
        state.onAttack(ClientSprintState.AttackReset.POSSIBLE);
        assertFalse(state.flagKnown());
        state.onClientTick(true);
        assertTrue(state.flagKnown());
    }

    @Test
    void respawnFollowsKeepMask() {
        ClientSprintState keepAll = sprinting();
        keepAll.onRespawn(true, true);
        assertTrue(keepAll.flag());
        assertTrue(keepAll.modifier());

        ClientSprintState keepEntityData = sprinting();
        keepEntityData.onRespawn(true, false);
        assertTrue(keepEntityData.flag());
        assertFalse(keepEntityData.modifier());

        ClientSprintState keepAttributes = sprinting();
        keepAttributes.onRespawn(false, true);
        assertFalse(keepAttributes.flag());
        assertTrue(keepAttributes.modifier());

        ClientSprintState keepNothing = sprinting();
        keepNothing.onRespawn(false, false);
        assertFalse(keepNothing.flag());
        assertFalse(keepNothing.modifier());
    }
}
