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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

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
    

    // B3d: peer-app never got ready (crashed, or no network): exit code + time limit.
    private static final int EXIT_CODE_PEER_FAILED = 4;
    private static final long PEER_START_TIMEOUT_MS = 60_000;
    

    // B3d: stop reconnecting when the server stays unreachable this long.
    // Safe: peer-app keeps transfers on disk; B3c resumes them on the next start.
    // RECONNECT_GIVE_UP_SECS overrides it (handy for testing).
    private static final long GIVE_UP_AFTER_MS =
            Transfers.envInt("RECONNECT_GIVE_UP_SECS", 10 * 60) * 1000L;
    private static final int EXIT_CODE_SERVER_UNREACHABLE = 5;
    

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
        
        // B3d: peer-app must report its endpoint and relay before we log in.
        // If it dies, or hangs (e.g. no network), stop with a clear message
        // instead of logging in with "endpoint: null".
        Deque<String> lastPeerLines = new ArrayDeque<>(); // shown if startup fails
        AtomicBoolean peerReady = new AtomicBoolean(false);
        Thread startWatchdog = new Thread(() -> {
            try {
                Thread.sleep(PEER_START_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return; // peer-app got ready in time
            }
            if (!peerReady.get()) {
                System.out.println("peer-app did not get ready in " + (PEER_START_TIMEOUT_MS / 1000)
                        + "s, stopping it.");
                // Kill its child processes too: a child that still holds the
                // output pipe would keep readLine() below waiting.
                peerProcess.descendants().forEach(ProcessHandle::destroyForcibly);
                peerProcess.destroyForcibly(); // readLine() below then returns null
            }
        }, "peer-start-watchdog");
        startWatchdog.setDaemon(true);
        startWatchdog.start();

        while ((peerLine = peerOut.readLine()) != null) {
            if (peerLine.startsWith("EVENT:ENDPOINT_READY:")) {
                myEndpointId = peerLine.substring("EVENT:ENDPOINT_READY:".length());
            } else if (peerLine.startsWith("EVENT:RELAY_READY:")) {
                myRelayUrl = peerLine.substring("EVENT:RELAY_READY:".length());
            } else if (peerLine.startsWith("EVENT:OFFER_DROPPED:")) {
                // B3c: saved offers are checked before the endpoint is ready.
                // A dropped one (file changed / gone) is remembered, so the
                // restart check can tell the receiver why.
                transfers.onPeerEvent(peerLine);
            }
            
            else {
                // B3d: keep the last few lines, in case startup fails.
                lastPeerLines.addLast(peerLine);
                if (lastPeerLines.size() > 5) lastPeerLines.removeFirst();
            }
            
            if (myEndpointId != null && myRelayUrl != null) {
                peerReady.set(true);
                break;
            }
        }
        startWatchdog.interrupt();

        if (!peerReady.get()) {
            System.out.println("Could not start peer-app: "
                    + (myEndpointId == null ? "no endpoint" : "no relay") + ". Last output:");
            for (String l : lastPeerLines) {
                System.out.println("  [peer-app] " + l);
            }
            // The shutdown hook stops peer-app if it is still running.
            System.exit(EXIT_CODE_PEER_FAILED);
        }
        System.out.println("Local peer-app ready, endpoint: " + myEndpointId + ", relay: " + myRelayUrl);

        final String finalMyEndpointId = myEndpointId;
        final String finalMyRelayUrl = myRelayUrl;
        final String myUsername = username;
        final boolean isBot = botMode;
        final AtomicBoolean shuttingDown = new AtomicBoolean(false);

        final PrintWriter[] currentOut = new PrintWriter[1];

        // B3b: transfers can ask "is this user online?" (for resuming).
        // B3c: and who we are (sender or receiver of a restored transfer).
        transfers.init(currentOut, peerWriter, knownUsers::contains, myUsername);

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
        
        long[] offlineSince = { 0 }; // B3d: when the current outage began (0 = online)

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
                
                transfers.onDisconnected(); // B3d: warn once; results wait in the outbox

                if (sessionDurationSeconds[0] >= HEALTHY_SESSION_SECONDS) {
                    currentDelaySeconds[0] = RECONNECT_DELAY_START_SECONDS;
                    offlineSince[0] = 0; // B3d: it was a good session: new outage
                }

                // B3d: give up if the server has been unreachable for too long.
                long now = System.currentTimeMillis();
                if (offlineSince[0] == 0) offlineSince[0] = now;
                long offlineMs = now - offlineSince[0];
                if (offlineMs >= GIVE_UP_AFTER_MS) {
                    shuttingDown.set(true);
                    System.out.println("Server unreachable for " + (offlineMs / 1000) + "s, giving up."
                            + " Saved transfers resume when you start the client again.");
                    // The shutdown hook stops peer-app.
                    System.exit(EXIT_CODE_SERVER_UNREACHABLE);
                }

                System.out.println("Reconnecting in " + currentDelaySeconds[0] + "s... (offline "
                        + (offlineMs / 1000) + "s, giving up after " + (GIVE_UP_AFTER_MS / 1000) + "s)");
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
            	 printHelp();
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
                    // B3d: print who is online FIRST, then resume transfers,
                    // so "is back" never shows before the user list.
                    if (!isBot) {
                        System.out.println("Online now: "
                                + (fresh.isEmpty() ? "(nobody else)" : String.join(", ", fresh)));
                    }
                    // B3c: a new login; MY_TRANSFER lines follow.
                    transfers.onLogin();
                    // B3b: anyone we were waiting for may be back.
                    for (String u : fresh) {
                        transfers.onUserBack(u);
                    }
                    continue;
                }

                if (line.startsWith(">>> ") && line.contains(" joined the chat")) {
                    String who = line.substring(4, line.indexOf(" joined the chat"));
                    if (!who.equals(myUsername) && !knownUsers.contains(who)) {
                        knownUsers.add(who);
                    }
                    // B3d: show the JOIN line first, then "is back" + resume events.
                    System.out.println(line);
                    // B3b: "sender back online" — resume waiting fetches from them.
                    if (!who.equals(myUsername)) {
                        transfers.onUserBack(who);
                    }
                    continue; // already printed
                }

                if (line.startsWith("<<< ") && line.contains(" left the chat")) {
                    String who = line.substring(4, line.indexOf(" left the chat"));
                    knownUsers.remove(who);
                    // B3d: show the LEFT line first, then what it means for transfers.
                    System.out.println(line);
                    // Mesh v2 S2: anything waiting on that user can't happen now.
                    transfers.onUserLeft(who);
                    continue; // already printed
                }

                if (line.startsWith("FILE_REQUEST|")) {
                    // FILE_REQUEST|<from>|<filename>|<size>|<id>|<hash>|<senderEndpoint>|<senderRelay>
                    // B3a: hash is empty for meta3 (then it's a push).
                    String[] parts = line.split("\\|", -1);
                    String from = parts[1];
                    String name = parts.length > 2 ? parts[2] : "file";
                    long size = parts.length > 3 ? parseLong(parts[3]) : 0;
                    String transferId = parts.length > 4 && !parts[4].isEmpty() ? parts[4] : UUID.randomUUID().toString();
                    String hash = parts.length > 5 ? parts[5] : "";
                    String senderEndpoint = parts.length > 6 ? parts[6] : "";
                    transfers.onIncomingRequest(transferId, from, name, size, hash, senderEndpoint);

                } else if (line.startsWith("FILE_READY|")) {
                    // B3a: FILE_READY|<sender>|<id> — fetch now.
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) {
                        transfers.onFileReady(parts[1], parts[2]);
                    }

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

                } else if (line.startsWith("MY_TRANSFER|")) {
                    // B3c: MY_TRANSFER|<id>|<state>|<from>|<to>|<pct> — one of our
                    // active transfers, as the server sees it (sent on login).
                    transfers.onMyTransfer(line);

                } else if (line.startsWith("MY_TRANSFERS_END|")) {
                    // B3c: the server's list is complete.
                    transfers.onMyTransfersEnd();

                } else if (line.startsWith("TRANSFER_END|")) {
                    // B3b: TRANSFER_END|<from>|<id>|<state>|<reason>
                    String[] parts = line.split("\\|", 5);
                    if (parts.length >= 4) {
                        transfers.onTransferEnd(parts[1], parts[2], parts[3],
                                parts.length > 4 ? parts[4] : "");
                    }

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

                            // B3a: queued — at most MAX_SENDS are prepared at once.
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
        while (sc.hasNextLine()) {
            // B3d: ignore spaces around the input (" /status " works too).
            String input = sc.nextLine().trim();
            if (input.isEmpty()) continue; // nothing to send

            if (input.equals("/status")) {
                transfers.printStatus();
                continue;
            }
            if (input.equals("/help")) {
                printHelp();
                continue;
            }

            boolean isSend = input.equals("/send") || input.startsWith("/send ");
            // B3d: a typo like "/stauts" must not go out as a chat message.
            if (input.startsWith("/") && !isSend) {
                System.out.println("unknown command: " + input.split("\\s+")[0] + "  (type /help)");
                continue;
            }

            PrintWriter out = currentOut[0];
            if (out == null) {
                System.out.println("(not connected - try again in a moment)");
                continue;
            }

            if (isSend) {
                // "/send  admin2   my file.bin" -> ["admin2", "my file.bin"]
                String[] parts = input.substring("/send".length()).trim().split("\\s+", 2);
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

    // B3d: one place for the command list (shown on connect and on /help).
    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  <message>                              - send a chat message");
        System.out.println("  /send <username> <file_path>           - send a file to a user");
        System.out.println("  /status                                - show active and queued transfers");
        System.out.println("  /help                                  - show this list");
        System.out.println("  !chat <all|username>                   - tell bots to send a chat message");
        System.out.println("  !send <bot|all> <recipient|all> <small|medium|large> - tell bots to send a file");
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
    // File transfers (Mesh v2 S2, B3a).
    //
    // Everything is keyed by TRANSFER ID (never by username), so two files
    // to the same user can't be mixed up. Extra transfers wait in a queue.
    //
    // B3a: two ways to move a file, chosen by the SENDER:
    //   blobs (default): share -> FILE_REQUEST with hash -> (accept) ->
    //                    PEER_INFO -> allow -> FILE_READY -> receiver fetches
    //   meta3 (fallback, TRANSFER_PROTOCOL=meta3): FILE_REQUEST without hash
    //                    -> (accept) -> PEER_INFO -> sender pushes (sendto)
    // A receiver handles both: a request WITH a hash is blobs.
    //
    // Limits: at most MAX_SENDS being prepared/requested and MAX_RECEIVES
    // being received at the same time (default 2 + 2). A blobs sender's
    // slot is freed after FILE_READY: from then on its peer-app serves the
    // file by itself, so the sender doesn't need a slot.
    //
    // B3b: sender gone mid-fetch. peer-app tries 3 times (FETCH_RETRYING,
    // shown as "reconnecting n/3" on the mesh), then FETCH_WAITING: the partial data is kept and the transfer goes to
    // phase "waiting" — it gives its receive slot back and the server shows
    // "waiting for sender". When the sender is online again (JOIN, ONLINE
    // list, or the ticker noticing) we send `retry <id>`: it continues from
    // the chunks already on disk. No free slot -> "resume-queued", resumed
    // before any new request. After the TTL peer-app expires it by itself
    // (TRANSFER_FAILED).
    //
    // B3c: restart sync. Java keeps transfers only in memory, peer-app keeps
    // them on disk (offers.json / fetches.json), the server keeps them in
    // Redis. After a restart peer-app reports what it restored
    // (RESTORED_OFFER / RESTORED_FETCH, then RESTORED) and does NOT start
    // the fetches; the server sends our active transfers on login
    // (MY_TRANSFER lines, then MY_TRANSFERS_END). When both lists are in,
    // they are compared ONCE (syncRestart):
    //   both have it        -> resume: receiver sends `retry`, sender keeps sharing
    //   only peer-app has it -> delete: `cancel` (receiver) / `unshare` (sender)
    //   only server has it   -> report failed + TRANSFER_END failed to the
    //                           other side ("file changed", "lost local data",
    //                           "restarted before the transfer began")
    // Transfers Java already knows are never compared: on a plain reconnect
    // (no restart) Java still knows them all, and peer-app's restored list
    // is only from its start (E1).
    //
    // Events to the server use one message type, with key=value fields:
    //   TRANSFER_EVENT|id=<id>|state=<path|progress|retrying|waiting|done|failed>|path=..|pct=..|reason=..|secs=..|retry=..
    // The server creates the "started" state itself when it sees FILE_ACCEPT.
    // =====================================================================
    static class Transfers {

        static final long TICK_MS = 5_000;
        // Accepted, but the sender never connected (meta3) / never got ready (blobs).
        static final long ACCEPT_TIMEOUT_MS = 60_000;
        // Our request was never accepted (receiver busy for a long time).
        static final long REQUEST_TIMEOUT_MS = 15 * 60_000;
        // B3a: peer-app is hashing the file (big files take a while).
        static final long SHARE_TIMEOUT_MS = 10 * 60_000;
        // B3a: allow sent, no ALLOWED back.
        static final long ALLOW_TIMEOUT_MS = 60_000;
        // Progress to the server: receiver side only, at most every 3 s.
        static final long PROGRESS_FORWARD_MS = 3_000;
        // B3b: a waiting transfer whose sender is online is retried at most
        // this often by the ticker (in case its JOIN was missed, or the
        // sender's peer-app wasn't serving yet when we last tried).
        static final long RESUME_RETRY_MS = 60_000;
        

        // B3d: how long peer-app keeps an offer (same env var peer-app reads).
        static final long TRANSFER_TTL_MS = envInt("PEER_TRANSFER_TTL_SECS", 30 * 60) * 1000L;

        // B3a: settings (environment variables).
        static final boolean USE_BLOBS =
                !"meta3".equalsIgnoreCase(System.getenv().getOrDefault("TRANSFER_PROTOCOL", "blobs"));
        static final int MAX_SENDS = envInt("MAX_SENDS", 2);
        static final int MAX_RECEIVES = envInt("MAX_RECEIVES", 2);

        enum Dir { SEND, RECEIVE }

        static class Transfer {
            final String id;
            final Dir dir;
            final String peer;      // the other user
            final String name;      // file name (no '|', safe for messages)
            final String filePath;  // SEND only (absolute)
            final long size;
            // send:    queued -> sharing -> requested -> allowing -> serving   (blobs)
            //          queued -> requested -> accepted -> moving               (meta3)
            // receive: queued -> accepted -> fetching / moving
            //          B3b: moving -> (retrying) -> waiting -> (resume-queued) -> fetching -> moving
            String phase = "queued";
            String hash = "";           // blobs: from SHARED (send) or from the request (receive)
            String senderEndpoint = ""; // receive, blobs: where to fetch from
            boolean shared = false;     // send, blobs: peer-app holds an offer for it
            String path = "";
            int pct = 0;
            int lastMilestone = 0;
            long lastActivity = System.currentTimeMillis();
            long lastProgressSent = 0;
            int pctSent = 0;            // B3d: last % the server got (receive side)
            long lastResumeAt = 0;      // B3b: last time we sent `retry` for it
            boolean restored = false;   // B3c: came back after a restart
            
            long sharedAt = 0;          // B3d: send, when peer-app's offer started (0 = unknown)
            boolean peerAway = false;   // B3d: send, the receiver left while we serve
            
            long lockedUntil = 0;       // B3d: receive, waiting: when peer-app gives up (0 = unknown)
            long resumedShown = -1;     // B3d: bytes in the last "resuming from" line (print each value once)

            Transfer(String id, Dir dir, String peer, String name, String filePath, long size) {
                this.id = id;
                this.dir = dir;
                this.peer = peer;
                this.name = name;
                this.filePath = filePath;
                this.size = size;
            }

            boolean isBlobs() {
                return !hash.isEmpty();
            }
        }

        private final Object lock = new Object();
        private final Map<String, Transfer> byId = new ConcurrentHashMap<>();
        private final Deque<Transfer> sendQueue = new ArrayDeque<>();
        private final Deque<Transfer> receiveQueue = new ArrayDeque<>();
        private final List<Transfer> activeSends = new ArrayList<>();
        private final List<Transfer> activeReceives = new ArrayList<>();

        private PrintWriter[] currentOut;
        private PrintWriter peerWriter;
        private Predicate<String> isOnline = u -> false;
        private String me = "";

        // B3c: one item peer-app restored from disk, waiting for the check.
        static class Restored {
            final String id;
            final boolean offer;   // true: we share it (sender), false: we fetch it (receiver)
            final String hash;
            final long size;
            final long bytesOnDisk;
            final String name;

            Restored(String id, boolean offer, String hash, long size, long bytesOnDisk, String name) {
                this.id = id;
                this.offer = offer;
                this.hash = hash;
                this.size = size;
                this.bytesOnDisk = bytesOnDisk;
                this.name = name;
            }
        }

        // B3c: restart sync state (all under lock).
        private final Map<String, Restored> restored = new LinkedHashMap<>();
        private final Map<String, String> droppedAtStart = new HashMap<>(); // id -> why peer-app dropped it
        private final Map<String, String> droppedNames = new HashMap<>();   // B3d: id -> file name (for messages)
        private boolean restoreListDone = false;           // EVENT:RESTORED seen
        private List<String[]> serverBuilding = new ArrayList<>();
        private List<String[]> serverList = null;          // complete MY_TRANSFERS list
        private boolean synced = false;                    // compared once, never again
        

        // B3d: server lost. Final events (done / failed / TRANSFER_END) made
        // while offline wait in the outbox and are sent after the next login,
        // so the server's record ends up right (D1 dashboard, history).
        static final int OUTBOX_MAX = 500;
        private final Deque<String[]> outbox = new ConcurrentLinkedDeque<>(); // {id, line}
        private boolean offline = false;       // lost the server since the last login
        private boolean loggedInOnce = false;  // no "back online" before the first login

        void init(PrintWriter[] currentOut, PrintWriter peerWriter, Predicate<String> isOnline, String me) {
            this.currentOut = currentOut;
            this.peerWriter = peerWriter;
            this.isOnline = isOnline;
            this.me = me == null ? "" : me;
            say("[transfers] protocol=" + (USE_BLOBS ? "blobs" : "meta3")
                    + " max_sends=" + MAX_SENDS + " max_receives=" + MAX_RECEIVES);
        }

        // ---------------- sending ----------------

        void queueSend(String target, String filePath) {
            java.io.File f = new java.io.File(filePath);
            if (!f.isFile()) {
                System.out.println("File not found: " + filePath);
                return;
            }
            // '|' separates message fields, so it can't be part of a name.
            String safeName = f.getName().replace('|', '_');
            Transfer t = new Transfer(UUID.randomUUID().toString(), Dir.SEND, target,
                    safeName, f.getAbsolutePath(), f.length());
            synchronized (lock) {
                byId.put(t.id, t);
                sendQueue.addLast(t);
                if (activeSends.size() >= MAX_SENDS) {
                    say("[send] " + t.name + " -> " + t.peer + "   queued (position " + sendQueue.size() + ")");
                }
                startNextSend();
            }
        }

        // Caller holds lock.
        private void startNextSend() {
            while (activeSends.size() < MAX_SENDS && !sendQueue.isEmpty()) {
                PrintWriter out = out();
                if (out == null) return; // not connected — the ticker retries
                Transfer t = sendQueue.pollFirst();
                activeSends.add(t);
                t.lastActivity = System.currentTimeMillis();
                if (USE_BLOBS) {
                    // B3a step 1: hash the file first; FILE_REQUEST goes out on SHARED.
                    t.phase = "sharing";
                    t.shared = true;
                    t.sharedAt = System.currentTimeMillis(); // B3d: offer lifetime starts now
                    peerWriter.println("share " + t.id + " " + t.filePath);
                    say("[send] " + t.name + " -> " + t.peer + "   preparing (" + human(t.size) + ")");
                } else {
                    sendRequest(t, out);
                }
            }
        }

        // Caller holds lock. B3a step 2: the request (with the hash in blobs mode).
        private void sendRequest(Transfer t, PrintWriter out) {
            t.phase = "requested";
            t.lastActivity = System.currentTimeMillis();
            out.println("FILE_REQUEST|" + t.peer + "|" + t.name + "|" + t.size + "|" + t.id
                    + (t.isBlobs() ? "|" + t.hash : ""));
            say("[send] " + t.name + " -> " + t.peer + "   requested (" + human(t.size) + ")");
        }

        // EVENT:SHARED:<id>:<hash>:<size>
        private void onShared(String id, String hash) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || !t.phase.equals("sharing")) return;
                t.hash = hash;
                PrintWriter out = out();
                if (out == null) {
                    t.phase = "shared"; // not connected — the ticker sends the request
                    return;
                }
                sendRequest(t, out);
            }
        }

        // B3a step 3: the receiver accepted; PEER_INFO carries its endpoint.
        void onPeerInfo(String id, String endpointId, String relayUrl) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || !activeSends.contains(t) || !t.phase.equals("requested")) return;
                t.lastActivity = System.currentTimeMillis();
                if (t.isBlobs()) {
                    // B3a step 4a: only this receiver may fetch this file.
                    t.phase = "allowing";
                    peerWriter.println("allow " + t.id + " " + endpointId);
                    say("[send] " + t.name + " -> " + t.peer + "   accepted, allowing");
                } else {
                    t.phase = "accepted";
                    String relay = (relayUrl == null || relayUrl.isBlank() || relayUrl.equals("null")) ? "-" : relayUrl;
                    peerWriter.println("sendto " + t.id + " " + endpointId + " " + relay + " " + t.filePath);
                    say("[send] " + t.name + " -> " + t.peer + "   started");
                }
            }
        }

        // EVENT:ALLOWED:<id>:<endpoint> — B3a step 4b: tell the receiver to fetch.
        private void onAllowed(String id) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || !t.phase.equals("allowing")) return;
                PrintWriter out = out();
                if (out == null) {
                    t.phase = "allowed"; // not connected — the ticker sends FILE_READY
                    return;
                }
                sendReady(t, out);
            }
        }

        // Caller holds lock.
        private void sendReady(Transfer t, PrintWriter out) {
            out.println("FILE_READY|" + t.peer + "|" + t.id);
            t.phase = "serving";
            t.lastActivity = System.currentTimeMillis();
            say("[send] " + t.name + " -> " + t.peer + "   ready, " + t.peer + " is fetching");
            // From here peer-app serves the file by itself: free the slot.
            activeSends.remove(t);
            startNextSend();
        }

        // EVENT:SERVED:<id>:<peer>:<path> — the receiver got everything.
        private void onServed(String id, String path) {
            peerWriter.println("unshare " + id);
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null) return; // e.g. an offer restored after a restart
                t.shared = false;
                t.path = path;
                finish(t, true, null, false);
            }
        }

        // B3b: TRANSFER_END|<from>|<id>|done|… — the receiver has the file.
        // Stop serving and finish, whether or not SERVED came (the receiver
        // may already have had the file, then our peer-app never served).
        void onTransferEnd(String from, String id, String state, String reason) {
            synchronized (lock) {
                // B3c: the other side ended it before our restart check ran.
                Restored r = restored.remove(id);
                if (r != null) {
                    dropLocal(r);
                    say("[restart] " + r.name + "   " + (state.equals("done")
                            ? "finished on " + from + "'s side, removed"
                            : "FAILED " + from + ": " + reason));
                    return;
                }
            }
            if (state.equals("failed")) {
                onFailedByOther(from, id, reason);
                return;
            }
            if (!state.equals("done")) {
                say("[transfers] TRANSFER_END " + state + " from " + from + " not handled yet (B4)");
                return;
            }
            synchronized (lock) {
                Transfer t = byId.get(id);
                // Already finished (SERVED came first), or unknown to us.
                if (t == null) return;
                if (t.dir != Dir.SEND || !t.peer.equals(from)) return; // not ours to end
                if (t.shared) {
                    peerWriter.println("unshare " + t.id);
                    t.shared = false;
                }
                finish(t, true, null, false);
            }
        }

        // B3c: TRANSFER_END|<from>|<id>|failed|<reason> — the other side
        // failed it (lost its data, file changed, ...). It already told the
        // server, so we only clean up: receiver cancels, sender unshares.
        private void onFailedByOther(String from, String id, String reason) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || !t.peer.equals(from)) return;
                if (t.dir == Dir.RECEIVE && t.isBlobs()) {
                    peerWriter.println("cancel " + t.id);
                }
                // finish() unshares a send that still has an offer.
                finish(t, false, from + ": " + (reason == null || reason.isEmpty() ? "failed" : reason), false);
            }
        }

        // EVENT:OFFER_EXPIRED:<id> / EVENT:OFFER_DROPPED:<id>:<reason>|<name>
        // rawReason: peer-app's own words (e.g. "file changed while peer-app was off").
        // fileName: B3d, from peer-app ("" if unknown).
        private void onOfferGone(String id, String reason, String rawReason, String fileName) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null) {
                    // B3c: at startup, before the restart check: remember why,
                    // so the receiver can be told.
                    if (!synced) {
                        restored.remove(id);
                        droppedAtStart.put(id, rawReason);
                        if (!fileName.isEmpty()) droppedNames.put(id, fileName); // B3d
                        // B3d: show WHICH file (also covers offers that
                        // expired while peer-app was off).
                        say("[restart] saved share " + (fileName.isEmpty() ? "" : fileName + " ")
                                + "dropped: " + rawReason);
                    }
                    return;
                }
                if (t.dir != Dir.SEND) return;
                t.shared = false;
                if (t.phase.equals("serving")) {
                    // B3c: the receiver is fetching (or waiting for us):
                    // tell it now instead of letting it wait for the TTL.
                    reportFailed(t.id, t.peer, rawReason);
                }
                finish(t, false, reason, false);
            }
        }

        void onRejected(String who) {
            synchronized (lock) {
                for (Transfer t : new ArrayList<>(activeSends)) {
                    if (t.peer.equals(who) && t.phase.equals("requested")) {
                        finish(t, false, "rejected by " + who, false);
                        return;
                    }
                }
            }
        }

        // ---------------- receiving ----------------

        // hash and senderEndpoint are empty for a meta3 request.
        void onIncomingRequest(String id, String from, String name, long size,
                               String hash, String senderEndpoint) {
            synchronized (lock) {
                if (byId.containsKey(id)) return; // duplicate
                Transfer t = new Transfer(id, Dir.RECEIVE, from, name, null, size);
                t.hash = hash == null ? "" : hash;
                t.senderEndpoint = senderEndpoint == null ? "" : senderEndpoint;
                byId.put(id, t);
                if (activeReceives.size() < MAX_RECEIVES) {
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
            activeReceives.add(t);
            t.phase = "accepted";
            t.lastActivity = System.currentTimeMillis();
            out.println("FILE_ACCEPT|" + t.peer + "|" + t.id);
            say("[recv] " + t.name + " <- " + t.peer + "   accepted (" + human(t.size)
                    + (t.isBlobs() ? ", blobs" : "") + ")");
        }

        // Caller holds lock.
        private void startNextReceive() {
            while (activeReceives.size() < MAX_RECEIVES && !receiveQueue.isEmpty()) {
                Transfer next = receiveQueue.peekFirst();
                if (next.phase.equals("resume-queued")) {
                    // B3b: a resume needs no server connection, only peer-app.
                    receiveQueue.pollFirst();
                    resume(next);
                    continue;
                }
                if (out() == null) return;
                accept(receiveQueue.pollFirst());
            }
        }

        // ---------------- B3b: waiting for the sender, resuming ----------------

        // EVENT:FETCH_RETRYING:<id>:<n>:<secs>:<reason> — one of peer-app's 3 quick tries.
        private void onFetchRetrying(String id, String n, String secs, String reason) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || t.dir != Dir.RECEIVE) return;
                t.lastActivity = System.currentTimeMillis();
                say(arrow(t) + "   retrying " + n + "/3 in " + secs + "s (" + shorten(reason) + ")");
                // Tell the server: the mesh shows "reconnecting n/3" instead
                // of a line that looks like it is still moving. It also keeps
                // the record alive (the tries can take longer than the
                // server's 120 s "no response" backup).
                flushProgress(t); // B3d: the mesh shows the real % while retrying
                sendEvent(t.id, "retrying", "retry=" + n + "/3");
            }
        }

        // EVENT:FETCH_WAITING:<id>:<secs_left>:<reason> — tries failed, chunks kept.
        private void onFetchWaiting(String id, long secsLeft, String reason) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || t.dir != Dir.RECEIVE) return;
                t.phase = "waiting";
                t.lastActivity = System.currentTimeMillis();
                // Give the slot back: other files can be received meanwhile.
                activeReceives.remove(t);
                receiveQueue.remove(t);
                flushProgress(t); // B3d: the mesh shows the % that is really kept
                sendEvent(t.id, "waiting", "secs=" + secsLeft, "reason=" + shorten(reason));
                // Minutes rounded up, the same as the mesh shows.
                t.lockedUntil = System.currentTimeMillis() + secsLeft * 1000;
                say(arrow(t) + "   🔒 locked: " + t.pct + "% kept, waiting for " + t.peer
                        + lockLeft(t) + " — resumes when they're back");
                startNextReceive();
            }
        }
        

        // B3d: " (N min left)" until peer-app gives up on a locked transfer.
        private static String lockLeft(Transfer t) {
            if (t.lockedUntil == 0) return "";
            long ms = t.lockedUntil - System.currentTimeMillis();
            return ms <= 0 ? " (expiring)" : " (" + ((ms + 59_999) / 60_000) + " min left)";
        }

        /** B3b: a user is online again (JOIN, or in the ONLINE list after our reconnect). */
        void onUserBack(String who) {
            synchronized (lock) {
                for (Transfer t : new ArrayList<>(byId.values())) {
                    if (t.dir == Dir.RECEIVE && t.peer.equals(who) && t.phase.equals("waiting")) {
                        say(arrow(t) + "   " + who + " is back");
                        resumeOrQueue(t);
                    } else if (t.dir == Dir.SEND && t.peer.equals(who) && t.peerAway) {
                        // B3d: the receiver resumes by itself; we only say so.
                        t.peerAway = false;
                        say(arrow(t) + "   " + who + " is back");
                    }
                }
            }
        }

        // Caller holds lock.
        private void resumeOrQueue(Transfer t) {
            if (activeReceives.size() < MAX_RECEIVES) {
                resume(t);
            } else if (!receiveQueue.contains(t)) {
                t.phase = "resume-queued";
                receiveQueue.addFirst(t); // before new requests
                say(arrow(t) + "   resume queued (receive slots full)");
            }
        }

        // Caller holds lock. Takes a slot and asks peer-app to continue.
        private void resume(Transfer t) {
            activeReceives.add(t);
            t.phase = "fetching";
            long now = System.currentTimeMillis();
            t.lastResumeAt = now;
            t.lastActivity = now;
            peerWriter.println("retry " + t.id);
            say(arrow(t) + "   resuming");
        }

        // EVENT:ERROR:retry <id>: <message> — peer-app couldn't start the retry.
        private void onRetryError(String id, String message) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || t.dir != Dir.RECEIVE) return;
                if (message.contains("already fetching")) return; // fine, it's running
                // Nothing to continue right now: back to waiting. If it has
                // expired, peer-app reports TRANSFER_FAILED by itself.
                if (t.phase.equals("fetching")) {
                    activeReceives.remove(t);
                    t.phase = "waiting";
                    startNextReceive();
                }
                say(arrow(t) + "   retry not started: " + message);
            }
        }

        // FILE_READY|<sender>|<id> — B3a step 5: the sender allowed us, fetch now.
        void onFileReady(String from, String id) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null || t.dir != Dir.RECEIVE || !t.phase.equals("accepted") || !t.isBlobs()) return;
                if (t.senderEndpoint.isEmpty() || t.senderEndpoint.equals("null")) {
                    finish(t, false, "no address for sender " + from, true);
                    return;
                }
                t.phase = "fetching";
                t.lastActivity = System.currentTimeMillis();
                // fetch <id> <hash> <size> <sender_endpoint_id> <name>   (name last: may contain spaces)
                peerWriter.println("fetch " + t.id + " " + t.hash + " " + t.size + " "
                        + t.senderEndpoint + " " + t.name);
                say("[recv] " + t.name + " <- " + t.peer + "   fetching");
            }
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
                        // B3b: tell the sender too. If we already had the file
                        // its peer-app never serves, so no SERVED would come
                        // and it would stay in "serving" until the offer expires.
                        if (t.isBlobs()) sendTransferEnd(t, "done", "");
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
                    restored.remove(kv[0]); // B3c: expired before the restart check
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

            // ---- B3a: blobs events ----
            if (line.startsWith("EVENT:SHARED:")) {
                // EVENT:SHARED:<id>:<hash>:<size>
                String[] p = line.substring("EVENT:SHARED:".length()).split(":");
                if (p.length >= 2) onShared(p[0], p[1]);
                return true;
            }
            if (line.startsWith("EVENT:ALLOWED:")) {
                onAllowed(idAndRest(line, "EVENT:ALLOWED:")[0]);
                return true;
            }
            if (line.startsWith("EVENT:SERVED:")) {
                // EVENT:SERVED:<id>:<peer>:<path>
                String[] p = line.substring("EVENT:SERVED:".length()).split(":");
                onServed(p[0], p.length >= 3 ? p[2] : "");
                return true;
            }
            if (line.startsWith("EVENT:OFFER_EXPIRED:")) {
                onOfferGone(idAndRest(line, "EVENT:OFFER_EXPIRED:")[0], "offer expired", "offer expired", "");
                return true;
            }
            if (line.startsWith("EVENT:OFFER_DROPPED:")) {
                // B3d: EVENT:OFFER_DROPPED:<id>:<reason>|<name>
                String[] kv = idAndRest(line, "EVENT:OFFER_DROPPED:");
                String rest = kv[1];
                int bar = rest.lastIndexOf('|');
                String why  = bar >= 0 ? rest.substring(0, bar) : rest;   // old peer-app: no '|'
                String file = bar >= 0 ? rest.substring(bar + 1) : "";
                onOfferGone(kv[0], "offer dropped: " + why, why, file);
                return true;
            }
            if (line.startsWith("EVENT:RESUMED:")) {
                // EVENT:RESUMED:<id>:<bytes already on disk>
                String[] kv = idAndRest(line, "EVENT:RESUMED:");
                synchronized (lock) {
                    Transfer t = byId.get(kv[0]);
                    long bytes = parseLong(kv[1]);
                    // B3d: every quick retry reports RESUMED; show it only when the number changed.
                    if (t != null && bytes != t.resumedShown) {
                        t.resumedShown = bytes;
                        say(arrow(t) + "   resuming from " + human(bytes));
                    }
                }
                return true;
            }

            // ---- B3c: what peer-app restored after a restart ----
            if (line.startsWith("EVENT:RESTORED_OFFER:")) {
                // EVENT:RESTORED_OFFER:<id>:<hash>:<size>:<name>   (name may contain ':')
                String[] p = line.substring("EVENT:RESTORED_OFFER:".length()).split(":", 4);
                if (p.length == 4) {
                    addRestored(new Restored(p[0], true, p[1], parseLong(p[2]), 0, p[3]));
                }
                return true;
            }
            if (line.startsWith("EVENT:RESTORED_FETCH:")) {
                // EVENT:RESTORED_FETCH:<id>:<hash>:<size>:<bytes_on_disk>:<name>
                String[] p = line.substring("EVENT:RESTORED_FETCH:".length()).split(":", 5);
                if (p.length == 5) {
                    addRestored(new Restored(p[0], false, p[1], parseLong(p[2]), parseLong(p[3]), p[4]));
                }
                return true;
            }
            if (line.startsWith("EVENT:RESTORED:")) {
                // EVENT:RESTORED:<offers>:<fetches> — peer-app's list is complete.
                synchronized (lock) {
                    restoreListDone = true;
                    if (!restored.isEmpty()) {
                        say("[restart] " + restored.size() + " saved transfer(s) found, checking with the server...");
                    }
                    syncRestart();
                }
                return true;
            }
            if (line.startsWith("EVENT:ERROR:fetch ") && line.contains(": saved fetch dropped: ")) {
                // A saved fetch peer-app could not load: its data is lost.
                String[] kv = idAndRest(line, "EVENT:ERROR:fetch ");
                synchronized (lock) {
                    if (!synced) droppedAtStart.put(kv[0], "lost local data");
                }
                return false; // also shown
            }

            // ---- B3b: sender gone, waiting, resuming ----
            if (line.startsWith("EVENT:FETCH_RETRYING:")) {
                // EVENT:FETCH_RETRYING:<id>:<n>:<secs>:<reason>   (reason may contain ':')
                String[] p = line.substring("EVENT:FETCH_RETRYING:".length()).split(":", 4);
                if (p.length >= 3) onFetchRetrying(p[0], p[1], p[2], p.length > 3 ? p[3] : "");
                return true;
            }
            if (line.startsWith("EVENT:FETCH_WAITING:")) {
                // EVENT:FETCH_WAITING:<id>:<secs_left>:<reason>
                String[] p = line.substring("EVENT:FETCH_WAITING:".length()).split(":", 3);
                if (p.length >= 2) onFetchWaiting(p[0], parseLong(p[1]), p.length > 2 ? p[2] : "");
                return true;
            }
            if (line.startsWith("EVENT:RETRY:")) {
                // peer-app started our `retry` — RESUMED / progress follow.
                return true;
            }
            if (line.startsWith("EVENT:ERROR:retry ")) {
                // EVENT:ERROR:retry <id>: <message>
                String[] kv = idAndRest(line, "EVENT:ERROR:retry ");
                onRetryError(kv[0], kv[1].trim());
                return true;
            }

            // Quiet events: useful in logs, too noisy for the screen.
            // FETCH_EXPIRED is always followed by TRANSFER_FAILED (handled above).
            return line.startsWith("EVENT:FILE_HASH:")
                    || line.startsWith("EVENT:TRANSFER_TIMING:")
                    || line.startsWith("EVENT:SHARE_TIMING:")
                    || line.startsWith("EVENT:UNSHARED:")
                    || line.startsWith("EVENT:RETRY_ALL:")
                    || line.startsWith("EVENT:FETCH_EXPIRED:")
                    // B3c: our `cancel` worked, or there was nothing left to cancel.
                    || line.startsWith("EVENT:FETCH_CANCELLED:")
                    || line.startsWith("EVENT:ERROR:cancel ")
                    // B3b: SERVED and TRANSFER_END both unshare; the second
                    // one finds no offer. Harmless, so not shown.
                    || line.startsWith("EVENT:ERROR:unshare: no offer")
                    // B3d: peer-app refused a request for a share it no longer has
                    // (e.g. dropped after a restart); the other side is told anyway.
                    || line.startsWith("EVENT:PEER_REJECTED:")
                    // B3d: peer-app stopping because we exit.
                    || line.startsWith("EVENT:SHUTDOWN:");
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
                    t.pctSent = pct;
                    sendEvent(t.id, "progress", "pct=" + pct);
                }
            }
        }
        
        // B3d: progress goes to the server at most every 3 s, so the last
        // few % can be missing when a transfer stops. Send them now.
        // Caller holds lock.
        private void flushProgress(Transfer t) {
            if (t.dir != Dir.RECEIVE || t.pct <= t.pctSent) return;
            t.pctSent = t.pct;
            t.lastProgressSent = System.currentTimeMillis();
            sendEvent(t.id, "progress", "pct=" + t.pct);
        }

        private void onPath(String id, String path) {
            synchronized (lock) {
                Transfer t = byId.get(id);
                if (t == null) return;
                t.lastActivity = System.currentTimeMillis();
                // B3b: peer-app repeats the path on every retry ("direct ->
                // direct"). Only a real change is shown and sent: a repeat
                // would turn the server's "retrying" back into "moving".
                if (path.equals(t.path)) return;
                if (t.phase.equals("accepted") || t.phase.equals("fetching")) t.phase = "moving";
                String before = t.path;
                t.path = path;
                say(arrow(t) + "   " + (before.isEmpty() ? "path: " + path : "path: " + before + " -> " + path));
                sendEvent(t.id, "path", "path=" + path);
            }
        }

        // ---------------- B3c: restart sync ----------------

        private void addRestored(Restored r) {
            synchronized (lock) {
                if (synced || byId.containsKey(r.id)) return;
                restored.put(r.id, r);
            }
        }

        /** B3c: a new login started (ONLINE line); a fresh MY_TRANSFER list follows. */
        void onLogin() {
            synchronized (lock) {
                serverBuilding = new ArrayList<>();
                loggedInOnce = true;
                if (!offline) return;
                offline = false;
                // B3d: back after an outage: send what waited in the outbox,
                // then the current state of running transfers.
                PrintWriter out = out();
                java.util.Set<String> sentIds = new java.util.HashSet<>();
                String[] e;
                while (out != null && (e = outbox.pollFirst()) != null) {
                    out.println(e[1]);
                    sentIds.add(e[0]); // done + TRANSFER_END of one transfer count once
                }
                reannounce();
                say("[sync] back online · in sync with the server"
                        + (sentIds.isEmpty() ? "" : " (" + sentIds.size()
                                + " result(s) from while it was away sent)"));
            }
        }

        /** B3d: the connection to the server is gone (called by the reconnect loop). */
        void onDisconnected() {
            synchronized (lock) {
                if (offline || !loggedInOnce) return; // warn once per outage
                offline = true;
                int running = 0;
                for (Transfer t : byId.values()) {
                    if (t.phase.equals("moving") || t.phase.equals("fetching") || t.phase.equals("serving")) {
                        running++;
                    }
                }
                if (running > 0) {
                    say("⚠ server lost · " + running + " transfer(s) continue directly (peer-to-peer)."
                            + " Results are kept and sent when the server is back.");
                }
            }
        }

        // Caller holds lock. B3d: tell the server where running receives are
        // now; their progress / waiting events were dropped while offline.
        private void reannounce() {
            long now = System.currentTimeMillis();
            for (Transfer t : byId.values()) {
                if (t.dir != Dir.RECEIVE) continue;
                if (t.phase.equals("moving") || t.phase.equals("fetching")) {
                    t.pctSent = -1; // force it out
                    flushProgress(t);
                } else if (t.phase.equals("waiting") && t.lockedUntil > now) {
                    t.pctSent = -1;
                    flushProgress(t);
                    sendEvent(t.id, "waiting", "secs=" + ((t.lockedUntil - now) / 1000),
                            "reason=sender offline");
                }
            }
        }

        /** B3c: MY_TRANSFER|<id>|<state>|<from>|<to>|<pct> */
        void onMyTransfer(String line) {
            String[] p = line.split("\\|", -1);
            if (p.length < 6) return;
            synchronized (lock) {
                serverBuilding.add(new String[] { p[1], p[2], p[3], p[4], p[5] });
            }
        }

        /** B3c: MY_TRANSFERS_END|<count> — the server's list is complete. */
        void onMyTransfersEnd() {
            synchronized (lock) {
                List<String[]> done = serverBuilding;
                serverBuilding = new ArrayList<>();
                if (synced) return; // compared once already (plain reconnect, E1)
                serverList = done;
                syncRestart();
            }
        }

        // Caller holds lock. Runs once, when both lists are complete.
        private void syncRestart() {
            if (synced || !restoreListDone || serverList == null) return;
            synced = true;

            for (String[] f : serverList) {
                String id = f[0], state = f[1], from = f[2], to = f[3];
                // Known to Java: nothing to compare (only possible for a
                // transfer started after this process began).
                if (byId.containsKey(id)) continue;
                boolean iAmSender = me.equals(from);
                if (!iAmSender && !me.equals(to)) continue;
                String other = iAmSender ? to : from;
                Restored r = restored.remove(id);
                boolean begun = !state.equals("started");

                if (r != null && r.offer == iAmSender && begun) {
                    if (iAmSender) keepSharing(r, other);
                    else resumeRestored(r, other);
                    continue;
                }
                // The server still has it, but we can't continue it.
                if (r != null) dropLocal(r);
                String reason = droppedAtStart.containsKey(id) ? droppedAtStart.get(id)
                        : !begun ? "restarted before the transfer began"
                        : "lost local data";
                reportFailed(id, other, reason);
                String name = r != null ? r.name
                        : droppedNames.getOrDefault(id, "transfer " + shortId(id)); // B3d: name if peer-app told us
                say("[restart] " + name + (iAmSender ? " -> " : " <- ") + other + "   FAILED " + reason);
            }

            // Only peer-app has it: the server no longer does (finished,
            // failed or expired while we were gone) -> delete.
            for (Restored r : restored.values()) {
                dropLocal(r);
                say("[restart] " + r.name + "   removed: no longer active on the server");
            }
            restored.clear();
            droppedAtStart.clear();
            droppedNames.clear(); // B3d
            serverList = null;
        }

        // Caller holds lock. Receiver: continue the fetch from the chunks on disk.
        private void resumeRestored(Restored r, String sender) {
            Transfer t = new Transfer(r.id, Dir.RECEIVE, sender, r.name, null, r.size);
            t.hash = r.hash;
            t.restored = true;
            t.phase = "waiting";
            t.pct = r.size > 0 ? (int) Math.min(99, r.bytesOnDisk * 100 / r.size) : 0;
            t.lastMilestone = (t.pct / 25) * 25;
            byId.put(t.id, t);
            say(arrow(t) + "   restored (" + t.pct + "% kept)");
            // If the sender is offline, peer-app's own B3b tries end in
            // FETCH_WAITING ("waiting for sender") as usual.
            resumeOrQueue(t);
        }

        // Caller holds lock. Sender: peer-app already serves the restored offer.
        private void keepSharing(Restored r, String receiver) {
            Transfer t = new Transfer(r.id, Dir.SEND, receiver, r.name, null, r.size);
            t.hash = r.hash;
            t.shared = true;
            t.restored = true;
            t.phase = "serving";
            byId.put(t.id, t);
            say(arrow(t) + "   restored, still sharing");
        }

        // Caller holds lock. Delete what peer-app kept for it.
        private void dropLocal(Restored r) {
            peerWriter.println((r.offer ? "unshare " : "cancel ") + r.id);
        }

        // Caller holds lock. Mark it failed on the server and tell the other side.
        private void reportFailed(String id, String other, String reason) {
            sendEvent(id, "failed", "reason=" + reason);
            sendTransferEnd(other, id, "failed", reason);
        }

        private static String shortId(String id) {
            return id.length() > 8 ? id.substring(0, 8) : id;
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
            // B3a: a failed send that still has an offer: close it.
            if (!ok && t.dir == Dir.SEND && t.shared) {
                peerWriter.println("unshare " + t.id);
                t.shared = false;
            }
            if (ok) {
                say(arrow(t) + "   done OK" + (t.path.isEmpty() ? "" : " (" + t.path + ")"));
            } else {
                say(arrow(t) + "   FAILED " + (reason == null ? "" : reason));
            }
            sendQueue.remove(t);
            receiveQueue.remove(t);
            activeSends.remove(t);
            activeReceives.remove(t);
            startNextSend();
            startNextReceive();
        }

        void onUserLeft(String who) {
            synchronized (lock) {
                for (Transfer t : new ArrayList<>(byId.values())) {
                    if (!t.peer.equals(who)) continue;
                    boolean notStarted = t.phase.equals("queued")
                            || t.phase.equals("sharing") || t.phase.equals("shared")
                            || t.phase.equals("requested") || t.phase.equals("allowing")
                            || t.phase.equals("allowed");
                    if (notStarted) {
                        finish(t, false, who + " left", false);
                    } else if (t.dir == Dir.SEND && t.phase.equals("serving") && !t.peerAway) {
                        // B3d: our peer-app keeps the offer, so the receiver can
                        // still resume. Say so plainly — nothing failed.
                        t.peerAway = true;
                        say(arrow(t) + "   " + who + " left the chat — still sharing" + minutesLeft(t));
                    }
                    // Transfers already moving (or served by peer-app) end on
                    // their own: peer-app reports the result, the server marks it.
                }
            }
        }
        

        // B3d: " (up to N min)" until the offer expires; "" if unknown (restored).
        private static String minutesLeft(Transfer t) {
            if (t.sharedAt == 0) return "";
            long msLeft = t.sharedAt + TRANSFER_TTL_MS - System.currentTimeMillis();
            if (msLeft <= 0) return "";
            return " (up to " + ((msLeft + 59_999) / 60_000) + " min)";
        }

        // Called every TICK_MS by the ticker thread.
        void tick() {
            synchronized (lock) {
                long now = System.currentTimeMillis();
                for (Transfer t : new ArrayList<>(activeReceives)) {
                    if (t.phase.equals("accepted") && now - t.lastActivity > ACCEPT_TIMEOUT_MS) {
                        finish(t, false, t.isBlobs() ? "sender never got ready" : "sender never connected", true);
                    }
                }
                PrintWriter out = out();
                for (Transfer t : new ArrayList<>(activeSends)) {
                    long idle = now - t.lastActivity;
                    switch (t.phase) {
                        case "sharing" -> {
                            if (idle > SHARE_TIMEOUT_MS) finish(t, false, "could not prepare the file", false);
                        }
                        case "shared" -> {
                            if (out != null) sendRequest(t, out);
                        }
                        case "requested" -> {
                            if (idle > REQUEST_TIMEOUT_MS) finish(t, false, "request not accepted in time", false);
                        }
                        case "allowing" -> {
                            if (idle > ALLOW_TIMEOUT_MS) finish(t, false, "could not allow the receiver", true);
                        }
                        case "allowed" -> {
                            if (out != null) sendReady(t, out);
                        }
                        default -> {
                        }
                    }
                }
                // B3b: a waiting transfer whose sender is online (JOIN missed,
                // or its peer-app wasn't serving yet): try again, not too often.
                for (Transfer t : new ArrayList<>(byId.values())) {
                    if (t.dir == Dir.RECEIVE && t.phase.equals("waiting")
                            && now - t.lastResumeAt > RESUME_RETRY_MS && isOnline.test(t.peer)) {
                        resumeOrQueue(t);
                    }
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
                if (!synced && !restored.isEmpty()) {
                    sb.append("  [restart] ").append(restored.size())
                      .append(" saved transfer(s), checking with the server...\n");
                }
                sb.append("  [send] active (").append(activeSends.size()).append("/").append(MAX_SENDS)
                  .append("), queue: ").append(sendQueue.size()).append("\n");
                for (Transfer t : activeSends) {
                    sb.append("      ").append(describe(t)).append("\n");
                }
                for (Transfer t : byId.values()) {
                    if (t.dir == Dir.SEND && t.phase.equals("serving")) {
                        sb.append("      ").append(describe(t)).append("\n");
                    }
                }
                for (Transfer t : sendQueue) {
                    sb.append("      waiting: ").append(t.name).append(" -> ").append(t.peer).append("\n");
                }
                sb.append("  [recv] active (").append(activeReceives.size()).append("/").append(MAX_RECEIVES)
                  .append("), queue: ").append(receiveQueue.size()).append("\n");
                for (Transfer t : activeReceives) {
                    sb.append("      ").append(describe(t)).append("\n");
                }
                for (Transfer t : receiveQueue) {
                    sb.append("      ").append(t.phase.equals("resume-queued") ? "resume queued: " : "waiting: ")
                      .append(t.name).append(" <- ").append(t.peer).append("\n");
                }
                for (Transfer t : byId.values()) {
                    if (t.dir == Dir.RECEIVE && t.phase.equals("waiting")) {
                        sb.append("      🔒 ").append(t.name).append(" <- ").append(t.peer)
                        .append("  locked, ").append(t.pct).append("% kept, waiting for ").append(t.peer)
                        .append(lockLeft(t)).append("\n");
                    }
                }
                System.out.print(sb);
            }
        }

        private String describe(Transfer t) {
            if (t == null) return "none";
            String dirArrow = t.dir == Dir.SEND ? " -> " : " <- ";
            return t.name + dirArrow + t.peer + "  " + t.phase
                    + (t.isBlobs() ? " [blobs]" : " [meta3]")
                    + (t.pct > 0 ? " " + t.pct + "%" : "")
                    + (t.path.isEmpty() ? "" : " (" + t.path + ")")
                    + (t.restored ? " (restored)" : "");
        }

        // ---------------- helpers ----------------

        private PrintWriter out() {
            return currentOut == null ? null : currentOut[0];
        }

        private void sendEvent(String id, String state, String... fields) {
            StringBuilder sb = new StringBuilder("TRANSFER_EVENT|id=").append(id).append("|state=").append(state);
            for (String f : fields) {
                sb.append('|').append(f.replace('|', ' ').replace('\n', ' '));
            }
            // B3d: only the final result is worth keeping for later.
            send(id, sb.toString(), state.equals("done") || state.equals("failed"));
        }

        // B3d: send now; if offline (or the write fails), keep final events
        // in the outbox. Progress / path / waiting are not kept: reannounce()
        // sends the current state after the next login instead.
        private void send(String id, String line, boolean keepIfOffline) {
            PrintWriter out = out();
            if (out != null) {
                out.println(line);
                if (!out.checkError()) return; // sent
                // the connection just died under us: fall through and keep it
            }
            if (keepIfOffline && outbox.size() < OUTBOX_MAX) {
                outbox.addLast(new String[] { id, line });
            }
        }

        // B3b: TRANSFER_END|<other user>|<id>|<state>|<reason> to the server.
        private void sendTransferEnd(Transfer t, String state, String reason) {
            sendTransferEnd(t.peer, t.id, state, reason);
        }

        private void sendTransferEnd(String peer, String id, String state, String reason) {
            // B3d: always final, so kept in the outbox when offline.
            send(id, "TRANSFER_END|" + peer + "|" + id + "|" + state + "|"
                    + (reason == null ? "" : reason.replace('|', ' ').replace('\n', ' ')), true);
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

        // B3b: keep peer-app reasons short on screen and in events.
        private static String shorten(String s) {
            if (s == null) return "";
            s = s.trim();
            return s.length() > 120 ? s.substring(0, 117) + "..." : s;
        }

        private static int parseInt(String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static int envInt(String name, int fallback) {
            try {
                int v = Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(fallback)).trim());
                return v > 0 ? v : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static String human(long bytes) {
            if (bytes >= 1024L * 1024) return String.format("%.1f MB", bytes / 1048576.0);
            if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
            return bytes + " B";
        }
    }
}