package ac.cult.cultac.utils.collisions.datatypes;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.List;

/** A Java voxel collider retained through the existing axis/step runner. */
public final class VoxelShapeCollisionBox extends SimpleCollisionBox {
    private VoxelShape shape;
    private final boolean fullBlock;

    public VoxelShapeCollisionBox(VoxelShape shape) {
        this(shape, false);
    }

    public VoxelShapeCollisionBox(VoxelShape shape, boolean fullBlock) {
        this.shape = shape;
        this.fullBlock = fullBlock;
        minX = minY = minZ = Double.POSITIVE_INFINITY;
        maxX = maxY = maxZ = Double.NEGATIVE_INFINITY;
        var boxes = shape.boxes();
        for (Box box : boxes) {
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxY = Math.max(maxY, box.maxY());
            maxZ = Math.max(maxZ, box.maxZ());
        }
        if (shape.isEmpty()) throw new IllegalArgumentException("Empty voxel collider");
        // Preserve CollisionBox's local full-cube classification through offsets/copies.
        isFullBlock = boxes.size() == 1 && minX == 0 && minY == 0 && minZ == 0 && maxX == 1 && maxY == 1 && maxZ == 1;
    }

    public double[] stepCoordinates() {
        return shape.coordinates(1);
    }

    public VoxelShape voxelShape() {
        return shape;
    }

    public boolean sameGrid(VoxelShapeCollisionBox other) {
        for (int axis = 0; axis < 3; axis++)
            if (!java.util.Arrays.equals(shape.coordinates(axis), other.shape.coordinates(axis))) return false;
        return true;
    }

    @Override
    public VoxelShapeCollisionBox copy() {
        var result = new VoxelShapeCollisionBox(shape, fullBlock);
        result.isFullBlock = isFullBlock;
        return result;
    }

    /** BlockCollisions uses strict AABB intersection for Shapes.block(), voxel AND for other shapes. */
    public boolean intersectsQuery(SimpleCollisionBox query, VoxelShape queryShape) {
        return fullBlock
                ? query.minX < maxX
                        && query.maxX > minX
                        && query.minY < maxY
                        && query.maxY > minY
                        && query.minZ < maxZ
                        && query.maxZ > minZ
                : Shapes.joinIsNotEmpty(shape, queryShape, BooleanOp.AND);
    }

    @Override
    public VoxelShapeCollisionBox offset(double x, double y, double z) {
        super.offset(x, y, z);
        shape = shape.move(x, y, z);
        return this;
    }

    @Override
    public void downCast(List<SimpleCollisionBox> list) {
        for (Box box : shape.boxes())
            list.add(
                    SimpleCollisionBox.between(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
    }

    @Override
    public void downCastForMovement(List<SimpleCollisionBox> list) {
        list.add(this);
    }

    @Override
    public boolean isCollided(SimpleCollisionBox other) {
        for (Box box : shape.boxes())
            if (SimpleCollisionBox.between(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())
                    .isCollided(other)) return true;
        return false;
    }

    @Override
    public boolean isIntersected(SimpleCollisionBox other) {
        for (Box box : shape.boxes())
            if (SimpleCollisionBox.between(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())
                    .isIntersected(other)) return true;
        return false;
    }

    private double collide(int axis, SimpleCollisionBox moving, double distance) {
        return shape.collide(
                axis, new Box(moving.minX, moving.minY, moving.minZ, moving.maxX, moving.maxY, moving.maxZ), distance);
    }

    @Override
    public double collideX(SimpleCollisionBox moving, double distance, AxisEpsilon epsilon) {
        return collide(0, moving, distance);
    }

    @Override
    public double collideY(SimpleCollisionBox moving, double distance, AxisEpsilon epsilon) {
        return collide(1, moving, distance);
    }

    @Override
    public double collideZ(SimpleCollisionBox moving, double distance, AxisEpsilon epsilon) {
        return collide(2, moving, distance);
    }
}
