package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 26.2 client sprinting off a stair's step with shift pressed. Player#maybeBackOffFromEdge holds it over the stair's
 * lower half on the ground tick and, through Player#isAboveGround's fall-distance clause, on the two falling ticks
 * after it, while deltaMovement keeps the input the edge took; it lands on the lower half and walks on toward the next
 * stair. The moves are a real client's, translated onto the fixture: the upper floor is FLOOR_Y, the stair at z = 0.
 */
class JavaSneakEdgeAirborneTest {
    private static final ServerboundPlayerInput SPRINT_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final float YAW = -183.6553F;

    @Test
    void theVelocityTheEdgeHidesWhileFallingIsCarried() throws Exception {
        try (var f = new JavaTickFixture()) {
            stairs(f);
            // LivingEntity#travelInAir's ground friction, 0.6F * 0.91F, and gravity, applied to the previous move.
            double friction = 0.6F * 0.91F;
            f.place(
                    recorded(12033.64914660181, 123.0, 12003.203528879536),
                    new Vec3(
                            -0.015746520406537456 * friction, -0.08D * (double) 0.98F, -0.2933180369454931 * friction));
            f.input(SPRINT);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
            assertExact(f, 46.82401F, 12033.632431977067, 123.0, 12002.916236044503, true);

            f.input(SPRINT_SNEAK);
            assertExact(f, 46.92259F, 12033.615188767293, 122.92159999847412, 12002.732232970095, false);
            assertExact(f, 47.054028F, 12033.60528695227, 122.76636799395752, 12002.719538803727, false);
            assertExact(f, 47.218327F, 12033.595789278947, 122.53584062504456, 12002.713858638335, false);
            assertExact(f, 47.316906F, 12033.586659374581, 122.5, 12002.564561214014, true);
            assertExact(f, 47.382626F, 12033.575916054091, 122.42159999847412, 12002.390558200395, false);
        }
    }

    /**
     * The recorded stairs: a south-facing outer stair whose step tops the upper floor, its lower half 0.5 below, and a
     * drop past it to a south-facing stair one block down.
     */
    private static void stairs(JavaTickFixture f) {
        var registry = DataTables.defaults().registry();
        int stairs = registry.with(registry.block("minecraft:stone_stairs").defaultState(), "facing", "south");
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= -1; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
            }
        }
        f.player.compensatedWorld.updateBlock(
                new BlockPos(0, FLOOR_Y - 1, 0), registry.with(stairs, "shape", "outer_left"));
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y - 2, -1), stairs);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertExact(JavaTickFixture f, float pitch, double x, double y, double z, boolean onGround) {
        f.player.xRot = YAW;
        f.player.yRot = pitch;
        PredictionResult result = f.tick(recorded(x, y, z), onGround);
        assertTrue(exact(result), describe(result));
    }

    /** A recorded position moved onto the fixture: its upper floor y = 123 becomes FLOOR_Y, its stair z = 12003 z = 0. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 12033.0, y - 123.0 + FLOOR_Y, z - 12003.0);
    }
}
