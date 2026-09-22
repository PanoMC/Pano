package com.panomc.platform.node

import com.github.jknack.handlebars.Handlebars
import com.panomc.platform.AppConstants
import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.util.WebsiteUrlUtil
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The install scripts Pano serves for a node somebody sets up by hand, and the commands that run
 * them.
 *
 * Pano templates them rather than shipping them as static files because everything that makes an
 * install correct is only known here: which release of the daemon matches *this* Pano, where that
 * release is published, and which URL the node should report to. A script that had to be told
 * those by the person running it is a script that gets them wrong.
 *
 * The daemon's release is pinned to Pano's own version, because the two speak a versioned
 * protocol and a node newer than its platform is a support case nobody can debug. When Pano does
 * not know its own version — a development build started outside a jar — the URL falls back to
 * the newest release rather than to a tag that does not exist. Better still, when this install
 * has the jar on disk the script is pointed back at Pano itself, which needs no release to exist
 * anywhere and cannot install a mismatched build.
 *
 * The scripts install nodes only. A Pano Agent is not installed on a machine at all (SM-74): it is
 * the same jar saved as `pano-agent.jar` in a server's folder, and this class builds the one-line
 * commands the panel shows for that too -- download it, run it once with a code, start it from then
 * on ([agentLink]).
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeInstallScriptProvider(
    private val configManager: ConfigManager,
    private val nodeJarProvider: NodeJarProvider
) {
    private val handlebars by lazy { Handlebars() }

    /**
     * Pano's version, or null when this build has no released one.
     *
     * `local-build` is what Gradle stamps a development jar with and there is no release tagged
     * that, so it counts as "unknown" exactly like a missing manifest does: better to install the
     * newest published daemon than to point an installer at a tag that does not exist.
     */
    val version: String?
        get() = releaseVersion()

    /**
     * Where the installer fetches the daemon from.
     *
     * Pano's own copy wins whenever it has one. Handing over the jar this install already runs is
     * both faster and more honest than pointing a stranger's machine at a release: the node ends
     * up on exactly the build whose protocol this Pano speaks, and a development Pano — or one on
     * a network that cannot reach github.com — can set up a node at all, which it otherwise
     * could not. The release URL stays the fallback for an install that has no jar on disk yet.
     *
     * [panoUrlOverride] is for the setups described in [PanoUrlOverride]: when the node reaches
     * Pano somewhere other than the website URL, it has to download the jar there too, or the
     * install fails at its first step.
     */
    fun downloadUrl(panoUrlOverride: String? = null): String =
        downloadUrl(panoUrl(panoUrlOverride), nodeJarProvider.isAvailable(), version)

    /** Where its sha256 is published, which the scripts verify against when the asset exists. */
    fun checksumUrl(panoUrlOverride: String? = null): String = "${downloadUrl(panoUrlOverride)}.sha256"

    /**
     * The URL a node should report to: this Pano's public address, or [override] when the node
     * cannot reach that one. See [PanoUrlOverride] for when that is the case.
     */
    fun panoUrl(override: String? = null): String = override?.trim()?.ifEmpty { null } ?: websiteUrl()

    /** The POSIX shell installer, already filled in for this Pano. */
    fun shellScript(panoUrlOverride: String? = null): String = render(SHELL_TEMPLATE, panoUrlOverride)

    /** The PowerShell installer, already filled in for this Pano. */
    fun powerShellScript(panoUrlOverride: String? = null): String = render(POWERSHELL_TEMPLATE, panoUrlOverride)

    /**
     * The one line an admin pastes into a shell on the machine that should become a node.
     *
     * The pairing code is quoted because it comes from Pano and goes into a shell: it is six
     * digits today, but a quoted argument is what keeps that an implementation detail.
     */
    fun installCommand(code: String, panoUrlOverride: String? = null): String =
        shellInstallCommand(panoUrl(panoUrlOverride), code)

    /**
     * The PowerShell equivalent.
     *
     * `iex` cannot pass arguments to what it runs, so the script is turned into a script block
     * first; that is the only shape of this command that can carry the pairing code.
     */
    fun installCommandWindows(code: String, panoUrlOverride: String? = null): String =
        powerShellInstallCommand(panoUrl(panoUrlOverride), code)

    /**
     * Where a Pano Agent is downloaded from: this Pano's own copy under the agent's name when it has
     * one, and otherwise the node jar of the matching release, which the download command saves as
     * `pano-agent.jar` all the same.
     */
    fun agentJarUrl(panoUrlOverride: String? = null): String =
        agentJarUrl(panoUrl(panoUrlOverride), nodeJarProvider.isAvailable(), version)

    private fun render(templatePath: String, panoUrlOverride: String?): String {
        val source = javaClass.classLoader.getResourceAsStream(templatePath)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("Install script template not found: $templatePath")

        return handlebars.compileInline(source).apply(
            mapOf(
                "version" to (version ?: "latest"),
                "downloadUrl" to downloadUrl(panoUrlOverride),
                "checksumUrl" to checksumUrl(panoUrlOverride),
                "jarName" to JAR_NAME
            )
        )
    }

    private fun websiteUrl() = WebsiteUrlUtil.normalize(configManager.config.websiteUrl)

    companion object {
        const val SHELL_TEMPLATE = "node/install.sh.hbs"
        const val POWERSHELL_TEMPLATE = "node/install.ps1.hbs"

        const val JAR_NAME = LocalNodeJarLocator.JAR_NAME

        /** What Gradle stamps a jar with when it was not built for a release. */
        const val DEV_VERSION = "local-build"

        /**
         * [version] as a real release, or null for a development build or a missing manifest (see
         * the instance [version]). Also the daemon version this Pano hands out, which the panel
         * shows as the `latestVersion` of a node or a Pano Agent (SM-77).
         */
        fun releaseVersion(version: String? = Main.VERSION): String? =
            version?.takeIf { it.isNotBlank() && it != "null" && !it.equals(DEV_VERSION, true) }

        /** The name the agent is saved as in a server's folder, and recognises itself by. */
        const val AGENT_JAR_NAME = LocalNodeJarLocator.AGENT_JAR_NAME

        /** The Java the agent (and the node) needs, shown next to the agent's commands. */
        const val AGENT_JAVA_VERSION = 17

        /** How the server is started once the agent is linked. */
        const val AGENT_START_COMMAND = "java -jar $AGENT_JAR_NAME"

        private val RELEASE_BASE = "https://github.com/${AppConstants.REPO}/releases"

        /**
         * The download URL an installer should use, given where Pano lives, whether Pano has a
         * jar to hand over and which release it belongs to.
         *
         * Pure so the decision can be asserted without a Spring context: it is the one thing that
         * makes an install reach the wrong daemon if it is wrong.
         */
        fun downloadUrl(panoUrl: String, servedByPano: Boolean, version: String?): String = when {
            servedByPano -> "$panoUrl/api/node/$JAR_NAME"
            version != null -> "$RELEASE_BASE/download/v$version/$JAR_NAME"
            else -> "$RELEASE_BASE/latest/download/$JAR_NAME"
        }

        /** The one-line POSIX install command for [url] and [code]. Pure, so the exact text can be asserted. */
        fun shellInstallCommand(url: String, code: String): String =
            "curl -fsSL $url/api/node/install.sh | sh -s -- --pano '$url' --code '${shellQuote(code)}'"

        /** The PowerShell counterpart of [shellInstallCommand]. */
        fun powerShellInstallCommand(url: String, code: String): String =
            "powershell -NoProfile -ExecutionPolicy Bypass -Command \"& ([scriptblock]::Create(" +
                "(Invoke-RestMethod '$url/api/node/install.ps1'))) " +
                "-Pano '$url' -Code '${powerShellQuote(code)}'\""

        /**
         * Where the agent is downloaded from (see the instance [agentJarUrl]): Pano's route under the
         * agent's name when [servedByPano], the release's node jar otherwise.
         */
        fun agentJarUrl(panoUrl: String, servedByPano: Boolean, version: String?): String =
            if (servedByPano) "$panoUrl/api/node/$AGENT_JAR_NAME" else downloadUrl(panoUrl, false, version)

        /**
         * What `GET /api/panel/servers/agent-link` answers (SM-74): the code and when it stops
         * pairing, where the jar is, and the three commands -- download it into the server folder
         * (POSIX shell or PowerShell), run it once with the code, start it like that from then on.
         * `panoUrl` is the address the run command carries, which is also what the agent's first
         * run asks for when it is started without one (SM-76). `enabled` is always true here: it is
         * `managed-servers.accept-agent-links`, and while that is off the endpoint answers
         * [agentLinkDisabled] instead (SM-77). Pure, so the exact shape can be asserted.
         */
        fun agentLink(code: String, expiresAt: Long, panoUrl: String, jarUrl: String): Map<String, Any> = mapOf(
            "enabled" to true,
            "code" to code,
            "expiresAt" to expiresAt,
            "panoUrl" to panoUrl,
            "jarUrl" to jarUrl,
            "jarFileName" to AGENT_JAR_NAME,
            "downloadCommand" to "curl -fLo $AGENT_JAR_NAME '${shellQuote(jarUrl)}'",
            "downloadCommandWindows" to "Invoke-WebRequest -Uri '${powerShellQuote(jarUrl)}' -OutFile $AGENT_JAR_NAME",
            "runCommand" to "$AGENT_START_COMMAND --pano '${shellQuote(panoUrl)}' --code $code",
            "startCommand" to AGENT_START_COMMAND,
            "javaVersion" to AGENT_JAVA_VERSION
        )

        /**
         * What `GET /api/panel/servers/agent-link` answers while `managed-servers.accept-agent-links`
         * is off (SM-77): no code, no expiry and no command that would carry one -- nothing that
         * pairs -- only what the dialog shows greyed out behind its switch: where the jar is, what
         * it is called, how the server is started and the Java it needs. Pure, like [agentLink].
         */
        fun agentLinkDisabled(jarUrl: String): Map<String, Any> = mapOf(
            "enabled" to false,
            "jarUrl" to jarUrl,
            "jarFileName" to AGENT_JAR_NAME,
            "startCommand" to AGENT_START_COMMAND,
            "javaVersion" to AGENT_JAVA_VERSION
        )

        /** Single quotes are what a POSIX shell trusts, so the only thing to neutralise is one. */
        fun shellQuote(value: String): String = value.replace("'", "'\\''")

        /** PowerShell escapes a single quote inside a single-quoted string by doubling it. */
        fun powerShellQuote(value: String): String = value.replace("'", "''")
    }
}
