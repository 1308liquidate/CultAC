package ac.cult.cultac.manager.portal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Tells the cloud's team sync ({@code /api/v1/team/sync}) which portal {@link Role} each of this server's players
 * holds. A player still online {@link #JOIN_DELAY_SECONDS} seconds after joining who holds a role is reported; a
 * permission change for a player checked that way reports the new role when it differs; the offline sweep (LuckPerms
 * only) reports the stored role of synced players who are not online. {@link #tick} makes at most one call, and runs
 * every {@link #TICK_SECONDS} seconds; mode {@code off}, a pause or a refused key stops all calls for
 * {@link #BACK_OFF_MS} ms, then one GET re-checks. Every method is safe from any thread and returns without waiting on
 * HTTP or LuckPerms.
 */
public final class PortalSync {
    static final String ONLINE = "online";
    static final String OFFLINE = "offline";
    static final long JOIN_DELAY_SECONDS = 120;
    static final long TICK_SECONDS = 30;
    static final long FIRST_SWEEP_MS = TimeUnit.MINUTES.toMillis(10);
    static final long SWEEP_EVERY_MS = TimeUnit.HOURS.toMillis(6);
    static final long BACK_OFF_MS = TimeUnit.HOURS.toMillis(6);
    static final int MAX_PER_CALL = 100;
    static final int MAX_QUEUED = 500;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** A portal role and the node granting it, lowest first. */
    public enum Role {
        VIEWER("viewer"),
        MODERATOR("moderator"),
        ADMIN("admin");

        private static final Role[] HIGHEST_FIRST = {ADMIN, MODERATOR, VIEWER};

        private final String wire;
        private final String node;

        Role(String wire) {
            this.wire = wire;
            this.node = "cult.portal." + wire;
        }

        public String node() {
            return node;
        }

        String wire() {
            return wire;
        }

        /** The highest role whose node {@code holds} answers true for; null when it holds none. */
        public static @Nullable Role highest(Predicate<String> holds) {
            for (Role role : HIGHEST_FIRST) {
                if (holds.test(role.node)) return role;
            }
            return null;
        }
    }

    /** A finished call: the HTTP status and the body. */
    public record Reply(int status, String body) {}

    /** The cloud's team sync endpoint. */
    public interface Transport {
        CompletableFuture<Reply> post(JsonObject body);

        CompletableFuture<Reply> get();
    }

    /** The server's online players. */
    public interface Players {
        boolean isOnline(UUID uuid);

        /** The player's name and highest held {@link Role}; null when they are not online. */
        @Nullable
        Online check(UUID uuid);
    }

    /** An online player; {@code role} is null when they hold none. */
    public record Online(String name, @Nullable Role role) {}

    /**
     * What the permission plugin stores for a player who is not online; {@code name} is null when it knows none,
     * {@code role} when they hold none.
     */
    public record Offline(@Nullable String name, @Nullable Role role) {}

    @FunctionalInterface
    public interface OfflineLookup {
        /** Completes with null when the player has no stored data. */
        CompletableFuture<Offline> lookup(UUID uuid);
    }

    /** {@code previous} is the role the cloud last took for an online report, restored if this one never lands. */
    record Report(
            UUID uuid,
            @Nullable String name,
            @Nullable Role role,
            @Nullable Role previous,
            String source,
            boolean retried) {
        Report retry() {
            return new Report(uuid, name, role, previous, source, true);
        }

        Report since(@Nullable Role landed) {
            return new Report(uuid, name, role, landed, source, retried);
        }
    }

    private final Players players;
    private final LongSupplier clock;
    private final Supplier<OfflineLookup> offline;
    private final Consumer<String> debug;

    private final Map<UUID, Report> queue = new LinkedHashMap<>();
    /** Online players whose join check ran; only their permission changes are re-checked. */
    private final Set<UUID> watched = new HashSet<>();
    /** The role last reported for each watched player; absent when none was, or the last report was null. */
    private final Map<UUID, Role> reported = new HashMap<>();

    private final Set<UUID> changed = new LinkedHashSet<>();
    private long blockedUntil = Long.MIN_VALUE;
    private boolean recheck;
    /** After a re-check, the next tick reports each watched player's state whatever was reported before. */
    private boolean resync;

    private boolean inFlight;
    private long nextSweep;

    public PortalSync(Players players, LongSupplier clock, Supplier<OfflineLookup> offline, Consumer<String> debug) {
        this.players = players;
        this.clock = clock;
        this.offline = offline;
        this.debug = debug;
        this.nextSweep = clock.getAsLong() + FIRST_SWEEP_MS;
    }

    /** Only version-4 (Mojang) UUIDs; offline-mode and Floodgate accounts are never reported. */
    public static boolean accepts(@Nullable UUID uuid) {
        return uuid != null && uuid.version() == 4;
    }

    static boolean validName(@Nullable String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The join check, {@link #JOIN_DELAY_SECONDS} seconds after the player joined. */
    public void checkJoined(UUID uuid) {
        if (!accepts(uuid)) return;
        Online online = players.check(uuid);
        if (online == null) return;
        synchronized (this) {
            watched.add(uuid);
            if (online.role() != null && online.role() != reported.get(uuid)) apply(uuid, online);
        }
    }

    /** A permission plugin's data for {@code uuid} changed; the next tick re-checks a watched player. */
    public synchronized void permissionsChanged(UUID uuid) {
        if (watched.contains(uuid)) changed.add(uuid);
    }

    public void tick(Transport api) {
        long now = clock.getAsLong();
        List<UUID> recheckNow;
        boolean all = false;
        synchronized (this) {
            if (inFlight || now < blockedUntil) return;
            if (recheck) {
                inFlight = true;
                recheckNow = null;
            } else {
                for (Iterator<UUID> it = watched.iterator(); it.hasNext(); ) {
                    if (!players.isOnline(it.next())) it.remove();
                }
                reported.keySet().retainAll(watched);
                changed.retainAll(watched);
                recheckNow = new ArrayList<>(changed);
                changed.clear();
                all = resync;
                resync = false;
            }
        }
        if (recheckNow == null) {
            call(api::get, this::rechecked);
            return;
        }

        for (UUID uuid : recheckNow) {
            Online online = players.check(uuid);
            synchronized (this) {
                if (online == null) watched.remove(uuid);
                else if (all
                        ? online.role() != null || reported.containsKey(uuid)
                        : online.role() != reported.get(uuid)) apply(uuid, online);
            }
        }

        List<Report> batch = new ArrayList<>();
        synchronized (this) {
            if (inFlight || now < blockedUntil) return;
            if (offline.get() != null && now >= nextSweep) {
                nextSweep = now + SWEEP_EVERY_MS;
                inFlight = true;
            } else {
                if (queue.isEmpty()) return;
                Iterator<Report> it = queue.values().iterator();
                while (it.hasNext() && batch.size() < MAX_PER_CALL) {
                    batch.add(it.next());
                    it.remove();
                }
                inFlight = true;
            }
        }
        if (batch.isEmpty()) call(api::get, this::swept);
        else call(() -> api.post(body(batch)), (reply, error) -> posted(batch, reply, error));
    }

    private void apply(UUID uuid, Online online) {
        if (!validName(online.name())) return;
        Report queued = queue.get(uuid);
        Role landed = queued != null ? queued.previous() : reported.get(uuid);
        if (!enqueue(new Report(uuid, online.name(), online.role(), landed, ONLINE, false))) return;
        remember(uuid, online.role());
    }

    private void remember(UUID uuid, @Nullable Role role) {
        if (role != null) reported.put(uuid, role);
        else reported.remove(uuid);
    }

    private boolean enqueue(Report report) {
        if (clock.getAsLong() < blockedUntil || recheck) return false;
        if (queue.size() >= MAX_QUEUED && !queue.containsKey(report.uuid())) {
            debug.accept("Team sync queue full; dropped a report");
            return false;
        }
        queue.put(report.uuid(), report);
        return true;
    }

    static JsonObject body(List<Report> batch) {
        JsonArray players = new JsonArray();
        for (Report report : batch) {
            JsonObject player = new JsonObject();
            player.addProperty("uuid", report.uuid().toString());
            player.addProperty("name", report.name());
            player.addProperty("role", report.role() != null ? report.role().wire() : null);
            player.addProperty("source", report.source());
            players.add(player);
        }
        JsonObject body = new JsonObject();
        body.add("players", players);
        return body;
    }

    @FunctionalInterface
    private interface Answer {
        void accept(@Nullable Reply reply, @Nullable Throwable error);
    }

    private void call(Supplier<CompletableFuture<Reply>> request, Answer answer) {
        CompletableFuture<Reply> call;
        try {
            call = request.get();
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete(answer::accept);
    }

    private void posted(List<Report> batch, @Nullable Reply reply, @Nullable Throwable error) {
        synchronized (this) {
            inFlight = false;
            if (reply != null && ok(reply.status())) {
                if (stopped(reply.body())) backOff("the cloud answered mode off or paused");
                return;
            }
            if (reply != null && refused(reply.status())) {
                backOff("the cloud answered HTTP " + reply.status());
                return;
            }
            if (reply == null || reply.status() != 400) {
                for (Report report : batch) {
                    if (report.retried() || queue.containsKey(report.uuid()) || !enqueue(report.retry())) {
                        dropped(report);
                    }
                }
            } else {
                batch.forEach(this::dropped);
            }
        }
        debug.accept("Team sync report of " + batch.size() + " failed: " + outcome(reply, error));
    }

    /**
     * A report the cloud never took: the player's reported role goes back to the one the cloud last took so a later
     * check sends it again, or, while a newer report for the player is queued, that report carries it.
     */
    private void dropped(Report report) {
        if (!ONLINE.equals(report.source()) || !watched.contains(report.uuid())) return;
        Report newer = queue.get(report.uuid());
        if (newer != null) queue.put(report.uuid(), newer.since(report.previous()));
        else remember(report.uuid(), report.previous());
    }

    private void rechecked(@Nullable Reply reply, @Nullable Throwable error) {
        synchronized (this) {
            inFlight = false;
            if (reply != null && ok(reply.status()) && !stopped(reply.body())) {
                recheck = false;
                resync = true;
                changed.addAll(watched);
                return;
            }
        }
        backOff("re-check: " + outcome(reply, error));
    }

    private void swept(@Nullable Reply reply, @Nullable Throwable error) {
        synchronized (this) {
            inFlight = false;
            if (reply == null || !ok(reply.status())) {
                if (reply != null && refused(reply.status())) backOff("the cloud answered HTTP " + reply.status());
                else debug.accept("Team sync sweep failed: " + outcome(reply, error));
                return;
            }
            if (stopped(reply.body())) {
                backOff("the cloud answered mode off or paused");
                return;
            }
        }
        OfflineLookup lookup = offline.get();
        if (lookup == null) return;
        for (UUID uuid : synced(reply.body())) {
            if (!accepts(uuid) || players.isOnline(uuid)) continue;
            CompletableFuture<Offline> found;
            try {
                found = lookup.lookup(uuid);
            } catch (RuntimeException e) {
                found = CompletableFuture.failedFuture(e);
            }
            found.whenComplete((stored, failure) -> {
                if (failure != null) {
                    debug.accept(
                            "Team sync lookup failed: " + failure.getClass().getSimpleName());
                    return;
                }
                if (stored == null) return;
                String name = validName(stored.name()) ? stored.name() : null;
                synchronized (this) {
                    enqueue(new Report(uuid, name, stored.role(), null, OFFLINE, false));
                }
            });
        }
    }

    private void backOff(String why) {
        synchronized (this) {
            blockedUntil = clock.getAsLong() + BACK_OFF_MS;
            recheck = true;
            queue.clear();
            changed.clear();
        }
        debug.accept("Team sync paused for 6 hours: " + why);
    }

    private static boolean ok(int status) {
        return status >= 200 && status < 300;
    }

    /** A key the cloud will not take, or a cloud without team sync. */
    private static boolean refused(int status) {
        return status == 401 || status == 403 || status == 404;
    }

    static boolean stopped(String body) {
        JsonObject answer = object(body);
        if (answer == null) return false;
        JsonElement mode = answer.get("mode");
        JsonElement paused = answer.get("paused");
        return (mode != null && mode.isJsonPrimitive() && "off".equals(mode.getAsString()))
                || (paused != null && paused.isJsonPrimitive() && paused.getAsBoolean());
    }

    static List<UUID> synced(String body) {
        JsonObject answer = object(body);
        if (answer == null || !(answer.get("synced") instanceof JsonArray array)) return List.of();
        List<UUID> uuids = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) continue;
            try {
                uuids.add(UUID.fromString(element.getAsString()));
            } catch (IllegalArgumentException notUuid) {
                // Skipped; the rest still sweep.
            }
        }
        return uuids;
    }

    private static @Nullable JsonObject object(@Nullable String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException notJson) {
            return null;
        }
    }

    private static String outcome(@Nullable Reply reply, @Nullable Throwable error) {
        if (reply != null) return "HTTP " + reply.status();
        if (error == null) return "no answer";
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }
}
