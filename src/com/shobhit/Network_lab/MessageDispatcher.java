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

            case FILE_REQUEST ->
                chatRoom.sendTo(message.getTarget(), message);

            case FILE_ACCEPT -> {
                String receiverEndpoint = chatRoom.getEndpointId(message.getSender());
                Message peerInfoMsg = new Message(
                        MessageType.PEER_INFO,
                        message.getSender(),
                        message.getTarget(),
                        message.getSender() + "|" + receiverEndpoint
                );
                chatRoom.sendTo(message.getTarget(), peerInfoMsg);
            }

            case FILE_REJECT ->
                chatRoom.sendTo(message.getTarget(), message);

            default -> {
            }
        }
    }
}
