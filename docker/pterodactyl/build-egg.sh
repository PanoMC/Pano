#!/bin/bash
# Writes egg-pano.json with install.sh as its install script.
#   bash docker/pterodactyl/build-egg.sh           # regenerate
#   bash docker/pterodactyl/build-egg.sh --check   # fail when egg-pano.json is stale
set -euo pipefail
dir=$(cd "$(dirname "$0")" && pwd)

script=$(cat "$dir/install.sh")

# One line, no script (egg repo rule). The yolk runs it as `exec env <line>` after an `eval echo`, so the
# leading PANO_HTTP_PORT=... is an env assignment and $(( )) / $( ) are expanded:
# - the heap comes from the panel's memory limit, not container detection: Java 11 before 11.0.16 (the
#   java_11 yolk ships 11.0.13) sizes it from the host's RAM and commits all of it unless -Xms is set;
#   SERVER_MEMORY=0 (unlimited) counts as 2 GB
# - .pano-jar names the jar (re-read on every start, puts Pano in container mode): after a self-update
#   Pano exits with 75 and Wings restarts the server on the new jar
# - user.home: Wings runs the container as its own uid, which has no passwd entry
startup='PANO_HTTP_PORT={{SERVER_PORT}} java -Xms64M -Xmx$(( ({{SERVER_MEMORY}} > 0 ? {{SERVER_MEMORY}} : 2048) * {{JAVA_RAM_PERCENT}} / 100 ))M -Duser.home=/home/container -jar $(cat .pano-jar) -nogui'

egg=$(jq -n --arg script "$script" --arg startup "$startup" '{
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
  "startup": $startup,
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
