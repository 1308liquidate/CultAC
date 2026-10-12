package ac.cult.reload.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface RuntimeInstance {
    void load() throws Exception;

    void start() throws Exception;

    CompletionStage<Void> close();

    void attach(Object connection, Object nativePlayer) throws Exception;

    CompletionStage<Void> disconnect(Object connection);

    Object api();

    default CompletionStage<Void> execute(Object sender, String command) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    default CompletionStage<java.util.List<String>> suggest(Object sender, String command) {
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
    }

    default java.util.Map<String, byte[]> configurationFiles() throws Exception {
        return java.util.Map.of();
    }

    default Object prepareReload(Object configuration) throws Exception {
        return configuration;
    }

    default Object currentConfiguration() {
        return null;
    }

    default boolean applyReload(Object configuration) throws Exception {
        return true;
    }

    default java.util.List<String> placeholders() {
        return java.util.List.of();
    }

    default String placeholder(UUID player, String parameter) {
        return null;
    }

    default int playerCount() {
        return 0;
    }
}
