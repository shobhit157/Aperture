#!/usr/bin/env bash
# B2b-1 restart tests for peer-app (blobs). Runs two peer-apps ("ta" sender,
# "tb" receiver) with their own data folders, so your admin keys are untouched.
#
#   tools/blobs-test.sh basic             share -> allow -> fetch
#   tools/blobs-test.sh receiver INT      receiver Ctrl+C at ~30%, restart, resumes
#   tools/blobs-test.sh receiver KILL     same with kill -9
#   tools/blobs-test.sh sender            sender Ctrl+C mid-fetch, restart, fetch again
#   tools/blobs-test.sh changed           file changed while sender was off
#
# Settings: PEER=path/to/peer  SIZE_MB=1024  WORK=/tmp/aperture-blobs-test
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PEER="${PEER:-$ROOT/peer-app/target/release/peer}"
WORK="${WORK:-/tmp/aperture-blobs-test}"
SIZE_MB="${SIZE_MB:-1024}"
FILE="$WORK/send/test.bin"
ID="t1"

say()  { echo -e "\n== $*"; }
fail() { echo "FAIL: $*"; exit 1; }

cleanup() {
  for n in ta tb; do
    if [[ -f "$WORK/$n.pid" ]]; then kill -9 "$(cat "$WORK/$n.pid")" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT

# start_peer <name> <fd> <working folder>
start_peer() {
  local name=$1 fd=$2 dir=$3
  [[ -f "$WORK/$name.log" ]] && mv "$WORK/$name.log" "$WORK/$name.log.prev"
  rm -f "$WORK/$name.in"; mkfifo "$WORK/$name.in"
  ( cd "$dir" && PEER_DATA_DIR="$WORK/data-$name" exec "$PEER" "$name" ) \
      < "$WORK/$name.in" > "$WORK/$name.log" 2>&1 &
  echo $! > "$WORK/$name.pid"
  eval "exec $fd>\"$WORK/$name.in\""
  wait_for "$name" "EVENT:READY_FOR_COMMANDS" 60
}

# stop_peer <name> <fd> <signal: INT|TERM|KILL>
# Fails if the peer is still running 20 s after the signal.
stop_peer() {
  local name=$1 fd=$2 sig=$3 pid i
  pid=$(cat "$WORK/$name.pid")
  kill -"$sig" "$pid" 2>/dev/null || true
  for ((i = 0; i < 100; i++)); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.2
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "--- $name.log (last 10 lines)"; tail -10 "$WORK/$name.log"
    kill -9 "$pid" 2>/dev/null || true
    fail "$name did not exit within 20s after $sig"
  fi
  wait "$pid" 2>/dev/null || true
  eval "exec $fd>&-"
  rm -f "$WORK/$name.pid"
}

send_a() { echo "$*" >&3; }
send_b() { echo "$*" >&4; }

# wait_for <name> <regex> <timeout seconds>
wait_for() {
  local name=$1 pattern=$2 timeout=$3 i
  for ((i = 0; i < timeout * 5; i++)); do
    if grep -qE "$pattern" "$WORK/$name.log" 2>/dev/null; then return 0; fi
    sleep 0.2
  done
  echo "--- $name.log (last 20 lines)"; tail -20 "$WORK/$name.log" || true
  fail "$name: no '$pattern' within ${timeout}s"
}

field() { grep -m1 "$2" "$WORK/$1.log" | cut -d: -f"$3"; }

check_file() {
  local got
  got=$(ls "$WORK"/recv/received_${ID}_test*.bin 2>/dev/null | head -1)
  [[ -n "$got" ]] || fail "no received file"
  local a b
  a=$(sha256sum "$FILE" | cut -d' ' -f1)
  b=$(sha256sum "$got" | cut -d' ' -f1)
  [[ "$a" == "$b" ]] || fail "sha256 differs"
  echo "sha256 match: $a"
}

setup() {
  [[ -x "$PEER" ]] || fail "peer binary not found: $PEER"
  rm -rf "$WORK/data-ta" "$WORK/data-tb" "$WORK/recv"
  mkdir -p "$WORK/send" "$WORK/recv"
  if [[ ! -f "$FILE" ]] || [[ $(stat -c %s "$FILE") -ne $((SIZE_MB * 1048576)) ]]; then
    say "creating ${SIZE_MB} MB test file"
    head -c "${SIZE_MB}M" /dev/urandom > "$FILE"
  fi
  start_peer ta 3 "$WORK/send"
  start_peer tb 4 "$WORK/recv"
  A_ID=$(field ta EVENT:ENDPOINT_READY 3)
  B_ID=$(field tb EVENT:ENDPOINT_READY 3)
}

share_and_allow() {
  send_a "share $ID $FILE"
  wait_for ta "EVENT:SHARED:$ID:" 120
  HASH=$(field ta "EVENT:SHARED:$ID:" 4)
  SIZE=$(field ta "EVENT:SHARED:$ID:" 5)
  send_a "allow $ID $B_ID"
  wait_for ta "EVENT:ALLOWED:$ID:" 10
}

fetch_cmd() { echo "fetch $ID $HASH $SIZE $A_ID test.bin"; }

# progress of at least 30%
AT_30='EVENT:PROGRESS:receiving\|[^|]*\|([3-9][0-9]|100)\|'

case "${1:-basic}" in
  basic)
    setup; share_and_allow
    send_b "$(fetch_cmd)"
    wait_for tb "EVENT:FILE_RECEIVED:$ID:" 300
    check_file ;;

  receiver)
    sig="${2:-INT}"
    setup; share_and_allow
    send_b "$(fetch_cmd)"
    wait_for tb "$AT_30" 120
    say "stopping receiver with $sig"
    stop_peer tb 4 "$sig"
    start_peer tb 4 "$WORK/recv"          # no fetch command: it must resume by itself
    wait_for tb "EVENT:RESTORED:0:1" 10
    wait_for tb "EVENT:FILE_RECEIVED:$ID:" 300
    grep -m1 "EVENT:RESUMED:$ID" "$WORK/tb.log" || echo "(no RESUMED line: resumed from 0)"
    check_file ;;

  sender)
    setup; share_and_allow
    send_b "$(fetch_cmd)"
    wait_for tb "$AT_30" 120
    say "stopping sender with INT"
    stop_peer ta 3 INT
    wait_for tb "EVENT:TRANSFER_FAILED:$ID:" 120
    start_peer ta 3 "$WORK/send"
    wait_for ta "EVENT:RESTORED:1:0" 10
    say "fetch again on the receiver"
    send_b "$(fetch_cmd)"
    wait_for tb "EVENT:RESUMED:$ID:" 30
    wait_for tb "EVENT:FILE_RECEIVED:$ID:" 300
    check_file ;;

  changed)
    setup; share_and_allow
    stop_peer ta 3 INT
    say "changing the shared file while the sender is off"
    echo "changed" >> "$FILE"
    start_peer ta 3 "$WORK/send"
    wait_for ta "EVENT:OFFER_DROPPED:$ID:" 10
    wait_for ta "EVENT:RESTORED:0:0" 10
    rm -f "$FILE"                          # next run recreates a clean file
    echo "offer dropped as expected" ;;

  *)
    echo "usage: $0 basic | receiver [INT|KILL] | sender | changed"; exit 2 ;;
esac

send_a quit 2>/dev/null || true
send_b quit 2>/dev/null || true
sleep 1
say "PASS: ${1:-basic} ${2:-}"
