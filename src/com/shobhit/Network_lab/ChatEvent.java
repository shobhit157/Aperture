package com.shobhit.Network_lab;

import java.time.LocalDateTime;

public class ChatEvent {

    private final Message message;
    private final LocalDateTime timestamp;

    public ChatEvent(Message message) {
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

    public Message getMessage() {
        return message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] "
                + message.getType()
                + " | " + message.getSender()
                + " | " + message.getContent();
    }
}
