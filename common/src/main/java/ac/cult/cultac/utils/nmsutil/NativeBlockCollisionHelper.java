package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.ComplexCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import java.util.ArrayList;
import java.util.List;

public final class NativeBlockCollisionHelper {
    private NativeBlockCollisionHelper() {}

    public static CollisionBox getCollisionBox(CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        return getCollisionBox(
                player,
                state,
                x,
                y,
                z,
                entityBottom,
                BlockIds.is(state, BlockIds.POWDER_SNOW) ? JavaCollisionState.current(player) : null);
    }

    public static CollisionBox getCollisionBox(
            CultPlayer player, int state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return NoCollisionBox.INSTANCE;
        }

        return collider(player, getCollisionShape(player, state, x, y, z, entityBottom, actor), x, y, z);
    }

    private static CollisionBox collider(CultPlayer player, VoxelShape shape, int x, int y, int z) {
        // Entity#collideWithShapes scans VoxelShape's cells, including internal
        // faces when a server update places the actor partly inside a shape.
        boolean voxelMovement = !player.isBedrockMovement();
        if (voxelMovement && !shape.isEmpty())
            return new VoxelShapeCollisionBox(shape, shape == Shapes.block()).offset(x, y, z);
        return fromShape(shape, x, y, z);
    }

    /** Adds getCollisionBox's boxes to {@code result} when any of them intersects {@code query}. */
    public static void addIfAnyIntersects(
            CultPlayer player,
            int state,
            int x,
            int y,
            int z,
            double entityBottom,
            JavaCollisionState actor,
            SimpleCollisionBox query,
            List<SimpleCollisionBox> scratch,
            List<SimpleCollisionBox> result) {
        VoxelShape shape = getCollisionShape(player, state, x, y, z, entityBottom, actor);
        if (shape.isEmpty()) return;
        if (shape == Shapes.block() && !player.isBedrockMovement() && query.getClass() == SimpleCollisionBox.class) {
            // The single box VoxelShapeCollisionBox.downCast emits for a unit cube.
            double minX = 0.0 + x, minY = 0.0 + y, minZ = 0.0 + z;
            double maxX = 1.0 + x, maxY = 1.0 + y, maxZ = 1.0 + z;
            if (maxX - SimpleCollisionBox.COLLISION_EPSILON > query.minX
                    && minX + SimpleCollisionBox.COLLISION_EPSILON < query.maxX
                    && maxY - SimpleCollisionBox.COLLISION_EPSILON > query.minY
                    && minY + SimpleCollisionBox.COLLISION_EPSILON < query.maxY
                    && maxZ - SimpleCollisionBox.COLLISION_EPSILON > query.minZ
                    && minZ + SimpleCollisionBox.COLLISION_EPSILON < query.maxZ)
                result.add(SimpleCollisionBox.between(minX, minY, minZ, maxX, maxY, maxZ));
            return;
        }
        addIfAnyIntersects(collider(player, shape, x, y, z), query, scratch, result);
    }

    /** Adds {@code box}'s boxes to {@code result} when any of them intersects {@code query}. */
    public static void addIfAnyIntersects(
            CollisionBox box,
            SimpleCollisionBox query,
            List<SimpleCollisionBox> scratch,
            List<SimpleCollisionBox> result) {
        scratch.clear();
        box.downCast(scratch);
        for (int i = 0; i < scratch.size(); i++) {
            if (query.isIntersected(scratch.get(i))) {
                result.addAll(scratch);
                return;
            }
        }
    }

    public static CollisionBox getSelectionBox(CultPlayer player, int state, int x, int y, int z) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return NoCollisionBox.INSTANCE;
        }

        return fromShape(getSelectionShape(player, state, x, y, z), x, y, z);
    }

    public static VoxelShape getCollisionShape(CultPlayer player, int state, int x, int y, int z) {
        return getCollisionShape(player, state, x, y, z, player == null ? Double.NaN : player.y);
    }

    public static VoxelShape getCollisionShape(CultPlayer player, int state, int x, int y, int z, double entityBottom) {
        return getCollisionShape(
                player,
                state,
                x,
                y,
                z,
                entityBottom,
                BlockIds.is(state, BlockIds.POWDER_SNOW) ? JavaCollisionState.current(player) : null);
    }

    public static VoxelShape getCollisionShape(
            CultPlayer player, int state, int x, int y, int z, double entityBottom, JavaCollisionState actor) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return Shapes.empty();
        }

        if (BlockIds.is(state, BlockIds.POWDER_SNOW) && actor != null) {
            return actor.powderSnowShape(y, entityBottom);
        }

        if (BlockIds.is(state, BlockIds.MOVING_PISTON)) {
            return player.compensatedWorld.pistons.getMovingPistonCollisionShape(new BlockPos(x, y, z));
        }

        var fixed = player.compensatedWorld.geometry().staticCollision(state);
        if (fixed != null) return fixed;

        return player.compensatedWorld
                .geometry()
                .collision(state, new ac.cult.blocksim.engine.BlockPos(x, y, z), entityContext(player, entityBottom));
    }

    /** Inputs shared only by one synchronous old/new-state shape comparison. */
    public static VoxelShape getCollisionShape(
            CultPlayer player,
            int state,
            ac.cult.blocksim.engine.BlockPos pos,
            double entityBottom,
            JavaCollisionState actor,
            EntityCollisionContext context) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) return Shapes.empty();
        if (BlockIds.is(state, BlockIds.POWDER_SNOW) && actor != null)
            return actor.powderSnowShape(pos.y(), entityBottom);
        if (BlockIds.is(state, BlockIds.MOVING_PISTON))
            return player.compensatedWorld.pistons.getMovingPistonCollisionShape(
                    new BlockPos(pos.x(), pos.y(), pos.z()));
        var fixed = player.compensatedWorld.geometry().staticCollision(state);
        if (fixed != null) return fixed;
        return player.compensatedWorld.geometry().collision(state, pos, context);
    }

    public static VoxelShape getSelectionShape(CultPlayer player, int state, int x, int y, int z) {
        if (player == null
                || player.compensatedWorld == null
                || state < 0
                || DataTables.defaults().registry().facts(state).has(StateFacts.AIR)) {
            return Shapes.empty();
        }

        // Selected-target checks use a closed shulker; movement keeps the real lid.
        if (player.compensatedWorld.isReadingSelectionSnapshot() && BlockFamilies.SHULKER_BOX.test(state))
            return Shapes.block();

        return player.compensatedWorld
                .geometry()
                .outline(state, new ac.cult.blocksim.engine.BlockPos(x, y, z), entityContext(player, player.y));
    }

    /** VoxelShape.toAabbs uses this same ordered callback, without needing native box objects. */
    public static List<SimpleCollisionBox> toBoxes(VoxelShape shape) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        ClientShapeBoxes.forEach(
                shape,
                (minX, minY, minZ, maxX, maxY, maxZ) ->
                        boxes.add(SimpleCollisionBox.between(minX, minY, minZ, maxX, maxY, maxZ)));
        return boxes;
    }

    public static CollisionBox fromShape(VoxelShape shape, int x, int y, int z) {
        if (shape == null || shape.isEmpty()) {
            return NoCollisionBox.INSTANCE;
        }

        var result = new ColliderBuilder(x, y, z);
        ClientShapeBoxes.forEach(shape, result);
        return result.complex != null ? result.complex : result.first == null ? NoCollisionBox.INSTANCE : result.first;
    }

    private static final class ColliderBuilder implements VoxelShape.BoxConsumer {
        final int x, y, z;
        SimpleCollisionBox first;
        ComplexCollisionBox complex;

        ColliderBuilder(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public void accept(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            // Normalize before adding offsets, then normalize again as between(...).move(...) does.
            var box = SimpleCollisionBox.between(
                    Math.min(minX, maxX) + x,
                    Math.min(minY, maxY) + y,
                    Math.min(minZ, maxZ) + z,
                    Math.max(minX, maxX) + x,
                    Math.max(minY, maxY) + y,
                    Math.max(minZ, maxZ) + z);
            if (first == null) first = box;
            else {
                if (complex == null) {
                    complex = new ComplexCollisionBox();
                    complex.add(first);
                }
                complex.add(box);
            }
        }
    }

    public static EntityCollisionContext entityContext(CultPlayer player, double entityBottom) {
        String key = heldItem(player);
        return new EntityCollisionContext(entityBottom, player.isSneaking, 0, false, false, key, false, false, false);
    }

    private static String heldItem(CultPlayer player) {
        String selectionItem = player.compensatedWorld.selectionHeldItemKey();
        if (selectionItem != null) return selectionItem;
        try {
            var held = player.getInventory().getHeldItem();
            return held == null || held.isEmpty() ? "minecraft:air" : held.itemKey();
        } catch (RuntimeException ignored) {
            return "minecraft:air";
        }
    }
}
