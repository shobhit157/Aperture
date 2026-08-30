package com.shobhit.Network_lab;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class FileStorageSubscriber implements EventSubscriber {

    private final String filePath;
    private final DateTimeFormatter logFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public FileStorageSubscriber() {
        // Generate filename once when server starts
        DateTimeFormatter fileFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        this.filePath = "chat-" + LocalDateTime.now().format(fileFormatter) + ".log";
        System.out.println("[FileStorage] Logging to: " + filePath);
    }

    @Override
    public void onEvent(ChatEvent event) {

        if (event.getMessage().getType() != MessageType.CHAT) return;

        String line = "[" + event.getTimestamp().format(logFormatter) + "] "
                + event.getMessage().getSender()
                + " : "
                + event.getMessage().getContent();

        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(filePath, true))) {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("[FileStorage] Failed to write: " + e.getMessage());
        }
    }
}