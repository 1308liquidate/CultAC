package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.VoxelShapeCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ScalarCollisionProbeTest {
    @Test
    void scalarProbesMatchThePreviousVectorPathWithoutMutatingTheActor() {
        var random = new Random(810327);
        var epsilons =
                List.of(SimpleCollisionBox.AxisEpsilon.JAVA, new SimpleCollisionBox.AxisEpsilon(1e-7, 1e-5, 1e-7));
        double[] distances = {
            -1,
            -.2,
            -1e-7,
            -1e-8,
            -0.0,
            0.0,
            1e-8,
            1e-7,
            .2,
            1,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            Double.NaN
        };
        for (int iteration = 0; iteration < 1000; iteration++) {
            var colliders = new ArrayList<SimpleCollisionBox>();
            if ((iteration & 3) != 0) {
                colliders.add(new VoxelShapeCollisionBox(Shapes.block()).offset(0, 64, 0));
                colliders.add(SimpleCollisionBox.between(-1, 64.25, -.5, .125, 65, 1));
                colliders.add(new VoxelShapeCollisionBox(Shapes.create(.25, .125, .25, .75, .875, .75))
                        .offset(.3, 63.5, -.2));
            }
            double x = random.nextDouble() * 3 - 1.5,
                    y = 63 + random.nextDouble() * 3,
                    z = random.nextDouble() * 3 - 1.5;
            var actor = SimpleCollisionBox.between(x, y, z, x + .6, y + 1.8, z + .6);
            for (var epsilon : epsilons)
                for (var axis : Collisions.Axis.values())
                    for (double distance : distances) {
                        var movement = new Vec3(
                                axis == Collisions.Axis.X ? distance : 0,
                                axis == Collisions.Axis.Y ? distance : 0,
                                axis == Collisions.Axis.Z ? distance : 0);
                        var reference =
                                Collisions.collideBoundingBoxLegacy(movement, actor, colliders, List.of(axis), epsilon);
                        double expected = axis == Collisions.Axis.X
                                ? reference.x
                                : axis == Collisions.Axis.Y ? reference.y : reference.z;
                        double actual = Collisions.collideAxis(distance, actor, colliders, axis, epsilon);
                        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
                        assertEquals(x, actor.minX, 0);
                        assertEquals(y, actor.minY, 0);
                        assertEquals(z, actor.minZ, 0);
                        assertEquals(x + .6, actor.maxX, 0);
                        assertEquals(y + 1.8, actor.maxY, 0);
                        assertEquals(z + .6, actor.maxZ, 0);
                    }
        }
    }

    @Test
    void primitiveStepCollectionRetainsInternalFacesFloatCastingDeduplicationAndSorting() throws Exception {
        var occupancy = new BitSet();
        occupancy.set(0, 4);
        var root = Shapes.fromGrid(new double[][] {{0, 1}, {0, .25, .5, .75, 1}, {0, 1}}, false, occupancy);
        var voxel = new VoxelShapeCollisionBox(root).offset(0, 64, 0);
        var actor = SimpleCollisionBox.between(.2, 64.1, .2, .8, 65.9, .8);
        var colliders = List.of(
                voxel,
                SimpleCollisionBox.between(0, 64.3, 0, 1, 64.35, 1),
                SimpleCollisionBox.between(0, 64.5, 0, 1, 65, 1));
        var method = Collisions.class.getDeclaredMethod(
                "collectCandidateStepUpHeights", SimpleCollisionBox.class, List.class, double.class, double.class);
        method.setAccessible(true);
        assertArrayEquals(new float[] {.2F, .25F, .4F}, (float[]) method.invoke(null, actor, colliders, .6, .15));
    }
}
