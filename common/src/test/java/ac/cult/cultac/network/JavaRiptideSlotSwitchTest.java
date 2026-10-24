package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.nbt.CanonicalSnbt;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerDigging;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItem;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.math.VanillaMath;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A riptide release after the hotbar selection changed to another trident. TridentItem#releaseUsing reads the riptide
 * strength from LivingEntity.useItem, which LivingEntity#updatingUsingItem rebinds at each entity tick to the stack in
 * the hand when it is the same item, and which ServerboundSetCarriedItemPacket does not touch on the client. A change
 * whose carried-item packet is sent with the release (MultiPlayerGameMode#releaseUsingItem syncs the slot first) has
 * not been rebound, and one an entity tick saw has.
 */
class JavaRiptideSlotSwitchTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 SHALLOWS = new Vec3(0.5, FLOOR_Y, 0.5);
    /** LivingEntity#travelInFluid's gravity on a player resting on the floor of the water. */
    private static final Vec3 SETTLED = new Vec3(0, -0.005, 0);

    private static final float LIFT = 1.1999999F;
    private static final float PITCH = -10.0F;

    @Test
    void aReleaseSentWithTheSlotChangeSpinsWithTheUsedTrident() throws Exception {
        try (var f = new JavaTickFixture()) {
            charge(f, 3, 1);
            select(f, 1);
            release(f);
            PredictionResult result = spin(f, 3);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void aReleaseAfterAnEntityTickWithTheOtherTridentSpinsWithIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            charge(f, 3, 1);
            select(f, 1);
            PredictionResult held = f.tick(SHALLOWS, true);
            assertTrue(exact(held), describe(held));
            release(f);
            PredictionResult result = spin(f, 1);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void noTridentTheClientMayUseSpinsAtAnotherStrength() throws Exception {
        try (var f = new JavaTickFixture()) {
            charge(f, 3, 1);
            select(f, 1);
            release(f);
            PredictionResult result = spin(f, 2);
            assertFalse(result.isExempt(), describe(result));
            assertFalse(exact(result), describe(result));
        }
    }

    @Test
    void aSingleTridentStillSpinsAtItsOwnStrength() throws Exception {
        try (var f = new JavaTickFixture()) {
            charge(f, 3, 0);
            select(f, 1);
            release(f);
            PredictionResult result = spin(f, 1);
            assertFalse(result.isExempt(), describe(result));
            assertFalse(exact(result), describe(result));
        }
    }

    /**
     * A trident at {@code used} in slot 0, one at {@code other} in slot 1 when positive, used for eleven ticks standing
     * in water at {@link #SHALLOWS}.
     */
    private static void charge(JavaTickFixture f, int used, int other) {
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y, 0), BlockIds.WATER.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        var storage = f.player.getInventory().inventory.getInventoryStorage();
        storage.setItem(Inventory.HOTBAR_OFFSET, trident(used));
        if (other > 0) storage.setItem(Inventory.HOTBAR_OFFSET + 1, trident(other));
        f.place(SHALLOWS, SETTLED);
        f.input(NONE);
        f.player.xRot = 0.0F;
        f.player.yRot = PITCH;
        // PacketManager's receive order: the digging listener, then the check manager's.
        f.fixture.registrar.register(routes -> {
            routes.registerReceiveListener(PacketListenerPriority.LOW, new PacketPlayerDigging());
            routes.registerReceiveListener(PacketListenerPriority.LOW, f.listener);
        });
        dispatch(f, ServerboundPackets.USE_ITEM, new ServerboundUseItem(Hand.MAIN_HAND, 1, 0, PITCH));
        for (int tick = 0; tick < 11; tick++) {
            PredictionResult result = f.tick(SHALLOWS, true);
            assertTrue(exact(result), describe(result));
        }
    }

    private static void select(JavaTickFixture f, int slot) {
        dispatch(f, ServerboundPackets.SET_CARRIED_ITEM, new ServerboundSetCarriedItem(slot));
    }

    private static void release(JavaTickFixture f) {
        dispatch(
                f,
                ServerboundPackets.PLAYER_ACTION,
                new ServerboundPlayerAction(PlayerAction.RELEASE_USE_ITEM, new BlockPos(0, 0, 0), Direction.DOWN, 0));
    }

    /** The release tick's move on the ground: TridentItem#releaseUsing's lift, then its push at {@code level}. */
    private static PredictionResult spin(JavaTickFixture f, int level) {
        return f.tick(SHALLOWS.add(0, LIFT, 0).add(SETTLED).add(push(level)), false);
    }

    private static SimItemStack trident(int riptide) {
        SimItemStack trident = OfflineCultTestBootstrap.item("minecraft:trident");
        trident.setComponent("minecraft:enchantments", CanonicalSnbt.parse("{'minecraft:riptide':" + riptide + "}"));
        return trident;
    }

    /** TridentItem#releaseUsing looking along +Z at {@link #PITCH} with the riptide strength of {@code level}. */
    private static Vec3 push(int level) {
        float strength = 3.0F * ((1.0F + level) / 4.0F);
        float xRot = PITCH * (float) (Math.PI / 180.0);
        float xd = -VanillaMath.sin(0.0F) * VanillaMath.cos(xRot);
        float yd = -VanillaMath.sin(xRot);
        float zd = VanillaMath.cos(0.0F) * VanillaMath.cos(xRot);
        float dist = (float) Math.sqrt(xd * xd + yd * yd + zd * zd);
        return new Vec3(xd * (strength / dist), yd * (strength / dist), zd * (strength / dist));
    }

    /** The production receive route. */
    private static <T extends ServerboundPacket> void dispatch(JavaTickFixture f, PacketType<T> type, T packet) {
        PacketReceiveEvent<T> event = f.event(type, packet);
        f.fixture.registrar.receive(event, f.fixture.registrar.get(type).receive());
    }
}
