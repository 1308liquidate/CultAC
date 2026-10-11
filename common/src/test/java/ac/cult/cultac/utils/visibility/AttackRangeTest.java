package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.ItemComponents;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackRangeTest {
    @Test
    void absentComponentUsesDefaultPickingAndOmittedFieldsUseVanillaDefaults() {
        assertNull(ItemComponents.attackRange(Components.EMPTY));
        var components = new Components(Map.of("minecraft:attack_range", JsonParser.parseString("{}")));
        assertEquals(new ItemComponents.AttackRange(0, 3, 0, 5, .3F, 1), ItemComponents.attackRange(components));
    }

    @Test
    void preservesCustomRangesMarginAndMobFactor() {
        var components = new Components(
                Map.of(
                        "minecraft:attack_range",
                        JsonParser.parseString(
                                "{\"min_reach\":1.5,\"max_reach\":12,\"min_creative_reach\":2,\"max_creative_reach\":32,\"hitbox_margin\":0.125,\"mob_factor\":2}")));
        assertEquals(new ItemComponents.AttackRange(1.5F, 12, 2, 32, .125F, 2), ItemComponents.attackRange(components));
    }
}
