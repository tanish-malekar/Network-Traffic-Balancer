package com.tanishmalekar.loadbalancer.backend;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;

public final class BackendAlive {
    private BackendAlive() { }
    public static boolean isBackendAlive(Backend backend) {
        var url = backend.getURL();
        int port = url.getPort() < 0 ? ("https".equals(url.getScheme()) ? 443 : 80) : url.getPort();
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(url.getHost(), port), 10_000);
            return true;
        } catch (IOException e) { return false; }
    }
}
