package ac.cult.cultac.checks.impl.combat;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

public final class EntityReach extends ReachCheck {
    public EntityReach(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(
                player,
                visibility,
                CheckInfo.builder()
                        .name("EntityReach")
                        .stableKey("cult.combat.entity.reach")
                        .description("Attacked a non-player entity from too far away")
                        .build());
    }

    @Override
    protected boolean targets(int type) {
        return type != EntityTypeIds.PLAYER;
    }

    @Override
    protected Class<? extends Check> hitboxes() {
        return EntityHitboxes.class;
    }
}
