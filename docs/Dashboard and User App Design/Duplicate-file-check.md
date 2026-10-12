# Idea: "You already have this file"

**Status:** parked for the user app (Phase U). Not part of B3.

## The problem

With blobs, the receiver keeps its store copy of a file for only about 1 minute after saving it (cleanup runs every 60 s). After that, the store has forgotten it.

So if the same file is sent again later, the receiver:
- accepts it
- downloads the whole file again
- saves a second copy under a new name (`received_<new-id>_<name>`)

The store only knows what is inside it. It never looks at the saved files in the folder.

This is normal behavior (WhatsApp and email do the same), but it wastes time and disk space.

## The idea

The receiver already knows the file's **hash before downloading**, because it comes in the request. So it can check whether it already has that file and ask the user first:

> You already received this on Oct 12 (saved in Downloads).
> **Open it** · **Download again**

## How it would work

1. When the app saves a file, it adds one line to a small local list:

   | hash | saved at | size | modified time | received on |
   |---|---|---|---|---|

2. When a new request comes in, the app looks up the hash in that list.
3. If it finds it, it checks:
   - Is the file still at that path?
   - Is it unchanged? Same size and modified time is a quick check; re-hashing the file is a full check.
4. If both are yes → show "You already have this file".
5. Otherwise → download normally.

## Where it can't find the file

| Case | What happens |
|---|---|
| The user moved or renamed the file | Not found → downloads again. The app only remembers where *it* saved the file. Scanning the whole disk is slow and feels invasive, so we don't. |
| The user edited the file | Check says "changed" → downloads again. This is correct behavior. |
| The user deleted the file | Not found → downloads again |
| Browser version of the app | Browsers can't see files after download (privacy). It can only say "you received this before", not where it is. |

So it works for the common case (the file is still where the app put it), and safely falls back to a normal download in every other case.

## Related

- **Same-file bug (B3b):** if the same file is sent again *within about 1 minute*, the receiver finishes instantly from its store, but the sender never gets `SERVED` and stays in `serving`. Fix: the server forwards the receiver's "done" to the sender. That fix is needed whether or not this idea is built.
