package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import java.util.List;
import java.util.Objects;

/**
 * The compensated entities the client holds that this tick's movement systems read.
 *
 * @param spinAttackTargets the boxes of the mobs a riptide spin attack may hit, at the positions the client shows them
 *     on this tick
 */
public record EntityContactState(
        DolphinBoostState dolphinBoostState, boolean passenger, List<WorldCollisionBox> spinAttackTargets) {
    public static final EntityContactState NONE = new EntityContactState(DolphinBoostState.NONE);

    public EntityContactState {
        dolphinBoostState = Objects.requireNonNull(dolphinBoostState, "dolphinBoostState");
        spinAttackTargets = List.copyOf(Objects.requireNonNull(spinAttackTargets, "spinAttackTargets"));
    }

    public EntityContactState(DolphinBoostState dolphinBoostState, boolean passenger) {
        this(dolphinBoostState, passenger, List.of());
    }

    public EntityContactState(DolphinBoostState dolphinBoostState) {
        this(dolphinBoostState, false);
    }

    public boolean dolphinBoostAvailable() {
        return dolphinBoostState.boostAvailable();
    }

    /** The number of spin attack targets whose box intersects {@code sweep}. */
    public int spinAttackHits(WorldCollisionBox sweep) {
        int hits = 0;
        for (WorldCollisionBox target : spinAttackTargets) {
            if (target.intersects(sweep)) hits++;
        }
        return hits;
    }
}
