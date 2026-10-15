# behaviour-smoke.mjs (CX-05)

Behaviour checks on a real instance: what no Kotlin unit ran. Sixteen independent checks (register and sign in, CSRF, origins and CORS,
front-end key and site token, WebSocket ticket, fallback pages, URL map, front-end settings, webhooks and restart delivery, version gate,
package files, widgets, OpenAPI, compatibility reconcile, sitemap, plugin path rule and panel refusals). Each prints `ok | FAIL | skip <name>: <detail>`, cleans up what it made;
the JSON report lists every FAIL with the file that most likely owns it. Exit 1 on a FAIL.

Run (one command = start, test, stop; the slot is taken by `of-slot.sh`):

    of-slot.sh bun /home/kahverengi/Projects/Pano/pano-showcase/scripts/shots.mjs --jars current --theme vanilla \
      [--vanilla-build <themes/vanilla-theme/build> --panel-build <panel-ui/build>] -- \
      node scripts/api-v1/behaviour-smoke.mjs --out /home/kahverengi/Projects/Pano/.open-frontend-run/gate/behaviour.json

Reads `PANO_URL`, `PANO_ADMIN_USER`, `PANO_ADMIN_PASSWORD` and the `PANO_OF_SLOT_*` variables (set by shots.mjs inside the slot).
Checks 8 (descriptor refresh), 9 (pending delivery after restart) and 11 (`client/fallback.css`) are expected FAIL until CX-06.

The `--jars current` instance needs a theme and panel built after the `/api/v1` client migration (the frozen bundle ones are older);
pass `--vanilla-build` and `--panel-build` with fresh builds.
