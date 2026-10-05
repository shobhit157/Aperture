package com.shobhit.Network_lab;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mesh v2 S4: the mesh is a live window onto Redis.
 *
 * This pod keeps NO transfer state of its own. All pods write transfer
 * state to Redis (TransferStore); any pod can serve the mesh page and they
 * all show the same thing (see docs/decisions/003-redis-transfer-state.md).
 *
 *  - Browser connects      -> "snapshot" of all active transfers
 *  - Redis ping (changed)  -> "update" for that transfer, batched every 250 ms
 *  - Every 10 s            -> fresh "snapshot" (safety net for a lost ping)
 *
 * JSON sent to mesh.html:
 *   {"type":"snapshot","transfers":[ {...}, ... ]}
 *   {"type":"update","transfer":{...}}
 * where {...} = {"id","from","to","state","path","pct","reason","age_ms"}
 * state: started | moving | done | failed. age_ms = time since the last
 * event, so the page can show "stalled" without the server guessing.
 */
public class MeshEventServer extends WebSocketServer {

    private static final long FLUSH_EVERY_MS = 250;
    private static final long RESYNC_EVERY_MS = 10_000;
    private static final long RESUBSCRIBE_DELAY_MS = 2_000;

    private final TransferStore store;
    private final String redisHost;
    private final int redisPort;

    private final Set<WebSocket> connections = new CopyOnWriteArraySet<>();
    // Transfer ids that changed since the last flush.
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mesh-flush");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean running = true;
    private volatile JedisPubSub subscription;

    public MeshEventServer(int port, TransferStore store, String redisHost, int redisPort) {
        super(new InetSocketAddress(port));
        this.store = store;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
    }

    // ---------------- WebSocket ----------------

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        connections.add(conn);
        try {
            conn.send(buildSnapshotJson());
        } catch (Exception e) {
            System.out.println("[MeshEventServer] snapshot failed: " + e.getMessage());
        }
        System.out.println("[MeshEventServer] Dashboard client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // The page only listens.
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[MeshEventServer] Error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[MeshEventServer] Listening for dashboard connections on port " + getPort());
        scheduler.scheduleAtFixedRate(this::flushDirty, FLUSH_EVERY_MS, FLUSH_EVERY_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::resync, RESYNC_EVERY_MS, RESYNC_EVERY_MS, TimeUnit.MILLISECONDS);

        Thread sub = new Thread(this::subscribeLoop, "mesh-subscriber");
        sub.setDaemon(true);
        sub.start();
    }

    // ---------------- Redis pings ----------------

    // Blocking subscribe on its own connection; reconnects if Redis drops.
    private void subscribeLoop() {
        while (running) {
            try (Jedis jedis = new Jedis(redisHost, redisPort)) {
                JedisPubSub pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String id) {
                        dirty.add(id);
                    }
                };
                subscription = pubSub;
                System.out.println("[MeshEventServer] Subscribed to " + TransferStore.CHANGED_CHANNEL);
                jedis.subscribe(pubSub, TransferStore.CHANGED_CHANNEL); // blocks
            } catch (Exception e) {
                if (running) {
                    System.out.println("[MeshEventServer] Redis subscription lost: " + e.getMessage()
                            + " — retrying in " + RESUBSCRIBE_DELAY_MS + " ms");
                }
            }
            sleep(RESUBSCRIBE_DELAY_MS);
        }
    }

    // ---------------- sending ----------------

    // Every FLUSH_EVERY_MS: send one update per changed transfer. Several
    // pings for the same transfer in between become one update (batching).
    private void flushDirty() {
        try {
            if (dirty.isEmpty()) return;
            List<String> ids = new ArrayList<>(dirty);
            dirty.removeAll(ids);
            if (connections.isEmpty()) return; // nobody watching on this pod

            long now = System.currentTimeMillis();
            for (String id : ids) {
                Map<String, String> t = store.get(id);
                if (t == null || t.isEmpty()) continue; // already expired
                send("{\"type\":\"update\",\"transfer\":" + transferJson(t, now) + "}");
            }
        } catch (Exception e) {
            System.out.println("[MeshEventServer] flush failed: " + e.getMessage());
        }
    }

    // Every RESYNC_EVERY_MS: full snapshot, so a lost ping can never leave
    // the page wrong for long.
    private void resync() {
        try {
            if (connections.isEmpty()) return;
            send(buildSnapshotJson());
        } catch (Exception e) {
            System.out.println("[MeshEventServer] resync failed: " + e.getMessage());
        }
    }

    private String buildSnapshotJson() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("{\"type\":\"snapshot\",\"transfers\":[");
        boolean first = true;
        for (String id : store.activeIds()) {
            Map<String, String> t = store.get(id);
            if (t == null || t.isEmpty()) continue;
            if (!first) sb.append(',');
            sb.append(transferJson(t, now));
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String transferJson(Map<String, String> t, long now) {
        long updated = parseLong(t.get("updated_at"));
        long age = updated > 0 ? Math.max(0, now - updated) : 0;
        return "{"
                + "\"id\":" + str(t.get("id"))
                + ",\"from\":" + str(t.get("from"))
                + ",\"to\":" + str(t.get("to"))
                + ",\"state\":" + str(t.get("state"))
                + ",\"path\":" + str(t.get("path"))
                + ",\"pct\":" + parseLong(t.get("pct"))
                + ",\"reason\":" + str(t.get("reason"))
                + ",\"age_ms\":" + age
                + "}";
    }

    private void send(String json) {
        for (WebSocket conn : connections) {
            try {
                conn.send(json);
            } catch (Exception ignored) {
                // closed in between — onClose removes it
            }
        }
    }

    // ---------------- shutdown ----------------

    @Override
    public void stop() throws InterruptedException {
        running = false;
        JedisPubSub s = subscription;
        if (s != null) {
            try {
                s.unsubscribe();
            } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
        super.stop();
    }

    // ---------------- helpers ----------------

    // JSON string with escaping (names and reasons come from users).
    private static String str(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isEmpty() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}