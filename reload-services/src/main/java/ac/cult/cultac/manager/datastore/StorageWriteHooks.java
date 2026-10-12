package ac.cult.cultac.manager.datastore;

import ac.grim.grimac.api.AbstractCheck;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plugin-side facade that fans out player-lifecycle and flag events into the
 * data store. Datastore-disabled or init-failed paths get {@link #NOOP}, so
 * callers don't null-check.
 *
 * <p>The "from" convenience methods build all the per-call args (UUID, name,
 * timestamp, {@link SessionTracker.ClientMeta}) inside the impl. NOOP skips
 * the work entirely. Granular methods stay platform-neutral for tests +
 * tooling.
 */
public interface StorageWriteHooks {

    /** Player joined. Upsert identity (firstSeen=min / lastSeen=max) and open/extend a session. */
    void onJoin(@NotNull UUID uuid, @Nullable String name, long now, @NotNull SessionTracker.ClientMeta meta);

    /** Player disconnected. Flush the closing heartbeat on the session row + clear the in-memory tracker entry. */
    void onQuit(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta);

    /**
     * Brand packet arrived. Re-issues a session upsert with the now-known
     * {@code clientBrand} field — the brand-channel plugin message can arrive
     * after PlayerJoinEvent, so the initial onJoin upsert may have left
     * client_brand null.
     */
    void observeBrand(@NotNull UUID uuid, long now, @NotNull SessionTracker.ClientMeta meta);

    /** Record a single flag with the full check object — pulls stable_key + description from the check. */
    void recordFlag(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            @Nullable String verbose,
            long now,
            @NotNull SessionTracker.ClientMeta meta);

    /** Record a single flag whose verbose payload is already encoded as opaque bytes. */
    void recordFlagData(
            @NotNull UUID playerUuid,
            @NotNull AbstractCheck check,
            double vl,
            byte @Nullable [] verboseData,
            long now,
            @NotNull SessionTracker.ClientMeta meta);

    StorageWriteHooks NOOP = new StorageWriteHooks() {
        @Override
        public void onJoin(@NotNull UUID u, @Nullable String n, long t, @NotNull SessionTracker.ClientMeta m) {}

        @Override
        public void onQuit(@NotNull UUID u, long t, @NotNull SessionTracker.ClientMeta m) {}

        @Override
        public void observeBrand(@NotNull UUID u, long t, @NotNull SessionTracker.ClientMeta m) {}

        @Override
        public void recordFlag(
                @NotNull UUID u,
                @NotNull AbstractCheck c,
                double v,
                @Nullable String vb,
                long t,
                @NotNull SessionTracker.ClientMeta m) {}

        @Override
        public void recordFlagData(
                @NotNull UUID u,
                @NotNull AbstractCheck c,
                double v,
                byte @Nullable [] d,
                long t,
                @NotNull SessionTracker.ClientMeta m) {}
    };
}
