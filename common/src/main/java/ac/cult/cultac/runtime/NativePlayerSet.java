package ac.cult.cultac.runtime;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.*;

/** Each runtime adapts shared native recipients through its own platform factory. */
public final class NativePlayerSet extends AbstractSet<PlatformPlayer> {
    private final Set<Object> nativePlayers;

    public NativePlayerSet(Set<Object> nativePlayers) {
        this.nativePlayers = nativePlayers;
    }

    public Iterator<PlatformPlayer> iterator() {
        return nativePlayers.stream()
                .map(player -> CultAPI.INSTANCE.getPlatformPlayerFactory().getFromNativePlayerType(player))
                .iterator();
    }

    public int size() {
        return nativePlayers.size();
    }

    public boolean add(PlatformPlayer player) {
        return nativePlayers.add(player.getNative());
    }

    public boolean remove(Object player) {
        return player instanceof PlatformPlayer wrapped && nativePlayers.remove(wrapped.getNative());
    }

    public boolean contains(Object player) {
        return player instanceof PlatformPlayer wrapped && nativePlayers.contains(wrapped.getNative());
    }
}
