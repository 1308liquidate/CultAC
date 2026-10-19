package ac.cult.cultac.platform.api.player;

import java.util.Collection;
import java.util.UUID;

public interface PlatformPlayerFactory {
    PlatformPlayer getFromName(String name);

    PlatformPlayer getFromUUID(UUID uuid);

    PlatformPlayer getFromNativePlayerType(Object playerObject);

    /** Returns the cached wrapper only when it wraps this native; never writes the cache. */
    PlatformPlayer getUncachedFromNativePlayerType(Object playerObject);

    void invalidatePlayer(UUID uuid);

    void invalidatePlayer(PlatformPlayer player);

    Collection<PlatformPlayer> getOnlinePlayers();
}
