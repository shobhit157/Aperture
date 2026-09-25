# Future Plan: Self-Hosted Iroh Relay

> [!NOTE]
> This is a forward-looking design doc, not a built feature. It exists to
> record the evidence and reasoning behind self-hosting a relay, so the
> decision doesn't have to be re-derived later.

---

## 1. The problem this solves

Aperture currently relies entirely on n0's free, publicly-hosted relay
servers (`aps1-1.relay.n0.iroh.link` and similar) whenever a direct
peer-to-peer connection can't be established.

```text
Today:

  peer-app A ──┐
               ├──► n0 relay (free, shared, no SLA)
  peer-app B ──┘
```

This infrastructure is genuinely useful and free to use, but it is
**best-effort** — there is no uptime guarantee, and it is shared across
every Iroh user in the world, not just Aperture.

---

## 2. Evidence this is a real, recurring issue

Across real testing on two separate networks (home ISP and mobile
carrier), independently:

- `dns.iroh.link` (n0's discovery service, separate from relay) was
  confirmed unreachable — the same failure, on two unrelated networks.
- The relay server itself, while usually healthy, showed real packet loss
  (33% observed on one occasion) under degraded conditions.
- A client's `peer-app` was observed getting "stuck" registered against a
  distant, high-latency relay (`use1-1`, US-East) instead of the correct
  nearby one, causing every transfer to it to time out until the client
  was restarted.

None of these are bugs in Aperture's own code — they are properties of
depending on shared, third-party infrastructure with no control over its
capacity or health.

---

## 3. What self-hosting changes

```text
Planned:

  peer-app A ──┐
               ├──► own relay (EC2, self-hosted, monitored)
  peer-app B ──┘         │
                          └─ fallback: n0 relay (last resort)
```

- **Known uptime** — the relay's health can be checked directly, the same
  way the signaling server's own health already is.
- **No shared capacity** — not competing with every other Iroh user
  worldwide for the same free servers.
- **A real second option** — even without eliminating n0's relay
  entirely, self-hosting gives Aperture an independent path that doesn't
  fail for the same reason at the same time as n0's infrastructure.

> [!IMPORTANT]
> Fallback order decision: direct connection first (always), then the
> self-hosted relay, with n0's relay kept as the final fallback — not
> because n0's relay is worse on average, but because a relay you can
> directly monitor and control is more *knowable*, even if a single
> self-hosted instance has less redundancy than n0's presumably
> multi-region setup. This is a deliberate tradeoff, not a claim that
> self-hosting is strictly better in every dimension.

---

## 4. Implementation steps

- [ ] Install Rust/cargo on the signaling server's EC2 instance, if not
      already present (`peer-app` is currently only built on the local
      dev machine).
- [ ] `cargo install iroh-relay` on that instance.
- [ ] Configure and run the relay binary, exposing whatever port it needs
      through the instance's security group.
- [ ] Update `peer-app`'s endpoint configuration to include the
      self-hosted relay as a candidate, alongside (not instead of) the
      default n0 preset.
- [ ] Confirm the relay is genuinely reachable and usable with a real
      transfer test, the same way `dns.iroh.link` reachability has been
      verified throughout this project (`ping`, direct connection tests).
- [ ] Decide and implement the actual fallback ordering logic in
      `peer-app` (direct → own relay → n0 relay).
- [ ] Re-run the direct-vs-relay ratio test after this change, alongside
      the direct-address-sharing fix, since both affect the same metric.

---

## 5. Honest scope note

This does not remove the `dns.iroh.link` discovery dependency — that is a
separate concern, covered in the DHT future-plan doc. This plan is scoped
specifically to the **relay** (data-forwarding) side of Iroh's
infrastructure, not discovery.
