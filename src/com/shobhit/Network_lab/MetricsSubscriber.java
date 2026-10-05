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
        MessageType type = event.getMessage().getType();

        switch (type) {
            case JOIN -> {
                metrics.userJoined();
                prometheusServer.incrementJoins();
            }
            case LEAVE -> {
                metrics.userLeft();
                prometheusServer.incrementLeaves();
            }
            case SESSION_ENDED -> {
                // Step 2: a session that ended without owning its username
                // (superseded by a newer session before it noticed it was
                // dead). The live count needs correcting, but this was
                // never a real, visible departure from the chat's point of
                // view — no LEAVE was announced, and totalLeaves/
                // incrementLeaves stay untouched so they only ever count
                // genuine departures.
                metrics.sessionEnded();
            }
            case CHAT -> {
                metrics.messageSent();
                prometheusServer.incrementMessages();
            }
            default -> {
            }
        }

        System.out.println("[METRICS] online=" + metrics.getConnectedUsers()
                + " msgs=" + metrics.getTotalMessages()
                + " joins=" + metrics.getTotalJoins()
                + " leaves=" + metrics.getTotalLeaves());
    }
}