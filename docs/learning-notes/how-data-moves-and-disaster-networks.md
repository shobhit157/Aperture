# How Data Moves, and What Happens When Networks Fail

> Learning note. Written while building Aperture Phase B (October 2026).
> Questions answered: how does a file on my laptop reach another peer? How do
> radio waves and Wi-Fi actually work? Why can't my device just "hop" to any
> network? What still works in a disaster?

---

## 1. From file to signals

A file is already digital: bits (0/1) stored as electric charge in SSD cells.
Sending it means copying those bits onto a link, piece by piece.

```text
SSD (charge) → RAM → peer-app cuts the file into packets + labels
  → Wi-Fi chip: bits → radio waves → router antenna: waves → bits
  → router → ISP → fibre (laser light, amplified every ~80–100 km)
  → many routers, each checks and forwards a clean copy
  → other side's network → their peer-app
  → QUIC: reorder, ask again for anything missing
  → blobs: check every chunk's hash → save file
```

### A packet

```text
┌──────────────────────────────────────────────────────────────┐
│ Wi-Fi header │ IP header │ UDP │ QUIC header │ data │ checksum │
│ (next hop,   │ (from/to  │     │ (sequence   │      │ (CRC)    │
│  MAC address)│ IP)       │     │  number…)   │      │          │
└──────────────────────────────────────────────────────────────┘
```

| Label | Purpose |
|---|---|
| MAC address | Which device on the local link the frame is for |
| IP address | Final destination on the internet |
| Sequence number | Put packets back in order, notice missing ones |
| Checksum | Detect damaged bits |

Iroh uses **QUIC over UDP** instead of TCP, but both do the same reliability
job: sequence numbers, acknowledgements, retransmission.

### How the signal travels

| Link | Physical form |
|---|---|
| Wi-Fi | Radio waves at 2.4 / 5 GHz; bits encoded in the wave's strength and timing (modulation, e.g. QAM), many sub-frequencies at once (OFDM) |
| Fibre | Pulses of laser light in glass; optical amplifiers every ~80–100 km |
| Mobile | Radio between phone and tower on licensed frequencies |

### Why it doesn't break

It does break, constantly. The system expects damage:

| Protection | What it does |
|---|---|
| Digital regeneration | Each hop reads the bits and sends a **clean new copy**; noise doesn't add up |
| Error-correcting codes | Extra bits let the receiver fix small errors itself |
| Checksums | Bigger damage is detected; the packet is thrown away |
| ACK + retransmission (TCP / QUIC) | Missing packets are sent again; order restored by sequence numbers |
| Aperture's BLAKE3 hashes | A wrong file can never be saved as correct (META3: whole file, blobs: every chunk) |

---

## 2. Radio waves and sharing the air

### Radio waves spread everywhere

- Sending: the antenna pushes electrons back and forth billions of times a
  second, creating a wave that spreads in **all directions** (like ripples in
  a pond).
- Receiving: the wave passes through the receiver's antenna and nudges its
  electrons in the same rhythm, creating a tiny current. The radio chip
  amplifies it and decodes the bits. The antenna doesn't "pull" anything in;
  it moves with the wave, like a cork on a pond.
- **Coverage area** = where the signal is still stronger than background
  noise. Distance, walls, floors and water weaken it.
- **Beamforming**: several antennas time the signal so it adds up stronger in
  one direction. Like cupping hands around your mouth, not a laser.

### Many users, one router (e.g. 3 people in a PG)

Wi-Fi is a **shared medium**: everyone in range hears every frame.

| Mechanism | What it does |
|---|---|
| Listen before talk (CSMA/CA) | Send only when the channel is quiet; wait a random time if busy |
| ACK + retry | Router confirms each frame; no ACK → resend after a random wait |
| Checksum (CRC) | Garbled frames (collisions, noise) are discarded |
| MAC addresses | Each frame says who it is for; others ignore it |
| OFDMA, MU-MIMO (Wi-Fi 6/7) | Router schedules users into separate frequency slices / directions |
| Rate adaptation | Noisy link → slower, sturdier encoding |
| Per-user encryption (WPA2/3) | Others can receive your frames but cannot read them |

Sharing costs **speed**, not data: airtime is split between users.

---

## 3. The internet is links + permission + agreements

The internet is a **network of networks**, joined by routers.

```text
Your laptop ── Wi-Fi ── home router ── ISP (Jio/Airtel) ──┐
                                                          ├── backbone (fibre, undersea cables)
Friend's phone ── tower ── their ISP ─────────────────────┤
                                                          │
AWS data centre (EC2) ────────────────────────────────────┘
```

Three layers must all work:

| Layer | Examples | Fails when |
|---|---|---|
| 1. Physical links | Cables, fibre, radio, satellites | Cuts, power loss, damage |
| 2. Permission | Wi-Fi password, SIM card, ISP account | You're not a customer / don't have the key |
| 3. Agreements + routing | Peering/transit between ISPs; BGP announces routes | No agreement, no route |

**Key insight:** even if my signal physically reached another provider's tower
or a stranger's router, it would be **ignored**: I'm not authenticated, and
there's no agreement to carry my traffic.

### What "peer-to-peer" means

P2P means **no server in the middle at the application level** (the file isn't
stored on a company server). It still uses the internet's roads.

| Aperture path | Route |
|---|---|
| Direct | peer → routers → peer |
| Relay | peer → relay server → peer (when NAT blocks the direct road) |

---

## 4. DHT: finding vs reaching

| Problem | Solved by |
|---|---|
| **Finding** a peer without a central server | DHT (lookups go through other peers) |
| **Reaching** a peer (a path for bytes) | A network link |

- The **global DHT** lives on computers worldwide. With no internet, they're
  unreachable, and the addresses they store (IPs, relay URLs) are useless anyway.
- Reaching them "by radio" would need a chain of radio hops across the world,
  which is basically building another internet.
- A **local DHT** among devices in a radio mesh *does* work: nearby devices
  share "who is here, which hop leads to them".

---

## 5. When networks fail: what exists today (India)

| Mechanism | How it helps |
|---|---|
| Phone switches to a farther tower | Automatic, if another tower of the same operator is in range |
| Emergency number **112** | Accepted by any operator's tower |
| **Intra-circle roaming (ICR)** | The Department of Telecommunications can order operators to let each other's customers roam. Activated in four Arunachal Pradesh districts during floods (June 2026) and in earlier disasters (Tripura floods, cyclones) |
| Cells on wheels | Temporary truck-mounted towers, often with satellite backhaul |
| Wi-Fi calling | Works over any Wi-Fi with internet (e.g. relief camps) |
| Cell broadcast | Government alerts to every phone in an area |
| Satellite texting | Emerging on some phones; limited and evolving in India |

**Not allowed:** transmitting on an operator's licensed frequencies with your
own equipment, or relaying other people's mobile traffic.

---

## 6. What I can build (legally, without the internet)

| Option | Range per hop | Speed | Licence in India | Good for |
|---|---|---|---|---|
| Wi-Fi mesh (e.g. B.A.T.M.A.N. routing on Raspberry Pis) | ~50–100 m (km with outdoor antennas) | Mbps | No | Files, chat, maps |
| Wi-Fi Direct / Bluetooth | ~10–100 m | Medium–slow | No | Messages, small files |
| **LoRa** (e.g. Meshtastic), 865–867 MHz | **km** per hop | A few kbps | No (delicensed band) | **Text alerts**, GPS positions |
| Ham radio | Long distance | Varies | **Yes** (ASOC exam) | Voice, text in emergencies |
| Store-and-forward ("data mules") | Whatever people carry | Delayed | — | Anything, when no live path exists |

**Rule of thumb:** long range = low speed. Text by radio, files by network.

### Why Aperture's design fits offline meshes

| Mesh challenge | Aperture piece |
|---|---|
| Links drop often | Blobs resume from verified chunks |
| Untrusted middle hops | Every chunk hash-checked; Iroh is end-to-end encrypted |
| Identity without a server | B0: each device has its own key (can sign messages) |
| Data hopping device to device | Content addressing: any node with the hash can serve it (store-and-forward) |
| Other link types | Iroh 1.0 has a "custom transport" path type — worth investigating |

### Possible future project: "Aperture Mesh" (parked)

| Stage | What | Hardware |
|---|---|---|
| 1 | Local discovery (mDNS): peers on the same Wi-Fi/hotspot connect with no internet | Laptops / phones |
| 2 | Wi-Fi mesh lab: file transfer over 3–4 hops, no internet | 3–4 Raspberry Pis |
| 3 | LoRa alerts: small signed messages over km-range radio | A few LoRa boards |
| 4 | Store-and-forward with blobs: a middle node keeps a file and serves it onward | Software on stage 2 |

---

## 7. Interview-ready summary

- A packet crosses many links; each hop checks and regenerates it, and
  TCP/QUIC resend anything lost.
- Wi-Fi shares the air with listen-before-talk, ACKs, checksums, MAC
  addressing and (in Wi-Fi 6) scheduling.
- The internet = physical links + permission + agreements between providers.
- A DHT finds peers; it doesn't create a path to them.
- In disasters: build on what exists (112, roaming orders, temporary towers),
  and for your own systems use unlicensed links (Wi-Fi mesh, LoRa) with
  store-and-forward.

---

## Sources

- [DoT Activates Intra-Circle Roaming in Four Arunachal Districts Amid Flood Disruptions (Sentinel Assam)](https://www.sentinelassam.com/north-east-india-news/arunachal-news/dot-activates-intra-circle-roaming-in-four-arunachal-districts-amid-flood-disruptions)
- [DoT activates intra circle roaming in four districts of Arunachal Pradesh (Indian Infrastructure)](https://indianinfrastructure.com/2026/06/29/dot-activates-intra-circle-roaming-in-four-districts-of-arunachal-pradesh/)
- [DoT and TSPs Restore 94 Percent of Telecom Connectivity in Flood-Hit Tripura (TelecomTalk)](https://telecomtalk.info/?p=980573)
- [COAI Says Intra Circle Roaming to Help Cyclone Affected Users (TelecomTalk)](https://telecomtalk.info/?p=483392)
