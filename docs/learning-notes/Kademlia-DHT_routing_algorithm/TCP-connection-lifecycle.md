# Long-Lived Connections: TCP, Keepalive, Heartbeats, and Reconnection

## 1. The Basic Question

A bot running inside Kubernetes on one EC2 instance can connect to a server running on another EC2 instance using TCP.

Example:

    EC2-A
      │
      └── Kubernetes
            │
            └── Bot Pod
                  │
                  │ TCP
                  ▼
               Internet
                  │
                  ▼
                EC2-B
                  │
                  └── Server

A TCP connection does **not** inherently reconnect at fixed intervals.

Once the TCP handshake succeeds, the connection can remain established for hours or even days if:

- both endpoints remain alive
- the network path remains usable
- no firewall/NAT/load balancer closes it
- the application does not close it
- Kubernetes does not restart the container

Therefore:

> TCP itself does not periodically reconnect.

If a bot reconnects every 30, 60, or 120 seconds, something else is usually causing it.

---

# 2. What a TCP Connection Actually Does

A simplified TCP connection looks like:

    Bot                         Server
     │                            │
     │──── SYN ──────────────────>│
     │<─── SYN + ACK ─────────────│
     │──── ACK ──────────────────>│
     │                            │
     │<════ TCP connection ══════>│

After this handshake, the connection is considered established.

If neither side sends application data:

    Bot                         Server
     │                            │
     │                            │
     │        silence             │
     │                            │
     │                            │
     │                            │

That does **not automatically mean TCP reconnects**.

TCP does not require the application to continuously send data.

---

# 3. No Keepalive Does Not Mean TCP Must Reconnect

It is important to distinguish:

> No keepalive

from:

> TCP connection must reconnect.

They are not the same.

A TCP connection can remain established even if no keepalive mechanism has been configured.

For example:

    Bot                         Server
     │                            │
     │──── connection established │
     │                            │
     │                            │
     │       5 minutes idle       │
     │                            │
     │                            │
     │──── normal message ───────>│
     │                            │

The connection may still work perfectly.

Therefore:

> Not implementing TCP keepalive is not, by itself, an explanation for fixed-interval reconnects.

---

# 4. Why Might a Bot Reconnect Repeatedly?

If the bot repeatedly does:

    connect
       ↓
    wait
       ↓
    disconnect
       ↓
    reconnect
       ↓
    repeat

possible causes include:

## 4.1 Application-Level Timeout

The bot may have logic such as:

    if no message received for 30 seconds:
        close connection
        reconnect

For example:

    last_message = current_time

    if current_time - last_message > 30 seconds:
        socket.close()
        reconnect()

This is not TCP causing the reconnect.

The application is deciding that the connection is dead.

---

## 4.2 Server-Side Timeout

The server might close idle connections.

Example:

    Client connects
          ↓
    Server waits
          ↓
    No activity for 60 seconds
          ↓
    Server closes socket
          ↓
    Bot reconnects

In this case the server is responsible for the disconnect.

---

## 4.3 Kubernetes Restarting the Bot

The bot might not actually be reconnecting.

The entire Pod could be restarting.

Example:

    Bot Pod starts
          ↓
    Bot connects
          ↓
    Container exits/crashes
          ↓
    Kubernetes restarts container
          ↓
    Bot starts again
          ↓
    Bot connects again

Check:

    kubectl get pods

Look at:

    RESTARTS

and:

    kubectl describe pod <pod-name>

Important fields include:

- Restart Count
- Last State
- Reason
- Exit Code

If the restart count keeps increasing, the problem may be the container rather than TCP.

---

## 4.4 Reconnect Loop in the Application

The code itself may implement something like:

    while (true) {
        try {
            connect();
            run();
        } catch (Exception e) {
            sleep(30000);
        }
    }

This produces:

    connection fails
          ↓
    catch exception
          ↓
    sleep 30 seconds
          ↓
    connect again

In this situation, the 30-second interval is not a networking property.

It is simply the application's reconnect policy.

---

## 4.5 NAT / Firewall / Load Balancer Timeout

A network path can contain:

    Bot
      │
      ▼
    Kubernetes
      │
      ▼
    EC2 / NAT
      │
      ▼
    Internet
      │
      ▼
    Firewall / Load Balancer
      │
      ▼
    Server

Some network devices track connections.

If a connection is completely idle for a long time, a device may eventually remove its state for that connection.

Then the endpoints may believe they still have a connection while the network path no longer has valid state.

This is one reason long-lived connections often use keepalive or heartbeats.

---

# 5. TCP Keepalive

TCP has a built-in keepalive mechanism.

Conceptually:

    TCP connection
          │
          │ idle
          ▼
    keepalive probe
          │
          ▼
    "Are you still there?"
          │
          ▼
    response

If the peer continues responding, the connection can remain alive.

If the peer/network repeatedly fails to respond, the operating system can eventually report the connection as broken.

However:

> TCP keepalive is not the same thing as an application heartbeat.

---

# 6. Application-Level Heartbeats

Applications commonly implement their own heartbeat.

Example:

    Bot                         Server
     │                            │
     │──── HEARTBEAT ────────────>│
     │<──────── ACK ──────────────│
     │                            │
     │──── HEARTBEAT ────────────>│
     │<──────── ACK ──────────────│
     │                            │
     │──── HEARTBEAT ────────────>│
     │<──────── ACK ──────────────│

For example, the bot could send a heartbeat every 20–30 seconds.

The server maintains:

    bot_id: bot-127
    last_seen: 04:17:42

Then:

    current_time - last_seen > timeout

can mean:

    BOT OFFLINE

This is an application-level definition of liveness.

---

# 7. TCP Keepalive vs Application Heartbeat

These solve related but different problems.

## TCP Keepalive

Operates at the TCP/network layer.

Purpose:

    Detect whether a TCP peer/path is still responsive.

It generally does not understand the application's state.

---

## Application Heartbeat

Operates at the application layer.

Example:

    HEARTBEAT
    bot_id=127
    status=READY
    load=23%

Purpose:

- detect application-level liveness
- maintain online/offline state
- expose useful information to the server
- support monitoring
- detect an unresponsive application

Therefore:

    TCP Keepalive
          ↓
    "Is the TCP peer still responding?"

while:

    Application Heartbeat
          ↓
    "Is my bot application alive and functioning?"

---

# 8. Reconnection Is a Separate Concept

A good architecture separates:

    Connection
    Liveness
    Reconnection

For example:

    TCP connection
          │
          └── Is the connection established?

    Heartbeat
          │
          └── Is the bot responding?

    Reconnection
          │
          └── What should happen when the connection fails?

These should not be treated as one thing.

---

# 9. Reconnection With Backoff

A production system should not reconnect thousands of clients simultaneously.

Bad approach:

    connection fails
          ↓
    wait 30 seconds
          ↓
    reconnect

for every bot.

If 500 bots fail at the same time:

    500 bots
       │
       ├── reconnect at 30 sec
       ├── reconnect at 30 sec
       ├── reconnect at 30 sec
       └── ...

This can create a reconnect storm.

A common approach is exponential backoff:

    1 second
    2 seconds
    4 seconds
    8 seconds
    16 seconds
    ...

Usually some randomness ("jitter") is also added.

The goal is to spread reconnect attempts over time.

---

# 10. How Real Applications Can Appear Permanently Connected

When a user opens a chat application, it can feel like:

    "I am continuously connected to the server."

But internally, the application may maintain:

    Application session
          │
          ├── Connection 1
          │
          ├── Connection 2
          │
          └── Connection 3

If the network changes:

    Wi-Fi
      ↓
    Mobile data

the original TCP connection may die.

The application can establish another connection while preserving the user's logical session.

Therefore:

> "I am still connected to the service"

does not necessarily mean:

> "The exact same TCP connection has existed continuously."

The application hides the underlying reconnection from the user.

---

# 11. TCP vs Logical Session

This is an important distinction.

## Physical/network connection

    TCP connection
          ↓
    IP + port
          ↓
    specific network path

## Logical application session

    User / Bot identity
          ↓
    authentication
          ↓
    session state
          ↓
    current connection

The application can preserve the logical identity even if the underlying connection changes.

---

# 12. How This Relates to Iroh

Iroh is different from a simple TCP client/server architecture.

Iroh uses QUIC, and QUIC runs over UDP.

Simplified:

    Bot A                         Bot B
      │                             │
      │      Iroh / QUIC            │
      │<═══════════════════════════>│
      │                             │
      │        chat messages        │
      │<───────────────────────────>│
      │                             │

Iroh endpoints provide a higher-level peer communication model.

The important idea is that:

> Iroh is not maintaining a TCP connection between the two bots.

Instead, it uses QUIC and Iroh's peer-to-peer networking mechanisms.

---

# 13. Iroh Can Change the Underlying Network Path

Suppose initially:

    Bot A ───────── direct path ───────── Bot B

If that path becomes unavailable:

    Bot A ───── X ───── Bot B

Iroh can attempt to establish another viable path, potentially through a relay:

                  Relay
                 /     \
                /       \
             Bot A     Bot B

The important distinction is between:

    logical peer relationship

and:

    current network path

The peer relationship can remain meaningful even if the underlying path changes.

---

# 14. Iroh Architecture for Aperture

For the Aperture project, the architecture can be viewed as two layers.

    ┌─────────────────────────────────────┐
    │              Aperture               │
    ├──────────────────┬──────────────────┤
    │                  │                  │
    │   Control Plane  │    Data Plane    │
    │                  │                  │
    │   Java Server    │   Iroh / Rust    │
    │                  │                  │
    │   Discovery      │   Peer-to-peer   │
    │   Coordination   │   Chat           │
    │   Online state   │   File transfer  │
    │   Commands       │   QUIC streams   │
    │                  │                  │
    └──────────────────┴──────────────────┘

The Java server can provide coordination and discovery.

Iroh can handle peer-to-peer communication.

This matches the architectural principle:

    Signaling is centralized.
    Data transfer is decentralized.

---

# 15. What "Online" Should Mean for the Bot Simulation?

For a system with 100–500 simulated bots, it is better not to define:

    TCP socket exists
          ↓
    bot is online

Instead, separate the concepts.

Example:

    Bot 127

    TCP:
    CONNECTED

    Heartbeat:
    last ACK = 4 seconds ago

    Application:
    READY

    Monitoring:
    bot_connected = 1

This gives a much more useful definition of online status.

---

# 16. Example Failure

Suppose the network disappears.

Before failure:

    Bot 127
       │
       ├── TCP: CONNECTED
       ├── Heartbeat: OK
       └── Application: ONLINE

Network failure:

    Bot 127
       │
       ├── TCP: BROKEN
       ├── Heartbeat: no response
       └── Application: RECONNECTING

Then:

    reconnect with backoff
          ↓
    connection established
          ↓
    heartbeat succeeds
          ↓
    application becomes ONLINE

---

# 17. What to Check in the Current Bot

Before adding keepalive, determine why the existing bot reconnects.

Log:

    CONNECTED
    DISCONNECTED
    exception
    exception message
    reconnect attempt
    reconnect delay

Example:

    CONNECTED
    04:17:01

    DISCONNECTED
    04:18:01

    reason:
    SocketTimeoutException

    reconnecting in 5 seconds

If the interval is extremely consistent:

    60.02 seconds
    60.01 seconds
    60.04 seconds
    59.98 seconds

look for a configured timeout.

Possible sources:

- application timeout
- server timeout
- socket timeout
- Kubernetes probe
- Kubernetes container restart
- NAT timeout
- firewall timeout
- load balancer timeout
- reconnect loop
- explicit timer in the bot

---

# 18. Recommended Architecture for the Bot System

For the bot simulation, a clean design is:

    ┌───────────────────────────────┐
    │             Bot               │
    │                               │
    │  TCP / QUIC connection        │
    │           │                   │
    │           ▼                   │
    │  Application heartbeat        │
    │           │                   │
    │           ▼                   │
    │  Connection state             │
    │           │                   │
    │           ▼                   │
    │  Reconnect + backoff          │
    └───────────────────────────────┘

The server can maintain:

    bot_id
    connection_state
    last_seen
    current_status

Prometheus can expose metrics such as:

    bot_connections
    bot_heartbeats_total
    bot_reconnects_total
    bot_connection_failures_total
    bot_last_seen
    bot_online

Grafana can then visualize the state of hundreds of bots.

---

# 19. Important Takeaways

1. TCP does not inherently reconnect at fixed intervals.

2. A TCP connection can remain established for a very long time.

3. Not implementing TCP keepalive does not mean the connection must reconnect.

4. Fixed-interval reconnects usually point toward:
   - application timeout
   - server timeout
   - Kubernetes restart
   - network/NAT/firewall timeout
   - reconnect logic
   - another configured timer

5. TCP keepalive helps detect certain dead connections.

6. Application heartbeats provide application-level liveness.

7. Reconnection logic should be separate from liveness detection.

8. Production systems commonly use reconnect backoff and jitter.

9. A user's logical session can survive even if the underlying TCP connection changes.

10. Iroh uses QUIC over UDP rather than TCP.

11. Iroh provides a higher-level peer communication model where the underlying network path can change.

12. For a large bot simulation, "online" should ideally mean that the bot is recently reachable/responsive, not simply that a socket exists.

---

# 20. The Core Mental Model

The most useful way to think about the system is:

    ┌─────────────────────────────┐
    │       Application           │
    │                             │
    │  "Is my bot alive?"         │
    │          │                  │
    │       Heartbeat             │
    └──────────┬──────────────────┘
               │
    ┌──────────▼──────────────────┐
    │       Transport             │
    │                             │
    │  TCP / QUIC                 │
    │                             │
    │  "Can I communicate?"       │
    └──────────┬──────────────────┘
               │
    ┌──────────▼──────────────────┐
    │       Network               │
    │                             │
    │  Internet / NAT / Firewall  │
    │  Kubernetes / EC2           │
    └─────────────────────────────┘

The application should not assume that a network connection will live forever.

The network layer should not be responsible for defining application state.

The application should detect failure, maintain its logical state, and reconnect when necessary.
