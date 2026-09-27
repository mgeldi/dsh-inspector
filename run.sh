#!/usr/bin/env bash
#
# DSH Inspector — one command, both processes, no manual step.
#
#   ./run.sh demo [corpus]          the committed synthetic fixtures (default)
#   ./run.sh live [corpus]          your real DSH session logs, read-only
#   ./run.sh report [corpus] [out]  index, write one JSON analysis snapshot, exit — no ports
#
# report is the headless shape for an agent working on the harness between two changes: it
# binds nothing, starts no frontend, and leaves one file (default backend/inspector-report.json)
# with the board, findings by kind, the cohort tables and the judge's verdicts. The judge's pair
# comes from INSPECTOR_BASELINE / INSPECTOR_CANDIDATE (and INSPECTOR_GROUP_BY, default
# harnessVersion) when set. Like live, it defaults to ~/.dsh/sessions and its output describes
# real sessions: a file to read, not to publish.
#
# live and report import harness-timeline.yml from the repository root when it exists (it is
# gitignored; harness-timeline.example.yml shows the shape): the harness versions, one entry per
# change, that the session logs do not record themselves. demo imports the committed synthetic
# backend/fixtures/harness-timeline.yml instead, so a fresh clone's cohorts and judge have two
# versions to compare — the operator's real timeline has no business describing the fixtures.
#
# demo is the default because it is the only corpus that can travel with the repo:
# it is generated, it holds invented paths and project names, and a fresh clone can
# show a populated dashboard from it without anyone's data being in the repository.
# live reads ~/.dsh/sessions unless a path is given. Its findings quote real paths
# and real commands, so a screen from a live run is not a screen to publish.
#
# Fresh-clone contract: no database and no pre-built jar required. If backend/target
# holds no jar the script builds one (skipping tests); the index file is created next
# to the jar on first start and the corpus is indexed automatically.
#
# Ctrl-C stops both processes — and their children. `&` hands back a wrapper pid
# (the subshell), not the JVM's, and killing the wrapper leaves the listener holding
# its port, so each side is started as a process-group leader and the trap kills the
# exact group: the backend job `exec`s the JVM so $! *is* the JVM, and the frontend
# job's group contains npm, ng and the dev server's node.
#
# Both jobs take stdin from /dev/null. `set -m` makes them background process groups, and
# the Angular dev server installs a keypress listener on stdin ("press h + enter to show
# help"). A background group that reads the controlling terminal is stopped by the kernel
# with SIGTTIN, which froze the dev server partway through startup — after printing its
# notice, sometimes before it had bound 4300. Detached from a terminal the read returns EOF
# and the server starts normally; only the help key is lost.
#
# The CLI's once-per-machine analytics question has the same shape: with no stdin it reads
# EOF and aborts ("An unhandled exception occurred: User force closed the prompt with 0
# null"). This variable removes the question on a machine with no recorded answer anywhere
# — verified against a HOME holding no angular config, where the prompt otherwise fires.
#
set -euo pipefail
# A path the caller typed is relative to where they typed it, not to this script's directory.
caller=$PWD
from_caller() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *)  printf '%s/%s' "$caller" "$1" ;;
  esac
}
cd "$(dirname "$0")"

mode=${1:-demo}
case "$mode" in
  demo)
    # the default is resolved against the JVM's working directory, which is backend/; a path the
    # caller gives is resolved against the caller's directory, like live and report
    if [ -n "${2:-}" ]; then corpus=$(from_caller "$2"); else corpus=fixtures/sessions; fi
    db=inspector.sqlite
    banner="fixture corpus — synthetic data, safe to screenshot and publish"
    ;;
  live)
    # absolute, because the JVM starts inside backend/
    corpus=$(from_caller "${2:-"$HOME/.dsh/sessions"}")
    if [ ! -d "$corpus" ]; then
      echo "no session directory at '$corpus'" >&2
      echo "pass one as the second argument, or copy logs somewhere and point at it" >&2
      exit 1
    fi
    corpus=$(cd "$corpus" && pwd -P)
    db=inspector-live.sqlite
    banner="LIVE corpus: $corpus — findings contain real paths and commands; do not publish screenshots of this run"
    ;;
  report)
    corpus=$(from_caller "${2:-"$HOME/.dsh/sessions"}")
    if [ ! -d "$corpus" ]; then
      echo "no session directory at '$corpus'" >&2
      exit 1
    fi
    corpus=$(cd "$corpus" && pwd -P)
    db=inspector-report.sqlite
    # "-" is standard output, as for the application (ReportProperties), not a file named "-".
    # Standard output then carries the JSON and nothing else: from here on every line this script
    # or a build prints goes to stderr, and fd 3 keeps the real stdout for the report itself.
    if [ "${3:-}" = - ]; then out=-; exec 3>&1 1>&2
    elif [ -n "${3:-}" ]; then out=$(from_caller "$3"); else out="$PWD/backend/inspector-report.json"; fi
    banner="REPORT from $corpus — the file describes real sessions; do not publish it"
    ;;
  *)
    echo "usage: $(basename "$0") [demo|live] [corpus-directory]" >&2
    echo "       $(basename "$0") report [corpus-directory] [output.json]" >&2
    exit 2
    ;;
esac

# The harness timeline: the fixtures' own in demo, the operator's when they keep one otherwise.
# optional: a missing file is not an error.
timeline=()
if [ "$mode" = demo ]; then
  timeline=(--spring.config.import="optional:file:$PWD/backend/fixtures/harness-timeline.yml")
elif [ -f harness-timeline.yml ]; then
  timeline=(--spring.config.import="optional:file:$PWD/harness-timeline.yml")
fi

# sqlite-jdbc loads its native library through System.load, and the JVM warns about that on every
# start, quoting the jar's path — which sits under the user's home. Granting the access is what the
# warning asks for, and it keeps the path out of the output.
jvm=(--enable-native-access=ALL-UNNAMED)

# Refuse to start on top of a running instance — before anything is built or launched.
#
# Two reasons, and the second is the one that cost an afternoon to find. The obvious one: a
# second backend cannot bind the port and a second dev server cannot bind its own, so starting
# on top of a live instance yields two half-started processes and a log that blames the wrong
# thing. The worse one: if no jar existed yet, this script packages one — and Maven rewrites the
# jar in place. A running JVM holds that same file open and loads classes from it lazily, so
# repackaging underneath it leaves the process resolving classes against a zip whose central
# directory no longer matches its bytes. Every class already loaded keeps working, so the
# dashboard looks healthy while any path it has not taken yet starts returning an empty 500.
# Observed on this machine; docs/AI-NOTES.md has the incident.
#
# The probe is a request rather than a socket listing because `ss` and `lsof` are not both
# present on the platforms this script is meant to run on, and curl already is.
shopt -s nullglob
jars=( backend/target/*.jar )

# A jar older than the source it was built from is not the application in this checkout: the
# script used to build only when no jar existed, so after a pull or an edit it kept starting the
# previous build, and the screen described code that was no longer there.
needs_build=0
if [ ${#jars[@]} -eq 0 ]; then
  needs_build=1
elif [ -n "$(find backend/src/main backend/pom.xml -newer "${jars[0]}" -print -quit)" ]; then
  needs_build=1
fi

# demo and live bind both ports; report binds none, so to it only a backend JVM matters, and only
# when the jar it may be running from has to be rebuilt. A dev server holds no jar.
probes=()
[ "$mode" != report ] && probes+=("frontend http://127.0.0.1:4300/")
if [ "$mode" != report ] || [ "$needs_build" -eq 1 ]; then
  probes+=("backend http://127.0.0.1:8091/api/overview")
fi
for probe in ${probes[@]+"${probes[@]}"}; do
  what=${probe%% *}
  url=${probe#* }
  if curl -sf -m 2 -o /dev/null "$url" 2>/dev/null; then
    printf '%s already answers on %s\n' "$what" "$url" >&2
    if [ "$what" = backend ] && [ "$needs_build" -eq 1 ]; then
      printf 'stop that instance first: repackaging the jar under a running JVM corrupts its classpath\n' >&2
    else
      printf 'stop that instance first: this mode serves on the same port\n' >&2
    fi
    exit 1
  fi
done

if [ "$needs_build" -eq 1 ]; then
  echo "no current jar under backend/target — building (mvn -q -DskipTests package)"
  ( cd backend && mvn -q -DskipTests package )
  jars=( backend/target/*.jar )
  if [ ${#jars[@]} -eq 0 ]; then
    echo "the build produced no jar; aborting" >&2
    exit 1
  fi
fi
jar_path="target/${jars[0]##*/}"

if [ "$mode" = report ]; then
  judge=()
  [ -n "${INSPECTOR_GROUP_BY:-}" ] && judge+=(--inspector.report.group-by="$INSPECTOR_GROUP_BY")
  [ -n "${INSPECTOR_BASELINE:-}" ] && judge+=(--inspector.report.baseline="$INSPECTOR_BASELINE")
  [ -n "${INSPECTOR_CANDIDATE:-}" ] && judge+=(--inspector.report.candidate="$INSPECTOR_CANDIDATE")
  dest=$out
  if [ "$out" = - ]; then
    # The application logs to stdout (already sent to stderr above), so it writes the report to a
    # private temporary file (mktemp: mode 0600) that is printed to fd 3 and removed at the end,
    # or removed on failure.
    dest=$(mktemp "${TMPDIR:-/tmp}/inspector-report.XXXXXX")
    trap 'rm -f "$dest"' EXIT
  fi
  echo "corpus:   $banner"
  ( cd backend && java "${jvm[@]}" -jar "$jar_path" --spring.main.web-application-type=none \
      --reindex --inspector.corpus="$corpus" --inspector.db="$db" --inspector.report.path="$dest" \
      ${timeline[@]+"${timeline[@]}"} ${judge[@]+"${judge[@]}"} ) < /dev/null
  if [ "$out" = - ]; then
    cat "$dest" >&3
    echo "report:   standard output"
  else
    echo "report:   $out"
  fi
  exit 0
fi

# set -m puts every background job in its own process group, led by the job's pid. The
# group ids are what the trap kills; with plain job control the children (the JVM, the
# node dev server) would outlive the script.
set -m

# Index first, as its own process, before anything listens.
#
# The startup runner executes *after* Tomcat is already accepting connections, so a server
# boot opens 8091 about 0.8 s in while the index still needs seconds (168 streams measured
# at ~4 s). A health check against the port, and any browser opened right behind it, then
# reads a partial index and presents it as the whole picture: 37 sessions and 66 findings
# out of 165 and 389, with nothing on screen saying "still working". Running the batch first
# makes "the port answers" mean "the data is complete". The server boot that follows does no
# re-index — the corpus recorded in the database matches, so its gate skips.
echo "indexing ($mode) ..."
( cd backend && java "${jvm[@]}" -jar "$jar_path" --spring.main.web-application-type=none \
    --inspector.corpus="$corpus" --inspector.db="$db" ${timeline[@]+"${timeline[@]}"} ) < /dev/null

( cd backend && exec java "${jvm[@]}" -jar "$jar_path" --inspector.corpus="$corpus" --inspector.db="$db" \
    ${timeline[@]+"${timeline[@]}"} ) < /dev/null &
BE=$!

if [ ! -d frontend/node_modules ]; then
  echo "frontend/node_modules missing — npm install"
  ( cd frontend && npm install )
fi

export NG_CLI_ANALYTICS=false
( cd frontend && npm start ) < /dev/null &
FE=$!

stop_tree() {
  kill -TERM -- -"$BE" 2>/dev/null || true
  kill -TERM -- -"$FE" 2>/dev/null || true
}
trap stop_tree EXIT
trap 'stop_tree; exit 130' INT TERM

echo "mode:     $mode"
echo "corpus:   $banner"
echo "database: backend/$db"

for _ in $(seq 1 60); do
  curl -sf http://127.0.0.1:8091/api/overview >/dev/null && break
  sleep 1
done
if ! curl -sf http://127.0.0.1:8091/api/overview >/dev/null; then
  echo "backend did not come up on 8091 within 60 s; its output is above" >&2
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

# Printed only once both have answered. Announcing the URL before the check is exactly
# how a dev server that never started gets reported as started.
echo "backend   http://127.0.0.1:8091"
echo "frontend  http://127.0.0.1:4300   (the dashboard — Ctrl-C stops both)"

# Block until either side dies, then stop both.
#
# Not `wait -n`: the jobs live in their own process groups (set -m), so a terminal's
# Ctrl-C reaches this script but never them, and bash does not run a trap while it is
# blocked in `wait` — the script sat there with both children alive after every
# interrupt. Polling with a short sleep lets the signal land between iterations.
# `kill -0` is not enough as a liveness test either: an exited child stays a zombie
# until this shell reaps it and its pid still answers, so the state is read and Z
# counts as dead.
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
