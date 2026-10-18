package ac.cult.cultac.manager.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import ac.grim.grimac.api.config.ConfigManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PortalSyncServiceTest {
    private static final List<String> LANGUAGES =
            List.of("de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "zh");
    private static final CloudSettings CLOUD =
            new CloudSettings("https://intake.example", "key", CloudSettings.OWN_SERVER_ID, "lobby", List.of(5), false);

    private static ConfigManager config(Boolean teamSync) {
        ConfigManager config = Mockito.mock(ConfigManager.class);
        if (teamSync == null) {
            Mockito.when(config.getBooleanElse(Mockito.eq("cloud.team-sync"), Mockito.anyBoolean()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
        } else {
            Mockito.when(config.getBooleanElse(Mockito.eq("cloud.team-sync"), Mockito.anyBoolean()))
                    .thenReturn(teamSync);
        }
        return config;
    }

    @Test
    void offByDefaultNothingIsSentAndTheCloudIsNotAsked() {
        AtomicInteger asked = new AtomicInteger();
        Supplier<CloudSettings> settings = () -> {
            asked.incrementAndGet();
            return CLOUD;
        };

        assertFalse(PortalSyncService.enabled(config(null), name -> null));
        assertNull(PortalSyncService.client(config(null), name -> null, settings));
        assertNull(PortalSyncService.client(config(false), name -> null, settings));
        assertEquals(0, asked.get());
    }

    @Test
    void everyShippedConfigLeavesTeamSyncOff() throws IOException {
        for (String language : LANGUAGES) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config/" + language + ".yml")) {
                assertNotNull(in, language);
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains("\n  team-sync: false"), language);
                assertTrue(text.contains("CULTAC_CLOUD_TEAM_SYNC"), language);
            }
        }
    }

    @Test
    void onItCallsTheTeamSyncPathAtTheCloudsUrl() {
        PortalSyncClient client = PortalSyncService.client(config(true), name -> null, () -> CLOUD);

        assertNotNull(client);
        assertEquals("https://intake.example/api/v1/team/sync", client.uri().toString());
    }

    @Test
    void onItSendsNothingWhileTheUploadHasNoAddressOrKey() {
        CloudSettings keyless =
                new CloudSettings("https://intake.example", "", CloudSettings.OWN_SERVER_ID, "lobby", List.of(), false);

        assertNull(PortalSyncService.client(config(true), name -> null, () -> null));
        assertNull(PortalSyncService.client(config(true), name -> null, () -> CloudSettings.OFF));
        assertNull(PortalSyncService.client(config(true), name -> null, () -> keyless));
    }

    @Test
    void theEnvironmentWinsOverTheConfig() {
        for (String yes : List.of("true", "1", "yes", " YES ")) {
            Map<String, String> env = Map.of(PortalSyncService.ENV, yes);
            assertTrue(PortalSyncService.enabled(config(false), env::get), yes);
            assertNotNull(PortalSyncService.client(config(false), env::get, () -> CLOUD), yes);
        }
        for (String no : List.of("false", "0", "no", "False")) {
            Map<String, String> env = Map.of(PortalSyncService.ENV, no);
            assertFalse(PortalSyncService.enabled(config(true), env::get), no);
            assertNull(PortalSyncService.client(config(true), env::get, () -> CLOUD), no);
        }
        assertTrue(PortalSyncService.enabled(config(true), Map.of(PortalSyncService.ENV, "")::get));
        assertTrue(PortalSyncService.enabled(config(true), Map.of(PortalSyncService.ENV, "maybe")::get));
        assertFalse(PortalSyncService.enabled(config(null), Map.of(PortalSyncService.ENV, "maybe")::get));
    }
}
