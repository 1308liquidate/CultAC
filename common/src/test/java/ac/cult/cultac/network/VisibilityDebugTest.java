package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.BlockPlace;
import ac.cult.cultac.utils.blockplace.ClientBlockActions;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.ClientVisibility;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class VisibilityDebugTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);

    @Test
    void firstActualPlacementIsWatchedAndOnlyVisibilityTransitionsAreReported() throws Exception {
        try (var setup = new Setup()) {
            setup.place(new BlockPos(2, 64, 2));
            assertTrue(setup.messages.isEmpty());
            setup.fixture.player.visibilityDebug.toggle();
            setup.place(TARGET);
            setup.tick();
            assertEquals("[visibility] Watching block at 0, 64, 2.", setup.messages.get(1));
            assertEquals("[visibility] Block at 0, 64, 2 is visible.", setup.messages.get(2));
            setup.tick();
            assertEquals(3, setup.messages.size());

            setup.place(new BlockPos(3, 64, 2));
            setup.wall("stone");
            setup.tick();
            assertEquals("[visibility] Block at 0, 64, 2 is occluded.", setup.messages.get(3));
            setup.tick();
            assertEquals(4, setup.messages.size());
            setup.wall("air");
            setup.tick();
            assertEquals("[visibility] Block at 0, 64, 2 is visible.", setup.messages.get(4));

            setup.fixture.player.visibilityDebug.toggle();
            setup.wall("stone");
            setup.tick();
            setup.place(new BlockPos(4, 64, 2));
            assertEquals(6, setup.messages.size());
            assertEquals("[visibility] Disabled.", setup.messages.get(5));
        }
    }

    @Test
    void radiusPausesUpdatesWithoutReplacingTheTargetAndRetogglingArmsANewTarget() throws Exception {
        try (var setup = new Setup()) {
            var player = setup.fixture.player;
            player.visibilityDebug.toggle();
            setup.place(TARGET);
            setup.tick();
            player.z = -20;
            setup.wall("stone");
            setup.tick();
            assertEquals(3, setup.messages.size());
            player.z = -6;
            setup.tick();
            // Eight blocks away: the debug radius is independent of the 4.5-block interaction attribute.
            assertEquals("[visibility] Block at 0, 64, 2 is occluded.", setup.messages.get(3));
            setup.wall("air");
            setup.tick();
            assertEquals("[visibility] Block at 0, 64, 2 is visible.", setup.messages.get(4));

            player.visibilityDebug.toggle();
            player.visibilityDebug.toggle();
            player.z = -1.5;
            setup.place(new BlockPos(3, 64, 2));
            setup.tick();
            assertEquals("[visibility] Watching block at 3, 64, 2.", setup.messages.get(7));
            assertEquals("[visibility] Block at 3, 64, 2 is visible.", setup.messages.get(8));
        }
    }

    @Test
    void failedPlacementsAndBlockInteractionsDoNotConsumeTheWaitingTarget() throws Exception {
        try (var setup = new Setup()) {
            var player = setup.fixture.player;
            player.visibilityDebug.toggle();
            setup.block(TARGET, "stone");
            setup.block(TARGET.offset(0, -1, 0), "stone");
            setup.place(TARGET);
            assertEquals(1, setup.messages.size(), "An obstructed placement must not become the target");

            var door = new BlockPos(3, 64, 2);
            setup.block(door, "oak_trapdoor");
            for (String item : List.of("minecraft:stone", "minecraft:oak_trapdoor")) {
                var held = ItemUtil.modelItems().stack(item, 1);
                player.getInventory().inventory.setHeldItem(held);
                var use = new BlockPlace(player, Hand.MAIN_HAND, door, Direction.UP, held, null);
                use.setCursor(new Vec3(.5, 1, .5));
                ClientBlockActions.useOn(player, use);
                assertEquals(1, setup.messages.size(), "Toggling a trapdoor with " + item + " is not placement");
            }
            setup.place(new BlockPos(4, 64, 2));
            assertEquals("[visibility] Watching block at 4, 64, 2.", setup.messages.get(1));
        }
    }

    @Test
    void debugReadsTheCurrentWorldAndDoesNotReportACoordinateInAnotherDimension() throws Exception {
        try (var setup = new Setup()) {
            var player = setup.fixture.player;
            player.visibilityDebug.toggle();
            setup.place(TARGET);
            setup.tick();
            var world = player.compensatedWorld;
            world.beginSelectionSnapshot();
            world.startPredicting();
            world.updateBlock(new BlockPos(0, 64, 0), state("stone"));
            world.updateBlock(new BlockPos(0, 65, 0), state("stone"));
            world.stopPredicting(1);
            player.visibilityDebug.update();
            assertEquals("[visibility] Block at 0, 64, 2 is occluded.", setup.messages.get(3));
            world.endSelectionSnapshot();

            var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
            world.setDimension("minecraft:the_nether", dimension);
            setup.wall("air");
            player.visibilityDebug.update();
            assertEquals(4, setup.messages.size());
            world.setDimension("minecraft:overworld", dimension);
            player.visibilityDebug.update();
            assertEquals("[visibility] Block at 0, 64, 2 is visible.", setup.messages.get(4));
        }
    }

    @Test
    void partialShapesLeaveVisibleOpenings() throws Exception {
        try (var setup = new Setup()) {
            setup.place(TARGET);
            var visibility = new ClientVisibility(setup.fixture.player);
            setup.wall("glass");
            assertEquals(ClientVisibility.BlockVisibility.OCCLUDED, visibility.blockVisibility(TARGET, 10));
            setup.wall("air");
            setup.block(new BlockPos(0, 64, 0), "oak_slab");
            assertEquals(ClientVisibility.BlockVisibility.VISIBLE, visibility.blockVisibility(TARGET, 10));
        }
    }

    private static int state(String block) {
        return DataTables.defaults().registry().block("minecraft:" + block).defaultState();
    }

    private static String text(Component component) {
        String content = component instanceof TextComponent text ? text.content() : "";
        for (Component child : component.children()) content += text(child);
        return content;
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices();
        final RecordReceiveFixture fixture = new RecordReceiveFixture(ProtocolVersion.V26_3);
        final List<String> messages = new ArrayList<>();

        Setup() throws Exception {
            var player = fixture.player;
            player.platformPlayer = mock(PlatformPlayer.class);
            var platformWorld = mock(ac.cult.cultac.platform.api.world.PlatformWorld.class);
            when(player.platformPlayer.getWorld()).thenReturn(platformWorld);
            when(platformWorld.getName()).thenReturn("world");
            doAnswer(invocation -> {
                        messages.add(text(invocation.getArgument(0)));
                        return null;
                    })
                    .when(player.platformPlayer)
                    .sendMessage(any(Component.class));
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
        }

        void place(BlockPos position) {
            var player = fixture.player;
            var support = position.offset(0, -1, 0);
            block(support, "stone");
            var held = ItemUtil.modelItems().stack("minecraft:stone", 1);
            player.getInventory().inventory.setHeldItem(held);
            var place = new BlockPlace(player, Hand.MAIN_HAND, support, Direction.UP, held, null);
            place.setCursor(new Vec3(.5, 1, .5));
            ClientBlockActions.useOn(player, place);
        }

        void tick() {
            fixture.player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    fixture.user,
                    ConnectionPhase.PLAY,
                    ServerboundPackets.CLIENT_TICK_END,
                    ServerboundPackets.CLIENT_TICK_END.opaqueValue()));
        }

        void wall(String block) {
            block(new BlockPos(0, 64, 0), block);
            block(new BlockPos(0, 65, 0), block);
        }

        void block(BlockPos position, String block) {
            fixture.player.compensatedWorld.applyBlockChangeRawDANGER(
                    position.x(), position.y(), position.z(), state(block));
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
