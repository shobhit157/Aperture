package com.shobhit.Network_lab;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatRoom {

    private final Map<String, ClientHandler> localClients = new ConcurrentHashMap<>();
    private final RedisClientRegistry registry;
    private final MessageBroker broker;
    private final String instanceId;

    public ChatRoom(RedisClientRegistry registry, String snsTopicArn, String instanceId) {
        this.registry = registry;
        this.instanceId = instanceId;

        if (snsTopicArn != null && !snsTopicArn.isBlank()) {
            this.broker = new MessageBroker(snsTopicArn, this, instanceId);
            this.broker.start();
            System.out.println("[ChatRoom] SNS/SQS cross-instance routing enabled.");
        } else {
            this.broker = null;
            System.out.println("[ChatRoom] SNS not configured — single-instance mode (local delivery only).");
        }
    }

    public void join(ClientHandler client) {
        String user = client.getUsername();

        // Step 1 (measure only, kept): report when a second session for
        // the same name shows up while an older one is still registered.
        ClientHandler previous = localClients.put(user, client);
        if (previous != null && previous != client) {
            System.out.println("[SESSION] overlap user=" + user
                    + " oldSession=" + previous.getSessionId()
                    + " newSession=" + client.getSessionId()
                    + " (same pod)");
        }
        try {
            String registeredOn = registry.getInstanceId(user);
            String registeredSession = registry.getSessionId(user);
            if (registeredOn != null && !registeredOn.equals(instanceId)) {
                System.out.println("[SESSION] overlap user=" + user
                        + " newSession=" + client.getSessionId()
                        + " registeredOnPod=" + registeredOn
                        + " registeredSession=" + registeredSession
                        + " thisPod=" + instanceId
                        + " (other pod)");
            }
        } catch (Exception e) {
            System.out.println("[SESSION] overlap check failed: " + e.getMessage());
        }

        // Step 2 + 4: record, expiry and online entry written in one
        // atomic step. The session ID travels with it, so a later
        // unregister can check whether it's still the current one.
        registry.register(user, client.getEndpointId(), client.getRelayUrl(), instanceId, client.getSessionId());

        // Step 4: instant takeover on the same pod. MUST come after
        // register(): if the old session were ended first, its cleanup
        // would still see itself as owner, delete the record, and announce
        // a false "left the chat". Runs on its own thread so a write into a
        // dead socket can never stall the new session's join. Old sessions
        // on OTHER pods are caught by their own 10s ownership check.
        if (previous != null && previous != client) {
            String newSessionId = client.getSessionId();
            Thread t = new Thread(() -> previous.supersede(newSessionId),
                    "supersede-" + previous.getSessionId());
            t.setDaemon(true);
            t.start();
        }
    }

    // Step 2: returns true only if this call was the genuine, current
    // owner of the username (Redis's check-and-delete succeeded). Returns
    // false if a newer session had already taken over, in which case this
    // call did nothing to the shared state. The local map entry is removed
    // defensively either way, but only if it's still this exact handler —
    // never a newer one that already replaced it.
    public boolean leave(ClientHandler client) {
        String user = client.getUsername();

        localClients.remove(user, client);

        boolean wasOwner = registry.unregister(user, client.getSessionId());

        System.out.println("[SESSION] cleanup user=" + user
                + " session=" + client.getSessionId()
                + " wasOwner=" + wasOwner
                + " thisPod=" + instanceId);

        return wasOwner;
    }

    // Step 4: the session Redis currently records as owner of this
    // username, or null if none. Used by ClientHandler's ownership check.
    public String getRegisteredSessionId(String username) {
        return registry.getSessionId(username);
    }

    // Step 4: renew this session's presence (owner only). Called on PING.
    public void heartbeat(ClientHandler client) {
        int result = registry.heartbeat(client.getUsername(), client.getEndpointId(),
                client.getRelayUrl(), instanceId, client.getSessionId());
        if (result == 2) {
            System.out.println("[SESSION] user=" + client.getUsername()
                    + " session=" + client.getSessionId()
                    + " registry record was missing, re-registered");
        }
    }

    // Step 5: everyone online across all pods.
    public List<String> getOnlineUsers() {
        return registry.getOnlineUsers();
    }

    public void broadcast(Message message, ClientHandler sender) {
        if (broker != null) {
            broker.publishBroadcast(message);
        } else {
            deliverLocal(message);
        }
    }

    public void sendTo(String username, Message message) {
        String targetInstance = registry.getInstanceId(username);
        if (targetInstance == null) return;

        if (broker == null || targetInstance.equals(instanceId)) {
            deliverLocal(message);
        } else {
            broker.publishTargeted(targetInstance, message);
        }
    }

    public void deliverLocal(Message message) {
        if (message.getTarget() == null) {
            for (ClientHandler c : localClients.values()) {
                c.sendMessage(message);
            }
        } else {
            ClientHandler c = localClients.get(message.getTarget());
            if (c != null) {
                c.sendMessage(message);
            }
        }
    }

    public String getEndpointId(String username) {
        return registry.getEndpointId(username);
    }

    public String getRelayUrl(String username) {
        return registry.getRelayUrl(username);
    }

    public int getLocalClientCount() {
        return localClients.size();
    }
}