package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Bounded eye candidates; world geometry is never enlarged or eroded for position error. */
final class EyePositionTolerance {
    static final double BUFFER = .001;

    private EyePositionTolerance() {}

    static boolean outside(Vec3 eye, List<SimpleCollisionBox> shapes) {
        for (SimpleCollisionBox shape : shapes)
            if (eye.x > shape.minX
                    && eye.x < shape.maxX
                    && eye.y > shape.minY
                    && eye.y < shape.maxY
                    && eye.z > shape.minZ
                    && eye.z < shape.maxZ) return false;
        return true;
    }

    static SimpleCollisionBox bounds(Vec3 eye, double positionError, List<SimpleCollisionBox> shapes) {
        double radius = BUFFER + positionError;
        var bounds = new SimpleCollisionBox(
                eye.x - radius, eye.y - radius, eye.z - radius, eye.x + radius, eye.y + radius, eye.z + radius);
        for (SimpleCollisionBox shape : shapes) {
            if (!shape.intersects(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ))
                continue;
            boolean outside = false;
            // Retain the original side of every nearby separating face. The entire
            // candidate box, including the path from its centre, stays outside the
            // shape. Keeping both planes at a corner prevents a diagonal shortcut.
            if (eye.x <= shape.minX) {
                bounds.maxX = Math.min(bounds.maxX, Math.max(eye.x, Math.nextDown(shape.minX)));
                outside = true;
            } else if (eye.x >= shape.maxX) {
                bounds.minX = Math.max(bounds.minX, Math.min(eye.x, Math.nextUp(shape.maxX)));
                outside = true;
            }
            if (eye.y <= shape.minY) {
                bounds.maxY = Math.min(bounds.maxY, Math.max(eye.y, Math.nextDown(shape.minY)));
                outside = true;
            } else if (eye.y >= shape.maxY) {
                bounds.minY = Math.max(bounds.minY, Math.min(eye.y, Math.nextUp(shape.maxY)));
                outside = true;
            }
            if (eye.z <= shape.minZ) {
                bounds.maxZ = Math.min(bounds.maxZ, Math.max(eye.z, Math.nextDown(shape.minZ)));
                outside = true;
            } else if (eye.z >= shape.maxZ) {
                bounds.minZ = Math.max(bounds.minZ, Math.min(eye.z, Math.nextUp(shape.maxZ)));
                outside = true;
            }
            // Vanilla can pick from an already-inside origin. Preserve that exact
            // origin, but do not let the tolerance move it through or out of a block.
            if (!outside) return new SimpleCollisionBox(eye.x, eye.y, eye.z, eye.x, eye.y, eye.z);
        }
        return bounds;
    }

    /** Face, edge and corner witnesses of the clipped buffer; every query uses one complete ray origin. */
    static List<Vec3> candidates(Vec3 eye, SimpleCollisionBox bounds) {
        List<Vec3> result = new ArrayList<>();
        for (double x : coordinates(eye.x, bounds.minX, bounds.maxX))
            for (double y : coordinates(eye.y, bounds.minY, bounds.maxY))
                for (double z : coordinates(eye.z, bounds.minZ, bounds.maxZ)) {
                    if (x != eye.x || y != eye.y || z != eye.z) result.add(new Vec3(x, y, z));
                }
        return result;
    }

    private static double[] coordinates(double centre, double min, double max) {
        if (min == max) return new double[] {centre};
        if (min == centre) return new double[] {centre, max};
        if (max == centre) return new double[] {centre, min};
        return new double[] {centre, min, max};
    }
}
