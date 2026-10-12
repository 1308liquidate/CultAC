package ac.cult.cultac.manager.datastore;

import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.internal.storage.identity.PlayerIdentityService;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Live-write facade used when only the player-identity route is available.
 * History/session writes remain no-op, but join identity caching still works.
 */
final class IdentityStorageWriteHooks implements StorageWriteHooks {

    private final PlayerIdentityService identityService;

    IdentityStorageWriteHooks(@NotNull PlayerIdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public void onJoin(@NotNull UUID uuid, @Nullable String name, long now, @NotNull SessionTracker.ClientMeta meta) {
        identityService.observe(uuid, name, now);
    }

    @Override
    public void onQuit(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta) {}

    @Override
    public void observeBrand(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta) {}

    @Override
    public void recordFlag(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            @Nullable String verbose,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {}

    @Override
    public void recordFlagData(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            byte @Nullable [] verboseData,
            long now,
            @NotNull SessionTracker.ClientMeta meta) {}
}
