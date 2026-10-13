package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VelocityClientAdmissionTest {
    @ParameterizedTest
    @ValueSource(ints = {47, 340, 763, 764, 766, 767})
    void outdatedClientsWithoutViaAreDisconnectedBeforeProtocolResolution(int protocol) {
        Player player = player(protocol);
        var channel = new EmbeddedChannel();
        try {
            assertTrue(VelocityProtocols.disconnectOutdatedClient(player, channel));
            verify(player).disconnect(Component.text("Outdated client! Please use Minecraft 1.21.2 or newer."));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {768, 769, 770, 771, 772, 773, 774})
    void clientsFrom1212OnwardAreAdmitted(int protocol) {
        Player player = player(protocol);
        var channel = new EmbeddedChannel();
        try {
            assertFalse(VelocityProtocols.disconnectOutdatedClient(player, channel));
            verify(player, never()).disconnect(any(Component.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {47, 340, 763, 764, 766, 767})
    void outdatedClientsBehindViaKeepExistingAdmissionBehavior(int protocol) {
        Player player = player(protocol);
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("via-decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("via-encoder", new ChannelOutboundHandlerAdapter());
        try {
            assertFalse(VelocityProtocols.disconnectOutdatedClient(player, channel));
            verify(player, never()).disconnect(any(Component.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static Player player(int protocol) {
        Player player = mock(Player.class);
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.getProtocolVersion(protocol));
        return player;
    }
}
