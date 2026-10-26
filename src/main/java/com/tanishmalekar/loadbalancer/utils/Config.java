package com.tanishmalekar.loadbalancer.utils;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.List;
import java.util.Map;

public record Config(int port, int retryLimit, List<URI> backends, String strategy) {
    public static Config load(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(document instanceof Map<?, ?> values)) throw new IllegalArgumentException("configuration mapping expected");
            Object rawPort = values.get("lb_port");
            if (!(rawPort instanceof Number number) || number.intValue() < 1 || number.intValue() > 65535)
                throw new IllegalArgumentException("valid load balancer port expected");
            if (!(values.get("backends") instanceof List<?> hosts) || hosts.isEmpty())
                throw new IllegalArgumentException("backend hosts expected, none provided");
            List<URI> backends = hosts.stream().map(host -> {
                URI uri = URI.create(host.toString());
                if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getUserInfo() != null)
                    throw new IllegalArgumentException("invalid backend URL: " + host);
                return uri;
            }).toList();
            Object retry = values.containsKey("retry_limit") ? values.get("retry_limit") : values.get("max_attempt_limit");
            int retryLimit = retry == null ? 1 : Integer.parseInt(retry.toString());
            if (retryLimit < 0) throw new IllegalArgumentException("retry_limit cannot be negative");
            Object strategy = values.get("strategy");
            return new Config(number.intValue(), retryLimit, backends, strategy == null ? "round-robin" : strategy.toString());
        }
    }
}
