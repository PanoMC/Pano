package com.panomc.platform.util

import io.vertx.core.Future
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.httpproxy.Body
import io.vertx.httpproxy.MediaType
import io.vertx.httpproxy.ProxyContext
import io.vertx.httpproxy.ProxyInterceptor
import io.vertx.httpproxy.ProxyResponse

/**
 * Development mode only: when a UI upstream Pano did not start itself (`init-ui = false`, or a theme dev server
 * in `frontend.dev-url`) cannot be reached, a page navigation gets an HTML page that says which UI is missing, on
 * which address, and how to start it, instead of the proxy's empty 502 (a blank page). The page reloads by itself
 * once the UI answers.
 *
 * Only a GET that asks for HTML is answered this way; assets, API calls and WebSocket upgrades keep the plain 502.
 * [active] is read per request (development mode can be switched while Pano runs, and a UI can be started later).
 * The interceptor sits after the retry interceptor's caller, so it only sees a request that failed for good.
 */
class DevUiDownInterceptor(
    private val active: () -> Boolean,
    private val info: () -> Info,
) : ProxyInterceptor {
    /**
     * @param title    what is missing, e.g. "The panel is not running"
     * @param address  where Pano forwards to, e.g. "http://127.0.0.1:3001"
     * @param folder   the source folder to start it from, e.g. "panel-ui"; null when there is no command to give
     * @param command  the command that starts it, e.g. "bun run dev"
     * @param note     one extra sentence (HTML-escaped) shown under the command
     */
    data class Info(
        val title: String,
        val address: String,
        val folder: String?,
        val command: String = "bun run dev",
        val note: String? = null,
    )

    override fun handleProxyRequest(context: ProxyContext): Future<ProxyResponse> {
        val request = context.request()

        return context.sendRequest().recover { error ->
            if (!active() || !isPageNavigation(request.method, request.headers().get("Accept"), request.headers().get("Upgrade"))) {
                return@recover Future.failedFuture(error)
            }

            val html = render(info())
            val response = request.response()
                .setStatusCode(503)
                .setStatusMessage("UI Not Running")
                .putHeader("Cache-Control", "no-store")
                .putHeader(HEADER, "1")
                // the media type goes on the Body: a bare Body.body(buffer) is sent as application/octet-stream
                .setBody(Body.body(Buffer.buffer(html), MediaType.parse("text/html; charset=utf-8")))

            Future.succeededFuture(response)
        }
    }

    companion object {
        /** Marks the page, so its own poll can tell it from the real UI. */
        const val HEADER = "X-Pano-Dev-Ui-Down"

        fun isPageNavigation(method: HttpMethod, accept: String?, upgrade: String?): Boolean =
            method == HttpMethod.GET && upgrade == null && accept?.contains("text/html", ignoreCase = true) == true

        private fun escape(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        fun render(info: Info): String {
            val commandBlock = info.folder?.let {
                """
                <p>Start it in another terminal:</p>
                <pre><code>cd ${escape(it)}
${escape(info.command)}</code></pre>
                """.trimIndent()
            } ?: ""
            val note = info.note?.let { "<p>${escape(it)}</p>" } ?: ""

            return """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${escape(info.title)} · Pano development mode</title>
<style>
  :root { color-scheme: light dark; --bg: #f5f6f8; --card: #fff; --text: #1d2125; --muted: #5f6b76; --border: #dfe3e8; --code: #f0f2f5; --accent: #0d6efd; }
  @media (prefers-color-scheme: dark) { :root { --bg: #15181c; --card: #1e2227; --text: #e6e8eb; --muted: #9aa4ae; --border: #2e343b; --code: #262b31; --accent: #6ea8fe; } }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 16px;
         background: var(--bg); color: var(--text); font: 15px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { width: 100%; max-width: 560px; background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 28px; }
  .badge { display: inline-block; font-size: 12px; font-weight: 600; letter-spacing: .02em; color: var(--accent);
           border: 1px solid currentColor; border-radius: 999px; padding: 2px 10px; margin-bottom: 14px; }
  h1 { font-size: 20px; margin: 0 0 10px; }
  p { margin: 0 0 12px; color: var(--muted); }
  code { font: 13px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; }
  p code { background: var(--code); border-radius: 4px; padding: 1px 5px; color: var(--text); }
  pre { margin: 0 0 14px; background: var(--code); border: 1px solid var(--border); border-radius: 8px; padding: 12px 14px; overflow-x: auto; }
  pre code { color: var(--text); }
  .status { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--muted); margin: 18px 0 0; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: #f0ad4e; animation: pulse 1.4s ease-in-out infinite; }
  @keyframes pulse { 50% { opacity: .3; } }
</style>
</head>
<body>
<main>
  <span class="badge">Development mode</span>
  <h1>${escape(info.title)}</h1>
  <p>Pano forwards this page to <code>${escape(info.address)}</code>, but nothing answers there.</p>
  $commandBlock
  $note
  <p>To let Pano start the built UIs itself instead, set <code>init-ui = true</code> in <code>config.conf</code> and restart Pano.</p>
  <div class="status"><span class="dot"></span><span>Waiting for it to start. This page reloads by itself.</span></div>
</main>
<script>
  (function poll() {
    setTimeout(function () {
      fetch(location.href, { headers: { Accept: "text/html" }, cache: "no-store" })
        .then(function (r) { if (!r.headers.get("$HEADER")) location.reload(); else poll(); })
        .catch(poll);
    }, 2000);
  })();
</script>
</body>
</html>
            """.trimIndent()
        }
    }
}
