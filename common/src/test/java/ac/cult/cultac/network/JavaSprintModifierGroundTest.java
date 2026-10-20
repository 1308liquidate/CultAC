package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A sprinting 26.3 client on open ground whose movement_speed the server replaced with a minecraft:sprinting modifier
 * that quadruples it. ClientPacketListener#handleUpdateAttributes keeps that modifier while the client keeps sprinting,
 * so LivingEntity#getFrictionInfluencedSpeed gives the quadrupled speed on the ground.
 */
class JavaSprintModifierGroundTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FRICTION = 0.6F * 0.91F;
    private static final float YAW = 30.0F;
    private static final float CANONICAL = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final float QUADRUPLED = (float) ((double) 0.1F * 4.0D);

    @Test
    void aSprintAtTheServersModifierSpeedIsExact() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = startSprint(f);
            Vec3 velocity = carried(f, CANONICAL);
            holdQuadrupledModifier(f);
            for (int tick = 0; tick < 4; tick++) {
                Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED, YAW));
                position = position.add(delta.x, 0.0, delta.z);
                PredictionResult result = f.tick(position, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                velocity = new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
            }
        }
    }

    @Test
    void aSprintPastTheServersModifierSpeedFlags() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = startSprint(f);
            Vec3 velocity = carried(f, CANONICAL);
            holdQuadrupledModifier(f);
            Vec3 delta = velocity.add(ClientMoveInput.of(f, SPRINT, 1.0F, QUADRUPLED * 1.5F, YAW));
            PredictionResult result = f.tick(position.add(delta.x, 0.0, delta.z), true);
            assertFalse(result.getFlags().isEmpty(), describe(result));
        }
    }

    /** A first sprinting tick from rest at vanilla's sprint speed; the position it reached. */
    private static Vec3 startSprint(JavaTickFixture f) {
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
        f.input(SPRINT);
        Vec3 delta = ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, YAW);
        Vec3 position = new Vec3(0.5 + delta.x, FLOOR_Y, 0.5 + delta.z);
        PredictionResult result = f.tick(position, true);
        assertTrue(exact(result), describe(result));
        return position;
    }

    /** The first tick's delta after LivingEntity#travelInAir's ground friction. */
    private static Vec3 carried(JavaTickFixture f, float speed) {
        Vec3 delta = ClientMoveInput.of(f, SPRINT, 1.0F, speed, YAW);
        return new Vec3(delta.x * FRICTION, 0.0, delta.z * FRICTION);
    }

    private static void holdQuadrupledModifier(JavaTickFixture f) {
        var quadrupled =
                new AttributeModifier("minecraft:sprinting", 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        var attributes = new ClientboundUpdateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot("minecraft:movement_speed", 0.1F, List.of(quadrupled))));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, attributes, false);
        f.player.packetEntityReplication.onUpdateAttributes(event, f.player, attributes);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
