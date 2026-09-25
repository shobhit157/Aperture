# Why Most Transfers Fall Back to Relay

> [!WARNING]
> **Observation:** In real testing, roughly 9 out of 10 file transfers complete via **relay**, not **direct**, even between peers that should be able to reach each other directly.
>
> **Finding:** The signaling layer never shares each peer's observed direct (IP) addresses with the other side — only the relay URL. Iroh is never given a direct address to attempt hole-punching toward at connection time.

---

## 1. Current Behavior

When a peer connects to another, the address it is given looks like this:

```text
EndpointAddr {
    id: <target endpoint id>,
    addrs: [ Relay(https://aps1-1.relay.n0.iroh.link./) ]
}
```

Only a relay address is present. No `TransportAddr::Ip(...)` entries are ever included.

```rust
let addr = EndpointAddr::from_parts(
    target_id,
    std::iter::once(TransportAddr::Relay(relay_url)),
);
let conn = endpoint.connect(addr, ALPN).await?;
```

---

## 2. Why This Matters

Iroh's official `connect` example builds the target address from **both** sources:

```rust
let addrs = args.addrs.into_iter()
    .map(TransportAddr::Ip)
    .chain(std::iter::once(TransportAddr::Relay(args.relay_url)));

let addr = EndpointAddr::from_parts(args.endpoint_id, addrs);
```

```text
             Relay only                    Relay + IP addrs
                 │                                │
                 ▼                                ▼
        ┌────────────────┐               ┌────────────────┐
        │ connect via     │               │ attempt direct  │
        │ relay first     │               │ AND relay at    │
        │                 │               │ the same time   │
        └────────┬────────┘               └────────┬────────┘
                 │                                  │
                 ▼                                  ▼
        hole-punch negotiated            direct path already
        AFTER connecting, if             a real candidate from
        it has time to complete          the first packet
```

Without direct addresses supplied up front, Iroh has to discover the peer's
direct address **after** the connection is already established over relay —
through its own background NAT-traversal negotiation. That negotiation:

- takes real time
- may not complete before a small/fast transfer has already finished
- may not complete before the application's own timeout gives up

This is consistent with what was actually observed: small files (finish
fast) and time-bounded transfers disproportionately land on relay, while
same-machine/no-NAT tests (where direct is nearly instant regardless) were
the main cases seen going direct.

---

## 3. Root Cause

```text
Registration flow today:

peer-app                    Client.java                 Server
   │                             │                          │
   │  EVENT:RELAY_READY:<url>    │                          │
   ├────────────────────────────►│                          │
   │                             │  register(relayUrl)      │
   │                             ├─────────────────────────►│
   │                             │                          │
   │  (IP addresses known        │                          │
   │   locally, never reported)  │                          │
   │                             │                          │
```

`EndpointAddr` exposes both:

```rust
pub fn ip_addrs(&self) -> impl Iterator<Item = &SocketAddr>
pub fn relay_urls(&self) -> impl Iterator<Item = &RelayUrl>
```

Only `relay_urls()` is ever read. `ip_addrs()` has never been called.

---

## 4. The Fix

### 4.1 `peer-app` — report IP addresses at startup, not just relay

```rust
let my_addr = endpoint.addr();
if let Some(relay_url) = my_addr.relay_urls().next() {
    println!("EVENT:RELAY_READY:{relay_url}");
}
let ip_addr_list: Vec<String> = my_addr.ip_addrs().map(|a| a.to_string()).collect();
println!("EVENT:IP_ADDRS_READY:{}", ip_addr_list.join(","));
```

### 4.2 `peer-app` — accept and use them in `sendto`

```text
old: sendto <transfer_id> <endpoint_id> <relay_url> <file_path>
new: sendto <transfer_id> <endpoint_id> <relay_url> <ip_addrs> <file_path>
```

```rust
let addrs_iter = std::iter::once(TransportAddr::Relay(relay_url))
    .chain(
        ip_addr_strs
            .iter()
            .filter_map(|s| s.parse::<std::net::SocketAddr>().ok())
            .map(TransportAddr::Ip),
    );
let addr = EndpointAddr::from_parts(target_id, addrs_iter);
```

### 4.3 `Client.java` and server — carry the new field through

The same path `relayUrl` already travels:

```text
peer-app → Client.java (capture) → registration (extra field)
    → RedisClientRegistry → ChatRoom → PEER_INFO → other peer's
    Client.java → sendto command
```

`ipAddrs` needs to travel the identical path, stored and forwarded
alongside `relayUrl` at every one of those hops.

---

## 5. Expected Outcome

```text
Before:                              After:

connect(relay only)                  connect(relay + ip candidates)
        │                                     │
        ▼                                     ▼
   via relay                          direct attempt starts
        │                             immediately, alongside
        ▼                             relay as fallback
  (maybe migrate                             │
   to direct later,                          ▼
   maybe not)                        higher direct-connection
                                      success rate, especially
                                      for short-lived transfers
```

---

## 6. Caveats

> [!NOTE]
> Sharing a raw local IP address only helps when the peer is reachable at
> that address — e.g. same LAN, or a NAT with a predictable/static mapping.
> For peers behind harder NAT types (symmetric NAT, strict CGNAT), the
> shared IP candidate may simply fail to connect, and Iroh will still need
> to fall back to relay or complete its own STUN-based hole-punching
> negotiation. This fix improves the odds and the speed of a direct
> connection — it does not guarantee one in every network configuration.

---

## 7. Next Steps

- [ ] Add `ip_addrs()` reporting to `peer-app` startup.
- [ ] Extend `sendto` to accept IP address candidates.
- [ ] Extend registration protocol (`Client.java` → server) to carry `ipAddrs`.
- [ ] Extend `PEER_INFO` to forward the target's `ipAddrs` to the requester.
- [ ] Re-run the direct-vs-relay ratio test after the change, on the same
      test matrix used before, to confirm the fix actually moves the ratio.
