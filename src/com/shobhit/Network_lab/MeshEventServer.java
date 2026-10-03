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

    private static final long STALLED_AFTER_SECONDS = 20;
    private static final long FAILED_AFTER_SECONDS = 40;
    private static final long CHECK_INTERVAL_SECONDS = 5;

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
        volatile long lastSeenMillis;

        TransferInfo(String from, String to, String path) {
            this.from = from;
            this.to = to;
            this.path = path;
            this.lastSeenMillis = System.currentTimeMillis();
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
        scheduler.scheduleAtFixedRate(this::checkForStaleTransfers,
                CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void checkForStaleTransfers() {
        long now = System.currentTimeMillis();
        boolean changed = false;

        for (Map.Entry<String, TransferInfo> entry : activeTransfers.entrySet()) {
            TransferInfo info = entry.getValue();
            if (info.path.equals("failed")) continue;

            long silentFor = (now - info.lastSeenMillis) / 1000;

            if (silentFor >= FAILED_AFTER_SECONDS) {
                info.path = "failed";
                System.out.println("[MeshEventServer] Transfer failed (silent " + silentFor + "s): "
                        + info.from + " -> " + info.to);
                changed = true;
                String id = entry.getKey();
                scheduler.schedule(() -> {
                    activeTransfers.remove(id);
                    broadcastSnapshot();
                }, 5, TimeUnit.SECONDS);
            } else if (silentFor >= STALLED_AFTER_SECONDS && !info.path.equals("stalled")) {
                info.path = "stalled";
                changed = true;
            }
        }

        if (changed) {
            broadcastSnapshot();
        }
    }

    public void applyRemoteStart(String from, String to, String transferId) {
        if (transferId == null || transferId.isBlank()) {
            transferId = from + "->" + to + "->" + System.nanoTime();
        }
        activeTransfers.put(transferId, new TransferInfo(from, to, "pending"));
        broadcastSnapshot();
    }
    
 // Phase A1: a transfer is stored as (sender -> receiver), but its result
    // can now be reported by either side — the receiver reports success and
    // most failures as (receiver -> sender). Match in both directions.
    // Before this, only the SENDER's reports ever matched, so receiver-side
    // failures never reached the mesh.
    private static boolean samePair(TransferInfo info, String a, String b) {
        boolean forward = info.from.equals(a) && (b.isEmpty() || info.to.equals(b));
        boolean reverse = info.to.equals(a) && (b.isEmpty() || info.from.equals(b));
        return forward || reverse;
    }

    public void applyRemoteComplete(String from, String to, String path) {
        for (Map.Entry<String, TransferInfo> entry : activeTransfers.entrySet()) {
            TransferInfo info = entry.getValue();
            if (samePair(info, from, to)
                    && (info.path.equals("pending") || info.path.equals("stalled")
                        || info.path.equals("direct") || info.path.equals("relay"))) {
                info.path = path;
                info.lastSeenMillis = System.currentTimeMillis();
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

    public void applyRemoteProgress(String transferId, String pct) {
        TransferInfo info = activeTransfers.get(transferId);
        if (info == null) return;
        info.lastSeenMillis = System.currentTimeMillis();
        if (info.path.equals("stalled")) {
            info.path = "pending";
            broadcastSnapshot();
        }
    }

    public void applyRemoteFailed(String from, String to, String reason) {
        for (Map.Entry<String, TransferInfo> entry : activeTransfers.entrySet()) {
            TransferInfo info = entry.getValue();
            if (samePair(info, from, to) && !info.path.equals("failed")) {
                info.path = "failed";
                System.out.println("[MeshEventServer] Transfer failed (explicit): "
                        + info.from + " -> " + info.to + " (" + reason + ")");
                broadcastSnapshot();
                String id = entry.getKey();
                scheduler.schedule(() -> {
                    activeTransfers.remove(id);
                    broadcastSnapshot();
                }, 5, TimeUnit.SECONDS);
                return;
            }
        }
    }

    // Fix 4: a LIVE path update, matched by transfer ID directly. Fires at
    // connection start and again on any mid-transfer migration (e.g. direct
    // falling back to relay). Only updates the color while the transfer is
    // genuinely ongoing ("pending" or "stalled") — never overwrites a
    // transfer that has already reached a terminal state (failed, or
    // already completed via applyRemoteComplete), since this is purely a
    // visual indicator, not a completion signal. Also resets lastSeenMillis,
    // since a path confirmation is itself proof the transfer is alive —
    // same reasoning as applyRemoteProgress.
    public void applyConnectionPath(String transferId, String path) {
        TransferInfo info = activeTransfers.get(transferId);
        if (info == null) return;
        if (!info.path.equals("pending") && !info.path.equals("stalled")) return;

        info.path = path;
        info.lastSeenMillis = System.currentTimeMillis();
        broadcastSnapshot();
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