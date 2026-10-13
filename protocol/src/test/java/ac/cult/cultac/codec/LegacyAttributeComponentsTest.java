package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.WireValueDecoder;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.shaded.vialib.api.type.Types;
import ac.cult.shaded.vialib.nbt.tag.CompoundTag;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LegacyAttributeComponentsTest {

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_3", "V1_21_4"})
    void modifiersAndTooltipAreRetainedFromTheNativeLegacyRecord(ProtocolVersion version) throws Exception {
        var data = ModelRegistryData.load(version);
        var names = new WireValueDecoder.Registries() {
            public String name(String registry, int id) {
                return data.registry(registry).name(id);
            }

            public int id(String registry, String key) {
                return data.registry(registry).id(key);
            }
        };
        try (var decoder = new PrivateCodecService()) {
            for (boolean tooltip : new boolean[] {true, false}) {
                var input = Unpooled.buffer();
                try {
                    Wire.writeVarInt(input, 1);
                    Wire.writeVarInt(input, data.registry("minecraft:item").id("minecraft:stick"));
                    Wire.writeVarInt(input, 1);
                    Wire.writeVarInt(input, 0);
                    Wire.writeVarInt(
                            input,
                            data.registry("minecraft:data_component_type").id("minecraft:attribute_modifiers"));
                    Wire.writeVarInt(input, 1);
                    Wire.writeVarInt(input, data.registry("minecraft:attribute").id("minecraft:movement_speed"));
                    Types.STRING.write(input, "test:speed");
                    input.writeDouble(-0.0);
                    Wire.writeVarInt(input, 0);
                    Wire.writeVarInt(input, 1);
                    input.writeBoolean(tooltip);
                    var item = decoder.item(version, input, false, names);
                    assertFalse(input.isReadable());
                    var encoded = Unpooled.wrappedBuffer(item.components());
                    try {
                        CompoundTag patch = Types.COMPOUND_TAG.read(encoded);
                        var attributes = patch.getCompoundTag("minecraft:attribute_modifiers");
                        assertNotNull(attributes);
                        var modifier = attributes
                                .getListTag("modifiers", CompoundTag.class)
                                .get(0);
                        assertEquals(
                                "minecraft:movement_speed",
                                modifier.getStringTag("type").getValue());
                        assertEquals("test:speed", modifier.getStringTag("id").getValue());
                        assertEquals(
                                "add_value", modifier.getStringTag("operation").getValue());
                        assertEquals("mainhand", modifier.getStringTag("slot").getValue());
                        assertEquals(-0.0, modifier.getDoubleTag("amount").asDouble());
                        assertEquals(
                                tooltip,
                                attributes.getByteTag("show_in_tooltip") == null
                                        || attributes
                                                        .getByteTag("show_in_tooltip")
                                                        .asByte()
                                                != 0);
                        assertFalse(encoded.isReadable());
                        assertNotNull(item.componentEncodings().get("minecraft:attribute_modifiers"));
                    } finally {
                        encoded.release();
                    }
                } finally {
                    input.release();
                }
            }
        }
    }
}
