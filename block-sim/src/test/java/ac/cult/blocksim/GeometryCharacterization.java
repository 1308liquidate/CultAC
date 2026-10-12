package ac.cult.blocksim;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Random;

/** Bitwise fingerprint recorded from the pre-refactor geometry implementation. */
final class GeometryCharacterization {
    private final MessageDigest digest;

    private GeometryCharacterization() throws Exception {
        digest = MessageDigest.getInstance("SHA-256");
    }

    static String fingerprint() throws Exception {
        var fixture = new GeometryCharacterization();
        var registry = DataTables.defaults().registry();
        var seen = Collections.newSetFromMap(new IdentityHashMap<VoxelShape, Boolean>());
        var random = new Random(0xC0111510L);
        for (int state = 0; state < registry.stateCount(); state++) {
            var shapes = registry.shapes();
            for (var shape : new VoxelShape[] {
                    shapes.collision(state), shapes.outline(state), shapes.support(state), shapes.interaction(state)}) {
                if (seen.add(shape)) fixture.sample(shape, random);
            }
        }
        for (int sample = 0; sample < 1_000; sample++) {
            double[][] coords = new double[3][];
            int count = 1;
            for (int axis = 0; axis < 3; axis++) {
                int size = 1 + random.nextInt(4);
                count *= size;
                coords[axis] = new double[size + 1];
                for (int i = 0; i <= size; i++) coords[axis][i] = i * 0.37 - 1.0;
            }
            var cells = new BitSet(count);
            for (int i = 0; i < count; i++) if (random.nextBoolean()) cells.set(i);
            fixture.sample(Shapes.fromGrid(coords, false, cells), random);
        }
        fixture.sample(Shapes.create(-0.0, -0.0, -0.0, 0.3, 0.5, 0.7), random);
        return HexFormat.of().formatHex(fixture.digest.digest());
    }

    private void sample(VoxelShape original, Random random) {
        for (var shape : new VoxelShape[] {
                original, original.move(0.1, -0.25, 0.3).move(-0.1, 0.5, -0.3)}) {
            var boxes = shape.boxes();
            bits(boxes.size());
            for (var box : boxes) {
                number(box.minX()); number(box.minY()); number(box.minZ());
                number(box.maxX()); number(box.maxY()); number(box.maxZ());
            }
            for (int query = 0; query < 6; query++) {
                double x = random.nextDouble() * 4 - 2;
                double y = random.nextDouble() * 4 - 2;
                double z = random.nextDouble() * 4 - 2;
                var moving = new Box(x, y, z, x + random.nextDouble(), y + random.nextDouble(), z + random.nextDouble());
                for (int axis = 0; axis < 3; axis++) {
                    for (double distance : new double[] {
                            -0.0, 0.0, -1.0E-8, 1.0E-8, -1.0E-7, 1.0E-7, -0.3, 0.3,
                            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN}) {
                        number(shape.collide(axis, moving, distance));
                    }
                }
            }
        }
    }

    private void number(double value) {
        bits(Double.doubleToRawLongBits(value));
    }

    private void bits(long value) {
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    public static void main(String[] args) throws Exception {
        System.out.println(fingerprint());
    }
}
