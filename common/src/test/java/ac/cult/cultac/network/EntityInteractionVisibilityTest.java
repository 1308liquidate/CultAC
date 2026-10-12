package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.visibility.InteractEntityVisibility;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.ClientVisibility;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EntityInteractionVisibilityTest {
    static Stream<ProtocolVersion> clients() {
        return Stream.of(ProtocolVersion.V1_21_11, ProtocolVersion.V26_3);
    }

    static Stream<Arguments> targets() {
        return clients()
                .flatMap(version -> Stream.of(
                                EntityTypeIds.PLAYER,
                                EntityTypeIds.COW,
                                EntityTypeIds.ARMOR_STAND,
                                EntityTypeIds.OAK_BOAT,
                                EntityTypes.defaults()
                                        .byKey("minecraft:minecart")
                                        .id())
                        .map(type -> Arguments.of(version, type)));
    }

    @ParameterizedTest
    @MethodSource("targets")
    void blockedRightClicksAreCancelledBeforeForwardingAndClearClicksPass(ProtocolVersion version, int type)
            throws Exception {
        try (var setup = new Setup(version)) {
            setup.entity(42, type, 1.5);
            var actions = version.atLeast(ProtocolVersion.V26_1)
                    ? List.of(InteractAction.INTERACT_AT)
                    : List.of(InteractAction.INTERACT, InteractAction.INTERACT_AT);
            for (String wall : new String[] {"stone", "glass"}) {
                setup.wall(wall);
                for (var action : actions) {
                    for (var hand : Hand.values()) {
                        double before = setup.check.violations;
                        assertFalse(setup.fixture.channel.writeInbound(setup.encode(42, action, hand)));
                        assertNull(setup.fixture.channel.readInbound());
                        assertEquals(before + 1, setup.check.violations);
                    }
                }
            }
            setup.tickEnd();
            setup.wall("air");
            double before = setup.check.violations;
            for (var action : actions) {
                for (var hand : Hand.values()) setup.fixture.forward(setup.encode(42, action, hand));
            }
            assertEquals(before, setup.check.violations);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aPredictedWallRemovalDoesNotExposeTheEntityUntilTheNextPick(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.entity(42, EntityTypeIds.COW, 1.5);
            setup.wall("stone");
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            var world = setup.player.compensatedWorld;
            world.startPredicting();
            world.updateBlock(0, 64, 0, 0);
            world.updateBlock(0, 65, 0, 0);
            world.stopPredicting(1);
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.tickEnd();
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void theFirstRightClickCapturesSelectionBeforeLaterLocalPredictions(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.entity(42, EntityTypeIds.COW, 1.5);
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            var world = setup.player.compensatedWorld;
            world.startPredicting();
            world.updateBlock(0, 64, 0, state("stone"));
            world.updateBlock(0, 65, 0, state("stone"));
            world.stopPredicting(1);
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.tickEnd();
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void weaponReachAndHitboxMarginCannotExtendRightClickReach(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.entity(42, EntityTypeIds.COW, 2.5);
            var sword = ItemUtil.modelItems().stack("minecraft:iron_sword", 1);
            var components = new Components(
                    Map.of("minecraft:attack_range", JsonParser.parseString("{\"max_reach\":10,\"hitbox_margin\":2}")));
            setup.player.getInventory().inventory.setHeldItem(new SimItemStack(sword.definition(), 1, components));
            var visibility = new ClientVisibility(setup.player);
            assertFalse(visibility.entityHidden(setup.player.compensatedEntities.getEntity(42), 0));
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.player.compensatedEntities.getSelf().entityInteractionRange.replace(4.5, List.of());
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.player.compensatedEntities.getSelf().entityInteractionRange.replace(0, List.of());
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @Test
    void rangeToleranceRemainsOneMillimetreAtThePlayersEye() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            var visibility = new ClientVisibility(setup.player);
            setup.entity(42, EntityTypeIds.PLAYER, 1.8005);
            assertFalse(visibility.entityInteractionHidden(setup.player.compensatedEntities.getEntity(42), 0));
            setup.entity(43, EntityTypeIds.PLAYER, 1.8011);
            assertTrue(visibility.entityInteractionHidden(setup.player.compensatedEntities.getEntity(43), 0));
        }
    }

    @Test
    void attacksAndMissingTargetsAreOwnedByOtherChecks() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.entity(42, EntityTypeIds.COW, 1.5);
            setup.wall("stone");
            assertFalse(setup.route(42, InteractAction.ATTACK).isCancelled());
            assertFalse(setup.route(999, InteractAction.INTERACT).isCancelled());
            assertFalse(setup.route(999, InteractAction.INTERACT_AT).isCancelled());
            assertEquals(0, setup.check.violations);
            assertEquals("InteractEntityVisibility", setup.check.getConfigName());
            assertEquals("cult.interact.entity.visibility", setup.check.getStableKey());
        }
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block("minecraft:" + name).defaultState();
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture;
        final CultPlayer player;
        final ProtocolVersion version;
        final InteractEntityVisibility check;

        Setup(ProtocolVersion version) throws Exception {
            this.version = version;
            fixture = version == ProtocolVersion.V26_3
                    ? new RecordReceiveFixture(version, false)
                    : RecordReceiveFixture.olderHost(version, false);
            services.useNetwork(fixture.manager);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            check = player.checkManager.getCheck(InteractEntityVisibility.class);
            check.setEnabled(true);
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.registryState = new ClientComponentRegistries();
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
            fixture.registrar.register(routes -> new CheckManagerListener()
                    .registerForwardingEarlyReceivePackets(routes, PacketListenerPriority.LOW));
        }

        void entity(int id, int type, double z) {
            player.compensatedEntities.addEntity(id, type, new Vec3(.5, 64, z), 0, 0, 0);
        }

        void wall(String name) {
            player.compensatedWorld.applyBlockChangeRawDANGER(0, 64, 0, state(name));
            player.compensatedWorld.applyBlockChangeRawDANGER(0, 65, 0, state(name));
        }

        PacketReceiveEvent<ServerboundInteract> route(int id, InteractAction action) {
            var packet = new ServerboundInteract(
                    id,
                    action,
                    Hand.MAIN_HAND,
                    action == InteractAction.INTERACT_AT ? Optional.of(new Vec3d(0, .5, -.3)) : Optional.empty(),
                    false);
            var event =
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
            player.checkManager.dispatchEarlyReceive(event);
            return event;
        }

        ByteBuf encode(int id, InteractAction action, Hand hand) {
            var frame = fixture.id(ServerboundPackets.INTERACT, "interact");
            Wire.writeVarInt(frame, id);
            if (version.atLeast(ProtocolVersion.V26_1)) {
                Wire.writeVarInt(frame, hand.ordinal());
                Wire.writeLpVec3(frame, new Vec3d(0, .5, -.3));
            } else {
                Wire.writeVarInt(frame, action.ordinal());
                if (action == InteractAction.INTERACT_AT)
                    frame.writeFloat(0).writeFloat(.5F).writeFloat(-.3F);
                Wire.writeVarInt(frame, hand.ordinal());
            }
            return frame.writeBoolean(false);
        }

        void tickEnd() {
            player.checkManager.getListener(ClientVisibilityProcessor.class).onPlayerTickEnd(null);
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                services.close();
            }
        }
    }
}
