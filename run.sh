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

( cd backend && exec java -jar "$jar_path" --inspector.corpus=fixtures/sessions ) &
BE=$!

if [ ! -d frontend/node_modules ]; then
  echo "frontend/node_modules missing — npm install"
  ( cd frontend && npm install )
fi

( cd frontend && npm start ) &
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

echo "backend   http://127.0.0.1:8091"
echo "frontend  http://127.0.0.1:4300   (the dashboard — Ctrl-C stops both)"

for _ in $(seq 1 90); do
  curl -sf http://127.0.0.1:4300/ >/dev/null && break
  sleep 1
done
if ! curl -sf http://127.0.0.1:4300/ >/dev/null; then
  echo "frontend did not come up on 4300 within 90 s; its log was printed above" >&2
  exit 1
fi

# Block until either side dies, then stop both. wait -n returns the first
# side's exit status; set -e turns that into an exit, and the EXIT trap kills
# the remaining group.
wait -n "$BE" "$FE"
