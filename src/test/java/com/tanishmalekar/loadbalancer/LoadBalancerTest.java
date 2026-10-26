package com.tanishmalekar.loadbalancer;

import com.sun.net.httpserver.HttpServer;
import com.tanishmalekar.loadbalancer.backend.Backend;
import com.tanishmalekar.loadbalancer.frontend.LoadBalancer;
import com.tanishmalekar.loadbalancer.serverpool.*;
import com.tanishmalekar.loadbalancer.utils.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LoadBalancerTest {
    @TempDir Path directory;
    Backend backend(int port) { return new Backend(URI.create("http://localhost:" + port)); }

    @Test void backendStateAndConnections() {
        Backend b = backend(1234);
        assertTrue(b.isAlive()); b.setAlive(false); assertFalse(b.isAlive());
        b.beginRequest(); assertEquals(1, b.getActiveConnections());
        b.endRequest(); assertEquals(0, b.getActiveConnections());
    }
    @Test void roundRobinMatchesOriginalOrderAndSkipsDeadPeers() {
        var pool = new RoundRobinServerPool();
        assertNull(pool.getNextValidPeer());
        var a = backend(1234); var b = backend(1235); var c = backend(1236);
        pool.addBackend(a); pool.addBackend(b); pool.addBackend(c);
        assertSame(b, pool.getNextValidPeer()); assertSame(c, pool.getNextValidPeer());
        assertSame(a, pool.getNextValidPeer());
        b.setAlive(false); assertSame(c, pool.getNextValidPeer());
        a.setAlive(false); c.setAlive(false); assertNull(pool.getNextValidPeer());
    }
    @Test void concurrentRoundRobinDoesNotLoseRotations() throws Exception {
        var pool = new RoundRobinServerPool();
        pool.addBackend(backend(1234)); pool.addBackend(backend(1235));
        var counts = new ConcurrentHashMap<Backend, Integer>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1000; i++) executor.submit(() -> counts.merge(pool.getNextValidPeer(), 1, Integer::sum));
        }
        assertEquals(2, counts.size()); assertTrue(counts.values().stream().allMatch(n -> n == 500));
    }
    @Test void leastConnectionsSelectsIdleLiveBackend() {
        var pool = new LeastConnectionServerPool(); var a = backend(1234); var b = backend(1235);
        assertNull(pool.getNextValidPeer()); pool.addBackend(a); pool.addBackend(b);
        assertSame(a, pool.getNextValidPeer()); a.beginRequest(); assertSame(b, pool.getNextValidPeer());
        b.setAlive(false); assertSame(a, pool.getNextValidPeer());
        a.setAlive(false); assertNull(pool.getNextValidPeer());
    }
    @Test void yamlConfigDefaultsAliasesAndValidation() throws Exception {
        Path file = directory.resolve("config.yaml");
        Files.writeString(file, "lb_port: 3332\nbackends:\n  - http://localhost:3333\n");
        var config = Config.load(file); assertEquals(3332, config.port()); assertEquals(1, config.retryLimit());
        assertInstanceOf(RoundRobinServerPool.class, ServerPool.create(config.strategy()));
        Files.writeString(file, "lb_port: 3332\nmax_attempt_limit: 3\nstrategy: least-connection\nbackends: [http://localhost:3333]\n");
        assertEquals(3, Config.load(file).retryLimit());
        assertInstanceOf(LeastConnectionServerPool.class, ServerPool.create(Config.load(file).strategy()));
        Files.writeString(file, "lb_port: 0\nbackends: []\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(file));
    }
    HttpServer start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler); server.start(); return server;
    }
    @Test void forwardsMethodBodyRawPathQueryHeadersStatusAndCookies() throws Exception {
        var upstream = start(exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("/base/a%2Fb?q=one%20two", exchange.getRequestURI().toString());
            assertEquals("request-value", exchange.getRequestHeaders().getFirst("X-Test"));
            assertEquals("payload", new String(exchange.getRequestBody().readAllBytes()));
            assertNotNull(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            exchange.getResponseHeaders().add("Set-Cookie", "a=1");
            exchange.getResponseHeaders().add("Set-Cookie", "b=2");
            exchange.getResponseHeaders().add("X-Upstream", "yes");
            exchange.sendResponseHeaders(201, 5); exchange.getResponseBody().write("hello".getBytes()); exchange.close();
        });
        var pool = new RoundRobinServerPool();
        var b = new Backend(URI.create("http://localhost:" + upstream.getAddress().getPort() + "/base")); pool.addBackend(b);
        var proxy = start(new LoadBalancer(pool, 1));
        try {
            var result = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + proxy.getAddress().getPort() + "/a%2Fb?q=one%20two"))
                .header("X-Test", "request-value").POST(HttpRequest.BodyPublishers.ofString("payload")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, result.statusCode()); assertEquals("hello", result.body());
            assertEquals(2, result.headers().allValues("set-cookie").size());
            assertEquals("yes", result.headers().firstValue("x-upstream").orElseThrow());
            assertEquals(0, b.getActiveConnections());
        } finally { proxy.stop(0); upstream.stop(0); }
    }
    @Test void failedBackendRetriesThenHealthCheckRestoresIt() throws Exception {
        var upstream = start(exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
        int deadPort;
        try (var socket = new ServerSocket(0)) { deadPort = socket.getLocalPort(); }
        var good = backend(upstream.getAddress().getPort()); var dead = backend(deadPort);
        var pool = new RoundRobinServerPool(); pool.addBackend(good); pool.addBackend(dead);
        var proxy = start(new LoadBalancer(pool, 1));
        try {
            var client = HttpClient.newHttpClient(); var request = HttpRequest.newBuilder(URI.create("http://localhost:" + proxy.getAddress().getPort() + "/")).build();
            assertEquals(204, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertFalse(dead.isAlive()); assertEquals(0, dead.getActiveConnections());
            good.setAlive(false); assertEquals(503, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            HealthCheck.check(pool); assertTrue(good.isAlive()); assertFalse(dead.isAlive());
            assertEquals(204, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { proxy.stop(0); upstream.stop(0); }
    }
    @Test void zeroRetriesStopsAfterFailedFirstPeer() throws Exception {
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var pool = new RoundRobinServerPool(); pool.addBackend(backend(port));
        var proxy = start(new LoadBalancer(pool, 0));
        try {
            var result = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + proxy.getAddress().getPort() + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, result.statusCode()); assertEquals("Service not available\n", result.body());
        } finally { proxy.stop(0); }
    }
}
