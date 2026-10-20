package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintG;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A client in deep water starts sprinting while its eye is under the surface. Entity#updateSwimming swims it on the next
 * tick, before the server's swimming flag can arrive, and LocalPlayer#shouldStopSwimSprinting keeps the sprint while it
 * stays in water, even once it rises until its standing eye height would clear the surface.
 */
class JavaSprintSwimStartTest {
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);

    @Test
    void startingToSwimInAnyDirectionIsNotSprintG() throws Exception {
        for (float pitch : new float[] {-30.0F, 0.0F, 30.0F}) {
            for (float yaw : new float[] {0.0F, 90.0F, -135.0F}) {
                swim(yaw, pitch);
            }
        }
    }

    private static void swim(float yaw, float pitch) throws Exception {
        try (var f = pool(FLOOR_Y + 2)) {
            SprintG sprintG = sprintG(f);
            f.player.xRot = yaw;
            f.player.yRot = pitch;
            // Entity#calculateViewVector's y.
            double lookY = -f.player.trigHandler.sin(pitch * ((float) Math.PI / 180F));
            Vec3 position = new Vec3(0.5, FLOOR_Y + 1.2, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(FORWARD);

            // Not sprinting: LivingEntity#travelInWater's 0.8 slowDown, then getFluidFallingAdjustedMovement.
            Vec3 delta = ClientMoveInput.of(f, FORWARD, 1.0F, 0.02F, yaw);
            position = position.add(delta);
            f.tick(position, false);
            Vec3 velocity = new Vec3(delta.x * 0.8F, -0.005, delta.z * 0.8F);

            startSprinting(f);
            f.input(FORWARD_SPRINT);
            boolean eyeCleared = false;
            for (int tick = 1; tick < 10; tick++) {
                double y = velocity.y;
                // Player#travel's swim pitch, from the tick after the sprint starts.
                if (tick > 1
                        && (lookY <= 0.0
                                || f.player.compensatedWorld.getWaterFluidLevelAt(
                                                position.x, position.y + 0.9, position.z)
                                        > 0)) {
                    y += (lookY - y) * (lookY < -0.2 ? 0.085 : 0.06);
                }
                Vec3 input = ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw);
                delta = new Vec3(velocity.x + input.x, y, velocity.z + input.z);
                position = position.add(delta);
                PredictionResult result = f.tick(position, false);
                // EntityFluidInteraction#update: the standing eye over the surface block's 8/9 water.
                eyeCleared |= position.y + 1.62F > FLOOR_Y + 2 + 8.0F / 9.0F;
                String where = "yaw " + yaw + " pitch " + pitch + " tick " + tick + ": " + describe(result);
                assertEquals(0, sprintG.violations, where);
                // The sprinting 0.9 slowDown; a sprinter has no fluid gravity.
                velocity = new Vec3(delta.x * 0.9F, y * 0.8F, delta.z * 0.9F);
            }
            if (pitch < 0) assertTrue(eyeCleared, "the standing eye never cleared the surface");
        }
    }

    /** One block of water never covers a standing eye, so Entity#updateSwimming cannot swim the wader. */
    @Test
    void sprintingThroughShallowWaterIsSprintG() throws Exception {
        try (var f = pool(FLOOR_Y)) {
            SprintG sprintG = sprintG(f);
            float yaw = 0.0F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            startSprinting(f);
            f.input(FORWARD_SPRINT);
            Vec3 velocity = Vec3.ZERO;
            for (int tick = 0; tick < 4; tick++) {
                Vec3 delta = velocity.add(ClientMoveInput.of(f, FORWARD_SPRINT, 1.0F, 0.02F, yaw));
                position = position.add(delta.x, 0.0, delta.z);
                f.tick(position, true);
                velocity = new Vec3(delta.x * 0.9F, 0.0, delta.z * 0.9F);
            }
            assertTrue(sprintG.violations > 0, "a sprinting wader is not SprintG");
        }
    }

    private static JavaTickFixture pool(int surfaceY) throws Exception {
        var f = new JavaTickFixture();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= surfaceY; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    private static SprintG sprintG(JavaTickFixture f) {
        f.player.setExperimentalChecks(true);
        SprintG sprintG = f.player.checkManager.getCheck(SprintG.class);
        sprintG.violations = 0;
        return sprintG;
    }

    private static void startSprinting(JavaTickFixture f) {
        var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);
    }
}
