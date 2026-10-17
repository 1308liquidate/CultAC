package ac.cult.reload.host;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Posts each update's result line as one Discord-style webhook message ({@code content}, no mentions); a failed post
 * is reported, never retried.
 */
public final class UpdateWebhook {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClients.create(Duration.ofSeconds(5));
    private final Duration timeout;

    public UpdateWebhook() {
        this(TIMEOUT);
    }

    UpdateWebhook(Duration timeout) {
        this.timeout = timeout;
    }

    /** Posts {@code line} to {@code url}, completing once it was answered; does nothing when {@code url} is empty. */
    public CompletionStage<Void> post(String url, String line, Consumer<String> report) {
        if (url.isEmpty()) return CompletableFuture.completedFuture(null);
        HttpRequest request;
        try {
            var message = new JsonObject();
            message.addProperty("content", line);
            var mentions = new JsonObject();
            mentions.add("parse", new JsonArray());
            message.add("allowed_mentions", mentions);
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(message.toString()))
                    .build();
        } catch (IllegalArgumentException invalid) {
            report.accept("Update webhook URL is invalid");
            return CompletableFuture.completedFuture(null);
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((response, failure) -> {
                    if (failure != null) report.accept("Update webhook post failed: " + failure);
                    else if (response.statusCode() / 100 != 2)
                        report.accept("Update webhook answered HTTP " + response.statusCode());
                    return null;
                });
    }
}
