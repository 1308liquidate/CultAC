package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "EntityHitboxes",
        stableKey = "cult.combat.entity.hitboxes",
        description = "Tried to hit a non-player entity outside its valid hitbox")
public class EntityHitboxes extends Check implements CheckListener {
    public EntityHitboxes(CultPlayer player) {
        super(player);
    }
}
