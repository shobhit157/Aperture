package com.shobhit.Network_lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client {

    private static final Map<String, String> pendingFiles = new ConcurrentHashMap<>();
    private static final Map<String, String> pendingTransferIds = new ConcurrentHashMap<>();
    private static final Map<String, String> transferPeers = new ConcurrentHashMap<>();

    private static final Random random = new Random();
    private static final List<String> knownUsers = new CopyOnWriteArrayList<>();

    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_DELAY_SECONDS = 5;

    private static final List<String> CHAT_MESSAGES = List.of(
            "hey everyone",
            "how's it going?",
            "anyone around?",
            "just testing things out",
            "nice weather today",
            "working on something cool",
            "brb",
            "lol",
            "what's up?",
            "checking in"
    );

    public static void main(String[] args) throws IOException {

        boolean botMode = false;
        for (String a : args) {
            if (a.equals("--bot")) botMode = true;
        }

        String host = "localhost";
        int port = 5000;
        int nonFlagIndex = 0;
        for (String a : args) {
            if (a.startsWith("--")) continue;
            if (nonFlagIndex == 0) host = a;
            else if (nonFlagIndex == 1) port = Integer.parseInt(a);
            nonFlagIndex++;
        }

        String peerBinaryPath = System.getenv("PEER_BINARY_PATH");
        if (peerBinaryPath == null) {
            String[] knownPaths = {
                "/home/shobhit/peer-app/target/release/peer",
                "/home/ubuntu/aperture/peer-app/target/release/peer"
            };
            for (String candidate : knownPaths) {
                if (new java.io.File(candidate).exists()) {
                    peerBinaryPath = candidate;
                    break;
                }
            }
        }
        if (peerBinaryPath == null) {
            System.out.println("Could not find peer binary. Set PEER_BINARY_PATH environment variable.");
            return;
        }

        String username;
        if (botMode) {
            username = System.getenv().getOrDefault("HOSTNAME", "bot-" + (System.currentTimeMillis() % 100000));
            System.out.println("Bot mode. Username: " + username);
        } else {
            Scanner sc = new Scanner(System.in);
            System.out.print("Enter username: ");
            username = sc.nextLine();
        }

        if (botMode) {
            ensureTestFilesExist();
        }

        ProcessBuilder pb = new ProcessBuilder(peerBinaryPath, username);
        if (botMode) {
            pb.environment().put("RUST_LOG", "iroh=debug");
        }
        pb.redirectErrorStream(true);
        Process peerProcess = pb.start();

        OutputStream peerIn = peerProcess.getOutputStream();
        PrintWriter peerWriter = new PrintWriter(peerIn, true);
        BufferedReader peerOut = new BufferedReader(new InputStreamReader(peerProcess.getInputStream()));

        String peerLine;
        String myEndpointId = null;
        String myRelayUrl = null;

        while ((peerLine = peerOut.readLine()) != null) {
            if (peerLine.startsWith("EVENT:ENDPOINT_READY:")) {
                myEndpointId = peerLine.substring("EVENT:ENDPOINT_READY:".length());
            } else if (peerLine.startsWith("EVENT:RELAY_READY:")) {
                myRelayUrl = peerLine.substring("EVENT:RELAY_READY:".length());
            }
            if (myEndpointId != null && myRelayUrl != null) {
                break;
            }
        }
        System.out.println("Local peer-app ready, endpoint: " + myEndpointId + ", relay: " + myRelayUrl);

        final String finalMyEndpointId = myEndpointId;
        final String finalMyRelayUrl = myRelayUrl;
        final String myUsername = username;
        final boolean isBot = botMode;
        final AtomicBoolean shuttingDown = new AtomicBoolean(false);

        final PrintWriter[] currentOut = new PrintWriter[1];

        Thread peerListener = new Thread(() -> {
            try {
                String line;
                while ((line = peerOut.readLine()) != null) {
                    PrintWriter out = currentOut[0];
                    if (line.startsWith("EVENT:PROGRESS:")) {
                        String data = line.substring("EVENT:PROGRESS:".length());
                        String[] p = data.split("\\|");
                        if (p.length >= 5) {
                            ProgressBarRender.render(p[0], p[1], Integer.parseInt(p[2]), p[3], p[4]);
                        }
                    } else if (line.startsWith("EVENT:TRANSFER_PATH:")) {
                        String rest = line.substring("EVENT:TRANSFER_PATH:".length());
                        int sep = rest.indexOf(':');
                        String transferId = sep >= 0 ? rest.substring(0, sep) : rest;
                        String path = sep >= 0 ? rest.substring(sep + 1) : "unknown";
                        String peer = transferPeers.remove(transferId);
                        if (out != null) {
                            out.println("TRANSFER_METRIC|" + path + "|" + (peer == null ? "" : peer));
                        }
                    } else {
                        System.out.println("\n[peer-app] " + line);
                    }
                }
            } catch (IOException e) {
                // peer-app process ended
            }
        });
        peerListener.setDaemon(true);
        peerListener.start();

        if (!isBot) {
            Thread inputThread = new Thread(() -> runInteractiveInput(currentOut));
            inputThread.setDaemon(true);
            inputThread.start();
        }

        // Shared counter so runConnection() can reset it to 0 the moment a
        // connection is genuinely, successfully established — any successful
        // connect proves the network path currently works, so the next
        // failure (whenever it happens) starts a fresh set of 5 attempts.
        final int[] attemptCounter = {0};

        while (!shuttingDown.get()) {
            attemptCounter[0]++;
            try {
                runConnection(host, port, myUsername, finalMyEndpointId, finalMyRelayUrl,
                        isBot, peerWriter, currentOut, attemptCounter);
                break;
            } catch (IOException e) {
                System.out.println("\nConnection lost (attempt " + attemptCounter[0] + "/" + MAX_RECONNECT_ATTEMPTS
                        + "): " + e.getMessage());
                if (attemptCounter[0] >= MAX_RECONNECT_ATTEMPTS) {
                    System.out.println("Giving up after " + attemptCounter[0] + " attempts.");
                    break;
                }
                System.out.println("Reconnecting in " + RECONNECT_DELAY_SECONDS + "s...");
                try {
                    Thread.sleep(RECONNECT_DELAY_SECONDS * 1000L);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private static void runConnection(String host, int port, String myUsername,
                                       String myEndpointId, String myRelayUrl, boolean isBot,
                                       PrintWriter peerWriter, PrintWriter[] currentOut,
                                       int[] attemptCounter) throws IOException {

        Socket socket = new Socket(host, port);
        System.out.println("Connected to server!");
        attemptCounter[0] = 0; // successful connection — reset for the next episode

        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        currentOut[0] = out;

        out.println(myUsername);
        out.println(myEndpointId);
        out.println(myRelayUrl);

        if (isBot) {
            System.out.println("Bot ready. Waiting for commands (!chat, !send)...");
        } else {
            System.out.println("Commands:");
            System.out.println("  <message>                              - send a chat message");
            System.out.println("  /send <username> <file_path>           - send a file to a user");
            System.out.println("  !chat <all|username>                   - tell bots to send a chat message");
            System.out.println("  !send <bot|all> <recipient|all> <small|medium|large> - tell bots to send a file");
        }

        String line;
        while ((line = in.readLine()) != null) {

            if (line.startsWith(">>> ") && line.contains(" joined the chat")) {
                String who = line.substring(4, line.indexOf(" joined the chat"));
                if (!who.equals(myUsername) && !knownUsers.contains(who)) {
                    knownUsers.add(who);
                }
            } else if (line.startsWith("<<< ") && line.contains(" left the chat")) {
                String who = line.substring(4, line.indexOf(" left the chat"));
                knownUsers.remove(who);
            }

            if (line.startsWith("FILE_REQUEST|")) {
                String[] parts = line.split("\\|", 5);
                String from = parts[1];
                String rest = parts.length > 2 ? parts[2] : "";
                String transferId = parts.length > 4 ? parts[4] : UUID.randomUUID().toString();
                System.out.println("\n" + from + " wants to send you a file: " + rest);
                System.out.println("Auto-accepting...");
                transferPeers.put(transferId, from);
                out.println("FILE_ACCEPT|" + from + "|" + transferId);

            } else if (line.startsWith("PEER_INFO|")) {
                String[] parts = line.split("\\|", 5);
                String senderUsername = parts[1];
                String senderEndpointId = parts.length > 2 ? parts[2] : "";
                String senderRelayUrl = parts.length > 3 ? parts[3] : "";
                String transferId = parts.length > 4 ? parts[4] : pendingTransferIds.remove(senderUsername);
                String filePath = pendingFiles.remove(senderUsername);
                pendingTransferIds.remove(senderUsername);
                if (filePath != null && transferId != null) {
                    transferPeers.put(transferId, senderUsername);
                    peerWriter.println("sendto " + transferId + " " + senderEndpointId + " " + senderRelayUrl + " " + filePath);
                }

            } else if (line.startsWith("FILE_REJECT|")) {
                System.out.println("\nFile transfer was rejected.");

            } else if (isBot && line.contains("] !chat ")) {
                String target = line.substring(line.indexOf("!chat ") + 6).trim();
                if (target.equals("all") || target.equals(myUsername)) {
                    String msg = CHAT_MESSAGES.get(random.nextInt(CHAT_MESSAGES.size()));
                    out.println(msg);
                }

            } else if (isBot && line.contains("] !send ")) {
                String[] parts = line.substring(line.indexOf("!send ") + 6).trim().split(" ");
                if (parts.length >= 2) {
                    String senderTarget = parts[0];
                    String recipientTarget = parts[1];
                    String sizeChoice = parts.length >= 3 ? parts[2] : "medium";

                    boolean shouldSend = senderTarget.equals(myUsername) || senderTarget.equals("all");
                    if (shouldSend) {
                        List<String> recipients;
                        if (recipientTarget.equals("all")) {
                            recipients = new ArrayList<>(knownUsers);
                            recipients.remove(myUsername);
                        } else {
                            recipients = List.of(recipientTarget);
                        }

                        String fileName = switch (sizeChoice) {
                            case "small" -> "bot_small.txt";
                            case "large" -> "bot_large.bin";
                            default -> "bot_medium.bin";
                        };

                        for (String recipient : recipients) {
                            java.io.File f = new java.io.File(fileName);
                            if (f.exists()) {
                                String transferId = UUID.randomUUID().toString();
                                pendingFiles.put(recipient, fileName);
                                pendingTransferIds.put(recipient, transferId);
                                out.println("FILE_REQUEST|" + recipient + "|" + f.getName() + "|" + f.length() + "|" + transferId);
                            }
                        }
                    }
                }

            } else {
                System.out.println("\n" + line);
            }
        }

        currentOut[0] = null;
        try {
            socket.close();
        } catch (IOException ignored) {}
        throw new IOException("server closed the connection");
    }

    private static void runInteractiveInput(PrintWriter[] currentOut) {
        Scanner sc = new Scanner(System.in);
        while (true) {
            String input = sc.nextLine();
            PrintWriter out = currentOut[0];
            if (out == null) {
                continue;
            }

            if (input.startsWith("/send ")) {
                String[] parts = input.substring(6).split(" ", 2);
                if (parts.length != 2) {
                    System.out.println("usage: /send <username> <file_path>");
                    continue;
                }
                String targetUser = parts[0];
                String filePath = parts[1];

                java.io.File f = new java.io.File(filePath);
                if (!f.exists()) {
                    System.out.println("File not found: " + filePath);
                    continue;
                }

                String transferId = UUID.randomUUID().toString();
                pendingFiles.put(targetUser, filePath);
                pendingTransferIds.put(targetUser, transferId);
                out.println("FILE_REQUEST|" + targetUser + "|" + f.getName() + "|" + f.length() + "|" + transferId);
                System.out.println("Requested to send '" + f.getName() + "' to " + targetUser + ".");

            } else {
                out.println(input);
            }
        }
    }

    private static void ensureTestFilesExist() throws IOException {
        int multiplier = Integer.parseInt(System.getenv().getOrDefault("BOT_FILE_SIZE_MULTIPLIER", "1"));

        createIfMissing("bot_small.txt", 1024 * multiplier);
        createIfMissing("bot_medium.bin", 1024 * 1024 * multiplier);
        createIfMissing("bot_large.bin", 10 * 1024 * 1024 * multiplier);
    }

    private static void createIfMissing(String name, int size) throws IOException {
        java.io.File f = new java.io.File(name);
        if (!f.exists()) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            java.nio.file.Files.write(f.toPath(), data);
        }
    }
}