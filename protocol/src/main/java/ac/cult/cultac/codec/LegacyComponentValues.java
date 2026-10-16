package ac.cult.cultac.codec;

import ac.cult.shaded.vialib.api.minecraft.Holder;
import ac.cult.shaded.vialib.api.minecraft.codec.CodecContext;
import ac.cult.shaded.vialib.api.minecraft.data.StructuredData;
import ac.cult.shaded.vialib.api.minecraft.item.data.AttributeModifiers1_21;
import ac.cult.shaded.vialib.api.minecraft.item.data.Enchantments;
import ac.cult.shaded.vialib.api.minecraft.item.data.JukeboxPlayable;
import ac.cult.shaded.vialib.api.minecraft.item.data.Unbreakable;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrim1_20_5;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrimMaterial1_20_5;
import ac.cult.shaded.vialib.api.minecraft.item.data.trim.ArmorTrimPattern;
import ac.cult.shaded.vialib.codec.nbt.NbtOps;
import ac.cult.shaded.vialib.nbt.tag.CompoundTag;
import ac.cult.shaded.vialib.nbt.tag.StringTag;
import ac.cult.shaded.vialib.nbt.tag.Tag;

/**
 * Persistent records through 1.21.4 whose Via types only supply a wire writer.
 * Each wraps its 1.21.5 value with the optional show_in_tooltip (default true)
 * that 1.21.5 moved into TooltipDisplay.
 */
final class LegacyComponentValues {
    private LegacyComponentValues() {}

    /** The native persistent value, or null when the component's type has an NBT writer. */
    static Tag component(StructuredData<?> data, String name, CodecContext context) {
        Object value = data.value();
        var type = data.key().type();
        if (value instanceof AttributeModifiers1_21 modifiers && type == AttributeModifiers1_21.TYPE1_21) {
            var attributes = new CompoundTag();
            attributes.put("modifiers", NbtOps.serialize(context, AttributeModifiers1_21.TYPE1_21_5, modifiers));
            return tooltip(attributes, modifiers.showInTooltip());
        }
        if (value instanceof Unbreakable unbreakable) return tooltip(new CompoundTag(), unbreakable.showInTooltip());
        // ItemEnchantments.CODEC encodes FULL_CODEC; projected enchantments keep the model shape.
        if (value instanceof Enchantments enchantments
                && type == Enchantments.TYPE1_20_5
                && name.equals("stored_enchantments")) {
            var result = new CompoundTag();
            result.put("levels", NbtOps.serialize(context, Enchantments.TYPE1_21_5, enchantments));
            return tooltip(result, enchantments.showInTooltip());
        }
        if (value instanceof JukeboxPlayable playable && type == JukeboxPlayable.TYPE1_21) {
            var result = new CompoundTag();
            result.put("song", NbtOps.serialize(context, JukeboxPlayable.TYPE1_21_5, playable));
            return tooltip(result, playable.showInTooltip());
        }
        if (value instanceof ArmorTrim1_20_5 trim && type != ArmorTrim1_20_5.TYPE1_21_5) {
            var result = new CompoundTag();
            result.put("material", material(trim.material(), type == ArmorTrim1_20_5.TYPE1_21_4, context));
            result.put("pattern", pattern(trim.pattern(), context));
            return tooltip(result, trim.showInTooltip());
        }
        return null;
    }

    private static CompoundTag tooltip(CompoundTag value, boolean shown) {
        if (!shown) value.putBoolean("show_in_tooltip", false);
        return value;
    }

    // RegistryFileCodec encodes a registered holder as its key and a direct holder inline.
    private static Tag material(Holder<ArmorTrimMaterial1_20_5> holder, boolean assets, CodecContext context) {
        if (holder.hasId())
            return new StringTag(context.registryAccess()
                    .registryKey("trim_material", holder.id())
                    .toString());
        var material = holder.value();
        var result = new CompoundTag();
        result.putString("asset_name", material.assetName());
        result.putString(
                "ingredient", context.registryAccess().item(material.itemId()).toString());
        // 1.21.4 removed item_model_index and keyed overrides by equipment asset.
        if (!assets) result.putFloat("item_model_index", material.itemModelIndex());
        if (!material.overrideArmorMaterials().isEmpty()) {
            var overrides = new CompoundTag();
            material.overrideArmorMaterials().forEach(overrides::putString);
            result.put(assets ? "override_armor_assets" : "override_armor_materials", overrides);
        }
        result.put("description", material.description());
        return result;
    }

    private static Tag pattern(Holder<ArmorTrimPattern> holder, CodecContext context) {
        if (holder.hasId())
            return new StringTag(context.registryAccess()
                    .registryKey("trim_pattern", holder.id())
                    .toString());
        var pattern = holder.value();
        var result = new CompoundTag();
        result.putString("asset_id", pattern.assetName());
        result.putString(
                "template_item", context.registryAccess().item(pattern.itemId()).toString());
        result.put("description", pattern.description());
        // fieldOf("decal").orElse(false) only defaults on decode; encode always writes it.
        result.putBoolean("decal", pattern.decal());
        return result;
    }
}
