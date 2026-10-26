package com.tanishmalekar.loadbalancer;

import com.sun.net.httpserver.HttpServer;
import com.tanishmalekar.loadbalancer.backend.Backend;
import com.tanishmalekar.loadbalancer.frontend.LoadBalancer;
import com.tanishmalekar.loadbalancer.serverpool.HealthCheck;
import com.tanishmalekar.loadbalancer.serverpool.ServerPool;
import com.tanishmalekar.loadbalancer.utils.Config;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import static com.tanishmalekar.loadbalancer.utils.Logger.LOG;

public final class Main {
    public static void main(String[] args) throws Exception {
        Config config = Config.load(Path.of(args.length == 0 ? "config.yaml" : args[0]));
        ServerPool pool = ServerPool.create(config.strategy());
        config.backends().forEach(url -> pool.addBackend(new Backend(url)));
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", new LoadBalancer(pool, config.retryLimit()));
        var healthCheck = new HealthCheck(pool);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            healthCheck.close();
            server.stop(10);
            executor.close();
        }));
        server.start();
        LOG.log(System.Logger.Level.INFO, "Load Balancer started on port " + config.port());
    }
}
