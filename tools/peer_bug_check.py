#!/usr/bin/env python3
"""
peer_bug_check.py — verify peer-app file-transfer bugs automatically.

Runs real peer-app processes on this machine, drives them through stdin
(the same way Client.java does), and checks their EVENT: output.
No Java client, server, Redis or Kubernetes needed.

Usage:
    python3 tools/peer_bug_check.py                      # uses ~/peer-app/target/release/peer
    python3 tools/peer_bug_check.py --peer /path/to/peer
    python3 tools/peer_bug_check.py --only 1,5           # run selected tests
    python3 tools/peer_bug_check.py --keep               # keep test files afterwards

Bugs covered (numbers match docs/problems/file-transfer-bugs.md):
    1   receiver fails, sender still reports FILE_SENT
    2   receiver reports a path (TRANSFER_PATH) after a failure  (peer-app side)
    3   both sides report TRANSFER_PATH for one transfer          (peer-app side)
    4   sender-side failure is EVENT:ERROR, never TRANSFER_FAILED (peer-app side)
    5   two senders, same filename -> corrupted / mixed file
    7   corrupted data is saved as a good file (needs a hash; uses PEER_TEST_CORRUPT_BYTE)
    8   '|' in a filename breaks the header
    10  wrong relay URL in sendto
    11  progress output volume
    speed   MB/s, path and progress lines for --speed-mb sizes (default 100,500)
Bugs 2, 3 and 4 also have a Java/server half (metrics + mesh); this script
confirms the peer-app half that causes them.
"""

import argparse
import hashlib
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

DEFAULT_PEER = os.path.expanduser("~/peer-app/target/release/peer")
MB = 1024 * 1024


# ---------------------------------------------------------------- helpers

def make_random_file(path: Path, size_bytes: int) -> None:
    block = 4 * MB
    with open(path, "wb") as f:
        remaining = size_bytes
        while remaining > 0:
            n = min(block, remaining)
            f.write(os.urandom(n))
            remaining -= n


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(4 * MB), b""):
            h.update(chunk)
    return h.hexdigest()


class Peer:
    """One running peer-app process, driven through stdin."""

    def __init__(self, binary: str, name: str, workdir: Path, env: dict = None):
        self.name = name
        self.workdir = workdir
        workdir.mkdir(parents=True, exist_ok=True)
        self.lines = []                 # every stdout line, in order
        self.cond = threading.Condition()
        self.endpoint_id = None
        self.relay_url = None
        self.proc = subprocess.Popen(
            [binary, name],
            cwd=str(workdir),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            env={**os.environ, **(env or {})},
        )
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        for raw in self.proc.stdout:
            line = raw.rstrip("\n")
            with self.cond:
                self.lines.append(line)
                self.cond.notify_all()
        with self.cond:
            self.cond.notify_all()

    def wait_for(self, predicate, timeout: float, start_index: int = 0):
        """Return the first line (from start_index) matching predicate, or None."""
        deadline = time.time() + timeout
        idx = start_index
        with self.cond:
            while True:
                while idx < len(self.lines):
                    if predicate(self.lines[idx]):
                        return self.lines[idx]
                    idx += 1
                remaining = deadline - time.time()
                if remaining <= 0 or self.proc.poll() is not None and idx >= len(self.lines):
                    return None
                self.cond.wait(timeout=min(remaining, 0.5))

    def start(self, timeout: float = 60) -> None:
        ready = self.wait_for(lambda l: l.startswith("EVENT:READY_FOR_COMMANDS"), timeout)
        for l in list(self.lines):
            if l.startswith("EVENT:ENDPOINT_READY:"):
                self.endpoint_id = l.split(":", 2)[2]
            elif l.startswith("EVENT:RELAY_READY:"):
                self.relay_url = l.split(":", 2)[2]
        if not ready or not self.endpoint_id or not self.relay_url:
            raise RuntimeError(f"{self.name}: peer-app did not become ready "
                               f"(endpoint={self.endpoint_id}, relay={self.relay_url})")

    def send(self, command: str) -> None:
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()

    def sendto(self, transfer_id: str, target: "Peer", path: Path, relay_override: str = None):
        relay = relay_override or target.relay_url
        self.send(f"sendto {transfer_id} {target.endpoint_id} {relay} {path.resolve()}")

    def events_for(self, transfer_id: str):
        with self.cond:
            return [l for l in self.lines if f":{transfer_id}:" in l or l.endswith(f":{transfer_id}")]

    def stop(self):
        try:
            if self.proc.poll() is None:
                self.send("quit")
                self.proc.wait(timeout=5)
        except Exception:
            pass
        if self.proc.poll() is None:
            self.proc.kill()


def receiver_result(peer: Peer, transfer_id: str, timeout: float):
    """Wait for FILE_RECEIVED or TRANSFER_FAILED for this transfer on the receiver."""
    return peer.wait_for(
        lambda l: l.startswith(f"EVENT:FILE_RECEIVED:{transfer_id}:")
        or l.startswith(f"EVENT:TRANSFER_FAILED:{transfer_id}:"),
        timeout,
    )


def sender_result(peer: Peer, transfer_id: str, timeout: float):
    """Wait for FILE_SENT, FILE_SEND_FAILED, ERROR or TRANSFER_FAILED on the sender."""
    return peer.wait_for(
        lambda l: l.startswith(f"EVENT:FILE_SENT:{transfer_id}:")
        or l.startswith(f"EVENT:FILE_SEND_FAILED:{transfer_id}:")      # A1
        or l.startswith(f"EVENT:ERROR:{transfer_id}:")
        or l.startswith(f"EVENT:TRANSFER_FAILED:{transfer_id}:"),
        timeout,
    )


def saved_file(peer: Peer, received_line: str, fallback: str) -> Path:
    """Where the receiver saved the file, read from its FILE_RECEIVED line.

    A2 format:  EVENT:FILE_RECEIVED:<id>:<path>|<bytes>
    Older peer-app builds used a fixed name (received_<name>), so `fallback`
    is used when the line has no path.
    """
    try:
        rest = received_line.split(":", 3)[3]
        name = rest.rsplit("|", 1)[0]
        if name:
            return peer.workdir / name
    except (AttributeError, IndexError):
        pass
    return peer.workdir / fallback


# ---------------------------------------------------------------- tests

class Results:
    def __init__(self):
        self.rows = []

    def add(self, bug, title, status, detail):
        self.rows.append((bug, title, status, detail))
        print(f"  -> Bug {bug}: {status} — {detail}", flush=True)

    def print_table(self):
        print("\n" + "=" * 100)
        print(f"{'Bug':<5}{'Check':<48}{'Result':<18}Detail")
        print("-" * 100)
        for bug, title, status, detail in self.rows:
            print(f"{bug:<5}{title:<48}{status:<18}{detail}")
        print("=" * 100)

    def markdown(self) -> str:
        out = ["| Bug | Check | Result | Detail |", "|---|---|---|---|"]
        for bug, title, status, detail in self.rows:
            out.append(f"| {bug} | {title} | {status} | {detail.replace('|', '¦')} |")
        return "\n".join(out)


def test_normal_transfer(ctx, results):
    """Bug 3 (peer-app half): both sides emit TRANSFER_PATH for one transfer."""
    print("\n[Bug 3] normal transfer: who reports the path?")
    s, r = ctx["s1"], ctx["recv"]
    f = ctx["s1"].workdir / "normal.bin"
    make_random_file(f, 1 * MB)
    s_start, r_start = len(s.lines), len(r.lines)
    s.sendto("n1", r, f)
    rr = receiver_result(r, "n1", 120)
    sr = sender_result(s, "n1", 120)
    time.sleep(1)
    s_path = s.wait_for(lambda l: l.startswith("EVENT:TRANSFER_PATH:n1:"), 5, s_start)
    r_path = r.wait_for(lambda l: l.startswith("EVENT:TRANSFER_PATH:n1:"), 5, r_start)
    if not rr or not rr.startswith("EVENT:FILE_RECEIVED"):
        results.add(3, "Both sides report TRANSFER_PATH", "INCONCLUSIVE",
                    f"normal transfer did not succeed (receiver: {rr}, sender: {sr})")
        return
    ok_hash = sha256(f) == sha256(saved_file(r, rr, "received_normal.bin"))
    # A3: both sides print EVENT:FILE_HASH:<id>:blake3:<hex>
    s_hash = s.wait_for(lambda l: l.startswith("EVENT:FILE_HASH:n1:"), 2, s_start)
    r_hash = r.wait_for(lambda l: l.startswith("EVENT:FILE_HASH:n1:"), 2, r_start)
    if s_hash and r_hash:
        same = s_hash.split(":", 3)[3] == r_hash.split(":", 3)[3]
        print(f"   BLAKE3 sender == receiver: {same}", flush=True)
        ok_hash = ok_hash and same
    if s_path and r_path:
        results.add(3, "Both sides report TRANSFER_PATH", "CONFIRMED",
                    f"sender: {s_path.split(':')[-1]}, receiver: {r_path.split(':')[-1]} "
                    f"-> Java turns both into TRANSFER_METRIC (hash ok: {ok_hash})")
    else:
        results.add(3, "Both sides report TRANSFER_PATH", "NOT REPRODUCED",
                    f"sender path: {s_path}, receiver path: {r_path}")


def test_truncate_mid_send(ctx, results):
    """Bug 1 and bug 2 (peer-app half)."""
    print("\n[Bugs 1, 2] receiver fails mid-transfer (file shrinks while sending)")
    s, r = ctx["s1"], ctx["recv"]
    f = s.workdir / "shrinking.bin"
    make_random_file(f, ctx["big_mb"] * MB)
    s_start, r_start = len(s.lines), len(r.lines)
    s.sendto("t1", r, f)
    first = s.wait_for(lambda l: l.startswith("EVENT:PROGRESS:sending") and l.endswith("|t1"), 60, s_start)
    if not first:
        results.add(1, "Sender reports success after receiver failed", "INCONCLUSIVE",
                    f"transfer never started: {s.events_for('t1')[-3:]}")
        return
    os.truncate(f, 10 * MB)
    rr = receiver_result(r, "t1", 180)
    sr = sender_result(s, "t1", 180)
    time.sleep(2)

    receiver_failed = rr is not None and rr.startswith("EVENT:TRANSFER_FAILED")
    if not receiver_failed:
        results.add(1, "Sender reports success after receiver failed", "INCONCLUSIVE",
                    f"receiver did not fail (got: {rr}); file may have been sent before truncation — "
                    f"try --big-mb larger")
        return
    if sr and sr.startswith("EVENT:FILE_SENT"):
        results.add(1, "Sender reports success after receiver failed", "CONFIRMED",
                    f"receiver: {rr.split(':', 3)[3]} / sender: FILE_SENT")
    else:
        results.add(1, "Sender reports success after receiver failed", "NOT REPRODUCED",
                    f"receiver failed, sender reported: {sr}")

    path_after_fail = r.wait_for(lambda l: l.startswith("EVENT:TRANSFER_PATH:t1:"), 5, r_start)
    if path_after_fail:
        results.add(2, "Receiver reports a path after failing", "CONFIRMED",
                    f"TRANSFER_FAILED then {path_after_fail} -> Java sends a success metric after the failure")
    else:
        results.add(2, "Receiver reports a path after failing", "NOT REPRODUCED",
                    "no TRANSFER_PATH after TRANSFER_FAILED")


def test_dead_receiver(ctx, results):
    """Bug 4 (peer-app half): sender-side failure shape."""
    print("\n[Bug 4] sending to a peer that has gone offline")
    s = ctx["s1"]
    ghost = Peer(ctx["binary"], "ghost", ctx["root"] / "ghost")
    ghost.start()
    ghost_id, ghost_relay = ghost.endpoint_id, ghost.relay_url
    ghost.stop()
    time.sleep(2)

    class Dead:
        endpoint_id, relay_url = ghost_id, ghost_relay

    f = s.workdir / "to_ghost.txt"
    f.write_text("hello ghost\n")
    start = len(s.lines)
    t0 = time.time()
    s.sendto("g1", Dead, f)
    sr = sender_result(s, "g1", 150)
    took = time.time() - t0
    if sr is None:
        results.add(4, "Sender failure never becomes TRANSFER_FAILED", "CONFIRMED (hang)",
                    f"no result after {took:.0f}s — mesh would show 'in progress' forever")
    elif sr.startswith("EVENT:ERROR:g1:"):
        results.add(4, "Sender failure never becomes TRANSFER_FAILED", "CONFIRMED",
                    f"after {took:.0f}s: {sr[:90]} (Client.java ignores EVENT:ERROR)")
    elif sr.startswith("EVENT:TRANSFER_FAILED"):
        results.add(4, "Sender failure never becomes TRANSFER_FAILED", "NOT REPRODUCED", sr)
    else:
        results.add(4, "Sender failure never becomes TRANSFER_FAILED", "UNEXPECTED", sr)


def test_parallel_same_name(ctx, results):
    """Bug 5: two senders, same filename, at the same time."""
    print("\n[Bug 5] two senders, same filename, same time")
    s1, s2, r = ctx["s1"], ctx["s2"], ctx["recv"]
    f1, f2 = s1.workdir / "same.bin", s2.workdir / "same.bin"
    make_random_file(f1, ctx["same_mb"] * MB)
    make_random_file(f2, ctx["same_mb"] * MB)
    h1, h2 = sha256(f1), sha256(f2)
    old_name = "received_same.bin"            # fixed name used before A2

    bad_runs, details = 0, []
    for run in range(1, ctx["runs"] + 1):
        old = r.workdir / old_name
        if old.exists():
            old.unlink()
        a, b = f"p{run}a", f"p{run}b"
        s1.sendto(a, r, f1)
        s2.sendto(b, r, f2)                     # microseconds apart
        ra = receiver_result(r, a, 180)
        rb = receiver_result(r, b, 180)
        time.sleep(1)

        failures = [x for x in (ra, rb) if x is None or x.startswith("EVENT:TRANSFER_FAILED")]
        if failures:
            bad_runs += 1
            details.append(f"run {run}: a transfer failed: {failures[0]}")
            print("   " + details[-1], flush=True)
            continue

        pa, pb = saved_file(r, ra, old_name), saved_file(r, rb, old_name)
        if pa == pb:
            # Old behaviour: both transfers wrote the same file.
            hr = sha256(pa) if pa.exists() else None
            bad_runs += 1
            if hr not in (h1, h2):
                details.append(f"run {run}: both saved to {pa.name}, matches NEITHER sender (corrupted mix)")
            else:
                details.append(f"run {run}: both saved to {pa.name}, one file overwritten")
        else:
            ok_a = pa.exists() and sha256(pa) == h1
            ok_b = pb.exists() and sha256(pb) == h2
            if ok_a and ok_b:
                details.append(f"run {run}: ok, two files: {pa.name}, {pb.name}")
            else:
                bad_runs += 1
                details.append(f"run {run}: separate files but content wrong "
                               f"({pa.name}: {ok_a}, {pb.name}: {ok_b})")
        print("   " + details[-1], flush=True)

    status = "CONFIRMED" if bad_runs else "NOT REPRODUCED"
    summary = f"{bad_runs}/{ctx['runs']} runs bad"
    if details:
        summary += f"; last: {details[-1]}"
    results.add(5, "Parallel same filename corrupts file", status, summary)


def test_pipe_in_filename(ctx, results):
    """Bug 8: '|' in a filename breaks header parsing."""
    print("\n[Bug 8] '|' in a filename")
    s, r = ctx["s1"], ctx["recv"]
    f = s.workdir / "we|ird.txt"
    f.write_text("hello\n")
    start = len(r.lines)
    s.sendto("w1", r, f)
    line = r.wait_for(lambda l: l.startswith("EVENT:FILE_RECEIVED:") or l.startswith("EVENT:TRANSFER_FAILED:"),
                      60, start)
    time.sleep(1)
    wrong = (r.workdir / "received_we").exists()
    if wrong or (line and ":w1:" not in line):
        results.add(8, "'|' in filename breaks header", "CONFIRMED",
                    f"receiver event: {line}; saved as received_we: {wrong}")
    elif line and line.startswith("EVENT:FILE_RECEIVED:w1:"):
        results.add(8, "'|' in filename breaks header", "NOT REPRODUCED", line)
    else:
        results.add(8, "'|' in filename breaks header", "INCONCLUSIVE", f"receiver: {line}")


def test_wrong_relay(ctx, results):
    """Bug 10: dial with a relay URL that isn't the receiver's."""
    print("\n[Bug 10] sendto with a wrong relay URL")
    s, r = ctx["s1"], ctx["recv"]
    wrong = ("https://euw1-1.relay.n0.iroh.link./" if "euw1" not in r.relay_url
             else "https://use1-1.relay.n0.iroh.link./")
    f = s.workdir / "relay_test.bin"
    make_random_file(f, 1 * MB)
    t0 = time.time()
    s.sendto("r1", r, f, relay_override=wrong)
    rr = receiver_result(r, "r1", 120)
    sr = sender_result(s, "r1", 120)
    took = time.time() - t0
    if rr and rr.startswith("EVENT:FILE_RECEIVED"):
        results.add(10, "Wrong relay URL breaks the transfer", "NOT REPRODUCED",
                    f"succeeded in {took:.0f}s via {wrong} — discovery found the peer")
    else:
        results.add(10, "Wrong relay URL breaks the transfer", "CONFIRMED",
                    f"after {took:.0f}s receiver: {rr}, sender: {sr}")


def test_progress_volume(ctx, results):
    """Bug 11: one progress line per 64 KB."""
    print("\n[Bug 11] progress output volume")
    s, r = ctx["s1"], ctx["recv"]
    size_mb = ctx["big_mb"]
    f = s.workdir / "volume.bin"
    make_random_file(f, size_mb * MB)
    s_start, r_start = len(s.lines), len(r.lines)
    s.sendto("v1", r, f)
    rr = receiver_result(r, "v1", 300)
    sender_result(s, "v1", 300)
    if not rr or not rr.startswith("EVENT:FILE_RECEIVED"):
        results.add(11, "Progress lines per transfer", "INCONCLUSIVE", f"transfer failed: {rr}")
        return
    sl = sum(1 for l in s.lines[s_start:] if l.startswith("EVENT:PROGRESS:") and l.endswith("|v1"))
    rl = sum(1 for l in r.lines[r_start:] if l.startswith("EVENT:PROGRESS:") and l.endswith("|v1"))
    per_gb = (sl + rl) * 1024 / size_mb
    results.add(11, "Progress lines per transfer", "CONFIRMED" if per_gb > 5000 else "NOT REPRODUCED",
                f"{size_mb} MB -> sender {sl}, receiver {rl} lines (~{per_gb:,.0f} per GB, both sides)")


def test_hash_mismatch(ctx, results):
    """Bug 7: a corrupted file is saved as if it were fine."""
    print("\n[Bug 7] one byte corrupted in flight (test-only switch)")
    r = ctx["recv"]
    bad = Peer(ctx["binary"], "corrupter", ctx["root"] / "corrupter",
               env={"PEER_TEST_CORRUPT_BYTE": "1"})
    try:
        bad.start()
        f = bad.workdir / "corrupt_me.bin"
        make_random_file(f, 1 * MB)
        s_start, r_start = len(bad.lines), len(r.lines)
        bad.sendto("c1", r, f)
        rr = receiver_result(r, "c1", 120)
        sr = sender_result(bad, "c1", 120)
        time.sleep(1)
        hashed = bad.wait_for(lambda l: l.startswith("EVENT:FILE_HASH:c1:"), 1, s_start)
        leftovers = [p.name for p in r.workdir.glob("received_c1*")]
    finally:
        bad.stop()
        (ctx["root"] / "corrupter.log").write_text("\n".join(bad.lines))

    if not hashed:
        # Older build: the switch does nothing and nothing is hashed.
        results.add(7, "No integrity check on received files", "CONFIRMED",
                    f"this build sends no hash (receiver: {rr})")
    elif rr and rr.startswith("EVENT:TRANSFER_FAILED") and "hash mismatch" in rr and not leftovers:
        results.add(7, "No integrity check on received files", "NOT REPRODUCED",
                    f"receiver: {rr.split(':', 3)[3][:70]} / sender: {(sr or '')[:40]} / no file kept")
    else:
        results.add(7, "No integrity check on received files", "CONFIRMED",
                    f"receiver: {rr}, files left: {leftovers}")


def test_speed(ctx, results):
    """Speed baseline: MB/s, path and progress volume per file size."""
    s, r = ctx["s1"], ctx["recv"]
    for size_mb in ctx["speed_mb"]:
        print(f"\n[Speed] {size_mb} MB", flush=True)
        f = s.workdir / f"speed_{size_mb}.bin"
        make_random_file(f, size_mb * MB)
        tid = f"sp{size_mb}"
        s_start, r_start = len(s.lines), len(r.lines)
        t0 = time.time()
        s.sendto(tid, r, f)
        first = r.wait_for(lambda l: l.startswith("EVENT:PROGRESS:receiving") and l.endswith(f"|{tid}"),
                           120, r_start)
        t_first = time.time()
        rr = receiver_result(r, tid, 900)
        took = time.time() - t0
        sender_result(s, tid, 60)
        time.sleep(1)
        if not rr or not rr.startswith("EVENT:FILE_RECEIVED"):
            results.add("S", f"Speed {size_mb} MB", "FAILED", f"{rr}")
            continue
        path = r.wait_for(lambda l: l.startswith(f"EVENT:TRANSFER_PATH:{tid}:"), 5, r_start)
        paths = [l.rsplit(":", 1)[1] for l in r.lines[r_start:]
                 if l.startswith(f"EVENT:CONNECTION_PATH:{tid}:")]
        sl = sum(1 for l in s.lines[s_start:] if l.startswith("EVENT:PROGRESS:") and l.endswith(f"|{tid}"))
        rl = sum(1 for l in r.lines[r_start:] if l.startswith("EVENT:PROGRESS:") and l.endswith(f"|{tid}"))
        setup = (t_first - t0) if first else float("nan")
        data_time = max(took - setup, 0.001) if first else took
        results.add("S", f"Speed {size_mb} MB", f"{size_mb / took:.1f} MB/s",
                    f"total {took:.1f}s (setup {setup:.1f}s, data {size_mb / data_time:.1f} MB/s), "
                    f"final path {path.rsplit(':', 1)[1] if path else '?'}, "
                    f"path changes {'>'.join(paths) or '-'}, progress lines sender {sl} / receiver {rl}")
        f.unlink(missing_ok=True)
        saved_file(r, rr, "").unlink(missing_ok=True)


TESTS = {
    "3": test_normal_transfer,
    "1": test_truncate_mid_send,      # also bug 2
    "4": test_dead_receiver,
    "5": test_parallel_same_name,
    "7": test_hash_mismatch,
    "8": test_pipe_in_filename,
    "10": test_wrong_relay,
    "11": test_progress_volume,
    "speed": test_speed,
}


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description="Verify peer-app file-transfer bugs.")
    ap.add_argument("--peer", default=os.environ.get("PEER_BINARY_PATH", DEFAULT_PEER))
    ap.add_argument("--only", help="comma-separated test ids: " + ",".join(TESTS))
    ap.add_argument("--keep", action="store_true", help="keep the test folder afterwards")
    ap.add_argument("--big-mb", type=int, default=500, help="size for truncation/volume tests")
    ap.add_argument("--same-mb", type=int, default=20, help="size for the parallel same-name test")
    ap.add_argument("--runs", type=int, default=3, help="runs for the parallel same-name test")
    ap.add_argument("--speed-mb", default="100,500", help="file sizes (MB) for the speed test")
    args = ap.parse_args()

    if not os.path.isfile(args.peer):
        sys.exit(f"peer-app binary not found: {args.peer} (use --peer or PEER_BINARY_PATH)")

    root = Path.cwd() / f"peer-bug-check-{time.strftime('%Y%m%d-%H%M%S')}"
    root.mkdir()
    print(f"Working folder: {root}")

    selected = list(TESTS) if not args.only else [t.strip() for t in args.only.split(",")]
    results = Results()
    peers = []
    try:
        print("Starting peers (receiver, sender1, sender2)...", flush=True)
        recv = Peer(args.peer, "receiver", root / "recv")
        s1 = Peer(args.peer, "sender1", root / "s1")
        s2 = Peer(args.peer, "sender2", root / "s2")
        peers = [recv, s1, s2]
        for p in peers:
            p.start()
            print(f"  {p.name}: {p.endpoint_id[:16]}…  relay {p.relay_url}")

        ctx = {"binary": args.peer, "root": root, "recv": recv, "s1": s1, "s2": s2,
               "big_mb": args.big_mb, "same_mb": args.same_mb, "runs": args.runs,
               "speed_mb": [int(x) for x in args.speed_mb.split(",")]}

        for tid in selected:
            fn = TESTS.get(tid)
            if not fn:
                print(f"unknown test id {tid}")
                continue
            try:
                fn(ctx, results)
            except Exception as e:
                results.add(tid, fn.__doc__.strip().split("\n")[0][:46], "ERROR", repr(e))
    finally:
        for p in peers:
            p.stop()
        for p in peers:
            (root / f"{p.name}.log").write_text("\n".join(p.lines))

    results.print_table()
    (root / "results.md").write_text(results.markdown() + "\n")
    print(f"\nResults table (markdown): {root / 'results.md'}")
    print(f"Raw peer logs:            {root}/*.log")

    if not args.keep:
        for sub in ("recv", "s1", "s2", "ghost", "corrupter"):
            shutil.rmtree(root / sub, ignore_errors=True)
        print("Large test files removed (use --keep to keep them).")


if __name__ == "__main__":
    main()
