package ac.cult.cultac.utils.latency;

import ac.cult.cultac.network.packet.LightValues;
import ac.cult.cultac.network.packet.LightValues.Layer;

/** Packet-owned light values. Uniform sections retain a nibble instead of a 2 KiB array. */
final class CompensatedLight {
    private final Layer[] sky;
    private final Layer[] block;

    CompensatedLight(int sections) {
        sky = new Layer[sections + 2];
        block = new Layer[sections + 2];
    }

    void apply(LightValues values) {
        apply(sky, values, true);
        apply(block, values, false);
    }

    private static void apply(Layer[] layers, LightValues values, boolean skyLight) {
        int incoming = 0;
        for (int i = 0; i < layers.length; i++) {
            if (values.hasUpdate(skyLight, i)) {
                Layer layer = values.update(skyLight, incoming++);
                layer.validate();
                layers[i] = layer;
            } else if (values.isEmpty(skyLight, i)) layers[i] = Layer.empty();
        }
    }

    int brightness(int x, int y, int z, int minY, boolean hasSkyLight) {
        int section = (y >> 4) - (minY >> 4) + 1;
        int blockValue = section >= 0 && section < block.length && block[section] != null
                ? block[section].get(x & 15, y & 15, z & 15)
                : 0;
        return Math.max(blockValue, skyBrightness(x, y, z, minY, hasSkyLight));
    }

    /** LevelReader.canSeeSky reads the sky layer, independently of emitted block light. */
    int skyBrightness(int x, int y, int z, int minY, boolean hasSkyLight) {
        if (!hasSkyLight) return 0;
        int section = (y >> 4) - (minY >> 4) + 1;
        // SkyLightSectionStorage reads the bottom row of the next available section
        // when a layer is missing, and reads 15 above the column's top light section.
        int skyValue = 15;
        for (int i = Math.max(0, section); i < sky.length; i++) {
            if (sky[i] != null) {
                skyValue = sky[i].get(x & 15, i == section ? y & 15 : 0, z & 15);
                break;
            }
        }
        return skyValue;
    }
}
