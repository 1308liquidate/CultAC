package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.function.Consumer;

/** Conservative voxel broadphase for the finite cone joining an eye to a target box. */
final class VisibilityVoxels {
    private VisibilityVoxels() {}

    static void visit(
            Vec3 eye,
            SimpleCollisionBox target,
            double range,
            SimpleCollisionBox queryBounds,
            Consumer<BlockPos> visitor) {
        double[] origin = {eye.x, eye.y, eye.z};
        double[] lo = {
            Math.max(target.minX, eye.x - range),
            Math.max(target.minY, eye.y - range),
            Math.max(target.minZ, eye.z - range)
        };
        double[] hi = {
            Math.min(target.maxX, eye.x + range),
            Math.min(target.maxY, eye.y + range),
            Math.min(target.maxZ, eye.z + range)
        };
        int axis = -1;
        double separation = 0;
        for (int i = 0; i < 3; i++) {
            if (lo[i] > hi[i]) return;
            double nearest = lo[i] > origin[i] ? lo[i] - origin[i] : hi[i] < origin[i] ? hi[i] - origin[i] : 0;
            if (Math.abs(nearest) > Math.abs(separation)) {
                axis = i;
                separation = nearest;
            }
        }
        if (axis < 0) return; // Containment is resolved before a world query.
        int first = (axis + 1) % 3, second = (axis + 2) % 3;
        double sign = Math.signum(separation);
        double near = Math.min(sign * (lo[axis] - origin[axis]), sign * (hi[axis] - origin[axis]));
        double far = Math.max(sign * (lo[axis] - origin[axis]), sign * (hi[axis] - origin[axis]));
        double[][] slopes = {
            slopes(lo[first] - origin[first], hi[first] - origin[first], near, far),
            slopes(lo[second] - origin[second], hi[second] - origin[second], near, far)
        };
        double[] queryLow = {queryBounds.minX, queryBounds.minY, queryBounds.minZ};
        double[] queryHigh = {queryBounds.maxX, queryBounds.maxY, queryBounds.maxZ};
        int start = Math.max((int) Math.floor(Math.min(origin[axis], lo[axis])), (int) queryLow[axis]);
        int end = Math.min((int) Math.floor(Math.max(origin[axis], hi[axis])), (int) queryHigh[axis] - 1);
        int[] point = new int[3];
        for (int primary = start; primary <= end; primary++) {
            double d0 = Math.max(0, Math.min(sign * (primary - origin[axis]), sign * (primary + 1 - origin[axis])));
            double d1 = Math.min(far, Math.max(sign * (primary - origin[axis]), sign * (primary + 1 - origin[axis])));
            int a0 = floorBound(origin[first], slopes[0][0], d0, d1, false);
            int a1 = floorBound(origin[first], slopes[0][1], d0, d1, true);
            int b0 = floorBound(origin[second], slopes[1][0], d0, d1, false);
            int b1 = floorBound(origin[second], slopes[1][1], d0, d1, true);
            // The extra owning voxel covers endpoint extension and floating point
            // rounding in BlockGetter.traverseBlocks; it affects query work only.
            a0 = Math.max(a0 - 1, (int) Math.floor(Math.min(origin[first], lo[first])));
            a1 = Math.min(a1 + 1, (int) Math.floor(Math.max(origin[first], hi[first])));
            b0 = Math.max(b0 - 1, (int) Math.floor(Math.min(origin[second], lo[second])));
            b1 = Math.min(b1 + 1, (int) Math.floor(Math.max(origin[second], hi[second])));
            a0 = Math.max(a0, (int) queryLow[first]);
            a1 = Math.min(a1, (int) queryHigh[first] - 1);
            b0 = Math.max(b0, (int) queryLow[second]);
            b1 = Math.min(b1, (int) queryHigh[second] - 1);
            point[axis] = primary;
            for (int a = a0; a <= a1; a++)
                for (int b = b0; b <= b1; b++) {
                    point[first] = a;
                    point[second] = b;
                    visitor.accept(new BlockPos(point[0], point[1], point[2]));
                }
        }
    }

    private static double[] slopes(double low, double high, double near, double far) {
        return new double[] {
            Math.min(Math.min(low / near, low / far), Math.min(high / near, high / far)),
            Math.max(Math.max(low / near, low / far), Math.max(high / near, high / far))
        };
    }

    private static int floorBound(double origin, double slope, double near, double far, boolean upper) {
        return (int) Math.floor(
                origin + (upper ? Math.max(slope * near, slope * far) : Math.min(slope * near, slope * far)));
    }
}
