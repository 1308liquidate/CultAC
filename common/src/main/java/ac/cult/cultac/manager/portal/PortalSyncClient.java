package ac.cult.cultac.manager.portal;

import ac.cult.cultac.cloud.CloudHttp;
import ac.cult.cultac.cloud.transport.CloudConnection.CappedBody;
import ac.cult.cultac.manager.portal.PortalSync.Reply;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The cloud's team sync API at {@code cloud.url}, called with {@code cloud.api-key} on the cloud's shared HTTP client.
 * The key goes only into the {@code Authorization} header.
 */
final class PortalSyncClient implements PortalSync.Transport {
    static final String PATH = "/api/v1/team/sync";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** The most bytes of an answer read; past it the call fails. */
    static final int MAX_BODY = 256 << 10;

    private final URI uri;
    private final String apiKey;

    /** {@code base} is the cloud settings' {@code url}, which has no trailing slash. */
    PortalSyncClient(String base, String apiKey) {
        this.uri = URI.create(base + PATH);
        this.apiKey = apiKey;
    }

    URI uri() {
        return uri;
    }

    @Override
    public CompletableFuture<Reply> post(JsonObject body) {
        return send(request().POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    @Override
    public CompletableFuture<Reply> get() {
        return send(request().GET());
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    private static CompletableFuture<Reply> send(HttpRequest.Builder request) {
        return CompletableFuture.supplyAsync(
                        () -> CloudHttp.HTTP.sendAsync(request.build(), info -> new CappedBody(MAX_BODY)),
                        CloudHttp.CALLS)
                .thenCompose(Function.identity())
                .thenApply(response -> new Reply(response.statusCode(), response.body()));
    }
}
