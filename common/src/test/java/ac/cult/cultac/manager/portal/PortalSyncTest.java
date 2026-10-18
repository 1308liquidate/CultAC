package ac.cult.cultac.manager.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.manager.portal.PortalSync.Offline;
import ac.cult.cultac.manager.portal.PortalSync.OfflineLookup;
import ac.cult.cultac.manager.portal.PortalSync.Online;
import ac.cult.cultac.manager.portal.PortalSync.Reply;
import ac.cult.cultac.manager.portal.PortalSync.Role;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PortalSyncTest {
    private static final UUID SPEC = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final String APPROVE = "{\"mode\": \"approve\", \"paused\": false, \"applied\": 1}";
    private static final String OFF = "{\"mode\": \"off\", \"paused\": false, \"applied\": 0}";
    private static final String NODES = "\"nodes\":{\"viewer\":\"cult.portal.viewer\","
            + "\"moderator\":\"cult.portal.moderator\",\"admin\":\"cult.portal.admin\"}";

    private long now = 1_000_000L;
    private final Map<UUID, Online> online = new HashMap<>();
    private final Map<UUID, Offline> stored = new HashMap<>();
    private final List<UUID> lookedUp = new ArrayList<>();
    private OfflineLookup lookup;
    private final List<String> debug = new ArrayList<>();
    private final FakeTransport api = new FakeTransport();

    private final PortalSync sync = new PortalSync(
            new PortalSync.Players() {
                @Override
                public boolean isOnline(UUID uuid) {
                    return online.containsKey(uuid);
                }

                @Override
                public Online check(UUID uuid) {
                    return online.get(uuid);
                }
            },
            () -> now,
            () -> lookup,
            debug::add);

    private static final class FakeTransport implements PortalSync.Transport {
        final List<JsonObject> posts = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<CompletableFuture<Reply>> pending = new ArrayList<>();

        @Override
        public CompletableFuture<Reply> post(JsonObject body) {
            posts.add(body);
            calls.add("POST");
            return next();
        }

        @Override
        public CompletableFuture<Reply> get() {
            calls.add("GET");
            return next();
        }

        private CompletableFuture<Reply> next() {
            CompletableFuture<Reply> call = new CompletableFuture<>();
            pending.add(call);
            return call;
        }

        void answer(int status, String body) {
            pending.remove(0).complete(new Reply(status, body));
        }

        void fail() {
            pending.remove(0).completeExceptionally(new IOException("down"));
        }

        JsonObject lastPost() {
            return posts.get(posts.size() - 1);
        }
    }

    private static UUID v4(int n) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n));
    }

    private UUID join(int n, @Nullable Role role) {
        UUID uuid = v4(n);
        online.put(uuid, new Online("Player" + n, role));
        sync.checkJoined(uuid);
        return uuid;
    }

    private static List<String> uuids(JsonObject body) {
        List<String> uuids = new ArrayList<>();
        for (JsonElement player : body.getAsJsonArray("players")) {
            uuids.add(player.getAsJsonObject().get("uuid").getAsString());
        }
        return uuids;
    }

    @Test
    void aHolderStillOnlineIsReportedInTheSpecsShape() {
        online.put(SPEC, new Online("SpecPlayer", Role.MODERATOR));
        sync.checkJoined(SPEC);
        sync.tick(api);

        assertEquals(List.of("POST"), api.calls);
        assertEquals(
                JsonParser.parseString("{ \"players\": [ { \"uuid\": \"0f8fad5b-d9cb-469f-a165-70867728950e\","
                        + " \"name\": \"SpecPlayer\", \"role\": \"moderator\", \"source\": \"online\" } ] }"),
                api.lastPost());
        assertEquals(
                "{\"players\":[{\"uuid\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"name\":\"SpecPlayer\","
                        + "\"role\":\"moderator\",\"source\":\"online\"}]}",
                api.lastPost().toString());
    }

    @Test
    void theHighestHeldNodeWins() {
        assertEquals("cult.portal.viewer", Role.VIEWER.node());
        assertEquals("cult.portal.moderator", Role.MODERATOR.node());
        assertEquals("cult.portal.admin", Role.ADMIN.node());
        assertEquals(Role.ADMIN, Role.highest(Set.of("cult.portal.viewer", "cult.portal.admin")::contains));
        assertEquals(Role.MODERATOR, Role.highest(Set.of("cult.portal.viewer", "cult.portal.moderator")::contains));
        assertEquals(Role.VIEWER, Role.highest(Set.of("cult.portal.viewer")::contains));
        assertNull(Role.highest(Set.of("cult.portal", "cult.portal.owner")::contains));

        Set<String> held = Set.of("cult.portal.viewer", "cult.portal.moderator", "cult.portal.admin");
        join(1, Role.highest(held::contains));
        sync.tick(api);
        assertTrue(api.lastPost().toString().contains("\"role\":\"admin\""));
    }

    @Test
    void nonHoldersLeaversSpoofedUuidsAndOddNamesAreNotReported() {
        join(1, null);
        sync.checkJoined(v4(2));
        UUID offlineMode = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8));
        online.put(offlineMode, new Online("Steve", Role.MODERATOR));
        sync.checkJoined(offlineMode);
        UUID floodgate = UUID.fromString("00000000-0000-0000-0009-01f0a1b2c3d4");
        online.put(floodgate, new Online("Bedrock", Role.ADMIN));
        sync.checkJoined(floodgate);
        UUID oddName = v4(3);
        online.put(oddName, new Online(".Bedrock Name", Role.VIEWER));
        sync.checkJoined(oddName);

        sync.tick(api);

        assertFalse(PortalSync.accepts(offlineMode));
        assertFalse(PortalSync.accepts(floodgate));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void aReportedPlayerIsNotReportedAgainOnTheSameSession() {
        UUID uuid = join(1, Role.MODERATOR);
        sync.tick(api);
        api.answer(200, APPROVE);
        sync.checkJoined(uuid);
        sync.tick(api);

        assertEquals(1, api.calls.size());
    }

    @Test
    void oneCallPerTickOfAtMostAHundredPlayers() {
        for (int i = 0; i < 250; i++) join(i, Role.MODERATOR);

        sync.tick(api);
        sync.tick(api);
        assertEquals(1, api.calls.size(), "no second call while one is in flight");
        assertEquals(100, uuids(api.lastPost()).size());

        api.answer(200, APPROVE);
        sync.tick(api);
        assertEquals(100, uuids(api.lastPost()).size());
        api.answer(200, APPROVE);
        sync.tick(api);
        assertEquals(50, uuids(api.lastPost()).size());
        api.answer(200, APPROVE);
        sync.tick(api);

        assertEquals(3, api.calls.size());
        Set<String> sent = new HashSet<>();
        for (JsonObject post : api.posts) sent.addAll(uuids(post));
        assertEquals(250, sent.size());
    }

    @Test
    void theQueueHoldsAtMostFiveHundred() {
        for (int i = 0; i < 600; i++) join(i, Role.MODERATOR);

        int sent = 0;
        for (int i = 0; i < 10; i++) {
            sync.tick(api);
            if (!api.pending.isEmpty()) {
                api.answer(200, APPROVE);
            }
        }
        for (JsonObject post : api.posts) sent += uuids(post).size();

        assertEquals(PortalSync.MAX_QUEUED, sent);
    }

    @Test
    void aFailedReportIsRetriedOnceOnTheNextTick() {
        UUID uuid = join(1, Role.MODERATOR);
        sync.tick(api);
        api.fail();
        sync.tick(api);
        assertEquals(List.of(uuid.toString()), uuids(api.lastPost()));
        api.answer(503, "");
        sync.tick(api);

        assertEquals(2, api.calls.size());
    }

    @Test
    void aReportThatNeverLandedIsSentAgainOnTheNextPermissionChange() {
        UUID uuid = join(1, Role.MODERATOR);
        sync.tick(api);
        api.fail();
        sync.tick(api);
        api.answer(503, "");

        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(3, api.calls.size());
        assertEquals(List.of(uuid.toString()), uuids(api.lastPost()));
        assertTrue(api.lastPost().toString().contains("\"role\":\"moderator\""));
    }

    @Test
    void aDroppedReportLeavesANewerQueuedOneOwningTheReportedRole() {
        UUID uuid = join(1, Role.MODERATOR);
        sync.tick(api);
        online.put(uuid, new Online("Player1", Role.VIEWER));
        sync.checkJoined(uuid);
        api.fail();
        sync.tick(api);
        assertTrue(api.lastPost().toString().contains("\"role\":\"viewer\""));
        api.answer(200, APPROVE);

        online.put(uuid, new Online("Player1", null));
        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(3, api.calls.size());
        assertTrue(api.lastPost().toString().contains("\"role\":null"));
    }

    @Test
    void afterAPauseEveryWatchedPlayersStateIsSentAgain() {
        UUID kept = join(1, Role.MODERATOR);
        UUID lost = join(2, Role.ADMIN);
        sync.tick(api);
        api.answer(200, APPROVE);
        join(3, Role.VIEWER);
        sync.tick(api);
        api.answer(200, "{\"mode\":\"approve\",\"paused\":true,\"applied\":0}");

        online.put(lost, new Online("Player2", null));
        sync.permissionsChanged(lost);
        now += PortalSync.BACK_OFF_MS;
        sync.tick(api);
        api.answer(200, "{\"mode\":\"approve\",\"paused\":false," + NODES + ",\"synced\":[]}");
        sync.tick(api);

        assertEquals(List.of("POST", "POST", "GET", "POST"), api.calls);
        Map<String, String> sent = new HashMap<>();
        for (JsonElement player : api.lastPost().getAsJsonArray("players")) {
            JsonObject p = player.getAsJsonObject();
            sent.put(p.get("uuid").getAsString(), p.get("role").toString());
        }
        assertEquals(
                Map.of(kept.toString(), "\"moderator\"", lost.toString(), "null", v4(3).toString(), "\"viewer\""),
                sent);
    }

    @Test
    void aMalformedReportIsNotRetried() {
        join(1, Role.MODERATOR);
        sync.tick(api);
        api.answer(400, "{\"detail\":\"bad\"}");
        sync.tick(api);

        assertEquals(1, api.calls.size());
    }

    @Test
    void modeOffBacksOffSixHoursThenOneGetResumes() {
        UUID first = join(1, Role.MODERATOR);
        sync.tick(api);
        api.answer(200, OFF);

        join(2, Role.MODERATOR);
        now += TimeUnit.HOURS.toMillis(1);
        sync.tick(api);
        now += PortalSync.BACK_OFF_MS - TimeUnit.HOURS.toMillis(1) - 1;
        sync.tick(api);
        assertEquals(List.of("POST"), api.calls);

        now += 1;
        sync.tick(api);
        sync.tick(api);
        assertEquals(List.of("POST", "GET"), api.calls);
        api.answer(200, "{\"mode\":\"approve\",\"paused\":false," + NODES + ",\"synced\":[]}");

        sync.tick(api);
        assertEquals(List.of("POST", "GET", "POST"), api.calls);
        Set<String> resumed = new HashSet<>(uuids(api.lastPost()));
        assertEquals(Set.of(first.toString(), v4(2).toString()), resumed);
    }

    @Test
    void pausedOrARefusedKeyBacksOffAndTheRecheckCanBackOffAgain() {
        join(1, Role.MODERATOR);
        sync.tick(api);
        api.answer(200, "{\"mode\":\"approve\",\"paused\":true,\"applied\":0}");
        now += PortalSync.BACK_OFF_MS;
        sync.tick(api);
        api.answer(200, "{\"mode\":\"auto\",\"paused\":true}");
        now += PortalSync.BACK_OFF_MS - 1;
        sync.tick(api);
        assertEquals(List.of("POST", "GET"), api.calls);

        now += 1;
        sync.tick(api);
        api.answer(200, "{\"mode\":\"auto\",\"paused\":false}");
        sync.tick(api);
        api.answer(403, "{\"detail\":\"no organization\"}");
        join(2, Role.MODERATOR);
        sync.tick(api);

        assertEquals(List.of("POST", "GET", "GET", "POST"), api.calls);
    }

    @Test
    void aPermissionChangeReportsTheNewStateOnce() {
        UUID uuid = join(1, Role.MODERATOR);
        sync.tick(api);
        api.answer(200, APPROVE);

        online.put(uuid, new Online("Player1", null));
        sync.permissionsChanged(uuid);
        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(
                JsonParser.parseString("{\"players\":[{\"uuid\":\"" + uuid
                        + "\",\"name\":\"Player1\",\"role\":null,\"source\":\"online\"}]}"),
                api.lastPost());
        api.answer(200, APPROVE);

        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(2, api.calls.size(), "an unchanged state is not reported again");

        online.put(uuid, new Online("Player1", Role.MODERATOR));
        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(3, api.calls.size());
        assertTrue(api.lastPost().toString().contains("\"role\":\"moderator\""));
    }

    @Test
    void aRoleChangeFromModeratorToViewerReportsViewer() {
        UUID uuid = join(1, Role.MODERATOR);
        sync.tick(api);
        api.answer(200, APPROVE);

        online.put(uuid, new Online("Player1", Role.VIEWER));
        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(
                JsonParser.parseString("{\"players\":[{\"uuid\":\"" + uuid
                        + "\",\"name\":\"Player1\",\"role\":\"viewer\",\"source\":\"online\"}]}"),
                api.lastPost());
        api.answer(200, APPROVE);

        online.put(uuid, new Online("Player1", Role.ADMIN));
        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(3, api.calls.size());
        assertTrue(api.lastPost().toString().contains("\"role\":\"admin\""));
    }

    @Test
    void losingEveryNodeReportsNull() {
        UUID uuid = join(1, Role.ADMIN);
        sync.tick(api);
        api.answer(200, APPROVE);

        online.put(uuid, new Online("Player1", null));
        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(
                "{\"players\":[{\"uuid\":\"" + uuid + "\",\"name\":\"Player1\",\"role\":null,\"source\":\"online\"}]}",
                api.lastPost().toString());
        api.answer(200, APPROVE);

        sync.permissionsChanged(uuid);
        sync.checkJoined(uuid);
        sync.tick(api);
        assertEquals(2, api.calls.size(), "a player holding no role is reported only when losing one");
    }

    @Test
    void aPermissionChangeBeforeTheJoinCheckWaitsForIt() {
        UUID uuid = v4(1);
        online.put(uuid, new Online("Player1", Role.MODERATOR));
        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertTrue(api.calls.isEmpty());

        UUID later = join(2, null);
        online.put(later, new Online("Player2", Role.MODERATOR));
        sync.permissionsChanged(later);
        sync.tick(api);
        assertEquals(List.of(later.toString()), uuids(api.lastPost()));
    }

    @Test
    void aPlayerWhoLeftIsForgottenAndReportedAgainOnTheirNextJoin() {
        UUID uuid = join(1, Role.MODERATOR);
        sync.tick(api);
        api.answer(200, APPROVE);
        online.remove(uuid);
        sync.tick(api);
        sync.permissionsChanged(uuid);
        sync.tick(api);
        assertEquals(1, api.calls.size());

        join(1, Role.MODERATOR);
        sync.tick(api);
        assertEquals(2, api.calls.size());
    }

    @Test
    void theSweepReportsTheStoredRoleOfSyncedOfflinePlayers() {
        lookup = uuid -> {
            lookedUp.add(uuid);
            return CompletableFuture.completedFuture(stored.get(uuid));
        };
        UUID lost = v4(1);
        stored.put(lost, new Offline("LostMod", null));
        UUID kept = v4(2);
        stored.put(kept, new Offline("KeptMod", Role.VIEWER));
        UUID nameless = v4(3);
        stored.put(nameless, new Offline(null, null));
        UUID unknown = v4(4);
        UUID here = join(5, null);
        UUID offlineMode = UUID.nameUUIDFromBytes("OfflinePlayer:Alex".getBytes(StandardCharsets.UTF_8));

        sync.tick(api);
        assertTrue(api.calls.isEmpty(), "the first sweep waits");

        now += PortalSync.FIRST_SWEEP_MS;
        sync.tick(api);
        assertEquals(List.of("GET"), api.calls);
        api.answer(
                200,
                "{\"mode\":\"approve\",\"paused\":false," + NODES + ",\"synced\":[\"" + lost + "\",\"" + kept
                        + "\",\"" + nameless + "\",\"" + unknown + "\",\"" + here + "\",\"" + offlineMode
                        + "\",\"not-a-uuid\"]}");
        assertEquals(List.of(lost, kept, nameless, unknown), lookedUp);

        sync.tick(api);
        assertEquals(
                JsonParser.parseString("{\"players\":[{\"uuid\":\"" + lost
                        + "\",\"name\":\"LostMod\",\"role\":null,\"source\":\"offline\"},{\"uuid\":\""
                        + kept + "\",\"name\":\"KeptMod\",\"role\":\"viewer\",\"source\":\"offline\"},{\"uuid\":\""
                        + nameless + "\",\"name\":null,\"role\":null,\"source\":\"offline\"}]}"),
                api.lastPost());
        api.answer(200, APPROVE);

        now += PortalSync.SWEEP_EVERY_MS - 1;
        sync.tick(api);
        assertEquals(2, api.calls.size());
        now += 1;
        sync.tick(api);
        assertEquals(List.of("GET", "POST", "GET"), api.calls);
    }

    @Test
    void theSweepBacksOffWhenSyncIsOff() {
        lookup = uuid -> {
            lookedUp.add(uuid);
            return CompletableFuture.completedFuture(new Offline("Gone", null));
        };
        now += PortalSync.FIRST_SWEEP_MS;
        sync.tick(api);
        api.answer(200, "{\"mode\":\"off\",\"paused\":false," + NODES + ",\"synced\":[\"" + v4(1) + "\"]}");
        join(2, Role.MODERATOR);
        sync.tick(api);

        assertTrue(lookedUp.isEmpty());
        assertEquals(List.of("GET"), api.calls);
    }

    @Test
    void withoutLuckPermsThereIsNoSweep() {
        now += PortalSync.FIRST_SWEEP_MS + PortalSync.SWEEP_EVERY_MS;
        sync.tick(api);

        assertTrue(api.calls.isEmpty());
    }
}
