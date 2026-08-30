package com.shobhit.Network_lab;

public class Message {

        private MessageType type;
        private String sender;
        private String target;   // new: who this message is aimed at (null for broadcast chat)
        private String content;

        public Message(MessageType type, String sender, String content) {
                this.type = type;
                this.sender = sender;
                this.target = null;
                this.content = content;
        }

        public Message(MessageType type, String sender, String target, String content) {
                this.type = type;
                this.sender = sender;
                this.target = target;
                this.content = content;
        }

        public String getSender() { return sender; }
        public String getTarget() { return target; }
        public String getContent() { return content; }
        public MessageType getType() { return type; }
}
