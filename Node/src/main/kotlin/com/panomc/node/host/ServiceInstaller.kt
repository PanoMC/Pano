package com.panomc.node.host

import com.panomc.node.agent.AgentLayout
import com.panomc.node.util.NodeLogger
import java.io.File

/**
 * Writes the unit file that makes this daemon start with the host.
 *
 * It writes files and prints the one command that activates them; it never runs that command
 * itself. Registering a service needs root on every one of the three operating systems, and a
 * daemon that quietly escalates -- or that refuses to run at all because it could not -- would be
 * the wrong trade for a process whose whole point is to be startable inside a container by an
 * unprivileged user.
 *
 * A Pano Agent ([agent], SM-74) gets a unit of its own, named `pano-agent-<id>` after its server
 * folder so several can share a host: it runs the agent's launcher from the folder, as the user who
 * wrote it, restarts it only on a failure, and gives it time to stop the server before it is killed.
 */
class ServiceInstaller(
    private val dataDir: File,
    private val logger: NodeLogger,
    /** A Pano Agent's folders; null for an ordinary node. */
    private val agent: AgentLayout? = null
) {
    fun install(jarPath: String, javaPath: String) {
        val target = unitFile()

        target.parentFile?.mkdirs()

        target.writeText(
            if (agent != null) {
                when {
                    HostPlatform.isWindows -> agentWindowsScript(agent, jarPath, javaPath)
                    HostPlatform.isMac -> agentLaunchdPlist(agent, jarPath, javaPath)
                    else -> agentSystemdUnit(agent, jarPath, javaPath, System.getProperty("user.name").orEmpty())
                }
            } else {
                when {
                    HostPlatform.isWindows -> windowsWrapper(jarPath, javaPath)
                    HostPlatform.isMac -> launchdPlist(jarPath, javaPath)
                    else -> systemdUnit(jarPath, javaPath)
                }
            }
        )

        logger.info("Wrote ${target.absolutePath}")
        logger.info(activationHint(target))
    }

    fun uninstall() {
        val target = unitFile()

        if (target.delete()) {
            logger.info("Removed ${target.absolutePath}")
        } else {
            logger.warn("No service file at ${target.absolutePath}")
        }

        logger.info(deactivationHint(target))
    }

    /** Where the unit lands. Inside the data directory, which is the one place we may write. */
    fun unitFile(): File = File(
        File(dataDir, "service"),
        agent?.let { agentUnitFileName(it.serviceName) } ?: when {
            HostPlatform.isWindows -> "pano-node-service.cmd"
            HostPlatform.isMac -> "com.panomc.node.plist"
            else -> "pano-node.service"
        }
    )

    /** The unit's file name for the agent service [name] on this host. */
    private fun agentUnitFileName(name: String) = when {
        HostPlatform.isWindows -> "$name.cmd"
        HostPlatform.isMac -> "$name.plist"
        else -> "$name.service"
    }

    private fun systemdUnit(jarPath: String, javaPath: String) = """
        [Unit]
        Description=Pano node daemon
        After=network-online.target
        Wants=network-online.target

        [Service]
        Type=simple
        ExecStart=$javaPath -jar $jarPath --data ${dataDir.absolutePath}
        WorkingDirectory=${dataDir.absolutePath}
        Restart=always
        RestartSec=5
        # 75 is the node's "I have staged an update, restart me" exit code; systemd must treat it
        # as a normal restart rather than a failure. 78 (removed from Pano, see below) is the node
        # ending on purpose, and 143 is the JVM leaving on the SIGTERM of a `systemctl stop`: both
        # are clean stops, not a failed unit.
        SuccessExitStatus=75 78 143
        # 78 is "this node was removed from Pano": the daemon has deleted what it held and must not
        # be started again until someone sets the host up anew.
        RestartPreventExitStatus=78
        # Only the daemon is stopped or restarted, never the Minecraft servers it started: they keep
        # running and the next daemon re-attaches to them. The default (control-group) would kill
        # every server on the host on each `systemctl restart pano-node`.
        KillMode=process
        User=${System.getProperty("user.name")}

        [Install]
        WantedBy=multi-user.target

    """.trimIndent()

    private fun launchdPlist(jarPath: String, javaPath: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>Label</key>
          <string>com.panomc.node</string>
          <key>ProgramArguments</key>
          <array>
            <string>$javaPath</string>
            <string>-jar</string>
            <string>$jarPath</string>
            <string>--data</string>
            <string>${dataDir.absolutePath}</string>
          </array>
          <key>WorkingDirectory</key>
          <string>${dataDir.absolutePath}</string>
          <key>RunAtLoad</key>
          <true/>
          <key>KeepAlive</key>
          <true/>
          <!-- The Minecraft servers outlive a daemon restart; launchd must not take them down with it. -->
          <key>AbandonProcessGroup</key>
          <true/>
        </dict>
        </plist>

    """.trimIndent()

    /**
     * Windows has no unit file, so the node writes the `sc create` line instead.
     *
     * `sc` can only register an executable, not a jar, so the script registers a wrapper that
     * launches the JVM. Running it needs an elevated prompt, which is exactly why it is written
     * out rather than executed.
     */
    private fun windowsWrapper(jarPath: String, javaPath: String) = """
        @echo off
        REM Run this from an elevated prompt to register pano-node as a Windows service.
        REM sc can only start an executable, so it is pointed at the JVM with the jar as an argument.
        sc create PanoNode binPath= "\"$javaPath\" -jar \"$jarPath\" --data \"${dataDir.absolutePath}\"" start= auto DisplayName= "Pano node daemon"
        sc description PanoNode "Installs and supervises Minecraft servers for a Pano platform."
        sc start PanoNode

    """.trimIndent()

    private fun activationHint(target: File) = when {
        HostPlatform.isWindows -> "Run ${target.absolutePath} from an elevated prompt to register the service."
        HostPlatform.isMac -> "Copy it to ~/Library/LaunchAgents and run: launchctl load ${target.name}"
        else -> "Copy it to /etc/systemd/system and run: systemctl enable --now ${agent?.serviceName ?: "pano-node"}" +
            (if (agent != null) " (stop the server's current start script, screen or tmux first)" else "")
    }

    private fun deactivationHint(target: File) = when {
        HostPlatform.isWindows -> "Run \"sc delete ${agent?.serviceName ?: "PanoNode"}\" from an elevated prompt to unregister the service."
        HostPlatform.isMac -> "Run: launchctl unload ~/Library/LaunchAgents/${target.name}"
        else -> "Run: systemctl disable --now ${agent?.serviceName ?: "pano-node"}"
    }

    companion object {
        /**
         * The arguments after `-jar <jar>` an agent's service starts it with: nothing, when the jar
         * is called `pano-agent*.jar` and keeps its data in `.pano-agent` (the working directory is
         * the folder, which says everything else), and `--agent` / `--data` when it does not.
         */
        fun agentArguments(agent: AgentLayout, jarPath: String): List<String> {
            val arguments = mutableListOf<String>()

            if (!AgentLayout.isAgentJarName(File(jarPath).name)) {
                arguments.add("--agent")
            }

            if (!agent.defaultDataDir) {
                arguments.add("--data")
                arguments.add(agent.dataDir.path)
            }

            return arguments
        }

        /** A systemd unit that runs [agent]'s launcher from its server folder (SM-74). */
        fun agentSystemdUnit(agent: AgentLayout, jarPath: String, javaPath: String, user: String): String {
            val command = (listOf(javaPath, "-jar", jarPath) + agentArguments(agent, jarPath)).joinToString(" ") { systemdQuote(it) }

            return """
                |[Unit]
                |Description=Pano Agent for ${agent.serverDir.path}
                |After=network-online.target
                |Wants=network-online.target
                |
                |[Service]
                |Type=simple
                |ExecStart=$command
                |WorkingDirectory=${systemdQuote(agent.serverDir.path)}
                |User=$user
                |# Started again after a crash only: an agent that exits cleanly was stopped, or removed
                |# from Pano. The launcher restarts its own worker for updates.
                |Restart=on-failure
                |RestartSec=5
                |# 76: another agent already runs in this folder; 77: this agent is not linked to Pano.
                |# Starting it again changes neither.
                |RestartPreventExitStatus=76 77
                |# 143 is the JVM leaving on the SIGTERM of `systemctl stop` after the server was stopped:
                |# a clean stop, not a failed unit.
                |SuccessExitStatus=143
                |# Only the launcher is signalled: it has the server stopped the way a console `stop`
                |# would, and then exits. A big world can take a while to save.
                |KillMode=mixed
                |TimeoutStopSec=150
                |
                |[Install]
                |WantedBy=multi-user.target
                |""".trimMargin()
        }

        /** A launchd job for [agent] (SM-74): restarted only when it did not exit cleanly. */
        fun agentLaunchdPlist(agent: AgentLayout, jarPath: String, javaPath: String): String {
            val arguments = (listOf(javaPath, "-jar", jarPath) + agentArguments(agent, jarPath))
                .joinToString("\n") { "    <string>${xmlEscape(it)}</string>" }

            return """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                |<plist version="1.0">
                |<dict>
                |  <key>Label</key>
                |  <string>${agent.serviceName}</string>
                |  <key>ProgramArguments</key>
                |  <array>
                |$arguments
                |  </array>
                |  <key>WorkingDirectory</key>
                |  <string>${xmlEscape(agent.serverDir.path)}</string>
                |  <key>RunAtLoad</key>
                |  <true/>
                |  <key>KeepAlive</key>
                |  <dict>
                |    <key>SuccessfulExit</key>
                |    <false/>
                |  </dict>
                |  <key>ExitTimeOut</key>
                |  <integer>150</integer>
                |</dict>
                |</plist>
                |""".trimMargin()
        }

        /**
         * The `sc create` script for [agent] (SM-74). A Windows service starts in System32, so the
         * folder is passed with `--server` rather than taken from the working directory.
         */
        fun agentWindowsScript(agent: AgentLayout, jarPath: String, javaPath: String): String {
            val arguments = (agentArguments(agent, jarPath) + listOf("--server", agent.serverDir.path))
                .joinToString(" ") { "\\\"$it\\\"" }

            return """
                |@echo off
                |REM Run this from an elevated prompt to register the Pano Agent for ${agent.serverDir.path} as a Windows service.
                |sc create ${agent.serviceName} binPath= "\"$javaPath\" -jar \"$jarPath\" $arguments" start= auto DisplayName= "Pano Agent (${agent.serverDir.name})"
                |sc description ${agent.serviceName} "Runs the Minecraft server in ${agent.serverDir.path} for Pano."
                |sc start ${agent.serviceName}
                |""".trimMargin()
        }

        /** An ExecStart word: quoted when it holds a space, quote or backslash. */
        private fun systemdQuote(value: String): String =
            if (value.none { it == ' ' || it == '"' || it == '\\' || it == '\'' }) {
                value
            } else {
                "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }

        private fun xmlEscape(value: String): String =
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
