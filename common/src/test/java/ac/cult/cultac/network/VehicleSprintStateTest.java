package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsC;
import ac.cult.cultac.checks.impl.sprint.SprintH;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.data.packetentity.PacketEntityRideable;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#aiStep clears the sprint flag of a passenger unless its vehicle can sprint (only a Camel) and the player
 * controls it, and LocalPlayer#sendChanges sends no STOP_SPRINTING for a vehicle the player does not control, so the
 * server keeps the last START_SPRINTING.
 */
class VehicleSprintStateTest {
    private static final int VEHICLE = 100;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 1, 0.5);

    @Test
    void aPassengerOfAnUnsaddledHorseThatMountedSprintingIsNotSprinting() throws Exception {
        try (var f = ride(EntityTypeIds.HORSE)) {
            SprintH check = enabled(f);
            f.player.isSprinting = true;
            f.rideTick();
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            for (int tick = 0; tick < 3; tick++) f.rideTick();

            assertFalse(f.player.isClientSprinting());
            assertFalse(MultiActionsC.isVerboseSprinting(f.player));
            assertEquals(0, check.violations);
        }
    }

    @Test
    void aPassengerOfAVehicleItCannotControlIsNotSprintingWhateverItsType() throws Exception {
        for (int type : new int[] {EntityTypeIds.DONKEY, EntityTypeIds.PIG, EntityTypeIds.CAMEL}) {
            try (var f = ride(type)) {
                f.player.isSprinting = true;
                f.rideTick();

                assertFalse(f.player.isClientSprinting());
            }
        }
    }

    @Test
    void aVehicleThatCannotSprintClearsTheFlagEvenWhenTheRiderControlsIt() throws Exception {
        try (var f = ride(EntityTypeIds.HORSE)) {
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.player.isSprinting = true;
            f.rideTick();

            assertFalse(f.player.isClientSprinting());
        }
    }

    @Test
    void aSaddledPigRiderHoldingTheStickHasAClearedFlagToo() throws Exception {
        try (var f = ride(EntityTypeIds.PIG)) {
            ((PacketEntityRideable) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.player
                    .getInventory()
                    .inventory
                    .setHeldItem(ItemUtil.modelItems().stack("minecraft:carrot_on_a_stick", 1));
            f.player.isSprinting = true;
            f.rideTick();

            assertFalse(f.player.isClientSprinting());
        }
    }

    /** A passenger of a camel nobody controls never reaches LocalPlayer#sendIsSprintingIfNeeded mounted. */
    @Test
    void aPassengerOfAnUnsaddledCamelThatMountedSprintingIsNotSprinting() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            SprintH check = enabled(f);
            f.player.isSprinting = true;
            f.rideTick();
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            for (int tick = 0; tick < 3; tick++) f.rideTick();

            assertFalse(f.player.isClientSprinting());
            assertFalse(MultiActionsC.isVerboseSprinting(f.player));
            assertEquals(0, check.violations);
        }
    }

    /**
     * A tick controlling the camel ends in LocalPlayer#sendIsSprintingIfNeeded: a rider that no longer sprints sends
     * STOP_SPRINTING there.
     */
    @Test
    void gainingControlOfACamelSendsTheStopOfAClearedFlag() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            f.player.isSprinting = true;
            f.rideTick();
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            command(f, PlayerCommandAction.STOP_SPRINTING);
            f.rideTick();

            assertFalse(f.player.isClientSprinting());
        }
    }

    /**
     * LocalPlayer#aiStep sprints a controlling rider holding forward and sprint again, which matches the last command
     * sent, so LocalPlayer#sendIsSprintingIfNeeded sends nothing.
     */
    @Test
    void gainingControlOfACamelWithoutAStopSprintsAgain() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            f.player.isSprinting = true;
            f.rideTick();
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            f.rideTick();

            assertTrue(f.player.isClientSprinting());
        }
    }

    @Test
    void theFlagStaysClearedAfterDismountingUntilTheClientSprintsAgain() throws Exception {
        try (var f = ride(EntityTypeIds.HORSE)) {
            f.player.isSprinting = true;
            f.rideTick();
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());

            assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
            assertFalse(f.player.isClientSprinting());

            startSprinting(f);

            assertTrue(f.player.isClientSprinting());
        }
    }

    /** LocalPlayer#sendPosition ends a tick on foot in LocalPlayer#sendIsSprintingIfNeeded. */
    @Test
    void aDismountedRiderThatSendsNoStopSprintsAgain() throws Exception {
        try (var f = ride(EntityTypeIds.HORSE)) {
            f.player.isSprinting = true;
            f.rideTick();
            f.setPassengers(VEHICLE, List.of());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tickEnd();

            assertTrue(f.player.isClientSprinting());
        }
    }

    @Test
    void aPassengerOfACamelItControlsKeepsSprintingAndIsStillFlaggedInAnInventory() throws Exception {
        try (var f = ride(EntityTypeIds.CAMEL)) {
            ((PacketEntityHorse) f.player.compensatedEntities.getEntity(VEHICLE)).hasSaddle = true;
            SprintH check = enabled(f);
            f.player.isSprinting = true;
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.rideTick();

            assertTrue(f.player.isClientSprinting());
            assertEquals(1, check.violations);
        }
    }

    @Test
    void aPlayerOnFootSprintingInAnInventoryIsStillFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(SEAT.x, FLOOR_Y, SEAT.z), Vec3.ZERO);
            SprintH check = enabled(f);
            f.player.isSprinting = true;
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            f.tickEnd();

            assertTrue(f.player.isClientSprinting());
            assertEquals(1, check.violations);
        }
    }

    private static void startSprinting(JavaTickFixture f) {
        command(f, PlayerCommandAction.START_SPRINTING);
    }

    private static void command(JavaTickFixture f, PlayerCommandAction action) {
        var command = new ServerboundPlayerCommand(f.player.entityID, action, 0);
        new PacketEntityAction()
                .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, command), f.player, command);
    }

    private static JavaTickFixture ride(int type) throws Exception {
        var f = new JavaTickFixture();
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(VEHICLE, type, SEAT, 0, 0, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        return f;
    }

    private static SprintH enabled(JavaTickFixture f) {
        f.player.setExperimentalChecks(true);
        SprintH check = f.player.checkManager.getCheck(SprintH.class);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }
}
