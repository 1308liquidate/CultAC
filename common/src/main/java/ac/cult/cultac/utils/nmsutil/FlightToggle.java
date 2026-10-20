package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.data.PacketStateData;

/**
 * LocalPlayer#aiStep's double-tap flight toggle: a jump key going down while Player#jumpTriggerTime is armed by a
 * press within the previous 7 ticks. {@link PacketStateData#jumpTriggerTimes} holds each timer value the client
 * can have at its next toggle check, bit t for value t; the tick's player_abilities rules out the values it
 * contradicts.
 */
public final class FlightToggle {
    private static final int UNARMED = 1;
    private static final int PRESSED = 1 << 7;

    private FlightToggle() {}

    /** The jump key went down this client tick; LocalPlayer#aiStep reads the previous tick's keys as wasJumping. */
    public static boolean freshJumpPress(PacketStateData data) {
        return data.knownInput.jump() && !data.lastTickJump;
    }

    /** This client tick's keys toggle flight in LocalPlayer#aiStep unless the press is blocked. */
    public static boolean canToggle(CultPlayer player) {
        return pressReachesToggle(player) && (player.packetStateData.jumpTriggerTimes & ~UNARMED) != 0;
    }

    /** Runs the toggle's timer update and Player#aiStep's decrement for a client tick LocalPlayer#aiStep ran. */
    public static void onClientTick(CultPlayer player) {
        PacketStateData data = player.packetStateData;
        int times = data.jumpTriggerTimes;
        if (pressReachesToggle(player)) {
            times = afterPress(player, times);
        }
        data.jumpTriggerTimes = (times >>> 1) | (times & UNARMED);
        data.lastTickJump = data.knownInput.jump();
        data.autoJumpPossible = autoJumpCanFire(player);
    }

    /** A new LocalPlayer starts unarmed, without an auto-jump, with its input at Input.EMPTY. */
    public static void reset(PacketStateData data) {
        data.jumpTriggerTimes = UNARMED;
        data.lastTickJump = false;
        data.autoJumpPossible = false;
    }

    private static int afterPress(CultPlayer player, int times) {
        PacketStateData data = player.packetStateData;
        boolean armed = (times & ~UNARMED) != 0;
        // Only the toggle sends a flight start on a press, and it needs an armed timer.
        if (armed && data.flyingStartBeforeTravel) return UNARMED;

        boolean blocked = data.autoJumpPossible || swimmingPossible(player) || player.inVehicle();
        int next = 0;
        if ((times & UNARMED) != 0) {
            next |= PRESSED;
            if (data.autoJumpPossible) next |= UNARMED;
        }
        if (armed) {
            // A flight stop is the toggle, or LocalPlayer#aiStep landing after a blocked toggle.
            if (data.flightStopThisClientTick) next |= UNARMED;
            if (blocked) next |= times & ~UNARMED;
        }
        // A press no vanilla timer explains leaves the client unarmed until it presses again.
        return next == 0 ? UNARMED : next;
    }

    // Entity#baseTick updates swimming before aiStep, from the fluids at the tick's start; Player#updateSwimming
    // clears it in flight, and Entity#updateSwimming needs water.
    private static boolean swimmingPossible(CultPlayer player) {
        PacketStateData data = player.packetStateData;
        boolean flyingAtToggle = data.flightStopThisClientTick || player.isFlying && !data.flyingStartBeforeTravel;
        if (flyingAtToggle) return false;
        PredictionResult prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        return prediction == null
                || prediction.getSimulationContext().getWorldData().getInWater().getStates().contains(true);
    }

    // LocalPlayer#move runs updateAutoJump after the move; canAutoJump needs the player on the ground, the shift
    // key up, no vehicle and movement keys that don't cancel out. The client option and the step ahead stay open.
    private static boolean autoJumpCanFire(CultPlayer player) {
        KnownInput input = player.packetStateData.knownInput;
        return player.checkManager.getSimulationProcessor().getLastOnGround().getStates().contains(true)
                && !input.shift()
                && !player.inVehicle()
                && (input.forwardImpulse() != 0 || input.strafeImpulse() != 0);
    }

    // LocalPlayer#aiStep checks the jump press only with Abilities#mayfly and outside spectator.
    private static boolean pressReachesToggle(CultPlayer player) {
        return player.canFly && player.gamemode != GameMode.SPECTATOR && freshJumpPress(player.packetStateData);
    }
}
