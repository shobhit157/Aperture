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
        System.out.println("[MessageBroker] Received raw payload: " + payload);
        String[] parts = payload.split("\\|", 5);
        String kind = parts[0];

        if (kind.equals("BCAST")) {
            MessageType type = MessageType.valueOf(parts[1]);
            String sender = parts[2];
            String content = parts.length > 3 ? parts[3] : "";
            chatRoom.deliverLocal(new Message(type, sender, content));
        } else if (kind.equals("TGT")) {
            MessageType type = MessageType.valueOf(parts[1]);
            String sender = parts[2];
            String target = parts[3];
            String content = parts.length > 4 ? parts[4] : "";
            chatRoom.deliverLocal(new Message(type, sender, target, content));
        } else if (kind.equals("MESH")) {
            if (meshEventServer == null) return;
            String action = parts[1];
            if (action.equals("START")) {
                String from = parts[2];
                String to = parts.length > 3 ? parts[3] : "";
                meshEventServer.applyRemoteStart(from, to);
            } else if (action.equals("COMPLETE")) {
                String from = parts[2];
                String to = parts.length > 3 ? parts[3] : "";
                String path = parts.length > 4 ? parts[4] : "";
                meshEventServer.applyRemoteComplete(from, to, path);
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

    public void publishMeshStart(String from, String to) {
        String payload = "MESH|START|" + from + "|" + safe(to);
        publishToAll(payload);
    }

    public void publishMeshComplete(String from, String to, String path) {
        String payload = "MESH|COMPLETE|" + from + "|" + safe(to) + "|" + safe(path);
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