package ac.cult.cultac.bedrock.prediction.simulation.postmove;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementUpdate;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.DolphinBoostState;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import org.junit.Test;

/** A spinning actor scales the velocity it carries out of the tick by -0.2 for every mob its spin hits. */
public final class BedrockSpinAttackMovementTest {
    private static final Vec3d FEET = new Vec3d(100.5D, 80.0D, 100.5D);
    private static final Vec3d VELOCITY = new Vec3d(0.0D, 0.0D, 1.5D);
    // The travel moves the actor about 1.5 blocks toward +Z.
    private static final WorldCollisionBox IN_PATH =
            new WorldCollisionBox(100.2D, 80.0D, 101.0D, 100.8D, 81.8D, 101.6D);
    private static final WorldCollisionBox ALSO_IN_PATH =
            new WorldCollisionBox(100.0D, 80.2D, 101.2D, 100.6D, 82.0D, 101.8D);
    private static final WorldCollisionBox BESIDE_PATH =
            new WorldCollisionBox(103.0D, 80.0D, 101.0D, 103.6D, 81.8D, 101.6D);

    @Test
    public void spinThatHitsNothingKeepsItsVelocity() {
        assertEquals(endVelocity(true, List.of()), endVelocity(true, List.of(BESIDE_PATH)));
    }

    @Test
    public void spinScalesItsVelocityByMinusPointTwoForOneMob() {
        Vec3d missed = endVelocity(true, List.of());
        Vec3d hit = endVelocity(true, List.of(IN_PATH, BESIDE_PATH));

        assertNotEquals(missed, hit);
        assertEquals(BedrockSpinAttackMovement.rebound(missed, 1), hit);
        assertEquals(missed.z() * -0.2F, hit.z(), 1.0E-6D);
    }

    @Test
    public void spinScalesItsVelocityOncePerMobHit() {
        Vec3d missed = endVelocity(true, List.of());
        Vec3d hit = endVelocity(true, List.of(IN_PATH, ALSO_IN_PATH));

        assertEquals(BedrockSpinAttackMovement.rebound(missed, 2), hit);
        assertEquals(missed.z() * 0.04D, hit.z(), 1.0E-6D);
    }

    @Test
    public void actorWithoutSpinDoesNotRebound() {
        assertEquals(endVelocity(false, List.of()), endVelocity(false, List.of(IN_PATH)));
    }

    private static Vec3d endVelocity(boolean spinning, List<WorldCollisionBox> mobs) {
        BedrockMovementState state = state(spinning);
        BedrockInputFrame frame = BedrockInputFrame.idle(3L);
        var input = new BedrockSimulation.Input(
                state,
                frame,
                frame.intent(),
                BedrockWorldSnapshot.fromContext(context(mobs)),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO);
        var result = BedrockForwardTick.simulate(input).getFirst().movementResult();
        return BedrockForwardTick.finish(result, result.predictedState())
                .getFirst()
                .velocity();
    }

    private static BedrockMovementContext context(List<WorldCollisionBox> mobs) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(List.of())),
                new EquipmentState(0, 0, 0, 3, false, false),
                new EntityContactState(DolphinBoostState.NONE, false, mobs),
                new MovementModifierState(true, true, false, false, 0.05D, false, true, false, false, 0.35D, 0L),
                PlayerDimensionsState.DEFAULT);
    }

    private static BedrockMovementState state(boolean spinning) {
        BedrockMovementState initial = BedrockMovementState.fromPhysicalFeet(
                FEET, VELOCITY, BedrockInputFrame.idle(1L), BedrockCollisionFlags.AIR, Medium.AIR);
        return initial.advance(new BedrockMovementUpdate(
                initial.physicalFeetPosition(),
                VELOCITY,
                BedrockInputFrame.idle(2L),
                BedrockCollisionFlags.AIR,
                initial.boundingBoxMode(),
                initial.playerDimensions(),
                0.0F,
                0L,
                new BedrockMovementUpdate.Glide(false, false),
                false,
                0.0D,
                new BedrockMovementUpdate.Riptide(0L, spinning, spinning ? 2L : 0L),
                new BedrockMovementUpdate.ItemUse(false, 0L)));
    }
}
