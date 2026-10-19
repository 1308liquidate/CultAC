package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.EngineCheck;
import ac.cult.cultac.checks.impl.prediction.checks.NoFall;
import ac.cult.cultac.checks.impl.prediction.checks.PredCheckRunner;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.player.CultPlayer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The step proof VerticalAnalyzer leaves on a result is the one NoFall would prove itself. */
class StepProofMemoEquivalenceTest {
    private static final int SCENARIOS = 66;

    @Test
    void noFallSeesTheProofItWouldCompute() throws Exception {
        Class<?> type = Class.forName("ac.cult.cultac.checks.impl.prediction.checks.VanillaStepProof");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object proof = constructor.newInstance();
        Method prove = type.getDeclaredMethod("proveSourceStep", CultPlayer.class, PredictionResult.class);
        prove.setAccessible(true);
        int[] shared = {0};
        List<String> mismatches = new ArrayList<>();
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            try (var f = new JavaTickFixture()) {
                List<EngineCheck> checks = checks(f);
                int index = -1;
                for (int i = 0; i < checks.size(); i++) if (checks.get(i) instanceof NoFall) index = i;
                EngineCheck noFall = checks.get(index);
                checks.set(index, (player, result, lastResult) -> {
                    Boolean memo = result.getSourceProvenStep();
                    if (memo != null) {
                        shared[0]++;
                        boolean fresh;
                        try {
                            fresh = (boolean) prove.invoke(proof, player, result);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                        if (fresh != memo) mismatches.add(scenario + ": " + memo + " != " + fresh);
                    }
                    noFall.handleResult(player, result, lastResult);
                });
                PredictionScenarios.run(f, seed, result -> {});
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(shared[0] > SCENARIOS, shared[0] + " shared proofs");
    }

    @SuppressWarnings("unchecked")
    private static List<EngineCheck> checks(JavaTickFixture f) throws Exception {
        Field flagger = SimulationProcessor.class.getDeclaredField("flagger");
        flagger.setAccessible(true);
        Object runner = flagger.get(f.player.checkManager.getSimulationProcessor());
        Field checks = PredCheckRunner.class.getDeclaredField("checks");
        checks.setAccessible(true);
        return (List<EngineCheck>) checks.get(runner);
    }
}
