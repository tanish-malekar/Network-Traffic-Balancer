package com.tanishmalekar.loadbalancer.serverpool;

import com.tanishmalekar.loadbalancer.backend.Backend;
import java.util.ArrayList;
import java.util.List;

public final class RoundRobinServerPool implements ServerPool {
    private final List<Backend> backends = new ArrayList<>();
    private int current;
    public synchronized void addBackend(Backend backend) { backends.add(backend); }
    public synchronized List<Backend> getBackends() { return List.copyOf(backends); }
    public synchronized Backend getNextValidPeer() {
        for (int i = 0; i < backends.size(); i++) {
            current = (current + 1) % backends.size();
            Backend peer = backends.get(current);
            if (peer.isAlive()) return peer;
        }
        return null;
    }
}
