#!/usr/bin/env bash
# Your own session logs, indexed read-only: ./run-live.sh [corpus-directory]
# Defaults to ~/.dsh/sessions. Findings quote real paths and real commands, so this
# run's screens are not for publishing. It indexes into backend/inspector-live.sqlite
# so a demo database is never overwritten by a live one.
exec "$(dirname "$0")/run.sh" live "$@"
