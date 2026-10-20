package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.impl.visibility.InteractEntityVisibility;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundProjectilePower;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BoundingBoxSize;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import ac.cult.cultac.utils.visibility.ClientVisibility;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

class EntityInteractionVisibilityTest {
    // An add_entity data value that names no entity.
    private static final int NO_OWNER = -1;

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

    @ParameterizedTest
    @MethodSource("clients")
    void aWindChargeIsInRangeByItsBoxBelowItsPosition(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            // The charge's position is 3.048 above the eye, its box 2.898.
            setup.player.compensatedEntities.addEntity(
                    42, EntityTypeIds.WIND_CHARGE, new Vec3(.5594, 68.6679, -1.5126), 0, 0, 0);
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.player.compensatedEntities.addEntity(
                    43, EntityTypeIds.WIND_CHARGE, new Vec3(.5594, 68.6679 + .1511, -1.5126), 0, 0, 0);
            assertTrue(setup.route(43, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aWindChargeMovesAlongItsSpawnVelocityEachClientTick(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            // The charge is 9.19 from the eye at its server position, 2.43 after four client ticks.
            setup.spawn(
                    42,
                    "minecraft:wind_charge",
                    new Vec3d(1.8798, 65.5354, 7.768),
                    new Vec3d(-.10510895, -.0076909, -1.70396142));
            for (int tick = 0; tick < 4; tick++) {
                assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
                setup.clientTick();
            }
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aProjectileStopsAtTheFirstBlockOnItsPath(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.player.compensatedWorld.applyBlockChangeRawDANGER(0, 64, 2, state("stone"));
            // The block spans z 2..3 on 42's path: 42 stops at z 3, out of range; 43 passes it and ends in range.
            setup.spawn(42, "minecraft:wind_charge", new Vec3d(.5, 64.5, 6), new Vec3d(0, 0, -1.5));
            setup.spawn(43, "minecraft:wind_charge", new Vec3d(2.5, 64.5, 6), new Vec3d(0, 0, -1.5));
            for (int tick = 0; tick < 4; tick++) setup.clientTick();
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            assertFalse(setup.route(43, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aFireballAcceleratesByATenthAndKeepsNinetyFivePercentEachClientTick(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            // Launched at 1 block per tick, a fireball covers 3.261 in three client ticks: 42 is in range from 3.2
            // on, 43 up to 3.35.
            setup.spawn(42, "minecraft:fireball", new Vec3d(.5, 65.12, 5.2), new Vec3d(0, 0, -1));
            setup.spawn(43, "minecraft:fireball", new Vec3d(.5, 65.12, -1.35), new Vec3d(0, 0, 1));
            for (int tick = 0; tick < 3; tick++) {
                assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
                setup.clientTick();
            }
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            assertFalse(setup.route(43, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aFireballInWaterKeepsEightyPercentEachClientTick(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            for (int z = 0; z < 6; z++)
                setup.player.compensatedWorld.applyBlockChangeRawDANGER(0, 65, z, state("water"));
            setup.spawn(42, "minecraft:fireball", new Vec3d(.5, 65.12, 5.2), new Vec3d(0, 0, -1));
            var fireball = setup.player.compensatedEntities.getEntity(42);
            // wasTouchingWater starts false, so the first tick keeps 95%; then 80%: 2.774 in three ticks, 3.504 in
            // four, against 3.261 in three out of water.
            for (int tick = 0; tick < 4; tick++) {
                assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
                setup.clientTick();
                if (tick == 1) assertEquals(3.239, fireball.clientPhysicalPosition.z, 1.0E-6);
            }
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void setEntityMotionSetsTheVelocityAProjectileTicksAlong(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.spawn(42, "minecraft:wind_charge", new Vec3d(.5, 65.12, 5), Vec3d.ZERO);
            setup.motion(42, new Vec3d(0, 0, -1.5));
            for (int tick = 0; tick < 3; tick++) {
                assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
                setup.clientTick();
            }
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void projectilePowerSetsTheAccelerationAFireballTicksWith(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.spawn(42, "minecraft:fireball", new Vec3d(.5, 65.12, 5.2), Vec3d.ZERO);
            var fireball = setup.player.compensatedEntities.getEntity(42);
            setup.motion(42, new Vec3d(0, 0, -1));
            setup.power(42, .05);
            // (1 + 0.05) * 0.95 per tick: 2.985 in three ticks, short of the 3.2 that brings it in range; 3.976 in
            // four. Acceleration 0.1 covers 3.261 in three.
            for (int tick = 0; tick < 3; tick++) {
                assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
                setup.clientTick();
                if (tick == 0) assertEquals(5.2 - .9975, fireball.clientPhysicalPosition.z, 1.0E-6);
            }
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.clientTick();
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aDeflectedFireballTicksWithTheMotionAndPowerOfItsBundle(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.spawn(42, "minecraft:fireball", new Vec3d(.5, 65.12, 5.2), new Vec3d(0, 0, -1));
            var fireball = setup.player.compensatedEntities.getEntity(42);
            setup.clientTick();
            assertEquals(5.2 - 1.045, fireball.clientPhysicalPosition.z, 1.0E-6);
            // Projectile#deflect reverses the motion and AbstractHurtingProjectile#onDeflection halves the power.
            setup.motion(42, new Vec3d(0, 0, 1));
            setup.power(42, .05);
            setup.clientTick();
            assertEquals(5.2 - 1.045 + .9975, fireball.clientPhysicalPosition.z, 1.0E-6);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aFireballStopsAtAMobInFrontOfAWall(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.player.compensatedWorld.applyBlockChangeRawDANGER(0, 64, 2, state("stone"));
            setup.cow(41, new Vec3(.5, 64, 3.7));
            // The step from z 5 to 2.815 meets the cow's face at 4.15 before the wall's at 3.
            var fireball = setup.fireball(42, 5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(4.15, fireball.clientPhysicalPosition.z, 1.0E-6);
            assertEquals(4.15, fireball.getPossibleMovementCollisionBoxes().maxZ - .5, 1.0E-6);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aFireballStopsAtTheWallBeforeAMobBehindIt(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.player.compensatedWorld.applyBlockChangeRawDANGER(0, 64, 2, state("stone"));
            setup.cow(41, new Vec3(.5, 64, 1.5));
            var fireball = setup.fireball(42, 5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(3, fireball.clientPhysicalPosition.z, 1.0E-6);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aFireballPassesMobsBeyondItsStepOrBesideItsPath(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.cow(40, new Vec3(.5, 64, 1.5));
            setup.cow(41, new Vec3(1.5, 64, 3.7));
            var fireball = setup.fireball(42, 5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(5 - 2.185, fireball.clientPhysicalPosition.z, 1.0E-6);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aFireballPassesThroughItsOwnerUntilItHasLeftIt(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.cow(41, new Vec3(.5, 64, 3.7));
            var fireball = setup.fireball(42, 5, -2.2, 41);
            setup.clientTick();
            assertEquals(5 - 2.185, fireball.clientPhysicalPosition.z, 1.0E-6);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aMobThatMayHaveMovedOffThePathLeavesBothStopsAsCandidates(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.cow(41, new Vec3(.5, 64, 3.7));
            // Until the client answers the move, the cow may still be on the path or interpolating off it.
            setup.player.compensatedEntities.getEntity(41).onFirstTransaction(false, true, 3.5, 64, 3.7, setup.player);
            var fireball = setup.fireball(42, 5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(5 - 2.185, fireball.clientPhysicalPosition.z, 1.0E-6);
            var stops = fireball.getPossibleMovementCollisionBoxCandidates().stream()
                    .map(box -> box.minZ + .5)
                    .sorted()
                    .toList();
            assertEquals(2, stops.size());
            assertEquals(5 - 2.185, stops.get(0), 1.0E-6);
            assertEquals(4.15, stops.get(1), 1.0E-6);
            var envelope = fireball.getPossibleMovementCollisionBoxes();
            assertEquals(5 - 2.185 - .5, envelope.minZ, 1.0E-6);
            assertEquals(4.15 + .5, envelope.maxZ, 1.0E-6);
            assertNull(fireball.getExactMovementCollisionBox());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aFireballStopsAtTheLocalPlayer(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            // The player's box spans z -1.8 to -1.2; the step runs from .5 to -1.685.
            var fireball = setup.fireball(42, .5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(-1.2, fireball.clientPhysicalPosition.z, 1.0E-6);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aWindChargePassesThroughAnEndCrystalThatStopsAFireball(ProtocolVersion version) throws Exception {
        // The crystal's 2-wide box spans z 2.7 to 4.7 across both steps from z 5.
        try (var setup = new Setup(version)) {
            setup.player.compensatedEntities.addEntity(41, EntityTypeIds.END_CRYSTAL, new Vec3(.5, 64, 3.7), 0, 0, 0);
            var charge = setup.projectile(42, "minecraft:wind_charge", 5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(2.8, charge.clientPhysicalPosition.z, 1.0E-6);
        }
        try (var setup = new Setup(version)) {
            setup.player.compensatedEntities.addEntity(41, EntityTypeIds.END_CRYSTAL, new Vec3(.5, 64, 3.7), 0, 0, 0);
            var fireball = setup.fireball(42, 5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(4.7, fireball.clientPhysicalPosition.z, 1.0E-6);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aWindChargePassesThroughAnotherWindChargeThatStopsAFireball(ProtocolVersion version) throws Exception {
        // The resting charge's box spans z 3.54375 to 3.85625 and y 64.35 to 64.6625 around the path.
        try (var setup = new Setup(version)) {
            setup.projectile(41, "minecraft:wind_charge", 3.7, 0, NO_OWNER);
            var charge = setup.projectile(42, "minecraft:wind_charge", 5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(2.8, charge.clientPhysicalPosition.z, 1.0E-6);
        }
        try (var setup = new Setup(version)) {
            setup.projectile(41, "minecraft:wind_charge", 3.7, 0, NO_OWNER);
            var fireball = setup.fireball(42, 5, -2.2, NO_OWNER);
            setup.clientTick();
            assertEquals(3.85625, fireball.clientPhysicalPosition.z, 1.0E-6);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void theHitMarginGrowsByATwentiethPerTickFromTheThirdTick(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            // Tick 3 runs from z 5.867 to 4.739 and meets the cow's face at 4.95, inflated by 0.05.
            setup.cow(41, new Vec3(.5, 64, 4.5));
            var fireball = setup.fireball(42, 8, -1, NO_OWNER);
            for (int tick = 0; tick < 3; tick++) setup.clientTick();
            assertEquals(5, fireball.clientPhysicalPosition.z, 1.0E-6);
        }
    }

    @Test
    void clientsBefore1_21_2KeepAProjectileAtItsServerPosition() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V1_21_11)) {
            setup.spawn(42, "minecraft:wind_charge", new Vec3d(2.5, 65.12, 4.5), new Vec3d(0, 0, -1.5));
            var charge = setup.player.compensatedEntities.getEntity(42);
            JavaTickFixture.setField(CultPlayer.class, setup.player, "resolvedClientVersion", ClientVersion.V_1_21);
            for (int tick = 0; tick < 4; tick++) setup.clientTick();
            assertEquals(4.5, charge.clientPhysicalPosition.z, 1.0E-9);
            JavaTickFixture.setField(CultPlayer.class, setup.player, "resolvedClientVersion", ClientVersion.V_1_21_2);
            for (int tick = 0; tick < 4; tick++) setup.clientTick();
            assertEquals(-1.5, charge.clientPhysicalPosition.z, 1.0E-9);
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void anInteractionRidingAnAreaEffectCloudOutsideTheOwnTreeIsNeverHidden(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.entity(
                    41,
                    EntityTypes.defaults().byKey("minecraft:area_effect_cloud").id(),
                    1.5);
            setup.entity(
                    42, EntityTypes.defaults().byKey("minecraft:interaction").id(), 1.5);
            setup.wall("stone");
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.player.compensatedEntities.vehicles.applyVehiclePassengers(41, new int[] {42});
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void anInteractionIsPickedByItsReceivedWidthAndHeight(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            // A 2.5 by 5 interaction: its box is 2.69 from the eye, its position 4.79.
            setup.player.compensatedEntities.addEntity(
                    42,
                    EntityTypes.defaults().byKey("minecraft:interaction").id(),
                    new Vec3(.5 - .83046, 64 - 1.04918, -1.5 + 3.89156),
                    0,
                    0,
                    0);
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.player.compensatedEntities.updateEntityMetadata(
                    42,
                    List.of(
                            new EntityMetadata.Entry(8, 2.5F, ByteBuffer.allocate(0)),
                            new EntityMetadata.Entry(9, 5.0F, ByteBuffer.allocate(0))));
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void anInteractionWithANegativeWidthAndHeightSpansBelowItsPosition(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            // The box spans y 65..70 and z .25..2.75, 1.75 from the eye; above the position it would be out of range.
            setup.player.compensatedEntities.addEntity(
                    42, EntityTypes.defaults().byKey("minecraft:interaction").id(), new Vec3(.5, 70, 1.5), 0, 0, 0);
            setup.player.compensatedEntities.updateEntityMetadata(
                    42,
                    List.of(
                            new EntityMetadata.Entry(8, -2.5F, ByteBuffer.allocate(0)),
                            new EntityMetadata.Entry(9, -5.0F, ByteBuffer.allocate(0))));
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @Test
    void aMagmaCubeIsPickedByItsSizeFromSpawnAndAfterEachSizeUpdate() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            // A magma cube 3.6 from the player: in range at size 4, out of range at size 1, its spawn size.
            setup.spawn(42, "minecraft:magma_cube", new Vec3d(.5, 64, -1.5 + 3.6), new Vec3d(0, 0, 0));
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.size(42, 18, 4);
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.size(42, 18, 1);
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @Test
    void aSlimeOfSizeZeroIsPickedAtItsPosition() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            // At eye height 3.1 from the eye: its size 1 box is 2.84 away.
            setup.spawn(42, "minecraft:slime", new Vec3d(.5, 65.62, 1.6), Vec3d.ZERO);
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.size(42, 18, 0);
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            // Bedrock sizing keeps the floor of 1.
            setup.player.movementPlatform = MovementPlatform.BEDROCK;
            var slime = setup.player.compensatedEntities.getEntity(42);
            assertEquals(2.04F * 0.255F, BoundingBoxSize.getWidth(setup.player, slime));
            assertEquals(2.04F * 0.255F, BoundingBoxSize.getHeight(setup.player, slime));
        }
    }

    @Test
    void bedrockSizesAPhantomAsBefore() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            setup.player.movementPlatform = MovementPlatform.BEDROCK;
            setup.spawn(43, "minecraft:phantom", new Vec3d(.5, 65, 3), Vec3d.ZERO);
            var phantom = setup.player.compensatedEntities.getEntity(43);
            // Without size data, size 4; 0.9 + 0.2 * size wide and the type's 0.5 tall.
            assertEquals(0.9F + 4 * 0.2F, BoundingBoxSize.getWidth(setup.player, phantom));
            assertEquals(0.5F, BoundingBoxSize.getHeight(setup.player, phantom));
            setup.size(43, WatchableIndexUtil.PHANTOM_SIZE, 0);
            assertEquals(0.9F + 0.2F, BoundingBoxSize.getWidth(setup.player, phantom));
            assertEquals(0.5F, BoundingBoxSize.getHeight(setup.player, phantom));
        }
    }

    @Test
    void aSlimeOfNegativeSizeSpansBelowItsPosition() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            // Size -4 is 2.08 by 2.08 below the position, 2.13 from the eye; above it, the box would be 3.32 away.
            setup.spawn(42, "minecraft:slime", new Vec3d(.5, 68.22, 1.6), Vec3d.ZERO);
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.size(42, 18, -4);
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @Test
    void aPhantomIsScaledByOnePlusFifteenHundredthsOfItsSize() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            // 42 is in range from size 6 on, 43 from size 1; 0 is the spawn size.
            setup.spawn(42, "minecraft:phantom", new Vec3d(.5, 65.2, 2.3), Vec3d.ZERO);
            setup.spawn(43, "minecraft:phantom", new Vec3d(.5, 65.2, 1.98), Vec3d.ZERO);
            assertTrue(setup.route(43, InteractAction.INTERACT_AT).isCancelled());
            setup.size(43, WatchableIndexUtil.PHANTOM_SIZE, 1);
            assertFalse(setup.route(43, InteractAction.INTERACT_AT).isCancelled());
            setup.size(43, WatchableIndexUtil.PHANTOM_SIZE, 0);
            assertTrue(setup.route(43, InteractAction.INTERACT_AT).isCancelled());
            setup.size(42, WatchableIndexUtil.PHANTOM_SIZE, 4);
            assertTrue(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            setup.size(42, WatchableIndexUtil.PHANTOM_SIZE, 6);
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void anInteractionWithoutSizeDataIsOneByOne(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            // The 1 by 1 box is 2.9 from the eye, the position 3.44.
            setup.player.compensatedEntities.addEntity(
                    42, EntityTypes.defaults().byKey("minecraft:interaction").id(), new Vec3(.5, 65.12, 1.9), 0, 0, 0);
            assertFalse(setup.route(42, InteractAction.INTERACT_AT).isCancelled());
        }
    }

    @Test
    void aPre263BundledSyncSetsAnEntityWithoutInterpolationOntoItsPosition() throws Exception {
        for (int type : new int[] {EntityTypeIds.FIREBALL, EntityTypeIds.ZOMBIE}) {
            try (var setup = new Setup(ProtocolVersion.V1_21_11)) {
                JavaTickFixture.setField(
                        CultPlayer.class, setup.player, "resolvedClientVersion", ClientVersion.V_1_21_11);
                afterBundledSync(setup, type);
                assertEquals(
                        type == EntityTypeIds.ZOMBIE,
                        setup.route(42, InteractAction.INTERACT_AT).isCancelled());
            }
        }
    }

    @Test
    void bundledSyncsOn263AndBedrockStartTheInterpolationAtThePreviousPosition() throws Exception {
        for (var platform : MovementPlatform.values()) {
            for (var client : new ClientVersion[] {ClientVersion.V_1_21_11, ClientVersion.V_26_3}) {
                try (var setup =
                        new Setup(client == ClientVersion.V_26_3 ? ProtocolVersion.V26_3 : ProtocolVersion.V1_21_11)) {
                    JavaTickFixture.setField(CultPlayer.class, setup.player, "resolvedClientVersion", client);
                    setup.player.movementPlatform = platform;
                    var fireball = afterBundledSync(setup, EntityTypeIds.FIREBALL);
                    double top = fireball.newPacketLocation.getPossibleLocationCombined(new SimpleCollisionBox()).maxY;
                    boolean placed = platform == MovementPlatform.JAVA && client == ClientVersion.V_1_21_11;
                    assertEquals(!placed, top > SYNC_FROM.y, platform + " " + client);
                }
            }
        }
    }

    // The sync moves a fireball 19.3 from the eye to a box 2.83 from it.
    private static final Vec3 SYNC_FROM = new Vec3(.5 + 8.0858, 64 + 9.5144, -1.5 + 15.5905);
    private static final Vec3 SYNC_TO = new Vec3(.5 + .9772, 64 + 1.7351, -1.5 + 3.2842);

    private static PacketEntity afterBundledSync(Setup setup, int type) {
        setup.player.compensatedEntities.addEntity(42, type, SYNC_FROM, 0, 0, 0);
        var entity = setup.player.compensatedEntities.getEntity(42);
        entity.onBundledPositionSyncTransaction(SYNC_TO.x, SYNC_TO.y, SYNC_TO.z, setup.player);
        return entity;
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
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, .5, 64, -1.5);
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

        void spawn(int id, String type, Vec3d position, Vec3d velocity) {
            if (fixture.user.getPlayer() == null) fixture.user.bind(Mockito.mock(PlatformPlayer.class));
            var packet = new ClientboundAddEntity(id, type, position, 0, 0, 0, new UUID(0, id), velocity);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, packet, false);
            player.packetEntityReplication.onAddEntity(event, player, packet);
            acknowledge(event);
        }

        void cow(int id, Vec3 position) {
            player.compensatedEntities.addEntity(id, EntityTypeIds.COW, position, 0, 0, 0);
        }

        PacketEntity fireball(int id, double z, double velocityZ, int owner) {
            return projectile(id, "minecraft:fireball", z, velocityZ, owner);
        }

        PacketEntity projectile(int id, String type, double z, double velocityZ, int owner) {
            if (fixture.user.getPlayer() == null) fixture.user.bind(Mockito.mock(PlatformPlayer.class));
            var packet = new ClientboundAddEntity(
                    id,
                    type,
                    new Vec3d(.5, 64.5, z),
                    0,
                    0,
                    owner,
                    new UUID(0, id),
                    new Vec3d(0, 0, velocityZ));
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, packet, false);
            player.packetEntityReplication.onAddEntity(event, player, packet);
            acknowledge(event);
            return player.compensatedEntities.getEntity(id);
        }

        void motion(int id, Vec3d velocity) {
            var packet = new ClientboundEntityMotion(id, velocity);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_MOTION, packet, false);
            player.packetEntityReplication.onSetEntityMotion(event, player, packet);
            acknowledge(event);
        }

        void power(int id, double power) {
            var packet = new ClientboundProjectilePower(id, power);
            var event = new PacketSendEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ClientboundPackets.PROJECTILE_POWER, packet, false);
            player.packetEntityReplication.onProjectilePower(event, player, packet);
            acknowledge(event);
        }

        void size(int id, int index, int size) {
            player.compensatedEntities.updateEntityMetadata(
                    id, List.of(new EntityMetadata.Entry(index, size, ByteBuffer.allocate(0))));
        }

        private void acknowledge(PacketSendEvent<?> event) {
            event.tasksAfter().forEach(Runnable::run);
            for (var write : event.writesAfter()) {
                if (write.packet() instanceof ClientboundPing ping)
                    assertTrue(player.addTransactionResponse(ping.id()));
            }
        }

        void clientTick() {
            player.packetEntityReplication.onPlayerTickEnd(null);
            tickEnd();
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
