#!/usr/bin/env bash
# Prints `tags=<comma list>` for the pano_image release job (docker/build-push-action `tags`).
#   always: <version> and the branch channel (alpha, beta, or latest on main)
#   no stable release yet: beta also moves `latest`; alpha moves it only while no `beta` image exists,
#   so the compose/docs default `latest` always resolves to the most stable channel published so far.
# Inputs: PANO_VERSION, CHANNEL. Overridable for tests: STABLE_TAGS (stable release tags, one per line)
# and BETA_IMAGE_EXISTS (true/false); otherwise they come from `git ls-remote` and the registry.
set -euo pipefail
IMAGE="${IMAGE:-ghcr.io/panomc/pano-web-platform}"
: "${PANO_VERSION:?}" "${CHANNEL:?}"

if [ -z "${STABLE_TAGS+x}" ]; then
  STABLE_TAGS="$(git ls-remote --tags --refs origin 'v*' | sed 's#.*refs/tags/##' | grep -v -- '-' || true)"
fi

tags="$IMAGE:$PANO_VERSION,$IMAGE:$CHANNEL"
if [ "$CHANNEL" != latest ] && [ -z "$STABLE_TAGS" ]; then
  case "$CHANNEL" in
    beta) tags="$tags,$IMAGE:latest" ;;
    alpha)
      if [ -z "${BETA_IMAGE_EXISTS+x}" ]; then
        if docker buildx imagetools inspect "$IMAGE:beta" >/dev/null 2>&1; then BETA_IMAGE_EXISTS=true; else BETA_IMAGE_EXISTS=false; fi
      fi
      [ "$BETA_IMAGE_EXISTS" = true ] || tags="$tags,$IMAGE:latest"
      ;;
  esac
fi
echo "tags=$tags"
