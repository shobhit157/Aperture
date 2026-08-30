package com.shobhit.Network_lab;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatRoom {

    private final Map<String, ClientHandler> localClients = new ConcurrentHashMap<>();
    private final RedisClientRegistry registry;
    private final MessageBroker broker; // null if SNS not configured
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
        localClients.put(client.getUsername(), client);
        registry.register(client.getUsername(), client.getEndpointId(), instanceId);
    }

    public void leave(ClientHandler client) {
        localClients.remove(client.getUsername());
        registry.unregister(client.getUsername());
    }

    public void broadcast(Message message, ClientHandler sender) {
        if (broker != null) {
            broker.publishBroadcast(message);
        } else {
            deliverLocal(message); // single-instance: just deliver directly
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
}
