#!/usr/bin/env bash
# Masthead exchange helper (host side of writer.mode: exchange).
#
# The plugin writes <ai-dir>/requests/<id>.txt and waits for <ai-dir>/responses/<id>.txt. This script answers every
# pending request by piping it into the Claude CLI as the user who runs this script, then exits. Run it from the
# systemd path unit in tools/systemd/ (or cron, or by hand):
#
#     tools/masthead-exchange.sh /path/to/plugins/Masthead/ai
#
# Safety:
#   - Only reads regular files directly inside <ai-dir>/requests whose names match ^[a-z0-9-]{8,64}\.txt$.
#   - Never follows symlinks (the ai dir, requests/ and responses/ must be real directories; request files are opened
#     with O_NOFOLLOW; responses are written to a fresh mktemp file and renamed into place).
#   - Caps request size (MASTHEAD_MAX_INPUT, default 256 KiB) and response size (200 KiB).
#   - Requests and answers hold players' letters, so neither is readable by everyone: the plugin writes requests with
#     mode 640 and the group of requests/, and this script writes answers with mode 640 and the group of responses/.
#   - The Claude CLI runs with no tools (--tools ""), no MCP servers and no saved session, so the request text (which
#     contains player-written content) can only produce text.
#   - Claude Code reads hooks and CLAUDE.md from the folder it starts in, and CLAUDE.md from every folder above it. So
#     claude starts in a private folder, ${XDG_STATE_HOME:-~/.local/state}/masthead-exchange/cwd (mode 700), and the
#     helper refuses to run if that folder or any folder above it can be written by another user. It also passes
#     --setting-sources user (no project settings) and disableAllHooks (no hooks at all).
#   - Run it as its own user account: any CLAUDE.md in that user's home folder, or above it, becomes part of the
#     newspaper's instructions.
# Time limits: the plugin names each request <date>-t<seconds>-<hex>.txt, where <seconds> is how long it will wait.
# Claude gets that long minus 20 s minus the time the request already waited (at least 30 s), and a request older than
# its limit is skipped (the plugin has given up). Requests named without -t<seconds>- (older plugins) use
# MASTHEAD_TIMEOUT. An answer that arrives after the plugin gave up is thrown away.
# On failure it writes <id>.err (one line, no secrets) so the plugin stops waiting.
#
# Environment: CLAUDE_BIN (default: claude on PATH), MASTHEAD_TIMEOUT (seconds, for requests without a limit in the
#              name; default 280, under the plugin's default 300), MASTHEAD_MAX_INPUT (bytes, default 262144),
#              MASTHEAD_MODEL (default claude-opus-5-5, the plugin's writer.model default). The model is always passed, so
#              the paper never runs on whatever model this account last picked for its own Claude sessions.
set -euo pipefail
umask 022

AI_DIR="${1:?usage: masthead-exchange.sh <plugins/Masthead/ai directory>}"
CLAUDE_BIN="${CLAUDE_BIN:-claude}"
MODEL="${MASTHEAD_MODEL:-claude-opus-5-5}"
[[ "$MODEL" =~ ^[A-Za-z0-9._-]{1,80}$ ]] || MODEL=claude-opus-5-5
TIMEOUT="${MASTHEAD_TIMEOUT:-280}"
[[ "$TIMEOUT" =~ ^[0-9]{1,4}$ ]] || TIMEOUT=280
MAX_INPUT="${MASTHEAD_MAX_INPUT:-262144}"
MAX_OUTPUT=$((200 * 1024))
NAME_RE='^[a-z0-9-]{8,64}\.txt$'

log() { echo "masthead-exchange: $*" >&2; }

for d in "$AI_DIR" "$AI_DIR/requests" "$AI_DIR/responses"; do
  if [[ -L "$d" || ! -d "$d" ]]; then log "not a real directory (or a symlink): $d"; exit 1; fi
done
REQ="$AI_DIR/requests"
RESP="$AI_DIR/responses"

# One run at a time (the path unit and a timer may overlap).
exec 9>>"$RESP/.lock"
flock -n 9 || { log "another run is active"; exit 0; }

fail() {  # id message
  local tmp
  tmp="$(mktemp "$RESP/.err.XXXXXX")"
  printf '%s\n' "$2" > "$tmp"
  chgrp --reference="$RESP" -- "$tmp" 2>/dev/null || true
  chmod 0640 "$tmp"
  mv -fT -- "$tmp" "$RESP/$1.err"
  log "$1: $2"
}

# Claude's working folder: private, and no folder above it may be writable by anyone else (see Safety above).
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/masthead-exchange"
CWD="$STATE_DIR/cwd"
unsafe=""
if [[ -L "$STATE_DIR" || -L "$CWD" ]]; then
  unsafe="unsafe working folder: $CWD is a symlink. Fix: remove it"
else
  mkdir -p -m 700 -- "$CWD" && chmod 700 -- "$STATE_DIR" "$CWD" || unsafe="cannot create the working folder $CWD"
fi
if [[ -z "$unsafe" ]]; then
  p="$(cd -P -- "$CWD" && pwd)"
  me="$(id -u)"
  while :; do
    if ! read -r owner mode < <(stat -c '%u %a' -- "$p"); then unsafe="cannot check the working folder $p"; break; fi
    if [[ "$owner" != 0 && "$owner" != "$me" ]]; then
      unsafe="unsafe working folder: $p belongs to another user. Fix: run the helper as that user, or set XDG_STATE_HOME"
      break
    fi
    if (( 8#$mode & 8#022 )); then
      unsafe="unsafe working folder: $p can be written by other users. Fix: chmod go-w $p"
      break
    fi
    [[ "$p" == / ]] && break
    p="$(dirname -- "$p")"
  done
fi

shopt -s nullglob
if [[ -n "$unsafe" ]]; then
  log "$unsafe"
  for f in "$REQ"/*.txt; do
    name="${f##*/}"
    [[ "$name" =~ $NAME_RE ]] || continue
    [[ -e "$RESP/${name%.txt}.txt" || -e "$RESP/${name%.txt}.err" ]] || fail "${name%.txt}" "$unsafe"
  done
  exit 1
fi

WORK="$(mktemp -d "$STATE_DIR/work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

for f in "$REQ"/*.txt; do
  name="${f##*/}"
  [[ "$name" =~ $NAME_RE ]] || { log "skipping badly named request: ${name:0:80}"; continue; }
  id="${name%.txt}"
  [[ -e "$RESP/$id.txt" || -e "$RESP/$id.err" ]] && continue      # already answered
  [[ -L "$f" ]] && { fail "$id" "request is a symlink"; continue; }
  [[ -f "$f" ]] || continue

  # The request's time limit (from its name), and whether the plugin is still waiting for it.
  if [[ "$id" =~ -t([0-9]{1,4})- ]]; then
    limit=$((10#${BASH_REMATCH[1]})); (( limit < 30 )) && limit=30; (( limit > 900 )) && limit=900
  else
    limit=$TIMEOUT
  fi
  mtime=$(stat -c %Y -- "$f" 2>/dev/null) || continue
  age=$(( $(date +%s) - mtime )); (( age < 0 )) && age=0
  if (( age > limit )); then log "$id: skipped, older than its ${limit}s limit (the plugin stopped waiting)"; continue; fi
  secs=$(( limit - 20 - age )); (( secs < 30 )) && secs=30

  # Copy the request with O_NOFOLLOW, reading at most MAX_INPUT+1 bytes.
  in="$WORK/in.txt"
  if ! dd if="$f" of="$in" iflag=nofollow,count_bytes count=$((MAX_INPUT + 1)) status=none 2>/dev/null; then
    fail "$id" "could not read request"; continue
  fi
  size=$(stat -c %s "$in")
  if (( size > MAX_INPUT )); then fail "$id" "request larger than $MAX_INPUT bytes"; continue; fi
  if (( size == 0 )); then fail "$id" "request is empty"; continue; fi

  out="$WORK/out.txt"
  set +e
  ( cd "$CWD" && timeout --kill-after=10 "$secs" "$CLAUDE_BIN" -p --model "$MODEL" --setting-sources user --settings '{"disableAllHooks":true}' \
      --tools "" --strict-mcp-config --no-session-persistence --output-format text < "$in" ) 2>"$WORK/err.txt" | head -c $((MAX_OUTPUT + 1)) > "$out"
  rc=${PIPESTATUS[0]}
  set -e
  osize=$(stat -c %s "$out")
  [[ -e "$f" ]] || { log "$id: the plugin stopped waiting; answer thrown away"; continue; }
  if (( osize > MAX_OUTPUT )); then fail "$id" "claude output larger than $MAX_OUTPUT bytes"; continue; fi
  if (( rc == 124 || rc == 137 )); then fail "$id" "claude timed out after ${secs}s"; continue; fi
  if (( rc != 0 )); then tail -n 5 "$WORK/err.txt" >&2; fail "$id" "claude exited with code $rc"; continue; fi
  if (( osize == 0 )); then fail "$id" "claude printed nothing"; continue; fi

  tmp="$(mktemp "$RESP/.resp.XXXXXX")"
  cat "$out" > "$tmp"
  chgrp --reference="$RESP" -- "$tmp" 2>/dev/null || true
  chmod 0640 "$tmp"
  mv -fT -- "$tmp" "$RESP/$id.txt"
  log "$id: answered ($osize bytes)"
done
