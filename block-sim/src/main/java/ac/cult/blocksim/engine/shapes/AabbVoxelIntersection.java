package ac.cult.blocksim.engine.shapes;

/** Paper/Moonrise's direct AABB/occupancy test, retaining vanilla's ambiguous merger cases. */
final class AabbVoxelIntersection {
    private AabbVoxelIntersection() { }

    static boolean intersects(VoxelShape shape, VoxelShape query, double x, double y, double z, boolean translated) {
        if (shape.isEmpty() || query.isEmpty()) return false;
        if (query.coordinateCount(0) != 2 || query.coordinateCount(1) != 2 || query.coordinateCount(2) != 2
                || !safeAxis(shape, query, 0, x, translated)
                || !safeAxis(shape, query, 1, y, translated)
                || !safeAxis(shape, query, 2, z, translated)) {
            return Shapes.joinIsNotEmpty(translated ? shape.move(x, y, z) : shape, query, BooleanOp.AND);
        }
        int minX = Math.max(0, floor(shape.coords[0], query.coords[0][0] + Shapes.EPSILON, x, translated));
        int maxX = Math.min(shape.size(0), floor(shape.coords[0], query.coords[0][1] - Shapes.EPSILON, x, translated) + 1);
        int minY = Math.max(0, floor(shape.coords[1], query.coords[1][0] + Shapes.EPSILON, y, translated));
        int maxY = Math.min(shape.size(1), floor(shape.coords[1], query.coords[1][1] - Shapes.EPSILON, y, translated) + 1);
        int minZ = Math.max(0, floor(shape.coords[2], query.coords[2][0] + Shapes.EPSILON, z, translated));
        int maxZ = Math.min(shape.size(2), floor(shape.coords[2], query.coords[2][1] - Shapes.EPSILON, z, translated) + 1);
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) return false;
        for (int ix = minX; ix < maxX; ix++) for (int iy = minY; iy < maxY; iy++) {
            if (shape.anyFullZ(ix, iy, minZ, maxZ)) return true;
        }
        return false;
    }

    private static int floor(double[] coordinates, double value, double offset, boolean translated) {
        int low = 0, high = coordinates.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            double coordinate = translated ? coordinates[middle] + offset : coordinates[middle];
            if (value < coordinate) high = middle;
            else low = middle + 1;
        }
        return low - 1;
    }

    private static boolean safeAxis(VoxelShape shape, VoxelShape query, int axis, double offset, boolean translated) {
        double min = query.coords[axis][0], max = query.coords[axis][1];
        if (!Double.isFinite(min) || !Double.isFinite(max) || max - min <= 2 * Shapes.EPSILON) return false;
        double previous = Double.NEGATIVE_INFINITY;
        for (double root : shape.coords[axis]) {
            double value = translated ? root + offset : root;
            // Paper assumes grid intervals exceed epsilon. A tighter grid must retain index merging.
            if (!Double.isFinite(value) || value - previous <= 2 * Shapes.EPSILON) return false;
            double rounding = 8 * Math.max(Math.ulp(value), Math.max(Math.ulp(min), Math.ulp(max)));
            // Addition/subtraction and IndirectMerger's endpoint coalescing differ at epsilon ties.
            if (Math.abs(Math.abs(value - min) - Shapes.EPSILON) <= rounding
                    || Math.abs(Math.abs(value - max) - Shapes.EPSILON) <= rounding) return false;
            previous = value;
        }
        return true;
    }
}
