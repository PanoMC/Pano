package com.panomc.node.server

import com.panomc.node.crypto.NodeKeys
import com.panomc.node.net.PanoPluginSpec
import com.panomc.node.net.PlatformUrls
import com.panomc.node.util.Downloader
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueFactory
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Puts the Pano plugin inside a freshly installed server, already pointed at Pano.
 *
 * This is what makes a managed server a linked one without anybody typing `/pano connect`: Pano
 * created the row, so it also issued the token and the AES key, and all that is left is to drop
 * the jar in and write the config the plugin would otherwise have written itself after pairing.
 *
 * Failing here never fails the install. A server with no plugin is a working server that is not
 * linked yet, and losing a downloaded Paper build over an unreachable GitHub would be a far worse
 * trade than reporting it and carrying on. What the failure must not do is disappear: it used to be
 * logged here and nowhere else, so an install that left a server unlinked still reported DONE and
 * the operator had no idea anything had gone wrong. [Outcome.Failed] carries the sentence out to
 * the console and the task.
 */
class PanoPluginInstaller(
    private val logger: NodeLogger,
    private val platformUrls: PlatformUrls,
    /**
     * How this node runs servers, which decides what address the plugin can reach Pano on.
     *
     * The installer is invoked from more than one task service and every one of them wants the
     * same answer, so the active runtime is handed to the installer once rather than threaded
     * through each caller. See [ServerRuntime.pluginEndpoint].
     */
    private val runtime: ServerRuntime
) {
    /** What one attempt to put the plugin into a server came to. */
    sealed interface Outcome {
        /** Nothing was asked for, or what was asked for came without credentials. */
        data object None : Outcome

        data class Installed(val jar: String) : Outcome

        /** It was asked for and it did not work; [error] is what to tell the operator. */
        data class Failed(val error: String) : Outcome
    }

    /** Installs [spec] into [serverDirectory] and reports what happened, for the task message. */
    fun install(serverDirectory: File, spec: PanoPluginSpec?): Outcome {
        val jarUrl = spec?.jarUrl?.takeIf { it.isNotBlank() } ?: return Outcome.None
        val targetDir = spec.targetDir?.takeIf { it.isNotBlank() } ?: DEFAULT_TARGET_DIR
        val configPath = spec.configPath?.takeIf { it.isNotBlank() } ?: DEFAULT_CONFIG_PATH
        val platform = spec.config

        if (platform == null || platform.token.isNullOrBlank() || platform.encryptionKey.isNullOrBlank()) {
            logger.warn("Pano sent a plugin to install but no credentials for it; skipping.")

            return Outcome.None
        }

        return try {
            // Pano serves its own local builds, and the URL it sends for them is a path: only this
            // node knows which address it reaches Pano on.
            val resolved = platformUrls.resolve(jarUrl) ?: jarUrl

            val jarFile = copyJar(serverDirectory, targetDir, resolved)

            writeConfig(serverDirectory, configPath, spec)

            logger.info("Installed ${jarFile.name} into ${jarFile.parentFile.absolutePath}.")

            Outcome.Installed(jarFile.name)
        } catch (exception: Exception) {
            logger.error("Could not install the Pano plugin: ${exception.message}", exception)

            Outcome.Failed(exception.message ?: exception.javaClass.simpleName)
        }
    }

    /**
     * Fetches the jar into `<serverDir>/<targetDir>/`.
     *
     * A `file:` url is copied rather than downloaded — [Downloader] deliberately refuses every
     * scheme but http and https, and that refusal should stay exactly that strict for anything
     * arriving over the network. Pano no longer sends one (it publishes its local builds instead,
     * because only a node on the same machine could ever read such a path), but a node driven by
     * hand against a directory of jars still can.
     *
     * The bytes land on [partNameOf] beside the target and are moved onto the real name only once
     * they are all there and look like a jar. The server may already be starting while this runs
     * (an import is linked right after it finishes), and a jar written in place is one Paper opens
     * half-written -- "Failed to open plugin jar" -- and then runs without. A `.part` file is
     * nothing any plugin loader reads, and the move is a rename within one directory, so the
     * server sees either no Pano plugin or the whole of it. A failure takes the `.part` with it.
     */
    private fun copyJar(serverDirectory: File, targetDir: String, jarUrl: String): File {
        val directory = PathSafety.resolveRelative(serverDirectory, targetDir)

        directory.mkdirs()

        val uri = URI.create(jarUrl)
        val name = fileNameOf(uri)
        val target = PathSafety.resolveRelative(directory, name)
        val part = File(directory, partNameOf(name))

        part.delete()

        try {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val source = File(uri)

                require(source.isFile) { "${source.absolutePath} is not a file." }

                source.copyTo(part, overwrite = true)
            } else {
                Downloader.download(jarUrl, part) { }
            }

            if (!Downloader.isZip(part)) {
                throw IllegalStateException("What Pano pointed at is not a jar.")
            }

            moveIntoPlace(part, target)
        } catch (exception: Exception) {
            part.delete()

            throw exception
        }

        return target
    }

    /** Moves the finished download onto its real name, atomically where the filesystem allows it. */
    private fun moveIntoPlace(part: File, target: File) {
        try {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Writes the plugin's `config.conf`, owner-only.
     *
     * The file holds a token that can act as this server against Pano and the AES key its socket
     * is framed with, so it gets the same treatment as the node's own config: 600 where the
     * filesystem has POSIX bits.
     */
    private fun writeConfig(serverDirectory: File, configPath: String, spec: PanoPluginSpec) {
        val file = PathSafety.resolveRelative(serverDirectory, configPath)

        file.parentFile?.mkdirs()

        // Not the node's own address as it stands: a containerised server has its own loopback,
        // and only the runtime knows whether that address still means the same machine in there.
        file.writeText(render(spec, reachPanoAt = runtime.pluginEndpoint(platformUrls.endpoint())))

        restrictPermissions(file)
    }

    private fun restrictPermissions(file: File) {
        try {
            val view = Files.getFileAttributeView(
                file.toPath(),
                java.nio.file.attribute.PosixFileAttributeView::class.java
            ) ?: return

            view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: Exception) {
            // Windows has no POSIX bits; refusing to install over that would be worse than the
            // permissions staying at whatever the account's umask gives.
        }
    }

    companion object {
        /** The plugin config version this daemon writes. Matches pano-mc-plugin's newest migration. */
        const val CONFIG_VERSION = 6

        /** pano-mc-plugin's shipped heartbeat defaults (`PanoConfig`): 25 s between pings, 75 s to give up. */
        const val HEARTBEAT_INTERVAL_SECONDS = 25
        const val HEARTBEAT_TIMEOUT_SECONDS = 75

        const val DEFAULT_TARGET_DIR = "plugins"
        const val DEFAULT_CONFIG_PATH = "plugins/Pano/config.conf"

        private const val DEFAULT_JAR_NAME = "pano.jar"

        const val PART_SUFFIX = ".part"

        /**
         * The name a jar called [name] is downloaded under until it is complete: `.pano.jar.part`.
         *
         * Not ending in `.jar` is what keeps Bukkit, Paper, BungeeCord, Velocity and Fabric from
         * loading it; the leading dot keeps it out of a casual `ls` and out of loaders that skip
         * hidden files as well.
         */
        fun partNameOf(name: String): String = ".$name$PART_SUFFIX"

        /**
         * The exact `config.conf` text written next to the plugin.
         *
         * Pure, so the shape the plugin has to be able to read back can be asserted without a
         * filesystem or a Minecraft server.
         *
         * The RSA pair is generated here even though a pre-issued token means it is never used to
         * unwrap anything: pano-mc-plugin declares `public-key`/`private-key` as non-null Strings
         * but is instantiated by Gson through Unsafe, so a config at version 6 without them leaves
         * nulls in non-null fields — and nothing regenerates them, because no migration runs and
         * the file is only rewritten when one does. Writing a real pair costs one key generation
         * and removes that landmine entirely.
         */
        fun render(
            spec: PanoPluginSpec,
            keyPair: Pair<String, String> = NodeKeys.generate(),
            reachPanoAt: com.panomc.node.net.PlatformEndpoint? = null
        ): String {
            val platform = spec.config
            // The plugin runs on this host, so it can reach Pano exactly the way this node does.
            // Pano's own guess (its public website URL) is only a fallback: behind a tunnel, a
            // reverse proxy or split-horizon DNS that guess sent the plugin to a dead address.
            val host = reachPanoAt?.host ?: platform?.host ?: ""
            val port = reachPanoAt?.port ?: platform?.port ?: 80
            val ssl = reachPanoAt?.ssl ?: platform?.ssl ?: false
            val (publicKey, privateKey) = keyPair

            val config = ConfigFactory.empty()
                .withValue("config-version", ConfigValueFactory.fromAnyRef(CONFIG_VERSION))
                .withValue("public-key", ConfigValueFactory.fromAnyRef(publicKey))
                .withValue("private-key", ConfigValueFactory.fromAnyRef(privateKey))
                // Kept on, the plugin's own default: it holds the server's login handling back
                // until Pano has answered, which is exactly what a server Pano just created wants.
                .withValue("await-pano-connection", ConfigValueFactory.fromAnyRef(true))
                // The plugin's own defaults, written out because its Gson-loaded config reads a
                // missing key as 0, which it then rejects with a warning on every start.
                .withValue("heartbeat-interval", ConfigValueFactory.fromAnyRef(HEARTBEAT_INTERVAL_SECONDS))
                .withValue("heartbeat-timeout", ConfigValueFactory.fromAnyRef(HEARTBEAT_TIMEOUT_SECONDS))
                .withValue("console.enabled", ConfigValueFactory.fromAnyRef(true))
                .withValue("platform.host", ConfigValueFactory.fromAnyRef(host))
                .withValue("platform.port", ConfigValueFactory.fromAnyRef(port))
                .withValue("platform.ssl", ConfigValueFactory.fromAnyRef(ssl))
                .withValue("platform.token", ConfigValueFactory.fromAnyRef(platform?.token ?: ""))
                .withValue("platform.encryption-key", ConfigValueFactory.fromAnyRef(platform?.encryptionKey ?: ""))

            val options = ConfigRenderOptions.defaults()
                .setJson(false)
                .setOriginComments(false)
                .setComments(false)
                .setFormatted(true)

            return HEADER + config.root().render(options)
        }

        /**
         * The file name a jar url should be saved under.
         *
         * Taken from the url's last segment so a development build and a release asset keep their
         * own versioned names, and sanitised because the name ends up as a path segment.
         */
        fun fileNameOf(uri: URI): String {
            val raw = uri.path?.substringAfterLast('/').orEmpty()

            if (!raw.endsWith(".jar", ignoreCase = true) || !PathSafety.isSafeSegment(raw)) {
                return DEFAULT_JAR_NAME
            }

            return raw
        }

        private val HEADER = """
            # Written by Pano when this server was installed.
            #
            # platform.token and platform.encryption-key link this server to its row in Pano.
            # Anyone holding both can act as this server, so keep the file readable only by the
            # account the server runs as.

        """.trimIndent() + "\n"
    }
}
