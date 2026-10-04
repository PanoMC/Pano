#!/usr/bin/env bash
# Unit test for image-tags.sh (no network: STABLE_TAGS / BETA_IMAGE_EXISTS are injected).
set -euo pipefail
cd "$(dirname "$0")"
I=ghcr.io/panomc/pano
fail=0
check() { # name expected env...
  local name="$1" want="$2"; shift 2
  local got; got="$(env "$@" bash image-tags.sh)"
  if [ "$got" = "tags=$want" ]; then echo "ok   $name"; else echo "FAIL $name: got '$got' want 'tags=$want'"; fail=1; fi
}
check "main moves latest"            "$I:1.0.0,$I:latest"                PANO_VERSION=1.0.0 CHANNEL=latest STABLE_TAGS=v1.0.0
check "beta, no stable -> latest"    "$I:1.0.0-beta.35,$I:beta,$I:latest" PANO_VERSION=1.0.0-beta.35 CHANNEL=beta STABLE_TAGS=
check "beta, stable exists"          "$I:1.1.0-beta.1,$I:beta"           PANO_VERSION=1.1.0-beta.1 CHANNEL=beta STABLE_TAGS=v1.0.0
check "alpha, no stable, no beta"    "$I:1.0.0-alpha.520,$I:alpha,$I:latest" PANO_VERSION=1.0.0-alpha.520 CHANNEL=alpha STABLE_TAGS= BETA_IMAGE_EXISTS=false
check "alpha, no stable, beta image" "$I:1.0.0-alpha.521,$I:alpha"       PANO_VERSION=1.0.0-alpha.521 CHANNEL=alpha STABLE_TAGS= BETA_IMAGE_EXISTS=true
check "alpha, stable exists"         "$I:1.1.0-alpha.1,$I:alpha"         PANO_VERSION=1.1.0-alpha.1 CHANNEL=alpha STABLE_TAGS=v1.0.0 BETA_IMAGE_EXISTS=false
exit $fail
