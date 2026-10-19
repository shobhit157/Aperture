# Idea: duplicate / changed-file check before accept

**Status:** idea, planned for **B4**
**Raised:** 2026-10-20, during B3d step 4 (mesh "already had it")
**Touches:** peer-app (received list), Java client (prompt), server (one new message), mesh (nothing new)

---

## Problem

When a sender sends a file the receiver already has, two things can happen today:

1. **Same content:** peer-app finds the blob in its store and reports path `none`.
   The mesh shows it as a finished transfer ("already had it" since B3d).
   This is rare: after a file is saved, its copy in the store is deleted
   (decision B2-1), so the receiver usually does not remember it.
2. **Changed content** (the sender edited the file): it is a normal transfer.
   The new file is saved next to the old one (`received_<id>_<name>`)
   **without asking** the receiver.

So the receiver gets duplicates it didn't need, and new versions it never agreed to.

## Idea

Check **before any data moves**. The sender's peer-app hashes the file first
(`share`), and the hash (blake3) travels in `FILE_REQUEST`. The receiver compares
it with a list of files it received earlier, then:

| Receiver has…                      | What happens                                   | Mesh          |
|------------------------------------|------------------------------------------------|---------------|
| same content (same hash)           | skip, nothing is downloaded                    | not shown     |
| same name, different content       | **ask the receiver** first                     | only if accepted |
| nothing with that name or hash     | as today                                       | as today      |

### Why the hash and not metadata

Name, size and date can match while the content differs (an edited photo of the
same size), or differ while the content is the same (a renamed copy).
The hash is exact, and it is already there before the transfer starts, at no cost.

## What the users see

**Same content (skip):**

    receiver: [recv] holiday.mp4 <- admin   you already have this file (same content), skipped
    sender:   [send] holiday.mp4 -> admin2  admin2 already has it, not sent

**Changed (ask):**

    receiver: [recv] admin wants to send holiday.mp4 (300 MB).
              You already have a different holiday.mp4 (received 18 Oct).
              Download the new version?  /accept 3   /reject 3
    sender:   [send] holiday.mp4 -> admin2  waiting for admin2 to accept

- `/accept 3` → normal transfer (new file saved next to the old one)
- `/reject 3` → `FILE_REJECT` (exists today), sender sees "admin2 declined"
- no answer → the sender's existing 15 min request timeout ends it

## Design

### 1. peer-app: received list (`received.json`)

peer-app already keeps `offers.json` and `fetches.json`. Add one more:

    { "hash": "...", "size": 314572800, "name": "holiday.mp4",
      "path": "received_t41_holiday.mp4", "received_ms": 1792400000000 }

- Written when a fetch finishes (`FILE_RECEIVED`).
- An entry whose file was deleted, or changed size or date, is ignored
  (the user removed or edited their copy, so it no longer counts).
- New command and reply:

      lookup <id> <hash> <name>
      EVENT:LOOKUP:<id>:same|<path>
      EVENT:LOOKUP:<id>:changed|<path>|<received_ms>
      EVENT:LOOKUP:<id>:new

### 2. Java client: decide or ask

On `FILE_REQUEST` with a hash (blobs; meta3 has no hash, so no check):

1. send `lookup` to peer-app
2. `same` → send `FILE_HAVE|<sender>|<id>` to the server, print the skip line
3. `changed` → print the question, wait for `/accept n` or `/reject n`
4. `new` → accept as today

New commands: `/accept <n>`, `/reject <n>` (short numbers, not transfer ids).
Bots never get asked: `DUPLICATE_POLICY=ask|accept|skip`
(default `ask` for people, `accept` for bots).

### 3. Server: one new message

    client → server:  FILE_HAVE|<sender>|<id>
    server → sender:  FILE_HAVE|<receiver>|<id>

- No transfer record is created (there was no `FILE_ACCEPT`), so the mesh never
  sees it, which is what we want.
- The sender unshares the offer and prints "already has it".
- Optional: a Prometheus counter `aperture_duplicates_skipped_total`
  (and bytes saved) for the D1 dashboard.

### 4. Mesh

Nothing new. Skipped files have no record; accepted ones are normal transfers.
The B3d "already had it" label stays for the rare store-hit case.

## Edge cases

- **Two senders, same file at the same time:** both lookups say `new`;
  the second finish just adds another entry. Fine.
- **Receiver edited its own copy:** size or date differs from the entry, so it
  counts as `new`. (Re-hashing big files on every request would be too slow.)
- **Same content, different name:** `same`: skipped, and the skip line shows
  the name the receiver already has.
- **Restart while a question is open:** the request is not saved; the sender's
  15 min timeout ends it. Good enough for B4.

## Tasks (B4)

1. peer-app: `received.json` + `lookup` command + tests
2. Java: lookup on request, `/accept` / `/reject`, `DUPLICATE_POLICY`
3. Server: `FILE_HAVE` relay (+ optional metric)
4. Mockup first (user app: skip line, question card), then code
5. Tests: same file twice, edited file, renamed copy, deleted old copy,
   bot policy, no answer (timeout)

## Open questions

- Should "same content" still be shown somewhere (a quiet note in the sender's
  chat, a dashboard counter), or be completely silent?
- Keep the old file, or offer "replace the old version" on accept?
- How long to keep entries in `received.json` (forever, or N days)?
