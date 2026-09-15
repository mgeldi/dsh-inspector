#!/usr/bin/env bash
# The synthetic demo: committed fixtures, invented paths, safe to screenshot.
# All the work is in run.sh; this only names the mode so the two ways to run this
# have two obvious names instead of one flag to remember.
exec "$(dirname "$0")/run.sh" demo "$@"
