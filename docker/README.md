# Pano Docker images

All images live in one public GHCR package, `ghcr.io/panomc/pano-web-platform`, built by
`.github/workflows/release.yml` on every release (linux/amd64 + linux/arm64).

| Tag | What it is | Used by |
| --- | --- | --- |
| `<version>` (e.g. `1.0.0-alpha.520`) | Full image: runtime-jre11 + that release jar | self-hosters, pinned |
| `alpha`, `beta` | Full image, newest release of that channel | self-hosters testing prereleases |
| `latest` | Full image, newest stable (`main`) release | self-hosters (`docker/compose.yaml` default) |
| `runtime-jre<N>` (N = 11, 17, 21, 25) | Runtime base: JRE, tini, Bun, launcher, no release | Pano Host Portals |
| `runtime-jre<N>-<version>` | The runtime base built with that release | pins |

Sources: `runtime/` (runtime base, `pano-launcher.sh`), `pano/` (full image, `pano-seed.sh`).

## Run

Docker Compose with MariaDB (`compose.yaml` here, mirrored 1:1 in the docs):

```sh
mkdir pano && cd pano
curl -fsSLO https://raw.githubusercontent.com/PanoMC/Pano/main/docker/compose.yaml
echo "PANO_DB_PASSWORD=$(openssl rand -hex 24)" > .env
docker compose up -d
```

Then open `http://<server>:8088` and finish the setup wizard; the database step is already filled
from the environment. `PANO_TAG` (in `.env`) picks the tag, `PANO_PORT` the host port.

Plain `docker run` against an existing MariaDB/MySQL:

```sh
docker run -d --name pano --restart unless-stopped -p 8088:8088 -v pano-data:/data \
  -e PANO_DB_HOST=db.example.com -e PANO_DB_NAME=pano -e PANO_DB_USER=pano -e PANO_DB_PASSWORD=... \
  ghcr.io/panomc/pano-web-platform:latest
```

Environment (all optional; without `PANO_DB_*` the wizard asks for the database):

| Variable | Meaning |
| --- | --- |
| `PANO_DB_HOST`, `PANO_DB_PORT` (3306), `PANO_DB_NAME`, `PANO_DB_USER`, `PANO_DB_PASSWORD` | Database, seeds `config.conf` on first start only |
| `PANO_SMTP_HOST`, `PANO_SMTP_PORT` (587), `PANO_SMTP_USER`, `PANO_SMTP_PASSWORD`, `PANO_SMTP_FROM`, `PANO_SMTP_STARTTLS` | Mail, first start only |
| `PANO_HTTP_PORT` | Port Pano listens on inside the container (default 8088) |
| `PANO_JVM_ARGS` | JVM args, space separated (default `-XX:MaxRAMPercentage=75`) |

Everything Pano writes (config, plugins, themes, uploads, the jar) lives in the `/data` volume. The
container runs as uid 10000; a bind mount must be writable by it (`chown -R 10000:10000 <dir>`).

## Upgrade

`docker compose pull && docker compose up -d`. On start, `pano-seed` installs the image's jar into
`/data` when the image carries a different release than the one it seeded last. An in-panel update
between image upgrades stays in place until the next image change. To go back, set `PANO_TAG` to the
older version; the database is not downgraded, so back it up first.

## Visibility (package owner, one time)

The GitHub REST API cannot change a container package's visibility, so it is done in the web UI after
the first release has pushed the package:

1. Open <https://github.com/orgs/PanoMC/packages/container/package/pano-web-platform>.
2. **Package settings** → **Danger Zone** → **Change visibility** → **Public**, type
   `pano-web-platform` to confirm. This covers every tag, the `runtime-jre<N>` ones included.
3. If **Public** is greyed out: Organization settings → **Packages** → allow public packages, then retry.
4. Under **Manage Actions access**, PanoMC/Pano should have **Write** (it is linked through the
   `org.opencontainers.image.source` label; add it by hand otherwise).
5. Optional: delete the old private packages `pano` and `pano-runtime` in their own Danger Zone.

## Test locally

```sh
bash docker/runtime/test.sh 11     # runtime + launcher, stand-in jars, agent hardening flags
bash docker/pano/test.sh           # full image seeding
bash docker/runtime/e2e.sh build/libs/Pano-<version>.jar 11   # real jar + MariaDB, like a Portal runs it
cp build/libs/Pano-<version>.jar* docker/pano/release/ && bash docker/pano/compose-test.sh
                                   # compose.yaml + real jar: setup wizard smoke, /data survives down/up
```

`PH_PREFIX` names the local images and containers (default `ph-w4`; `ph-compose` for compose-test.sh, which serves on `PANO_PORT`, default 18088).
