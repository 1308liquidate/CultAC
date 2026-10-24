package ac.cult.cultac.checks.impl.prediction.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.ReachUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * FireworkRocketEntity#tick boosts a fall flying LocalPlayer after its tick, so a tick that no longer glides (the
 * shared flags update that ends the glide arrives between the ticks) still starts from the boosted velocity.
 */
class FireworkBoostTest {
    private static final float LAST_YAW = 30.0F, LAST_PITCH = -25.0F;
    private static final Vec3 VELOCITY = new Vec3(0.4D, -0.3D, 0.9D);

    @Test
    void aTickAfterTheGlideStartsFromEachRocketCountsBoost() {
        withPlayer(player -> {
            player.compensatedFireworks.addNewFirework(100);
            player.compensatedFireworks.addNewFirework(101);
            Vector3dm look = ReachUtils.getLook(player, LAST_YAW, LAST_PITCH);
            Vec3 once = FireworkBoost.boost(VELOCITY, look);
            Vec3 twice = FireworkBoost.boost(once, look);
            assertEquals(
                    List.of(VELOCITY, once, twice),
                    List.copyOf(carry(player, context(false, false), lastTick(true, false))));
            // v + look * 0.1 + (look * 1.5 - v) * 0.5
            assertEquals(VELOCITY.y * 0.5D + look.getY() * 0.85D, once.y, 1.0E-12D);
        });
    }

    @Test
    void noBoostWithoutARocketOrAfterATickThatDidNotEndFallFlying() {
        withPlayer(player -> {
            Set<Vec3> start = Set.of(VELOCITY);
            assertSame(
                    start, FireworkBoost.carryIntoTravel(player, context(false, false), lastTick(true, false), start));

            player.compensatedFireworks.addNewFirework(100);
            assertSame(
                    start, FireworkBoost.carryIntoTravel(player, context(false, false), lastTick(false, false), start));
            // LivingEntity#travelFallFlying's stopFallFlying on a climbable precedes the rocket's tick.
            assertSame(
                    start, FireworkBoost.carryIntoTravel(player, context(false, false), lastTick(true, true), start));
        });
    }

    /** ElytraTransform owns a gliding tick's boost; a fluid travel does not glide, so its start carries it. */
    @Test
    void aGlidingTickLeavesTheBoostToTheElytraTransform() {
        withPlayer(player -> {
            player.compensatedFireworks.addNewFirework(100);
            Set<Vec3> start = Set.of(VELOCITY);
            assertSame(
                    start, FireworkBoost.carryIntoTravel(player, context(true, false), lastTick(true, false), start));
            assertEquals(
                    2, carry(player, context(true, true), lastTick(true, false)).size());
        });
    }

    private static Set<Vec3> carry(CultPlayer player, SimulationContext context, PredictionResult last) {
        Set<Vec3> start = new LinkedHashSet<>();
        start.add(VELOCITY);
        return FireworkBoost.carryIntoTravel(player, context, last, start);
    }

    private static SimulationContext context(boolean glides, boolean mustBeInLiquid) {
        WorldData worldData = mock(WorldData.class);
        when(worldData.mustBeInLiquid()).thenReturn(mustBeInLiquid);
        SimulationContext context = mock(SimulationContext.class);
        when(context.usesFallFlyingMovement()).thenReturn(glides);
        when(context.isGliding()).thenReturn(glides);
        when(context.getWorldData()).thenReturn(worldData);
        when(context.getLastXRot()).thenReturn(LAST_YAW);
        when(context.getLastYRot()).thenReturn(LAST_PITCH);
        return context;
    }

    private static PredictionResult lastTick(boolean gliding, boolean stopsAfterTravel) {
        SimulationContext context = mock(SimulationContext.class);
        when(context.isGliding()).thenReturn(gliding);
        when(context.stopsFallFlyingAfterTravel()).thenReturn(stopsAfterTravel);
        PredictionResult last = mock(PredictionResult.class);
        when(last.getSimulationContext()).thenReturn(context);
        return last;
    }

    private static void withPlayer(Consumer<CultPlayer> test) {
        OfflineCultTestBootstrap.installConfig();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Firework_Carry"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        user.bind(mock(PlatformPlayer.class));
        try {
            test.accept(player);
        } finally {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) {
                embedded.runPendingTasks();
                embedded.runScheduledPendingTasks();
                embedded.finishAndReleaseAll();
            }
        }
    }
}
