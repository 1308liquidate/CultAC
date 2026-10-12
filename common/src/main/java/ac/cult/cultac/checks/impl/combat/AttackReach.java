package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.data.ItemComponents;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.visibility.ClientVisibility;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import java.util.List;

/**
 * The vanilla distance bounds for one attack, captured when the attack is sent.
 *
 * <p>LocalPlayer#raycastHitResult picks with the active item's AttackRange component when present
 * ({@code picks}), falling back to an entity_interaction_range pick ({@code entityRange}).
 * Minecraft#startAttack then drops entity hits outside the main-hand item's AttackRange#isInRange
 * ({@code itemLimit}), measured to a point on the target's bounding box.
 */
record AttackReach(double entityRange, double itemLimit, List<ClientVisibility.ItemPick> picks) {
    private static final double SQRT_3 = Math.sqrt(3);

    static AttackReach capture(CultPlayer player, ClientVisibilityProcessor visibility) {
        return new AttackReach(
                player.compensatedEntities.getSelf().getEntityInteractionRange(),
                mainHandItemLimit(player),
                visibility.attackItemPicks());
    }

    // MultiPlayerGameMode#attack sends the carried item before the attack, so the claimed slot is exact.
    private static double mainHandItemLimit(CultPlayer player) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_11)) return Double.NaN;
        var held = player.getInventory().getClientSelectedHeldItem();
        var range = held.isEmpty() ? null : ItemComponents.attackRange(held.components());
        if (range == null) return Double.NaN;
        // AttackRange#effectiveMaxRange for a Player, plus the hitbox margin, in float like vanilla.
        float max = player.gamemode == GameMode.CREATIVE ? range.maxCreativeReach() : range.maxReach();
        return max + range.hitboxMargin();
    }

    boolean hasItemLimit() {
        return !Double.isNaN(itemLimit);
    }

    /** An upper bound on the eye-to-box distance of any attack vanilla could send. */
    double maxClosestDistance() {
        if (hasItemLimit()) return itemLimit;
        double max = entityRange;
        // An item pick's view vector is at most sqrt(3) long, and its margin hit lies within
        // sqrt(3) * margin of the unexpanded box.
        for (ClientVisibility.ItemPick pick : picks) {
            max = Math.max(max, SQRT_3 * (pick.rayScale() + pick.hitboxMargin()));
        }
        return max;
    }
}
