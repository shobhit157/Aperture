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
        // TRANSFER_PROGRESS is high-frequency, purely operational plumbing
        // that feeds MeshEventServer's internal state — it was never meant
        // to be part of the chat/audit log. Excluding it here keeps the
        // server's console clean without affecting mesh accuracy at all.
        if (message.getType() != MessageType.TRANSFER_PROGRESS) {
            messageStore.save(message);
        }

        switch (message.getType()) {
            case CHAT, JOIN, LEAVE ->
                chatRoom.broadcast(message, null);

            case FILE_REQUEST ->
                chatRoom.sendTo(message.getTarget(), message);

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

                chatRoom.meshTransferStart(message.getTarget(), message.getSender(), transferId);
            }

            case FILE_REJECT ->
                chatRoom.sendTo(message.getTarget(), message);

            case TRANSFER_PROGRESS -> {
                String transferId = message.getTarget();
                String pct = message.getContent();
                chatRoom.meshTransferProgress(transferId, pct);
            }

            default -> {
            }
        }
    }
}