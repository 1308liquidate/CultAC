package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.EntityHitboxes;
import ac.cult.cultac.checks.impl.combat.EntityReach;
import ac.cult.cultac.checks.impl.combat.PlayerHitboxes;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.checks.impl.combat.ReachCheck;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReachInteractionRangeTest {
    // ServerPlayer#CREATIVE_ENTITY_INTERACTION_RANGE_MODIFIER
    private static final AttributeModifier CREATIVE =
            new AttributeModifier("minecraft:creative_mode_entity_range", 2.0, AttributeModifier.Operation.ADD_VALUE);

    @Test
    void attacksAreBoundedByTheEntityInteractionRangeAttribute() throws Exception {
        try (var setup = new Setup()) {
            assertFalse(setup.attack(2.5));
            assertTrue(setup.attack(3.5));
            setup.self().entityInteractionRange.replace(4.5, List.of());
            assertFalse(setup.attack(3.5));
            assertTrue(setup.attack(5.0));
        }
    }

    @Test
    void creativeReachComesFromTheServerModifierNotTheGameMode() throws Exception {
        try (var setup = new Setup()) {
            setup.player.gamemode = GameMode.CREATIVE;
            setup.self().onGameModeChange(true);
            assertTrue(setup.attack(4.5));
            setup.self().entityInteractionRange.replace(3.0, List.of(CREATIVE));
            assertFalse(setup.attack(4.5));
            assertTrue(setup.attack(5.5));
        }
    }

    @Test
    void gameModeChangesKeepTheAttributeAndRespawnsDropOnlyModifiers() throws Exception {
        try (var setup = new Setup()) {
            setup.self().entityInteractionRange.replace(6.0, List.of(CREATIVE));
            setup.self().onGameModeChange(false);
            assertEquals(8.0, setup.self().getEntityInteractionRange());
            setup.self().onRespawn(true, false);
            assertEquals(8.0, setup.self().getEntityInteractionRange());
            setup.self().onRespawn(false, false);
            assertEquals(6.0, setup.self().getEntityInteractionRange());
        }
    }

    @Test
    void mainHandAttackRangeReplacesTheAttributeLimit() throws Exception {
        try (var setup = new Setup()) {
            setup.hold("{\"max_reach\":6,\"hitbox_margin\":0.3}");
            assertFalse(setup.attack(6.2));
            assertTrue(setup.attack(6.5));
            setup.self().entityInteractionRange.replace(10.0, List.of());
            assertTrue(setup.attack(6.5));
        }
    }

    @Test
    void playerAndEntityTargetsFlagTheirOwnReachAndHitboxChecks() throws Exception {
        try (var setup = new Setup()) {
            assertEquals(List.of("PlayerReach"), setup.attack(EntityTypeIds.PLAYER, .5, 3.5));
            assertEquals(List.of("EntityReach"), setup.attack(EntityTypeIds.ZOMBIE, .5, 3.5));
            assertEquals(List.of(), setup.attack(EntityTypeIds.ZOMBIE, .5, 2.5));
            assertEquals(List.of("PlayerHitboxes"), setup.attack(EntityTypeIds.PLAYER, 2.5, 2.0));
            assertEquals(List.of("EntityHitboxes"), setup.attack(EntityTypeIds.ZOMBIE, 2.5, 2.0));
        }
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture;
        final CultPlayer player;
        final List<Check> checks;
        int nextEntity = 100;

        Setup() throws Exception {
            fixture = new RecordReceiveFixture(ProtocolVersion.V26_3, false);
            services.useNetwork(fixture.manager);
            CultAPI.INSTANCE.getPlayerDataManager().addUser(fixture.user);
            player = fixture.user.getCultPlayer();
            player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
            checks = List.of(
                    player.checkManager.getCheck(PlayerReach.class),
                    player.checkManager.getCheck(EntityReach.class),
                    player.checkManager.getCheck(PlayerHitboxes.class),
                    player.checkManager.getCheck(EntityHitboxes.class));
            checks.forEach(check -> check.setEnabled(true));
            player.gamemode = GameMode.SURVIVAL;
            player.x = .5;
            player.y = 64;
            player.z = -1.5;
            player.registryState = new ClientComponentRegistries();
            player.getSetbackTeleportUtil().hasFullyLoaded = true;
            var world = player.compensatedWorld;
            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
            world.setDimension("minecraft:overworld", dimension);
            world.ensureValidationChunkLoaded(0, 0);
            world.ensureValidationChunkLoaded(0, -1);
        }

        ac.cult.cultac.utils.data.packetentity.PacketEntitySelf self() {
            return player.compensatedEntities.getSelf();
        }

        void hold(String attackRange) {
            var sword = ItemUtil.modelItems().stack("minecraft:iron_sword", 1);
            var components = new Components(Map.of("minecraft:attack_range", JsonParser.parseString(attackRange)));
            player.getInventory().inventory.setHeldItem(new SimItemStack(sword.definition(), 1, components));
        }

        // Attacks a player looked at head-on whose hitbox face is this far from the eye; returns whether it flagged.
        boolean attack(double distance) {
            return !attack(EntityTypeIds.PLAYER, .5, distance).isEmpty();
        }

        // Attacks an entity at this x whose hitbox face is this far down the look; returns the checks that flagged.
        List<String> attack(int type, double x, double distance) {
            int id = nextEntity++;
            player.compensatedEntities.addEntity(id, type, new Vec3(x, 64, distance - 1.2), 0, 0, 0);
            var packet = new ServerboundInteract(id, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false);
            var event =
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.INTERACT, packet);
            double[] before =
                    checks.stream().mapToDouble(check -> check.violations).toArray();
            player.checkManager.dispatchReceiveHandlers(event);
            for (Check check : checks) {
                if (check instanceof ReachCheck reach) reach.onPlayerTickEnd(null);
            }
            player.checkManager.getListener(ClientVisibilityProcessor.class).onPlayerTickEnd(null);
            List<String> flagged = new java.util.ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                if (checks.get(i).violations > before[i])
                    flagged.add(checks.get(i).getCheckName());
            }
            return flagged;
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
