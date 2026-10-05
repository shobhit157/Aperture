package com.shobhit.Network_lab;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mesh v2 S3: everything the server does with transfer events.
 *
 *  - started:  when the receiver accepts (FILE_ACCEPT)
 *  - events:   TRANSFER_EVENT|id=..|state=..|path=..|pct=..|reason=.. from clients
 *  - peer left: a user who disconnects and does not come back within
 *               PEER_LEFT_GRACE_MS -> their active transfers fail
 *  - backup:    a transfer silent for NO_RESPONSE_MS -> failed
 *  - metrics:   counted only when THIS pod's call made the transfer
 *               done/failed (the Lua script decides), so exactly once
 *               no matter how many pods there are.
 */
public class TransferTracker {

    // A user disconnecting is often a short network blip: the client
    // reconnects and its peer-app keeps transferring. Wait before deciding.
    private static final long PEER_LEFT_GRACE_MS = 30_000;
    // Backup only: peers normally report every outcome themselves.
    private static final long NO_RESPONSE_MS = 120_000;
    private static final long SWEEP_EVERY_MS = 15_000;

    private static final int MAX_ID_LENGTH = 64;

    private final TransferStore store;
    private final RedisClientRegistry registry;
    private final PrometheusMetricsServer prometheus; // may be null
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "transfer-tracker");
                t.setDaemon(true);
                return t;
            });

    public TransferTracker(TransferStore store, RedisClientRegistry registry,
                           PrometheusMetricsServer prometheus) {
        this.store = store;
        this.registry = registry;
        this.prometheus = prometheus;
    }

    public void startSweeper() {
        scheduler.scheduleAtFixedRate(this::sweepSilent,
                SWEEP_EVERY_MS, SWEEP_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    /** The receiver accepted: from = sender, to = receiver. */
    public void started(String id, String from, String to) {
        if (!validId(id)) return;
        try {
            store.start(id, from, to);
        } catch (Exception e) {
            System.out.println("[TRANSFER] start failed id=" + id + ": " + e.getMessage());
        }
    }

    /**
     * One TRANSFER_EVENT line from a client.
     * Format: TRANSFER_EVENT|id=<id>|state=<path|progress|done|failed>|path=..|pct=..|reason=..
     * Fields are key=value, in any order; unknown fields are ignored.
     */
    public void onClientEvent(String user, String line) {
        Map<String, String> f = parseFields(line);
        String id = f.get("id");
        String state = f.get("state");
        if (!validId(id) || state == null) return;
        apply(id, state, f.get("path"), f.get("pct"), f.get("reason"), user);
    }

    /** A user's session ended (they were the owner, i.e. really left). */
    public void userLeft(String user) {
        scheduler.schedule(() -> {
            try {
                if (registry.getInstanceId(user) != null) {
                    return; // back online — transfers may still be fine
                }
                for (String id : store.activeIds()) {
                    Map<String, String> t = store.get(id);
                    if (user.equals(t.get("from")) || user.equals(t.get("to"))) {
                        apply(id, "failed", null, null, "peer left (" + user + ")", "");
                    }
                }
            } catch (Exception e) {
                System.out.println("[TRANSFER] peer-left check failed for " + user + ": " + e.getMessage());
            }
        }, PEER_LEFT_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    // Runs on every pod; the Lua script makes sure only one pod's call
    // actually marks a transfer failed (and counts it).
    private void sweepSilent() {
        try {
            for (String id : store.silentIds(NO_RESPONSE_MS)) {
                apply(id, "failed", null, null, "no response", "");
            }
        } catch (Exception e) {
            System.out.println("[TRANSFER] sweep failed: " + e.getMessage());
        }
    }

    private void apply(String id, String state, String path, String pct, String reason, String user) {
        try {
            TransferStore.Result r = store.apply(id, state, path, pct, reason, user);
            if (r == TransferStore.Result.DONE) {
                if (prometheus != null) prometheus.recordTransferPath(path);
                System.out.println("[TRANSFER] done id=" + id + " path=" + path);
            } else if (r == TransferStore.Result.FAILED) {
                if (prometheus != null) prometheus.recordTransferFailed();
                System.out.println("[TRANSFER] failed id=" + id + " reason=" + reason);
            }
        } catch (Exception e) {
            System.out.println("[TRANSFER] apply failed id=" + id + " state=" + state + ": " + e.getMessage());
        }
    }

    // "TRANSFER_EVENT|id=t1|state=path|path=relay" -> {id=t1, state=path, path=relay}
    static Map<String, String> parseFields(String line) {
        Map<String, String> fields = new HashMap<>();
        String[] parts = line.split("\\|");
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                fields.put(parts[i].substring(0, eq).trim(), parts[i].substring(eq + 1).trim());
            }
        }
        return fields;
    }

    static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) return false;
        }
        return true;
    }

    public void stop() {
        scheduler.shutdownNow();
        store.close();
    }

    // For S4 (mesh): read access to the shared state.
    public TransferStore store() {
        return store;
    }
}