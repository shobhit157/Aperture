package com.shobhit.Network_lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client {

    private static final Random random = new Random();
    private static final List<String> knownUsers = new CopyOnWriteArrayList<>();

    private static final long RECONNECT_DELAY_START_SECONDS = 1;
    private static final long RECONNECT_DELAY_MAX_SECONDS = 30;
    private static final long HEALTHY_SESSION_SECONDS = 60;

    private static final long HEARTBEAT_INTERVAL_MS = 20_000;

    // Step 4: if the server goes silent for this long — no PONG, no chat,
    // nothing at all — the connection is treated as dead. Slightly
    // shorter than the server's own 60s timeout, so the client notices
    // first in the common case rather than waiting on the server to give
    // up on it.
    private static final int READ_TIMEOUT_MS = 45_000;

    // Process ownership: exit code used when another process took over
    // this username, so logs and Kubernetes show why the client stopped.
    private static final int EXIT_CODE_SUPERSEDED = 3;

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

    // Mesh v2 S2: all file-transfer bookkeeping (queues, limits, events,
    // screen output) lives in one place.
    private static final Transfers transfers = new Transfers();

    // Process ownership: thrown when the server says a newer session (from
    // another process) now owns this username. It extends IOException only
    // so runConnection's signature doesn't change — main() catches it
    // BEFORE the generic IOException, so it never reaches the reconnect loop.
    //
    // A normal reconnect can never receive this: the client always closes
    // its old socket before opening a new one, so SUPERSEDED can only reach
    // a connection that is still alive — i.e. a different process.
    private static class SupersededException extends IOException {
        SupersededException() {
            super("superseded by a newer session");
        }
    }

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

        // Stop peer-app whenever this JVM exits normally or on Ctrl+C, so no
        // orphaned peer-app is left running with our identity. (kill -9
        // can't be caught; that case needs peer-app to exit on its own
        // when its parent disappears — tracked separately.)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopPeerApp(peerProcess),
                "peer-app-shutdown"));

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

        transfers.init(currentOut, peerWriter);

        // Mesh v2 S2: peer-app events go to the transfer manager. Only lines
        // it doesn't handle are printed (startup info, errors).
        Thread peerListener = new Thread(() -> {
            try {
                String line;
                while ((line = peerOut.readLine()) != null) {
                    if (!transfers.onPeerEvent(line)) {
                        System.out.println("[peer-app] " + line);
                    }
                }
            } catch (IOException e) {
                // peer-app process ended
            }
        }, "peer-listener");
        peerListener.setDaemon(true);
        peerListener.start();

        // Mesh v2 S2: checks timeouts (sender never connected, request never
        // accepted) and starts queued transfers after a reconnect.
        Thread transferTicker = new Thread(() -> {
            while (!shuttingDown.get()) {
                try {
                    Thread.sleep(Transfers.TICK_MS);
                    transfers.tick();
                } catch (InterruptedException ignored) {}
            }
        }, "transfer-ticker");
        transferTicker.setDaemon(true);
        transferTicker.start();

        if (!isBot) {
            Thread inputThread = new Thread(() -> runInteractiveInput(currentOut));
            inputThread.setDaemon(true);
            inputThread.start();
        }

        Thread heartbeatThread = new Thread(() -> {
            while (!shuttingDown.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    PrintWriter out = currentOut[0];
                    if (out != null) {
                        out.println("PING");
                    }
                } catch (InterruptedException ignored) {}
            }
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        long[] currentDelaySeconds = { RECONNECT_DELAY_START_SECONDS };
        int[] attemptCounter = { 0 };

        while (!shuttingDown.get()) {
            attemptCounter[0]++;
            long[] sessionDurationSeconds = { 0 };
            try {
                runConnection(host, port, myUsername, finalMyEndpointId, finalMyRelayUrl,
                        isBot, currentOut, sessionDurationSeconds);
                break;
            } catch (SupersededException e) {
                // Process ownership: newest wins. Another process now owns
                // this username, so this one steps aside for good — no
                // reconnect (that would take the name back and the two
                // processes would keep kicking each other off).
                shuttingDown.set(true);
                System.out.println("\nUsername '" + myUsername
                        + "' is now in use by another session. Exiting.");
                // The shutdown hook stops peer-app on exit.
                System.exit(EXIT_CODE_SUPERSEDED);
            } catch (IOException e) {
                System.out.println("\nConnection lost (attempt " + attemptCounter[0]
                        + ", was connected for " + sessionDurationSeconds[0] + "s): " + e.getMessage());

                if (sessionDurationSeconds[0] >= HEALTHY_SESSION_SECONDS) {
                    currentDelaySeconds[0] = RECONNECT_DELAY_START_SECONDS;
                }

                System.out.println("Reconnecting in " + currentDelaySeconds[0] + "s...");
                try {
                    Thread.sleep(currentDelaySeconds[0] * 1000L);
                } catch (InterruptedException ignored) {}

                currentDelaySeconds[0] = Math.min(currentDelaySeconds[0] * 2, RECONNECT_DELAY_MAX_SECONDS);
            }
        }
    }

    // Stops peer-app politely, then forcibly if it doesn't exit in time.
    private static void stopPeerApp(Process peerProcess) {
        if (peerProcess == null || !peerProcess.isAlive()) return;
        peerProcess.destroy();
        try {
            if (!peerProcess.waitFor(3, TimeUnit.SECONDS)) {
                peerProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            peerProcess.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void runConnection(String host, int port, String myUsername,
                                       String myEndpointId, String myRelayUrl, boolean isBot,
                                       PrintWriter[] currentOut,
                                       long[] sessionDurationSeconds) throws IOException {

        Socket socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), 5000);

        // Step 4: if the server goes silent for this long, in.readLine()
        // below throws SocketTimeoutException, which is an IOException and
        // flows straight into the existing reconnect logic in main() —
        // no changes needed there at all.
        socket.setSoTimeout(READ_TIMEOUT_MS);

        long connectedAtMillis = System.currentTimeMillis();
        System.out.println("Connected to server!");

        try {
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
                System.out.println("  /status                                - show active and queued transfers");
                System.out.println("  !chat <all|username>                   - tell bots to send a chat message");
                System.out.println("  !send <bot|all> <recipient|all> <small|medium|large> - tell bots to send a file");
            }

            String line;
            while ((line = in.readLine()) != null) {

                if (line.equals("PONG")) {
                    continue;
                }

                if (line.equals("SUPERSEDED")) {
                    // Process ownership: a newer session (another process)
                    // owns this username. The finally below closes only this
                    // socket; main() then exits without reconnecting.
                    throw new SupersededException();
                }

                if (line.startsWith("ONLINE|")) {
                    // Step 5: full snapshot from the server on every
                    // (re)connect. REPLACE the list, don't patch it.
                    List<String> fresh = new ArrayList<>();
                    for (String u : line.substring("ONLINE|".length()).split(",")) {
                        String name = u.trim();
                        if (!name.isEmpty() && !name.equals(myUsername)) {
                            fresh.add(name);
                        }
                    }
                    knownUsers.clear();
                    knownUsers.addAll(fresh);
                    if (!isBot) {
                        System.out.println("Online now: "
                                + (fresh.isEmpty() ? "(nobody else)" : String.join(", ", fresh)));
                    }
                    continue;
                }

                if (line.startsWith(">>> ") && line.contains(" joined the chat")) {
                    String who = line.substring(4, line.indexOf(" joined the chat"));
                    if (!who.equals(myUsername) && !knownUsers.contains(who)) {
                        knownUsers.add(who);
                    }
                } else if (line.startsWith("<<< ") && line.contains(" left the chat")) {
                    String who = line.substring(4, line.indexOf(" left the chat"));
                    knownUsers.remove(who);
                    // Mesh v2 S2: anything waiting on that user can't happen now.
                    transfers.onUserLeft(who);
                }

                if (line.startsWith("FILE_REQUEST|")) {
                    // FILE_REQUEST|<from>|<filename>|<size>|<transferId>
                    String[] parts = line.split("\\|", 5);
                    String from = parts[1];
                    String name = parts.length > 2 ? parts[2] : "file";
                    long size = parts.length > 3 ? parseLong(parts[3]) : 0;
                    String transferId = parts.length > 4 ? parts[4] : UUID.randomUUID().toString();
                    transfers.onIncomingRequest(transferId, from, name, size);

                } else if (line.startsWith("PEER_INFO|")) {
                    // PEER_INFO|<receiver>|<endpointId>|<relayUrl>|<transferId>
                    String[] parts = line.split("\\|", 5);
                    String receiverEndpointId = parts.length > 2 ? parts[2] : "";
                    String receiverRelayUrl = parts.length > 3 ? parts[3] : "";
                    String transferId = parts.length > 4 ? parts[4] : "";
                    transfers.onPeerInfo(transferId, receiverEndpointId, receiverRelayUrl);

                } else if (line.startsWith("FILE_REJECT|")) {
                    String who = line.substring("FILE_REJECT|".length());
                    transfers.onRejected(who);

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

                            // Mesh v2 S2: queued — this bot sends one at a time.
                            for (String recipient : recipients) {
                                transfers.queueSend(recipient, fileName);
                            }
                        }
                    }

                } else if (!line.startsWith("FILE_ACCEPT|")) {
                    System.out.println(line);
                }
            }

            throw new IOException("server closed the connection");

        } finally {
            sessionDurationSeconds[0] = (System.currentTimeMillis() - connectedAtMillis) / 1000;
            currentOut[0] = null;
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private static void runInteractiveInput(PrintWriter[] currentOut) {
        Scanner sc = new Scanner(System.in);
        while (true) {
            String input = sc.nextLine();

            if (input.equals("/status")) {
                transfers.printStatus();
                continue;
            }

            PrintWriter out = currentOut[0];
            if (out == null) {
                System.out.println("(not connected - try again in a moment)");
                continue;
            }

            if (input.startsWith("/send ")) {
                String[] parts = input.substring(6).split(" ", 2);
                if (parts.length != 2) {
                    System.out.println("usage: /send <username> <file_path>");
                    continue;
                }
                transfers.queueSend(parts[0], parts[1]);
            } else {
                out.println(input);
            }
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
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

    // =====================================================================
    // Mesh v2 S2: file transfers — 1 sending + 1 receiving at a time.
    //
    // Everything is keyed by TRANSFER ID (never by username), so two files
    // to the same user can't be mixed up. Extra transfers wait in a queue.
    //
    // Events to the server use one message type, with key=value fields:
    //   TRANSFER_EVENT|id=<id>|state=<path|progress|done|failed>|path=..|pct=..|reason=..
    // The server creates the "started" state itself when it sees FILE_ACCEPT.
    // =====================================================================
    static class Transfers {

        static final long TICK_MS = 5_000;
        // Accepted, but the sender's peer-app never connected.
        static final long ACCEPT_TIMEOUT_MS = 60_000;
        // Our request was never accepted (receiver busy for a long time).
        static final long REQUEST_TIMEOUT_MS = 15 * 60_000;
        // Progress to the server: receiver side only, at most every 3 s.
        static final long PROGRESS_FORWARD_MS = 3_000;

        enum Dir { SEND, RECEIVE }

        static class Transfer {
            final String id;
            final Dir dir;
            final String peer;      // the other user
            final String name;      // file name (for the screen)
            final String filePath;  // SEND only
            final long size;
            String phase = "queued"; // queued -> requested/accepted -> moving
            String path = "";
            int pct = 0;
            int lastMilestone = 0;
            long lastActivity = System.currentTimeMillis();
            long lastProgressSent = 0;

            Transfer(String id, Dir dir, String peer, String name, String filePath, long size) {
                this.id = id;
                this.dir = dir;
                this.peer = peer;
                this.name = name;
                this.filePath = filePath;
                this.size = size;
            }
        }

        private final Object lock = new Object();
        private final Map<String, Transfer> byId = new ConcurrentHashMap<>();
        private final Deque<Transfer> sendQueue = new ArrayDeque<>();
        private final Deque<Transfer> receiveQueue = new ArrayDeque<>();
        private Transfer activeSend;
        private Transfer activeReceive;

        private PrintWriter[] currentOut;
        private PrintWriter peerWriter;

        void init(PrintWriter[] currentOut, PrintWriter peerWriter) {
            this.currentOut = currentOut;
            this.peerWriter = peerWriter;
        }

        // ---------------- sending ----------------

        void queueSend(String target, String filePath) {
            java.io.File f = new java.io.File(filePath);
            if (!f.isFile()) {
                System.out.println("File not found: " + filePath);
                return;
            }
            Transfer t = new Transfer(UUID.randomUUID().toString(), Dir.SEND, target,
                    f.getName(), filePath, f.length());
            synchronized (lock) {
                byId.put(t.id, t);
                sendQueue.addLast(t);
                if (activeSend != null) {
                    say("[send] " + t.name + " -> " + t.peer + "   queued (position " + sendQueue.size() + ")");
                }
                startNextSend();
            }
        }

        // Caller holds lock.
        private void startNextSend() {
            if (activeSend != null || sendQueue.isEmpty()) return;
            PrintWriter out = out();
            if (out == null) return; // not connected — the ticker retries
            Transfer t = sendQueue.pollFirst();
            activeSend = t;
            t.phase = "requested";
            t.lastActivity = System.currentTimeMillis();
            out.println("FILE_REQUEST|" + t.peer + "|" + t.name + "|" + t.size + "|" + t.id);
            say("[send] " + t.name + " -> " + t.peer + "   requested (" + human(t.size) + ")");
        }

        void onPeerInfo(String id, String endpointId, String relayUrl) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || t != activeSend || !t.phase.equals("requested")) return;
                t.phase = "accepted";
                t.lastActivity = System.currentTimeMillis();
                String relay = (relayUrl == null || relayUrl.isBlank() || relayUrl.equals("null")) ? "-" : relayUrl;
                peerWriter.println("sendto " + t.id + " " + endpointId + " " + relay + " " + t.filePath);
                say("[send] " + t.name + " -> " + t.peer + "   started");
            }
        }

        void onRejected(String who) {
            synchronized (lock) {
                if (activeSend != null && activeSend.peer.equals(who) && activeSend.phase.equals("requested")) {
                    finish(activeSend, false, "rejected by " + who, false);
                }
            }
        }

        // ---------------- receiving ----------------

        void onIncomingRequest(String id, String from, String name, long size) {
            synchronized (lock) {
                if (byId.containsKey(id)) return; // duplicate
                Transfer t = new Transfer(id, Dir.RECEIVE, from, name, null, size);
                byId.put(id, t);
                if (activeReceive == null) {
                    accept(t);
                } else {
                    receiveQueue.addLast(t);
                    say("[recv] " + name + " <- " + from + "   waiting (position " + receiveQueue.size() + ")");
                }
            }
        }

        // Caller holds lock.
        private void accept(Transfer t) {
            PrintWriter out = out();
            if (out == null) {
                receiveQueue.addFirst(t); // not connected — the ticker retries
                return;
            }
            activeReceive = t;
            t.phase = "accepted";
            t.lastActivity = System.currentTimeMillis();
            out.println("FILE_ACCEPT|" + t.peer + "|" + t.id);
            say("[recv] " + t.name + " <- " + t.peer + "   accepted (" + human(t.size) + ")");
        }

        // Caller holds lock.
        private void startNextReceive() {
            if (activeReceive != null || receiveQueue.isEmpty()) return;
            accept(receiveQueue.pollFirst());
        }

        // ---------------- events from peer-app ----------------

        /** Returns true if the line was a transfer event (handled here). */
        boolean onPeerEvent(String line) {
            if (line.startsWith("EVENT:PROGRESS:")) {
                // EVENT:PROGRESS:<sending|receiving>|<name>|<pct>|<bytes>|<total>|<id>
                String[] p = line.substring("EVENT:PROGRESS:".length()).split("\\|");
                if (p.length >= 6) onProgress(p[5], parseInt(p[2]));
                return true;
            }
            if (line.startsWith("EVENT:CONNECTION_PATH:")) {
                String[] kv = idAndRest(line, "EVENT:CONNECTION_PATH:");
                onPath(kv[0], kv[1]);
                return true;
            }
            if (line.startsWith("EVENT:TRANSFER_PATH:")) {
                // Receiver only, only after success: the one "done" signal.
                String[] kv = idAndRest(line, "EVENT:TRANSFER_PATH:");
                synchronized (lock) {
                    Transfer t = byId.get(kv[0]);
                    if (t != null) {
                        t.path = kv[1];
                        sendEvent(t.id, "done", "path=" + kv[1], "pct=100");
                        finish(t, true, null, false);
                    }
                }
                return true;
            }
            if (line.startsWith("EVENT:TRANSFER_FAILED:")) {
                // Whichever side knows the failure reports it — once.
                String[] kv = idAndRest(line, "EVENT:TRANSFER_FAILED:");
                synchronized (lock) {
                    Transfer t = byId.get(kv[0]);
                    sendEvent(kv[0], "failed", "reason=" + kv[1]);
                    if (t != null) finish(t, false, kv[1], false);
                }
                return true;
            }
            if (line.startsWith("EVENT:FILE_SENT:")) {
                String[] kv = idAndRest(line, "EVENT:FILE_SENT:");
                synchronized (lock) {
                    Transfer t = byId.get(kv[0]);
                    if (t != null) finish(t, true, null, false);
                }
                return true;
            }
            if (line.startsWith("EVENT:FILE_SEND_FAILED:")) {
                // The receiver already reported this failure — no event.
                String[] kv = idAndRest(line, "EVENT:FILE_SEND_FAILED:");
                synchronized (lock) {
                    Transfer t = byId.get(kv[0]);
                    if (t != null) finish(t, false, kv[1], false);
                }
                return true;
            }
            if (line.startsWith("EVENT:FILE_RECEIVED:")) {
                // EVENT:FILE_RECEIVED:<id>:<saved_path>|<bytes>
                String[] kv = idAndRest(line, "EVENT:FILE_RECEIVED:");
                String saved = kv[1].contains("|") ? kv[1].substring(0, kv[1].lastIndexOf('|')) : kv[1];
                synchronized (lock) {
                    Transfer t = byId.get(kv[0]);
                    if (t != null) say("[recv] " + t.name + " <- " + t.peer + "   saved as " + saved);
                }
                return true;
            }
            // Quiet events: useful in logs, too noisy for the screen.
            return line.startsWith("EVENT:FILE_HASH:")
                    || line.startsWith("EVENT:TRANSFER_TIMING:");
        }

        private void onProgress(String id, int pct) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null) return;
                t.phase = "moving";
                t.pct = pct;
                t.lastActivity = System.currentTimeMillis();

                // Screen: 25 / 50 / 75 % milestones only.
                int milestone = (pct / 25) * 25;
                if (milestone > t.lastMilestone && milestone < 100) {
                    t.lastMilestone = milestone;
                    say(arrow(t) + "   " + milestone + "%");
                }

                // Server: receiver side only, at most every 3 s.
                long now = System.currentTimeMillis();
                if (t.dir == Dir.RECEIVE && now - t.lastProgressSent >= PROGRESS_FORWARD_MS) {
                    t.lastProgressSent = now;
                    sendEvent(t.id, "progress", "pct=" + pct);
                }
            }
        }

        private void onPath(String id, String path) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null) return;
                t.lastActivity = System.currentTimeMillis();
                if (t.phase.equals("accepted")) t.phase = "moving";
                String before = t.path;
                t.path = path;
                say(arrow(t) + "   " + (before.isEmpty() ? "path: " + path : "path: " + before + " -> " + path));
                sendEvent(t.id, "path", "path=" + path);
            }
        }

        // ---------------- ending a transfer ----------------

        // Caller holds lock. Frees the slot and starts the next queued one.
        // reportFailure: also send a "failed" event (for failures only this
        // client knows about, e.g. the sender never connected).
        private void finish(Transfer t, boolean ok, String reason, boolean reportFailure) {
            if (byId.remove(t.id) == null) return; // already finished
            if (!ok && reportFailure) {
                sendEvent(t.id, "failed", "reason=" + reason);
            }
            if (ok) {
                say(arrow(t) + "   done OK" + (t.path.isEmpty() ? "" : " (" + t.path + ")"));
            } else {
                say(arrow(t) + "   FAILED " + (reason == null ? "" : reason));
            }
            sendQueue.remove(t);
            receiveQueue.remove(t);
            if (t == activeSend) {
                activeSend = null;
                startNextSend();
            }
            if (t == activeReceive) {
                activeReceive = null;
                startNextReceive();
            }
        }

        void onUserLeft(String who) {
            synchronized (lock) {
                for (Transfer t : new ArrayList<>(byId.values())) {
                    if (!t.peer.equals(who)) continue;
                    boolean waiting = t.phase.equals("queued") || t.phase.equals("requested");
                    if (waiting) {
                        finish(t, false, who + " left", false);
                    }
                    // Transfers already moving end on their own: peer-app
                    // reports the failure, and the server marks it too.
                }
            }
        }

        // Called every TICK_MS by the ticker thread.
        void tick() {
            synchronized (lock) {
                long now = System.currentTimeMillis();
                if (activeReceive != null && activeReceive.phase.equals("accepted")
                        && now - activeReceive.lastActivity > ACCEPT_TIMEOUT_MS) {
                    finish(activeReceive, false, "sender never connected", true);
                }
                if (activeSend != null && activeSend.phase.equals("requested")
                        && now - activeSend.lastActivity > REQUEST_TIMEOUT_MS) {
                    finish(activeSend, false, "request not accepted in time", false);
                }
                // After a reconnect, start anything that waited for a connection.
                startNextSend();
                startNextReceive();
            }
        }

        // ---------------- /status ----------------

        void printStatus() {
            synchronized (lock) {
                StringBuilder sb = new StringBuilder();
                sb.append("  [send] sending:   ").append(describe(activeSend))
                  .append("   (queue: ").append(sendQueue.size()).append(")\n");
                for (Transfer t : sendQueue) {
                    sb.append("      waiting: ").append(t.name).append(" -> ").append(t.peer).append("\n");
                }
                sb.append("  [recv] receiving: ").append(describe(activeReceive))
                  .append("   (queue: ").append(receiveQueue.size()).append(")\n");
                for (Transfer t : receiveQueue) {
                    sb.append("      waiting: ").append(t.name).append(" <- ").append(t.peer).append("\n");
                }
                System.out.print(sb);
            }
        }

        private String describe(Transfer t) {
            if (t == null) return "none";
            String dirArrow = t.dir == Dir.SEND ? " -> " : " <- ";
            return t.name + dirArrow + t.peer + "  " + t.phase
                    + (t.pct > 0 ? " " + t.pct + "%" : "")
                    + (t.path.isEmpty() ? "" : " (" + t.path + ")");
        }

        // ---------------- helpers ----------------

        private PrintWriter out() {
            return currentOut == null ? null : currentOut[0];
        }

        private void sendEvent(String id, String state, String... fields) {
            PrintWriter out = out();
            if (out == null) return;
            StringBuilder sb = new StringBuilder("TRANSFER_EVENT|id=").append(id).append("|state=").append(state);
            for (String f : fields) {
                sb.append('|').append(f.replace('|', ' ').replace('\n', ' '));
            }
            out.println(sb);
        }

        private static String arrow(Transfer t) {
            return t.dir == Dir.SEND
                    ? "[send] " + t.name + " -> " + t.peer
                    : "[recv] " + t.name + " <- " + t.peer;
        }

        private static void say(String msg) {
            System.out.println(msg);
        }

        private static String[] idAndRest(String line, String prefix) {
            String rest = line.substring(prefix.length());
            int sep = rest.indexOf(':');
            return sep >= 0
                    ? new String[] { rest.substring(0, sep), rest.substring(sep + 1) }
                    : new String[] { rest, "" };
        }

        private static int parseInt(String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String human(long bytes) {
            if (bytes >= 1024L * 1024) return String.format("%.1f MB", bytes / 1048576.0);
            if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
            return bytes + " B";
        }
    }
}