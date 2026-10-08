package ac.cult.cultac.manager.init.start;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.utils.anticheat.LogUtil;

public final class JavaPacketLoggerInit implements StartableInitable {
    @Override
    public void start() {
        var api = CultAPI.INSTANCE;
        api.getNetworkManager()
                .initializePacketLogger(
                        api.getGrimPlugin().getDataFolder().toPath().resolve("javapacketlogs"),
                        api.getPlatform().getName(),
                        api.getExternalAPI().getGrimVersion(),
                        LogUtil::info);
    }
}
