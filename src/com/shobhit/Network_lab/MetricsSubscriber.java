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
            case TRANSFER_METRIC -> {
                String content = event.getMessage().getContent();
                String sender = event.getMessage().getSender();
                String target = event.getMessage().getTarget();

                if (content.startsWith("failed|") || content.equals("failed")) {
                    String reason = content.contains("|")
                            ? content.substring(content.indexOf('|') + 1)
                            : "unknown";
                    chatRoom.meshTransferFailed(sender, target, reason);
                } else {
                    prometheusServer.recordTransferPath(content);
                    chatRoom.meshTransferComplete(sender, target, content);
                }
            }
            case TRANSFER_PROGRESS -> {
                String transferId = event.getMessage().getTarget();
                String pct = event.getMessage().getContent();
                chatRoom.meshTransferProgress(transferId, pct);
            }
            case CONNECTION_PATH -> {
                String content = event.getMessage().getContent();
                int sep = content.indexOf('|');
                String transferId = sep >= 0 ? content.substring(0, sep) : content;
                String path = sep >= 0 ? content.substring(sep + 1) : "unknown";
                chatRoom.meshConnectionPath(transferId, path);
            }
            default -> {
            }
        }

        // Only log the summary line for the lower-frequency event types.
        if (type != MessageType.TRANSFER_PROGRESS && type != MessageType.CONNECTION_PATH) {
            System.out.println("[METRICS] online=" + metrics.getConnectedUsers()
                    + " msgs=" + metrics.getTotalMessages()
                    + " joins=" + metrics.getTotalJoins()
                    + " leaves=" + metrics.getTotalLeaves());
        }
    }
}