package com.panomc.platform.hosted

import com.panomc.platform.config.PanoConfig

/**
 * Environment → `config.conf` for container installs (workload-model.md "Env").
 *
 * On Pano Host (`PANO_HOSTED` set) the env is the source of truth for the database, mail relay, HTTP
 * port and trusted proxies and is re-applied on every boot: rotating the internal DB password
 * recreates the container with new env. Elsewhere (self-run image, bare metal with the vars set) it
 * only seeds a config that did not exist yet, so later panel edits win.
 *
 * `PANO_HOST_WORKLOAD_ID`, `PANO_HOST_INSTANCE_SECRET` and `PANO_HOST_API_URL` are read from env by
 * their consumers and never persisted.
 */
class HostedEnvConfig(private val env: Map<String, String> = System.getenv()) {
    companion object {
        /** Port the agent's Traefik routes to; port 80 cannot be bound under `--cap-drop ALL`. */
        const val CONTAINER_HTTP_PORT = 8088

        const val DEFAULT_DB_PORT = 3306
        const val DEFAULT_SMTP_PORT = 587

        val STARTTLS_MODES = setOf("DISABLED", "OPTIONAL", "REQUIRED")

        /** The DB pool size outside Pano Host (and the cap everywhere). */
        const val DEFAULT_DB_POOL_SIZE = 100

        /** Pano Host's per-instance `MAX_USER_CONNECTIONS` when the env does not say (control plane `db.internal.maxConnections`). */
        const val HOSTED_DB_MAX_CONNECTIONS = 40

        /** Pano Host mail sender name (before `@`): letters, digits, `. _ + -`, no leading/trailing/double dot. */
        val SENDER_LOCAL = Regex("^[A-Za-z0-9_+-]+(\\.[A-Za-z0-9_+-]+)*$")
        const val SENDER_LOCAL_MAX = 64

        /** panomc.com's per-workload management page is `<this>/<workloadId>`. */
        const val DEFAULT_MANAGE_URL = "https://panomc.com/host/manage/instances"

        /** Traefik reaches the workload over its private `pw-<id>` bridge network. */
        val PRIVATE_PROXY_RANGES = listOf(
            "127.0.0.1",
            "::1",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "fc00::/7"
        )

        val current by lazy { HostedEnvConfig() }
    }

    class Database(val host: String, val port: Int, val name: String, val user: String, val password: String) {
        val hostWithPort get() = "$host:$port"

        override fun toString() = "Database(host=$host, port=$port, name=$name, user=$user, password=***)"
    }

    class Smtp(val host: String, val port: Int, val user: String, val password: String, val from: String?) {
        override fun toString() = "Smtp(host=$host, port=$port, user=$user, password=***, from=$from)"
    }

    private fun value(key: String): String? = env[key]?.trim()?.takeIf { it.isNotEmpty() }

    private fun port(key: String, default: Int?): Int? {
        val raw = value(key) ?: return default
        return raw.toIntOrNull()?.takeIf { it in 1..65535 } ?: default
    }

    val isHosted: Boolean = value("PANO_HOSTED") != null

    val isContainer: Boolean = isHosted || value("PANO_CONTAINER") == "1"

    val workloadId: String? = value("PANO_HOST_WORKLOAD_ID")

    val instanceSecret: String? = value("PANO_HOST_INSTANCE_SECRET")

    val hostApiUrl: String? = value("PANO_HOST_API_URL")?.trimEnd('/')

    /**
     * Where the owner manages this instance: `PANO_HOST_MANAGE_URL` when it is an http(s) URL (never
     * rendered as a `javascript:` href), else panomc.com's workload page. The panel prefers the control
     * plane's `manageUrl` (right website per environment) over this fallback.
     */
    val manageUrl: String? = if (!isHosted) null else manageUrlOverride()
        ?: (DEFAULT_MANAGE_URL + (workloadId?.let { "/" + java.net.URLEncoder.encode(it, Charsets.UTF_8) } ?: ""))

    /** `PANO_HOST_MANAGE_URL` when set to an http(s) URL: wins over the control plane's link. */
    fun manageUrlOverride(): String? = value("PANO_HOST_MANAGE_URL")
        ?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

    /** Present when host, name and user are all set; the password may be empty. */
    val database: Database? = run {
        val host = value("PANO_DB_HOST") ?: return@run null
        val name = value("PANO_DB_NAME") ?: return@run null
        val user = value("PANO_DB_USER") ?: return@run null

        Database(host, port("PANO_DB_PORT", DEFAULT_DB_PORT)!!, name, user, env["PANO_DB_PASSWORD"] ?: "")
    }

    val smtp: Smtp? = run {
        val host = value("PANO_SMTP_HOST") ?: return@run null

        Smtp(
            host,
            port("PANO_SMTP_PORT", DEFAULT_SMTP_PORT)!!,
            value("PANO_SMTP_USER") ?: "",
            env["PANO_SMTP_PASSWORD"] ?: "",
            value("PANO_SMTP_FROM")
        )
    }

    /** `PANO_SMTP_STARTTLS` (DISABLED, OPTIONAL or REQUIRED) overrides the mode derived from the port. */
    val smtpStartTls: String? = value("PANO_SMTP_STARTTLS")?.uppercase()?.takeIf { it in STARTTLS_MODES }

    /** `PANO_HTTP_PORT`, else 8088 in container mode, else none. */
    val httpPort: Int? = port("PANO_HTTP_PORT", null) ?: if (isContainer) CONTAINER_HTTP_PORT else null

    /** `PANO_DB_MAX_CONNECTIONS`: the DB user's connection limit, when the host sets one. */
    val dbMaxConnections: Int? = value("PANO_DB_MAX_CONNECTIONS")?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * Size of Pano's DB pool: below the DB user's connection limit (`PANO_DB_MAX_CONNECTIONS`, else Pano Host's
     * default on a hosted instance), keeping a fifth (at least 2) for other short-lived connections such as the
     * migration importers. A full pool queues queries instead of opening connections the server refuses
     * ("has exceeded the 'max_user_connections' resource"). Self-hosted without a limit: [DEFAULT_DB_POOL_SIZE].
     */
    val dbPoolSize: Int
        get() {
            val limit = dbMaxConnections ?: (if (isHosted) HOSTED_DB_MAX_CONNECTIONS else return DEFAULT_DB_POOL_SIZE)

            return (limit - maxOf(2, limit / 5)).coerceIn(1, DEFAULT_DB_POOL_SIZE)
        }

    /** The DB step of the setup wizard is owned by the environment. */
    val databaseManaged get() = database != null

    val hasAny get() = database != null || smtp != null || httpPort != null || isHosted

    /** Hosted: every boot. Otherwise only when the config file is being created. */
    fun shouldApply(creatingConfig: Boolean) = hasAny && (isHosted || creatingConfig)

    private fun smtpSsl(smtp: Smtp) = smtp.port == 465

    private fun smtpStartTlsOf(smtp: Smtp) = smtpStartTls ?: when {
        smtpSsl(smtp) -> "DISABLED"
        // Pano Host's SMTP is the Portal mail relay on the private workload network, which
        // offers no STARTTLS: REQUIRED would fail every mail, so never demand it there.
        smtp.port == 587 && !isHosted -> "REQUIRED"
        else -> "OPTIONAL"
    }

    /**
     * Pano Host mail as [apply] would write it, in the panel's `email` shape, or null without host
     * mail. The panel shows it read-only; [withPassword] only for checking it server-side.
     */
    fun hostMail(sender: String?, withPassword: Boolean = false): Map<String, Any?>? {
        val smtp = smtp?.takeIf { isHosted } ?: return null

        return linkedMapOf(
            "hostname" to smtp.host,
            "port" to smtp.port,
            "ssl" to smtpSsl(smtp),
            "starttls" to smtpStartTlsOf(smtp),
            "username" to smtp.user,
            "sender" to (sender?.takeIf { it.isNotBlank() } ?: smtp.from ?: ""),
            // The instance's default sender (`PANO_SMTP_FROM`), what "reset" goes back to.
            "defaultSender" to smtp.from
        ).apply { if (withPassword) put("password", smtp.password) }
    }

    /**
     * The `host-sender` to store for the Pano Host mail sender [input]: only the name before `@` is the
     * customer's, the domain stays the default's (`PANO_SMTP_FROM`). Takes a bare name or a full address
     * at that domain; null = the default (empty or equal to it). Throws [IllegalArgumentException] for
     * another domain, an invalid name or when there is no default sender to take the domain from.
     */
    fun hostSender(input: String): String? {
        val trimmed = input.trim()
        val default = smtp?.from

        if (trimmed.isEmpty() || trimmed.equals(default, ignoreCase = true)) return null

        val domain = default?.substringAfterLast('@', "")?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("no default sender")
        val local = trimmed.substringBeforeLast('@')

        require(!trimmed.contains('@') || trimmed.substringAfterLast('@').equals(domain, ignoreCase = true)) { "foreign domain" }
        require(local.length <= SENDER_LOCAL_MAX && SENDER_LOCAL.matches(local)) { "invalid name" }

        return "$local@$domain".takeUnless { it.equals(default, ignoreCase = true) }
    }

    /**
     * Writes the env values into [config] and returns the names of the keys that changed (never
     * their values, which include secrets).
     */
    fun apply(config: PanoConfig, creatingConfig: Boolean = false): List<String> {
        val changed = mutableListOf<String>()

        fun <T> set(key: String, current: T, new: T, write: (T) -> Unit) {
            if (current != new) {
                write(new)
                changed += key
            }
        }

        database?.let { db ->
            val c = config.database
            set("database.type", c.type, "mariadb") { c.type = it }
            set("database.host", c.host, db.hostWithPort) { c.host = it }
            set("database.name", c.name, db.name) { c.name = it }
            set("database.username", c.username, db.user) { c.username = it }
            set("database.password", c.password, db.password) { c.password = it }
        }

        // Pano Host: the customer's own mail settings (saved in the panel, `host-managed = false`) are kept.
        smtp?.takeIf { !isHosted || config.email.hostManaged }?.let { smtp ->
            val c = config.email
            val ssl = smtpSsl(smtp)
            val starttls = smtpStartTlsOf(smtp)

            // On only in a new config: afterwards turning mail off in the panel must survive a boot.
            if (creatingConfig || !isHosted) set("email.enabled", c.enabled, true) { c.enabled = it }
            set("email.hostname", c.hostname, smtp.host) { c.hostname = it }
            set("email.port", c.port, smtp.port) { c.port = it }
            set("email.username", c.username, smtp.user) { c.username = it }
            set("email.password", c.password, smtp.password) { c.password = it }
            set("email.ssl", c.ssl, ssl) { c.ssl = it }
            set("email.starttls", c.starttls, starttls) { c.starttls = it }
            // Pano Host: the customer's sender name (`host-sender`, at the default's domain) wins over the default.
            val sender = c.hostSender?.takeIf { isHosted }?.let { runCatching { hostSender(it) }.getOrNull() } ?: smtp.from
            sender?.let { from -> set("email.sender", c.sender, from) { c.sender = it } }
        }

        httpPort?.let { port -> set("server.http-port", config.server.httpPort, port) { config.server.httpPort = it } }

        if (isHosted) {
            val proxies = config.server.trustedProxies
            val merged = proxies + PRIVATE_PROXY_RANGES.filter { it !in proxies }
            set("server.trusted-proxies", proxies, merged) { config.server.trustedProxies = it }
        }

        return changed
    }
}
