package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMountScreenOpen;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.EquipmentSlot;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A rider saddles the donkey it sits on. AbstractHorse#getControllingPassenger turns to the rider, so the root becomes
 * Entity#isLocalInstanceAuthoritative and LocalPlayer#tick starts sending its ServerboundMoveVehiclePacket. Entity#commonTick
 * still runs InterpolationHandler#interpolate for the root before LivingEntity#aiStep reaches travelRidden, so the
 * server path it was following keeps moving it until its steps run out.
 */
class SaddleControlSwitchTest {
    private static final int DONKEY = 100;
    private static final int NAUTILUS = 101;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    /** 600/4096 blocks along x, interpolated over the donkey's three-tick update interval. */
    private static final short STEP = 600;

    private static final double PATH = STEP / 4096.0;

    @Test
    void theRootFinishesTheServerPathItWasFollowing() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            move(f, STEP);
            f.rideTick();
            saddle(f);

            assertFinishesPath(f, 2);
            assertEquals(0, badPackets.violations);
        }
    }

    /** The client ticks once more before it reads the set_equipment; that tick it still rides passively. */
    @Test
    void aTickBeforeTheClientReadsTheSaddleStillInterpolates() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            move(f, STEP);
            f.player.sendTransactionAndGetId();
            f.pongThrough(f.player.lastTransactionSent.get());
            sendSaddle(f);
            f.rideTick();
            f.player.sendTransactionAndGetId();
            f.pongThrough(f.player.lastTransactionSent.get());

            assertFinishesPath(f, 2);
            assertEquals(0, badPackets.violations);
        }
    }

    /** Controlled ticks from interpolation step {@code first} of 3: no ridden input, so the path alone moves it. */
    private static void assertFinishesPath(JavaTickFixture f, int first) {
        for (int step = first; step <= 3; step++) {
            PredictionResult result = f.rideTick(START.add(PATH * step / 3, 0, 0), true);
            assertTrue(exact(result), "step " + step + ": " + describe(result));
        }
        PredictionResult settled = f.rideTick(START.add(PATH, 0, 0), true);
        assertTrue(exact(settled), describe(settled));
    }

    @Test
    void aStandingRootStartsFromWhereItStands() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            saddle(f);

            for (int tick = 0; tick < 3; tick++) {
                PredictionResult result = f.rideTick(START, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
            assertEquals(0, badPackets.violations);
        }
    }

    /**
     * The donkey's HorseInventoryMenu: slot 0 is an ArmorSlot over its EquipmentSlot.SADDLE, so a click that fills it
     * saddles the client's donkey before the click is sent, and the rider controls it from the next client tick.
     */
    @ParameterizedTest
    @EnumSource(SaddleClick.class)
    void aSaddleClickedOntoAStandingMountHandsOverControlAtTheNextTick(SaddleClick click) throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            openMountScreen(f, SimItemStack.EMPTY);
            click.perform(f);

            for (int tick = 0; tick < 3; tick++) {
                PredictionResult result = f.rideTick(START, true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
            }
            saddle(f);
            PredictionResult confirmed = f.rideTick(START, true);
            assertTrue(exact(confirmed), describe(confirmed));
            assertEquals(0, badPackets.violations);
        }
    }

    @Test
    void aSaddleClickedOntoAMovingMountFinishesTheServerPath() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            openMountScreen(f, SimItemStack.EMPTY);
            move(f, STEP);
            f.rideTick();
            SaddleClick.SHIFT.perform(f);

            assertFinishesPath(f, 2);
            assertEquals(0, badPackets.violations);
        }
    }

    /** Taking the saddle out empties the donkey's SADDLE at the click, so the next tick only sends the rider's Rot. */
    @Test
    void takingTheSaddleOutHandsControlBackAtTheNextTick() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            saddle(f);
            PredictionResult controlled = f.rideTick(START, true);
            assertTrue(exact(controlled), describe(controlled));
            openMountScreen(f, saddleItem());
            click(f, 0, 0, WindowClickType.PICKUP, Map.of(0, SimItemStack.EMPTY), saddleItem());

            f.rideTick();
            assertEquals(0, badPackets.violations);
            moveVehicle(f, START);
            assertEquals(1, badPackets.violations);
        }
    }

    /** A rejected click is undone by the server's set_slot, which empties the client's saddle again. */
    @Test
    void aSaddleClickTheServerRejectsHandsControlBackAtItsCorrection() throws Exception {
        try (var f = ridden()) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            openMountScreen(f, SimItemStack.EMPTY);
            SaddleClick.SHIFT.perform(f);
            PredictionResult controlled = f.rideTick(START, true);
            assertTrue(exact(controlled), describe(controlled));

            setSlot(f, 0, SimItemStack.EMPTY);
            setSlot(f, HOTBAR_0, saddleItem());
            f.pongThrough(f.player.lastTransactionSent.get());
            f.rideTick();
            assertEquals(0, badPackets.violations);
            moveVehicle(f, START);
            assertEquals(1, badPackets.violations);
        }
    }

    /** ArmorSlot#mayPlace is LivingEntity#isEquippableInSlot: AbstractHorse#canUseSlot needs a tamed adult. */
    @Test
    void anUntamedMountsSaddleSlotRefusesTheSaddle() throws Exception {
        try (var f = ridden(false)) {
            BadPacketsVehicle badPackets = enabled(f, BadPacketsVehicle.class);
            openMountScreen(f, SimItemStack.EMPTY);
            SaddleClick.SHIFT.perform(f);

            f.rideTick();
            moveVehicle(f, START);
            assertEquals(1, badPackets.violations);
        }
    }

    /** NautilusInventoryMenu has the same ArmorSlot, which AbstractNautilus#canUseSlot opens to a tamed adult. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aNautilusSaddleSlotTakesTheSaddleOnlyOnceTamed(boolean tamed) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(START, Vec3.ZERO);
            send(
                    f,
                    ClientboundPackets.ADD_ENTITY,
                    new ClientboundAddEntity(
                            NAUTILUS, "minecraft:nautilus", new Vec3d(START.x, START.y, START.z), 0, 0, 0));
            f.pongThrough(f.player.lastTransactionSent.get());
            // TamableAnimal's DATA_FLAGS_ID with its tame bit.
            f.player.compensatedEntities.updateEntityMetadata(
                    NAUTILUS, List.of(new EntityMetadata.Entry(18, (byte) (tamed ? 0x04 : 0), ByteBuffer.allocate(0))));
            openMountScreen(f, NAUTILUS, SimItemStack.EMPTY);
            SaddleClick.SHIFT.perform(f);

            var nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(NAUTILUS);
            assertEquals(tamed, nautilus.hasSaddle);
        }
    }

    /** The menu slot of the first hotbar slot: saddle, body armor, then the 27 inventory slots. */
    private static final int HOTBAR_0 = 2 + 27;

    private static final int WINDOW = 1;

    enum SaddleClick {
        /** AbstractMountInventoryMenu#quickMoveStack moves it into the empty saddle slot. */
        SHIFT {
            @Override
            void perform(JavaTickFixture f) {
                click(
                        f,
                        HOTBAR_0,
                        0,
                        WindowClickType.QUICK_MOVE,
                        Map.of(0, saddleItem(), HOTBAR_0, SimItemStack.EMPTY),
                        SimItemStack.EMPTY);
            }
        },
        /** Picked up onto the cursor, then put down into the saddle slot. */
        CURSOR {
            @Override
            void perform(JavaTickFixture f) {
                click(f, HOTBAR_0, 0, WindowClickType.PICKUP, Map.of(HOTBAR_0, SimItemStack.EMPTY), saddleItem());
                click(f, 0, 0, WindowClickType.PICKUP, Map.of(0, saddleItem()), SimItemStack.EMPTY);
            }
        },
        /** The first hotbar key over the saddle slot. */
        NUMBER_KEY {
            @Override
            void perform(JavaTickFixture f) {
                click(
                        f,
                        0,
                        0,
                        WindowClickType.SWAP,
                        Map.of(0, saddleItem(), HOTBAR_0, SimItemStack.EMPTY),
                        SimItemStack.EMPTY);
            }
        };

        abstract void perform(JavaTickFixture f);
    }

    private static void openMountScreen(JavaTickFixture f, SimItemStack saddle) {
        openMountScreen(f, DONKEY, saddle);
    }

    /** The server opens {@code mount}'s screen, holding {@code saddle}, with a saddle in the first hotbar slot. */
    private static void openMountScreen(JavaTickFixture f, int mount, SimItemStack saddle) {
        var open = new ClientboundMountScreenOpen(WINDOW, 0, mount);
        var openEvent = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.MOUNT_SCREEN_OPEN, open, false);
        new PacketPlayerWindow().onMountScreenOpen(openEvent, f.player, open);
        f.player.getInventory().onMountScreenOpen(openEvent, f.player, open);
        openEvent.tasksAfter().forEach(Runnable::run);

        var items = new ArrayList<SimItemStack>();
        for (int slot = 0; slot < 2 + 36; slot++) items.add(SimItemStack.EMPTY);
        items.set(0, saddle);
        if (saddle.isEmpty()) items.set(HOTBAR_0, saddleItem());
        var content = new InventoryPackets.Content(WINDOW, 1, items, SimItemStack.EMPTY);
        var contentEvent = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, type(InventoryPackets.Content.class), content, false);
        f.player.getInventory().onContainerSetContent(contentEvent, f.player, content);
        contentEvent.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void setSlot(JavaTickFixture f, int slot, SimItemStack item) {
        var packet = new InventoryPackets.Slot(WINDOW, 2, slot, item);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, type(InventoryPackets.Slot.class), packet, false);
        f.player.getInventory().onContainerSetSlot(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
    }

    /** A click between client ticks, as AbstractContainerScreen#slotClicked sends it, forwarded to the server. */
    private static void click(
            JavaTickFixture f,
            int slot,
            int button,
            WindowClickType clickType,
            Map<Integer, SimItemStack> changed,
            SimItemStack carried) {
        var click = new InventoryClick(WINDOW, 1, slot, button, clickType, changed, carried);
        var event = new PacketReceiveEvent<>(f.fixture.user, ConnectionPhase.PLAY, type(InventoryClick.class), click);
        f.player.getInventory().onContainerClick(event, f.player, click);
        assertFalse(event.isCancelled());
        f.player.getInventory().onClickForwarded(click);
    }

    /** A MoveVehicle at {@code to} after the rider's Rot, whether or not the root accepts it. */
    private static void moveVehicle(JavaTickFixture f, Vec3 to) {
        var rot = new ServerboundMovePlayer(0, 0, 0, f.player.xRot, f.player.yRot, true, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        var move = new ServerboundMoveVehicle(new Vec3d(to.x, to.y, to.z), f.player.xRot, f.player.yRot, true, true);
        f.listener.onMoveVehicle(f.event(ServerboundPackets.MOVE_VEHICLE, move), f.player, move);
        f.tickEnd();
    }

    private static SimItemStack saddleItem() {
        return ItemUtil.modelItems().stack("minecraft:saddle", 1);
    }

    private static JavaTickFixture ridden() throws Exception {
        return ridden(true);
    }

    /** An unsaddled donkey under the rider, mounted through set_passengers, after a second of passive riding. */
    private static JavaTickFixture ridden(boolean tamed) throws Exception {
        var f = new JavaTickFixture();
        f.place(START.add(0, 1, 0), Vec3.ZERO);
        send(
                f,
                ClientboundPackets.ADD_ENTITY,
                new ClientboundAddEntity(DONKEY, "minecraft:donkey", new Vec3d(START.x, START.y, START.z), 0, 0, 0));
        f.pongThrough(f.player.lastTransactionSent.get());
        if (tamed) {
            // AbstractHorse's DATA_ID_FLAGS with FLAG_TAME.
            f.player.compensatedEntities.updateEntityMetadata(
                    DONKEY, List.of(new EntityMetadata.Entry(18, (byte) 0x02, ByteBuffer.allocate(0))));
        }
        f.setPassengers(DONKEY, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
        // A second of passive riding, past the vehicle switch window the mount opens.
        for (int tick = 0; tick < 20; tick++) f.rideTick();
        return f;
    }

    private static void move(JavaTickFixture f, short x) {
        send(
                f,
                ClientboundPackets.MOVE_ENTITY,
                new ClientboundMoveEntity(
                        DONKEY, new EntityDelta.Linear(x, (short) 0, (short) 0), 0, 0, true, true, false));
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void saddle(JavaTickFixture f) {
        sendSaddle(f);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    private static void sendSaddle(JavaTickFixture f) {
        var item = DataTables.defaults().items().stream()
                .filter(definition -> definition.key().equals("minecraft:saddle"))
                .findFirst()
                .orElseThrow();
        var packet = new InventoryPackets.Equipment(
                DONKEY,
                List.of(new InventoryPackets.EquipmentEntry(
                        EquipmentSlot.SADDLE, new SimItemStack(item, 1, Components.EMPTY))));
        send(f, type(InventoryPackets.Equipment.class), packet);
    }

    @SuppressWarnings("unchecked")
    private static <T> PacketType<T> type(Class<T> record) {
        return (PacketType<T>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == record)
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Check> T enabled(JavaTickFixture f, Class<T> type) {
        f.player.setExperimentalChecks(true);
        T check = f.player.checkManager.getCheck(type);
        check.setEnabled(true);
        check.reload();
        check.violations = 0;
        return check;
    }

    @SuppressWarnings("unchecked")
    private static <T> void send(JavaTickFixture f, PacketType<T> type, T packet) {
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, type, packet, false);
        var replication = f.player.packetEntityReplication;
        Object raw = packet;
        if (raw instanceof ClientboundAddEntity add) {
            replication.onAddEntity((PacketSendEvent<ClientboundAddEntity>) (PacketSendEvent<?>) event, f.player, add);
        } else if (raw instanceof ClientboundMoveEntity move) {
            replication.onMoveEntity(
                    (PacketSendEvent<ClientboundMoveEntity>) (PacketSendEvent<?>) event, f.player, move);
        } else {
            replication.onSetEquipment(
                    (PacketSendEvent<InventoryPackets.Equipment>) (PacketSendEvent<?>) event,
                    f.player,
                    (InventoryPackets.Equipment) raw);
        }
        event.tasksAfter().forEach(Runnable::run);
    }
}
