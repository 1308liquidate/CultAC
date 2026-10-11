package ac.cult.cultac.utils.visibility;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exact union of adjacent rectangular pieces; no epsilon or gap bridging. */
final class OccluderBoxes {
    private OccluderBoxes() {}

    static List<SimpleCollisionBox> coalesce(List<SimpleCollisionBox> input) {
        List<SimpleCollisionBox> boxes = new ArrayList<>(input);
        int before;
        do {
            before = boxes.size();
            for (int axis = 0; axis < 3; axis++) boxes = merge(boxes, axis);
        } while (boxes.size() < before);
        return boxes;
    }

    private static List<SimpleCollisionBox> merge(List<SimpleCollisionBox> boxes, int axis) {
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        boxes.sort(Comparator.comparingDouble((SimpleCollisionBox box) -> low(box, a))
                .thenComparingDouble(box -> high(box, a))
                .thenComparingDouble(box -> low(box, b))
                .thenComparingDouble(box -> high(box, b))
                .thenComparingDouble(box -> low(box, axis)));
        List<SimpleCollisionBox> result = new ArrayList<>();
        for (SimpleCollisionBox box : boxes) {
            if (!result.isEmpty()) {
                SimpleCollisionBox previous = result.getLast();
                if (low(previous, a) == low(box, a)
                        && high(previous, a) == high(box, a)
                        && low(previous, b) == low(box, b)
                        && high(previous, b) == high(box, b)
                        && low(box, axis) <= high(previous, axis)) {
                    SimpleCollisionBox combined = previous.copy();
                    switch (axis) {
                        case 0 -> combined.maxX = Math.max(previous.maxX, box.maxX);
                        case 1 -> combined.maxY = Math.max(previous.maxY, box.maxY);
                        case 2 -> combined.maxZ = Math.max(previous.maxZ, box.maxZ);
                    }
                    result.set(result.size() - 1, combined);
                    continue;
                }
            }
            result.add(box);
        }
        return result;
    }

    private static double low(SimpleCollisionBox box, int axis) {
        return switch (axis) {
            case 0 -> box.minX;
            case 1 -> box.minY;
            default -> box.minZ;
        };
    }

    private static double high(SimpleCollisionBox box, int axis) {
        return switch (axis) {
            case 0 -> box.maxX;
            case 1 -> box.maxY;
            default -> box.maxZ;
        };
    }
}
