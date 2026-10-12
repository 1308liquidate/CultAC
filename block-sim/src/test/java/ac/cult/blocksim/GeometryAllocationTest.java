package ac.cult.blocksim;

import ac.cult.blocksim.data.Box;
import ac.cult.blocksim.engine.shapes.Shapes;
import java.util.ArrayList;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeometryAllocationTest {
    @Test void orderedDecompositionAndCollisionMatchPreRefactorBits() throws Exception {
        assertEquals("237208f9b99a51d0f4485970ae8ee4e58d5438b7e5d417852e3611770d5625da", GeometryCharacterization.fingerprint());
    }

    @Test void singleCellTraversalRetainsPaddedGridAndSignedZeroBounds() {
        var cells = new BitSet(); cells.set(5);
        var shape = Shapes.fromGrid(new double[][] {{-1, -0.0, 1}, {-1, 0, 1}, {-1, 0, 1}}, false, cells);
        var boxes = new ArrayList<Box>();
        shape.forEachBox((x1, y1, z1, x2, y2, z2) -> boxes.add(new Box(x1, y1, z1, x2, y2, z2)));
        assertEquals(1, boxes.size());
        assertEquals(new Box(-0.0, -1, 0, 1, 0, 1), boxes.getFirst());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(boxes.getFirst().minX()));
        cells.clear(); // Factory inputs remain independently owned.
        assertFalse(shape.isEmpty());
    }

    @Test void scalarAdaptersRetainBoundsValidationAndDefensiveCoordinates() {
        var shape = Shapes.block();
        assertThrows(IllegalArgumentException.class, () -> shape.collide(0, Double.NaN, 0, 0, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> shape.collide(0, 2, 0, 0, 1, 1, 1, 0));
        var coordinates = shape.coordinates(0); coordinates[0] = 9;
        assertTrue(shape.sameGrid(Shapes.block()));
        assertEquals(0, shape.coordinates(0)[0]);
        assertThrows(UnsupportedOperationException.class, () -> shape.boxes().clear());
    }
}
