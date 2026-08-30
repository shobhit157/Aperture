package com.shobhit.Network_lab;

import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;

import java.io.IOException;

public class PrometheusMetricsServer {

    // Gauge = can go up AND down (online users)
    private final Gauge connectedUsers = Gauge.builder()
            .name("chat_connected_users")
            .help("Number of users currently online")
            .register();

    // Counter = only goes up (total messages, joins, leaves)
    private final Counter totalMessages = Counter.builder()
            .name("chat_total_messages")
            .help("Total messages sent since server started")
            .register();

    private final Counter totalJoins = Counter.builder()
            .name("chat_total_joins")
            .help("Total joins since server started")
            .register();

    private final Counter totalLeaves = Counter.builder()
            .name("chat_total_leaves")
            .help("Total leaves since server started")
            .register();

    private final HTTPServer httpServer;

    public PrometheusMetricsServer(int port) throws IOException {
        httpServer = HTTPServer.builder()
                .port(port)
                .buildAndStart();
        System.out.println("[Prometheus] Metrics available at http://localhost:" + port + "/metrics");
    }

    // Called by MetricsSubscriber
    public void setConnectedUsers(int value) { connectedUsers.set(value); }
    public void incrementMessages()          { totalMessages.inc(); }
    public void incrementJoins()             { totalJoins.inc(); }
    public void incrementLeaves()            { totalLeaves.inc(); }

    public void stop() { httpServer.close(); }
}