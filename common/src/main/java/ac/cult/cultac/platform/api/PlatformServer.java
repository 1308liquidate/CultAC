package ac.cult.cultac.platform.api;

import ac.cult.cultac.platform.api.sender.Sender;

public interface PlatformServer {

    String getPlatformImplementationString();

    void dispatchCommand(Sender sender, String command);

    Sender getConsoleSender();

    void registerOutgoingPluginChannel(String name);

    void forwardAlert(String message);

    boolean isProxyForwardingEnabled();

    /**
     * Places a real server block as an explicit validation stimulus, then reports through the
     * callback. Only platforms with server authority support this.
     */
    void applyValidationBlock(
            ac.cult.cultac.platform.api.player.PlatformPlayer player,
            int x,
            int y,
            int z,
            String state,
            Runnable applied);

    double getTPS();
}
