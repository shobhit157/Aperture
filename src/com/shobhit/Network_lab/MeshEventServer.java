package com.shobhit.Network_lab;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MeshEventServer extends WebSocketServer {

    // How long a transfer can sit "pending" with no completion report before
    // we give up on it and mark it failed. This is a fixed guess, not
    // progress-aware — a genuinely slow but healthy large transfer could
    // theoretically get marked failed if it takes longer than this. A
    // progress-aware version (resetting the timer on live progress updates)
    // is the more accurate follow-up fix.
    private static final long PENDING_TIMEOUT_SECONDS = 60;

    private final Set<WebSocket> connections = new CopyOnWriteArraySet<>();
    private final Map<String, TransferInfo> activeTransfers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MeshEventServer(int port) {
        super(new InetSocketAddress(port));
    }

    private static class TransferInfo {
        String from;
        String to;
        String path;

        TransferInfo(String from, String to, String path) {
            this.from = from;
            this.to = to;
            this.path = path;
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        connections.add(conn);
        conn.send(buildSnapshotJson());
        System.out.println("[MeshEventServer] Dashboard client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[MeshEventServer] Error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[MeshEventServer] Listening for dashboard connections on port " + getPort());
    }

    public void applyRemoteStart(String from, String to) {
        String id = from + "->" + to + "->" + System.nanoTime();
        activeTransfers.put(id, new TransferInfo(from, to, "pending"));
        broadcastSnapshot();

        // Safety net: if this transfer never reports completion (disconnect,
        // crash, or any other failure), mark it failed and clear it — so the
        // mesh never gets permanently stuck on a transfer that's simply never
        // going to finish.
        scheduler.schedule(() -> {
            TransferInfo info = activeTransfers.get(id);
            if (info != null && info.path.equals("pending")) {
                info.path = "failed";
                System.out.println("[MeshEventServer] Transfer timed out: " + info.from + " -> " + info.to);
                broadcastSnapshot();
                scheduler.schedule(() -> {
                    activeTransfers.remove(id);
                    broadcastSnapshot();
                }, 5, TimeUnit.SECONDS);
            }
        }, PENDING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public void applyRemoteComplete(String from, String to, String path) {
        for (Map.Entry<String, TransferInfo> entry : activeTransfers.entrySet()) {
            TransferInfo info = entry.getValue();
            boolean toMatches = to.isEmpty() || info.to.equals(to);
            if (info.from.equals(from) && toMatches && info.path.equals("pending")) {
                info.path = path;
                broadcastSnapshot();
                String id = entry.getKey();
                scheduler.schedule(() -> {
                    activeTransfers.remove(id);
                    broadcastSnapshot();
                }, 4, TimeUnit.SECONDS);
                return;
            }
        }
    }

    private String buildSnapshotJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"snapshot\",\"transfers\":[");
        boolean first = true;
        for (TransferInfo info : activeTransfers.values()) {
            if (!first) sb.append(",");
            sb.append(String.format(
                    "{\"from\":\"%s\",\"to\":\"%s\",\"path\":\"%s\"}",
                    info.from, info.to, info.path
            ));
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private void broadcastSnapshot() {
        String json = buildSnapshotJson();
        for (WebSocket conn : connections) {
            conn.send(json);
        }
    }
}