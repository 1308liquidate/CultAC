package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.data.PacketStateData;

/**
 * LocalPlayer#aiStep's double-tap flight toggle: a jump key going down while Player#jumpTriggerTime is armed by a
 * press within the previous 7 ticks. {@link PacketStateData#jumpTriggerTimes} holds each timer value the client
 * can have at its next toggle check, bit t for value t.
 */
public final class FlightToggle {
    private static final int UNARMED = 1;
    private static final int PRESSED = 1 << 7;

    private FlightToggle() {}

    /** The jump key went down this client tick; LocalPlayer#aiStep reads the previous tick's keys as wasJumping. */
    public static boolean freshJumpPress(PacketStateData data) {
        return data.knownInput.jump() && !data.lastTickJump;
    }

    /** This client tick's keys can toggle flight in LocalPlayer#aiStep. */
    public static boolean canToggle(CultPlayer player) {
        return pressReachesToggle(player) && (player.packetStateData.jumpTriggerTimes & ~UNARMED) != 0;
    }

    /** The client's timer can be unarmed at this client tick's toggle check. */
    public static boolean unarmedPossible(PacketStateData data) {
        return (data.jumpTriggerTimes & UNARMED) != 0;
    }

    /** Runs the toggle's timer update and Player#aiStep's decrement for a client tick LocalPlayer#aiStep ran. */
    public static void onClientTick(CultPlayer player) {
        PacketStateData data = player.packetStateData;
        int times = data.jumpTriggerTimes;
        if (pressReachesToggle(player)) {
            // An auto-jump press (a client option), swimming or an unjumpable vehicle leaves every value as it is.
            int next = times;
            if ((times & UNARMED) != 0) next |= PRESSED;
            if ((times & ~UNARMED) != 0 && data.abilitiesThisClientTick) next |= UNARMED;
            times = next;
        }
        data.jumpTriggerTimes = (times >>> 1) | (times & UNARMED);
        data.lastTickJump = data.knownInput.jump();
    }

    /** A new LocalPlayer starts unarmed with its input at Input.EMPTY. */
    public static void reset(PacketStateData data) {
        data.jumpTriggerTimes = UNARMED;
        data.lastTickJump = false;
    }

    // LocalPlayer#aiStep checks the jump press only with Abilities#mayfly and outside spectator.
    private static boolean pressReachesToggle(CultPlayer player) {
        return player.canFly && player.gamemode != GameMode.SPECTATOR && freshJumpPress(player.packetStateData);
    }
}
