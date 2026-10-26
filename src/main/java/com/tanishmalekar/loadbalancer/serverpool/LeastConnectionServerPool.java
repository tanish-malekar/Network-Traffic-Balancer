package com.tanishmalekar.loadbalancer.serverpool;

import com.tanishmalekar.loadbalancer.backend.Backend;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LeastConnectionServerPool implements ServerPool {
    private final List<Backend> backends = new ArrayList<>();
    public synchronized void addBackend(Backend backend) { backends.add(backend); }
    public synchronized List<Backend> getBackends() { return List.copyOf(backends); }
    public synchronized Backend getNextValidPeer() {
        return backends.stream().filter(Backend::isAlive)
            .min(Comparator.comparingInt(Backend::getActiveConnections)).orElse(null);
    }
}
