package ac.cult.cultac.bedrock.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.junit.jupiter.api.Test;

class BedrockSubClientsTest {
    @Test
    void readsTheWrapperSubClientIds() {
        BedrockPacketWrapper wrapper = BedrockPacketWrapper.create(0, 3, 7, new NetworkStackLatencyPacket(), null);
        try {
            assertEquals(3, BedrockSubClients.sender(wrapper));
            assertEquals(7, BedrockSubClients.target(wrapper));
        } finally {
            wrapper.release();
        }
    }
}
