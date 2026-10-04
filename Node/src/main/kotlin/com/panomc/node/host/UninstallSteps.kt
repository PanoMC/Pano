package com.panomc.node.host

import com.panomc.node.agent.AgentLayout
import java.io.File

/**
 * How this daemon was installed, as far as it can tell from where it stands (SM-64, §2.4.29 B).
 *
 * Everything the uninstall needs to decide "can I remove the service myself, or do I have to hand
 * the operator the commands" is a fact about the host, and they are collected here once so that
 * [UninstallSteps] can stay a pure function of them — every branch of it is testable on any OS.
 */
data class UninstallEnvironment(
    /** [HostPlatform.os]: `linux`, `windows` or `macos`. */
    val os: String,
    /** Whether this process runs as root (uid 0); never true on Windows. */
    val isRoot: Boolean,
    /** Whether this process runs inside a container (Docker, Podman, Coolify). */
    val inContainer: Boolean,
    /** `/etc/systemd/system/pano-node.service` when `install.sh` (or an operator) put it there. */
    val systemdUnit: String?,
    /** The unit `--service install` wrote into the data directory, when it did. */
    val selfServiceUnit: String?,
    /** The jar this daemon runs from, or null when it was not started from one. */
    val jarPath: String?,
    /** The data directory, absolute. */
    val dataDir: String,
    /** The user this process runs as. */
    val user: String,
    /**
     * The server folder of a Pano Agent (SM-74); null for an ordinary node. An agent lives inside
     * that folder -- its jar next to the server's, its data in `.pano-agent` -- runs as whoever runs
     * the server, and has a service of its own only if `--service install` wrote one.
     */
    val agentFolder: String? = null
) {
    /**
     * `/opt/pano-node` (or wherever the jar lives) when that directory holds nothing but the daemon.
     * Never for an agent: the directory its jar is in is the server's folder.
     */
    val installDir: String? get() = if (agentFolder != null) null else jarPath?.let { parentOf(it) }

    /** The service `--service install` names after an agent's folder (`pano-agent-<id>`); null for a node. */
    val agentService: String? get() = agentFolder?.let { AgentLayout.serviceName(File(it)) }

    /** The systemd service to disable: this agent's, or `pano-node`. */
    val serviceName: String get() = agentService ?: SERVICE_NAME

    /** The environment file directory `install.sh` wrote for the node; an agent has none. */
    val systemdEnvPath: String get() = SYSTEMD_ENV_DIR

    companion object {
        /** The unit path `install.sh` writes. */
        const val SYSTEMD_UNIT_PATH = "/etc/systemd/system/pano-node.service"

        /** Where `install.sh` keeps the pairing environment file. */
        const val SYSTEMD_ENV_DIR = "/etc/pano-node"

        /** The system user `install.sh` creates and runs the service as. */
        const val SERVICE_USER = "pano-node"

        /** The systemd service `install.sh` registers for an ordinary node. */
        const val SERVICE_NAME = "pano-node"

        /** The Windows service `install.ps1` and `--service install` register. */
        const val WINDOWS_SERVICE = "PanoNode"

        /** The launchd label `--service install` writes on macOS. */
        const val LAUNCHD_LABEL = "com.panomc.node"

        /** Reads the facts off the running host. [agentFolder] is a Pano Agent's server folder. */
        fun detect(
            dataDir: File,
            selfServiceUnit: File?,
            jarPath: String?,
            agentFolder: String? = null
        ): UninstallEnvironment {
            val os = HostPlatform.os

            val agentService = agentFolder?.let { AgentLayout.serviceName(File(it)) }

            val unitPath = agentService?.let { "/etc/systemd/system/$it.service" } ?: SYSTEMD_UNIT_PATH

            // Only the unit that runs *this* daemon: install.sh writes `--data <dir>` into it, and an
            // agent's unit runs from its server folder. A unit for some other node on the same host
            // (Pano's local node next to a script install) is not this uninstall's to disable.
            val identifies = agentFolder ?: dataDir.absoluteFile.normalize().path

            val unit = File(unitPath).takeIf { os == "linux" && it.isFile }?.takeIf { file ->
                try {
                    file.readText().contains(identifies)
                } catch (_: Exception) {
                    false
                }
            }

            return UninstallEnvironment(
                os = os,
                isRoot = os != "windows" && isRootUser(),
                inContainer = os == "linux" && (File("/.dockerenv").exists() || File("/run/.containerenv").exists()),
                systemdUnit = unit?.absolutePath,
                selfServiceUnit = selfServiceUnit?.takeIf { it.isFile }?.absolutePath,
                jarPath = jarPath,
                dataDir = dataDir.absoluteFile.normalize().path,
                user = System.getProperty("user.name", ""),
                agentFolder = agentFolder
            )
        }

        /**
         * The parent of [path] by either separator, so a Windows path is read correctly on any host
         * (the steps are also built by tests, and `java.io.File` only knows the local separator).
         */
        fun parentOf(path: String): String? {
            val index = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))

            return if (index > 0) path.substring(0, index) else null
        }

        /** The last segment of [path] by either separator. */
        fun nameOf(path: String): String = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)

        private fun isRootUser(): Boolean = try {
            val process = ProcessBuilder("id", "-u").redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().use { it.readText().trim() }

            process.waitFor() == 0 && output == "0"
        } catch (_: Exception) {
            System.getProperty("user.name") == "root"
        }
    }
}

/**
 * The commands an operator still has to run once this node removed itself (SM-64, §2.4.29 B).
 *
 * The node deletes everything it owns — servers, backups, Java runtimes, caches — but not the
 * things that were put on the host *for* it: a service registration needs root, a container is
 * the deployment platform's to delete, and a data directory cannot remove itself while the process
 * is still holding it. Those are returned as copyable lines, commands where a command exists and a
 * `#` comment where only a person can do it (Coolify), so the panel can show them in one code block.
 *
 * The shapes follow the three installers: `install.sh` (systemd unit, `/opt/pano-node`, `/var/lib/
 * pano-node`, `/etc/pano-node`, the `pano-node` user), `install.ps1` (the `PanoNode` service under
 * Program Files / ProgramData), `--service install` (a unit written into the data directory for the
 * operator to copy), a container, or a jar someone simply ran by hand.
 */
object UninstallSteps {
    fun build(environment: UninstallEnvironment, serviceHandled: Boolean = false): List<String> {
        val steps = mutableListOf<String>()

        // Before the container case: an agent in a hosting panel's container is not the container.
        if (environment.agentFolder != null) {
            return agent(environment, serviceHandled)
        }

        if (environment.inContainer) {
            steps.add("# This node runs in a container. Delete the container together with its data volume:")
            steps.add("# in Coolify, delete the pano-node application (and its persistent storage);")
            steps.add("# with plain Docker: docker rm -f <container> && docker volume rm <volume>")

            return steps
        }

        return when (environment.os) {
            "windows" -> windows(environment, serviceHandled)
            "macos" -> mac(environment, serviceHandled)
            else -> linux(environment, serviceHandled)
        }
    }

    private fun linux(environment: UninstallEnvironment, serviceHandled: Boolean): List<String> {
        val steps = mutableListOf<String>()
        val sudo = if (environment.isRoot) "" else "sudo "

        // install.sh: the unit, its environment file, the install directory, the data directory
        // and the user all have to go, and only root can remove any of them.
        // Removed by the node itself (root under that unit): the unit, its environment file and the
        // install directory are gone, and only the data directory with the marker is left.
        if (environment.systemdUnit != null && serviceHandled) {
            return listOf("${sudo}rm -rf ${shellQuote(environment.dataDir)}")
        }

        if (environment.systemdUnit != null) {
            val paths = listOfNotNull(
                environment.systemdUnit,
                environment.systemdEnvPath,
                environment.installDir?.takeIf { isDedicatedInstallDir(it) },
                environment.dataDir
            ).distinct()

            steps.add("${sudo}systemctl disable --now ${environment.serviceName}")
            steps.add("${sudo}rm -rf ${paths.joinToString(" ") { shellQuote(it) }}")
            steps.add("${sudo}systemctl daemon-reload")

            if (environment.user == UninstallEnvironment.SERVICE_USER) {
                steps.add("${sudo}userdel ${UninstallEnvironment.SERVICE_USER}")
            }

            return steps
        }

        if (environment.selfServiceUnit != null && !serviceHandled) {
            // `--service install` only wrote a template; the operator copied it into place.
            steps.add("${sudo}systemctl disable --now pano-node")
            steps.add("${sudo}rm -f ${UninstallEnvironment.SYSTEMD_UNIT_PATH}")
            steps.add("${sudo}systemctl daemon-reload")
        }

        // The files belong to the user the daemon ran as, so no sudo for them.
        steps.addAll(removeFiles(environment))

        return steps
    }

    private fun mac(environment: UninstallEnvironment, serviceHandled: Boolean): List<String> {
        val steps = mutableListOf<String>()

        if (environment.selfServiceUnit != null && !serviceHandled) {
            val plist = "~/Library/LaunchAgents/${UninstallEnvironment.LAUNCHD_LABEL}.plist"

            steps.add("launchctl unload $plist")
            steps.add("rm -f $plist")
        }

        steps.addAll(removeFiles(environment))

        return steps
    }

    private fun windows(environment: UninstallEnvironment, serviceHandled: Boolean): List<String> {
        val steps = mutableListOf<String>()

        val installDir = environment.installDir
        // install.ps1 puts the jar in a folder of its own called PanoNode and registers the
        // PanoNode service for it; a user install (-UserInstall, under LOCALAPPDATA) registers none.
        val scriptService = installDir != null &&
                UninstallEnvironment.nameOf(installDir).equals("PanoNode", ignoreCase = true) &&
                !installDir.contains("AppData", ignoreCase = true)

        if ((scriptService || environment.selfServiceUnit != null) && !serviceHandled) {
            steps.add("sc.exe stop ${UninstallEnvironment.WINDOWS_SERVICE}")
            steps.add("sc.exe delete ${UninstallEnvironment.WINDOWS_SERVICE}")
        }

        val paths = listOfNotNull(
            installDir?.takeIf { scriptService },
            environment.dataDir
        ).distinct()

        if (!scriptService) {
            environment.jarPath?.let { steps.add("Remove-Item -Force ${powerShellQuote(it)}") }
        }

        paths.forEach { steps.add("Remove-Item -Recurse -Force ${powerShellQuote(it)}") }

        return steps
    }

    /**
     * The data directory and the jar, for an install nothing registered as a service.
     *
     * `install.sh --user-install` puts both under `~/.pano-node` (data in `~/.pano-node/data`), so
     * that folder is removed whole rather than as two lines.
     */
    private fun removeFiles(environment: UninstallEnvironment): List<String> {
        val dataDir = File(environment.dataDir)
        val jar = environment.jarPath?.let { File(it) }

        val userInstallRoot = dataDir.parentFile?.takeIf {
            it.name == ".pano-node" && dataDir.name == "data" && (jar == null || jar.parentFile == it)
        }

        if (userInstallRoot != null) {
            return listOf("rm -rf ${shellQuote(userInstallRoot.path)}")
        }

        val steps = mutableListOf("rm -rf ${shellQuote(dataDir.path)}")

        // A jar run by hand is the operator's own file and may be the one other nodes (or a dev
        // checkout's local node) run from, so it is pointed at rather than deleted.
        if (jar != null && !jar.absoluteFile.toPath().startsWith(dataDir.absoluteFile.toPath())) {
            steps.add("# ${jar.path} can be deleted too if no other node runs from it")
        }

        return steps
    }

    /**
     * A Pano Agent (SM-74): its own `.pano-agent` folder goes, and the service `--service install`
     * wrote for it if the operator installed that. Never the server folder, never a user, and the
     * jar is only pointed at -- the admin starts the server with its own jar again instead.
     */
    private fun agent(environment: UninstallEnvironment, serviceHandled: Boolean): List<String> {
        val steps = mutableListOf<String>()
        val service = environment.serviceName
        val sudo = if (environment.isRoot) "" else "sudo "
        val installed = environment.systemdUnit != null || environment.selfServiceUnit != null

        if (installed && !serviceHandled && !environment.inContainer) {
            when (environment.os) {
                "windows" -> {
                    steps.add("sc.exe stop $service")
                    steps.add("sc.exe delete $service")
                }

                "macos" -> {
                    steps.add("launchctl unload ~/Library/LaunchAgents/$service.plist")
                    steps.add("rm -f ~/Library/LaunchAgents/$service.plist")
                }

                else -> {
                    steps.add("${sudo}systemctl disable --now $service")
                    steps.add("${sudo}rm -f ${shellQuote(environment.systemdUnit ?: "/etc/systemd/system/$service.service")}")
                    steps.add("${sudo}systemctl daemon-reload")
                }
            }
        }

        steps.add(
            if (environment.os == "windows") {
                "Remove-Item -Recurse -Force ${powerShellQuote(environment.dataDir)}"
            } else {
                "rm -rf ${shellQuote(environment.dataDir)}"
            }
        )

        val jar = environment.jarPath?.let { UninstallEnvironment.nameOf(it) } ?: AgentLayout.JAR_NAME

        steps.add("# $jar can be deleted too: start the server with its own server jar again.")

        return steps
    }

    /** `/opt/pano-node`-style: a directory that exists only for the daemon. */
    private fun isDedicatedInstallDir(path: String): Boolean =
        UninstallEnvironment.nameOf(path).contains("pano-node", ignoreCase = true)

    /** Single-quoted for a POSIX shell; a quote inside becomes `'\''`. */
    fun shellQuote(value: String): String =
        if (value.matches(SAFE_SHELL)) value else "'" + value.replace("'", "'\\''") + "'"

    /** Single-quoted for PowerShell; a quote inside is doubled. */
    fun powerShellQuote(value: String): String = "'" + value.replace("'", "''") + "'"

    private val SAFE_SHELL = Regex("^[A-Za-z0-9_./+:=-]+$")
}
