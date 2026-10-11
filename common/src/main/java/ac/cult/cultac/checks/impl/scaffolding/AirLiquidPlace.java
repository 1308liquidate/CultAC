package ac.cult.cultac.checks.impl.scaffolding;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.BlockPlaceCheck;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;

@CheckData(
        name = "AirLiquidPlace",
        stableKey = "cult.scaffolding.air_liquid_place",
        description = "Placed a block against an invalid support")
public class AirLiquidPlace extends BlockPlaceCheck {
    public AirLiquidPlace(CultPlayer player) {
        super(player);
    }

    @Override
    public void onBlockPlace(final BlockPlace place) {
        if (player.gamemode == GameMode.CREATIVE) return;

        int placeAgainst = player.compensatedWorld.getBlockStateIdAt(place.getPlacedAgainstBlockLocation());
        if (!isValidSupport(placeAgainst) && flag() && shouldModifyPackets() && shouldCancel()) {
            place.resync();
        }
    }

    private static boolean isValidSupport(int state) {
        // The original material query inspected the block's default state.
        var registry = DataTables.defaults().registry();
        int defaultState = registry.block(state).defaultState();
        var facts = registry.facts(defaultState);
        return !facts.has(StateFacts.AIR)
                && !(BlockFamilies.LIQUID.test(defaultState) && facts.has(StateFacts.FLUID_SOURCE));
    }

    @Override
    protected int getDefaultCancelVL() {
        return 0;
    }
}
