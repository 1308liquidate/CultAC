package ac.cult.reload.api;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Opaque native handles are host-owned; engine classes must not cross this boundary. */
public interface HostContext {
    String generationId();

    String platform();

    String build();

    Path directory();

    Object plugin();

    Object server();

    Object logger();

    ResourceScope resources();

    boolean current();

    boolean owns(Object session);

    boolean pin(Object session);

    void release(Object session, CompletionStage<Void> cleanup);

    /** The pinned generation, or null for an unmanaged native connection. */
    String ownerOf(Object connection);

    void bindUser(Object connection, UUID uuid, Object nativePlayer, Object user);

    void unbindUser(Object connection);

    Object shared(String key, Supplier<Object> create);

    Object publicApi();

    Object wrap(Object value);

    Object wrap(Object value, Object user);

    CompletionStage<String> update(String filename);

    List<String> status();
}
