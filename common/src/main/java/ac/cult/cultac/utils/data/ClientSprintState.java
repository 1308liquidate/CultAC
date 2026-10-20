package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import org.jetbrains.annotations.Nullable;

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
    // The minecraft:sprinting movement_speed modifier the client holds: the server's until setSprinting replaces it.
    private @Nullable AttributeModifier modifier;
    // An earlier possible attack reset that aiStep silently re-sprinted may also have replaced it with vanilla's.
    private boolean canonicalPossible;
    // This tick's possible attack reset: travel carries vanilla's modifier exactly when Player#attack damped it.
    private boolean resetOutcomePending;
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

    /**
     * Which outcome of this tick's possible attack reset a travel candidate follows. Player#causeExtraKnockback damps
     * deltaMovement by 0.6 and calls setSprinting(false) together, so a damped candidate travels with the modifier
     * aiStep's re-sprint adds and an undamped one with the modifier held before the attack.
     */
    public enum Outcome {
        ANY,
        KEPT,
        RESET
    }

    /** Entity shared flag 3 from self entity data, applied by its bundled proof. */
    public void onServerFlag(boolean sprinting) {
        flag = sprinting;
    }

    /** The minecraft:sprinting modifier of the server's movement_speed snapshot, applied by its bundled proof. */
    public void onServerModifier(@Nullable AttributeModifier sprinting) {
        modifier = sprinting;
        canonicalPossible = false;
        resetOutcomePending = false;
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
            modifier = null;
            canonicalPossible = false;
            resetOutcomePending = false;
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
        if (flag != wasSprinting) {
            modifier = wasSprinting ? CompensatedEntities.SPRINTING_SPEED_MODIFIER : null;
            canonicalPossible = false;
            resetOutcomePending = false;
        } else if (sprintResetThisTick && wasSprinting) {
            // Only a possible reset leaves the flag: Player#attack resets only after damage and positive knockback.
            resetOutcomePending = !CompensatedEntities.SPRINTING_SPEED_MODIFIER.equals(modifier);
        }
        flag = wasSprinting;
        sprintResetThisTick = false;
        flagUncertain = false;
    }

    /**
     * The move after this tick's possible attack reset is committed. {@code reset} and {@code kept} say whether an
     * accepted travel candidate followed {@link Outcome#RESET} or {@link Outcome#KEPT}; the modifier is the one the
     * accepted candidates prove, or either while candidates of both outcomes, or none, are accepted.
     */
    public void onAttackOutcome(boolean reset, boolean kept) {
        if (!resetOutcomePending) return;
        resetOutcomePending = false;
        if (reset && !kept) {
            modifier = CompensatedEntities.SPRINTING_SPEED_MODIFIER;
            canonicalPossible = false;
        } else if (reset || !kept) {
            canonicalPossible = true;
        }
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
            modifier = null;
            canonicalPossible = false;
            resetOutcomePending = false;
        }
    }

    /** ClientPacketListener#handleLogin creates a fresh LocalPlayer after Minecraft#clearClientLevel. */
    public void reset() {
        flag = false;
        modifier = null;
        canonicalPossible = false;
        resetOutcomePending = false;
        flagBeforeAiStep = false;
        aiStepReconciled = false;
        sprintResetThisTick = false;
        flagUncertain = false;
    }

    /** Entity#isSprinting as the client currently holds it. */
    public boolean flag() {
        return flag;
    }

    /** Whether the client's movement_speed may currently carry minecraft:sprinting. */
    public boolean modifier() {
        return modifier != null || canonicalPossible || resetOutcomePending;
    }

    /**
     * The minecraft:sprinting modifier the client's movement_speed carries unless a possible attack reset replaced it,
     * or null.
     */
    public @Nullable AttributeModifier heldModifier() {
        return modifier;
    }

    /** Whether an earlier possible attack reset may have replaced {@link #heldModifier} with vanilla's modifier. */
    public boolean canonicalPossible() {
        return canonicalPossible;
    }

    /** Whether this tick's travel follows a possible attack reset's {@link Outcome}s until its move is committed. */
    public boolean resetOutcomePending() {
        return resetOutcomePending;
    }
}
