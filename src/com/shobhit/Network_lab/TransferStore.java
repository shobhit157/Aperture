package com.shobhit.Network_lab;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mesh v2 S3: transfer state in Redis — the single source of truth for all
 * server pods (see docs/decisions/003-redis-transfer-state.md).
 *
 *   transfer:<id>      hash: from, to, state, path, pct, reason, created_at, updated_at
 *                      B3b: + wait_until, wait_reason while state = waiting
 *                           + retry ("2/3") while state = retrying
 *                      B3c: + wait_for ("sender" | "receiver") while waiting
 *   transfers:active   sorted set: active ids, score = updated_at (ms)
 *   mesh:changed       pub/sub channel: "<id>" whenever a transfer changes
 *
 * States: started -> moving -> done | failed
 *         B3b: moving -> retrying (peer-app's quick tries) -> waiting
 *              (sender gone, receiver keeps its chunks) -> moving (resumed)
 *         B3c: receiver gone (after the 30 s grace) -> waiting for receiver
 *              until created_at + TTL -> moving when it is back, or failed
 *              (expired) by the sweeper
 *
 * Every change is ONE Lua script, so it is atomic even when several pods
 * write the same transfer at the same moment.
 */
public class TransferStore {

    public static final String ACTIVE_KEY = "transfers:active";
    public static final String CHANGED_CHANNEL = "mesh:changed";

    // A finished transfer's record stays this long (the mesh shows it ~10 s).
    private static final int FINISHED_TTL_SECONDS = 30;
    // Safety net: a record nobody touches for a day is removed anyway.
    private static final int RECORD_MAX_TTL_SECONDS = 86_400;

    /** Result of applying an event. */
    public enum Result {
        IGNORED,    // unknown id, not a participant, already final, or no change
        CHANGED,    // state updated (path / progress / retrying / waiting / started)
        DONE,       // this call made it done — count it (exactly once)
        FAILED      // this call made it failed — count it (exactly once)
    }

    // Current time in ms from Redis's own clock, so pod clocks never matter.
    private static final String NOW_MS =
            "local t = redis.call('TIME') " +
            "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) ";

    // KEYS[1]=transfer:<id>  KEYS[2]=transfers:active
    // ARGV: 1 id, 2 from, 3 to, 4 maxTtl, 5 channel
    // Returns 1 if created, 0 if it already existed.
    private static final String START_SCRIPT =
            NOW_MS +
            "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end " +
            "redis.call('HSET', KEYS[1], 'id', ARGV[1], 'from', ARGV[2], 'to', ARGV[3], " +
            "  'state', 'started', 'path', '', 'pct', '0', 'reason', '', " +
            "  'created_at', now, 'updated_at', now) " +
            "redis.call('EXPIRE', KEYS[1], ARGV[4]) " +
            "redis.call('ZADD', KEYS[2], now, ARGV[1]) " +
            "redis.call('PUBLISH', ARGV[5], ARGV[1]) " +
            "return 1";

    // KEYS[1]=transfer:<id>  KEYS[2]=transfers:active
    // ARGV: 1 id, 2 event (path|progress|retrying|waiting|receiver_left|done|failed),
    //       3 path, 4 pct, 5 reason (for retrying: "n/3"), 6 finishedTtl, 7 channel,
    //       8 user ('' = server itself), 9 waitSecs ('waiting': seconds from now;
    //       'receiver_left': the TTL, counted from created_at)
    // Returns 0 ignored, 1 changed, 2 became done, 3 became failed.
    private static final String APPLY_SCRIPT =
            NOW_MS +
            "local state = redis.call('HGET', KEYS[1], 'state') " +
            "if not state then return 0 end " +
            "if state == 'done' or state == 'failed' then return 0 end " +
            // Only the two people in a transfer may change it.
            "if ARGV[8] ~= '' then " +
            "  local from = redis.call('HGET', KEYS[1], 'from') " +
            "  local to = redis.call('HGET', KEYS[1], 'to') " +
            "  if ARGV[8] ~= from and ARGV[8] ~= to then return 0 end " +
            "end " +
            "local ev = ARGV[2] " +
            "local publish = true " +
            "if ev == 'path' then " +
            "  if ARGV[3] == '' then return 0 end " +
            // Both sides report the same path: second report only refreshes the time.
            "  if state == 'moving' and redis.call('HGET', KEYS[1], 'path') == ARGV[3] then " +
            "    publish = false " +
            "  end " +
            "  redis.call('HSET', KEYS[1], 'state', 'moving', 'path', ARGV[3], 'updated_at', now) " +
            // B3b: moving again after retrying/waiting (same transfer id, resumed).
            "  redis.call('HDEL', KEYS[1], 'wait_until', 'wait_reason', 'wait_for', 'retry') " +
            "elseif ev == 'progress' then " +
            "  redis.call('HSET', KEYS[1], 'state', 'moving', 'pct', ARGV[4], 'updated_at', now) " +
            "  redis.call('HDEL', KEYS[1], 'wait_until', 'wait_reason', 'wait_for', 'retry') " +
            "elseif ev == 'retrying' then " +
            // B3b: peer-app's quick tries (sender unreachable). Also keeps
            // the record alive for the sweeper. wait_until is kept if set.
            "  redis.call('HSET', KEYS[1], 'state', 'retrying', 'retry', ARGV[5], 'updated_at', now) " +
            "elseif ev == 'waiting' then " +
            // B3b: sender gone; receiver keeps its chunks until wait_until.
            "  local secs = tonumber(ARGV[9]) or 0 " +
            "  local wu = now + secs * 1000 " +
            "  redis.call('HSET', KEYS[1], 'state', 'waiting', 'wait_until', wu, " +
            "    'wait_reason', ARGV[5], 'wait_for', 'sender', 'updated_at', now) " +
            "  redis.call('HDEL', KEYS[1], 'retry') " +
            "elseif ev == 'receiver_left' then " +
            // B3c: only the server may send this (a client could fake it).
            // The receiver gets the same time as a sender: until
            // created_at + TTL (same clock as peer-app's own expiry).
            "  if ARGV[8] ~= '' then return 0 end " +
            "  local created = tonumber(redis.call('HGET', KEYS[1], 'created_at')) or now " +
            "  local wu = created + (tonumber(ARGV[9]) or 0) * 1000 " +
            "  if now >= wu then " +
            "    ev = 'failed' " +
            "    redis.call('HSET', KEYS[1], 'state', 'failed', 'reason', 'expired (receiver did not come back)', " +
            "      'updated_at', now) " +
            "    redis.call('HDEL', KEYS[1], 'wait_until', 'wait_reason', 'wait_for', 'retry') " +
            "  else " +
            "    redis.call('HSET', KEYS[1], 'state', 'waiting', 'wait_until', wu, " +
            "      'wait_reason', 'receiver offline', 'wait_for', 'receiver', 'updated_at', now) " +
            "    redis.call('HDEL', KEYS[1], 'retry') " +
            "  end " +
            "elseif ev == 'done' then " +
            "  redis.call('HSET', KEYS[1], 'state', 'done', 'pct', '100', 'updated_at', now) " +
            "  if ARGV[3] ~= '' then redis.call('HSET', KEYS[1], 'path', ARGV[3]) end " +
            "  redis.call('HDEL', KEYS[1], 'wait_until', 'wait_reason', 'wait_for', 'retry') " +
            "elseif ev == 'failed' then " +
            "  redis.call('HSET', KEYS[1], 'state', 'failed', 'reason', ARGV[5], 'updated_at', now) " +
            "  redis.call('HDEL', KEYS[1], 'wait_until', 'wait_reason', 'wait_for', 'retry') " +
            "else " +
            "  return 0 " +
            "end " +
            "if ev == 'done' or ev == 'failed' then " +
            "  redis.call('ZREM', KEYS[2], ARGV[1]) " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[6]) " +
            "else " +
            "  redis.call('ZADD', KEYS[2], now, ARGV[1]) " +
            "end " +
            "if publish then redis.call('PUBLISH', ARGV[7], ARGV[1]) end " +
            "if ev == 'done' then return 2 elseif ev == 'failed' then return 3 end " +
            "return 1";

    // B3b: the sweeper's check, done atomically in one script so a transfer
    // that changes between "find silent ids" and "fail it" is never failed
    // by mistake.
    //
    // KEYS[1]=transfer:<id>  KEYS[2]=transfers:active
    // ARGV: 1 id, 2 silentMs, 3 waitMarginMs, 4 reason (silent),
    //       5 reason (waited too long for the sender), 6 finishedTtl, 7 channel,
    //       8 reason (waited too long for the receiver, B3c)
    // Returns 3 if this call failed it, else 0.
    private static final String SILENT_FAIL_SCRIPT =
            NOW_MS +
            "local state = redis.call('HGET', KEYS[1], 'state') " +
            // Record already gone (expired) or final: just drop the leftover id.
            "if (not state) or state == 'done' or state == 'failed' then " +
            "  redis.call('ZREM', KEYS[2], ARGV[1]) " +
            "  return 0 " +
            "end " +
            "local upd = tonumber(redis.call('HGET', KEYS[1], 'updated_at')) or 0 " +
            "if now - upd < tonumber(ARGV[2]) then return 0 end " +
            "local reason = ARGV[4] " +
            "if state == 'waiting' then " +
            "  local wu = tonumber(redis.call('HGET', KEYS[1], 'wait_until')) or 0 " +
            "  if now < wu + tonumber(ARGV[3]) then return 0 end " +
            "  if redis.call('HGET', KEYS[1], 'wait_for') == 'receiver' then " +
            "    reason = ARGV[8] " +
            "  else " +
            "    reason = ARGV[5] " +
            "  end " +
            "end " +
            "redis.call('HSET', KEYS[1], 'state', 'failed', 'reason', reason, 'updated_at', now) " +
            "redis.call('HDEL', KEYS[1], 'wait_until', 'wait_reason', 'wait_for', 'retry') " +
            "redis.call('ZREM', KEYS[2], ARGV[1]) " +
            "redis.call('EXPIRE', KEYS[1], ARGV[6]) " +
            "redis.call('PUBLISH', ARGV[7], ARGV[1]) " +
            "return 3";

    private final JedisPool pool;

    public TransferStore(String host, int port) {
        this.pool = new JedisPool(new JedisPoolConfig(), host, port);
    }

    private static String key(String id) {
        return "transfer:" + id;
    }

    /** Creates the record when the receiver accepts. Safe to call twice. */
    public boolean start(String id, String from, String to) {
        try (Jedis jedis = pool.getResource()) {
            Object r = jedis.eval(START_SCRIPT,
                    List.of(key(id), ACTIVE_KEY),
                    List.of(id, nz(from), nz(to), String.valueOf(RECORD_MAX_TTL_SECONDS), CHANGED_CHANNEL));
            return toLong(r) == 1L;
        }
    }

    /**
     * Applies one event. user = the client that sent it (must be from/to),
     * or "" when the server itself decides (peer left, no response).
     */
    public Result apply(String id, String event, String path, String pct, String reason, String user) {
        return apply(id, event, path, pct, reason, user, 0);
    }

    /** B3b: same, with waitSecs for a 'waiting' event (seconds the receiver will wait). */
    public Result apply(String id, String event, String path, String pct, String reason,
                        String user, long waitSecs) {
        try (Jedis jedis = pool.getResource()) {
            Object r = jedis.eval(APPLY_SCRIPT,
                    List.of(key(id), ACTIVE_KEY),
                    List.of(id, nz(event), nz(path), nz(pct), nz(reason),
                            String.valueOf(FINISHED_TTL_SECONDS), CHANGED_CHANNEL, nz(user),
                            String.valueOf(waitSecs)));
            return toResult(r);
        }
    }

    /**
     * B3b: fails a transfer that has been silent for silentMs — unless it is
     * waiting (for its sender, or B3c: for its receiver) and
     * wait_until + waitMarginMs has not passed.
     */
    public Result failIfSilent(String id, long silentMs, long waitMarginMs,
                               String silentReason, String waitedSenderReason,
                               String waitedReceiverReason) {
        try (Jedis jedis = pool.getResource()) {
            Object r = jedis.eval(SILENT_FAIL_SCRIPT,
                    List.of(key(id), ACTIVE_KEY),
                    List.of(id, String.valueOf(silentMs), String.valueOf(waitMarginMs),
                            nz(silentReason), nz(waitedSenderReason),
                            String.valueOf(FINISHED_TTL_SECONDS), CHANGED_CHANNEL,
                            nz(waitedReceiverReason)));
            return toResult(r);
        }
    }

    /** One transfer's record (empty map if it no longer exists). */
    public Map<String, String> get(String id) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(key(id));
        }
    }

    /**
     * B3c: the active transfers (not done / failed) where this user is the
     * sender or the receiver, for MY_TRANSFERS on login. A scan of
     * transfers:active is fine for now (small); later a per-user index.
     */
    public List<Map<String, String>> activeFor(String user) {
        List<Map<String, String>> mine = new ArrayList<>();
        if (user == null || user.isEmpty()) return mine;
        try (Jedis jedis = pool.getResource()) {
            for (String id : jedis.zrange(ACTIVE_KEY, 0, -1)) {
                Map<String, String> t = jedis.hgetAll(key(id));
                if (t == null || t.isEmpty()) continue;
                String state = t.get("state");
                if ("done".equals(state) || "failed".equals(state)) continue;
                if (user.equals(t.get("from")) || user.equals(t.get("to"))) {
                    mine.add(t);
                }
            }
        }
        return mine;
    }

    /** All active transfer ids. */
    public List<String> activeIds() {
        try (Jedis jedis = pool.getResource()) {
            return new ArrayList<>(jedis.zrange(ACTIVE_KEY, 0, -1));
        }
    }

    /** Active ids not updated since (now - silentMs), by Redis time. */
    public List<String> silentIds(long silentMs) {
        try (Jedis jedis = pool.getResource()) {
            Object t = jedis.eval("local t = redis.call('TIME') " +
                    "return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
                    List.of(), List.of());
            long cutoff = toLong(t) - silentMs;
            return new ArrayList<>(jedis.zrangeByScore(ACTIVE_KEY, "-inf", String.valueOf(cutoff)));
        }
    }

    public void close() {
        pool.close();
    }

    private static Result toResult(Object r) {
        return switch ((int) toLong(r)) {
            case 1 -> Result.CHANGED;
            case 2 -> Result.DONE;
            case 3 -> Result.FAILED;
            default -> Result.IGNORED;
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static long toLong(Object o) {
        return (o instanceof Long l) ? l : 0L;
    }
}