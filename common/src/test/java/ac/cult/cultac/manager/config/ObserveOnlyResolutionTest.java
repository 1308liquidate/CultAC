package ac.cult.cultac.manager.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.grim.grimac.api.config.ConfigManager;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ObserveOnlyResolutionTest {
    @Test
    void theConfigWinsAndTheEnvironmentOnlyFillsAnAbsentKey() {
        assertFalse(BaseConfigManager.resolveObserveOnly(configured(false), env("true")));
        assertTrue(BaseConfigManager.resolveObserveOnly(configured(true), env("false")));
        assertTrue(BaseConfigManager.resolveObserveOnly(absent(), env(" 1 ")));
        assertTrue(BaseConfigManager.resolveObserveOnly(absent(), env("YES")));
        assertFalse(BaseConfigManager.resolveObserveOnly(absent(), env("no")));
        assertFalse(BaseConfigManager.resolveObserveOnly(absent(), env(null)));
    }

    private static ConfigManager configured(boolean value) {
        ConfigManager config = mock(ConfigManager.class);
        when(config.getBooleanElse(eq("observe-only"), anyBoolean())).thenReturn(value);
        return config;
    }

    private static ConfigManager absent() {
        ConfigManager config = mock(ConfigManager.class);
        when(config.getBooleanElse(eq("observe-only"), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return config;
    }

    private static Function<String, String> env(String value) {
        return name -> name.equals("CULTAC_OBSERVE_ONLY") ? value : null;
    }
}
