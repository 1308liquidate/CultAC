package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.engine.Direction;
import ac.cult.blocksim.engine.shapes.BooleanOp;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;

/** PistonHeadBlock.calculateShape through Java 1.21.4, before rotateAll replaced it. */
final class LegacyPistonHeadShapes {
    private static final VoxelShape[][] SHAPES = new VoxelShape[2][6];

    static {
        for (int shortened = 0; shortened < 2; shortened++)
            for (Direction direction : Direction.values()) {
                boolean shortArm = shortened != 0;
                VoxelShape platform, arm;
                switch (direction) {
                    case DOWN -> {
                        platform = box(0, 0, 0, 16, 4, 16);
                        arm = box(6, 4, 6, 10, shortArm ? 16 : 20, 10);
                    }
                    case UP -> {
                        platform = box(0, 12, 0, 16, 16, 16);
                        arm = box(6, shortArm ? 0 : -4, 6, 10, 12, 10);
                    }
                    case NORTH -> {
                        platform = box(0, 0, 0, 16, 16, 4);
                        arm = box(6, 6, 4, 10, 10, shortArm ? 16 : 20);
                    }
                    case SOUTH -> {
                        platform = box(0, 0, 12, 16, 16, 16);
                        arm = box(6, 6, shortArm ? 0 : -4, 10, 10, 12);
                    }
                    case WEST -> {
                        platform = box(0, 0, 0, 4, 16, 16);
                        arm = box(4, 6, 6, shortArm ? 16 : 20, 10, 10);
                    }
                    case EAST -> {
                        platform = box(12, 0, 0, 16, 16, 16);
                        arm = box(shortArm ? 0 : -4, 6, 6, 12, 10, 10);
                    }
                    default -> throw new AssertionError(direction);
                }
                SHAPES[shortened][direction.ordinal()] = Shapes.join(platform, arm, BooleanOp.OR);
            }
    }

    static VoxelShape shape(int facing, boolean shortened) {
        return SHAPES[shortened ? 1 : 0][facing];
    }

    private static VoxelShape box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return Shapes.create(x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16);
    }

    private LegacyPistonHeadShapes() {}
}
