package com.shobhit.Network_lab;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RedisClientRegistry {

    // Step 4: a user's presence lives this long after the owner's last
    // heartbeat. The client PINGs every 20s and the server ends silent
    // sessions after 60s (checked on a 5s tick), so 75s never expires a
    // live session, but still cleans up within ~75s if a whole server pod
    // dies and its finally/cleanup code never runs.
    public static final long PRESENCE_TTL_MS = 75_000;

    // Step 4: replaces the old plain set "online_users". A sorted set lets
    // each user expire individually: member = username, score = last-seen
    // time in ms (Redis clock). "Online" = seen within PRESENCE_TTL_MS.
    private static final String ONLINE_KEY = "online_users_v2";

    // Shared Lua snippet: current time in ms from Redis's own clock, so
    // the two server pods' clocks never matter.
    private static final String NOW_MS =
            "local t = redis.call('TIME') " +
            "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) ";

    // Step 4: register = write record + set expiry + mark online, as ONE
    // atomic step (closes step 2's leftover: hset and sadd were separate).
    // KEYS[1]=client:<user>  KEYS[2]=online set
    // ARGV: 1 username, 2 endpointId, 3 relayUrl, 4 instanceId, 5 sessionId, 6 ttlMs
    private static final String REGISTER_SCRIPT =
            NOW_MS +
            "redis.call('DEL', KEYS[1]) " +
            "redis.call('HSET', KEYS[1], 'endpointId', ARGV[2], 'relayUrl', ARGV[3], " +
            "           'instanceId', ARGV[4], 'sessionId', ARGV[5]) " +
            "redis.call('PEXPIRE', KEYS[1], ARGV[6]) " +
            "redis.call('ZADD', KEYS[2], now, ARGV[1]) " +
            "return 1";

    // Step 4: heartbeat, run on every PING. Only the genuine owner renews.
    // Same KEYS/ARGV as REGISTER_SCRIPT. Returns:
    //   1 = owner, renewed
    //   2 = record was missing (Redis restart / expired early), re-created
    //   0 = a different session owns the name, nothing touched
    private static final String HEARTBEAT_SCRIPT =
            NOW_MS +
            "local current = redis.call('HGET', KEYS[1], 'sessionId') " +
            "if current == ARGV[5] then " +
            "  redis.call('PEXPIRE', KEYS[1], ARGV[6]) " +
            "  redis.call('ZADD', KEYS[2], now, ARGV[1]) " +
            "  return 1 " +
            "elseif current == false then " +
            "  redis.call('HSET', KEYS[1], 'endpointId', ARGV[2], 'relayUrl', ARGV[3], " +
            "             'instanceId', ARGV[4], 'sessionId', ARGV[5]) " +
            "  redis.call('PEXPIRE', KEYS[1], ARGV[6]) " +
            "  redis.call('ZADD', KEYS[2], now, ARGV[1]) " +
            "  return 2 " +
            "else " +
            "  return 0 " +
            "end";

    // Step 2 (kept), now removing from the sorted set. Deletes only if the
    // caller still owns the name. Returns 1 if deleted, 0 if skipped.
    // KEYS[1]=client:<user>  KEYS[2]=online set   ARGV: 1 sessionId, 2 username
    private static final String UNREGISTER_IF_OWNER_SCRIPT =
            "local current = redis.call('HGET', KEYS[1], 'sessionId') " +
            "if current == ARGV[1] then " +
            "  redis.call('DEL', KEYS[1]) " +
            "  redis.call('ZREM', KEYS[2], ARGV[2]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";

    // Step 4/5: drop users not seen within the TTL, then return who is left.
    // KEYS[1]=online set   ARGV[1]=ttlMs
    private static final String ONLINE_USERS_SCRIPT =
            NOW_MS +
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. (now - tonumber(ARGV[1]))) " +
            "return redis.call('ZRANGE', KEYS[1], 0, -1)";

    // Same trim, then count.
    private static final String ONLINE_COUNT_SCRIPT =
            NOW_MS +
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. (now - tonumber(ARGV[1]))) " +
            "return redis.call('ZCARD', KEYS[1])";

    private final JedisPool pool;

    public RedisClientRegistry(String host, int port) {
        this.pool = new JedisPool(new JedisPoolConfig(), host, port);
    }

    private static List<String> presenceArgs(String username, String endpointId, String relayUrl,
                                             String instanceId, String sessionId) {
        return List.of(
                username,
                endpointId == null ? "" : endpointId,
                relayUrl == null ? "" : relayUrl,
                instanceId == null ? "" : instanceId,
                sessionId == null ? "" : sessionId,
                String.valueOf(PRESENCE_TTL_MS));
    }

    public void register(String username, String endpointId, String relayUrl, String instanceId, String sessionId) {
        try (var jedis = pool.getResource()) {
            jedis.eval(REGISTER_SCRIPT,
                    List.of("client:" + username, ONLINE_KEY),
                    presenceArgs(username, endpointId, relayUrl, instanceId, sessionId));
        }
    }

    // Step 4: see HEARTBEAT_SCRIPT for the return values.
    public int heartbeat(String username, String endpointId, String relayUrl, String instanceId, String sessionId) {
        try (var jedis = pool.getResource()) {
            Object result = jedis.eval(HEARTBEAT_SCRIPT,
                    List.of("client:" + username, ONLINE_KEY),
                    presenceArgs(username, endpointId, relayUrl, instanceId, sessionId));
            return result == null ? 0 : ((Long) result).intValue();
        }
    }

    public boolean unregister(String username, String sessionId) {
        try (var jedis = pool.getResource()) {
            Object result = jedis.eval(UNREGISTER_IF_OWNER_SCRIPT,
                    List.of("client:" + username, ONLINE_KEY),
                    List.of(sessionId == null ? "" : sessionId, username));
            return result != null && ((Long) result) == 1L;
        }
    }

    // Step 5: everyone seen within the TTL, across all pods.
    public List<String> getOnlineUsers() {
        try (var jedis = pool.getResource()) {
            Object result = jedis.eval(ONLINE_USERS_SCRIPT,
                    List.of(ONLINE_KEY),
                    List.of(String.valueOf(PRESENCE_TTL_MS)));
            List<String> users = new ArrayList<>();
            if (result instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof byte[] b) users.add(new String(b, StandardCharsets.UTF_8));
                    else if (o != null) users.add(o.toString());
                }
            }
            return users;
        }
    }

    public String getEndpointId(String username) {
        try (var jedis = pool.getResource()) {
            return jedis.hget("client:" + username, "endpointId");
        }
    }

    public String getRelayUrl(String username) {
        try (var jedis = pool.getResource()) {
            return jedis.hget("client:" + username, "relayUrl");
        }
    }

    public String getInstanceId(String username) {
        try (var jedis = pool.getResource()) {
            return jedis.hget("client:" + username, "instanceId");
        }
    }

    public String getSessionId(String username) {
        try (var jedis = pool.getResource()) {
            return jedis.hget("client:" + username, "sessionId");
        }
    }

    // Step 4: now counts only users seen within the TTL. Prometheus reads
    // this, so the gauge corrects itself after a pod crash.
    public long getGlobalOnlineCount() {
        try (var jedis = pool.getResource()) {
            Object result = jedis.eval(ONLINE_COUNT_SCRIPT,
                    List.of(ONLINE_KEY),
                    List.of(String.valueOf(PRESENCE_TTL_MS)));
            return result == null ? 0 : (Long) result;
        }
    }

    public void close() {
        pool.close();
    }
}