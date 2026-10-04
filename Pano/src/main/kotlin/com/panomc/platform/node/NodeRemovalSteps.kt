package com.panomc.platform.node

/**
 * The commands an operator has to run when Pano deleted a node without the node's help (SM-64).
 *
 * The node builds these itself when it uninstalls (it can see its own unit files, its jar and
 * whether it is root). This is Pano's version for everything else: a node that is offline, too old
 * to know `NODE_UNINSTALL`, or whose uninstall failed and was forced. All Pano has is what the
 * node's hellos told it — OS, data path, runtime — and how it was installed, which is enough to
 * name the installers' fixed paths: `install.sh` (`/var/lib/pano-node`, `/opt/pano-node`, the
 * `pano-node` unit and user), `install.sh --user-install` (`~/.pano-node`), `install.ps1`
 * (`ProgramData\PanoNode`, `Program Files\PanoNode`, the `PanoNode` service) and Coolify.
 *
 * Lines are commands where there is one and `#` comments where only a person can act, so the panel
 * can show them in one copyable block. Unlike the node's own steps these include stopping the
 * daemon, because nobody told it to stop.
 */
object NodeRemovalSteps {
    /** Where `install.sh` (as root) keeps the data directory. */
    const val SCRIPT_DATA_DIR = "/var/lib/pano-node"

    fun build(
        bootstrap: NodeBootstrap,
        kind: NodeKind,
        os: String?,
        dataPath: String?,
        runtime: NodeRuntime
    ): List<String> {
        // Pano's own local node is Pano's to clean up; it says so separately when it could not.
        if (kind == NodeKind.LOCAL || bootstrap == NodeBootstrap.LOCAL) {
            return emptyList()
        }

        if (bootstrap == NodeBootstrap.COOLIFY) {
            return listOf(
                "# This node runs as a Coolify application. Delete the application in Coolify,",
                "# together with its persistent storage (the /data volume) — that removes its servers too."
            )
        }

        val steps = when (os?.lowercase()) {
            "windows" -> windows(dataPath)
            "macos" -> mac(dataPath)
            else -> linux(dataPath)
        }

        if (runtime == NodeRuntime.DOCKER) {
            return steps + listOf(
                "# Its servers ran as Docker containers named pano-<uuid>; remove them too:",
                "docker ps -a --filter name=pano- --format '{{.Names}}' | xargs -r docker rm -f"
            )
        }

        return steps
    }

    private fun linux(dataPath: String?): List<String> {
        if (dataPath == null) {
            return listOf("# Stop pano-node on that machine and delete its data directory.")
        }

        if (dataPath.trimEnd('/') == SCRIPT_DATA_DIR) {
            return listOf(
                "sudo systemctl disable --now pano-node",
                "sudo rm -rf /etc/systemd/system/pano-node.service /etc/pano-node /opt/pano-node $SCRIPT_DATA_DIR",
                "sudo systemctl daemon-reload",
                "sudo userdel pano-node"
            )
        }

        val userInstall = userInstallRoot(dataPath)

        return listOf(
            "# Stop the pano-node process (or the service you registered for it), then:",
            "rm -rf ${shellQuote(userInstall ?: dataPath)}"
        )
    }

    private fun mac(dataPath: String?): List<String> {
        val steps = mutableListOf(
            "# If you registered pano-node with launchd, unload it first:",
            "launchctl unload ~/Library/LaunchAgents/com.panomc.node.plist",
            "rm -f ~/Library/LaunchAgents/com.panomc.node.plist"
        )

        steps.add(
            dataPath?.let { "rm -rf ${shellQuote(userInstallRoot(it) ?: it)}" }
                ?: "# Then delete the node's data directory."
        )

        return steps
    }

    private fun windows(dataPath: String?): List<String> {
        val steps = mutableListOf(
            "sc.exe stop PanoNode",
            "sc.exe delete PanoNode"
        )

        if (dataPath == null) {
            steps.add("# Then delete the node's data directory.")

            return steps
        }

        steps.add("Remove-Item -Recurse -Force ${powerShellQuote(dataPath)}")

        // install.ps1: data in ProgramData\PanoNode, the jar in Program Files\PanoNode.
        if (dataPath.trimEnd('\\').endsWith("\\ProgramData\\PanoNode", ignoreCase = true)) {
            steps.add("Remove-Item -Recurse -Force \"\$env:ProgramFiles\\PanoNode\"")
        }

        return steps
    }

    /** `~/.pano-node` when [dataPath] is the `install.sh --user-install` data directory inside it. */
    private fun userInstallRoot(dataPath: String): String? {
        val trimmed = dataPath.trimEnd('/')

        return if (trimmed.endsWith("/.pano-node/data")) trimmed.removeSuffix("/data") else null
    }

    /** Single-quoted for a POSIX shell unless it needs nothing; a quote inside becomes `'\''`. */
    fun shellQuote(value: String): String =
        if (value.matches(SAFE_SHELL)) value else "'" + value.replace("'", "'\\''") + "'"

    /** Single-quoted for PowerShell; a quote inside is doubled. */
    fun powerShellQuote(value: String): String = "'" + value.replace("'", "''") + "'"

    private val SAFE_SHELL = Regex("^[A-Za-z0-9_./+:=-]+$")
}
