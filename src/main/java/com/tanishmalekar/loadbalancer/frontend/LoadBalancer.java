package com.tanishmalekar.loadbalancer.frontend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.tanishmalekar.loadbalancer.serverpool.ServerPool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import static com.tanishmalekar.loadbalancer.utils.Logger.LOG;

public final class LoadBalancer implements HttpHandler {
    private static final Set<String> HOP_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect");
    private final ServerPool pool;
    private final int retryLimit;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();

    public LoadBalancer(ServerPool pool, int retryLimit) { this.pool = pool; this.retryLimit = retryLimit; }

    private static Set<String> blocked(java.util.List<String> connection) {
        var headers = new HashSet<>(HOP_HEADERS);
        if (connection != null) for (String value : connection) for (String token : value.split(","))
            headers.add(token.trim().toLowerCase(Locale.ROOT));
        return headers;
    }

    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            for (int attempt = 0; attempt <= retryLimit; attempt++) {
                var peer = pool.getNextValidPeer();
                if (peer == null) break;
                peer.beginRequest();
                try {
                    URI base = peer.getURL();
                    URI incoming = exchange.getRequestURI();
                    String basePath = base.getRawPath();
                    String path = (basePath == null || basePath.equals("/") ? "" : basePath.replaceAll("/$", "")) + incoming.getRawPath();
                    String query = base.getRawQuery();
                    if (incoming.getRawQuery() != null) query = query == null ? incoming.getRawQuery() : query + "&" + incoming.getRawQuery();
                    URI target = URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + (query == null ? "" : "?" + query));
                    var request = HttpRequest.newBuilder(target).method(exchange.getRequestMethod(),
                        body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                    var blocked = blocked(exchange.getRequestHeaders().get("Connection"));
                    exchange.getRequestHeaders().forEach((name, values) -> {
                        if (!blocked.contains(name.toLowerCase(Locale.ROOT))) values.forEach(value -> request.header(name, value));
                    });
                    request.header("X-Forwarded-For", exchange.getRemoteAddress().getAddress().getHostAddress());
                    HttpResponse<java.io.InputStream> response;
                    try {
                        response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                    } catch (IOException e) {
                        peer.setAlive(false);
                        LOG.log(System.Logger.Level.WARNING, "Error handling request to " + peer.getURL(), e);
                        continue;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    var responseBlocked = blocked(response.headers().allValues("connection"));
                    response.headers().map().forEach((name, values) -> {
                        if (!responseBlocked.contains(name.toLowerCase(Locale.ROOT))) exchange.getResponseHeaders().put(name, values);
                    });
                    boolean noBody = exchange.getRequestMethod().equals("HEAD") || response.statusCode() == 204 || response.statusCode() == 304;
                    try (var responseBody = response.body()) {
                        exchange.sendResponseHeaders(response.statusCode(), noBody ? -1 : 0);
                        if (!noBody) responseBody.transferTo(exchange.getResponseBody());
                    }
                    return;
                } finally { peer.endRequest(); }
            }
            byte[] unavailable = "Service not available\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, exchange.getRequestMethod().equals("HEAD") ? -1 : unavailable.length);
            if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(unavailable);
        }
    }
}
