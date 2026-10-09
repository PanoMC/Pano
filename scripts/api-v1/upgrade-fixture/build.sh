#!/usr/bin/env bash
# Builds the pre-cutover install of the upgrade fixture inside a test-instance slot (database, ports and folder come from the slot):
#   PANO_IT_MARIADB_PASSWORD=... /home/kahverengi/Projects/Pano/pano-open-frontend-spec/tools/of-slot.sh scripts/api-v1/upgrade-fixture/build.sh
#                                                                         [--out dir] [--db d] [--http-port p] [--stub-port p] [--pano-jar f] [--market-jar f] ...
# See fixture.mjs for the options, the inputs and what is built. `build.sh stop` stops the fixture JVM by its recorded PID.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
cmd=build
case "${1:-}" in stop|inputs|build) cmd=$1; shift ;; esac
if [ "$cmd" = build ]; then
  exec node "$here/fixture.mjs" build "$@"
fi
exec node "$here/fixture.mjs" "$cmd" "$@"
