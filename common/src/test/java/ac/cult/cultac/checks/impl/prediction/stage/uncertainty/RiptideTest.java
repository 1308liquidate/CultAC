package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.Test;

public class RiptideTest {
    private static final Vec3 END = new Vec3(0.0D, 3.0D, 0.0D);

    /** TridentItem#releaseUsing lifts by 1.2 only when the client was on ground, whatever this tick's pushes do. */
    @Test
    public void releaseLiftsOnlyFromTheCarriedGroundState() {
        assertEquals(1.5D, releasedY(DesyncStatus.FALSE), 1.0E-12D);
        assertEquals(2.7D, releasedY(DesyncStatus.TRUE), 1.0E-12D);
    }

    private static double releasedY(DesyncStatus carriedOnGround) {
        WorldData worldData = mock(WorldData.class);
        when(worldData.getCarriedOnGround()).thenReturn(carriedOnGround);
        when(worldData.getLastOnGround()).thenReturn(DesyncStatus.UNKNOWN);
        SimulationContext context = mock(SimulationContext.class);
        when(context.getRiptideLevel()).thenReturn(1);
        when(context.getWorldData()).thenReturn(worldData);
        when(context.getLastOnGround()).thenReturn(DesyncStatus.UNKNOWN);
        PredictionResult result = mock(PredictionResult.class);
        when(result.getSimulationContext()).thenReturn(context);
        return new Riptide()
                .handleMovementTrace(
                        null, null, result, context, null, MovementTrace.start(new PredVector(Vec3.ZERO)), END)
                .position()
                .y;
    }
}
