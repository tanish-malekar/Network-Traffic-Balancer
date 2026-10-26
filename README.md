# Java Load Balancer
Project of a Load Balancer in Java

A load balancer is a device responsible for distributing network traffic among a certain number of servers. Its a fundamental piece to increase reliability and robustness of modern applications

The concept behind this project consists of a basic load balancer written in Java.

The input is given through a configuration file refered to as `config.yaml`,  example of a configuration file down below:

```yaml
## Load Balancer Port
lb_port: 3333
## Retry limit of a backend server 
retry_limit: 3
## Backend servers host
backends:
  - "http://localhost:100"
  - "http://localhost:101"
```

This load balancer supports two strategies:
- Round Robin: this algorithm equally distributes incoming traffic among the available servers
- Least Connections: traffic is distributed taking into account the number of active connections in each server

The strategy con be specified in the configuration file:

```yaml
lb_port: 3333
## LB strategy (uses round-robin as default)
strategy: least-connection
retry_limit: 3
backends:
  - "http://localhost:100"
  - "http://localhost:101"
```
## Running

Requires Java 21 and Maven 3.9 or later.

```sh
mvn clean verify
java -jar target/network-traffic-balancer-1.0.0.jar
```

The default configuration file is `config.yaml`. An alternative path can be supplied as the first argument.

Backend health is checked over TCP every 20 seconds. Failed backends are skipped until they recover. Requests are retried once by default; `retry_limit` sets the number of additional backend attempts (and `max_attempt_limit` is accepted as an alias). The original Go implementation reads a retry setting but always retries once; this version makes that setting effective.

This Java port preserves the original HTTP reverse-proxy design and configuration. It supports HTTP/1.1 forwarding; WebSocket upgrades, gRPC/HTTP2 proxying, and consistent hashing are outside the original implementation's feature set. Request bodies are buffered for retries, so size limits should be enforced by an upstream server when handling untrusted traffic. Retrying non-idempotent requests can repeat backend side effects.

## References

- [simplelb](https://github.com/kasvith/simplelb) repository project
- [Goconcurrency](https://gist.github.com/rushilgupta/228dfdf379121cb9426d5e90d34c5b96) gist

- [Original Network Load Balancer](https://github.com/tanish-malekar/Network-Load-Balancer), ported from Go to Java. Original MIT license and attribution retained.
