#!/usr/bin/env bash
# Run gradle for RoadGuard with the dedicated release signing key loaded.
#
# The key material lives in /root/roadguard-signing/credentials.env (mode 600)
# and is only ever passed through the environment — never on the command line,
# never in the repo.
#
# Usage:
#   tools/roadguard-gradle.sh :app:testDebugUnitTest :app:assembleDebug --offline
#   tools/roadguard-gradle.sh :app:assembleRelease --offline
set -euo pipefail

CRED="${ROADGUARD_CREDENTIALS:-/root/roadguard-signing/credentials.env}"
if [ ! -f "$CRED" ]; then
  echo "signing credentials not found: $CRED" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a; . "$CRED"; set +a

cd "$(dirname "$0")/.."
exec ./gradlew "$@"
