# Pano egg for Pterodactyl / Pelican

`egg-pano.json` (Pterodactyl) and `egg-pano.yaml` (Pelican) run Pano as a server on a game panel, so
hosting companies can offer it next to their Minecraft servers.

| File | What it is |
| --- | --- |
| `egg-pano.json` | The Pterodactyl egg, generated: never edit it by hand |
| `egg-pano.yaml` | The Pelican egg (`PLCN_v3`): exported from a Pelican panel after importing `egg-pano.json` and switching the images to `ghcr.io/pelican-eggs/yolks:java_*` and the install container to `ghcr.io/pelican-eggs/installers:debian` |
| `install.sh` | Install script: downloads the jar (sha256 checked) and points `.pano-jar` at it |
| `build-egg.sh` | Builds the egg from `install.sh` and the startup line it holds (`--check` fails on a stale egg) |
| `test.sh` | Runs the egg the way Wings does, without a panel |

## Use

1. Pterodactyl: Admin → Nests → Import Egg → `egg-pano.json`. Pelican: Eggs → Import → `egg-pano.yaml`
   (or its raw URL).
2. Create a server with the egg. Java 11 or newer (the image list starts with Java 21), one
   allocation (the web port), **1 GB** memory recommended: the JVM takes half of it (`JAVA_RAM_PERCENT`, default 50), the
   other half is left to the three website processes (setup, panel, theme; about 50 MB each when
   idle, Pano restarts one that passes 200 MB). Measured right after the first start on 1 GB:
   about 600 MB on Java 21, about 720 MB on Java 11.
3. Optional: create a database on the server's Databases tab and copy it into the database
   variables. They are read on the first start only; left empty, the setup wizard asks for them.
4. Start the server, open `http://<allocation ip>:<port>` and finish the setup wizard.

A reinstall upgrades the jar (to `PANO_VERSION`, or the newest of `PANO_CHANNEL`) and keeps all data.
Pano's own update from the panel works too: it stages the new jar, points `.pano-jar` at it and exits
with 75; Wings restarts the server, and the startup command runs the jar `.pano-jar` names.

The startup command is a single line with no start script (a rule of the public egg repos). It passes
the allocation as `PANO_HTTP_PORT`, which Pano applies on every start (since the release after 1.1.0;
older versions take it only on the first start), and sizes the heap from the server's memory limit.

Not available on Pterodactyl: running managed Minecraft servers through `pano-node` (the panel host
already manages the servers). Connecting servers with the Pano MC plugin works as usual.

## Change

Edit `install.sh` or the startup line in `build-egg.sh`, then rebuild and test, and export
`egg-pano.yaml` again from a Pelican panel (see the table above):

```sh
bash docker/pterodactyl/build-egg.sh
bash docker/pterodactyl/test.sh java_11      # needs Docker; also java_17, java_21, java_25
PANO_TEST_JAR=build/libs/Pano-<version>.jar bash docker/pterodactyl/test.sh java_11   # a local build
```
