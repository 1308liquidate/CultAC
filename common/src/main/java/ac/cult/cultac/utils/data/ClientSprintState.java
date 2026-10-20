package ac.cult.cultac.utils.data;

/**
 * The Java client's sprint flag and sprint speed modifier, which MCP-Reborn writes independently.
 *
 * <ul>
 *   <li>ClientPacketListener#handleSetEntityData assigns the server's shared flags onto the local
 *       player, overwriting only Entity shared flag 3 (Entity#isSprinting).
 *   <li>ClientPacketListener#handleUpdateAttributes calls AttributeInstance#removeModifiers and
 *       re-adds the server's modifiers, overwriting only the minecraft:sprinting speed modifier.
 *   <li>LivingEntity#setSprinting writes the flag and removes/re-adds the modifier together.
 * </ul>
 *
 * The client applies both server packets in Minecraft#runTick's packetProcessor before ticking, so
 * between packet processing and LocalPlayer#aiStep the flag can hold the server's value. The client's
 * last START/STOP_SPRINTING (CultPlayer#isSprinting) is LocalPlayer#wasSprinting.
 */
public final class ClientSprintState {
    private boolean flag;
    private boolean modifier;
    private boolean sprintResetThisTick;
    private boolean flagUncertain;
    private boolean flagBeforeAiStep;
    private boolean aiStepReconciled;

    /** Whether Player#attack's causeExtraKnockback reached setSprinting(false). */
    public enum AttackReset {
        NONE,
        POSSIBLE,
        CERTAIN
    }

    /** Entity shared flag 3 from self entity data, applied by its bundled proof. */
    public void onServerFlag(boolean sprinting) {
        flag = sprinting;
    }

    /** Whether the server's movement_speed snapshot carries minecraft:sprinting, applied by its bundled proof. */
    public void onServerModifier(boolean present) {
        modifier = present;
    }

    /**
     * MultiPlayerGameMode#attack ran Player#attack before this tick's aiStep. When its causeExtraKnockback called
     * setSprinting(false), aiStep can re-sprint without a START_SPRINTING, re-adding the modifier.
     */
    public void onAttack(AttackReset reset) {
        if (reset == AttackReset.NONE) return;
        sprintResetThisTick = true;
        if (reset == AttackReset.CERTAIN) {
            flag = false;
            modifier = false;
        } else {
            flagUncertain = true;
        }
    }

    /** Whether flag() is exact here: no attack earlier in this tick may have reset it unprovably. */
    public boolean flagKnown() {
        return !flagUncertain;
    }

    /**
     * A locally controlled LocalPlayer#tick finished. LocalPlayer#sendIsSprintingIfNeeded makes the
     * flag equal wasSprinting. The flag differing from wasSprinting before aiStep proves aiStep called
     * LivingEntity#setSprinting, which resynchronized the modifier to the flag.
     */
    public void onClientTick(boolean wasSprinting) {
        if (!aiStepReconciled) {
            flagBeforeAiStep = flag;
            aiStepReconciled = true;
        }
        // Unmodeled: wall-sprinting start->stop in one aiStep can clear the modifier early; this only errs lenient.
        // An attack reset that aiStep re-sprinted re-added the modifier.
        if (flag != wasSprinting || sprintResetThisTick && wasSprinting) {
            modifier = wasSprinting;
        }
        flag = wasSprinting;
        sprintResetThisTick = false;
        flagUncertain = false;
    }

    /** A LocalPlayer#tick movement packet opens its tick; the next {@link #onClientTick} reconciles its aiStep. */
    public void onClientMove() {
        aiStepReconciled = false;
    }

    /** The client's tick ended; the next {@link #onClientTick} reconciles the next tick's aiStep. */
    public void onClientTickEnd() {
        aiStepReconciled = false;
    }

    /**
     * Entity#isSprinting as Entity#baseTick read it this tick, before LocalPlayer#aiStep: the previous tick's flag, or
     * the server's flag the client applied since.
     */
    public boolean flagBeforeAiStep() {
        return aiStepReconciled ? flagBeforeAiStep : flag;
    }

    /**
     * ClientPacketListener#handleRespawn. A kept entity-data respawn copies the old player's synched
     * data; a kept-attribute respawn copies its modifiers with AttributeInstance#replaceFrom.
     */
    public void onRespawn(boolean keepEntityData, boolean keepAttributeModifiers) {
        if (!keepEntityData) {
            flag = false;
        }
        if (!keepAttributeModifiers) {
            modifier = false;
        }
    }

    /** ClientPacketListener#handleLogin creates a fresh LocalPlayer after Minecraft#clearClientLevel. */
    public void reset() {
        flag = false;
        modifier = false;
        flagBeforeAiStep = false;
        aiStepReconciled = false;
        sprintResetThisTick = false;
        flagUncertain = false;
    }

    /** Entity#isSprinting as the client currently holds it. */
    public boolean flag() {
        return flag;
    }

    /** Whether the client's movement_speed currently carries minecraft:sprinting. */
    public boolean modifier() {
        return modifier;
    }
}
