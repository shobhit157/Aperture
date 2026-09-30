# Chat Reconnect Fix — Step 4 (In Progress)

Status: core mechanism built and verified. Two pieces from the original
design not yet built. One unresolved observation from testing needs
checking before this is considered done.

---

## What was wrong

Neither side of a chat connection had any way to notice a genuinely
silent failure — no error, no exception, nothing. Observed directly
during step 3 testing: disabling the local network produced no reconnect
attempt, no error, and no output at all, for as long as the network
stayed off. The only way this was ever noticed was the operating
system's own internal TCP retry behavior eventually giving up, which was
observed taking over 20 minutes in real cases.

---

## What was built

**Files changed:** `Client.java`, `ClientHandler.java`.

- The server now replies `PONG` to every `PING` it receives.
- The client recognizes `PONG` and silently absorbs it, rather than
  printing it as unrecognized text.
- The server's main message-loop read timeout, previously set to wait
  forever (`setSoTimeout(0)`), is now 60 seconds. Three missed
  20-second heartbeats without anything arriving is treated as dead.
- The client's own socket now has a matching 45-second read timeout,
  slightly shorter than the server's, so the client notices first in
  the common case. A timeout here throws `SocketTimeoutException`,
  which is a subclass of `IOException` and flows straight into the
  existing step 3 reconnect logic with no further changes needed.

---

## Verification

Tested live, on a real WiFi disconnect and reconnect, not a staged
scenario.

```text
Connection lost (attempt 1, was connected for 65s): Read timed out
Reconnecting in 1s...
Connection lost (attempt 2, was connected for 0s): No route to host
Reconnecting in 2s...
Connection lost (attempt 3, was connected for 0s): No route to host
Reconnecting in 4s...
Connection lost (attempt 4, was connected for 0s): Connect timed out
Reconnecting in 8s...
Connection lost (attempt 5, was connected for 0s): Connect timed out
Reconnecting in 16s...
Connected to server!
```

The first line, "Read timed out," is the client's new 45-second timeout
firing — the first time this project has ever detected a silent
disconnect this way, rather than not detecting it at all. Everything
after that is real network failure ("No route to host," genuine WiFi
being off) correctly handled by step 3's existing backoff, followed by
a clean, successful reconnect.

On the server side, the corresponding session ended with:

```text
[SESSION] end user=admin session=34ff6a60 reason=read timeout duration=79s joined=true
[SESSION] cleanup user=admin session=34ff6a60 wasOwner=true thisPod=40403f50
```

`reason=read timeout` confirms the server's own new 60-second timeout
fired, at 79 seconds — compared to the 1200-1450 seconds observed for
zombie sessions before this fix, relying only on the operating system's
own much slower retry behavior.

Chat and file transfer both continued working correctly after the
reconnect.

---

## Not yet built

- [ ] The `PONG` reply is currently unconditional — the server replies
      to any session's `PING`, even one that has already been
      superseded by a newer session under the same name. The design
      calls for checking ownership first (reusing the same check
      already built in step 2) and replying `SUPERSEDED` instead if the
      session is a zombie, so it can shut itself down immediately
      rather than waiting up to 60 seconds for its own timeout.
- [ ] The client does not yet recognize `SUPERSEDED` — it would
      currently be printed as unrecognized text rather than causing the
      client to stop or reconnect cleanly.
- [ ] A Redis expiry on each user record, refreshed only by the genuine
      owner's heartbeat, as an independent safety net beyond the two
      application-level timeouts above.
- [ ] Moving `online_users` to a new key, which the above expiry would
      require, since a plain Redis set cannot have individual members
      expire.

Given how effectively the two read timeouts alone have already reduced
zombie lifetime (from 1200+ seconds to under 90), it is worth
reconsidering whether `SUPERSEDED` and the Redis expiry are still
necessary, or whether they would only be a marginal improvement on
something already working well. Not yet decided.

---

## Open question from testing, unresolved

During the WiFi test above, the full session log showed:

```text
[SESSION] start user=admin session=1cabd3d1 remote=/10.42.0.1:28536
[SESSION] start user=admin session=34ff6a60 remote=/10.42.0.1:62141
[SESSION] end user=admin session=34ff6a60 reason=read timeout duration=79s joined=true
[SESSION] cleanup user=admin session=34ff6a60 wasOwner=true thisPod=40403f50
```

`1cabd3d1` (the first session) never shows an `end` line, and a Redis
check afterward showed it — not `34ff6a60` — as the currently registered
owner:

```text
admin -> 1cabd3d1
```

This is inconsistent with the visible terminal output, which showed a
single disconnect-and-reconnect cycle producing what should have been a
new, third session. Possible explanation, not confirmed: a second,
separate admin terminal window may have been open and untouched during
this test, which would account for `1cabd3d1` still being alive and
correctly registered throughout, independent of the window used for the
WiFi test. This needs checking directly before step 4 is considered
fully closed — an unexplained discrepancy between the log and Redis
should not be assumed away without confirmation.

---

## Relationship to other project documents

- Part of the reconnect redesign — see `chat-reconnect-plan.md`,
  `chat-reconnect-steps-1-2.md`, and `chat-reconnect-step-3.md` in
  `docs/fixes/`.
- Directly closes the gap identified while testing step 3 (see that
  document's "What step 3 does not fix" section).
