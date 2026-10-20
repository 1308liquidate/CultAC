package ac.cult.cultac.checks.impl.prediction.stage.world;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class WorldData {
    @Setter
    DesyncStatus lastOnGround;

    @Setter
    DesyncStatus inWater;

    @Setter
    DesyncStatus inLava;

    DesyncStatus touchingLava;

    @Setter
    DesyncStatus inFlowingLiquid;

    @Setter
    boolean mightBeInBlock;

    boolean canJump;
    boolean canFluidHop;
    boolean canFloatWhileRidden;
    boolean couldFloatWhileRidden;
    DesyncStatus climbingAtStart;

    @Setter
    DesyncStatus climbing;

    int numColliding;

    @Setter
    int numCollidingTickingAfterPlayer;

    /** The riders outside the player's riding trees among {@link #numCollidingTickingAfterPlayer}. */
    int numRidersTickingAfterPlayer;

    int numPushing;
    int numCarriedPushing;
    CarriedPush carriedPush;

    // Default model state identifies the supporting block, independent of its properties.
    int onBlock;
    StuckEdgeData sneak;
    BubbleColumnData bubbleColumn;
    PistonPushes pistonPushes;

    @Setter
    StuckSpeedData stuckSpeed;

    DesyncStatus honeySlide;
    DesyncStatus onHoneyBlock;
    MainSupportingBlockData mainSupportingBlockPos;
    SimpleCollisionBox fishingRodPulls;

    /** The x/z range of the pushes taken while the level loaded, carried into the first loaded travel; null if none. */
    SimpleCollisionBox loadingLevelPushes;

    /** The previous client tick's ground state, before this tick's pushes or riptide mark it unknown. */
    DesyncStatus carriedOnGround;

    /** Entity#isEyeInFluid(WATER) after this tick's Entity#baseTick. */
    boolean eyeInWater;

    /** LocalPlayer#isUnderWater during this tick: the previous tick's {@link #eyeInWater}. */
    boolean underwater;

    /** Entity#isSwimming after this tick's Entity#updateSwimming; true if the client may swim. */
    boolean swimming;

    /** A shallow copy: carry contexts share its sub-objects and boxes, so none may be mutated in place. */
    public WorldData copy() {
        return new WorldData(
                lastOnGround,
                inWater,
                inLava,
                touchingLava,
                inFlowingLiquid,
                mightBeInBlock,
                canJump,
                canFluidHop,
                canFloatWhileRidden,
                couldFloatWhileRidden,
                climbingAtStart,
                climbing,
                numColliding,
                numCollidingTickingAfterPlayer,
                numRidersTickingAfterPlayer,
                numPushing,
                numCarriedPushing,
                carriedPush,
                onBlock,
                sneak,
                bubbleColumn,
                pistonPushes,
                stuckSpeed,
                honeySlide,
                onHoneyBlock,
                mainSupportingBlockPos,
                fishingRodPulls,
                loadingLevelPushes,
                carriedOnGround,
                eyeInWater,
                underwater,
                swimming);
    }

    public boolean mustBeInLiquid() {
        return inWater.determinePessimistically() || inLava.determinePessimistically();
    }

    public boolean maybeInLiquid() {
        return inWater.determineOptimistically() || inLava.determineOptimistically();
    }
}
