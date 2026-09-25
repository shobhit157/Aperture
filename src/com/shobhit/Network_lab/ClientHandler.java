package com.shobhit.Network_lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final ChatRoom chatRoom;
    private final EventBus eventBus;

    private String username;
    private String endpointId;
    private String relayUrl;
    private PrintWriter out;
    private boolean joined = false;

    public ClientHandler(
            Socket clientSocket,
            ChatRoom chatRoom,
            EventBus eventBus) {
        this.clientSocket = clientSocket;
        this.chatRoom = chatRoom;
        this.eventBus = eventBus;
    }

    public String getUsername() {
        return username;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getRelayUrl() {
        return relayUrl;
    }

    public void sendMessage(Message message) {
        switch (message.getType()) {
            case CHAT ->
                out.println("[" + message.getSender() + "] " + message.getContent());
            case JOIN ->
                out.println(">>> " + message.getSender() + " joined the chat");
            case LEAVE ->
                out.println("<<< " + message.getSender() + " left the chat");
            case FILE_REQUEST ->
                out.println("FILE_REQUEST|" + message.getSender() + "|" + message.getContent());
            case FILE_ACCEPT ->
                out.println("FILE_ACCEPT|" + message.getSender() + "|" + message.getContent());
            case FILE_REJECT ->
                out.println("FILE_REJECT|" + message.getSender());
            case PEER_INFO ->
                out.println("PEER_INFO|" + message.getContent());
            case REGISTER_ENDPOINT -> {
            }
            case TRANSFER_METRIC -> {
            }
        }
    }

    @Override
    public void run() {
        try {
            System.out.println("ClientHandler running on thread: "
                    + Thread.currentThread().getName());

            clientSocket.setSoTimeout(0);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));

            out = new PrintWriter(clientSocket.getOutputStream(), true);

            username = in.readLine();
            endpointId = in.readLine();
            relayUrl = in.readLine();
            chatRoom.join(this);
            joined = true;

            eventBus.publish(new ChatEvent(
                    new Message(MessageType.JOIN, username, "joined the chat")
            ));

            String line;
            while (true) {
                line = in.readLine();
                if (line == null) {
                    System.out.println("[" + username + "] disconnected.");
                    break;
                }

                String[] parts = line.split("\\|", 5);
                String prefix = parts[0];

                switch (prefix) {
                    case "FILE_REQUEST" -> {
                        String targetUser = parts[1];
                        String filename = parts[2];
                        String size = parts.length > 3 ? parts[3] : "0";
                        String transferId = parts.length > 4 ? parts[4] : "";
                        eventBus.publish(new ChatEvent(
                                new Message(MessageType.FILE_REQUEST, username, targetUser, filename + "|" + size + "|" + transferId)
                        ));
                    }
                    case "FILE_ACCEPT" -> {
                        String targetUser = parts[1];
                        String transferId = parts.length > 2 ? parts[2] : "";
                        eventBus.publish(new ChatEvent(
                                new Message(MessageType.FILE_ACCEPT, username, targetUser, transferId)
                        ));
                    }
                    case "FILE_REJECT" -> {
                        String targetUser = parts[1];
                        eventBus.publish(new ChatEvent(
                                new Message(MessageType.FILE_REJECT, username, targetUser, "")
                        ));
                    }
                    case "TRANSFER_METRIC" -> {
                        String path = parts.length > 1 ? parts[1] : "unknown";
                        String peer = parts.length > 2 ? parts[2] : "";
                        eventBus.publish(new ChatEvent(
                                new Message(MessageType.TRANSFER_METRIC, username, peer, path)
                        ));
                    }
                    default -> {
                        eventBus.publish(new ChatEvent(
                                new Message(MessageType.CHAT, username, line)
                        ));
                    }
                }
            }

        } catch (SocketTimeoutException e) {
            System.out.println("[" + username + "] inactive timeout.");
        } catch (Exception e) {
            System.err.println("[" + username + "] Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (joined) {
                eventBus.publish(new ChatEvent(
                        new Message(MessageType.LEAVE, username, "left the chat")
                ));
                chatRoom.leave(this);
            }
            try {
                clientSocket.close();
                System.out.println("Socket closed for " + username);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}