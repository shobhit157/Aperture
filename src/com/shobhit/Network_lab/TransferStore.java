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
 *   transfers:active   sorted set: active ids, score = updated_at (ms)
 *   mesh:changed       pub/sub channel: "<id>" whenever a transfer changes
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
        CHANGED,    // state updated (path / progress / started)
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
    // ARGV: 1 id, 2 event (path|progress|done|failed), 3 path, 4 pct,
    //       5 reason, 6 finishedTtl, 7 channel, 8 user ('' = server itself)
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
            "elseif ev == 'progress' then " +
            "  redis.call('HSET', KEYS[1], 'state', 'moving', 'pct', ARGV[4], 'updated_at', now) " +
            "elseif ev == 'done' then " +
            "  redis.call('HSET', KEYS[1], 'state', 'done', 'pct', '100', 'updated_at', now) " +
            "  if ARGV[3] ~= '' then redis.call('HSET', KEYS[1], 'path', ARGV[3]) end " +
            "elseif ev == 'failed' then " +
            "  redis.call('HSET', KEYS[1], 'state', 'failed', 'reason', ARGV[5], 'updated_at', now) " +
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
        try (Jedis jedis = pool.getResource()) {
            Object r = jedis.eval(APPLY_SCRIPT,
                    List.of(key(id), ACTIVE_KEY),
                    List.of(id, nz(event), nz(path), nz(pct), nz(reason),
                            String.valueOf(FINISHED_TTL_SECONDS), CHANGED_CHANNEL, nz(user)));
            return switch ((int) toLong(r)) {
                case 1 -> Result.CHANGED;
                case 2 -> Result.DONE;
                case 3 -> Result.FAILED;
                default -> Result.IGNORED;
            };
        }
    }

    /** One transfer's record (empty map if it no longer exists). */
    public Map<String, String> get(String id) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(key(id));
        }
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static long toLong(Object o) {
        return (o instanceof Long l) ? l : 0L;
    }
}