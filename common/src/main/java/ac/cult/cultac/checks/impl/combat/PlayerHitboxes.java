package ac.cult.cultac.checks.impl.combat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;

@CheckData(
        name = "PlayerHitboxes",
        stableKey = "cult.combat.player.hitboxes",
        description = "Tried to hit a player outside its valid hitbox")
public class PlayerHitboxes extends Check implements CheckListener {
    public PlayerHitboxes(CultPlayer player) {
        super(player);
    }
}
