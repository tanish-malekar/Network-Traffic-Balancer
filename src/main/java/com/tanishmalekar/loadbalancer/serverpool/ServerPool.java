package com.tanishmalekar.loadbalancer.serverpool;

import com.tanishmalekar.loadbalancer.backend.Backend;
import java.util.List;

public interface ServerPool {
    void addBackend(Backend backend);
    List<Backend> getBackends();
    Backend getNextValidPeer();
    default int getServerPoolSize() { return getBackends().size(); }
    static ServerPool create(String strategy) {
        return "least-connection".equals(strategy) ? new LeastConnectionServerPool() : new RoundRobinServerPool();
    }
}
