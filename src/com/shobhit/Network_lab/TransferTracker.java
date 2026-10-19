package com.shobhit.Network_lab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mesh v2 S3: everything the server does with transfer events.
 *
 *  - started:  when the receiver accepts (FILE_ACCEPT)
 *  - events:   TRANSFER_EVENT|id=..|state=..|path=..|pct=..|reason=..|secs=.. from clients
 *  - peer left: a user who disconnects and does not come back within
 *               PEER_LEFT_GRACE_MS:
 *               B3c: transfers where they are the RECEIVER become "waiting
 *               for receiver" until created_at + TTL (the same time a sender
 *               gets); their peer-app keeps the chunks and continues after a
 *               restart once their Java has checked MY_TRANSFERS.
 *               B3b: transfers where they are the SENDER are left alone:
 *               the receiver is the active side and always reports the
 *               outcome itself (done, waiting, expired, never got ready).
 *  - my transfers: B3c, on login the client gets its active transfers
 *               (MY_TRANSFER lines + MY_TRANSFERS_END), so after a restart
 *               it can compare them with what its peer-app restored.
 *  - retrying:  B3b: the receiver reports each of peer-app's quick tries
 *               (retry=n/3); the mesh shows "reconnecting n/3". Also keeps
 *               the record alive for the backup sweeper.
 *  - waiting:   B3b: the receiver reports "waiting" (sender gone, chunks
 *               kept) with the seconds it will wait; the mesh shows it.
 *  - backup:    a transfer silent for NO_RESPONSE_MS -> failed, except a
 *               waiting one (for sender or receiver) before its wait_until
 *               (+ WAIT_MARGIN_MS).
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
    

    // B3d: after a server start, wait before the first sweep. Clients need a
    // moment to reconnect and send what happened while the server was down;
    // without this every transfer would look "silent" and be failed at once.
    private static final long START_GRACE_MS = 3 * 60_000;
    
    private static final long SWEEP_EVERY_MS = 15_000;
    // B3b: a waiting transfer is failed by the backup only this long after
    // its wait_until (normally the receiver reports "expired" itself first).
    private static final long WAIT_MARGIN_MS = 120_000;
    // B3b: used if a client says "waiting" without secs. Same variable and
    // default as peer-app's transfer lifetime.
    // B3c: also how long a receiver who left is waited for (from created_at).
    private static final long TRANSFER_TTL_SECS = envLong("PEER_TRANSFER_TTL_SECS", 1800);
    private static final long MAX_WAIT_SECS = 86_400;

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
        // B3d: first sweep after START_GRACE_MS, then every SWEEP_EVERY_MS.
        scheduler.scheduleAtFixedRate(this::sweepSilent,
                START_GRACE_MS, SWEEP_EVERY_MS, TimeUnit.MILLISECONDS);
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
     * Format: TRANSFER_EVENT|id=<id>|state=<path|progress|retrying|waiting|done|failed>|path=..|pct=..|reason=..|secs=..|retry=..
     * Fields are key=value, in any order; unknown fields are ignored.
     * secs:  B3b, only for waiting — how long the receiver will keep waiting.
     * retry: B3b, only for retrying — which quick try, e.g. "2/3".
     */
    public void onClientEvent(String user, String line) {
        Map<String, String> f = parseFields(line);
        String id = f.get("id");
        String state = f.get("state");
        if (!validId(id) || state == null) return;
        long secs = 0;
        String reason = f.get("reason");
        if (state.equals("waiting")) {
            secs = parseSecs(f.get("secs"));
            if (secs <= 0) secs = TRANSFER_TTL_SECS;
        } else if (state.equals("retrying")) {
            reason = f.get("retry"); // stored as the "retry" field, e.g. "2/3"
        }
        apply(id, state, f.get("path"), f.get("pct"), reason, user, secs);
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
                    if (user.equals(t.get("to"))) {
                        // B3c: the receiver is gone, but its peer-app keeps
                        // the chunks. Wait for it like for a sender, until
                        // created_at + TTL; the sweeper fails it after that.
                        apply(id, "receiver_left", null, null, "receiver offline (" + user + ")", "",
                                TRANSFER_TTL_SECS);
                    } else if (user.equals(t.get("from"))) {
                        // B3b: the sender is gone. The receiver waits for it
                        // and reports the outcome itself — don't fail here.
                        System.out.println("[TRANSFER] sender " + user + " left, receiver decides id=" + id
                                + " state=" + t.get("state"));
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
                TransferStore.Result r = store.failIfSilent(id, NO_RESPONSE_MS, WAIT_MARGIN_MS,
                        "no response", "sender did not come back", "receiver did not come back");
                if (r == TransferStore.Result.FAILED) {
                    if (prometheus != null) prometheus.recordTransferFailed();
                    System.out.println("[TRANSFER] failed id=" + id + " (sweeper)");
                }
            }
        } catch (Exception e) {
            System.out.println("[TRANSFER] sweep failed: " + e.getMessage());
        }
    }

    private void apply(String id, String state, String path, String pct, String reason,
                       String user, long waitSecs) {
        try {
            TransferStore.Result r = store.apply(id, state, path, pct, reason, user, waitSecs);
            if (r == TransferStore.Result.DONE) {
                if (prometheus != null) prometheus.recordTransferPath(path);
                System.out.println("[TRANSFER] done id=" + id + " path=" + path);
            } else if (r == TransferStore.Result.FAILED) {
                if (prometheus != null) prometheus.recordTransferFailed();
                System.out.println("[TRANSFER] failed id=" + id + " reason=" + reason);
            } else if (r == TransferStore.Result.CHANGED && state.equals("waiting")) {
                System.out.println("[TRANSFER] waiting id=" + id + " for " + waitSecs + "s"
                        + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
            } else if (r == TransferStore.Result.CHANGED && state.equals("receiver_left")) {
                System.out.println("[TRANSFER] waiting for receiver id=" + id
                        + " (until created + " + waitSecs + "s) " + reason);
            }
        } catch (Exception e) {
            System.out.println("[TRANSFER] apply failed id=" + id + " state=" + state + ": " + e.getMessage());
        }
    }

    /**
     * B3c: the lines a client gets on login: one MY_TRANSFER line per active
     * transfer where it is sender or receiver, then MY_TRANSFERS_END with the
     * count (sent even when it is 0, so the client knows the list is
     * complete). On a Redis error: no lines at all; the client keeps what it
     * has and the TTL is the safety net.
     *
     *   MY_TRANSFER|<id>|<state>|<from>|<to>|<pct>
     *   MY_TRANSFERS_END|<count>
     */
    public List<String> myTransferLines(String user) {
        List<String> lines = new ArrayList<>();
        try {
            for (Map<String, String> t : store.activeFor(user)) {
                lines.add(myTransferLine(t));
            }
        } catch (Exception e) {
            System.out.println("[TRANSFER] my transfers failed for " + user + ": " + e.getMessage());
            return List.of();
        }
        lines.add("MY_TRANSFERS_END|" + lines.size());
        return lines;
    }

    static String myTransferLine(Map<String, String> t) {
        return "MY_TRANSFER|" + nz(t.get("id")) + "|" + nz(t.get("state")) + "|" + nz(t.get("from"))
                + "|" + nz(t.get("to")) + "|" + (t.get("pct") == null || t.get("pct").isEmpty() ? "0" : t.get("pct"));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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

    // B3b: secs from a client — a number, 0..MAX_WAIT_SECS, else 0.
    static long parseSecs(String s) {
        if (s == null) return 0;
        try {
            long v = Long.parseLong(s.trim());
            return (v < 0) ? 0 : Math.min(v, MAX_WAIT_SECS);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long envLong(String name, long fallback) {
        try {
            long v = Long.parseLong(System.getenv().getOrDefault(name, String.valueOf(fallback)).trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
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