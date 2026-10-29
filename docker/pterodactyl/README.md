# Pano egg for Pterodactyl / Pelican

`egg-pano.json` runs Pano as a server on a Pterodactyl (or Pelican) panel, so hosting companies can
offer it next to their Minecraft servers.

| File | What it is |
| --- | --- |
| `egg-pano.json` | The egg, generated: never edit it by hand |
| `install.sh` | Install script: downloads the jar (sha256 checked), writes `.pano-jar` and `start.sh` |
| `start.sh` | Startup: syncs `http-port` with the allocation, relaunches on exit 75 (self-update / restart) |
| `build-egg.sh` | Builds the egg from the two scripts (`--check` fails on a stale egg) |
| `test.sh` | Runs the egg the way Wings does, without a panel |

## Use

1. Admin → Nests → Import Egg → `egg-pano.json`.
2. Create a server with the egg. Java 11 or newer (the image list starts with Java 21), one
   allocation (the web port), at least **1.5 GB** memory: Pano runs up to three website processes
   (setup, panel, theme, about 200 MB each) next to the JVM.
3. Optional: create a database on the server's Databases tab and copy it into the database
   variables. They are read on the first start only; left empty, the setup wizard asks for them.
4. Start the server, open `http://<allocation ip>:<port>` and finish the setup wizard.

A reinstall upgrades the jar (to `PANO_VERSION`, or the newest of `PANO_CHANNEL`) and keeps all data.
Pano's own update from the panel works too: it stages the new jar and exits with 75, `start.sh`
starts it again.

Not available on Pterodactyl: running managed Minecraft servers through `pano-node` (the panel host
already manages the servers). Connecting servers with the Pano MC plugin works as usual.

## Change

Edit `install.sh` / `start.sh`, then:

```sh
bash docker/pterodactyl/build-egg.sh
bash docker/pterodactyl/test.sh java_11      # needs Docker; also java_17, java_21, java_25
```
