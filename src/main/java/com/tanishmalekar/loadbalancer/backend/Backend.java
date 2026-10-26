package com.tanishmalekar.loadbalancer.backend;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Backend {
    private final URI url;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicInteger connections = new AtomicInteger();

    public Backend(URI url) { this.url = url; }
    public URI getURL() { return url; }
    public boolean isAlive() { return alive.get(); }
    public void setAlive(boolean value) { alive.set(value); }
    public int getActiveConnections() { return connections.get(); }
    public void beginRequest() { connections.incrementAndGet(); }
    public void endRequest() { connections.decrementAndGet(); }
}
