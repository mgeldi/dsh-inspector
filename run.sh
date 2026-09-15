#!/usr/bin/env bash
#
# DSH Inspector — one command, both processes, no manual step.
#
# Fresh-clone contract: no database and no pre-built jar required. If
# backend/target holds no jar the script builds one (skipping tests); the default
# corpus is the committed fixture set under backend/fixtures/sessions, so the
# dashboard comes up populated on first run.
#
# Ctrl-C stops both processes — and their children. `&` hands back a wrapper pid
# (the subshell), not the JVM's, and killing the wrapper leaves the listener
# holding its port, so each side is started as a process-group leader and the
# trap kills the exact group:
#   * the backend job is a subshell that `exec`s the JVM, so $! *is* the JVM;
#   * the frontend job's group contains npm, ng and the dev server's node.
#
# Both jobs take stdin from /dev/null. `set -m` makes them background process
# groups, and the Angular dev server installs a keypress listener on stdin
# ("press h + enter to show help"). A background group that reads the
# controlling terminal is stopped by the kernel with SIGTTIN, which froze the
# dev server partway through startup — after printing its notice, sometimes
# before it had bound 4300. Run detached from a terminal (no controlling tty)
# the read returns EOF instead and the server starts normally, which is why
# this only showed up when a human ran it from a shell. Only the help key is lost.
#
set -euo pipefail
cd "$(dirname "$0")"

shopt -s nullglob
jars=( backend/target/*.jar )
if [ ${#jars[@]} -eq 0 ]; then
  echo "no packaged jar under backend/target — building (mvn -q -DskipTests package)"
  ( cd backend && mvn -q -DskipTests package )
  jars=( backend/target/*.jar )
  if [ ${#jars[@]} -eq 0 ]; then
    echo "the build produced no jar; aborting" >&2
    exit 1
  fi
fi
jar_base=${jars[0]##*/}
# The JVM's working directory is backend/ — the corpus flag and the bare sqlite
# file name both resolve against it — so the jar is addressed as target/<name>.
jar_path="target/$jar_base"

# set -m puts every background job in its own process group, led by the job's
# pid. The group ids are what the trap kills; with plain job control the
# children (the JVM, the node dev server) would outlive the script.
set -m

( cd backend && exec java -jar "$jar_path" --inspector.corpus=fixtures/sessions ) < /dev/null &
BE=$!

if [ ! -d frontend/node_modules ]; then
  echo "frontend/node_modules missing — npm install"
  ( cd frontend && npm install )
fi

( cd frontend && npm start ) < /dev/null &
FE=$!

stop_tree() {
  kill -TERM -- -"$BE" 2>/dev/null || true
  kill -TERM -- -"$FE" 2>/dev/null || true
}
trap stop_tree EXIT
trap 'stop_tree; exit 130' INT TERM

echo "starting backend on 8091 (fixture corpus, populated on first run) ..."
for _ in $(seq 1 60); do
  curl -sf http://127.0.0.1:8091/api/overview >/dev/null && break
  sleep 1
done
if ! curl -sf http://127.0.0.1:8091/api/overview >/dev/null; then
  echo "backend did not come up on 8091 within 60 s; its log was printed above" >&2
  exit 1
fi

for _ in $(seq 1 90); do
  curl -sf http://127.0.0.1:4300/ >/dev/null && break
  sleep 1
done
if ! curl -sf http://127.0.0.1:4300/ >/dev/null; then
  echo "frontend did not answer on 4300 within 90 s; its output is above" >&2
  exit 1
fi

# Printed only once both have answered. Announcing the URL before the check is
# exactly how a dev server that never started gets reported as started.
echo "backend   http://127.0.0.1:8091"
echo "frontend  http://127.0.0.1:4300   (the dashboard — Ctrl-C stops both)"

# Block until either side dies, then stop both.
#
# Not `wait -n`: the two jobs live in their own process groups (set -m), so a
# terminal's Ctrl-C reaches this script but never them, and bash does not run a
# trap while it is blocked in `wait` — the script sat there with both children
# alive after every interrupt. Polling with a short sleep lets the signal land
# between iterations instead. `kill -0` is not enough as a liveness test: an
# exited child stays a zombie until this shell reaps it, and its pid still
# answers, so the state is read and Z counts as dead.
alive() {
  local state
  state=$(ps -o stat= -p "$1" 2>/dev/null | tr -d ' ')
  [ -n "$state" ] || return 1
  case "$state" in Z*) return 1 ;; esac
  return 0
}

while true; do
  for side in "$BE" "$FE"; do
    if ! alive "$side"; then
      stop_tree
      exit 0
    fi
  done
  sleep 0.5
done
