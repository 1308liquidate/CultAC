package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Ordered shape decomposition; only the fixed generated geometry has a retained index. */
public final class ClientShapeBoxes {
    private ClientShapeBoxes() {}

    private static final class Defaults {
        static final Map<VoxelShape, List<Box>> BOXES = index();

        private static Map<VoxelShape, List<Box>> index() {
            var registry = DataTables.defaults().registry();
            var shapes = registry.shapes();
            var result = new IdentityHashMap<VoxelShape, List<Box>>();
            for (int state = 0; state < registry.stateCount(); state++) {
                var facts = registry.facts(state);
                result.putIfAbsent(shapes.collision(state), facts.collision());
                result.putIfAbsent(shapes.outline(state), facts.outline());
                result.putIfAbsent(shapes.support(state), facts.support());
                result.putIfAbsent(shapes.interaction(state), facts.interaction());
            }
            return Collections.unmodifiableMap(result);
        }
    }

    /** Fixed generated boxes only; transient/world-dependent shapes are never retained here. */
    public static List<Box> cached(VoxelShape shape) {
        return Defaults.BOXES.get(shape);
    }

    public static void forEach(VoxelShape shape, VoxelShape.BoxConsumer consumer) {
        var boxes = cached(shape);
        if (boxes == null) {
            shape.forEachBox(consumer);
        } else {
            for (int i = 0; i < boxes.size(); i++) {
                var box = boxes.get(i);
                consumer.accept(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
            }
        }
    }

    /** Finish the old shape before querying the new state, including its bounds validation. */
    public static Comparison comparison(VoxelShape oldShape) {
        var boxes = cached(oldShape);
        if (boxes != null) return new Comparison(boxes, null, boxes.size());
        var values = new Coordinates();
        oldShape.forEachBox(values);
        return new Comparison(null, values.values, values.size / 6);
    }

    /** Same ordered, normalized comparison for two known immutable generated shapes. */
    public static boolean sameCachedShape(VoxelShape first, VoxelShape second) {
        var a = cached(first);
        var b = cached(second);
        if (a == null || b == null) return comparison(first).matches(second);
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var left = a.get(i);
            var right = b.get(i);
            if (Double.compare(Math.min(left.minX(), left.maxX()), Math.min(right.minX(), right.maxX())) != 0
                    || Double.compare(Math.min(left.minY(), left.maxY()), Math.min(right.minY(), right.maxY())) != 0
                    || Double.compare(Math.min(left.minZ(), left.maxZ()), Math.min(right.minZ(), right.maxZ())) != 0
                    || Double.compare(Math.max(left.minX(), left.maxX()), Math.max(right.minX(), right.maxX())) != 0
                    || Double.compare(Math.max(left.minY(), left.maxY()), Math.max(right.minY(), right.maxY())) != 0
                    || Double.compare(Math.max(left.minZ(), left.maxZ()), Math.max(right.minZ(), right.maxZ())) != 0)
                return false;
        }
        return true;
    }

    public static final class Comparison implements VoxelShape.BoxConsumer {
        private final List<Box> boxes;
        private final double[] values;
        private final int count;
        private int index;
        private boolean matches = true;

        private Comparison(List<Box> boxes, double[] values, int count) {
            this.boxes = boxes;
            this.values = values;
            this.count = count;
        }

        public boolean matches(VoxelShape newShape) {
            forEach(newShape, this);
            return matches && index == count;
        }

        @Override
        public void accept(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            int current = index++;
            if (!matches) return; // Traversal still finishes to preserve eager validation.
            if (current >= count) {
                matches = false;
                return;
            }
            double x1, y1, z1, x2, y2, z2;
            if (boxes != null) {
                var box = boxes.get(current);
                x1 = Math.min(box.minX(), box.maxX());
                y1 = Math.min(box.minY(), box.maxY());
                z1 = Math.min(box.minZ(), box.maxZ());
                x2 = Math.max(box.minX(), box.maxX());
                y2 = Math.max(box.minY(), box.maxY());
                z2 = Math.max(box.minZ(), box.maxZ());
            } else {
                int offset = current * 6;
                x1 = values[offset];
                y1 = values[offset + 1];
                z1 = values[offset + 2];
                x2 = values[offset + 3];
                y2 = values[offset + 4];
                z2 = values[offset + 5];
            }
            matches = Double.compare(x1, Math.min(minX, maxX)) == 0
                    && Double.compare(y1, Math.min(minY, maxY)) == 0
                    && Double.compare(z1, Math.min(minZ, maxZ)) == 0
                    && Double.compare(x2, Math.max(minX, maxX)) == 0
                    && Double.compare(y2, Math.max(minY, maxY)) == 0
                    && Double.compare(z2, Math.max(minZ, maxZ)) == 0;
        }
    }

    private static final class Coordinates implements VoxelShape.BoxConsumer {
        double[] values = new double[6];
        int size;

        @Override
        public void accept(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (size + 6 > values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = Math.min(minX, maxX);
            values[size++] = Math.min(minY, maxY);
            values[size++] = Math.min(minZ, maxZ);
            values[size++] = Math.max(minX, maxX);
            values[size++] = Math.max(minY, maxY);
            values[size++] = Math.max(minZ, maxZ);
        }
    }
}
