package com.shobhit.Network_lab;

public class MetricsSubscriber implements EventSubscriber {

    private final ServerMetrics metrics;
    private final PrometheusMetricsServer prometheusServer;
    private final ChatRoom chatRoom;

    public MetricsSubscriber(ServerMetrics metrics, PrometheusMetricsServer prometheusServer, ChatRoom chatRoom) {
        this.metrics = metrics;
        this.prometheusServer = prometheusServer;
        this.chatRoom = chatRoom;
    }

    @Override
    public void onEvent(ChatEvent event) {
        switch (event.getMessage().getType()) {
            case JOIN -> {
                metrics.userJoined();
                prometheusServer.incrementJoins();
            }
            case LEAVE -> {
                metrics.userLeft();
                prometheusServer.incrementLeaves();
            }
            case CHAT -> {
                metrics.messageSent();
                prometheusServer.incrementMessages();
            }
            case TRANSFER_METRIC -> {
                prometheusServer.recordTransferPath(event.getMessage().getContent());
                chatRoom.meshTransferComplete(event.getMessage().getSender(), event.getMessage().getTarget(), event.getMessage().getContent());
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