#!/bin/bash
# Writes egg-pano.json from install.sh + start.sh (start.sh goes in at install.sh's __START_SH__ line).
#   bash docker/pterodactyl/build-egg.sh           # regenerate
#   bash docker/pterodactyl/build-egg.sh --check   # fail when egg-pano.json is stale
set -euo pipefail
dir=$(cd "$(dirname "$0")" && pwd)

script=$(awk -v start="$dir/start.sh" '
  $0 == "__START_SH__" { while ((getline l < start) > 0) print l; next }
  { print }
' "$dir/install.sh")

egg=$(jq -n --arg script "$script" '{
  "meta": {
    "version": "PTDL_v2",
    "update_url": "https://raw.githubusercontent.com/PanoMC/Pano/main/docker/pterodactyl/egg-pano.json"
  },
  "exported_at": "2026-10-29T00:00:00+00:00",
  "name": "Pano",
  "author": "support@panomc.com",
  "description": "Pano is an open-source web platform for Minecraft servers: website, web store and management panel in one. Create a database for the server (Databases tab) and fill the database variables, or enter it in the setup wizard. https://panomc.com",
  "features": null,
  "docker_images": {
    "Java 21": "ghcr.io/pterodactyl/yolks:java_21",
    "Java 25": "ghcr.io/pterodactyl/yolks:java_25",
    "Java 17": "ghcr.io/pterodactyl/yolks:java_17",
    "Java 11": "ghcr.io/pterodactyl/yolks:java_11"
  },
  "file_denylist": [],
  "startup": "bash start.sh",
  "config": {
    "files": "{}",
    "startup": "{\r\n    \"done\": \"ready to rock & roll\"\r\n}",
    "logs": "{}",
    "stop": "stop"
  },
  "scripts": {
    "installation": {
      "script": $script,
      "container": "ghcr.io/pterodactyl/installers:debian",
      "entrypoint": "bash"
    }
  },
  "variables": [
    {
      "name": "Pano version",
      "description": "A version such as 1.1.0, or latest for the newest release of the channel below. Reinstall the server to apply; Pano can also update itself from its panel.",
      "env_variable": "PANO_VERSION",
      "default_value": "latest",
      "user_viewable": true,
      "user_editable": true,
      "rules": "required|string|max:40",
      "field_type": "text"
    },
    {
      "name": "Release channel",
      "description": "Channel that latest installs from: release, beta or alpha.",
      "env_variable": "PANO_CHANNEL",
      "default_value": "release",
      "user_viewable": true,
      "user_editable": true,
      "rules": "required|string|in:release,beta,alpha",
      "field_type": "text"
    },
    {
      "name": "Java memory (%)",
      "description": "Share of the server memory the JVM may use. The rest is left to the website processes Pano runs next to it (usually 50 MB, at most 200 MB each).",
      "env_variable": "JAVA_RAM_PERCENT",
      "default_value": "50",
      "user_viewable": true,
      "user_editable": true,
      "rules": "required|integer|between:25,90",
      "field_type": "text"
    },
    {
      "name": "Database host",
      "description": "MariaDB / MySQL host. Read on the first start only; leave empty to enter the database in the setup wizard.",
      "env_variable": "PANO_DB_HOST",
      "default_value": "",
      "user_viewable": true,
      "user_editable": true,
      "rules": "nullable|string|max:255",
      "field_type": "text"
    },
    {
      "name": "Database port",
      "description": "MariaDB / MySQL port.",
      "env_variable": "PANO_DB_PORT",
      "default_value": "3306",
      "user_viewable": true,
      "user_editable": true,
      "rules": "required|integer|between:1,65535",
      "field_type": "text"
    },
    {
      "name": "Database name",
      "description": "Name of the database.",
      "env_variable": "PANO_DB_NAME",
      "default_value": "",
      "user_viewable": true,
      "user_editable": true,
      "rules": "nullable|string|max:64",
      "field_type": "text"
    },
    {
      "name": "Database user",
      "description": "Database user name.",
      "env_variable": "PANO_DB_USER",
      "default_value": "",
      "user_viewable": true,
      "user_editable": true,
      "rules": "nullable|string|max:64",
      "field_type": "text"
    },
    {
      "name": "Database password",
      "description": "Database password.",
      "env_variable": "PANO_DB_PASSWORD",
      "default_value": "",
      "user_viewable": true,
      "user_editable": true,
      "rules": "nullable|string|max:255",
      "field_type": "text"
    }
  ]
}')

if [ "${1:-}" = --check ]; then
  if [ "$egg" != "$(cat "$dir/egg-pano.json")" ]; then
    echo "egg-pano.json is stale, run: bash docker/pterodactyl/build-egg.sh" >&2
    exit 1
  fi
  echo "egg-pano.json is up to date"
else
  printf '%s\n' "$egg" >"$dir/egg-pano.json"
  echo "wrote $dir/egg-pano.json"
fi
