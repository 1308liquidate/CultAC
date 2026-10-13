package ac.cult.cultac.codec;

import ac.cult.cultac.protocol.WireValueDecoder.NbtFactory;
import ac.cult.shaded.vialib.nbt.tag.*;
import java.util.ArrayList;
import java.util.HashMap;

/** Matches the existing unnamed-NBT read, including zero normalization and list unwrapping. */
final class PortableNbt {
    private PortableNbt() {}

    static <T> T read(Tag tag, NbtFactory<T> factory) {
        return read(tag, factory, 0);
    }

    private static <T> T read(Tag tag, NbtFactory<T> factory, int depth) {
        if (tag instanceof NumberTag number) {
            Number value = number.getValue();
            if (value instanceof Float && value.floatValue() == 0F) value = 0F;
            else if (value instanceof Double && value.doubleValue() == 0D) value = 0D;
            return factory.number(tag.getTagId(), value);
        }
        if (tag instanceof StringTag text) return factory.text(text.getValue());
        if (tag instanceof ByteArrayTag array) {
            var values = new ArrayList<Long>(array.getValue().length);
            for (byte value : array.getValue()) values.add((long) value);
            return factory.array(7, values);
        }
        if (tag instanceof IntArrayTag array) {
            var values = new ArrayList<Long>(array.getValue().length);
            for (int value : array.getValue()) values.add((long) value);
            return factory.array(11, values);
        }
        if (tag instanceof LongArrayTag array) {
            var values = new ArrayList<Long>(array.getValue().length);
            for (long value : array.getValue()) values.add(value);
            return factory.array(12, values);
        }
        if (depth >= 512) throw new IllegalArgumentException("NBT depth exceeds 512");
        if (tag instanceof CompoundTag compound) {
            var values = new HashMap<String, T>();
            compound.getValue().forEach((key, value) -> values.put(key, read(value, factory, depth + 1)));
            return factory.compound(values);
        }
        if (tag instanceof ListTag<?> sequence) {
            var values = new ArrayList<T>(sequence.size());
            for (Tag child : sequence) {
                // ListTag.TYPE.load unwraps one marker compound at the list boundary.
                if (child instanceof CompoundTag compound && compound.size() == 1 && compound.contains("")) {
                    if (depth + 1 >= 512) throw new IllegalArgumentException("NBT depth exceeds 512");
                    values.add(read(compound.get(""), factory, depth + 2));
                } else values.add(read(child, factory, depth + 1));
            }
            return factory.sequence(values);
        }
        throw new IllegalArgumentException("Invalid NBT tag type " + tag.getTagId());
    }
}
