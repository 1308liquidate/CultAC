package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.behavior.BlockBehavior;
import ac.cult.blocksim.engine.BlockPos;
import ac.cult.blocksim.engine.EntityCollisionContext;
import ac.cult.blocksim.engine.SimLevel;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

class StateOnlyCollisionDispatchTest {
    static class Inherited extends BlockBehavior {}

    static class PositionDependent extends BlockBehavior {
        @Override
        public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos) {
            return Shapes.block().move(pos.x(), 0, 0);
        }
    }

    static class ContextDependent extends Inherited {
        @Override
        public VoxelShape collisionShape(SimLevel level, int state, BlockPos pos, EntityCollisionContext context) {
            return context.descending() ? Shapes.empty() : Shapes.block();
        }
    }

    @Test
    void shortcutRequiresBothEffectiveMethodsToBeTheBaseImplementation() {
        assertTrue(ClientBlockGeometry.inheritsStateOnlyCollision(BlockBehavior.class));
        assertTrue(ClientBlockGeometry.inheritsStateOnlyCollision(Inherited.class));
        assertFalse(ClientBlockGeometry.inheritsStateOnlyCollision(PositionDependent.class));
        assertFalse(ClientBlockGeometry.inheritsStateOnlyCollision(ContextDependent.class));
    }
}
