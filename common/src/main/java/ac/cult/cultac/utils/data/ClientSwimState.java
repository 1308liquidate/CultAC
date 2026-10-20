package ac.cult.cultac.utils.data;

/**
 * The Java client's Entity#isSwimming. Entity#updateSwimming recomputes it in every Entity#baseTick from the local
 * sprint flag, eye and fluid state, so the client swims a round trip before the server's shared flag says so.
 *
 * <ul>
 *   <li>ClientPacketListener#handleSetEntityData overwrites it with the server's shared flag 4 between ticks.
 *   <li>Player#updatePlayerPose reads it at the end of the tick; the server never sends the local player its pose.
 * </ul>
 *
 * Where the engine's fluid or eye state is uncertain the model keeps the client swimming.
 */
public final class ClientSwimState {
    private boolean swimming;
    private boolean swimmingPose;

    /** Entity shared flag 4 from self entity data, applied by its bundled proof. */
    public void onServerFlag(boolean swimming) {
        this.swimming = swimming;
    }

    /** A client tick's Entity#updateSwimming result, which its Player#updatePlayerPose also read. */
    public void onClientTick(boolean swimming) {
        this.swimming = swimming;
        this.swimmingPose = swimming;
    }

    /** ClientPacketListener#handleRespawn copies the old shared flags and pose only for KEEP_ENTITY_DATA. */
    public void onRespawn(boolean keepEntityData) {
        if (!keepEntityData) reset();
    }

    /** ClientPacketListener#handleLogin creates a fresh LocalPlayer. */
    public void reset() {
        swimming = false;
        swimmingPose = false;
    }

    /** Entity#isSwimming as the next Entity#updateSwimming reads it. */
    public boolean swimming() {
        return swimming;
    }

    /** Whether the last Player#updatePlayerPose wanted the swimming pose because the client swam. */
    public boolean swimmingPose() {
        return swimmingPose;
    }
}
