package ac.cult.reload.host;

import java.net.http.HttpClient;
import java.time.Duration;

public final class HttpClients {
    private HttpClients() {}

    public static HttpClient create(Duration timeout) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(HttpClients.class.getClassLoader());
            return HttpClient.newBuilder().connectTimeout(timeout).build();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
