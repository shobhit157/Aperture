package com.shobhit.Network_lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;

public class ClientHandler implements Runnable {

    // Step 4: readLine() wakes up at least this often, even when nothing
    // arrives, so the session can check on itself. A timeout here is NOT
    // an error — it is just a tick.
    private static final int TICK_MS = 5_000;

    // Step 4: how often a session asks Redis "am I still the owner of my
    // username?". This is what lets a zombie notice it has been replaced
    // even though the client abandoned its socket and will never send
    // anything on it again.
    private static final long OWNERSHIP_CHECK_MS = 10_000;

    // Step 4: unchanged 60s dead-connection limit (three missed 20s
    // heartbeats). Still needed for the case where there is no newer
    // session to notice — e.g. the user never comes back.
    private static final long IDLE_LIMIT_MS = 60_000;

    // Step 4: after SUPERSEDED is sent, give a live client this long to
    // read it and close its end before the server closes anyway.
    private static final long SUPERSEDE_GRACE_MS = 5_000;

    private final Socket clientSocket;
    private final ChatRoom chatRoom;
    private final EventBus eventBus;

    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
    private final long startedAtMillis = System.currentTimeMillis();
    private String endReason = "unknown";

    private String username;
    private String endpointId;
    private String relayUrl;
    private PrintWriter out;
    private boolean joined = false;

    // Step 4: set once, when this session learns a newer session owns its
    // username. Written by this session's own thread (ownership check) or
    // by the newer session's join on the same pod (forced takeover), so it
    // is volatile and set under a lock.
    private volatile String supersededBy = null;
    private volatile long supersededAt = 0;

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

    public String getSessionId() {
        return sessionId;
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
            case TRANSFER_PROGRESS -> {
            }
            case CONNECTION_PATH -> {
            }
            case SESSION_ENDED -> {
                // Never delivered to any client's screen — this is a
                // metrics-only signal, not a chat event.
            }
        }
    }

    // Step 4: tell this session it has been replaced by a newer one.
    //
    // Sends SUPERSEDED, then half-closes the socket (shutdownOutput) so the
    // word is delivered in order, followed by a clean FIN — never a reset
    // that could make a live client think the network dropped and
    // reconnect. The full close and all cleanup still happen on this
    // session's own thread, through the normal finally block.
    //
    // Safe to call from any thread, and more than once: only the first
    // call does anything.
    public void supersede(String newerSessionId) {
        synchronized (this) {
            if (supersededBy != null) return;
            supersededBy = newerSessionId;
            supersededAt = System.currentTimeMillis();
        }
        System.out.println("[SESSION] user=" + username
                + " session=" + sessionId
                + " superseded by " + newerSessionId + ", sending SUPERSEDED");
        try {
            if (out != null) {
                out.println("SUPERSEDED");
            }
            clientSocket.shutdownOutput();
        } catch (IOException ignored) {
            // Socket already dead — the common zombie case. Nothing to do;
            // the grace timer in run() will end the session.
        }
    }

    @Override
    public void run() {
        try {
            System.out.println("ClientHandler running on thread: "
                    + Thread.currentThread().getName());

            clientSocket.setSoTimeout(10_000);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));

            out = new PrintWriter(clientSocket.getOutputStream(), true);

            username = in.readLine();
            endpointId = in.readLine();
            relayUrl = in.readLine();

            if (username == null || username.isBlank()) {
                endReason = "rejected: blank or missing username";
                System.out.println("[SESSION] rejected session=" + sessionId
                        + " reason=blank username remote=" + clientSocket.getRemoteSocketAddress());
                return;
            }

            // Step 4: short tick instead of a single 60s timeout. The 60s
            // dead-connection rule is now enforced by IDLE_LIMIT_MS below.
            clientSocket.setSoTimeout(TICK_MS);

            System.out.println("[SESSION] start user=" + username
                    + " session=" + sessionId
                    + " remote=" + clientSocket.getRemoteSocketAddress());

            chatRoom.join(this);
            joined = true;

            eventBus.publish(new ChatEvent(
                    new Message(MessageType.JOIN, username, "joined the chat")
            ));

            // Step 5: send the current online list (all pods) right away.
            sendOnlineList();

            long lastHeardAt = System.currentTimeMillis();
            long lastOwnershipCheckAt = System.currentTimeMillis();

            while (true) {
                String line = null;
                boolean tick = false;
                try {
                    line = in.readLine();
                } catch (SocketTimeoutException e) {
                    // Nothing arrived for TICK_MS. Not an error — fall
                    // through to the self-checks below.
                    tick = true;
                }
                long now = System.currentTimeMillis();

                if (!tick) {
                    if (line == null) {
                        if (supersededBy != null) {
                            // The live client read SUPERSEDED and closed
                            // its end, exactly as intended.
                            endReason = "superseded by session " + supersededBy;
                        } else {
                            endReason = "EOF (client closed or connection lost)";
                        }
                        System.out.println("[" + username + "] disconnected.");
                        break;
                    }
                    lastHeardAt = now;
                    if (supersededBy == null) {
                        handleLine(line);
                    }
                    // Lines arriving after SUPERSEDED are ignored.
                }

                // Already told to stop: only wait for the client to close,
                // up to the grace period. No more ownership or idle checks.
                if (supersededBy != null) {
                    if (now - supersededAt >= SUPERSEDE_GRACE_MS) {
                        endReason = "superseded by session " + supersededBy;
                        break;
                    }
                    continue;
                }

                // Step 4: the session notices by itself that it has been
                // replaced. Runs on a timer, so it works for an abandoned
                // socket that will never deliver another line.
                if (now - lastOwnershipCheckAt >= OWNERSHIP_CHECK_MS) {
                    lastOwnershipCheckAt = now;
                    String newerOwner = newerOwnerOrNull();
                    if (newerOwner != null) {
                        supersede(newerOwner);
                        continue;
                    }
                }

                if (now - lastHeardAt >= IDLE_LIMIT_MS) {
                    endReason = "read timeout";
                    System.out.println("[" + username + "] inactive timeout.");
                    break;
                }
            }

        } catch (SocketTimeoutException e) {
            // Only reachable during the handshake now; the main loop
            // handles its own timeouts as ticks.
            endReason = joined ? "read timeout" : "handshake timeout";
            System.out.println("[" + username + "] inactive timeout.");
        } catch (Exception e) {
            if (supersededBy != null) {
                endReason = "superseded by session " + supersededBy;
            } else {
                endReason = "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                System.out.println("[" + username + "] Error: " + e.getMessage());
                e.printStackTrace(System.out);
            }
        } finally {
            long seconds = (System.currentTimeMillis() - startedAtMillis) / 1000;
            System.out.println("[SESSION] end user=" + username
                    + " session=" + sessionId
                    + " reason=" + endReason
                    + " duration=" + seconds + "s"
                    + " joined=" + joined);

            try {
                clientSocket.close();
                System.out.println("Socket closed for " + username);
            } catch (IOException e) {
                e.printStackTrace(System.out);
            }

            if (joined) {
                boolean wasOwner = false;
                try {
                    wasOwner = chatRoom.leave(this);
                } catch (Exception e) {
                    System.out.println("[" + username + "] chatRoom.leave failed: " + e.getMessage());
                }

                try {
                    if (wasOwner) {
                        eventBus.publish(new ChatEvent(
                                new Message(MessageType.LEAVE, username, "left the chat")
                        ));
                    } else {
                        eventBus.publish(new ChatEvent(
                                new Message(MessageType.SESSION_ENDED, username, "")
                        ));
                        System.out.println("[SESSION] " + username + " session=" + sessionId
                                + " ended without owning the name — no LEAVE announced");
                    }
                } catch (Exception e) {
                    System.out.println("[" + username + "] publish on cleanup failed: " + e.getMessage());
                }
            }
        }
    }

    // Step 4: returns the newer session's id ONLY on positive proof that a
    // different session owns this username. A missing record or a Redis
    // error returns null, so a genuine user is never closed by mistake —
    // SUPERSEDED is permanent on the client side, so a false positive
    // would be far worse than a zombie living a little longer.
    private String newerOwnerOrNull() {
        try {
            String owner = chatRoom.getRegisteredSessionId(username);
            return (owner != null && !owner.equals(sessionId)) ? owner : null;
        } catch (Exception e) {
            System.out.println("[SESSION] user=" + username + " session=" + sessionId
                    + " ownership check failed: " + e.getMessage());
            return null;
        }
    }

    // Step 5: tell a freshly joined client who is online right now (all
    // pods), so it never relies only on JOIN/LEAVE lines it saw live.
    private void sendOnlineList() {
        try {
            out.println("ONLINE|" + String.join(",", chatRoom.getOnlineUsers()));
        } catch (Exception e) {
            System.out.println("[SESSION] user=" + username + " session=" + sessionId
                    + " could not send online list: " + e.getMessage());
        }
    }

    private void handleLine(String line) {
        String[] parts = line.split("\\|", 5);
        String prefix = parts[0];

        switch (prefix) {
            case "PING" -> {
                out.println("PONG");
                // Step 4: renew presence AFTER replying, so PONG is never
                // delayed by Redis. A Redis error never ends the session.
                try {
                    chatRoom.heartbeat(this);
                } catch (Exception e) {
                    System.out.println("[SESSION] user=" + username + " session=" + sessionId
                            + " heartbeat refresh failed: " + e.getMessage());
                }
            }
            case "CONNECTION_PATH" -> {
                String transferId = parts.length > 1 ? parts[1] : "";
                String path = parts.length > 2 ? parts[2] : "unknown";
                String peer = parts.length > 3 ? parts[3] : "";
                eventBus.publish(new ChatEvent(
                        new Message(MessageType.CONNECTION_PATH, username, peer, transferId + "|" + path)
                ));
            }
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
                String reason = parts.length > 3 ? parts[3] : "";
                String content = reason.isEmpty() ? path : path + "|" + reason;
                eventBus.publish(new ChatEvent(
                        new Message(MessageType.TRANSFER_METRIC, username, peer, content)
                ));
            }
            case "TRANSFER_PROGRESS" -> {
                String transferId = parts.length > 1 ? parts[1] : "";
                String pct = parts.length > 2 ? parts[2] : "0";
                eventBus.publish(new ChatEvent(
                        new Message(MessageType.TRANSFER_PROGRESS, username, transferId, pct)
                ));
            }
            default -> {
                eventBus.publish(new ChatEvent(
                        new Message(MessageType.CHAT, username, line)
                ));
            }
        }
    }
}