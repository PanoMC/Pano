#!/usr/bin/env bash
# Runs pano-api (doc 04 section 9) from the sdk package. Locally $PANO_SDK_DIR (default ../theme-core/packages/sdk
# next to this checkout); in CI, set PANO_SDK_DIR empty and the script falls back to `bunx @panomc/sdk pano-api`.
# Usage: scripts/api-v1/tool.sh <command> [options]   e.g. scripts/api-v1/tool.sh extract-routes --check
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

sdk="${PANO_SDK_DIR-$root/../theme-core/packages/sdk}"

if [ -n "$sdk" ]; then
  cli="$sdk/bin/pano-api.js"
  if [ ! -f "$cli" ]; then
    echo "tool.sh: $cli not found; set PANO_SDK_DIR to the theme-core sdk package" >&2
    exit 2
  fi
  cmd=(node "$cli")
else
  cmd=(bunx @panomc/sdk pano-api)
fi

# Commands that scan a checkout default to this repository.
case "${1:-}" in
  extract-routes|migrate-v1|check-paths)
    cd "$root"
    ;;
esac

exec "${cmd[@]}" "$@"
