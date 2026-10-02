package com.shobhit.Network_lab;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class MessageBroker {

    private final SnsClient snsClient;
    private final SqsClient sqsClient;
    private final String topicArn;
    private final String queueUrl;
    private final ChatRoom chatRoom;
    private final String instanceId;
    private MeshEventServer meshEventServer;

    public MessageBroker(String topicArn, ChatRoom chatRoom, String instanceId) {
        this.snsClient = SnsClient.create();
        this.sqsClient = SqsClient.create();
        this.topicArn = topicArn;
        this.chatRoom = chatRoom;
        this.instanceId = instanceId;
        this.queueUrl = createInstanceQueue();
    }

    public void setMeshEventServer(MeshEventServer meshEventServer) {
        this.meshEventServer = meshEventServer;
    }

    private String createInstanceQueue() {
        String queueName = "chat-instance-" + instanceId;

        CreateQueueResponse queueResp = sqsClient.createQueue(
                CreateQueueRequest.builder()
                        .queueName(queueName)
                        .attributes(Map.of(
                                QueueAttributeName.MESSAGE_RETENTION_PERIOD, "300"
                        ))
                        .build());
        String url = queueResp.queueUrl();

        String queueArn = sqsClient.getQueueAttributes(
                GetQueueAttributesRequest.builder()
                        .queueUrl(url)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build()
        ).attributes().get(QueueAttributeName.QUEUE_ARN);

        String filterPolicy = "{\"target\": [\"broadcast\", \"" + instanceId + "\"]}";

        Map<String, String> subAttrs = new HashMap<>();
        subAttrs.put("FilterPolicy", filterPolicy);
        subAttrs.put("RawMessageDelivery", "true");

        snsClient.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol("sqs")
                .endpoint(queueArn)
                .attributes(subAttrs)
                .build());

        String policy = """
            {
              "Version": "2012-10-17",
              "Statement": [{
                "Effect": "Allow",
                "Principal": "*",
                "Action": "sqs:SendMessage",
                "Resource": "%s",
                "Condition": {"ArnEquals": {"aws:SourceArn": "%s"}}
              }]
            }
            """.formatted(queueArn, topicArn);

        sqsClient.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(url)
                .attributes(Map.of(QueueAttributeName.POLICY, policy))
                .build());

        return url;
    }

    public void start() {
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                try {
                    ReceiveMessageResponse resp = sqsClient.receiveMessage(
                            ReceiveMessageRequest.builder()
                                    .queueUrl(queueUrl)
                                    .maxNumberOfMessages(10)
                                    .waitTimeSeconds(20)
                                    .build());

                    for (software.amazon.awssdk.services.sqs.model.Message m : resp.messages()) {
                        handleIncoming(m.body());
                        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(m.receiptHandle())
                                .build());
                    }
                } catch (Exception e) {
                    System.err.println("[MessageBroker] Error in receive loop: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    private void handleIncoming(String payload) {
        if (!payload.startsWith("MESH|PROGRESS") && !payload.startsWith("MESH|CONNPATH")) {
            System.out.println("[MessageBroker] Received raw payload: " + payload);
        }

        int firstSep = payload.indexOf('|');
        if (firstSep < 0) return;
        String kind = payload.substring(0, firstSep);
        String rest = payload.substring(firstSep + 1);

        if (kind.equals("BCAST")) {
            String[] parts = rest.split("\\|", 3);
            MessageType type = MessageType.valueOf(parts[0]);
            String sender = parts[1];
            String content = parts.length > 2 ? parts[2] : "";
            chatRoom.deliverLocal(new Message(type, sender, content));
        } else if (kind.equals("TGT")) {
            String[] parts = rest.split("\\|", 4);
            MessageType type = MessageType.valueOf(parts[0]);
            String sender = parts[1];
            String target = parts[2];
            String content = parts.length > 3 ? parts[3] : "";
            chatRoom.deliverLocal(new Message(type, sender, target, content));
        } else if (kind.equals("MESH")) {
            if (meshEventServer == null) return;
            String[] parts = rest.split("\\|");
            String action = parts[0];
            if (action.equals("START")) {
                String from = parts[1];
                String to = parts.length > 2 ? parts[2] : "";
                String transferId = parts.length > 3 ? parts[3] : "";
                meshEventServer.applyRemoteStart(from, to, transferId);
            } else if (action.equals("COMPLETE")) {
                String from = parts[1];
                String to = parts.length > 2 ? parts[2] : "";
                String path = parts.length > 3 ? parts[3] : "";
                meshEventServer.applyRemoteComplete(from, to, path);
            } else if (action.equals("PROGRESS")) {
                String transferId = parts[1];
                String pct = parts.length > 2 ? parts[2] : "0";
                meshEventServer.applyRemoteProgress(transferId, pct);
            } else if (action.equals("FAILED")) {
                String from = parts[1];
                String to = parts.length > 2 ? parts[2] : "";
                String reason = parts.length > 3 ? parts[3] : "unknown";
                meshEventServer.applyRemoteFailed(from, to, reason);
            } else if (action.equals("CONNPATH")) {
                // Fix 4: a LIVE path update, keyed by transfer ID directly.
                String transferId = parts[1];
                String path = parts.length > 2 ? parts[2] : "unknown";
                meshEventServer.applyConnectionPath(transferId, path);
            }
        }
    }

    public void publishBroadcast(Message message) {
        String payload = "BCAST|" + message.getType() + "|" + message.getSender()
                + "|" + safe(message.getContent());

        Map<String, MessageAttributeValue> attrs = new HashMap<>();
        attrs.put("target", MessageAttributeValue.builder()
                .dataType("String").stringValue("broadcast").build());

        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(payload)
                .messageAttributes(attrs)
                .build());
    }

    public void publishTargeted(String targetInstanceId, Message message) {
        String payload = "TGT|" + message.getType() + "|" + message.getSender()
                + "|" + message.getTarget() + "|" + safe(message.getContent());

        Map<String, MessageAttributeValue> attrs = new HashMap<>();
        attrs.put("target", MessageAttributeValue.builder()
                .dataType("String").stringValue(targetInstanceId).build());

        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(payload)
                .messageAttributes(attrs)
                .build());
    }

    public void publishMeshStart(String from, String to, String transferId) {
        String payload = "MESH|START|" + from + "|" + safe(to) + "|" + safe(transferId);
        publishToAll(payload);
    }

    public void publishMeshComplete(String from, String to, String path) {
        String payload = "MESH|COMPLETE|" + from + "|" + safe(to) + "|" + safe(path);
        publishToAll(payload);
    }

    public void publishMeshProgress(String transferId, String pct) {
        String payload = "MESH|PROGRESS|" + transferId + "|" + safe(pct);
        publishToAll(payload);
    }

    public void publishMeshFailed(String from, String to, String reason) {
        String payload = "MESH|FAILED|" + from + "|" + safe(to) + "|" + safe(reason);
        publishToAll(payload);
    }

    // Fix 4: a LIVE path update, broadcast to all pods so every pod's
    // mesh state stays in sync, mirroring publishMeshProgress above.
    public void publishConnectionPath(String transferId, String path) {
        String payload = "MESH|CONNPATH|" + transferId + "|" + safe(path);
        publishToAll(payload);
    }

    private void publishToAll(String payload) {
        Map<String, MessageAttributeValue> attrs = new HashMap<>();
        attrs.put("target", MessageAttributeValue.builder()
                .dataType("String").stringValue("broadcast").build());

        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(payload)
                .messageAttributes(attrs)
                .build());
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}