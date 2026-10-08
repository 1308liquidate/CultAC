package ac.cult.blocksim.behavior;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.InteractionRegistries;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.interaction.SimInteraction;
import ac.cult.blocksim.interaction.UseContext;
import com.google.gson.JsonPrimitive;

/** Native AxeItem/ShovelItem/HoeItem contracts before component-based tools in 26.3. */
public final class LegacyToolBehavior {
    private final BlockTransformerBehavior axes;
    public LegacyToolBehavior(DataTables data) {
        // Older clients do not receive the block_transformer registry or its overrides.
        axes = new BlockTransformerBehavior(data, InteractionRegistries.defaults());
    }
    public SimInteraction useOn(UseContext context) {
        String tool = switch (context.usedItem().key()) {
            case "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:copper_axe", "minecraft:iron_axe",
                 "minecraft:golden_axe", "minecraft:diamond_axe", "minecraft:netherite_axe" -> "axe";
            case "minecraft:wooden_shovel", "minecraft:stone_shovel", "minecraft:copper_shovel", "minecraft:iron_shovel",
                 "minecraft:golden_shovel", "minecraft:diamond_shovel", "minecraft:netherite_shovel" -> "shovel";
            case "minecraft:wooden_hoe", "minecraft:stone_hoe", "minecraft:copper_hoe", "minecraft:iron_hoe",
                 "minecraft:golden_hoe", "minecraft:diamond_hoe", "minecraft:netherite_hoe" -> "hoe";
            default -> "";
        };
        if (tool.isEmpty()) return SimInteraction.PASS;
        if (tool.equals("axe")) return axes.useOn(context, new JsonPrimitive("minecraft:axe"));
        var level = context.level();
        int state = level.stateAt(context.clickedPos());
        String block = level.registry().block(state).key();
        // HoeItem's rooted-dirt predicate is unconditional; both tools mutate only on ServerLevel.
        if (tool.equals("hoe") && block.equals("minecraft:rooted_dirt")) return SimInteraction.SUCCESS;
        if (context.clickedFace() == Direction.DOWN) return SimInteraction.PASS;
        if (tool.equals("shovel") && (block.equals("minecraft:campfire") || block.equals("minecraft:soul_campfire"))
            && level.registry().value(state, "lit").equals("true")) return SimInteraction.SUCCESS;
        boolean valid = tool.equals("shovel")
            ? switch (block) {
                case "minecraft:grass_block", "minecraft:dirt", "minecraft:podzol", "minecraft:coarse_dirt",
                     "minecraft:mycelium", "minecraft:rooted_dirt" -> true;
                default -> false;
            }
            : switch (block) {
                case "minecraft:grass_block", "minecraft:dirt_path", "minecraft:dirt", "minecraft:coarse_dirt" -> true;
                default -> false;
            };
        return valid && level.registry().facts(level.stateAt(context.clickedPos().relative(Direction.UP))).has(StateFacts.AIR)
            ? SimInteraction.SUCCESS : SimInteraction.PASS;
    }
}
