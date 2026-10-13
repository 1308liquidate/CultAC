package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Owns the Bukkit world access used by explicit validation commands. */
final class BukkitValidationBlocks {
    private BukkitValidationBlocks() {}

    static void apply(PlatformPlayer player, int x, int y, int z, String state, Runnable applied) {
        var blockData = Bukkit.createBlockData(state);
        Player nativePlayer = (Player) player.getNative();
        var location = new Location(nativePlayer.getWorld(), x, y, z);
        var api = CultAPI.INSTANCE;
        api.getScheduler().getRegionScheduler().execute(api.getGrimPlugin(), player.getWorld(), x >> 4, z >> 4, () -> {
            location.getBlock().setBlockData(blockData, false);
            applied.run();
        });
    }
}
