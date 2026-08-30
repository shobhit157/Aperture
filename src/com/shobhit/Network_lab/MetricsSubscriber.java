package com.shobhit.Network_lab;

public class MetricsSubscriber implements EventSubscriber {

    private final ServerMetrics metrics;
    private final PrometheusMetricsServer prometheusServer;

    public MetricsSubscriber(ServerMetrics metrics, PrometheusMetricsServer prometheusServer) {
        this.metrics = metrics;
        this.prometheusServer = prometheusServer;
    }

    @Override
    public void onEvent(ChatEvent event) {

        switch (event.getMessage().getType()) {
            case JOIN -> {
                metrics.userJoined();
                prometheusServer.incrementJoins();
                prometheusServer.setConnectedUsers(metrics.getConnectedUsers());
            }
            case LEAVE -> {
                metrics.userLeft();
                prometheusServer.incrementLeaves();
                prometheusServer.setConnectedUsers(metrics.getConnectedUsers());
            }
            case CHAT -> {
                metrics.messageSent();
                prometheusServer.incrementMessages();
            }
        }

        System.out.println("[METRICS] online=" + metrics.getConnectedUsers()
                + " msgs=" + metrics.getTotalMessages()
                + " joins=" + metrics.getTotalJoins()
                + " leaves=" + metrics.getTotalLeaves());
    }
}