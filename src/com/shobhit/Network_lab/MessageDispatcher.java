package com.shobhit.Network_lab;

public class MessageDispatcher {

    private final MessageStore messageStore;
    private final ChatRoom chatRoom;

    public MessageDispatcher(
            MessageStore messageStore,
            ChatRoom chatRoom) {
        this.messageStore = messageStore;
        this.chatRoom = chatRoom;
    }

    public void dispatch(Message message) {
        messageStore.save(message);

        switch (message.getType()) {
            case CHAT, JOIN, LEAVE ->
                chatRoom.broadcast(message, null);

            case FILE_REQUEST -> {
                // B3a: add the sender's address, so a blobs receiver
                // knows where to fetch from. Content becomes:
                // filename|size|id|hash|senderEndpoint|senderRelay
                String senderEndpoint = chatRoom.getEndpointId(message.getSender());
                String senderRelayUrl = chatRoom.getRelayUrl(message.getSender());
                Message withAddress = new Message(
                        MessageType.FILE_REQUEST,
                        message.getSender(),
                        message.getTarget(),
                        message.getContent() + "|" + senderEndpoint + "|" + senderRelayUrl
                );
                chatRoom.sendTo(message.getTarget(), withAddress);
            }

            case FILE_ACCEPT -> {
                String receiverEndpoint = chatRoom.getEndpointId(message.getSender());
                String receiverRelayUrl = chatRoom.getRelayUrl(message.getSender());
                String transferId = message.getContent();
                Message peerInfoMsg = new Message(
                        MessageType.PEER_INFO,
                        message.getSender(),
                        message.getTarget(),
                        message.getSender() + "|" + receiverEndpoint + "|" + receiverRelayUrl + "|" + transferId
                );
                chatRoom.sendTo(message.getTarget(), peerInfoMsg);
            }

            case FILE_REJECT ->
                chatRoom.sendTo(message.getTarget(), message);

            // B3a: the sender allowed the receiver; tell the receiver to fetch.
            case FILE_READY ->
                chatRoom.sendTo(message.getTarget(), message);

            default -> {
            }
        }
    }
}