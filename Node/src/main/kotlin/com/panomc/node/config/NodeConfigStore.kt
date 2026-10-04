package com.panomc.node.config

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Reads and writes `<data>/config.conf`.
 *
 * HOCON, like Pano's own config.conf and the Minecraft plugin's, so an operator who has edited one
 * already knows this one. Writes go through a temporary file and an atomic move: the file holds
 * the only copy of the RSA private key and the pairing token, and a truncating write interrupted
 * by a full disk would destroy both with nothing to restore from.
 */
object NodeConfigStore {
    private const val FILE_NAME = "config.conf"

    fun file(dataDir: File) = File(dataDir, FILE_NAME)

    /** The stored config, or defaults when the file does not exist yet. */
    fun load(dataDir: File): NodeConfig {
        val file = file(dataDir)

        if (!file.isFile) {
            return NodeConfig()
        }

        val config = ConfigFactory.parseFile(file).resolve()

        fun string(path: String, fallback: String = "") =
            if (config.hasPath(path)) config.getString(path) else fallback

        fun int(path: String, fallback: Int) =
            if (config.hasPath(path)) config.getInt(path) else fallback

        fun boolean(path: String, fallback: Boolean) =
            if (config.hasPath(path)) config.getBoolean(path) else fallback

        return NodeConfig(
            platformUrl = string("platform.url"),
            token = string("platform.token"),
            encryptionKey = string("platform.encryption-key"),
            publicKey = string("node.public-key"),
            privateKey = string("node.private-key"),
            name = string("node.name", "pano-node"),
            portRangeStart = int("node.port-range.start", NodeConfig.DEFAULT_PORT_RANGE_START),
            portRangeEnd = int("node.port-range.end", NodeConfig.DEFAULT_PORT_RANGE_END),
            stopServersOnExit = boolean("node.stop-servers-on-exit", false),
            // Absent in every config.conf written before SM-63, which is the default: on. The
            // next save writes the key, so the setting becomes visible without a migration step.
            javaAutoDownload = boolean("node.java-auto-download", true),
            // Absent before SM-67; the same "missing means on" as the Java switch above.
            toolAutoDownload = boolean("node.tool-auto-download", true),
            // Absent on every ordinary node, which is exactly what the defaults say.
            agent = boolean("node.agent", false),
            agentServer = string("node.agent-server")
        )
    }

    /** Writes [config] atomically, owner-only where the filesystem has POSIX permissions. */
    fun save(dataDir: File, config: NodeConfig) {
        dataDir.mkdirs()

        val rendered = render(config)
        val target = file(dataDir)
        val temporary = File(dataDir, "$FILE_NAME.tmp")

        temporary.writeText(rendered)

        restrictPermissions(temporary)

        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )

        restrictPermissions(target)
    }

    /** The exact text [save] writes. Split out so the round trip can be tested without a disk. */
    fun render(config: NodeConfig): String {
        var rendered = ConfigFactory.empty()
            .withValue("platform.url", ConfigValueFactory.fromAnyRef(config.platformUrl))
            .withValue("platform.token", ConfigValueFactory.fromAnyRef(config.token))
            .withValue("platform.encryption-key", ConfigValueFactory.fromAnyRef(config.encryptionKey))
            .withValue("node.name", ConfigValueFactory.fromAnyRef(config.name))
            .withValue("node.public-key", ConfigValueFactory.fromAnyRef(config.publicKey))
            .withValue("node.private-key", ConfigValueFactory.fromAnyRef(config.privateKey))
            .withValue("node.port-range.start", ConfigValueFactory.fromAnyRef(config.portRangeStart))
            .withValue("node.port-range.end", ConfigValueFactory.fromAnyRef(config.portRangeEnd))
            .withValue("node.stop-servers-on-exit", ConfigValueFactory.fromAnyRef(config.stopServersOnExit))
            .withValue("node.java-auto-download", ConfigValueFactory.fromAnyRef(config.javaAutoDownload))
            .withValue("node.tool-auto-download", ConfigValueFactory.fromAnyRef(config.toolAutoDownload))

        // Only on an agent: an ordinary node's file stays exactly what it was before agents existed.
        if (config.agent) {
            rendered = rendered
                .withValue("node.agent", ConfigValueFactory.fromAnyRef(true))
                .withValue("node.agent-server", ConfigValueFactory.fromAnyRef(config.agentServer))
        }

        val options = ConfigRenderOptions.defaults()
            .setJson(false)
            .setOriginComments(false)
            .setComments(true)
            .setFormatted(true)

        return HEADER + rendered.root().render(options)
    }

    /** Parses text produced by [render]. Used by [load] and by the round-trip test. */
    fun parse(text: String): NodeConfig {
        val config = ConfigFactory.parseString(text).resolve()

        fun string(path: String, fallback: String = "") =
            if (config.hasPath(path)) config.getString(path) else fallback

        fun int(path: String, fallback: Int) =
            if (config.hasPath(path)) config.getInt(path) else fallback

        fun boolean(path: String, fallback: Boolean) =
            if (config.hasPath(path)) config.getBoolean(path) else fallback

        return NodeConfig(
            platformUrl = string("platform.url"),
            token = string("platform.token"),
            encryptionKey = string("platform.encryption-key"),
            publicKey = string("node.public-key"),
            privateKey = string("node.private-key"),
            name = string("node.name", "pano-node"),
            portRangeStart = int("node.port-range.start", NodeConfig.DEFAULT_PORT_RANGE_START),
            portRangeEnd = int("node.port-range.end", NodeConfig.DEFAULT_PORT_RANGE_END),
            stopServersOnExit = boolean("node.stop-servers-on-exit", false),
            // Absent in every config.conf written before SM-63, which is the default: on. The
            // next save writes the key, so the setting becomes visible without a migration step.
            javaAutoDownload = boolean("node.java-auto-download", true),
            toolAutoDownload = boolean("node.tool-auto-download", true),
            agent = boolean("node.agent", false),
            agentServer = string("node.agent-server")
        )
    }

    private fun restrictPermissions(file: File) {
        try {
            val view = Files.getFileAttributeView(
                file.toPath(),
                java.nio.file.attribute.PosixFileAttributeView::class.java
            ) ?: return

            view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: Exception) {
            // Windows and any filesystem without POSIX bits simply keep whatever they were given;
            // failing the whole save over file permissions would be worse than the warning.
        }
    }

    private val HEADER = """
        # pano-node configuration.
        #
        # platform.token and platform.encryption-key are credentials: anyone holding both can act
        # as this node. node.private-key is what the pairing reply was encrypted to. Keep the file
        # readable only by the account the daemon runs as.
        #
        # node.port-range is the range of ports this node's managed servers may bind. It is announced
        # to Pano, which allocates new servers' ports inside it, and a port Pano asks for outside it
        # is moved into it. In a container it must match the published ports. --port-range and
        # PANO_NODE_PORT_RANGE (start-end, e.g. 25660-25669) write it here on start.
        #
        # node.stop-servers-on-exit decides what happens to running servers when this daemon exits.
        # False (the default) leaves them running and adopts them again on the next start, which is
        # what keeps a node restart or a self-update from taking a server full of players down.
        #
        # node.java-auto-download decides whether a Java runtime a server needs and this host lacks
        # is downloaded (Eclipse Temurin, else Azul Zulu) into <data>/java. True by default; the
        # PANO_NODE_JAVA_AUTO_DOWNLOAD environment variable overrides it without editing this file.
        #
        # node.tool-auto-download decides whether a build tool a job needs and this host lacks is
        # downloaded into <data>/tools -- today the portable git Spigot's BuildTools needs, from the
        # pano-web-platform GitHub release. A git already on the PATH is always used instead. True by
        # default; PANO_NODE_TOOL_AUTO_DOWNLOAD overrides it.
        #
        # node.agent and node.agent-server are only present on a Pano Agent: pano-agent.jar run in an
        # existing server's folder, dedicated to that one server, which Pano runs from where it is.
        # agent-server is rewritten from the folder on every start; delete the server in Pano rather
        # than editing either.

    """.trimIndent() + "\n"
}
