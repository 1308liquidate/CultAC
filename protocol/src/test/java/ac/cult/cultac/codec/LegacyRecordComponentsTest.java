package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.shaded.vialib.api.minecraft.Holder;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredDataKey;
import ac.cult.shaded.vialib.api.minecraft.item.data.Enchantments;
import ac.cult.shaded.vialib.api.minecraft.item.data.JukeboxPlayable;
import ac.cult.shaded.vialib.api.minecraft.item.data.Unbreakable;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrim1_20_5;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrimMaterial1_20_5;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrimPattern;
import ac.cult.shaded.vialib.api.type.Type;
import ac.cult.shaded.vialib.api.type.Types;
import ac.cult.shaded.vialib.nbt.tag.CompoundTag;
import ac.cult.shaded.vialib.nbt.tag.StringTag;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Records through 1.21.4 whose Via types only supply a wire writer keep their native persistent shape. */
class LegacyRecordComponentsTest {

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_3", "V1_21_4"})
    void registeredHoldersAndHiddenTooltipsUseTheNativeCodecs(ProtocolVersion version) throws Exception {
        var data = ModelRegistryData.load(version);
        var components = new LinkedHashMap<String, Object>();
        components.put("minecraft:unbreakable", new Unbreakable(false));
        var stored = new Enchantments(false);
        stored.add(3, 2);
        components.put("minecraft:stored_enchantments", stored);
        components.put("minecraft:trim", new ArmorTrim1_20_5(Holder.of(1), Holder.of(2), false));
        components.put("minecraft:jukebox_playable", new JukeboxPlayable(Holder.of(4), false));
        var patch = decode(version, data, components);

        assertEquals(hidden(), patch.getCompoundTag("minecraft:unbreakable"));
        var enchantments = patch.getCompoundTag("minecraft:stored_enchantments");
        assertEquals(2, enchantments.getCompoundTag("levels").getInt("minecraft:enchantment_3"));
        assertFalse(enchantments.getBoolean("show_in_tooltip", true));
        var trim = patch.getCompoundTag("minecraft:trim");
        assertEquals("minecraft:trim_material_1", trim.getString("material"));
        assertEquals("minecraft:trim_pattern_2", trim.getString("pattern"));
        assertFalse(trim.getBoolean("show_in_tooltip", true));
        var jukebox = patch.getCompoundTag("minecraft:jukebox_playable");
        assertEquals("minecraft:jukebox_song_4", jukebox.getString("song"));
        assertFalse(jukebox.getBoolean("show_in_tooltip", true));
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_3", "V1_21_4"})
    void directTrimHoldersUseTheVersionsDirectCodecs(ProtocolVersion version) throws Exception {
        var data = ModelRegistryData.load(version);
        var items = data.registry("minecraft:item");
        var material = new ArmorTrimMaterial1_20_5(
                "test",
                items.id("minecraft:diamond"),
                0.5F,
                Map.of("minecraft:diamond", "test_darker"),
                new StringTag("Material"));
        var pattern =
                new ArmorTrimPattern("minecraft:test", items.id("minecraft:stick"), new StringTag("Pattern"), false);
        var patch = decode(
                version,
                data,
                Map.of("minecraft:trim", new ArmorTrim1_20_5(Holder.of(material), Holder.of(pattern), true)));

        var trim = patch.getCompoundTag("minecraft:trim");
        assertFalse(trim.contains("show_in_tooltip"));
        var materialTag = trim.getCompoundTag("material");
        assertEquals("test", materialTag.getString("asset_name"));
        assertEquals("minecraft:diamond", materialTag.getString("ingredient"));
        assertEquals("Material", materialTag.getString("description"));
        boolean assets = version == ProtocolVersion.V1_21_4;
        assertEquals(!assets, materialTag.contains("item_model_index"));
        var overrides = materialTag.getCompoundTag(assets ? "override_armor_assets" : "override_armor_materials");
        assertEquals("test_darker", overrides.getString("minecraft:diamond"));
        var patternTag = trim.getCompoundTag("pattern");
        assertEquals("minecraft:test", patternTag.getString("asset_id"));
        assertEquals("minecraft:stick", patternTag.getString("template_item"));
        assertEquals("Pattern", patternTag.getString("description"));
        assertFalse(patternTag.getBoolean("decal", true));
    }

    private static CompoundTag hidden() {
        var tag = new CompoundTag();
        tag.putBoolean("show_in_tooltip", false);
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static CompoundTag decode(ProtocolVersion version, ModelRegistryData data, Map<String, Object> components)
            throws Exception {
        var names = new WireValueDecoder.Registries() {
            public String name(String registry, int id) {
                return switch (registry) {
                    case "minecraft:enchantment",
                            "minecraft:trim_material",
                            "minecraft:trim_pattern",
                            "minecraft:jukebox_song" -> registry + "_" + id;
                    default -> data.registry(registry).name(id);
                };
            }

            public int id(String registry, String key) {
                return data.registry(registry).id(key);
            }
        };
        var componentTypes = data.registry("minecraft:data_component_type");
        try (var decoder = new PrivateCodecService()) {
            var input = Unpooled.buffer();
            try {
                Wire.writeVarInt(input, 1);
                Wire.writeVarInt(input, data.registry("minecraft:item").id("minecraft:stick"));
                Wire.writeVarInt(input, components.size());
                Wire.writeVarInt(input, 0);
                for (var component : components.entrySet()) {
                    int id = componentTypes.id(component.getKey());
                    StructuredDataKey<?> key =
                            PrivateCodecService.types(version).structuredData().key(id);
                    Wire.writeVarInt(input, id);
                    ((Type<Object>) key.type()).write(input, component.getValue());
                }
                var item = decoder.item(version, input, false, names);
                assertFalse(input.isReadable());
                for (var component : components.keySet())
                    assertNotNull(item.componentEncodings().get(component));
                ByteBuf encoded = Unpooled.wrappedBuffer(item.components());
                try {
                    var patch = Types.COMPOUND_TAG.read(encoded);
                    assertFalse(encoded.isReadable());
                    return patch;
                } finally {
                    encoded.release();
                }
            } finally {
                input.release();
            }
        }
    }
}
