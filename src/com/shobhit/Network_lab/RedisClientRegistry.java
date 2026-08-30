package com.shobhit.Network_lab;


import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisClientRegistry {

    private final JedisPool pool;

    public RedisClientRegistry(String host, int port) {
        this.pool = new JedisPool(new JedisPoolConfig(), host, port);
    }

    public void register(String username, String endpointId, String instanceId) {
        try (var jedis = pool.getResource()) {
            jedis.hset("client:" + username, "endpointId", endpointId == null ? "" : endpointId);
            jedis.hset("client:" + username, "instanceId", instanceId);
            jedis.sadd("online_users", username);
        }
    }

    public void unregister(String username) {
        try (var jedis = pool.getResource()) {
            jedis.del("client:" + username);
            jedis.srem("online_users", username);
        }
    }

    public String getEndpointId(String username) {
        try (var jedis = pool.getResource()) {
            return jedis.hget("client:" + username, "endpointId");
        }
    }

    public String getInstanceId(String username) {
        try (var jedis = pool.getResource()) {
            return jedis.hget("client:" + username, "instanceId");
        }
    }

    public void close() {
        pool.close();
    }
}