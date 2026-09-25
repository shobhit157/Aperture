package com.shobhit.Network_lab;

import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;

import java.io.IOException;

public class PrometheusMetricsServer {

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

    private final Counter fileTransfersDirect = Counter.builder()
            .name("chat_file_transfers_direct_total")
            .help("Total file transfers completed via direct P2P connection")
            .register();

    private final Counter fileTransfersRelay = Counter.builder()
            .name("chat_file_transfers_relay_total")
            .help("Total file transfers completed via relay")
            .register();

    private final HTTPServer httpServer;

    public PrometheusMetricsServer(int port, RedisClientRegistry registry, ChatRoom chatRoom) throws IOException {

        GaugeWithCallback.builder()
                .name("chat_connected_users")
                .help("Number of users currently online globally (live from Redis)")
                .callback(callback -> callback.call(registry.getGlobalOnlineCount()))
                .register();

        GaugeWithCallback.builder()
                .name("chat_local_connected_users")
                .help("Number of users currently connected to this specific instance")
                .callback(callback -> callback.call(chatRoom.getLocalClientCount()))
                .register();

        httpServer = HTTPServer.builder()
                .port(port)
                .buildAndStart();
        System.out.println("[Prometheus] Metrics available at http://localhost:" + port + "/metrics");
    }

    public void incrementMessages() { totalMessages.inc(); }
    public void incrementJoins()    { totalJoins.inc(); }
    public void incrementLeaves()   { totalLeaves.inc(); }

    public void recordTransferPath(String path) {
        if ("direct".equals(path)) {
            fileTransfersDirect.inc();
        } else if ("relay".equals(path)) {
            fileTransfersRelay.inc();
        }
    }

    public void stop() { httpServer.close(); }
}