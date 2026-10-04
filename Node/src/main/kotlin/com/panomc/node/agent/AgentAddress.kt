package com.panomc.node.agent

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The Pano address a Pano Agent's first run asks for (SM-76): what the admin typed, made into the
 * base URL the worker pairs with, and checked before anything else is asked -- a typo found now is
 * one question, found at pairing it is the whole run again.
 *
 * Plain JDK (`java.net.http`), because the launcher asks.
 */
object AgentAddress {
    /** What Pano serves next to the agent's jar; only a Pano that knows agents has the route. */
    const val PROBE_PATH = "/api/node/pano-agent.jar.sha256"

    /** The node jar's checksum, which Panos from before the agent already served. */
    const val NODE_PROBE_PATH = "/api/node/pano-node.jar.sha256"

    val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

    /** How a check ended. */
    sealed class Check {
        /** A Pano that takes agents, at [url] (where a redirect ended, if the address redirected). */
        data class Ok(val url: String) : Check()

        /** Nothing answered: the host is unknown, refused, timed out, or its TLS failed. */
        data class Unreachable(val reason: String) : Check()

        /** Something answered, and it is not a Pano. */
        data class NotPano(val status: Int) : Check()

        /** A Pano, from before the Pano Agent. */
        object TooOld : Check()

        /** A Pano that asked to be left alone for a moment (its rate limit). */
        object Busy : Check()
    }

    /**
     * [input] as a base URL: trimmed and unquoted, `https://` when no scheme was typed, without a
     * trailing `/` or `/panel` (the panel's address is what people copy), and without a query.
     * Null when it is not a web address at all.
     */
    fun normalise(input: String): String? {
        var text = input.trim().let { unquote(it) }.trim()

        if (text.isEmpty() || text.any { it.isWhitespace() }) {
            return null
        }

        if (!text.contains("://")) {
            text = "https://$text"
        }

        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase() ?: return null

        var path = uri.rawPath.orEmpty()

        while (true) {
            val trimmed = path.trimEnd('/')
            val next = if (trimmed.endsWith("/panel", ignoreCase = true)) trimmed.dropLast("/panel".length) else trimmed

            if (next == path) break

            path = next
        }

        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

        return "$scheme://$hostPart$port$path"
    }

    /**
     * Asks [url] whether it is a Pano that takes agents: `GET <url>/api/node/pano-agent.jar.sha256`.
     *
     * - 200 with a checksum: yes.
     * - Pano's own JSON 404 on that route: yes -- a Pano with agents that serves no jar of its own
     *   (the agent then came from the release), since only such a Pano has the route at all.
     * - anything else: the node jar's route tells a Pano from before agents (too old) from
     *   something that is not a Pano.
     */
    fun check(url: String, timeout: Duration = DEFAULT_TIMEOUT): Check {
        val client = try {
            HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        } catch (_: Throwable) {
            // A runtime without java.net.http: nothing to check with, and the pairing will tell.
            return Check.Ok(url)
        }

        val first = try {
            get(client, url + PROBE_PATH, timeout)
        } catch (exception: Exception) {
            return Check.Unreachable(describe(exception, timeout))
        }

        if (first.status == 200 && isChecksum(first.body)) {
            return Check.Ok(baseOf(first.finalUrl, url))
        }

        if (first.status == 404 && isPanoError(first.body)) {
            return Check.Ok(baseOf(first.finalUrl, url))
        }

        if (first.status == 429) {
            return Check.Busy
        }

        val node = try {
            get(client, url + NODE_PROBE_PATH, timeout)
        } catch (_: Exception) {
            null
        }

        if (node != null && ((node.status == 200 && isChecksum(node.body)) || (node.status == 404 && isPanoError(node.body)))) {
            return Check.TooOld
        }

        return Check.NotPano(first.status)
    }

    /** One line for the admin about a check that did not pass. */
    fun message(url: String, check: Check, schemeTyped: Boolean): String = when (check) {
        is Check.Ok -> "Pano answered at ${check.url}."

        is Check.Unreachable -> "Could not reach $url (${check.reason})." +
            if (!schemeTyped && url.startsWith("https://")) " If this Pano has no HTTPS, type the address with http:// in front." else ""

        is Check.NotPano -> "$url answered, but it is not a Pano (HTTP ${check.status}). Type your Pano website's address."

        Check.TooOld -> "$url is a Pano too old for the Pano Agent. Update Pano first."

        Check.Busy -> "$url is asking for fewer requests right now. Wait a minute and press Enter to try again."
    }

    private class Answer(val status: Int, val body: String, val finalUrl: String)

    /** Where [finalUrl] (the probe, after redirects) says Pano lives; [asked] when it says nothing. */
    private fun baseOf(finalUrl: String, asked: String): String =
        finalUrl.takeIf { it.endsWith(PROBE_PATH) }?.removeSuffix(PROBE_PATH)?.takeIf { it.isNotEmpty() } ?: asked

    private fun get(client: HttpClient, url: String, timeout: Duration): Answer {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "pano-agent")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

        val body = response.body().use { stream ->
            val bytes = stream.readNBytes(MAX_BODY_BYTES)

            String(bytes, Charsets.UTF_8)
        }

        return Answer(response.statusCode(), body, response.uri().toString())
    }

    private fun isChecksum(body: String): Boolean = Regex("^[0-9a-fA-F]{64}(\\s|$)").containsMatchIn(body.trim())

    /** Pano's error body: `{"result":"error","error":"NOT_EXISTS"}`. */
    private fun isPanoError(body: String): Boolean {
        val compact = body.replace(Regex("\\s"), "")

        return compact.startsWith("{") && compact.contains("\"result\":\"error\"") && compact.contains("\"error\":\"NOT_EXISTS\"")
    }

    private fun describe(exception: Exception, timeout: Duration): String {
        val chain = generateSequence(exception as Throwable) { it.cause }.take(16).toList()

        return when {
            chain.any { it is java.net.http.HttpTimeoutException } -> "no answer within ${timeout.seconds} seconds"

            chain.any { it is java.net.UnknownHostException || it is java.nio.channels.UnresolvedAddressException } ->
                "unknown host"

            chain.any { it is javax.net.ssl.SSLException } ->
                "HTTPS failed: " + (chain.first { it is javax.net.ssl.SSLException }.message ?: "handshake error")

            chain.any { it is java.net.ConnectException } -> "connection refused"

            else -> chain.last().let { it.message?.takeIf { message -> message.isNotBlank() } ?: it.javaClass.simpleName }
        }
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && (value.first() == '\'' || value.first() == '"') && value.last() == value.first()) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    private const val MAX_BODY_BYTES = 8 * 1024
}
