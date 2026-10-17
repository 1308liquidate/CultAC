package ac.cult.blocksim;

import ac.cult.blocksim.behavior.ConsumableBehavior;
import ac.cult.blocksim.data.*;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.*;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Original client action contracts, including the release on each side of every changed boundary. */
class VersionedClientActionsTest {
    private static final DataTables DATA = DataTables.defaults();
    private static final ItemRegistry ITEMS = new ItemRegistry(DATA);
    private static final BlockSimulator SIM = simulator(InteractionRegistries.defaults());
    private static final BlockPos FLOOR = new BlockPos(3, 63, 3), ABOVE = FLOOR.relative(Direction.UP);

    private static BlockSimulator simulator(InteractionRegistries registries) {
        return BlockSimulator.create(DATA, ITEMS, registries, (context, state, shape) -> true,
                new ConsumableBehavior(context -> { throw new AssertionError("Not consuming food"); }));
    }
    private static int state(String key) { return DATA.registry().block("minecraft:" + key).defaultState(); }
    private static SimPlayer owner(String item, int count, boolean creative, boolean sneaking, float yaw, float pitch, Vec3 position) {
        var player = new SimPlayer(new SimPlayer.State(position, yaw, pitch, sneaking, creative, true, false,
                creative ? SimPlayer.GameMode.CREATIVE : SimPlayer.GameMode.SURVIVAL),
                ITEMS.stack("minecraft:" + item, count), ITEMS.empty());
        return new SimPlayer(player.state(), player.inventory(), null,
                new SimPlayer.Sight(position.addScaled(new Vec3(0, 1, 0), 1.62), 5.0), null, null);
    }
    private static SimPlayer owner(String item, int count) {
        return owner(item, count, false, false, 0, 0, new Vec3(5.5, 64, 1.5));
    }
    private static SimAction.UseOn click(BlockPos pos, Direction face) {
        return new SimAction.UseOn(Hand.MAIN_HAND, new BlockHit(pos, face,
                new Vec3(pos.x() + .5 + face.x() * .5, pos.y() + .5 + face.y() * .5, pos.z() + .5 + face.z() * .5), false));
    }
    private static SimResult run(BlockSimulator simulator, World world, SimPlayer owner, BlockSimClientVersion version,
                                 SimCooldowns cooldowns, SimAction action) {
        var result = simulator.simulate(action, new SimInput(world, owner, cooldowns, null,
                BreakSession.State.initial(ITEMS.empty()), HolderSets.Overlay.EMPTY, version));
        assertNull(result.decline(), version.toString());
        return result;
    }
    private static SimResult run(World world, SimPlayer owner, BlockSimClientVersion version, SimAction action) {
        return run(SIM, world, owner, version, new SimCooldowns(0, Map.of()), action);
    }

    @Test void bedHeadWriteStartsAt26_2() {
        // BedBlock#setPlacedBy: !isClientSide before 26.2, setBlockAndUpdate on both sides thereafter.
        for (var version : BlockSimClientVersion.values()) {
            var result = run(new World(), owner("red_bed", 1), version, click(FLOOR, Direction.UP));
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind(), version.toString());
            assertEquals(version.protocol() >= 776 ? 2 : 1, result.writes().size(), version.toString());
            assertTrue(result.writes().stream().anyMatch(w -> w.pos().equals(ABOVE)
                    && DATA.registry().value(w.newState(), "part").equals("foot")));
            assertEquals(version.protocol() >= 776, result.writes().stream().anyMatch(w -> w.pos().equals(ABOVE.relative(Direction.SOUTH))
                    && DATA.registry().value(w.newState(), "part").equals("head")), version.toString());
        }
    }

    @Test void useOnRemainderAndCooldownStartAt26_3AndDoNotMutateInputs() {
        // ItemStack#useOn acquired applyAfterUseComponentSideEffects in 26.3; use() already had it.
        for (var version : BlockSimClientVersion.values()) {
            var player = owner("stone", 1);
            player.hand(Hand.MAIN_HAND).setComponent("minecraft:use_remainder", CanonicalSnbt.parse("{id:\"minecraft:bowl\",count:1}"));
            player.hand(Hand.MAIN_HAND).setComponent("minecraft:use_cooldown", CanonicalSnbt.parse("{seconds:5.0f}"));
            var result = run(new World(), player, version, click(FLOOR, Direction.UP));
            assertEquals(version.protocol() == 777 ? "minecraft:bowl" : "minecraft:air", result.player().hand(Hand.MAIN_HAND).itemKey(), version.toString());
            assertEquals(version.protocol() == 777, !result.cooldowns().snapshot().isEmpty(), version.toString());
            assertEquals(1, player.hand(Hand.MAIN_HAND).count());
        }
    }

    @Test void olderClientsCanPlaceAgainWithoutAnInventedUseOnCooldown() {
        for (var version : BlockSimClientVersion.values()) {
            var world = new World(); var player = owner("stone", 2);
            player.hand(Hand.MAIN_HAND).setComponent("minecraft:use_cooldown", CanonicalSnbt.parse("{seconds:5.0f}"));
            var first = run(world, player, version, click(FLOOR, Direction.UP));
            world.apply(first);
            var second = run(SIM, world, first.player(), version, first.cooldowns(), click(FLOOR.relative(Direction.EAST), Direction.UP));
            assertEquals(version.protocol() == 777 ? InteractionKind.PASS : InteractionKind.SUCCESS, second.interaction().kind(), version.toString());
            assertEquals(version.protocol() == 777 ? 0 : 1, second.writes().size(), version.toString());
            assertEquals(version.protocol() == 777 ? 1 : 0, second.player().hand(Hand.MAIN_HAND).count(), version.toString());
        }
    }

    @Test void instantUseStillAppliesItsOlderAfterUseHooks() {
        for (var version : BlockSimClientVersion.values()) {
            var player = owner("snowball", 1);
            player.hand(Hand.MAIN_HAND).setComponent("minecraft:use_remainder", CanonicalSnbt.parse("{id:\"minecraft:bowl\",count:1}"));
            player.hand(Hand.MAIN_HAND).setComponent("minecraft:use_cooldown", CanonicalSnbt.parse("{seconds:5.0f}"));
            var result = run(new World(), player, version, new SimAction.Use(Hand.MAIN_HAND));
            assertEquals("minecraft:bowl", result.player().hand(Hand.MAIN_HAND).itemKey(), version.toString());
            assertFalse(result.cooldowns().snapshot().isEmpty(), version.toString());
        }
    }

    @Test void tntIgnitionRemovalAndChargeConsumptionEndAt1_21_5() {
        // TntBlock#useItemOn wrote AIR locally until prime() moved behind ServerLevel in 1.21.5.
        for (var version : BlockSimClientVersion.values()) for (boolean creative : new boolean[]{false, true})
            for (String item : new String[]{"fire_charge", "flint_and_steel"}) {
                var world = new World(); world.states.put(ABOVE, state("tnt"));
                var player = owner(item, 2, creative, false, 0, 0, new Vec3(5.5, 64, 1.5));
                var result = run(world, player, version, click(ABOVE, Direction.NORTH));
                assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
                assertEquals(version.protocol() < 770 ? 1 : 0, result.writes().size(), version.toString());
                if (!result.writes().isEmpty()) assertEquals(state("air"), result.writes().getFirst().newState());
                assertEquals(version.protocol() < 770 && !creative && item.equals("fire_charge") ? 1 : 2,
                        result.player().hand(Hand.MAIN_HAND).count(), version.toString());
            }
    }

    @Test void sneakingBypassesWaterloggingOnlyFrom1_21_9() {
        for (var version : BlockSimClientVersion.values()) for (boolean sneaking : new boolean[]{false, true}) {
            var world = new World(); world.states.put(ABOVE, state("oak_fence"));
            var player = owner("water_bucket", 1, false, sneaking, 0, 20, new Vec3(3.5, 64, 1.5));
            var result = run(world, player, version, new SimAction.Use(Hand.MAIN_HAND));
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind(), version.toString());
            boolean adjacentWater = sneaking && version.protocol() >= 773;
            assertEquals(adjacentWater ? 1 : 0, result.writes().size(), version.toString());
            if (adjacentWater) {
                assertEquals(new BlockPos(3, 64, 2), result.writes().getFirst().pos());
                assertEquals(state("water"), result.writes().getFirst().newState());
            }
            assertEquals("minecraft:bucket", result.player().hand(Hand.MAIN_HAND).itemKey());
        }
    }

    @Test void arbitraryTransformerComponentsStartAt26_3() {
        for (var version : BlockSimClientVersion.values()) {
            var world = new World(); world.states.put(FLOOR, state("grass_block"));
            var player = owner("stick", 1);
            player.hand(Hand.MAIN_HAND).components(player.hand(Hand.MAIN_HAND).components().with("minecraft:block_transformer", JsonParser.parseString("\"minecraft:shovel\"")));
            var result = run(world, player, version, click(FLOOR, Direction.UP));
            assertEquals(version.protocol() == 777 ? InteractionKind.SUCCESS : InteractionKind.PASS, result.interaction().kind(), version.toString());
            assertEquals(version.protocol() == 777 ? 1 : 0, result.writes().size(), version.toString());
            if (!result.writes().isEmpty()) assertEquals(state("dirt_path"), result.writes().getFirst().newState());
        }
    }

    @Test void olderAxesUseIdentityAndIgnoreComponentAndRegistryOverrides() {
        var override = JsonParser.parseString("[{\"block_state_provider\":{\"id\":\"minecraft:gold_block\"}}]");
        var custom = simulator(InteractionRegistries.defaults().withRegistry("block_transformer", Map.of("minecraft:axe", override)));
        for (var version : BlockSimClientVersion.values()) {
            if (version.protocol() == 777) continue;
            var world = new World(); world.states.put(FLOOR, DATA.registry().with(state("oak_log"), "axis", "x"));
            var player = owner("diamond_axe", 2);
            player.hand(Hand.MAIN_HAND).components(player.hand(Hand.MAIN_HAND).components().overlay(Components.EMPTY, java.util.Set.of("minecraft:block_transformer"))
                    .with("minecraft:max_stack_size", JsonParser.parseString("8")));
            var result = run(custom, world, player, version, new SimCooldowns(0, Map.of()), click(FLOOR, Direction.UP));
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind(), version.toString());
            assertEquals(DATA.registry().with(state("stripped_oak_log"), "axis", "x"), result.writes().getFirst().newState(), version.toString());
            assertEquals(2, result.player().hand(Hand.MAIN_HAND).count(), version.toString());
        }
    }

    @Test void olderHoesAndShovelsReturnSuccessButWaitForServerBlockChanges() {
        // Native HoeItem/ShovelItem mutate inside !isClientSide; AxeItem mutates on both sides.
        for (var version : BlockSimClientVersion.values()) for (String item : new String[]{"diamond_hoe", "diamond_shovel"}) {
            var world = new World(); world.states.put(FLOOR, state("grass_block"));
            var result = run(world, owner(item, 1), version, click(FLOOR, Direction.UP));
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind(), version.toString());
            assertEquals(version.protocol() == 777 ? 1 : 0, result.writes().size(), version.toString());
            if (!result.writes().isEmpty()) assertEquals(state(item.endsWith("hoe") ? "farmland" : "dirt_path"), result.writes().getFirst().newState());
        }
    }

    @Test void axeBlockingIntentUsesShieldIdentityBeforeBlocksAttacksWasIntroduced() {
        for (var version : BlockSimClientVersion.values()) for (boolean removedShieldComponent : new boolean[]{false, true}) {
            var world = new World(); world.states.put(FLOOR, state("oak_log"));
            var player = owner("diamond_axe", 1);
            var offhand = ITEMS.stack(removedShieldComponent ? "minecraft:shield" : "minecraft:stick", 1);
            offhand.components(removedShieldComponent
                    ? offhand.components().overlay(Components.EMPTY, java.util.Set.of("minecraft:blocks_attacks"))
                    : offhand.components().with("minecraft:blocks_attacks", JsonParser.parseString("{}")));
            player.hand(Hand.OFF_HAND, offhand);
            var result = run(world, player, version, click(FLOOR, Direction.UP));
            boolean blocking = version.protocol() < 770 ? removedShieldComponent : !removedShieldComponent;
            assertEquals(blocking ? InteractionKind.PASS : InteractionKind.SUCCESS, result.interaction().kind(), version.toString());
            assertEquals(blocking ? 0 : 1, result.writes().size(), version.toString());
        }
    }

    @Test void sneakingPreservesAxeUseWithAnOffhandBlockingItem() {
        for (var version : BlockSimClientVersion.values()) {
            var world = new World(); world.states.put(FLOOR, state("oak_log"));
            var player = owner("diamond_axe", 1, false, true, 0, 0, new Vec3(5.5, 64, 1.5));
            player.hand(Hand.OFF_HAND, ITEMS.stack("minecraft:shield", 1));
            var result = run(world, player, version, click(FLOOR, Direction.UP));
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind(), version.toString());
            assertEquals(state("stripped_oak_log"), result.writes().getFirst().newState(), version.toString());
        }
    }

    @Test void oldToolPredicatesDoNotAcquireModernOffhandOrFaceRestrictions() {
        var world = new World(); world.states.put(FLOOR, state("rooted_dirt"));
        var player = owner("diamond_hoe", 1); player.hand(Hand.OFF_HAND, ITEMS.stack("minecraft:shield", 1));
        var result = run(world, player, BlockSimClientVersion.V1_21_11, click(FLOOR, Direction.DOWN));
        assertEquals(InteractionKind.SUCCESS, result.interaction().kind()); assertTrue(result.writes().isEmpty());
        player = owner("diamond_shovel", 1); world.states.put(FLOOR, state("grass_block"));
        result = run(world, player, BlockSimClientVersion.V1_21_11, click(FLOOR, Direction.DOWN));
        assertEquals(InteractionKind.PASS, result.interaction().kind());
    }

    @Test void campfireBlockDispatchDoesNotPreemptTheOlderShovelContract() {
        // CampfireBlock's douses_campfires branch was introduced in 26.3, before tool dispatch.
        for (var version : BlockSimClientVersion.values()) for (var face : new Direction[]{Direction.UP, Direction.DOWN}) {
            var world = new World(); world.states.put(FLOOR, state("campfire"));
            var result = run(world, owner("diamond_shovel", 1), version, click(FLOOR, face));
            assertEquals(version.protocol() == 777 || face != Direction.DOWN ? InteractionKind.SUCCESS : InteractionKind.PASS,
                    result.interaction().kind(), version.toString());
            assertEquals(version.protocol() == 777 ? 1 : 0, result.writes().size(), version.toString());
            if (!result.writes().isEmpty()) assertEquals("false", DATA.registry().value(result.writes().getFirst().newState(), "lit"));
        }
    }

    @Test void hangingSignDirectionUsesTheClientMathBoundary() {
        for (var version : BlockSimClientVersion.values()) {
            var world = new World(); world.states.put(ABOVE.relative(Direction.EAST), state("stone"));
            world.states.put(ABOVE.relative(Direction.SOUTH), state("stone"));
            var player = owner("oak_hanging_sign", 1, false, false, 45, 29.802204F, new Vec3(5.5, 64, 1.5));
            var result = run(world, player, version, click(FLOOR, Direction.UP));
            assertEquals(InteractionKind.SUCCESS, result.interaction().kind());
            assertEquals(version.protocol() < 774 ? "north" : "east", DATA.registry().value(result.writes().getFirst().newState(), "facing"), version.toString());
        }
    }

    @Test void harvestedBerryOutlineDrivesTheNextBucketTarget() {
        // SweetBerryBushBlock.useWithoutItem sets age=1 before 1.21.9, narrowing its outline by 1/16.
        for (var version : BlockSimClientVersion.values()) {
            var world = new World(); world.states.put(ABOVE, DATA.registry().with(state("sweet_berry_bush"), "age", "3"));
            var player = owner("air", 0, false, false, 0, 30, new Vec3(3.03, 64, 1.5));
            var harvest = run(world, player, version, click(ABOVE, Direction.NORTH));
            assertEquals(InteractionKind.SUCCESS, harvest.interaction().kind());
            assertEquals(version.protocol() < 773 ? 1 : 0, harvest.writes().size(), version.toString());
            world.apply(harvest);
            player = harvest.player(); player.hand(Hand.MAIN_HAND, ITEMS.stack("minecraft:water_bucket", 1));
            var bucket = run(world, player, version, new SimAction.Use(Hand.MAIN_HAND));
            assertEquals(new BlockPos(3, 64, version.protocol() < 773 ? 4 : 2), bucket.writes().getFirst().pos(), version.toString());
            assertEquals(state("water"), bucket.writes().getFirst().newState());
        }
    }

    private static final class World implements StubWorldView {
        final Map<BlockPos, Integer> states = new HashMap<>();
        World() { for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) states.put(new BlockPos(x, 63, z), state("stone")); }
        void apply(SimResult result) { result.writes().forEach(w -> states.put(w.pos(), w.newState())); }
        public int stateAt(BlockPos pos) { return states.getOrDefault(pos, state("air")); }
        public boolean isLoaded(BlockPos pos) { return true; }
        public boolean isSectionEmpty(BlockPos pos) { return false; }
        public int minY() { return -64; }
        public int height() { return 384; }
        public boolean isWithinBorder(BlockPos pos) { return true; }
        public boolean creakingActiveAt(BlockPos pos) { return false; }
        public boolean waterEvaporatesAt(BlockPos pos) { return false; }
        public BlockEntityData blockEntityAt(BlockPos pos) { return null; }
    }
}
