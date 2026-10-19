package ac.cult.cultac.platform.api.player;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.PlatformLoader;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Platform factory over plain native objects; installing it swaps the global loader until closed. */
public final class FakePlatformPlayers extends AbstractPlatformPlayerFactory<FakePlatformPlayers.Native>
        implements AutoCloseable {
    /** Identity-compared, like a server's player object. */
    public static final class Native {
        private final UUID uuid;

        public Native(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID uuid() {
            return uuid;
        }
    }

    private final Field loaderField;
    private final Object originalLoader;

    public FakePlatformPlayers() throws ReflectiveOperationException {
        loaderField = CultAPI.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        originalLoader = loaderField.get(CultAPI.INSTANCE);
        loaderField.set(
                CultAPI.INSTANCE,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {PlatformLoader.class},
                        (proxy, method, args) -> method.getName().equals("getPlatformPlayerFactory")
                                ? this
                                : method.invoke(originalLoader, args)));
    }

    @Override
    public void close() throws IllegalAccessException {
        loaderField.set(CultAPI.INSTANCE, originalLoader);
    }

    @Override
    protected Native getNativePlayer(@NotNull UUID uuid) {
        return null;
    }

    @Override
    protected Native getNativePlayer(@NotNull String name) {
        return null;
    }

    @Override
    protected PlatformPlayer createPlatformPlayer(@NotNull Native nativePlayer) {
        PlatformPlayer player = mock(PlatformPlayer.class);
        when(player.getUniqueId()).thenReturn(nativePlayer.uuid());
        when(player.getNative()).thenReturn(nativePlayer);
        return player;
    }

    @Override
    protected UUID getPlayerUUID(@NotNull Native nativePlayer) {
        return nativePlayer.uuid();
    }

    @Override
    protected Collection<Native> getNativeOnlinePlayers() {
        return List.of();
    }
}
