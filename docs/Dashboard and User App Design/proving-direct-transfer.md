# Proving a file went direct

Can Aperture prove to a user that their file went **directly** to the other person, and not through a server?

Short answer: **not perfectly, and nobody can.** The app is the one saying "it went direct", and an app could always lie. What we *can* do is make the claim easy to check from outside the app.

---

## Three separate questions

| Question | Can we prove it? | How |
|---|---|---|
| Is the file correct? | **Yes, fully** | BLAKE3 hash, checked piece by piece |
| Could anyone in the middle read it? | **Yes** | Iroh encrypts end to end; a relay only sees scrambled bytes |
| Did it go direct or through a relay? | **Strong evidence, not perfect proof** | See below |

The important point: **privacy holds either way.** Even through a relay, nobody can read the file. "Direct vs relay" is about **speed and path**, not safety.

---

## Evidence for "direct"

1. **What Iroh reports.** peer-app sends `CONNECTION_PATH` (direct / relay), based on Iroh's own view of the connection.
   - Improvement: report **how many bytes went direct vs relay**, because a transfer can start on the relay and switch to direct halfway.
2. **The other person's IP.** Show the IP the data went to. If it's the friend's IP and not the relay's, it went direct.
3. **Check from outside the app.** During a transfer, `ss -u -p | grep peer` or Wireshark shows traffic going straight to the other machine. This doesn't trust the app at all.
4. **The server never sees the file.** Server traffic stays tiny, even for a 300 MB file.

---

## Tools that show where data goes

- **Wireshark / tcpdump**: record every packet and show which IP it went to. The strongest check, but technical.
- **Per-app network monitors**: show, for each app, which addresses it talks to and how much it sends.
  - Little Snitch (Mac)
  - GlassWire (Windows)
  - OpenSnitch (Linux)
- **`ss` / `netstat`**: quick command-line check of live connections.

All of these work outside the app, so they don't depend on trusting it.

---

## How privacy-focused tools handle this

They mostly **don't rely on "it went direct"**. They rely on:

1. **End-to-end encryption.** Only the two ends hold the keys, so the path doesn't matter.
2. **Checking the other person's key.** Signal's "safety numbers": both people compare a short code in person or on a call. If the codes match, nobody is secretly in the middle. This is the proof people actually trust.
3. **Open source + audits.** Anyone can read the code, so "the app might lie" becomes checkable.
4. **Hiding who talks to whom.** **Direct P2P shows your IP address to the other person.** Very privacy-focused tools avoid going direct for this reason.
   - **Tor** routes traffic through 3 relays, so nobody sees both ends.
   - **OnionShare** and **Briar** share files and messages over Tor.

**Direct is great for speed. For secrecy, what matters is encryption + verified keys.**

---

## Plan for Aperture

| What | When |
|---|---|
| Report bytes direct vs relay per transfer | User app (Phase U), in the "check" panel |
| Show the IP the data went to | User app (Phase U) |
| Show a short **fingerprint** of the other person's peer ID (e.g. `4F2A-91C3`) to compare over a call | User app (Phase U). The peer ID *is* a public key, so this works like Signal's safety numbers |
| Prove it once from outside the app (Wireshark / `ss`) | Demo video |

Not part of B3. B3 continues as planned.
