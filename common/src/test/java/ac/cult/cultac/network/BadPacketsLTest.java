package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.checks.impl.badpackets.BadPacketsL;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerAction;
import org.junit.jupiter.api.Test;

class BadPacketsLTest {
    @org.junit.jupiter.api.BeforeAll
    static void registerVerboseTags() {
        // The plugin registers {digging_lower} at startup through VerboseCodecs' initializer.
        ac.cult.cultac.checks.impl.verbose.VerboseCodecs.digging(PlayerAction.DROP_ITEM);
    }

    @Test
    void changeDestroyDirectionCarriesTheTargetAndFaceButNeverASequence() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            var check = f.player.checkManager.getCheck(BadPacketsL.class);
            // MultiPlayerGameMode#continueDestroyBlock: the mined block and the newly hit face, sequence 0.
            receive(
                    f,
                    check,
                    new ServerboundPlayerAction(
                            PlayerAction.CHANGE_DESTROY_DIRECTION, new BlockPos(12, 64, -7), Direction.EAST, 0));
            assertEquals(0, check.getViolations());

            receive(
                    f,
                    check,
                    new ServerboundPlayerAction(
                            PlayerAction.CHANGE_DESTROY_DIRECTION, new BlockPos(12, 64, -7), Direction.EAST, 5));
            assertEquals(1, check.getViolations());
        }
    }

    @Test
    void otherNonDigActionsStillRequireTheZeroedShape() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            var check = f.player.checkManager.getCheck(BadPacketsL.class);
            receive(
                    f,
                    check,
                    new ServerboundPlayerAction(PlayerAction.DROP_ITEM, new BlockPos(0, 0, 0), Direction.DOWN, 0));
            assertEquals(0, check.getViolations());
            receive(
                    f,
                    check,
                    new ServerboundPlayerAction(PlayerAction.DROP_ITEM, new BlockPos(1, 0, 0), Direction.DOWN, 0));
            assertEquals(1, check.getViolations());
        }
    }

    private static void receive(RecordReceiveFixture f, BadPacketsL check, ServerboundPlayerAction packet) {
        var event = new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, ServerboundPackets.PLAYER_ACTION, packet);
        check.onPlayerAction(event, f.player, packet);
    }
}
