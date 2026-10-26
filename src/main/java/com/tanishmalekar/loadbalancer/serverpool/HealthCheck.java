package com.tanishmalekar.loadbalancer.serverpool;

import com.tanishmalekar.loadbalancer.backend.BackendAlive;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static com.tanishmalekar.loadbalancer.utils.Logger.LOG;

public final class HealthCheck implements AutoCloseable {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    public HealthCheck(ServerPool pool) {
        scheduler.scheduleWithFixedDelay(() -> check(pool), 20, 20, TimeUnit.SECONDS);
    }
    public static void check(ServerPool pool) {
        for (var backend : pool.getBackends()) {
            if (Thread.currentThread().isInterrupted()) return;
            backend.setAlive(BackendAlive.isBackendAlive(backend));
            LOG.log(System.Logger.Level.DEBUG, backend.getURL() + " " + (backend.isAlive() ? "up" : "down"));
        }
    }
    public void close() { scheduler.shutdownNow(); }
}
