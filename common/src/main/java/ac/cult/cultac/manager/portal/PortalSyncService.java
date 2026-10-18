package ac.cult.cultac.manager.portal;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.manager.init.stop.StoppableInitable;
import ac.cult.cultac.manager.portal.PortalSync.OfflineLookup;
import ac.cult.cultac.manager.portal.PortalSync.Online;
import ac.cult.cultac.manager.portal.PortalSync.Role;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Runs {@link PortalSync} on the async scheduler: the join check and every tick run there, never on the main thread
 * or a connection's event loop. Off unless {@code cloud.team-sync} (or {@value #ENV}) turns it on, and while the cloud
 * upload has no address or key.
 */
public final class PortalSyncService implements StartableInitable, StoppableInitable {
    static final String ENV = "CULTAC_CLOUD_TEAM_SYNC";

    private volatile @Nullable PortalSync sync;
    private volatile @Nullable OfflineLookup offline;
    private @Nullable TaskHandle tick;
    private final AtomicReference<Runnable> nextJoin = new AtomicReference<>();

    @Override
    public void start() {
        PortalSync started = new PortalSync(new Players(), System::currentTimeMillis, () -> offline, LogUtil::debug);
        sync = started;
        tick = CultAPI.INSTANCE
                .getScheduler()
                .getAsyncScheduler()
                .runAtFixedRate(
                        CultAPI.INSTANCE.getGrimPlugin(),
                        () -> tick(started),
                        PortalSync.TICK_SECONDS,
                        PortalSync.TICK_SECONDS,
                        TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        TaskHandle handle = tick;
        if (handle != null) handle.cancel();
        tick = null;
        sync = null;
        nextJoin.set(null);
    }

    /** Runs {@code task} once on the async scheduler at the next player join, whether team sync is on or not. */
    public void onNextJoin(Runnable task) {
        nextJoin.set(task);
    }

    /** Schedules the join check; called on the connection's event loop, so it only schedules. */
    public void joined(PlatformPlayer player) {
        Runnable once = nextJoin.getAndSet(null);
        if (once != null)
            CultAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(CultAPI.INSTANCE.getGrimPlugin(), once);
        PortalSync current = sync;
        UUID uuid = player.getUniqueId();
        if (current == null || !PortalSync.accepts(uuid) || configured() == null) return;
        CultAPI.INSTANCE
                .getScheduler()
                .getAsyncScheduler()
                .runDelayed(
                        CultAPI.INSTANCE.getGrimPlugin(),
                        () -> {
                            if (sync == current) current.checkJoined(uuid);
                        },
                        PortalSync.JOIN_DELAY_SECONDS,
                        TimeUnit.SECONDS);
    }

    public void permissionsChanged(UUID uuid) {
        PortalSync current = sync;
        if (current != null) current.permissionsChanged(uuid);
    }

    /** The permission plugin's store for players who are not online; null turns the offline sweep off. */
    public void offlineLookup(@Nullable OfflineLookup lookup) {
        offline = lookup;
    }

    private void tick(PortalSync current) {
        PortalSyncClient client = configured();
        if (client != null) current.tick(client);
    }

    private static @Nullable PortalSyncClient configured() {
        return client(CultAPI.INSTANCE.getConfigManager().getConfig(), System::getenv, CloudModule::settings);
    }

    /**
     * The client for the cloud's team sync; null while team sync is off, and while the upload has no address or key.
     * {@code settings} answers null until the upload is configured, and is read only while team sync is on.
     */
    static @Nullable PortalSyncClient client(
            ConfigManager config, Function<String, String> env, Supplier<CloudSettings> settings) {
        if (!enabled(config, env)) return null;
        CloudSettings cloud = settings.get();
        if (cloud == null || cloud.url().isEmpty() || cloud.apiKey().isEmpty()) return null;
        return new PortalSyncClient(cloud.url(), cloud.apiKey());
    }

    /** {@value #ENV} when it is true/1/yes or false/0/no, else {@code cloud.team-sync}, off by default. */
    static boolean enabled(ConfigManager config, Function<String, String> env) {
        String value = env.apply(ENV);
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> config.getBooleanElse("cloud.team-sync", false);
        };
    }

    private static final class Players implements PortalSync.Players {
        @Override
        public boolean isOnline(UUID uuid) {
            return CultAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(uuid) != null;
        }

        @Override
        public @Nullable Online check(UUID uuid) {
            PlatformPlayer player = CultAPI.INSTANCE.getPlatformPlayerFactory().getFromUUID(uuid);
            if (player == null) return null;
            return new Online(player.getName(), Role.highest(node -> player.hasPermission(node, false)));
        }
    }
}
