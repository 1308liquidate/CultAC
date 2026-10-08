package ac.cult.cultac.network.codec;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProjectedRegistryTagsTest {
    static Stream<ProtocolVersion> projectedDynamicRegistryFormats() {
        return Arrays.stream(ProtocolVersion.values())
                .filter(version -> version.atLeast(ProtocolVersion.V1_21_5) && version != ProtocolVersion.V26_3);
    }

    @ParameterizedTest
    @MethodSource("projectedDynamicRegistryFormats")
    void unconsumedDynamicTagsDoNotPreventConsumedTagProjection(ProtocolVersion version) {
        // Via can inject an empty dynamic-registry payload without corresponding contents.
        // Vanilla resolves members lazily; no member means no registry ID is dereferenced.
        for (int emptyTags : new int[] {0, 1, 2}) {
            var names = new WireRegistryState(version, ModelRegistryNamesState::defaults);
            var input = Unpooled.buffer();
            try {
                Wire.writeVarInt(input, 2);
                Wire.writeString(input, "minecraft:cat_variant", Wire.MAX_STRING_LENGTH);
                Wire.writeVarInt(input, Math.min(emptyTags, 1));
                if (emptyTags >= 1) {
                    Wire.writeString(input, "test:empty", Wire.MAX_STRING_LENGTH);
                    Wire.writeVarInt(input, emptyTags == 2 ? 1 : 0);
                    if (emptyTags == 2) Wire.writeVarInt(input, 0);
                }
                Wire.writeString(input, "minecraft:block", Wire.MAX_STRING_LENGTH);
                Wire.writeVarInt(input, 1);
                Wire.writeString(input, "test:consumed", Wire.MAX_STRING_LENGTH);
                Wire.writeVarInt(input, 1);
                Wire.writeVarInt(input, 0);
                var decoded = RegistryTagValues.readProjected(input, version, names);
                assertEquals(0, input.readableBytes());
                if (emptyTags == 2) assertFalse(decoded.tags().containsKey("minecraft:cat_variant"));
                else {
                    var catTags = decoded.tags().get("minecraft:cat_variant").entries();
                    if (emptyTags == 0) assertTrue(catTags.isEmpty());
                    else assertEquals(List.of(), catTags.get("test:empty"));
                }
                assertEquals(
                        List.of(ModelRegistryNamesState.defaults().id("minecraft:block", "minecraft:air")),
                        decoded.tags().get("minecraft:block").entries().get("test:consumed"));
            } finally {
                input.release();
            }
        }
    }
}
